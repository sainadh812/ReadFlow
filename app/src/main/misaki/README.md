# Experimental Misaki frontend

This adapter embeds the complete English frontend from `androiddrew/go-g2p`
revision `ec904cda37a171003dc0d2ef9011f8a30c32e7a1`: Misaki dictionaries and
rules, trained spaCy tokenizer/POS, and PeterReid's neural unknown-word fallback.
It imports `fallback/neural` only. No eSpeak implementation, data, loader or
subprocess fallback is included in this library. ReadFlow's separate legacy
Sherpa backend still bundles eSpeak, so this is not a GPL-free APK claim.

`go.mod` and `go.sum` pin every source module and embedded asset. Retained
licenses, source revisions, actual dependency versions and asset SHA-256 values
are in `app/src/main/assets/licenses/misaki/`. The upstream dictionaries and
neural model cards declare Apache-2.0; their training-data provenance is limited.

Run `python3 scripts/prepare_misaki.py` to build the Android arm64 `.so`.
`--abi x86_64` supports the existing emulator build. Gradle packages the generated
library from `app/build/generated/misaki/jniLibs`. The script pins Go 1.26.4,
uses NDK 27.2.12479018, verifies modules, rejects eSpeak-related imports and sets
16 KB ELF segment alignment. Native ONNX Runtime 1.22.0 is provided by the app's
existing Java/Android ONNX dependency; the frontend loads its C API dynamically.

The Kotlin `MisakiFrontend` constructor synchronously loads the dictionaries and
five small ONNX sessions on the caller's worker thread. `phonemize` uses US
English and fails explicitly if a word is unresolved or neural generation is
truncated. `close` releases owned sessions. The Go ORT environment stays alive
until process exit; closing one frontend cannot invalidate another frontend.
Requests and close are serialized. Native inference cannot be interrupted in
the middle of a call. The direct Kokoro adapter converts the returned current
Misaki phoneme alphabet to the existing v0.19 model's token alphabet.

The host smoke test is `python3 scripts/smoke_misaki.py --ort /path/to/libonnxruntime.so`.
It checks context-sensitive pronunciation, neural fallback, explicit failures,
concurrent frontend ownership and repeat loading. Successful phoneme production
does not certify pronunciation: uncommon names still need review and overrides.
The host smoke records two observed upstream limitations: raw `Milanković's`
loses its final affricate in neural fallback, and present-tense `read` tagged VBP
uses the past-tense vowel in the current dictionary. These are reasons to keep
this frontend experimental and preserve ReadFlow's pronunciation overrides.
