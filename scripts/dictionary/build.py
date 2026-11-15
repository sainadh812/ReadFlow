#!/usr/bin/env python3
"""Build the Read Assist offline dictionary from a pinned Kaikki JSONL dump.

Python standard library only. Input is read line by line, never as one JSON value.
See docs/dictionary/BUILD.txt for the source lock and redistribution details.
"""
from __future__ import annotations

import argparse
import collections
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import time
import unicodedata
from urllib.parse import quote

IMPORTER_VERSION = "1"
PACK_ID = "english-dictionary"
PACK_VERSION = "2026.10-v1"
LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
SOURCE_URL = "https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz"
SOURCE = "English Wiktionary via Kaikki / Wiktextract, 2026-10-01 dump"
CREDITS = (
    "English Wiktionary contributors; extracted by Kaikki / Wiktextract (Tatu Ylonen and contributors). "
    "Read Assist filtered English text, selected definitions and authored examples, normalized lookup keys, "
    "resolved word forms, and converted the data to SQLite. Adapted dictionary data: CC BY-SA 4.0. "
    "Each definition links to its Wiktionary source and contributor history."
)
TRANSLATION = str.maketrans({
    **{c: "'" for c in "\u2018\u2019\u02bc\uff07"},
    **{c: "-" for c in "\u2010\u2011\u2012\u2013\u2014\u2015\u2212\ufe63\uff0d"},
})
LANG_EN = re.compile(br'"lang_code"\s*:\s*"en"')
POS = {
    "adj": "adjective", "adv": "adverb", "prep": "preposition",
    "conj": "conjunction", "det": "determiner", "pron": "pronoun",
    "intj": "interjection", "num": "numeral", "name": "proper noun",
    "proverb": "proverb", "phrase": "phrase", "symbol": "symbol",
    "punct": "punctuation", "character": "character", "abbrev": "abbreviation",
    "affix": "affix", "prefix": "prefix", "suffix": "suffix", "particle": "particle",
    "noun": "noun", "verb": "verb", "article": "article", "contraction": "contraction",
}
SCHEMA = """
PRAGMA user_version=1;
CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE entries(id INTEGER PRIMARY KEY,headword TEXT NOT NULL,lookup TEXT NOT NULL,
 pos TEXT NOT NULL,definition TEXT NOT NULL,example TEXT NOT NULL DEFAULT '',
 ipa TEXT NOT NULL DEFAULT '',source_url TEXT NOT NULL,labels TEXT NOT NULL DEFAULT '');
CREATE TABLE forms(lookup TEXT NOT NULL,headword TEXT NOT NULL);
CREATE INDEX entries_lookup ON entries(lookup);
CREATE INDEX entries_headword ON entries(headword);
CREATE INDEX forms_lookup ON forms(lookup);
CREATE TABLE raw_links(lookup TEXT NOT NULL,target TEXT NOT NULL,target_lookup TEXT NOT NULL,
 PRIMARY KEY(lookup,target)) WITHOUT ROWID;
"""
COVERAGE = ["the", "because", "although", "run", "running", "child", "children", "go", "went",
            "resilient", "serendipity", "well-being", "colour", "color", "photosynthesis",
            "can't", "word,", "Polish", "polish", "reader’s", "well‑being"]


def normalize(value: str) -> str:
    value = " ".join(unicodedata.normalize("NFKC", value).translate(TRANSLATION).split())
    while value and unicodedata.category(value[0])[0] not in "LNM":
        value = value[1:]
    while value and unicodedata.category(value[-1])[0] not in "LNM":
        value = value[:-1]
    return value.lower()


def text(value: object) -> str:
    return value.strip() if isinstance(value, str) else ""


def fits(value: str, limit: int) -> bool:
    # Android/Kotlin String.length counts UTF-16 code units.
    return len(value.encode("utf-16-le")) // 2 <= limit


def strings(value: object) -> list[str]:
    return [item.strip() for item in value if isinstance(item, str) and item.strip()] if isinstance(value, list) else []


def objects(value: object) -> list[dict]:
    return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []


def selected_example(sense: dict, stats: collections.Counter) -> str:
    selected = ""
    for example in objects(sense.get("examples")):
        # Wiktextract explicitly classifies ux/usex examples. Unknown examples and
        # every cited/quoted example are excluded, even if another tag says example.
        eligible = example.get("type") == "example" and not example.get("ref")
        content = text(example.get("text"))
        if eligible and content and len(content) <= 1000:
            stats["eligible_authored_examples"] += 1
            if not selected:
                selected = content
        else:
            stats["excluded_examples"] += 1
    return selected


