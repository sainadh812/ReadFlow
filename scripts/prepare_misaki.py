#!/usr/bin/env python3
"""Build the pinned, eSpeak-free Misaki frontend for Android arm64.

Go module checksums cover upstream source and embedded dictionary/POS/neural
assets. The APK's existing Sherpa backend is independent and still includes eSpeak.
Requires Linux x86_64 or an installed Go 1.26.4, and Android NDK 27.2.12479018.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/misaki"
GO_VERSION = "go1.26.4"
GO_ARCHIVE_SHA256 = "1153d3d50e0ac764b447adfe05c2bcf08e889d42a02e0fe0259bd47f6733ad7f"
NDK_VERSION = "27.2.12479018"
OUTPUT_ROOT = ROOT / "app/build/generated/misaki/jniLibs"
PROVENANCE = ROOT / "app/src/main/assets/licenses/misaki/readflow-provenance.json"


def run(*command: str | Path, env=None) -> str:
    return subprocess.run([str(c) for c in command], cwd=SOURCE, env=env,
                          check=True, text=True, stdout=subprocess.PIPE).stdout.strip()


def go_binary() -> Path:
    selected = os.environ.get("READFLOW_GO") or shutil.which("go")
    if selected and GO_VERSION in run(selected, "version").split():
        return Path(selected)
    if (platform.system(), platform.machine()) != ("Linux", "x86_64"):
        raise SystemExit("Install Go 1.26.4 and set READFLOW_GO to its executable.")
    directory = ROOT / ".deps/go-toolchain"
    go = directory / "go/bin/go"
    if go.exists() and GO_VERSION in run(go, "version").split():
        return go
    archive = ROOT / f".deps/{GO_VERSION}.linux-amd64.tar.gz"
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        temporary = archive.with_suffix(".part")
        urllib.request.urlretrieve(f"https://go.dev/dl/{archive.name}", temporary)
        temporary.replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != GO_ARCHIVE_SHA256:
        raise SystemExit(f"Checksum mismatch: {archive}")
    directory.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as bundle:
        bundle.extractall(directory, filter="data")
    return go


def sdk_root() -> Path:
    for value in (os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")):
        if value:
            return Path(value)
    properties = ROOT / "local.properties"
    if properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    raise SystemExit("Set ANDROID_SDK_ROOT or sdk.dir in local.properties.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", action="store_true", help="Build a host C ABI library for smoke tests")
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), default="arm64-v8a")
    args = parser.parse_args()
    go = go_binary()
    env = dict(os.environ, CGO_ENABLED="1", GOTOOLCHAIN="local", GOWORK="off")
    output = ROOT / ".deps/libreadflow_misaki_host.so" if args.host else OUTPUT_ROOT / args.abi / "libreadflow_misaki.so"
    fingerprint = {"go": GO_VERSION, "host": args.host, "abi": args.abi}
    if not args.host:
        ndk = sdk_root() / "ndk" / NDK_VERSION
        prebuilt = ndk / "toolchains/llvm/prebuilt"
        compiler, architecture = ("aarch64", "arm64") if args.abi == "arm64-v8a" else ("x86_64", "amd64")
        candidates = sorted(prebuilt.glob(f"*/bin/{compiler}-linux-android35-clang"))
        if len(candidates) != 1:
            raise SystemExit(f"Install NDK {NDK_VERSION}; Android {args.abi} compiler missing at {prebuilt}")
        env.update(GOOS="android", GOARCH=architecture, CC=str(candidates[0]),
                   CGO_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
        fingerprint["ndk"] = NDK_VERSION
    else:
        env.update(GOOS=run(go, "env", "GOHOSTOS"), GOARCH=run(go, "env", "GOHOSTARCH"))
    # Source, lockfiles, this script and toolchain are all cache inputs. Gradle
    # also tracks these; standalone calls use the same cache validation.
    fingerprint["inputs"] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                             for p in sorted([*SOURCE.glob("*"), Path(__file__), PROVENANCE]) if p.is_file()}
    stamp = output.with_suffix(".build.json")
    if output.exists() and stamp.exists():
        cached = json.loads(stamp.read_text())
        if cached.get("inputs") == fingerprint and cached.get("sha256") == hashlib.sha256(output.read_bytes()).hexdigest():
            print(f"Misaki native library already verified: {output.relative_to(ROOT)}")
            return
    run(go, "mod", "verify", env=env)
    deps = run(go, "list", "-mod=readonly", "-deps", ".", env=env).splitlines()
    if any("espeak" in module.lower() or "phonemizer" in module.lower() for module in deps):
        raise SystemExit("An eSpeak/phonemizer dependency entered the Misaki native build.")
    module = json.loads(run(go, "list", "-mod=readonly", "-m", "-json", "github.com/androiddrew/go-g2p", env=env))
    provenance = json.loads(PROVENANCE.read_text())
    if not module["Version"].endswith(provenance["goG2pRevision"][:12]):
        raise SystemExit("The Misaki module and retained provenance revision disagree.")
    for relative, expected in provenance["assetSha256"].items():
        asset = Path(module["Dir"]) / relative
        if hashlib.sha256(asset.read_bytes()).hexdigest() != expected:
            raise SystemExit(f"The embedded Misaki asset differs from its retained provenance: {relative}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.stem + ".pending.so")
    run(go, "build", "-mod=readonly", "-trimpath", "-buildmode=c-shared",
        "-ldflags=-s -w -buildid=", "-o", temporary, ".", env=env)
    temporary.replace(output)
    temporary.with_suffix(".h").unlink(missing_ok=True)
    stamp.write_text(json.dumps({"inputs": fingerprint, "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}, indent=2) + "\n")
    print(f"Misaki native library built: {output.relative_to(ROOT)} ({output.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
