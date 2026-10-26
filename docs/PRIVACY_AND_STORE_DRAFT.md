# Read Assist: privacy, Data safety and store draft

**DRAFT — not ready to publish. Reviewed 2026-10-26.** The current candidate source is **Read Assist 0.2.3**, package `app.readflow`, version code 19, with host validation recorded in the 0.2.3 evidence and device/production checks still pending. Historical 0.2.2 evidence covers that earlier Read Assit-branded build and previous recipient, not this corrected version. The older ReadFlow `0.2.1` download, version code 17, does not contain the new tip dialog. Keep `app.readflow` for updates and use **Read Assist** (Assist has a double “s”) for the new public name. All existing features remain free. Paid tiers, prices and coupons are deferred. The source now includes optional external UPI support, with no payment SDK/backend, paid entitlement or daily speech quota.

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

## Owner facts needed

Replace every placeholder below with verified information. Unresolved implementation choices go back to the coding agent, not into public text.

| Placeholder / decision | What is needed |
| --- | --- |
| `[DEVELOPER_LEGAL_NAME]` | Real developer/person/company responsible for the app; consistent with the Play listing. |
| `[PRIVACY_CONTACT]`, `[SUPPORT_CONTACT]` | Working owner-approved contact addresses or inquiry mechanism. |
| `[PRIVACY_POLICY_URL]`, `[EFFECTIVE_DATE]` | Public, globally accessible HTTPS HTML policy and its effective date. Supply the URL through `READFLOW_PRIVACY_POLICY_URL` in the final build. |
| `[OWNER_REPORT_RETENTION_AND_DELETION]` | How the developer handles voluntarily received reports, when they are deleted, how users request deletion, and any necessary exceptions. Local deletion does not delete GitHub comments or recipients' copies. |
| `[TIP_RECIPIENT_NAME]` and verification of `readassist@ybl` | Real creator identity and confirmation that the owner has registered/changed this new ID with their provider and it belongs to the intended recipient. Check the payment-app-displayed recipient before shipping. Never place banking secrets in the repo. |
| `[OWNER_TIP_DATA_AND_RETENTION]` | What transaction information the creator actually receives, its purpose, retention/deletion rules and payment-help contact. Do not invent fields, periods or a verification backend. |
| Optional tip device verification | Test the implemented external intent/copy flow against installed UPI apps and verify the recipient. Re-audit before adding a callback, payment SDK, transaction storage or server. |
| Audience / territories | Actual intended audience, launch countries and contact/business disclosures required by the account. Do not select a children-directed audience without assessing the additional obligations. |
| Application terms | Owner-approved application license/EULA and voluntary contribution/payment-help terms. Third-party license texts remain separate. |

## Privacy Policy — draft public text

**Privacy Policy for Read Assist**

Effective: `[EFFECTIVE_DATE]`

Developer: `[DEVELOPER_LEGAL_NAME]`

Privacy contact: `[PRIVACY_CONTACT]`

Read Assist is a document reading and text-to-speech app, formerly called ReadFlow. Its Android package is `app.readflow`. This policy describes the app's data handling. Contact us using the details above for privacy questions.

**Documents and reading.** You choose the PDF files, images and webpages to import. The app stores imported content, extracted text, generated speech, reading progress and settings in its private storage on your device. OCR and speech generation run locally. Your document content is not uploaded to model hosts for these operations. No Read Assist account is required. The app contains no advertising SDK or developer-operated analytics service.

**Internet connections.** Downloading speech, alignment and optional OCR models contacts the model hosts identified in the app, including Hugging Face and GitHub and their download infrastructure. Importing a webpage contacts the website you choose. Those services receive connection/request information such as your IP address and requested resource and apply their own privacy policies. These app requests use HTTPS. After the required models and content are downloaded, local reading and speech do not require a document-processing server.

