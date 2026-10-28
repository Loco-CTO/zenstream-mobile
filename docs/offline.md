# Offline media

## Ownership

The offline feature lives in `app/src/main/java/com/zenstream/zenstreammobile/offline/`:

- `OfflineRuntime` composes the feature.
- `OfflineDatabase` and `OfflineDao` persist profile-scoped snapshots, media references, local state, and the sync outbox.
- `DownloadCoordinator` and `DownloadExecution` share transfer state across user-initiated transfers, the foreground service, and WorkManager.
- `PlaybackSourceResolver` verifies local assets and must not silently stream when an offline source is absent or corrupt.
- `ConnectionCoordinator`, `OfflineAccountSyncCoordinator`, and `OfflineMetadataRefreshCoordinator` own offline-mode policy, account reconciliation, and metadata refresh.

Keep media in app-private storage. Scope local profiles by server instance and account, and keep downloaded files separate from server-issued playback URLs.

## Orchestrator boundary

The Orchestrator owns `offline-v1` routes and account reconciliation. Apply grants before returning offline catalog information or an asset. Keep selection, preparation, delivery, metadata refresh, and account reconciliation persistent and bounded.

## Verification

For offline changes, use the repository's full Android validation command:

```sh
./gradlew spotlessCheck kspDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Run `./gradlew :app:connectedDebugAndroidTest --console=plain` when an authorized emulator or device is available. For changes to the online/offline boundary, also verify behavior with the Orchestrator offline contract tests.