def add_link(db: sqlite3.Connection, spelling: str, target: str) -> None:
    target = target.split("#", 1)[0].strip()
    key, target_key = normalize(spelling), normalize(target)
    if key and target_key and key != target_key and all(fits(s, 256) for s in (spelling, target, key, target_key)):
        db.execute("INSERT OR IGNORE INTO raw_links VALUES(?,?,?)", (key, target, target_key))


def add_record(db: sqlite3.Connection, record: dict, stats: collections.Counter) -> None:
    if record.get("lang_code") != "en":
        return
    word = text(record.get("word"))
    key = normalize(word)
    if not word or not key or not fits(word, 256) or not fits(key, 256):
        stats["empty_headwords"] += 1
        return
    stats["english_records"] += 1
    pos = text(record.get("pos"))
    pos = POS.get(pos, pos.replace("-", " ")) or "word"
    ipa = next((text(s.get("ipa")) for s in objects(record.get("sounds")) if text(s.get("ipa")) and fits(text(s.get("ipa")), 256)), "")
    source_url = "https://en.wiktionary.org/wiki/" + quote(word.replace(" ", "_"), safe="") + "#English"
    seen = set()
    for sense in objects(record.get("senses")):
        references = objects(sense.get("form_of")) + objects(sense.get("alt_of"))
        # A reference plus extra words is not equivalent to its target: isn't
        # means "is + not", not "is". Keep the source gloss and do not create a
        # misleading alias for contractions or qualified/multiword references.
        semantic_reference = (
            any(text(reference.get("extra")) for reference in references)
            or bool({"contraction", "negative"}.intersection(strings(sense.get("tags"))))
            or len(strings(sense.get("glosses"))) > 1
            or len(references) > 1
        )
        if not semantic_reference:
            for reference in references:
                add_link(db, word, text(reference.get("word")))
        example = selected_example(sense, stats)
        # Pure morphological/spelling references become aliases. Their actual
        # headword definitions are returned by lookup rather than an empty redirect.
        if references and not semantic_reference:
            stats["reference_senses"] += 1
            continue
        glosses = list(dict.fromkeys(strings(sense.get("glosses"))))
        if not glosses:
            stats["empty_senses"] += 1
            continue
        definition = " ".join(glosses)
        if not fits(definition, 8192):
            stats["overlong_senses"] += 1
            continue
        labels = list(dict.fromkeys(strings(sense.get("tags")) + strings(sense.get("raw_tags"))))
        qualifier = text(sense.get("qualifier"))
        if qualifier:
            labels.append(qualifier)
        labels = ", ".join(label.replace("-", " ") for label in labels if label not in {"no-gloss", "error-unknown-tag"})
        identity = (definition, labels)
        if identity in seen:
            stats["duplicate_senses"] += 1
            continue
        seen.add(identity)
        db.execute("INSERT INTO entries(headword,lookup,pos,definition,example,ipa,source_url,labels) VALUES(?,?,?,?,?,?,?,?)",
                   (word, key, pos, definition, example, ipa, source_url, labels))
        stats["entries"] += 1
        stats["entries_with_examples"] += bool(example)
    for reference in objects(record.get("form_of")) + objects(record.get("alt_of")):
        if not text(reference.get("extra")) and "contraction" not in strings(record.get("tags")):
            add_link(db, word, text(reference.get("word")))
    for form in objects(record.get("forms")):
        tags = strings(form.get("tags"))
        if any(tag in tags for tag in ("table-tags", "inflection-template", "class", "negative", "contraction")):
            continue
        add_link(db, text(form.get("form")), word)


