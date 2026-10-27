# Read Assist

A native Android reading application built with Kotlin, Compose and local speech synthesis. **Read Assist** is the new displayed name in source; the package remains `app.readflow` and this repository remains ReadFlow. The owner has chosen [free distribution first](docs/FREE_DISTRIBUTION_PLAN.md): all existing features stay free, with optional coffee support. Paid tiers, quotas and coupons are deferred. The [second license audit](docs/PLAY_REAUDIT_2026-10-26.md) and [updated Muse Spark handoff](docs/PLAY_STORE_HANDOFF.md) distinguish completed checks from remaining publication work.

The current source is **Read Assist 0.2.5** (version code 21), adding optional **Firebase Crashlytics** and **Report a problem** by email. Reports are off by default; the email flow previews exactly what will be sent and includes reduced technical details only when selected. Production builds no longer ask customers for GitHub tokens. **Muse Spark reports the free Firebase project is created. The upload key and GitHub secrets are ready by name; the workflow accepts the existing Firebase secret under its earlier spelling. A configured build and real-device delivery checks are still required.** This review build has no Firebase project configuration and clearly shows reporting as unavailable. See the [GitHub build setup](docs/GITHUB_RELEASE_BUILD.md), [setup and publishing handoff](docs/PLAY_STORE_HANDOFF.md), [diagnostics design](docs/PRODUCTION_DIAGNOSTICS.md) and [validation status](docs/STATUS.md).

