# Read Assit publishing handoff for Muse Spark

**Status: NOT READY TO PUBLISH. Updated 2026-10-26.** The displayed app name is now **Read Assit** in source. The owner wants a free download with a one-time Pro unlock, and asked to review the feature plan before implementation. Billing, free-tier limits and `READASSIT` redemption are **not implemented**. Do not publish the historical 0.2.1 candidate as this new offer.

Package remains `app.readflow`; repository remains https://github.com/sainadh812/ReadFlow ; working branch `fix/reader-playback-and-pdf-space`. The historical candidate is version `0.2.1`, code `17`, Android 15+ ARM64, target API 36, tag `v0.2.1-play-candidate`. Its downloadable files still say ReadFlow. This document supersedes the paid-download prompt attached to that tag.

Read the [proposed free/Pro split and prices](FREE_AND_PRO_PLAN.md), [completed second license audit](PLAY_REAUDIT_2026-10-26.md) and [prepared privacy/store worksheet](PRIVACY_AND_STORE_DRAFT.md). The owner has not yet accepted the proposed 15-minute allowance or the constrained Google Play coupon mechanism. Do not treat a proposal as shipped behavior.

## Prompt for the publishing agent

> You are Muse Spark, the Play Console publishing assistant for **Read Assit** (spelling exactly Assit), repository `sainadh812/ReadFlow`, package `app.readflow`. Use the current `docs/PLAY_STORE_HANDOFF.md` on branch `fix/reader-playback-and-pdf-space`, not an older release attachment. Read the linked feature plan, license re-audit and privacy/store worksheet first. Your role is to fill the prepared Console/account information and record results; technical implementation and policy conclusions belong to the reviewed repository documents.
>
> **Current gate: NOT READY.** The new commercial offer is a proposal, and no billing-enabled release has been delivered. You may prepare draft Console entries using the instructions below, but do not upload 0.2.1 as the freemium release, submit it for review or publish it. Continue to internal-track upload only after the repository handoff identifies the approved feature plan, implemented billing/coupon flow, final artifact/hash and completed checks. Do not invent a coupon system, contact address, source license, audit clearance or test result to fill a missing field.
>
> Configure the app as a **free download**, with planned non-consumable one-time Pro product `read_assit_pro`: **INR 10 for the India Play market**, **USD 0.99 base outside India**, with reviewed local-currency prices. Confirm that Console accepts these prices and report any exception before choosing a different amount. Do not create a subscription or charge for both download and unlock. `READASSIT` is a proposed campaign alias that distributes unique Play promo codes; it is not a reusable native Play code for a one-time product. Follow the feature-plan limitations, including the 500-code quarterly allowance. Never place codes or service credentials in repository files.
>
> The second audit is recorded in `docs/PLAY_REAUDIT_2026-10-26.md`. Preserve all model/font/runtime notices and MPL source availability. Before the eventual upload, have the technical agent reconcile the **exact final AAB** with that inventory, including newly added Billing dependencies, corrected model-license metadata and absence of Sherpa/eSpeak. An unsigned older bundle's audit does not certify a later one. Model/voice rights rely on the pinned upstream declarations; unresolved distribution questions must remain visible, not be marked passed by assumption.
>
> Use `docs/PRIVACY_AND_STORE_DRAFT.md` to complete privacy, Data safety and listing fields. Obtain only the genuinely missing owner/account facts: legal developer identity, support/privacy contact, public HTTPS policy address, audience/territories and approved application terms. Keep the exact Read Assit name in the app, listing and policy. Account for ML Kit telemetry, model-host connections, optional GitHub uploads and the actual future purchase/promotion service. Do not select “no data collected” merely because inference is local. Draft-only sections must not appear in a public policy.
>
> For the eventual ready release, use the owner's secure upload key and Play App Signing; never use the debug certificate or preview APK for production. Keep package `app.readflow`, assign an unused higher version code, and use the final reviewed commit. Complete internal-track purchases/redemption/restoration, device checks and applicable closed testing before production. Preserve fonts/themes, saved 5–34 sizing, right-swipe next/left-swipe previous, document/model migration and Kokoro + Misaki. Record the exact AAB hash, signing fingerprint, version code, product/local prices, completed declarations, test results and track/tester link. Keep secrets in the owner's secure environment, never in chat or Git. Do not report production publication until Console actually confirms it.

