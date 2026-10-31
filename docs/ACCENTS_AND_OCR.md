# ReadFlow 0.1.8: accented speech and OCR refinement

The English speech normalizer previously rejected `ć` and `ö`, even when OCR recognized the names correctly. This update preserves document spelling, offsets, word IDs and geometry while producing an English speech form that both synthesis and word alignment can accept.

## Speech

- Whole-name `Milanković` and `Milankovic` use the English spelling `Milankovitch`, with case, punctuation and possessives retained. This also works with canonically decomposed accents and names joined across a line break.
- Other supported Latin accents use an English spelling approximation, including `Köppen` → `Koppen`, ligatures and common nondecomposing Latin letters. This is not a guarantee of native pronunciation for every name.
- The same speech text feeds synthesis and alignment. Original display text, source ownership and selection geometry are unchanged. Other scripts, equations and unsupported/orphan combining marks still report unsupported text rather than disappearing.
- Normalizer revision `english-4` regenerates speech cache entries automatically. Existing documents and downloaded model packs are retained.

The bundled eSpeak frontend produces a hard `k` ending for `Milankovic’s`, but a `ch` ending for `Milankovitch’s`. The latter is the spelling used by [NASA](https://science.nasa.gov/science-research/earth-science/milankovitch-orbital-cycles-and-their-role-in-earths-climate/). `Koppen` produces the American English KOP-en pronunciation listed by [Dictionary.com](https://www.dictionary.com/browse/koppen-climate-classification). Actual frontend output is retained in [espeak-phonemes.json](evidence/accented-speech/espeak-phonemes.json).

## OCR

- Retains bundled ML Kit 16.0.1. PDF rendering is up to 300 DPI with a 10-million-pixel cap; image decoding retains up to 10 million source pixels and does not enlarge the full photo. Very high resolution camera photos are still bounded.
- A consistent slope across at least three suitable lines triggers a trial correction of 0.8–12 degrees. It is adopted only when recognition/coverage checks pass. Rotation uses white padding and reversible coordinates.
- Up to six low-confidence, small or suspicious lines receive focused crop retries. Crops have padding for accents, optional local straightening and at most 2× enlargement, capped at two million pixels. Enlarging a crop cannot recover detail absent from the source.
- A replacement must improve confidence and retain overlapping word coverage; a high-confidence existing word cannot be rewritten just to improve the average. The entire corresponding line is replaced, never appended. Neighboring lines seen in an expanded crop do not become duplicates.
- Confidence thresholds are conservative engineering heuristics, not measured guarantees. Confidently wrong text may remain. The implementation does not guess words from a dictionary or rewrite recognized names.
- Source image dimensions and complete affine transforms retain highlight coordinates after decode scaling, crop retries and rotation. Temporary bitmaps are released after ML Kit completes, including cancelled work.
- Reading order uses upright processing coordinates, including the full page width for column detection; highlights retain the original source coordinates. This also fixes line/word order after manual quarter-turn rotation.

Google's guidance emphasizes sufficient character pixels and image quality; a larger image alone does not guarantee better OCR. See [ML Kit input guidance](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) and [element confidence/geometry](https://developers.google.com/android/reference/com/google/mlkit/vision/text/Text.Element).

## Existing pages

New extractions use the improved pipeline. Cached page text is not silently replaced: open the page and choose **Reader menu > Retry text recognition**. This preserves its rotation. The separate rotate action remains available. Reprocessing can change word IDs when recognized text changes; a stale bookmark opens its page for word reselection. Audio cache keys already include speech and source IDs, so a retry does not delete unrelated cached audio. A completed retry does not pull the reader away from a newer navigation/playback request.

## Validation

Ten accented-speech regression tests cover the photographed paragraph, unchanged source mappings, canonical Unicode forms, punctuation, numeric expansions, unsupported scripts and old-cache invalidation. Fifteen OCR policy/geometry tests cover bounded memory dimensions, confidence handling, crop acceptance, lost/duplicate words and reversible coordinate transforms. Six reading-order regressions cover quarter-turns, scaling, sparse columns, identity transforms and duplicate occurrences without changing source geometry.

The original short fixture `Milanković’s theory informed Köppen’s work.` was synthesized using both actual host engines. `RealAudioTest` checks that production normalization exactly matches the synthesized transcript and that production CTC alignment maps all five original source words in order. Audio, model emissions, run metadata and mappings are retained under [evidence/accented-speech](evidence/accented-speech). This verifies host synthesis/alignment behavior; it is not human listening validation or a phone timing measurement.

To regenerate the audio fixtures with the existing downloaded models:

```bash
.venv/bin/python scripts/prototype.py kokoro --text "Milankovitch's theory informed Koppen's work." --output docs/evidence/accented-speech
.venv/bin/python scripts/prototype.py pocket --text "Milankovitch's theory informed Koppen's work." --output docs/evidence/accented-speech
./gradlew testDebugUnitTest --tests app.readflow.core.RealAudioTest --offline --console=plain
```

Android instrumentation includes generated skewed and large-image fixtures. No device is attached, so actual ML Kit execution, S25 Ultra accuracy/speed/memory, audio listening and UI behavior remain unverified on the phone. The supplied photo has not been run through ML Kit here.

Final verification:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --offline --console=plain
```

- **BUILD SUCCESSFUL in 2m 34s**, after the final playback/retry guard. **143 JVM tests passed**, zero failures, errors or skips. Lint: zero errors, three warnings. App and instrumentation APKs compiled.
- Both retained accented-name audio fixtures passed production normalization/alignment: minimum token confidence 0.543 for Kokoro and 0.674 for Pocket. These are alignment checks, not pronunciation accuracy scores.
- Local APK: `dist/ReadFlow-0.1.8-arm64-debug.apk`, Android 15+ / ARM64, version code 9, 130,964,492 bytes. SHA-256: `f2d30d82cc9ee1aa2e400fb1f5ca7d3fae23f1dd59079cb27d7df60510ba81c8`.
- APK signature verified and matches the previous build, permitting an in-place update without uninstalling. No new model download is needed. No release was published.
- Machine-readable results: [build-results.json](evidence/accented-speech/build-results.json).
