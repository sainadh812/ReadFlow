# Read Assist 0.3.2 — Play Console handoff

Prepared 15 November 2026 for the authorized production release of **Read Assist**, package `app.readflow`, version **0.3.2 / code 28**. Use the [existing Console app](https://play.google.com/console/u/0/developers/9173169759090733636/app/4975999632955979701); preserve its signing setup, free pricing and existing tester lists. This handoff supplies answers and evidence; it does not establish that declarations are saved or production is approved.

## Verified work and current boundary

- The [GitHub candidate](https://github.com/sainadh812/ReadFlow/releases/tag/v0.3.2-play-candidate) is public. The signed AAB is **72,436,186 bytes**, SHA-256 `809f7fc41c0662c085abc313dc88269e4be1c1c416ff983405bcbc670461f12b`. Reuse this bundle; do not rebuild or upload code 28 again.
- [Internal testing publication](evidence/reader-0.3.2/play-release-summary.json) is confirmed: code 28, completed track, lifecycle `PUBLISHED`. Other tracks and tester membership were unchanged. Production was empty in that snapshot.
- Final [store attempt 43754924404](evidence/reader-0.3.2/store-publication-attempt-43754924404.json), revision `86a6ed8`, staged the en-US listing and all ten images: icon, feature graphic and **eight finished screenshots**. Readback verified them (`pendingSnapshotVerified=true`), but `POST /androidpublisher/v3/applications/app.readflow/edits/{editId}:validate` again returned **403 `PERMISSION_DENIED`**, with no additional Google reason. No commit was attempted; the owned edit was deleted. The listing/images remain unpublished. The already-requested check concerns saved access for `play-publisher@read-assist-fbd21.iam.gserviceaccount.com` and pending account notices; the cause is unestablished. Production is blocked and has **not been attempted**. No new key or broader permission request is introduced here.
- The Android dictionary regression passed. Signed-build verification recorded 462 tests, zero failures/errors, three skips and zero lint errors. See [current readiness and evidence](PRODUCTION_READINESS_0.3.2.md) for the precise device/build limits. These results do not establish saved policy answers, production eligibility or physical-device playback.
- Reviewed capture `43753190368` showed actual narration playback and a page turn from 2 to 3. Its capture checks incorrectly rejected Android's named playback state and assumed a different starting page; the app features were observed working. Eight screenshot frames are preserved with [capture provenance](../store-assets/release-0.3.2/capture-provenance.json). Corrected full capture [43754883035](https://github.com/sainadh812/ReadFlow/actions/runs/43754883035) is pending; final videos and continuous background-playback proof are not yet verified here.

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

**Planned reviewer page:** `https://sainadh812.github.io/ReadFlow/demo.html#background-title`

**Planned direct proof video:** `https://sainadh812.github.io/ReadFlow/media/Read-Assist-0.3.2-foreground-service.mp4`

These URLs are publication targets, **not confirmed live links in this handoff**. Attach the reviewed proof after the media-publication report verifies public access. The continuous recording should show opening the document, pressing Play, audible narration, leaving the reader, background playback, notification pause/resume and returning to the reader. Keep the emulator/build provenance with it; an emulator demonstration does not establish a Play-installed ARM64 device test.

Google requires a functionality description, interruption/deferral impact, the selected use case and a video showing how the user triggers it. Its Media Playback example requires user-started audio/video; the official guidance does not prescribe YouTube hosting for this proof. The continuous recording above also demonstrates our background-playback claim. [Official foreground-service instructions](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en).

## Retrieve these saved Console or owner facts

| Item | Exact remaining check |
| --- | --- |
| Account and production access | Inspect Dashboard/account verification and whether Production is enabled. Only if the account falls under Google's newer-personal-account rule: verify 12 continuously opted-in **closed** testers for 14 days and the production-access application. Internal publication is not that evidence. Use actual engagement, feedback and install forecasts if requested. [Conditional testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en). |
| Target audience, rating, territories | Preserve actual intended age groups and selected countries. Complete any unfinished rating questionnaire using the app and its general-purpose Wiktionary content; do not invent ages or a desired rating. |
| Data safety | Inspect the saved answers/export first. Include ML Kit telemetry separately from default-off Crashlytics, plus applicable support-email and external-provider flows. Do not mark all collection optional or declare no collection because document processing is local. The [reviewed data-flow facts](PRODUCTION_READINESS_0.3.1.md#privacy-and-data-safety-facts-for-the-current-submission) remain applicable: 0.3.2 changes dictionary normalization, not those flows. |
| Optional creator support | Establish the actual registered recipient for `readassist@ybl`, transaction information available to the recipient and whether the full contribution reaches the creator. Tips unlock no features. Use those facts for any financial-feature/payment disclosure; neither a verified recipient nor “no financial features” is established here. |

Known support retention is **90 days after resolution**, with earlier deletion requests through the privacy contact. Payment records follow actual accounting/legal needs. These previously supplied practices do not need to be invented or requested again. Saved declaration completion and any account-specific notices remain unverified.

The existing automation updates listings/media and promotes the approved bundle; it does not submit these policy declarations. Google separately supports [Data safety submission from a CSV](https://developers.google.com/android-publisher/api-ref/rest/v3/applications/dataSafety), but this handoff has no approved current CSV and that API supplies no missing answers. Preserve/export existing answers before any replacement.

## Optional Play preview and final submission

The planned `demo.html` also hosts `media/Read-Assist-0.3.2-product-demo.mp4`. That product walkthrough is separate from the continuous foreground-service proof. A **Play store preview video is optional** for this app and accepts a YouTube video URL, not this hosted MP4/page. If adding one, upload the reviewed product demo to an authorized channel, set public or unlisted, allow embedding, disable ads, avoid age restriction and use a direct video URL without extra time parameters. No YouTube upload is established here. [Official preview requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en).

After the existing access check and actual outstanding Console tasks are resolved: publish and verify the approved media/listing, run `validate_production`, then `publish_production` for the existing code 28 bundle. Do not bypass validation. Record actual results separately as committed, under review, approved or publicly available; none of these states can be inferred from this handoff alone.
