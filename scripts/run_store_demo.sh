#!/usr/bin/env bash
# Run only on the disposable, accelerated GitHub Actions capture machine.
set -euo pipefail
cd "$(dirname "$0")/.."
demo_dir="$PWD/build/store-media/capture"
mkdir -p "$demo_dir"
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
          'run':os.environ.get('CAPTURE_RUN_URL'), 'captureApkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
          'buildKind':'release runtime, x86_64 emulator, local debug signing, Firebase unconfigured',
          'playArtifact':False, 'productionUploadKeyUsed':False}
pathlib.Path('build/store-media/capture/capture-build.json').write_text(json.dumps(record,indent=2)+'\n')
PY
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
test -c /dev/kvm
test -r /dev/kvm
test -w /dev/kvm
emulator -version > "$demo_dir/emulator-version.txt" 2>&1
printf 'no\n' | avdmanager create avd --name readassist_store_demo --package 'system-images;android-35;google_apis;x86_64' --device pixel_2 --force

pulseaudio --start --exit-idle-time=-1
pactl load-module module-null-sink sink_name=readassist_demo sink_properties=device.description=ReadAssistDemo
pactl set-default-sink readassist_demo
export PULSE_SINK=readassist_demo
export QEMU_AUDIO_DRV=pa
emulator -avd readassist_store_demo -no-window -no-snapshot -no-boot-anim \
  -gpu swiftshader_indirect -accel on -cores 2 -memory 4096 \
  -camera-back none -camera-front none > "$demo_dir/emulator.log" 2>&1 &
emulator_pid=$!
cleanup_demo() {
  adb -s emulator-5554 emu kill >/dev/null 2>&1 || true
  kill "$emulator_pid" >/dev/null 2>&1 || true
}
trap cleanup_demo EXIT
export ANDROID_SERIAL=emulator-5554
adb wait-for-device
booted=false
for attempt in $(seq 1 90); do
  if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == '1' ]]; then
    booted=true
    break
  fi
  sleep 2
done
if [[ "$booted" != true ]]; then
  adb logcat -d > "$demo_dir/boot-logcat.txt" || true
  exit 1
fi
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
adb shell am instrument -w -r \
  -e class app.readflow.StoreDemoSeedTest \
  -e demo true -e models true -e dictionary true \
  app.readflow.test/androidx.test.runner.AndroidJUnitRunner | tee "$demo_dir/seed-instrumentation.txt"
if ! rg -q 'OK \([0-9]+ tests?\)' "$demo_dir/seed-instrumentation.txt"; then
  adb logcat -d > "$demo_dir/seed-logcat.txt"
  exit 1
fi
python3 scripts/capture_demo.py capture --output "$demo_dir" --audio-source readassist_demo.monitor
adb shell dumpsys media_session > "$demo_dir/final-media-session.txt"
adb logcat -d -v threadtime > "$demo_dir/device-logcat.txt"
