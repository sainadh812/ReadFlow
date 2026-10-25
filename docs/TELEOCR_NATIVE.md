# Local TeleOCR runtime

ReadFlow's optional TeleOCR engine uses local CPU inference. Model downloads are explicit; document images are never sent to a server by this engine. Model choice is separate from the Fast/Balanced/High processing presets. ML Kit remains the fallback when TeleOCR fails, runs out of its processing budget, or cannot safely map a page.

## Reproducible native build

Run `python3 scripts/prepare_teleocr.py`, then the normal Gradle build. Android NDK `27.2.12479018`, CMake `3.22.1`, and C++17 are required. The script downloads [llama.cpp commit 2280f0fd163b03d4331d49e08460f66502e35854](https://github.com/ggml-org/llama.cpp/tree/2280f0fd163b03d4331d49e08460f66502e35854) into ignored `.deps/teleocr-llama` and applies the reviewed patch under `scripts/patches/teleocr-qwen2vl.patch`.

TeleOCR's text backbone has a larger attention head width than stock Qwen2-VL and adds per-head Q/K normalization. The patch corrects the Q/output tensor dimensions and applies those normalization weights before rotary position encoding. It follows the community converter's [documented architecture changes](https://huggingface.co/nandraj/NaviDC-OCR-GGUF/blob/53dcb6bfd439ccd0f552e6667592f0653270f9c4/PATCHES.md). Unlike a load-only check, this release's validation exercises real image inference through the same JNI source compiled for Android.

`readflow_teleocr` statically links llama, ggml and mtmd. Only the five JNI entry points are exported, avoiding native symbol collisions with the existing speech libraries. Its ELF load segments use 16 KiB alignment. Runtime license notices are bundled in `assets/licenses/teleocr-runtime.txt`.

## Model pack

Source: [nandraj/NaviDC-OCR-GGUF, revision 53dcb6bfd439ccd0f552e6667592f0653270f9c4](https://huggingface.co/nandraj/NaviDC-OCR-GGUF/tree/53dcb6bfd439ccd0f552e6667592f0653270f9c4), linked by the [official TeleOCR model card](https://huggingface.co/XingChen-AGI/TeleOCR). NaviDC-OCR is TeleOCR's earlier name. Quantization is a community conversion, not an independently trained model.

| File | Bytes | SHA-256 |
|---|---:|---|
| NaviDC-OCR-Q5_K_M.gguf | 551374976 | 99871e52c43058dd4d2f3e1a03c657e8870a6be90de2cef3978152b2be67cf61 |
| NaviDC-OCR-mmproj-q8_0.gguf | 842193952 | 240002b4a74b77066510de1f4a4395930a4a4ef31fc2d5c36fec2e7282f8d60e |

The approximately 1.39 GB download is separate from the APK. Runtime memory exceeds the weight file size. The text model is memory-mapped and the session releases its models after each page. JNI bounds image bytes, visual tokens, output tokens and context size; it rejects empty or truncated outputs. A session handle is created before model loading so cancellation reaches loader progress callbacks as well as vision matrix operations, text prefill and generation. All native work runs on a worker thread.

## Validation and limits

Synthetic fixtures under `docs/evidence/teleocr` are original, shareable test documents. `scripts/test_teleocr_native.sh` compiles the production JNI source for Linux and invokes it through the small Java harness in `scripts/teleocr-smoke`; it requires the model files in `.deps/teleocr-model`.

The initial real-inference tests use the Q5 text model and Q8 vision component, CPU inference, greedy decoding and no network calls during recognition. The text fixture covers accented names, apostrophes, ranges, year labels, capacities and currency. The table fixture checks content and OTSL row/column structure. The layout fixture exercises the upstream 1036 × 1036 layout input. Token-limit and cancellation tests verify that incomplete results are rejected.

Host smoke tests establish working inference and integration contracts, not comparative accuracy or phone performance. There is no attached S25 Ultra in this workspace. Do not present host timing, published full-precision benchmarks, or successful synthetic examples as measured S25 speed or book-level accuracy. This optional engine remains experimental until tested on real books on the device.
