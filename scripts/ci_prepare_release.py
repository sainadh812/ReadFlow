#!/usr/bin/env python3
"""Materialize owner-supplied CI inputs without printing or archiving secret values."""
import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import sys

PROJECT_ID = "read-assist-fbd21"
APP_ID = "1:831366793321:android:573b80e46b55eb65103af8"
PACKAGE = "app.readflow"
REQUIRED = ("READASSIST_UPLOAD_KEYSTORE_BASE64", "READASSIST_FIREBASE_CONFIG_BASE64",
            "READFLOW_UPLOAD_STORE_PASSWORD", "READFLOW_UPLOAD_KEY_ALIAS",
            "READFLOW_UPLOAD_KEY_PASSWORD")
PRIVATE_DIRECTORY = "read-assist-release-private"


def decode(value, maximum, label):
    try:
        result = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error):
        raise ValueError(f"{label} must contain valid base64.") from None
    if not result or len(result) > maximum:
        raise ValueError(f"{label} has an invalid decoded size.")
    return result


def validate_inputs(environment):
    missing = [name for name in REQUIRED if not environment.get(name)]
    if missing:
        raise ValueError("Missing required GitHub Actions secrets: " + ", ".join(missing))
    key = decode(environment[REQUIRED[0]], 64_000, REQUIRED[0])
    config = decode(environment[REQUIRED[1]], 32_000, REQUIRED[1])
    try:
        document = json.loads(config)
        project = document["project_info"]
        clients = document["client"]
        matches = [client for client in clients
                   if client.get("client_info", {}).get("android_client_info", {}).get("package_name") == PACKAGE]
        if (project.get("project_id") != PROJECT_ID or project.get("project_number") != "831366793321"
                or len(matches) != 1 or matches[0]["client_info"].get("mobilesdk_app_id") != APP_ID
                or not any(re.fullmatch(r"AIza[0-9A-Za-z_-]{35}", item.get("current_key", ""))
                           for item in matches[0].get("api_key", []))):
            raise ValueError()
    except (ValueError, TypeError, KeyError, AttributeError, UnicodeDecodeError):
        raise ValueError("Firebase configuration must match the approved Read Assist project and Android app.") from None
    return key, config


def private_write(path, data):
    # Exclusive creation refuses both existing files and symlinks.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(data)


def prepare(workspace, temporary, environment):
    key, config = validate_inputs(environment)
    workspace, temporary = workspace.resolve(), temporary.resolve()
    private = temporary / PRIVATE_DIRECTORY
    config_path = workspace / "app/google-services.json"
    if config_path.exists() or config_path.is_symlink() or private.exists() or private.is_symlink():
        raise ValueError("Refusing to overwrite existing release configuration or private CI files.")
    if not config_path.parent.is_dir():
        raise ValueError("Expected the checked-out Android application workspace.")
    private.mkdir(mode=0o700)
    key_path = private / "upload.jks"
    created_config = False
    try:
        private_write(key_path, key)
        private_write(config_path, config)
        created_config = True
        ownership = {"workspace": str(workspace), "configSha256": hashlib.sha256(config).hexdigest()}
        private_write(private / "ownership.json", json.dumps(ownership).encode())
    except BaseException:
        if created_config:
            config_path.unlink(missing_ok=True)
        for name in ("upload.jks", "ownership.json"):
            (private / name).unlink(missing_ok=True)
        private.rmdir()
        raise
    return key_path


def cleanup(workspace, temporary):
    workspace = workspace.resolve()
    private = temporary.resolve() / PRIVATE_DIRECTORY
    if not private.exists():
        return
    if private.is_symlink():
        raise ValueError("Refusing an unexpected symbolic link in the private CI directory.")
    marker = private / "ownership.json"
    config_path = workspace / "app/google-services.json"
    if not marker.is_file() or marker.is_symlink():
        raise ValueError("Refusing to remove a private directory without this job's ownership marker.")
    ownership = json.loads(marker.read_bytes())
    if ownership.get("workspace") != str(workspace):
        raise ValueError("Refusing to remove private files belonging to another workspace.")
    changed_config = False
    try:
        if config_path.is_file() and not config_path.is_symlink():
            if hashlib.sha256(config_path.read_bytes()).hexdigest() == ownership.get("configSha256"):
                config_path.unlink()
            else:
                changed_config = True
    finally:
        # These are the only owned file names. Never recursively remove a caller path.
        for name in ("upload.jks", "ownership.json"):
            (private / name).unlink(missing_ok=True)
        private.rmdir()
    if changed_config:
        raise ValueError("Firebase config changed after preparation; retained the replacement, removed the upload key.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "prepare", "cleanup"))
    args = parser.parse_args()
    if args.action == "check":
        validate_inputs(os.environ)
        print("Required release inputs are present and target the approved Firebase application.")
        return
    workspace = Path(os.environ["GITHUB_WORKSPACE"])
    temporary = Path(os.environ["RUNNER_TEMP"])
    if args.action == "cleanup":
        cleanup(workspace, temporary)
        print("Temporary upload-key and owned Firebase config cleanup completed.")
        return
    path = prepare(workspace, temporary, os.environ)
    try:
        # Passwords and config content never go into GITHUB_ENV.
        if any(character in str(path) for character in "\r\n"):
            raise ValueError("Unexpected newline in the runner directory.")
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
            output.write(f"READFLOW_UPLOAD_STORE_FILE={path}\n")
    except BaseException:
        cleanup(workspace, temporary)
        raise
    print("Private release inputs prepared; only the keystore path was exported.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError):
        # A JSON parser or file exception can contain user-supplied data. Never dump it.
        # Explicit validation errors contain only fixed text or known variable names.
        error = sys.exc_info()[1]
        print(str(error) if isinstance(error, ValueError) else "Release input preparation failed; check runner paths and secret configuration.", file=sys.stderr)
        sys.exit(1)
