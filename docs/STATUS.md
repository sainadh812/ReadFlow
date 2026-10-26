# Delivery status

This is a native Android implementation with prototype and Play candidate build variants and real-audio host regression evidence. **The full core experience is not yet accepted:** the final release still needs target-device execution and manually checked audible word boundaries. No new phone performance, timing-error, offline, Bluetooth or 30-minute-session result is claimed.

The current candidate is **0.2.0** (version code 16), with Warm Paperback, Crisp Paper and Evening Paper themes, six bundled optional reader fonts, saved 5–34 font sizes and swipes in both reader views. Release uses Kokoro + Misaki exclusively, a clean direct-file model catalog, target API 36 and environment-based upload signing. Debug retains the historical speech comparisons. The user reported that the earlier Misaki trial worked on their phone; the new release artifact and appearance still require device validation. The [publisher handoff](PLAY_STORE_HANDOFF.md) explicitly requires a second license audit, real policy/contact information, signing and Play testing before publication.

User-supplied 0.1.12 benchmarks recorded ML Kit around 0.42 seconds and TeleOCR around 244 seconds per repeated synthetic page, with variable Kokoro preparation throughput. The earlier OCR accuracy/table scores are contaminated by narration punctuation, and TeleOCR memory was sampled after model teardown. Those reports cannot establish corrected OCR accuracy or working peak RAM; 0.1.13 changes the measurement protocol.

Earlier normalization, OCR, page swipes, speech-reserve and diagnostics fixes remain included. The user reported basic Kokoro speech on their SM-S938B, Android 16/API 36, and supplied normalization error reports. This is user-reported evidence, not independently measured timing or both-engine acceptance. Historical sections below retain their original version-specific results.

## Implemented

- Native Kotlin/Compose/Material 3 application: library, SAF import, pasted/shared HTTPS article import, reflowed reader, original PDF/image overlays, search, page/bookmark navigation, font size, dark mode, model manager and compact playback controls.
- Per-page native PDF extraction with exact search geometry when trustworthy; bundled Latin ML Kit OCR fallback, bounded rendering, blocks/lines/elements, reversible quarter-turn transforms, blank/corrupt/password error states. Mixed pages use one OCR representation, avoiding double reading.
- Original/reader/speech representations, UTF-16 offsets, stable positional IDs, source-to-spoken expansion mappings, conservative two-column ordering, bounded sentence splitting, optional geometric margin skipping.
- Real Sherpa Kotlin/JNI adapters for distinct Kokoro and Pocket exports; fixed voice catalog, cancellation callbacks, serialized lifecycle and unloading. Separate ONNX Runtime known-transcript CTC aligner, sample-based timings and confidence failure states.
- APK-pinned asset manifests with archive/file SHA-256s, exact download/installed sizes, licensed fixed voice, phonemizer/tokenizer/alignment resources, resumable downloads, retries/cancel/free-space checks, safe archive handling and atomic installation.
- Media3 ExoPlayer/MediaSessionService, audio focus, route-unplug handling, local WAV queue, actual media-position highlighting, sample-boundary seeking, source-word persistence, generation invalidation, bounded prefetch and thermal/memory pressure reduction.
- Room records for documents/pages/words/chunks/timings/positions/bookmarks; DataStore preferences; private managed file copies, LRU cache, exact audio hash validation, deletion and backup exclusion.
- Issue-only reports for caught import/extraction/normalization/model/alignment/playback failures and extraction warnings. Reports retain bounded input excerpts, source mappings, actual alignment confidence failures and exact available generated WAVs, with local inspection, explicit ZIP export and deletion. Success/cancellation does not create a report; no automatic upload. Best-effort Java crash capture and next-launch Android exit metadata are implemented.
- One combined JSONL history export and user-confirmed GitHub batch uploads into a single ongoing issue. Public metadata-only/private-excerpt modes, repeat grouping, server-ID deduplication/retry recovery, Android Keystore token protection and a host triage reader are implemented. There is no background upload scheduler or unattended fix agent.

## Verified so far

