#!/usr/bin/env python3
"""Read the current Google Play listing and release state without publishing edits.

The temporary edit is never mutated or committed. Endpoint permission failures
are recorded independently so a restricted account can still inspect what it
can access. This report does not assert production eligibility.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys

import publish_play
from publish_play import API, APP_PATH, PACKAGE, PublishError, authenticate, redact, safe_error

DEFAULT_REPORT = Path("build/play-store-inspection/report.json")
IMAGE_TYPES = (
    "icon", "featureGraphic", "phoneScreenshots",
    "sevenInchScreenshots", "tenInchScreenshots",
)


def clean_report(value):
    """Use publisher redaction while preserving complete public listing copy."""
    if isinstance(value, str):
        # redact() intentionally caps diagnostics at 800 characters; store
        # descriptions can be 4,000. Reuse its exact secret registry before
        # applying its sensitive-content guard without truncating public text.
        for secret in sorted(publish_play._SECRETS, key=len, reverse=True):
            if secret:
                value = value.replace(secret, "[REDACTED]")
        if redact(value) == "[Sensitive diagnostic omitted]":
            return "[Sensitive diagnostic omitted]"
        return value
    if isinstance(value, dict):
        return {clean_report(str(key)): clean_report(item) for key, item in value.items()}
    if isinstance(value, list):
        return [clean_report(item) for item in value]
    return value


def inspect(api: API, report: dict) -> None:
    errors = report.setdefault("endpointErrors", {})

    def read(label: str, path: str) -> dict:
        try:
            return api.request("GET", path)
        except Exception as error:
            diagnostic = safe_error(error)
            errors[label] = diagnostic
            return {"unavailable": diagnostic}

    edit_id = None
    tracks = []
    try:
        try:
            edit_id = api.create_edit()
            report["inspectionEditId"] = edit_id
        except Exception as error:
            errors["createEdit"] = safe_error(error)
        if edit_id is not None:
            prefix = f"{APP_PATH}/edits/{edit_id}"
            report["details"] = read("details", prefix + "/details")
            report["listings"] = read("listings", prefix + "/listings")
            report["images"] = {"en-US": {
                image_type: read("images.en-US." + image_type,
                                 prefix + "/listings/en-US/" + image_type)
                for image_type in IMAGE_TYPES
            }}
            report["tracks"] = read("tracks", prefix + "/tracks")
            report["bundles"] = read("bundles", prefix + "/bundles")
            tracks = report["tracks"].get("tracks", [])
            if not isinstance(tracks, list):
                errors["tracksStructure"] = safe_error(PublishError("Google returned an invalid tracks list."))
                tracks = []
    finally:
        if edit_id is not None:
            try:
                api.delete_edit(edit_id)
                report["inspectionEditDeleted"] = True
            except Exception as error:
                report["inspectionEditDeleted"] = False
                errors["deleteEdit"] = safe_error(error)

    # These endpoints do not require an edit, so inspect the known tracks even
    # if creating or reading an edit failed. Empty releases do not establish
    # whether the app may be published to production.
    names = {"internal", "production"}
    for track in tracks:
        name = track.get("track") if isinstance(track, dict) else None
        if isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9:_-]+", name):
            names.add(name)
        else:
            errors["trackName"] = safe_error(PublishError("Google returned an invalid track name; its lifecycle was not queried."))
    report["lifecycles"] = {
        name: read("lifecycles." + name, f"{APP_PATH}/tracks/{name}/releases")
        for name in sorted(names)
    }
    report["status"] = "inspected_with_errors" if errors else "inspected"


def run(report_path: Path = DEFAULT_REPORT) -> dict:
    report_path = Path(report_path)
    report = {
        "action": "inspect_store", "package": PACKAGE,
        "status": "started", "readOnly": True, "committed": False,
        "productionEligibility": "not_determined_by_these_endpoints",
        "startedAt": datetime.now(timezone.utc).isoformat(),
    }
    try:
        inspect(API(authenticate()), report)
    except Exception as error:
        report["status"] = "failed"
        report["error"] = safe_error(error)
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        report = clean_report(report)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, indent=2) + "\n")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()
    report = run(args.report)
    print(json.dumps({"status": report["status"], "report": str(args.report),
                      "endpointErrorCount": len(report.get("endpointErrors", {})),
                      "error": report.get("error")}))
    return 1 if report["status"] == "failed" else 0


if __name__ == "__main__":
    sys.exit(main())
