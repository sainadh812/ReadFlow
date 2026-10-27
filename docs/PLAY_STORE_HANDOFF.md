# Read Assist publishing handoff for Muse Spark

**FREE DISTRIBUTION FIRST. Updated 2026-10-27.** All existing features remain free. Paid tiers, INR 10 / USD 0.99 prices, quotas and coupons are deferred. Optional **Buy me a coffee** support to `readassist@ybl` grants no benefit. Package `app.readflow` and repository `sainadh812/ReadFlow` remain unchanged; work from branch `fix/reader-playback-and-pdf-space`.

The current source is **Read Assist 0.2.5, versionCode 21**, Android 15+ ARM64, target API 36. It adds optional Firebase Crashlytics and a previewed support-email flow; production hides GitHub upload/token entry. **Muse Spark reports that the Firebase project is created. The Firebase config secret is stored under the earlier spelling `READASSIT_FIREBASE_CONFIG_BASE64`, which the workflow accepts. Its contents and real-device report delivery remain unverified. The new upload key and four GitHub signing secrets are prepared.** Do not treat historical 0.2.4 tests or artifacts as validation of this build.

Public developer display name: **Oh My Pi**. Support/privacy contact: **xperimenting.ai@gmail.com**. Policy URL: **https://sainadh812.github.io/ReadFlow/privacy.html**. The **27 October 2026 policy revision is live and verified** with HTTP 200 and exact matching bytes; it covers optional Crashlytics and manual support email. Check [publication status](PRIVACY_PUBLICATION.md), [Data safety worksheet](PRIVACY_AND_STORE_DRAFT.md), [free distribution plan](FREE_DISTRIBUTION_PLAN.md) and [license re-audit](PLAY_REAUDIT_2026-10-26.md).

## Prompt for the publishing agent

