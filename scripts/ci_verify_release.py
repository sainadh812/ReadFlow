#!/usr/bin/env python3
"""Verify a configured, upload-signed CI build and stage only approved release files."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

from ci_prepare_release import APP_ID, PACKAGE, PROJECT_ID

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_CERTIFICATE = "b8e3cdfd71bc98427f17921e5b2c0e97a8543cc90a78b066afae7bfab6fb29a2"
BUILD_TOOLS = "35.0.0"
NDK = "27.2.12479018"


class ReleaseVerificationError(ValueError):
    """A fixed diagnostic message that is safe to show in CI logs."""


def command(*arguments):
    result = subprocess.run([str(arg) for arg in arguments], cwd=ROOT, capture_output=True)
    if result.returncode:
        # Commands may be processing secret input files. Never replay their output.
        raise ReleaseVerificationError(f"Release verification command failed: {Path(str(arguments[0])).name}")
    return result.stdout


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def expected_certificate():
    recorded = json.loads((ROOT / "docs/evidence/play-store-upload/upload-key.json").read_text())
    if recorded.get("certificateSha256") != EXPECTED_CERTIFICATE:
        raise ReleaseVerificationError("The approved upload certificate record changed; review the workflow pin before building.")
    return EXPECTED_CERTIFICATE


def verify_key():
    certificate = command("keytool", "-exportcert", "-keystore", os.environ["READFLOW_UPLOAD_STORE_FILE"],
                          "-alias", os.environ["READFLOW_UPLOAD_KEY_ALIAS"],
                          "-storepass:env", "READFLOW_UPLOAD_STORE_PASSWORD")
    if hashlib.sha256(certificate).hexdigest() != expected_certificate():
        raise ReleaseVerificationError("The configured upload key does not match the approved certificate.")
    print("Upload key matches the approved public certificate.")


def certificate_from_pem(output):
    blocks = re.findall(rb"-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+)-----END CERTIFICATE-----", output)
    if not blocks:
        raise ReleaseVerificationError("No signing certificate was found in the AAB.")
    return hashlib.sha256(base64.b64decode(re.sub(rb"\s", b"", blocks[0]), validate=True)).hexdigest()


def verify_firebase_resources():
    build_config = ROOT / "app/build/generated/source/buildConfig/release/app/readflow/BuildConfig.java"
    if not re.search(r"boolean\s+FIREBASE_CONFIGURED\s*=\s*true\s*;", build_config.read_text()):
        raise ReleaseVerificationError("The release was built without Firebase configuration.")
    resources = list((ROOT / "app/build/generated/res/google-services/release").rglob("values.xml"))
    if len(resources) != 1:
        raise ReleaseVerificationError("Expected one generated release Firebase resource file.")
    values = {element.attrib.get("name"): element.text for element in ET.parse(resources[0]).getroot()}
    if (values.get("google_app_id") != APP_ID or values.get("project_id") != PROJECT_ID
            or values.get("gcm_defaultSenderId") != "831366793321"):
        raise ReleaseVerificationError("Built Firebase resources do not match the approved Android application.")


def read_native(path, readelf):
    notes = command(readelf, "-n", path).decode()
    sections = command(readelf, "-SW", path).decode()
    match = re.search(r"Build ID:\s*([a-fA-F0-9]+)", notes)
    return {"gnuBuildId": match.group(1).lower() if match else None,
            "symbolTable": ".symtab" in sections, "dwarf": ".debug_info" in sections,
            "sha256": digest(path), "bytes": path.stat().st_size}


def stage_release(output):
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
    tools = sdk / "build-tools" / BUILD_TOOLS
    readelf = sdk / "ndk" / NDK / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    aab = ROOT / "app/build/outputs/bundle/release/app-release.aab"
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    output.mkdir(parents=True, exist_ok=True)
    verify_firebase_resources()
    packaging = json.loads((output / "packaging.json").read_text())
    if packaging["sha256"] != digest(aab):
        raise ReleaseVerificationError("The inspected bundle changed after packaging verification.")
    if len(packaging["signatureEntries"]) != 1:
        raise ReleaseVerificationError("Expected exactly one signing identity in the release AAB.")
    jar_report = command("jarsigner", "-J-Duser.language=en", "-J-Duser.country=US", "-verify", "-verbose", "-certs", aab)
    if b"jar verified." not in jar_report:
        raise ReleaseVerificationError("AAB signature verification did not succeed.")
    strict = subprocess.run(["jarsigner", "-verify", "-strict", str(aab)], capture_output=True)
    # Bit 4 is the expected trust-chain warning for an owner-created upload
    # certificate. Reject unsigned entries (16), bad signatures and all other bits.
    if strict.returncode not in (0, 4):
        raise ReleaseVerificationError("AAB contains unsigned entries or another signature verification error.")
    cert_report = command("keytool", "-printcert", "-rfc", "-jarfile", aab)
    if certificate_from_pem(cert_report) != expected_certificate():
        raise ReleaseVerificationError("The signed AAB uses an unexpected certificate.")
    apk_report = command(tools / "apksigner", "verify", "--verbose", "--print-certs", apk)
    apk_certs = re.findall(rb"Signer #[0-9]+ certificate SHA-256 digest:\s*([a-fA-F0-9]+)", apk_report)
    if apk_certs != [EXPECTED_CERTIFICATE.encode()]:
        raise ReleaseVerificationError("The signed APK uses an unexpected certificate or signer set.")
    zip_report = command(tools / "zipalign", "-c", "-P", "16", "-v", "4", apk)

    test_files = sorted((ROOT / "app/build/test-results/testReleaseUnitTest").glob("TEST-*.xml"))
    if not test_files:
        raise ReleaseVerificationError("No release unit-test results were produced.")
    tests = {name: sum(int(ET.parse(path).getroot().get(name, "0")) for path in test_files)
             for name in ("tests", "failures", "errors", "skipped")}
    if not tests["tests"] or tests["failures"] or tests["errors"]:
        raise ReleaseVerificationError("Release unit tests did not pass.")
    lint = ROOT / "app/build/reports/lint-results-release.xml"
    issues = list(ET.parse(lint).getroot().iter("issue"))
    lint_errors = sum(issue.get("severity") in {"Fatal", "Error"} for issue in issues)
    if lint_errors:
        raise ReleaseVerificationError("Release lint has errors.")

    merged = ROOT / "app/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib/arm64-v8a"
    stripped = ROOT / "app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a"
    native = []
    with zipfile.ZipFile(aab) as bundle:
        for row in packaging["nativeLibraries"]:
            name = Path(row["path"]).name
            original, packaged = read_native(merged / name, readelf), read_native(stripped / name, readelf)
            if hashlib.sha256(bundle.read(row["path"])).hexdigest() != packaged["sha256"]:
                raise ReleaseVerificationError("The retained native library differs from the final AAB.")
            if original["gnuBuildId"] != packaged["gnuBuildId"]:
                raise ReleaseVerificationError("Native GNU build IDs differ between original and packaged library.")
            if name == "libreadflow_misaki.so" and not all(original[field] for field in ("gnuBuildId", "symbolTable", "dwarf")):
                raise ReleaseVerificationError("Misaki's exact unstripped native symbols are incomplete.")
            native.append({"name": name, "unstripped": original, "packaged": packaged})
            destination = output / "native-symbols/arm64-v8a" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(merged / name, destination)

    shutil.copyfile(ROOT / "app/build/generated/misaki/jniLibs/arm64-v8a/libreadflow_misaki.build.json",
                    output / "native-symbols/misaki-build.json")
    version = packaging["versionName"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ReleaseVerificationError("Unexpected release version name.")
    shutil.copyfile(aab, output / f"Read-Assist-{version}-upload-signed.aab")
    shutil.copyfile(apk, output / f"Read-Assist-{version}-upload-signed.apk")
    (output / "aab-signature.txt").write_bytes(jar_report)
    (output / "apk-signature.txt").write_bytes(apk_report)
    (output / "apk-16kb-alignment.txt").write_bytes(zip_report)
    (output / "native-symbols.json").write_text(json.dumps(native, indent=2) + "\n")
    for path in test_files:
        destination = output / "unit-tests" / path.name
        destination.parent.mkdir(exist_ok=True)
        shutil.copyfile(path, destination)
    shutil.copyfile(lint, output / "lint-results-release.xml")
    notices = ROOT / "app/build/generated/releaseNotices/licenses/dependencies"
    for name in ("resolved-runtime.json", "README.txt"):
        shutil.copyfile(notices / name, output / name)
    record = {"versionName": version, "versionCode": packaging["versionCode"],
              "sourceCommit": command("git", "rev-parse", "HEAD").decode().strip(),
              "repository": "sainadh812/ReadFlow", "package": PACKAGE,
              "firebaseProjectId": PROJECT_ID, "firebaseAndroidAppId": APP_ID,
              "firebaseConfigured": True, "certificateSha256": EXPECTED_CERTIFICATE,
              "unitTests": tests, "lintErrors": lint_errors,
              "lintWarnings": sum(issue.get("severity") == "Warning" for issue in issues),
              "nativeSymbolsUploaded": False, "crashDeliveryVerified": False,
              "deviceTestsPerformed": False, "licenseAuditStillRequired": True,
              "playUploadPerformed": False,
              "signingNote": "Upload-key-signed APK; Play App Signing uses a separate app-signing certificate."}
    (output / "release-evidence.json").write_text(json.dumps(record, indent=2) + "\n")
    print("Final AAB/APK signatures, Firebase destination, tests, lint and matching native artifacts verified.")


def finalize(output, symbols_uploaded):
    record_path = output / "release-evidence.json"
    record = json.loads(record_path.read_text())
    packaging = json.loads((output / "packaging.json").read_text())
    if digest(ROOT / "app/build/outputs/bundle/release/app-release.aab") != packaging["sha256"]:
        raise ReleaseVerificationError("The final bundle changed while native symbols were being uploaded.")
    native = json.loads((output / "native-symbols.json").read_text())
    merged = ROOT / "app/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib/arm64-v8a"
    if any(digest(merged / item["name"]) != item["unstripped"]["sha256"] for item in native):
        raise ReleaseVerificationError("Native libraries changed while symbols were being uploaded.")
    record["nativeSymbolsUploaded"] = symbols_uploaded
    record_path.write_text(json.dumps(record, indent=2) + "\n")
    for path in output.rglob("*"):
        if path.is_symlink() or path.is_file() and not artifact_allowed(path.relative_to(output)):
            raise ReleaseVerificationError("Unexpected file in the release artifact directory; refusing publication.")
    files = {str(path.relative_to(output)): {"sha256": digest(path), "bytes": path.stat().st_size}
             for path in sorted(output.rglob("*")) if path.is_file() and path.name != "SHA256SUMS.json"}
    (output / "SHA256SUMS.json").write_text(json.dumps(files, indent=2) + "\n")
    print("Release artifact checksums recorded.")


def artifact_allowed(relative):
    name = relative.as_posix()
    if name in {"SHA256SUMS.json", "packaging.json", "font-verification.json", "privacy-verification.txt",
                "aab-signature.txt", "apk-signature.txt", "apk-16kb-alignment.txt", "native-symbols.json",
                "lint-results-release.xml", "resolved-runtime.json", "README.txt", "release-evidence.json",
                "native-symbols/misaki-build.json"}:
        return True
    return bool(re.fullmatch(r"Read-Assist-[0-9]+\.[0-9]+\.[0-9]+-upload-signed\.(aab|apk)", name)
                or re.fullmatch(r"native-symbols/arm64-v8a/lib[A-Za-z0-9_.-]+\.so", name)
                or re.fullmatch(r"unit-tests/TEST-[A-Za-z0-9_.$-]+\.xml", name))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("key", "stage", "finalize"))
    parser.add_argument("--output", type=Path, default=ROOT / "dist/play-production")
    parser.add_argument("--symbols-uploaded", action="store_true")
    args = parser.parse_args()
    if args.action == "key":
        verify_key()
    elif args.action == "stage":
        stage_release(args.output)
    else:
        finalize(args.output, args.symbols_uploaded)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile):
        error = sys.exc_info()[1]
        print(str(error) if isinstance(error, ReleaseVerificationError) else
              "Release verification failed while reading build output. Check tool paths and generated reports; private input contents were omitted.",
              file=sys.stderr)
        sys.exit(1)
