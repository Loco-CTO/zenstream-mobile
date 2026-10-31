#!/usr/bin/env bash
set -euo pipefail

native_contract=com.zenstream.zenstreammobile.ui.player.Media3NativeContractTest
status=0

for method in configuredDecodersAreActuallyRegistered media3ThenMpv mpvThenMedia3; do
  if ! ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$native_contract#$method"; then
    status=1
  fi
done

if ! ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass="$native_contract"; then
  status=1
fi

exit "$status"
