# ReadFlow implementation decisions

## Scope and ownership

One Android app module, Kotlin/Compose/Material 3, API 35 minimum and target, ARM64 by default. API 35 is an MVP choice so the platform PDF text APIs are available, not a permanent policy. `-Pemulator=true` selects x86_64 for tests. There is no Android system-TTS fallback, cloud inference, account, or Python server.

Packages separate `core`, `ingest`, `speech`, `models`, `playback`, `data`, `diagnostics`, and `ui`. `PageExtractor`, `ArticleExtractor`, `ReadingOrder`, `TextNormalizer`, `SpeechAligner`, `TtsEngine`, and `PackRepository` expose replaceable boundaries. The application owns repositories; a ViewModel owns screen state. `ReadingService` owns ExoPlayer and MediaSession; it attaches to the application playback coordinator. Coroutines and StateFlow carry work/state, not WorkManager.

## Timing milestone

`scripts/prototype.py` produces actual Kokoro/Pocket audio and Wav2Vec2 emissions on a Linux host. `RealAudioTest` runs the Android application's Kotlin CTC algorithm against those emissions, writes source mappings/timings, checks every boundary lookup, and verifies monotonic sample ranges. Artifacts are in `docs/evidence`. These are original test paragraphs, not prerecorded audio substituted in the application.

Sherpa 1.13.8's Kotlin `GeneratedAudio` contains PCM and sample rate, with no word timings. Its Kokoro export is not the reference Python duration/token pipeline. Neither adapter claims native timing. Both engines use a separate, visible Wav2Vec2 alignment pack. ONNX emissions are constrained to the already-known normalized transcript using blank-expanded CTC Viterbi paths, including repeated-label restrictions. No fresh ASR transcript replaces document text. A low-confidence or impossible path throws an error instead of returning guessed boundaries.

Wav2Vec2 uses 16 kHz normalized input, 320-sample convolution stride, and 400-sample receptive field. On Android, a windowed-sinc resampler and waveform normalization precede inference. Result times are converted back to the original TTS PCM sample rate. PCM is first quantized to the exact 16-bit WAV that playback uses, then aligned. There is no silence trimming. Boundaries are not generated from text length, equal word durations, or separate word synthesis.

The 0.12 per-token posterior gate is an initial fail-closed engineering threshold, **not an audibly calibrated accuracy guarantee**. Boundary timing, phonetic confusions, and confidence thresholds still need a human-audited corpus on the target device. Host smoke success does not meet the full product acceptance criterion.

## Text and geometry

Offsets use Kotlin/Android UTF-16 code units, half-open `[start,end)` ranges. Document identity derives from the copied file hash and processing version. Page, paragraph, sentence, and word IDs derive from document/page identity and source position, never the word text alone. Repeated words remain distinct.

`PageContent` retains original extraction text, cleaned reading text, paragraphs, source words, block/line layout and word boxes. `SpeechText` separately retains normalized speech text and explicit many-to-many spoken-token/source-ID links. Numeric expansions attach all spoken tokens to the original displayed token. Joining a line-break hyphen can attach two source words to one spoken token. Both fragments share the spoken span and are highlighted together; one primary source ID records the durable position.

PDF extraction tries `getTextContents()` independently per page. Nonempty but sparse text is insufficient. Usable native text uses exact `searchText()` bounds only when occurrence counts match the source tokens. Ambiguous substring/repeated matches fall back to OCR, never proportional boxes. Pages containing images use one full-page OCR representation to avoid native/OCR duplication. This trades some native-text accuracy for a conservative, geometry-complete result; selective region fusion is not implemented.

OCR uses bundled ML Kit 16.0.1. Render scale starts at 225 DPI and caps at six million pixels. Extraction resources are serialized; preview rendering has a separate lock and independently opened PDF resources so display need not wait for OCR. ML Kit tasks drain before bitmaps are recycled even on cancellation. Only the requested page and pages needed to fill the bounded audio queue are processed. No whole-book scan occurs before playback. Quarter-turn scan rotation has an invertible processed-to-page transform. Automatic deskew and user cropping are not implemented.

Reading order handles simple lines and a conservative clear two-column gutter. Complex layouts, tables, math, marginalia, and overlapping text are not generally supported. Margin skipping is optional, non-destructive, and geometric; cross-page repeated-header classification is not yet implemented. Potential equations are flagged and unsupported speech symbols fail with an explicit skip instruction.

## Web extraction boundary

Explicit HTTPS imports fetch bounded HTML via OkHttp. Jsoup removes active elements and applies an allowlist. A disposable WebView has file/content access, network resources, popups, and DOM storage disabled. It exposes no JavaScript bridge. Only the pinned bundled Readability code is evaluated, returning JSON through `evaluateJavascript`. The sanitised source and extracted article are stored privately for offline reading. Tables are omitted with a visible warning; headings and paragraphs are retained. There is no login/paywall bypass and no promise of JavaScript-heavy site support.

