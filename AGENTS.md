# Android client guidance

## Offline persistence and playback

- `app/src/main/java/com/zenstream/zenstreammobile/offline/` owns Room-backed downloaded catalog data, verified media references, local playback state, and the durable account-operation outbox. Apply a local state change and its sync operation in one `OfflinePlaybackStateRepository` transaction.
- When the playback source is `ResolvedPlaybackSource.Local`, persist progress through the offline playback repository without relying on server reachability or an online session. Audio queue `playbackInstanceId` values identify one music play event across recovery; reset them only when the user starts a new play or replay.
- Keep Room schema snapshots under `app/schemas/` checked in with explicit migrations. Cover migration and offline transaction behavior with Android instrumentation tests.
