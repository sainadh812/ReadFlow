# Delivery status

This is a native Android implementation with a built debug APK and real-audio host regression evidence. **The full core experience is not yet accepted:** both engines still need Android target execution and manually checked audible word boundaries. No phone performance, timing-error, offline, Bluetooth or 30-minute-session result is claimed.

The latest patch is **0.1.3-prototype**, addressing webpage normalization failures, page-wide preparation failures, and PDF viewing. See [TEXT_PDF_FIX.md](TEXT_PDF_FIX.md). The user reports that basic Kokoro speech worked on their SM-S938B, Android 16/API 36, with 0.1.2; that is user-reported smoke evidence, not independently measured timing or both-engine acceptance. The [native callback fix](PLAYBACK_CRASH_FIX.md) and [installation fix](INSTALLATION_FIX.md) remain included. Earlier build results below describe the original 0.1.0 delivery; `evidence/build-results.json` records the latest build.

## Implemented

- Native Kotlin/Compose/Material 3 application: library, SAF import, pasted/shared HTTPS article import, reflowed reader, original PDF/image overlays, search, page/bookmark navigation, font size, dark mode, model manager and compact playback controls.
- Per-page native PDF extraction with exact search geometry when trustworthy; bundled Latin ML Kit OCR fallback, bounded rendering, blocks/lines/elements, reversible quarter-turn transforms, blank/corrupt/password error states. Mixed pages use one OCR representation, avoiding double reading.
- Original/reader/speech representations, UTF-16 offsets, stable positional IDs, source-to-spoken expansion mappings, conservative two-column ordering, bounded sentence splitting, optional geometric margin skipping.
- Real Sherpa Kotlin/JNI adapters for distinct Kokoro and Pocket exports; fixed voice catalog, cancellation callbacks, serialized lifecycle and unloading. Separate ONNX Runtime known-transcript CTC aligner, sample-based timings and confidence failure states.
- APK-pinned asset manifests with archive/file SHA-256s, exact download/installed sizes, licensed fixed voice, phonemizer/tokenizer/alignment resources, resumable downloads, retries/cancel/free-space checks, safe archive handling and atomic installation.
- Media3 ExoPlayer/MediaSessionService, audio focus, route-unplug handling, local WAV queue, actual media-position highlighting, sample-boundary seeking, source-word persistence, generation invalidation, bounded prefetch and thermal/memory pressure reduction.
- Room records for documents/pages/words/chunks/timings/positions/bookmarks; DataStore preferences; private managed file copies, LRU cache, exact audio hash validation, deletion and backup exclusion.

## Verified so far

- JDK 17 and SDK platform 35/build-tools 34.0.0 available. Gradle 8.11.1 / AGP 8.6.1 / Kotlin 2.1.20 compile together. Android lint is independently pinned to 8.8.2 for Compose lint compatibility.
- Upstream Sherpa 1.13.8 Android AAR downloaded and SHA-256 checked. ARM64 JNI inspected with `readelf`: ONNX Runtime is statically linked there. A redundant 32-bit x86 runtime from the upstream AAR is excluded; supported ARM64/x86_64 binaries are retained as selected by the build.
- Both TTS model archives downloaded and checked against GitHub's upstream release digests. Full per-file inventories generated. Wav2Vec2 weights checked against the pinned Hugging Face LFS SHA-256. No large weights are source-controlled.
- Both engines synthesized the original paragraph on a Linux x86_64 host. The production Kotlin alignment algorithm returned all 17 source-word boundaries for each audio file. Monotonic ranges and media-position lookup at every start boundary passed. Evidence: `docs/evidence/{kokoro,pocket}`.
- First full command `./gradlew assembleDebug testDebugUnitTest lintDebug` passed after correcting the upstream 32-bit native-library collision. Subsequent validation results are recorded below as they finish.
- JVM tests exercise core mappings, integrity/races and real-audio alignment for both engines. Final counts are recorded below. These are not Android/device or auditory acceptance tests.

## Partial or unverified

- Both model packs are labelled experimental. JNI compilation/API compatibility and host inference do not establish correct Android inference, audible timing, or phone performance.
- CTC posterior threshold and convolution-frame boundaries require auditory calibration. No mean/p95/max human-checked timing errors are available. Additional alignment memory and speed on Android are unmeasured; the dependency is visibly disclosed.
- Cached word jumps currently wait for an outstanding native request and pack verification before queue rebuild. They seek to an actual aligned boundary, but low latency is not guaranteed. Uncached jumps synthesize with sentence context and show preparation.
- Long sentences split at conservative normalized-text bounds. Broader per-engine token/phoneme-limit stress tests remain necessary. Preparation visits only the requested sentence and bounded successors; a normalization failure has an explicit Skip sentence action. Unknown equations remain unsupported. Superscript digits directly after sentence punctuation/quotes are read literally as "superscript" plus digits, without assuming exponent or footnote meaning.
- OCR crop/automatic deskew, selective region fusion, complex-layout reading order and repeated-header classification are not implemented. Margin skipping is geometric and user-controlled. Tables/math are unsupported. Full-page OCR on mixed/native-geometry-deficient pages can introduce recognition errors.
- Joined hyphen fragments have many-to-one timing mappings and are highlighted together; only the primary fragment is the persisted reading position.
- Article extraction is local and sandboxed, but live websites requiring JavaScript/authentication/paywalls can fail. Table contents are omitted with a warning.
- PDF/image Original view is now the default, fitted to width with page controls. The current page preview can render while the separately serialized OCR task runs; only bounded page bitmaps are retained. This is a single-page pan/zoom viewer, not Edge's continuous multipage PDF UI. Android rendering, gesture ergonomics and extracted highlight geometry still require device checks.
- Downloads resume manually from persisted partial bytes, not automatically in a background worker. Process death restores a word position; background session resurrection is not implemented.
- No physical Galaxy S25 Ultra was attached. Hardware audio latency (especially Bluetooth), 1x/2x synchronization, rapid UI tap races, airplane-mode operation, focus interruptions, accessibility usability, power/thermal/memory and 30-minute sessions are unverified.
- No approved reading-screen image was attached/accessibly located, so visual matching to that specific reference is unverified.

