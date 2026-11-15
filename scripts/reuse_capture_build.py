#!/usr/bin/env python3
"""Reuse only the hash-pinned 0.3.2 emulator build with passing Android setup.

The original capture navigation failed, but its release/test build and seed passed.
This avoids rebuilding unchanged application code when retrying capture scripts.
These APKs are not production artifacts. No publishing/signing secrets are used.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
RUN = "43749926901"
SOURCE = "e1a73d4dfed9c7fd0ffcfbcc3cf3c9449201e8d3"
TREES = {
    "app/src/main": "82a53a2b49b7316d8def3a708550f342a525741b",
    "app/src/release": "e98f60bab8f45f39833437585503e6b2e86b1648",
    "app/src/storeDemoAndroidTest": "7a45438f4213e804eaa9de5655d497e6ee0b7604",
    "app/build.gradle.kts": "d69158f23fdc8f554c3751f6e3192c87332c24d7",
}
APKS = {
    "app/build/outputs/apk/release/app-release.apk": "eea874334d186b6a5c5be7614a30d3fa31d51923443c203de4a5228723d387e2",
    "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk": "f880ca95289ef905b928b40186a6b67085bf40fbed556d03377dd368b7ff6a15",
}


def main():
    for name, expected in TREES.items():
        actual = subprocess.check_output(["git", "rev-parse", "HEAD:" + name], cwd=ROOT, text=True).strip()
        if actual != expected:
            raise SystemExit("Capture app/setup source changed. Run again without reuse_capture_build.")
    destination = ROOT / "build/capture-reuse"
    subprocess.run(["gh", "run", "download", RUN, "--repo", "sainadh812/ReadFlow",
                    "--name", f"Read-Assist-Store-Capture-{RUN}-1", "--dir", str(destination)],
                   cwd=ROOT, check=True)
    for name, expected in APKS.items():
        source = destination / name
        if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
            raise SystemExit("The downloaded emulator APK does not match its pinned SHA256.")
    for name in APKS:
        target = ROOT / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(destination / name, target)
    evidence = ROOT / "build/store-media/capture/reused-build.json"
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps({"run": RUN, "source": SOURCE, "apkHashes": APKS,
                                   "matchingSourceObjects": TREES, "productionArtifact": False}, indent=2) + "\n")
    if os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a") as handle:
            handle.write(f"CAPTURE_APK_BUILD_SOURCE={SOURCE}\nCAPTURE_REUSED_APK_BUILD_RUN={RUN}\n")
    print("Verified and reused the two pinned emulator-only APKs.")


if __name__ == "__main__":
    main()
