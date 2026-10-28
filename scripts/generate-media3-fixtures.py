"""Generate synthetic, redistributable decoder fixtures; no user media is included."""

import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/androidTest/assets/media3"
CASES = [
    ("h264.mp4", "libx264", ["-preset", "ultrafast"], "aac"),
    ("h264-high10.mkv", "libx264", ["-preset", "ultrafast", "-pix_fmt", "yuv420p10le"], "aac"),
    ("hevc-main.mkv", "libx265", ["-preset", "ultrafast", "-x265-params", "pools=2:frame-threads=2:log-level=error"], "aac"),
    ("hevc-main10.mp4", "libx265", ["-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-x265-params", "pools=2:frame-threads=2:log-level=error"], "aac"),
    ("vp8.webm", "libvpx", ["-deadline", "realtime", "-cpu-used", "8", "-b:v", "300k"], "libopus"),
    ("vp9.webm", "libvpx-vp9", ["-deadline", "realtime", "-cpu-used", "8", "-b:v", "300k"], "libopus"),
    ("av1.mp4", "libsvtav1", ["-preset", "12", "-crf", "40"], "aac"),
    ("mpeg2.mpg", "mpeg2video", ["-b:v", "500k", "-f", "vob"], "mp2"),
    ("mpeg2.ts", "mpeg2video", ["-b:v", "500k"], "mp2"),
    ("mpeg4.avi", "mpeg4", ["-q:v", "5"], "mp3"),
    ("msmpeg4v2.avi", "msmpeg4v2", ["-q:v", "5"], "mp3"),
    ("msmpeg4v3.avi", "msmpeg4", ["-q:v", "5"], "mp3"),
    ("mjpeg.avi", "mjpeg", ["-q:v", "8"], "pcm_s16le"),
    ("ac3.mkv", "libx264", ["-preset", "ultrafast"], "ac3"),
    ("eac3.mkv", "libx264", ["-preset", "ultrafast"], "eac3"),
    ("dts.mkv", "libx264", ["-preset", "ultrafast"], "dca"),
    ("truehd.mkv", "libx264", ["-preset", "ultrafast"], "truehd"),
]

OUTPUT.mkdir(parents=True, exist_ok=True)
for name, video, options, audio in CASES:
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
        "testsrc2=size=320x180:rate=24", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
        "-t", "8", "-threads", "2", "-c:v", video, *options, "-c:a", audio, "-strict", "-2",
        str(OUTPUT / name),
    ], check=True)
    print(name, flush=True)
