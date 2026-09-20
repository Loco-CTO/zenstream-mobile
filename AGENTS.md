# AGENTS.md

## Project Overview

- `app/` is the Jetpack Compose Android client for ZenStream. It uses the Orchestrator APIs for catalog, account, and playback data.

## Architecture

- Device-local player preferences live in `app/src/main/java/com/zenstream/zenstreammobile/data/SessionStore.kt` and survive account/server changes.
- `MpvPlaybackSettings` stores MPV video output, profile, and scaler choices. Safe defaults are `gpu`, `fast`, and `bilinear`; settings are sampled when `PlaybackViewModel` creates the player and apply to the next video-player session.
- MPV configuration belongs in `ui/player/PlayerEngine.kt`. MPV advanced settings must not become Orchestrator/API fields or change Media3 behavior.
- Player settings UI is owned by `ui/screens/MyPageSettings.kt`; keep MPV-specific controls hidden when Media3 is selected and label advanced values with a warning.

## Commands

- From the repository root, use `gradlew.bat` for Android checks.

## Verification

- Kotlin formatting: `gradlew.bat spotlessCheck`
- JVM tests and debug packaging: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
- Instrumentation source compilation: `gradlew.bat :app:compileDebugAndroidTestKotlin`
- Run connected Android tests with `gradlew.bat :app:connectedDebugAndroidTest` when a device or emulator is available.
