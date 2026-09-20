"""Read structured diagnostic comments as data. No uploaded text is executed."""
import argparse
from collections import Counter
import hashlib
import json
import re
import subprocess


def parse_comments(comments):
    events = {}
    for comment in comments:
        body = comment.get("body") or ""
        if "```jsonl\n" not in body or not body.endswith("\n```"):
            continue
        payload = body.split("```jsonl\n", 1)[1][:-4]
        marker = "<!-- readflow-batch:" + hashlib.sha256(payload.encode()).hexdigest() + " -->"
        if not body.startswith(marker):
            continue
        for line in payload.splitlines():
            try:
                entry = json.loads(line)
                if not isinstance(entry, dict) or not isinstance(entry.get("eventIds"), list):
                    continue
                for event in entry["eventIds"]:
                    if not isinstance(event, str):
                        continue
                    old = events.get(event)
                    if old is None or entry.get("inputIncluded"):
                        events[event] = entry
            except (ValueError, TypeError):
                continue
    return events


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default="sainadh812/ReadFlow")
    parser.add_argument("--issue", required=True, type=int)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}", args.repo) or ".." in args.repo or args.issue < 1:
        parser.error("Invalid repository or issue number")
    response = subprocess.run(["gh", "api", "--paginate", "--slurp", f"repos/{args.repo}/issues/{args.issue}/comments?per_page=100"],
                              check=True, text=True, capture_output=True)
    events = parse_comments([comment for page in json.loads(response.stdout) for comment in page])
    counts = Counter(entry.get("stage", "UNKNOWN") for entry in events.values())
    groups = {}
    for entry in events.values():
        signature = json.dumps([entry.get("stage"), entry.get("failureTypes"), entry.get("normalization"), entry.get("alignment"),
                                entry.get("configuration"), entry.get("input")], sort_keys=True)
        group = groups.setdefault(signature, {"events": 0, "latest": entry})
        group["events"] += 1
        if entry.get("timestampMs", 0) >= group["latest"].get("timestampMs", 0):
            group["latest"] = entry
    print(json.dumps({"repository": args.repo, "issue": args.issue, "uniqueEvents": len(events), "byStage": dict(counts),
                      "groups": sorted(groups.values(), key=lambda group: -group["events"])}, indent=2))


if __name__ == "__main__":
    main()
