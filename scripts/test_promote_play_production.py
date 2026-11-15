"""Offline transaction tests for exact-artifact production promotion."""
from __future__ import annotations

import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import promote_play_production as production
import publish_play
import test_publish_store_media as media_fixtures


ASSETS = [
    {"image_type": "icon", "sha256": "1" * 64},
    {"image_type": "featureGraphic", "sha256": "2" * 64},
    {"image_type": "phoneScreenshots", "sha256": "3" * 64},
    {"image_type": "phoneScreenshots", "sha256": "4" * 64},
]


def lifecycle(state="PUBLISHED", code=27, track="production"):
    return {"releases": [{"track": track, "activeArtifacts": [{"versionCode": code}],
                          "releaseLifecycleState": "RELEASE_LIFECYCLE_STATE_" + state}]}


class FakeProductionAPI(media_fixtures.FakeAPI):
    def __init__(self):
        super().__init__()
        self.live["bundles"] = [{"versionCode": 27, "sha256": production.AAB_SHA256}]
        for asset in ASSETS:
            self.live["images"][asset["image_type"]].append({"sha256": asset["sha256"]})
        self.lifecycle = lifecycle()
        self.lifecycle_error = None
        self.commit_lands = False
        self.validate_error = None

    def request(self, method, path, **kwargs):
        if path == production.APP_PATH + "/tracks/production/releases":
            self.calls.append((method, path, kwargs))
            if self.lifecycle_error:
                raise self.lifecycle_error
            return copy.deepcopy(self.lifecycle)
        if path.endswith("/bundles"):
            self.calls.append((method, path, kwargs))
            edit_id = path.split("/edits/", 1)[1].split("/", 1)[0]
            return {"bundles": copy.deepcopy(self.edits[edit_id]["bundles"])}
        if method == "PUT" and path.endswith("/tracks/production"):
            self.calls.append((method, path, kwargs))
            edit_id = path.split("/edits/", 1)[1].split("/", 1)[0]
            state = self.edits[edit_id]
            value = copy.deepcopy(kwargs["json"])
            state["tracks"] = [track for track in state["tracks"] if track["track"] != "production"] + [value]
            return value
        if path.endswith(":validate") and self.validate_error:
            self.calls.append((method, path, kwargs))
            raise self.validate_error
        if path.endswith(":commit") and self.commit_error and self.commit_lands:
            edit_id = path.split("/edits/", 1)[1].split(":", 1)[0]
            self.live = copy.deepcopy(self.edits[edit_id])
        return super().request(method, path, **kwargs)


