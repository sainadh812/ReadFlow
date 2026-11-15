"""Offline regression tests for credential, artifact and release safeguards."""
import copy
import hashlib
import pathlib
import tempfile
import unittest
from unittest.mock import patch

import publish_play as publisher


class FakeAPI:
    """Stateful API substitute, including Google's draft-app restriction."""
    def __init__(self, draft_only=False, ambiguous_commit=False):
        self.draft_only = draft_only
        self.ambiguous_commit = ambiguous_commit
        self.calls = []
        self.edits = 0
        self.release = None
        self.committed = False
        self.bundle = None
        self.production = {"track": "production", "releases": []}

    def create_edit(self):
        self.edits += 1
        return str(self.edits)

    def read_state(self, edit_id):
        internal = {"track": "internal", "releases": [self.release] if self.committed else []}
        return [copy.deepcopy(self.production), copy.deepcopy(internal)], [self.bundle] if self.bundle else []

    def delete_edit(self, edit_id):
        self.calls.append(("DELETE", edit_id, {}))

    def lifecycles(self, tracks):
        return {"internal": {"releases": ([{
            "activeArtifacts": [{"versionCode": publisher.VERSION_CODE}],
            "releaseLifecycleState": "RELEASE_LIFECYCLE_STATE_DRAFT" if self.release["status"] == "draft" else "RELEASE_LIFECYCLE_STATE_PUBLISHED",
        }] if self.committed else [])}}

    def upload(self, edit_id, path):
        self.calls.append(("UPLOAD", edit_id, {}))
        self.bundle = {"versionCode": publisher.VERSION_CODE, "sha256": publisher.AAB_SHA256}
        return self.bundle

    def request(self, method, path, **kwargs):
        self.calls.append((method, path, kwargs))
        if method == "PUT":
            self.release = copy.deepcopy(kwargs["json"]["releases"][0])
            return kwargs["json"]
        if path.endswith(":validate"):
            if self.draft_only and self.release["status"] != "draft":
                raise publisher.ApiError(400, "INVALID_ARGUMENT", "Only releases with status draft may be created on draft app.")
            return {}
        if path.endswith(":commit"):
            self.committed = True
            if self.ambiguous_commit:
                raise publisher.ApiError(0, "TRANSPORT_ERROR", "Connection interrupted")
            return {"id": "1"}
        raise AssertionError((method, path))


class PublishingOrchestrationTest(unittest.TestCase):
    def publish(self, api):
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(publisher, "authenticate", return_value="test-token"), \
                patch.object(publisher, "API", return_value=api), \
                patch.object(publisher, "download_aab", return_value=pathlib.Path(tmp) / "approved.aab"):
            return publisher.run("publish", pathlib.Path(tmp) / "report.json")

    def test_publication_only_updates_internal_and_preserves_review(self):
        api = FakeAPI()
        report = self.publish(api)
        self.assertEqual(report["status"], "published", report)
        self.assertTrue(report["otherTracksUnchanged"])
        puts = [call for call in api.calls if call[0] == "PUT"]
        self.assertEqual(len(puts), 1)
        self.assertTrue(puts[0][1].endswith("/tracks/internal"))
        self.assertEqual(puts[0][2]["json"]["releases"][0]["versionCodes"], ["27"])
        commits = [call for call in api.calls if call[1].endswith(":commit")]
        self.assertEqual(len(commits), 1)
        self.assertEqual(commits[0][2]["params"], {"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"})

    def test_draft_only_app_is_saved_without_claiming_publication(self):
        report = self.publish(FakeAPI(draft_only=True))
        self.assertEqual(report["status"], "draft", report)
        self.assertTrue(report["commitAccepted"])
        self.assertEqual(report["verifiedVersionCode"], 27)

    def test_ambiguous_commit_is_reconciled_without_retry(self):
        api = FakeAPI(ambiguous_commit=True)
        report = self.publish(api)
        self.assertEqual(report["status"], "published", report)
        self.assertTrue(report["commitReconciled"])
        self.assertEqual(sum(call[1].endswith(":commit") for call in api.calls), 1)
        self.assertEqual(sum(call[0] == "UPLOAD" for call in api.calls), 1)

    def test_unknown_error_details_are_not_exposed(self):
        self.assertNotIn("secret-value", str(publisher.safe_error(RuntimeError("secret-value"))))

    def test_completed_track_alone_does_not_prove_availability(self):
        self.assertEqual(publisher.lifecycle_status({}), "committed_availability_unconfirmed")


