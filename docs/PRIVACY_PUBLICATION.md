# Publishing the Read Assist privacy policy

Status: **Published and verified on 2026-10-26.** The [live privacy policy](https://sainadh812.github.io/ReadFlow/privacy.html) identifies the developer as **Oh My Pi** and uses **xperimenting.ai@gmail.com** for privacy, support and deletion requests. The owner approved deleting support reports within 90 days after resolution and retaining payment records only as needed for accounting/legal requirements. The existing 0.2.3 APK/AAB were built without a public policy URL; 0.2.4/code 20 embeds the verified URL by default.

The public-only page is [site/privacy.html](../site/privacy.html). Its owner placeholders and draft notice have been removed. It includes GitHub Pages' visitor-IP disclosure and uses local CSS/system fonts, with no scripts, external fonts or analytics. Do not publish the internal Data safety worksheet as the public policy.

## Future policy updates

1. Fill the developer name, working public privacy/support email and effective date from owner-confirmed facts.
2. Fill report retention/deletion and the payment information received by the creator, its purpose and retention. The app's lack of payment callbacks does not mean the recipient receives no information through their bank/payment app.
3. Check the page against [the privacy worksheet](PRIVACY_AND_STORE_DRAFT.md), the actual app and the owner's practices. Remove the visible draft paragraph and set the HTML `data-publication-status` to `published` only after the text is final.
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

This performs an unauthenticated HTTPS GET and checks HTTP 200, HTML content type, the complete page and an exact byte match with the finalized source. That check passed for `gh-pages` commit `2e3aa9c1092dc20d1bc72adc1b45d0aa8b6e9a93`, effective 26 October 2026, with SHA-256 `48483d2dc25a971765d77c553cfeb6b242c47c7bb6851dfd4a28bee2a1fbc1d1`. Also open it in a signed-out browser on the phone. Record updated verification evidence after future edits. A deployment request alone is not evidence that the URL works.

## Include the verified URL in the final AAB

Only after the live check succeeds, configure the owner's secure production signing environment from [the publishing handoff](PLAY_STORE_HANDOFF.md), then run:

```bash
export READFLOW_PRIVACY_POLICY_URL=https://sainadh812.github.io/ReadFlow/privacy.html
python3 scripts/verify_privacy_page.py --url "$READFLOW_PRIVACY_POLICY_URL" && \
  ./gradlew :app:bundleRelease :app:testReleaseUnitTest :app:lintRelease -PplayPublication=true --console=plain
```

Gradle embeds this value as `BuildConfig.PRIVACY_POLICY_URL`; from 0.2.4 it also defaults to this verified URL when the environment override is unset. Settings → Privacy and licenses shows the public-policy link when the value is nonempty. Final publication also requires secure upload signing, the final license/artifact checks, and device/Play acceptance. Confirm the link opens from a Play-delivered installation and use that same URL in Play Console. Rebuilding creates a new artifact/hash; the earlier preview does not gain a link automatically.

Primary references: [GitHub Pages branch publishing](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site), [Pages API](https://docs.github.com/en/rest/pages/pages), [Pages visitor data](https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages#data-collection), [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311).
