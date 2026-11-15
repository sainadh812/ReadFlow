# Read Assist 0.3.1 — Google Play Internal testing release

**Published to Internal testing on 15 November 2026 at 17:51 UTC:** Read Assist **0.3.1 / versionCode 27**. Google accepted the verified bundle and the release commit; a fresh API read confirmed Internal testing `completed` and `RELEASE_LIFECYCLE_STATE_PUBLISHED`. Production, alpha and beta remained unchanged.

## Current access status

The owner selected **Internal testing** and authorized uploading and releasing this exact candidate. On 15 November 2026, the owner enabled the Android Publisher API in `read-assist-fbd21`, created `play-publisher@read-assist-fbd21.iam.gserviceaccount.com`, and granted access to Read Assist with app-information and testing-track release permissions. The owner stored its JSON credential as the repository Actions secret `READFLOW_PLAY_SERVICE_ACCOUNT_JSON`; the secret's presence was verified without reading or exporting its contents.

The registered release workflow now supports `inspect_play` and `publish_play` operations. Credentials are used only inside the relevant GitHub Actions step. The publisher pins package `app.readflow`, Internal testing, version code 27, the artifact size/hash, and the approved service-account identity. It preserves other tracks and testers, refuses to cancel an existing review, and checks Google's release lifecycle before claiming publication. See [workflow instructions](GITHUB_RELEASE_BUILD.md).

The credential authenticated successfully. [Inspection run 43743287816](https://github.com/sainadh812/ReadFlow/actions/runs/43743287816) found 0.2.6/code 22 published on Internal testing with other tracks empty. [Publishing run 43743340573](https://github.com/sainadh812/ReadFlow/actions/runs/43743340573) uploaded the approved bundle, committed code 27, and confirmed its published lifecycle and exact SHA-256 from a fresh edit. Both runs used publisher source `24d7d425ab8a2cfb7497298eb554c2b45f5bb3e7`; all 16 publishing safeguard tests passed locally and in CI. The Android app was not rebuilt.

Evidence: [inspection](evidence/reader-0.3.1/play-inspection.json), [publication](evidence/reader-0.3.1/play-publication.json), and [release summary](evidence/reader-0.3.1/play-release-summary.json).

**Tester link:** [Join the Read Assist internal test](https://play.google.com/apps/testing/app.readflow). Sign in with a Google account already included in the test. The link redirects unauthenticated requests to Google sign-in; tester membership and phone installation were not checked or changed. Supported devices remain Android 15+ / ARM64.

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

The live 15 November API inspection superseded that old draft: code 22 was published before this operation. The post-commit read now confirms code 27 published on Internal testing with the exact approved AAB hash. Future runs must reconcile code 27 instead of blindly uploading it again; the publisher performs that check.

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
- API access, bundle acceptance and Internal testing publication are now verified. Account declarations, production eligibility, tester membership and installation on a physical device were not audited by this operation. The older [store handoff](PLAY_STORE_HANDOFF.md) supplies historical configuration context; its 0.2.6 artifact details are superseded by this exact 0.3.1 bundle.

The GitHub candidate and the Google Play Internal testing release now refer to the same approved AAB. This operation did not publish a production release.
