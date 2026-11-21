"""Offline tests for the narrowly scoped two-track policy-remediation publisher."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import publish_testing_fix as publisher
from publish_play import ApiError, PublishError


def release(code=30):
    return {"name": f"Read Assist test {code}", "versionCodes": [str(code)], "status": "completed",
            "releaseNotes": [{"language": "en-US", "text": "Previous release"}]}


class FakeAPI:
    def __init__(self):
        self.tracks = [{"track": "production"}, {"track": "beta", "releases": []},
                       {"track": "alpha", "releases": [release()]},
                       {"track": "internal", "releases": [release()]},
                       {"track": "closed-extra", "releases": [release(29)]}]
        self.bundles = [{"versionCode": 30, "sha256": "b" * 64}]
        self.details = {"defaultLanguage": "en-US", "contactEmail": "owner@example.com"}
        self.listings = [{"language": "en-US", "title": "Read Assist", "fullDescription": "Unchanged description"}]
        self.edits = {}
        self.next_edit = 1
        self.calls = []
        self.commit_errors = []
        self.ambiguous_commits_apply = False
        self.upload_error = None
        self.upload_error_applies = True
        self.upload_hash = "a" * 64
        self.validate_error = None
        self.in_review = False
        self.lifecycle_unavailable = False
        self.mutate_other_after_commit = False
        self.mutate_listing_after_commit = False
        self.corrupt_bundle_after_commit = False
        self.mutate_staged = False
        self.committed = False
        self.not_sent_for_review = False

    def create_edit(self):
        edit_id = str(self.next_edit)
        self.next_edit += 1
        self.edits[edit_id] = {"tracks": copy.deepcopy(self.tracks), "bundles": copy.deepcopy(self.bundles)}
        self.calls.append(("CREATE", edit_id, {}))
        return edit_id

    def read_state(self, edit_id):
        self.calls.append(("READ", edit_id, {}))
        state = copy.deepcopy(self.edits[edit_id])
        if self.mutate_staged and any(
            "31" in r.get("versionCodes", []) for t in state["tracks"] for r in t.get("releases", [])
        ):
            state["tracks"][0]["releases"] = [release(29)]
        return state["tracks"], state["bundles"]

    def delete_edit(self, edit_id):
        self.calls.append(("DELETE", edit_id, {}))
        self.edits.pop(edit_id, None)

    def lifecycles(self, tracks):
        result = {}
        for track in self.tracks:
            name = track["track"]
            releases = []
            for item in track.get("releases", []):
                state = "PUBLISHED"
                if name == "alpha":
                    state = "NOT_SENT_FOR_REVIEW" if self.not_sent_for_review else ("IN_REVIEW" if self.committed else "NOT_APPROVED")
                if self.in_review and name == "production":
                    state = "IN_REVIEW"
                releases.append({"releaseLifecycleState": publisher.STATE_PREFIX + state,
                                 "activeArtifacts": [{"versionCode": code} for code in item["versionCodes"]]})
            if self.in_review and name == "production" and not releases:
                releases = [{"releaseLifecycleState": publisher.STATE_PREFIX + "IN_REVIEW", "activeArtifacts": []}]
            result[name] = {"releases": releases}
        if self.lifecycle_unavailable:
            result["beta"] = {"unavailable": {"status": "PERMISSION_DENIED"}}
        return copy.deepcopy(result)

    def upload(self, edit_id, artifact):
        self.calls.append(("UPLOAD", edit_id, {}))
        bundle = {"versionCode": 31, "sha256": self.upload_hash}
        if self.upload_error is None or self.upload_error_applies:
            self.edits[edit_id]["bundles"].append(bundle)
        if self.upload_error:
            raise self.upload_error
        return copy.deepcopy(bundle)

    def request(self, method, path, **kwargs):
        self.calls.append((method, path, copy.deepcopy(kwargs)))
        edit_id = path.split("/edits/")[1].split("/")[0].split(":")[0]
        if method == "GET" and path.endswith("/details"):
            return copy.deepcopy(self.details)
        if method == "GET" and path.endswith("/listings"):
            return {"listings": copy.deepcopy(self.listings)}
        if method == "PUT" and "/tracks/" in path:
            name = path.rsplit("/", 1)[1]
            state = self.edits[edit_id]["tracks"]
            state[:] = [copy.deepcopy(kwargs["json"]) if t["track"] == name else t for t in state]
            return copy.deepcopy(kwargs["json"])
        if path.endswith(":validate"):
            if self.validate_error:
                raise self.validate_error
            return {}
        if path.endswith(":commit"):
            error = self.commit_errors.pop(0) if self.commit_errors else None
            if error is None or (isinstance(error, ApiError) and publisher.ambiguous(error) and self.ambiguous_commits_apply):
                self.tracks = copy.deepcopy(self.edits[edit_id]["tracks"])
                self.bundles = copy.deepcopy(self.edits[edit_id]["bundles"])
                self.committed = True
                self.not_sent_for_review = kwargs.get("params", {}).get("changesNotSentForReview", False)
                if self.mutate_other_after_commit:
                    self.tracks[0]["releases"] = [release(29)]
                if self.mutate_listing_after_commit:
                    self.listings[0]["fullDescription"] = "Changed outside this command"
                if self.corrupt_bundle_after_commit:
                    self.bundles[-1]["sha256"] = "c" * 64
            if error:
                raise error
            return {"id": edit_id}
        raise AssertionError((method, path))


class TwoTrackPublishingTests(unittest.TestCase):
    def run_publish(self, api, *, validate_only=False, pins=None, download_error=None):
        constants = {
            "VERSION_CODE": 31, "VERSION_NAME": "0.3.5", "AAB_SHA256": "a" * 64, "AAB_BYTES": 1234,
            "AAB_URL": "https://github.com/sainadh812/ReadFlow/releases/download/v0.3.5-play-candidate/Read-Assist-0.3.5-upload-signed.aab",
        }
        constants.update(pins or {})
        with tempfile.TemporaryDirectory() as directory, patch.multiple(publisher.shared, **constants), \
                patch.object(publisher, "authenticate", return_value="offline-test-token") as auth, \
                patch.object(publisher, "API", return_value=api), \
                patch.object(publisher.shared, "download_aab", return_value=Path(directory) / "pinned.aab",
                             side_effect=download_error) as download:
            report = publisher.run(Path(directory) / "report.json", validate_only)
            self.auth_calls = auth.call_count
            self.download_calls = download.call_count
            return report

    def commits(self, api):
        return [call for call in api.calls if call[1].endswith(":commit")]

    def uploads(self, api):
        return [call for call in api.calls if call[0] == "UPLOAD"]

    def test_both_tracks_committed_in_one_edit_with_exact_hash_and_no_other_writes(self):
        api = FakeAPI()
        original_others = copy.deepcopy([t for t in api.tracks if t["track"] not in publisher.TARGETS])
        report = self.run_publish(api)
        self.assertEqual(report["status"], "in_review", report)
        self.assertTrue(report["commitAccepted"])
        self.assertTrue(report["reviewConfirmed"])
        self.assertEqual(report["trackStatuses"], {"alpha": "in_review", "internal": "published"})
        self.assertEqual(report["verifiedSha256"], "a" * 64)
        self.assertTrue(report["otherTracksUnchanged"])
        self.assertTrue(report["storeMetadataUnchanged"])
        self.assertEqual(original_others, [t for t in api.tracks if t["track"] not in publisher.TARGETS])
        puts = [call for call in api.calls if call[0] == "PUT"]
        self.assertEqual([call[1] for call in puts], [publisher.shared.APP_PATH + "/edits/1/tracks/alpha",
                                                   publisher.shared.APP_PATH + "/edits/1/tracks/internal"])
        self.assertTrue(all(call[2]["json"]["releases"][0]["versionCodes"] == ["31"] for call in puts))
        self.assertEqual(len(self.uploads(api)), 1)
        self.assertEqual(len(self.commits(api)), 1)
        self.assertEqual(self.commits(api)[0][2]["params"], {"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"})
        self.assertFalse(report["testersModified"])
        self.assertFalse(report["declarationsModified"])

    def test_validate_only_deletes_owned_edit_without_committing(self):
        api = FakeAPI()
        report = self.run_publish(api, validate_only=True)
        self.assertEqual(report["status"], "validated_not_committed", report)
        self.assertTrue(report["editDeleted"])
        self.assertFalse(report["committed"])
        self.assertEqual(self.commits(api), [])
        self.assertTrue(all(t["releases"][0]["versionCodes"] == ["30"] for t in api.tracks if t["track"] in publisher.TARGETS))

    def test_unverified_identity_or_artifact_pin_stops_before_authentication(self):
        for pin in ({"VERSION_CODE": 30}, {"VERSION_NAME": "0.3.4"}, {"AAB_URL": "https://example.com/a.aab"},
                    {"AAB_SHA256": "UNVERIFIED"}, {"AAB_BYTES": 0}, {"PACKAGE": "another.app"}):
            with self.subTest(pin=pin):
                report = self.run_publish(FakeAPI(), pins=pin)
                self.assertEqual(report["status"], "failed")
                self.assertEqual(self.auth_calls, 0)
                self.assertEqual(self.download_calls, 0)

    def test_download_hash_failure_stops_before_authentication(self):
        report = self.run_publish(FakeAPI(), download_error=PublishError("AAB SHA256 does not match"))
        self.assertEqual(report["status"], "failed")
        self.assertEqual(self.auth_calls, 0)

    def test_newer_version_on_any_track_or_in_bundles_is_preserved(self):
        for location in ("alpha", "internal", "production", "bundle"):
            with self.subTest(location=location):
                api = FakeAPI()
                if location == "bundle":
                    api.bundles.append({"versionCode": 32, "sha256": "c" * 64})
                else:
                    next(t for t in api.tracks if t["track"] == location)["releases"] = [release(32)]
                report = self.run_publish(api)
                self.assertEqual(report["status"], "failed", report)
                self.assertTrue(report["editDeleted"])
                self.assertEqual(self.uploads(api), [])
                self.assertEqual(self.commits(api), [])

    def test_any_existing_review_or_unavailable_lifecycle_blocks_mutation(self):
        for field in ("in_review", "lifecycle_unavailable"):
            with self.subTest(field=field):
                api = FakeAPI()
                setattr(api, field, True)
                report = self.run_publish(api)
                self.assertEqual(report["status"], "failed", report)
                self.assertEqual(self.uploads(api), [])
                self.assertFalse(any(call[0] == "PUT" for call in api.calls))

    def test_unexpected_target_shape_and_rollout_are_not_replaced(self):
        for modification in ("missing", "empty", "multiple", "draft", "inProgress", "halted", "fraction", "country", "extra_code", "older"):
            with self.subTest(modification=modification):
                api = FakeAPI()
                track = next(t for t in api.tracks if t["track"] == "alpha")
                if modification == "missing":
                    api.tracks.remove(track)
                elif modification == "empty":
                    track["releases"] = []
                elif modification == "multiple":
                    track["releases"].append(release(29))
                elif modification in {"draft", "inProgress", "halted"}:
                    track["releases"][0]["status"] = modification
                elif modification == "fraction":
                    track["releases"][0]["userFraction"] = 0.5
                elif modification == "country":
                    track["releases"][0]["countryTargeting"] = {"countries": ["US"]}
                elif modification == "extra_code":
                    track["releases"][0]["versionCodes"].append("29")
                else:
                    track["releases"] = [release(29)]
                report = self.run_publish(api)
                self.assertEqual(report["status"], "failed", report)
                self.assertEqual(self.uploads(api), [])
                self.assertEqual(self.commits(api), [])

    def test_existing_matching_bundle_is_reused_without_upload(self):
        api = FakeAPI()
        api.bundles.append({"versionCode": 31, "sha256": "a" * 64})
        report = self.run_publish(api)
        self.assertEqual(report["status"], "in_review", report)
        self.assertIn("reusedBundle", report)
        self.assertEqual(self.uploads(api), [])

    def test_existing_hash_mismatch_or_missing_hash_fails_without_upload(self):
        for sha in ("c" * 64, ""):
            with self.subTest(sha=sha):
                api = FakeAPI()
                api.bundles.append({"versionCode": 31, "sha256": sha})
                report = self.run_publish(api)
                self.assertEqual(report["status"], "failed", report)
                self.assertEqual(self.uploads(api), [])
                self.assertEqual(self.commits(api), [])

    def test_upload_timeout_is_reconciled_once_without_second_upload(self):
        api = FakeAPI()
        api.upload_error = ApiError(0, "TRANSPORT_ERROR", "Outcome unknown")
        report = self.run_publish(api)
        self.assertEqual(report["status"], "in_review", report)
        self.assertTrue(report["uploadReconciled"])
        self.assertEqual(len(self.uploads(api)), 1)

    def test_missing_upload_after_timeout_does_not_retry_or_commit(self):
        api = FakeAPI()
        api.upload_error = ApiError(0, "TRANSPORT_ERROR", "Outcome unknown")
        api.upload_error_applies = False
        report = self.run_publish(api)
        self.assertEqual(report["status"], "failed", report)
        self.assertTrue(report["editDeleted"])
        self.assertEqual(len(self.uploads(api)), 1)
        self.assertEqual(self.commits(api), [])

    def test_uploaded_wrong_hash_never_reaches_track_mutations(self):
        api = FakeAPI()
        api.upload_hash = "c" * 64
        report = self.run_publish(api)
        self.assertEqual(report["status"], "failed", report)
        self.assertFalse(any(call[0] == "PUT" for call in api.calls))
        self.assertTrue(report["editDeleted"])

    def test_staged_other_track_change_is_detected_before_commit(self):
        api = FakeAPI()
        api.mutate_staged = True
        report = self.run_publish(api)
        self.assertEqual(report["status"], "failed", report)
        self.assertEqual(self.commits(api), [])
        self.assertTrue(report["editDeleted"])

    def test_validation_rejection_deletes_owned_edit_without_commit(self):
        api = FakeAPI()
        api.validate_error = ApiError(403, "PERMISSION_DENIED", "Not permitted")
        report = self.run_publish(api)
        self.assertEqual(report["status"], "failed", report)
        self.assertTrue(report["editDeleted"])
        self.assertEqual(self.commits(api), [])

    def test_definite_commit_rejection_is_not_retried(self):
        api = FakeAPI()
        api.commit_errors = [ApiError(403, "PERMISSION_DENIED", "Not permitted")]
        report = self.run_publish(api)
        self.assertEqual(report["status"], "failed", report)
        self.assertTrue(report["editDeleted"])
        self.assertEqual(len(self.commits(api)), 1)

    def test_explicit_automatic_review_restriction_retries_only_requested_flag(self):
        api = FakeAPI()
        api.commit_errors = [ApiError(400, "INVALID_ARGUMENT", "Changes cannot be sent for review automatically. "
                                      "Please set the query parameter changesNotSentForReview to true.")]
        report = self.run_publish(api)
        self.assertEqual(report["status"], "not_sent_for_review", report)
        self.assertTrue(report["committed"])
        self.assertFalse(report["reviewConfirmed"])
        self.assertEqual(len(self.commits(api)), 2)
        self.assertEqual(self.commits(api)[1][2]["params"], {
            "changesInReviewBehavior": "ERROR_IF_IN_REVIEW", "changesNotSentForReview": True,
        })

    def test_arbitrary_error_mentioning_review_flag_does_not_permit_retry(self):
        api = FakeAPI()
        api.commit_errors = [ApiError(400, "INVALID_ARGUMENT", "Unknown changesNotSentForReview parameter")]
        report = self.run_publish(api)
        self.assertEqual(report["status"], "failed", report)
        self.assertEqual(len(self.commits(api)), 1)

    def test_ambiguous_commit_applied_is_reconciled_by_non_edit_lifecycles(self):
        api = FakeAPI()
        api.commit_errors = [ApiError(0, "TRANSPORT_ERROR", "Outcome unknown")]
        api.ambiguous_commits_apply = True
        report = self.run_publish(api)
        self.assertEqual(report["status"], "in_review", report)
        self.assertTrue(report["commitReconciled"])
        self.assertEqual(report["verifiedVersionCode"], 31)
        self.assertEqual(len(self.commits(api)), 1)
        self.assertEqual(len(self.uploads(api)), 1)

    def test_unknown_commit_is_retained_without_retry_delete_or_fresh_edit(self):
        api = FakeAPI()
        api.commit_errors = [ApiError(503, "UNAVAILABLE", "Outcome unknown")]
        report = self.run_publish(api)
        self.assertEqual(report["status"], "commit_outcome_unknown", report)
        self.assertTrue(report["editMayBePending"])
        self.assertIsNone(report["committed"])
        self.assertNotIn("editDeleted", report)
        self.assertEqual(len(self.commits(api)), 1)
        self.assertEqual(sum(call[0] == "CREATE" for call in api.calls), 1)
        self.assertFalse(any(call[0] == "DELETE" for call in api.calls))

    def test_unexpected_commit_exception_is_also_retained_without_retry(self):
        api = FakeAPI()
        api.commit_errors = [RuntimeError("PRIVATE_RAW_FAILURE")]
        report = self.run_publish(api)
        self.assertEqual(report["status"], "commit_outcome_unknown", report)
        self.assertTrue(report["editMayBePending"])
        self.assertEqual(len(self.commits(api)), 1)
        self.assertFalse(any(call[0] == "DELETE" for call in api.calls))
        self.assertNotIn("PRIVATE_RAW_FAILURE", str(report))

    def test_fresh_verification_detects_other_track_listing_and_bundle_changes(self):
        for field in ("mutate_other_after_commit", "mutate_listing_after_commit", "corrupt_bundle_after_commit"):
            with self.subTest(field=field):
                api = FakeAPI()
                setattr(api, field, True)
                report = self.run_publish(api)
                self.assertEqual(report["status"], "failed", report)
                self.assertTrue(report["committed"])
                self.assertTrue(report["verificationEditDeleted"])
                self.assertEqual(len(self.commits(api)), 1)

    def test_both_tracks_already_target_is_idempotent_without_mutations(self):
        api = FakeAPI()
        api.bundles.append({"versionCode": 31, "sha256": "a" * 64})
        for track in api.tracks:
            if track["track"] in publisher.TARGETS:
                track["releases"] = [release(31)]
        report = self.run_publish(api)
        self.assertEqual(report["status"], "not_approved", report)
        self.assertTrue(report["alreadyCommitted"])
        self.assertTrue(report["editDeleted"])
        self.assertEqual(self.uploads(api), [])
        self.assertFalse(any(call[0] == "PUT" for call in api.calls))
        self.assertEqual(self.commits(api), [])

    def test_already_committed_target_in_review_is_observed_without_mutations(self):
        api = FakeAPI()
        api.bundles.append({"versionCode": 31, "sha256": "a" * 64})
        for track in api.tracks:
            if track["track"] in publisher.TARGETS:
                track["releases"] = [release(31)]
        api.committed = True
        report = self.run_publish(api)
        self.assertEqual(report["status"], "in_review", report)
        self.assertTrue(report["alreadyCommitted"])
        self.assertTrue(report["reviewConfirmed"])
        self.assertTrue(report["editDeleted"])
        self.assertEqual(self.uploads(api), [])
        self.assertFalse(any(call[0] == "PUT" for call in api.calls))
        self.assertEqual(self.commits(api), [])


if __name__ == "__main__":
    unittest.main()