Copy the quoted prompt into Muse Spark. This [current handoff](https://github.com/sainadh812/ReadFlow/blob/fix/reader-playback-and-pdf-space/docs/PLAY_STORE_HANDOFF.md) supersedes release attachments and earlier paid/free-Pro prompts.

> You are Muse Spark, the publishing assistant for **Read Assist** (double “s”), package `app.readflow`, repository `sainadh812/ReadFlow`. Work from the current `fix/reader-playback-and-pdf-space` branch and this handoff. The candidate is **0.2.5/code 21**. Record the exact final commit and evidence; never transfer test results from an older artifact. Use **Oh My Pi** as the approved public developer display name and **xperimenting.ai@gmail.com** for public support/privacy contact. Preserve existing app data and model identities.
>
> Use the **existing** Firebase Spark project **Read Assist**, ID `read-assist-fbd21`, Android package `app.readflow`, Firebase app ID `1:831366793321:android:573b80e46b55eb65103af8`. Muse Spark reports project creation, Spark/no-cost plan, Analytics disabled and the client JSON downloaded. Do not create another project or regenerate the upload key. Four upload-signing secrets are already stored in `sainadh812/ReadFlow`; the public certificate/fingerprint are in `docs/evidence/play-store-upload/`. The config is already stored as **`READASSIT_FIREBASE_CONFIG_BASE64`** (earlier spelling), and the workflow accepts it as a fallback for **`READASSIST_FIREBASE_CONFIG_BASE64`**. No repeat transfer is needed. Do not submit the pending New secret form. The first manual build must validate the stored config against the exact project and app identifiers. Do not paste its contents in chat, commit it, overwrite the signing secrets, create a service-account key, enable Analytics or upgrade to Blaze. See `docs/GITHUB_RELEASE_BUILD.md` for the manual workflow and verification steps.
>
> Reporting is **off by default** and Firebase initializes only at startup with valid configuration and saved consent. Full enable/disable takes effect on the next app launch; our handled-error reports stop immediately on opt-out, while queued work may finish and stored reports may send after re-enabling. Test fresh install/off, consent/on/restart, opt-out/restart and re-enable with pending reports. Verify real sanitized nonfatal, Java fatal, ANR where practical and native-crash delivery in the correct Firebase project after restarting the test app. Use a dedicated test installation and development-only test triggers; do not crash the owner's reading session or expose forced-crash controls in production. Upload matching native symbols for the exact release, follow `docs/NATIVE_CRASH_SYMBOLS.md`, and document any remaining vendor-library or Go unwinding/symbolication limits. A build pass or a visible switch does not prove delivery. Unconfigured review builds cannot send Crashlytics reports and are not this feature's production-ready package.
>
> Configure a **free download with all existing features available**, no subscriptions, quotas, coupon campaign or paid products. The optional coffee feature opens an external UPI app or copies `readassist@ybl`; it does not unlock perks, track transactions or verify success. Confirm the owner registered this address and verify the bank-resolved recipient before shipping. Check the direct creator-tip conditions; do not invent merchant details, claim worldwide card payments/tax-deductible donations, make a transfer, or require payment for reviewers.
>
> Use **https://sainadh812.github.io/ReadFlow/privacy.html** for the public policy. The current `site/privacy.html` revision was published and verified on 27 October 2026; recheck the live URL against the final source using `docs/PRIVACY_PUBLICATION.md` before distribution. The source covers optional Crashlytics identifiers/crash logs/diagnostics, automatic/native runtime-data limitations, manually sent support email, ML Kit, model hosts, local exports and external UPI. Do not say “no data collected” because inference is local. Use `docs/PRIVACY_AND_STORE_DRAFT.md` to complete Data safety from the final artifact: optional Firebase crash logs, diagnostics and device/other IDs, plus any applicable session/performance categories; account for manual email/content the user chooses to send, ML Kit and payment-provider handling. No Google Analytics or app user ID is added. Developer support correspondence/reports are deleted within 90 days after resolution; Firebase's separate retention and payment-record rules are described in the policy. Production GitHub upload/token entry is unavailable; do not ask customers for GitHub credentials.
>
> Audit licensing again for the exact final signed AAB, including newly added Firebase/transport dependencies and native binaries. Use `docs/PLAY_REAUDIT_2026-10-26.md` as the baseline, not blanket clearance. Preserve notices, font licenses, MPL sources/offers and the eSpeak/Sherpa-free release. Check model/runtime inventories and hashes; do not change serialized model IDs casually. Record unresolved findings for technical review.
>
> Build with the owner's secure upload key and use Play App Signing, never the prototype/debug certificate. Use versionCode 21 only if unused in Console, otherwise choose a higher unused code. Keep configuration, keys and passwords outside Git/chat. Complete release tests/lint, notices/license inventory, 16 KB/native/font/bundle checks, current public-policy verification, Firebase symbol upload and real-device crash-delivery checks. Repeat final checks after configuration or signing changes. Prior unsigned AABs and prototype-signed APKs are review artifacts, not production uploads.
>
> Complete developer/account verification, approved application terms, audience/territories, content rating, app access, Data safety and media-playback foreground-service declarations from verified facts. Use real final-app screenshots and a service-use video. Test model setup, retained documents on updates, OCR/speech/offline reading, sustained playback, swipes, themes/fonts, notification controls, privacy/deletion, the email preview/no-email-app fallback, diagnostics consent and UPI cancellation/no-handler behavior on the S25 Ultra and a 16 KB environment. Complete applicable internal/closed testing. Do not publish with required checks or owner facts unresolved. Report final version, commit, AAB hash, certificate fingerprint, Firebase app/project identifiers (not secrets), delivery/symbol evidence and actual track/tester URL. Claim publication only when Console confirms it.

## Firebase setup and release checks

Muse Spark reports that the owner Firebase project and client configuration exist in its workspace. The file is absent from this coding workspace, but the repository secret `READASSIT_FIREBASE_CONFIG_BASE64` exists. The workflow accepts that earlier spelling; its value has not yet been validated in a configured build. The new upload key and four signing secrets are already stored in GitHub. Ordinary review builds without the config remain usable, with automatic reporting unavailable. Publication builds using `-PplayPublication=true` require a valid owner-supplied `app/google-services.json`, secure signing and the policy URL. The app's Gradle check accepts the exact `app.readflow` client and rejects source-set overrides. It does not verify project ownership, Console enablement or delivery.

After verifying the configured signed release, explicitly run:

```bash
./gradlew :app:uploadCrashlyticsSymbolFileRelease --console=plain
```

Keep the exact build outputs and GNU build IDs. The Misaki build now retains GNU build ID, full DWARF and symbol-table information in its generated unstripped library, validated by the preparation script. Verify the final packaged copy has the same GNU build ID; retain that exact original for upload. See [native crash symbols](NATIVE_CRASH_SYMBOLS.md). The upload task searches merged native libraries; pre-stripped third-party libraries still require matching supplier symbols for complete frames. Real-device native/Go stack unwinding and symbolication remain to be demonstrated; successful symbol generation/upload alone does not prove complete coverage. Confirm mapping requirements if minification is enabled later. [Firebase NDK setup](https://firebase.google.com/docs/crashlytics/android/get-started-ndk).

The new consent/network flow requires device tests. No claim of Firebase project creation, successful upload, production signing or Play approval follows from local compilation. Record the 0.2.5 artifact's own validation evidence when those checks complete.

## Current 0.2.5 review artifacts

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