- JDK 17 and SDK platform 35/build-tools 34.0.0 available. Gradle 8.11.1 / AGP 8.6.1 / Kotlin 2.1.20 compile together. Android lint is independently pinned to 8.8.2 for Compose lint compatibility.
- Upstream Sherpa 1.13.8 Android AAR downloaded and SHA-256 checked. ARM64 JNI inspected with `readelf`: ONNX Runtime is statically linked there. A redundant 32-bit x86 runtime from the upstream AAR is excluded; supported ARM64/x86_64 binaries are retained as selected by the build.
- Both TTS model archives downloaded and checked against GitHub's upstream release digests. Full per-file inventories generated. Wav2Vec2 weights checked against the pinned Hugging Face LFS SHA-256. No large weights are source-controlled.
- Both engines synthesized the original paragraph on a Linux x86_64 host. The production Kotlin alignment algorithm returned all 17 source-word boundaries for each audio file. Monotonic ranges and media-position lookup at every start boundary passed. Evidence: `docs/evidence/{kokoro,pocket}`.
- A second, original OCR-punctuation fixture was synthesized on the host with both engines. Kokoro's normalized expansions aligned to their original source-word spans. Pocket's generated audio failed the unchanged confidence gate at the first word (`YOU`, about 0.012 versus 0.12 required); this is a recorded failure, not a word-sync success. Evidence: `docs/evidence/ocr-normalization`. No manually audited boundary accuracy is claimed.
- First full command `./gradlew assembleDebug testDebugUnitTest lintDebug` passed after correcting the upstream 32-bit native-library collision. Subsequent validation results are recorded below as they finish.
- JVM tests exercise core mappings, integrity/races and real-audio alignment for both engines. Final counts are recorded below. These are not Android/device or auditory acceptance tests.

## Partial or unverified

- Both model packs are labelled experimental. JNI compilation/API compatibility and host inference do not establish correct Android inference, audible timing, or phone performance.
- CTC posterior threshold and convolution-frame boundaries require auditory calibration. No mean/p95/max human-checked timing errors are available. Additional alignment memory and speed on Android are unmeasured; the dependency is visibly disclosed.
- Cached word jumps currently wait for an outstanding native request and pack verification before queue rebuild. They seek to an actual aligned boundary, but low latency is not guaranteed. Uncached jumps synthesize with sentence context and show preparation.
- Long sentences split at conservative normalized-text bounds. Broader per-engine token/phoneme-limit stress tests remain necessary. Preparation visits only the requested sentence and bounded successors; a normalization failure has an explicit Skip sentence action. Unknown equations remain unsupported. Superscript digits directly after sentence punctuation/quotes are read literally as "superscript" plus digits, without assuming exponent or footnote meaning.
- User-selected crop, selective region fusion and repeated-header classification are not implemented. Automatic deskew, focused retries, configurable effort and optional TeleOCR are implemented. Simple tables read in row order; arbitrary financial-table structure, complex layouts and general mathematical speech remain unsupported. Margin skipping is geometric and user-controlled. Full-page OCR on mixed/native-geometry-deficient pages can introduce recognition errors.
- The normalizer reads `|` literally as "vertical bar"; it does not guess whether OCR meant "I", a divider or a mathematical operator. Attached numerals after word-ending punctuation are read, not omitted or relabelled as footnotes. Original text/geometry remain unchanged. These fixes do not correct all OCR errors or expand general equation support.
- Joined hyphen fragments have many-to-one timing mappings and are highlighted together; only the primary fragment is the persisted reading position.
- Article extraction is local and sandboxed, but live websites requiring JavaScript/authentication/paywalls can fail. Table contents are omitted with a warning.
- PDF/image Original view is now the default, fitted to width with page controls. The current page preview can render while the separately serialized OCR task runs; only bounded page bitmaps are retained. This is a single-page pan/zoom viewer, not Edge's continuous multipage PDF UI. Android rendering, gesture ergonomics and extracted highlight geometry still require device checks.
- Downloads resume manually from persisted partial bytes, not automatically in a background worker. Process death restores a word position; background session resurrection is not implemented.
- No physical Galaxy S25 Ultra was attached. Hardware audio latency (especially Bluetooth), 1x/2x synchronization, rapid UI tap races, airplane-mode operation, focus interruptions, accessibility usability, power/thermal/memory and 30-minute sessions are unverified.
- Issue-log export UI, retention across phone restart and Android crash/ANR history recovery are not device-verified. Native crashes/OS kills lose in-memory input; next-launch reports contain OS metadata only. Reports do not attach original PDFs/images or full webpages. Unavailable storage, early startup failure or abrupt death can prevent logging; capture is not guaranteed for every issue.
- GitHub uploads require a user-supplied repository-scoped token configured on the phone. No account credential was embedded and no user report was uploaded during development. End-to-end phone upload, Keystore persistence and network interruption behavior remain unverified on the S25 Ultra. Private-repository visibility is checked before each batch, but the app cannot prevent an owner later making that repository public.
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

## 0.1.11 speech reserve and reader controls — 2026-10-25

The audio queue now targets four complete future sentences and 30 seconds at the current listening speed, with bounded startup/recovery and queue size. UI-hidden callbacks no longer shrink the reserve. A genuine memory trim expires after 60 seconds. External pause/stop cancels pending playback; rewind preserves the requested position while releasing held recovery audio. The reader exposes larger playback controls, speed, Original/Text, and prepared-audio status; the library has clearer document cards.

Validation on the final source:

