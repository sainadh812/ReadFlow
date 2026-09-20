# ReadFlow 0.1.4: automatic issue logs

This is the historical 0.1.4 delivery report. The [0.1.5 update](OCR_TEXT_FIX.md) adds rejected-token context; [0.1.6](GITHUB_DIAGNOSTICS.md) adds combined history export and explicit GitHub sends. Old reports remain readable. Use the latest APK linked from the repository README. The no-upload-client description below applies to the original 0.1.4 implementation; 0.1.6 allows only user-confirmed diagnostic uploads.

## Install and send a report

[Download the ARM64 debug APK](https://github.com/sainadh812/ReadFlow/releases/download/v0.1.4-prototype/ReadFlow-0.1.4-arm64-debug.apk). Minimum Android 15/API 35. Version code 5; same package and signing certificate as 0.1.3. Install over the existing app, without clearing data or uninstalling, to retain models and documents. Actual upgrade on the Galaxy S25 Ultra remains unverified here.

1. Reproduce the problem. Caught failures and extraction warnings automatically create a local report; normal successful work and user cancellations do not.
2. Open **Settings > Issue logs** or **Issue logs** in the reader menu/error area.
3. Select the newest relevant report and review its input/error details.
4. Tap **Export issue log** (save icon), confirm the privacy warning, then save the ZIP using the Android file picker.
5. Attach the ZIP when asking for debugging help. **Do not publish private document text/audio in a public GitHub issue.**

No automatic upload occurs. The destination is chosen using Android's [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files), without broad storage permission. Choosing a cloud-backed provider is an explicit user action. Delete one report or all reports from the Issue logs screen. Deleting a document deletes reports associated with its document ID; source-only import-failure reports must be deleted separately. Exported copies are outside app control and are not deleted by these actions.

## Implemented

- File and URL imports, article fetch/extraction failures, PDF/OCR warnings/errors, page rendering, scan rotation, speech normalization/unsupported input, model download/install/switch/delete, synthesis, alignment, cache operations and Media3 playback failures feed the issue store. A routine successful OCR geometry-fallback notice is excluded.
- Reports identify the operation stage, failure type/message/cause stack, app/device/Android/runtime versions and available model/voice/settings. Model failures include manifest hash and download progress context.
- Input includes the failed sentence's original and reading text, normalized speech, stable word IDs, UTF-16 offsets, available geometry and source-to-spoken token mappings. Before a sentence is available, a bounded page/visible-web-text excerpt or source/file metadata is retained instead. Missing inputs are not fabricated.
- Low-confidence alignment reports include the actual failed token, source IDs, measured confidence and threshold. The existing 0.12 threshold is unchanged and remains uncalibrated against manually audited phone audio.
- If speech was generated, its exact WAV can be retained before the failed cache entry is removed. The report records its SHA-256, sample rate/count when available, and omission reason if it cannot be included. Export verifies that hash again.
- Best-effort uncaught Java exception logging runs before delegating to Android's original handler. The next launch checks available [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo) crash/native-crash/ANR/low-memory metadata, excluding older-build history and already handled exits. Native tombstones/traces are **not** attached.
- Existing content-free **Copy playback diagnostics** remains separate. Successful stage checkpoints are now memory-only; only FAILED checkpoints persist.

## Storage and privacy

Reports are stored under private `noBackupFilesDir/issues`, excluded from app backups. No new third-party dependency, analytics service or diagnostic network endpoint is added.

| Item | Bound/policy |
|---|---|
| Retention | Newest 20 reports, at most 24 MiB total; oldest removed first |
| JSON | Schema version 1; at most 512 KiB per report |
| Text | At most 8,192 UTF-16 code units per text field; failed word range preferred |
| Mapping | At most 128 source words, 8 boxes per word, 512 spoken tokens |
| Audio | At most 4 MiB of an exact generated WAV; oversized audio omitted, not truncated |
| Exceptions | At most 6 causes and 40 bounded stack frames per cause |
| Export | ZIP with `report.json` and optional `audio.wav` only |

Writes use a staging directory and atomic rename; unfinished/corrupt reports are cleaned on refresh. Repeated identical extraction warnings are deduplicated; separate errors remain separate reports. Ordinary document/cache persistence is unchanged: this policy describes diagnostic logging, not the saved library.

Source URL fields drop credentials, query strings and fragments; SAF document identifiers are masked. Titles, URL paths, text excerpts, exception messages and generated audio can still be private. This is **not complete secret redaction**. Web import failures retain sanitized visible text, not raw scripts, cookies or request headers. Original PDF/image files and full webpages are not automatically attached. Oversized fields are bounded; `truncatedFields` identifies store-level truncation and excerpt policies describe earlier sampling.

## Verified on the build host

Executed in `/home/archgen_guest_1/ReadFlow`, 2026-09-20:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
cp app/build/outputs/apk/debug/app-debug.apk dist/ReadFlow-arm64-debug.apk
cp dist/ReadFlow-arm64-debug.apk dist/ReadFlow-0.1.4-arm64-debug.apk
python3 scripts/verify_delivery.py
/home/archgen_guest_1/.local/opt/android-sdk/build-tools/34.0.0/apksigner verify --verbose --print-certs dist/ReadFlow-0.1.4-arm64-debug.apk
/home/archgen_guest_1/.local/opt/android-sdk/build-tools/34.0.0/aapt dump badging dist/ReadFlow-0.1.4-arm64-debug.apk
/home/archgen_guest_1/.local/opt/android-sdk/platform-tools/adb devices -l
```

- Final Gradle command: **exit 0, BUILD SUCCESSFUL in 2m 25s**. Application and instrumentation APKs compiled.
- **69 JVM tests, 0 failures/errors/skips**, including 18 new issue-log tests. Coverage includes no success/cancel reports, persistence, measured CTC failure details, exact audio/export integrity, truncation, retention, corrupt reports, unwritable storage, warning deduplication, URL redaction, failed-sentence selection, deletion and slow exports not blocking new reports.
- Lint: **0 errors, 15 existing warnings** (`ExportedService`, `GradleDependency`, `UsableSpace`). No phone tests ran.
- An earlier development run caught an overly small synthetic retention-test budget that counted the JUnit stack trace; that test was corrected, independent count-limit coverage added, and the full command rerun successfully.
- APK inspection: `app.readflow`, version `0.1.4-prototype` (5), min/target API 35, ARM64 only, no bundled speech/alignment weights.
- APK: **130,898,376 bytes**, SHA-256 `5e6c560fa8f67c8b10e39f0d657d0668e51e319f47f1fbbf29e7d41fba539226`.
- Signature: valid APK v2; certificate SHA-256 `f7313f98e9414c166f3fb2d2a9329ba1bb5dd9d76b068254522d20d1f8b109f9`, matching the previous 0.1.3 APK.
- ADB reported no attached devices. Structured results are in `docs/evidence/build-results.json`.

## Unverified and limits

- The Settings/export UI, actual in-place upgrade, Samsung SAF provider behavior, crash recovery and persistence across phone restart need checks on the user's SM-S938B, Android 16/API 36. Instrumentation compiled, but no physical device was available. Earlier software-emulator failures are recorded in `STATUS.md`; they were not rerun for this patch.
- Abrupt native crashes, OS kills and ANRs cannot reliably run Java error handlers. Since successful inputs are not written as diagnostic breadcrumbs, next-launch OS reports have metadata only and explicitly say input was lost. OS exit history is optional. Java crash capture can also fail during out-of-memory, storage exhaustion or very early startup failure.
- Import failures before extraction have source/file metadata but no extracted document text. Bounded excerpts may omit the decisive layout, character or content; a separate relevant original page may still be needed.
- Logging failures show a storage warning without masking the original operation error. Export/delete failures show a UI message rather than recursively logging themselves. Capturing every possible failure is not guaranteed.
- This patch improves diagnosability; it does not claim to fix every PDF/website or to complete both-engine on-device word-sync acceptance. No phone timing, alignment-error, memory, battery or thermal results were produced.

### Remaining phone checks

1. Update without clearing data. Confirm downloaded packs/documents remain.
2. Clear issue logs, then read known-good text and cancel a preparation/download. Confirm no new issue report.
3. Reproduce the reported PDF/website error. Confirm the newest report identifies the failing stage and available input; for alignment confidence failures inspect token/confidence/threshold and retained audio.
4. Restart the app, export the report ZIP, and verify `report.json` plus any `audio.wav` can be opened. Keep private input out of public issue trackers.
5. Delete the report and confirm it disappears; confirm any previously exported ZIP is unaffected. Repeat with document deletion for document-associated reports.
6. Check Java/native crash/ANR recovery only on a controlled test build/device; confirm ordinary force-stop does not create an error report. Native crash input is expected to be unavailable.