The approved developer name is **Oh My Pi**, and support/privacy contact is **xperimenting.ai@gmail.com**. The [public Privacy Policy](https://sainadh812.github.io/ReadFlow/privacy.html) now covers optional crash reporting and support email. All existing features remain free. **Settings → Buy me a coffee** retains optional UPI support to `readassist@ybl`; registration, ownership and payment-app acceptance still need verification. Physical-device testing, final licensing review and production signing remain publication requirements.

## Install on a phone

**[Download Read Assist 0.2.5 Play preview — Android 15+ ARM64](https://github.com/sainadh812/ReadFlow/releases/download/v0.2.5-play-candidate/Read-Assist-0.2.5-play-preview.apk).** It supports the email-report flow; automatic Crashlytics reporting remains unavailable until the publisher builds with the owner's Firebase configuration. It uses the existing prototype certificate and can update matching prototype installations. The [review release](https://github.com/sainadh812/ReadFlow/releases/tag/v0.2.5-play-candidate) includes an unsigned AAB, source, handoff and checksums. These are not production-signed Play installations.

The [historical 0.2.2 APK](https://github.com/sainadh812/ReadFlow/releases/download/v0.2.2-play-candidate/Read-Assit-0.2.2-play-preview.apk) and [release](https://github.com/sainadh812/ReadFlow/releases/tag/v0.2.2-play-candidate) remain available with their original **Read Assit** spelling and previous recipient. They do not contain this correction.

Tap **Aa** in the reader for **Warm Paperback**, **Crisp Paper** or **Evening Paper**, plus **OpenDyslexic, Atkinson Hyperlegible Next, Lexend, Literata, Source Serif 4 and Noto Sans**. Pick a size from **5–34** and **Save as my default**. Paper/font choices persist independently of size. Original PDF pages retain their source fonts and colours. Swipe right for next page, left for previous; text view now supports page swipes too.

The Play build contains only the direct Kokoro + Misaki speech path and a clean Kokoro download manifest without eSpeak data. **0.2.1 fixes the upgrade error “Download kokoro first”:** starting playback or a speech benchmark automatically checks and migrates compatible existing Kokoro and word-alignment files, without downloading them again. Missing or damaged files still require setup in Voice models. Debug builds retain legacy speech comparisons and their license obligations. **Settings → Privacy and licenses** contains packaged notices. The [publishing agent prompt](docs/PLAY_STORE_HANDOFF.md) now assigns Console entry tasks to Muse Spark and identifies the technical/policy work that must be completed before upload.

The entries below describe earlier prototype releases and their features; legacy speech choices described there are not offered in the Play variant.

Version **0.1.14** adds an optional **Kokoro + Misaki** pronunciation experiment. In **Settings → Voice models**, select Misaki and tap **Try pronunciation sample**, or compare it with the current Kokoro engine in **Phone testbench**. Your existing model downloads are reused. The new path uses a neural fallback without eSpeak, but the prototype APK still bundles the old engine and retains its GPL obligations. This is not a Play Store licensing clearance. See [the trial, tests and limitations](docs/MISAKI_TRIAL.md).

Version **0.1.13** adds **PaddleOCR mobile Latin** alongside ML Kit and TeleOCR, with a pinned ~13 MB optional model pack. TeleOCR and PaddleOCR reuse warm sessions and release them after idle time or memory pressure. **Settings > Performance** controls speech CPU effort; **Reader menu > Prepare pages before listening** caches a chosen chapter/page range. The upgraded testbench scores raw OCR before narration formatting, samples memory during inference, compares CPU thread counts and runs sustained speech tests. An optional SC Kokoro external-engine experiment is available in the testbench; it requires a separate installation and cannot verify GPU placement. Native Kokoro duration timings are not available from the current audio-only export; validated CTC remains active. See [testbench and limits](docs/PERFORMANCE_AND_BENCHMARK.md). No new S25 performance gain is claimed before phone testing.

Version **0.1.12** adds an optional live performance card, **Settings > Phone testbench**, and a clearer **ML Kit · Original / TeleOCR** choice under Text recognition. The card reports speech characters/minute, preparation speed relative to listening, and buffered audio; hide it with its close button or in Settings. The testbench measures real local speech generation, word alignment and OCR on original synthetic reading/table samples, with sampled memory and thermal information. It pauses reading and keeps completed results if cancelled. The refreshed UI uses warmer surfaces, teal accents and grouped settings. See [metric definitions, testbench and limits](docs/PERFORMANCE_AND_BENCHMARK.md). Phone results are produced on your device; no S25 Ultra speed claim is inferred from the build.

Version **0.1.11** prepares the current sentence plus **four complete sentences ahead**, targeting at least **30 seconds at your playback speed** within bounded storage/queue limits. Startup and recovery build a reserve; playable audio waits at most eight additional seconds after the first prepared chunk, and short documents start when preparation ends. Backgrounding no longer counts as low memory. The reader has larger playback controls, direct speed and Original/Text buttons, visible audio readiness, and a cleaner library. Speech models and pronunciation quality are unchanged. This reduces avoidable gaps; sustained smoothness still depends on generation keeping up on your phone. **Settings > Copy playback diagnostics** now includes content-free preparation times and underrun counts.

Version **0.1.10** adds **Settings > Text recognition** with Low/Fast, Medium/Balanced and High/Accuracy effort, plus an optional **TeleOCR** engine running locally. TeleOCR requires a separate **1.394 GB** verified model download and is experimental: phone speed/accuracy are not yet measured. Choose the model after downloading, then **Apply to current page** to refresh a saved page. Original-page word highlights are retained where ML Kit supplies matching word geometry; other recognized words remain available in text view. Tables read row by row. See [OCR setup and validation](docs/OCR_MODELS_AND_PRESETS.md).

Version **0.1.9** fixes attached comma references, compact number ranges, currency magnitudes and the reported year/storage entries. The PDF page stays fitted within bounded scrolling and zooming. Swipe **right for next page**, **left for previous page** at normal zoom; reverse or disable this in **Settings**. The reader menu also offers previous/next page; tap the page count to jump to a page. See [behavior and validation](docs/NUMBERS_AND_PAGE_SWIPES.md).

Version **0.1.8** fixes accented Latin speech, including `Milanković’s` and `Köppen`, while retaining the original spelling and word highlighting. OCR now uses more source detail, conservative automatic straightening and bounded retries for uncertain lines. For an already saved page, choose **Reader menu > Retry text recognition**; speech corrections apply without reimporting. No new model download is needed. See [behavior and validation](docs/ACCENTS_AND_OCR.md).

Version **0.1.7** adds playback recovery when word alignment is unreliable, prevents idle dimming in the visible reader, and gives the PDF more room by removing the horizontal seek slider and moving secondary controls into menus. The reader has three main playback buttons; selected-word playback, sentence skipping and follow controls no longer cover the page. Tap the expand icon to hide both control rows, then use the restore icon or Android Back to bring them back. See [diagnosis, behavior and validation](docs/READER_RECOVERY_FIX.md) and the [latest uploaded logs](docs/READER_LATEST_LOGS.md). Build the APK with the instructions below; the latest published download remains linked here until a release is published.


Version 0.1.6 adds a **combined error-history export** and **Send new errors** to GitHub. Reports append as batches to one ongoing diagnostics issue; repeated events are grouped and previously sent IDs are skipped. Public repositories receive sanitized metadata only. Optional failing-input excerpts require a private repository and confirmation on every send. Set up a repository-scoped token on the phone under **Settings > Issue logs > GitHub settings**. No token is bundled in the APK. See [setup and verification](docs/GITHUB_DIAGNOSTICS.md).

Version 0.1.5 fixes two reported normalization stops: OCR tokens such as `money.4` now read literally as "money. four", and `|` reads as "vertical bar". The latter is not automatically changed to "I": it may be an OCR error, but its meaning is ambiguous. Displayed text, geometry and source-word IDs are unchanged. No reimport or model download is needed; changed speech cache entries regenerate. Normalization issue logs now identify the exact rejected token. See [OCR text fixes and actual validation](docs/OCR_TEXT_FIX.md), including a separate Pocket alignment failure found during host testing.

Version 0.1.4 adds **automatic issue-only logs** for import/parsing, extraction warnings, unsupported speech text, low-confidence alignment, model operations and playback errors. Normal successful operations and user cancellations do not create reports. Logs capture bounded failing input, source-word mappings, error details, model/device versions and, when available, the exact generated audio. Nothing is uploaded automatically.

After an issue, open **Settings > Issue logs**, select the report, tap **Export issue log** (save icon), and choose a destination for the ZIP. Attach that ZIP when asking for help. **Review it first: it can contain private document text, source URLs and speech audio. Do not post private logs to a public GitHub issue.** Individual/all logs can be deleted. See [issue logging and verification](docs/ISSUE_LOGS.md), including limits for native crashes and missing input.

The history screen's save icon exports all retained events into one `issues.jsonl` inside a ZIP, without audio. Individual exports still support audio. Local retention remains bounded to 20 reports/24 MiB; this is not an unlimited lifetime archive. Uploaded GitHub history remains until you delete it on GitHub. Uploads do not start an unattended coding agent; ask the agent to review the diagnostics issue when ready.

Version 0.1.3 fixes ordinary webpage punctuation and technical identifiers being rejected as equations. PDFs now open in **Original PDF** view, fitted to the screen width, with direct page controls. Preview rendering does not wait for OCR; word selection becomes available when extraction finishes. The reflowed Reader remains optional. Unsupported sentences stop preparation with an explicit **Skip sentence** action instead of blocking navigation throughout the page.

Install over the existing app without uninstalling or clearing data to preserve downloaded models and documents. The 0.1.2 native speech-callback crash fix remains included. **Settings > Copy playback diagnostics** remains a separate content-free summary; use the new Issue logs export when the failing input is needed. See the [text/PDF fix report](docs/TEXT_PDF_FIX.md) for earlier checks and remaining device verification.

The 0.1.1 installation fixes remain included. Open **Voice models**, then choose **Resume setup** for interrupted packs. Completed downloads are verified and reused. Word highlighting also requires the separate **Word alignment** pack. Keep ReadFlow open until setup finishes; interrupted setup can be resumed after reopening the app.

See the [playback crash fix](docs/PLAYBACK_CRASH_FIX.md) and [installation fix](docs/INSTALLATION_FIX.md) reports. APKs are attached to GitHub Releases because they exceed GitHub's normal Git file-size limit.

## Build

Use JDK 17, Android SDK platform 36 / build-tools 35.0.0, NDK 27.2.12479018 and CMake 3.22.1. Minimum Android 15/API 35 is an MVP scope choice, not a permanent requirement. Default APK is ARM64 only. Build tools and dependencies are explicitly pinned in Gradle; Sherpa/Readability bootstrap assets have SHA-256 locks. The bootstrap script prepares a pinned, patched llama.cpp checkout for the local TeleOCR JNI runtime.

For the Play variant, use `bash scripts/bootstrap.sh --play` and `./gradlew bundleRelease testReleaseUnitTest lintRelease`. With no upload credentials this produces an **unsigned** review bundle. Publication configuration and the final license/device audit are documented in [PLAY_STORE_HANDOFF.md](docs/PLAY_STORE_HANDOFF.md). The debug workflow below is for legacy prototype development.

```bash
cd ReadFlow
# Point ANDROID_HOME at your SDK, or set sdk.dir in ignored local.properties.
bash scripts/bootstrap.sh
./gradlew assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.readflow/.MainActivity
```

`bootstrap.sh` needs git, curl, sha256sum and Python 3.10+ only as build/download tools. **The installed app has no Python dependency or server.** A checked-in Gradle wrapper downloads the pinned Gradle 8.11.1 distribution with checksum verification. Large speech/OCR packs are not required to compile the application. The Misaki native build embeds small dictionary/POS/G2P assets fetched through checksum-pinned Go modules; Gradle runs `scripts/prepare_misaki.py` automatically. This step requires the pinned NDK and Go 1.26.4 (downloaded automatically on Linux x86_64), plus Python with the tarfile data extraction filter.

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
