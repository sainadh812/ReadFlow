# Read Assist 0.3.2 — Play Console handoff

**Historical version-specific record. Updated navigation, 21 November 2026:** Use the [current Play handoff](PLAY_STORE_HANDOFF.md) for 0.3.4/code 30 and its compact Tt controls. Code 30 is not approved on alpha; Internal remains published. The owner-provided notice identifies Play Console Requirements → Developer Account: the category or declared features require an organization account. The exact saved selection still needs inspection; see the current handoff for resolution. The dated artifact facts, instructions and states below describe their original release and must not be used to select an older bundle for resubmission.

**Listening demonstration update — 16 November 2026:** The [56.53-second product film](https://sainadh812.github.io/ReadFlow/demo.html#watch) now includes two complete sentences spoken by the app, with its actual word highlights enlarged at **14.60–22.43 seconds**. The pitch narrator pauses for the sample. Original app audio/video timing is preserved; the recorded highlighting includes brief gaps and does not establish perfect alignment. The narration and picture outside the insertion are unchanged. Public MP4, MP3, English captions, poster and native-source archive are verified on GitHub; the film and companion files are also verified on the demo site. Browser playback, all 19 captions, caption placement above the reading area, and desktop/mobile layouts passed. The new recording reuses the verified emulator APK and is marked partial because speech ended before a later notification Pause check. The selected reading passage is valid; the earlier successful continuous playback proof remains unchanged. This media update does not change the app or Google Play release. [Build report](evidence/reader-0.3.2/listening-pitch/build-report.json), [independent review](evidence/reader-0.3.2/listening-pitch/independent-review.json), [public downloads](evidence/reader-0.3.2/listening-pitch/publication-verification.json), [browser verification](evidence/reader-0.3.2/listening-pitch/browser-verification.json).

**Update — 16 November 2026, 02:47 UTC:** A [fresh read-only Play inspection](evidence/reader-0.3.2/store-inspection-43794197823.json) confirms all eight prepared screenshots, the icon and feature graphic are now present in the saved en-US listing, with matching hashes. Their save occurred outside the failed automated attempts recorded below; its author/time is not established. Preserve these images and their current ordering rather than re-uploading them. The earlier listing copy remains, no YouTube preview URL is configured, Internal code 28 remains `PUBLISHED`, and Production has no release. No new write or validation retry was performed. Saved images do not establish public-store availability or resolution of the earlier validation denial. This update supersedes the older statements below that the images remain unsaved on Play.

Prepared 15 November 2026 for the authorized production release of **Read Assist**, package `app.readflow`, version **0.3.2 / code 28**. Use the [existing Console app](https://play.google.com/console/u/0/developers/9173169759090733636/app/4975999632955979701); preserve its signing setup, free pricing and existing tester lists. This handoff supplies answers and evidence; it does not establish that declarations are saved or production is approved.

## Verified work and current boundary

- The [GitHub candidate](https://github.com/sainadh812/ReadFlow/releases/tag/v0.3.2-play-candidate) is public. The signed AAB is **72,436,186 bytes**, SHA-256 `809f7fc41c0662c085abc313dc88269e4be1c1c416ff983405bcbc670461f12b`. Reuse this bundle; do not rebuild or upload code 28 again.
- [Internal testing publication](evidence/reader-0.3.2/play-release-summary.json) is confirmed: code 28, completed track, lifecycle `PUBLISHED`. Other tracks and tester membership were unchanged. Production was empty in that snapshot.
- Final [store attempt 43760801498](evidence/reader-0.3.2/store-publication-attempt-43760801498.json), revision `60643bc`, staged the en-US listing and all ten images: icon, feature graphic and **eight finished screenshots**. Readback verified them (`pendingSnapshotVerified=true`), but `POST /androidpublisher/v3/applications/app.readflow/edits/{editId}:validate` again returned **403 `PERMISSION_DENIED`**, with no additional Google reason. No commit was attempted; the owned edit was deleted. The listing/images remain unpublished **on Play**. The already-requested check concerns saved access for `play-publisher@read-assist-fbd21.iam.gserviceaccount.com` and pending account notices; the cause is unestablished. Production is blocked and has **not been attempted**. No new key or broader permission request is introduced here.
- The [graphics ZIP](https://github.com/sainadh812/ReadFlow/releases/download/v0.3.2-play-candidate/Read-Assist-0.3.2-store-media.zip) is publicly uploaded and verified: eight screenshots, icon, feature graphic, original captures, listing, provenance, credits and three sample PDFs. Its [download evidence](evidence/reader-0.3.2/store-images-publication.json) records HTTP 200 and the exact size/hash. Videos are distributed separately.
- The Android dictionary regression passed. Signed-build verification recorded 462 tests, zero failures/errors, three skips and zero lint errors. See [current readiness and evidence](PRODUCTION_READINESS_0.3.2.md) for the precise device/build limits. These results do not establish saved policy answers, production eligibility or physical-device playback.
- The [live demo page](https://sainadh812.github.io/ReadFlow/demo.html) now hosts the **74-second product walkthrough** and **33-second continuous background-playback demonstration**. Both MP4s are also attached to the GitHub release. Complete public downloads, hashes, video MIME/range requests and actual browser playback passed; desktop/mobile layouts had no horizontal overflow. [Media delivery](evidence/reader-0.3.2/media-site-verification.json), [browser verification](evidence/reader-0.3.2/media-browser-verification.json).
- The playback recording from `43761868949` shows **Play → Home → continuing narration → notification Pause**. Actual app-scoped states confirm PLAYING after Home, PAUSED after Pause, and a foreground `mediaPlayback` service. The viewing copy preserves the complete encoded video and raises the genuine recorded audio by a disclosed constant **26 dB**. Timing/content checks and full-channel clipping checks passed. The [source ZIP](https://github.com/sainadh812/ReadFlow/releases/download/v0.3.2-play-candidate/Read-Assist-0.3.2-capture-sources.zip) retains the unchanged original MP4, other selected captures, recipe, credits and review reports. [Playback review](evidence/reader-0.3.2/demo-video/playback-review.json), [render evidence](evidence/reader-0.3.2/demo-video/video-build-report.json), [source download verification](evidence/reader-0.3.2/capture-sources-publication.json). This is an Android emulator demonstration; no subjective human listening or Play-installed ARM64 verification is claimed.

## Enter or preserve these supported answers

In **Read Assist → App content**, inspect **Needs attention** and the existing declarations first. Preserve accurate saved answers.

| Field | Ready-to-use answer |
| --- | --- |
| Privacy policy | `https://sainadh812.github.io/ReadFlow/privacy.html` |
| Public support/privacy contact | `xperimenting.ai@gmail.com` |
| Ads | **No** — no ads or advertising SDK in the reviewed release. |
| App access | **No access restrictions requiring authentication.** No login, invitation, subscription, purchase or special credentials are required. |
| Advertising ID, if asked | **No use** in the reviewed release source/runtime inventory. ML Kit installation identifiers are a separate Data safety disclosure. |
| Health features | **None** — this is a document reader, with no health tracking, diagnosis, treatment or Health Connect integration. |
| Foreground service | **Media Playback / `mediaPlayback`**. Use the text and video below. |
| Store copy | The approved [en-US listing](../store-assets/listing/en-US.json) is prepared for the store publisher. Coordinate with its retry to avoid competing edits. |

**Reviewer instructions:** Open the Library and choose **Try a sample**. For narration, open **Voices**, download the offered **Kokoro** voice and **alignment** packs, wait for installation, then return to the document and press **Play**. Import a multi-page PDF to inspect the bottom **Text / PDF** switch and right-to-left page turns. Open the bottom panel with its up arrow or an upward swipe for reading controls and lighting. Open **Dictionary**, download its pack once, then look up an English word. Themes are under **Settings → App theme**. Initial pack downloads require internet; no payment or account is required. The distributed app requires **Android 15+ on ARM64**.

## Foreground-service declaration: paste-ready text

**Type and use case:** `mediaPlayback` — **Media Playback**. The application declares `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; `ReadingService` is a Media3 media session service for narration audio.

**Functionality:**

> Read Assist reads a document aloud after the user presses Play or starts a voice preview. Its media playback foreground service lets that requested narration continue while the user switches apps or turns the screen off. The user can pause or resume playback with media controls.

**Effect of deferral or interruption:**

> Deferral prevents requested narration from starting when the user presses Play. Interruption stops the listening session and breaks audio continuity. Background narration is a user-initiated reading feature.

**Verified reviewer page:** `https://sainadh812.github.io/ReadFlow/demo.html#background-title`

**Verified direct proof video:** `https://sainadh812.github.io/ReadFlow/media/Read-Assist-0.3.2-foreground-service.mp4`

Both URLs are publicly accessible and verified. The continuous recording starts with the document already open, then shows **Play → Home → continuing narration → notification Pause**. Resume and return to the reader are outside its recording; do not describe them as demonstrated. The viewing copy raises the actual captured audio by a constant 26 dB for clarity, with its original video stream and timeline preserved. The unchanged source is included in the source ZIP. Keep the emulator/build provenance with it; this does not establish a Play-installed ARM64 device test.

Google requires a functionality description, interruption/deferral impact, the selected use case and a video showing how the user triggers it. Its Media Playback example requires user-started audio/video; the official guidance does not prescribe YouTube hosting for this proof. The reviewed continuous recording also demonstrates background playback and notification Pause. [Official foreground-service instructions](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en).

## Retrieve these saved Console or owner facts

| Item | Exact remaining check |
| --- | --- |
| Account and production access | Inspect Dashboard/account verification and whether Production is enabled. Only if the account falls under Google's newer-personal-account rule: verify 12 continuously opted-in **closed** testers for 14 days and the production-access application. Internal publication is not that evidence. Use actual engagement, feedback and install forecasts if requested. [Conditional testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en). |
| Target audience, rating, territories | Preserve actual intended age groups and selected countries. Complete any unfinished rating questionnaire using the app and its general-purpose Wiktionary content; do not invent ages or a desired rating. |
| Data safety | Inspect the saved answers/export first. Include ML Kit telemetry separately from default-off Crashlytics, plus applicable support-email and external-provider flows. Do not mark all collection optional or declare no collection because document processing is local. The [reviewed data-flow facts](PRODUCTION_READINESS_0.3.1.md#privacy-and-data-safety-facts-for-the-current-submission) remain applicable: 0.3.2 changes dictionary normalization, not those flows. |
| Optional creator support | Establish the actual registered recipient for `readassist@ybl`, transaction information available to the recipient and whether the full contribution reaches the creator. Tips unlock no features. Use those facts for any financial-feature/payment disclosure; neither a verified recipient nor “no financial features” is established here. |

**Clarification added 21 November 2026:** The historical creator-support row above does not instruct selecting a financial service. Financial-data disclosures, Financial features and Play Billing are different questions. Use the [current account-type resolution and creator-tip guidance](PLAY_STORE_HANDOFF.md#creator-support-three-separate-policy-questions) to inspect the exact saved selections; neither an automatic “Other” financial feature nor a blanket “No” is supported by that old uncertainty.

Known support retention is **90 days after resolution**, with earlier deletion requests through the privacy contact. Payment records follow actual accounting/legal needs. These previously supplied practices do not need to be invented or requested again. Saved declaration completion and any account-specific notices remain unverified.

The existing automation updates listings/media and promotes the approved bundle; it does not submit these policy declarations. Google separately supports [Data safety submission from a CSV](https://developers.google.com/android-publisher/api-ref/rest/v3/applications/dataSafety), but this handoff has no approved current CSV and that API supplies no missing answers. Preserve/export existing answers before any replacement.

## Optional Play preview and final submission

The published `demo.html` also hosts `media/Read-Assist-0.3.2-product-demo.mp4`. That product walkthrough is separate from the continuous foreground-service proof. A **Play store preview video is optional** for this app and accepts a YouTube video URL, not this hosted MP4/page. If adding one, upload the reviewed product demo to an authorized channel, set public or unlisted, allow embedding, disable ads, avoid age restriction and use a direct video URL without extra time parameters. No YouTube upload is established here. [Official preview requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en).

If saved app access is correct and no Console/account notice explains the denial, provide Play support with the exact validation endpoint, timestamp and [sanitized failure report](evidence/reader-0.3.2/store-publication-attempt-43760801498.json). The response does not justify guessing another permission or replacing the existing key.

After the existing access check and actual outstanding Console tasks are resolved: publish and verify the approved Play listing/images, run `validate_production`, then `publish_production` for the existing code 28 bundle. Do not bypass validation. Record actual results separately as committed, under review, approved or publicly available; none of these states can be inferred from this handoff alone.
