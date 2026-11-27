#!/usr/bin/env python3
"""Verify built APK signatures, package metadata, ZIP and native ELF alignment.

This performs artifact checks only; it never installs an APK or contacts the business API.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def run(command):
    result = subprocess.run([str(value) for value in command], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    return {"command": [str(value) for value in command], "exitCode": result.returncode,
            "output": result.stdout}


def elf_load_alignment(data):
    if data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("Expected little-endian ELF")
    is64 = data[4] == 2
    machine = struct.unpack_from("<H", data, 18)[0]
    phoff = struct.unpack_from("<Q" if is64 else "<I", data, 32 if is64 else 28)[0]
    phsize, phnum = struct.unpack_from("<HH", data, 54 if is64 else 42)
    alignments = []
    for index in range(phnum):
        offset = phoff + index * phsize
        if struct.unpack_from("<I", data, offset)[0] == 1:
            alignments.append(struct.unpack_from("<Q" if is64 else "<I", data, offset + (48 if is64 else 28))[0])
    return {"machine": machine, "bits": 64 if is64 else 32,
            "loadAlignments": alignments, "supports16KiBPages": bool(alignments) and min(alignments) >= 16384}


def verify(path, expected_package, tools, java):
    with zipfile.ZipFile(path) as archive:
        bad = archive.testzip()
        natives = []
        for info in archive.infolist():
            if info.filename.startswith("lib/") and info.filename.endswith(".so"):
                natives.append({"path": info.filename, "bytes": info.file_size,
                                "compressed": info.compress_type != zipfile.ZIP_STORED,
                                **elf_load_alignment(archive.read(info))})
    signature = run([java, "-jar", tools["apksigner"], "verify", "--verbose", "--print-certs", "--print-certs-pem", path])
    metadata = run([tools["aapt2"], "dump", "badging", path])
    alignment = run([tools["zipalign"], "-c", "-P", "16", "4", path])
    manifest = run([tools["aapt2"], "dump", "xmltree", path, "--file", "AndroidManifest.xml"])
    found = re.search(r"package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'", metadata["output"])
    cert = re.search(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)", signature["output"])
    min_sdk = re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", metadata["output"], re.MULTILINE)
    target_sdk = re.search(r"^targetSdkVersion:'(\d+)'", metadata["output"], re.MULTILINE)
    permissions = re.findall(r"^uses-permission: name='([^']+)'", metadata["output"], re.MULTILINE)
    is_test = expected_package.endswith(".test")
    version_expected = "1.0.0-debug" if expected_package.endswith(".debug") else "1.0.0"
    success = bad is None and all(check["exitCode"] == 0 for check in [signature, metadata, alignment, manifest])
    success = success and found is not None and found[1] == expected_package and cert is not None
    success = success and min_sdk is not None and min_sdk[1] == "26" and target_sdk is not None and target_sdk[1] == "35"
    if not is_test:
        success = success and found is not None and found[2] == "10000" and found[3] == version_expected
    return {
        "file": str(path.relative_to(ROOT)), "bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "zipCrcValid": bad is None,
        "package": found[1] if found else None, "versionCode": found[2] if found else None,
        "versionName": found[3] if found else None,
        "minSdk": int(min_sdk[1]) if min_sdk else None,
        "targetSdk": int(target_sdk[1]) if target_sdk else None,
        "permissions": permissions,
        "debuggable": "application-debuggable" in metadata["output"],
        "signerSha256": cert[1].lower() if cert else None,
        "nativeLibraries": natives, "checksPassed": success,
        "checks": {"signature": signature, "metadata": metadata, "zipAlignment": alignment, "manifest": manifest},
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/evidence/apk-verification.json")
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_HOME", ROOT / "tools/android-sdk"))
    native = ROOT / "tools/arm64-sdk-tools/build-tools"
    build_tools = sdk / "build-tools/34.0.0"
    java_home = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-arm64"))
    java = java_home / "bin/java"
    tools = {"apksigner": build_tools / "lib/apksigner.jar",
             "aapt2": (native if os.uname().machine in ("aarch64", "arm64") else build_tools) / "aapt2",
             "zipalign": (native if os.uname().machine in ("aarch64", "arm64") else build_tools) / "zipalign"}
    for required in [java, *tools.values()]:
        if not required.is_file():
            raise SystemExit(f"Required verification tool missing: {required}")
    inputs = [
        ("app/build/outputs/apk/debug/app-debug.apk", "app.zhinengtika.nativeapp.debug"),
        ("app/build/outputs/apk/release/app-release.apk", "app.zhinengtika.nativeapp"),
        ("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", "app.zhinengtika.nativeapp.debug.test"),
    ]
    results = []
    for name, package in inputs:
        path = ROOT / name
        if not path.is_file():
            raise SystemExit(f"APK missing: {path}")
        result = verify(path, package, tools, java)
        results.append(result)
        print(package, result["bytes"], result["sha256"], "PASS" if result["checksPassed"] else "FAIL", flush=True)
    release = results[1]
    distinct = bool(release["signerSha256"]) and release["signerSha256"] != results[0]["signerSha256"]
    test_certificate_matches = bool(results[0]["signerSha256"]) and results[2]["signerSha256"] == results[0]["signerSha256"]
    release_safe = not release["debuggable"]
    native16k = all(lib["supports16KiBPages"] for lib in release["nativeLibraries"] if lib["bits"] == 64)
    report = {"timestampUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "kind": "artifact-only-not-device-validation", "deviceTestsExecuted": False,
              "independentReleaseAndDebugCertificates": distinct, "testSharesDebugCertificate": test_certificate_matches,
              "releaseNotDebuggable": release_safe,
              "release64BitNative16KiBAlignment": native16k, "apks": results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    pem = re.search(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", release["checks"]["signature"]["output"], re.DOTALL)
    if pem:
        (args.output.parent / "release-certificate.pem").write_text(pem[0] + "\n")
    print("Evidence:", args.output)
    raise SystemExit(0 if all(result["checksPassed"] for result in results) and distinct and test_certificate_matches and release_safe and native16k else 1)
