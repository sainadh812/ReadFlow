# Read Assist: GitHub release build and Google Play publication

The owner chose GitHub Actions for the configured, signed build. The workflow is
manual only. Its default `build` operation builds the approved
`fix/reader-playback-and-pdf-space` branch. The owner subsequently authorized
**full publication of 0.3.1/code 27**, including store copy, screenshots, demo
videos and promotion to production for the existing `app.readflow` app.
[Publication run 43743340573](https://github.com/sainadh812/ReadFlow/actions/runs/43743340573)
confirmed code 27 published to **Internal testing** on 15 November 2026; see the
[release evidence](evidence/reader-0.3.1/play-publication.json). That evidence does
not establish store-media completion, production access or production publication.

## Current setup

- Muse Spark reports the existing Firebase Spark project `read-assist-fbd21`,
  Android package `app.readflow`, app ID
  `1:831366793321:android:573b80e46b55eb65103af8`, with Analytics disabled.
- A dedicated RSA-3072 upload key has been generated. Four repository secrets are
  stored and verified by name: `READASSIST_UPLOAD_KEYSTORE_BASE64`,
  `READFLOW_UPLOAD_STORE_PASSWORD`, `READFLOW_UPLOAD_KEY_ALIAS`,
  `READFLOW_UPLOAD_KEY_PASSWORD`. **Do not generate a replacement or overwrite them.**
- Public upload certificate: [PEM](evidence/play-store-upload/upload-certificate.pem).
  SHA-256: `b8e3cdfd71bc98427f17921e5b2c0e97a8543cc90a78b066afae7bfab6fb29a2`.
  This is the upload certificate; Play App Signing manages a separate signing key.
- The Firebase file is not present in this coding workspace. Its repository secret
  exists as `READASSIT_FIREBASE_CONFIG_BASE64` (the earlier spelling). The workflow
  accepts this name as a fallback, so no repeat transfer is needed. The successful
  configured build validated its project/app IDs, and the downloaded APK resources
  independently match those identifiers.
- The owner reports enabling the Google Play Android Developer API in
  `read-assist-fbd21`, granting
  `play-publisher@read-assist-fbd21.iam.gserviceaccount.com` access to Read Assist
  with view-app-information and release-to-testing permissions, and storing its
  JSON credential as the repository Actions secret
  `READFLOW_PLAY_SERVICE_ACCOUNT_JSON`. The API operations read that secret only
  in their execution step; it is not an artifact, repository file, or command-line
  argument. Successful authentication and Internal testing publication are recorded in the [current release handoff](PLAY_UPLOAD_0.3.1.md).
- Store publication additionally needs **Manage store presence**; production
  promotion needs **Release to production, exclude devices, and use Play App Signing**
  for Read Assist. These additional grants remain unverified until exercised with
  the API. Keep the same service account and `READFLOW_PLAY_SERVICE_ACCOUNT_JSON`
  secret; no replacement key is needed. Policy declarations may separately need
  **Manage policy declarations** and Console work. Owner authorization is not
  evidence that Google has granted production eligibility or accepted declarations.

## Current Google Play API operations

Use the existing `.github/workflows/read-assist-release.yml` workflow and select
an `operation`:

| Operation | Behavior |
| --- | --- |
| `build` | Default; build and verify signed APK/AAB files without uploading to Play. `upload_symbols` applies only to this operation. |
| `inspect_play` | Check API access and inspect the existing app and Internal testing state without committing a release. |
| `inspect_store` | Read listing copy, image hashes, bundles, tracks and available lifecycle information; delete the temporary inspection edit without committing. |
| `capture_demo` | Build an isolated emulator variant and attempt actual app screenshots, videos and background-playback evidence. No Play upload or production signing key is used. |
| `publish_play` | Upload the exact verified 0.3.1 AAB below and commit it to Internal testing. |
| `publish_store` | Publish approved en-US listing copy and verified icon, feature graphic and actual app screenshots; preserve tracks, other locales and app details. |
| `validate_production` | Stage promotion of the existing verified code 27 bundle, call Play validation, then delete the owned edit without committing. |
| `publish_production` | Promote the existing verified code 27 bundle to production, validate and commit, then check the resulting release state. No bundle upload or rebuild occurs. |

The Play operations check out the dispatched Git commit (`github.sha`), install
hash-pinned Python dependencies, and run publishing safeguards before accessing
the credential. They share the build workflow's concurrency group and retain its
repository and approved-branch restrictions. No new Android build runs during
these operations.

Store operations use `store-assets/release-0.3.1/manifest.json` and
`store-assets/listing/en-US.json`. They verify PNG sizes, dimensions, hashes and
source provenance, including the generator, fonts and original app captures.
The required set is one 512×512 icon, one 1024×500 feature graphic and 2–8 actual
1080×1920 screenshots. Conflicting existing media is not deleted automatically.
Production additionally requires the live listing and media to match these
approved files, completed Internal testing code 27, and Google's bundle hash to
match the AAB below. A newer bundle/track or an active or halted production rollout
stops promotion. Other tracks and store metadata must remain unchanged.

`capture_demo` runs only from the approved release branch. Its source-tree guard
requires `app/src/main` and `app/src/release` to match the signed 0.3.1 source.
Capture provenance records the actual commit, APK hash and emulator build:
x86_64 release runtime, local debug signing and no configured Firebase. These
recordings are not physical-device or Play-installed ARM64 verification. Review
the capture report, footage and real audio before generating or publishing media;
a capture dispatch alone does not establish that usable media was produced.

Store and production commits use `ERROR_IF_IN_REVIEW`; ambiguous commit responses
require reconciliation rather than automatic retries. Validation failures and
Console requirements are retained as evidence. An empty production track or an
inspection lifecycle quota error does not establish production eligibility or a
permission refusal. See [production submission evidence](PRODUCTION_READINESS_0.3.1.md).

The publishing script pins this existing GitHub release artifact:

- Repository/tag: `sainadh812/ReadFlow` / `v0.3.1-play-candidate`.
- Asset: `Read-Assist-0.3.1-upload-signed.aab`, **72,436,099 bytes**.
- SHA-256: `a247863362aec730e6c50ebe85a403de8cb572821276077d38a3c0b2f8e7e1f6`.
- Package/version: `app.readflow`, **0.3.1/code 27**.
- App source commit: `94006546634355658abfba7ce1a7a442d8825828`.
- Signed build: [run 43736870521](https://github.com/sainadh812/ReadFlow/actions/runs/43736870521).
- Evidence: [configured release](evidence/reader-0.3.1/release-evidence.json),
  [GitHub artifact verification](evidence/reader-0.3.1/github-release.json), and
  [independent AAB verification](evidence/reader-0.3.1/aab-verification.json).

Dispatch inspection from the approved release branch:

```bash
gh workflow run read-assist-release.yml \
  --repo sainadh812/ReadFlow \
  --ref fix/reader-playback-and-pdf-space \
  -f operation=inspect_play \
  -f upload_symbols=false
```

Select another operation using the same command. After inspecting current state,
review captured media and commit the approved assets, publish the store listing,
then run `validate_production` before `publish_production`. These operations do not
submit policy declarations or resolve account/testing prerequisites automatically.
GitHub supports `--ref` with workflow
inputs; the workflow must already exist on the repository's default branch for
manual dispatch. See [GitHub's manual workflow instructions](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow).

API reports contain sanitized evidence and are retained as Actions artifacts for
15 days, when the corresponding report exists:

| Operations | Report | Artifact prefix |
| --- | --- | --- |
| `inspect_play`, `publish_play` | `build/play-upload-0.3.1/report.json` | `Read-Assist-Play-<operation>` |
| `inspect_store` | `build/play-store-inspection/report.json` | `Read-Assist-Store-Inspection` |
| `publish_store` | `build/play-store-publication/report.json` | `Read-Assist-Store-Publication` |
| `validate_production`, `publish_production` | `build/play-production-publication/report.json` | `Read-Assist-Production` |

Artifact names append `<run-id>-<attempt>`. `capture_demo` separately retains
`build/store-media/capture/`, the emulator APK and its test APK in
`Read-Assist-Store-Capture-<run-id>-<attempt>` for 15 days, including diagnostics on
failed runs. Preserve relevant reports, media hashes and run URLs. A successful
build, capture, validation or commit does not by itself establish public store
availability: distinguish lifecycle states such as in review, published and
availability unconfirmed, including failures to read lifecycle information.

## Historical configured build — 31 October 2026

[Successful run](https://github.com/sainadh812/ReadFlow/actions/runs/41183292058) · [signed APK/AAB and verification files](https://github.com/sainadh812/ReadFlow/actions/runs/41183292058/artifacts/13036231224) · [published 0.2.6 prerelease](https://github.com/sainadh812/ReadFlow/releases/tag/v0.2.6-play-candidate).
Source commit: `25944d402312adf69e44f5e3d6f5b42a2e6b179f`; version **0.2.6/code 22**.
Artifacts expire after 14 days; preserve the downloaded files and their checksums.

- Normalization `english-6` handles a numeric sentence ending followed by an
  attached reference, such as `2019.)71`, while retaining its source-word mapping.
  Reporting settings now distinguish saved consent from an active service and
  show local SDK submission status. A synchronous submission failure allows a
  later occurrence to retry within the hourly limit; an SDK return is not delivery confirmation.
- Firebase project/app, upload certificate, bundle/APK signatures, fonts, policy,
  16 KB compatibility and matching native build IDs passed the CI checks.
- **372 release tests passed**; three real-model tests were skipped because the clean
  runner has no downloaded model fixtures. Lint: zero errors, 34 warnings.
- All **69 downloaded artifact file hashes** matched. The AAB SHA-256 is
  `e222bcf1610e211093836918a7a30960c692bb43c6d4de12d23fe6b3a0579727`;
  the APK SHA-256 is `b4bddf9865623d8ec4d3c374eaac9ee399a964c215d87bfec28964742fb8fddb`.
- Signatures and packaged Firebase identifiers passed independent local checks.
  All 144 runtime dependency hashes, 314 assets (287 license assets) and seven
  fonts match 0.2.5; only the app-owned Misaki native binary changed. This comparison
  does not establish legal clearance.
- Native symbols were generated and retained, but not uploaded. No phone crash
  delivery or device testing was verified. **0.2.6/code 22 has not been uploaded to Play.**

Evidence: [release](evidence/play-store-0.2.6-configured/release-evidence.json),
[checksums](evidence/play-store-0.2.6-configured/SHA256SUMS.json),
[local verification](evidence/play-store-0.2.6-configured/local-verification.json),
[dependency delta](evidence/play-store-0.2.6-configured/dependency-delta.json),
[GitHub publication](evidence/play-store-0.2.6-configured/github-release.json).
Use this exact candidate for further checks; rebuild only if a change is needed.

The historical configured **0.2.5/code 21** build remains documented in
[run 40529659238](https://github.com/sainadh812/ReadFlow/actions/runs/40529659238)
and its [release evidence](evidence/play-store-0.2.5-configured/release-evidence.json).
Its checks and the owner-reported Play draft below apply to that older artifact.

## Historical Play upload handoff

The owner reports Muse Spark uploaded the verified AAB as **0.2.5/code 21** to the
new free Read Assist app (**4975999632955979701**) and saved an internal-test draft.
No rollout was performed at that time; code 21 is already used. This is historical,
owner-reported Console evidence, separate from the independently verified CI
builds. The subsequent Internal testing authorization and publication concerned
the verified **0.3.1/code 27 AAB** in that same app, using the existing upload key
and Play App Signing. The owner's newer full-publication authorization is described
above. Inspect the current API state before publishing; do not assume the old
draft still represents the current track. See the
[earlier handoff](PLAY_STORE_HANDOFF.md#current-play-console-state--owner-reported).

## Firebase config secret

The existing `READASSIT_FIREBASE_CONFIG_BASE64` secret is sufficient. Do not
recreate it or change signing secrets. If `READASSIST_FIREBASE_CONFIG_BASE64` is
added later, that preferred spelling takes precedence. Both workflow input steps
resolve names identically and retain the same strict project/app validation.

For a future deliberate config replacement, verify the downloaded file's project,
package and Firebase app ID against the values above. Store its base64 encoding as
`READASSIST_FIREBASE_CONFIG_BASE64` in repository Actions secrets. With authenticated
GitHub CLI on Linux:

```bash
base64 -w0 app/google-services.json | gh secret set READASSIST_FIREBASE_CONFIG_BASE64 --repo sainadh812/ReadFlow
```

Alternatively use GitHub **Settings → Secrets and variables → Actions → New
repository secret** from the owner's authenticated browser. Transfer the encoding
directly into that field; do not paste it into chat, commit it, or include it in an
artifact. The client JSON is app configuration, not an administrative credential.

## Build and review

Use **Actions → Read Assist signed release candidate → Run workflow** and choose
`operation=build`. The workflow must exist on `main` for this button to be
available; the build operation checks out the approved release branch separately.
Leave native symbol upload off for the initial build/review.

The workflow validates the exact Firebase destination, installs the pinned Android
toolchain, builds a signed AAB/APK, runs tests/lint and checks the public policy,
bundled notices, fonts, 16 KB compatibility and expected upload certificate. It
uploads a narrowly selected release artifact and removes temporary signing/config
files. Missing configuration stops the build; an unconfigured success is not accepted.

Download the Actions artifact and audit that **exact signed AAB**, including its
hash, dependency/model notices, consent/privacy disclosures and Play Data safety
answers. Host checks do not provide legal clearance. Native symbols can then be
uploaded by explicitly selecting that option for a verified build; review the new
artifact/hash if a later workflow run rebuilds anything. Follow
[native symbol instructions](NATIVE_CRASH_SYMBOLS.md) and the
[publishing handoff](PLAY_STORE_HANDOFF.md) for phone checks and Console entries.

Do not claim crash delivery until controlled test reports appear in the existing
Firebase app. Keep reporting off by default. Use a dedicated test installation;
the new upload-signed APK cannot update an older prototype signed with the debug
certificate or a Play-installed copy signed with the separate Play signing certificate.
Do not uninstall the owner's reading installation without preserving its data.

## Key custody

Private key/password files are retained outside the repository under the owner's
protected local signing directory. Keep an owner-controlled secure backup; GitHub
secrets do not offer a normal settings-UI export. Never upload private signing
files as Actions artifacts, GitHub releases or source archives. Only the public
certificate and its metadata belong in Git. The Play service-account JSON stays
in the repository secret and is available only to the API step. The `build`
operation publishes no app to Play. Explicit `publish_play`, `publish_store` and
`publish_production` operations respectively target Internal testing, store
listing/media and production. No operation changes Firebase billing.
