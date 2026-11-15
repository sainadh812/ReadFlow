# Read Assist 0.3.1: production submission evidence

Reviewed **15 November 2026** against the signed code **27** bundle and application source commit **`94006546634355658abfba7ce1a7a442d8825828`**. The owner has requested full publication. This document supplies current implementation facts and reviewer instructions; it does not assert that Play has granted production access or approved a production release.

## Exact artifact and observed Play state

| Item | Verified evidence |
| --- | --- |
| Application | Read Assist, `app.readflow`, version `0.3.1`, version code `27` |
| Signed AAB | `Read-Assist-0.3.1-upload-signed.aab`, 72,436,099 bytes; SHA-256 `a247863362aec730e6c50ebe85a403de8cb572821276077d38a3c0b2f8e7e1f6` |
| Device support | Android 15/API 35 or later, ARM64; target API 36 |
| Artifact checks | AAB signature and bundletool validation passed; APK/native 16 KB alignment checked. See [signed verification](evidence/reader-0.3.1/signed-artifact-verification.json) and [packaging](evidence/reader-0.3.1/packaging.json). |
| Automated app checks | CI: 460 tests reported, 457 passed and 3 fixture-dependent skips, no failures; lint: 0 errors and 38 warnings. [Build evidence](evidence/reader-0.3.1/release-evidence.json). |
| Internal testing | Code 27 committed and explicitly confirmed `RELEASE_LIFECYCLE_STATE_PUBLISHED` on 15 November. [Publication summary](evidence/reader-0.3.1/play-release-summary.json). |
| Later store inspection | Actions run `43744037729`: en-US title is Read Assist; default language en-US; support email `xperimenting.ai@gmail.com`; descriptions and all five queried image categories were empty. Production/alpha/beta tracks had no releases. Some lifecycle requests returned **“Listing releases quota exceeded”**; that message is quota evidence, not evidence that production permission was refused. |

An empty production track says nothing about account eligibility, completed declarations, countries, review requirements or testing prerequisites. The API inspection did not expose those Console decisions. Use the subsequent store-publication report to determine whether descriptions, graphics or production state have changed since this snapshot.

The original build record's `playUploadPerformed: false` is a historical build-time fact superseded by the later publication report. Its `deviceTestsPerformed: false` and `crashDeliveryVerified: false` remain limits of that build's evidence. Emulator recordings created afterward must identify their own source/build and must not be described as an ARM64 physical-device or Play-install test.

## Features and data flows in code 27

| Feature | Confirmed behavior and disclosure consequence |
| --- | --- |
| Reader and speech | Imports chosen PDFs/images or an HTTPS webpage. Extraction, OCR, Kokoro/Misaki speech and alignment operate locally. Models require downloads. The Play variant offers Kokoro and alignment, without the prototype Sherpa/Pocket/eSpeak path or external voice integration. |
| Main interface | Warm Library is the default; Modern Focus and Night Reader are selectable under Settings → App theme. Theme choices are local DataStore preferences. No new network service accompanies these themes. |
| Page and playback controls | Default book curl, right-to-left swipe advances; bottom Text/PDF switch, centered playback controls, up-arrow/swipe to open reader settings. Reader position and each mode's viewport are retained when switching. A text-only sample has no PDF original to toggle to. |
| Brightness and warm light | Reader-window brightness and a local yellow overlay; preference changes are stored locally. Brightness is restored when the reader loses foreground ownership. No system-settings permission or screen-recording permission is introduced. |
| Speech failures | Known punctuation/symbols are normalized for speech. Unsupported content can still fail; affected sentence words are marked red. This is not a guarantee that all text can be spoken. No new upload path is introduced by failure highlighting. |
| English dictionary | User-requested GitHub pack download: 96,929,395 compressed bytes, 280,727,552 installed bytes. Checksummed local SQLite definitions and suggestions; no per-word network lookup or saved search-history table. Opening a source/license link launches its website. A word bookmarked explicitly is stored as a document bookmark. Opening the dictionary pauses active narration. |
| Crash reporting | Firebase configuration is present for `read-assist-fbd21`. Reporting is off by default and Firebase initializes only after saved opt-in at a subsequent app start. Handled-error summaries exclude document text/names/URLs/audio and raw messages; automatic traces/native information can contain incidental runtime data. Opt-out stops app submissions immediately; SDK shutdown and queued-report behavior follow the policy's next-launch explanation. |
| Support | “Report a problem” previews an email to `xperimenting.ai@gmail.com`; the optional technical-details checkbox starts unchecked. The user sends using their email provider. Production GitHub report/token UI is unavailable. Local report/export paths remain separate and can contain reading material. |
| Creator support | Optional external UPI handoff to owner-supplied `readassist@ybl`; recipient, amount and note are sent to the selected payment app. No payment SDK, callback, transaction storage or entitlement is implemented. All current features remain free. The app's display name is not bank verification of that recipient. |

