# Read Assist 0.3.1 — Google Play upload handoff

Prepared 15 November 2026 for the owner's request to push the latest app to Google Play. Use the verified **0.3.1 / versionCode 27** bundle below. This handoff records the available artifact and prior Console evidence; it does not establish a successful Play upload, rollout or production publication.

## Current access status

The owner selected **Internal testing**. This session has no connected Play publisher plugin or interactive browser tools, no authenticated `gcloud` account or Application Default Credentials, and no discovered Play publishing credential in environment variables or GitHub secrets. The discovered repository secrets support signing and Firebase configuration. The owner reports that publishing credentials exist securely, but their accessible location has not been provided. No Play upload has been performed from this session.

At the owner's request, the existing Internal testing URL was opened. The HTTP request redirected to `accounts.google.com/v3/signin/identifier`; the authenticated app was not visible. The web reader also could not access the Console page. TinyFish was found as an available browser integration and suggested, but installation, connection and a signed-in Play profile have not been confirmed. Either working publisher API credentials or an authenticated browser connection is needed to continue.

API publishing requires an authorized OAuth client or service account with suitable Play Console permissions; bundle upload uses the `https://www.googleapis.com/auth/androidpublisher` scope. See Google's [API setup guide](https://developers.google.com/android-publisher/getting_started) and [bundle upload authorization](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.bundles/upload). Once access is available, inspect the live release state and follow [Google's release preparation and rollout procedure](https://support.google.com/googleplay/android-developer/answer/9859348?hl=en) for the selected Internal testing track.

## Exact bundle

| Field | Value |
| --- | --- |
| App / package | Read Assist / `app.readflow` |
| Version | `0.3.1` / code `27` |
| AAB | [Read-Assist-0.3.1-upload-signed.aab](https://github.com/sainadh812/ReadFlow/releases/download/v0.3.1-play-candidate/Read-Assist-0.3.1-upload-signed.aab) |
| Workspace path | `build/release-0.3.1/signed-ci/Read-Assist-0.3.1-upload-signed.aab` |
| Size | `72,436,099` bytes |
| SHA-256 | `a247863362aec730e6c50ebe85a403de8cb572821276077d38a3c0b2f8e7e1f6` |
| Source commit | `94006546634355658abfba7ce1a7a442d8825828` |
| Signed build | [GitHub Actions run 43736870521](https://github.com/sainadh812/ReadFlow/actions/runs/43736870521) |
| GitHub release | [v0.3.1-play-candidate](https://github.com/sainadh812/ReadFlow/releases/tag/v0.3.1-play-candidate) |
| Upload certificate SHA-256 | `b8e3cdfd71bc98427f17921e5b2c0e97a8543cc90a78b066afae7bfab6fb29a2` |
| Device support | Android 15+ / ARM64; min SDK 35, target SDK 36 |

The local bundle's size and SHA-256 were checked again while preparing this handoff and match the published release evidence. Upload the AAB, not the APK. Keep the existing upload key and Google-managed Play App Signing; the upload certificate and Play's app-signing certificate have different roles. No rebuild or new signing key is needed to obtain this candidate.

## Existing Play app

| Field | Existing value |
| --- | --- |
| Developer display name | Oh My Pi |
| Developer account ID | `9173169759090733636` |
| Play app ID | `4975999632955979701` |
| Package | `app.readflow` |
| Distribution | Free app; existing features require no purchase |
| Support / privacy contact | `xperimenting.ai@gmail.com` |
| Privacy policy | <https://sainadh812.github.io/ReadFlow/privacy.html> |

Open the [existing Internal testing track](https://play.google.com/console/u/0/developers/9173169759090733636/app/4975999632955979701/tracks/internal-testing) in the authenticated owner or publisher account. Reuse this app rather than creating another listing.

The last recorded Console state is **historical and owner-reported**: on 27 October, 0.2.5/code 21 had been accepted into a draft, Internal testing was inactive, and no rollout was reported. [Recorded report](evidence/play-store-0.2.5-configured/play-console-draft-reported.json) · [Historical draft URL](https://play.google.com/console/u/0/developers/9173169759090733636/app/4975999632955979701/tracks/4701509931226024011/releases/1/prepare).

The current Console state, accepted version codes, available tracks and release status must be read from the live account. Do not treat the old draft as current. If code 27 already exists, reconcile its artifact and release state instead of uploading it again. Record the actual track, release status and tester/store link after the Console operation; uploading a bundle alone does not make it available to users.

## Copy-paste release notes

Release name: **Read Assist 0.3.1**. The English text below is under 500 characters.

```text
<en-US>
The reader now has a Text/PDF switch in the bottom panel, with evenly spaced playback controls and a centered Play/Pause button. Use the up arrow or swipe up to open reader settings, and the down arrow to close them. Switching views keeps your reading position, PDF zoom and text scroll. Playback speed is easier to reach from the expanded controls.
</en-US>
```

## Verified results and remaining evidence

- Signed CI: 460 tests reported, 457 passed and three model-fixture tests skipped; zero failures/errors. Local release tests: all 460 passed. Lint: zero errors, 38 warnings.
- All 83 downloaded artifact hashes matched. AAB validation and upload-certificate verification passed. Packaging checks recorded 16 KB alignment. See [release evidence](evidence/reader-0.3.1/release-evidence.json), [local validation](evidence/reader-0.3.1/local-validation.json), [artifact verification](evidence/reader-0.3.1/signed-artifact-verification.json), [AAB verification](evidence/reader-0.3.1/aab-verification.json) and [packaging](evidence/reader-0.3.1/packaging.json).
- No connected Android device was available. Physical-phone layout, TalkBack, gestures, playback, install/upgrade, Play-delivered split APKs and a real 16 KB device environment remain unverified for this version.
- The signed artifact contains the approved Firebase configuration, with crash reporting off by default. Native symbols were retained but not uploaded to Firebase; remote crash delivery remains unverified. The evidence still marks final license review as outstanding.
- Live Console access is needed to determine account verification, declarations, store assets, testing/production eligibility, tester membership and any actual upload or submission errors. The older [store handoff](PLAY_STORE_HANDOFF.md) supplies historical configuration context; its 0.2.6 artifact details are superseded by this exact 0.3.1 bundle.

If this workspace cannot access the Play account, the AAB, checksum, app link and release notes above are ready for an authenticated publisher to use. Record that access limitation accurately; do not describe the GitHub release as a Google Play release.
