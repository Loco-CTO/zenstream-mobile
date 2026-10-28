# Media3 decoder integration

The app bundles `nextlib-media3ext-1.11.1-0.16.0-zenstream1.aar`, built from
NextLib commit `2dc77cd2d9352bc308aa26782f9c77a320df510b` against Media3 1.11.1.
The checked artifact keeps normal Gradle builds independent of Docker and native tools.

`lock.json` records the source URLs/checksums, artifact checksum and original MPV
binary checksums. Run `python third_party/media3-nextlib/verify.py` to inspect all
four ABIs, ELF alignment/dependencies, configured decoders and MPV preservation.

## Integration delta

- FFmpeg 9.0.1 uses `--build-suffix=-zenstream-media3`, with matching CMake imports.
  Filenames, SONAMEs and dependency references are private to this decoder stack.
  MPV binaries are unchanged; do not solve collisions with packaging `pickFirst`.
- MPEG-4 Part 2/DivX, Microsoft MPEG-4 v2/v3, VC-1 and MJPEG are enabled and mapped.
  MPEG-4/VC-1 initialization headers and container frame dimensions are passed to
  the decoder; Microsoft MPEG-4 requires dimensions before codec initialization.
- Recognized HDR formats are ineligible for the software video renderer because
  its RGBA/8-bit output does not establish correct HDR presentation.
- A JNI runtime-version query validates the actual loaded libavcodec ABI.

The integration replaces the separate official AV1 and FFmpeg-audio artifacts.
The dedicated official libFLAC extension remains preferred for music; NextLib's
float/16-bit audio output selection is not assumed equivalent to every FLAC path.
Media3 retains its own container readers; VC-1 decoding does not add ASF support.

## Rebuild

Requirements: Python 3.12+, Docker running Linux containers, the project's existing
Android SDK/Gradle prerequisites, and network access to the locked upstream sources.
The Docker image installs its NDK inside the container, not on the host.

```text
python third_party/media3-nextlib/rebuild.py
```

This verifies and prepares sources under ignored `build/nextlib`, applies the
checked integration delta, builds the four native ABIs in a Docker volume, then
uses the opt-in `:nextlibArtifact` Gradle module to compile/package Java and Kotlin.
The output goes to `build/nextlib`; it does not replace the committed artifact.
The source-patch script fails if upstream context changes rather than silently
applying a partial patch. Move an old ignored source cache aside when changing
the source lock or patch recipe.

Review the rebuilt AAR, run codec/native/packaging tests, then intentionally replace
the app artifact and update its checksum in `lock.json`. ZIP timestamps can differ
between rebuilds; the source recipe is repeatable, not a claim of byte-identical ZIPs.
Keep native updates separate from unrelated Media3 upgrades. Update Media3 and
NextLib as a tested pair and rerun the complete fixture matrix.

## Validation

`scripts/generate-media3-fixtures.py` creates redistributable synthetic moving/color
patterns and sine-wave audio. Instrumentation forces app software decoding, checks
actual Surface pixels change, and seeks to another rendered frame. Native contract
tests exercise registered decoders and both library load orders. Run load-order
methods in separate instrumentation invocations to start from a fresh process.

```text
gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.zenstream.zenstreammobile.ui.player.Media3CodecPlaybackTest
```

VC-1 registration is tested, but a real VC-1 fixture and physical-device performance
remain separate acceptance gates. Emulator color-pattern tests do not establish
physical-device 1080p/4K performance, hardware HDR or audible output. Record those
limits in verification reports.

For optional real-media acceptance, copy a readable file to the debug app's external
files directory and pass its device path as
`-Pandroid.testInstrumentationRunnerArguments.media3AcceptanceFile=/sdcard/Android/data/com.zenstream.zenstreammobile.debug/files/sample.mkv`.
The same test class then also checks automatic and forced app-software playback,
visible nonuniform/changing Surface pixels, and seeking. Those two cases skip when
the argument is absent. Preserve original encoded streams when making a short
excerpt (`ffmpeg -ss 300 -i input.mkv -t 20 -map 0:v:0 -map 0:a:0? -c copy -sn -dn sample.mkv`).
Keep private media and captures in ignored build output; never commit them as fixtures.

## Licenses and corresponding source

NextLib is GPL-3.0 (`LICENSE.GPL-3.0`); this FFmpeg configuration is LGPL-3.0
(`FFMPEG.LGPL-3.0`), dav1d is BSD-2-Clause (`DAV1D.COPYING`). The build also uses
Mbed TLS (`MBEDTLS.LICENSE`); the shipped decoder module does not include its
separate media-info/HTTP module. Locked sources plus `patch-source.py`, Dockerfile,
Gradle packaging module and build scripts describe the complete integration source.
Distribute these source/build materials alongside releases containing this artifact.
The release workflow packages the locked upstream archives and this integration
recipe as a verified `media3-source.tar.gz` asset. Extract it into the matching
application source checkout to rebuild the decoder with the same Gradle wrapper.

Upstream: https://github.com/anilbeesetti/nextlib/tree/v1.11.1-0.16.0
