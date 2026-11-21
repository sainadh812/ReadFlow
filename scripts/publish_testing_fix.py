#!/usr/bin/env python3
"""Replace code 30 with the pinned 0.3.5 policy fix on alpha and internal only.

One edit owns both track changes. No production, listing, tester, account, or
declaration writes are made. --validate-only stages and validates the edit
(including an upload if needed), then deletes it without committing.
"""
from __future__ import annotations

import argparse
import copy
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys

from inspect_play_store import clean_report
import publish_play as shared
from publish_play import API, ApiError, PublishError, authenticate, safe_error

TARGETS = ("alpha", "internal")
VERSION_CODE = 31
VERSION_NAME = "0.3.5"
PREVIOUS_CODE = 30
DEFAULT_REPORT = Path("build/play-testing-fix-0.3.5/report.json")
RELEASE_NOTES = (
    "Removed the optional tipping button and all payment links. Reading features remain free. "
    "Play now opens voice setup when required speech or word-alignment files are missing."
)
STATE_PREFIX = "RELEASE_LIFECYCLE_STATE_"
KNOWN_STATES = {
    STATE_PREFIX + name for name in (
        "PUBLISHED", "IN_REVIEW", "DRAFT", "NOT_SENT_FOR_REVIEW", "APPROVED_NOT_PUBLISHED", "NOT_APPROVED"
    )
}


def version(value: object) -> int:
    if isinstance(value, bool) or not re.fullmatch(r"[1-9][0-9]*", str(value)):
        raise PublishError("Google returned an invalid version code.")
    return int(value)


def normalized_tracks(tracks: list[dict]) -> dict:
    if not isinstance(tracks, list):
        raise PublishError("Google returned an invalid tracks list.")
    result = {}
    for track in tracks:
        name = track.get("track") if isinstance(track, dict) else None
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9:_-]+", name) or name in result:
            raise PublishError("Google returned invalid or duplicate track records.")
        item = copy.deepcopy(track)
        releases = item.get("releases", [])
        if not isinstance(releases, list) or any(not isinstance(r, dict) for r in releases):
            raise PublishError("Google returned invalid release records.")
        for release in releases:
            values = release.get("versionCodes", [])
            if not isinstance(values, list):
                raise PublishError("Google returned invalid release version codes.")
            release["versionCodes"] = sorted(str(version(value)) for value in values)
        item["releases"] = sorted(releases, key=lambda value: json.dumps(value, sort_keys=True))
        result[name] = item
    return result


def validate_state(tracks: list[dict], bundles: list[dict]) -> dict | None:
    indexed = normalized_tracks(tracks)
    for track in indexed.values():
        for release in track["releases"]:
            if any(version(code) > VERSION_CODE for code in release["versionCodes"]):
                raise PublishError("A track contains a newer version; existing work was preserved.")
    for name in TARGETS:
        track = indexed.get(name)
        if track is None or len(track["releases"]) != 1:
            raise PublishError(f"{name} must already contain exactly one completed release.")
        release = track["releases"][0]
        if set(track) - {"track", "releases"} or set(release) - {"name", "versionCodes", "releaseNotes", "status"}:
            raise PublishError(f"{name} has unexpected release metadata or rollout targeting; reconcile it first.")
        if release.get("status") != "completed" or release["versionCodes"] not in ([str(PREVIOUS_CODE)], [str(VERSION_CODE)]):
            raise PublishError(f"{name} must contain a single completed code {PREVIOUS_CODE} or {VERSION_CODE}; no rollout will be replaced.")
    if not isinstance(bundles, list) or any(not isinstance(bundle, dict) for bundle in bundles):
        raise PublishError("Google returned an invalid bundle list.")
    seen, target = set(), None
    for bundle in bundles:
        code = version(bundle.get("versionCode"))
        if code in seen:
            raise PublishError("Google returned duplicate bundle records.")
        seen.add(code)
        if code > VERSION_CODE:
            raise PublishError("A newer bundle already exists; existing work was preserved.")
        if code == VERSION_CODE:
            if bundle.get("sha256", "").lower() != shared.AAB_SHA256:
                raise PublishError("Existing version 31 has a different SHA256; no upload will be attempted.")
            target = bundle
    if target is None and any(indexed[name]["releases"][0]["versionCodes"] == [str(VERSION_CODE)] for name in TARGETS):
        raise PublishError("A target track references code 31 without a verifiable bundle hash.")
    return target


