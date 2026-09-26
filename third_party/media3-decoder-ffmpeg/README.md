# Media3 FFmpeg audio decoder artifact

`app/libs/media3-decoder-ffmpeg-1.11.1.aar` is the official AndroidX Media3
`decoder_ffmpeg` module built against the app's pinned Media3 version. FFmpeg
is configured with only the `ac3`, `eac3`, `alac`, `dca` (DTS), and `truehd`
decoders. It includes `libffmpegJNI.so` for `arm64-v8a`, `armeabi-v7a`, `x86`,
and `x86_64`. Decoded audio is sent to Android as PCM; passthrough is not used.

The checked-in artifact SHA-256 is:

```text
C2B0A69F55B88A852167E3CD2A1FEA0EF8104D68CAFC374353626CEE071C5FCF
```

## Sources and licenses

- AndroidX Media tag `1.11.1`, commit
  `8c6678b657ede1e7883fc164ef73ed483c7796c3` (Apache 2.0).
- FFmpeg `release/6.0`, commit
  `110e2cd229d5acfdd950f5d09381afc41c263523` (LGPL 2.1 for this build).

The corresponding license texts are kept beside this document as
`ANDROIDX_LICENSE` and `FFMPEG_COPYING.LGPLv2.1`.

## Rebuilding

1. Check out AndroidX Media tag `1.11.1` and use Android NDK
   `27.1.12297006`.
2. Clone FFmpeg `release/6.0` into
   `libraries/decoder_ffmpeg/src/main/jni/ffmpeg`.
3. From `libraries/decoder_ffmpeg/src/main/jni/`, run `build_ffmpeg.sh` with
   Android API 24 and exactly these enabled decoders:
   `ac3 eac3 alac dca truehd`.
4. Run `gradlew.bat :lib-decoder-ffmpeg:assembleRelease` and replace the
   checked-in AAR only after verifying the version, ABI entries, JNI library
   name, decoder configuration, and SHA-256.

Runtime capability reporting calls Media3's `FfmpegLibrary.supportsFormat` for
each advertised audio codec, so an extension that cannot load or lacks a
decoder does not claim support.
