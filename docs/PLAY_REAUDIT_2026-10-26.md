# Read Assit: distribution-license re-audit, 26 October 2026

**Commercial distribution is feasible under the reviewed declared licenses, provided their notice and source-availability obligations are preserved. This review is not blanket legal clearance or Play approval.** No commercial-use prohibition was established for the selected release models or reader fonts.

This audit inspected the already built **ReadFlow 0.2.1**, package `app.readflow`, version code `17`. The public rename to **Read Assit**, proposed free/paid feature split, billing and any promotion mechanism are subsequent work. The artifact inspected here contains neither the proposed paywall nor the new notice additions described below. The repository/package identity remains unchanged.

**Plan update:** the owner subsequently chose [free distribution with optional creator tips](FREE_DISTRIBUTION_PLAN.md). Paid features and coupons are deferred. The artifact facts below remain the historical 0.2.1 review.

## Artifact and reproducible checks

| Item | Verified result |
|---|---|
| Artifact | `dist/play-0.2.1/ReadFlow-0.2.1-play-unsigned.aab` |
| SHA-256 | `84056ec99abaa69753e7f5f032ce7f3fa8b7a658ad4d9e4c4e011b91fa3fb447` |
| Size | 70,975,360 bytes |
| Bundle validation | Passed; target API 36; not debuggable; unsigned review bundle |
| Native libraries | Seven ARM64 libraries; checked ELF load alignment and 16 KB bundle configuration |
| Speech exclusion checks | Known Sherpa/eSpeak native entry points, Sherpa `OfflineTts` JNI class and eSpeak model-download files absent |
| Runtime inventory | 133 resolved artifacts; 85 without embedded notices have supplemental license/POM mappings |
| Fonts | Six families, seven files; all packaged font and OFL license hashes match retained provenance |

The absence checks target known libraries, classes and entry points. They do not prove the absence of every possible copyleft implementation in closed-source SDKs.

Re-run commands used:

```bash
python3 scripts/verify_play_bundle.py \
  --aab dist/play-0.2.1/ReadFlow-0.2.1-play-unsigned.aab \
  --report /tmp/readflow-0.2.1-reaudit-packaging.json
python3 scripts/verify_reader_fonts.py \
  --artifact dist/play-0.2.1/ReadFlow-0.2.1-play-unsigned.aab
```

See [the audit record](evidence/play-reaudit-2026-10-26/audit.json), [bundle inspection](evidence/play-reaudit-2026-10-26/packaging.json), [MPL source evidence](evidence/play-reaudit-2026-10-26/mpl-source-evidence.json) and [font result](evidence/play-reaudit-2026-10-26/fonts.json). Prior compilation, tests and artifact signing evidence remains in [the 0.2.1 build results](evidence/play-store-0.2.1/build-results.json). No physical-device tests were performed during this re-audit.

## Models, fonts and runtime scope

