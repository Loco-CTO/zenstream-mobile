#!/usr/bin/env bash
set -euo pipefail
if [[ ! -d /work/source ]]; then
    cp -a /input/source /work/source
fi
cd /work/source
export JOBS="${JOBS:-8}"
bash ffmpeg/setup.sh
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    output="/export/$abi"
    mkdir -p "$output"
    cmake -S media3ext/src/main/cpp -B "/work/cmake/$abi" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=23 -DCMAKE_BUILD_TYPE=Release
    cmake --build "/work/cmake/$abi" --parallel "$JOBS"
    cp "/work/cmake/$abi/libmedia3ext.so" "$output/"
    for lib in avcodec avutil swresample swscale; do
        cp "ffmpeg/output/lib/$abi/lib${lib}-zenstream-media3.so" "$output/"
    done
done
