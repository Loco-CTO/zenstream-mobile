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

exit "$status"
