#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?Usage: smoke-release-apk.sh <signed-apk-path>}"
package_name="com.zenstream.zenstreammobile"
activity_name="$package_name/.MainActivity"

fail() {
  echo "Android release APK smoke failed: $1" >&2
  adb -e logcat -b all -d -t 5000 -v threadtime -s AndroidRuntime:E ActivityTaskManager:E >&2 || true
  exit 1
}

if [[ ! -s "$apk_path" ]]; then
  fail "APK is missing or empty at $apk_path"
fi

"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose --print-certs "$apk_path" \
  || fail "APK signature verification failed"

booted=false
for attempt in $(seq 1 60); do
  if [[ "$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    booted=true
    break
  fi
  sleep 2
done
if [[ "$booted" != true ]]; then
  fail "emulator did not finish booting"
fi

adb -e install -r "$apk_path" || fail "APK installation failed"
adb -e shell am force-stop "$package_name"
start_output="$(adb -e shell am start -W -n "$activity_name" 2>&1 | tr -d '\r')" \
  || fail "MainActivity could not be started"
printf '%s\n' "$start_output"
if [[ "$start_output" != *"Status: ok"* ]]; then
  fail "Android did not report a successful activity start"
fi

sleep 5
app_pid="$(adb -e shell pidof "$package_name" 2>/dev/null | tr -d '\r' || true)"
if [[ -z "$app_pid" ]]; then
  fail "application process exited after launch"
fi

activity_dump="$(adb -e shell dumpsys activity activities | tr -d '\r')"
if ! printf '%s\n' "$activity_dump" \
  | grep -Ei '(mResumedActivity|topResumedActivity|ResumedActivity)' \
  | grep -F "$package_name" \
  | grep -F "MainActivity" >/dev/null; then
  fail "MainActivity is not the resumed activity"
fi

echo "Signed release APK installed and MainActivity remained running (pid $app_pid)."
