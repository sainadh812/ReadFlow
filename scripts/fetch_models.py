"""Developer setup: verify trusted upstream assets and extract outside source control."""
import hashlib
from pathlib import Path
import tarfile
import shutil
import urllib.request

ROOT = Path(__file__).resolve().parents[1] / ".deps"
ASSETS = [
    ("kokoro-en-v0_19.tar.bz2", "912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7"),
    ("sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2", "2f3b88823cbbb9bf0b2477ec8ae7b3fec417b3a87b6bb5f256dba66f2ad967cb"),
]

def fetch(url, path, digest):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        print("Downloading", path.name, flush=True)
        partial = path.with_suffix(".part")
        urllib.request.urlretrieve(url, partial)
        partial.rename(path)
    with path.open("rb") as source:
        checksum = hashlib.sha256()
        for block in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(block)
        actual = checksum.hexdigest()
    if actual != digest:
        path.unlink()
        raise ValueError(f"Checksum mismatch: {path}")
    return path

if __name__ == "__main__":
    for name, digest in ASSETS:
        archive = fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" + name, ROOT / name, digest)
        destination = ROOT / name.removesuffix(".tar.bz2")
        if not destination.exists():
            with tarfile.open(archive) as tf:
                for item in tf:
                    target = ROOT / item.name
                    if not target.resolve().is_relative_to(ROOT.resolve()) or not (item.isfile() or item.isdir()):
                        raise ValueError("Unsafe archive entry: " + item.name)
                    if item.isdir():
                        target.mkdir(parents=True, exist_ok=True)
                    else:
                        target.parent.mkdir(parents=True, exist_ok=True)
                        with tf.extractfile(item) as source, target.open("wb") as output:
                            shutil.copyfileobj(source, output)
        print("Verified", name, flush=True)
    fetch("https://huggingface.co/onnx-community/wav2vec2-base-960h-ONNX/resolve/729c1a6730fb549c20a1c73a3d3f96f11020225e/onnx/model_quantized.onnx", ROOT / "alignment/model.onnx", "1d9a366c27b2966625cd5035ac3db8847c53bba617169912bb251b42975a3a22")
