# Publishing the Read Assist privacy policy

Status: **21 November 2026 clarification published and verified.** The [public URL](https://sainadh812.github.io/ReadFlow/privacy.html) returns HTTP 200 and exactly matches the current [source](../site/privacy.html). The clarification moves the current app's free, payment-free status near the top and replaces obsolete payment-handoff mechanics with a shorter historical-record disclosure. Record types, purposes, retention/deletion and provider responsibility remain covered. SDK and support disclosures are unchanged. See [publication evidence](evidence/privacy-policy/publication.json), including earlier publication records.

**The verified 0.3.6/code 32 APK and AAB now contain the clarified in-app notice.** [Signed build 44876273082](https://github.com/sainadh812/ReadFlow/actions/runs/44876273082), source `27ae8d749abd83b44fade7cbb6a14add85660456`, passed; [independent artifact verification](evidence/reader-0.3.6/signed-artifact-verification.json) confirms the embedded notice matches current source, SHA-256 `eae7c4616edaf3259f1d5156574833c522761e33e638f39f36d36b71b910544b`. The [GitHub release](https://github.com/sainadh812/ReadFlow/releases/tag/v0.3.6-play-candidate) is published. The historical [Play publication report](evidence/reader-0.3.6/play-publication.json), completed at 2026-11-21T06:51:03.748894+00:00, records an accepted commit and exact AAB hash readback: Internal was published and alpha was staged. **After the owner-reported fourth rejection at 12:45 IST, a fresh inspection at 12:48 IST confirmed alpha code 32 is `NOT_APPROVED`.** See the [fourth-rejection evidence](evidence/play-review-2026-11-21/fourth-rejection-reported.json). A subsequent read-only inspection at **12:54 IST confirms alpha code 32 is now IN_REVIEW**, matching the owner-reported 12:52 Console audit. See the [audit and latest API checkpoint](evidence/play-review-2026-11-21/console-audit-1252-reported.json). Preserve the active review; no approval is established. The original pending-submission instruction is superseded. The audit reports no Financial info types and corrected saved Financial features answers, while its overview still shows the earlier selection. A classification appeal is prepared if the issue persists; neither the cause of that mismatch nor the values used for the earlier enforcement are established. The already signed 0.3.5/code 31 artifacts retain the earlier notice. Both versions state that contributions were removed in 0.3.5. This build does not change the verified public website or its hash. There is no evidence that privacy wording caused the repeated account-type rejection. Submitted Console declarations and the exact enforcement reference remain separate checks in the [publishing handoff](PLAY_STORE_HANDOFF.md).

The developer remains **Oh My Pi**, with **xperimenting.ai@gmail.com** for privacy, support and deletion requests. Support correspondence/reports received by the developer are deleted within 90 days after resolution; payment records remain only as needed for accounting/legal requirements. Firebase's separate crash/identifier retention is stated explicitly. Version 0.2.5/code 21 retains the default URL first added in 0.2.4; 0.2.3 artifacts lack that link.

The public-only page is [site/privacy.html](../site/privacy.html). Its owner placeholders and draft notice have been removed. It includes GitHub Pages' visitor-IP disclosure and uses local CSS/system fonts, with no scripts, external fonts or analytics. Do not publish the internal Data safety worksheet as the public policy.

## Future policy updates

1. Preserve the approved developer/contact details; change them only from owner-confirmed facts. Update the effective date for changed practices.
2. Check support, Firebase and payment retention separately. Keep the configured/unconfigured reporting distinction, default-off consent, next-launch behavior, possible pending-report delivery and incidental native-runtime-data disclosure. Do not promise instant SDK shutdown, confirmed deletion or content-free minidumps.
3. Check the page against [the privacy worksheet](PRIVACY_AND_STORE_DRAFT.md), the actual app and the owner's practices. The source's `data-publication-status="published"` marker denotes final public-facing text accepted by the validator, not proof of deployment; future revisions must retain pending status until live verification succeeds.
4. Run `python3 scripts/verify_privacy_page.py`. The check rejects unresolved placeholders and drafts; it does not verify email ownership or replace a factual/legal review.

## Publish and verify

From the repository root, with the existing GitHub owner access:

```bash
python3 scripts/publish_privacy_site.py
```

The script first verifies the page, then commits only `privacy.html`, an index link and `.nojekyll` to a dedicated `gh-pages` branch. It preserves existing branch history and refuses to replace an unrelated homepage/Pages configuration. It configures GitHub Pages to serve that branch's root with HTTPS. It does not change the app branch, expose the internal docs directory or build/upload an Android artifact.

Verified public URL:
`https://sainadh812.github.io/ReadFlow/privacy.html`

After GitHub's Pages deployment finishes:

```bash
python3 scripts/verify_privacy_page.py --url https://sainadh812.github.io/ReadFlow/privacy.html
```

This performs an unauthenticated HTTPS GET and checks HTTP 200, HTML content type, the complete page and an exact byte match with the finalized source. **Current verification passed at 2026-11-21T06:23:14Z:** effective 21 November 2026, `gh-pages` commit `91a57d528da4002b569bcb9700019575b3e961e0`, SHA-256 `079f67e9be93b73fa1c4256cd5850073415b8817495b777a637513a42b894e9f`. Pages reported `built`. The earlier 21 November policy hash is linked in the evidence; the 15 November and October publication records are preserved under `previousPublication`.

The previous 26 October page was verified at commit `2e3aa9c1092dc20d1bc72adc1b45d0aa8b6e9a93`, SHA-256 `48483d2dc25a971765d77c553cfeb6b242c47c7bb6851dfd4a28bee2a1fbc1d1`; its record is preserved under `previousPublication`. Also open the current URL in a signed-out browser on the phone. Repeat and record verification after future changes; a deployment request alone is not evidence that the URL works.

## Include the verified URL in the final AAB

Only after the live check succeeds, use the existing config secret with the prepared [GitHub release build](GITHUB_RELEASE_BUILD.md), which materializes `app/google-services.json` and the upload key outside Git, or configure the equivalent local environment from [the publishing handoff](PLAY_STORE_HANDOFF.md), then run:

```bash
export READFLOW_PRIVACY_POLICY_URL=https://sainadh812.github.io/ReadFlow/privacy.html
python3 scripts/verify_privacy_page.py --url "$READFLOW_PRIVACY_POLICY_URL" && \
  ./gradlew :app:bundleRelease :app:testReleaseUnitTest :app:lintRelease -PplayPublication=true --console=plain
```

Gradle embeds this value as `BuildConfig.PRIVACY_POLICY_URL`; from 0.2.4 it also defaults to this URL when the environment override is unset. Settings → Privacy and licenses shows the public-policy link when the value is nonempty. Version 0.2.5 publication builds also require valid Firebase client configuration. Final publication requires secure signing, final license/artifact checks, consent/network and crash-delivery tests, symbol upload and device/Play acceptance. Confirm the link opens from a Play-delivered installation and use it in Play Console. Rebuilding creates a new artifact/hash; an earlier preview does not gain code or configuration automatically.

Primary references: [GitHub Pages branch publishing](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site), [Pages API](https://docs.github.com/en/rest/pages/pages), [Pages visitor data](https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages#data-collection), [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311).
