# Product pitch voice credits

The narration is AI-generated speech using **Kokoro
v1.0**, the **af_heart** voice, and the existing Misaki English frontend.
It is an editorial voiceover, separate from the app speech heard in the continuous recording.
The script was written for Read Assist. No human performer recording or
endorsement is implied.

## Model and voice sources

- Original model and voice weights: [hexgrad/Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M/tree/f3ff3571791e39611d31c381e3a41a3af07b4987),
  licensed Apache-2.0 according to the [pinned model card](https://huggingface.co/hexgrad/Kokoro-82M/blob/f3ff3571791e39611d31c381e3a41a3af07b4987/README.md).
  The [voice listing](https://huggingface.co/hexgrad/Kokoro-82M/blob/f3ff3571791e39611d31c381e3a41a3af07b4987/VOICES.md)
  identifies `af_heart` as an American English voice. These are upstream license
  and provenance declarations, not an independent audit of all training rights.
- Full FP32 ONNX model and NumPy voice bank:
  [thewh1teagle/kokoro-onnx, model-files-v1.0](https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0).
  The [exporter wrapper](https://github.com/thewh1teagle/kokoro-onnx/tree/3596b26764286a7de9d90c363e988d50578918e5)
  uses the [MIT license](https://github.com/thewh1teagle/kokoro-onnx/blob/3596b26764286a7de9d90c363e988d50578918e5/LICENSE).
  The media generator uses the documented token/style/speed inputs; it does not
  install or execute the downloaded wrapper.
- Original Kokoro implementation: [hexgrad/kokoro](https://github.com/hexgrad/kokoro/tree/dfb907a02bba8152ca444717ca5d78747ccb4bec),
  [Apache-2.0](https://github.com/hexgrad/kokoro/blob/dfb907a02bba8152ca444717ca5d78747ccb4bec/LICENSE).
- Frontend: the repository's pinned native Misaki / go-g2p implementation. Its
  component notices and precise revisions remain in
  [the existing Misaki notices](https://github.com/sainadh812/ReadFlow/blob/fix/reader-playback-and-pdf-space/app/src/main/assets/licenses/misaki/readflow-provenance.json)
  and [third-party notices](https://github.com/sainadh812/ReadFlow/blob/fix/reader-playback-and-pdf-space/docs/THIRD_PARTY_NOTICES.md).

## Download verification

The GitHub release API provided matching asset sizes but **no upstream SHA-256
digest**. The following hashes were calculated from the downloaded primary-source
files on 2026-11-16 and are pinned in `scripts/synthesize_product_pitch.py` for
subsequent verification. They are not represented as independently published
upstream checksums.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `kokoro-v1.0.onnx` | 325,532,387 | `7d5df8ecf7d4b1878015a32686053fd0eebe2bc377234608764cc0ef3636a6c5` |
| `voices-v1.0.bin` | 28,214,398 | `bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d` |
| Original model `config.json` | — | `5abb01e2403b072bf03d04fde160443e209d7a0dad49a423be15196b9b43c17f` |

The voice bank is a NumPy ZIP archive and is loaded with `allow_pickle=False`.
No arbitrary pickle or performer-uploaded reference recording is used.

## Production treatment

The four connected paragraphs use native synthesis speed `0.85`. Internal pauses
are retained; only outside silence is trimmed, with 0.15 seconds of silence
placed at each paragraph edge. Audio is resampled from 24 kHz to 48 kHz without
changing playback speed. Two-pass FFmpeg loudness normalization targets −18 LUFS
with a −1.5 dBTP true-peak ceiling. This processing uses dynamic gain and
true-peak limiting, preserves the exact sample count, and applies only to the
synthetic narration. There is no time stretching or speech truncation; original
app recordings are unchanged.

Generated WAVs, exact timings, checksums, loudness measurements, and automatic
CTC transcripts and approximate word/sentence anchors are recorded in the build's
synthesis report. Automated transcription is not a human listening or
pronunciation review. This media-only
voice upgrade does not change the app's bundled or downloaded speech models.
The published MP4 preserves the approved editing preview's encoded narration;
the MP3 is an additional export of the same soundtrack.
