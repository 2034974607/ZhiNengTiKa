#!/usr/bin/env python3
"""Package committed source and verified artifacts into a new delivery directory.

Never overwrites an existing delivery, installs an app, or copies signing secrets.
Run verify-apks.py against the final APKs before invoking this script.
"""
import argparse
import csv
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def git(*arguments):
    return subprocess.check_output(["git", *arguments], cwd=ROOT).decode().strip()


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def copy_file(source, destination):
    if not source.is_file() or source.is_symlink():
        raise SystemExit(f"Expected a regular evidence file: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with source.open("rb") as reader, destination.open("xb") as writer:
        shutil.copyfileobj(reader, writer)


def copy_tree(source, destination):
    if not source.is_dir():
        raise SystemExit(f"Missing evidence directory: {source}")
    for item in sorted(source.rglob("*")):
        if item.is_symlink():
            raise SystemExit(f"Evidence must not contain symlinks: {item}")
        if item.is_file():
            copy_file(item, destination / item.relative_to(source))


def zip_tree(source, destination):
    with zipfile.ZipFile(destination, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for item in sorted(source.rglob("*")):
            if item.is_file():
                archive.write(item, item.relative_to(source).as_posix())
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None:
            raise SystemExit(f"Archive CRC verification failed: {destination}")


def unit_summary():
    files = sorted((ROOT / "protocol/build/test-results/test").glob("TEST-*.xml"))
    if not files:
        raise SystemExit("No JVM test results found")
    summary = {key: 0 for key in ["tests", "failures", "errors", "skipped"]}
    for file in files:
        suite = ET.parse(file).getroot()
        for key in summary:
            summary[key] += int(suite.get(key, "0"))
    if summary["tests"] != 32 or any(summary[key] for key in ["failures", "errors", "skipped"]):
        raise SystemExit(f"Review test report/documentation before packaging: {summary}")
    return summary


def lint_summary(variant):
    path = ROOT / f"app/build/reports/lint-results-{variant}.xml"
    report = ET.parse(path).getroot()
    issues = [{"id": issue.get("id"), "severity": issue.get("severity"),
               "message": issue.get("message")} for issue in report.findall("issue")]
    if any(issue["severity"] in ("Error", "Fatal") for issue in issues):
        raise SystemExit(f"Unresolved lint errors in {variant}")
    return {"variant": variant, "errors": 0, "issues": issues}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-log", type=Path, required=True, help="Successful final build log inside this project")
    parser.add_argument("--destination", type=Path, default=Path("/sdcard/Download/DSH-Go"))
    args = parser.parse_args()
    log = args.build_log.resolve()
    if not log.is_relative_to(ROOT / "artifacts/logs") or not log.is_file():
        raise SystemExit("Use a final build log from artifacts/logs")
    log_text = log.read_text()
    required_tasks = [":app:assembleDebug", ":app:assembleRelease", ":app:assembleDebugAndroidTest",
                      ":app:lintDebug", ":app:lintRelease", ":protocol:test"]
    if "Build exit code: 0" not in log_text or any(f"> Task {task}" not in log_text for task in required_tasks):
        raise SystemExit("Final build log must show all variants, tests and both lint tasks succeeding")
    if git("status", "--porcelain", "--untracked-files=all"):
        raise SystemExit("Commit all intended source and documentation changes before packaging")
    commit = git("rev-parse", "HEAD")
    source_files = git("ls-files").splitlines()
    forbidden_parts = {".git", ".gradle", ".kotlin", "android-sdk", "arm64-sdk-tools", "downloads", "artifacts", "__pycache__"}
    for name in source_files:
        path = Path(name)
        if (any(part in forbidden_parts for part in path.parts)
                or path.suffix.lower() in {".p12", ".pfx", ".jks", ".keystore", ".apk"}
                or path.name in {"store-password.txt", "key.properties", "local.properties"}):
            raise SystemExit(f"Unexpected generated or secret file tracked in source: {name}")
    verification_path = ROOT / "artifacts/evidence/apk-verification.json"
    verification = json.loads(verification_path.read_text())
    if (not verification["independentReleaseAndDebugCertificates"]
            or not verification.get("testSharesDebugCertificate", False)
            or not verification["releaseNotDebuggable"]
            or not verification["release64BitNative16KiBAlignment"]):
        raise SystemExit("APK verification checks did not pass")
    for apk in verification["apks"]:
        if not apk["checksPassed"] or sha(ROOT / apk["file"]) != apk["sha256"]:
            raise SystemExit("APK changed after verification; verify it again")
    tests = unit_summary()
    lint = [lint_summary(variant) for variant in ["debug", "release"]]
    with (ROOT / "app/build/reports/dependency-inventory.tsv").open(newline="") as stream:
        inventory = list(csv.DictReader(stream, delimiter="\t"))
    documented = json.loads((ROOT / "docs/dependency-artifacts.json").read_text())["artifacts"]
    identity = lambda row: (row["coordinate"], row["filename"], row["sha256"])
    if sorted(map(identity, inventory)) != sorted(map(identity, documented)):
        raise SystemExit("Refresh dependency notices from the final dependency inventory first")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    destination = args.destination.expanduser().resolve()
    destination.mkdir(parents=True, exist_ok=True)
    delivery = destination / f"ZhiNengTiKa-native-1.0.0-{stamp}-{commit[:8]}"
    delivery.mkdir(exist_ok=False)
    names = ["ZhiNengTiKa-1.0.0-debug.apk", "ZhiNengTiKa-1.0.0-release.apk", "ZhiNengTiKa-1.0.0-ui-tests.apk"]
    for apk, name in zip(verification["apks"], names, strict=True):
        copy_file(ROOT / apk["file"], delivery / name)
    source_zip = delivery / "ZhiNengTiKa-native-1.0.0-source.zip"
    if source_zip.exists():
        raise SystemExit("Refusing to overwrite a source archive")
    subprocess.run(["git", "archive", "--format=zip", "--prefix=ZhiNengTiKa-native/",
                    f"--output={source_zip}", commit], cwd=ROOT, check=True)
    with zipfile.ZipFile(source_zip) as archive:
        archived = {name.removeprefix("ZhiNengTiKa-native/") for name in archive.namelist() if not name.endswith("/")}
        if archive.testzip() is not None or archived != set(source_files):
            raise SystemExit("Source archive does not match the committed source files")
    bundle = delivery / "ZhiNengTiKa-native-1.0.0.git.bundle"
    subprocess.run(["git", "bundle", "create", str(bundle), "--all"], cwd=ROOT, check=True)
    subprocess.run(["git", "bundle", "verify", str(bundle)], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    reports = delivery / "reports"
    reports.mkdir()
    for name in ["README.md", "BUILDING.md", "TESTING.md", "PERFORMANCE.md", "LICENSE", "NOTICE"]:
        copy_file(ROOT / name, reports / name)
    copy_tree(ROOT / "docs", reports / "docs")
    # Keep the source references in the reports readable without extracting the source ZIP.
    for name in ["app/build.gradle.kts", "protocol/build.gradle.kts", "tools/build-android.py",
                 "tools/verify-apks.py", "tools/package-delivery.py"]:
        copy_file(ROOT / name, reports / name)
    copy_tree(ROOT / "artifacts/evidence", reports / "evidence")
    copy_tree(ROOT / "artifacts/logs", reports / "build-logs")
    copy_tree(ROOT / "protocol/build/test-results/test", reports / "jvm-test-results")
    copy_tree(ROOT / "protocol/build/reports/tests/test", reports / "jvm-test-report")
    copy_file(ROOT / "protocol/build/reports/synthetic-benchmark.json", reports / "synthetic-benchmark.json")
    copy_file(ROOT / "app/build/reports/dependency-inventory.tsv", reports / "dependency-inventory.tsv")
    copy_tree(ROOT / "app/build/outputs/mapping/release", reports / "release-r8")
    for variant in ["debug", "release"]:
        for extension in ["xml", "txt", "html"]:
            copy_file(ROOT / f"app/build/reports/lint-results-{variant}.{extension}",
                      reports / f"lint-results-{variant}.{extension}")
    toolchain = ROOT / "tools/toolchain-verification.json"
    if toolchain.is_file():
        copy_file(toolchain, reports / "toolchain-verification.json")
    evidence = {
        "createdAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "version": "1.0.0", "versionCode": 10000, "sourceCommit": commit,
        "sourceTree": git("rev-parse", "HEAD^{tree}"), "sourceFileCount": len(source_files),
        "sourceCommitCount": int(git("rev-list", "--count", "HEAD")),
        "gitRemotes": git("remote").splitlines(),
        "finalBuildLog": f"reports/build-logs/{log.name}",
        "jvmTests": tests, "lint": lint,
        "apkVerification": "reports/evidence/apk-verification.json",
        "deviceTestsExecuted": verification.get("deviceTestsExecuted", False),
        "realServiceTestsExecuted": False,
        "limitations": ["No real student accounts, uploads or answer submissions were used.",
                        "Navigation custom lint registries are incompatible with this lint API; skipped checks are reported.",
                        "Synthetic host-JVM benchmark is not Android UI performance or a comparison with Qt."],
    }
    (delivery / "delivery-manifest.json").write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n")
    readme = f"""# 新教育智能题卡 原生版 1.0.0 交付\n\n源码提交：`{commit}`（独立新历史，无远端推送）。\n\n- [Release APK]({names[1]})：日常安装入口，独立签名，启用 R8。\n- [Debug APK]({names[0]})：开发与 UI 测试使用，与 Release 分包。\n- [UI 测试 APK]({names[2]})：配合 Debug 安装，不是单独的应用。\n- [完整源码 ZIP]({source_zip.name})：包含 Gradle wrapper、构建脚本、测试与许可。\n- [独立 Git bundle]({bundle.name})：可用 `git clone 文件路径 新目录` 恢复新历史。\n- [构建与签名](reports/BUILDING.md)、[测试](reports/TESTING.md)、[性能](reports/PERFORMANCE.md)。\n- [功能对照](reports/docs/feature-comparison.md)、[协议](reports/docs/protocol.md)、[已知问题](reports/docs/known-issues.md)。\n- [交付清单](delivery-manifest.json)、[APK 签名与对齐证据](reports/evidence/apk-verification.json)。\n\nJVM 合成测试 {tests['tests']} 项通过；Debug / Release Lint 无错误，保留全部警告。\n真实账号与服务端互操作未测试；设备运行状态以测试报告为准。未包含签名私钥或密码。\n将所有文件留在同一目录可保持上述相对链接。报告压缩包内也包含同一套文档与证据。\n"""
    (delivery / "交付说明.md").write_text(readme)
    zip_tree(reports, delivery / "ZhiNengTiKa-native-1.0.0-reports.zip")
    all_files = sorted(file for file in delivery.rglob("*") if file.is_file())
    checksums = "".join(f"{sha(file)}  {file.relative_to(delivery).as_posix()}\n" for file in all_files)
    (delivery / "SHA256SUMS.txt").write_text(checksums)
    print(json.dumps({"deliveryDirectory": str(delivery), "sourceCommit": commit,
                      "fileCount": len(all_files) + 1,
                      "primaryFiles": [str(delivery / name) for name in [names[1], names[0], source_zip.name,
                                       "ZhiNengTiKa-native-1.0.0-reports.zip"]]}, indent=2, ensure_ascii=False))
