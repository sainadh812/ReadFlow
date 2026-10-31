#!/usr/bin/env python3
"""Fetch the app's exact pinned official PaddleOCR assets for development/smoke testing.

Uses the same byte counts and SHA-256 values as Android's atomic, resumable installer.
No conversion, floating branch download, or unofficial replacement weights.
"""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / ".deps" / "paddleocr"


def main():
    pack = next(p for p in json.loads((ROOT / "app/src/main/assets/ocr-model-manifests.json").read_text()) if p["id"] == "paddleocr")
    DEST.mkdir(parents=True, exist_ok=True)
    for item in pack["files"] + pack["extras"]:
        target = DEST / item["path"]
        assert target.resolve().parent == DEST.resolve()
        if not target.is_file():
            temporary = target.with_suffix(target.suffix + ".part")
            urllib.request.urlretrieve(item.get("url", pack["url"]), temporary)
            if temporary.stat().st_size != item["bytes"] or hashlib.sha256(temporary.read_bytes()).hexdigest() != item["sha256"]:
                raise SystemExit(f"Invalid download: {temporary}; no installed asset replaced")
            temporary.replace(target)
        checksum = hashlib.sha256(target.read_bytes()).hexdigest()
        if target.stat().st_size != item["bytes"] or checksum != item["sha256"]:
            raise SystemExit(f"Invalid asset: {target}; remove it and fetch again")
        print(f"Verified {item['path']}: {item['bytes']} bytes {checksum}")


if __name__ == "__main__":
    main()
