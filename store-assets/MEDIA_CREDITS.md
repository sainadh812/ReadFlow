# Read Assist media credits

These credits accompany Read Assist screenshots, the product demonstration and the separate foreground-service recording. A rendered video's `video-build-report.json` identifies its exact capture inputs, hashes, edit decisions, audio offsets and verification results. The raw recordings are retained separately.

## What the recordings show

The app footage is captured from the actual Read Assist Android interface on an isolated emulator. Its emulator ABI/signing and capture source are recorded in the provenance report; this is not represented as a physical-device or Play-delivered ARM64 recording. Product-video titles and backgrounds surround the complete app frame. Retained footage plays at its original speed; editorial cuts omit time between scenes. Models and sample audio may already be prepared. No loading-time or benchmark claim is made.

The foreground-service clip keeps the complete original video stream. When synchronized Android audio and video are captured together with scrcpy, the foreground-service file preserves the original MP4 bytes, both encoded streams and their device timestamps. The report identifies the recorder, version and live audio source. Earlier recordings may use a separate real host audio capture; for those, the build records the approximate host start offset and any explicitly reviewed synchronization adjustment. No replacement soundtrack, simulated narration, stock music, synthetic controls or artificial word highlights are added. A signal-level audio check is distinct from playback and synchronization review.

## Original documents and graphics

The original reading sample **A quiet space for thought**, project-created sample PDFs and other generated demonstration fixtures are offered under **CC0 1.0**. They contain no user documents. Source: `app/src/main/assets/fixtures/reading.txt`, `app/src/storeDemoAndroidTest/java/app/readflow/StoreDemoSeedTest.kt`, and project fixture scripts. The Read Assist wordmark, editorial layout and simple book motif were created for this project. No third-party stock imagery or music is used.

## Synthesized narration

Narration, when present, is the actual app's synthesized output from its English **Kokoro-82M v0.19** voice model and **Misaki** pronunciation frontend. It is not a recording of an independently hired narrator or a claim that a human performer endorsed Read Assist.

- Kokoro model: Hexgrad; ONNX export by csukuangfj / k2-fsa contributors. The pinned export declares Apache License 2.0: [Kokoro v0.19 export](https://huggingface.co/csukuangfj/kokoro-en-v0_19/tree/92805c485745946a0d945562d3aba19e7cbb2104).
- Misaki frontend: Hexgrad and contributors, Apache License 2.0, with its incorporated dictionary/POS/runtime notices retained separately. [Pinned Misaki source](https://github.com/hexgrad/misaki/tree/fba1236595f2d2bf21d414ba6e57d25256afada3).
- Complete runtime/model/source notices are available in the app under **Settings → Privacy and licenses** and in the repository's `app/src/main/assets/licenses/` and `app/src/release/assets/licenses/models/` directories.

## Dictionary definitions

Dictionary text visible in the app is adapted from **English Wiktionary contributors**, extracted by **Kaikki / Wiktextract, Tatu Ylonen and contributors**, from the 1 October 2026 dump. Read Assist filters English entries, selects definitions and identified authored usage examples, normalizes lookup keys, resolves word forms and converts the data to SQLite. Quotations, unclassified examples and media are excluded.

The adapted dictionary text is **CC BY-SA 4.0**, separately from application code and the rest of the video. The displayed Wiktionary source link identifies the entry; its History tab lists contributors. Preserve this attribution, the license and the indication of changes when redistributing dictionary text. The application's dictionary sheet also exposes its source and license links.

[English Wiktionary](https://en.wiktionary.org/) · [Kaikki extraction](https://kaikki.org/dictionary/rawdata.html) · [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) · [Full project attribution](https://github.com/sainadh812/ReadFlow/blob/fix/reader-playback-and-pdf-space/docs/dictionary/ATTRIBUTION.txt).

## Fonts

The product-video titles use **Source Serif 4** by Adobe; supporting text uses **Atkinson Hyperlegible Next** by the Braille Institute and its font contributors. Both are used from the project's unmodified, pinned **SIL Open Font License 1.1** files. The app may additionally show OpenDyslexic, Lexend, Literata and Noto Sans, also with preserved OFL notices. Exact upstream authorship, revisions and full notices are in `app/src/main/assets/licenses/fonts/` and its `provenance.json`.

Font and model notices do not imply endorsement by their contributors. No claim of medical benefit, guaranteed recognition accuracy or universal device support is made in these materials.
