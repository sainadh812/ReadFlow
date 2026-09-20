# Dependency and model notices

This inventory is part of the source delivery. No large model weights or third-party source trees are committed. The Gradle wrapper, small generated original fixtures and evidence audio are included. Downloaded third-party build inputs remain under ignored `app/libs`, `app/src/main/assets/vendor` and `.deps`.

| Component | Pin | License / source |
|---|---|---|
| Kotlin/compiler/Compose compiler | 2.1.20 | Apache-2.0; https://github.com/JetBrains/kotlin |
| Gradle wrapper | 8.11.1 | Apache-2.0; https://github.com/gradle/gradle |
| Android Gradle plugin | 8.6.1 | Apache-2.0; https://developer.android.com/build/releases/agp-8-6-0-release-notes |
| Android lint override | 8.8.2 | Apache-2.0; https://developer.android.com/develop/ui/compose/tooling/lint |
| Compose BOM/Material 3 | 2025.04.01 | Apache-2.0; https://developer.android.com/jetpack/compose/bom |
| Activity/Lifecycle | 1.10.1 / 2.8.7 | Apache-2.0, AndroidX |
| Room/DataStore | 2.7.1 / 1.1.4 | Apache-2.0, AndroidX |
| Media3 | 1.6.1 | Apache-2.0; https://developer.android.com/media/media3/session/background-playback |
| KSP | 2.1.20-1.0.32 | Apache-2.0; https://github.com/google/ksp |
| Coroutines/serialization | 1.10.1 / 1.8.0 | Apache-2.0, JetBrains |
| Bundled ML Kit Latin OCR | 16.0.1 | Google ML Kit terms and notices; https://developers.google.com/ml-kit/terms and https://developers.google.com/ml-kit/vision/text-recognition/v2/android |
| Sherpa-onnx Android static AAR | 1.13.8 | Apache-2.0 for Sherpa; bundled native dependencies retain their licenses. https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8 |
| Sherpa embedded ONNX Runtime | 1.28.2 | MIT; Android ARM64 static dependency pin verified in Sherpa's `cmake/onnxruntime-android-aarch64-static.cmake` |
| ONNX Runtime Java/Android alignment | 1.20.0 | MIT; https://github.com/microsoft/onnxruntime/tree/v1.20.0 |
| Mozilla Readability | 0.6.0 | Apache-2.0; pinned source and full LICENSE.md fetched by bootstrap from https://github.com/mozilla/readability/tree/0.6.0 |
| Jsoup | 1.18.3 | MIT; https://jsoup.org/license |
| OkHttp/Okio | OkHttp 4.12.0, resolved Okio dependency | Apache-2.0; https://github.com/square/okhttp/tree/parent-4.12.0 |
| Apache Commons Compress | 1.27.1 | Apache-2.0; https://commons.apache.org/proper/commons-compress/ |
| JUnit / AndroidX Test | 4.13.2 / runner 1.6.2 | EPL-1.0 / Apache-2.0 |

## Models and voices

- **Kokoro-82M v0.19 English ONNX export**: Hexgrad Kokoro; Apache-2.0. The verified k2-fsa export archive includes its Apache license, model, voices, tokens and eSpeak data. https://huggingface.co/hexgrad/Kokoro-82M and https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models . Speaker IDs exposed here are 0 (default af), 1 (Bella), 2 (Nicole); no performance/quality ranking is claimed.
- **eSpeak NG**: GPL-3.0-or-later for the phonemizer and data, with component-specific notices where applicable. https://github.com/espeak-ng/espeak-ng . Sherpa's TTS binary includes this dependency; distribution must comply with its copyleft/source obligations, not merely Sherpa's Apache license. Upstream build/source instructions are supplied below. Public-store release licensing has not been approved by the project owner.
- **Pocket TTS**: Kyutai upstream source uses MIT; the selected k2-fsa `int8-2026-01-26` ONNX export archive includes a **CC BY 4.0** LICENSE. Attribute Kyutai and the k2-fsa/sherpa-onnx export contributors. Preserve the export license as well as upstream notices. https://github.com/kyutai-labs/pocket-tts and https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models . Only required model/tokenizer files are installed; unrelated archive test voices are not installed or offered.
- **Alba MacKenna, Casual voice**: performed by Alba MacKenna; supplied by Kyutai at pinned repository revision `323332d33f997de8394f24a193e1a76df720e01a`; CC BY 4.0. Original recording is used as a fixed model voice reference, without an arbitrary voice cloning interface. https://huggingface.co/kyutai/tts-voices/blob/323332d33f997de8394f24a193e1a76df720e01a/README.md and https://creativecommons.org/licenses/by/4.0/ . Generated speech is produced by the model, not presented as an original recording by the performer.
- **Wav2Vec2 base 960h**: Meta/Facebook base checkpoint under Apache-2.0, ONNX Community quantized export at `729c1a6730fb549c20a1c73a3d3f96f11020225e`. https://huggingface.co/facebook/wav2vec2-base-960h and https://huggingface.co/onnx-community/wav2vec2-base-960h-ONNX . The quantized export is used only for emissions against the known transcript. Its download is explicitly separate from speech packs.

## Source/build availability

The exact Sherpa AAR is pinned to the upstream release SHA-256. Obtain corresponding source with `git clone --branch v1.13.8 --depth 1 https://github.com/k2-fsa/sherpa-onnx.git` outside this repository and follow that tag's Android build workflow/scripts. Its CMake files pin ONNX Runtime and native dependencies. The eSpeak fork is `csukuangfj/espeak-ng` commit `ed530aa113046142eb5115cf2fc9157854d0ffe1`, source-archive SHA-256 `e4e262cbe34f7fe21f91f1ba3397f2728e1f30eafbae7853f2b753a9ed13f0dd`, as specified by `cmake/espeak-ng-for-piper.cmake`. Record all fetched dependency revisions and preserve their license notices when redistributing a rebuilt binary. ReadFlow does not vendor these source trees. No modified model export is required by this implementation.

Original reading text, generated PDF/image fixtures and fixture scripts were created for this project and may be used under CC0. Fixture speech derives from the listed voice models; keep the corresponding model/voice attribution when sharing it.

App code adds no analytics or automatic diagnostic uploads. At the user's request, 0.1.4 adds private, error-only input logging with explicit export/delete controls; logs may contain document excerpts and generated speech. Version 0.1.6 adds user-confirmed GitHub issue uploads through the existing OkHttp client, with metadata-only public mode and private-repository input excerpts. No new Android dependency is added. See [ISSUE_LOGS.md](ISSUE_LOGS.md) and [GITHUB_DIAGNOSTICS.md](GITHUB_DIAGNOSTICS.md). ML Kit is a Google binary SDK; its terms and telemetry behavior must be assessed in the requested airplane-mode/network capture test. Do not interpret absence of app analytics code as a verified statement about every transitive SDK's telemetry.
