"""Release-input privacy/ownership guards; fixture values are synthetic, never owner secrets."""
import base64
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import ci_prepare_release as prepare
import ci_verify_release as verify


def fixture():
    config = {"project_info": {"project_number": "831366793321", "project_id": prepare.PROJECT_ID},
              "client": [{"client_info": {"mobilesdk_app_id": prepare.APP_ID,
                          "android_client_info": {"package_name": prepare.PACKAGE}},
                          "api_key": [{"current_key": "AIza" + "0" * 35}]}]}
    environment = {"READASSIST_UPLOAD_KEYSTORE_BASE64": base64.b64encode(b"synthetic-keystore").decode(),
                   "READASSIST_FIREBASE_CONFIG_BASE64": base64.b64encode(json.dumps(config).encode()).decode(),
                   "READFLOW_UPLOAD_STORE_PASSWORD": "synthetic-store-password",
                   "READFLOW_UPLOAD_KEY_ALIAS": "synthetic-alias",
                   "READFLOW_UPLOAD_KEY_PASSWORD": "synthetic-key-password"}
    return config, environment


class ReleaseInputsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.workspace = self.root / "workspace"
        (self.workspace / "app").mkdir(parents=True)
        self.temporary = self.root / "runner"
        self.temporary.mkdir()
        self.config, self.environment = fixture()

    def replace_config(self, config):
        self.environment["READASSIST_FIREBASE_CONFIG_BASE64"] = base64.b64encode(json.dumps(config).encode()).decode()

    def test_required_secrets_error_only_names_missing_values(self):
        del self.environment["READFLOW_UPLOAD_STORE_PASSWORD"]
        with self.assertRaisesRegex(ValueError, "READFLOW_UPLOAD_STORE_PASSWORD") as failure:
            prepare.validate_inputs(self.environment)
        self.assertNotIn("synthetic-key-password", str(failure.exception))

    def test_rejects_other_project_app_package_and_duplicate_clients(self):
        alternatives = []
        other = copy.deepcopy(self.config)
        other["project_info"]["project_id"] = "unapproved-project"
        alternatives.append(other)
        other = copy.deepcopy(self.config)
        other["client"][0]["client_info"]["mobilesdk_app_id"] = "unapproved-app"
        alternatives.append(other)
        other = copy.deepcopy(self.config)
        other["client"][0]["client_info"]["android_client_info"]["package_name"] = "app.other"
        alternatives.append(other)
        other = copy.deepcopy(self.config)
        other["client"].append(copy.deepcopy(other["client"][0]))
        alternatives.append(other)
        for item in alternatives:
            with self.subTest(config=item):
                self.replace_config(item)
                with self.assertRaisesRegex(ValueError, "approved Read Assist"):
                    prepare.validate_inputs(self.environment)

    def test_invalid_base64_and_excessive_data_never_echo_values(self):
        for value in ("SECRET-not-base64", base64.b64encode(b"x" * 64_001).decode()):
            self.environment["READASSIST_UPLOAD_KEYSTORE_BASE64"] = value
            with self.assertRaises(ValueError) as failure:
                prepare.validate_inputs(self.environment)
            self.assertNotIn(value, str(failure.exception))

    def test_writes_private_files_and_cleanup_removes_only_owned_files(self):
        key = prepare.prepare(self.workspace, self.temporary, self.environment)
        config = self.workspace / "app/google-services.json"
        unrelated = self.temporary / "keep.txt"
        unrelated.write_text("retain")
        self.assertEqual(0o600, key.stat().st_mode & 0o777)
        self.assertEqual(0o600, config.stat().st_mode & 0o777)
        self.assertEqual(0o700, key.parent.stat().st_mode & 0o777)
        self.assertEqual(b"synthetic-keystore", key.read_bytes())
        prepare.cleanup(self.workspace, self.temporary)
        self.assertFalse(config.exists())
        self.assertFalse(key.parent.exists())
        self.assertEqual("retain", unrelated.read_text())
        prepare.cleanup(self.workspace, self.temporary)

    def test_preexisting_configuration_is_never_overwritten_or_removed(self):
        config = self.workspace / "app/google-services.json"
        config.write_text("existing-owner-file")
        with self.assertRaisesRegex(ValueError, "overwrite"):
            prepare.prepare(self.workspace, self.temporary, self.environment)
        prepare.cleanup(self.workspace, self.temporary)
        self.assertEqual("existing-owner-file", config.read_text())

    def test_cleanup_does_not_delete_an_unowned_private_directory(self):
        private = self.temporary / prepare.PRIVATE_DIRECTORY
        private.mkdir()
        key = private / "upload.jks"
        key.write_bytes(b"not-owned-by-this-job")
        with self.assertRaisesRegex(ValueError, "ownership marker"):
            prepare.cleanup(self.workspace, self.temporary)
        self.assertEqual(b"not-owned-by-this-job", key.read_bytes())

    def test_symbolic_link_is_not_followed(self):
        target = self.root / "external.json"
        target.write_text("external")
        (self.workspace / "app/google-services.json").symlink_to(target)
        with self.assertRaisesRegex(ValueError, "overwrite"):
            prepare.prepare(self.workspace, self.temporary, self.environment)
        self.assertEqual("external", target.read_text())

    def test_partial_write_failure_removes_key_and_created_config(self):
        actual_write = prepare.private_write

        def fail_ownership(path, data):
            if path.name == "ownership.json":
                raise OSError("synthetic write failure")
            actual_write(path, data)

        with patch.object(prepare, "private_write", side_effect=fail_ownership):
            with self.assertRaises(OSError):
                prepare.prepare(self.workspace, self.temporary, self.environment)
        self.assertFalse((self.temporary / prepare.PRIVATE_DIRECTORY).exists())
        self.assertFalse((self.workspace / "app/google-services.json").exists())

    def test_cleanup_preserves_a_replacement_config_but_always_removes_key(self):
        key = prepare.prepare(self.workspace, self.temporary, self.environment)
        config = self.workspace / "app/google-services.json"
        config.write_text("replaced")
        with self.assertRaisesRegex(ValueError, "changed"):
            prepare.cleanup(self.workspace, self.temporary)
        self.assertFalse(key.exists())
        self.assertEqual("replaced", config.read_text())

    def test_artifact_allowlist_excludes_all_private_inputs(self):
        for path in ("upload.jks", "google-services.json", "signing.properties", "native-symbols/upload.jks",
                     "native-symbols/arm64-v8a/credentials.json", "unit-tests/secret.txt", "../upload.jks"):
            self.assertFalse(verify.artifact_allowed(Path(path)), path)
        for path in ("Read-Assist-0.2.5-upload-signed.aab", "Read-Assist-0.2.5-upload-signed.apk",
                     "native-symbols/arm64-v8a/libreadflow_misaki.so", "unit-tests/TEST-app.readflow.Test.xml"):
            self.assertTrue(verify.artifact_allowed(Path(path)), path)

    def test_certificate_fingerprint_is_derived_from_der_not_pem_formatting(self):
        encoded = base64.b64encode(b"synthetic DER").decode()
        pem = f"-----BEGIN CERTIFICATE-----\n{encoded[:4]}\n{encoded[4:]}\n-----END CERTIFICATE-----".encode()
        self.assertEqual(hashlib.sha256(b"synthetic DER").hexdigest(), verify.certificate_from_pem(pem))
        with self.assertRaises(ValueError):
            verify.certificate_from_pem(b"unsigned bundle")


if __name__ == "__main__":
    unittest.main()
