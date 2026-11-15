#!/usr/bin/env python3
"""Run the reader's exact SQL against a finished pack, using host SQLite.

This checks shipped data/query compatibility, not Android UI or device latency.
The Python normalization and bounded selection mirror the Kotlin helpers; hashes
record which production source snapshot was reviewed alongside the extracted SQL.
"""

from __future__ import annotations

import argparse
from collections import OrderedDict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import statistics
import sys
import textwrap
import time
import unicodedata


REPOSITORY = Path(__file__).resolve().parents[2]
JAVA_UNICODE_WHITESPACE = re.compile(r"[\t\n\v\f\r \u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+")
TRANSLATION = str.maketrans({
    **{character: "'" for character in "\u2018\u2019\u02bc\uff07"},
    **{character: "-" for character in "\u2010\u2011\u2012\u2013\u2014\u2015\u2212\ufe63\uff0d"},
})


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def normalize(raw: str) -> tuple[str, str]:
    def check_length(value: str) -> None:
        if len(value.encode("utf-16-le")) // 2 > 256:
            raise ValueError("Search for a shorter word or phrase.")

    def edge(character: str) -> bool:
        category = unicodedata.category(character)
        return not (category.startswith("L") or category == "Nd" or category.startswith("M"))

    check_length(raw)
    value = unicodedata.normalize("NFKC", raw).translate(TRANSLATION)
    start, end = 0, len(value)
    while start < end and edge(value[start]):
        start += 1
    while end > start and edge(value[end - 1]):
        end -= 1
    display = JAVA_UNICODE_WHITESPACE.sub(" ", value[start:end])
    check_length(display)
    return display, display.lower()


def select_senses(candidates: list[sqlite3.Row], limit: int) -> list[sqlite3.Row]:
    distinct = list(OrderedDict((row["id"], row) for row in candidates).values())
    positions: set[str] = set()
    selected: list[sqlite3.Row] = []
    for row in distinct:
        if row["pos"] not in positions and len(selected) < limit:
            positions.add(row["pos"])
            selected.append(row)
    selected_ids = {row["id"] for row in selected}
    groups: OrderedDict[tuple[str, str], list[sqlite3.Row]] = OrderedDict()
    for row in distinct:
        groups.setdefault((row["headword"], row["pos"]), []).append(row)
    iterators = [iter(group) for group in groups.values()]
    while len(selected) < limit:
        added = False
        for group in iterators:
            for row in group:
                if row["id"] in selected_ids:
                    continue
                selected_ids.add(row["id"])
                selected.append(row)
                added = True
                break
            if len(selected) == limit:
                break
        if not added:
            break
    return selected


def extract_reader_query(path: Path) -> tuple[str, int]:
    source = path.read_text(encoding="utf-8")
    columns = re.findall(r'const val ENTRY_COLUMNS = "([^"]+)"', source)
    queries = re.findall(r'"""(WITH matches AS.*?)"""\.trimIndent\(\)', source, re.DOTALL)
    limits = re.findall(r"const val MAX_SENSES = ([0-9]+)", source)
    if len(columns) != 1 or len(queries) != 1 or len(limits) != 1:
        raise ValueError("Production SQL extraction no longer matches the reader; update this verifier explicitly.")
    query = textwrap.dedent(queries[0]).replace("$ENTRY_COLUMNS", columns[0])
    if "$" in query or query.count("?") != 4:
        raise ValueError("Unexpected interpolation or parameter shape in production reader SQL.")
    return query, int(limits[0])


CASES = [
    {"query": "running", "pairs": [("running", "adjective"), ("run", "verb")]},
    {"query": "run", "pairs": [("run", "noun"), ("run", "verb")]},
    {"query": "children", "headword": "child", "first_headword": "child"},
    {"query": "went", "headword": "go"},
    {"query": "can't", "any_headword": ["can't", "cannot", "can"]},
    {"query": "can’t", "same_as": "can't"},
    {"query": "won't", "any_headword": ["won't", "will", "will not"]},
    {"query": "Polish", "first_headword": "Polish"},
    {"query": "polish", "first_headword": "polish"},
    {"query": "good", "headword": "good"},
    {"query": "good₹%&", "same_as": "good"},
    {"query": "well-being", "headword": "well-being"},
    {"query": "“well–being,”", "same_as": "well-being"},
    {"query": "resilient", "headword": "resilient"},
    {"query": "serendipity", "headword": "serendipity"},
    {"query": "colour", "any_headword": ["colour", "color"]},
    {"query": "photosynthesis", "headword": "photosynthesis"},
    {"query": "the", "headword": "the"},
    {"query": "because", "headword": "because"},
    {"query": "although", "headword": "although"},
    {"query": "zzzxqv_not_a_real_english_word_8391", "empty": True},
]


