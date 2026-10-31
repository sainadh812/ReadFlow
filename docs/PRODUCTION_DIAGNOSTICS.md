# Production diagnostics

Read Assist 0.2.5 adds optional Firebase Crashlytics and manual **Report a problem**
email. Crashlytics is available on Firebase's no-cost Spark plan; Analytics and
paid Firebase services are not needed. Muse Spark reports the owner’s project is created; the GitHub workflow now accepts its existing config secret under the earlier
`READASSIT_FIREBASE_CONFIG_BASE64` spelling. The configured signed build passed in run 40529659238; native-symbol upload and device/crash-delivery checks remain pending. See [the publishing handoff](PLAY_STORE_HANDOFF.md).

## Reporting boundary

- Default off. `FirebaseInitProvider` is removed, and collection defaults are false.
  The app initializes Firebase only at process startup with saved consent and a
  valid build configuration. Missing configuration disables the switch honestly.
- Enabling takes effect after a process restart. Disabling immediately closes the
  app-report queue, disables SDK collection and requests deletion of unsent reports.
  The SDK cannot guarantee immediate shutdown/deletion; handlers survive until exit,
  queued sends may finish and remaining stored reports may send on a later opt-in.
  Re-enabling during the same process never resumes reporting.
- `TechnicalDiagnostics` builds a separate allowlisted schema. Document names,
  identifiers/hashes, excerpts, URLs, audio and raw exception messages never enter
  this schema. Synthetic exceptions retain permitted stack frames and failure codes.
  The original Java fatal exception remains in local storage; a reduced exception is
  forwarded to the SDK's uncaught handler. Native/automatic runtime data cannot be
  given the same guarantee; the consent dialog and policy disclose this limit.
- Handled errors are submitted only after durable local storage. Reporter failures
  never erase the local log or recursively create more reports. The queue holds at
  most eight reduced events; repeated failure signatures are suppressed for ten
  minutes, and app-submitted reports are capped at 32 per hour per process.
  SDK fatal/ANR reporting has separate behavior. No account user ID or Analytics is set.
- Settings distinguishes the saved opt-in switch from a reporting service that
  actually started. It shows the number of reports handed to the on-device Firebase
  service in this process, the last reduced failure code, and synchronous submission
  failures. These are not server acknowledgments: the SDK saves and uploads
  asynchronously, usually after a process restart. Existing local logs are not replayed.
  If a submission throws, a later occurrence of that signature can retry immediately
  within the hourly attempt limit; successful signatures retain duplicate suppression.
- Firebase receives installation/session identifiers and technical diagnostics.
  This is optional data collection, not anonymous or wholly offline operation.

## User feedback

Settings and issue details offer **Report a problem**. Users can describe the issue,
optionally include reduced details from one/up to five saved failures, and review the
exact email body. The technical-details checkbox defaults off. The app opens a draft
to `xperimenting.ai@gmail.com` using `ACTION_SENDTO`; it never sends email itself.
Copying is available if no email app is installed. No attachments are added.

Full local issue logs can still be exported/deleted. Those exports may contain private
reading excerpts/audio and have their own warning. Production UI and ViewModel guards
hide/block GitHub setup and sending. Debug builds retain the developer workflow.

## Validation and remaining device checks

JVM tests cover absent consent/configuration, opt-in/restart, opt-out/re-enable,
persistence/SDK failures, concurrent startup, sanitizer exclusions, fatal chains,
rate limits, callback durability and the exact email-preview body. Bundle inspection
checks manifest defaults, removed auto-initialization and absence of Analytics.
Native build checks retain Misaki symbols; see [symbol instructions](NATIVE_CRASH_SYMBOLS.md).

Build checks are not delivery tests. Before production, use the owner's real Firebase
configuration and a dedicated test installation to verify fresh-install/off network
behavior; opt-in/restart; nonfatal, Java fatal and native-crash arrival; opt-out/restart;
pending-report re-enable; symbolication; and email/no-email-app behavior. Crash reports
typically upload on the next launch. Never expose forced-crash controls to production
users. Check Android vitals in Play Console as a separate source of crash/ANR evidence.

Sources: [Firebase pricing](https://firebase.google.com/pricing),
[reporting controls](https://firebase.google.com/docs/crashlytics/android/customize-crash-reports),
[NDK setup](https://firebase.google.com/docs/crashlytics/android/get-started-ndk),
[SDK data disclosure](https://firebase.google.com/docs/android/play-data-disclosure#crashlytics).
