#!/usr/bin/env python3
"""Host functional smoke for the real Misaki frontend and Kokoro v0.19 weights.

Reproduce from the repository root, without global Python installs:
  python3 -m venv .venv
  .venv/bin/pip install -r scripts/prototype-requirements.txt
  python3 scripts/fetch_models.py
  python3 scripts/prepare_misaki.py --host
  .venv/bin/python scripts/misaki_speech_smoke.py

Requires the model downloads above, Go/C toolchains from prepare_misaki.py, and
Linux x86_64 (or --ort pointing to a host ONNX Runtime >=1.22 shared library).
The default host ORT archive is downloaded with a pinned checksum. WAVs stay in
ignored .deps; only the bounded JSON report is committed. This is not an Android
performance benchmark, pronunciation score, or implementation of app playback.
"""
from __future__ import annotations

import argparse
import ctypes
import gc
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import sys
import tarfile

from fetch_models import fetch

ROOT = Path(__file__).resolve().parents[1]
DEPS = ROOT / ".deps"
DEFAULT_ORT = DEPS / "onnxruntime-linux-x64-1.22.0/lib/libonnxruntime.so"
ORT_SHA256 = "8344d55f93d5bc5021ce342db50f62079daf39aaafb5d311a451846228be49b3"
ORT_LIBRARY_SHA256 = "3da6146e14e7b8aaec625dde11d6114c7457c87a5f93d744897da8781e35c673"
TEXTS = [
    "Hello world.",
    "People aged twenty to twenty four can compare income and expenses.",
    "Milankovitch's example includes an emergency fund.",
    "The wind will wind the clock.",
    "By the time he was thirty he had a net worth of one million dollars.",
]
PHONE_MAP = dict(A="eɪ", I="aɪ", O="oʊ", Q="əʊ", W="aʊ", Y="ɔɪ", T="ɾ",
                 **{"ʤ": "dʒ", "ʧ": "tʃ", "ᵊ": "ə"})


def digest(path: Path) -> str:
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def frontend(ort: Path) -> list[dict]:
    # Run in a subprocess, so Go's ORT 1.22 and the prototype Python ORT version
    # cannot accidentally resolve each other's native symbols.
    lib = ctypes.CDLL(str(DEPS / "libreadflow_misaki_host.so"))
    lib.ReadFlowMisakiCreate.argtypes = [ctypes.c_char_p, ctypes.c_int]
    lib.ReadFlowMisakiPhonemize.argtypes = [ctypes.c_uint64, ctypes.c_char_p]
    lib.ReadFlowMisakiClose.argtypes = [ctypes.c_uint64]
    lib.ReadFlowMisakiFree.argtypes = [ctypes.c_void_p]
    lib.ReadFlowMisakiFree.restype = None
    for name in ("Create", "Phonemize", "Close"):
        getattr(lib, "ReadFlowMisaki" + name).restype = ctypes.c_void_p

    def call(name: str, *arguments) -> dict:
        pointer = getattr(lib, "ReadFlowMisaki" + name)(*arguments)
        if not pointer:
            raise RuntimeError(f"Null frontend response: {name}")
        try:
            response = json.loads(ctypes.string_at(pointer).decode("utf-8"))
        finally:
            lib.ReadFlowMisakiFree(pointer)
        if "error" in response:
            raise RuntimeError(response["error"])
        return response

    handle = call("Create", str(ort.resolve()).encode(), 1)["handle"]
    try:
        rows = [call("Phonemize", handle, text.encode()) for text in TEXTS]
        if not all(row["complete"] and row["phonemes"] for row in rows):
            raise RuntimeError("Incomplete phonemization")
        return rows
    finally:
        call("Close", handle)


