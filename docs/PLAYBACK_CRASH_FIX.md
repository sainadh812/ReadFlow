# ReadFlow 0.1.2: native playback callback fix

## Install and retry

Install the release APK over the existing ReadFlow installation. It uses the same package and signing key; no model re-download or app-data reset is needed. Select Kokoro and try the original sample or an imported document again. The fix applies to the callback shared by both TTS engines. Android 15+ and ARM64 remain the supported APK configuration.

If playback still closes the app, reopen it and choose **Settings > Copy playback diagnostics**, then paste the report into the support conversation. It includes the app version, phone model, Android version, selected engine, last recorded playback stage, failure class names, and recent Android process-exit reasons. It contains no document text, titles, paths, URLs, exception messages, or audio. The checkpoint stays in private, backup-excluded storage; copying is explicit and nothing is uploaded by the app.

## Reproduced defect

The reported device is a Samsung Galaxy S25 Ultra, with Kokoro-82M selected. No device log or attached phone was available. Investigation nevertheless reproduced a concrete binary-interface defect in the shipped APK:

- Sherpa 1.13.8 requests callback method `invoke` with JNI descriptor `([F)Ljava/lang/Integer;`.
- ReadFlow's Kotlin 2.1.20 lambda compiled through `invokedynamic`. Its Android DEX callback class exposed only `invoke(Object): Object`, not the typed method requested by native code.
- Sherpa's native callback returns with the failed method lookup's Java exception still pending, then continues making JNI calls. On Android, that path can abort the process before any audio reaches the player. An ordinary Kotlin `catch (Exception)` around playback cannot catch a native process abort.

The typed descriptor was checked in the actual ARM64 native library using `strings`, and the missing method was checked with `dexdump` in the original D8 output. A focused regression test initially failed with `NoSuchMethodException` for the original callback. This establishes the compatibility defect; without the phone's crash trace it does not prove this was its only failure.

Upstream references: [Sherpa 1.13.8 native callback](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/jni/offline-tts.cc#L360), [Kotlin's lambda compilation change](https://kotlinlang.org/docs/whatsnew20.html#generation-of-lambda-functions-using-invokedynamic), and [Android process-exit information](https://developer.android.com/reference/android/app/ApplicationExitInfo).

## Changes

An explicit, kept `SherpaAudioCallback` class now exposes the exact boxed-return method expected by the pinned native runtime. Generation cancellation still returns zero through this callback and native work still holds the lifecycle mutex until it finishes. Predicate exceptions stop generation instead of propagating into JNI. Java linkage failures during TTS creation/generation become a recoverable reader error; this does not attempt to catch native signals or silently switch speech engines.

Playback now distinguishes loading the model, generating speech, and aligning words. Small local checkpoints survive a process crash, and the settings action combines them with this app's Android exit history. No runtime, model asset, manifest, network permission, or content-processing behavior was changed.

## Verification commands

Executed on 2026-09-20 with JDK 17, SDK 35, build-tools 34.0.0, Gradle 8.11.1, AGP 8.6.1, and Kotlin 2.1.20:

```bash
# Before the fix: one test failed with NoSuchMethodException.
./gradlew testDebugUnitTest --tests app.readflow.speech.SpeechCallbackTest --console=plain
# Updated application, regression suite, lint and instrumentation compilation:
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
javap -p -s app/build/tmp/kotlin-classes/debug/app/readflow/speech/SherpaAudioCallback.class
$ANDROID_HOME/build-tools/34.0.0/dexdump app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/app/readflow/speech/SherpaAudioCallback.dex
# Check the final APK, not just intermediate bytecode:
$ANDROID_HOME/cmdline-tools/19.0/bin/apkanalyzer dex code --class app.readflow.speech.SherpaAudioCallback --method 'invoke([F)Ljava/lang/Integer;' dist/ReadFlow-arm64-debug.apk
python3 scripts/verify_delivery.py
```

Both JVM bytecode and Android DEX now contain `invoke([F)Ljava/lang/Integer;`, alongside the ordinary erased bridge. An instrumentation test additionally checks the packaged method on Android when a device is available. Host tests cover exact method lookup, continued/cancelled generation, callback exceptions, recoverable linkage errors, cancellation propagation, and checkpoint persistence/privacy/corruption.

Final result: **BUILD SUCCESSFUL in 3m 10s**, exit 0. **34 JVM tests passed, with zero failures, errors, or skips. Lint: zero errors, 15 existing warnings.** Both the ARM64 app and instrumentation APKs built. APK Analyzer independently found the required typed method in the final release asset. The report in `docs/evidence/build-results.json` records all suite counts and the APK hash.

`apksigner verify --print-certs` passed with the unchanged certificate SHA-256 `f7313f98e9414c166f3fb2d2a9329ba1bb5dd9d76b068254522d20d1f8b109f9`. `aapt dump badging` confirmed package `app.readflow`, version code 3, version `0.1.2-prototype`, minimum/target API 35, and ARM64 only. APK size: 130,153,002 bytes. SHA-256: `dcc96e89f0fc7246e4f068cea4275f643b9b8ef9fb361635290866be4e012b1a`.

## Device verification remaining

No physical device is connected; `adb devices -l` returned an empty list. The S25 Ultra recovery and Android TTS playback still need an actual retry. This patch is not evidence of completed audible word-sync acceptance for either engine, and the pre-existing limitations in `STATUS.md` remain open.
