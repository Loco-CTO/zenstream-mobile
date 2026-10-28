"""Apply the small, checked integration delta to the pinned pristine NextLib source."""

import pathlib
import sys


def patch(root: pathlib.Path, path: str, old: str, new: str) -> None:
    target = root / path
    content = target.read_text(encoding="utf-8")
    if content.count(old) != 1:
        raise RuntimeError(f"Upstream patch context changed: {path}: {old[:70]}")
    target.write_text(content.replace(old, new), encoding="utf-8", newline="\n")


def main(root: pathlib.Path) -> None:
    patch(root, "ffmpeg/setup.sh", "mpegvideo vp8", "mpegvideo mpeg4 msmpeg4v2 msmpeg4v3 vc1 mjpeg vp8")
    patch(root, "ffmpeg/setup.sh", "JOBS=$(nproc", "JOBS=${JOBS:-$(nproc")
    patch(root, "ffmpeg/setup.sh", "|| echo 4)\n", "|| echo 4)}\n")
    patch(root, "ffmpeg/setup.sh", "      --enable-shared \\\n", "      --enable-shared \\\n      --build-suffix=-zenstream-media3 \\\n")
    patch(root, "media3ext/src/main/cpp/CMakeLists.txt", "lib${ffmpeg_lib_name}.so", "lib${ffmpeg_lib_name}-zenstream-media3.so")
    base = "media3ext/src/main/java/io/github/anilbeesetti/nextlib/media3ext/ffdecoder/"
    patch(root, base + "FfmpegLibrary.java", '      case MimeTypes.VIDEO_VP8 -> "vp8";', '''      case MimeTypes.VIDEO_MP4V, MimeTypes.VIDEO_DIVX -> "mpeg4";
      case MimeTypes.VIDEO_MP42 -> "msmpeg4v2";
      case MimeTypes.VIDEO_MP43 -> "msmpeg4";
      case MimeTypes.VIDEO_VC1 -> "vc1";
      case MimeTypes.VIDEO_MJPEG -> "mjpeg";
      case MimeTypes.VIDEO_VP8 -> "vp8";''')
    patch(root, base + "FfmpegVideoDecoder.java", "case MimeTypes.VIDEO_H265 ->", "case MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MP4V, MimeTypes.VIDEO_DIVX, MimeTypes.VIDEO_VC1 ->")
    patch(root, base + "FfmpegVideoDecoder.java", "ffmpegInitialize(codecName, extraData, threads)", "ffmpegInitialize(codecName, extraData, threads, format.width, format.height)")
    patch(root, base + "FfmpegVideoDecoder.java", "int threads);", "int threads, int width, int height);")
    video = root / "media3ext/src/main/cpp/ffvideo.cpp"
    content = video.read_text(encoding="utf-8")
    if content.count("jint threads) {") != 2:
        raise RuntimeError("Upstream video initialization signatures changed")
    content = content.replace("jint threads) {", "jint threads, jint width, jint height) {")
    content = content.replace("codecContext->thread_count = threads;", "codecContext->width = width > 0 ? width : 0;\n    codecContext->height = height > 0 ? height : 0;\n    codecContext->thread_count = threads;")
    content = content.replace("createVideoContext(env, codec, extra_data, threads)", "createVideoContext(env, codec, extra_data, threads, width, height)")
    video.write_text(content, encoding="utf-8", newline="\n")
    patch(root, base + "FfmpegVideoRenderer.java", "        } else if (!FfmpegLibrary.supportsFormat(mimeType)) {", '''        } else if (androidx.media3.common.ColorInfo.isTransferHdr(format.colorInfo)
                || MimeTypes.VIDEO_DOLBY_VISION.equals(mimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
        } else if (!FfmpegLibrary.supportsFormat(mimeType)) {''')
    patch(root, base + "FfmpegLibrary.java", "  public static boolean supportsFormat(String mimeType) {", '''  public static int getRuntimeCodecVersion() {
    return isAvailable() ? ffmpegRuntimeCodecVersion() : 0;
  }

  private static native int ffmpegRuntimeCodecVersion();

  public static boolean supportsFormat(String mimeType) {''')
    native = root / "media3ext/src/main/cpp/ffmain.cpp"
    with native.open("a", encoding="utf-8", newline="\n") as output:
        output.write('''
extern "C" unsigned avcodec_version(void);
extern "C" JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegLibrary_ffmpegRuntimeCodecVersion(
        JNIEnv *, jclass) {
    return static_cast<jint>(avcodec_version());
}
''')


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]).resolve())
