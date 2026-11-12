# Offline media

## Ownership

The offline feature lives in `app/src/main/java/com/zenstream/zenstreammobile/offline/`:

- `OfflineRuntime` composes the feature.
- `OfflineDatabase` and `OfflineDao` persist profile-scoped snapshots, media references, local state, and the sync outbox.
- `DownloadCoordinator` and `DownloadExecution` share transfer state across user-initiated transfers, the foreground service, and WorkManager.
- `PlaybackSourceResolver` verifies local assets and must not silently stream when an offline source is absent or corrupt.
- `ConnectionCoordinator`, `OfflineAccountSyncCoordinator`, and `OfflineMetadataRefreshCoordinator` own offline-mode policy, account reconciliation, and metadata refresh.

Keep media in app-private storage. Scope local profiles by server instance and account, and keep downloaded files separate from server-issued playback URLs.

Restoring an authenticated activity reuses an existing download job for the same profile and network constraints. The legacy foreground service also keeps its current runner on repeated starts. When Android assigns a different network to a transfer job, cancel and join the previous runner before resuming on that network. Binary calls must close their sockets on coroutine cancellation so a blocked response cannot retain the queue or staging-file writer. Interrupted transfers resume from the actual staging-file length with Range/If-Range checks and verify the completed digest before publication.

Automatic reachability is separate from explicit Offline mode. Any HTTP response proves server reachability, including an endpoint error. Cancelled calls, timeouts, and stream failures must not mark the whole server unavailable. Connection/DNS failures and certificate errors remain distinct. Once authenticated content is displayed, background request failures must not replace it with the offline entry screen; retained downloads remain available during startup and through Downloads.

## Downloads browsing

Downloads keeps an All filter and adds one filter for each library that has downloaded media. The filter uses the catalog library ID, so media of the same type from different libraries remains separate. Library names are cached per offline profile and remain available without a connection; libraries with no downloaded media do not appear.

## Automatic maintenance and media updates

When a music download has no embedded or sidecar lyrics, ZenStream asks the server for NetEase lyrics if the provider is enabled. A match is saved with the download for offline use. Background attachment refresh preserves saved NetEase lyrics until embedded or sidecar lyrics become available.

Subtitles, lyrics, artwork, chapters, and presentation metadata refresh automatically. Periodic maintenance runs approximately every six hours, with additional checks on reconnect, authenticated startup, opening Downloads, and local playback. Foreground checks become eligible after fifteen minutes. Android scheduling may defer background work; explicit Offline mode prevents network access.

Servers advertising `item-bundle-refresh` expose an item-based snapshot independent of the prepared asset reservation. The external-subtitle snapshot merges with saved embedded tracks; stable file identities map to persistent local track IDs. Lyrics use the same saved bundle and update the local Now Playing screen. Timing-dependent attachments stay with the saved copy when its source changes. Older servers retain the legacy asset-bundle refresh path.

Room stores each asset's successful check, retry deadline, and observed source revision. Maintenance processes sixteen due assets per pass and continues until the library is covered. Failed items back off independently. Subtitle and artwork transfers respect the existing metered-download preference, reuse verified unchanged files, and publish new references only after successful download. Playback leases retain obsolete files until active users release them.

Downloads settings offers **Check for updates**, check status, and an off-by-default **Automatically update downloaded media** preference. Full replacements normally start from the visible **Update download** action. Container actions cover downloaded members, retaining source and quality; individual actions allow choosing another available source or quality. Automatic replacements retain both choices and leave unsupported combinations for manual selection.

Replacement intent is persisted in transfer options (`replacesAssetId`, target source revision, automatic/manual origin, and the preparation selection ID). Reuse the resumable transfer queue, reserve space for both copies, and verify before atomically moving all surviving selection references. The old file remains playable; its leases defer cleanup. Cancellation, item removal, sign-out, or disabling automatic media updates must not allow a queued replacement to republish removed media. Scheduled replacements use a WorkManager data-sync foreground worker, never background UIDT starts.

Release Orchestrator item-bundle support before this client. Cover database migrations, new and corrected lyrics/subtitles, libraries larger than a maintenance page, replacement recovery, shared references, and active playback in device tests.

## Persistence and account state

- `app/src/main/java/com/zenstream/zenstreammobile/offline/` owns Room-backed downloaded catalog data, verified media references, local playback and account-state overlays, and the durable account-operation outbox. Apply a local state change and its sync operation in one `OfflinePlaybackStateRepository` transaction; catalog refreshes replace canonical state while preserving pending overlays.
- `OfflineAccountSyncCoordinator` owns ordered, single-flight account reconciliation. It refreshes server time and history/privacy generations before upload, freezes operations until terminal acknowledgements, retries uncertain batches unchanged, and applies canonical state without replacing later pending overlays. WorkManager reconciliation requires connectivity and pauses in explicit Offline mode or when sign-in is required.
- Online watched/unwatched actions and playback completion reconcile the ordered outbox before returning, so a fresh catalog request can see the saved state. Rejected explicit container changes use the catalog state API, which supports series and seasons. Offline or uncertain uploads remain queued for retry. Streamed completion uses the automatic `complete` operation and respects watch-history privacy; terminal player flushes must not append checkpoints after completion.
- `OfflinePlaylistRepository` owns private offline playlist edits. It requires a complete cached membership snapshot, updates its local playlist rows and ordered account-operation outbox in one Room transaction, and never changes media asset references when playlist entries are removed.
- When the playback source is `ResolvedPlaybackSource.Local`, persist progress through the offline playback repository without relying on server reachability or an online session. Audio queue `playbackInstanceId` values identify one music play event across recovery; reset them only when the user starts a new play or replay.
- Playback language preferences remain Orchestrator-owned and are cached in `SessionStore` by server and account. Local playback uses the cached selections without a network request in explicit Offline mode.
- Keep Room schema snapshots under `app/schemas/` checked in with explicit migrations. Cover migration and offline transaction behavior with Android instrumentation tests.

## Orchestrator boundary

The Orchestrator owns `offline-v1` routes and account reconciliation. Apply grants before returning offline catalog information or an asset. Keep selection, preparation, delivery, metadata refresh, and account reconciliation persistent and bounded.

## Verification

For offline changes, use the repository's full Android validation command:

```sh
./gradlew spotlessCheck kspDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Run `./gradlew :app:connectedDebugAndroidTest --console=plain` when an authorized emulator or device is available. For changes to the online/offline boundary, also verify behavior with the Orchestrator offline contract tests.
