#!/usr/bin/env bash
set -euo pipefail

apk="${1:?Provide the signed release APK path}"
application_id="com.zenstream.zenstreammobile"
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose --print-certs "$apk"
adb install --replace "$apk"
adb shell am force-stop "$application_id"
adb shell am start -W -n "$application_id/.MainActivity" | tee "$RUNNER_TEMP/android-launch.txt"
grep -Fq "Status: ok" "$RUNNER_TEMP/android-launch.txt"

launched=false
for attempt in $(seq 1 30); do
	if adb shell pidof "$application_id" >/dev/null 2>&1 &&
		adb shell dumpsys activity activities | tr -d '\r' | grep -Fq "$application_id/.MainActivity"; then
		launched=true
		break
	fi
	sleep 1
done
[[ "$launched" == true ]] || {
	adb logcat -d -t 300
	echo "The signed APK did not keep MainActivity running in the emulator." >&2
	exit 1
}

echo "Signed APK installation and MainActivity launch passed."
