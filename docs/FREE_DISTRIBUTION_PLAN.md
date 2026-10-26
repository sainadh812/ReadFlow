# Read Assist free distribution and optional support

The owner has chosen **free distribution first**. All existing release features remain available: reading, fonts/themes, all offered voices, unlimited local speech, ML Kit/PaddleOCR/TeleOCR, chapter preparation, performance controls and the testbench. No daily speech quota, paid tier, subscription, ads or coupon is being added. Model downloads, storage and hardware limits still apply.

Paid features and the previous INR 10 / USD 0.99 Pro proposal are deferred. Once offered free, the same Play app cannot become a paid download; a later in-app upgrade remains a separate possible design. [Play pricing rules](https://support.google.com/googleplay/android-developer/answer/6334373?hl=en).

## Optional coffee support

Add **Settings → Buy me a coffee** for voluntary support to the developer. The owner supplied the new public recipient **`readassist@ybl`** and will register/change it with their UPI provider. This is a configured address, not proof of ownership, completed registration or successful payment. Read Assist cannot create or reserve a UPI ID. Confirm provider registration and verify the bank-resolved recipient before publishing; neither is established by the app update.

The support flow shows the recipient and INR amount, with choices of INR 10, 50 or 100 and a custom amount from INR 1 to 1,000. The user explicitly opens a compatible UPI app and confirms there. Copy UPI ID is always available for manual entry. No QR code, card provider, payment SDK, merchant account, transaction backend or payment-verification service is added. Only users with a compatible UPI setup can use this option; it is not a worldwide card-payment solution.

The app must never infer payment success from launching another app or returning to Read Assist. Payment history/confirmation stays with the user's payment app and bank. There are no purchase entitlements to grant. Do not collect a PIN, bank credentials, payment receipts or transaction results in Read Assist, and do not put document data or installation identifiers in payment requests. If the selected app rejects a direct intent, show the existing manual-copy route rather than inventing merchant credentials or bypassing bank checks. Google's documented intent integration is intended for eligible merchants; recipient/provider support still requires a phone check. [Google UPI integration](https://developers.google.com/pay/india/api/android/in-app-payments), [receiving with an existing UPI ID](https://support.google.com/pay/india/answer/7296412?hl=en).

Google's direct-tip exception requires the creator to receive the entire contribution and the payer to receive no digital benefit. The feature is designed around those conditions: no unlock, badge, extra minutes, priority service or different app behavior. It is voluntary support, not a charitable or tax-deductible donation claim. This is not an exception for selling paid features through UPI. [Play payment-policy guidance](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en-EN).

## Distribution work

1. Produce Read Assist 0.2.3 (version code 19) with the corrected name and new `readassist@ybl` recipient, preserving the optional support option and corrected bundled notices. Preserve `app.readflow`, model identities and existing documents/settings. The prototype preview retains the prior test certificate; it is not the production signing identity.
2. Publish a clearly labeled test APK and unsigned review AAB on GitHub. No payment or real device behavior is established by a host build.
3. Complete actual owner/contact/public-policy information, the final artifact license review, production signing and the required Play/device tests. Billing implementation is **not** a prerequisite for this free release.
4. Test the support screen, visible address/amount, invalid custom amounts, cancellation, missing UPI app, manual copy, actual recipient and return behavior on a phone. Testing must not imply a payment is required to use or review the app. Do not make a real transfer without the payer's explicit confirmation.
5. Use the [privacy/store worksheet](PRIVACY_AND_STORE_DRAFT.md) and [publishing handoff](PLAY_STORE_HANDOFF.md). Muse Spark fills verified Console facts; it should not invent a support identity or mark unchecked requirements complete.

The model/font/runtime review is in [the dated license audit](PLAY_REAUDIT_2026-10-26.md). The future final signed Play artifact must preserve those notices and source rights. Optional support does not remove privacy, licensing or testing obligations.
