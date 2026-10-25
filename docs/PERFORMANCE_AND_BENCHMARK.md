# Performance and phone testbench — 0.1.13

Install the update over the existing prototype without uninstalling. The package and signing identity are unchanged; versionCode is 14. Books, downloaded packs, bookmarks and preferences stay in the same stores.

## Reading controls

**Settings > Performance > Speech processing effort** selects Low (one speech/one alignment thread), Medium (two/two) or High (four/two). Medium remains the default. The optional worker-spinning switch is off by default; it can trade battery use for shorter scheduling waits. These controls do not change voice quality, OCR quality or playback speed. More threads are not guaranteed faster, especially when the phone heats up. The testbench can compare three threads too, and its expanded CPU results have **Use these CPU settings**.

The speech engine and aligner apply real ONNX Runtime thread/spinning settings. The resampler reuses bounded rational-phase filter tables rather than recalculating trigonometric coefficients for each output sample; the existing 49-tap bandlimited filter, sample count and timing convention are preserved. Unit tests compare the prior implementation, anti-aliasing, DC, impulses and concurrent callers. No S25 speedup is inferred from those tests.

**Reader menu > Prepare pages before listening** prepares from the current page through a chosen final page. Enter a chapter's final page when reading a PDF; automatic chapter-boundary discovery is not implemented. This runs real extraction, normalization, synthesis and validated alignment into the ordinary verified audio cache. It does not start playback automatically. Keep ReadFlow open: backgrounding cancels preparation, preserving completed chunks. Progress includes prepared pages and audio duration. Preparation stops before exceeding the configured cache budget or available storage; earlier chunks in the chosen range are protected from eviction while the operation runs. Normal cache eviction still applies during later use. Changing voice or recognized text requires different audio cache entries.

The existing bounded lookahead remains: four complete future sentences and a listening-time reserve. It cannot compensate indefinitely for a producer slower than playback. Preparing a range before listening is useful in that situation.

## OCR selection and memory reuse

**Settings > Text recognition** offers ML Kit (bundled/default), PaddleOCR mobile Latin (~13 MB optional download) and TeleOCR (~1.394 GB optional download). Both downloads are pinned by revision, byte count and SHA-256. See [PaddleOCR provenance](PADDLE_OCR.md) and [OCR settings](OCR_MODELS_AND_PRESETS.md).

Saved pages keep their text until **Apply to current page**. Reliable native PDF text still bypasses OCR. PaddleOCR runs its own detector and recognizer; ML Kit supplies matching measured word boxes only, never substitute text. Unsupported exact word highlights are omitted and disclosed. PaddleOCR has no dedicated table-structure model. TeleOCR reconstructs supported tables and adds pauses for narration.

TeleOCR keeps one warm native session for up to 60 seconds of idle time; PaddleOCR keeps one detector/recognizer pair for up to 45 seconds. Both serialize work and release safely on memory pressure, quality replacement, deletion or benchmark boundaries. Cancelled TeleOCR contexts are discarded. TeleOCR reuses bounded image-transfer buffers. Models are not all deliberately kept loaded together. There is no artificial RAM reservation or `largeHeap` performance switch.

The user's 0.1.12 test took about four minutes per TeleOCR synthetic page. Warm reuse removes repeated loading but does not establish that inference itself is now fast. ML Kit remains the immediate-reading default; use heavy OCR before listening or on selected difficult pages.

## Phone benchmark

Open **Settings > Phone testbench**. Select installed speech models and any of the three OCR engines. Choose 1, 3 or 5 repetitions. OCR repeats the *same* synthetic financial page; it is not a sample of different books. Speech runs an initial sample followed by warm samples. **Compare CPU thread counts** adds sequential 2/3/4-thread blocks, with only one resident speech model at a time. Blocks may experience different thermal conditions; reverse/repeat comparable runs on a cooled phone before deciding a setting is best. **Extra sustained speech samples** adds 30 or 60 samples at the chosen configuration. All runs use real model inference, not simulated throughput.

Reading pauses before the benchmark, and model changes/imports/new reading are blocked until model work drains. Cancel, leaving the screen or backgrounding the app preserves completed rows. The report records the cancellation reason; rotation retains the run. Elapsed time, OCR processing stage and current memory update during native work. The progress ticker does not repeatedly fsync the report; reports are saved at measurement/phase boundaries and completion.

Reports remain local. **Copy results** explicitly copies synthetic fixture outputs and measurements; it never includes books or benchmark audio. One bounded report is retained. Old schema-1 reports can be read but their OCR accuracy and memory methodology should not be compared directly with schema 2.

