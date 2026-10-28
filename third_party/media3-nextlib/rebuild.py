"""Rebuild the pinned Media3 decoder in Docker; normal app builds use the checked AAR."""

import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

RECIPE = pathlib.Path(__file__).resolve().parent
ROOT = RECIPE.parents[1]
BUILD = ROOT / "build/nextlib"
LOCK = json.loads((RECIPE / "lock.json").read_text())


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def prepare():
    downloads = BUILD / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    for source in LOCK["sources"]:
        target = downloads / source["name"]
        if not target.exists():
            with urllib.request.urlopen(source["url"], timeout=120) as response, target.open("wb") as out:
                shutil.copyfileobj(response, out)
        if hashlib.sha256(target.read_bytes()).hexdigest() != source["sha256"]:
            raise RuntimeError(f"Source checksum mismatch: {source['name']}")
    source_root = BUILD / "source"
    source_lock = {key: LOCK[key] for key in ("sources", "media3_version", "ndk_version")}
    signature = hashlib.sha256((RECIPE / "patch-source.py").read_bytes() + json.dumps(source_lock, sort_keys=True).encode()).hexdigest()
    marker = source_root / ".zenstream-source"
    if source_root.exists():
        if not marker.exists() or marker.read_text() != signature:
            raise RuntimeError("Existing build/nextlib/source has a different recipe; move that ignored cache aside before preparing again")
        return signature
    with zipfile.ZipFile(downloads / LOCK["sources"][0]["name"]) as archive:
        archive.extractall(BUILD / "upstream")
    shutil.copytree(BUILD / "upstream" / LOCK["sources"][0]["directory"], source_root)
    sources = source_root / "ffmpeg/sources"
    sources.mkdir(exist_ok=True)
    for source in LOCK["sources"][1:]:
        with tarfile.open(downloads / source["name"]) as archive:
            archive.extractall(sources, filter="data")
    run(sys.executable, RECIPE / "patch-source.py", source_root)
    marker.write_text(signature)
    return signature


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--sources-only", action="store_true", help="Package locked upstream sources and the integration recipe for release")
    parser.add_argument("--output", type=pathlib.Path, default=BUILD / LOCK["artifact"])
    args = parser.parse_args()
    signature = prepare()
    if args.sources_only:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tarfile.open(args.output, "w:gz") as archive:
            for source in LOCK["sources"]:
                archive.add(BUILD / "downloads" / source["name"], arcname="build/nextlib/downloads/" + source["name"])
            for path in RECIPE.rglob("*"):
                if path.is_file() and "build" not in path.relative_to(RECIPE).parts and "__pycache__" not in path.parts:
                    archive.add(path, arcname="third_party/media3-nextlib/" + path.relative_to(RECIPE).as_posix())
        print(f"Decoder sources: {args.output}")
        return
    if args.prepare_only:
        return
    image = f"zenstream-media3-native:{signature[:12]}"
    native = BUILD / "native"
    native.mkdir(exist_ok=True)
    run("docker", "build", "-t", image, "-f", RECIPE / "Dockerfile", RECIPE)
    run("docker", "run", "--rm", "--mount", f"type=volume,source=zenstream-media3-{signature[:12]},target=/work",
        "--mount", f"type=bind,source={BUILD / 'source'},target=/input/source,readonly",
        "--mount", f"type=bind,source={RECIPE},target=/recipe,readonly",
        "--mount", f"type=bind,source={native},target=/export", "--env", "JOBS=8", image)
    gradle = ["cmd", "/c", "gradlew.bat"] if os.name == "nt" else ["bash", "gradlew"]
    run(*gradle, "-PbuildNextlib=true", ":nextlibArtifact:assembleRelease", "--console=plain", "--no-daemon")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(RECIPE / "artifact/build/outputs/aar/nextlibArtifact-release.aar", args.output)
    run(sys.executable, RECIPE / "verify.py", "--artifact", args.output, "--rebuilt")
    print(f"Rebuilt {args.output}; SHA-256 {hashlib.sha256(args.output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
