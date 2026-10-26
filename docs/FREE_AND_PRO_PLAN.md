# Read Assit free and Pro plan

**Proposal for owner review — not implemented.** The owner requested a feature plan before billing changes. Read Assit is the new displayed name; `app.readflow` and the ReadFlow repository remain unchanged. The existing 0.2.1 download has the old name and no paywall, purchases or coupon redemption.

## Recommended offer

Use one freely downloadable app with a **one-time, non-consumable Pro purchase**, without a subscription or ads. Pro unlocks all features in the release. Do not promise that this small purchase funds unlimited future cloud services.

| Feature | Free | Pro |
|---|---|---|
| Import and manually read PDFs, images and supported web articles; library, search and bookmarks | Unlimited | Unlimited |
| All reader fonts, OpenDyslexic, paper themes, saved font sizes, swipes and accessibility settings | Included | Included |
| ML Kit OCR and supported native PDF text extraction | Included | Included |
| Kokoro + Misaki speech | 15 listening minutes per day; one default voice | Unlimited listening; all bundled licensed voices |
| Word highlighting, background playback, speed controls and smooth audio buffering | Included within listening allowance | Included |
| PaddleOCR and TeleOCR | — | Included; optional model downloads |
| Prepare a chapter or selected page range before listening | — | Included |
| Basic buffer/speed information, report export, privacy and license screens | Included | Included |
| Full phone testbench and manual CPU tuning | — | Included |

OCR and speech remain on the phone after setup. Use the same speech quality and automatic performance management in both tiers. Keep all fonts and accessibility controls free; do not deliberately slow the free tier. TeleOCR remains an optional slow, memory-intensive engine, not a promise of better results on every book.

The 15-minute allowance is the proposed product decision, not a current restriction. Count time actually listened to, including cached speech, rather than generation or prefetch time. Finish the current sentence at the limit; explain the allowance before model setup and show remaining minutes. A pause, failure or benchmark must not silently spend listening minutes. Persist daily use, handle clock/date changes conservatively, and keep manual reading available. Document these rules in the implemented purchase screen.

Do not erase documents, bookmarks or accessibility preferences when a quota expires or a purchase is refunded. Existing locally recognized text remains readable. Paid model downloads must disclose storage requirements before starting.

## Prices

- **App download: free in every offered market.**
- **India Pro price: INR 10, once**, subject to the current Console accepting that price for this product.
- **Outside India: USD 0.99 base price, once**, converted to supported local currencies. Review rounding, taxes and regional minimums; it cannot mean an identical USD charge in every country.
- Use the buyer's Google Play market and the price returned by Play, not nationality, GPS, SIM country or an app-calculated conversion. Display the actual formatted purchase price.
- The amount paid by a customer is not developer proceeds; fees and taxes apply. Confirm the account's applicable fee program rather than promising a fixed margin.

Create the planned one-time product `read_assit_pro` with an active buy option. Do not set a paid download price: once an app has been distributed free, Play does not allow that same app to become a paid download. Monetization here is the in-app unlock. See [Play pricing](https://support.google.com/googleplay/android-developer/answer/6334373?hl=en) and [one-time products](https://support.google.com/googleplay/android-developer/answer/16430488).

## READASSIT promotion

The requested code should grant the same Pro features for free. **A reusable custom Google Play code is available for subscriptions, not this one-time purchase.** Non-subscription promotions use unique, single-use codes, with a combined allowance of **500 codes per app per quarter**. See [Play promotions](https://support.google.com/googleplay/android-developer/answer/6321495?hl=en-EN) and [promotion integration](https://developer.android.com/google/play/billing/promo).

Recommended implementation: `READASSIT` is the campaign name entered in the app. A small service assigns an available Google-generated Pro code; the user redeems it through Google Play. The resulting Pro purchase is restored through Play like a purchased unlock. Explain campaign dates, eligibility and limited availability before redemption. Ending the campaign does not impose a subscription on someone who already redeemed Pro.

This needs an actual secure code inventory and service, not an embedded list or `if (code == "READASSIT") unlock` flag. Rate-limit claims, make retries return the same assignment, and prevent concurrent allocation of the same code. Store no redeemable codes or credentials in Git. The precise anti-abuse identifiers and retention policy must be settled with the service design before collecting them.

The 500-code limit is a limit of this recommended **Google Play promotion mechanism**, not a claim that developers can never grant complimentary access by other means. An unlimited owner-operated entitlement system would be a separate design with its own restoration, privacy and policy assessment; it is not included or claimed compliant here.

## Implementation and acceptance after the plan is accepted

1. Add a single entitlement layer for free, purchased Pro and redeemed Pro. Enforce it consistently in UI and background actions; do not change the OCR/speech model identities just to add billing or rename the app.
2. Integrate the supported Play Billing library and a purchase-verification service. Grant Pro only for verified `PURCHASED` transactions, never `PENDING`; acknowledge purchases on time and never consume this non-consumable product. Query existing purchases on startup/resume and provide Restore purchases. Handle cancellations, already-owned products, refunds and revocations. See [billing integration](https://developer.android.com/google/play/billing/integrate) and [billing security](https://developer.android.com/google/play/billing/security).
3. Cache a validated entitlement so ordinary offline reading works after activation. Keep existing Pro access through transient network failures; reconcile changes when online. Account changes and restored purchases need explicit tests. No separate Read Assit account is proposed for ordinary purchases.
4. Implement the listening allowance and campaign flow, including exhaustion, expired/used codes, retry and offline messaging. Reviewer access must use a documented real flow, not a hidden review-only bypass. Follow [Play's payments policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en).
5. Test free and Pro on an internal Play track: local pricing, purchase/restore/redeem, pending and cancelled purchases, reinstall, refund/revocation, daily allowance, document preservation and continued offline speech. A debug APK or mocked billing result cannot establish that Play delivery works.
6. Update public/in-app privacy text and Data safety for the actual purchase and promotion services. Re-audit the changed dependencies, notices and exact signed AAB; preserve the eSpeak-free release path. Run the existing regression/build checks and target-device acceptance.

The [license re-audit](PLAY_REAUDIT_2026-10-26.md), [privacy/store draft](PRIVACY_AND_STORE_DRAFT.md) and [Muse Spark handoff](PLAY_STORE_HANDOFF.md) separate the completed review from the remaining implementation and owner-account details. No production-ready monetized artifact exists yet.
