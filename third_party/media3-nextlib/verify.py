"""Check the checked-in decoder artifact and its separation from the unchanged MPV graph."""

import argparse
import hashlib
import json
import pathlib
import struct
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
LIBS = {"libmedia3ext.so"} | {
    f"lib{lib}-zenstream-media3.so" for lib in ("avcodec", "avutil", "swresample", "swscale")
}
FORBIDDEN = {f"lib{lib}.so" for lib in ("avcodec", "avutil", "swresample", "swscale")}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def elf(data):
    assert data[:4] == b"\x7fELF", "Expected ELF native library"
    wide = data[4] == 2
    offset = struct.unpack_from("<Q" if wide else "<I", data, 32 if wide else 28)[0]
    size, count = struct.unpack_from("<HH", data, 54 if wide else 42)
    headers = []
    for index in range(count):
        h = struct.unpack_from("<IIQQQQQQ" if wide else "<IIIIIIII", data, offset + index * size)
        headers.append((h[0], h[2], h[3], h[5], h[7]) if wide else (h[0], h[1], h[2], h[4], h[7]))
    loads = [h for h in headers if h[0] == 1]
    assert loads and all(h[4] >= 16384 for h in loads), "ELF LOAD alignment below 16 KB"
    dynamic = next(h for h in headers if h[0] == 2)
    step = 16 if wide else 8
    tags = [struct.unpack_from("<qQ" if wide else "<iI", data, n) for n in range(dynamic[1], dynamic[1] + dynamic[3], step)]
    address = next(value for tag, value in tags if tag == 5)
    segment = next(h for h in loads if h[2] <= address < h[2] + h[3])
    strings = segment[1] + address - segment[2]

    def string(value):
        return data[strings + value:data.index(b"\0", strings + value)].decode()

    return [string(v) for t, v in tags if t == 14], [string(v) for t, v in tags if t == 1]


def verify(artifact, check_hash=True):
    lock = json.loads((ROOT / "third_party/media3-nextlib/lock.json").read_text())
    if check_hash:
        assert digest(artifact.read_bytes()) == lock["artifact_sha256"], "Decoder AAR checksum mismatch"
    with zipfile.ZipFile(artifact) as aar:
        for abi in ABIS:
            names = {path.rsplit("/", 1)[-1] for path in aar.namelist() if path.startswith(f"jni/{abi}/") and path.endswith(".so")}
            assert names == LIBS, f"Unexpected native payload for {abi}: {names}"
            for name in names:
                soname, needed = elf(aar.read(f"jni/{abi}/{name}"))
                assert soname == [name], f"SONAME mismatch: {abi}/{name}: {soname}"
                assert not FORBIDDEN.intersection(needed), f"MPV dependency leaked into {abi}/{name}"
                assert not (ROOT / "app/src/main/jniLibs" / abi / name).exists(), f"Native filename collision: {abi}/{name}"
            config = aar.read(f"jni/{abi}/libavcodec-zenstream-media3.so")
            for codec in ("h264", "hevc", "mpeg2video", "mpeg4", "msmpeg4v2", "msmpeg4v3", "vc1", "mjpeg", "vp8", "vp9", "libdav1d"):
                assert f"--enable-decoder={codec}".encode() in config, f"Missing configured decoder: {abi}/{codec}"
    for relative, expected in lock["mpv_sha256"].items():
        assert digest((ROOT / relative).read_bytes()) == expected, f"MPV binary changed: {relative}"
    print(f"Verified {artifact.name}: four ABIs, isolated dependencies, 16 KB alignment, codecs and unchanged MPV")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact", type=pathlib.Path)
    parser.add_argument("--rebuilt", action="store_true", help="Validate a rebuild without requiring identical ZIP timestamps")
    args = parser.parse_args()
    verify(args.artifact or ROOT / "app/libs/nextlib-media3ext-1.11.1-0.16.0-zenstream1.aar", not args.rebuilt)
