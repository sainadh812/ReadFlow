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
- The Firebase file is not present in this coding workspace. Muse Spark must
  transfer its existing file to the final repository secret below. Do not create
  another Firebase project. No service-account/admin credential is needed.

## One remaining secret

In the workspace that actually has the downloaded file, first verify its project,
package and Firebase app ID match the values above. Store its base64 encoding as
`READASSIST_FIREBASE_CONFIG_BASE64` in `sainadh812/ReadFlow` repository Actions
secrets. With authenticated GitHub CLI on Linux:

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
certificate, and Play-delivered APKs use the separate Play signing certificate.
Do not uninstall the owner's reading installation without preserving its data.

## Key custody

Private key/password files are retained outside the repository under the owner's
protected local signing directory. Keep an owner-controlled secure backup; GitHub
secrets do not offer a normal settings-UI export. Never upload private signing
files as Actions artifacts, GitHub releases or source archives. Only the public
certificate and its metadata belong in Git. The workflow publishes no app to Play
and does not change Firebase billing.