def shorten_pauses(samples, np):
    # Matches the app's existing scale=.2, amplitude<=.01, duration>=200 ms
    # policy (Sherpa 1.13.8, Apache-2.0, Copyright 2023 Xiaomi Corporation).
    sections, start = [], None
    for index, sample in enumerate(samples):
        if abs(sample) <= .01:
            if start is None:
                start = index
        elif start is not None:
            if index - start >= 4800:
                sections.append((start, index))
            start = None
    if start is not None and len(samples) - start > 4800:
        sections.append((start, len(samples)))
    if not sections:
        return samples
    parts, previous = [], 0
    for first, end in sections:
        parts.append(samples[previous:first + int(np.float32(end - first) * np.float32(.2))])
        previous = end
    return np.concatenate([*parts, samples[previous:]])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ort", type=Path, default=DEFAULT_ORT)
    parser.add_argument("--audio-dir", type=Path, default=DEPS / "misaki-speech-smoke")
    parser.add_argument("--report", type=Path, default=ROOT / "docs/evidence/misaki/speech-smoke.json")
    parser.add_argument("--frontend-only", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.frontend_only:
        print(json.dumps(frontend(args.ort), ensure_ascii=False))
        return
    if args.ort == DEFAULT_ORT:
        if (platform.system(), platform.machine()) != ("Linux", "x86_64"):
            raise SystemExit("Supply --ort for your host's ONNX Runtime >=1.22 shared library.")
        if not args.ort.is_file():
            archive = DEPS / "onnxruntime-linux-x64-1.22.0.tgz"
            fetch("https://github.com/microsoft/onnxruntime/releases/download/v1.22.0/" + archive.name,
                  archive, ORT_SHA256)
            with tarfile.open(archive) as bundle:
                bundle.extractall(DEPS, filter="data")
        if digest(args.ort) != ORT_LIBRARY_SHA256:
            raise RuntimeError("Unexpected default host ORT library; reinstall the pinned host archive")
    if not args.ort.is_file():
        raise SystemExit(f"Missing host ONNX Runtime: {args.ort}")
    subprocess.run([sys.executable, str(ROOT / "scripts/prepare_misaki.py"), "--host"], check=True)
    prepared = subprocess.run([sys.executable, __file__, "--frontend-only", "--ort", str(args.ort)],
                              text=True, capture_output=True, check=True)
    front = json.loads(prepared.stdout)

    import numpy as np
    import onnxruntime as ort
    import soundfile as sf
    from scipy.signal import resample_poly

    manifests = json.loads((ROOT / "app/src/main/assets/model-manifests.json").read_text())
    packs = {pack["id"]: pack for pack in manifests}
    kokoro = DEPS / "kokoro-en-v0_19"
    hashes = {}
    for identifier, directory, needed in [
        ("kokoro", kokoro, {"model.onnx", "tokens.txt", "voices.bin"}),
        ("alignment", DEPS / "alignment", {"model.onnx"}),
    ]:
        for item in packs[identifier]["files"]:
            if item["path"] in needed:
                path = directory / item["path"]
                actual = digest(path)
                if actual != item["sha256"]:
                    raise RuntimeError(f"Model checksum mismatch: {path}")
                hashes[f"{identifier}/{item['path']}"] = actual
    vocabulary = next(item for item in packs["alignment"]["extras"] if item["path"] == "vocab.json")
    vocab_file = fetch(vocabulary["url"], DEPS / "alignment/vocab.json", vocabulary["sha256"])
    vocab = {int(index): token for token, index in json.loads(vocab_file.read_text()).items()}
    phone_ids = {line.rsplit(" ", 1)[0]: int(line.rsplit(" ", 1)[1])
                 for line in (kokoro / "tokens.txt").read_text().splitlines()}
    styles = np.fromfile(kokoro / "voices.bin", dtype="<f4").reshape(11, 511, 256)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.add_session_config_entry("session.intra_op.allow_spinning", "0")
    options.add_session_config_entry("session.inter_op.allow_spinning", "0")
    session = ort.InferenceSession(str(kokoro / "model.onnx"), options, providers=["CPUExecutionProvider"])
    args.audio_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for index, (text, result) in enumerate(zip(TEXTS, front, strict=True)):
        phones = "".join(PHONE_MAP.get(phone, phone) for phone in result["phonemes"])
        ids = [phone_ids[phone] for phone in phones]  # Unknown phones fail; never filter them out.
        if not 1 <= len(ids) <= 510:
            raise RuntimeError("Invalid model input length")
        samples = session.run(None, {"tokens": np.array([[0, *ids, 0]], dtype=np.int64),
                                    "style": styles[0, len(ids)][None, :],
                                    "speed": np.array([1.], dtype=np.float32)})[0]
        if not len(samples) or not np.isfinite(samples).all():
            raise RuntimeError("Empty or non-finite Misaki audio")
        samples = shorten_pauses(samples, np)
        sf.write(args.audio_dir / f"misaki-{index}.wav", samples, 24000, subtype="PCM_16")
        rows.append(dict(normalizedText=text, frontendPhones=result["phonemes"], adaptedPhones=phones,
                         phonemeCount=len(ids), fallbackCalls=result["fallbackCalls"],
                         misakiAudioSamples=len(samples), misakiAudioSeconds=len(samples) / 24000,
                         misakiFinite=True))
    del session
    gc.collect()

    import sherpa_onnx
    model = sherpa_onnx.OfflineTtsModelConfig(kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
        model=str(kokoro / "model.onnx"), voices=str(kokoro / "voices.bin"),
        tokens=str(kokoro / "tokens.txt"), data_dir=str(kokoro / "espeak-ng-data"), lang="en-us"), num_threads=2)
    engine = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=model, max_num_sentences=1))
    for index, text in enumerate(TEXTS):
        audio = engine.generate(text, sid=0, speed=1.)
        if audio.sample_rate != 24000 or not len(audio.samples) or not np.isfinite(audio.samples).all():
            raise RuntimeError("Empty, non-finite or wrong-rate reference audio")
        sf.write(args.audio_dir / f"espeak-{index}.wav", audio.samples, audio.sample_rate, subtype="PCM_16")
        rows[index].update(espeakAudioSamples=len(audio.samples), espeakAudioSeconds=len(audio.samples) / 24000,
                           espeakFinite=True)
    del engine
    gc.collect()
    alignment = ort.InferenceSession(str(DEPS / "alignment/model.onnx"), options, providers=["CPUExecutionProvider"])
    for index, row in enumerate(rows):
        for backend in ("misaki", "espeak"):
            path = args.audio_dir / f"{backend}-{index}.wav"
            samples, rate = sf.read(path, dtype="float32")
            if rate != 24000:
                raise RuntimeError("Unexpected stored audio sample rate")
            signal = resample_poly(samples, 2, 3).astype("float32")
            signal = (signal - signal.mean()) / np.sqrt(signal.var() + 1e-7)
            prediction = alignment.run(None, {"input_values": signal[None, :]})[0][0].argmax(-1)
            previous, characters = -1, []
            for token in prediction:
                if token != previous and token != 0:
                    characters.append(vocab[int(token)])
                previous = token
            row[backend + "GreedyCtcTranscript"] = "".join(characters).replace("|", " ").strip()
            row[backend + "Pcm16WaveSha256"] = digest(path)
    provenance = json.loads((ROOT / "app/src/main/assets/licenses/misaki/readflow-provenance.json").read_text())
    report = dict(schemaVersion=1, fixtureVersion="misaki-speech-smoke-1", status="PASSED",
                  scope="Host functional validation only. PASSED means complete inputs and finite PCM, not pronunciation correctness.",
                  limitations=["No Android device was used; these are not S25 performance results.",
                               "No human listening or pronunciation score; greedy CTC itself makes recognition errors.",
                               "Wall-clock speed is deliberately omitted; host builds competed for CPU.",
                               "The Python acoustic runtime differs from Android's ONNX Runtime 1.22.0.",
                               "Unusual names and context-dependent pronunciation still require listening.",
                               "WAVs are retained only in ignored .deps and are not committed."],
                  host=platform.platform(), pythonOnnxRuntime=ort.__version__,
                  frontendOrtLibrarySha256=digest(args.ort.resolve()), sherpa=sherpa_onnx.__version__,
                  frontendRevision=provenance["goG2pRevision"], model="kokoro v0.19", voice="0 (af)",
                  sampleRate=24000, speechThreads=2, alignmentThreads=2, frontendThreads=1,
                  modelSha256=hashes, rows=rows)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(f"Host functional smoke passed: {len(rows)} paired phrases; {args.report}")


if __name__ == "__main__":
    main()
