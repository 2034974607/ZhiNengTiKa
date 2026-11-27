#!/usr/bin/env python3
"""Prepare a pinned ARM64 host AAPT2 and official Java SDK manager.
SDK license acceptance and package installation are separate, explicit steps.
"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
DOWNLOADS = ROOT / "downloads"
SDK = ROOT / "android-sdk"
NATIVE = ROOT / "arm64-sdk-tools"
SPECS = [
    ("commandlinetools-linux-11076708_latest.zip", "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip", "sha1", "d313adb7aedccf6cf0cfca51ec180f0059f5f8f8", 153607504),
    ("android-sdk-tools-static-aarch64.zip", "https://github.com/lzhiyong/android-sdk-tools/releases/download/36.0.0/android-sdk-tools-static-aarch64.zip", "sha256", "cbe5446eedfe788d7fe01af3681f18d1544dce1bf5064e4847e150a95c8f44e4", 13551377),
]

def download(spec):
    name, url, algorithm, expected, size = spec
    path = DOWNLOADS / name
    if not path.exists():
        print("Downloading", name, flush=True)
        with urllib.request.urlopen(url, timeout=60) as response, path.open("xb") as target:
            shutil.copyfileobj(response, target, length=1024 * 1024)
    actual = hashlib.new(algorithm, path.read_bytes()).hexdigest()
    if path.stat().st_size != size or actual != expected:
        raise RuntimeError(f"Archive verification failed: {name}; retained for inspection")
    print("Verified", name, algorithm, actual, flush=True)
    return path


def extract(archive, directory):
    directory.mkdir(parents=True, exist_ok=True)
    base = directory.resolve()
    with zipfile.ZipFile(archive) as z:
        for member in z.infolist():
            dest = (directory / member.filename).resolve()
            if not dest.is_relative_to(base):
                raise RuntimeError("Unsafe archive member")
        z.extractall(directory)
        for member in z.infolist():
            dest = directory / member.filename
            if dest.is_file() and (member.external_attr >> 16) & 0o111:
                dest.chmod(0o755)


def verify_elf(binary):
    b = binary.read_bytes()
    if b[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", b, 18)[0] != 183:
        raise RuntimeError("AAPT2 must be ELF64 little-endian AArch64")
    phoff = struct.unpack_from("<Q", b, 32)[0]
    phsize, phnum = struct.unpack_from("<HH", b, 54)
    for index in range(phnum):
        offset = phoff + index * phsize
        kind = struct.unpack_from("<I", b, offset)[0]
        if kind == 3:
            raise RuntimeError("Expected static AAPT2, found PT_INTERP")
        if kind == 2:
            file_offset = struct.unpack_from("<Q", b, offset + 8)[0]
            file_size = struct.unpack_from("<Q", b, offset + 32)[0]
            for entry in range(file_offset, file_offset + file_size, 16):
                if struct.unpack_from("<q", b, entry)[0] == 1:
                    raise RuntimeError("Expected static AAPT2, found DT_NEEDED")
    print("Verified AAPT2 ELF64 AArch64, no PT_INTERP or DT_NEEDED", flush=True)


if __name__ == "__main__":
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    free = shutil.disk_usage(ROOT).free
    if free < 1024 * 1024 * 1024:
        raise RuntimeError("Need at least 1 GiB free before SDK preparation")
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        archives = list(pool.map(download, SPECS))
    cli = SDK / "cmdline-tools" / "12.0"
    if not (cli / "bin" / "sdkmanager").exists():
        unpacked = ROOT / "cli-unpacked"
        extract(archives[0], unpacked)
        # Copy verified archive contents; never rename or relabel another tool release.
        shutil.copytree(unpacked / "cmdline-tools", cli, dirs_exist_ok=False)
    if not (NATIVE / "build-tools" / "aapt2").exists():
        extract(archives[1], NATIVE)
    aapt2 = NATIVE / "build-tools" / "aapt2"
    verify_elf(aapt2)
    for command, stdin in [([str(aapt2), "version"], None), ([str(aapt2), "daemon"], "quit\n\n")]:
        result = subprocess.run(command, input=stdin, text=True, capture_output=True, timeout=20)
        print(result.stdout, result.stderr, flush=True)
        result.check_returncode()
    report = {
        "archives": [{"name": spec[0], "url": spec[1], "digestAlgorithm": spec[2], "digest": spec[3], "size": spec[4], "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for spec, path in zip(SPECS, archives)],
        "aapt2": str(aapt2),
        "elf": {"class": 64, "endian": "little", "machine": 183, "PT_INTERP": False, "DT_NEEDED": False},
        "independentlyReproduced": False,
        "sdkLicenseAcceptedByThisScript": False,
    }
    (ROOT / "toolchain-verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print("SDK manager ready:", cli / "bin" / "sdkmanager", flush=True)
