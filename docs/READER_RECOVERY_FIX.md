# ReadFlow 0.1.7: playback recovery and a larger PDF viewport

## Diagnostic evidence

[Diagnostic history issue #1](https://github.com/sainadh812/ReadFlow/issues/1) contains two verified JSONL batches: 20 retained events uploaded at 08:21 UTC and 4 at 08:33 UTC on September 20, 2026. Checksums and event IDs give **24 unique events: 16 ALIGN_WORDS and 8 NORMALIZE_TEXT**.

All eight normalization errors came from 0.1.4 (`english-2`). The earlier source-input investigation documented `money.4` and `|`; those patterns were fixed in 0.1.5 and remain covered by the normalization regressions. The new 0.1.6 batch has four alignment errors and no normalization errors. The public reports omit original text and audio, so they cannot establish that every older normalization event has the same cause, or reproduce each reported acoustic mismatch.

All 16 alignment events use Kokoro, voices 0 or 2. They fail the unchanged 0.12 confidence gate. The previous coordinator treated that optional word-timing failure as a fatal playback failure: it deleted the generated audio and stopped preparing the document.

The retained host fixtures also demonstrate why simply reducing the confidence threshold is unsafe. Both Kokoro fixtures align successfully (minimum token confidence approximately 0.619 and 0.844). The failed Pocket OCR fixture contains missing or mismatched speech: `YOU` has confidence 0.0120 and `DO` 0.000270. Padding the recording and relaxing word delimiters did not repair those mismatches. The recorded rejection remains an expected regression test; passing it does not claim accurate word synchronization.

## Resulting behavior

- A low-confidence alignment retains and plays the exact generated PCM. That chunk has no word timings and displays **Reading · word highlighting unavailable**. The next confidently aligned chunk restores highlighting automatically.
- The confidence exception, source context and generated WAV remain in local issue logs. Reusing the cached chunk does not repeatedly log the same failure. Cancellation, missing models and unrelated inference errors still propagate.
- **Read from here**, bookmarks and resume generate text beginning at the selected spoken source word. This avoids pretending an interior word has a known audio position when alignment is unavailable. Joined line-break words retain their complete source mapping. Unhighlighted chunks save their first spoken word as the resume point; an exact interior resume position cannot be inferred without validated timing.
- The reader prevents idle screen dimming while visible and resumed. Leaving it or backgrounding the app restores the prior keep-awake setting. System brightness is unchanged.
- Normal reader controls occupy a compact header and a single playback row. The horizontal audio seek slider and separate timer, mode, page-navigation and voice rows are removed. Page navigation, original/text view, fit, rotation, speed, voices, search, warnings and logs remain available through the page button, reader menu or reading-details dialog. PDF padding and overlaid rotate/fit buttons are removed. Contextual read/skip/follow controls appear only when relevant.

## Validation

Executed on September 20, 2026:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
python3 -m unittest discover -s scripts -p test_read_github_diagnostics.py -v
python3 scripts/verify_delivery.py
```

- Gradle **BUILD SUCCESSFUL in 3m 20s**. **111 JVM tests passed**, zero failures/errors/skips. Both Python diagnostic-reader tests passed.
- Lint: **0 errors, 15 existing warnings**. Android app and instrumentation APKs compiled. Two new keep-awake lifecycle tests are compiled but **not executed**.
- The existing real-audio fixtures verify accepted boundaries and retained rejection. New recovery tests verify no fabricated timings, exact word selection, cancellation/error propagation, cached unhighlighted chunks and restoration of highlighting for later chunks. Selection tests cover both halves of line-break hyphenation and raw/expanded speech chunk limits.
- APK: `ReadFlow-0.1.7-arm64-debug.apk`, version code **8**, Android 15+ / ARM64, **130,628,197 bytes**. SHA-256: `e6068b1f833fbc232c1c624cc7ad9073734af986e966499131c78112d245c884`.
- APK v2 signature verified. Certificate SHA-256 `f7313f98e9414c166f3fb2d2a9329ba1bb5dd9d76b068254522d20d1f8b109f9` matches previous releases, allowing an in-place update. No new model weights are required.
- A software Android API 35 emulator was attempted because hardware acceleration is unavailable. ADB eventually responded, but `sys.boot_completed` remained empty, boot animation remained `running`, and package queries returned `cmd: Can't find service: package`. It was stopped. No running-app screenshot, native test execution or phone result is claimed.

Machine-readable build results are in [build-results.json](evidence/build-results.json).

The reported phone audio and source text are absent from the public uploads. Exact per-event word-alignment accuracy and physical Samsung display behavior require a device check; the playback recovery does not claim to repair the acoustic model's word recognition.