- **Kokoro v0.19 and voice styles:** the exact pinned ONNX export contains Apache-2.0 licensing. Its unchanged four-file release download excludes the old eSpeak archive. [Pinned export](https://huggingface.co/csukuangfj/kokoro-en-v0_19/tree/92805c485745946a0d945562d3aba19e7cbb2104).
- **Wav2Vec2 alignment:** the pinned ONNX conversion identifies the Apache-2.0 Facebook checkpoint; retained provenance identifies both revisions. [Base checkpoint](https://huggingface.co/facebook/wav2vec2-base-960h/tree/22aad52d435eb6dbaf354bdad9b0da84ce7d6156), [conversion](https://huggingface.co/onnx-community/wav2vec2-base-960h-ONNX/tree/729c1a6730fb549c20a1c73a3d3f96f11020225e).
- **Misaki:** the direct path uses the Apache-2.0 frontend/dictionaries and neural fallback, with MIT/BSD/Unicode notices for incorporated assets/dependencies. Its native build rejects eSpeak/phonemizer imports. Training and voice provenance are based on upstream declarations, not an independently established chain of all underlying rights. [Pinned Misaki license](https://github.com/hexgrad/misaki/blob/fba1236595f2d2bf21d414ba6e57d25256afada3/LICENSE).
- **PaddleOCR:** both pinned official model cards declare Apache-2.0; the optional download includes the upstream license. [Detector](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/tree/e6f4fa85f00e168c862bc462aebca69eef9b3d3d), [recognizer](https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx/tree/89d3a50e2c27e2e7cceeab0e944c25c807d5db4f).
- **TeleOCR:** original and converted GGUF weights declare Apache-2.0; the native llama.cpp runtime is MIT. The manifest wording `llama.cpp and GGUF conversion: MIT` is inaccurate for the weights. **This is a recorded erratum; the serialized download manifest was deliberately not changed**, because even a label-only change affects whole-manifest installation fingerprints. Correct it with migration handling in a future implementation. [Pinned conversion card](https://huggingface.co/nandraj/NaviDC-OCR-GGUF/blob/53dcb6bfd439ccd0f552e6667592f0653270f9c4/README.md), [original model](https://huggingface.co/XingChen-AGI/TeleOCR).
- **Reader fonts:** all six families use OFL-1.1 and are bundled unchanged with their original notices. Commercial app bundling is permitted; this does not make the application itself OFL-licensed. Do not claim a medical benefit or sell the fonts as standalone products. [SIL's guidance](https://openfontlicense.org/ofl-faq/).

The historical Sherpa/Pocket/eSpeak distribution remains a **debug/prototype** concern. The project-authored Misaki provenance note now states this scope explicitly. Original upstream notices are preserved.

## Resolved source-level questions

**Media3 1.6.1:** downloaded all eight resolved modules' source JARs from Google's Maven repository. Of 891 Java/Kotlin source files, 888 carry Apache-2.0 headers; the three generated AIDL classes were traced to their matching Apache-2.0 upstream `.aidl` sources. The tagged upstream tree has an Apache LICENSE and no additional NOTICE file. Source URLs and hashes are recorded in the audit JSON. This closes the previously open module-header/NOTICE question for these selected modules; it is not a claim about every optional Media3 codec. [Tagged source](https://github.com/androidx/media/tree/1.6.1).

**Graphics Path 1.0.1:** inspected the release-notes-linked source commit `8a05a22af450d589ef911d772a001a49dcb05b71`. Its ten native C++/header files carry Apache-2.0 headers, and CMake compiles `Conic.cpp`, `PathIterator.cpp` and `pathway.cpp`. No separate Skia code/license was established in this module inspection. The existing Apache notice applies to these sources. [Exact native tree](https://android.googlesource.com/platform/frameworks/support/+/8a05a22af450d589ef911d772a001a49dcb05b71/graphics/graphics-path/src/main/cpp/), [release notes](https://developer.android.com/jetpack/androidx/releases/graphics#graphics-path-1.0.1).

**DataStore's relocated Protobuf:** existing notices correctly preserve its separate BSD-3-Clause license and upstream version evidence; do not describe it as covered only by AndroidX's Apache license.

## MPL components and source availability

MPL-2.0 is file-level copyleft and permits inclusion in a proprietary commercial application. Covered source must remain available under its terms; unrelated application files are not automatically relicensed. [Mozilla's distribution FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/).

**OkHttp Public Suffix List:** found the original source snapshot in the exact OkHttp 4.12.0 commit `4984568367caaf359b82c452bd28b5e192824d1c`. Reconstructed its sorted UTF-8 rule/exception payload using the upstream generator's format and compared it with `base/root/okhttp3/internal/publicsuffix/publicsuffixes.gz` in the candidate: **exact match**. The unchanged 246,016-byte source is now supplied under `assets/licenses/runtime/okhttp-4.12.0-public_suffix_list.dat`, together with a readable source index and full MPL text. Existing upstream notices remain intact. The new files will enter the next build, not the previously inspected AAB. [Pinned source](https://github.com/square/okhttp/blob/4984568367caaf359b82c452bd28b5e192824d1c/okhttp/src/test/resources/okhttp3/internal/publicsuffix/public_suffix_list.dat).

**ONNX Runtime Eigen:** upstream v1.22.0 `cmake/deps.txt` pins Eigen commit `1d8b82b0740839c0de7f1242a3585e3390ff5f33`; `cmake/external/eigen.cmake` consumes that dependency. The index now links that exact source and preserves Microsoft's original source-availability offer in `onnxruntime-1.22-ThirdPartyNotices.txt`. This establishes the published upstream source pin, not a reproducible build of Microsoft's prebuilt Android binary. A newly generated GitLab archive's transport hash differs from the old SHA-1 in `deps.txt`; both are recorded rather than claiming that archive check passed. [ORT dependency pin](https://github.com/microsoft/onnxruntime/blob/v1.22.0/cmake/deps.txt), [Eigen source](https://gitlab.com/libeigen/eigen/-/tree/1d8b82b0740839c0de7f1242a3585e3390ff5f33).

## Google SDK limits and publication gates

The bundled ML Kit/Play-services notices are preserved. They contain aggregate GPL/LGPL text alongside permissive, dual-license and toolchain notices, including Kotlin, LLVM, GCC and ducc. **A license-text match does not establish that a GPL-only implementation is linked into this app.** This review did not establish a complete binary-to-source/exception mapping and does not assert “no copyleft anywhere.” No new confirmed distribution violation was identified. The vendor SDK terms and data disclosures still apply. [ML Kit terms](https://developers.google.com/ml-kit/terms), [Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).

Before public release:

1. Preserve the bundled/source-availability notices and record any new dependency licenses. Include the OCR labeling erratum in technical follow-up without silently invalidating existing installations.
2. Decide the app's own license/EULA with the owner; preserve third-party rights and source offers. A public repository does not itself license all application source, and model licenses do not supply the owner's EULA.
3. Finish real developer/contact/privacy details and accurate Data safety disclosures, including Google SDK telemetry and the eventual purchase/promotion data flow. There is no final monetized data-flow implementation to certify here.
4. Rebuild with production signing after the rename, policy and approved monetization work. Verify the exact final signed AAB's dependency delta, model manifests, notices, hashes, billing behavior and device/Play delivery. The unsigned 0.2.1 review bundle and debug-signed preview are not production upload credentials.

The proposed free/paid plan, regional pricing and promotion mechanics need their own Play-policy and implementation review. This license audit does not authorize publication of an unimplemented offer.