## What the historical 0.2.1 candidate contains

- Three independent reader themes: Warm Paperback, Crisp Paper and Evening Paper. Theme changes preserve the chosen reader font and size. Original PDFs retain their page colours and printed fonts.
- OpenDyslexic, Atkinson Hyperlegible Next, Lexend, Literata, Source Serif 4 and Noto Sans, plus the existing system serif. Files are unmodified; exact source revisions, SHA-256s and copyright/OFL texts are under `app/src/main/assets/licenses/fonts/`.
- A 5–34 reader font size with explicit saving as the user's default. Font changes affect text view; original PDF view uses zoom.
- Right swipe advances, left swipe goes back in both original and text views by default. Direction can be reversed or swipes disabled. Zoomed PDF panning remains separate.
- Release speech uses Kokoro + Misaki only. `src/debug` retains the historical Sherpa/Pocket comparison; do not distribute that variant as the Play release.
- Release model manifests download four Kokoro files directly from an immutable upstream revision, plus the alignment model. Compatible existing files are automatically verified and migrated before playback or speech benchmarking, without another download action or the old eSpeak archive.
- Privacy information and packaged third-party notices are accessible in Settings. The public policy URL must be supplied by the publisher; no fictitious business contact or policy URL was inserted.

## Historical artifacts and future signing

`ReadFlow-0.2.1-play-unsigned.aab` is an **unsigned review artifact**, not an upload-ready bundle. It has neither the new display name nor monetization. Build a new version from the reviewed implementation after the feature plan, billing, notices and policy details are finished.

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

Use free app pricing and the proposed Pro regional prices once approved; complete developer/merchant verification, content rating, target audience, app access, Data safety and the media-playback foreground-service declaration. For reviewer access, document a working path to restricted features. The foreground-service evidence should show reading started by the user, continued background audio and notification controls. Supply real screenshots from the final app, icon/feature graphic and support contact. The prior AI-generated theme concepts are not screenshots of the shipped app. Confirm app name/package availability and avoid unsupported medical or financial-advice claims: Read Assit is a reading tool.

Preserve the separate third-party licenses. The repository's absence of a root app LICENSE does not itself grant a license to all app source; the owner must decide their own application distribution/EULA terms. Do not label the whole app Apache/MIT merely because many dependencies use those licenses. Use the completed re-audit and final dependency changes to establish obligations rather than starting an unbounded new review in Console.

## Primary references checked during preparation

- [Current Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878): API 36 for new phone apps/updates from August 31, 2026. Recheck at upload time.
- [Android 16 KB page-size support](https://developer.android.com/guide/practices/page-sizes): inspect both native ELF alignment and generated APK packaging, and test on a 16 KB environment.
- [Signing and uploading app bundles](https://developer.android.com/studio/publish/upload-bundle): new apps use Play App Signing; the upload key and app-signing key have different roles.
- [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and [terms](https://developers.google.com/ml-kit/terms): include SDK diagnostics/usage collection in the assessment, along with the app's actual behavior.
- [Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311): finalize an accessible privacy policy and accurate declarations.
- [New personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465): where applicable, 12 opted-in testers continuously for 14 days before applying for production access.
- [SIL OFL FAQ](https://openfontlicense.org/ofl-faq/): paid-app bundling is permitted with the font's required notices; this does not require relicensing the app under OFL.

The second audit and its exact scope are linked above. Neither these checks nor filling Console forms guarantees Google approval; the final commercial build and actual data flows still need the specified validation.
