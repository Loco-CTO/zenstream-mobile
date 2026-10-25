FROM ubuntu:22.04@sha256:0a9887931b0e38af2e3487ed39e29436b0535c4f3f52e57745baaf79d1898956
ENV DEBIAN_FRONTEND=noninteractive ANDROID_HOME=/opt/android ANDROID_NDK_VERSION=25.2.9519653 ANDROID_CMAKE_VERSION=3.22.1
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl unzip build-essential pkg-config python3 python3-pip cmake ninja-build nasm \
    && python3 -m pip install --no-cache-dir meson==1.4.2 \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fL --retry 3 https://dl.google.com/android/repository/android-ndk-r25c-linux.zip -o /tmp/ndk.zip \
    && echo '53af80a1cce9144025b81c78c8cd556bff42bd0e  /tmp/ndk.zip' | sha1sum -c - \
    && mkdir -p /opt/android/ndk /opt/android/cmake/3.22.1/bin \
    && unzip -q /tmp/ndk.zip -d /opt/android/ndk \
    && mv /opt/android/ndk/android-ndk-r25c /opt/android/ndk/25.2.9519653 \
    && ln -s /usr/bin/cmake /opt/android/cmake/3.22.1/bin/cmake \
    && rm /tmp/ndk.zip
WORKDIR /work
ENTRYPOINT ["bash", "/recipe/build-native.sh"]
