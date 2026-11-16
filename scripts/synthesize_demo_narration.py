#!/usr/bin/env python3
"""Synthesize timed demo narration with the repository's existing local models.

Run with .venv/bin/python. No network, downloads, or model rebuilds are performed.
The native frontend runs in a subprocess before Python loads ONNX Runtime. Audio
is generated at the requested fixed speed, with only edge silence trimmed; it is
never time-stretched, internally pause-shortened, or truncated to fit a window.
"""
from __future__ import annotations

import argparse
import gc
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DEPS = ROOT / ".deps"
SAMPLE_RATE = 24000
EDGE_PADDING_SECONDS = 0.08
EDGE_SILENCE_THRESHOLD = 0.0001


def digest(path: Path) -> str:
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def checked_models(include_alignment: bool) -> dict[str, str]:
    packs = {pack["id"]: pack for pack in json.loads(
        (ROOT / "app/src/main/assets/model-manifests.json").read_text())}
    required = [("kokoro", DEPS / "kokoro-en-v0_19", {"model.onnx", "tokens.txt", "voices.bin"})]
    if include_alignment:
        required.append(("alignment", DEPS / "alignment", {"model.onnx", "vocab.json"}))
    hashes = {}
    for identifier, directory, names in required:
        files = [*packs[identifier]["files"], *packs[identifier].get("extras", [])]
        entries = {item["path"]: item for item in files}
        if not names.issubset(entries):
            raise RuntimeError(f"Manifest lacks required {identifier} files: {names - entries.keys()}")
        for name in sorted(names):
            path = directory / name
            actual = digest(path)
            if actual != entries[name]["sha256"]:
                raise RuntimeError(f"Model checksum mismatch: {path}")
            hashes[f"{identifier}/{name}"] = actual
    return hashes


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--script", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--skip-transcription", action="store_true")
    args = parser.parse_args()
    script_bytes = args.script.read_bytes()
    script_sha256 = hashlib.sha256(script_bytes).hexdigest()
    generator_sha256 = digest(Path(__file__))
    data = json.loads(script_bytes)
    voice = data.get("voiceIndex", 0)
    speed = data.get("speed", 1.0)
    if type(voice) is not int or not 0 <= voice < 11:
        raise ValueError("voiceIndex must be an integer from 0 to 10")
    if not isinstance(speed, (int, float)) or isinstance(speed, bool) or not math.isfinite(speed) or not 0.5 <= speed <= 2:
        raise ValueError("speed must be finite and between 0.5 and 2")
    segments = data["segments"]
    if not isinstance(segments, list) or not segments:
        raise ValueError("segments must be a nonempty list")
    previous_end, identifiers = 0.0, set()
    for row in segments:
        identifier, start, end, text = row["id"], row["start"], row["end"], row["text"]
        if not isinstance(identifier, str) or not re.fullmatch(r"[a-zA-Z0-9_-]+", identifier) or identifier in identifiers:
            raise ValueError("Segment ids must be unique safe filename components")
        if not all(isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) for value in (start, end)):
            raise ValueError(f"Invalid times: {identifier}")
        if start < previous_end or end <= start:
            raise ValueError(f"Segments must have positive, ascending, nonoverlapping windows: {identifier}")
        if not isinstance(text, str) or not text.strip():
            raise ValueError(f"Missing text: {identifier}")
        previous_end = end
        identifiers.add(identifier)

    # Importing this helper does not load either ONNX runtime.
    from misaki_speech_smoke import DEFAULT_ORT, ORT_LIBRARY_SHA256, PHONE_MAP
    if digest(DEFAULT_ORT) != ORT_LIBRARY_SHA256:
        raise RuntimeError("Host frontend ONNX Runtime checksum mismatch")
    model_hashes = checked_models(not args.skip_transcription)
    frontend_code = (
        "import json,sys; from pathlib import Path; "
        "sys.path.insert(0, str(Path(sys.argv[1]) / 'scripts')); "
        "import misaki_speech_smoke as smoke; "
        "smoke.TEXTS=json.load(sys.stdin); "
        "print(json.dumps(smoke.frontend(smoke.DEFAULT_ORT), ensure_ascii=False))"
    )
    prepared = subprocess.run(
        [sys.executable, "-c", frontend_code, str(ROOT)],
        input=json.dumps([row["text"] for row in segments]), text=True,
        capture_output=True, check=True,
    )
    front = json.loads(prepared.stdout)
    if len(front) != len(segments):
        raise RuntimeError("Frontend returned a different segment count")

    # Native frontend has exited before the Python acoustic runtime is loaded.
    import numpy as np
    import onnxruntime as ort
    import soundfile as sf

    kokoro = DEPS / "kokoro-en-v0_19"
    phone_ids = {line.rsplit(" ", 1)[0]: int(line.rsplit(" ", 1)[1])
                 for line in (kokoro / "tokens.txt").read_text().splitlines()}
    inputs = []
    for row, result in zip(segments, front, strict=True):
        phones = "".join(PHONE_MAP.get(phone, phone) for phone in result["phonemes"])
        unknown = sorted(set(phones) - phone_ids.keys())
        if unknown:
            raise RuntimeError(f"Unknown phonemes in {row['id']}: {unknown}")
        ids = [phone_ids[phone] for phone in phones]
        if not 1 <= len(ids) <= 510:
            raise RuntimeError(f"Invalid phoneme length in {row['id']}: {len(ids)}")
        inputs.append((phones, ids))
    styles = np.fromfile(kokoro / "voices.bin", dtype="<f4").reshape(11, 511, 256)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.add_session_config_entry("session.intra_op.allow_spinning", "0")
    options.add_session_config_entry("session.inter_op.allow_spinning", "0")
    session = ort.InferenceSession(str(kokoro / "model.onnx"), options, providers=["CPUExecutionProvider"])
    args.output_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for row, result, (phones, ids) in zip(segments, front, inputs, strict=True):
        samples = np.asarray(session.run(None, {
            "tokens": np.array([[0, *ids, 0]], dtype=np.int64),
            "style": styles[voice, len(ids)][None, :],
            "speed": np.array([speed], dtype=np.float32),
        })[0], dtype=np.float32).reshape(-1)
        if not samples.size or not np.isfinite(samples).all():
            raise RuntimeError(f"Empty or nonfinite audio for {row['id']}")
        active = np.flatnonzero(np.abs(samples) > EDGE_SILENCE_THRESHOLD)
        if not active.size:
            raise RuntimeError(f"Silent audio for {row['id']}")
        padding = round(EDGE_PADDING_SECONDS * SAMPLE_RATE)
        start = max(0, int(active[0]) - padding)
        end = min(samples.size, int(active[-1]) + 1 + padding)
        audio = samples[start:end]
        peak = float(np.max(np.abs(audio)))
        if peak >= 1:
            raise RuntimeError(f"Audio would clip PCM16 for {row['id']}: peak={peak}")
        path = args.output_dir / f"{row['id']}.wav"
        sf.write(path, audio, SAMPLE_RATE, subtype="PCM_16")
        seconds = len(audio) / SAMPLE_RATE
        window = row["end"] - row["start"]
        entry = {
            **row, "file": str(path), "wavSha256": digest(path), "sampleRate": SAMPLE_RATE,
            "rawSamples": int(samples.size), "rawDurationSeconds": samples.size / SAMPLE_RATE,
            "trimmedLeadingSamples": start, "trimmedTrailingSamples": int(samples.size) - end,
            "samples": int(audio.size), "durationSeconds": seconds,
            "windowSeconds": window, "remainingSeconds": window - seconds, "fitsWindow": seconds <= window,
            "peak": peak, "peakDbfs": 20 * math.log10(peak), "finite": True,
            "frontendPhones": result["phonemes"], "adaptedPhones": phones,
            "phonemeCount": len(ids), "fallbackCalls": result.get("fallbackCalls"),
        }
        rows.append(entry)
        print(f"{row['id']}: {seconds:.3f}s / {window:.3f}s; fits={entry['fitsWindow']}", flush=True)
    del session
    gc.collect()

    if not args.skip_transcription:
        from difflib import SequenceMatcher
        from scipy.signal import resample_poly
        vocabulary = json.loads((DEPS / "alignment/vocab.json").read_text())
        vocab = {int(index): token for token, index in vocabulary.items()}
        alignment = ort.InferenceSession(str(DEPS / "alignment/model.onnx"), options, providers=["CPUExecutionProvider"])
        for row in rows:
            samples, rate = sf.read(row["file"], dtype="float32")
            if rate != SAMPLE_RATE:
                raise RuntimeError("Unexpected stored sample rate")
            signal = resample_poly(samples, 2, 3).astype("float32")
            signal = (signal - signal.mean()) / np.sqrt(signal.var() + 1e-7)
            prediction = alignment.run(None, {"input_values": signal[None, :]})[0][0].argmax(-1)
            previous, characters = -1, []
            for token in prediction:
                if token != previous and token != 0:
                    characters.append(vocab[int(token)])
                previous = token
            transcript = "".join(characters).replace("|", " ").strip()
            expected_words = re.findall(r"[A-Z]+(?:'[A-Z]+)?", row["text"].upper())
            transcript_words = re.findall(r"[A-Z]+(?:'[A-Z]+)?", transcript.upper())
            matcher = SequenceMatcher(None, expected_words, transcript_words, autojunk=False)
            row["greedyCtcTranscript"] = transcript
            row["ctcWordSimilarity"] = matcher.ratio()
            row["ctcWordDifferences"] = [
                {"kind": kind, "expected": expected_words[a:b], "recognized": transcript_words[c:d]}
                for kind, a, b, c, d in matcher.get_opcodes() if kind != "equal"
            ]
            print(f"{row['id']} CTC: {transcript}", flush=True)

    provenance = json.loads((ROOT / "app/src/main/assets/licenses/misaki/readflow-provenance.json").read_text())
    report = {
        "schemaVersion": 1, "status": "ALL_SEGMENTS_FIT" if all(row["fitsWindow"] for row in rows) else "SCRIPT_REVISION_NEEDED",
        "script": str(args.script), "scriptSha256": script_sha256,
        "generator": "scripts/synthesize_demo_narration.py", "generatorSha256": generator_sha256,
        "generatedNarration": True, "model": "Kokoro v0.19", "voiceIndex": voice,
        "voice": "Default (af)" if voice == 0 else str(voice), "speed": speed,
        "sampleRate": SAMPLE_RATE, "modelSha256": model_hashes,
        "frontendRevision": provenance["goG2pRevision"],
        "frontendLibrarySha256": digest(DEPS / "libreadflow_misaki_host.so"),
        "frontendOrtLibrarySha256": digest(DEFAULT_ORT), "pythonOnnxRuntime": ort.__version__,
        "edgeTrim": {"absoluteSilenceThreshold": EDGE_SILENCE_THRESHOLD, "retainedPaddingSeconds": EDGE_PADDING_SECONDS},
        "processing": {"timeStretch": False, "internalPauseShortening": False, "speechTruncation": False, "amplitudeNormalization": False},
        "limitations": ["Generated explanatory voiceover, not live app speech.",
                        "Greedy CTC transcripts may contain recognition mistakes; they are not a human listening or pronunciation review.",
                        "Window fit measures duration only; the narration still needs review against the video."],
        "segments": rows,
    }
    report_path = args.output_dir / "synthesis-report.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(f"Report: {report_path}")


if __name__ == "__main__":
    main()