def resolve_forms(db: sqlite3.Connection, stats: collections.Counter) -> None:
    """Resolve explicit references on disk, bounded to eight hops with set dedup.

    Headwords with definitions terminate a path. This avoids deriving false links
    through unrelated same-spelling inflections, while preserving direct senses.
    """
    db.executescript("""
      CREATE INDEX raw_links_target ON raw_links(target);
      CREATE INDEX raw_links_lookup ON raw_links(lookup);
      CREATE TABLE resolved(lookup TEXT NOT NULL,headword TEXT NOT NULL,
        PRIMARY KEY(lookup,headword)) WITHOUT ROWID;
      INSERT OR IGNORE INTO resolved
        SELECT r.lookup,e.headword FROM raw_links r JOIN entries e ON e.headword=r.target;
      CREATE TABLE frontier(lookup TEXT NOT NULL,target TEXT NOT NULL,target_lookup TEXT NOT NULL,
        PRIMARY KEY(lookup,target)) WITHOUT ROWID;
      INSERT OR IGNORE INTO frontier SELECT r.* FROM raw_links r
        WHERE NOT EXISTS(SELECT 1 FROM entries e WHERE e.headword=r.target);
      CREATE TABLE visited(lookup TEXT NOT NULL,target TEXT NOT NULL,
        PRIMARY KEY(lookup,target)) WITHOUT ROWID;
      INSERT OR IGNORE INTO visited SELECT lookup,target FROM frontier;
    """)
    stats["raw_form_links"] = db.execute("SELECT count(*) FROM raw_links").fetchone()[0]
    for depth in range(8):
        count = db.execute("SELECT count(*) FROM frontier").fetchone()[0]
        if not count:
            break
        stats["form_resolution_rounds"] = depth + 1
        db.executescript("""
          CREATE TABLE following(lookup TEXT NOT NULL,target TEXT NOT NULL,target_lookup TEXT NOT NULL,
            PRIMARY KEY(lookup,target)) WITHOUT ROWID;
          INSERT OR IGNORE INTO following
            SELECT f.lookup,r.target,r.target_lookup FROM frontier f JOIN raw_links r ON r.lookup=f.target_lookup
            WHERE f.lookup != r.target_lookup
              AND NOT EXISTS(SELECT 1 FROM visited v WHERE v.lookup=f.lookup AND v.target=r.target);
          INSERT OR IGNORE INTO resolved
            SELECT f.lookup,e.headword FROM following f JOIN entries e ON e.headword=f.target;
          INSERT OR IGNORE INTO visited SELECT lookup,target FROM following;
          DROP TABLE frontier;
          ALTER TABLE following RENAME TO frontier;
          DELETE FROM frontier WHERE EXISTS(SELECT 1 FROM entries e WHERE e.headword=frontier.target);
        """)
    db.execute("INSERT INTO forms SELECT lookup,headword FROM resolved ORDER BY lookup,headword")
    stats["forms"] = db.execute("SELECT count(*) FROM forms").fetchone()[0]
    stats["unresolved_form_lookups"] = db.execute(
        "SELECT count(DISTINCT r.lookup) FROM raw_links r WHERE NOT EXISTS(SELECT 1 FROM forms f WHERE f.lookup=r.lookup)"
    ).fetchone()[0]
    db.executescript("DROP TABLE raw_links; DROP TABLE resolved; DROP TABLE frontier; DROP TABLE visited;")


