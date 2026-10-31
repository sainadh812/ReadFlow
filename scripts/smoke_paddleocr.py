#!/usr/bin/env python3
"""Run pinned Paddle ONNX models + the app's actual Kotlin DB/CTC postprocessing.

Development-only host smoke, NOT a phone performance/accuracy benchmark. Requires
numpy, Pillow, onnxruntime and a downloaded Gradle distribution (for Kotlin CLI).
The tiny Box/TextElement stubs isolate the production pure-Kotlin postprocessor;
Android Bitmap/Canvas/ORT lifecycle is covered separately by app builds/device runs.
"""
import glob
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile
import time

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / ".deps/paddleocr"
OUT = ROOT / "docs/evidence/paddleocr"
EXPECTED = [
    "Milanković’s theory and Köppen’s work.",
    "He is 20-24 years old. A net worth of $9.3 million.",
    "1999-Apple’s iMac with 6 gigs. 2003-120 gigs.",
    "Year", "Assets", "Debt",
    "2023", "$1,000", "$250",
    "2024", "$1,250", "$200",
    "2025", "$1,500", "$150",
]

STUB = '''package app.readflow.core
data class Box(val left:Float,val top:Float,val right:Float,val bottom:Float) {
 val width get()=right-left; val height get()=bottom-top
 fun intersection(b:Box)=(minOf(right,b.right)-maxOf(left,b.left)).coerceAtLeast(0f)*(minOf(bottom,b.bottom)-maxOf(top,b.top)).coerceAtLeast(0f)
}
data class TextElement(val text:String,val boxes:List<Box>,val block:Int=0,val line:Int=0)
'''
BRIDGE = '''package app.readflow.ingest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun main(args:Array<String>) {
 val bytes=File(args[1]).readBytes()
 val floats=FloatArray(bytes.size/4)
 ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
 if(args[0]=="detect") {
  val boxes=PaddleOcrPostprocess.detect(floats,args[2].toInt(),args[3].toInt(),args[4].toInt(),args[5].toInt())
  println(boxes.joinToString("\\n") { b -> b.points.joinToString(" ") { "${it.x} ${it.y}" } })
 } else {
  val dictionary=PaddleOcrPostprocess.dictionary(File(args[4]).readText())
  val decoded=PaddleOcrPostprocess.decode(floats,args[2].toInt(),args[3].toInt(),dictionary)
  println(decoded.confidence); print(decoded.text)
 }
}
'''


def verify():
    pack = next(p for p in json.loads((ROOT / "app/src/main/assets/ocr-model-manifests.json").read_text()) if p["id"] == "paddleocr")
    for asset in pack["files"] + pack["extras"]:
        data = (MODELS / asset["path"]).read_bytes()
        assert len(data) == asset["bytes"] and hashlib.sha256(data).hexdigest() == asset["sha256"], asset["path"]
    return pack["version"]


def fixture():
    image = Image.new("RGB", (1280, 720), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 34)
    for i, text in enumerate(EXPECTED[:3]):
        draw.text((45, 36 + i * 64), text, font=font, fill="black")
    for row in range(4):
        for column in range(3):
            draw.text((75 + column * 360, 300 + row * 85), EXPECTED[3 + row * 3 + column], font=font, fill="black")
    for y in (278, 363, 448, 533, 618):
        draw.line((50, y, 1160, y), fill="#777777", width=2)
    for x in (50, 410, 770, 1160):
        draw.line((x, 278, x, 618), fill="#777777", width=2)
    return image


def crop(image, points):
    p = np.asarray(points, dtype=float).reshape(4, 2)
    w = max(2, round(max(np.linalg.norm(p[1] - p[0]), np.linalg.norm(p[2] - p[3]))))
    h = max(2, round(max(np.linalg.norm(p[3] - p[0]), np.linalg.norm(p[2] - p[1]))))
    matrix, rhs = [], []
    for (x, y), (sx, sy) in zip(((0, 0), (w - 1, 0), (w - 1, h - 1), (0, h - 1)), p):
        matrix += [[x, y, 1, 0, 0, 0, -sx * x, -sx * y], [0, 0, 0, x, y, 1, -sy * x, -sy * y]]
        rhs += [sx, sy]
    transform = np.linalg.solve(matrix, rhs)
    result = image.transform((w, h), Image.Transform.PERSPECTIVE, transform, Image.Resampling.BILINEAR, fillcolor="white")
    return result.transpose(Image.Transpose.ROTATE_90) if h / w >= 1.5 else result


