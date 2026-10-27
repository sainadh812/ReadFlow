#!/usr/bin/env python3
"""Inspect a built Play candidate; this is a technical check, not license approval."""
import argparse
import hashlib
import json
import pathlib
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def elf_load_alignments(data):
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected 64-bit little-endian ELF")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phcount = struct.unpack_from("<HH", data, 54)
    alignments = []
    for index in range(phcount):
        values = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phsize)
        kind, _, offset, address, _, _, _, alignment = values
        if kind == 1:
            if alignment < 16384 or offset % 16384 != address % 16384:
                raise ValueError(f"ELF segment is not 16 KB compatible: {alignment}")
            alignments.append(alignment)
    if not alignments:
        raise ValueError("No loadable ELF segments")
    return alignments


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--aab", type=pathlib.Path, default=pathlib.Path("app/build/outputs/bundle/release/app-release.aab"))
    parser.add_argument("--bundletool", type=pathlib.Path, default=pathlib.Path(".deps/bundletool-all-1.18.3.jar"))
    parser.add_argument("--report", type=pathlib.Path, default=pathlib.Path("docs/evidence/play-store/packaging.json"))
    args = parser.parse_args()

    def bundletool(*arguments):
        return subprocess.check_output(["java", "-jar", str(args.bundletool), *arguments], text=True)

    bundletool("validate", f"--bundle={args.aab}")
    manifest = ET.fromstring(bundletool("dump", "manifest", f"--bundle={args.aab}", "--module=base"))
    android = "{http://schemas.android.com/apk/res/android}"
    application = manifest.find("application")
    sdk = manifest.find("uses-sdk")
    assert manifest.attrib["package"] == "app.readflow"
    assert application.attrib.get(android + "debuggable", "false") == "false"
    assert int(sdk.attrib[android + "targetSdkVersion"]) >= 36
    assert manifest.find("queries") is None, "Unexpected external TTS package queries"
    providers = [item.attrib.get(android + "name", "") for item in application.findall("provider")]
    assert "com.google.firebase.provider.FirebaseInitProvider" not in providers, "Firebase must not initialize before consent"
    metadata = {item.attrib.get(android + "name"): item.attrib.get(android + "value")
                for item in application.findall("meta-data")}
    assert metadata.get("firebase_crashlytics_collection_enabled") == "false"
    assert metadata.get("firebase_data_collection_default_enabled") == "false"
    assert metadata.get("firebase_analytics_collection_deactivated") == "true"
    config = bundletool("dump", "config", f"--bundle={args.aab}")
    assert "PAGE_ALIGNMENT_16K" in config, "Bundle must request 16 KB native APK alignment"
    with zipfile.ZipFile(args.aab) as archive:
        names = archive.namelist()
        native = [name for name in names if name.startswith("base/lib/") and name.endswith(".so")]
        assert native and all("/arm64-v8a/" in name for name in native)
        assert not any("sherpa" in name.lower() or "espeak" in name.lower() for name in native)
        native_report = []
        for name in native:
            data = archive.read(name)
            assert not any(symbol in data for symbol in [b"espeak_Initialize", b"espeak_ng_Initialize", b"espeak_Synth"]), name
            native_report.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                                  "loadAlignments": elf_load_alignments(data)})
        for name in names:
            if name.startswith("base/dex/") and name.endswith(".dex"):
                assert b"Lcom/k2fsa/sherpa/onnx/OfflineTts;" not in archive.read(name), "Sherpa JNI classes packaged"
        packs = json.loads(archive.read("base/assets/model-manifests.json"))
        assert {pack["id"] for pack in packs} == {"kokoro", "alignment"}
        kokoro = next(pack for pack in packs if pack["id"] == "kokoro")
        assert kokoro["archiveRoot"] == "", "Play speech download must not be the legacy archive"
        paths = [item["path"] for pack in packs for item in pack["files"] + pack.get("extras", [])]
        assert not any("espeak" in path.lower() for path in paths)
        assert "base/assets/privacy-information.txt" in names
        assert "base/assets/licenses/dependencies/resolved-runtime.json" in names
        dependencies = json.loads(archive.read("base/assets/licenses/dependencies/resolved-runtime.json"))
        assert not any("sherpa" in item["coordinate"].lower() for item in dependencies)
        assert not any(item["coordinate"].startswith(("com.google.firebase:firebase-analytics:",
            "com.google.firebase:firebase-perf:", "com.google.android.gms:play-services-measurement:"))
            for item in dependencies), "Unexpected Analytics/performance collection dependency"
        fonts = [name for name in names if name.startswith("base/res/font/") and name.endswith((".ttf", ".otf"))]
        assert len(fonts) >= 6, "Reader font resources missing"
        font_notices = [name for name in names if name.startswith("base/assets/licenses/fonts/")]
        assert font_notices, "Font licenses missing"
        signatures = [name for name in names if name.startswith("META-INF/") and name.endswith((".RSA", ".DSA", ".EC"))]
    report = {
        "versionName": manifest.attrib[android + "versionName"], "versionCode": manifest.attrib[android + "versionCode"],
        "aab": args.aab.name, "bytes": args.aab.stat().st_size, "sha256": hashlib.sha256(args.aab.read_bytes()).hexdigest(),
        "targetSdk": sdk.attrib[android + "targetSdkVersion"], "minSdk": sdk.attrib[android + "minSdkVersion"],
        "debuggable": False, "signatureEntries": signatures, "bundletoolValidation": "passed", "apkPageAlignment": "16 KB",
        "nativeLibraries": native_report, "fontResources": fonts, "fontNoticeFiles": len(font_notices),
        "speechModelIds": [pack["id"] for pack in packs], "sherpaNativeAndJniClassesAbsent": True,
        "legacyEspeakModelDataAbsent": True, "runtimeDependencies": len(dependencies),
        "firebaseAutoInitializationDisabled": True, "crashReportingDefaultOff": True,
        "firebaseAnalyticsNotBundled": True,
        "licenseAuditStillRequired": True, "deviceTestsPerformed": False,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