| Measurement | Meaning and limits |
|---|---|
| Speech characters/min | Normalized characters actually sent to synthesis divided by synthesis time. It is generation speed, not listening speed or pronunciation quality. |
| Preparation speed | Generated audio seconds divided by full preparation seconds. Greater than the selected playback speed is needed to build reserve. Includes normalization, synthesis, PCM16 WAV writing and alignment; excludes playback and database commit. |
| Alignment stages | Session load, resampling, normalization, neural inference, output copying and CTC decoding are recorded separately. Confidence acceptance is not a pronunciation score or a human timing audit. |
| Model setup | Verification and loading are separated. "Fresh" means a new session, not cleared OS caches. External engine model warmth is unknown and not reported as cold. |
| OCR time | Image decode, ML Kit geometry/retries, selected model inference and reading order. TeleOCR/Paddle additionally report model loading, detection/layout and recognition stages. Sessions are reused between repetitions. |
| OCR character errors | Edit distance against the original synthetic reference, after Unicode/whitespace normalization only. Scores raw recognized text before narration punctuation or speech cleanup. Real accents, decimals, separators and monetary symbols remain significant. |
| Table token sequence | Exact cell tokens in expected row order. A mismatch can be spelling or order; it is not by itself proof of a layout failure. |
| Table structure / cell accuracy | Actual structured grid dimensions/spans and exact values at row/column coordinates. Null for engines without table structure output; not mislabeled as a recognition failure. |
| Memory | ReadFlow process PSS sampled every second during inference and at boundaries, with per-row and whole-run highest observations. Brief peaks can be missed. External engine process RAM is excluded. Values are not managed-heap limits or whole-device usage. |
| Device conditions | Android thermal status, battery temperature, charging, battery saver and available memory. Battery temperature is not CPU temperature; correlation alone does not prove thermal throttling. |

### Corrected OCR scoring

Schema 1 compared narrated TeleOCR output to a reference without narration punctuation. The adapter adds eight semicolons and four periods to this fixture's table, so perfect recognition could score 12/276 = 4.35% error and fail the table-token test. Schema 2 scores `recognitionText` before these additions, retains raw synthetic output/reference for inspection, and tests table cells separately. It does not globally remove punctuation, which would hide real financial-text mistakes. Regression tests reproduce the old false errors and verify zero error for a perfectly recognized raw fixture.

### Optional external acceleration experiment

The testbench can call a separately installed [SC Kokoro](https://github.com/speech-central/sc-voices-android) Android TTS engine. Finish its model download/device test first. ReadFlow explicitly selects its offline English voice, requests a PCM16 WAV without playing it, then uses the normal alignment path. The default test voice is `af_heart`; this differs from ReadFlow's older Kokoro v0.19 default voice. Synthesized test text crosses a local Android IPC boundary to that app. ReadFlow does not bundle its wrapper, OpenCL backend or model, or change the system default TTS engine.

Results are labeled `external-unverified`: Android's TTS API cannot tell ReadFlow whether SC Kokoro used GPU or CPU. Its process memory is not counted in ReadFlow PSS, and its synthesis time includes IPC/WAV handling. No silent CPU substitute is counted as an acceleration result. This is a functional test route, not verified S25 GPU support or an embedded acceleration toggle.

Direct embedded OpenCL integration and the S25 QNN/NPU port remain unimplemented. The current bundled Kokoro v0.19 ONNX graph exposes only `audio`, and Sherpa's public API provides no phoneme/source-word duration mapping. Native-duration highlighting therefore still needs a compatible model/frontend/JNI migration and acoustic validation; the current update keeps validated CTC. XNNPACK is not exposed as an unverified acceleration option.

## Validation

See [build results](evidence/build-results.json), [Paddle host smoke](evidence/paddleocr/host-smoke.json) and [delivery status](STATUS.md). The host smoke exercises official ONNX weights with the app's actual Kotlin detection/CTC postprocessing; it is not an Android performance measurement. The packaged ARM64 app and instrumentation APK are built; no physical S25 is attached here.

On the phone, compare all three OCR engines, review raw fixture outputs/amounts, and test a difficult real page with each. Run baseline and sustained CPU tests under comparable conditions. Test warm-page transitions, cancel/background/rotation, memory pressure, page-range cancellation/cache limits and ordinary reading afterward. Listen for pronunciation and sentence gaps; the synthesis benchmark does not play audio or measure speaker/Bluetooth latency.

## Misaki comparison in 0.1.14

The testbench offers **Kokoro + Misaki · experimental** when the existing Kokoro and Word alignment packs are installed. Select it alongside Kokoro, keep the same voice and thread settings, and choose the financial or pronunciation sample set. The pronunciation set includes names, money, age ranges and context-dependent words. Reports record the exact synthetic input and normalized speech, and separate Misaki phonemization time from acoustic inference. Existing private documents are never used as benchmark fixtures.

The new backend uses direct ONNX Runtime 1.22.0, while the legacy backend uses Sherpa’s embedded runtime. Results therefore compare whole implementations, not only phonemizers. The frontend remains resident for each measured block; model-load timing includes POS and neural fallback session initialization. See [limitations and listening instructions](MISAKI_TRIAL.md).
