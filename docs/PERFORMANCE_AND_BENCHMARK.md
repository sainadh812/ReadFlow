# Performance display and phone testbench — 0.1.12

## Use on your phone

Install the update over ReadFlow; keep the existing app and its data. Open **Settings > Text recognition** to choose **ML Kit · Original** or **TeleOCR**. ML Kit is bundled. TeleOCR needs its separate installed model pack; selecting it does not download it automatically. Use **Apply to current page** to replace a saved page's OCR with the selected engine/quality. Native PDF text remains preferred when usable.

The reader's performance card is enabled by default. Tap it for details; use its close button, reader menu or **Settings > Show reading metrics** to hide it. Fullscreen hides it. The display observes existing measurements at 2 Hz; it does not run an additional inference job.

Open **Settings > Phone testbench**, select installed speech/OCR models and 1, 3 or 5 samples, then run. Word alignment must be installed for speech tests. Tests run sequentially using real local production models and original fixed fixtures. Reading pauses first; model changes, extraction and new playback are blocked until native work finishes. Cancel, leave the screen or background the app to stop; an in-flight native call may need to drain. Screen rotation retains the run. Cancellation preserves completed rows. Results remain private; **Copy results** explicitly puts the report on the clipboard. No automatic upload occurs.

## What the numbers mean

| Measurement | Definition and interpretation |
|---|---|
| Speech characters/min | Normalized Unicode characters actually sent to speech synthesis divided by synthesis time. Numeric expansions count as spoken text. Cached audio and zero-duration samples do not inflate this rate. |
| Preparation speed | Generated audio seconds divided by measured preparation seconds. At 1× listening, greater than 1 is needed to build reserve; at 2×, greater than 2. Live headroom divides this ratio by the selected playback speed. |
| Live reserve / gaps | Listening seconds already prepared, complete future sentences, and unexpected queue depletions. Startup and normal completion are not counted as gaps. These are application observations, not measured speaker latency. |
| Model setup | Speech model verification and loading measured separately. The first sample also includes lazy alignment loading; later samples use the loaded sessions. OS caches are not cleared, so “cold” means a fresh model session. |
| Speech benchmark | Normalization, real synthesis, PCM16 WAV writing and forced word alignment. Preparation time excludes actual playback and the database cache commit. Alignment confidence is shown separately and is not a pronunciation-quality score. |
| OCR characters/min | Recognized output characters divided by page processing time. More output can include mistakes, so consider this alongside character error rate. TeleOCR includes ML Kit geometry and a fresh TeleOCR load per page, matching production. |
| OCR error rate | Character edit distance against the synthetic page after Unicode/whitespace normalization. Case, accents, punctuation and missing/extra text count. Lower is better; insertions can produce more than 100%. This is one fixture, not general book accuracy. |
| Table order | Whether this fixture's cell tokens occur contiguously in expected row order. It does not establish table geometry, arbitrary financial-table accuracy or spreadsheet extraction. |
| Memory / temperature | Whole-app proportional memory (PSS), Android thermal state and battery temperature at phase boundaries. Highest sampled memory is not continuously measured peak memory; TeleOCR can unload before sampling. Battery temperature is not CPU temperature. |

Live measurements reset for a new reading session, retain at most 64 preparation samples and contain no document excerpts. Live OCR identifies cached pages, embedded PDF text and TeleOCR fallback explicitly; fallback has no misleading engine throughput score. OCR time is separate from speech preparation, so fast speech alone does not establish uninterrupted reading across slow page extraction.

The testbench keeps one bounded report under private no-backup storage and removes temporary generated audio/images after cleanup. It synthesizes audio without playing it aloud. It does not measure Bluetooth delay, actual first-audible time, voice quality, battery drain, sustained 30-minute throughput, GPU/NPU utilization or peak memory. Speech and alignment remain CPU inference with two threads each. Run on your phone under comparable conditions before choosing models; the testbench does not automatically change your model or OCR settings.

## Validation

JVM tests cover rate calculations, Unicode OCR comparison, fixture expectations, report limits/restoration, cache/fallback attribution and benchmark exclusivity/cancellation. Application and instrumentation APKs are built with the release. Exact results are in [build-results.json](evidence/build-results.json) and [STATUS.md](STATUS.md).

No Android device is attached in the build environment. On the S25 Ultra, verify the settings toggle persists, ML Kit/TeleOCR selection and page refresh work, both installed speech models complete the benchmark, cancel/background waits safely for cleanup, copied results retain completed rows, and normal reading resumes afterward. Check light/dark themes and large text. Device performance and visual acceptance are pending these checks.
