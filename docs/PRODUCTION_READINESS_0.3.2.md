# Read Assist 0.3.2: production candidate

**Pending signed-artifact verification and publication.** The owner authorized full publication, including store graphics and demo videos. Version **0.3.2/code 28** replaces code 27 as the candidate after actual Android emulator testing exposed a dictionary failure. The [0.3.1 Internal release evidence](evidence/reader-0.3.1/play-release-summary.json) remains historical; it does not establish a code 28 upload or production release.

## Fix and evidence boundary

Android's ICU regular-expression engine rejects the JVM-only `(?U)` flag used by dictionary query normalization. This made dictionary lookup fail on Android despite desktop unit tests. [Fix commit a6c99f3](https://github.com/sainadh812/ReadFlow/commit/a6c99f33f6e8934f4a304355a3e093b1b569dfa8) replaces that flag with an explicit whitespace, Unicode-separator and NEL character class. Regression coverage includes Unicode whitespace and a real Android seeded-dictionary lookup.

The targeted desktop `DictionaryQueryTest` passed **10 tests**, and the corrected expression passed a native ICU probe on the host for **25 whitespace characters**. The complete Android dictionary integration/capture run remains pending; these narrower checks do not establish that result. [Focused regression evidence](evidence/reader-0.3.2/dictionary-regression-summary.json).

Compared with the signed 0.3.1 application's source revision `94006546634355658abfba7ce1a7a442d8825828`, the sole change under `app/src/main` and `app/src/release` is `DictionaryModels.kt`. No privacy, model, dictionary-pack, font or license asset changed in those source trees. The Gradle version and test/capture infrastructure also changed. This source comparison does not replace verification of the new signed files or an Android runtime result.

| Item | Current target or evidence |
| --- | --- |
| App | Read Assist, package `app.readflow`, version `0.3.2`, code `28` |
| Application fix revision | `a6c99f33f6e8934f4a304355a3e093b1b569dfa8` |
| Dispatched build/capture revision | `e1a73d4dfed9c7fd0ffcfbcc3cf3c9449201e8d3` |
| Signed build | [Run 43749924111](https://github.com/sainadh812/ReadFlow/actions/runs/43749924111); outcome and artifact verification pending |
| Android capture | [Run 43749926901](https://github.com/sainadh812/ReadFlow/actions/runs/43749926901); dictionary regression and usable recordings pending |
| Signed AAB | Expected `Read-Assist-0.3.2-upload-signed.aab`; **SHA-256 and byte count UNVERIFIED** |
| GitHub release target | `v0.3.2-play-candidate`; existence/download verification pending |
| Device support in source | Android 15/API 35+, ARM64; target API 36 |
| Internal/production | No code 28 publication is established by this document |
| Play permissions | Owner reports adding store-presence, production-release and policy-declaration grants to the existing service account. Actual relevant API/Console access still needs verification. |

Keep `READFLOW_PLAY_SERVICE_ACCOUNT_JSON` and the existing upload key. The publishers deliberately contain `UNVERIFIED_0_3_2_AAB_SHA256` and byte count `0` until the exact new signed artifact is audited. Internal upload and production validation/promotion fail before credential or API access while these placeholders remain. Inspection and store-media operations remain available.

## Complete this candidate in order

1. Confirm the signed build checks and Android dictionary regression pass. Download the exact APK/AAB and evidence, verify every recorded checksum, signature/upload certificate, version/package and packaging checks, and preserve results under `docs/evidence/reader-0.3.2/`. Record actual test and lint totals; do not copy code 27 results.
2. Publish the verified files to the new GitHub candidate release. Set `AAB_SHA256` and `AAB_BYTES` in `scripts/publish_play.py` from those verified bytes; never substitute the code 27 hash. Keep its version/code/download URL consistent, then rerun publishing safeguards.
3. Inspect Play and publish the exact code 28 bundle to Internal testing. Preserve the sanitized report at `build/play-upload-0.3.2/report.json` and verify the bundle hash and actual lifecycle before claiming availability.
4. Review actual code 28 captures and audio. Generate approved graphics from original captures under `store-assets/release-0.3.2/`; retain hashes, captions and source provenance. Produce `Read-Assist-0.3.2-product-demo.mp4` and the continuous `Read-Assist-0.3.2-foreground-service.mp4`. Emulator APKs use local debug signing and are not physical-device or Play-installed ARM64 evidence. Never relabel the failed code 27 recordings as code 28.
5. Publish the reviewed demo page/media/credits and verify public access. Publish the en-US listing and approved image manifest with `publish_store`. Store preview placement requires a valid YouTube URL; a separately hosted foreground-service proof is not automatically a YouTube store preview.
6. Complete any actual outstanding Console declarations/account tasks below. Run `validate_production`, resolve its specific blockers, then `publish_production` for the existing verified code 28 bundle. Save the production report and distinguish commit acceptance, review, approval and public availability.

The old capture-build reuse helper is pinned to code 27 artifacts. Leave reuse disabled for code 28 unless a separate verified code 28 emulator build and all its source/APK pins are deliberately recorded.

## Console handoff retained from the source review

The source fix introduces no new data flow, SDK, permission or paid feature. The [ready-to-enter Console answers](PRODUCTION_READINESS_0.3.1.md#console-handoff-answers-already-supported-by-evidence), [reviewer steps](PRODUCTION_READINESS_0.3.1.md#app-access-instructions-ready-for-the-reviewer) and [mediaPlayback declaration text](PRODUCTION_READINESS_0.3.1.md#foreground-service-declaration-draft) remain the starting material. Recheck the new signed manifest/runtime inventory before attaching code 28 artifact claims, and supply the reviewed code 28 video URL.

Use the existing public privacy URL and support email `xperimenting.ai@gmail.com`; preserve free pricing, no ads and unrestricted access without login or purchase. The existing English listing describes the retained feature set. Privacy/Data safety must still include ML Kit telemetry separately from optional Crashlytics; optional diagnostics does not establish that every shared data type is optional.

The unresolved owner/account facts remain intended age groups and existing territories, actual production eligibility/verification, and the registered UPI recipient/provider facts needed for payment disclosures. Inspect saved Console/account answers first. Production access, content rating, Data safety and other declarations are not established by the Internal release, by a permission grant, or by creating media. The publishing scripts do not submit policy declarations or invent account facts.

Google also exposes a [Data safety CSV submission endpoint](https://developers.google.com/android-publisher/api-ref/rest/v3/applications/dataSafety). No approved CSV or matching read endpoint is available in this handoff; its existence does not supply the missing declaration answers. It is separate from the current listing/release automation.
