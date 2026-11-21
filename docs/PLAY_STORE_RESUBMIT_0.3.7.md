# Read Assist 0.3.7: Console resubmission handoff

**Code 33 is built, verified, published on GitHub, and committed to alpha/Internal testing. Review submission is still required in Console.** Fresh readback at **11:04:33 UTC / 16:34:33 IST on 21 November 2026** confirms both tracks include only code 33 and the exact verified AAB hash; beta/production are empty. Google returned the explicit manual-review requirement. Lifecycle reads were quota-limited, so Internal tester availability is unconfirmed; no approval is claimed.

[Download APK](https://github.com/sainadh812/ReadFlow/releases/download/v0.3.7-play-candidate/Read-Assist-0.3.7-upload-signed.apk) · [GitHub release](https://github.com/sainadh812/ReadFlow/releases/tag/v0.3.7-play-candidate) · [Play upload evidence](evidence/reader-0.3.7/play-publication.json). No further build or bundle upload is needed.

Google's reply for enforcement **4989388494721813173** asks for the explicit no-financial-features declaration to be saved, all active tracks to contain compliant artifacts, and a new incremented release to be submitted with the declarations.

Version **0.3.7 / code 33** updates the embedded privacy notice to match the live policy. The owner confirmed that no tips were ever received and no related records are held, so obsolete historical-contribution text was removed. App logic, free reading features, SDK behavior and support/deletion disclosures are unchanged. This is the requested resubmission procedure, not a confirmed resolution of the account-classification issue.

## Muse: complete these Console steps

1. Open **Read Assist → Policy and programs → App content → Financial features**. Confirm **My app doesn't provide any financial features** is explicitly selected. Use **Save**, not Save as draft, when available; reopen and capture the saved answer. If already saved and Save is disabled, record that fact without temporarily entering a false answer.
2. Confirm the store category is **Productivity**, **Health: no health features**, and **Government apps: No**. Check Data safety against the current app and SDKs. The previous audit selected no Financial info. Preserve accurate SDK disclosures; do not set global no-data-collection. Crash logs are optional; ML Kit's separate diagnostics, performance and installation identifiers are not controlled by that switch.
3. Check current alpha/Internal release details and **Included / Not included** artifacts. Code 33 should be the only included version in those releases; beta/production should remain empty unless their state has independently changed. Also inspect any additional active closed or form-factor tracks if present. Historical bundles in All app bundles are not proof of active distribution.
4. Open **Publishing overview** and review the release and declaration changes together, then click **Send for review**. Capture **Submission activity ID**, receipt time, included modifications and resulting status. Keep the saved Financial form capture separately; a generic declaration-updated entry does not reveal the exact submitted answers.
5. Add the submission ID and evidence to the **existing Google case**, and confirm whether it is registered as an appeal for **4989388494721813173**. The response's case ID and receiving team have not been supplied to this workspace. Avoid a duplicate request; follow Google's explicit appeal instructions if they confirm the existing ticket is support-only.

No new Play credentials are needed. The current service-account publisher cannot edit the Financial form or perform the required post-rejection Console review action. Uploading a bundle is separate from sending changes for review and receiving approval.

## Evidence

- [Signed artifact verification](evidence/reader-0.3.7/signed-artifact-verification.json): 83 file checksums, signatures, bundle/16 KB checks, updated privacy and removed-tip scans passed; zero test failures/errors and lint errors.
- [Google reply, owner confirmation and work record](evidence/play-review-2026-11-21/google-reply-and-code33-plan-reported.json)
- [Pre-upload track inspection](evidence/play-review-2026-11-21/store-inspection-before-code33-44896100415.json): at 10:48:59 UTC on 21 November, alpha code 32 was NOT_APPROVED, Internal code 32 was PUBLISHED, and beta/production were empty.
- [Policy verification](evidence/privacy-policy/publication.json): the local HTML exactly matches the live page; no website rewrite was needed.

[Financial declaration instructions](https://support.google.com/googleplay/android-developer/answer/13849271?hl=en) · [Review submission](https://support.google.com/googleplay/android-developer/answer/9859654?hl=en) · [Submission activity](https://support.google.com/googleplay/android-developer/answer/17118609?hl=en).
