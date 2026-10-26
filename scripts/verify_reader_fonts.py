#!/usr/bin/env python3
"""Verify untouched reader fonts and their notices, optionally inside an APK/AAB. No network needed."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("app/src/main/assets/licenses/fonts/provenance.json")
EXPECTED_IDS = {"opendyslexic", "atkinson", "lexend", "literata", "source_serif", "noto_sans"}


def validate_file(row, data):
    assert len(data) == row["bytes"], f"Size changed: {row['path']}"
    assert hashlib.sha256(data).hexdigest() == row["sha256"], f"Hash changed: {row['path']}"
    if row["kind"] == "font":
        assert data[:4] in (b"OTTO", b"\x00\x01\x00\x00"), f"Invalid OpenType file: {row['path']}"
        count = struct.unpack_from(">H", data, 4)[0]
        tags = {data[12 + 16 * i:16 + 16 * i] for i in range(count)}
        assert {b"name", b"cmap"} <= tags, f"Missing font metadata: {row['path']}"
    elif row["kind"] == "license":
        text = data.decode("utf-8")
        assert "SIL OPEN FONT LICENSE Version 1.1" in text, f"Unexpected license: {row['path']}"
        assert "Copyright" in text or "copyright" in text, f"Missing copyright: {row['path']}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", type=Path, help="Also inspect an assembled APK or AAB")
    args = parser.parse_args()
    manifest_bytes = (ROOT / MANIFEST).read_bytes()
    manifest = json.loads(manifest_bytes)
    assert {font["id"] for font in manifest["fonts"]} == EXPECTED_IDS
    listed_fonts = set()
    files = []
    for font in manifest["fonts"]:
        assert font["license"] == "OFL-1.1" and font["modified"] is False
        assert len(font["revision"]) == 40
        assert any(row["kind"] == "license" for row in font["files"])
        for row in font["files"]:
            assert font["revision"] in row["url"], f"Unpinned source: {row['url']}"
            validate_file(row, (ROOT / row["path"]).read_bytes())
            files.append(row)
            if row["kind"] == "font":
                listed_fonts.add(row["path"])
    actual_fonts = {str(p.relative_to(ROOT)) for p in (ROOT / "app/src/main/res/font").glob("*") if p.suffix in (".ttf", ".otf")}
    assert listed_fonts == actual_fonts, "Font binaries missing from provenance"
    if args.artifact:
        with zipfile.ZipFile(args.artifact) as artifact:
            prefix = "base/" if args.artifact.suffix == ".aab" else ""
            assert artifact.read(prefix + "assets/licenses/fonts/provenance.json") == manifest_bytes
            for row in files:
                packaged_path = prefix + row["path"].removeprefix("app/src/main/")
                validate_file(row, artifact.read(packaged_path))
    print(json.dumps({"status": "PASS", "families": len(EXPECTED_IDS), "fontFiles": len(listed_fonts),
                      "fontBytes": sum(r["bytes"] for r in files if r["kind"] == "font"),
                      "licenses": len([r for r in files if r["kind"] == "license"]),
                      "artifact": str(args.artifact) if args.artifact else None}, indent=2))


if __name__ == "__main__":
    main()
