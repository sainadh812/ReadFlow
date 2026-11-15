"""Offline safety tests; synthetic image fixtures are never uploaded."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zlib

import publish_play
import publish_store_media as media
from publish_play import VERSION_CODE


def synthetic_png(width: int, height: int, alpha: bool = False, shade: int = 100) -> bytes:
    def chunk(kind: bytes, value: bytes) -> bytes:
        return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value) & 0xFFFFFFFF)
    header = struct.pack(">IIBBBBB", width, height, 8, 6 if alpha else 2, 0, 0, 0)
    row = b"\x00" + bytes([shade] * (4 if alpha else 3)) * width
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(row * height)) + chunk(b"IEND", b"")


class FakeAPI:
    """A transactional in-memory API; never makes a network request."""
    def __init__(self):
        self.live = {
            "tracks": [{"track": "internal", "releases": [{"status": "completed", "versionCodes": [str(VERSION_CODE)]}]},
                       {"track": "production", "releases": []}],
            "listings": [{"language": "en-US", "title": "Read Assist", "shortDescription": "Old summary",
                          "fullDescription": "Old description", "video": "https://youtu.be/abcdefghijk"},
                         {"language": "fr-FR", "title": "Read Assist", "shortDescription": "French summary",
                          "fullDescription": "French description"}],
            "details": {"defaultLanguage": "en-US", "contactEmail": "support@example.com"},
            "images": {kind: [] for kind in media.IMAGE_TYPES},
        }
        self.edits, self.calls, self.created = {}, [], 0
        self.commit_error = None
        self.bad_upload_hash = False
        self.change_after_commit = None

    def create_edit(self):
        self.created += 1
        edit_id = str(self.created)
        self.edits[edit_id] = copy.deepcopy(self.live)
        self.calls.append(("CREATE", edit_id, {}))
        return edit_id

    def delete_edit(self, edit_id):
        self.calls.append(("DELETE_EDIT", edit_id, {}))
        self.edits.pop(edit_id, None)

    def request(self, method, path, **kwargs):
        self.calls.append((method, path, {key: value for key, value in kwargs.items() if key != "data"}))
        self.assert_path(path)
        tail = path.removeprefix("/upload").split("/edits/", 1)[1]
        edit_id = tail.split("/", 1)[0].split(":", 1)[0]
        state = self.edits[edit_id]
        if path.endswith(":validate"):
            return {}
        if path.endswith(":commit"):
            if self.commit_error:
                raise self.commit_error
            self.live = copy.deepcopy(state)
            if self.change_after_commit:
                self.change_after_commit(self.live)
            return {"id": edit_id}
        if method == "GET":
            if path.endswith("/tracks"):
                return {"tracks": copy.deepcopy(state["tracks"])}
            if path.endswith("/listings"):
                return {"listings": copy.deepcopy(state["listings"])}
            if path.endswith("/details"):
                return copy.deepcopy(state["details"])
            kind = path.rsplit("/", 1)[1]
            return {"images": copy.deepcopy(state["images"][kind])}
        if method == "PUT":
            value = copy.deepcopy(kwargs["json"])
            state["listings"] = [item for item in state["listings"] if item["language"] != media.LANGUAGE] + [value]
            return value
        if method == "POST" and path.startswith("/upload"):
            sha = hashlib.sha256(kwargs["data"].read()).hexdigest()
            image = {"id": sha[:16], "sha256": "0" * 64 if self.bad_upload_hash else sha}
            state["images"][path.rsplit("/", 1)[1]].append(image)
            return {"image": image}
        raise AssertionError(f"Unexpected API request {method} {path}")

    @staticmethod
    def assert_path(path):
        if not path.startswith((media.APP_PATH + "/edits/", "/upload" + media.APP_PATH + "/edits/")):
            raise AssertionError("Unapproved API endpoint")


class StoreMediaTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pngs = {
            "icon": synthetic_png(512, 512, True),
            "featureGraphic": synthetic_png(1024, 500, shade=120),
            "phone1": synthetic_png(1080, 1920, shade=140),
            "phone2": synthetic_png(1080, 1920, shade=160),
        }

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.root_patch = patch.object(media, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.assets_dir = self.root / "assets"
        self.assets_dir.mkdir()
        (self.root / "generator.py").write_text("# Synthetic test fixture generator only\n")
        generator_hash = media.digest((self.root / "generator.py").read_bytes())
        self.manifest = {"schema_version": 1, "app_package": "app.readflow", "app_name": "Read Assist",
                         "generator": "generator.py", "generator_sha256": generator_hash,
                         "phone_screenshot_count": 2, "assets": []}
        for name, data in self.pngs.items():
            kind = "phoneScreenshots" if name.startswith("phone") else name
            filename = name + ".png"
            path = self.assets_dir / filename
            path.write_bytes(data)
            source = self.root / ("original-" + filename)
            source.write_bytes(data)
            width, height, mode = media.png_info(data)
            self.manifest["assets"].append({"file": filename, "image_type": kind, "bytes": len(data),
                                            "sha256": media.digest(data), "width": width, "height": height,
                                            "mode": mode, "sources": [{"path": source.name,
                                            "sha256": media.digest(data), "kind": "caller_supplied_app_capture" if kind == "phoneScreenshots" else "test_fixture"}]})
        self.save_manifest()
        self.listing_file = self.root / "en-US.json"
        self.listing = {"language": "en-US", "title": "Read Assist", "shortDescription": "Read and listen to your documents",
                        "fullDescription": "Read your documents in Text and PDF views, with playback controls."}
        self.save_listing()
        self.report = {"status": "started", "committed": False, "verified": False}

    def save_manifest(self):
        (self.assets_dir / "manifest.json").write_text(json.dumps(self.manifest))

    def save_listing(self):
        self.listing_file.write_text(json.dumps(self.listing))

    def assets(self):
        return media.load_assets(self.assets_dir)

    def test_valid_assets_and_listing(self):
        self.assertEqual(len(self.assets()), 4)
        self.assertEqual(media.load_listing(self.listing_file), self.listing)

    def test_rejects_tampered_asset_and_source(self):
        (self.assets_dir / "phone1.png").write_bytes(b"changed")
        with self.assertRaises(media.PublishError):
            self.assets()
        (self.assets_dir / "phone1.png").write_bytes(self.pngs["phone1"])
        (self.root / "original-phone1.png").write_bytes(b"changed")
        with self.assertRaises(media.PublishError):
            self.assets()

    def test_rejects_generated_screenshot_without_capture(self):
        self.manifest["assets"][2]["sources"][0]["kind"] = "generated_ui"
        self.save_manifest()
        with self.assertRaisesRegex(media.PublishError, "original app capture"):
            self.assets()

    def test_rejects_false_dimensions_and_package(self):
        self.manifest["assets"][2]["width"] = 720
        self.save_manifest()
        with self.assertRaisesRegex(media.PublishError, "dimensions"):
            self.assets()
        self.manifest["app_package"] = "another.app"
        self.save_manifest()
        with self.assertRaisesRegex(media.PublishError, "package"):
            self.assets()

    def test_rejects_escape_and_duplicate_assets(self):
        self.manifest["assets"][0]["file"] = "../outside.png"
        self.save_manifest()
        with self.assertRaisesRegex(media.PublishError, "basenames"):
            self.assets()
        self.manifest["assets"][0]["file"] = "icon.png"
        self.manifest["assets"].append(copy.deepcopy(self.manifest["assets"][0]))
        self.save_manifest()
        with self.assertRaises(media.PublishError):
            self.assets()

    def test_png_crc_and_alpha_rules(self):
        damaged = bytearray(self.pngs["icon"])
        damaged[29] ^= 1
        with self.assertRaisesRegex(media.PublishError, "checksum"):
            media.png_info(bytes(damaged))
        self.manifest["assets"][0]["mode"] = "RGB"
        self.save_manifest()
        with self.assertRaises(media.PublishError):
            self.assets()

    def test_listing_identity_lengths_and_video_constraints(self):
        for change in ({"language": "fr-FR"}, {"title": "Another app"}, {"shortDescription": "x" * 81},
                       {"fullDescription": "x" * 4001}, {"video": "https://evil.example/watch?v=abcdefghijk"},
                       {"video": "https://youtu.be/abcdefghijk?t=3"}, {"unknown": "value"}):
            with self.subTest(change=change):
                self.listing_file.write_text(json.dumps({**self.listing, **change}))
                with self.assertRaises(media.PublishError):
                    media.load_listing(self.listing_file)
        self.listing_file.write_text(json.dumps({**self.listing, "video": "https://www.youtube.com/watch?v=abcdefghijk"}))
        self.assertIn("video", media.load_listing(self.listing_file))

    def test_publish_preserves_other_state_and_uses_commit_guard(self):
        api = FakeAPI()
        previous = copy.deepcopy(api.live)
        media.publish(api, self.assets(), self.listing, self.report)
        self.assertEqual(self.report["status"], "committed_and_verified")
        self.assertEqual(previous["tracks"], api.live["tracks"])
        self.assertEqual(previous["details"], api.live["details"])
        self.assertEqual(previous["listings"][1], api.live["listings"][0])
        english = next(value for value in api.live["listings"] if value["language"] == "en-US")
        self.assertEqual(english["video"], "https://youtu.be/abcdefghijk")
        commits = [call for call in api.calls if call[1].endswith(":commit")]
        self.assertEqual(len(commits), 1)
        self.assertEqual(commits[0][2]["params"], {"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"})
        self.assertFalse(any("/tracks/" in path or "/testers" in path for method, path, _ in api.calls))
        writes = [(method, path) for method, path, _ in api.calls if method in {"PUT", "POST"}]
        self.assertTrue(all("/listings/en-US" in path or path.endswith((":validate", ":commit")) for _, path in writes))
        self.assertEqual(api.created, 2)
        self.assertTrue(self.report["verificationEditDeleted"])

    def test_rerun_reuses_images_without_upload_or_commit(self):
        api = FakeAPI()
        media.publish(api, self.assets(), self.listing, self.report)
        api.calls.clear()
        report = {"status": "started", "committed": False}
        media.publish(api, self.assets(), self.listing, report)
        self.assertEqual(report["status"], "already_matches")
        self.assertFalse(any(method in {"PUT", "POST"} for method, _, _ in api.calls))

    def test_unknown_existing_image_conflict_never_mutates(self):
        api = FakeAPI()
        api.live["images"]["icon"] = [{"sha256": "f" * 64}]
        with self.assertRaisesRegex(media.PublishError, "conflicts"):
            media.publish(api, self.assets(), self.listing, self.report)
        self.assertFalse(any(method in {"PUT", "POST"} for method, _, _ in api.calls))
        self.assertTrue(self.report["unmodifiedEditDeleted"])

    def test_screenshot_order_conflict_is_not_reordered(self):
        api = FakeAPI()
        api.live["images"]["phoneScreenshots"] = [{"sha256": self.assets()[-1]["sha256"]}]
        with self.assertRaisesRegex(media.PublishError, "conflicts"):
            media.publish(api, self.assets(), self.listing, self.report)

    def test_partial_matching_prefix_only_uploads_missing(self):
        api = FakeAPI()
        assets = self.assets()
        for asset in assets[:3]:
            api.live["images"][asset["image_type"]].append({"sha256": asset["sha256"]})
        media.publish(api, assets, self.listing, self.report)
        self.assertEqual([item["file"] for item in self.report["uploaded"]], ["phone2.png"])

    def test_ambiguous_commit_is_not_retried_or_claimed_success(self):
        api = FakeAPI()
        api.commit_error = media.ApiError(0, "TRANSPORT_ERROR", "Outcome unknown")
        with self.assertRaises(media.ApiError):
            media.publish(api, self.assets(), self.listing, self.report)
        self.assertEqual(self.report["status"], "commit_outcome_unknown")
        self.assertTrue(self.report["reconciliationRequired"])
        self.assertFalse(self.report["committed"])
        self.assertTrue(self.report["editMayBePending"])
        self.assertFalse(any(method == "DELETE_EDIT" for method, _, _ in api.calls))
        self.assertEqual(api.created, 1)
        self.assertEqual(sum(path.endswith(":commit") for _, path, _ in api.calls), 1)

    def test_permission_denial_is_not_success_or_retried(self):
        api = FakeAPI()
        api.commit_error = media.ApiError(403, "PERMISSION_DENIED", "Missing permission")
        with self.assertRaises(media.ApiError):
            media.publish(api, self.assets(), self.listing, self.report)
        self.assertEqual(self.report["status"], "commit_rejected")
        self.assertFalse(self.report["committed"])
        self.assertEqual(api.created, 1)

    def test_wrong_upload_hash_prevents_commit(self):
        api = FakeAPI()
        api.bad_upload_hash = True
        with self.assertRaisesRegex(media.PublishError, "different SHA256"):
            media.publish(api, self.assets(), self.listing, self.report)
        self.assertFalse(any(path.endswith(":commit") for _, path, _ in api.calls))
        self.assertTrue(self.report["ownedEditDeleted"])
        self.assertFalse(self.report["editMayBePending"])
        self.assertEqual(api.edits, {})

    def test_staging_permission_failure_discards_owned_edit(self):
        api = FakeAPI()
        original_request = api.request
        before = copy.deepcopy(api.live)

        def denied(method, path, **kwargs):
            if method == "PUT":
                api.calls.append((method, path, kwargs))
                raise media.ApiError(403, "PERMISSION_DENIED", "Store permission missing")
            return original_request(method, path, **kwargs)

        with patch.object(api, "request", side_effect=denied):
            with self.assertRaises(media.ApiError):
                media.publish(api, self.assets(), self.listing, self.report)
        self.assertTrue(self.report["ownedEditDeleted"])
        self.assertFalse(self.report["editMayBePending"])
        self.assertEqual(api.edits, {})
        self.assertEqual(api.live, before)

    def test_precommit_validation_failure_discards_partial_uploads(self):
        api = FakeAPI()
        original_request = api.request
        before = copy.deepcopy(api.live)

        def rejected(method, path, **kwargs):
            if path.endswith(":validate"):
                raise media.ApiError(400, "FAILED_PRECONDITION", "Missing required declaration")
            return original_request(method, path, **kwargs)

        with patch.object(api, "request", side_effect=rejected):
            with self.assertRaises(media.ApiError):
                media.publish(api, self.assets(), self.listing, self.report)
        self.assertEqual(len(self.report["uploaded"]), 4)
        self.assertTrue(self.report["ownedEditDeleted"])
        self.assertFalse(self.report["editMayBePending"])
        self.assertEqual(api.edits, {})
        self.assertEqual(api.live, before)
        self.assertFalse(any(path.endswith(":commit") for _, path, _ in api.calls))

    def test_post_upload_denial_identifies_exact_call_after_cleanup(self):
        cases = [("read_pending.tracks", "GET", "/tracks"),
                 ("read_pending.listings", "GET", "/listings"),
                 *[("read_pending.images." + kind, "GET", f"/listings/en-US/{kind}")
                   for kind in media.IMAGE_TYPES],
                 ("read_pending.details", "GET", "/details"),
                 ("validate_edit", "POST", ":validate")]
        secret = "fixture-private-token-not-for-diagnostics"
        for phase, method, suffix in cases:
            with self.subTest(phase=phase):
                api = FakeAPI()
                original_request = api.request
                before = copy.deepcopy(api.live)

                def denied(request_method, path, **kwargs):
                    uploaded = sum(len(values) for values in api.edits["1"]["images"].values())
                    if uploaded == 4 and request_method == method and path.endswith(suffix):
                        api.calls.append((request_method, path, kwargs))
                        raise media.ApiError(403, "PERMISSION_DENIED", "Permission denied " + secret)
                    return original_request(request_method, path, **kwargs)

                report_file = self.root / "denied-report.json"
                with patch.object(api, "request", side_effect=denied), \
                        patch.object(media, "authenticate", return_value=secret), \
                        patch.object(media, "API", return_value=api), \
                        patch.object(publish_play, "_SECRETS", [secret]):
                    report = media.run(self.assets_dir, self.listing_file, report_file)
                self.assertEqual(report["phase"], phase)
                self.assertEqual(report["failedApiOperation"], {
                    "phase": phase, "method": method,
                    "endpoint": media.APP_PATH + "/edits/{editId}" + suffix})
                self.assertEqual(len(report["uploaded"]), 4)
                self.assertEqual(report.get("pendingSnapshotVerified", False), phase == "validate_edit")
                self.assertNotIn("validated", report)
                self.assertFalse(report["committed"])
                self.assertTrue(report["ownedEditDeleted"])
                self.assertFalse(report["editMayBePending"])
                self.assertEqual(api.edits, {})
                self.assertEqual(api.live, before)
                self.assertFalse(any(path.endswith(":commit") for _, path, _ in api.calls))
                self.assertEqual(report["error"]["httpCode"], 403)
                self.assertNotIn(secret, report_file.read_text())

    def test_precommit_cleanup_error_keeps_pending_flag_and_diagnostic(self):
        api = FakeAPI()
        api.bad_upload_hash = True
        with patch.object(api, "delete_edit", side_effect=media.ApiError(503, "UNAVAILABLE", "Cleanup unavailable")):
            with self.assertRaises(media.PublishError):
                media.publish(api, self.assets(), self.listing, self.report)
        self.assertTrue(self.report["editMayBePending"])
        self.assertEqual(self.report["cleanupError"]["httpCode"], 503)

    def test_concurrent_track_change_detected_after_commit(self):
        api = FakeAPI()
        api.change_after_commit = lambda state: state["tracks"][0]["releases"][0].update(versionCodes=[str(VERSION_CODE + 1)])
        with self.assertRaisesRegex(media.PublishError, "tracks changed"):
            media.publish(api, self.assets(), self.listing, self.report)
        self.assertTrue(self.report["committed"])
        self.assertFalse(self.report["verified"])
        self.assertTrue(self.report["verificationEditDeleted"])

    def test_invalid_local_files_never_authenticate(self):
        self.manifest["app_package"] = "wrong.app"
        self.save_manifest()
        with patch.object(media, "authenticate") as auth:
            report = media.run(self.assets_dir, self.listing_file, self.root / "report.json")
        auth.assert_not_called()
        self.assertEqual(report["status"], "failed")

    def test_report_redacts_credentials_from_api_errors(self):
        secret = "fixture-sensitive-token-12345"
        with patch.object(publish_play, "_SECRETS", [secret]), patch.object(media, "authenticate", return_value="fake"):
            api = FakeAPI()
            api.commit_error = media.ApiError(403, "PERMISSION_DENIED", secret)
            with patch.object(media, "API", return_value=api):
                report = media.run(self.assets_dir, self.listing_file, self.root / "report.json")
        self.assertNotIn(secret, json.dumps(report))
        self.assertNotIn(secret, (self.root / "report.json").read_text())
        self.assertEqual(report["productionRelease"], "not_attempted")


if __name__ == "__main__":
    unittest.main()
