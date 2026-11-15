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

Release checks and signed-artifact evidence will be recorded here after the build. No connected Android device or hardware-accelerated emulator is available in this workspace; on-device layout, TalkBack and playback checks are not yet verified.