def verify(pack_dir: Path, output: Path) -> dict:
    manifest_path = pack_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    database_path = pack_dir / f"{manifest['id']}-{manifest['version']}.sqlite"
    repository_source = REPOSITORY / "app/src/main/java/app/readflow/dictionary/DictionaryRepository.kt"
    models_source = repository_source.with_name("DictionaryModels.kt")
    sql, limit = extract_reader_query(repository_source)
    evidence = {
        "checkedAtUtc": datetime.now(timezone.utc).isoformat(),
        "scope": "Production reader SQL on the final real dictionary pack, executed by host SQLite. Python mirrors normalization and sense selection; Android integration is checked separately.",
        "timingScope": "Host build machine, one read-only SQLite connection; seven warm repeated SQL/normalization/selection lookups after each initial lookup. These are not Android device timings.",
        "sqliteVersion": sqlite3.sqlite_version,
        "pack": {"id": manifest["id"], "version": manifest["version"], "installedBytes": database_path.stat().st_size, "installedSha256": sha256(database_path)},
        "sourceHashes": {str(path.relative_to(REPOSITORY)): sha256(path) for path in (repository_source, models_source)},
        "exactReaderSql": sql,
        "maxSenses": limit,
        "checks": [],
        "failures": [],
    }
    failures = evidence["failures"]
    if evidence["pack"]["installedBytes"] != manifest["installedBytes"] or evidence["pack"]["installedSha256"] != manifest["installedSha256"]:
        failures.append("Final database bytes/hash do not match the app manifest.")
    previous: dict[str, list[int]] = {}
    timings = []
    with sqlite3.connect(database_path.resolve().as_uri() + "?mode=ro", uri=True) as database:
        database.row_factory = sqlite3.Row
        metadata = dict(database.execute("SELECT key, value FROM metadata"))
        if metadata.get("pack_id") != manifest["id"] or metadata.get("version") != manifest["version"]:
            failures.append("Database identity does not match the app manifest.")
        if database.execute("PRAGMA user_version").fetchone()[0] != manifest["schemaVersion"]:
            failures.append("Database schema version does not match the app manifest.")

        def lookup(raw: str) -> tuple[list[sqlite3.Row], int]:
            display, key = normalize(raw)
            candidates = database.execute(sql, (key, display, key, key)).fetchall()
            return select_senses(candidates, limit), len(candidates)

        for case in CASES:
            raw = case["query"]
            started = time.perf_counter()
            selected, candidate_count = lookup(raw)
            initial_ms = (time.perf_counter() - started) * 1000
            repeated = []
            for _ in range(7):
                started = time.perf_counter()
                lookup(raw)
                repeated.append((time.perf_counter() - started) * 1000)
            timings.extend(repeated)
            pairs = {(row["headword"], row["pos"]) for row in selected}
            words = {row["headword"] for row in selected}
            selected_ids = [row["id"] for row in selected]
            problems = []
            if len(selected) > limit or len(set(selected_ids)) != len(selected):
                problems.append("Sense limit or deduplication violated")
            if candidate_count > 128:
                problems.append("Candidate bound violated")
            if case.get("empty"):
                if selected:
                    problems.append("Missing word unexpectedly returned definitions")
            elif not selected:
                problems.append("No definitions returned")
            for pair in case.get("pairs", []):
                if tuple(pair) not in pairs:
                    problems.append(f"Missing headword/POS {pair}")
            if case.get("headword") and case["headword"] not in words:
                problems.append(f"Missing lemma {case['headword']}")
            if case.get("any_headword") and not set(case["any_headword"]) & words:
                problems.append(f"None of the expected contraction/spelling targets returned: {case['any_headword']}")
            if case.get("first_headword") and (not selected or selected[0]["headword"] != case["first_headword"]):
                problems.append("Exact-case headword lost first priority")
            if case.get("same_as") and selected_ids != previous[case["same_as"]]:
                problems.append(f"Normalization changed results relative to {case['same_as']}")
            previous[raw] = selected_ids
            evidence["checks"].append({
                "query": raw, "normalized": normalize(raw)[1], "passed": not problems,
                "candidateCount": candidate_count, "selectedCount": len(selected),
                "headwordPartsOfSpeech": sorted(pairs), "firstSenseId": selected_ids[0] if selected_ids else None,
                "firstHeadword": selected[0]["headword"] if selected else None,
                "initialHostMs": round(initial_ms, 3), "warmMedianHostMs": round(statistics.median(repeated), 3),
                "problems": problems,
            })
            failures.extend(f"{raw}: {problem}" for problem in problems)
    evidence["timings"] = {"warmLookups": len(timings), "warmMedianHostMs": round(statistics.median(timings), 3), "warmMaxHostMs": round(max(timings), 3)}
    evidence["passed"] = not failures
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack-dir", type=Path, default=REPOSITORY / "build/dictionary")
    parser.add_argument("--output", type=Path, default=REPOSITORY / "build/dictionary/reader-query-verification.json")
    args = parser.parse_args()
    if not (args.pack_dir / "manifest.json").is_file():
        parser.error("Final pack manifest is not ready. Run after the dictionary pack build completes.")
    evidence = verify(args.pack_dir, args.output)
    print(json.dumps({"passed": evidence["passed"], "checks": len(evidence["checks"]), "failures": evidence["failures"], "hostTimings": evidence["timings"], "evidence": str(args.output)}, ensure_ascii=False, indent=2))
    return 0 if evidence["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
