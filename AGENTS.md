# Android client guidance

## Offline persistence and playback

- `app/src/main/java/com/zenstream/zenstreammobile/offline/` owns Room-backed downloaded catalog data, verified media references, local playback and account-state overlays, and the durable account-operation outbox. Apply a local state change and its sync operation in one `OfflinePlaybackStateRepository` transaction; catalog refreshes replace canonical state while preserving pending overlays.
- `OfflineAccountSyncCoordinator` owns ordered, single-flight account reconciliation. It refreshes server time and history/privacy generations before upload, freezes operations until terminal acknowledgements, retries uncertain batches unchanged, and applies canonical state without replacing later pending overlays. WorkManager reconciliation requires connectivity and pauses in explicit Offline mode or when sign-in is required.
- `OfflinePlaylistRepository` owns private offline playlist edits. It requires a complete cached membership snapshot, updates its local playlist rows and ordered account-operation outbox in one Room transaction, and never changes media asset references when playlist entries are removed.
- When the playback source is `ResolvedPlaybackSource.Local`, persist progress through the offline playback repository without relying on server reachability or an online session. Audio queue `playbackInstanceId` values identify one music play event across recovery; reset them only when the user starts a new play or replay.
- Keep Room schema snapshots under `app/schemas/` checked in with explicit migrations. Cover migration and offline transaction behavior with Android instrumentation tests.