class ProductionPromotionTests(unittest.TestCase):
    def setUp(self):
        self.api = FakeProductionAPI()
        self.approved_listing = copy.deepcopy(self.api.live["listings"][0])
        self.report = {"status": "started", "committed": False, "trackVerified": False}

    def promote(self, validate_only=False):
        production.promote(self.api, ASSETS, self.report, validate_only, self.approved_listing)

    def lifecycle_calls(self):
        return [call for call in self.api.calls if call[1] == production.APP_PATH + "/tracks/production/releases"]

    def commit_calls(self):
        return [call for call in self.api.calls if call[1].endswith(":commit")]

    def test_exact_promotion_only_changes_production(self):
        before = copy.deepcopy(self.api.live)
        self.promote()
        self.assertEqual(self.report["status"], "published")
        self.assertTrue(self.report["committed"])
        self.assertTrue(self.report["trackVerified"])
        self.assertEqual(self.report["verifiedSha256"], production.AAB_SHA256)
        self.assertEqual(before["tracks"][0], self.api.live["tracks"][0])
        for key in ("bundles", "listings", "details", "images"):
            self.assertEqual(before[key], self.api.live[key])
        writes = [(method, path) for method, path, _ in self.api.calls if method in {"POST", "PUT"}]
        self.assertTrue(all(path.endswith(("/tracks/production", ":validate", ":commit")) for _, path in writes))
        self.assertFalse(any("/upload" in path or "/testers" in path for _, path, _ in self.api.calls))
        self.assertEqual(len(self.commit_calls()), 1)
        self.assertEqual(self.commit_calls()[0][2]["params"], {"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"})
        self.assertEqual(len(self.lifecycle_calls()), 1)
        self.assertLessEqual(len(production.RELEASE_NOTES), 500)

    def test_validate_only_discards_edit_and_never_publishes(self):
        before = copy.deepcopy(self.api.live)
        self.promote(validate_only=True)
        self.assertEqual(self.report["status"], "validated_not_committed")
        self.assertFalse(self.report["committed"])
        self.assertTrue(self.report["ownedEditDeleted"])
        self.assertFalse(self.report["editMayBePending"])
        self.assertEqual(before, self.api.live)
        self.assertEqual(self.api.edits, {})
        self.assertEqual(len(self.commit_calls()), 0)
        self.assertEqual(len(self.lifecycle_calls()), 0)

    def test_validate_error_is_preserved_and_owned_edit_deleted(self):
        self.api.validate_error = production.ApiError(400, "FAILED_PRECONDITION", "Complete the required declaration.")
        with self.assertRaises(production.ApiError) as error:
            self.promote(validate_only=True)
        self.assertEqual(error.exception.message, "Complete the required declaration.")
        self.assertEqual(self.report["phase"], "validate")
        self.assertTrue(self.report["ownedEditDeleted"])
        self.assertEqual(len(self.commit_calls()), 0)

    def test_missing_listing_blocks_any_track_write(self):
        self.api.live["listings"][0]["shortDescription"] = ""
        with self.assertRaisesRegex(production.PublishError, "shortDescription"):
            self.promote()
        self.assertFalse(any(method == "PUT" for method, _, _ in self.api.calls))

    def test_stale_nonempty_listing_blocks_any_track_write(self):
        self.api.live["listings"][0]["fullDescription"] = "A different valid description."
        with self.assertRaisesRegex(production.PublishError, "approved local listing"):
            self.promote()
        self.assertFalse(any(method == "PUT" for method, _, _ in self.api.calls))

    def test_unapproved_store_images_block_promotion(self):
        self.api.live["images"]["featureGraphic"][0]["sha256"] = "f" * 64
        with self.assertRaisesRegex(production.PublishError, "artwork"):
            self.promote()
        self.assertFalse(any(method == "PUT" for method, _, _ in self.api.calls))

    def test_internal_must_already_be_completed(self):
        self.api.live["tracks"][0]["releases"][0]["status"] = "draft"
        with self.assertRaisesRegex(production.PublishError, "Internal testing"):
            self.promote()

    def test_wrong_bundle_hash_blocks_promotion(self):
        self.api.live["bundles"][0]["sha256"] = "f" * 64
        with self.assertRaisesRegex(production.PublishError, "approved AAB"):
            self.promote()

    def test_missing_or_duplicate_bundle_is_rejected(self):
        for bundles in ([], self.api.live["bundles"] * 2):
            with self.subTest(bundles=bundles):
                api = FakeProductionAPI()
                api.live["bundles"] = bundles
                with self.assertRaisesRegex(production.PublishError, "approved AAB"):
                    production.promote(api, ASSETS, {"committed": False}, approved_listing=self.approved_listing)

    def test_newer_track_or_uploaded_bundle_blocks_promotion(self):
        self.api.live["tracks"].append({"track": "beta", "releases": [{"status": "draft", "versionCodes": ["28"]}]})
        with self.assertRaisesRegex(production.PublishError, "newer version"):
            self.promote()
        self.api = FakeProductionAPI()
        self.api.live["bundles"].append({"versionCode": 28, "sha256": "f" * 64})
        with self.assertRaisesRegex(production.PublishError, "newer bundle"):
            self.promote()

    def test_production_in_progress_or_halted_is_preserved(self):
        for status in ("inProgress", "halted"):
            with self.subTest(status=status):
                api = FakeProductionAPI()
                api.live["tracks"][1]["releases"] = [{"status": status, "versionCodes": ["22"]}]
                with self.assertRaisesRegex(production.PublishError, "rollout"):
                    production.promote(api, ASSETS, {"committed": False}, approved_listing=self.approved_listing)
                self.assertFalse(any(method == "PUT" for method, _, _ in api.calls))

    def test_already_completed_production_reconciles_without_commit(self):
        self.api.live["tracks"][1]["releases"] = [{"status": "completed", "versionCodes": ["27"]}]
        self.promote()
        self.assertEqual(self.report["status"], "published")
        self.assertTrue(self.report["alreadyPresent"])
        self.assertTrue(self.report["trackVerified"])
        self.assertFalse(self.report["committed"])
        self.assertFalse(any(method in {"PUT", "POST"} for method, _, _ in self.api.calls))
        self.assertEqual(len(self.lifecycle_calls()), 1)

    def test_quota_error_never_becomes_false_publication(self):
        self.api.lifecycle_error = production.ApiError(403, "PERMISSION_DENIED", "Listing releases quota exceeded")
        self.promote()
        self.assertEqual(self.report["status"], "committed_availability_unconfirmed")
        self.assertTrue(self.report["committed"])
        self.assertTrue(self.report["trackVerified"])
        self.assertIn("quota exceeded", self.report["productionLifecycleError"]["message"])
        self.assertEqual(len(self.lifecycle_calls()), 1)

    def test_lifecycle_mapping_requires_correct_track_and_version(self):
        self.assertEqual(production.lifecycle_status(lifecycle(code=22)), "availability_unconfirmed")
        self.assertEqual(production.lifecycle_status(lifecycle(track="internal")), "availability_unconfirmed")
        self.assertEqual(production.lifecycle_status(lifecycle("IN_REVIEW")), "in_review")
        self.assertEqual(production.lifecycle_status(lifecycle("NOT_APPROVED")), "not_approved")
        conflicting = {"releases": lifecycle()["releases"] + lifecycle("DRAFT")["releases"]}
        self.assertEqual(production.lifecycle_status(conflicting), "availability_unconfirmed")

    def test_pending_review_is_reported_honestly(self):
        self.api.lifecycle = lifecycle("IN_REVIEW")
        self.promote()
        self.assertEqual(self.report["status"], "in_review")
        self.assertNotEqual(self.report["status"], "published")

    def test_ambiguous_commit_without_confirmation_retains_edit(self):
        self.api.commit_error = production.ApiError(0, "TRANSPORT_ERROR", "Outcome unknown")
        self.api.lifecycle = {"releases": []}
        with self.assertRaisesRegex(production.PublishError, "outcome remains unknown"):
            self.promote()
        self.assertEqual(self.report["status"], "commit_outcome_unknown")
        self.assertFalse(self.report["committed"])
        self.assertTrue(self.report["editMayBePending"])
        self.assertEqual(self.api.created, 1)
        self.assertEqual(len(self.commit_calls()), 1)
        self.assertEqual(len(self.lifecycle_calls()), 1)

    def test_ambiguous_commit_reconciles_only_after_direct_confirmation(self):
        self.api.commit_error = production.ApiError(0, "TRANSPORT_ERROR", "Outcome unknown")
        self.api.commit_lands = True
        self.promote()
        self.assertEqual(self.report["status"], "published")
        self.assertTrue(self.report["commitReconciled"])
        self.assertTrue(self.report["trackVerified"])
        self.assertFalse(self.report["editMayBePending"])
        self.assertEqual(self.api.created, 2)
        self.assertEqual(len(self.commit_calls()), 1)
        self.assertEqual(len(self.lifecycle_calls()), 1)

    def test_draft_lifecycle_does_not_resolve_ambiguous_commit(self):
        self.api.commit_error = production.ApiError(503, "UNAVAILABLE", "Outcome unknown")
        self.api.lifecycle = lifecycle("DRAFT")
        with self.assertRaises(production.PublishError):
            self.promote()
        self.assertEqual(self.report["status"], "commit_outcome_unknown")
        self.assertEqual(self.api.created, 1)

    def test_lifecycle_alone_cannot_claim_publication_without_fresh_track(self):
        self.api.commit_error = production.ApiError(0, "TRANSPORT_ERROR", "Outcome unknown")
        self.api.commit_lands = False
        with self.assertRaisesRegex(production.PublishError, "did not confirm"):
            self.promote()
        self.assertFalse(self.report["trackVerified"])
        self.assertFalse(self.report["committed"])
        self.assertNotEqual(self.report["status"], "published")
        self.assertFalse(self.report["editMayBePending"])
        self.assertEqual(len(self.commit_calls()), 1)

    def test_explicit_commit_rejection_is_not_retried(self):
        self.api.commit_error = production.ApiError(400, "FAILED_PRECONDITION", "You already have changes in review.")
        with self.assertRaises(production.ApiError):
            self.promote()
        self.assertEqual(self.report["status"], "commit_rejected")
        self.assertFalse(self.report["committed"])
        self.assertEqual(len(self.commit_calls()), 1)
        self.assertEqual(len(self.lifecycle_calls()), 0)

    def test_other_track_changes_are_detected_after_commit(self):
        self.api.change_after_commit = lambda state: state["tracks"].append({"track": "beta", "releases": []})
        with self.assertRaisesRegex(production.PublishError, "non-production track"):
            self.promote()
        self.assertTrue(self.report["committed"])
        self.assertFalse(self.report["trackVerified"])

    def test_listing_changes_are_detected_after_commit(self):
        self.api.change_after_commit = lambda state: state["details"].update(contactEmail="changed@example.com")
        with self.assertRaisesRegex(production.PublishError, "metadata changed"):
            self.promote()
        self.assertTrue(self.report["committed"])
        self.assertFalse(self.report["trackVerified"])

    def test_validate_only_cleanup_failure_is_not_success(self):
        with patch.object(self.api, "delete_edit", side_effect=production.ApiError(500, "INTERNAL", "Cleanup failed")):
            self.promote(validate_only=True)
        self.assertEqual(self.report["status"], "validation_cleanup_failed")
        self.assertTrue(self.report["editMayBePending"])

    def test_invalid_local_assets_never_authenticate(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(production, "load_assets", side_effect=production.PublishError("Local assets invalid")), patch.object(production, "authenticate") as auth:
                report = production.run(Path(directory), Path(directory) / "report.json")
            auth.assert_not_called()
            self.assertEqual(report["status"], "failed")
            self.assertEqual(report["phase"], "validate_local_assets")

    def test_run_preserves_api_blocker_without_credential_leaks(self):
        token = "synthetic-secret-token-12345"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "manifest.json").write_text("{}")
            (root / "en-US.json").write_text(json.dumps(self.approved_listing))
            with patch.object(publish_play, "_SECRETS", [token]):
                self.api.validate_error = production.ApiError(400, "FAILED_PRECONDITION", "Required declaration missing " + token)
                with patch.object(production, "load_assets", return_value=ASSETS), patch.object(production, "authenticate", return_value="fake"), patch.object(production, "API", return_value=self.api):
                    report = production.run(root, root / "report.json", True, root / "en-US.json")
            self.assertEqual(report["status"], "failed")
            self.assertEqual(report["phase"], "validate")
            self.assertIn("Required declaration missing", report["error"]["message"])
            self.assertNotIn(token, (root / "report.json").read_text())
            self.assertTrue(report["ownedEditDeleted"])


if __name__ == "__main__":
    unittest.main()
