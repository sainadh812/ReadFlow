# Number reading and PDF navigation — 0.1.9

The reported stops occurred during speech normalization, after text extraction. `english-5` recognizes additional numeric forms without changing the displayed document, OCR geometry or source IDs. Existing audio caches regenerate under the new normalization revision; no model download or document reimport is required.

| Source | Speech text |
| --- | --- |
| `inflation,16` | `inflation, sixteen` |
| `$1 million` | `one million dollars` |
| `$9.3 million` | `nine point three million dollars` |
| `he is 20-24 years old` | `he is twenty to twenty four years old` |
| `1999-Apple's iMac with 6 gigs` | `nineteen ninety nine: Apple's iMac with six gigs` |
| `2003-120 gigs` | `two thousand three: one hundred twenty gigs` |
| `2006-250 gigs` | `two thousand six: two hundred fifty gigs` |

Attached reference-like numerals are spoken literally; the app does not infer or remove footnotes. Compact ascending numeric pairs are read as ranges. Descending pairs without the recognized storage context retain a literal “dash.” A year attached to a capitalized name, or to a short storage value followed by a storage unit, gets a pause. This is deliberately bounded grammar, not a general date/equation interpreter. Existing signed numbers, decimals, thousands separators, identifiers and hyphenated compounds retain their behavior. Unsupported expressions still produce an explicit error. Curly/nonbreaking dashes and the modifier-letter apostrophe are supported.

Currency magnitudes stay together through selection and chunking, and both source words share the phrase's validated highlight interval. Selecting either part reads the whole currency phrase. Year/storage pairs also stay together for chunking, but the storage unit retains its own highlight and selection boundary. Synthesis and alignment always receive the same normalized transcript.

## PDF viewer

The original page is fitted to the viewport with a neutral background. At normal zoom, horizontal movement is locked and vertical scrolling stops at the page edges. Short pages remain centered. Pinch zoom is limited to 1–6× and panning stays within the enlarged page. Follow-reading movement is bounded by the same geometry, including rotated pages and resized windows.

At normal zoom, a deliberate one-finger horizontal swipe turns one page on release: **right → next, left → previous**. Taps, short/diagonal movements, vertical scrolling, multi-touch and zoomed panning do not turn pages. There is no wraparound at the first/last page. Page changes use the existing navigation path, which pauses playback and cancels outdated work. Previous/next buttons are also beside the page counter. Swipes work in page-only mode.

**Settings** persists swipe enablement and direction; the reader menu also toggles swipes. Reflowed text retains its normal vertical scrolling.

## Verification and limitations

`NumericSpeechTest` checks the reported text, punctuation variants, currency grammar, source ownership, cache invalidation, chunk limits, selection and rejected ambiguous expressions. `PageViewportTest` checks fit, bounds, zoom anchoring, resize/rotation geometry and swipe eligibility/direction. These geometry tests do not simulate Android touch dispatch.

The original combined numeric fixture under `docs/evidence/numeric-speech` was synthesized by both local engines and aligned using production Kotlin. Kokoro passes the source mapping check. Pocket's recorded fixture fails the existing 0.12 confidence gate on `FIFTY` (0.00715). Regression tests preserve this rejection and check the existing playback fallback: audio remains available, with no invented word timings. This does not establish that Pocket pronounced every number correctly. No confidence threshold was reduced. Audio boundaries and pronunciation have not been manually audited; the host fixture is not a phone performance benchmark.

Build/test results and APK identity are recorded in `docs/evidence/numeric-speech/build-results.json`. Physical S25 Ultra gesture behavior, UI layout, runtime instrumentation and listening still require the checks in [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).
