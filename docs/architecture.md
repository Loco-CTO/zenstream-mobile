# Android architecture

## Scope and ownership

This repository contains the native Android client. It owns Android navigation, Compose presentation, installation-local preferences, native playback services, and offline media storage. The Orchestrator owns catalog, account identity, server permissions, and API contracts. Web presentation is maintained separately.

## Application flow

`MainActivity` composes the app shell and its shared state. `ui/navigation/ZenStreamApp.kt` owns authenticated route navigation and screen composition. Screen state is collected by Compose from the data and player owners; UI code should not duplicate service or persistence state.

Authenticated requests and API response parsing belong in `data/`. `SessionStore` owns installation-local settings, the current local session representation, and client configuration. Keep server account preferences distinct from device-local values such as player engine, subtitle appearance, and local playback settings.

## Media ownership

- Video player UI and source recovery live under `ui/player/`; the negotiated server source remains separate from local decoder choice.
- `audio/AudioPlaybackService.kt` owns active audio playback and the app-owned queue exposed through the Android media session.
- `offline/` composes the database, transfer coordination, account synchronization, metadata refresh, and local-source resolver. See [offline media](offline.md).
- Checked-in media decoder AARs and build/verification details live in `third_party/` and `app/libs/`; see [video and audio playback](playback.md).

## Repository map

- `app/src/main/java/com/zenstream/zenstreammobile/ui/` — Compose UI, navigation, theme, and player surfaces.
- `app/src/main/java/com/zenstream/zenstreammobile/data/` — networking, session, and client persistence.
- `app/src/main/java/com/zenstream/zenstreammobile/model/` — client-facing models.
- `app/src/main/java/com/zenstream/zenstreammobile/audio/` — foreground audio service and media-session integration.
- `app/src/main/java/com/zenstream/zenstreammobile/offline/` — offline runtime and transfer pipeline.
- `app/src/test/` and `app/src/androidTest/` — local and device-level verification.
- `third_party/` — source preparation, licenses, and reproducible native artifact checks.

## Cross-repository contracts

API UUIDs and payloads must stay compatible with the Orchestrator's curated OpenAPI contract and fixtures. When changing an API consumer, update the focused client fixture test and coordinate contract changes in the Orchestrator repository.

## Change guidance

Use [Android interface design](ui-design.md) for layout and interaction work. Use [playback](playback.md) or [offline media](offline.md) for those domains. Run the smallest focused Gradle checks for the changed area, then the repository checks required by the task.
