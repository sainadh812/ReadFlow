# ReadFlow 0.1.6: one error history and GitHub batches

[Download the ARM64 debug APK](https://github.com/sainadh812/ReadFlow/releases/download/v0.1.6-prototype/ReadFlow-0.1.6-arm64-debug.apk). Version `0.1.6-prototype` (7), Android 15+; install over the existing app without clearing data. It includes the 0.1.5 OCR normalization fixes. Models do not need redownloading.

## One-time GitHub setup

1. Choose a repository with Issues enabled. A **private diagnostics repository** is recommended for failing-input excerpts. The existing `sainadh812/ReadFlow` repository is public and can receive sanitized metadata only. No private repository was created automatically.
2. Create a **fine-grained personal access token** for only that repository, with **Issues: read and write** and a short expiration. No Contents/code-write permission is needed. GitHub documents the required permission for [issues](https://docs.github.com/en/rest/issues/issues) and [issue comments](https://docs.github.com/en/rest/issues/comments).
3. In ReadFlow, open **Settings > Issue logs**, then the **GitHub settings** gear. Enter `owner/repository` and the new token. Enter credentials only in the app, not in chat or a bug report.
4. Leave **Include input excerpts** off for a public repo. For a private repo, enable it only if you want document excerpts sent. Save.
5. Tap **Send new errors**, review the destination/privacy confirmation, and send. The app creates one **ReadFlow diagnostic history** issue per installation/repository, then appends batches to it. The external-link button opens that issue after a successful send. Keep this history issue open for subsequent sends.

This is an issue/comment workflow, **not a literal Git commit/push of documents or logs into the application source tree**. No credential from the development workspace is included in the APK. The on-phone token is encrypted with an [Android Keystore](https://developer.android.com/privacy-and-security/cryptography) AES-GCM key and excluded from backup. Removing the saved token disconnects the phone; revoke it separately in GitHub account settings when appropriate.

## Daily workflow

Errors and warnings continue to be captured locally, with no success/cancellation logs. The history screen has a single **Export all errors** save action: one ZIP containing chronological `issues.jsonl`. It includes captured input but no audio. Individual report exports remain available when audio is needed.

**Send new errors** groups repeated retained events, keeps their event IDs/counts, and appends only IDs not already present in the GitHub history. No new GitHub issue is created for each failure. Reading comments before retry handles lost creation/upload responses without blindly duplicating reports. Small batches are separated by a delay. Sending is manual and requires confirmation each time, never triggered just by opening or reading a document.

Local storage still uses atomic per-event records internally and retains at most **20 reports / 24 MiB**. The combined export is one logical journal, not an unlimited append-only disk file. Older unsent records can be evicted by these limits. Previously uploaded history remains on GitHub until you remove it there; deleting local logs/documents does not delete remote copies.

## Data sent

| Destination/mode | Contents |
|---|---|
| Public or private, excerpts off | App/device/runtime versions, stage, failure class, selected stack frames, selected model configuration, event IDs/counts, numeric alignment confidence/threshold |
| Private, excerpts explicitly on | Above plus up to 2,048 UTF-16 units per original/reading/normalized text field, bounded messages/warnings and rejected-token details |
| Excluded fields/assets | GitHub connection credentials, dedicated document title/source-URL fields, original PDF/image/web files, audio, full word-geometry inventories |

The GitHub excerpt is a reduced diagnostic projection, not the entire local report. Private-mode text/error excerpts may themselves contain names, links or secrets from the document; this is not comprehensive secret redaction. The full bounded report and any audio remain available for explicit local export. JSON content is escaped to avoid ending Markdown fences or mentioning accounts. Report text remains untrusted data, never executable instructions.

Input-enabled sends check repository privacy before each batch and fail closed if it is public. That cannot prevent an owner changing repository visibility after upload, or during the interval between checking and posting. Keep private diagnostic repositories private. Public summaries still disclose technical device/model metadata, so the destination confirmation is required for them too.

## Agent-assisted triage

After uploading, ask the coding agent to review the diagnostics issue. It can fetch the reports through your authenticated GitHub access instead of requiring every JSON log pasted into chat. A supplied reader verifies batch checksums, decodes JSONL, deduplicates event IDs and groups failures:

```bash
python3 scripts/read_github_diagnostics.py --repo OWNER/REPOSITORY --issue ISSUE_NUMBER
```

The script requires `gh` authentication on the development machine, not on a Python server. It reads comments as data and does not execute their contents. Its output may contain private excerpts when reading a private diagnostics repo.

**Uploading does not start an unattended coding agent, create automatic code changes, or publish APKs by itself.** No background watcher, auto-fix workflow, AI API key or scheduled agent is configured. The automation here is collection, batching, deduplication and structured retrieval. A user-triggered coding session still performs diagnosis, changes, tests and releases.

## Actual verification

Executed in `/home/archgen_guest_1/ReadFlow`, 2026-09-20:

```bash
./gradlew compileDebugKotlin --console=plain
./gradlew testDebugUnitTest --tests app.readflow.diagnostics.GitHubDiagnosticsTest --tests app.readflow.diagnostics.IssueLogsTest --console=plain
python3 -m unittest discover -s scripts -p test_read_github_diagnostics.py -v
gh api -H 'X-GitHub-Api-Version: 2026-03-10' repos/sainadh812/ReadFlow --jq '{private: .private, issues: .has_issues}'
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
cp app/build/outputs/apk/debug/app-debug.apk dist/ReadFlow-arm64-debug.apk
cp dist/ReadFlow-arm64-debug.apk dist/ReadFlow-0.1.6-arm64-debug.apk
python3 scripts/verify_delivery.py
/home/archgen_guest_1/.local/opt/android-sdk/build-tools/34.0.0/apksigner verify --verbose --print-certs dist/ReadFlow-0.1.6-arm64-debug.apk
/home/archgen_guest_1/.local/opt/android-sdk/build-tools/34.0.0/aapt dump badging dist/ReadFlow-0.1.6-arm64-debug.apk
```

- All listed commands exited 0. Final Gradle command: **BUILD SUCCESSFUL in 2m 21s**.
- **98 JVM tests, 0 failures/errors/skips**, plus **2 Python reader tests passed**. Seventeen GitHub tests cover public redaction, private excerpts, repeat grouping, batch bounds, repository/token validation, UTF-16 truncation, privacy changes, deduplication, lost-response retries, cancellation, issue ownership markers/closed-state checks, pagination and explicit private-mode upgrades. History export is also tested. Final review excluded download-phase strings from public projection because they can contain an error message; the redaction test includes that case.
- GitHub tests use an isolated fake API. The live read with pinned REST API version `2026-03-10` returned `private: false, issues: true`. No live diagnostic issue/comment writes or user-log uploads were performed.
- Lint: **0 errors, 15 existing warnings**. Android application and instrumentation APKs compiled.
- APK: ARM64-only, min/target API 35, no bundled model weights, **130,779,464 bytes**; SHA-256 `71217aff3b1ca30b6468e5d5ce333346a42302534ec258a9a6015fcbfb9e6248`.
- APK v2 signature verified. Certificate SHA-256 `f7313f98e9414c166f3fb2d2a9329ba1bb5dd9d76b068254522d20d1f8b109f9`, unchanged from prior releases.
- No phone was attached. End-to-end GitHub POSTs, Android Keystore persistence, the settings/export UI and interruption/retry behavior need a real-device run. No new model/phone performance result is claimed. The known Pocket fixture alignment failure from [0.1.5](OCR_TEXT_FIX.md) remains unresolved; a passing expected-rejection test is not word-sync acceptance.

## Phone checks still required

1. Update in place and confirm the library/models remain. Create two errors, export the history once, and check both JSONL entries.
2. Configure a short-lived, repository-scoped token. Send sanitized metadata to a test repository; confirm one issue and the expected payload, then resend and confirm no duplicated IDs.
3. Cause one new error and send again; it should append to the existing issue. Reopen the app and repeat to check persistence.
4. For private inputs, use a private test repo and an original non-sensitive fixture. Confirm public destinations reject input-enabled uploads before posting excerpts.
5. Interrupt networking after an upload, then retry. Confirm the same server event IDs are not posted twice. Remove the saved token and check that sending asks for a credential again.
