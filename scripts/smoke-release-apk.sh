#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?Usage: smoke-release-apk.sh <signed-apk-path>}"
package_name="com.zenstream.zenstreammobile"
activity_name="$package_name/.MainActivity"
log_file=""
logcat_pid=""

fail() {
  echo "Android release APK smoke failed: $1" >&2
  echo "--- Android crash buffer ---" >&2
  adb -e logcat -d -b crash -v threadtime 2>&1 | tail -n 300 >&2 || true
  if [[ -n "$log_file" && -s "$log_file" ]]; then
    echo "--- Captured app startup logcat ---" >&2
    grep -E -C 12 \
      'AndroidRuntime|FATAL EXCEPTION|Fatal signal|tombstoned|com\.zenstream\.zenstreammobile|Exception|Error' \
      "$log_file" | tail -n 500 >&2 || true
  fi
  echo "--- Crash report from DropBox ---" >&2
  adb -e shell dumpsys dropbox --print data_app_crash 2>&1 | tail -n 300 >&2 || true
  exit 1
}

cleanup() {
  if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" 2>/dev/null || true
    wait "$logcat_pid" 2>/dev/null || true
  fi
  if [[ -n "$log_file" ]]; then
    rm -f "$log_file"
  fi
}
trap cleanup EXIT

if [[ ! -s "$apk_path" ]]; then
  fail "APK is missing or empty at $apk_path"
fi

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
adb -e logcat -b all -c || fail "could not clear emulator logcat before launch"
log_file="$(mktemp)"
adb -e logcat -b all -v threadtime >"$log_file" 2>&1 &
logcat_pid=$!
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
