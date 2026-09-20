# Latest reader diagnostics, September 20, 2026

The four batches in [diagnostic history issue #1](https://github.com/sainadh812/ReadFlow/issues/1) contain **36 unique events: 21 `ALIGN_WORDS` and 15 `NORMALIZE_TEXT`**. The two uploads after the [0.1.7 investigation](READER_RECOVERY_FIX.md) add **12 events: 5 alignment failures and 7 normalization failures**.

| Verified batch | Unique events | Alignment | Normalization |
|---|---:|---:|---:|
| [First retained history](https://github.com/sainadh812/ReadFlow/issues/1#issuecomment-5748640376) | 20 | 12 | 8 |
| [Second upload](https://github.com/sainadh812/ReadFlow/issues/1#issuecomment-5748696724) | 4 | 4 | 0 |
| [New third upload](https://github.com/sainadh812/ReadFlow/issues/1#issuecomment-5749682694) | 1 | 1 | 0 |
| [New fourth upload](https://github.com/sainadh812/ReadFlow/issues/1#issuecomment-5749876089) | 11 | 4 | 7 |
| **Total** | **36** | **21** | **15** |

The existing `parse_comments` function in [read_github_diagnostics.py](../scripts/read_github_diagnostics.py) verified each JSONL payload against its SHA-256 batch marker and deduplicated by `eventIds`. There are 21 grouped JSONL records; record count and the sum of repeatedly visited `occurrences` are not event counts. Uploaded content was parsed only as data. Both diagnostic-reader unit tests passed.

## What the new evidence establishes

- All 12 new events report **0.1.6-prototype**, version code **7**, normalization **`english-3`**, Samsung **SM-S938B**, Android **16/API 36**, Kokoro **v0.19**, voice **0**, speed **1.0**. Their recorded event times range from **12:04:50 to 12:44:00 UTC** on September 20, 2026. These are event times, not upload times.
- The five alignment events are `AlignmentConfidenceException` failures at the unchanged **0.12** threshold. One reports token index **27**, confidence **0.02401672**; four grouped occurrences report token index **17**, confidence **0.04976267**. Generated audio metadata is present at **24,000 Hz**, but the actual audio is absent.
- All seven normalization events identify `SpeechPreparationException` caused by `NormalizationException`. Unlike the first two uploads, the latest upload therefore confirms normalization failures on **`english-3`**. Across all four batches, 0.1.4 / `english-2` accounts for 12 alignment and 8 normalization events; 0.1.6 / `english-3` accounts for 9 alignment and 7 normalization events.
- Every event has **`inputIncluded: false`**. The reports omit source text, failed token text, normalized expansion, detailed failure message and audio. They do not identify the unsupported character or pronunciation and cannot distinguish a newly unsupported input from an intended rejection. No original document text is reproduced here.

## Code review and limits

The current [playback recovery](../app/src/main/java/app/readflow/core/PlaybackAlignment.kt) catches only the measured confidence rejection, keeps generated audio playable with empty word timings, and preserves the confidence threshold. The coordinator records the warning and commits that audio instead of deleting it. Existing tests cover that recovery, real rejected alignment data, selection from the requested spoken word, restoration of highlighting, and propagation of cancellation and unrelated inference failures. This addresses the fatal-alignment behavior present in the reported **0.1.6** build; these uploads do not test the later implementation.

The current normalizer already contains the earlier source-backed OCR fixes. The seven new normalization reports omit the input needed to justify another pronunciation rule, so this review makes no speculative normalization change. Normalization remains an unresolved input-specific issue; local detailed reports would be needed to reproduce it. Existing failed-sentence reporting and skip behavior remain available.

No uploaded event is from **0.1.7 or later**, and no event measures screen brightness, idle dimming or viewport size. These logs cannot verify a later update, prove the reason for the display symptom, or establish phone playback and highlighting accuracy. Reader layout and keep-awake behavior require separate validation.
