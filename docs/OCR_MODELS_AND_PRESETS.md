# OCR model update — 0.1.13

The current app offers **ML Kit**, **PaddleOCR mobile Latin** and **TeleOCR**. ML Kit remains the default; PaddleOCR and TeleOCR are optional pinned downloads. PaddleOCR is about 13 MB and uses an actual PP-OCRv5 mobile detector plus Latin recognizer on ONNX Runtime CPU. It recognizes text and line order, without a separate table-structure model. See [PaddleOCR implementation and model provenance](PADDLE_OCR.md).

TeleOCR retains a single warm native session for up to 60 seconds between calls; PaddleOCR retains detector/recognizer sessions for up to 45 seconds. Quality changes, explicit removal, benchmark boundaries and memory pressure release sessions after native work drains. Cancelled TeleOCR sessions are discarded. Heavy OCR is best applied before listening through **Prepare pages before listening**, or manually to a difficult page. Default live recognition remains ML Kit.

The phone testbench compares all three on the same synthetic page, distinguishing fresh and warm sessions. Raw recognition is scored before narration punctuation; structured table scores are unavailable for engines without table reconstruction. Memory is sampled during inference once per second. See [benchmark definitions](PERFORMANCE_AND_BENCHMARK.md).

The following section records the original 0.1.10 setup and validation. Its one-page session lifetime has been superseded by the reuse policy above.

## Original OCR models and processing effort — 0.1.10

Install the ARM64 APK over 0.1.9. The application ID remains `app.readflow`; version code rises from 10 to 11. The Room database version, private document paths, voice-model manifests and preference storage name are unchanged. Existing extraction defaults remain ML Kit and Medium/Balanced. Do not uninstall or clear app data when updating.

## Use

Open **Settings > Text recognition**. Choose OCR effort separately from the model:

| Effort | ML Kit source | Focused retries | TeleOCR crop image budget | CPU threads |
|---|---|---|---|---|
| Low / Fast | 200 DPI, at most 4 MP | None; no automatic deskew | About 512 visual tokens | 2 |
| Medium / Balanced | 300 DPI, at most 10 MP | At most 6 uncertain lines | About 1024 visual tokens | 3 |
| High / Accuracy | 300 DPI, at most 10 MP | At most 12, broader uncertainty selection | About 2048 visual tokens | 4 |

Thread counts apply to TeleOCR only. The layout stage retains the upstream 1036×1036 input independently of the crop effort. These are processing budgets, not measured latency or accuracy promises. High does not mean faster CPU processing.

ML Kit is built in. For TeleOCR, tap **Download TeleOCR**, keep the app open during setup, then select its radio button. Setup downloads 1,393,568,928 bytes, requires approximately 3 GB free space while copying/verifying, and supports resuming interrupted transfers. The model is not bundled into the APK. Downloads are pinned by revision, byte length and SHA-256 in `app/src/main/assets/ocr-model-manifests.json`.

Settings apply to new recognition. Saved pages retain their text and bookmarks until **Apply to current page** or **Reader menu > Retry text recognition**. Embedded PDF text that meets the existing usability and geometry checks bypasses OCR. Changing OCR settings does not change speech speed or voice models.

## Local TeleOCR pipeline

The app uses the community Q5_K_M language model plus Q8_0 vision projector from [nandraj/NaviDC-OCR-GGUF](https://huggingface.co/nandraj/NaviDC-OCR-GGUF/tree/53dcb6bfd439ccd0f552e6667592f0653270f9c4), the earlier name of [TeleOCR](https://huggingface.co/XingChen-AGI/TeleOCR). The Android JNI bridge links a pinned CPU-only llama.cpp runtime plus the tracked architecture patch. Full source pin and patch are in `scripts/prepare_teleocr.py` and `scripts/patches/`.

The upstream model uses separate layout and cropped-region recognition passes. ReadFlow follows that structure, including region rotation and the table OTSL prompt. It preserves model reading order and reads table cells row by row with pauses. Cell coordinates, blank cells and merged spans are retained in saved table metadata. Column headings are not automatically repeated before each value.

ML Kit runs first to supply fallback text and measured word boxes. TeleOCR words use those boxes only when correspondence is defensible; unmatched words get no invented original-page geometry. They can still be read and selected in text view. The reading-details dialog identifies the actual extraction engine and any limitations.

The model stays loaded only while processing one page, then releases its native context and weights. Native calls check cancellation during loading and vision/text evaluation. Context/output/image budgets are bounded, and generation that exhausts its output budget is rejected. A ten-minute page timeout, malformed/partial tables/layout, insufficient free memory or runtime errors preserve the ML Kit result with a visible warning. Cancelling or navigating away does not save a partial page. No page image or extracted text is uploaded for inference.

## Validation and limits

Release checks passed: **189 JVM tests**, zero failures/skips; Android lint with zero errors and 16 warnings (dependency versions, target API, exported service and storage-space checks); ARM64 APK and instrumentation APK compilation. The packaged OCR library exports the five expected JNI methods and has 16 KiB ELF/ZIP alignment. APK certificate, package ID and increasing versionCode match the 0.1.9 update path. Exact APK hash and results are in [apk-validation.json](evidence/teleocr/apk-validation.json); real native text/table/layout results are in [validation.json](evidence/teleocr/validation.json).

The supplied evidence fixtures are original test text, not extracts from the user's book. Host recognition, JVM regression results, ARM64 packaging and upgrade-signature checks are recorded with this release. A host result is not an S25 Ultra benchmark. No phone is attached in the build environment; on-device runtime speed, memory, heating, gestures and installation remain to be checked on the target device.

For comparison on the phone, use the same difficult pages with each engine and effort, and check names, every monetary amount/decimal, table associations and reading order against the original. **Apply to current page** is necessary to bypass the saved extraction. Last TeleOCR duration and fallback status appear in Settings. Keep a copy of the ML Kit result before comparison if needed; re-recognition replaces that page's saved text.
