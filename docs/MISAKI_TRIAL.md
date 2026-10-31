# Misaki pronunciation trial

ReadFlow 0.1.14 adds an **experimental Kokoro + Misaki** option. The existing Kokoro/eSpeak option remains the default. The experiment reuses the verified v0.19 acoustic model, voice styles and word-alignment download. It changes how English text becomes phonemes and runs Kokoro directly through ONNX Runtime.

## Try it

1. Install the update over the existing prototype. Keep the existing Kokoro and Word alignment packs installed.
2. Open **Settings → Voice models → English pronunciation → Misaki · experimental**. Tap **Try pronunciation sample**, preview a Kokoro voice, or read an imported document. Switching pronunciation stops the current reading session; press Play to start with the selected option.
3. In **Phone testbench**, select both Kokoro and Kokoro + Misaki. Use the same voice and CPU settings. Choose the pronunciation sample set and five warm samples to cover names, numeric ranges and words whose pronunciation depends on context. The financial sample set remains available for comparison with previous reports.

The benchmark measures real local generation and word alignment. It reports the Misaki phonemizer and acoustic-inference times separately, alongside total preparation throughput and sampled process memory. The testbench does not score pronunciation by listening, and it does not measure speaker gaps. Use the voice preview and actual reading to compare pronunciation. A faster phonemizer is not proof that the complete speech pipeline is faster.

## Implementation

- The English frontend is the complete `androiddrew/go-g2p` Misaki port at `ec904cda37a171003dc0d2ef9011f8a30c32e7a1`: dictionaries, tokenizer, part-of-speech model, morphological rules and a neural fallback for words outside the dictionary. The Android bridge imports `fallback/neural`, never `fallback/espeak`. No Python runtime or server is used.
- Misaki's compressed phoneme alphabet is explicitly converted to the older Kokoro v0.19 alphabet. Unsupported phones or an overlong pronunciation fail visibly instead of silently dropping text. Boundary tokens, the length-indexed voice style and the existing short-silence policy are preserved.
- Existing numeric normalization and pronunciation corrections remain active. For example, `Milanković's` still becomes `Milankovitch's` before synthesis. The neural fallback alone can guess an unusual name incorrectly; this is not an automatic name-pronunciation guarantee. Host checks also found the wrong vowel for present-tense “read” in “I read books every day”; keep this trial optional while evaluating it.
- Only the selected speech backend is loaded. Native calls drain before session teardown. The small frontend uses one inference thread; acoustic generation follows the selected speech CPU effort. Thread spinning remains off by default.
- Misaki audio has its own cache identity, including the pinned frontend and phone-adapter versions. Legacy cached speech cannot mask which frontend is being tested; switching back can reuse legacy audio.
- ONNX Runtime Java/Android is upgraded from 1.20.0 to 1.22.0 for the Go binding's C API. This also changes the runtime used by PaddleOCR and alignment. The unchanged Sherpa backend retains its separately embedded runtime, so the comparison measures the complete alternative implementation, not an isolated phonemizer change.

## Build and provenance

The Gradle `prepareMisaki` task runs `scripts/prepare_misaki.py`. It builds the JNI/Go bridge from `app/src/main/misaki`, verifies Go module checksums, pins Go 1.26.4 and NDK 27.2.12479018, and rejects an eSpeak import in the dependency graph. Linux x86_64 can download the checksum-pinned Go toolchain; other build hosts need that Go version installed. Python must support `tarfile`'s `data` extraction filter. The normal ABI is ARM64; `-Pemulator=true` builds x86_64.

The frontend embeds its dictionaries and small POS/G2P models in its native library. These are included with the APK; the much larger Kokoro acoustic weights, voice styles and alignment model remain separate downloads. Full notices, exact upstream revisions, actual resolved Go dependency versions and embedded asset hashes are packaged under `assets/licenses/misaki/`.

Relevant upstream sources: [Misaki](https://github.com/hexgrad/misaki/tree/fba1236595f2d2bf21d414ba6e57d25256afada3), [Go port](https://github.com/androiddrew/go-g2p/tree/ec904cda37a171003dc0d2ef9011f8a30c32e7a1), [US neural fallback](https://huggingface.co/PeterReid/graphemes_to_phonemes_en_us/tree/a5631b285d18d59483c32c0c3379cb9fac924f4b), [spaCy English model](https://huggingface.co/spacy/en_core_web_sm). The direct Kokoro pause policy adapts Sherpa's Apache-2.0 `GeneratedAudio::ScaleSilence` algorithm (Copyright 2023 Xiaomi Corporation), without using its GPL phonemizer implementation.

## Licensing boundary and validation limits

The new path uses Apache-2.0, MIT, BSD and Unicode-licensed components and retained notices. Its dependency graph has no eSpeak implementation, executable or data dependency. Upstream dictionaries and neural weights retain their declared licenses; upstream training-data provenance is limited.

**The whole prototype APK still contains the old Sherpa/eSpeak backend. Selecting Misaki does not remove that binary or its GPL obligations. This release is a migration experiment, not clearance for a closed-source Play Store launch.** A subsequent release must remove or replace that bundled dependency, complete the wider license inventory and satisfy store packaging/policy requirements.

Host synthesis, JVM regressions and Android packaging checks can establish functional integration. They cannot establish S25 pronunciation quality, sustained speed, heat or battery use. Android instrumentation tests exercise the packaged frontend and optionally download Kokoro for a real synthesis test; without an attached device these are compiled but not executed. See `STATUS.md` for exact completed checks.
