#!/usr/bin/env python3
"""Verify a built dictionary artifact before publishing its immutable URL."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import platform
import sqlite3
import statistics
import time
import unicodedata

from build import COVERAGE, normalize, sha256


def lookup(db: sqlite3.Connection, query: str) -> list[sqlite3.Row]:
    key = normalize(query)
    return db.execute("""SELECT headword,pos,definition,example FROM entries
      WHERE lookup=? OR headword IN (SELECT headword FROM forms WHERE lookup=?)
      ORDER BY CASE WHEN headword=? THEN 0 ELSE 1 END,id LIMIT 32""", (key, key, query)).fetchall()


def verify(manifest_path: Path) -> dict:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    name = f"{manifest['id']}-{manifest['version']}.sqlite"
    database, archive = manifest_path.parent / name, manifest_path.parent / (name + ".gz")
    for path, byte_key, hash_key in ((database, "installedBytes", "installedSha256"), (archive, "downloadBytes", "downloadSha256")):
        assert path.stat().st_size == manifest[byte_key], f"Byte count mismatch: {path}"
        assert sha256(path) == manifest[hash_key], f"SHA-256 mismatch: {path}"
    unpacked_hash, unpacked_size = hashlib.sha256(), 0
    with gzip.open(archive, "rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            unpacked_hash.update(block)
            unpacked_size += len(block)
    assert unpacked_size == manifest["installedBytes"], "Decompressed size mismatch"
    assert unpacked_hash.hexdigest() == manifest["installedSha256"], "Decompressed content mismatch"
    db = sqlite3.connect(f"file:{database.resolve()}?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    assert db.execute("PRAGMA user_version").fetchone()[0] == 1
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    assert {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")} == {"entries", "forms", "metadata"}
    metadata = dict(db.execute("SELECT key,value FROM metadata"))
    assert metadata["pack_id"] == manifest["id"]
    assert metadata["version"] == manifest["version"]
    for table, expected in (("entries", int(metadata["entries"])), ("forms", int(metadata["forms"]))):
        assert db.execute(f"SELECT count(*) FROM {table}").fetchone()[0] == expected
    assert not db.execute("SELECT 1 FROM entries WHERE headword='' OR lookup='' OR definition='' OR source_url NOT LIKE 'https://en.wiktionary.org/wiki/%#English' LIMIT 1").fetchone()
    assert not db.execute("SELECT 1 FROM forms f WHERE NOT EXISTS(SELECT 1 FROM entries e WHERE e.headword=f.headword) LIMIT 1").fetchone()
    # These fixture expectations are independent of the importer transformations.
    for word in COVERAGE[:15] + ["can't", "cannot", "isn't", "won't"]:
        assert lookup(db, word), f"Missing representative vocabulary: {word}"
    for form, headword in (("children", "child"), ("went", "go"), ("running", "run"), ("colour", "color")):
        assert db.execute("SELECT 1 FROM forms WHERE lookup=? AND headword=?", (form, headword)).fetchone(), (form, headword)
    assert any(row["headword"] == "running" for row in lookup(db, "running")), "Lost substantive exact sense"
    for word in ("can't", "cannot", "isn't", "won't"):
        rows = db.execute("SELECT definition FROM entries WHERE headword=? ORDER BY id", (word,)).fetchall()
        assert rows and any("not" in row[0].lower() or "unable" in row[0].lower() for row in rows), f"Lost negation: {word}"
    assert not db.execute("SELECT 1 FROM forms WHERE lookup=? AND headword IN ('is','be')", ("isn't",)).fetchone()
    assert lookup(db, "Polish")[0]["headword"] == "Polish"
    assert lookup(db, "polish")[0]["headword"] == "polish"
    assert lookup(db, "“well‑being!”")
    assert lookup(db, "word,")
    assert not lookup(db, "zzreadassistmissingwordzz")
    words = [row[0] for row in db.execute("SELECT headword FROM entries WHERE id % 997=0 LIMIT 300")]
    milliseconds = []
    for word in words:
        start = time.perf_counter()
        assert lookup(db, word)
        milliseconds.append((time.perf_counter() - start) * 1000)
    result = {
        "status": "passed", "checks": "Hashes, gzip bytes, complete integrity, schema, metadata, coverage, forms, contraction negation, case distinction, punctuation, missing-word handling",
        "manifest": manifest,
        "runtime": {"python": platform.python_version(), "sqlite": sqlite3.sqlite_version, "unicode": unicodedata.unidata_version},
        "lookupBenchmark": {"environment": "Linux build machine; these are not Android device measurements", "queries": len(milliseconds),
                            "medianMs": round(statistics.median(milliseconds), 3),
                            "p95Ms": round(sorted(milliseconds)[int(len(milliseconds) * 0.95)], 3),
                            "maxMs": round(max(milliseconds), 3)},
    }
    db.close()
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, nargs="?", default=Path("build/dictionary/manifest.json"))
    args = parser.parse_args()
    report = verify(args.manifest)
    (args.manifest.parent / "verification-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
