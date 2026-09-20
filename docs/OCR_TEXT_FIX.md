# ReadFlow 0.1.5: OCR normalization fixes

[Download the ARM64 debug APK](https://github.com/sainadh812/ReadFlow/releases/download/v0.1.5-prototype/ReadFlow-0.1.5-arm64-debug.apk). Install over the existing app without uninstalling or clearing data. No model redownload or PDF reimport is required. Retry playback on the same pages; the normalizer revision invalidates older speech cache keys while preserving page/word IDs and reading positions.

## Diagnosis and implemented changes

The two supplied 0.1.4 reports failed at `NORMALIZE_TEXT`, before synthesis/alignment of the failed sentence. They are not evidence of a low-confidence aligner failure or a model-installation failure.

- `money.4` retained its numeral because the normalizer recognized bare numbers and some alphanumeric compounds, but not a numeral attached after sentence punctuation. It now expands literally to `money. four`. Quoted/bracketed variants are covered, and the source token owns both spoken words. It is not silently removed or assumed to be a footnote.
- A standalone `|` was rejected as an unsupported symbol. In prose it could be OCR confusing "I" with a bar, but a blanket substitution would corrupt other text. It now reads literally as "vertical bar", retaining the original text, offsets and geometry. This fixes the hard stop, not the underlying OCR recognition error.
- Decimal numbers and units retain their existing number path. Regression testing additionally exposed loss of the minus sign in `-0.5%`; negative zero before decimal digits now retains its sign.
- Genuine unsupported expressions, unsupported scripts and out-of-range numbers still fail clearly. No equations are interpreted, alignment thresholds weakened, timestamps estimated or speech words synthesized individually.
- `NormalizationException` now carries the exact rejected token, source IDs and attempted expansion into the issue log. The optional `normalization` field is backward-compatible with earlier schema-1 logs. Audio sample metadata is cleared before the lazy planner advances, fixing stale previous-chunk sample counts in normalization-error reports.
- App version is `0.1.5-prototype` (6); normalizer revision is `english-3`. Dependencies, model packs, runtime versions, document processing revision and alignment algorithm are unchanged.

Only short, original fixtures are committed. The supplied private reports, document identifiers, original PDF and full quoted passages are not added to the public repository.

## Verification commands

Executed in `/home/archgen_guest_1/ReadFlow` on 2026-09-20:

```bash
./gradlew testDebugUnitTest --tests app.readflow.core.OcrSpeechTest --console=plain
./gradlew testDebugUnitTest --tests app.readflow.core.OcrSpeechTest --tests app.readflow.core.WebSpeechTest --tests app.readflow.core.SpeechPlanningTest --tests app.readflow.diagnostics.IssueLogsTest --console=plain
.venv/bin/python scripts/prototype.py kokoro --text 'You read faster than vertical bar do. We read about money. four' --output docs/evidence/ocr-normalization
.venv/bin/python scripts/prototype.py pocket --text 'You read faster than vertical bar do. We read about money. four' --output docs/evidence/ocr-normalization
./gradlew testDebugUnitTest --tests app.readflow.core.RealAudioTest --console=plain
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
```

The initial seven-test reproduction failed six tests against 0.1.4, exposing both reported rejection patterns and the negative-zero issue. The focused normalization/mapping/logging checks passed after the fixes. Real-audio testing then passed for Kokoro but exposed the Pocket failure described below; a diagnostic rerun identified its exact failing token. The final suite includes an explicitly named **expected rejection** test for that preserved Pocket artifact, not a claim that Pocket passed word-sync for it.

## Real-audio results

The original fixture is in `docs/evidence/ocr-normalization/input.txt`. Both engines actually synthesized its normalized text on Linux x86_64 using Sherpa 1.13.8, CPU with two threads. Host ONNX Runtime 1.20.1 produced known-transcript alignment emissions; the production Kotlin CTC algorithm checked the actual PCM. The Android alignment runtime remains 1.20.0. Metadata, audio, emissions and source mappings are retained under the fixture directory.

- **Kokoro:** normalization and sample-coordinate source mappings passed, including merged spans for `|` and `money.4`. Each merged span was compared to the separately mapped tokens from the same single synthesized audio artifact. Playback-position lookup at the recorded word start boundaries was checked. This is a functional host test, not manual audible-boundary validation.
- **Pocket:** synthesis succeeded, but alignment was **rejected at token 0, `YOU`, confidence 0.012028131 versus threshold 0.12**. No timings for that fixture are accepted. `alignment-result.json` explicitly records this failure. The cause of that acoustic/alignment mismatch remains unresolved; no confidence-gate workaround is included.
- The earlier original-paragraph regression for both models remains in the suite. Its success does not override the newly observed Pocket failure or establish target-device acceptance.

These are smoke tests, not comparable performance benchmarks. No claim is made that either model is faster/better. Recorded elapsed values exclude production Android overhead and the Kotlin Viterbi calculation from their host combined-RTF field.

## Final build and package results

- `./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain`: **exit 0, BUILD SUCCESSFUL in 2m 27s**. Application and instrumentation APKs built.
- **80 JVM tests, 0 failures/errors/skips**, including seven OCR-normalization regressions, backward-compatible diagnostic parsing, and the explicitly expected Pocket rejection test. This count does not mean both engines passed the new audio fixture.
- Lint: **0 errors, 15 existing warnings** (`ExportedService`, `GradleDependency`, `UsableSpace`).
- APK is `app.readflow`, `0.1.5-prototype` (6), min/target API 35, ARM64 only. No bundled speech/alignment weights. Size: **130,464,357 bytes**; SHA-256: `a3cfea4eac941458ebae5d8ba6f03954d8d88f68c451c16028feae566043daa8`.
- Signature verification passed (APK v2). Certificate SHA-256: `f7313f98e9414c166f3fb2d2a9329ba1bb5dd9d76b068254522d20d1f8b109f9`, unchanged from 0.1.4 for in-place updates.
- ADB returned no attached devices; instrumentation was compiled, not run. Earlier emulator blockers remain documented in `STATUS.md` and were not retested for this patch.

Packaging/inspection commands, all exit 0:

```bash
cp app/build/outputs/apk/debug/app-debug.apk dist/ReadFlow-arm64-debug.apk
cp dist/ReadFlow-arm64-debug.apk dist/ReadFlow-0.1.5-arm64-debug.apk
python3 scripts/verify_delivery.py
/home/archgen_guest_1/.local/opt/android-sdk/build-tools/34.0.0/apksigner verify --verbose --print-certs dist/ReadFlow-0.1.5-arm64-debug.apk
/home/archgen_guest_1/.local/opt/android-sdk/build-tools/34.0.0/aapt dump badging dist/ReadFlow-0.1.5-arm64-debug.apk
/home/archgen_guest_1/.local/opt/android-sdk/platform-tools/adb devices -l
```

`docs/evidence/build-results.json` contains report-derived counts and package inspection. Release assets include the APK, source archive and checksums. No private user logs or large weights are included.

## Remaining phone verification

No S25 Ultra is attached here. Actual update installation, playback of the supplied PDF pages, word highlighting and jumps at 1x/2x, and the live diagnostic-reset behavior still need device checks. Retry with the existing Kokoro pack after updating; no library reset is needed. Any remaining parsing or confidence failure should produce a new log under Settings > Issue logs.

The OCR output itself is not corrected by this patch. Literal "vertical bar" may sound awkward where the source page actually says "I"; reliable source-aware correction remains open. Other book pages can still expose unsupported text or alignment failures. Full two-engine word-sync acceptance remains incomplete, particularly given the recorded Pocket failure.
