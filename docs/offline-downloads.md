# Offline downloads and playback

ZenStream Mobile stores selected media inside the app so it remains available when the Orchestrator is unreachable. Offline downloads require an Orchestrator that advertises the `offline-v1` contract. Older servers continue to support ordinary streaming; download controls explain when their server does not support offline downloads.

## Download media

Use the download action on a supported movie, episode, season, series, track, album, artist, collection, or playlist. The server expands group choices into a fixed membership snapshot before file transfers start. New episodes, tracks, and playlist members are not added automatically; use **Download missing members** when you want to update a saved selection.

Choose the main media quality in the download sheet. Video choices include the original or portable H.264 MP4 profiles up to 720p or 1080p without upscaling. Track downloads offer the original or AAC profiles at 128 or 256 kbps. Available options depend on the source and the server's conversion support. Downloads include all audio tracks and available subtitle tracks automatically, along with artwork, lyrics, chapters, and segments. Image subtitles require an original download.

Open **My Page → Downloads** to browse saved Movies, TV, and Music, search local metadata, and manage transfers. Pause, resume, retry, cancel, or remove downloads there. In **Manage downloads**, expand a show and remove a completed episode from its actions while keeping the rest of the series. Removing a selection preserves a media file that another saved selection still references.

## Use offline mode

In **Downloads → Settings**, turn on **Offline mode** to stop server requests, WebSocket connections, background synchronization, transfer activity, and startup update checks. Downloaded content remains browsable and playable after the app is restarted, even when online credentials have expired. Automatic mode prefers a verified local copy; choose the explicit stream action while connected when you want to use another server representation. A missing or damaged local file does not silently start a network stream.

When the server is unavailable, the Downloads screen reports that saved media remains available. New downloads and online-only actions wait until the server can be reached again. Metadata updates are applied separately from media files, and a changed source is marked as having a newer version available. Metadata and playlist membership refreshes do not download new media automatically.

## Account state and privacy

Progress, watched state, music history, favorites, Follow, and eligible playlist edits are saved locally and queued for synchronization. The app retries after reconnection and removes an operation only after the server acknowledges a final result. Original activity time is preserved for conflict resolution. If device timing is unreliable, the server's newer observed state takes precedence over an old offline change.

History clearing and changes to automatic Watch History are synchronized as privacy barriers. Stale automatic-history events cannot restore cleared history; explicit watched and unwatched actions remain distinct from automatic history.

Downloads belong to their server instance and account. Logging out removes that profile's media by default. You can choose to keep it locked; it unlocks only after signing in to the same account on the same server instance. Switching accounts or servers stops playback and transfer work for the previous identity.

Offline media uses internal app storage and is excluded from device backup and device-to-device transfer. Removing a profile or clearing the app's storage deletes its local media. File export, removable storage, and automatic subscriptions for future group members are not currently supported.