def main():
    version = verify()
    libs = sorted(glob.glob(str(Path.home() / ".gradle/wrapper/dists/gradle-*-bin/*/gradle-*/lib")))
    if not libs:
        raise SystemExit("Download the project's Gradle distribution first")
    lib = Path(libs[-1])
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="paddle-smoke-") as temporary:
        temporary = Path(temporary)
        (temporary / "Core.kt").write_text(STUB)
        (temporary / "Bridge.kt").write_text(BRIDGE)
        classes = temporary / "classes"
        stdlib = next(lib.glob("kotlin-stdlib-*.jar"))
        subprocess.run(["java", "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-no-stdlib",
                        "-classpath", str(stdlib), "-d", str(classes), str(temporary / "Core.kt"), str(temporary / "Bridge.kt"),
                        str(ROOT / "app/src/main/java/app/readflow/ingest/PaddleOcrPostprocess.kt")], check=True)
        cli = ["java", "-cp", os.pathsep.join((str(classes), str(stdlib))), "app.readflow.ingest.BridgeKt"]
        options = ort.SessionOptions()
        options.intra_op_num_threads = 3
        options.inter_op_num_threads = 1
        options.add_session_config_entry("session.intra_op.allow_spinning", "0")
        options.add_session_config_entry("session.inter_op.allow_spinning", "0")
        options.log_severity_level = 3
        start = time.perf_counter()
        detector = ort.InferenceSession(str(MODELS / "det.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
        recognizer = ort.InferenceSession(str(MODELS / "rec.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
        load = time.perf_counter() - start
        image = fixture()
        image.save(OUT / "finance-fixture.png")
        w = 960
        h = max(32, int(math.floor(image.height * w / image.width / 32 + .5)) * 32)
        pixels = np.asarray(image.resize((w, h), Image.Resampling.BILINEAR), dtype=np.float32)[:, :, ::-1] / 255
        pixels = (pixels - np.array([.485, .456, .406], dtype=np.float32)) / np.array([.229, .224, .225], dtype=np.float32)
        start = time.perf_counter()
        detection = detector.run(None, {detector.get_inputs()[0].name: pixels.transpose(2, 0, 1)[None]})[0]
        detector_seconds = time.perf_counter() - start
        output = temporary / "output.f32"
        detection.astype("<f4").tofile(output)
        quads_text = subprocess.check_output(cli + ["detect", str(output), str(detection.shape[3]), str(detection.shape[2]), str(image.width), str(image.height)], text=True)
        quads = [list(map(float, line.split())) for line in quads_text.strip().splitlines() if line.strip()]
        rows = []
        for points in sorted(quads, key=lambda p: min(p[1::2])):
            if rows and abs((min(points[1::2]) + max(points[1::2])) / 2 - rows[-1][0]) < 20:
                rows[-1][1].append(points)
            else:
                rows.append(((min(points[1::2]) + max(points[1::2])) / 2, [points]))
        quads = [p for _, row in rows for p in sorted(row, key=lambda q: min(q[0::2]))]
        results, recognition_seconds = [], 0
        for points in quads:
            region = crop(image, points)
            scaled = max(16, math.ceil(48 * region.width / region.height))
            assert scaled <= 3200
            data = np.zeros((1, 3, 48, max(320, scaled)), dtype=np.float32)
            data[0, :, :, :scaled] = (np.asarray(region.resize((scaled, 48), Image.Resampling.BILINEAR), dtype=np.float32) / 127.5 - 1).transpose(2, 0, 1)
            start = time.perf_counter()
            recognized = recognizer.run(None, {recognizer.get_inputs()[0].name: data})[0]
            recognition_seconds += time.perf_counter() - start
            recognized.astype("<f4").tofile(output)
            decoded = subprocess.check_output(cli + ["decode", str(output), str(recognized.shape[1]), str(recognized.shape[2]), str(MODELS / "rec.yml")], text=True)
            confidence, text = decoded.split("\n", 1)
            results.append({"text": text, "confidence": float(confidence), "quad": points})
        actual = " ".join(r["text"] for r in results)
        expected = " ".join(EXPECTED)
        distance = list(range(len(actual) + 1))
        for i, left in enumerate(expected, 1):
            following = [i]
            for j, right in enumerate(actual, 1):
                following.append(min(following[-1] + 1, distance[j] + 1, distance[j - 1] + (left != right)))
            distance = following
        checks = {"full_exact_fixture": actual == expected, "accented_names": "Milanković" in actual and "Köppen" in actual,
                  "currency_decimal": "$9.3" in actual, "age_range": "20-24" in actual,
                  "all_table_cells_in_order": " ".join(EXPECTED[3:]) in actual}
        report = {"model_version": version, "host_runtime": ort.__version__, "provider": "CPUExecutionProvider", "threads": 3,
                  "scope": "Synthetic host smoke of real ONNX models and production Kotlin DB/CTC; not Android/S25 speed or book accuracy.",
                  "host_model_load_seconds": load, "host_detector_inference_seconds": detector_seconds,
                  "host_recognizer_inference_seconds": recognition_seconds, "timing_excludes": "Kotlin process startup, preprocessing, DB/CTC and Android runtime",
                  "fixture_character_edits": distance[-1], "fixture_reference_characters": len(expected),
                  "fixture_character_error_rate": distance[-1] / len(expected),
                  "checks": checks, "reference": expected, "recognized": actual, "regions": results}
        (OUT / "host-smoke.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
        print(json.dumps({"checks": checks, "recognized": actual, "regions": len(results), "inference_seconds": detector_seconds + recognition_seconds}, indent=2, ensure_ascii=False))
        assert len(results) >= 10, "Detector did not find the fixture regions"
        assert checks["accented_names"] and checks["currency_decimal"] and checks["age_range"] and checks["all_table_cells_in_order"], "Name/numeric fixture recognition failed"


if __name__ == "__main__":
    main()
