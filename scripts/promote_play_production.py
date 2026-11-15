#!/usr/bin/env python3
"""Promote the exact approved Read Assist 0.3.1 bundle to production.

This changes only the production track. It never uploads another bundle,
rebuilds the app, submits policy answers, or changes other tracks or testers.
--validate-only stages the release in an owned edit, validates it, then deletes
that edit without committing. Google may still require Console work/review.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

from inspect_play_store import clean_report
from publish_play import (
    AAB_SHA256, API, APP_PATH, PACKAGE, VERSION_CODE, VERSION_NAME,
    ApiError, PublishError, authenticate, safe_error,
)
from publish_store_media import IMAGE_TYPES, LANGUAGE, digest, load_assets, load_listing, snapshot

DEFAULT_REPORT = Path("build/play-production-publication/report.json")
DEFAULT_LISTING = Path(__file__).resolve().parents[1] / "store-assets/listing/en-US.json"
RELEASE_NOTES = (
    "Read and listen to PDFs, scanned pages, images and saved web articles. Switch between "
    "PDF and text, turn pages with a swipe, and adjust speed, voice, brightness and warm light "
    "from the reader panel. Look up words with the English dictionary and choose Warm Library, "
    "Modern Focus or Night Reader. Document processing and speech run on your device; "
    "downloadable packs are required for some features."
)
LIFECYCLE_STATES = {
    "RELEASE_LIFECYCLE_STATE_PUBLISHED": "published",
    "RELEASE_LIFECYCLE_STATE_IN_REVIEW": "in_review",
    "RELEASE_LIFECYCLE_STATE_NOT_SENT_FOR_REVIEW": "not_sent_for_review",
    "RELEASE_LIFECYCLE_STATE_APPROVED_NOT_PUBLISHED": "approved_not_published",
    "RELEASE_LIFECYCLE_STATE_NOT_APPROVED": "not_approved",
    "RELEASE_LIFECYCLE_STATE_DRAFT": "draft",
}


def version_codes(release: dict) -> set[int]:
    values = release.get("versionCodes", []) if isinstance(release, dict) else None
    if not isinstance(values, list):
        raise PublishError("Google returned invalid release version codes.")
    try:
        result = {int(value) for value in values}
    except (ValueError, TypeError):
        raise PublishError("Google returned a non-numeric release version code.") from None
    if any(value < 1 for value in result):
        raise PublishError("Google returned an invalid release version code.")
    return result


def production_complete(state: dict) -> bool:
    releases = state["tracks"].get("production", {}).get("releases", [])
    return len(releases) == 1 and releases[0].get("status") == "completed" and version_codes(releases[0]) == {VERSION_CODE}


def read_snapshot(api: API, edit_id: str) -> dict:
    state = snapshot(api, edit_id)
    bundles = api.request("GET", f"{APP_PATH}/edits/{edit_id}/bundles").get("bundles", [])
    if not isinstance(bundles, list):
        raise PublishError("Google returned an invalid bundle list.")
    state["bundles"] = bundles
    return state


def validate_ready(state: dict, assets: list[dict], approved_listing: dict) -> dict:
    listing = state["listings"].get(LANGUAGE, {})
    if listing.get("title") != "Read Assist":
        raise PublishError("The live English Play listing must have the approved Read Assist title.")
    for field, maximum in (("shortDescription", 80), ("fullDescription", 4000)):
        text = listing.get(field)
        if not isinstance(text, str) or not text.strip() or len(text) > maximum:
            raise PublishError(f"The live English listing is missing a valid {field}.")
    if any(listing.get(field) != value for field, value in approved_listing.items()):
        raise PublishError("The live English listing differs from the approved local listing copy.")
    for kind in IMAGE_TYPES:
        wanted = [asset["sha256"] for asset in assets if asset["image_type"] == kind]
        if state["imageHashes"].get(kind) != wanted:
            raise PublishError("Live store artwork does not match the locally approved image hashes and order.")
    if len(state["imageHashes"].get("phoneScreenshots", [])) < 2:
        raise PublishError("At least two actual app screenshots must already be configured on Play.")
    for name, track in state["tracks"].items():
        releases = track.get("releases", [])
        if not isinstance(releases, list):
            raise PublishError("Google returned invalid release records.")
        for release in releases:
            if any(code > VERSION_CODE for code in version_codes(release)):
                raise PublishError("A track already contains a newer version; refusing to promote the older candidate.")
            if name == "production" and release.get("status") in {"inProgress", "halted"}:
                raise PublishError("Production has an in-progress or halted rollout; reconcile it before promotion.")
    internal = state["tracks"].get("internal", {}).get("releases", [])
    if not any(release.get("status") == "completed" and VERSION_CODE in version_codes(release) for release in internal):
        raise PublishError("Internal testing must already contain completed version 27 before production promotion.")
    approved = []
    for bundle in state["bundles"]:
        try:
            code = int(bundle["versionCode"])
        except (KeyError, TypeError, ValueError):
            raise PublishError("Google returned an invalid bundle version code.") from None
        if code > VERSION_CODE:
            raise PublishError("A newer bundle is already uploaded; reconcile the release candidate before promotion.")
        if code == VERSION_CODE:
            approved.append(bundle)
    if len(approved) != 1 or str(approved[0].get("sha256", "")).lower() != AAB_SHA256:
        raise PublishError("Google Play's version 27 bundle does not match the approved AAB SHA256.")
    return approved[0]


def verify_unchanged(before: dict, after: dict) -> None:
    other_before = {key: value for key, value in before["tracks"].items() if key != "production"}
    other_after = {key: value for key, value in after["tracks"].items() if key != "production"}
    if other_before != other_after:
        raise PublishError("A non-production track changed during promotion; no such mutations were sent.")
    for field in ("listings", "details", "imageHashes"):
        if before[field] != after[field]:
            raise PublishError("Store metadata changed during promotion; reconcile external changes.")


def lifecycle_status(payload: dict) -> str:
    states = set()
    releases = payload.get("releases", [])
    if not isinstance(releases, list):
        return "availability_unconfirmed"
    for release in releases:
        if not isinstance(release, dict) or release.get("track") != "production":
            continue
        artifacts = release.get("activeArtifacts", [])
        if not isinstance(artifacts, list):
            continue
        try:
            codes = {int(item["versionCode"]) for item in artifacts if isinstance(item, dict)}
        except (KeyError, TypeError, ValueError):
            continue
        if VERSION_CODE in codes and not any(code > VERSION_CODE for code in codes):
            state = LIFECYCLE_STATES.get(release.get("releaseLifecycleState"))
            if state:
                states.add(state)
    # Conflicting lifecycle records need reconciliation rather than optimism.
    return states.pop() if len(states) == 1 else "availability_unconfirmed"


def production_lifecycle_once(api: API, report: dict) -> dict:
    if report.get("lifecycleRequested"):
        return report.get("productionLifecycle", {})
    report["lifecycleRequested"] = True
    try:
        payload = api.request("GET", f"{APP_PATH}/tracks/production/releases")
        report["productionLifecycle"] = payload
        return payload
    except Exception as error:
        report["productionLifecycleError"] = safe_error(error)
        return {}


def availability_report(api: API, report: dict) -> None:
    report["phase"] = "read_production_lifecycle"
    status = lifecycle_status(production_lifecycle_once(api, report))
    report["availability"] = status
    if status == "availability_unconfirmed":
        report["status"] = "committed_availability_unconfirmed" if report.get("committed") else "production_availability_unconfirmed"
    else:
        report["status"] = status


def verify_fresh(api: API, before: dict, assets: list[dict], approved_listing: dict, report: dict) -> None:
    edit_id = api.create_edit()
    report["verificationEditId"] = edit_id
    # A successful fresh edit invalidates any older edit owned by this account.
    report["editMayBePending"] = False
    try:
        after = read_snapshot(api, edit_id)
        report["after"] = after
        validate_ready(after, assets, approved_listing)
        verify_unchanged(before, after)
        if not production_complete(after):
            raise PublishError("Production did not confirm completed version 27 after the commit attempt.")
        report.update(trackVerified=True, otherTracksUnchanged=True,
                      verifiedVersionCode=VERSION_CODE, verifiedSha256=AAB_SHA256)
    finally:
        try:
            api.delete_edit(edit_id)
            report["verificationEditDeleted"] = True
        except Exception as error:
            report["verificationCleanupError"] = safe_error(error)


def promote(api: API, assets: list[dict], report: dict, validate_only: bool = False,
            approved_listing: dict | None = None) -> None:
    if approved_listing is None:
        approved_listing = load_listing(DEFAULT_LISTING)
    report["phase"] = "create_edit"
    edit_id = api.create_edit()
    report.update(editId=edit_id, editMayBePending=True)
    try:
        report["phase"] = "inspect_existing_state"
        before = read_snapshot(api, edit_id)
        report["before"] = before
        target = validate_ready(before, assets, approved_listing)
        report["reusedBundle"] = target
        prefix = f"{APP_PATH}/edits/{edit_id}"
        if production_complete(before):
            report.update(alreadyPresent=True, trackVerified=True, otherTracksUnchanged=True,
                          verifiedVersionCode=VERSION_CODE, verifiedSha256=AAB_SHA256)
            if validate_only:
                report["phase"] = "validate"
                api.request("POST", prefix + ":validate")
                report.update(validated=True, status="validated_not_committed")
            else:
                availability_report(api, report)
            return
        report["phase"] = "stage_production"
        release = {"track": "production", "releases": [{
            "name": "Read Assist 0.3.1", "versionCodes": [str(VERSION_CODE)], "status": "completed",
            "releaseNotes": [{"language": LANGUAGE, "text": RELEASE_NOTES}],
        }]}
        if len(RELEASE_NOTES) > 500:
            raise PublishError("Production release notes exceed the Play limit.")
        report["requestedRelease"] = release
        api.request("PUT", prefix + "/tracks/production", json=release)
        pending = read_snapshot(api, edit_id)
        validate_ready(pending, assets, approved_listing)
        verify_unchanged(before, pending)
        if not production_complete(pending):
            raise PublishError("The staged production edit does not contain the exact completed release.")
        report["phase"] = "validate"
        api.request("POST", prefix + ":validate")
        report["validated"] = True
        if validate_only:
            report["status"] = "validated_not_committed"
            return
        report["phase"] = "commit"
        report["commitAttempted"] = True
        try:
            api.request("POST", prefix + ":commit", params={"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"})
            report.update(commitAccepted=True, committed=True, editMayBePending=False)
        except Exception as error:
            ambiguous = not isinstance(error, ApiError) or error.http_code == 0 or error.http_code >= 500 or 200 <= error.http_code < 300
            if not ambiguous:
                report["status"] = "commit_rejected"
                raise
            report["commitError"] = safe_error(error)
            report["status"] = "commit_outcome_unknown"
            report["reconciliationRequired"] = True
            state = lifecycle_status(production_lifecycle_once(api, report))
            if state not in {"published", "in_review", "not_sent_for_review", "approved_not_published"}:
                # Opening a new edit would invalidate the potentially pending one.
                # Without independent confirmation, retain it and never retry.
                raise PublishError("Production commit outcome remains unknown; edit retained and no commit retried.") from None
            report["commitReconciliationLifecycle"] = state
        report["phase"] = "verify_committed_state"
        verify_fresh(api, before, assets, approved_listing, report)
        if report.get("reconciliationRequired"):
            report.update(committed=True, commitReconciled=True, reconciliationRequired=False, editMayBePending=False)
        availability_report(api, report)
    finally:
        if validate_only or not report.get("commitAttempted"):
            try:
                api.delete_edit(edit_id)
                report.update(ownedEditDeleted=True, editMayBePending=False)
            except Exception as error:
                report["ownedEditCleanupError"] = safe_error(error)
                if validate_only:
                    report["status"] = "validation_cleanup_failed"


def run(assets_dir: Path, report_path: Path = DEFAULT_REPORT, validate_only: bool = False,
        listing_file: Path = DEFAULT_LISTING) -> dict:
    assets_dir, report_path, listing_file = Path(assets_dir), Path(report_path), Path(listing_file)
    report = {"action": "validate_production" if validate_only else "promote_production",
              "package": PACKAGE, "track": "production", "versionName": VERSION_NAME,
              "versionCode": VERSION_CODE, "aabSha256": AAB_SHA256,
              "status": "started", "phase": "validate_local_assets", "validateOnly": validate_only,
              "committed": False, "trackVerified": False, "testersModified": False,
              "policyDeclarationsModified": False, "startedAt": datetime.now(timezone.utc).isoformat()}
    try:
        assets = load_assets(assets_dir)
        approved_listing = load_listing(listing_file)
        report["manifestSha256"] = digest((assets_dir / "manifest.json").read_bytes())
        report["listingSha256"] = digest(listing_file.read_bytes())
        report["phase"] = "authenticate"
        promote(API(authenticate()), assets, report, validate_only, approved_listing)
    except Exception as error:
        if report["status"] not in {"commit_outcome_unknown", "commit_rejected", "validation_cleanup_failed"}:
            report["status"] = "committed_verification_failed" if report["committed"] else "failed"
        report["error"] = safe_error(error)
    report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    report = clean_report(report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--listing", type=Path, default=DEFAULT_LISTING)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    report = run(args.assets, args.report, args.validate_only, args.listing)
    print(json.dumps({"status": report["status"], "phase": report["phase"], "report": str(args.report),
                      "committed": report["committed"], "trackVerified": report["trackVerified"],
                      "error": report.get("error")}))
    good = {"published", "in_review", "not_sent_for_review", "approved_not_published",
            "committed_availability_unconfirmed", "production_availability_unconfirmed", "validated_not_committed"}
    return 0 if report["status"] in good else 1


if __name__ == "__main__":
    sys.exit(main())