**Google ML Kit.** The app includes Google's ML Kit text-recognition SDK. Google states that ML Kit processes input images/text on the device rather than sending that input and its recognition results to Google. ML Kit sends technical diagnostics and usage metrics, including device/app information, installation identifiers, performance measurements, configuration, input/output sizes, events and error codes. See [Google's ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and [terms/privacy information](https://developers.google.com/ml-kit/terms). Local processing does not mean the app or its SDKs never transmit data.

**Error reports and benchmarks.** The app saves bounded issue reports and benchmark results locally. Reports may contain document excerpts, source identifiers, device/configuration information, error details and generated speech. The local issue store retains at most 20 reports within a 24 MiB limit; older reports can be removed as these limits are reached. You can review, export or delete reports. Individual exports may include retained audio; combined history exports exclude audio.

GitHub uploads occur only after you configure a destination and confirm sending. Reduced reports exclude document text, titles, source URLs, error messages and audio, but retain technical data and an installation identifier. Text excerpts can be uploaded only to a private repository. GitHub uploads do not include original document files or audio. GitHub credentials are encrypted on the device using Android Keystore and can be removed in the app. Copies sent to another app, repository or recipient are subject to that destination's handling. Reports received by the developer are handled as follows: `[OWNER_REPORT_RETENTION_AND_DELETION]`.

**Storage and deletion.** Deleting a document through the library removes its local content, associated speech cache and issue reports. Reports and downloaded models can also be deleted through their respective screens. Remaining local app data can be removed by clearing app storage or uninstalling. Android backup and device transfer of app data are disabled. Files exported outside the app and copies already sent to GitHub or another recipient are not removed by these local controls. Contact `[PRIVACY_CONTACT]` about copies held by the developer. Other services handle their own records under their policies.

**Security and changes.** We use app-private storage, HTTPS for the app's network requests and Android Keystore protection for saved GitHub credentials. This does not describe document storage as end-to-end encrypted or promise that recipient-controlled exports have the same protections. We will update this policy when the app's practices change and identify the updated effective date here.

### Voluntary support — draft public text for 0.2.3

The support flow is retained from 0.2.2 with the corrected name and new recipient in 0.2.3. Resolve the owner identity/transaction-handling placeholders, confirm provider registration and verify the final build before publishing this section; the new recipient is not present in older downloads:

> You may choose to send an optional contribution directly to `[TIP_RECIPIENT_NAME]` at `readassist@ybl` using your payment app. Contributing does not unlock features, perks or priority support. Read Assist passes the recipient ID, the display label “Read Assist,” your selected INR amount and an optional-tip note to the external UPI app. You can instead copy the recipient ID to your clipboard and enter it in your payment app. No document information is included. Read Assist does not receive payment callbacks, verify payment status or store transaction records. Your payment app and the payer's and recipient's banks process the payment under their own terms and privacy policies. The creator receives transaction information through the recipient's payment service: `[OWNER_TIP_DATA_AND_RETENTION]`. Read Assist does not ask for or handle your UPI PIN, bank password or account-access credentials. Check the recipient name, ID and amount in your payment app before confirming. Returning to Read Assist is not confirmation that a transfer succeeded; check your payment app for its status. Contact `[SUPPORT_CONTACT]` for contribution questions.

Determine the transaction information visible to the creator in their recipient account before publishing this section or answering Data safety. Although the inspected app has no transaction callback or records, do not claim “we receive no payment data” for the developer's wider handling. Do not assume Read Assist accesses the credentials that the bank/payment app handles directly. Disclose any future SDK/server and its data handling before shipping it. [Data safety payment guidance](https://support.google.com/googleplay/android-developer/answer/10787469).

The public page must be clearly labeled a Privacy Policy, include the developer/contact and retention/deletion information, and be publicly accessible without login, geofencing or a PDF download. Keep the in-app disclosure, public page and Play answers consistent. Account deletion requirements become relevant if app account creation is added; do not invent an account system just to fill the form. [Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311).

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

This copy describes the 0.2.3 source, including the retained support dialog and new recipient; publish it only after final build/device checks, recipient verification and owner policy completion. Do not attach it to older artifacts as though they contain the corrected name/new recipient; 0.2.1 also lacks the support dialog. Do not claim dyslexia treatment, financial advice, perfect OCR, guaranteed gap-free reading or universal device compatibility. Use real app screenshots, with content the developer is authorized to redistribute. Android 15+ ARM64 is the current candidate's device support; reassess for the final build.

Support contact: `[SUPPORT_CONTACT]`. Privacy Policy: `[PRIVACY_POLICY_URL]`.

## Application terms and Console handoff

Recommended application terms: distribute the app-specific code under the owner's proprietary app license, while preserving every third-party license and source right. This is a proposed business choice, not a license already applied to the repository. A public repository and permissively licensed dependencies do not automatically give the whole app an MIT/Apache license.

The following short terms are ready for owner review once the contact details are final; they are not presently in force:

> **Read Assist application terms**
>
> `[DEVELOPER_LEGAL_NAME]` provides Read Assist for reading and listening to documents you are authorized to use. You retain your rights in your documents. For the app-specific software, we grant you permission to install and use the app on compatible devices, subject to these terms and any applicable Google Play terms. Third-party software, models and fonts have their own licenses, available in Settings → Privacy and licenses; these terms do not restrict the rights those licenses grant.
>
> The app is free to download and all included features are available without payment or a daily speech quota. No subscription is required.
>
> Model downloads require storage and an internet connection. Subsequent local use depends on compatible hardware and installed models. Recognition, pronunciation and reading order can contain errors; verify important content against the original document. Read Assist is a reading tool, not a medical treatment or financial adviser.
>
> For support, contact `[SUPPORT_CONTACT]`. Nothing in these terms removes statutory rights. Data handling is described at `[PRIVACY_POLICY_URL]`.

Additional terms for the optional 0.2.3 support flow:

> Voluntary contributions go directly to the creator named before payment through the payment service you choose. They do not buy app features, content, badges or services. Read Assist does not represent these contributions as tax-deductible charitable donations. Check the recipient and amount before confirming in your payment app. Payment status and processing are handled by that app and the banks involved; opening or returning from it is not a Read Assist confirmation of payment. For contribution questions or a mistaken transfer, contact `[SUPPORT_CONTACT]` and your payment service as appropriate. Your statutory rights remain unaffected.

Do not add unsupported warranty waivers, governing-law claims or a blanket “no refunds” promise. Confirm the owner's rights to the app-specific code before applying its license. The model/font/runtime obligations are recorded in the separate re-audit.

Once the final app and policy are ready, Muse Spark should enter the free listing, Data safety answers, audience/content rating and app-access instructions. All existing features must remain accessible without payment; do not configure a Pro product, speech quota or coupon campaign. Explain the external creator-support flow accurately and demonstrate that declining it leaves all features available. Complete the media-playback foreground-service declaration with a real video showing user-started audio continuing in the background and notification controls. [Foreground-service declaration requirements](https://support.google.com/googleplay/android-developer/answer/13392821).

Stop submission if required owner placeholders, recipient verification, licensing findings or release/device checks remain unresolved. If support is removed from the final build, remove its public-policy/listing text too. Neither the old unsigned 0.2.1 AAB nor any debug-key preview APK is an upload-ready production artifact; use the final production-signed release.