def coverage_report(db: sqlite3.Connection) -> dict:
    report = {}
    for query in COVERAGE:
        key = normalize(query)
        rows = db.execute("""SELECT headword,pos,definition,example,labels FROM entries
          WHERE lookup=? OR headword IN (SELECT headword FROM forms WHERE lookup=?)
          ORDER BY CASE WHEN headword=? THEN 0 ELSE 1 END,id""", (key, key, query)).fetchall()
        report[query] = {"found": bool(rows), "senses": len(rows),
                         "headwords": list(dict.fromkeys(row[0] for row in rows)),
                         "first_senses": [dict(zip(("headword", "pos", "definition", "example", "labels"), row)) for row in rows[:3]]}
    return report


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build(source: Path, output: Path, lock: dict, url: str) -> dict:
    actual_hash = sha256(source)
    if actual_hash != lock["sha256"] or source.stat().st_size != lock["bytes"]:
        raise ValueError("Source does not match the pinned SHA-256 and byte count; do not build an unreviewed replacement.")
    output.mkdir(parents=True, exist_ok=True)
    pack_name = f"{PACK_ID}-{PACK_VERSION}.sqlite"
    database = output / pack_name
    temporary = output / (pack_name + ".building")
    temporary.unlink(missing_ok=True)
    db = sqlite3.connect(temporary)
    db.executescript("PRAGMA page_size=4096; PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA temp_store=FILE;")
    db.executescript(SCHEMA)
    stats = collections.Counter()
    start = time.monotonic()
    opener = gzip.open if source.suffix == ".gz" else open
    with opener(source, "rb") as stream:
        for line_number, line in enumerate(stream, 1):
            stats["source_records"] += 1
            if not LANG_EN.search(line):
                continue
            try:
                record = json.loads(line)
            except (ValueError, UnicodeError) as error:
                raise ValueError(f"Invalid source JSON on line {line_number}") from error
            if not isinstance(record, dict):
                raise ValueError(f"Expected a JSON object on line {line_number}")
            add_record(db, record, stats)
            if stats["english_records"] and stats["english_records"] % 50000 == 0:
                db.commit()
                print(f"Imported {stats['english_records']:,} English records ({stats['entries']:,} senses), {time.monotonic()-start:.0f}s", file=sys.stderr)
    db.commit()
    print("Resolving explicit word forms…", file=sys.stderr)
    resolve_forms(db, stats)
    stats["headwords"] = db.execute("SELECT count(DISTINCT headword) FROM entries").fetchone()[0]
    metadata = {
        "id": PACK_ID, "pack_id": PACK_ID, "version": PACK_VERSION, "name": "English dictionary", "schemaVersion": "1",
        "source": SOURCE, "credits": CREDITS, "licenseUrl": LICENSE_URL, "source_url": SOURCE_URL,
        "license": "CC-BY-SA-4.0", "dump_date": lock["dumpDate"], "extracted_date": lock["extractedDate"],
        "source_sha256": actual_hash, "source_bytes": str(source.stat().st_size),
        "extractor_commit": lock["extractorCommit"], "importer_version": IMPORTER_VERSION,
        "normalization": "NFKC, apostrophe/dash mapping, collapse whitespace, trim edge non-letter/number/mark, lowercase",
        "example_policy": "Only explicitly typed example without ref, at most 1000 characters; quotations and unclassified examples excluded.",
        "form_policy": "Explicit forms/form_of/alt_of only; at most eight reference hops; no suffix guessing.",
        "entries": str(stats["entries"]), "headwords": str(stats["headwords"]), "forms": str(stats["forms"]),
    }
    db.executemany("INSERT INTO metadata VALUES(?,?)", sorted(metadata.items()))
    db.commit()
    coverage = coverage_report(db)
    required = COVERAGE[:15]
    missing = [word for word in required if not coverage[word]["found"]]
    if missing:
        raise ValueError(f"Required coverage gate failed: {missing}")
    if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
        raise ValueError("Dictionary integrity check failed")
    db.execute("VACUUM")
    db.close()
    os.replace(temporary, database)
    compressed = output / (pack_name + ".gz")
    with database.open("rb") as data, compressed.open("wb") as destination:
        with gzip.GzipFile(filename="", mode="wb", fileobj=destination, compresslevel=9, mtime=0) as archive:
            shutil.copyfileobj(data, archive, 1024 * 1024)
    manifest = {
        "id": PACK_ID, "version": PACK_VERSION, "name": "English dictionary", "url": url,
        "downloadBytes": compressed.stat().st_size, "downloadSha256": sha256(compressed),
        "installedBytes": database.stat().st_size, "installedSha256": sha256(database),
        "schemaVersion": 1, "source": SOURCE, "credits": CREDITS, "licenseUrl": LICENSE_URL,
    }
    evidence = {"manifest": manifest, "source": lock, "statistics": dict(stats), "coverage": coverage,
                "limitations": ["First sense is stable source order, not context-aware ranking.",
                                "A coverage sample is not a guarantee that every English word is included.",
                                "Missing forms are not guessed; unresolved references remain unavailable.",
                                "Exact definitions and explicit form matches may legitimately return multiple headwords."]}
    for filename, contents in (("manifest.json", manifest), ("build-report.json", evidence)):
        (output / filename).write_text(json.dumps(contents, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"manifest": manifest, "statistics": dict(stats)}, ensure_ascii=False, indent=2))
    return evidence


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--lock", type=Path, default=Path("docs/dictionary/source-lock.json"))
    parser.add_argument("--output", type=Path, default=Path("build/dictionary"))
    parser.add_argument("--url", default=f"https://github.com/sainadh812/ReadFlow/releases/download/{PACK_ID}-{PACK_VERSION}/{PACK_ID}-{PACK_VERSION}.sqlite.gz")
    args = parser.parse_args()
    build(args.source, args.output, json.loads(args.lock.read_text(encoding="utf-8")), args.url)


if __name__ == "__main__":
    main()
