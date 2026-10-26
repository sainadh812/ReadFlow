# Read Assit: privacy, Data safety and store draft

**DRAFT — not ready to publish. Reviewed 2026-10-26.** The existing `0.2.1` candidate is branded ReadFlow, package `app.readflow`, version code 17. Keep that package for updates; the requested public name is **Read Assit**, exactly as spelled. The reviewed code has no Play Billing, paid entitlement, speech allowance or coupon implementation. Nothing in this document means those features already exist.

Muse Spark should fill verified owner/account information and enter the final approved materials in Play Console. It must not invent policy facts, enable sales against the old candidate, or substitute a paid-download listing for the proposed free app.

## Proposed offer — awaiting approval and implementation

| Item | Proposed behavior |
| --- | --- |
| Download | Free; no subscription or ads. |
| Free | Document reading, ML Kit OCR, all reader themes/fonts/accessibility controls, and 15 minutes of AI speech per day. Define and test allowance accounting/reset behavior before advertising it. |
| Pro | One non-consumable, one-time purchase: unlimited speech, all offered voices, PaddleOCR, TeleOCR and advanced benchmarks. “Unlimited” removes the app's allowance; it does not promise unlimited phone storage, speed or perfect recognition. |
| Price | Owner's target: ₹10 in India; US$0.99 outside India expressed through regional local-currency prices. Confirm allowed prices, taxes and territories in Play Console. Use the actual Play checkout price in the app. |
| Promotion | `READASSIT` is a proposed campaign name distributing individual Google Play promo codes for Pro. It is not a universal hardcoded unlock password. Define availability, distribution and expiration before announcing the campaign. |