Source anchors: [reader UI](../app/src/main/java/app/readflow/ui/ReadFlowUi.kt), [theme preferences](../app/src/main/java/app/readflow/data/AppTheme.kt), [lighting](../app/src/main/java/app/readflow/ui/ReaderLighting.kt), [dictionary repository](../app/src/main/java/app/readflow/dictionary/DictionaryRepository.kt), [crash reporting](../app/src/main/java/app/readflow/diagnostics/CrashReporting.kt), [support email](../app/src/main/java/app/readflow/ui/ReportProblemDialog.kt), [UPI parameters](../app/src/main/java/app/readflow/ui/SupportTipDetails.kt).

## Privacy and Data safety facts for the current submission

The public [Privacy Policy](https://sainadh812.github.io/ReadFlow/privacy.html), effective **15 November 2026**, returned HTTP 200 and matched `site/privacy.html` exactly during this review: SHA-256 **`0bd9eb1e31a99fefb8da580b98ae9f4c8ceafbd4d99fbac7f610f56df01d197a`**. It includes dictionary downloads, offline lookups, removal and external source/license links. The signed AAB's `privacy-information.txt` also matches the current source. [Publication history](PRIVACY_PUBLICATION.md).

Do not submit “no data collected” based on local document processing. The actual resolved graph contains ML Kit text recognition **16.0.1**, ML Kit common **18.11.0**, Crashlytics/NDK **21.0.1**, Firebase Installations **20.0.0**, and Firebase Sessions **4.0.1**. Firebase Analytics is absent. ML Kit's disclosure includes installation identifiers, diagnostics and performance/usage information; its collection is not controlled by this app's Crashlytics consent switch. [Google ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure).

For the form, account for crash logs, diagnostics, device/other IDs, and applicable performance/session information from enabled Firebase reporting; this flow is optional because it has a real default-off choice. Firebase expressly identifies its Installations and Sessions dependencies. The separate email-support flow can contain an email address, user-written description and optionally reduced technical details. Model/dictionary hosts and imported websites receive connection/request information. Local theme changes and local dictionary queries add no off-device collection by themselves. Exact form choices must reflect both these code facts and the owner's actual recipient/support practices; the older worksheet is not evidence that the Console answers were completed. [Firebase disclosure](https://firebase.google.com/docs/android/play-data-disclosure#crashlytics), [Play Data safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469).

No new privacy-policy mismatch was found for dictionary/themes/lighting in this review. No first-launch/consent network capture or Firebase delivery test was performed here; configured SDK presence is not delivery evidence.

## Notices, models and demo content

The final AAB's `licenses/dependencies/resolved-runtime.json` was compared with signed configured 0.2.6 (`e222bcf1610e211093836918a7a30960c692bb43c6d4de12d23fe6b3a0579727`): **all 144 inventory entries, artifact hashes and inventory fields are identical**. All **287** prior license assets remain byte-for-byte identical. Exactly three dictionary files were added, for **290** license assets total: attribution, CC BY-SA 4.0 and source lock. Both speech/OCR manifests are unchanged. Existing bounded reviews therefore remain relevant to those unchanged inputs; this comparison is not a claim of complete legal clearance.

- The Play bundle contains Kokoro/Misaki and ONNX Runtime, with no Sherpa JNI/library or legacy eSpeak model data according to the signed packaging evidence. Kokoro and alignment provenance/Apache notices are present, along with Misaki, OCR/runtime, font and generated dependency notices.
- The exact OkHttp public-suffix source, full MPL-2.0 text, `MPL-SOURCE-AVAILABILITY.txt`, ONNX third-party notices and Readability license are present. The six font families retain their OFL notices. Existing [model-distribution](PLAY_MODEL_DISTRIBUTION.md) and [source-level review](PLAY_REAUDIT_2026-10-26.md) distinguish release dependencies from prototype-only ones.
- Dictionary pack **2026.10-v1** has local SHA-256 **`2abc472051994a9b8109119291d89d51bd44a0ad94041c6768dfa30075785ad8`**, matching the manifest. Its metadata includes attribution, CC BY-SA 4.0, source checksum and extractor revision. All 1,110,658 definition rows retain an English Wiktionary source URL. Settings → Privacy and licenses exposes the bundled notices; the dictionary sheet exposes source and license links. [Dictionary attribution](dictionary/ATTRIBUTION.txt), [build/source instructions](dictionary/BUILD.txt).
- The built-in **“A quiet space for thought”** sample is project-authored CC0 text, identified as such by the importer. Original generated project fixtures can be used for screenshots and recordings. Recorded synthesized narration retains the Kokoro/Misaki model attribution; dictionary screenshots retain the visible Wiktionary/license links and attribution in accompanying media credits. No user document is required for a demo.

One concrete, pre-existing wording defect remains: `ocr-model-manifests.json` says **“llama.cpp and GGUF conversion: MIT”**. The converted TeleOCR weights are described by their pinned source as Apache-2.0; **llama.cpp runtime is MIT**. The same manifest separately lists TeleOCR/NaviDC-OCR as Apache-2.0 and notices preserve the distinction. Do not repeat the incorrect combined label in store copy. The prior audit deliberately retained serialized manifest bytes because they affect installation fingerprints; a future correction needs migration handling, not an unreviewed label edit to an already published code 27 artifact. No new missing-notice or dependency-license defect was established in this review.

## What the old worksheet gets wrong now

[PRIVACY_AND_STORE_DRAFT.md](PRIVACY_AND_STORE_DRAFT.md) is explicitly a **0.2.5 / 27 October** worksheet. Its pending Firebase setup, old public-policy revision, no-Play-upload state and feature list are stale for code 27. Its proposed application EULA was never a statement that Google requires a separate custom EULA. The public-repository license question remains distinct from the owner's authorization to distribute this app and from preserved third-party rights. Do not turn that old proposal into a newly invented publication approval step.

[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)'s historical paragraph claiming no automatic diagnostic uploads predates optional Crashlytics and must not be used as current privacy copy. Its prototype Sherpa/eSpeak/Pocket descriptions do not describe the Play release. The dated artifact JSON fields should remain historical; later evidence supplies the updated state.

## Console facts that this source review cannot supply

The remaining work is to inspect and complete the actual Console fields, using available owner/account evidence. These are not all established failures or newly imposed gates:

| Console/owner item | Current evidence and exact missing information |
| --- | --- |
| Service-account permissions | The owner previously granted app view and testing releases. Store-presence and production publishing permission updates are being handled separately. Inspect the actual grant or API result; a lifecycle quota response does not prove a missing grant. |
| Production access/account status | Account type, verification status, production eligibility and any Console-specific testing prerequisite are not in the inspected API report. Do not assume a personal-account test requirement or that Internal publication satisfies it. |
| Store presence | The observed en-US listing lacked descriptions and graphics. Current images/video publication is separate work; verify the final listing after its commit. |
| App content | Verify submitted Data safety, ads declaration, target audience, content rating, app access, privacy URL and foreground-service declaration. Source supports no ads, no app login and no paid feature gate; target audience and rating must be chosen from actual intended users/content, including the general-purpose dictionary. No current completion state was retrieved. |
| Distribution | Confirm the existing free price and chosen territories in Console. Current user authorization covers publication; do not infer new prices or country exclusions. |
| Creator support | Owner confirmation of `readassist@ybl` registration/recipient and what transaction details the recipient actually receives is absent from repository evidence. Use real owner/provider facts for the relevant disclosure rather than inventing a payment backend or a verified recipient name. |

## App-access instructions ready for the reviewer

No Read Assist account, login, invitation, purchase or special credentials are required. Use a compatible Android 15+ ARM64 device. Internet access is needed for the initial optional model and dictionary downloads.

1. Open Read Assist. On the empty Library, tap **Try a sample** to open **A quiet space for thought**. This text sample can be read immediately.
2. For narration, open **Voices** and download the offered **Kokoro** voice pack and **alignment** pack; wait until both show Installed. Choose a voice, then use its preview or return to the sample and tap **Play**. The two packs total about 447 MB of downloads; model setup/storage takes additional space.
3. To inspect PDF mode and page turns, import a reviewer-owned multi-page PDF through Library's add/import action. Use the bottom **Text / PDF** switch. Swipe right-to-left to advance. Tap the up arrow or swipe upward from the bottom dock for speed, speech/recognition controls, brightness and warm light; use the down arrow to close it.
4. Long press a recognized word, or open the reader menu → **Dictionary**. Choose **Download dictionary** if needed, then look up an English word. The pack is about 97 MB compressed and 281 MB installed. Closing the sheet leaves narration paused until the user resumes it.
5. Open **Settings → App theme** to inspect Warm Library, Modern Focus and Night Reader. **Privacy and licenses** contains the offline disclosures, full notices and public policy link. Automatic crash reports and creator support are optional and are not needed to review any reading feature.

## Foreground-service declaration draft

**Type/use case:** `mediaPlayback` / Media Playback. The manifest declares `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; `ReadingService` is a Media3 `MediaSessionService` using ExoPlayer for speech audio. No microphone or media-projection feature is declared by this application.

**Functionality:** “Read Assist reads a document aloud after the user presses Play or a voice-preview button. A media playback foreground service lets the chosen narration continue while the user switches apps or turns the screen off. Media controls let the user pause or resume playback.”

**Impact of deferral/interruption:** “Deferring the service prevents requested narration from starting when Play is pressed. Interrupting it stops the reader's listening session and audio continuity. Background playback is an explicit reading/listening feature and begins through a user action.”

**Video to attach:** Record the real app opening a sample/document, the user pressing Play, audible narration, leaving the app while playback continues, opening the media notification, pausing/resuming there and returning to the reader. Preserve the recording's build/device provenance. Do not substitute a GUI mockup, a synthesized screen animation or a marketing montage as proof of background playback. The demo URL is supplied by the media publication task; no video is claimed attached by this document. Google requires a video showing the user-triggered feature for the declared service. [Official foreground-service declaration guidance](https://support.google.com/googleplay/android-developer/answer/13392821).
