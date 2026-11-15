import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import inspect_play_store as store
from publish_play import ApiError, PublishError


class StoreInspectionTests(unittest.TestCase):
    def make_api(self):
        api = Mock()
        api.create_edit.return_value = "inspection123"

        def request(method, path):
            self.assertEqual(method, "GET")
            if path.endswith("/tracks"):
                return {"tracks": [{"track": "internal"}, {"track": "production"}, {"track": "beta"}]}
            if path.endswith("/listings"):
                return {"listings": [{"language": "en-US", "fullDescription": "Reading is useful. " * 150}]}
            return {}

        api.request.side_effect = request
        return api

    def test_reads_store_and_release_endpoints_without_mutations(self):
        api = self.make_api()
        report = {}
        store.inspect(api, report)
        self.assertEqual(report["status"], "inspected")
        self.assertTrue(report["inspectionEditDeleted"])
        api.create_edit.assert_called_once_with()
        api.delete_edit.assert_called_once_with("inspection123")
        self.assertEqual(set(report["images"]["en-US"]), set(store.IMAGE_TYPES))
        self.assertEqual(set(report["lifecycles"]), {"internal", "production", "beta"})
        self.assertTrue(all(call.args[0] == "GET" for call in api.request.call_args_list))

    def test_permission_failure_does_not_skip_other_endpoints_or_cleanup(self):
        api = self.make_api()
        original = api.request.side_effect

        def request(method, path):
            if path.endswith("/details") or path.endswith("/featureGraphic"):
                raise ApiError(403, "PERMISSION_DENIED", "Access denied.")
            return original(method, path)

        api.request.side_effect = request
        report = {}
        store.inspect(api, report)
        self.assertEqual(report["status"], "inspected_with_errors")
        self.assertEqual(set(report["endpointErrors"]), {"details", "images.en-US.featureGraphic"})
        self.assertIn("bundles", report)
        self.assertIn("production", report["lifecycles"])
        api.delete_edit.assert_called_once_with("inspection123")

    def test_edit_creation_failure_still_inspects_non_edit_lifecycles(self):
        api = self.make_api()
        api.create_edit.side_effect = ApiError(403, "PERMISSION_DENIED", "Access denied.")
        report = {}
        store.inspect(api, report)
        self.assertEqual(report["status"], "inspected_with_errors")
        self.assertEqual(set(report["lifecycles"]), {"internal", "production"})
        api.delete_edit.assert_not_called()

    def test_cleanup_failure_is_reported_and_lifecycle_still_read(self):
        api = self.make_api()
        api.delete_edit.side_effect = ApiError(503, "UNAVAILABLE", "Try later.")
        report = {}
        store.inspect(api, report)
        self.assertFalse(report["inspectionEditDeleted"])
        self.assertIn("deleteEdit", report["endpointErrors"])
        self.assertIn("internal", report["lifecycles"])

    def test_full_public_listing_is_preserved_but_secrets_anywhere_are_removed(self):
        text = "Reading is useful. " * 150
        secret = "private-access-token-for-test"
        with patch.object(store.publish_play, "_SECRETS", [secret]):
            cleaned = store.clean_report({"description": text, "value": text + secret})
        self.assertEqual(cleaned["description"], text)
        self.assertNotIn(secret, json.dumps(cleaned))
        self.assertTrue(cleaned["value"].endswith("[REDACTED]"))
        self.assertEqual(store.clean_report("prefix " * 300 + "Bearer secret"), "[Sensitive diagnostic omitted]")

    def test_invalid_track_name_is_not_used_as_a_path(self):
        api = self.make_api()
        api.request.side_effect = lambda method, path: {"tracks": [{"track": "../../different-app"}]} if path.endswith("/tracks") else {}
        report = {}
        store.inspect(api, report)
        self.assertIn("trackName", report["endpointErrors"])
        self.assertEqual(set(report["lifecycles"]), {"internal", "production"})

    def test_authentication_failure_writes_sanitized_failure_report(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            with patch.object(store, "authenticate", side_effect=RuntimeError("secret credential data")):
                report = store.run(path)
            saved = path.read_text()
        self.assertEqual(report["status"], "failed")
        self.assertNotIn("secret credential data", saved)
        self.assertFalse(report["committed"])
        self.assertEqual(report["productionEligibility"], "not_determined_by_these_endpoints")


if __name__ == "__main__":
    unittest.main()