Use Google Play Billing for the digital upgrade. One-time products can be non-consumable; Play promotions can grant an eligible product at no charge. Code redemption and purchase restoration must use the implemented entitlement flow. [One-time products](https://developer.android.com/google/play/billing/one-time-products), [promotions](https://support.google.com/googleplay/android-developer/answer/6321495), [Payments policy guidance](https://support.google.com/googleplay/android-developer/answer/10281818).

## Owner facts needed

Replace every placeholder below with verified information. Unresolved implementation choices go back to the coding agent, not into public text.

| Placeholder / decision | What is needed |
| --- | --- |
| `[DEVELOPER_LEGAL_NAME]` | Real developer/person/company responsible for the app; consistent with the Play listing. |
| `[PRIVACY_CONTACT]`, `[SUPPORT_CONTACT]` | Working owner-approved contact addresses or inquiry mechanism. |
| `[PRIVACY_POLICY_URL]`, `[EFFECTIVE_DATE]` | Public, globally accessible HTTPS HTML policy and its effective date. Supply the URL through `READFLOW_PRIVACY_POLICY_URL` in the final build. |
| `[OWNER_REPORT_RETENTION_AND_DELETION]` | How the developer handles voluntarily received reports, when they are deleted, how users request deletion, and any necessary exceptions. Local deletion does not delete GitHub comments or recipients' copies. |
| Purchase / allowance implementation | Whether verification uses a server, exact data sent/stored, processors, retention and deletion; how the daily allowance is stored. Do not assume either a backend or client-only design. |
| Audience / territories | Actual intended audience, launch countries and contact/business disclosures required by the account. Do not select a children-directed audience without assessing the additional obligations. |
| Application terms | Owner-approved application license/EULA, purchase/support/refund terms and treatment of already purchased access. Third-party license texts remain separate. |

## Privacy Policy — draft public text

**Privacy Policy for Read Assit**

Effective: `[EFFECTIVE_DATE]`

Developer: `[DEVELOPER_LEGAL_NAME]`

Privacy contact: `[PRIVACY_CONTACT]`

Read Assit is a document reading and text-to-speech app, formerly called ReadFlow. Its Android package is `app.readflow`. This policy describes the app's data handling. Contact us using the details above for privacy questions.

**Documents and reading.** You choose the PDF files, images and webpages to import. The app stores imported content, extracted text, generated speech, reading progress and settings in its private storage on your device. OCR and speech generation run locally. Your document content is not uploaded to model hosts for these operations. No Read Assit account is required. The app contains no advertising SDK or developer-operated analytics service.

**Internet connections.** Downloading speech, alignment and optional OCR models contacts the model hosts identified in the app, including Hugging Face and GitHub and their download infrastructure. Importing a webpage contacts the website you choose. Those services receive connection/request information such as your IP address and requested resource and apply their own privacy policies. These app requests use HTTPS. After the required models and content are downloaded, local reading and speech do not require a document-processing server.

**Google ML Kit.** The app includes Google's ML Kit text-recognition SDK. Google states that ML Kit processes input images/text on the device rather than sending that input and its recognition results to Google. ML Kit sends technical diagnostics and usage metrics, including device/app information, installation identifiers, performance measurements, configuration, input/output sizes, events and error codes. See [Google's ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and [terms/privacy information](https://developers.google.com/ml-kit/terms). Local processing does not mean the app or its SDKs never transmit data.

**Error reports and benchmarks.** The app saves bounded issue reports and benchmark results locally. Reports may contain document excerpts, source identifiers, device/configuration information, error details and generated speech. The local issue store retains at most 20 reports within a 24 MiB limit; older reports can be removed as these limits are reached. You can review, export or delete reports. Individual exports may include retained audio; combined history exports exclude audio.

GitHub uploads occur only after you configure a destination and confirm sending. Reduced reports exclude document text, titles, source URLs, error messages and audio, but retain technical data and an installation identifier. Text excerpts can be uploaded only to a private repository. GitHub uploads do not include original document files or audio. GitHub credentials are encrypted on the device using Android Keystore and can be removed in the app. Copies sent to another app, repository or recipient are subject to that destination's handling. Reports received by the developer are handled as follows: `[OWNER_REPORT_RETENTION_AND_DELETION]`.

**Storage and deletion.** Deleting a document through the library removes its local content, associated speech cache and issue reports. Reports and downloaded models can also be deleted through their respective screens. Remaining local app data can be removed by clearing app storage or uninstalling. Android backup and device transfer of app data are disabled. Files exported outside the app and copies already sent to GitHub or another recipient are not removed by these local controls. Contact `[PRIVACY_CONTACT]` about copies held by the developer. Other services handle their own records under their policies.

**Security and changes.** We use app-private storage, HTTPS for the app's network requests and Android Keystore protection for saved GitHub credentials. This does not describe document storage as end-to-end encrypted or promise that recipient-controlled exports have the same protections. We will update this policy when the app's practices change and identify the updated effective date here.

### Conditional future purchase section — do not publish unchanged

Include a finalized version only after Billing and entitlement verification are implemented and inspected:

> Pro is an optional one-time purchase processed by Google Play. Google handles checkout and payment details under its terms. Read Assit `[EXACT PURCHASE/ENTITLEMENT DATA ACCESSED]` to grant, verify and restore access. `[EXACT DATA SENT OFF DEVICE, RECIPIENTS, PURPOSES AND SECURITY — OR VERIFIED CLIENT-ONLY BEHAVIOR]`. `[RETENTION AND DELETION RULES]`. Promotional access uses individual Play redemption codes and the same entitlement handling. The free speech allowance uses `[EXACT LOCAL OR SERVER DATA, RESET BEHAVIOR AND RETENTION]`.

Do not say the app receives card details if it does not. Conversely, purchase tokens/history sent to a verification service must not be omitted because Google handles the payment. Google explains the distinction in its [Data safety payment guidance](https://support.google.com/googleplay/android-developer/answer/10787469).

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
| Purchases / promotions | Absent in 0.2.1. Future purchase history/tokens/entitlement verification may introduce collection. | Inspect Billing plus any backend before selecting categories, purposes, optionality or retention answers. |
| Daily speech allowance | Absent in 0.2.1. | Decide and document whether accounting is local or transmitted, then assess app-interaction/identifier data accordingly. |
| Security / deletion | Current app requests use HTTPS; local deletion exists. No automatic deletion of external copies. | Validate final transport, owner deletion contact/process and any purchase/support retention. Do not equate uninstall with deletion from all services. |
| Ads / account creation | Neither found in reviewed release. | Reconfirm final dependency graph and account flow before declaring. |

Record the signed artifact hash, SDK versions, inspection date and decisions beside the completed worksheet. Inspect network behavior during first launch, model setup, OCR, purchases/restoration and optional report sending. Do not attach private book excerpts or purchase tokens to public audit evidence.

## Store copy — future approved build only

**Name:** Read Assit

**Short description:** Read PDFs aloud with offline AI voices, OCR and comfortable reading tools.

**Full description draft:**

> Read Assit helps you read and listen to your PDFs, images and saved web articles.
>
> The free app includes document reading, ML Kit text recognition, paper themes, reader-friendly fonts including OpenDyslexic, adjustable text size and 15 minutes of AI speech each day.
>
> An optional one-time Pro purchase unlocks unlimited AI speech, all offered voices, PaddleOCR, TeleOCR and advanced phone benchmarks. No subscription or ads. The app shows your local price before checkout.
>
> Speech and recognition run on your device after setup. Voice and optional OCR models require downloads and storage. Speed varies with your phone and document; complex OCR can take longer. Recognition, pronunciation and table order can contain mistakes.
>
> Choose your own documents. No Read Assit account is required. ML Kit may send technical diagnostics to Google; see our Privacy Policy for details.

Publish that copy only after the described free/Pro features and allowance are approved, implemented and tested. Do not publish a promise that the shared word `READASSIT` universally unlocks Pro. Do not claim dyslexia treatment, financial advice, perfect OCR, guaranteed gap-free reading or universal device compatibility. Use real app screenshots, with content the developer is authorized to redistribute. Android 15+ ARM64 is the current candidate's device support; reassess for the final build.

Support contact: `[SUPPORT_CONTACT]`. Privacy Policy: `[PRIVACY_POLICY_URL]`.

## Application terms and Console handoff

Recommended application terms: distribute the app-specific code under the owner's proprietary app license, while preserving every third-party license and source right. This is a proposed business choice, not a license already applied to the repository. A public repository and permissively licensed dependencies do not automatically give the whole app an MIT/Apache license.

The following short terms are ready for owner review once the offer and contact details are final; they are not presently in force:

> **Read Assit application terms**
>
> `[DEVELOPER_LEGAL_NAME]` provides Read Assit for reading and listening to documents you are authorized to use. You retain your rights in your documents. For the app-specific software, we grant you permission to install and use the app on compatible devices, subject to these terms and any applicable Google Play terms. Third-party software, models and fonts have their own licenses, available in Settings → Privacy and licenses; these terms do not restrict the rights those licenses grant.
>
> The app is free to download. The optional one-time Pro purchase unlocks the Pro features described at checkout, without a recurring subscription. A valid promotional redemption unlocks the same features. Purchases and restoration use Google Play. A refunded or revoked purchase may remove Pro access; it does not remove your local documents or free reading access. Regional prices and any applicable taxes are shown before purchase. The free speech allowance and promotion conditions are disclosed in the app.
>
> Model downloads require storage and an internet connection. Subsequent local use depends on compatible hardware and installed models. Recognition, pronunciation and reading order can contain errors; verify important content against the original document. Read Assit is a reading tool, not a medical treatment or financial adviser.
>
> For support or purchase issues, contact `[SUPPORT_CONTACT]`. Refunds are handled through the applicable Google Play process and any rights required by consumer law. Nothing in these terms removes statutory rights. Data handling is described at `[PRIVACY_POLICY_URL]`.

Do not add unsupported warranty waivers, governing-law claims or a blanket “no refunds” promise. Confirm the owner's rights to the app-specific code before applying its license. The model/font/runtime obligations are recorded in the separate re-audit.

Once the final app and policy are ready, Muse Spark should enter the approved free listing, Pro product/local prices, Data safety answers, audience/content rating and app-access instructions. Provide reviewers access to paid features using a tested review mechanism. Complete the media-playback foreground-service declaration with a real video showing user-started audio continuing in the background and notification controls. [Foreground-service declaration requirements](https://support.google.com/googleplay/android-developer/answer/13392821).

Stop submission if owner placeholders, purchase implementation, licensing findings or release/device checks remain unresolved. The unsigned 0.2.1 AAB and debug-key preview APK are not upload-ready commercial artifacts.
