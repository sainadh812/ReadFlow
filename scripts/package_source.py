"""Create a small source delivery without downloaded weights, binaries or third-party trees."""
import hashlib
import json
import argparse
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--out", type=Path, default=ROOT / "dist/ReadFlow-source.zip")
target = parser.parse_args().out.resolve()
target.parent.mkdir(parents=True, exist_ok=True)
paths = [ROOT / name for name in ["README.md", ".gitignore", ".gitattributes", "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "app/build.gradle.kts"]]
for directory in ["gradle", "app/src", "app/schemas", "docs", "scripts", "site"]:
    paths.extend(p for p in (ROOT / directory).rglob("*") if p.is_file() and "__pycache__" not in p.parts and "vendor" not in p.parts)
with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(paths):
        archive.write(path, "ReadFlow/" + str(path.relative_to(ROOT)))
artifacts = {}
for path in sorted(target.parent.glob("*")):
    if not path.is_file() or path.name == "SHA256SUMS.json":
        continue
    artifacts[path.name] = dict(bytes=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest())
(target.parent / "SHA256SUMS.json").write_text(json.dumps(artifacts, indent=2) + "\n")
print(json.dumps(artifacts, indent=2))
