#!/usr/bin/env bash
set -euo pipefail

native_contract=com.zenstream.zenstreammobile.ui.player.Media3NativeContractTest
session_journey=com.zenstream.zenstreammobile.SessionRestorationJourneyTest
cold_seed=com.zenstream.zenstreammobile.SessionColdStartSeedTest
status=0

# This journey uses the activity's real session store, before other tests can seed it.
if ! ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$session_journey"; then
  status=1
fi

python_command=python3
if [[ "${OS:-}" == Windows_NT ]]; then
  python_command=python
fi
if ! "$python_command" scripts/test-session-cold-start.py; then
  status=1
fi

for method in configuredDecodersAreActuallyRegistered media3ThenMpv mpvThenMedia3; do
  if ! ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$native_contract#$method"; then
    status=1
  fi
done

if ! ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass="$native_contract,$session_journey,$cold_seed"; then
  status=1
fi

# Install a lower-version APK, seed a session, then update the same package in place.
debug_apk=app/build/outputs/apk/debug/app-debug.apk
upgrade_artifacts="$(mktemp -d "${TMPDIR:-/tmp}/zenstream-session-upgrade.XXXXXX")"
previous_apk="$upgrade_artifacts/app-previous.apk"
current_apk="$upgrade_artifacts/app-current.apk"
if [[ ! -f "$debug_apk" ]]; then
  echo "Current debug APK is missing: $debug_apk" >&2
  status=1
else
  cp "$debug_apk" "$current_apk"
  previous_version="$("$python_command" - <<'PY'
from pathlib import Path

version = next(
    line.partition("=")[2].strip()
    for line in Path("version.properties").read_text(encoding="utf-8").splitlines()
    if line.startswith("version=")
)
major, minor, patch = map(int, version.split("."))
if patch > 1:
    previous = (major, minor, patch - 1)
elif minor > 0:
    previous = (major, minor - 1, 999)
elif major > 0:
    previous = (major - 1, 999, 999)
else:
    raise SystemExit(f"Cannot derive a lower positive versionCode from {version}")
print(".".join(str(part) for part in previous))
PY
)"
  if ./gradlew :app:assembleDebug -PzenstreamVersion="$previous_version"; then
    cp "$debug_apk" "$previous_apk"
    cp "$current_apk" "$debug_apk"
    if ! "$python_command" scripts/test-session-cold-start.py \
      --upgrade-from-apk "$previous_apk" \
      --upgrade-to-apk "$current_apk"; then
      status=1
    fi
  else
    echo "Could not build the lower-version APK ($previous_version)" >&2
    cp "$current_apk" "$debug_apk"
    status=1
  fi
fi

exit "$status"
