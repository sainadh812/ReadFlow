#!/usr/bin/env bash
# Run only on the disposable, accelerated GitHub Actions capture machine.
set -euo pipefail
cd "$(dirname "$0")/.."
demo_dir="$PWD/build/store-media/capture"
mkdir -p "$demo_dir"
demo_phase() {
  printf '%s\n' "$1" > "$demo_dir/current-phase.txt"
  printf '%s %s\n' "$(date -u +%FT%TZ)" "$1" | tee -a "$demo_dir/phase-timeline.txt"
}
demo_phase 'verify-source'
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess
expected = {
    'app/src/main': '7e3cef462b28059d56efe8b5dd0126b2510f088d',
    'app/src/release': 'e98f60bab8f45f39833437585503e6b2e86b1648',
}
trees = {path: subprocess.check_output(['git', 'rev-parse', 'HEAD:'+path], text=True).strip() for path in expected}
if trees != expected:
    raise SystemExit('Capture source does not match the published 0.3.1 application source.')
apk = pathlib.Path('app/build/outputs/apk/release/app-release.apk')
record = {'appVersion':'0.3.1', 'versionCode':27, 'publishedAppSource':'94006546634355658abfba7ce1a7a442d8825828',
          'sourceTrees':trees, 'captureSource':os.environ.get('CAPTURE_SOURCE_SHA'),
          'apkBuildSource':os.environ.get('CAPTURE_APK_BUILD_SOURCE', os.environ.get('CAPTURE_SOURCE_SHA')),
          'reusedApkBuildRun':os.environ.get('CAPTURE_REUSED_APK_BUILD_RUN'),
          'run':os.environ.get('CAPTURE_RUN_URL'), 'captureApkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
          'buildKind':'release runtime, x86_64 emulator, local debug signing, Firebase unconfigured',
          'playArtifact':False, 'productionUploadKeyUsed':False}
pathlib.Path('build/store-media/capture/capture-build.json').write_text(json.dumps(record,indent=2)+'\n')
PY
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
# The current avdmanager and emulator may choose different default user folders.
# Give both tools one explicit AVD location (without changing the user's HOME).
export ANDROID_AVD_HOME="$PWD/build/store-media/avd"
mkdir -p "$ANDROID_AVD_HOME"
demo_phase 'prepare-emulator'
test -c /dev/kvm
test -r /dev/kvm
test -w /dev/kvm
emulator -version > "$demo_dir/emulator-version.txt" 2>&1
printf 'no\n' | avdmanager create avd --name readassist_store_demo --package 'system-images;android-35;google_apis;x86_64' --device pixel_2 --force --path "$ANDROID_AVD_HOME/readassist_store_demo.avd"
emulator -list-avds > "$demo_dir/emulator-avds.txt" 2>&1
avdmanager list avd > "$demo_dir/avdmanager-avds.txt" 2>&1
if ! grep -Fxq 'readassist_store_demo' "$demo_dir/emulator-avds.txt"; then
  demo_phase 'failed-avd-not-visible-to-emulator'
  cat "$demo_dir/emulator-avds.txt" "$demo_dir/avdmanager-avds.txt"
  exit 1
fi

pulseaudio --start --exit-idle-time=-1
pactl load-module module-null-sink sink_name=readassist_demo sink_properties=device.description=ReadAssistDemo
pactl set-default-sink readassist_demo
export PULSE_SINK=readassist_demo
export QEMU_AUDIO_DRV=pa
adb start-server
demo_phase 'launch-emulator'
emulator -avd readassist_store_demo -no-window -no-snapshot -no-boot-anim \
  -gpu swiftshader_indirect -accel on -cores 2 -memory 4096 \
  -camera-back none -camera-front none > "$demo_dir/emulator.log" 2>&1 &
emulator_pid=$!
cleanup_demo() {
  demo_exit=$?
  set +e
  printf '%s\n' "$demo_exit" > "$demo_dir/wrapper-exit-code.txt"
  printf '%s\n' "$(date -u +%FT%TZ)" > "$demo_dir/wrapper-finished-at.txt"
  kill -0 "$emulator_pid" 2>/dev/null
  printf '%s\n' "$?" > "$demo_dir/emulator-alive-check-exit-code.txt"
  if [[ "$demo_exit" != 0 ]]; then
    timeout 15 adb devices -l > "$demo_dir/failure-adb-devices.txt" 2>&1
    timeout 15 adb -s emulator-5554 logcat -d > "$demo_dir/failure-logcat.txt" 2>&1
    timeout 15 adb -s emulator-5554 pull /sdcard/Android/data/app.readflow/files/store-demo "$demo_dir/seed" > "$demo_dir/failure-seed-pull.txt" 2>&1
    tail -n 120 "$demo_dir/emulator.log"
  fi
  if [[ -n "${heartbeat_pid:-}" ]]; then kill "$heartbeat_pid" >/dev/null 2>&1; fi
  timeout 10 adb -s emulator-5554 emu kill >/dev/null 2>&1 || true
  kill "$emulator_pid" >/dev/null 2>&1 || true
}
trap cleanup_demo EXIT
export ANDROID_SERIAL=emulator-5554
(
  while kill -0 "$emulator_pid" 2>/dev/null; do
    printf 'Demo heartbeat %s: %s\n' "$(date -u +%FT%TZ)" "$(cat "$demo_dir/current-phase.txt")"
    sleep 30
  done
) &
heartbeat_pid=$!
demo_phase 'wait-for-adb-device'
if ! timeout 180 adb wait-for-device > "$demo_dir/adb-wait.txt" 2>&1; then
  demo_phase 'failed-adb-device-timeout'
  exit 1
fi
demo_phase 'wait-for-android-boot'
booted=false
boot_deadline=$((SECONDS + 180))
while (( SECONDS < boot_deadline )); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    demo_phase 'failed-emulator-process-exited'
    exit 1
  fi
  if [[ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == '1' ]]; then
    booted=true
    break
  fi
  sleep 2
done
if [[ "$booted" != true ]]; then
  demo_phase 'failed-android-boot-timeout'
  timeout 15 adb logcat -d > "$demo_dir/boot-logcat.txt" || true
  exit 1
fi
demo_phase 'install-demo-apks'
adb shell wm size 1080x1920
adb shell wm density 420
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb shell settings put system screen_off_timeout 600000
adb shell input keyevent 82
adb shell svc power stayon true
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb shell pm grant app.readflow android.permission.POST_NOTIFICATIONS
demo_phase 'seed-real-documents-and-models'
timeout --signal=INT --kill-after=15s 2250 adb shell am instrument -w -r \
  -e class app.readflow.StoreDemoSeedTest \
  -e demo true -e models true -e dictionary true \
  app.readflow.test/androidx.test.runner.AndroidJUnitRunner | tee "$demo_dir/seed-instrumentation.txt"
if ! grep -Eq 'OK \([0-9]+ tests?\)' "$demo_dir/seed-instrumentation.txt"; then
  timeout 15 adb logcat -d > "$demo_dir/seed-logcat.txt" || true
  exit 1
fi
demo_phase 'capture-real-ui-and-playback'
python3 scripts/capture_demo.py capture --output "$demo_dir" --audio-source readassist_demo.monitor
adb shell dumpsys media_session > "$demo_dir/final-media-session.txt"
adb logcat -d -v threadtime > "$demo_dir/device-logcat.txt"
demo_phase 'complete'