## Models and audio queue

The APK bundles no speech/alignment weights. The asset manifest is compiled into the app. Model archives are pinned to SHA-256 digests published by the upstream GitHub release API; the alignment export is pinned to a Hugging Face commit and LFS hash. Every installed file, phonemizer resource, fixed voice, tokenizer and alignment configuration is verified against the manifest. Pocket accepts only the shipped licensed voice selection; there is no voice-upload or voice-cloning feature.

Downloads use HTTPS, bounded lengths, free-space checks, partial files, HTTP Range validation, up to three attempts, cancellation, hash checks, archive path/type/size validation and same-filesystem atomic rename. Staging never counts as installed. Downloads are foreground-screen coroutine work; partial bytes survive process death for manual resume. Automatic background download resumption/WorkManager is not implemented.

Only one TTS engine is retained. Its mutex covers creation, inference and native destruction. Generation IDs invalidate late results on every jump/model change. A new uncached request waits for old native work to drain; it does not free a model under inference. Cache hits seek to real sample boundaries, rounded upward to the nearest millisecond. This MVP currently also drains stale work and verifies packs before a cached jump, so cached-jump responsiveness needs improvement.

Sentence chunks are split conservatively at word boundaries, with a second bound on normalized text length. No source words are dropped. Engine callbacks support best-effort cancellation, with noninterruptible portions acknowledged. Queue target is 30 media seconds, reduced to 8 under memory/thermal pressure, with one chunk of possible overshoot. One previous audio item is retained for navigation. The player receives complete, aligned local WAV items. Slow synthesis produces real buffering gaps.

Room stores documents, pages, indexed words, positions, bookmarks, chunks and sample-coordinate timings. Paragraph/sentence structure also resides in the versioned page JSON. Cache keys include source IDs, pipeline version, normalized text, model/runtime/voice version, synthesis settings, and alignment version. Cache reads verify the precise WAV hash. LRU eviction protects queued audio. Private copied imports, models and audio live under `noBackupFilesDir`; application/cloud/device backups are excluded.

## Playback and UI

Media3 handles audio focus, noisy-route events, foreground playback, notification/lock-screen controls and pitch-preserving speed. The UI polls actual player position every 33 ms. This is observation of the media clock, not an independently advancing highlight timer. Speed is not reapplied to media timestamps. An optional output-delay calibration converts route delay to media coordinates once; automatic Bluetooth latency measurement is not available.

The reading screen uses source offsets from Compose text layout, rejects whitespace/outside-glyph taps, and offers a paused read-from-here action. Original-page mode shares one affine mapping between drawing and inverse tap hit testing, including zoom, pan and quarter-turn rotation. Manual dragging suspends following. Screen reader labels/custom paragraph actions are supplied without a live-region narration of every highlight.

No approved mockup attachment was accessible in this turn. The implementation follows the written light/blue/compact layout requirements; fidelity to the specific approved image is unverified.

## Issue diagnostics

`IssueLogs` is a testable, error-only store under `noBackupFilesDir/issues`. Call sites supply the actual failed sentence, prepared speech/mappings, source metadata and operation stage when available. `AlignmentConfidenceException` preserves the measured failing token posterior and existing threshold without weakening the alignment gate. The coordinator captures generated WAVs before failed cache cleanup, then rechecks the generation ID before updating UI. Media3 player errors have their own listener. Cancellation is not an issue.

Successful work keeps only a memory context for best-effort uncaught Java exception capture. There is no persistent content breadcrumb stream. On reopening after a native crash/ANR/low-memory exit, available Android exit metadata can produce a report; lost input is explicitly marked unavailable. The old content-free `PlaybackDiagnostics` summary persists only failed checkpoints now.

Reports use bounded JSON, optional exact WAV attachments, same-directory atomic installation, count/byte retention and warning deduplication. Export snapshots are taken under the store lock; external SAF output runs after releasing it. Export verifies the attached audio hash. Logs are private until an explicit, privacy-confirmed export; no upload client is present. User-facing storage failure does not replace the original issue. See [ISSUE_LOGS.md](ISSUE_LOGS.md) for limits, deletion semantics and coverage.

## Remaining validation

See `STATUS.md` and `DEVICE_VALIDATION.md`. In particular, Android execution of both models, auditory timestamp error, target-device latency/memory/thermal/battery measurements, interruption recovery, and the 30-minute session are required before calling the core experience complete. Process death restores a source-word position but does not automatically reconstruct and resume a background media session.
