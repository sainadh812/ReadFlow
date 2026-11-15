#!/usr/bin/env python3
"""Reuse only the hash-pinned successful emulator build from the first capture run.

The original emulator launch failed, but its release/test APK build passed.
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
RUN = "43745021098"
SOURCE = "9856a49897f2caabe421d085ed6e90a203380c11"
TREES = {
    "app/src/main": "7e3cef462b28059d56efe8b5dd0126b2510f088d",
    "app/src/release": "e98f60bab8f45f39833437585503e6b2e86b1648",
    "app/src/storeDemoAndroidTest": "3fa42320bf3e556b8b35a4cec6f1a17d33f04d27",
    "app/build.gradle.kts": "996e78b4e783bd829663f63929bb387d9bd30cda",
}
APKS = {
    "app/build/outputs/apk/release/app-release.apk": "80bd5a37d3cad56a25f46c42fb056398e2ee54ff04fa0130947cf885c763c97e",
    "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk": "f4076633d7f1fe5ed3237415724277f6b70d83d936637f32688830911f71e91d",
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