## Host timing evidence

Host: Linux 6.8.0-1066-gcp, Intel Xeon 2.20 GHz VM, 4 logical CPUs / 2 physical cores, x86_64. Sherpa 1.13.8 CPU with two threads; host ORT 1.20.1 with two emission threads. The first smoke runs overlapped each other and a Gradle build, so the following values are **not controlled benchmarks or a model comparison**:

| Export | TTS load | Synthesis | Alignment load + emissions | Audio length | Combined RTF* |
|---|---:|---:|---:|---:|---:|
| Kokoro v0.19 | 15.885 s | 15.475 s | 3.433 s | 5.046 s | 3.747 |
| Pocket int8 2026-01-26 | 10.209 s | 11.419 s | 3.399 s | 3.499 s | 4.235 |

*Excludes TTS load; includes alignment-session load and host preprocessing. Kotlin Viterbi time is tested separately, not included. No S25 Ultra inference can be drawn. Warm-load, OCR, first-audible, jump, peak app memory, battery and thermal measurements are not available. Optimization targets were not met or established by these smoke tests.

## Emulator verification blocked

There is no `/dev/kvm` and no physical device attached. Installed API 35 Google APIs x86_64 system image and created `readflow_api35`; emulator version 37.1.11 ran with software CPU emulation and two cores. Requested RAM was 2048 MB; the emulator automatically raised it to 2560 MB:

```bash
sdkmanager 'system-images;android-35;google_apis;x86_64'
avdmanager create avd --name readflow_api35 --package 'system-images;android-35;google_apis;x86_64' --device pixel_7
emulator -avd readflow_api35 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect -accel off -cores 2 -memory 2048 -no-boot-anim
./gradlew -Pemulator=true assembleDebug assembleDebugAndroidTest --console=plain
./gradlew -Pemulator=true connectedDebugAndroidTest --console=plain
adb install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
```

The x86_64 app/test APKs compiled. The connected test command failed **twice before app tests ran**: `ShellCommandUnresponsiveException`, device `Unknown API Level`, and `No compatible devices connected`. Single-property ADB queries did return API 35, but `sys.boot_completed` never became `1`. A 120-second ADB installation timeout did not fix the property-fetch problem. Direct installation transferred the APK, then failed in Android's PackageManager/StorageManager with a null `PackageManagerInternal` during startup. No application UI screenshot or successful installation is claimed. The emulator was shut down with `adb emu kill`.

The instrumentation suites compile and are supplied for a working API 35+ device: local PDF/OCR/web/Room tests plus an opt-in real-model JNI/alignment/ExoPlayer seek test. Run the latter with `-Pandroid.testInstrumentationRunnerArguments.models=true`. See `DEVICE_VALIDATION.md` for the physical-device protocol.

## Original 0.1.0 build results

Executed in `/home/archgen_guest_1/ReadFlow` on 2026-09-19:

```bash
bash scripts/bootstrap.sh
python3 scripts/fetch_models.py
python3 scripts/build_manifests.py
.venv/bin/python scripts/prototype.py kokoro
.venv/bin/python scripts/prototype.py pocket
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
```

- Bootstrap/assets: successful checksum verification and download. The model fetch helper initially used a Python 3.11-only API; it was corrected for Python 3.10 and rerun successfully.
- Both prototype commands: exit 0; real PCM, emissions, source mappings, timings and host run metadata produced. Production Kotlin alignment test checks every one of the 17 words for each engine.
- Final Gradle command: **exit 0, BUILD SUCCESSFUL in 1m 10s**. Built both ARM64 application and instrumentation APKs.
- **17 JVM tests, 0 failures, 0 errors, 0 skipped**: 12 core, 4 integrity/race, 1 real-audio regression covering both TTS engines.
- Lint: **0 errors, 15 warnings**. Warnings concern newer dependency versions being available, the intentionally exported MediaSessionService, and use of filesystem free-space checks instead of StorageManager allocation. Pinned older stable versions are deliberate; no Play Store release compliance is claimed. Deprecated APIs produce compiler warnings but do not prevent the build.
- A previous build failed on a redundant upstream x86 `libonnxruntime.so`; excluding unsupported 32-bit ABIs fixed that collision. Compose lint compatibility was fixed by the officially documented lint-version override to 8.8.2.
- `docs/evidence/build-results.json` records report-derived counts, exact APK size/hash, and ARM64-only/no-speech-weights inspection. `dist/SHA256SUMS.json` records delivery artifact checksums.
- `apksigner verify --verbose dist/ReadFlow-arm64-debug.apk`: exit 0, APK Signature Scheme v2 verified, one debug signer. `aapt dump badging` confirms package `app.readflow`, version `0.1.0-prototype`, minimum/target API 35 and only `arm64-v8a`. APK size is 130,103,850 bytes; SHA-256 `f7c40c5b1cf0005db97de319d9b3c474820ba4aec78623ed06064774e2e75163`.
- Delivered APK: `dist/ReadFlow-arm64-debug.apk`. Source archive: `dist/ReadFlow-source.zip`. Build/run instructions, trusted manifests, original fixtures, setup scripts and license notices are included in the source archive; weights/downloaded third-party trees are excluded.

Core acceptance remains **incomplete**: runtime/device/auditory checks and the implementation limitations listed above remain open. A successful build and host regression cannot establish the complete requested on-device experience.
