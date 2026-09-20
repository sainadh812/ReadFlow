# ReadFlow

A native Android reading application built with Kotlin, Compose and local speech synthesis. This is an **experimental implementation, not a completed/validated product**. Both real TTS engines and transcript-constrained word alignment work in host smoke tests. Physical Galaxy S25 Ultra acceptance and manually audited audio boundaries remain required. See [status](docs/STATUS.md).

## Install on a phone

[Download ReadFlow 0.1.5 for Android 15+ ARM64](https://github.com/sainadh812/ReadFlow/releases/download/v0.1.5-prototype/ReadFlow-0.1.5-arm64-debug.apk).

Version 0.1.5 fixes two reported normalization stops: OCR tokens such as `money.4` now read literally as "money. four", and `|` reads as "vertical bar". The latter is not automatically changed to "I": it may be an OCR error, but its meaning is ambiguous. Displayed text, geometry and source-word IDs are unchanged. No reimport or model download is needed; changed speech cache entries regenerate. Normalization issue logs now identify the exact rejected token. See [OCR text fixes and actual validation](docs/OCR_TEXT_FIX.md), including a separate Pocket alignment failure found during host testing.

Version 0.1.4 adds **automatic issue-only logs** for import/parsing, extraction warnings, unsupported speech text, low-confidence alignment, model operations and playback errors. Normal successful operations and user cancellations do not create reports. Logs capture bounded failing input, source-word mappings, error details, model/device versions and, when available, the exact generated audio. Nothing is uploaded automatically.

After an issue, open **Settings > Issue logs**, select the report, tap **Export issue log** (save icon), and choose a destination for the ZIP. Attach that ZIP when asking for help. **Review it first: it can contain private document text, source URLs and speech audio. Do not post private logs to a public GitHub issue.** Individual/all logs can be deleted. See [issue logging and verification](docs/ISSUE_LOGS.md), including limits for native crashes and missing input.

Version 0.1.3 fixes ordinary webpage punctuation and technical identifiers being rejected as equations. PDFs now open in **Original PDF** view, fitted to the screen width, with direct page controls. Preview rendering does not wait for OCR; word selection becomes available when extraction finishes. The reflowed Reader remains optional. Unsupported sentences stop preparation with an explicit **Skip sentence** action instead of blocking navigation throughout the page.

Install over the existing app without uninstalling or clearing data to preserve downloaded models and documents. The 0.1.2 native speech-callback crash fix remains included. **Settings > Copy playback diagnostics** remains a separate content-free summary; use the new Issue logs export when the failing input is needed. See the [text/PDF fix report](docs/TEXT_PDF_FIX.md) for earlier checks and remaining device verification.

The 0.1.1 installation fixes remain included. Open **Voice models**, then choose **Resume setup** for interrupted packs. Completed downloads are verified and reused. Word highlighting also requires the separate **Word alignment** pack. Keep ReadFlow open until setup finishes; interrupted setup can be resumed after reopening the app.

See the [playback crash fix](docs/PLAYBACK_CRASH_FIX.md) and [installation fix](docs/INSTALLATION_FIX.md) reports. APKs are attached to GitHub Releases because they exceed GitHub's normal Git file-size limit.

## Build

Use JDK 17 and Android SDK platform 35 / build-tools 34.0.0. Minimum Android 15/API 35 is an MVP scope choice, not a permanent requirement. Default APK is ARM64 only. Build tools and dependencies are explicitly pinned in Gradle; Sherpa/Readability bootstrap assets have SHA-256 locks.

```bash
cd ReadFlow
# Point ANDROID_HOME at your SDK, or set sdk.dir in ignored local.properties.
bash scripts/bootstrap.sh
./gradlew assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.readflow/.MainActivity
```

`bootstrap.sh` needs curl, sha256sum and Python 3.10+ only as build/download tools. **The installed app has no Python dependency or server.** A checked-in Gradle wrapper downloads the pinned Gradle 8.11.1 distribution with checksum verification. Models are not required to compile the application.

## Run

1. Open Voice models. Download either Kokoro-82M or Pocket TTS, plus the separately listed Word alignment pack. Both speech packs may be installed, but only the selected engine is loaded.
2. Choose a voice. Import an English PDF/image with the Android file picker, paste an HTTPS article URL, or share a URL/file from another app. Imports are copied into managed private storage.
3. Start reading. Tap a word during playback to jump; while paused, use Read from here. Reader mode supports font resizing. Original mode supports geometry overlays, zoom, pan and rotation. Scan rotation/re-OCR is in the reader menu.
4. Use notification/lock-screen controls for background audio. Grant notification permission when asked. Downloaded content, OCR, speech, alignment, positions and caches work locally; offline reading still needs target-device validation.

The main reader is implemented, not a web mockup. The approved visual attachment was not available, so its exact visual fidelity cannot be claimed. The sample paragraph is an original CC0 fixture; it is not a canned audio substitute.

## Downloads and provenance

`app/src/main/assets/model-manifests.json` lists exact archive/file sizes, SHA-256s, trusted URLs, versions, voices and licenses. Kokoro export: `kokoro-en-v0_19`; Pocket export: `sherpa-onnx-pocket-tts-int8-2026-01-26`. Model versions are deliberately pinned, not advertised as latest/best. Pocket exposes only the licensed fixed Alba voice, with no arbitrary voice input.

The shared Wav2Vec2 alignment export is an additional ~95.2 MB download, not one of the two TTS models. Its resident memory and runtime overhead on Android are unmeasured. Host overhead is recorded in `docs/evidence/*/run.json`; those runs are functional smoke evidence, not phone benchmarks. See [licenses](docs/THIRD_PARTY_NOTICES.md).

## Reproduce the timing prototype

```bash
python3 -m venv .venv
.venv/bin/pip install -r scripts/prototype-requirements.txt
python3 scripts/fetch_models.py
python3 scripts/build_manifests.py
python3 scripts/fetch_voice.py
.venv/bin/python scripts/prototype.py kokoro
.venv/bin/python scripts/prototype.py pocket
./gradlew testDebugUnitTest
```

This downloads large weights only into ignored `.deps/`. It verifies upstream release hashes before extraction. `build_manifests.py` regenerates the complete file inventory from those verified archives and the pinned alignment repository. Treat manifest changes as release/source changes requiring review. Normal app builds use the checked-in manifests unchanged.

`RealAudioTest` uses the same Kotlin alignment code as Android with actual model emissions and PCM artifacts. The small original WAV/source/timing fixtures in `docs/evidence` allow that regression test without model downloads. It verifies mapping and seeking math, **not human-perceived timing accuracy**. No timing-error numbers are fabricated.

## Instrumented tests

```bash
# Physical ARM64 API 35+ device:
./gradlew connectedDebugAndroidTest
# An x86_64 API 35+ emulator:
./gradlew -Pemulator=true connectedDebugAndroidTest
# Opt-in real model download + JNI + alignment + ExoPlayer seek test:
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.models=true
# Regenerate the original document fixtures if desired:
.venv/bin/python scripts/fixtures.py
```

Instrumentation checks platform PDF/OCR ingestion, rotated images, blank/corrupt/mixed pages, sandboxed saved-webpage extraction and Room position persistence. Unit tests cover UTF-16/source mappings, IDs, normalization, transforms, simple reading order, long sentence splitting, cache keys, corrupt assets/path traversal, late-generation cancellation and real-audio sample-boundary lookup.

The full physical-device protocol is in [DEVICE_VALIDATION.md](docs/DEVICE_VALIDATION.md); package/API/lifecycle decisions are in [ARCHITECTURE.md](docs/ARCHITECTURE.md). No performance target is presented as an achieved measurement.
