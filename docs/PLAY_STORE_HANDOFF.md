# Read Assist publishing handoff for Muse Spark

**Current plan: FREE DISTRIBUTION FIRST. Updated 2026-10-26.** All existing features remain free. Paid tiers, INR 10 / USD 0.99 pricing, daily speech limits and the earlier coupon proposal are deferred. Do not create Pro products or wait for Billing implementation. Optional **Buy me a coffee** support uses the owner-supplied UPI ID `readassist@ybl` and grants no digital benefit.

Package remains `app.readflow`; repository remains https://github.com/sainadh812/ReadFlow ; working branch `fix/reader-playback-and-pdf-space`. The current candidate source is **Read Assist 0.2.4**, code **20**, Android 15+ ARM64, target API 36; its build, all 335 release JVM tests, release lint, preview signature and APK 16 KB ZIP alignment checks passed. Review artifacts are linked below; final signed-artifact/device/Play validation remains required. Version 0.2.4 adds the verified public privacy URL to the app. Historical 0.2.3 review artifacts have the corrected name/UPI address but lack this configured URL; 0.2.2 artifacts retain their original Read Assit spelling and prior recipient, and 0.2.1 files still display ReadFlow. This document supersedes both the paid-download and free/Pro publishing prompts.

**Confirmed public details:** developer display name **Oh My Pi**; support/privacy contact **xperimenting.ai@gmail.com**; [live Privacy Policy](https://sainadh812.github.io/ReadFlow/privacy.html), effective **2026-10-26**. The policy was published and its exact bytes verified over HTTP 200. Developer-received support correspondence/reports are deleted within 90 days after resolution; payment records are retained only as needed for accounting/legal requirements. See [publication evidence and maintenance](PRIVACY_PUBLICATION.md).

**Production remains pending** legal/account verification, audience/territories, approved application terms, final artifact review, secure production signing and phone/Play acceptance. The public developer display name does not establish the verified legal account identity. The owner will register/change `readassist@ybl` with their UPI provider. Source/build validation does not register this ID or verify its ownership; both remain unconfirmed. Read the [free distribution plan](FREE_DISTRIBUTION_PLAN.md), [license re-audit](PLAY_REAUDIT_2026-10-26.md) and [privacy/store worksheet](PRIVACY_AND_STORE_DRAFT.md).

## Prompt for the publishing agent

Copy the entire quoted prompt below into Muse Spark. The [live GitHub handoff](https://github.com/sainadh812/ReadFlow/blob/fix/reader-playback-and-pdf-space/docs/PLAY_STORE_HANDOFF.md) is authoritative for publishing instructions; release source archives preserve the app source as built.

> You are Muse Spark, the Play Console publishing assistant for **Read Assist** (Assist has a double “s”; use this spelling everywhere), repository `sainadh812/ReadFlow`, package `app.readflow`. Read the [current handoff on branch `fix/reader-playback-and-pdf-space`](https://github.com/sainadh812/ReadFlow/blob/fix/reader-playback-and-pdf-space/docs/PLAY_STORE_HANDOFF.md), not an older release attachment. Your role is to enter the prepared Console/account information and record actual results. Technical implementation and policy conclusions are documented in this repository.
>
> Start from the **0.2.4 candidate source, versionCode 20**, on the branch above. Use [release v0.2.4-play-candidate](https://github.com/sainadh812/ReadFlow/releases/tag/v0.2.4-play-candidate) and `docs/evidence/play-store-0.2.4/build-results.json`; the build and 335 release JVM tests passed, release lint has zero errors and 28 warnings, and preview-signature/APK 16 KB ZIP alignment checks passed. Record the exact final commit and final artifact checks. Do not substitute the historical 0.2.3 APK/AAB, which lacks the configured public privacy URL. Use **Read Assist** for the Play listing, screenshots and support text; use **Oh My Pi** as the owner-approved public developer display name. Preserve package `app.readflow`, model identifiers and existing user data. The configured support recipient is exactly **`readassist@ybl`**. Earlier spelling, UPI addresses, prices and coupon proposals are superseded.
>
> Configure the app as a **free download with all existing features available**. Do not create paid tiers, daily quotas, subscriptions, coupon campaigns, Pro products or a paid download price. Those ideas were deferred by the owner. Do not treat the old paid-plan implementation gate as a blocker for this free release.
>
> The app offers optional **Buy me a coffee** support to `readassist@ybl`. It opens a UPI app or copies the ID, grants no perks, and does not track or confirm transactions. The owner will register/change this new ID with their provider. Before publication, verify that the supplied ID has been registered to the intended developer and that the selected payment route satisfies the direct creator-tip conditions in the distribution plan. Do not invent a merchant ID or bank identity, claim a payment succeeded, or require a tip for reviewer access. The app's reading/OCR/speech features work without any contribution. Do not claim worldwide card payments or tax-deductible donations.
>
> Enter **xperimenting.ai@gmail.com** as the approved public support/privacy email and **https://sainadh812.github.io/ReadFlow/privacy.html** as the Privacy Policy URL. Do not publish a personal profile email in their place. The policy is already live, effective 2026-10-26; do not recreate it from an obsolete draft. Its canonical source is `site/privacy.html`, with publication evidence in `docs/PRIVACY_PUBLICATION.md`. Reports/support correspondence received by the developer are deleted within 90 days after resolution; payment records are retained only as needed for accounting/legal requirements. Use `docs/PRIVACY_AND_STORE_DRAFT.md` for the remaining Data safety/store worksheet. Obtain only genuinely missing facts: verified legal/account identity, audience/territories, actual recipient/provider details and approved application terms. Account for ML Kit metrics, model-host connections, optional GitHub reports and the external UPI flow. Do not select “no data collected” merely because inference runs locally.
>
> Recheck licensing before submission using `docs/PLAY_REAUDIT_2026-10-26.md`, the historical 0.2.3 artifact delta in `docs/evidence/play-store-0.2.3/license-delta.json`, and the 0.2.4 artifact review and exact final signed-artifact evidence. Cover the shipped models, phonemizer, OCR engines, fonts and runtime dependencies. Preserve their notices, bundled MPL source and source offers. Use the exact final signed AAB's dependency/model inventory and hashes; do not claim an older artifact's audit certifies a later build. Keep the release eSpeak/Sherpa-free and retain model compatibility. Read the TeleOCR label erratum; do not change a serialized model manifest without migration handling. Record unresolved license questions for technical review instead of declaring blanket clearance.
>
> The 0.2.4 build record establishes 335 passing release JVM tests, release lint with zero errors and 28 warnings, and successful preview-signature/APK 16 KB ZIP alignment checks. The separate bundletool/16 KB/font checks and artifact delta review also passed, including confirmation of the policy URL in the packaged app. Read their recorded scope; no host result establishes phone acceptance. Historical 0.2.3 evidence remains tied to that older artifact. `Read-Assist-0.2.4-play-preview.apk` is prototype-signed and `Read-Assist-0.2.4-play-unsigned.aab` is unsigned; neither is the production upload artifact. Keep completed public-policy publication and current host checks separate from pending legal/account verification, UPI registration/recipient verification, production signing and device/Play checks.
>
> Use the owner's secure upload key and Play App Signing for production, never the debug certificate or preview APK. Keep package `app.readflow`; use versionCode 20 only if available in Console, otherwise assign a higher unused code. The public privacy URL is the default in 0.2.4; `READFLOW_PRIVACY_POLICY_URL` is an optional override, not an unresolved owner fact. Complete the final build checks, S25 Ultra/internal-track tests and any applicable closed-testing requirement before production. Check updates retain documents/models, swipes, reader fonts/themes, sustained speech, offline use, notification controls, the public privacy link and the optional support flow. All app features must remain available without tipping.
>
> Complete developer verification, audience/content rating, app access, Data safety and media-playback foreground-service declarations using verified facts. Supply actual final-app screenshots and a real service-use video. Do not publish while owner placeholders or required artifact/device checks remain. Report the final commit, AAB hash, certificate fingerprint, version, completed checks and track/tester URL. Never request or paste private keys, PINs or credentials in chat/Git; use the owner's secure account environment. Do not claim publication until Console confirms it.

## Read Assist 0.2.4 review candidate

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

Do not paste secret values into commands recorded in chat, build logs, PRs or documentation. The Gradle publication flag checks signing settings and the effective HTTPS policy URL; it does not prove that the key, policy content or completed Console declarations are suitable.

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
5. Privacy link, notices, document/report deletion, optional report export/GitHub confirmation and network behavior. Run a network inspection for the SDK disclosures used in Data safety.
6. Settings → Buy me a coffee shows `readassist@ybl`; Copy UPI ID copies that exact address. Verify the intended recipient in the external payment app, amount/currency and cancellation/no-compatible-app handling. Returning to Read Assist must not claim payment success or change access to any feature. Record UPI provider registration and recipient verification separately from UI checks.

## Store/account fields for the publisher

Use free app pricing; complete developer verification, content rating, target audience, app access, Data safety and the media-playback foreground-service declaration. All current features are accessible without a purchase, login or tip; describe that accurately for reviewers. The foreground-service evidence should show reading started by the user, continued background audio and notification controls. Supply real screenshots from the final app, icon/feature graphic and support contact. The prior AI-generated theme concepts are not screenshots of the shipped app. Confirm app name/package availability and avoid unsupported medical or financial-advice claims: Read Assist is a reading tool.

Preserve the separate third-party licenses. The repository's absence of a root app LICENSE does not itself grant a license to all app source; the owner must decide their own application distribution/EULA terms. Do not label the whole app Apache/MIT merely because many dependencies use those licenses. Use the completed re-audit and final dependency changes to establish obligations rather than starting an unbounded new review in Console.

## Primary references checked during preparation

- [Current Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878): API 36 for new phone apps/updates from August 31, 2026. Recheck at upload time.
- [Android 16 KB page-size support](https://developer.android.com/guide/practices/page-sizes): inspect both native ELF alignment and generated APK packaging, and test on a 16 KB environment.
- [Signing and uploading app bundles](https://developer.android.com/studio/publish/upload-bundle): new apps use Play App Signing; the upload key and app-signing key have different roles.
- [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and [terms](https://developers.google.com/ml-kit/terms): include SDK diagnostics/usage collection in the assessment, along with the app's actual behavior.
- [Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311): finalize an accessible privacy policy and accurate declarations.
- [New personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465): where applicable, 12 opted-in testers continuously for 14 days before applying for production access.
- [SIL OFL FAQ](https://openfontlicense.org/ofl-faq/): paid-app bundling is permitted with the font's required notices; this does not require relicensing the app under OFL.

The second audit and its exact scope are linked above. Neither these checks nor filling Console forms guarantees Google approval; the final signed build and actual data flows still need the specified validation.
