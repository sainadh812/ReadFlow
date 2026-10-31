# Play model distribution

The `release` source set replaces `model-manifests.json` with a Kokoro + alignment catalog. The prototype's archive, Pocket voice and eSpeak data are not offered by this catalog. The release speech implementation uses Misaki and direct ONNX inference; removing a menu option alone would not remove a native dependency. Audit the final release AAB and dependency graph separately to confirm the Sherpa AAR and its eSpeak binary are absent.

## Exact downloadable files

The Kokoro export is pinned to [csukuangfj/kokoro-en-v0_19 revision `92805c485745946a0d945562d3aba19e7cbb2104`](https://huggingface.co/csukuangfj/kokoro-en-v0_19/tree/92805c485745946a0d945562d3aba19e7cbb2104). Four direct files total **351,323,831 bytes**. They have the same byte counts and SHA-256 values as the corresponding files already tested in the prototype archive.

| File | Bytes | SHA-256 |
|---|---:|---|
| `model.onnx` | 345,555,491 | `10ff414106a038ce7e9e0126c6461e4dc8a86efaa89dc91d2009d69fe635e339` |
| `voices.bin` | 5,755,904 | `a372c67b056ef0b695c375d39b99630d23fb07ad4c8d87aa32a19a62fca523ad` |
| `tokens.txt` | 1,078 | `4f31c71282d14af4e926cd12462078fe9d20d00c589e63fe2750a8f56d6d7f7b` |
| `LICENSE` | 11,358 | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |

The release does not download the original tar archive, whose contents include eSpeak language data. Every URL uses the immutable revision, not `main`. The exact historical revision remains necessary even if upstream changes or removes files on its current branch.

Alignment remains the [ONNX Community quantized Wav2Vec2 export at `729c1a6730fb549c20a1c73a3d3f96f11020225e`](https://huggingface.co/onnx-community/wav2vec2-base-960h-ONNX/tree/729c1a6730fb549c20a1c73a3d3f96f11020225e), totaling **95,215,546 bytes** including configuration. Its inference runtime label now matches the app's ONNX Runtime 1.22.0.

## Existing installations

An old verification marker is insufficient to reuse a file. `ModelStore.verifyInstalled` and explicit setup verify every file against the new manifest's size and SHA-256, then copies only that manifest's allowlist into a fresh staging directory. Old eSpeak files are not copied. From 0.2.1, playback, prepare-ahead and speech benchmarks automatically perform this local migration when the stored manifest identity changes. No Download tap is needed for compatible files. Verification never starts a network download: if required files are missing or corrupt, it preserves the previous installation and directs the user to Voice models. Explicit setup can then use the normal checked download path.

The completed staging directory is verified before activation. Replacement renames the old installation to a temporary backup, promotes staging within the same filesystem, then removes the backup. If promotion fails, the old directory is restored. Startup restores a backup left by process death before promotion and removes a leftover backup after successful promotion. Cancellation during checking/copying preserves the prior installation. This does not delete unrelated documents or other model directories.

Tests cover direct primary-plus-supporting-file installation, changed-manifest migration, same-size corruption, cancellation/retry, concurrent verification, interrupted replacement recovery, release manifest contents, and offline installation of all eight real speech/alignment files when the pinned `.deps` files are available. A release regression seeds the actual prototype manifests and all their pinned files, then invokes only playback verification; it checks automatic migration of both packs, release allowlists and fresh-process verification. Host tests do not establish Android download reliability or on-device performance.

## License evidence and audit limits

The pinned Kokoro export includes an [Apache-2.0 license](https://huggingface.co/csukuangfj/kokoro-en-v0_19/blob/92805c485745946a0d945562d3aba19e7cbb2104/LICENSE). The [alignment export's card](https://huggingface.co/onnx-community/wav2vec2-base-960h-ONNX/blob/729c1a6730fb549c20a1c73a3d3f96f11020225e/README.md) identifies the Facebook checkpoint; the [Facebook model card](https://huggingface.co/facebook/wav2vec2-base-960h/blob/22aad52d435eb6dbaf354bdad9b0da84ce7d6156/README.md) declares Apache-2.0. Release assets include the license text, attribution and provenance under `licenses/models/`. Misaki and native/runtime notices remain separately bundled under `licenses/`.

Network verification on 2026-10-26 confirmed HTTP 200 and matching lengths for all four immutable Kokoro URLs. Downloaded `tokens.txt` and `LICENSE` bodies matched their SHA-256 locks. The immutable repository tree's LFS SHA-256 identifiers for model and voices matched the manifest and independently hashed local archive copies; the large network blobs were not fully downloaded again during this audit. Alignment source cards were checked online, and all local alignment files matched their manifest hashes. A host download check does not prove Play-device networking, future upstream availability or distribution rights beyond upstream's license declarations.

This is a distribution inventory, **not blanket license approval**. Before publishing, re-audit the final AAB, downloaded model/voice files, source and transitive notices, proprietary ML Kit terms and disclosures, privacy policy and store requirements. Verify voice and training-data rights as appropriate for the intended commercial use. The debug/prototype build continues to carry Sherpa/eSpeak obligations described in [the general notices](THIRD_PARTY_NOTICES.md); those historical obligations are not erased by this release variant. Optional PaddleOCR and TeleOCR retain their existing pinned manifests and license obligations.
