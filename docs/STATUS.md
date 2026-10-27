# Delivery status

As of 2026-10-27, the current source is **Read Assist 0.2.5** (code 21), package `app.readflow`. It adds default-off Firebase Crashlytics integration, reduced technical error reports, a previewed support-email flow and production guards against GitHub token/upload use. New consent requires a process restart; disabling blocks app-submitted reports immediately and asks the SDK to discard pending reports, with SDK/re-upload limitations disclosed. See [implementation and checks](PRODUCTION_DIAGNOSTICS.md).

**Connection status:** Muse Spark reports the Firebase Spark project `read-assist-fbd21` is created with Analytics disabled and Android app `1:831366793321:android:573b80e46b55eb65103af8`. The file is absent from this coding workspace, but the existing repository secret `READASSIT_FIREBASE_CONFIG_BASE64` is now accepted by the workflow. No repeat transfer is needed; the stored value passed configured-build validation and the final APK resource check. A new upload key and four GitHub signing secrets have been created and verified here. See [GitHub build setup](GITHUB_RELEASE_BUILD.md) and the [public certificate record](evidence/play-store-upload/upload-key.json). The old review APK remains unconfigured. The new upload-signed candidate is configured and verified; real report delivery remains unverified.

**Configured 0.2.5 candidate:** [successful run](https://github.com/sainadh812/ReadFlow/actions/runs/40529659238) and [signed APK/AAB](https://github.com/sainadh812/ReadFlow/actions/runs/40529659238/artifacts/12745713255), source `f025430e9e18d65fc317afe06e5bde06acb7fb47`. **360 tests passed, three real-model-fixture tests skipped; lint 0 errors / 34 warnings.** Firebase destination, signing, 16 KB, font/policy and native-ID checks passed; downloaded hashes and signatures independently verified. All 144 runtime dependency hashes and bundled notices are preserved. Native symbols are retained but not uploaded; device/crash-delivery and Play checks remain pending. See [configured evidence](evidence/play-store-0.2.5-configured/release-evidence.json).

**Public policy:** https://sainadh812.github.io/ReadFlow/privacy.html was updated and verified on 2026-10-27. The served HTML matched source exactly (SHA-256 `cf51f940459aa7ceb246790508aefe5637c5a7c09f6ecff65a5edd94de01f32f`, Pages commit `b4aea432664121f5bf82504cd165738f57e982c0`). Developer name: **Oh My Pi**; contact: **xperimenting.ai@gmail.com**. See [publication evidence](PRIVACY_PUBLICATION.md). Support correspondence is deleted within 90 days after resolution; Firebase has its separately disclosed retention policy.

**Historical unconfigured 0.2.5 validation:** all **363 release JVM tests passed**, lint has **zero errors and 34 warnings**, and bundle/font/16 KB/signature checks passed. The temporary Firebase configuration exercised plugin resources and local symbol generation; it was removed and is absent from the review artifacts. No reports were uploaded. Misaki’s packaged/unstripped GNU build IDs match, and all 143 generated/supplemental dependency-notice files match the AAB.

**0.2.5 review artifacts:** [release](https://github.com/sainadh812/ReadFlow/releases/tag/v0.2.5-play-candidate), [build evidence](evidence/play-store-0.2.5/build-results.json), [packaging](evidence/play-store-0.2.5/packaging.json), and [Firebase dependency review](evidence/play-store-0.2.5/firebase-dependencies.json). The preview uses the existing prototype certificate; the AAB is unsigned. All current features stay free, with optional `readassist@ybl` support; owner registration/payment-app verification is pending. The [Muse Spark handoff](PLAY_STORE_HANDOFF.md) covers the remaining native-symbol upload, policy/Console, licensing and device acceptance work.

**Historical 0.2.4:** [release](https://github.com/sainadh812/ReadFlow/releases/tag/v0.2.4-play-candidate), 335 release JVM tests passed, lint zero errors/28 warnings. It added the policy URL and has no Crashlytics integration. Its build evidence applies only to that version.

**Historical 0.2.3 review artifacts are published:** `Read-Assist-0.2.3-play-preview.apk` and `Read-Assist-0.2.3-play-unsigned.aab`, under `v0.2.3-play-candidate`. They lack the configured public policy URL added in 0.2.4. All 335 release tests passed for 0.2.3; lint had zero errors and 28 warnings. Signature and 16 KB ZIP checks passed. See [0.2.3 evidence](evidence/play-store-0.2.3/build-results.json). These results do not validate 0.2.4; no new device or payment validation was performed.

Historical **0.2.2** (Read Assit, code 18) release/debug JVM tests passed **335/334**, with zero failures/errors/skips; release lint has zero errors and 28 warnings. That historical preview retained the prototype certificate and passed 16 KB ZIP alignment. See [0.2.2 build evidence](evidence/play-store-0.2.2/build-results.json). No UPI transfer, recipient verification or device acceptance was performed.

This is a native Android implementation with prototype and Play candidate build variants and real-audio host regression evidence. **The full core experience is not yet accepted:** the final release still needs target-device execution and manually checked audible word boundaries. No new phone performance, timing-error, offline, Bluetooth or 30-minute-session result is claimed.

The historical **0.2.1** candidate (version code 17) shipped with Warm Paperback, Crisp Paper and Evening Paper themes, six bundled optional reader fonts, saved 5–34 font sizes and swipes in both reader views. Release uses Kokoro + Misaki exclusively, a clean direct-file model catalog, target API 36 and environment-based upload signing. Debug retains the historical speech comparisons. The user reported that the earlier Misaki trial worked on their phone; the new release artifact and appearance still require device validation. The [publisher handoff](PLAY_STORE_HANDOFF.md) links the completed second audit and identifies real policy/contact information, final artifact review, signing and Play testing still needed before publication.

User-supplied 0.1.12 benchmarks recorded ML Kit around 0.42 seconds and TeleOCR around 244 seconds per repeated synthetic page, with variable Kokoro preparation throughput. The earlier OCR accuracy/table scores are contaminated by narration punctuation, and TeleOCR memory was sampled after model teardown. Those reports cannot establish corrected OCR accuracy or working peak RAM; 0.1.13 changes the measurement protocol.

Version 0.2.1 fixes the 0.2.0 upgrade regression that rejected compatible Kokoro/alignment files because their manifest identities changed. Playback and benchmarks now perform checked local migration before inference; failed checks preserve the previous setup and require explicit repair. Earlier normalization, OCR, page swipes, speech-reserve and diagnostics fixes remain included. The user reported basic Kokoro speech on their SM-S938B, Android 16/API 36, and supplied normalization error reports. This is user-reported evidence, not independently measured timing or both-engine acceptance. Historical sections below retain their original version-specific results.

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

## 0.2.0 Play candidate, paper themes and licensed fonts — 2026-10-26

- Added Warm Paperback, Crisp Paper and Evening Paper reader palettes, subtle cached paper grain, an in-reader appearance sheet, six optional offline font families and an explicit saved size from 5–34. Theme/font choices persist independently. Original PDFs keep their source appearance; font settings affect text view. Right swipe advances and left swipe goes back in both views, with reverse/disable options retained.
- Release now contains Kokoro + Misaki only; Sherpa/Pocket and external-engine comparisons remain debug-only. The release catalog downloads checked Kokoro files directly from a pinned revision and safely reuses compatible existing files. No legacy eSpeak model archive is downloaded by the release catalog. Required font, model, Misaki and dependency notices are included; Settings provides privacy information and notices.
- Upgraded to target/compile API 36 and AGP 8.9.2. Built the non-debuggable ARM64 release AAB and APK. Both release and debug passed **327 JVM tests each, zero failures/errors/skips**; both lint runs passed with **zero errors and 28 warnings**. Instrumentation compiled only. A publication build correctly rejects missing upload-signing credentials/privacy URL.
- Bundletool validation, 16 KB ELF/bundle alignment, packaged font hashes and known Sherpa/eSpeak runtime/data absence checks passed. The preview APK passed signature and 16 KB zip-alignment checks. Its test certificate matches previous prototypes and versionCode increased to 16; physical upgrade has not been exercised.
- [Exact artifact hashes and validation](evidence/play-store/build-results.json), [packaging report](evidence/play-store/packaging.json) and [release dependency inventory](evidence/play-store/resolved-runtime.json) accompany the release. The preview APK is test-signed and the review AAB is unsigned. Neither is a production Play upload.
- No Android device was connected. Appearance, gestures, upgrade, sustained speech and Play-delivered split APKs require device validation. The [publishing handoff](PLAY_STORE_HANDOFF.md) requires another exact-artifact license audit, the owner's real privacy/contact details, secure production signing and Play account/testing work. No Play publication or blanket legal clearance is claimed.

## 0.2.1 automatic speech-model upgrade repair — 2026-10-26

- Fixed the user-reported `VERIFY_MODELS` / `Download kokoro first` failure after installing 0.2.0 over a prototype. Both Kokoro and alignment manifest identities changed even though their required model bytes matched. Playback rejected old setup markers before reaching the installer-only reuse path.
- `verifyInstalled` now checks and migrates an existing installation locally under the model mutex, before inference. It verifies hashes, copies only the new allowlist, verifies staging and atomically promotes it. No automatic download is performed. Cancellation/failure preserves the previous setup; missing or damaged files prompt explicit repair in Voice models. Benchmarks accept local candidates but still verify before loading. Voice models distinguishes Update setup from Download.
- The release upgrade regression reproduced the exact original exception before the fix, then passed using all pinned prototype Kokoro/alignment files and original markers, without calling install. It verifies both new installations, removed legacy files, no download/staging residue and successful fresh-process checks. Added coverage includes concurrent verification, same-size corruption, missing files, cancellation/retry and unmarked files.
- `testReleaseUnitTest testDebugUnitTest lintRelease lintDebug bundleRelease assembleRelease` passed in 4m 11s: **332 release tests and 331 debug tests**, zero failures/errors/skips; both lint runs had zero errors and 28 warnings. Bundletool, font hashes, known Sherpa/eSpeak absence, native 16 KB alignment, APK signature and 16 KB zip alignment passed. Package `app.readflow`, versionCode **17**, version **0.2.1**; preview certificate matches 0.2.0 and earlier prototypes.
- [Build evidence and checksums](evidence/play-store-0.2.1/build-results.json), [bundle inspection](evidence/play-store-0.2.1/packaging.json) and [release dependency inventory](evidence/play-store-0.2.1/resolved-runtime.json). No phone was attached: physical upgrade, Android synthesis and visual acceptance remain unverified. The preview is test-signed and AAB unsigned; production publishing requirements in the handoff still apply.
