# Read Assist: GitHub production build

The owner chose GitHub Actions for the configured, signed build. The workflow is
manual only and builds the approved `fix/reader-playback-and-pdf-space` branch.
It does not publish to Play Console.

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

## Verified configured build — 31 October 2026

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

## Play upload handoff

The owner reports Muse Spark uploaded the verified AAB as **0.2.5/code 21** to the
new free Read Assist app (**4975999632955979701**) and saved an internal-test draft.
No rollout was performed. Preserve that existing app and draft; code 21 is already
used. For a subsequently authorized update, use the verified **0.2.6/code 22 AAB**
with the existing upload key and Play App Signing. Uploading or rolling out code 22
is not part of this build workflow. The code 21 draft is owner-reported Console
evidence, separate from the independently verified CI builds.
See [current handoff](PLAY_STORE_HANDOFF.md#current-play-console-state--owner-reported).

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

Use **Actions → Read Assist signed release candidate → Run workflow**. The workflow must
exist on `main` for this button to be available; it checks out the approved release
branch separately. Leave native symbol upload off for the initial build/review.

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
certificate and its metadata belong in Git. The workflow publishes no app to Play
and does not change Firebase billing.
