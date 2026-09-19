"""Fetch small, pinned build dependencies; never run as an application server."""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
for asset in json.loads((ROOT / "scripts/vendor-lock.json").read_text()):
    path = ROOT / asset["path"]
    if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == asset["sha256"]:
        continue
    data = urllib.request.urlopen(asset["url"], timeout=60).read()
    assert hashlib.sha256(data).hexdigest() == asset["sha256"], asset["path"]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
