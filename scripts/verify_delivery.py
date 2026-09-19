"""Summarize actual Gradle reports and inspect the delivered APK using structured parsers."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
tests = []
for path in sorted((ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")):
    suite = ET.parse(path).getroot()
    tests.append({key: suite.attrib[key] for key in ["name", "tests", "failures", "errors", "skipped", "time", "timestamp"]})
issues = ET.parse(ROOT / "app/build/reports/lint-results-debug.xml").getroot().findall("issue")
apk = ROOT / "dist/ReadFlow-arm64-debug.apk"
with zipfile.ZipFile(apk) as package:
    names = package.namelist()
    abis = sorted({name.split("/")[1] for name in names if name.startswith("lib/")})
    assert abis == ["arm64-v8a"], abis
    assert not any(name.endswith(".onnx") or name.endswith("voices.bin") for name in names)
    assert "assets/model-manifests.json" in names
    assert "assets/vendor/Readability.js" in names
result = dict(command="./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain",
    exitCode=0, tests=tests, testTotal=sum(int(s["tests"]) for s in tests),
    failures=sum(int(s["failures"]) + int(s["errors"]) for s in tests),
    skipped=sum(int(s["skipped"]) for s in tests),
    lintErrors=sum(i.attrib["severity"] in ["Error", "Fatal"] for i in issues),
    lintWarnings=sum(i.attrib["severity"] == "Warning" for i in issues),
    lintIssueIds=sorted({i.attrib["id"] for i in issues}),
    apk=dict(file=apk.name, bytes=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), abis=abis, bundledSpeechOrAlignmentWeights=False),
    androidRuntimeVerification="Blocked: software emulator startup/property queries/PackageManager failed before app execution",
    physicalTargetVerification="No attached device; not performed")
assert result["testTotal"] > 0 and result["failures"] == 0 and result["skipped"] == 0 and result["lintErrors"] == 0
(ROOT / "docs/evidence/build-results.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
