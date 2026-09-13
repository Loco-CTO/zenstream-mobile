# Media3 FLAC decoder artifact

`app/libs/media3-decoder-flac-1.11.1.aar` is the official AndroidX Media3
`decoder_flac` module built from AndroidX Media tag `1.11.1` with Xiph libFLAC
tag `1.5.0`. It contains the native `flacJNI` renderer for all four Android
ABIs used by the app: `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.

The checked-in artifact SHA-256 is:

```text
0E4411D5F04FA1552C5DAA10658C4A5B5C262F70D56528EFF0D47021ED427E94
```

The app pins every Media3 dependency to `1.11.1` and creates its audio player
with `DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER`, so direct FLAC
remains progressive and lossless while the bundled LibFLAC renderer is chosen
before the platform FLAC decoder.

## Rebuilding

1. Check out the AndroidX Media `1.11.1` tag.
2. Clone Xiph libFLAC `1.5.0` into
   `libraries/decoder_flac/src/main/jni/libflac`.
3. With an Android SDK/NDK, CMake, and Ninja configured, run
   `gradlew.bat :lib-decoder-flac:assembleRelease` from the Media3 checkout.
4. Replace the app AAR only after verifying the Media3 version, all four ABIs,
   the native library name, and the SHA-256.

Sources:

- https://github.com/androidx/media/tree/1.11.1/libraries/decoder_flac
- https://github.com/xiph/flac/tree/1.5.0

The accompanying `ANDROIDX_LICENSE` and `LIBFLAC_COPYING.Xiph` files preserve
the licenses for the two source projects. The AAR is intentionally checked in
so normal app builds do not depend on a machine-specific native toolchain.
