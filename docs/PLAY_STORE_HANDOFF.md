# Read Assit publishing handoff for Muse Spark

**Current plan: FREE DISTRIBUTION FIRST. Updated 2026-10-26.** All existing features remain free. Paid tiers, INR 10 / USD 0.99 pricing, daily speech limits and `READASSIT` coupons are deferred. Do not create Pro products or wait for Billing implementation. Optional **Buy me a coffee** support uses the owner-supplied UPI ID `readassit@ybl` and grants no digital benefit.

Package remains `app.readflow`; repository remains https://github.com/sainadh812/ReadFlow ; working branch `fix/reader-playback-and-pdf-space`. The review build is **Read Assit 0.2.2**, code **18**, Android 15+ ARM64, target API 36. Historical 0.2.1 files still display ReadFlow. This document supersedes both the paid-download and free/Pro publishing prompts.

**Production remains pending** real developer/contact/public-policy information, secure production signing and phone/Play acceptance. Source/build validation does not register or verify the supplied UPI ID. Read the [free distribution plan](FREE_DISTRIBUTION_PLAN.md), [license re-audit](PLAY_REAUDIT_2026-10-26.md) and [privacy/store worksheet](PRIVACY_AND_STORE_DRAFT.md).

## Prompt for the publishing agent

> You are Muse Spark, the Play Console publishing assistant for **Read Assit** (exact spelling), repository `sainadh812/ReadFlow`, package `app.readflow`. Use this current handoff on branch `fix/reader-playback-and-pdf-space`, not an older release attachment. Your role is to enter the prepared Console/account information and record actual results. Technical implementation and policy conclusions are documented in this repository.
>
> Configure the app as a **free download with all existing features available**. Do not create paid tiers, daily quotas, subscriptions, coupon campaigns, Pro products or a paid download price. Those ideas were deferred by the owner. Do not treat the old paid-plan implementation gate as a blocker for this free release.
>
> The app offers optional **Buy me a coffee** support to `readassit@ybl`. It opens a UPI app or copies the ID, grants no perks, and does not track or confirm transactions. Before publication, verify that the supplied ID is registered to the intended developer and that the selected payment route satisfies the direct creator-tip conditions in the distribution plan. Do not invent a merchant ID or bank identity, claim a payment succeeded, or require a tip for reviewer access. The app's reading/OCR/speech features work without any contribution. Do not claim worldwide card payments or tax-deductible donations.
>
> Use `docs/PRIVACY_AND_STORE_DRAFT.md` to complete the policy, Data safety and store listing. Obtain the genuinely missing owner facts: legal developer identity, support/privacy contact, public HTTPS policy address, audience/territories and approved application terms. Account for ML Kit metrics, model-host connections, optional GitHub reports and the external UPI flow. Do not select “no data collected” merely because inference runs locally. Remove draft-only instructions/placeholders before publishing the policy.
>
> The second license audit is in `docs/PLAY_REAUDIT_2026-10-26.md`. Preserve model/font/runtime notices, bundled MPL source and source offers. Use the exact final signed AAB's dependency/model inventory and hashes; do not claim an older artifact's audit certifies a later build. Keep the release eSpeak/Sherpa-free and retain model compatibility. Read the TeleOCR label erratum; do not change a serialized model manifest without migration handling.
>
> Use the owner's secure upload key and Play App Signing for production, never the debug certificate or preview APK. Keep package `app.readflow`; use versionCode 18 only if available in Console, otherwise assign a higher unused code. Complete the final build checks, S25 Ultra/internal-track tests and any applicable closed-testing requirement before production. Check updates retain documents/models, swipes, reader fonts/themes, sustained speech, offline use, notification controls and the optional support flow. All app features must remain available without tipping.
>
> Complete developer verification, audience/content rating, app access, Data safety and media-playback foreground-service declarations using verified facts. Supply actual final-app screenshots and a real service-use video. Do not publish while owner placeholders or required artifact/device checks remain. Report the final commit, AAB hash, certificate fingerprint, version, completed checks and track/tester URL. Never request or paste private keys, PINs or credentials in chat/Git; use the owner's secure account environment. Do not claim publication until Console confirms it.

## Read Assit 0.2.2 review build

Version 0.2.2/code 18 adds the Read Assit display name, optional Settings → Buy me a coffee, and the corrected notice/source files from the re-audit. All existing functionality is free. The recipient is `readassit@ybl`; actual registration, identity and UPI-app acceptance require owner/device verification.

The GitHub review release contains `Read-Assit-0.2.2-play-preview.apk` (existing prototype certificate) and `Read-Assit-0.2.2-play-unsigned.aab` (unsigned). These are for review, not a published Play installation. Source and evidence accompany the release. Release/debug passed **335/334 JVM tests**, with no failures/errors/skips. Release lint passed with zero errors and 28 warnings. Preview signature and 16 KB ZIP alignment passed. See the [0.2.2 validation record](evidence/play-store-0.2.2/build-results.json), [exact bundle inspection](evidence/play-store-0.2.2/packaging.json) and [license delta](evidence/play-store-0.2.2/license-delta.json). No physical-device or real-payment check ran.

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
- `READFLOW_PRIVACY_POLICY_URL`: the real, public HTTPS policy URL.

Do not paste secret values into commands recorded in chat, build logs, PRs or documentation. The Gradle publication flag checks that all required environment settings exist; it does not prove that the policy or key is suitable.

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

## Store/account fields for the publisher

Use free app pricing; complete developer verification, content rating, target audience, app access, Data safety and the media-playback foreground-service declaration. All current features are accessible without a purchase, login or tip; describe that accurately for reviewers. The foreground-service evidence should show reading started by the user, continued background audio and notification controls. Supply real screenshots from the final app, icon/feature graphic and support contact. The prior AI-generated theme concepts are not screenshots of the shipped app. Confirm app name/package availability and avoid unsupported medical or financial-advice claims: Read Assit is a reading tool.

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
