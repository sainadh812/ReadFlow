#!/usr/bin/env python3
"""Inspect or publish the approved 0.3.1 bundle to Google Play Internal testing.

Credentials are consumed from the Actions secret in memory. This script never
changes testers, another track, signing settings, or the approved artifact.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from datetime import datetime, timezone

PACKAGE = "app.readflow"
VERSION_CODE = 27
VERSION_NAME = "0.3.1"
EXPECTED_PROJECT = "read-assist-fbd21"
EXPECTED_EMAIL = "play-publisher@read-assist-fbd21.iam.gserviceaccount.com"
AAB_BYTES = 72436099
AAB_SHA256 = "a247863362aec730e6c50ebe85a403de8cb572821276077d38a3c0b2f8e7e1f6"
AAB_URL = "https://github.com/sainadh812/ReadFlow/releases/download/v0.3.1-play-candidate/Read-Assist-0.3.1-upload-signed.aab"
DEFAULT_REPORT = Path("build/play-upload-0.3.1/report.json")
API_ROOT = "https://androidpublisher.googleapis.com"
APP_PATH = f"/androidpublisher/v3/applications/{PACKAGE}"
RELEASE_NOTES = (
    "The reader now has a Text/PDF switch in the bottom panel, with evenly spaced playback "
    "controls and a centered Play/Pause button. Use the up arrow or swipe up to open reader "
    "settings, and the down arrow to close them. Switching views keeps your reading position, "
    "PDF zoom and text scroll. Playback speed is easier to reach from the expanded controls."
)
_SECRETS: list[str] = []


class PublishError(Exception):
    """An error whose message contains only public, controlled information."""


def redact(value: str) -> str:
    text = str(value)
    for secret in sorted(_SECRETS, key=len, reverse=True):
        if secret:
            text = text.replace(secret, "[REDACTED]")
    if re.search(r"BEGIN .*PRIVATE KEY|private_key|Bearer\s|ya29\.", text, re.I):
        return "[Sensitive diagnostic omitted]"
    return text[:800]


class ApiError(PublishError):
    def __init__(self, http_code: int, status: str, message: str):
        self.http_code = http_code
        self.status = redact(status)
        self.message = redact(message)
        super().__init__(f"Google API {http_code} {self.status}: {self.message}")


def safe_error(error: Exception) -> dict:
    if isinstance(error, ApiError):
        return {"httpCode": error.http_code, "status": error.status, "message": error.message}
    if isinstance(error, PublishError):
        return {"status": "VALIDATION_FAILED", "message": redact(str(error))}
    return {"status": "UNEXPECTED_ERROR", "message": "Operation failed; raw diagnostics withheld."}


def mask_secret(value: str) -> None:
    if not value:
        return
    _SECRETS.extend([value, json.dumps(value)[1:-1]])
    _SECRETS.extend(line for line in value.splitlines() if len(line) > 20)
    if os.environ.get("GITHUB_ACTIONS") == "true":
        escaped = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::add-mask::{escaped}", flush=True)


def validate_credential_info(info: dict) -> dict:
    required = {
        "type": "service_account",
        "project_id": EXPECTED_PROJECT,
        "client_email": EXPECTED_EMAIL,
        "token_uri": "https://oauth2.googleapis.com/token",
    }
    if not isinstance(info, dict) or any(info.get(k) != v for k, v in required.items()):
        raise PublishError("Publishing credential identity or token endpoint does not match the approved account.")
    if info.get("universe_domain", "googleapis.com") != "googleapis.com":
        raise PublishError("Publishing credential universe domain is not googleapis.com.")
    if not isinstance(info.get("private_key"), str) or not info["private_key"].strip():
        raise PublishError("Publishing credential has no private key.")
    return info.copy()


def authenticate() -> str:
    raw = os.environ.pop("READFLOW_PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw:
        raise PublishError("READFLOW_PLAY_SERVICE_ACCOUNT_JSON is missing.")
    mask_secret(raw)
    try:
        info = json.loads(raw)
    except (ValueError, TypeError):
        raise PublishError("Publishing credential is not valid JSON.") from None
    info = validate_credential_info(info)
    mask_secret(info["private_key"])
    try:
        from google.oauth2 import service_account
        from google.auth.transport.requests import Request
        credentials = service_account.Credentials.from_service_account_info(
            info, scopes=["https://www.googleapis.com/auth/androidpublisher"]
        )
        credentials.refresh(Request())
        token = credentials.token
    except Exception:
        raise PublishError("Google service-account authentication failed; credential diagnostics withheld.") from None
    if not isinstance(token, str) or not token:
        raise PublishError("Google authentication returned no access token.")
    mask_secret(token)
    return token


def verify_aab(path: Path) -> str:
    path = Path(path)
    if path.stat().st_size != AAB_BYTES:
        raise PublishError("AAB size does not match the approved 0.3.1 artifact.")
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != AAB_SHA256:
        raise PublishError("AAB SHA256 does not match the approved 0.3.1 artifact.")
    return digest.hexdigest()


def download_aab(directory: Path) -> Path:
    import requests
    path = directory / "Read-Assist-0.3.1-upload-signed.aab"
    directory.mkdir(parents=True, exist_ok=True)
    with requests.Session() as session:
        session.trust_env = False
        try:
            with session.get(AAB_URL, stream=True, timeout=(20, 180)) as response:
                if response.status_code != 200:
                    raise PublishError(f"Approved GitHub AAB download returned HTTP {response.status_code}.")
                written = 0
                with path.open("wb") as target:
                    for block in response.iter_content(1024 * 1024):
                        written += len(block)
                        if written > AAB_BYTES:
                            raise PublishError("Downloaded AAB exceeds the approved artifact size.")
                        target.write(block)
        except requests.RequestException:
            raise PublishError("Approved GitHub AAB download failed.") from None
    verify_aab(path)
    return path


def internal_track(tracks: list[dict]) -> dict:
    return next((item for item in tracks if item.get("track") == "internal"), {"track": "internal", "releases": []})


def codes(release: dict) -> set[int]:
    return {int(code) for code in release.get("versionCodes", [])}


def validate_existing_state(tracks: list[dict], bundles: list[dict]) -> dict | None:
    releases = internal_track(tracks).get("releases", [])
    for release in releases:
        if any(code > VERSION_CODE for code in codes(release)):
            raise PublishError("Internal testing already contains a newer version; refusing to replace it.")
        if release.get("status") in {"inProgress", "halted"}:
            raise PublishError("Internal testing has an in-progress or halted release; reconcile it before publishing.")
    matches = [b for b in bundles if int(b.get("versionCode", -1)) == VERSION_CODE]
    if len(matches) > 1:
        raise PublishError("Google returned duplicate bundle records for version 27.")
    target = matches[0] if matches else None
    if target and target.get("sha256", "").lower() != AAB_SHA256:
        raise PublishError("Existing Google Play version 27 has a different SHA256; no upload will be attempted.")
    if target is None and any(VERSION_CODE in codes(release) for release in releases):
        raise PublishError("Internal version 27 exists but its bundle hash cannot be verified.")
    return target


def normalize_other_tracks(tracks: list[dict]) -> dict:
    result = {}
    for track in tracks:
        if track.get("track") == "internal":
            continue
        item = json.loads(json.dumps(track))
        for release in item.get("releases", []):
            release["versionCodes"] = sorted(release.get("versionCodes", []), key=int)
        item["releases"] = sorted(item.get("releases", []), key=lambda r: json.dumps(r, sort_keys=True))
        result[item["track"]] = item
    return result


def draft_required(error: Exception) -> bool:
    return isinstance(error, ApiError) and error.http_code == 400 and bool(re.search(
        r"only releases with status draft may be created on draft app", error.message, re.I
    ))


class API:
    def __init__(self, token: str):
        import requests
        self.session = requests.Session()
        self.session.trust_env = False
        self.session.headers.update({"Authorization": f"Bearer {token}", "Accept": "application/json"})

    def request(self, method: str, path: str, **kwargs) -> dict:
        import requests
        if not path.startswith((APP_PATH + "/", "/upload" + APP_PATH + "/")) or "?" in path or "#" in path:
            raise PublishError("Refusing an unexpected Google API endpoint.")
        kwargs.setdefault("timeout", (20, 180))
        try:
            response = self.session.request(method, API_ROOT + path, allow_redirects=False, **kwargs)
        except requests.RequestException:
            raise ApiError(0, "TRANSPORT_ERROR", "Google API outcome is unknown after a transport failure.") from None
        try:
            payload = response.json() if response.content else {}
        except ValueError:
            raise ApiError(response.status_code, "INVALID_RESPONSE", "Google returned a non-JSON response.") from None
        if not 200 <= response.status_code < 300:
            error = payload.get("error", {}) if isinstance(payload, dict) else {}
            raise ApiError(response.status_code, str(error.get("status", "HTTP_ERROR")), str(error.get("message", "Google API request failed.")))
        if not isinstance(payload, dict):
            raise ApiError(response.status_code, "INVALID_RESPONSE", "Google returned an unexpected response structure.")
        return payload

    def create_edit(self) -> str:
        result = self.request("POST", APP_PATH + "/edits", json={})
        edit_id = result.get("id", "")
        if not isinstance(edit_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", edit_id):
            raise PublishError("Google returned an invalid edit identifier.")
        return edit_id

    def read_state(self, edit_id: str) -> tuple[list[dict], list[dict]]:
        prefix = f"{APP_PATH}/edits/{edit_id}"
        tracks = self.request("GET", prefix + "/tracks").get("tracks", [])
        bundles = self.request("GET", prefix + "/bundles").get("bundles", [])
        return tracks, bundles

    def delete_edit(self, edit_id: str) -> None:
        self.request("DELETE", f"{APP_PATH}/edits/{edit_id}")

    def lifecycles(self, tracks: list[dict]) -> dict:
        result = {}
        names = {"internal"} | {track["track"] for track in tracks}
        for name in sorted(names):
            if not re.fullmatch(r"[A-Za-z0-9:_-]+", name):
                continue
            try:
                result[name] = self.request("GET", f"{APP_PATH}/tracks/{name}/releases")
            except ApiError as error:
                result[name] = {"unavailable": safe_error(error)}
        return result

    def upload(self, edit_id: str, path: Path) -> dict:
        with path.open("rb") as handle:
            return self.request("POST", f"/upload{APP_PATH}/edits/{edit_id}/bundles",
                                params={"uploadType": "media"}, data=handle,
                                headers={"Content-Type": "application/octet-stream"})


def lifecycle_status(lifecycles: dict) -> str:
    states = {
        release.get("releaseLifecycleState")
        for release in lifecycles.get("internal", {}).get("releases", [])
        if VERSION_CODE in {int(a["versionCode"]) for a in release.get("activeArtifacts", [])}
    }
    if "RELEASE_LIFECYCLE_STATE_PUBLISHED" in states:
        return "published"
    if "RELEASE_LIFECYCLE_STATE_IN_REVIEW" in states:
        return "in_review"
    if "RELEASE_LIFECYCLE_STATE_DRAFT" in states:
        return "draft"
    if "RELEASE_LIFECYCLE_STATE_NOT_SENT_FOR_REVIEW" in states:
        return "not_sent_for_review"
    if "RELEASE_LIFECYCLE_STATE_APPROVED_NOT_PUBLISHED" in states:
        return "approved_not_published"
    if "RELEASE_LIFECYCLE_STATE_NOT_APPROVED" in states:
        return "not_approved"
    return "committed_availability_unconfirmed"


def inspect_fresh(api: API, report: dict, key: str) -> tuple[list[dict], list[dict]]:
    edit_id = api.create_edit()
    report[key + "EditId"] = edit_id
    try:
        tracks, bundles = api.read_state(edit_id)
        report[key] = {"tracks": tracks, "bundles": bundles, "lifecycles": api.lifecycles(tracks)}
        return tracks, bundles
    finally:
        try:
            api.delete_edit(edit_id)
            report[key + "EditDeleted"] = True
        except ApiError as error:
            report[key + "EditCleanupError"] = safe_error(error)


def execute(action: str, report: dict, directory: Path) -> None:
    artifact = download_aab(directory) if action == "publish" else None
    if artifact:
        report["artifactVerified"] = True
    api = API(authenticate())
    if action == "inspect":
        inspect_fresh(api, report, "inspection")
        report["status"] = "inspected"
        return

    edit_id = api.create_edit()
    report["editId"] = edit_id
    report["editMayBePending"] = True
    tracks, bundles = api.read_state(edit_id)
    before = {"tracks": tracks, "bundles": bundles, "lifecycles": api.lifecycles(tracks)}
    report["before"] = before
    target = validate_existing_state(tracks, bundles)
    complete = any(VERSION_CODE in codes(r) and r.get("status") == "completed"
                   for r in internal_track(tracks).get("releases", []))
    if target and complete:
        api.delete_edit(edit_id)
        report.update(editMayBePending=False, alreadyCommitted=True,
                      status=lifecycle_status(before["lifecycles"]), after=before)
        return
    if any(release.get("releaseLifecycleState") == "RELEASE_LIFECYCLE_STATE_IN_REVIEW"
           for state in before["lifecycles"].values() for release in state.get("releases", [])):
        raise PublishError("Google Play already has changes in review; the existing review was preserved.")

    if target is None:
        report["uploadAttempted"] = True
        try:
            target = api.upload(edit_id, artifact)
        except ApiError as error:
            report["uploadError"] = safe_error(error)
            # Reconcile once, never repeat a potentially accepted upload.
            current_tracks, current_bundles = api.read_state(edit_id)
            target = validate_existing_state(current_tracks, current_bundles)
            if target is None:
                raise error
            report["uploadReconciled"] = True
        if int(target.get("versionCode", -1)) != VERSION_CODE or target.get("sha256", "").lower() != AAB_SHA256:
            raise PublishError("Google's uploaded bundle identity does not match the approved AAB.")
        report["uploadedBundle"] = target
    else:
        report["reusedBundle"] = target

    prefix = f"{APP_PATH}/edits/{edit_id}"
    release_status = "completed"

    def prepare(status: str) -> None:
        api.request("PUT", prefix + "/tracks/internal", json={"track": "internal", "releases": [{
            "name": "Read Assist 0.3.1", "versionCodes": [str(VERSION_CODE)], "status": status,
            "releaseNotes": [{"language": "en-US", "text": RELEASE_NOTES}],
        }]})
        api.request("POST", prefix + ":validate")

    try:
        prepare(release_status)
    except ApiError as error:
        if not draft_required(error):
            raise
        report["draftRestriction"] = safe_error(error)
        release_status = "draft"
        prepare(release_status)
    report["requestedReleaseStatus"] = release_status
    commit_params = {"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"}
    for commit_attempt in range(2):
        try:
            report["commit"] = api.request("POST", prefix + ":commit", params=commit_params)
            report["commitAccepted"] = True
            break
        except ApiError as error:
            if draft_required(error) and release_status != "draft" and commit_attempt == 0:
                report["draftRestriction"] = safe_error(error)
                release_status = "draft"
                prepare(release_status)
                report["requestedReleaseStatus"] = release_status
                # Only an explicit rejection permits another commit, after correction.
                continue
            if error.http_code == 0 or error.http_code >= 500 or error.status == "INVALID_RESPONSE":
                report["commitAmbiguous"] = safe_error(error)
                report["commitReconciliationLifecycle"] = api.lifecycles(tracks)
                # A new edit would invalidate the possibly pending edit. Only open
                # verification after the non-edit endpoint confirms version 27.
                state_now = lifecycle_status(report["commitReconciliationLifecycle"])
                state_before = lifecycle_status(before["lifecycles"])
                confirmed = state_now != "committed_availability_unconfirmed" and state_now != state_before
                if release_status == "completed" and state_now == "draft":
                    confirmed = False
                if not confirmed:
                    raise PublishError("Commit outcome remains unknown; edit retained for reconciliation and no commit retried.") from None
                break
            raise

    after_tracks, after_bundles = inspect_fresh(api, report, "after")
    report["editMayBePending"] = False
    if normalize_other_tracks(tracks) != normalize_other_tracks(after_tracks):
        raise PublishError("Other track state changed during publication; no other-track mutations were sent.")
    report["otherTracksUnchanged"] = True
    final_bundle = validate_existing_state(after_tracks, after_bundles)
    final_releases = internal_track(after_tracks).get("releases", [])
    if final_bundle is None or not any(VERSION_CODE in codes(r) and r.get("status") == release_status for r in final_releases):
        raise PublishError("Version 27 was not confirmed on Internal testing after the commit attempt; no commit was retried.")
    report["verifiedVersionCode"] = VERSION_CODE
    report["verifiedSha256"] = final_bundle["sha256"]
    report["status"] = "draft" if release_status == "draft" else lifecycle_status(report["after"]["lifecycles"])
    if "commitAmbiguous" in report:
        report["commitReconciled"] = True


def run(action: str, report_path: Path = DEFAULT_REPORT) -> dict:
    if action not in {"inspect", "publish"}:
        raise PublishError("Action must be inspect or publish.")
    report_path = Path(report_path)
    report = {"action": action, "package": PACKAGE, "versionName": VERSION_NAME,
              "versionCode": VERSION_CODE, "track": "internal", "aabSha256": AAB_SHA256,
              "aabBytes": AAB_BYTES, "status": "started", "testersModified": False,
              "startedAt": datetime.now(timezone.utc).isoformat()}
    try:
        execute(action, report, report_path.parent)
    except Exception as error:
        report["status"] = "failed"
        report["error"] = safe_error(error)
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        report_path.parent.mkdir(parents=True, exist_ok=True)
        # Defensive redaction also covers Google-provided release names/notes.
        def clean(value):
            if isinstance(value, str):
                return redact(value)
            if isinstance(value, dict):
                return {str(k): clean(v) for k, v in value.items()}
            if isinstance(value, list):
                return [clean(v) for v in value]
            return value
        report = clean(report)
        report_path.write_text(json.dumps(report, indent=2) + "\n")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["inspect", "publish"])
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()
    report = run(args.action, args.report)
    print(json.dumps({"status": report["status"], "report": str(args.report), "error": report.get("error")}))
    return 1 if report["status"] == "failed" else 0


if __name__ == "__main__":
    sys.exit(main())
