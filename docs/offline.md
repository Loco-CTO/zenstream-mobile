# Offline media

## Ownership

The offline feature lives in `app/src/main/java/com/zenstream/zenstreammobile/offline/`:

- `OfflineRuntime` composes the feature.
- `OfflineDatabase` and `OfflineDao` persist profile-scoped snapshots, media references, local state, and the sync outbox.
- `DownloadCoordinator` and `DownloadExecution` share transfer state across user-initiated transfers, the foreground service, and WorkManager.
- `PlaybackSourceResolver` verifies local assets and must not silently stream when an offline source is absent or corrupt.
- `ConnectionCoordinator`, `OfflineAccountSyncCoordinator`, and `OfflineMetadataRefreshCoordinator` own offline-mode policy, account reconciliation, and metadata refresh.

Keep media in app-private storage. Scope local profiles by server instance and account, and keep downloaded files separate from server-issued playback URLs.

## Persistence and account state

- `app/src/main/java/com/zenstream/zenstreammobile/offline/` owns Room-backed downloaded catalog data, verified media references, local playback and account-state overlays, and the durable account-operation outbox. Apply a local state change and its sync operation in one `OfflinePlaybackStateRepository` transaction; catalog refreshes replace canonical state while preserving pending overlays.
- `OfflineAccountSyncCoordinator` owns ordered, single-flight account reconciliation. It refreshes server time and history/privacy generations before upload, freezes operations until terminal acknowledgements, retries uncertain batches unchanged, and applies canonical state without replacing later pending overlays. WorkManager reconciliation requires connectivity and pauses in explicit Offline mode or when sign-in is required.
- `OfflinePlaylistRepository` owns private offline playlist edits. It requires a complete cached membership snapshot, updates its local playlist rows and ordered account-operation outbox in one Room transaction, and never changes media asset references when playlist entries are removed.
- When the playback source is `ResolvedPlaybackSource.Local`, persist progress through the offline playback repository without relying on server reachability or an online session. Audio queue `playbackInstanceId` values identify one music play event across recovery; reset them only when the user starts a new play or replay.
- Keep Room schema snapshots under `app/schemas/` checked in with explicit migrations. Cover migration and offline transaction behavior with Android instrumentation tests.

## Orchestrator boundary

The Orchestrator owns `offline-v1` routes and account reconciliation. Apply grants before returning offline catalog information or an asset. Keep selection, preparation, delivery, metadata refresh, and account reconciliation persistent and bounded.

## Verification

For offline changes, use the repository's full Android validation command:

```sh
./gradlew spotlessCheck kspDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Run `./gradlew :app:connectedDebugAndroidTest --console=plain` when an authorized emulator or device is available. For changes to the online/offline boundary, also verify behavior with the Orchestrator offline contract tests.