def lifecycle_states(lifecycles: dict, tracks: list[dict], *, block_review: bool) -> dict[str, set[str]]:
    states = {name: set() for name in TARGETS}
    if not isinstance(lifecycles, dict):
        raise PublishError("Release lifecycle inspection was unavailable.")
    for name in normalized_tracks(tracks):
        value = lifecycles.get(name)
        if not isinstance(value, dict) or "unavailable" in value:
            raise PublishError("Release lifecycle inspection was unavailable; no review will be overwritten.")
        releases = value.get("releases", [])
        if not isinstance(releases, list):
            raise PublishError("Google returned invalid lifecycle records.")
        for release in releases:
            if not isinstance(release, dict) or release.get("releaseLifecycleState") not in KNOWN_STATES:
                raise PublishError("Google returned an unknown release lifecycle state.")
            state = release["releaseLifecycleState"]
            if block_review and state == STATE_PREFIX + "IN_REVIEW":
                raise PublishError("Google Play has changes in review; the existing review was preserved.")
            artifacts = release.get("activeArtifacts", [])
            if not isinstance(artifacts, list) or any(not isinstance(a, dict) for a in artifacts):
                raise PublishError("Google returned invalid lifecycle artifacts.")
            codes = {version(a.get("versionCode")) for a in artifacts}
            if any(code > VERSION_CODE for code in codes):
                raise PublishError("A lifecycle contains a newer version; existing work was preserved.")
            if name in TARGETS and VERSION_CODE in codes:
                states[name].add(state)
    return states


def read_metadata(api: API, edit_id: str) -> dict:
    prefix = f"{shared.APP_PATH}/edits/{edit_id}"
    details = api.request("GET", prefix + "/details")
    listings = api.request("GET", prefix + "/listings").get("listings", [])
    if not isinstance(details, dict) or not isinstance(listings, list):
        raise PublishError("Google returned invalid app details or listings.")
    values = {}
    for listing in listings:
        name = listing.get("language") if isinstance(listing, dict) else None
        if not isinstance(name, str) or name in values:
            raise PublishError("Google returned invalid or duplicate listings.")
        values[name] = listing
    return {"details": details, "listings": values}


def cleanup(api: API, edit_id: str, report: dict, key: str) -> None:
    try:
        api.delete_edit(edit_id)
        report[key + "Deleted"] = True
        if key == "edit":
            report["editMayBePending"] = False
    except Exception as error:
        report[key + "CleanupError"] = safe_error(error)


def review_flag_required(error: ApiError) -> bool:
    return error.http_code == 400 and bool(re.search(
        r"changes cannot be sent for review automatically", error.message, re.I
    )) and bool(re.search(r"changesNotSentForReview\s+to\s+true", error.message, re.I))


def ambiguous(error: ApiError) -> bool:
    return error.http_code == 0 or error.http_code >= 500 or error.status == "INVALID_RESPONSE"


def record_status(report: dict, lifecycles: dict, tracks: list[dict]) -> None:
    states = lifecycle_states(lifecycles, tracks, block_review=False)
    per_track = {}
    for name in TARGETS:
        per_track[name] = (next(iter(states[name])).removeprefix(STATE_PREFIX).lower()
                           if len(states[name]) == 1 else "availability_unconfirmed")
    report["trackStatuses"] = per_track
    if report.get("changesNotSentForReview"):
        report["status"] = "not_sent_for_review"
    elif per_track["alpha"] != "availability_unconfirmed":
        report["status"] = per_track["alpha"]
    else:
        report["status"] = "committed_availability_unconfirmed"
    report["reviewConfirmed"] = per_track["alpha"] == "in_review"


