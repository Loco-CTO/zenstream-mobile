# Agent guidance

## Scope

This repository owns the ZenStream Android client. It is a Jetpack Compose application that uses the Orchestrator for authenticated catalog and account APIs. Keep Android-only navigation, device preferences, playback, and offline behavior here.

## Repository map

- `app/src/main/java/com/zenstream/zenstreammobile/ui/` — Compose screens, components, navigation, and theme.
- `app/src/main/java/com/zenstream/zenstreammobile/data/` — API clients, session store, and local settings.
- `app/src/main/java/com/zenstream/zenstreammobile/audio/` — `AudioPlaybackService` and audio queue/session integration.
- `app/src/main/java/com/zenstream/zenstreammobile/offline/` — offline database, account sync, downloads, and local playback resolution.
- `app/src/test/` — JVM unit tests; `app/src/androidTest/` — device tests.
- `third_party/` and `app/libs/` — pinned or checked-in media artifacts and their provenance.
- `docs/` — architecture, interface design, playback, and offline guides.

## Important boundaries

- The Orchestrator owns catalog and account data. Device-local settings in `SessionStore` must not be moved into account preferences without an API and product decision.
- `AudioPlaybackService` owns audio queue and Android media-session behavior. The active negotiated source is separate from the queue snapshot.
- Offline playback must resolve a verified local asset and must not silently fall back to streaming. Read `docs/offline.md` before changing offline flows.
- Read `docs/playback.md` before changing Media3, MPV, audio service, PiP, decoder recovery, or player UI behavior.
- Use Material 3 and the generated Lucide resources for new or changed controls. Follow `docs/ui-design.md` and keep music artwork accents routed through `ui/components/ArtworkPalette.kt`.
- Before interface changes, read `docs/ui-design.md` and inspect the closest existing screen. Reuse its visual system and navigation patterns, and review the changed flow against that reference.

## Commands

- `./gradlew assembleDebug` — build a debug APK.
- `./gradlew spotlessCheck lint test assembleDebug` — standard local checks.
- `python third_party/media3-nextlib/verify.py` — verify the checked-in Media3 NextLib artifact.

## Documentation conventions

- Use the same README structure across all four repositories: `How it fits together`, `Configuration`, `Quick start`, `Deployment`, `Development checks`, `Developer documentation`, `Troubleshooting`, `Releases`, and `License`, in that order where relevant. Keep a project title and one-sentence summary at the top; place optional screenshots after the overview and first-run or platform-specific steps under the relevant section.
- Use sentence-case headings, short paragraphs, descriptive links, and repository-relative paths.
- Architecture documents explain ownership and flows. Keep detailed behavior in one focused guide and link it here; do not duplicate the entire feature specification in `AGENTS.md`.
- Update `docs/ui-design.md` when shared visual or navigation patterns change; keep feature-specific behavior in its focused guide.

## Downloads interface

- Downloads browsing and local container navigation live in `OfflineLibraryUi.kt`; `OfflineTransferUi.kt` groups transfer controls by selection, and `OfflineDownloadSettings.kt` owns connection/storage preferences. The landing list groups shows and albums, while search may return their descendants. Route containers locally before dispatching individual playback, preserve album queues and local Continue Watching/Next Up, and retain filter/search/scroll state through Back navigation. Keep offline UI on the shared theme background and match Library filters and Favorites media rows.
