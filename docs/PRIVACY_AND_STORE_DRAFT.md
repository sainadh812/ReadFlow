# Read Assist: privacy, Data safety and store draft

**Store/Data safety worksheet — final submission pending. Updated 2026-10-26.** The public [Privacy Policy](https://sainadh812.github.io/ReadFlow/privacy.html) is published, with developer display name **Oh My Pi** and owner-approved support/privacy contact **xperimenting.ai@gmail.com**. The current candidate source is **Read Assist 0.2.4**, package `app.readflow`, version code 20; its build and all 335 release JVM tests passed, with release lint at zero errors and 28 warnings. Preview signature, APK 16 KB ZIP alignment, bundle/font checks and the artifact delta review also passed; see the [build record](evidence/play-store-0.2.4/build-results.json). Final production artifact/device checks remain required. Version 0.2.4 configures the live policy URL in the app. Historical 0.2.3 evidence covers that earlier build, which lacks this configured URL. Keep `app.readflow` for updates and use **Read Assist** (Assist has a double “s”) for the public app name. All existing features remain free; paid tiers, prices and coupons are deferred. Optional external UPI support has no payment SDK/backend, paid entitlement or daily speech quota.

Muse Spark should fill verified owner/account information and enter the final approved materials in Play Console. It must not invent policy facts, configure a paid download or create paid products for this launch.

## Free launch and implemented optional support

| Item | Current direction |
| --- | --- |
| Download | Free; no subscription or ads. |
| Features | All existing document reading, OCR engines, offered voices, speech, reader themes/fonts/accessibility controls and phone benchmarks remain free. No daily speech quota or payment-gated feature. Model downloads and phone/storage limitations still apply. |
| Optional tip | Settings → “Buy me a coffee” opens an optional support dialog. Tipping provides no unlocked content, extra usage, badge, status, feature or priority support. This phrase does not imply integration with a particular payment company. |
| Recipient | The owner supplied the new `readassist@ybl` and will register/change it with their UPI provider. Registration, ownership and the bank-displayed recipient name have not been verified. Verify these before shipping; do not treat the app's display label as proof of the bank account holder. |
| Scope | An external UPI app handoff and “Copy UPI ID” fallback. No QR, global card-payment provider, payment SDK, backend, payment callback, transaction-record storage or success verification. Users can dismiss support and keep using every feature. |
| Amount | Default ₹10; presets ₹10/₹50/₹100; custom ₹1–₹1,000 with up to two decimal places. The selected amount is sent in INR. |

Google's direct creator-tip exception requires the entire contribution to go to the creator and no digital content or service in exchange, including badges or similar benefits. Verify those conditions for the actual payment arrangement; a “tip” label alone does not qualify. Do not add a platform revenue share or connect payments to app entitlements. This is support for the creator, not a tax-deductible charitable donation. [Play Payments policy guidance, direct tips](https://support.google.com/googleplay/android-developer/answer/10281818).

The dialog shows the recipient UPI ID and selected amount, asks the payer to check the bank-displayed recipient name/ID/amount, and opens an external payment app. Its feedback explicitly says Read Assist does not verify payment status. It never asks for a UPI PIN or bank password, receives no payment callback and does not unlock anything. Test cancellation, payment-app rejection, no-handler and Android-blocked-launch cases on the phone; a return to Read Assist must not be interpreted as success.

Source evidence: [support dialog](../app/src/main/java/app/readflow/ui/SupportTipDialog.kt), [validated amounts and UPI parameters](../app/src/main/java/app/readflow/ui/SupportTipDetails.kt), [Settings entry](../app/src/main/java/app/readflow/ui/ManagementScreens.kt). The handoff contains `pa=readassist@ybl`, `pn=Read Assist`, the INR amount/currency and an optional-tip note. It contains no document text, payer identifier or app-generated transaction identifier. `pn` is an app-supplied display label, not independent verification of the recipient's identity.

## Confirmed public details and remaining owner facts

Use the approved facts below; do not ask the publishing agent to invent or republish them from an obsolete draft.

| Item | Confirmed information |
| --- | --- |
| Public developer display name | **Oh My Pi**. This is the approved display name, not evidence of a verified legal entity/account identity. |
| Public support/privacy contact | **xperimenting.ai@gmail.com**. Use this store contact; do not substitute a personal profile email. |
| Privacy Policy | **https://sainadh812.github.io/ReadFlow/privacy.html**, effective **2026-10-26**. Published and HTTP 200/exact bytes verified; source is [site/privacy.html](../site/privacy.html). |
| Developer-received reports/support correspondence | Delete within **90 days after resolution**. Earlier deletion can be requested through the privacy contact; externally controlled copies are separate. |
| Developer-held payment records | Retain only as needed for **accounting or legal requirements**. Read Assist itself has no transaction callback or transaction-record storage. Banks/payment providers have their own policies. |

| Remaining fact / decision | What is still needed |
| --- | --- |
| Verified legal/account identity | Complete Play's actual developer/account verification. Do not infer the legal identity from the public display name. |
| Actual recipient and verification of `readassist@ybl` | Confirm the owner has registered/changed this ID and it belongs to the intended recipient. Check the bank-displayed recipient before shipping. Never place banking secrets in the repo. |
| Recipient-accessible transaction data | Verify what the payment provider actually exposes and keep the policy/Data safety assessment accurate. Do not invent a payment-verification backend. |
| Optional tip device verification | Test the external intent/copy flow with installed UPI apps. Re-audit before adding callbacks, payment SDKs, transaction storage or servers. |
| Audience / territories | Actual intended audience, launch countries and account-required disclosures. Assess additional obligations before selecting a children-directed audience. |
| Application terms | Owner-approved application license/EULA and contribution/payment-help terms. Third-party licenses remain separate. |

## Canonical published Privacy Policy

Use the [live Privacy Policy](https://sainadh812.github.io/ReadFlow/privacy.html) and [canonical source](../site/privacy.html), not a second copied policy in this worksheet. Publication and maintenance details are in [PRIVACY_PUBLICATION.md](PRIVACY_PUBLICATION.md). The page was verified on **2026-10-26** with HTTP 200 and exact matching bytes: GitHub Pages commit `2e3aa9c1092dc20d1bc72adc1b45d0aa8b6e9a93`; SHA-256 `48483d2dc25a971765d77c553cfeb6b242c47c7bb6851dfd4a28bee2a1fbc1d1`.

The policy covers local documents/speech, model-host connections, ML Kit metrics, optional reports/exports, retention/deletion, external UPI support and GitHub Pages hosting. Public availability does not certify Play approval or UPI registration. Keep the app disclosure, page and final Console answers consistent. Version 0.2.4 uses this URL by default; `READFLOW_PRIVACY_POLICY_URL` can override it only with an approved replacement. Earlier 0.2.3 artifacts lack the configured public link.

Determine the transaction information visible in the actual recipient account before completing Data safety. The inspected app has no payment callback or records, but do not claim the developer never receives payment data. Disclose future SDK/server handling before shipping it. [Data safety payment guidance](https://support.google.com/googleplay/android-developer/answer/10787469).

Keep the page accessible without login, geofencing or a PDF download. Account deletion requirements become relevant if app accounts are added; do not invent an account system to fill the form. [Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311).

## Data safety worksheet — fill from the final implementation

This is evidence for the form, not a ready-made set of Yes/No answers. “Collected” generally means transmitted off the device, including by an SDK; local access alone is different. User-initiated transfers can qualify for a sharing exception without automatically exempting collection. Optional collection requires an actual choice for all affected users. [Play definitions and form guidance](https://support.google.com/googleplay/android-developer/answer/10787469).

| Flow | Current evidence / likely form assessment | Final verification required |
| --- | --- | --- |
| Imported documents, OCR, speech, progress | Local-only processing is not collection by itself. Privacy policy still describes the access. | Confirm no new processing/upload service was introduced. |
| ML Kit metrics | Collection of diagnostics and device/other IDs; inspect whether event data also maps to app interactions or other performance data. Diagnostic/usage purposes. | Exact SDK version and observed network behavior; required/optional status; appropriate sharing/service-provider assessment. Never select blanket “no collection.” |
| GitHub metadata reports | Optional diagnostics/crash information and app installation identifier transmitted after confirmation. | Final schema, repository recipient, purposes and whether the user-initiated sharing exception applies. |
| Input-bearing reports / exports | May include files/document information and text excerpts; individual exports may include other audio files. | Destination, actual contents, collection vs sharing and user-initiated exceptions. Private repository does not itself exempt disclosure. |
| Model downloads / webpage import | Hosts receive connection and request information. App does not derive location from an IP address in the reviewed source. | Host/SDK behavior and any retained data; do not invent location collection or promise hosts keep no logs. |
| Optional creator tip | Introduced in 0.2.2; the corrected name and new recipient apply to 0.2.3. Absent in the 0.2.1 download. Recipient/display label, INR amount and note go to the external UPI app; copy-ID writes the public recipient ID to the clipboard. No document data, callback or app transaction records. Banks/payment apps process transfers and the creator may receive transaction information. | Test the actual handoff and identify recipient-accessible transaction data. Assess applicable financial/identifier categories and user-initiated/payment-service exceptions without blanket “no data” answers. |
| Security / deletion | Current app requests use HTTPS; local deletion exists. No automatic deletion of external copies. | Validate final transport, owner deletion contact/process and any tip/support retention. Do not equate uninstall with deletion of bank or payment-service records. |
| Ads / account creation | Neither found in reviewed release. | Reconfirm final dependency graph and account flow before declaring. |

Record the signed artifact hash, SDK versions, inspection date and decisions beside the completed worksheet. Inspect network behavior during first launch, model setup, OCR and optional report sending. Test tip handoff, return and cancellation; confirm no app transaction records are created. Do not attach private book excerpts, payment credentials or identifying transaction records to public audit evidence.

## Store copy — future approved build only

**Name:** Read Assist

**Short description:** Read PDFs aloud with offline AI voices, OCR and comfortable reading tools.

**Full description draft:**

> Read Assist helps you read and listen to your PDFs, images and saved web articles.
>
> Read documents with paper themes, reader-friendly fonts including OpenDyslexic and adjustable text size. Listen with on-device AI voices and word highlighting. Choose ML Kit, PaddleOCR or TeleOCR for recognition and use the phone testbench to compare performance.
>
> The app and all these features are free, with no daily speech quota, subscription or ads.
>
> If you wish, “Buy me a coffee” in Settings lets you support the developer through an external UPI app. Contributing is optional and does not unlock any features or benefits.
>
> Speech and recognition run on your device after setup. Voice and optional OCR models require downloads and storage. Speed varies with your phone and document; complex OCR can take longer. Recognition, pronunciation and table order can contain mistakes.
>
> Choose your own documents. No Read Assist account is required. ML Kit may send technical diagnostics to Google; see our Privacy Policy for details.

This copy describes the 0.2.4 source, including the retained support dialog and published policy link; publish the listing only after final build/device checks, recipient verification and remaining account decisions. Do not attach it to older artifacts as though they contain the corrected name/new recipient; 0.2.1 also lacks the support dialog. Do not claim dyslexia treatment, financial advice, perfect OCR, guaranteed gap-free reading or universal device compatibility. Use real app screenshots, with content the developer is authorized to redistribute. Android 15+ ARM64 is the current candidate's device support; reassess for the final build.

Public developer display name: **Oh My Pi**. Support/privacy contact: **xperimenting.ai@gmail.com**. Privacy Policy: **https://sainadh812.github.io/ReadFlow/privacy.html**.

## Application terms and Console handoff

Recommended application terms: distribute the app-specific code under the owner's proprietary app license, while preserving every third-party license and source right. This is a proposed business choice, not a license already applied to the repository. A public repository and permissively licensed dependencies do not automatically give the whole app an MIT/Apache license.

The following short terms remain a proposal for owner approval; they are not presently in force. Confirm the legally responsible party before replacing `[DEVELOPER_LEGAL_NAME]`; the public display name Oh My Pi alone does not establish it:

> **Read Assist application terms**
>
> `[DEVELOPER_LEGAL_NAME]` provides Read Assist for reading and listening to documents you are authorized to use. You retain your rights in your documents. For the app-specific software, we grant you permission to install and use the app on compatible devices, subject to these terms and any applicable Google Play terms. Third-party software, models and fonts have their own licenses, available in Settings → Privacy and licenses; these terms do not restrict the rights those licenses grant.
>
> The app is free to download and all included features are available without payment or a daily speech quota. No subscription is required.
>
> Model downloads require storage and an internet connection. Subsequent local use depends on compatible hardware and installed models. Recognition, pronunciation and reading order can contain errors; verify important content against the original document. Read Assist is a reading tool, not a medical treatment or financial adviser.
>
> For support, contact xperimenting.ai@gmail.com. Nothing in these terms removes statutory rights. Data handling is described at https://sainadh812.github.io/ReadFlow/privacy.html.

Additional proposed terms for the optional support flow retained in 0.2.4:

> Voluntary contributions go directly to the creator named before payment through the payment service you choose. They do not buy app features, content, badges or services. Read Assist does not represent these contributions as tax-deductible charitable donations. Check the recipient and amount before confirming in your payment app. Payment status and processing are handled by that app and the banks involved; opening or returning from it is not a Read Assist confirmation of payment. For contribution questions or a mistaken transfer, contact xperimenting.ai@gmail.com and your payment service as appropriate. Your statutory rights remain unaffected.

Do not add unsupported warranty waivers, governing-law claims or a blanket “no refunds” promise. Confirm the owner's rights to the app-specific code before applying its license. The model/font/runtime obligations are recorded in the separate re-audit.

The public policy is ready; once the final app and remaining account facts are verified, Muse Spark should enter the free listing, Data safety answers, audience/content rating and app-access instructions. All existing features must remain accessible without payment; do not configure a Pro product, speech quota or coupon campaign. Explain the external creator-support flow accurately and demonstrate that declining it leaves all features available. Complete the media-playback foreground-service declaration with a real video showing user-started audio continuing in the background and notification controls. [Foreground-service declaration requirements](https://support.google.com/googleplay/android-developer/answer/13392821).

Stop submission if required owner placeholders, recipient verification, licensing findings or release/device checks remain unresolved. If support is removed from the final build, remove its public-policy/listing text too. Neither the old unsigned 0.2.1 AAB nor any debug-key preview APK is an upload-ready production artifact; use the final production-signed release.