- `./gradlew testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest --console=plain`: successful in 1m 36s.
- **218 JVM tests, 0 failures/errors/skips**, including 18 reserve-policy tests, 6 new performance-diagnostic tests, and 5 MediaSession forwarding tests. Tests cover split-sentence counts, speed, startup/EOF/caps, memory expiry/UI-hidden exclusion, bounded/private metrics, external no-op pause and seek delegation. The full coordinator/native player lifecycle is not simulated by these unit tests.
- Lint: **0 errors, 16 warnings** (dependency/target versions, exported media service and space checks). Application and instrumentation APKs compile. No Android device is attached; instrumentation and visual/device playback checks were not executed.
- Package `app.readflow`, version `0.1.11-prototype`, versionCode **12**, API 35+, ARM64. Signing certificate SHA-256 matches 0.1.10: `f7313f98e9414c166f3fb2d2a9329ba1bb5dd9d76b068254522d20d1f8b109f9`. Install-over compatibility is established by identity/version/certificate inspection, not a physical upgrade test.
- APK `ReadFlow-0.1.11-arm64-debug.apk`: **136,045,360 bytes**, SHA-256 `6f97bdd53b05639cb53b98afe66047e69bbec228635168f4ad5a0997b95530d7`. Previous native libraries remain present, including TeleOCR.

Required phone checks: foreground/background reading at 1× and 2×, short/long sentence transitions, next-page ML Kit/TeleOCR extraction, startup/recovery pause and rewind from app/notification/headset, final EOF, and large-font/light/dark UI. Copy playback diagnostics now exposes measured preparation times and underrun counts. No S25 speedup, uninterrupted-playback guarantee, GPU/NPU acceleration, or voice-quality change is claimed.

## 0.1.12 live metrics and phone testbench — 2026-10-25

The reader now has optional speech throughput, preparation headroom and buffer metrics. A cached page read preserves the latest measured extraction timing; cache hits and TeleOCR fallback cannot inflate engine throughput. Text recognition exposes ML Kit (Original) and TeleOCR clearly. Settings and reader styling use warm surfaces and teal light/dark accents.

The integrated testbench measures real synthesis, PCM16 WAV preparation, forced alignment and selected OCR engines against original synthetic reading/table fixtures. Models run sequentially behind an application-wide reservation. Reading and extraction drain before tests start; cancellation before dispatch and during native cleanup retains exclusivity until cleanup ends. Partial results survive cancellation/restart and can be copied explicitly. Sampled PSS/thermal/battery conditions and OCR errors are reported with their limits. See [usage and metric definitions](PERFORMANCE_AND_BENCHMARK.md).

Validation on the final source:

- `./gradlew testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest --console=plain`: successful. **250 JVM tests, 0 failures/errors/skips**; 32 additional tests cover performance attribution, fixture/error metrics, bounded persistence and cancellation/exclusivity lifecycle.
- Lint: **0 errors, 16 warnings**, in the existing dependency/target version, exported media service and filesystem-space categories. ARM64 application and instrumentation APKs compile. No device is attached; instrumentation, phone benchmark execution and visual checks were not run.
- Package `app.readflow`, version `0.1.12-prototype`, versionCode **13**, API 35+, ARM64. Certificate SHA-256 is unchanged: `f7313f98e9414c166f3fb2d2a9329ba1bb5dd9d76b068254522d20d1f8b109f9`. Identity/version/certificate inspection supports installing over earlier releases; a physical upgrade was not performed.
- APK `ReadFlow-0.1.12-arm64-debug.apk`: **137,072,283 bytes**, SHA-256 `def6cf7111b15596d05ed1f85004b18364c759dbe305e2b7cfe7ef5d133fc733`.

Required phone checks: toggle persistence and large-font/light/dark layout; switching OCR and refreshing saved pages; installed speech/OCR benchmark completion; cancel, background and rotation; retained/copied partial results; normal reading after cleanup. No measured S25 speedup, peak memory, pronunciation accuracy, audio-output latency or GPU/NPU benefit is claimed.

## 0.1.13 OCR choice, speech controls and sustained measurements — 2026-10-25

PaddleOCR mobile Latin joins ML Kit and TeleOCR as a real, optional local detector/recognizer with a pinned 12,886,734-byte download. TeleOCR/Paddle sessions stay warm between nearby pages and release on idle, memory pressure or explicit cleanup. Speech exposes real CPU thread/spinning controls and reuses bounded bandlimited-resampling filter tables. Preparing a selected page range fills the verified speech cache before listening, with cancellation, completed-chunk retention and storage limits.

