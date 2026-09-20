# ReadFlow 0.1.1: model installation fix

## Update and recover

1. Download `ReadFlow-arm64-debug.apk` from the 0.1.1 release assets. Requires Android 15+ and ARM64.
2. Install it over the existing ReadFlow app. Do not uninstall or clear app data: doing so removes your documents and downloaded model files.
3. Open **Voice models** and tap **Resume setup** for each interrupted pack. Completed downloads are checked and reused; partial downloads resume when supported by the server.
4. Wait for **Installed**. The screen now shows separate download, checksum, unpacking, and installed-file checks, with percentages and byte counts. A second requested pack shows that it is waiting for the other setup to finish.
5. Install the separate **Word alignment** pack too, then choose a voice and try its preview.

Keep ReadFlow open until setup finishes. Setup is still a foreground activity operation, not a persistent background worker. If Android stops the process, reopen the app and resume. Model weights, manifests, runtime versions, application ID, signing certificate, and storage locations are unchanged by this update.

## Diagnosis and changes

The previous archive reader passed a raw `FileInputStream` into the BZip2 decompressor. The pinned Commons Compress 1.27.1 decoder issues individual byte reads, causing excessive file I/O for the roughly 100-300 MB archives. The previous UI then displayed an indefinite installation indicator without reporting actual extraction or checksum progress. These are confirmed code defects and a plausible explanation for the reported stall; no log or trace from the affected phone was available to establish its exact cause.

The installer now buffers compressed input in 64 KiB blocks before decompression, checks cancellation between extraction/copy/hash blocks, and reports byte progress at most about five times per second (plus phase completion). Installed-file checks remain mandatory before atomic activation. Cancellation closes an active HTTP call; completed downloads remain available for another installation attempt. Pending setup has a visible, cancellable queue state, and a new process recognizes interrupted downloads. Free-space checks account for bytes already downloaded, and manifest fingerprints are cached instead of being recomputed on every progress-driven UI refresh.

The buffering choice follows the [Apache Commons Compress buffering guidance](https://commons.apache.org/proper/commons-compress/examples.html#Buffering). No model or alignment weights were added to the APK or Git repository.

## Verification

Executed on 2026-09-20:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
python3 scripts/verify_delivery.py
```

Gradle: **BUILD SUCCESSFUL in 6m 12s**, exit 0. Application and instrumentation APKs built. **26 JVM tests passed; zero failures, errors, or skipped tests. Lint: zero errors, 15 existing warnings.** Report-derived details are in `docs/evidence/build-results.json`.

Nine new tests exercise buffered reads, real byte progress, resuming a completed archive, recognition after store recreation, raw alignment-pack installation, corrupt files, unsafe/incomplete archives, cancellation while unpacking and hashing, retry after cancellation, and all three actual pinned packs. The byte-read regression reproduces **527,295 individual reads** with the previous stream arrangement versus **nine bulk reads and zero individual reads** through the fixed arrangement for the same original fixture.

The production Kotlin installer installed the actual pinned archives and required extras from pre-downloaded files. Every installed file was checked against its pinned SHA-256, and all three packs reached the verified/installed state. One host run produced:

| Pack | Verified files | Installation including verification |
| --- | ---: | ---: |
| Kokoro `kokoro-en-v0_19` | 360 | 77.455 s |
| Pocket `int8-2026-01-26` | 10 | 36.347 s |
| Wav2Vec2 alignment | 4 | 2.138 s |

Host: Linux x86_64 VM, Intel Xeon 2.20 GHz, four logical CPUs/two physical cores, OpenJDK 17.0.20; Commons Compress 1.27.1; one serialized installer; 64 KiB stream/copy buffers. These are host functional-test observations under build load, **not Android performance estimates or inference benchmarks**. BZip2 decompression still takes time, which is now visible in the UI.

The real-pack test uses the ignored `.deps` assets from `scripts/fetch_models.py` and `scripts/fetch_voice.py`. It is explicitly skipped when those optional large fixtures are absent. `READFLOW_MODEL_TEST_DIR` can point to another fixture location on the same filesystem as the test temporary directory.

`apksigner verify --print-certs` confirms that both the original APK and this update use certificate SHA-256 `f7313f98e9414c166f3fb2d2a9329ba1bb5dd9d76b068254522d20d1f8b109f9`. `aapt dump badging` confirms `app.readflow`, version code 2, version `0.1.1-prototype`, API 35 minimum/target, and ARM64 only. APK size: 130,120,234 bytes. APK SHA-256: `e006540909ff7c490505008c1e94236e27dca3bc8624f10ac5c6edfbb770f700`.

## Remaining verification

`adb devices -l` returned no attached devices. Installation recovery, UI progress, cancellation over a live phone network, and playback after recovery still need confirmation on the affected phone. Instrumentation compiled but was not run for this patch. The pre-existing Android inference, audible word timing, and physical-device acceptance limitations in `docs/STATUS.md` remain open; this installation patch does not establish full product acceptance.
