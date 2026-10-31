# PaddleOCR mobile Latin integration

ReadFlow offers **PaddleOCR · Mobile Latin** as a third, optional local OCR engine. It runs a real PP-OCRv5 mobile text detector and Latin-script recognizer using the app's existing ONNX Runtime CPU dependency. The model download is **12,886,734 bytes (12.89 MB / 12.29 MiB)** including its dictionary/configuration and license. Model weights are downloaded separately from the APK.

This is text detection/recognition, not the larger PaddleOCR-VL document model. It does not infer table structure or reconstruct merged cells. Accuracy, latency and memory on the S25 Ultra must be measured with the integrated benchmark and representative book pages.

## Provenance and integrity

All assets come from official PaddlePaddle repositories at immutable revisions. The app's existing `ModelStore` verifies byte counts and SHA-256, stages the complete pack, then atomically installs it. The exact URLs are in [ocr-model-manifests.json](../app/src/main/assets/ocr-model-manifests.json).

| Asset | Official revision | Bytes | SHA-256 |
|---|---|---:|---|
| `det.onnx` | [PP-OCRv5_mobile_det_onnx @ e6f4fa8](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/tree/e6f4fa85f00e168c862bc462aebca69eef9b3d3d) | 4,826,518 | `a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d` |
| `rec.onnx` | [latin_PP-OCRv5_mobile_rec_onnx @ 89d3a50](https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx/tree/89d3a50e2c27e2e7cceeab0e944c25c807d5db4f) | 8,042,023 | `7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498` |
| `rec.yml` | Same recognizer revision | 6,817 | `0bbe984570f597af3638e50bdf2e8276f3ab26a61966096538b3b0d1849f5c84` |
| `LICENSE-PaddleOCR.txt` | [PaddleOCR @ dab3fe3](https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/LICENSE) | 11,376 | `3840c5c0c61c294264d2dd77b8777be6ddd90121ef4e0e64abcd22edea581d6e` |

PaddleOCR models and reference deployment are Apache-2.0, copyright PaddlePaddle Authors. ONNX Runtime is MIT. ReadFlow's bounded Kotlin postprocessing follows the published DB/CTC contracts; it does not bundle OpenCV or a new native Paddle runtime.

Reference implementation: [official Android deployment documentation](https://www.paddleocr.ai/latest/en/version3.x/inference_deployment/cross_platform/android_deployment.html) and [pinned Android SDK source](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk/src/main/java/com/paddle/ocr). The reference uses a newer ORT/OpenCV stack; ReadFlow uses its existing ORT 1.20.0 Android dependency, with the pinned ONNX models also smoke-tested on host ORT 1.20.1. Host compatibility does not replace Android runtime testing.

## Pipeline and deliberate limits

1. Resize the detector input to a multiple of 32; normalize BGR channels with ImageNet means/deviations. The detector outputs a text-probability map.
2. Apply DB threshold 0.3, confidence threshold 0.6 and expansion ratio 1.5. The Kotlin implementation finds connected components, convex hulls and minimum-area rectangles; it scores all pixels inside each rectangle before expansion. It expands the rectangle along its axes rather than adding OpenCV and polygon-offset dependencies. Very irregular/curved text may differ from upstream postprocessing.
3. Perspective-crop each quadrilateral, rotate vertical crops, then resize to height 48 with RGB `[-1,1]` normalization and zero padding. As in the upstream Android deployment, recognition inputs are RGB even though the model training configuration mentions a BGR decoder. Lines requiring more than 3,200 pixels width fail visibly instead of being silently squashed.
4. Decode actual recognizer probabilities with CTC: blank index 0, collapse adjacent duplicate labels, preserve labels separated by blanks. Parse the verified model dictionary in its original order: 836 entries plus blank and space, **838 classes**. Do not deduplicate dictionary entries. Unicode NFC normalization preserves accents, currency and punctuation.
5. Order lines geometrically, with a conservative central-gutter heuristic for two-column pages. Keep `recognitionText` before any narration formatting so the benchmark measures model output. This is not a learned layout model; dense tables and mixed layouts need manual checking.

| OCR effort | Detector longest side | ORT intra-op threads |
|---|---:|---:|
| Low / Fast | 736 px | 2 |
| Medium / Balanced | 960 px | 3 |
| High / Accuracy | 1,280 px | 4 |

The small models stay warm across pages. An idle timer releases both sessions after 45 seconds; explicit close/model deletion also drains active work before releasing native sessions. ORT intra/inter-op thread spinning is disabled to avoid idle CPU use while sessions remain loaded. Inference cancellation sets ORT's terminate flag, waits for the call to return and only then closes tensors/options. Detection maps, region counts, tensor output sizes and crop pixels are bounded.

ML Kit's independently observed word boxes are reused only when the recognized words agree exactly (Unicode NFC-equivalent text is accepted). Paddle text always comes from Paddle inference. Changed numbers, missing accents, ambiguous repeats or unmatched words get **no invented original-page highlight box**. They remain available for speech and selection in text view. The same measured reference box cannot be assigned to multiple overlapping Paddle regions. This conservatism means a correctly recognized rare name can lack its original-page highlight if ML Kit misread it.

No image or recognized text is uploaded during inference. Downloads only fetch public model assets.

## Reproduce the host smoke

```sh
python3 scripts/fetch_paddleocr.py
.venv/bin/python scripts/smoke_paddleocr.py
```

The smoke needs NumPy, Pillow, ONNX Runtime and the project's downloaded Gradle distribution (used only to launch its Kotlin compiler). It compiles the **production** `PaddleOcrPostprocess.kt` with minimal host geometry types, runs the actual ONNX detector/recognizer, and feeds their real tensors through production Kotlin DB/CTC. Pillow supplies host image preprocessing/cropping; Android bitmap operations, native cancellation/lifecycle and device thermals need phone testing.

Evidence: [original synthetic finance fixture](evidence/paddleocr/finance-fixture.png) and [host-smoke.json](evidence/paddleocr/host-smoke.json). The fixture contains accented names, an age range, years, monetary decimals and a four-row table. Detection found 15 regions; recognition preserved **Milanković**, **Köppen**, **20-24**, **$9.3** and every table cell in row order. Two curly apostrophes became straight apostrophes, so the fixture is not an exact transcription. This small, clean fixture is a compatibility/regression check, not a general book-accuracy score. Its measured host inference durations exclude image preparation, Kotlin process startup/postprocessing and all Android costs; they are not S25 Ultra latency claims.

JVM tests cover dictionary indexing/quotes/Unicode, CTC blanks and repeated characters, nonfinite and malformed tensors, detector scoring/expansion/clipping/cancellation, exact geometry matching, ambiguous repeats, changed numbers and overlapping regions. The integrated phone benchmark compares cold/warm runs and uses unformatted OCR output for text error rate.
