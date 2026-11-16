# Read Assist 0.3.2 — explanatory narration

The narrated product walkthrough adds an English explanatory voiceover to the previously reviewed 74-second product demo. The original encoded video is copied without changing its frames, timing or speed. The original product video and the separate continuous background-playback demonstration remain available unchanged.

The explanatory voice is generated locally in post-production with **Kokoro-82M v0.19**, its default English voice, and the **Misaki** pronunciation frontend. It is separate from the speech produced by the recorded Android app. This is a generic synthetic voice, with no claim of endorsement by a human narrator.

Each explanation is timed to its corresponding scene. Two complete sentences from the app's original recording play between the explanations, at approximately 57.5–64.15 seconds. The original speech is muted elsewhere in this narrated edition to avoid competing voices. Audio levels and short fades make the transitions easier to hear. No music is added. The downloadable MP3 contains this complete mixed soundtrack; the SRT and WebVTT files contain timed English captions.

The narration script, synthesis and render reports record the exact text, input and output hashes, speech durations, audio processing and verification. Automatic speech recognition assists content checks; it does not establish a subjective human listening review. This narrated edition is a product explanation, not the continuous foreground-service evidence video.

The footage uses actual Android emulator recordings, original project sample documents and the existing editorial layout. See the [original media credits](https://sainadh812.github.io/ReadFlow/MEDIA_CREDITS.md) for the source recordings, CC0 sample documents, Wiktionary/Kaikki dictionary attribution, and font notices.

- [Kokoro v0.19 model/export](https://huggingface.co/csukuangfj/kokoro-en-v0_19/tree/92805c485745946a0d945562d3aba19e7cbb2104): Hexgrad; ONNX export by csukuangfj / k2-fsa contributors, with Apache License 2.0 notices retained by the project.
- [Pinned Misaki source](https://github.com/hexgrad/misaki/tree/fba1236595f2d2bf21d414ba6e57d25256afada3): Hexgrad and contributors; Apache License 2.0, with incorporated dictionary, POS and runtime notices retained separately.
- The displayed adapted dictionary content remains CC BY-SA 4.0, separately from the application and other video content. Its source attribution is retained in the original credits and app interface.

The generated voiceover does not change the app, its release status, or its supported devices.