The testbench compares all three OCR engines, sequential 2/3/4-thread speech configurations and optional 30/60-sample sustained runs. It samples ReadFlow PSS every second during inference, splits alignment/OCR stages and records cancellation reasons. Schema 2 scores raw recognition before narration punctuation and reports structured table cells separately. Earlier schema-1 accuracy and memory values use a different method. A separately installed SC Kokoro engine can be tested through Android TTS, explicitly labeled `external-unverified`; its process memory is excluded. Embedded GPU/NPU acceleration and native-duration word highlighting are not implemented. Normal reading retains internal speech and validated CTC alignment.

Validation on the final source:

- `./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain`: successful in 1m 48s. **292 JVM tests, 0 failures/errors/skips**. Tests cover Paddle DB/CTC/geometry, resampling equivalence and anti-aliasing, runtime settings, PCM decoding, prepare-range budgets, in-flight memory aggregation and raw OCR scoring regressions.
- Lint: **0 errors, 17 warnings**, in the existing dependency/target version, exported media service and filesystem-space categories. ARM64 application and instrumentation APKs compile. No Android device is attached; instrumentation, physical upgrade, visual and phone performance checks were not executed.
- Official Paddle ONNX weights ran on host ORT with production Kotlin DB/CTC postprocessing. The original fixture preserved Milanković, Köppen, 20-24, $9.3 and all twelve table cells; two curly apostrophes became straight apostrophes. [Host evidence](evidence/paddleocr/host-smoke.json) is a compatibility check, not a general accuracy score or S25 latency measurement.
- Package `app.readflow`, version `0.1.13-prototype`, versionCode **14**, API 35+, ARM64. APK Signature Scheme v2 verifies. Certificate SHA-256 remains `f7313f98e9414c166f3fb2d2a9329ba1bb5dd9d76b068254522d20d1f8b109f9`; identity/version/certificate inspection supports installing over earlier releases without uninstalling.
- APK `ReadFlow-0.1.13-arm64-debug.apk`: **138,036,083 bytes**, SHA-256 `db05fb1a472d4baff6a97cead2ab2f770fc457797f608c7dcdd0b69074842039`. Speech, alignment and optional OCR weights remain separate downloads.

Required phone checks: all three OCR engines on representative book/table pages; warm transitions and cancellation/rotation/backgrounding; sustained CPU comparisons under comparable thermal conditions; prepare-range cache limits and playback afterward; exact-name pronunciation and sentence gaps; optional external-engine interoperability. No S25 speedup, peak-memory capture, uninterrupted-reading guarantee or verified GPU placement is claimed.


## 0.1.14 Misaki pronunciation experiment — 2026-10-25

- Added optional Misaki dictionary/POS/neural pronunciation and a direct ONNX Kokoro v0.19 backend. Current Kokoro/eSpeak remains the default; existing acoustic/voice/alignment downloads are reused. Separate cache identities prevent replaying legacy speech as a Misaki result. Model and pronunciation transitions drain preceding native work before new reading begins.
- Added a listening sample in Voice models and a pronunciation comparison in Phone testbench, including input/normalized text and separate frontend/acoustic timings. Upgraded shared ONNX Runtime to 1.22.0. The frontend has pinned Go modules, model-asset hashes and bundled notices; its new native library has no eSpeak import. The old Sherpa/eSpeak binary still ships, so app-wide GPL obligations remain.
- `./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain` passed. **309 JVM tests**, zero failures/errors/skips. Lint: zero errors and 17 warnings in existing categories. After adding runtime license assets, `assembleDebug` repackaged the final APK; no executable-source changes followed the checks.
- Exact C ABI frontend host smoke and upstream Go real-inference race tests passed. Five paired Misaki/legacy host phrases produced finite 24 kHz PCM. Reports explicitly distinguish functional checks from pronunciation quality; the raw accented name and some present-tense “read” cases remain problematic. Generated WAVs stay under ignored `.deps`; only synthetic-text reports are committed.
- ARM64 app and instrumentation APKs compiled. All eight packaged native libraries have 16 KB ELF load-segment alignment; Android build-tools 35 zipalign passed the 16 KB APK check. Package `app.readflow`, versionCode **15**, minimum/target API 35. Signing certificate SHA-256 remains `f7313f98e9414c166f3fb2d2a9329ba1bb5dd9d76b068254522d20d1f8b109f9`, supporting install-over compatibility by inspection.
- APK `ReadFlow-0.1.14-arm64-debug.apk`: **166,887,159 bytes**, SHA-256 `94f8a046fae2dfc3e53073725ab4fd6ed2769b8d047920723fb22c7cd8f70f34`. Small phonemizer assets are embedded; acoustic and alignment weights remain separate downloads.
- No Android device was attached. The packaged frontend tests were compiled, not executed on Android. S25 pronunciation, sustained speed, memory, heat, listening gaps and physical upgrade remain unverified. See [trial details](MISAKI_TRIAL.md) and `evidence/misaki/`.