class PublishingSafeguardsTest(unittest.TestCase):
    def credential_info(self):
        return {
            "type": "service_account",
            "project_id": publisher.EXPECTED_PROJECT,
            "client_email": publisher.EXPECTED_EMAIL,
            "private_key": "-----BEGIN PRIVATE KEY-----\nTEST-ONLY\n-----END PRIVATE KEY-----\n",
            "private_key_id": "test-key",
            "client_id": "123456",
            "token_uri": "https://oauth2.googleapis.com/token",
            "universe_domain": "googleapis.com",
        }

    def target_bundle(self):
        return {"versionCode": publisher.VERSION_CODE, "sha256": publisher.AAB_SHA256}

    def track(self, status="completed", code=26, name="internal"):
        return {"track": name, "releases": [{"name": "Test release", "status": status, "versionCodes": [str(code)]}]}

    def test_expected_service_account_is_accepted(self):
        self.assertEqual(publisher.validate_credential_info(self.credential_info()), self.credential_info())

    def test_credential_destination_and_identity_are_restricted(self):
        cases = {
            "type": "authorized_user",
            "project_id": "another-project",
            "client_email": "another-publisher@example.com",
            "token_uri": "https://example.com/collect-token",
            "universe_domain": "example.com",
        }
        for field, value in cases.items():
            with self.subTest(field=field):
                info = self.credential_info()
                info[field] = value
                with self.assertRaises(publisher.PublishError):
                    publisher.validate_credential_info(info)

    def test_missing_credential_identity_is_rejected(self):
        for field in ["type", "project_id", "client_email", "token_uri", "private_key"]:
            with self.subTest(field=field):
                info = self.credential_info()
                del info[field]
                with self.assertRaises(publisher.PublishError):
                    publisher.validate_credential_info(info)

    def test_hash_and_size_both_guard_the_upload(self):
        data = b"known test artifact"
        expected = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "bundle.aab"
            path.write_bytes(data)
            with patch.object(publisher, "AAB_SHA256", expected), patch.object(publisher, "AAB_BYTES", len(data)):
                self.assertEqual(publisher.verify_aab(path), expected)
                path.write_bytes(b"x" * len(data))
                with self.assertRaises(publisher.PublishError):
                    publisher.verify_aab(path)
                path.write_bytes(data + b"extra")
                with self.assertRaises(publisher.PublishError):
                    publisher.verify_aab(path)

    def test_matching_existing_bundle_is_reused(self):
        bundle = self.target_bundle()
        self.assertEqual(publisher.validate_existing_state([self.track()], [bundle]), bundle)

    def test_absent_bundle_requires_upload(self):
        self.assertIsNone(publisher.validate_existing_state([self.track()], []))

    def test_same_version_different_hash_stops(self):
        bundle = self.target_bundle()
        bundle["sha256"] = "0" * 64
        with self.assertRaises(publisher.PublishError):
            publisher.validate_existing_state([self.track()], [bundle])

    def test_higher_internal_release_stops_downgrade(self):
        with self.assertRaises(publisher.PublishError):
            publisher.validate_existing_state([self.track(code=28)], [])

    def test_active_rollout_is_not_overwritten(self):
        for status in ["inProgress", "halted"]:
            with self.subTest(status=status), self.assertRaises(publisher.PublishError):
                publisher.validate_existing_state([self.track(status=status)], [])

    def test_other_tracks_are_compared_without_mutating_input(self):
        tracks = [self.track(), self.track(code=21, name="production"), self.track(code=22, name="alpha")]
        original = copy.deepcopy(tracks)
        normalized = publisher.normalize_other_tracks(tracks)
        self.assertNotIn("internal", normalized)
        self.assertIn("production", normalized)
        self.assertEqual(tracks, original)
        self.assertEqual(normalized, publisher.normalize_other_tracks(list(reversed(tracks))))

    def test_only_explicit_draft_restriction_allows_draft_fallback(self):
        self.assertTrue(publisher.draft_required(publisher.ApiError(400, "INVALID_ARGUMENT", "Only releases with status draft may be created on draft app.")))
        for code, status, message in [
            (403, "PERMISSION_DENIED", "The caller does not have permission"),
            (400, "FAILED_PRECONDITION", "CHANGES_ALREADY_IN_REVIEW"),
            (400, "INVALID_ARGUMENT", "Version code 27 has already been used"),
            (500, "INTERNAL", "Only releases with status draft may be created on draft app."),
        ]:
            with self.subTest(message=message, code=code):
                self.assertFalse(publisher.draft_required(publisher.ApiError(code, status, message)))


if __name__ == "__main__":
    unittest.main()
