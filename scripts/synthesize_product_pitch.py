#!/usr/bin/env python3
"""Synthesize connected product narration using the pinned Kokoro v1.0 export.

Run with .venv/bin/python. This is media tooling; it does not change the app's
voice model. Requires the existing native Misaki frontend and FFmpeg environment.
Use --download-models to fetch the three hash-pinned public inference files.
The narration is generated for review before publication.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from difflib import SequenceMatcher
import gc
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RATE = 48000
SOURCE_RATE = 24000
EDGE_SECONDS = 0.15
MAX_PHONES = 400
NATIVE_SPEED = 0.85
MODEL_RELEASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/"
HEXGRAD_REVISION = "f3ff3571791e39611d31c381e3a41a3af07b4987"
ASSETS = {
    "kokoro-v1.0.onnx": (
        MODEL_RELEASE + "kokoro-v1.0.onnx",
        "7d5df8ecf7d4b1878015a32686053fd0eebe2bc377234608764cc0ef3636a6c5",
    ),
    "voices-v1.0.bin": (
        MODEL_RELEASE + "voices-v1.0.bin",
        "bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d",
    ),
    "model-config.json": (
        f"https://huggingface.co/hexgrad/Kokoro-82M/resolve/{HEXGRAD_REVISION}/config.json",
        "5abb01e2403b072bf03d04fde160443e209d7a0dad49a423be15196b9b43c17f",
    ),
}


def sha256(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def artifact(path: Path) -> dict:
    return {"file": str(path.resolve().relative_to(ROOT)),
            "bytes": path.stat().st_size, "sha256": sha256(path)}


def checked_assets(folder: Path, download: bool) -> list[dict]:
    folder.mkdir(parents=True, exist_ok=True)
    result = []
    for name, (url, expected) in ASSETS.items():
        path = folder / name
        if not path.exists():
            if not download:
                raise FileNotFoundError(f"Missing {path}; use --download-models")
            temporary = path.with_suffix(path.suffix + ".part")
            urllib.request.urlretrieve(url, temporary)
            if sha256(temporary) != expected:
                temporary.unlink()
                raise RuntimeError(f"Downloaded asset checksum mismatch: {name}")
            temporary.replace(path)
        if sha256(path) != expected:
            raise RuntimeError(f"Asset checksum mismatch: {path}")
        result.append({**artifact(path), "source": url,
                       "digestOrigin": "Locally recorded from primary-source download; release API supplied no digest"})
    return result


def phonemize(texts: list[str]) -> list[dict]:
    # Keep the native frontend's ORT version out of the acoustic Python process.
    from misaki_speech_smoke import DEFAULT_ORT, ORT_LIBRARY_SHA256
    if sha256(DEFAULT_ORT) != ORT_LIBRARY_SHA256:
        raise RuntimeError("Native frontend ONNX Runtime checksum mismatch")
    code = (
        "import json,sys;from pathlib import Path;"
        "sys.path.insert(0,str(Path(sys.argv[1])/'scripts'));"
        "import misaki_speech_smoke as smoke;smoke.TEXTS=json.load(sys.stdin);"
        "print(json.dumps(smoke.frontend(smoke.DEFAULT_ORT),ensure_ascii=False))"
    )
    result = subprocess.run([sys.executable, "-c", code, str(ROOT)],
                            input=json.dumps(texts), text=True,
                            capture_output=True, check=True)
    return json.loads(result.stdout)


def chunks(phones: str) -> list[str]:
    """Keep a paragraph intact unless it exceeds the documented rush threshold."""
    result = []
    remaining = phones
    while len(remaining) > MAX_PHONES:
        stops = [m.end() for m in re.finditer(r"[.!?;]", remaining[:MAX_PHONES])]
        cut = stops[-1] if stops else remaining.rfind(" ", 0, MAX_PHONES)
        if cut <= 0:
            raise ValueError("Cannot split long phonemes at a word boundary")
        result.append(remaining[:cut].strip())
        remaining = remaining[cut:].strip()
    if remaining:
        result.append(remaining)
    return result


def measured_loudness(ff, path: Path) -> dict:
    result = ff.call(["-hide_banner", "-i", path, "-map", "0:a:0", "-vn", "-af",
                      "loudnorm=I=-18:TP=-1.5:LRA=11:print_format=json", "-f", "null", "-"])
    blocks = re.findall(r'\{\s*"input_i".*?\}', result.stderr, flags=re.S)
    if not blocks:
        raise RuntimeError("Missing loudness analysis")
    values = json.loads(blocks[-1])
    if not all(math.isfinite(float(values[k])) for k in
               ["input_i", "input_tp", "input_lra", "input_thresh", "target_offset"]):
        raise RuntimeError("Nonfinite loudness analysis")
    return values


def ctc_anchors(prediction, inverse: dict, row: dict) -> tuple[str, list, list]:
    # Wav2Vec2 emits a frame every 320 samples at 16 kHz, approximately 20 ms.
    # CTC token peaks approximate linguistic boundaries, not exact word onsets.
    letters, words, current, last = [], [], [], -1
    for frame, token in enumerate(prediction):
        token = int(token)
        if token != last and token != 0:
            char = inverse[token]
            if char == "|":
                if current:
                    words.append({"word": "".join(c["character"] for c in current),
                                  "start": current[0]["start"], "end": current[-1]["end"]})
                    current = []
            else:
                item = {"character": char, "start": frame * 0.02, "end": (frame + 1) * 0.02}
                letters.append(item)
                current.append(item)
        elif token == last and token != 0 and inverse[token] != "|" and current:
            current[-1]["end"] = (frame + 1) * 0.02
        last = token
    if current:
        words.append({"word": "".join(c["character"] for c in current),
                      "start": current[0]["start"], "end": current[-1]["end"]})
    for word in words:
        word.update(globalStart=row["start"] + word["start"], globalEnd=row["start"] + word["end"])
    expected = re.sub(r"[^A-Z]", "", row["text"].upper())
    actual_letters = [c for c in letters if re.fullmatch(r"[A-Z]", c["character"])]
    actual = "".join(c["character"] for c in actual_letters)
    mapping = {}
    for a, b, size in SequenceMatcher(None, expected, actual, autojunk=False).get_matching_blocks():
        for offset in range(size):
            mapping[a + offset] = b + offset
    sentence_anchors, cursor = [], 0
    for sentence in re.findall(r"[^.!?]+[.!?]?", row["text"]):
        length = len(re.sub(r"[^A-Z]", "", sentence.upper()))
        matches = [mapping[i] for i in range(cursor, cursor + length) if i in mapping]
        if matches:
            start, end = actual_letters[min(matches)]["start"], actual_letters[max(matches)]["end"]
            sentence_anchors.append({"text": sentence.strip(), "start": start, "end": end,
                                     "globalStart": row["start"] + start,
                                     "globalEnd": row["start"] + end,
                                     "matchedCharacterFraction": len(matches) / length})
        cursor += length
    return " ".join(w["word"] for w in words), words, sentence_anchors


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--script", type=Path,
                        default=ROOT / "store-assets/release-0.3.2/product-pitch/script.json")
    parser.add_argument("--model-dir", type=Path, default=ROOT / "build/product-pitch/kokoro-v1")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build/product-pitch/narration")
    parser.add_argument("--download-models", action="store_true")
    args = parser.parse_args()
    source = json.loads(args.script.read_text())
    paragraphs = source["paragraphs"]
    if not paragraphs or any(not re.fullmatch(r"[a-z0-9_-]+", p["id"]) or
                             not p["text"].strip() for p in paragraphs):
        raise ValueError("Paragraphs need safe unique IDs and nonempty text")
    if len({p["id"] for p in paragraphs}) != len(paragraphs):
        raise ValueError("Duplicate paragraph IDs")
    assets = checked_assets(args.model_dir, args.download_models)
    frontend = phonemize([p["text"] for p in paragraphs])
    if len(frontend) != len(paragraphs):
        raise RuntimeError("Frontend returned a different paragraph count")

    import numpy as np
    import onnxruntime as ort
    import soundfile as sf
    from scipy.signal import resample_poly
    from build_narrated_demo import FFmpeg

    args.output_dir.mkdir(parents=True, exist_ok=True)
    raw_dir = args.output_dir / "raw"
    raw_dir.mkdir(exist_ok=True)
    vocab = json.loads((args.model_dir / "model-config.json").read_text())["vocab"]
    with np.load(args.model_dir / "voices-v1.0.bin", allow_pickle=False) as voices:
        voice = voices["af_heart"].copy()
    if voice.shape != (510, 1, 256) or not np.isfinite(voice).all():
        raise RuntimeError("Invalid af_heart style array")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.add_session_config_entry("session.intra_op.allow_spinning", "0")
    options.add_session_config_entry("session.inter_op.allow_spinning", "0")
    session = ort.InferenceSession(str(args.model_dir / "kokoro-v1.0.onnx"), options,
                                   providers=["CPUExecutionProvider"])
    rows, arrays = [], []
    for paragraph, front in zip(paragraphs, frontend, strict=True):
        phones = front["phonemes"]
        unknown = sorted(set(phones) - vocab.keys())
        if not front["complete"] or unknown:
            raise RuntimeError(f"Incomplete phonemes in {paragraph['id']}: {unknown}")
        parts = chunks(phones)
        generated = []
        begin = time.perf_counter()
        for part in parts:
            ids = [vocab[p] for p in part]
            samples = np.asarray(session.run(None, {
                "tokens": np.array([[0, *ids, 0]], dtype=np.int64),
                "style": voice[len(ids) - 1],
                "speed": np.array([NATIVE_SPEED], dtype=np.float32),
            })[0], dtype=np.float32).reshape(-1)
            if not samples.size or not np.isfinite(samples).all():
                raise RuntimeError("Empty or nonfinite synthesized speech")
            generated.append(samples)
        samples = np.concatenate(generated)
        elapsed = time.perf_counter() - begin
        raw_path = raw_dir / f"{paragraph['id']}.wav"
        sf.write(raw_path, samples, SOURCE_RATE, subtype="FLOAT")
        # Trim only the outside silence; all speech and interior pauses survive.
        active = np.flatnonzero(np.abs(samples) > 0.001)
        if not active.size:
            raise RuntimeError("Silent synthesized paragraph")
        first, last = int(active[0]), int(active[-1]) + 1
        pad = np.zeros(round(EDGE_SECONDS * SOURCE_RATE), dtype=np.float32)
        edged = np.concatenate([pad, samples[first:last], pad])
        values = resample_poly(edged, 2, 1).astype(np.float64)
        arrays.append(values)
        row = {"id": paragraph["id"], "text": paragraph["text"],
               "durationSeconds": len(values) / RATE, "sampleCount": len(values),
               "sourceDurationSeconds": len(samples) / SOURCE_RATE,
               "trimmedLeadingSamples": first, "trimmedTrailingSamples": len(samples) - last,
               "edgePaddingSeconds": EDGE_SECONDS, "synthesisSeconds": elapsed,
               "phonemes": phones, "phonemeCount": len(phones), "chunkCount": len(parts),
               "fallbackCalls": front.get("fallbackCalls"), "rawAudio": artifact(raw_path)}
        rows.append(row)
        print(f"{row['id']}: {row['durationSeconds']:.6f}s; {len(phones)} phones", flush=True)
    del session
    gc.collect()

    ff = FFmpeg()
    unnormalized = np.concatenate(arrays)
    measure_path = args.output_dir / "unnormalized.wav"
    sf.write(measure_path, unnormalized, RATE, subtype="FLOAT")
    before = measured_loudness(ff, measure_path)
    final_path = args.output_dir / "narration.wav"
    normalizer = (
        "loudnorm=I=-18:TP=-1.5:LRA=11:linear=false:"
        f"measured_I={before['input_i']}:measured_TP={before['input_tp']}:"
        f"measured_LRA={before['input_lra']}:measured_thresh={before['input_thresh']}:"
        f"offset={before['target_offset']}:print_format=json"
    )
    normalized_result = ff.call(["-y", "-hide_banner", "-i", measure_path, "-map", "0:a:0",
                                "-af", normalizer, "-ar", str(RATE), "-c:a", "pcm_s24le", final_path])
    pass_two = json.loads(re.findall(r'\{\s*"input_i".*?\}', normalized_result.stderr, flags=re.S)[-1])
    normalized, normalized_rate = sf.read(final_path)
    if normalized_rate != RATE or len(normalized) != len(unnormalized):
        raise RuntimeError("Loudness processing changed the narration's sample count")
    current = 0
    for row, values in zip(rows, arrays, strict=True):
        values = normalized[current:current + len(values)]
        path = args.output_dir / f"{row['id']}.wav"
        sf.write(path, values, RATE, subtype="PCM_24")
        stored, rate = sf.read(path)
        row.update(audio=artifact(path), sampleRate=rate,
                   start=current / RATE, end=(current + len(stored)) / RATE,
                   peakDbfs=20 * math.log10(float(np.max(np.abs(stored)))),
                   clippedSamples=int(np.count_nonzero(np.abs(stored) >= 1)),
                   finite=bool(np.isfinite(stored).all()))
        current += len(stored)
    after = measured_loudness(ff, final_path)
    if float(after["input_tp"]) > -1.45 or any(r["clippedSamples"] for r in rows):
        raise RuntimeError("Normalized audio exceeds true peak ceiling")

    alignment = ort.InferenceSession(str(ROOT / ".deps/alignment/model.onnx"), options,
                                    providers=["CPUExecutionProvider"])
    av = json.loads((ROOT / ".deps/alignment/vocab.json").read_text())
    inverse = {index: token for token, index in av.items()}
    for row in rows:
        values, _ = sf.read(ROOT / row["audio"]["file"], dtype="float32")
        x = resample_poly(values, 1, 3).astype(np.float32)
        x = (x - x.mean()) / np.sqrt(x.var() + 1e-7)
        logits = alignment.run(None, {"input_values": x[None, :]})[0][0]
        transcript, word_times, sentence_times = ctc_anchors(logits.argmax(axis=1), inverse, row)
        row["ctcWordAnchors"] = word_times
        row["sentenceAnchors"] = sentence_times
        expected = re.findall(r"[A-Z]+(?:'[A-Z]+)?", row["text"].upper())
        recognized = re.findall(r"[A-Z]+(?:'[A-Z]+)?", transcript)
        matcher = SequenceMatcher(None, expected, recognized, autojunk=False)
        row["ctcTranscript"] = transcript
        row["ctcWordSimilarity"] = matcher.ratio()
        row["ctcWordDifferences"] = [
            {"kind": kind, "expected": expected[a:b], "recognized": recognized[c:d]}
            for kind, a, b, c, d in matcher.get_opcodes() if kind != "equal"]
        print(f"{row['id']} CTC: {transcript}", flush=True)
    report = {"schemaVersion": 1, "createdAt": datetime.now(timezone.utc).isoformat(),
              "status": "GENERATED_FOR_REVIEW", "script": artifact(args.script),
              "generator": artifact(Path(__file__)), "model": "Kokoro v1.0 full FP32 ONNX",
              "voice": "af_heart", "nativeSynthesisSpeed": NATIVE_SPEED, "threads": 2,
              "modelAssets": assets, "sampleRate": RATE, "narration": artifact(final_path),
              "totalDurationSeconds": current / RATE, "paragraphs": rows,
              "normalization": {"method": "Two-pass FFmpeg loudnorm, dynamic gain and true-peak limiting",
                                "targetLufs": -18, "maxTruePeakDbtp": -1.5,
                                "before": before, "passTwo": pass_two, "after": after,
                                "sampleCountPreserved": True},
              "timeStretch": False, "dynamicGainAndLimiting": True,
              "internalPauseShortening": False, "subjectiveListeningPerformed": False,
              "limitations": ["AI-generated editorial narration, subject to review before publication.",
                              "Not live app speech; app source audio is unchanged.",
                              "CTC word and sentence anchors are approximate token-peak timings, not manually aligned boundaries.",
                              "CTC may make recognition errors; it is not a human listening or pronunciation review."]}
    (args.output_dir / "synthesis-report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"duration": current / RATE,
                      "loudness": after, "report": str(args.output_dir / "synthesis-report.json")}))


if __name__ == "__main__":
    main()
