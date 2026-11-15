# Library themes — Read Assist 0.3.0

The approved main-screen redesign includes three native Compose layouts. Warm Library is the default for new installs and upgrades without a saved app-theme choice. Settings → Appearance → App theme applies and saves a selection immediately.

| Theme | Library layout | Appearance |
| --- | --- | --- |
| A · Warm Library | Large Continue card and document grid | Ivory, forest green and serif headings |
| B · Modern Focus | Compact resume card and document rows | Bright surfaces, navy text and cobalt actions |
| C · Night Reader | Listening card with Listen and Open page, plus document shelf | Charcoal, olive and sage |

The app-theme choice replaces the previous app dark-mode switch. System dark mode does not override it. Reader paper, typeface, brightness and warm light remain separate settings. Changing the app theme does not stop narration or model downloads.

Library, Voices and Settings share bottom navigation. Back returns through the screens actually visited. The Add sheet imports a PDF/image or webpage; the empty library offers the existing sample document. Search matches local document titles, filters select All/PDFs/Articles/Images, and sorting offers Newest or Title. Search/filter/sort remain in the view model while changing screens or themes. Removal is in each document's overflow menu and still asks for confirmation; the original source file is preserved.

PDF/image covers are first-page previews rendered locally in the background with a bounded cache. Articles use typographic covers. Covers are not retrieved from online bookstores. Page labels describe saved location, not reading completion.

Manual page history is stored separately from narration's page/word bookmark. The Room database migration adds this history without replacing existing documents or narration positions. Continue/Open page restores the last viewed document page without starting audio; a current session is opened without resetting playback. Listen is an explicit narration action. Existing narration bookmarks provide an upgrade fallback until a document has manual history. Long text pages restore the document page rather than an exact scroll offset.

The approved medium playback dock, dictionary, page turns, reading light and red speech-failure marking remain available in the reader.

## Validation

Released as [Read Assist 0.3.0](https://github.com/sainadh812/ReadFlow/releases/tag/v0.3.0-play-candidate), version code 26, from commit `f40664444ccc8ec4e691188dd3e0eaa94b77ebd5`. [Download the signed APK](https://github.com/sainadh812/ReadFlow/releases/download/v0.3.0-play-candidate/Read-Assist-0.3.0-upload-signed.apk) for Android 15+ ARM64.

- Local release build: **460 tests passed**, no skips. [GitHub CI run 43724779348](https://github.com/sainadh812/ReadFlow/actions/runs/43724779348): **457 passed, 3 model-fixture tests skipped**, no failures. Lint: **0 errors, 38 warnings**. See [local](evidence/reader-0.3.0/local-validation.json) and [CI evidence](evidence/reader-0.3.0/release-evidence.json).
- **9 host SQLite checks passed**, using the production migration and history SQL, including preserved narration positions, durable manual history and matching Room schema v2. These are host checks, not Android instrumentation. See [migration evidence](evidence/reader-0.3.0/manual-reading-history-verification.json).
- All **18 checked theme text/background pairs** have contrast of at least **4.97:1**. See [contrast results](evidence/reader-0.3.0/theme-contrast.json).
- APK/AAB signatures match the approved upload certificate; APK 16 KB alignment and bundle validation passed. Dictionary, fonts and license assets were retained. The public APK download matched its published hash. See [APK](evidence/reader-0.3.0/apk-verification.json), [bundle](evidence/reader-0.3.0/aab-verification.json) and [download evidence](evidence/reader-0.3.0/github-release.json).

No physical-device validation was performed. Phone installation/upgrade, visual layout, TalkBack and runtime playback remain unverified; no connected Android device or working hardware-accelerated emulator was available.