def execute(report: dict, directory: Path, validate_only: bool = False) -> None:
    if shared.PACKAGE != "app.readflow" or shared.APP_PATH != "/androidpublisher/v3/applications/app.readflow":
        raise PublishError("The publishing package does not match Read Assist.")
    if shared.VERSION_CODE != VERSION_CODE or shared.VERSION_NAME != VERSION_NAME:
        raise PublishError("Shared publishing pins must identify Read Assist 0.3.5/code 31 before this command can run.")
    expected_url = "https://github.com/sainadh812/ReadFlow/releases/download/v0.3.5-play-candidate/Read-Assist-0.3.5-upload-signed.aab"
    if shared.AAB_URL != expected_url:
        raise PublishError("The approved artifact URL does not match the 0.3.5 release.")
    shared.validate_artifact_pin(shared.AAB_SHA256, shared.AAB_BYTES)
    artifact = shared.download_aab(directory)
    report["artifactVerified"] = True
    api = API(authenticate())
    edit_id = api.create_edit()
    report.update(editId=edit_id, editMayBePending=True)
    commit_unknown = False
    committed = False
    try:
        tracks, bundles = api.read_state(edit_id)
        target = validate_state(tracks, bundles)
        lifecycles = api.lifecycles(tracks)
        before_states = lifecycle_states(lifecycles, tracks, block_review=False)
        metadata = read_metadata(api, edit_id)
        report["before"] = {"tracks": tracks, "bundles": bundles, "lifecycles": lifecycles, **metadata}
        indexed = normalized_tracks(tracks)
        if target and all(indexed[name]["releases"][0]["versionCodes"] == [str(VERSION_CODE)] for name in TARGETS):
            report.update(alreadyCommitted=True, verifiedVersionCode=VERSION_CODE, verifiedSha256=shared.AAB_SHA256)
            record_status(report, lifecycles, tracks)
            return
        lifecycle_states(lifecycles, tracks, block_review=True)
        if target is None:
            report["uploadAttempted"] = True
            try:
                target = api.upload(edit_id, artifact)
            except ApiError as error:
                report["uploadError"] = safe_error(error)
                # The request may have succeeded. Inspect once; never upload twice.
                now_tracks, now_bundles = api.read_state(edit_id)
                if normalized_tracks(now_tracks) != indexed:
                    raise PublishError("Track state changed while reconciling the upload.") from None
                target = validate_state(now_tracks, now_bundles)
                if target is None:
                    raise error
                report["uploadReconciled"] = True
            if version(target.get("versionCode")) != VERSION_CODE or target.get("sha256", "").lower() != shared.AAB_SHA256:
                raise PublishError("Uploaded bundle identity does not match the pinned 0.3.5 artifact.")
            report["uploadedBundle"] = target
        else:
            report["reusedBundle"] = target
        prefix = f"{shared.APP_PATH}/edits/{edit_id}"
        expected = copy.deepcopy(indexed)
        for name in TARGETS:
            release = expected[name]["releases"][0]
            release.update(name=f"Read Assist {VERSION_NAME}", versionCodes=[str(VERSION_CODE)], status="completed",
                           releaseNotes=[{"language": "en-US", "text": RELEASE_NOTES}])
            api.request("PUT", prefix + "/tracks/" + name, json=expected[name])
        staged_tracks, staged_bundles = api.read_state(edit_id)
        validate_state(staged_tracks, staged_bundles)
        if normalized_tracks(staged_tracks) != expected or read_metadata(api, edit_id) != metadata:
            raise PublishError("Staged tracks or store metadata differ from the requested edit; refusing to commit.")
        api.request("POST", prefix + ":validate")
        report["validated"] = True
        if validate_only:
            report["status"] = "validated_not_committed"
            return
        lifecycle_states(api.lifecycles(tracks), tracks, block_review=True)
        params = {"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"}
        for attempt in range(2):
            try:
                report["commit"] = api.request("POST", prefix + ":commit", params=params.copy())
                committed = True
                report.update(commitAccepted=True, committed=True, editMayBePending=False)
                break
            except Exception as error:
                if isinstance(error, ApiError) and attempt == 0 and review_flag_required(error):
                    report["automaticReviewRestriction"] = safe_error(error)
                    params["changesNotSentForReview"] = True
                    report["changesNotSentForReview"] = True
                    continue
                if isinstance(error, ApiError) and not ambiguous(error):
                    raise
                commit_unknown = True
                report["commitAmbiguous"] = safe_error(error)
                report["committed"] = None
                current = api.lifecycles(tracks)
                report["commitReconciliationLifecycle"] = current
                observed = lifecycle_states(current, tracks, block_review=False)
                # Opening another edit could invalidate the uncertain original.
                # Only do so when non-edit endpoints prove both target releases.
                definite = all(len(observed[name]) == 1 and STATE_PREFIX + "DRAFT" not in observed[name]
                               for name in TARGETS)
                changed = any(observed[name] != before_states[name] for name in TARGETS)
                if not definite or not changed:
                    raise PublishError("Commit outcome remains unknown; edit retained and commit was not retried.") from None
                committed = True
                commit_unknown = False
                report.update(commitReconciled=True, committed=True, editMayBePending=False)
                break
        fresh_id = api.create_edit()
        report["verificationEditId"] = fresh_id
        try:
            after_tracks, after_bundles = api.read_state(fresh_id)
            after_metadata = read_metadata(api, fresh_id)
            after_lifecycles = api.lifecycles(after_tracks)
            report["after"] = {"tracks": after_tracks, "bundles": after_bundles,
                               "lifecycles": after_lifecycles, **after_metadata}
            final = validate_state(after_tracks, after_bundles)
            after_indexed = normalized_tracks(after_tracks)
            if {key: value for key, value in indexed.items() if key not in TARGETS} != {
                key: value for key, value in after_indexed.items() if key not in TARGETS
            }:
                raise PublishError("Other tracks changed during publication; this command did not mutate them.")
            if after_metadata != metadata:
                raise PublishError("Store metadata changed during publication; this command did not mutate it.")
            if final is None or after_indexed != expected:
                raise PublishError("The exact target releases and bundle were not confirmed after commit; no commit was retried.")
            report.update(otherTracksUnchanged=True, storeMetadataUnchanged=True,
                          verifiedVersionCode=VERSION_CODE, verifiedSha256=final["sha256"])
            record_status(report, after_lifecycles, after_tracks)
        finally:
            cleanup(api, fresh_id, report, "verificationEdit")
    finally:
        if not committed and not commit_unknown:
            cleanup(api, edit_id, report, "edit")


def run(report_path: Path = DEFAULT_REPORT, validate_only: bool = False) -> dict:
    report_path = Path(report_path)
    report = {"action": "validate_testing_fix" if validate_only else "publish_testing_fix",
              "package": shared.PACKAGE, "versionName": VERSION_NAME, "versionCode": VERSION_CODE,
              "tracks": list(TARGETS), "aabSha256": shared.AAB_SHA256, "aabBytes": shared.AAB_BYTES,
              "status": "started", "committed": False, "testersModified": False, "declarationsModified": False,
              "startedAt": datetime.now(timezone.utc).isoformat()}
    try:
        execute(report, report_path.parent, validate_only)
    except Exception as error:
        report["status"] = "commit_outcome_unknown" if report.get("commitAmbiguous") and not report.get("commitReconciled") else "failed"
        report["error"] = safe_error(error)
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        report = clean_report(report)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, indent=2) + "\n")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()
    report = run(args.report, args.validate_only)
    print(json.dumps({"status": report["status"], "report": str(args.report),
                      "trackStatuses": report.get("trackStatuses"), "error": report.get("error")}))
    return 1 if report["status"] in {"failed", "commit_outcome_unknown", "not_approved"} else 0


if __name__ == "__main__":
    sys.exit(main())
