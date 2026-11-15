"""Offline regression tests for credential, artifact and release safeguards."""
import copy
import hashlib
import json
import pathlib
import tempfile
import unittest
from unittest.mock import Mock, patch

import publish_play as publisher


class GoogleErrorDiagnosticsTest(unittest.TestCase):
    def api_error_report(self, error):
        # Exercise response parsing without opening a session or making a request.
        api = publisher.API.__new__(publisher.API)
        api.session = Mock()
        response = Mock(status_code=403, content=b"error")
        response.json.return_value = {"error": error}
        api.session.request.return_value = response
        with self.assertRaises(publisher.ApiError) as caught:
            api.request("POST", publisher.APP_PATH + "/edits/test-edit:validate")
        api.session.request.assert_called_once()
        return publisher.safe_error(caught.exception)

    def test_google_permission_and_legacy_reason_survive_response_parsing(self):
        report = self.api_error_report({
            "status": "PERMISSION_DENIED", "message": "The caller does not have permission",
            "details": [{
                "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                "reason": "IAM_PERMISSION_DENIED", "domain": "androidpublisher.googleapis.com",
                "metadata": {"permission": "androidpublisher.edits.validate", "resource": "applications/app.readflow"},
            }],
            "errors": [{"reason": "insufficientPermissions", "domain": "global", "message": "Unneeded duplicate prose"}],
        })
        self.assertEqual(report, {
            "httpCode": 403, "status": "PERMISSION_DENIED", "message": "The caller does not have permission",
            "diagnostics": [
                {"source": "google.rpc.ErrorInfo", "reason": "IAM_PERMISSION_DENIED",
                 "domain": "androidpublisher.googleapis.com",
                 "metadata": {"permission": "androidpublisher.edits.validate", "resource": "applications/app.readflow"}},
                {"source": "legacy", "reason": "insufficientPermissions", "domain": "global"},
            ],
        })

    def test_untrusted_details_and_sensitive_fields_are_not_reported(self):
        secret = "RegisteredCredentialValue"
        with patch.object(publisher, "_SECRETS", [secret]):
            report = self.api_error_report({
                "status": "PERMISSION_DENIED", "message": "Denied for " + secret,
                "access_token": "UNTRUSTED_ACCESS_TOKEN",
                "headers": {"Authorization": "Bearer UNTRUSTED_HEADER_TOKEN"},
                "details": [
                    {"@type": "type.googleapis.com/google.rpc.DebugInfo", "stackEntries": ["PRIVATE_STACK_ENTRY"]},
                    {"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": secret,
                     "domain": "googleapis.com", "metadata": {
                         "permission": "androidpublisher." + secret,
                         "resource": "applications/app.readflow?access_token=PRIVATE_QUERY_TOKEN",
                         "access_token": "PRIVATE_METADATA_TOKEN", "private_key": "PRIVATE_METADATA_KEY",
                         "headers": {"Authorization": "Bearer PRIVATE_NESTED_TOKEN"},
                     }},
                    {"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "SAFE_REASON",
                     "domain": "UNTRUSTED_DOMAIN", "metadata": {
                         "permission": "eyJhbGciOiJIUzI.eyJzdWIiOiIxMjM.signature",
                         "resource": "projects/unapproved-private-project",
                     }},
                    {"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "ya29.PRIVATE_TOKEN",
                     "domain": {"private_key": "NESTED_KEY"}, "metadata": {
                         "permission": "-----BEGIN PRIVATE KEY-----PRIVATE_KEY_BODY",
                         "resource": ["applications/app.readflow", "PRIVATE_LIST_VALUE"],
                     }},
                ],
                "errors": [{"reason": {"access_token": "NESTED_REASON_TOKEN"}, "domain": "global",
                            "location": "PRIVATE_LOCATION", "message": "PRIVATE_LEGACY_MESSAGE"}],
            })
        self.assertEqual(report["message"], "Denied for [REDACTED]")
        self.assertEqual(report["diagnostics"], [
            {"source": "google.rpc.ErrorInfo", "domain": "googleapis.com"},
            {"source": "google.rpc.ErrorInfo", "reason": "SAFE_REASON"},
            {"source": "legacy", "domain": "global"},
        ])
        rendered = json.dumps(report)
        for withheld in (secret, "PRIVATE_", "NESTED_", "UNTRUSTED_", "eyJ", "access_token", "private_key", "headers"):
            self.assertNotIn(withheld, rendered)

    def test_diagnostic_volume_is_bounded_and_oversized_values_are_omitted(self):
        report = self.api_error_report({
            "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "R" + str(i),
                         "domain": "x" * 200, "metadata": {"permission": "CAN_" + "X" * 200,
                                                          "resource": "applications/app.readflow/" + "x" * 500}}
                        for i in range(100)],
            "errors": [{"reason": "legacy" + str(i), "domain": "global"} for i in range(100)],
        })
        self.assertEqual(len(report["diagnostics"]), 8)
        self.assertEqual(report["diagnostics"][0], {"source": "google.rpc.ErrorInfo", "reason": "R0"})
        self.assertLess(len(json.dumps(report)), 1000)

    def test_malformed_error_fields_are_not_stringified_into_reports(self):
        for error in ([], "PRIVATE_RAW_ERROR", {"status": {"token": "PRIVATE_STATUS"},
                                               "message": ["PRIVATE_MESSAGE"], "details": {}, "errors": "PRIVATE_ERRORS"}):
            with self.subTest(error=error):
                self.assertEqual(self.api_error_report(error), {
                    "httpCode": 403, "status": "HTTP_ERROR", "message": "Google API request failed.",
                })

    def test_three_argument_errors_remain_compatible(self):
        error = publisher.ApiError(403, "PERMISSION_DENIED", "The caller does not have permission")
        self.assertEqual(publisher.safe_error(error), {
            "httpCode": 403, "status": "PERMISSION_DENIED", "message": "The caller does not have permission",
        })


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
                patch.object(publisher, "AAB_SHA256", "a" * 64), \
                patch.object(publisher, "AAB_BYTES", 1234), \
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
        self.assertEqual(puts[0][2]["json"]["releases"][0]["versionCodes"], [str(publisher.VERSION_CODE)])
        self.assertEqual(puts[0][2]["json"]["releases"][0]["name"], f"Read Assist {publisher.VERSION_NAME}")
        commits = [call for call in api.calls if call[1].endswith(":commit")]
        self.assertEqual(len(commits), 1)
        self.assertEqual(commits[0][2]["params"], {"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"})

    def test_draft_only_app_is_saved_without_claiming_publication(self):
        report = self.publish(FakeAPI(draft_only=True))
        self.assertEqual(report["status"], "draft", report)
        self.assertTrue(report["commitAccepted"])
        self.assertEqual(report["verifiedVersionCode"], publisher.VERSION_CODE)

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

    def test_unverified_artifact_never_downloads_or_authenticates(self):
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(publisher, "AAB_SHA256", "UNVERIFIED"), \
                patch.object(publisher, "AAB_BYTES", 0), \
                patch.object(publisher, "download_aab") as download, \
                patch.object(publisher, "authenticate") as auth:
            report = publisher.run("publish", pathlib.Path(tmp) / "report.json")
        self.assertEqual(report["status"], "failed")
        self.assertIn("UNVERIFIED", report["error"]["message"])
        download.assert_not_called()
        auth.assert_not_called()

    def test_inspection_remains_available_with_unverified_artifact(self):
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(publisher, "AAB_SHA256", "UNVERIFIED"), \
                patch.object(publisher, "AAB_BYTES", 0), \
                patch.object(publisher, "authenticate", return_value="test-token"), \
                patch.object(publisher, "API", return_value=FakeAPI()):
            report = publisher.run("inspect", pathlib.Path(tmp) / "report.json")
        self.assertEqual(report["status"], "inspected")


class PublishingSafeguardsTest(unittest.TestCase):
    def setUp(self):
        for name, value in (("AAB_SHA256", "a" * 64), ("AAB_BYTES", 1234)):
            patcher = patch.object(publisher, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_incomplete_artifact_pins_are_rejected(self):
        for sha, size in (("UNVERIFIED", 1234), ("a" * 64, 0), ("a" * 64, -1), ("a" * 64, True)):
            with self.subTest(sha=sha, size=size), self.assertRaisesRegex(publisher.PublishError, "UNVERIFIED"):
                publisher.validate_artifact_pin(sha, size)

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
            publisher.validate_existing_state([self.track(code=publisher.VERSION_CODE + 1)], [])

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
            (400, "INVALID_ARGUMENT", f"Version code {publisher.VERSION_CODE} has already been used"),
            (500, "INTERNAL", "Only releases with status draft may be created on draft app."),
        ]:
            with self.subTest(message=message, code=code):
                self.assertFalse(publisher.draft_required(publisher.ApiError(code, status, message)))


if __name__ == "__main__":
    unittest.main()
