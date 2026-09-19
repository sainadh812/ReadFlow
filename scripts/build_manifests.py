"""Derive per-file manifests only from archives already pinned to upstream release hashes."""
import hashlib
import json
import urllib.request
from pathlib import Path
from fetch_models import ASSETS, ROOT as DEPS, fetch

ROOT = Path(__file__).resolve().parents[1]
packs = []
for ident, (archive, digest) in zip(["kokoro", "pocket"], ASSETS):
    fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" + archive, DEPS / archive, digest)
    folder = DEPS / archive.removesuffix(".tar.bz2")
    files = []
    for path in sorted(folder.rglob("*")):
        if not path.is_file() or "test_wavs" in path.parts:
            continue
        files.append(dict(path=str(path.relative_to(folder)), bytes=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    extras = []
    if ident == "pocket":
        extras = [dict(path="voices/alba.wav", bytes=958542, sha256="46264e83cb99115c3d210260e029117566d9c64f20266d10daa78107759ede3e",
            url="https://huggingface.co/kyutai/tts-voices/resolve/323332d33f997de8394f24a193e1a76df720e01a/alba-mackenna/casual.wav")]
    packs.append(dict(id=ident, name="Kokoro-82M" if ident == "kokoro" else "Pocket TTS", version="v0.19" if ident == "kokoro" else "int8-2026-01-26",
        runtime="sherpa-onnx-1.13.8/static-onnxruntime-1.28.2", archiveRoot=folder.name,
        url="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" + archive,
        sha256=digest, downloadBytes=(DEPS / archive).stat().st_size, installedBytes=sum(x["bytes"] for x in files + extras), files=files, extras=extras,
        licenses=["Apache-2.0", "GPL-3.0-or-later (eSpeak NG)"] if ident == "kokoro" else ["MIT (upstream code)", "CC-BY-4.0 (export archive)", "CC-BY-4.0 (Alba MacKenna voice)"],
        voices=[dict(id="0", name="Default (af)"), dict(id="1", name="Bella"), dict(id="2", name="Nicole")] if ident == "kokoro" else [dict(id="alba", name="Alba MacKenna")],
        alignment="wav2vec2-729c1a6-ctc-v1", support="Experimental: Android inference and audible boundaries require target-device verification"))
packs.append(dict(id="alignment", name="Word alignment", version="729c1a6730fb549c20a1c73a3d3f96f11020225e", runtime="onnxruntime-android-1.20.0", archiveRoot="",
    url="https://huggingface.co/onnx-community/wav2vec2-base-960h-ONNX/resolve/729c1a6730fb549c20a1c73a3d3f96f11020225e/onnx/model_quantized.onnx",
    sha256="1d9a366c27b2966625cd5035ac3db8847c53bba617169912bb251b42975a3a22", downloadBytes=95212816, installedBytes=95212816,
    files=[dict(path="model.onnx", bytes=95212816, sha256="1d9a366c27b2966625cd5035ac3db8847c53bba617169912bb251b42975a3a22")], extras=[],
    licenses=["Apache-2.0 (facebook/wav2vec2-base-960h)"], voices=[], alignment="", support="Shared dependency; 95.2 MB download; memory and latency not yet measured on Android"))
target = ROOT / "app/src/main/assets/model-manifests.json"
alignment = packs[-1]
for name in ["vocab.json", "config.json", "preprocessor_config.json"]:
    url = "https://huggingface.co/onnx-community/wav2vec2-base-960h-ONNX/resolve/729c1a6730fb549c20a1c73a3d3f96f11020225e/" + name
    data = urllib.request.urlopen(url, timeout=30).read()
    (DEPS / "alignment" / name).write_bytes(data)
    alignment["extras"].append(dict(path=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), url=url))
    alignment["installedBytes"] += len(data)
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(packs, indent=2) + "\n")
print("Wrote", target, "from verified upstream archives")
