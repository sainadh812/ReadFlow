#!/usr/bin/env python3
"""Commit verified English Play listing artwork without changing any release.

The service-account credential stays in memory using publish_play's pinned
identity and Google endpoint rules. This command never changes release tracks,
testers, declarations, prices, countries, or another language's listing.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
from datetime import datetime, timezone
import zlib

from inspect_play_store import clean_report
from publish_play import API, APP_PATH, PACKAGE, ApiError, PublishError, authenticate, safe_error

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REPORT = Path("build/play-store-publication/report.json")
LANGUAGE = "en-US"
IMAGE_TYPES = ("icon", "featureGraphic", "phoneScreenshots")
LISTING_FIELDS = {"language", "title", "shortDescription", "fullDescription", "video"}
SHA256 = re.compile(r"[0-9a-f]{64}")


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text())
    except (OSError, ValueError):
        raise PublishError("A required local JSON file could not be read.") from None
    if not isinstance(value, dict):
        raise PublishError("A required local JSON file must contain an object.")
    return value


def verified_source(source: dict) -> None:
    if not isinstance(source, dict) or not isinstance(source.get("path"), str):
        raise PublishError("An image provenance entry is invalid.")
    path = (ROOT / source["path"]).resolve()
    if not path.is_relative_to(ROOT.resolve()):
        raise PublishError("Image provenance escapes the checked-out repository.")
    if not SHA256.fullmatch(str(source.get("sha256", ""))):
        raise PublishError("Image provenance requires a SHA256 hash.")
    if not path.is_file() or digest(path.read_bytes()) != source["sha256"]:
        raise PublishError("A source image, font, vector, or palette does not match its provenance hash.")


def png_info(data: bytes) -> tuple[int, int, str]:
    """Validate PNG structure/CRCs and read true dimensions without Pillow."""
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise PublishError("Only verified PNG assets are accepted.")
    offset, info, has_data, ended = 8, None, False, False
    while offset < len(data):
        if offset + 12 > len(data):
            raise PublishError("Truncated PNG asset.")
        size = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + size
        if end > len(data):
            raise PublishError("Truncated PNG chunk.")
        payload = data[offset + 8:offset + 8 + size]
        crc = struct.unpack(">I", data[offset + 8 + size:end])[0]
        if zlib.crc32(kind + payload) & 0xFFFFFFFF != crc:
            raise PublishError("PNG chunk checksum mismatch.")
        if offset == 8 and kind != b"IHDR":
            raise PublishError("PNG must start with IHDR.")
        if kind == b"IHDR":
            if info is not None or size != 13:
                raise PublishError("Invalid PNG header.")
            width, height, bits, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            if bits != 8 or color not in (2, 6) or compression or filtering or interlace not in (0, 1):
                raise PublishError("PNG must be 8-bit RGB or RGBA.")
            info = (width, height, "RGB" if color == 2 else "RGBA")
        elif kind == b"IDAT":
            has_data = has_data or size > 0
        elif kind == b"acTL" or kind == b"tRNS":
            raise PublishError("Animated PNGs and transparency outside RGBA are not accepted.")
        elif kind == b"IEND":
            if size or end != len(data):
                raise PublishError("PNG has invalid trailing data.")
            ended = True
            break
        offset = end
    if not info or not has_data or not ended:
        raise PublishError("Incomplete PNG asset.")
    return info


def load_assets(directory: Path) -> list[dict]:
    directory = directory.resolve()
    manifest = read_json(directory / "manifest.json")
    if manifest.get("app_package") != PACKAGE or manifest.get("app_name") != "Read Assist" or manifest.get("schema_version") != 1:
        raise PublishError("Store manifest is not the approved Read Assist package/schema.")
    generator = {"path": manifest.get("generator"), "sha256": manifest.get("generator_sha256")}
    verified_source(generator)
    records = manifest.get("assets")
    if not isinstance(records, list) or not records:
        raise PublishError("Store manifest has no image assets.")
    result, names, hashes = [], set(), set()
    counts = {kind: 0 for kind in IMAGE_TYPES}
    for record in records:
        if not isinstance(record, dict) or record.get("image_type") not in IMAGE_TYPES:
            raise PublishError("Store manifest contains an unsupported image type.")
        name = record.get("file")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+\.png", name) or name in names:
            raise PublishError("Asset names must be unique PNG basenames.")
        names.add(name)
        path = (directory / name).resolve()
        if not path.is_relative_to(directory) or not path.is_file():
            raise PublishError("Store asset is missing or escapes the asset directory.")
        data = path.read_bytes()
        if not 0 < len(data) <= 15 * 1024 * 1024 or len(data) != record.get("bytes"):
            raise PublishError("Store asset file size does not match its manifest.")
        sha = digest(data)
        if not SHA256.fullmatch(str(record.get("sha256", ""))) or sha != record["sha256"]:
            raise PublishError("Store asset SHA256 does not match its manifest.")
        if sha in hashes:
            raise PublishError("Duplicate store artwork is not accepted.")
        hashes.add(sha)
        width, height, mode = png_info(data)
        if (width, height, mode) != (record.get("width"), record.get("height"), record.get("mode")):
            raise PublishError("Actual PNG dimensions or color mode differ from the manifest.")
        kind = record["image_type"]
        required = {"icon": (512, 512, "RGBA"), "featureGraphic": (1024, 500, "RGB"),
                    "phoneScreenshots": (1080, 1920, "RGB")}[kind]
        if (width, height, mode) != required or (kind == "icon" and len(data) > 1024 * 1024):
            raise PublishError("An image does not meet the approved Play artwork dimensions or format.")
        sources = record.get("sources")
        if not isinstance(sources, list) or not sources:
            raise PublishError("Store artwork requires source provenance.")
        for source in sources:
            verified_source(source)
        if kind == "phoneScreenshots" and not any(source.get("kind") == "caller_supplied_app_capture" for source in sources):
            raise PublishError("Screenshots require a verified original app capture, not generated UI.")
        counts[kind] += 1
        result.append({"path": path, "file": name, "image_type": kind, "sha256": sha, "bytes": len(data)})
    if counts["icon"] != 1 or counts["featureGraphic"] != 1 or not 2 <= counts["phoneScreenshots"] <= 8:
        raise PublishError("The media set requires one icon, one feature graphic, and two to eight real screenshots.")
    if manifest.get("phone_screenshot_count") != counts["phoneScreenshots"]:
        raise PublishError("Screenshot count differs from the manifest.")
    return result


def load_listing(path: Path) -> dict:
    listing = read_json(path)
    if set(listing) - LISTING_FIELDS or listing.get("language", LANGUAGE) != LANGUAGE:
        raise PublishError("Only supported en-US listing fields may be changed.")
    if listing.get("title") != "Read Assist":
        raise PublishError("Public title must remain Read Assist.")
    for field, maximum in (("shortDescription", 80), ("fullDescription", 4000)):
        value = listing.get(field)
        if not isinstance(value, str) or not value.strip() or len(value) > maximum:
            raise PublishError(f"Listing {field} must contain 1–{maximum} characters.")
    if "video" in listing and (not isinstance(listing["video"], str) or not re.fullmatch(
        r"https://(?:(?:www\.)?youtube\.com/watch\?v=|youtu\.be/)[A-Za-z0-9_-]{11}", listing["video"]
    )):
        raise PublishError("A preview video must be an HTTPS YouTube video URL without extra parameters.")
    return {**listing, "language": LANGUAGE}


def normalized_tracks(tracks: list[dict]) -> dict:
    result = {}
    for track in tracks:
        if not isinstance(track, dict) or not isinstance(track.get("track"), str) or track["track"] in result:
            raise PublishError("Google returned invalid track records.")
        item = json.loads(json.dumps(track))
        for release in item.get("releases", []):
            release["versionCodes"] = sorted(release.get("versionCodes", []), key=int)
        item["releases"] = sorted(item.get("releases", []), key=lambda value: json.dumps(value, sort_keys=True))
        result[item["track"]] = item
    return result


def listing_map(values: list[dict]) -> dict:
    result = {}
    for listing in values:
        if not isinstance(listing, dict) or not isinstance(listing.get("language"), str) or listing["language"] in result:
            raise PublishError("Google returned invalid localized listings.")
        result[listing["language"]] = listing
    return result


def image_hashes(images: list[dict]) -> list[str]:
    hashes = []
    for image in images:
        value = str(image.get("sha256", "")).lower() if isinstance(image, dict) else ""
        if not SHA256.fullmatch(value):
            raise PublishError("Google did not provide a verifiable image SHA256.")
        hashes.append(value)
    if len(set(hashes)) != len(hashes):
        raise PublishError("Google contains duplicate images; reconcile the listing before publishing.")
    return hashes


def snapshot(api: API, edit_id: str) -> dict:
    prefix = f"{APP_PATH}/edits/{edit_id}"
    tracks = api.request("GET", prefix + "/tracks").get("tracks", [])
    listings = api.request("GET", prefix + "/listings").get("listings", [])
    if not isinstance(tracks, list) or not isinstance(listings, list):
        raise PublishError("Google returned an invalid store snapshot.")
    images = {}
    for kind in IMAGE_TYPES:
        values = api.request("GET", prefix + f"/listings/{LANGUAGE}/{kind}").get("images", [])
        if not isinstance(values, list):
            raise PublishError("Google returned invalid store images.")
        images[kind] = image_hashes(values)
    return {"tracks": normalized_tracks(tracks), "listings": listing_map(listings),
            "details": api.request("GET", prefix + "/details"), "imageHashes": images}


def publication_plan(before: dict, assets: list[dict], requested: dict) -> tuple[dict, list[dict]]:
    current = before["listings"].get(LANGUAGE, {})
    listing = {key: value for key, value in current.items() if key in LISTING_FIELDS}
    listing.update(requested)
    missing = []
    for kind in IMAGE_TYPES:
        wanted = [asset for asset in assets if asset["image_type"] == kind]
        desired = [asset["sha256"] for asset in wanted]
        existing = before["imageHashes"][kind]
        # Exact prefix permits a repeat or continuation without deleting images
        # or silently changing screenshot order. Unknown existing media conflicts.
        if existing != desired[:len(existing)]:
            raise PublishError(f"Existing {kind} conflicts with the approved assets; no images were replaced.")
        missing.extend(wanted[len(existing):])
    return listing, missing


def verify_snapshot(before: dict, after: dict, assets: list[dict], listing: dict) -> None:
    if before["tracks"] != after["tracks"]:
        raise PublishError("Release tracks changed during the media operation; reconcile external changes.")
    if before["details"] != after["details"]:
        raise PublishError("App contact details changed during the media operation.")
    other_before = {key: value for key, value in before["listings"].items() if key != LANGUAGE}
    other_after = {key: value for key, value in after["listings"].items() if key != LANGUAGE}
    if other_before != other_after:
        raise PublishError("Another language's listing changed during the media operation.")
    actual = after["listings"].get(LANGUAGE, {})
    if any(actual.get(key) != value for key, value in listing.items()):
        raise PublishError("English listing verification failed.")
    for kind in IMAGE_TYPES:
        expected = [asset["sha256"] for asset in assets if asset["image_type"] == kind]
        if after["imageHashes"][kind] != expected:
            raise PublishError("Fresh Google image hashes or screenshot order differ from the approved assets.")


def publish(api: API, assets: list[dict], requested: dict, report: dict) -> None:
    edit_id = api.create_edit()
    report["editId"] = edit_id
    report["editMayBePending"] = True
    changed = False
    try:
        before = snapshot(api, edit_id)
        report["before"] = before
        listing, missing = publication_plan(before, assets, requested)
        report["requestedListing"] = listing
        report["plannedUploads"] = [{key: asset[key] for key in ("file", "image_type", "sha256")} for asset in missing]
        prefix = f"{APP_PATH}/edits/{edit_id}"
        if listing != before["listings"].get(LANGUAGE, {}):
            changed = True
            api.request("PUT", prefix + f"/listings/{LANGUAGE}", json=listing)
        report["uploaded"] = []
        for asset in missing:
            changed = True
            with asset["path"].open("rb") as handle:
                response = api.request("POST", "/upload" + prefix + f"/listings/{LANGUAGE}/{asset['image_type']}",
                                       params={"uploadType": "media"}, data=handle,
                                       headers={"Content-Type": "image/png"})
            if image_hashes([response.get("image")]) != [asset["sha256"]]:
                raise PublishError("Uploaded image response has a different SHA256; edit was not committed.")
            report["uploaded"].append({"file": asset["file"], "sha256": asset["sha256"]})
        if not changed:
            verify_snapshot(before, before, assets, listing)
            report.update(status="already_matches", verified=True, committed=False)
            return
        pending = snapshot(api, edit_id)
        verify_snapshot(before, pending, assets, listing)
        api.request("POST", prefix + ":validate")
        report["validated"] = True
        report["commitAttempted"] = True
        try:
            api.request("POST", prefix + ":commit", params={"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"})
        except Exception as error:
            ambiguous = not isinstance(error, ApiError) or error.http_code == 0 or error.http_code >= 500 or 200 <= error.http_code < 300
            report["status"] = "commit_outcome_unknown" if ambiguous else "commit_rejected"
            report["reconciliationRequired"] = ambiguous
            raise
        report["committed"] = True
        report["editMayBePending"] = False
        report["status"] = "committed_verification_pending"
        verify_id = api.create_edit()
        report["verificationEditId"] = verify_id
        try:
            after = snapshot(api, verify_id)
            report["after"] = after
            verify_snapshot(before, after, assets, listing)
            report.update(status="committed_and_verified", verified=True)
        finally:
            try:
                api.delete_edit(verify_id)
                report["verificationEditDeleted"] = True
            except Exception as error:
                report["verificationCleanupError"] = safe_error(error)
    finally:
        # No pre-commit staging/upload failure can have changed the deployed
        # listing. Discard this owned edit instead of leaving partial artwork
        # pending. Never discard an edit after an ambiguous commit attempt.
        if not report.get("commitAttempted"):
            try:
                api.delete_edit(edit_id)
                report.update(ownedEditDeleted=True, editMayBePending=False)
                if not changed:
                    report["unmodifiedEditDeleted"] = True
            except Exception as error:
                report["cleanupError"] = safe_error(error)


def run(assets_dir: Path, listing_file: Path, report_path: Path = DEFAULT_REPORT) -> dict:
    report = {"action": "publish_store_media", "package": PACKAGE, "language": LANGUAGE,
              "status": "started", "committed": False, "verified": False,
              "productionRelease": "not_attempted", "storeReviewState": "not_determined_by_this_operation",
              "startedAt": datetime.now(timezone.utc).isoformat()}
    try:
        assets = load_assets(assets_dir)
        listing = load_listing(listing_file)
        report["manifestSha256"] = digest((assets_dir / "manifest.json").read_bytes())
        report["listingSha256"] = digest(listing_file.read_bytes())
        publish(API(authenticate()), assets, listing, report)
    except Exception as error:
        if report["status"] in {"started", "committed_verification_pending"}:
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
    parser.add_argument("--listing", type=Path, required=True)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()
    report = run(args.assets, args.listing, args.report)
    print(json.dumps({"status": report["status"], "report": str(args.report),
                      "committed": report["committed"], "verified": report["verified"],
                      "error": report.get("error")}))
    return 0 if report["status"] in {"already_matches", "committed_and_verified"} else 1


if __name__ == "__main__":
    sys.exit(main())
