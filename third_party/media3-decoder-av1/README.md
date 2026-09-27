# Media3 AV1 decoder artifact

`app/libs/media3-decoder-av1-1.11.1.aar` is the official AndroidX Media3
`decoder_av1` module built against the app's pinned Media3 version. It bundles
the dav1d JNI library for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
Media3 tries platform renderers first and uses dav1d when the platform renderer
does not support the AV1 stream.

The checked-in artifact SHA-256 is:

```text
9BB72A102FC5047F5403EA2051FDAB96A1FF747FCFBDD01DA2FB0009E96EC207
```

## Sources and licenses

- AndroidX Media tag `1.11.1`, commit
  `8c6678b657ede1e7883fc164ef73ed483c7796c3` (Apache 2.0).
- dav1d tag `1.5.1`, commit
  `42b2b24fb8819f1ed3643aa9cf2a62f03868e3aa` (BSD 2-Clause).
- cpu_features tag `v0.9.0`, commit
  `ba4bffa86cbb5456bdb34426ad22b9551278e2c0` (Apache 2.0).

The corresponding license texts are kept beside this document as
`ANDROIDX_LICENSE`, `DAV1D_COPYING`, and `CPU_FEATURES_LICENSE`.

## Rebuilding

1. Check out AndroidX Media tag `1.11.1` and use Android NDK
   `27.1.12297006`.
2. Clone dav1d `1.5.1` and cpu_features `v0.9.0` into
   `libraries/decoder_av1/src/main/jni/`.
3. Install Meson, Ninja, NASM for x86 targets, CMake, and the Android SDK.
4. Run `build_dav1d.sh` from `libraries/decoder_av1/src/main/jni/` for the four
   ABIs listed above.
5. Run `gradlew.bat :lib-decoder-av1:assembleRelease` and replace the checked-in
   AAR only after verifying the version, ABI entries, JNI library name, and
   SHA-256.

The decoder is enabled in the video player with
`EXTENSION_RENDERER_MODE_ON`, which leaves platform decoding first.
