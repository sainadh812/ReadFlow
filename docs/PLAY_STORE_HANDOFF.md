# Read Assist publishing handoff for Muse Spark

**Current review state — 21 November 2026: rejected again after the declaration correction.** The owner reports that Financial features was corrected to **“My app doesn’t provide any financial features”** and resubmitted at approximately **10:32 IST**. A second rejection arrived at **10:39 IST** with the same **Play Console Requirements → Developer Account** wording as the first notice at **10:08 IST**. The earlier checkbox diagnosis did not resolve the enforcement. Neither notice identifies a specific app feature, and the seven-minute interval does not establish how Google performed the review.

Read Assist **0.3.4 / versionCode 30**, package `app.readflow`, remains **not approved on alpha closed testing**. The latest read-only [Play inspection](evidence/play-review-2026-11-21/store-inspection-recheck-44869681936.json), [run 44869681936](https://github.com/sainadh812/ReadFlow/actions/runs/44869681936), completed at **10:41 IST** and shows the same bundle and release state: alpha `RELEASE_LIFECYCLE_STATE_NOT_APPROVED`, Internal testing `RELEASE_LIFECYCLE_STATE_PUBLISHED`, no production or beta release. The declaration change, resubmission and second email are [owner-reported evidence](evidence/play-review-2026-11-21/second-rejection-reported.json); those details are not exposed by this API inspection.

**Current direction: research a replacement support model before another submission.** The owner requested examples of how other Android apps handle tipping. The recommendation below is an optional digital supporter purchase through Play Billing, keeping all existing reading features free. This is a proposal, not an implemented change or a guaranteed remedy for the account-type rejection. The [classification appeal](evidence/play-review-2026-11-21/account-classification-appeal.txt) is retained as a fallback; no appeal has been submitted. The shipped UPI tip remains a possible explanation, not a confirmed cause.

This is the current operational handoff. Older version-specific handoffs and the dated material below remain historical records, not instructions to upload codes 22, 27 or 28. A track entry with `status: completed` does not override its `NOT_APPROVED` review lifecycle. The earlier API listing-validation HTTP 403 is a separate event, not the reason supplied for this review rejection.

## Current artifact and preserved setup

| Item | Verified value |
| --- | --- |
| App / version | Read Assist, `app.readflow`, **0.3.4 / code 30** |
| AAB | `Read-Assist-0.3.4-upload-signed.aab`, **72,436,935 bytes** |
| SHA-256 | `7371bdf73cd8dc91e05f2889c481ec896c3b80995ba925a1037f2f5b768ef41c` |
| Source / release | `ffb5e0b4a34a28149c13c9d1c570d6f4d4a894f4`; [GitHub release](https://github.com/sainadh812/ReadFlow/releases/tag/v0.3.4-play-candidate) |
| Build verification | [0.3.4 release summary](evidence/reader-0.3.4/release-summary.json); [signed artifact verification](evidence/reader-0.3.4/signed-artifact-verification.json) |
| Existing Play app | [Read Assist in Play Console](https://play.google.com/console/u/0/developers/9173169759090733636/app/4975999632955979701), app ID `4975999632955979701` |
| Device support | Android 15/API 35 or later, ARM64; target API 36 |
| Developer / support | Oh My Pi; `xperimenting.ai@gmail.com` |
| Privacy policy | `https://sainadh812.github.io/ReadFlow/privacy.html` |

The 21 November inspection found the same code 30 AAB hash already on Play. Preserve that artifact and the existing upload key, Play App Signing, free pricing and tester lists. Use the existing GitHub Actions secret `READFLOW_PLAY_SERVICE_ACCOUNT_JSON`; do not export or replace it to investigate a policy rejection. Changes to app code would need a new verified build and version code; a listing or declaration correction does not by itself require rebuilding the app.

## Resolve the confirmed account-type rejection

The rejection is about **developer-account eligibility for the app’s selected category or declared features**. The existing API validation denial, outdated screenshots and missing-model playback defect are separate findings. Fixing playback or uploading another APK does not resolve this account-type policy by itself. Google’s [account-type requirements](https://support.google.com/googleplay/android-developer/answer/13634885?hl=en) explain the affected service classes.

The following answers combine Muse’s original Console inspection with the owner’s subsequent correction report. They are **owner-reported Console evidence**, separate from the API readback. The [original inspection](evidence/play-review-2026-11-21/console-declarations-reported.json) is retained as a historical snapshot; its pending-action flags predate the correction and second rejection.

| Item | Saved answer reported by Muse | Action |
| --- | --- | --- |
| App category | **App / Productivity**; no tags | Keep. This accurately describes the general document reader. |
| Financial features | Originally **“Mobile payments and digital wallets”** only, last edited 16 November. Owner now reports this was changed to **“My app doesn’t provide any financial features”** before the approximately 10:32 IST resubmission | Preserve the corrected saved summary and submission evidence for the appeal. The correction did not clear the rejection. |
| Health apps | Last edited **5 November 2026**. **“My app does not have any health features”**; all 18 feature boxes unchecked | Keep. Source has no diagnosis, treatment, health tracking, research or Health Connect feature. |
| Government apps | Last edited **5 November 2026**. **No** | Keep the owner-reported affiliation answer. |
| VPN | No VPN / `VpnService` declaration exists in App content | Keep. Source has no VPN; its foreground service is media playback. |

### Support model research and recommendation

Primary sources checked on **21 November 2026** show several approaches:

| App | Observed support model | What the evidence establishes |
| --- | --- | --- |
| **Home Upkeep** | Optional repeatable tips using Google Play purchases; its features remain free | [Current listing](https://play.google.com/store/apps/details?id=com.joshapproved.homemaintenance) and [pinned source](https://github.com/Josh-Approved/home-maintenance/blob/03028587371354267aad33de594c4fe61169a683/src/lib/tipJar.ts) show the tip jar and consumable purchase completion. The publisher is an LLC; this is not evidence of personal-account eligibility. |
| **Pure Light** | Optional consumable tips with names/prices supplied by Google Play | Its [current listing](https://play.google.com/store/apps/details?id=com.exquinet.purelight) explicitly describes repeatable Play tips and free app functionality. This is the developer’s published description, not a source audit or tested purchase. |
| **Fossify Thank You** | Paid supporter companion app providing digital customization benefits | The [official project](https://github.com/FossifyOrg/Thank-You) describes global themes/customization and removal of contribution prompts. This demonstrates purchasing a supporter benefit; it is a separate paid app, not an in-app consumable tip. |
| **AnkiDroid** | Donation links removed from the Play build, retained in its other distribution | A [maintainer’s 24 October update](https://github.com/ankidroid/Anki-Android/issues/21656#issuecomment-6386339620) reports that version 2.25.1 was accepted after removing Play donation links. Its issue was **Payments policy**, distinct from Read Assist’s **Developer Account** rejection. |

The current listings and developer reports establish examples, not approval of the same design for Read Assist. Kettle also has an [explicit support design](https://celsaad.github.io/kettle/support.html) and [native Kotlin Billing implementation](https://github.com/celsaad/kettle/blob/39489a728c0ba1ab7cfc5c01c8ffb0ab9e059d65/native/android/app/src/main/java/com/casco/kettle/shell/support/TipBilling.kt), but its linked Play listing returned 404 during this research; it is an implementation reference only.

**Recommended design:** Replace the external UPI flow in the Play build with **Settings → Support Read Assist → buy an optional permanent supporter badge → Google Play checkout**. The badge would be a real, clearly described cosmetic purchase displayed only in Settings, with all current PDF, speech, OCR, dictionary, theme and lighting features remaining free. No subscription or reading interruption. Use a **non-consumable one-time product** because the badge is permanent; restore ownership after reinstall. This adopts the digital-supporter-purchase model without creating a separate companion app.

This is the clearest fit with Google’s published [digital-purchase policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en) and [one-time product definitions](https://developer.android.com/google/play/billing/one-time-products). Repeatable no-benefit tips are implemented by the apps above, but the official guidance reviewed does not explicitly approve every pure developer-tip SKU. Conversely, the external direct-tip exception has its own conditions and does not settle the account-type question. A website or a renamed button alone would not resolve that uncertainty.

Implementation would require:

1. An eligible linked Play payments profile and a real one-time product configured in Console. Display Play’s localized price; do not hard-code an arbitrary amount or a universal fee percentage. The current merchant setup has not been inspected. [Billing setup](https://developer.android.com/google/play/billing/getting-ready), [service fees](https://support.google.com/googleplay/android-developer/answer/112622?hl=en).
2. Native Billing integration with purchase verification, pending/cancel/error handling, actual badge delivery, acknowledgement and restoration. Test with licensed testers; a failed or cancelled purchase must never affect reading. [Integration](https://developer.android.com/google/play/billing/integrate), [verification guidance](https://developer.android.com/google/play/billing/security).
3. Removal of the external UPI amount field, copy-ID action and payment handoff from the Play build, plus accurate updates to listing/privacy disclosures for the final purchase flow. Read Assist currently shares its release implementation between APK and AAB; retaining different support flows for GitHub and Play would require an explicit build-distribution split.
4. A new verified version code and replacement of affected active bundles across test tracks. No code, product, account setup or release has been changed for this proposal. Personal accounts can monetize under Google’s [account-type guidance](https://support.google.com/googleplay/android-developer/answer/13634885?hl=en), but adopting Billing does not establish why this app was rejected or guarantee approval.

If retaining a pure no-benefit tip is essential, the repeatable Play checkout approach is a real alternative supported by the implementation examples, but obtain clarification for that exact design rather than describing it as an automatic account-type fix. If the immediate priority is eliminating in-app support transactions, the AnkiDroid-style Play build without a tip flow is the smaller change; it also cannot guarantee clearance of an unidentified account requirement.

### Fallback appeal handoff for Muse

1. Finish checking **Policy status** in the [existing Read Assist app](https://play.google.com/console/u/0/developers/9173169759090733636/app/4975999632955979701). Record any additional issue detail, affected version and enforcement reference. If it adds a specific finding, incorporate that detail into the appeal before submission.
2. Preserve the **10:08 and 10:39 IST rejection notices**, the **saved corrected Financial features summary**, and the **Publishing overview submission record**. Approximately 10:32 IST is inferred from the owner’s report that rejection followed seven minutes after resubmission; use the exact Console time if available. Include the current Productivity/Health/Government answers and account-type or verification details only as actually displayed. Do not invent an account-verification defect.
3. Use the **appeal route in the latest enforcement email** and the ready-to-paste [appeal text](evidence/play-review-2026-11-21/account-classification-appeal.txt). Where the form supports attachments, include the relevant Console/email evidence. The draft does not claim those files are already attached. Use English and identify **`app.readflow`, alpha, 0.3.4/code 30**. If an appeal for this enforcement already exists, add evidence through that case rather than creating a duplicate. See Google’s [appeal instructions](https://support.google.com/googleplay/android-developer/answer/9899142?hl=en) and [one-appeal-per-enforcement guidance](https://support.google.com/googleplay/android-developer/answer/2477981?hl=en).
4. Record the appeal acknowledgement, case/reference number, submitted text and time. An acknowledgement is not approval. If the appeal route is unavailable, use Console Help to request classification clarification with the same facts and existing enforcement reference.
5. Keep the app and declarations stable while Google identifies the outstanding requirement. If Google explicitly requires a feature change, implement that change openly and validate a new version code; any affected bundle must be replaced on every track where it remains active, including Internal testing. If Google identifies a real organization-account requirement, use the genuine organization-verification route. Neither outcome is established by the current notice.

This workspace has publishing API access but no signed-in Console browser or supported appeal-submission tool. The draft is ready for Muse’s signed-in session; no new API key is needed. No additional resubmission, appeal, feature removal or account transfer was performed here. The separate source playback fix remains unreleased.

### Creator support: three separate policy questions

**Implemented behavior:** Settings → Buy me a coffee offers a voluntary tip to the single fixed developer recipient `readassist@ybl`. The app passes that recipient, an INR amount and a support note to an external UPI app, or copies the public recipient ID. It provides no user wallet, stored balance, arbitrary-recipient transfers, banking, loans, trading, payment callback or transaction backend. The external payment app handles authorization and payment status. Tips unlock no content, features, usage, badges or priority support. The configured ID alone does not verify the recipient’s identity or provider arrangements.

1. **Financial features declaration** classifies financial functionality offered by the app. Our source-based assessment supported changing the original payments/wallets answer to no financial features. The owner reports that this correction was submitted and rejected again. Google’s [financial-feature guidance](https://support.google.com/googleplay/android-developer/answer/13849271?hl=en-GB) does not explicitly classify this exact UPI handoff; the appeal therefore asks for a ruling on the disclosed behavior. Do not present the previous assessment as an accepted exemption.
2. **Data safety** describes information collected or shared. A financial-data assessment about recipient-visible payment information is not an instruction to declare a wallet or money-transfer service. Preserve accurate data disclosures, including ML Kit telemetry and optional Crashlytics; this account-type rejection does not justify declaring no collection. See [the current data-flow review](PRODUCTION_READINESS_0.3.1.md#privacy-and-data-safety-facts-for-the-current-submission).
3. **Play Billing** has a [direct creator-tip exception](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en) subject to its actual conditions, including the entire contribution reaching the creator and no digital benefit in return. That exception addresses billing; it does not automatically exempt an app from the Financial features declaration or organization-account requirements.

The submitted-build source was rechecked at commit `ffb5e0b4a34a28149c13c9d1c570d6f4d4a894f4`: [tip parameters](https://github.com/sainadh812/ReadFlow/blob/ffb5e0b4a34a28149c13c9d1c570d6f4d4a894f4/app/src/main/java/app/readflow/ui/SupportTipDetails.kt), [tip dialog and external handoff](https://github.com/sainadh812/ReadFlow/blob/ffb5e0b4a34a28149c13c9d1c570d6f4d4a894f4/app/src/main/java/app/readflow/ui/SupportTipDialog.kt). The public privacy policy also describes the tip and external provider’s handling of payment information. Do not conceal or deny that shipped feature. The appeal asks Google to identify the outstanding requirement before another release is attempted.

## Separate product and listing corrections

These findings improve accuracy and usability but are **not established causes of the account-type rejection**:

- The 21 November API read still returns older listing copy claiming documents “never leave your phone” and unqualified offline operation. Local report exports can include reading material, and speech/alignment setup requires downloads. The prepared [en-US listing](../store-assets/listing/en-US.json) accurately describes on-device processing and required setup; it is **not yet the saved Play copy**. Importing scanned-page images is supported; do not imply an in-app camera scanner.
- Current store screenshots and the public demo were captured on code 28, and the demo page’s APK button still links to 0.3.2. Refresh affected materials using real captures of the submitted build and preserve provenance; do not relabel old captures.
- The independent missing-model playback fix routes the user to voice setup. Its [validation](evidence/play-review-2026-11-21/validation.json) passed 481 unit tests, including 12 setup-action tests and 8 dock tests, with zero lint errors. No fresh-install Android UI test was performed. Shipping it requires a new verified build/version code. It is not a remedy for the account-type rejection.

No login, invitation, subscription or payment is required to review reading features. All existing features remain free and there are no ads. After the actual account/declaration issue is resolved and any chosen app changes are verified, submit through the applicable track and record review submission, approval and public availability separately. Internal publication is not approval of the rejected alpha release.

## Reviewer instructions for 0.3.4

Use Android 15+ on an ARM64 device. Initial speech and dictionary setup needs internet and storage; the downloads are not bundled into the APK.

1. Open the Library and tap **Try a sample** to open **A quiet space for thought**. Text reading is immediately available.
2. Open **Voices** (screen title **Voice models**). Download the offered **Kokoro** and **alignment** packs and wait until both say **Installed**. The combined downloads are about **447 MB**; installation needs additional storage. Choose a voice, return to the sample and tap **Play**. The primary button's circle indicates audio preparation; no separate Ready/Preparing row or horizontal loading bar is shown.
3. Import a reviewer-owned multi-page PDF to review original-page rendering. Tap the small **Tt icon immediately after skip-next and before the up arrow** to toggle text/original view. There are no separate Text and PDF buttons. The icon appears only for documents with an original PDF/image. Swipe right-to-left to advance a page.
4. Tap the up arrow or swipe upward from the bottom controls for speed, voice, recognition, brightness and warm light. Performance information has a saved show/hide setting. The compact dock keeps its transport controls in place during audio preparation.
5. Long press a recognized word or open the reader menu → **Dictionary**. Download its pack once (about **97 MB** compressed, **281 MB** installed), then look up an English word. Closing the dictionary leaves narration paused until the user resumes.
6. Open **Settings → App theme** for the three library themes. **Privacy and licenses** contains local notices and the public policy. Crash reporting and creator support are optional.

## Reviewer video and current evidence limits

The existing [continuous foreground-service demonstration](https://sainadh812.github.io/ReadFlow/media/Read-Assist-0.3.2-foreground-service.mp4) remains reachable and shows actual **Play → Home → continuing narration → notification Pause**. Its original capture is from **0.3.2**, with emulator/build provenance in the [video report](evidence/reader-0.3.2/demo-video/video-build-report.json). It is evidence of that unchanged media-playback flow, not a video of the new compact controls or a physical ARM64 phone. If Google's notice requests different or current-version evidence, capture that exact flow on the corrected build. The marketing film is separate from this continuous proof.

The public privacy page returned HTTP 200 on 21 November and matches `site/privacy.html` exactly (SHA-256 `0bd9eb1e31a99fefb8da580b98ae9f4c8ceafbd4d99fbac7f610f56df01d197a`). Both the foreground-service and product-demo MP4s returned HTTP 200 with their recorded hashes. No broken privacy or demonstration link was found. This does not establish which URL or declaration Muse actually submitted.

## Historical records

The following dated build/configuration notes are retained for provenance. Their old pending states and artifact-selection instructions do not describe the current release or authorize uploading an older bundle.

## Historical 0.2.6 configuration and release checks — 31 October 2026

Muse Spark reports that the owner Firebase project and client configuration exist in its workspace. The file is absent from this coding workspace, but the repository secret `READASSIT_FIREBASE_CONFIG_BASE64` exists. The workflow accepts that earlier spelling; its value passed the configured build, and the final APK resources match the approved identifiers. The new upload key and four signing secrets are already stored in GitHub. Ordinary review builds without the config remain usable, with automatic reporting unavailable. Publication builds using `-PplayPublication=true` require a valid owner-supplied `app/google-services.json`, secure signing and the policy URL. The app's Gradle check accepts the exact `app.readflow` client and rejects source-set overrides. It does not verify project ownership, Console enablement or delivery.

After verifying the configured signed release, explicitly run:

```bash
./gradlew :app:uploadCrashlyticsSymbolFileRelease --console=plain
```

Keep the exact build outputs and GNU build IDs. The Misaki build now retains GNU build ID, full DWARF and symbol-table information in its generated unstripped library, validated by the preparation script. Verify the final packaged copy has the same GNU build ID; retain that exact original for upload. See [native crash symbols](NATIVE_CRASH_SYMBOLS.md). The upload task searches merged native libraries; pre-stripped third-party libraries still require matching supplier symbols for complete frames. Real-device native/Go stack unwinding and symbolication remain to be demonstrated; successful symbol generation/upload alone does not prove complete coverage. Confirm mapping requirements if minification is enabled later. [Firebase NDK setup](https://firebase.google.com/docs/crashlytics/android/get-started-ndk).

The consent/network flow requires device tests. A local SDK submission count is not proof of report persistence or server delivery. Record the 0.2.6 artifact's own device, delivery and Console evidence when those checks complete.

## Historical 0.2.6 configured, upload-signed candidate

[Verified build](https://github.com/sainadh812/ReadFlow/actions/runs/41183292058) · [download APK/AAB and reports](https://github.com/sainadh812/ReadFlow/actions/runs/41183292058/artifacts/13036231224) (14-day artifact retention) · [published 0.2.6 prerelease](https://github.com/sainadh812/ReadFlow/releases/tag/v0.2.6-play-candidate).
Version **0.2.6/code 22**, source `25944d402312adf69e44f5e3d6f5b42a2e6b179f`.
The exact AAB SHA-256 is `e222bcf1610e211093836918a7a30960c692bb43c6d4de12d23fe6b3a0579727` (72,201,078 bytes).
The APK SHA-256 is `b4bddf9865623d8ec4d3c374eaac9ee399a964c215d87bfec28964742fb8fddb` (130,803,112 bytes).
The public upload certificate pin is unchanged. Firebase is configured for the
approved app; reports remain off by default. Tests: **372 passed, three skipped**
(real-model files absent on CI); lint: **0 errors / 34 warnings**. CI packaging,
signatures, fonts, public policy and 16 KB checks passed, and all 69 downloaded
artifact hashes matched. Independent local checks verified signatures and packaged
Firebase identifiers. All 144 runtime dependency hashes, 314 assets (287 license
assets) and seven fonts match 0.2.5; only the app-owned Misaki native binary changed.
See [release evidence](evidence/play-store-0.2.6-configured/release-evidence.json),
[checksums](evidence/play-store-0.2.6-configured/SHA256SUMS.json),
[local verification](evidence/play-store-0.2.6-configured/local-verification.json)
and [dependency delta](evidence/play-store-0.2.6-configured/dependency-delta.json).
The [GitHub publication record](evidence/play-store-0.2.6-configured/github-release.json)
confirms the prerelease assets; it is separate from a Play upload.
These checks do not establish legal clearance or phone acceptance.

Continue in small tasks. Preserve the exact artifact and reports first. Native
symbols are retained in the download but have not been uploaded; real device
consent/crash delivery, final publishing/license review and production readiness
remain pending. **0.2.6/code 22 has not been uploaded to Play.** The owner-reported
accepted draft is 0.2.5/code 21; no automatic internal rollout or production
submission follows from the new build.
The upload-signed APK cannot update a prototype signed with the old debug key or
a Play-installed copy signed with the separate Play app-signing key. Use the
appropriate Play test track for that installation, or a separate test device;
preserve the owner's reading data.

## Historical configured 0.2.5 candidate

[Run 40529659238](https://github.com/sainadh812/ReadFlow/actions/runs/40529659238)
built **0.2.5/code 21** from `f025430e9e18d65fc317afe06e5bde06acb7fb47`.
Its AAB SHA-256 is `9709f780723e279cbe7cb9a09cb868374e7c158fb9e3d42a2ee32d4bfe7b6bab`;
APK SHA-256 is `723fd50d4a68fc8ddfa73032d721d9415000c6cd51c5ec5be68e30c04659fb2f`.
That build passed 360 tests with three fixture skips and lint at 0 errors/34 warnings.
Its [release evidence](evidence/play-store-0.2.5-configured/release-evidence.json),
[local verification](evidence/play-store-0.2.5-configured/local-verification.json)
and [dependency delta](evidence/play-store-0.2.5-configured/dependency-delta.json)
remain historical records. The owner-reported Play draft above contains code 21;
it does not contain the 0.2.6 normalization or reporting fixes.

## Historical unconfigured 0.2.5 review artifacts

[Review release](https://github.com/sainadh812/ReadFlow/releases/tag/v0.2.5-play-candidate): preview APK (prototype certificate), unsigned AAB, source, handoff and checksums. All **363 release JVM tests passed**; lint has **0 errors / 34 warnings**. Bundle, font, APK signature and 16 KB checks passed. The final review artifacts have **no Firebase configuration**, so automatic reporting is unavailable. A temporary synthetic client configuration passed local resource/build-flag/native-symbol generation checks, then was removed; it was never used for report or symbol upload. The final APK/AAB were rebuilt unconfigured. See [build evidence](evidence/play-store-0.2.5/build-results.json), [packaging](evidence/play-store-0.2.5/packaging.json), [native symbols](evidence/play-store-0.2.5/native-symbols.json) and [dependency notices](evidence/play-store-0.2.5/firebase-dependencies.json). These checks do not replace the real-project/device tests above.

## Historical 0.2.4 review candidate

Version 0.2.4/code 20 configures the published [Privacy Policy](https://sainadh812.github.io/ReadFlow/privacy.html) as the app's default policy URL, while preserving the corrected name, UPI recipient, package and existing functionality. The owner-approved public developer name is Oh My Pi and the contact is xperimenting.ai@gmail.com. The policy source is [site/privacy.html](../site/privacy.html); its published bytes were verified on 2026-10-26 at GitHub Pages commit `2e3aa9c1092dc20d1bc72adc1b45d0aa8b6e9a93`, SHA-256 `48483d2dc25a971765d77c553cfeb6b242c47c7bb6851dfd4a28bee2a1fbc1d1`.

The [0.2.4 review release](https://github.com/sainadh812/ReadFlow/releases/tag/v0.2.4-play-candidate) contains `Read-Assist-0.2.4-play-preview.apk` (existing prototype certificate), `Read-Assist-0.2.4-play-unsigned.aab` (unsigned), source, this publishing handoff and SHA-256 checksums. The Gradle build passed in **3m 38s**; all **335 release JVM tests passed** and release lint has **zero errors and 28 warnings**. APK signature and 16 KB ZIP alignment checks passed. See the [build record](evidence/play-store-0.2.4/build-results.json). The [bundle inspection](evidence/play-store-0.2.4/packaging.json) and [license delta](evidence/play-store-0.2.4/license-delta.json) passed: bundletool, 16 KB and font checks passed, and the public URL was confirmed in the packaged DEX. Compared with 0.2.3, all 133 dependencies, seven native libraries, both model manifests, permissions and 222 notice assets are unchanged. These checks cover the review artifact; recheck the final production-signed artifact. Do not reuse 0.2.3 validation results for this version. Final production signing, licensing review against the signed artifact, device/Play acceptance and UPI verification remain pending.

## Historical 0.2.3 review build

Version 0.2.3/code 19 corrected the app name to Read Assist and changed the configured recipient to `readassist@ybl`. It retains Settings → Buy me a coffee and the corrected notice/source files from the re-audit, but lacks the configured public privacy URL added in 0.2.4. All existing functionality remains free. The owner will register/change the UPI ID with their provider; registration, ownership and UPI-app acceptance remain unverified.

The [0.2.3 review release](https://github.com/sainadh812/ReadFlow/releases/tag/v0.2.3-play-candidate) contains `Read-Assist-0.2.3-play-preview.apk` (existing prototype certificate), `Read-Assist-0.2.3-play-unsigned.aab` (unsigned), source and SHA-256 checksums. All **335 release JVM tests passed**; release lint passed with zero errors and 28 warnings. Signature, bundle, font and 16 KB checks passed. See the [build record](evidence/play-store-0.2.3/build-results.json), [bundle inspection](evidence/play-store-0.2.3/packaging.json) and [artifact delta review](evidence/play-store-0.2.3/license-delta.json). The test certificate and unsigned AAB are review artifacts; production signing and actual device/UPI verification remain pending.

## Historical 0.2.2 review build

Version 0.2.2/code 18 used the spelling **Read Assit** and a previous recipient. Its artifacts remain `Read-Assit-0.2.2-play-preview.apk` and `Read-Assit-0.2.2-play-unsigned.aab`; do not rename those historical files or imply they contain the 0.2.3 correction. Release/debug passed **335/334 JVM tests**, with no failures/errors/skips. Release lint passed with zero errors and 28 warnings. Preview signature and 16 KB ZIP alignment passed. See the [0.2.2 validation record](evidence/play-store-0.2.2/build-results.json), [exact bundle inspection](evidence/play-store-0.2.2/packaging.json) and [license delta](evidence/play-store-0.2.2/license-delta.json). These results belong to 0.2.2, not 0.2.3. No physical-device or real-payment check ran.

## What the historical 0.2.1 candidate contains

- Three independent reader themes: Warm Paperback, Crisp Paper and Evening Paper. Theme changes preserve the chosen reader font and size. Original PDFs retain their page colours and printed fonts.
- OpenDyslexic, Atkinson Hyperlegible Next, Lexend, Literata, Source Serif 4 and Noto Sans, plus the existing system serif. Files are unmodified; exact source revisions, SHA-256s and copyright/OFL texts are under `app/src/main/assets/licenses/fonts/`.
- A 5–34 reader font size with explicit saving as the user's default. Font changes affect text view; original PDF view uses zoom.
- Right swipe advances, left swipe goes back in both original and text views by default. Direction can be reversed or swipes disabled. Zoomed PDF panning remains separate.
- Release speech uses Kokoro + Misaki only. `src/debug` retains the historical Sherpa/Pocket comparison; do not distribute that variant as the Play release.
- Release model manifests download four Kokoro files directly from an immutable upstream revision, plus the alignment model. Compatible existing files are automatically verified and migrated before playback or speech benchmarking, without another download action or the old eSpeak archive.
- Privacy information and packaged third-party notices are accessible in Settings. The public policy URL must be supplied by the publisher; no fictitious business contact or policy URL was inserted.

## Historical artifacts and future signing

`ReadFlow-0.2.1-play-unsigned.aab` is an **unsigned review artifact**, not an upload-ready bundle. It predates the new display name, support option and added notice/source files. Use the newer reviewed build after owner policy details and production signing are finished.

`ReadFlow-0.2.1-play-preview.apk` uses the release code/model selection but is signed with the existing Android debug certificate solely for prototype-device testing. It can update prototypes signed with that same certificate. It is not a production signing identity. A later Play-signed build normally cannot update a differently signed prototype; plan data preservation and migration with the owner, and never uninstall the prototype automatically.

See [0.2.1 build results and artifact hashes](evidence/play-store-0.2.1/build-results.json), [bundle inspection](evidence/play-store-0.2.1/packaging.json) and the [resolved release dependencies](evidence/play-store-0.2.1/resolved-runtime.json). The previous 0.2.0 validation remains in `evidence/play-store/`. The 0.2.1 regression reproduced the old exception and now passes prototype-to-Play model migration without a Download action. Release/debug passed 332/331 JVM tests respectively, with zero failures/errors/skips; lint had zero errors and 28 warnings each. Bundle, font hashes, preview signature and 16 KB alignment checks passed. No device tests ran.

Keep keystores and secrets outside Git. Supply these through the publisher's secure environment:

- `READFLOW_UPLOAD_STORE_FILE`: upload keystore path.
- `READFLOW_UPLOAD_STORE_PASSWORD`, `READFLOW_UPLOAD_KEY_ALIAS`, `READFLOW_UPLOAD_KEY_PASSWORD`.
- `READFLOW_PRIVACY_POLICY_URL`: optional override for the default `https://sainadh812.github.io/ReadFlow/privacy.html`; use only an approved replacement public HTTPS policy.

Do not paste secret values into commands recorded in chat, build logs, PRs or documentation. The Gradle publication flag checks Firebase client configuration, signing settings and the effective HTTPS policy URL; it does not prove that the key, policy content or completed Console declarations are suitable.

## Reproduce and verify

Use JDK 17, SDK platform 36, build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1 and the checked-in Gradle wrapper. `bootstrap.sh --play` excludes the legacy Sherpa AAR download. Small pinned Misaki assets are prepared automatically; speech/OCR weight downloads are not needed to compile.

```bash
bash scripts/bootstrap.sh --play
python3 scripts/verify_reader_fonts.py
./gradlew :app:bundleRelease :app:testReleaseUnitTest :app:lintRelease --console=plain
```

The default release bundle is unsigned when upload credentials are absent. For publication, configure the secure environment above, then run:

```bash
./gradlew :app:bundleRelease :app:testReleaseUnitTest :app:lintRelease -PplayPublication=true --console=plain
python3 scripts/verify_reader_fonts.py --artifact app/build/outputs/bundle/release/app-release.aab
python3 scripts/verify_play_bundle.py --aab app/build/outputs/bundle/release/app-release.aab
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
```

`verify_play_bundle.py` expects the official bundletool 1.18.3 JAR at `.deps/bundletool-all-1.18.3.jar` (or pass `--bundletool`). It checks target SDK, non-debuggable manifest, 16 KB ELF load alignment and bundle configuration, font presence, exact release model IDs, and absence of known Sherpa/eSpeak binary entry points. It records checksums and explicitly leaves legal and device approval pending. Static absence checks are not a substitute for source/dependency review or a 16 KB device test.

Inspect `app/build/generated/releaseNotices/licenses/dependencies/resolved-runtime.json`, its notices and any supplementary notices in app assets. The inventory is generated from **releaseRuntimeClasspath**, not the prototype dependency graph. After signing, generate/install device APKs with bundletool and confirm zip alignment with build-tools 35 `zipalign -c -P 16 -v 4`.

## Required release validation

There is no connected Android device in this development workspace. Compilation, JVM tests, font-file inspection and bundle inspection do not demonstrate touch behavior, actual font rendering, native speech on a release install, Play-delivered split APKs or sustained phone performance.

On the S25 Ultra and a 16 KB Android environment, check:

1. Fresh install, model downloads and interrupted/resumed setup; speech and OCR work offline after setup. For prototype updates, verify compatible model reuse and retained documents/settings.
2. Each theme/font at 5, a normal reading size and 34; accents (`Milanković’s`, `Köppen`), punctuation, long words and headings render without clipping. Save defaults, reopen the app and change document/theme without resetting them. Check large Android system font scaling and TalkBack.
3. Right/left page swipes, page limits, text scroll, word tap/long-press, PDF pinch/zoom/pan, Original/Text switching and gesture navigation. Original PDF colours and source coordinates stay intact.
4. Misaki pronunciation, speech highlighting, seek/pause/skip/speed, screen-off/notification controls and at least 20 minutes of reading. Benchmark numbers are measurements, not guarantees of gap-free playback.
5. Privacy link, notices, document/report deletion, local exports and the manual email preview/no-email-app fallback. Confirm production offers no GitHub token/upload UI. Inspect network behavior before consent, after opt-in/restart and after opt-out/restart. Verify Firebase fatal/nonfatal/native delivery and matching symbols from the configured release; preserve accurate pending-report/re-enable wording.
6. Settings → Buy me a coffee shows `readassist@ybl`; Copy UPI ID copies that exact address. Verify the intended recipient in the external payment app, amount/currency and cancellation/no-compatible-app handling. Returning to Read Assist must not claim payment success or change access to any feature. Record UPI provider registration and recipient verification separately from UI checks.

## Store/account fields for the publisher

Use free app pricing; complete developer verification, content rating, target audience, app access, Data safety and the media-playback foreground-service declaration. All current features are accessible without a purchase, login or tip; describe that accurately for reviewers. The foreground-service evidence should show reading started by the user, continued background audio and notification controls. Supply real screenshots from the final app, icon/feature graphic and support contact. The prior AI-generated theme concepts are not screenshots of the shipped app. Confirm app name/package availability and avoid unsupported medical or financial-advice claims: Read Assist is a reading tool.

Preserve the separate third-party licenses. The repository's absence of a root app LICENSE does not itself grant a license to all app source; the owner must decide their own application distribution/EULA terms. Do not label the whole app Apache/MIT merely because many dependencies use those licenses. Use the completed re-audit and final dependency changes to establish obligations rather than starting an unbounded new review in Console.

## Primary references checked during preparation

- [Current Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878): API 36 for new phone apps/updates from August 31, 2026. Recheck at upload time.
- [Android 16 KB page-size support](https://developer.android.com/guide/practices/page-sizes): inspect both native ELF alignment and generated APK packaging, and test on a 16 KB environment.
- [Signing and uploading app bundles](https://developer.android.com/studio/publish/upload-bundle): new apps use Play App Signing; the upload key and app-signing key have different roles.
- [Firebase Android data disclosure](https://firebase.google.com/docs/android/play-data-disclosure#crashlytics), [privacy/retention](https://firebase.google.com/support/privacy) and [reporting controls](https://firebase.google.com/docs/crashlytics/android/customize-crash-reports): disclose optional identifiers/diagnostics and test consent transitions; include transitive Installations/Sessions.
- [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and [terms](https://developers.google.com/ml-kit/terms): include SDK diagnostics/usage collection in the assessment, along with the app's actual behavior.
- [Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311): finalize an accessible privacy policy and accurate declarations.
- [New personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465): where applicable, 12 opted-in testers continuously for 14 days before applying for production access.
- [SIL OFL FAQ](https://openfontlicense.org/ofl-faq/): paid-app bundling is permitted with the font's required notices; this does not require relicensing the app under OFL.

The second audit and its exact scope are linked above. Neither these checks nor filling Console forms guarantees Google approval; the final signed build and actual data flows still need the specified validation.
