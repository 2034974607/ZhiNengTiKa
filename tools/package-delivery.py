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
import runpy
import shutil
import subprocess
import sys
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
    parser.add_argument("--apk-source-commit", help="Earlier delivered source commit when repackaging unchanged APK build inputs; defaults to HEAD")
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
    apk_source_commit = git("rev-parse", "--verify", "--end-of-options", (args.apk_source_commit or commit) + "^{commit}")
    build_inputs = ["app", "protocol", "gradle", "build.gradle.kts", "settings.gradle.kts",
                    "gradle.properties", "gradlew", "gradlew.bat", "tools/build-android.py"]
    if git("diff", "--name-only", apk_source_commit, commit, "--", *build_inputs):
        raise SystemExit("APK build inputs changed since --apk-source-commit; rebuild and verify APKs first")
    runner_check = subprocess.run([sys.executable, str(ROOT / "tools/check-device-acceptance.py")],
                                  cwd=ROOT, check=True, capture_output=True, text=True)
    runner_evidence = json.loads(runner_check.stdout)
    if (runner_evidence.get("kind") != "synthetic-host-runner-checks"
            or runner_evidence.get("adbProcessesStarted") != 0
            or runner_evidence.get("deviceTestsExecuted") is not False
            or not runner_evidence.get("checks")
            or runner_evidence.get("passedChecks") != len(runner_evidence["checks"])):
        raise SystemExit("Acceptance-runner host checks did not produce the expected complete result")
    runner_evidence["executedAtUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    runner_evidence["sourceSha256"] = {
        name: sha(ROOT / name) for name in ["tools/acceptance-device.py", "tools/check-device-acceptance.py"]}
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
    device_validation = json.loads((ROOT / "artifacts/evidence/device-20261128/session-final.json").read_text())
    instrumentation_log = (ROOT / "artifacts/evidence/device-20261128/instrumentation-final-stdout.txt").read_text()
    instrumentation = runpy.run_path(str(ROOT / "tools/acceptance-device.py"))["instrumentation_summary"](instrumentation_log)
    current_hashes = {item["package"]: item["sha256"] for item in verification["apks"]}
    if (not instrumentation["complete"] or instrumentation["passed"] != 2
            or (ROOT / "artifacts/evidence/device-20261128/instrumentation-final-exit-code.txt").read_text().strip() != "0"
            or (ROOT / "artifacts/evidence/device-20261128/instrumentation-final-stderr.txt").read_text().strip()
            or device_validation.get("deviceTestsExecuted") is not True
            or device_validation.get("apkSha256ByPackage") != current_hashes
            or device_validation.get("instrumentation", {}).get("testApkSha256") != current_hashes["app.zhinengtika.nativeapp.debug.test"]
            or device_validation.get("instrumentation", {}).get("debugApkSha256") != current_hashes["app.zhinengtika.nativeapp.debug"]
            or any(device_validation.get("instrumentation", {}).get(key) != value for key, value in instrumentation.items())
            or device_validation.get("releasePerformance", {}).get("apkSha256") != current_hashes["app.zhinengtika.nativeapp"]
            or len(device_validation.get("releasePerformance", {}).get("coldProcessLaunch", {}).get("samples", [])) != 5
            or len(device_validation.get("releasePerformance", {}).get("sameProcessForegroundReturn", {}).get("samples", [])) != 5):
        raise SystemExit("Final device evidence does not match the verified APKs and two complete tests")
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
                 "tools/verify-apks.py", "tools/package-delivery.py", "tools/acceptance-device.py",
                 "tools/check-device-acceptance.py"]:
        copy_file(ROOT / name, reports / name)
    copy_tree(ROOT / "artifacts/evidence", reports / "evidence")
    with (reports / "evidence/device-runner-selfcheck.json").open("x", encoding="utf-8") as stream:
        json.dump(runner_evidence, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    subprocess.run([sys.executable, str(ROOT / "tools/acceptance-device.py"), "--delivery", str(delivery),
                    "--output", str(reports / "evidence/device-acceptance-plan.json")],
                   cwd=ROOT, check=True, capture_output=True, text=True)
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
        "apkBuildInputs": {"sourceCommit": apk_source_commit, "matchesDeliverySource": True,
                           "pathsCompared": build_inputs, "reusedApks": apk_source_commit != commit},
        "jvmTests": tests, "lint": lint,
        "deviceAcceptance": {"plan": "reports/evidence/device-acceptance-plan.json",
                             "runnerSelfcheck": "reports/evidence/device-runner-selfcheck.json",
                             "runnerHostChecksPassed": runner_evidence["passedChecks"],
                             "planExecutesDeviceCommands": False,
                             "legacyRunnerExecuted": False,
                             "legacyRunnerInapplicableReasons": ["multiple Android users", "preexisting Release/Debug packages"],
                             "method": "separately authorized phone-tool and user0 Shizuku validation",
                             "results": "reports/docs/device-results-20261128.md",
                             "evidence": "reports/evidence/device-20261128/session-final.json",
                             "testPackageSha256": device_validation["apkSha256ByPackage"]["app.zhinengtika.nativeapp.debug.test"]},
        "apkVerification": "reports/evidence/apk-verification.json",
        "deviceTestsExecuted": instrumentation["started"] > 0,
        "instrumentation": instrumentation,
        "deviceValidation": "reports/evidence/device-validation.json",
        "realServiceTestsExecuted": False,
        "limitations": ["Two Debug Compose tests and limited Release smoke/performance checks passed on Android API37 user0; this is not complete device coverage.",
                        "The device also has user999, which was not tested; clean fresh-install validation remains separate.",
                        "Foreground task returns only reported WaitTime; no TotalTime, fully-drawn, scrolling, peak-memory, power or Qt comparison is claimed.",
                        "No real student accounts, uploads or answer submissions were used.",
                        "Navigation custom lint registries are incompatible with this lint API; skipped checks are reported.",
                        "Synthetic host-JVM benchmark is distinct from the separately recorded Android samples."],
    }
    (delivery / "delivery-manifest.json").write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n")
    readme = f"""# 新教育智能题卡 原生版 1.0.0 交付\n\n源码提交：`{commit}`（独立新历史，无远端推送）。\n\n- [Release APK]({names[1]})：日常安装入口，独立签名，启用 R8。\n- [Debug APK]({names[0]})：开发与 UI 测试使用，与 Release 分包。\n- [UI 测试 APK]({names[2]})：配合 Debug 安装，不是单独的应用。\n- [完整源码 ZIP]({source_zip.name})：包含 Gradle wrapper、构建脚本、测试与许可。\n- [独立 Git bundle]({bundle.name})：可用 `git clone 文件路径 新目录` 恢复新历史。\n- [构建与签名](reports/BUILDING.md)、[测试](reports/TESTING.md)、[性能](reports/PERFORMANCE.md)。\n- [功能对照](reports/docs/feature-comparison.md)、[协议](reports/docs/protocol.md)、[已知问题](reports/docs/known-issues.md)。\n- [本轮真机结果](reports/docs/device-results-20261128.md)、[设备验收方案](reports/docs/device-acceptance.md)、[默认命令计划](reports/evidence/device-acceptance-plan.json)、[验收脚本合成检查](reports/evidence/device-runner-selfcheck.json)。\n- [交付清单](delivery-manifest.json)、[APK 签名与对齐证据](reports/evidence/apk-verification.json)。\n\nJVM 合成测试 {tests['tests']} 项通过；Debug / Release Lint 无错误，保留全部警告。\n验收脚本主机合成检查 {runner_evidence['passedChecks']} 项通过，未启动 ADB；附带的计划未执行设备命令。\nAPK 构建输入对应源码提交 `{apk_source_commit}`，与本交付的 Android / protocol 模块、Gradle 输入及构建入口一致。成功构建日志保留实际时间；本轮修复 Espresso 测试兼容性，重新执行三个 APK 的构建与静态核验，Debug 摘要保持不变。\nOPPO PHY120 / Android 17（API 37）/ 用户 0 上，两项 Debug Compose 测试实际通过；Release 完成离线阅读、持久恢复、SAF 取消和五次进程冷启动/五次同进程返回的有限验证。其他用户、相机、完整文件交换和线上业务仍未覆盖；返回前台的 WaitTime 不作为渲染耗时。\n真实账号与服务端互操作未测试；设备运行状态以测试报告为准。未包含签名私钥或密码。\n将所有文件留在同一目录可保持上述相对链接。报告压缩包内也包含同一套文档与证据。\n"""
    (delivery / "交付说明.md").write_text(readme)
    zip_tree(reports, delivery / "ZhiNengTiKa-native-1.0.0-reports.zip")
    all_files = sorted(file for file in delivery.rglob("*") if file.is_file())
    checksums = "".join(f"{sha(file)}  {file.relative_to(delivery).as_posix()}\n" for file in all_files)
    (delivery / "SHA256SUMS.txt").write_text(checksums)
    print(json.dumps({"deliveryDirectory": str(delivery), "sourceCommit": commit,
                      "fileCount": len(all_files) + 1,
                      "primaryFiles": [str(delivery / name) for name in [names[1], names[0], source_zip.name,
                                       "ZhiNengTiKa-native-1.0.0-reports.zip"]]}, indent=2, ensure_ascii=False))
