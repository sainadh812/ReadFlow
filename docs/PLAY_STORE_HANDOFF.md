# ReadFlow 0.2.0: Play release candidate and publishing handoff

This is a prepared release candidate, not a published or legally approved store release. Package `app.readflow`, version name `0.2.0`, version code `16`, Android 15+ ARM64, target API 36. Repository: https://github.com/sainadh812/ReadFlow ; working branch `fix/reader-playback-and-pdf-space`. Candidate tag: `v0.2.0-play-candidate`.

## Prompt for the publishing agent

> Finish publishing ReadFlow as a paid Google Play app from `sainadh812/ReadFlow`, tag `v0.2.0-play-candidate`. Read `docs/PLAY_STORE_HANDOFF.md`, `docs/THIRD_PARTY_NOTICES.md`, the release model provenance and `docs/evidence/play-store/` before proceeding. Preserve the implemented Warm Paperback, Crisp Paper and Evening Paper themes, six optional bundled fonts, the user's saved 5–34 font size, right-swipe next/left-swipe previous behavior, Kokoro + Misaki speech and current OCR choices.
>
> **Audit licensing again against the exact final AAB and every downloaded model, font and transitive dependency.** Verify commercial redistribution rights, copyright notices, required license texts, voice/model provenance and any copyleft or noncommercial restrictions. Confirm the Play artifact has no Sherpa/eSpeak runtime or downloaded eSpeak data. Check OpenDyslexic's exact OFL release and all other font licenses. Inspect the complete resolved dependency inventory and reconcile packages without embedded notices; do not assume that changing the selected speech engine or having a public GitHub repo clears licensing. Record the findings and unresolved issues. Do not publish with unresolved distribution restrictions.
>
> Finalize the developer's real identity/contact, privacy policy, Data safety disclosures, app code licensing/EULA, store listing and price/countries with the owner where these values are missing. Account for ML Kit SDK telemetry, model-host connections and optional user-confirmed GitHub diagnostics. Do not claim that the app collects no data merely because OCR and speech run locally. No ads, subscription or in-app purchase implementation was added; use the owner's chosen paid-app price in Play Console.
>
> Build and sign a fresh **release AAB** using the owner's secure upload key and Play App Signing. Never publish the preview APK or use the Android debug key for production. Verify package/version availability in Play Console; increase versionCode if 16 is already used. Test the actual release build on the S25 Ultra and through an internal Play track, review the pre-launch report and complete any account-specific closed-testing requirements before production. Check swipes, all font/theme combinations, font size persistence, downloads/resume, OCR, word highlights, sustained speech, notification controls and offline reading after setup. Run the technical/license checks below against the final signed artifact. Complete the necessary Play declarations and publish when the checks and account requirements are satisfied. Report the final commit, AAB hash, signing certificate fingerprint, track, version code, audit results and store/tester link. Ask the owner only for missing account/business details or access; never request passwords or private keys in chat.

## What the candidate contains

- Three independent reader themes: Warm Paperback, Crisp Paper and Evening Paper. Theme changes preserve the chosen reader font and size. Original PDFs retain their page colours and printed fonts.
- OpenDyslexic, Atkinson Hyperlegible Next, Lexend, Literata, Source Serif 4 and Noto Sans, plus the existing system serif. Files are unmodified; exact source revisions, SHA-256s and copyright/OFL texts are under `app/src/main/assets/licenses/fonts/`.
- A 5–34 reader font size with explicit saving as the user's default. Font changes affect text view; original PDF view uses zoom.
- Right swipe advances, left swipe goes back in both original and text views by default. Direction can be reversed or swipes disabled. Zoomed PDF panning remains separate.
- Release speech uses Kokoro + Misaki only. `src/debug` retains the historical Sherpa/Pocket comparison; do not distribute that variant as the Play release.
- Release model manifests download four Kokoro files directly from an immutable upstream revision, plus the alignment model. Compatible existing files can be verified and reused during setup without downloading the old eSpeak archive.
- Privacy information and packaged third-party notices are accessible in Settings. The public policy URL must be supplied by the publisher; no fictitious business contact or policy URL was inserted.

## Artifacts and signing

`ReadFlow-0.2.0-play-unsigned.aab` is an **unsigned review artifact**, not an upload-ready bundle. Rebuild with the real upload credentials after finishing the policy/contact details.

`ReadFlow-0.2.0-play-preview.apk` uses the release code/model selection but is signed with the existing Android debug certificate solely for prototype-device testing. It can update prototypes signed with that same certificate. It is not a production signing identity. A later Play-signed build normally cannot update a differently signed prototype; plan data preservation and migration with the owner, and never uninstall the prototype automatically.

The candidate artifacts were built from implementation commit `60a2789f826d808ea8392f7a0b346ae242da1f4c`. Later candidate commits contain verification and delivery records only. See [build results and artifact hashes](evidence/play-store/build-results.json), [bundle inspection](evidence/play-store/packaging.json) and [resolved release dependencies](evidence/play-store/resolved-runtime.json). Both variants passed 327 JVM tests each and lint with zero errors (28 warnings each). Final AAB/APK font hashes, APK signature and 16 KB zip alignment passed. No device tests ran.

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

## Store/account work still owned by the publisher

Choose the actual paid-app price and territories; complete developer/merchant verification, content rating, target audience, app access, Data safety and the media-playback foreground-service declaration. Supply real screenshots from the final app, icon/feature graphic and support contact. The prior AI-generated theme concepts are not screenshots of the shipped app. Confirm app name/package availability and no unsupported medical or financial-advice claims in the listing. ReadFlow is a reading tool.

Audit the exact models and voices as well as code. Preserve the font license separately from the application license. The repository's absence of a root app LICENSE does not itself grant a license to all app source; the owner must decide their distribution/EULA terms. Do not label the whole app Apache/MIT merely because many dependencies use those licenses.

## Primary references checked during preparation

- [Current Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878): API 36 for new phone apps/updates from August 31, 2026. Recheck at upload time.
- [Android 16 KB page-size support](https://developer.android.com/guide/practices/page-sizes): inspect both native ELF alignment and generated APK packaging, and test on a 16 KB environment.
- [Signing and uploading app bundles](https://developer.android.com/studio/publish/upload-bundle): new apps use Play App Signing; the upload key and app-signing key have different roles.
- [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and [terms](https://developers.google.com/ml-kit/terms): include SDK diagnostics/usage collection in the assessment, along with the app's actual behavior.
- [Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311): finalize an accessible privacy policy and accurate declarations.
- [New personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465): where applicable, 12 opted-in testers continuously for 14 days before applying for production access.
- [SIL OFL FAQ](https://openfontlicense.org/ofl-faq/): paid-app bundling is permitted with the font's required notices; this does not require relicensing the app under OFL.

These references and technical checks are inputs to the requested second audit, not a guarantee of Google approval or a legal opinion.
