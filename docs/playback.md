# Video and audio playback

## Video playback

`ui/player/` owns video presentation and `Media3PlaybackEngine.kt` owns Media3 source preparation and decoder recovery. Keep decoder overrides local to a playback session. Unsupported video and missing rendered frames need a bounded software recovery attempt before existing server conversion modes are considered. Do not add a fixed bitrate cap to recovery.

Retain working hardware HDR. Recognized HDR must not enter a software renderer until tone mapping is implemented. Changes to the checked-in NextLib AAR or native source require `python third_party/media3-nextlib/verify.py`; the ordinary app build consumes the checked-in artifact.

PiP, mini-player gestures, subtitle rendering, route changes, and session restoration have separate lifecycle owners. Read their implementation and focused tests before changing how they hand off or release playback.

## Audio playback

`audio/AudioPlaybackService.kt` owns the full app queue and Android media session. ExoPlayer holds only the active negotiated stream. Keep queue snapshots metadata-only and never persist playback URLs.

- Selecting a different queue entry starts at zero; selecting the current entry resumes it.
- Full Now Playing owns previous/next, shuffle, and repeat actions. The compact mini-player remains focused on current playback.
- Queue and lyrics are inner pages of Now Playing. Lyrics highlighting uses the playback position anchor and bounded shared lyrics cache.
- Direct lossless FLAC uses the checked-in Media3 FLAC extension. Keep libFLAC preferred for music, and do not resolve FFmpeg/MPV collisions with `pickFirst` or by replacing MPV binaries.
- Album, artist, mini-player, and Now Playing color accents use `ArtworkPalette.kt`.

## Offline playback

Offline source resolution is part of playback correctness. When a verified local file is missing or corrupt, show a recoverable local error rather than silently starting a network stream. See [offline media](offline.md).

## Verification

For decoder changes, run the native artifact verifier and the focused player unit tests. The full offline change checks are listed in [offline media](offline.md). Physical-device performance and user-media acceptance require separate device validation; a successful build does not establish either.
