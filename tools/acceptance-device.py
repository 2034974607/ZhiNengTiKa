#!/usr/bin/env python3
"""Plan or run acceptance on an explicitly selected, already authorized ADB device.

The default only writes a plan and never executes ADB. --run installs the three
verified APKs only if their package names are absent, runs the two synthetic UI
tests, and records Release launch timings and process memory. No login, network
business action, data clear, uninstall, pairing, reconnect or automatic retry.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
RELEASE = "app.zhinengtika.nativeapp"
DEBUG = RELEASE + ".debug"
TEST = DEBUG + ".test"
COMPONENT = RELEASE + "/" + RELEASE + ".MainActivity"
TEST_CLASS = RELEASE + ".AppNavigationTest"
TEST_METHODS = {"homeShowsQueryAndAccountNavigationOpensLoginForm", "offlineSyntheticCardNeverOffersSubmission"}
APKS = [(DEBUG, "ZhiNengTiKa-1.0.0-debug.apk"),
        (RELEASE, "ZhiNengTiKa-1.0.0-release.apk"),
        (TEST, "ZhiNengTiKa-1.0.0-ui-tests.apk")]


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def instrumentation_summary(text):
    bundle, started, passed, codes, identity_errors = {}, set(), set(), [], []
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if separator:
                bundle[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            try:
                code = int(line.partition(":")[2].strip())
            except ValueError:
                code = -999
            codes.append(code)
            if code in {0, 1}:
                identity = (bundle.get("class"), bundle.get("test"))
                valid = identity[0] == TEST_CLASS and identity[1] in TEST_METHODS and bundle.get("numtests", "2") == "2"
                if not valid:
                    identity_errors.append("Unexpected class, method or test count")
                elif code == 1:
                    if identity in started:
                        identity_errors.append("Duplicate test start")
                    started.add(identity)
                else:
                    if identity not in started or identity in passed:
                        identity_errors.append("Missing start or duplicate test completion")
                    passed.add(identity)
            bundle = {}
    failed = sum(value in {-1, -2} for value in codes)
    skipped = sum(value in {-3, -4} for value in codes)
    unknown = [value for value in codes if value not in {1, 0, -1, -2, -3, -4}]
    ok = re.search(r"^OK \((\d+) tests?\)\s*$", text, re.MULTILINE)
    terminal = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", text, re.MULTILINE)
    crashed = any(marker in text for marker in ["INSTRUMENTATION_FAILED", "FAILURES!!!", "shortMsg=", "Process crashed"])
    expected = {(TEST_CLASS, method) for method in TEST_METHODS}
    complete = bool(ok and ok[1] == "2" and started == passed == expected and failed == skipped == 0
                    and terminal == ["-1"] and not crashed and not unknown and not identity_errors)
    return {"started": len(started), "passed": len(passed), "failed": failed, "skipped": skipped, "expected": 2,
            "unknownStatusCodes": unknown, "identityErrors": identity_errors, "complete": complete}


def launch_summary(text):
    status = re.search(r"^Status:\s*(\S+)\s*$", text, re.MULTILINE)
    total = re.search(r"^TotalTime:\s*(\d+)\s*$", text, re.MULTILINE)
    wait = re.search(r"^WaitTime:\s*(\d+)\s*$", text, re.MULTILINE)
    state = re.search(r"^LaunchState:\s*(\S+)\s*$", text, re.MULTILINE)
    activity = re.search(r"^Activity:\s*(\S+)\s*$", text, re.MULTILINE)
    actual = activity[1] if activity else None
    expected_activity = actual in {COMPONENT, RELEASE + "/.MainActivity"}
    return {"complete": bool(status and status[1] == "ok" and total and expected_activity),
            "totalTimeMs": int(total[1]) if total else None,
            "waitTimeMs": int(wait[1]) if wait else None,
            "launchState": state[1] if state else None, "activity": actual}


def memory_pss(text):
    summary = re.search(r"^\s*TOTAL PSS:\s*(\d+)\b", text, re.MULTILINE)
    table = re.search(r"^\s*TOTAL\s+(\d+)\s+\d+", text, re.MULTILINE)
    return int((summary or table)[1]) if summary or table else None


def checked_apks(delivery):
    report = json.loads((delivery / "reports/evidence/apk-verification.json").read_text())
    expected = {item["package"]: item for item in report["apks"]}
    if not all(report.get(key) for key in ["independentReleaseAndDebugCertificates", "testSharesDebugCertificate",
                                           "releaseNotDebuggable", "release64BitNative16KiBAlignment"]):
        raise ValueError("Delivery APK verification flags did not pass")
    result = []
    for package, name in APKS:
        file = delivery / name
        record = expected[package]
        if file.is_symlink() or not file.is_file() or not record["checksPassed"] or sha(file) != record["sha256"]:
            raise ValueError("APK is missing or changed after verification: " + name)
        if record["minSdk"] != 26 or record["targetSdk"] != 35:
            raise ValueError("Unexpected SDK metadata")
        result.append({"package": package, "path": str(file), "sha256": record["sha256"]})
    return result


def stage_apks(apks):
    # A private snapshot keeps later installs independent of mutable shared-storage paths.
    directory = Path(tempfile.mkdtemp(prefix="zhinengtika-device-apks-"))
    directory.chmod(0o700)
    copies = []
    for item in apks:
        source = Path(item["path"])
        target = directory / source.name
        with source.open("rb") as reader, target.open("xb") as writer:
            shutil.copyfileobj(reader, writer)
        target.chmod(0o600)
        if sha(target) != item["sha256"]:
            raise ValueError("APK changed while staging; no device command was started")
        copies.append({**item, "deliveryPath": item["path"], "path": str(target)})
    return copies


def build_plan(adb, serial, apks, samples):
    prefix = [str(adb), "-s", serial]
    steps = []

    def add(label, arguments, check="exit", timeout=30):
        steps.append({"id": label, "argv": prefix + arguments, "check": check, "timeoutSeconds": timeout})

    add("device-state", ["get-state"], "device")
    add("current-user", ["shell", "am", "get-current-user"], "owner")
    add("device-users", ["shell", "pm", "list", "users"], "single-user")
    for key in ["ro.product.manufacturer", "ro.product.model", "ro.build.version.release", "ro.build.version.sdk"]:
        add("property-" + key, ["shell", "getprop", key], "property")
    add("battery-context", ["shell", "dumpsys", "battery"])
    # Run all presence checks before the first install; never replace an existing package.
    for item in apks:
        add("absent-" + item["package"], ["shell", "pm", "list", "packages", "-u", "--user", "0", item["package"]], "absent")
    for item in apks:
        add("install-" + item["package"], ["install", "--no-streaming", "--user", "0", item["path"]], "install", 120)
    add("synthetic-ui-tests", ["shell", "am", "instrument", "--user", "0", "-w", "-r", "-e", "class", TEST_CLASS,
                               TEST + "/androidx.test.runner.AndroidJUnitRunner"], "instrumentation", 180)
    for index in range(samples):
        add(f"cold-stop-{index + 1}", ["shell", "am", "force-stop", "--user", "0", RELEASE])
        add(f"cold-launch-{index + 1}", ["shell", "am", "start", "--user", "0", "-W", "-n", COMPONENT], "launch", 45)
    add("release-baseline-pid", ["shell", "pidof", RELEASE], "pid-baseline")
    for index in range(samples):
        add(f"resume-home-{index + 1}", ["shell", "input", "keyevent", "KEYCODE_HOME"])
        add(f"resume-pid-before-{index + 1}", ["shell", "pidof", RELEASE], "pid-continuity")
        add(f"resume-launch-{index + 1}", ["shell", "am", "start", "--user", "0", "-W", "-n", COMPONENT], "launch", 45)
        add(f"resume-pid-after-{index + 1}", ["shell", "pidof", RELEASE], "pid-continuity")
    add("release-memory", ["shell", "dumpsys", "meminfo", RELEASE], "memory")
    add("release-frame-records", ["shell", "dumpsys", "gfxinfo", RELEASE, "framestats"])
    return steps


def evaluate(step, output, report):
    check = step["check"]
    if check == "device":
        return output.strip().splitlines()[-1:] == ["device"]
    if check == "owner":
        return output.strip() == "0"
    if check == "single-user":
        users = re.findall(r"\bUserInfo\{(\d+):", output)
        return users == ["0"] and "Error" not in output and "Exception" not in output
    if check == "property":
        value = output.strip()
        report["device"][step["id"].removeprefix("property-")] = value
        return bool(value) and (step["id"] != "property-ro.build.version.sdk" or value.isdigit() and int(value) >= 26)
    if check == "absent":
        # Includes retained/uninstalled package history; any unexpected output also refuses installation.
        return not output.strip()
    if check in {"pid-baseline", "pid-continuity"}:
        value = output.strip()
        if not re.fullmatch(r"[1-9]\d*", value):
            return False
        if check == "pid-baseline":
            report["resumeProcessPid"] = int(value)
            return True
        return int(value) == report.get("resumeProcessPid")
    if check == "install":
        success = bool(re.search(r"^Success\s*$", output, re.MULTILINE))
        if success:
            report["installedPackages"].append(step["id"].removeprefix("install-"))
        return success
    if check == "instrumentation":
        report["instrumentation"] = instrumentation_summary(output)
        report["deviceTestsExecuted"] = report["instrumentation"]["started"] > 0
        return report["instrumentation"]["complete"]
    if check == "launch":
        result = launch_summary(output)
        if (step["id"].startswith("resume-") and result["launchState"] == "COLD"
                or step["id"].startswith("cold-") and result["launchState"] not in {None, "COLD"}):
            result["complete"] = False
        report["launches"].append({"id": step["id"], **result})
        return result["complete"]
    if check == "memory":
        report["releasePssKiB"] = memory_pss(output)
        return report["releasePssKiB"] is not None
    return True


class RunInterrupted(Exception):
    pass


def execute(plan, report, sink):
    def save():
        # Hold the exclusive descriptor. A leading newline isolates a partial last write
        # after interruption; consumers use the last complete JSON object line.
        sink.write("\n" + json.dumps(report, ensure_ascii=False) + "\n")
        sink.flush()
        os.fsync(sink.fileno())

    save()
    for step in plan:
        record = {"id": step["id"], "status": "in-progress", "startedAtUtc": now(), "argv": step["argv"]}
        report["commands"].append(record)
        process = None
        try:
            save()  # An interrupted command is visible even if this host process is killed.
            process = subprocess.Popen(step["argv"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            stdout, _ = process.communicate(timeout=step["timeoutSeconds"])
            record.update({"exitCode": process.returncode, "output": stdout, "finishedAtUtc": now()})
            if process.returncode != 0 and step["check"] == "instrumentation":
                # A client error can occur after tests have already started on Android.
                evaluate(step, stdout, report)
                report["instrumentation"]["complete"] = False
            passed = process.returncode == 0 and evaluate(step, stdout, report)
            record["status"] = "passed" if passed else "failed"
            if not passed:
                report.update({"status": "stopped-on-failure", "stoppedAt": step["id"]})
                save()
                return 1
        except (subprocess.TimeoutExpired, KeyboardInterrupt, RunInterrupted, OSError) as error:
            # Stop only the local client. A remote install or test may already be running.
            if process is not None:
                try:
                    process.kill()
                    process.communicate(timeout=5)
                except (OSError, subprocess.TimeoutExpired, KeyboardInterrupt, RunInterrupted):
                    pass
            partial = getattr(error, "output", "") or ""
            partial = partial.decode(errors="replace") if isinstance(partial, bytes) else partial
            not_started = process is None and isinstance(error, (FileNotFoundError, PermissionError))
            if step["check"] == "instrumentation":
                evaluate(step, partial, report)
                report["instrumentation"].update({"complete": False, "commandResultUnknown": not not_started})
            record.update({"status": "not-started" if not_started else "unknown",
                           "output": partial,
                           "reason": type(error).__name__, "deviceSideEffectsMayContinue": not not_started})
            report.update({"status": "stopped-before-command-start" if not_started else "stopped-on-unknown-result",
                           "stoppedAt": step["id"]})
            save()
            return 2
        save()
    report["status"] = "scripted-checks-complete"
    report["finishedAtUtc"] = now()
    for prefix, key in [("cold-launch-", "coldProcessLaunch"), ("resume-launch-", "sameProcessForegroundReturn")]:
        values = [item["totalTimeMs"] for item in report["launches"] if item["id"].startswith(prefix) and item["complete"]]
        report[key] = {"samplesMs": values, "medianMs": statistics.median(values), "minMs": min(values), "maxMs": max(values)}
    save()
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--delivery", type=Path, required=True, help="Unpacked delivery directory containing all three APKs and reports")
    parser.add_argument("--serial", default="DEVICE_SERIAL", help="Explicit already-authorized ADB serial; required for --run")
    parser.add_argument("--adb", type=Path, help="Host-compatible ADB executable")
    parser.add_argument("--samples", type=int, choices=range(3, 21), default=5)
    parser.add_argument("--run", action="store_true", help="Execute the reviewed plan on the specified test device")
    parser.add_argument("--output", type=Path, help="New plan JSON or append-only run JSONL; refuses existing files")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:\[\]-]{0,199}", args.serial):
        parser.error("Invalid device serial")
    if args.run and args.serial == "DEVICE_SERIAL":
        parser.error("Choose an explicit authorized test device serial")
    adb = args.adb
    if adb is None:
        candidate = ROOT / "tools/arm64-sdk-tools/platform-tools/adb"
        adb = Path(shutil.which("adb") or (str(candidate) if os.uname().machine in {"aarch64", "arm64"} and candidate.is_file() else "adb"))
    apks = checked_apks(args.delivery.expanduser().resolve())
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    output = (args.output or ROOT / "artifacts/device-acceptance" / (stamp + ("-run.jsonl" if args.run else "-plan.json"))).expanduser().absolute()
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as sink:
        if args.run:
            apks = stage_apks(apks)
        plan = build_plan(adb, args.serial, apks, args.samples)
        report = {"createdAtUtc": now(), "kind": "device-acceptance-run" if args.run else "device-acceptance-plan",
                  "status": "starting" if args.run else "plan-only", "deviceTestsExecuted": False,
                  "realBusinessRequestsMade": False, "device": {}, "installedPackages": [], "commands": [], "launches": [],
                  "apks": apks, "plan": plan,
                  "limitations": ["Requires an unlocked, authorized single-user test device running owner user 0, with no retained package history.",
                                  "Does not pair, connect, retry, replace packages, uninstall or clear data.",
                                  "A timeout or cancellation stops the run; device side effects may continue.",
                                  "Process-cold am start timings and same-process foreground return are not Macrobenchmark or fully drawn timings.",
                                  "PSS is one snapshot; frame output covers initial screens only, not list scrolling, power or temperature profiling.",
                                  "Manual SAF, camera, permissions, lifecycle and authorized service acceptance remain separate."]}
        if not args.run:
            json.dump(report, sink, ensure_ascii=False, indent=2)
            sink.write("\n")
            code = 0
        else:
            def terminated(signum, frame):
                raise RunInterrupted("Termination requested")
            previous = signal.signal(signal.SIGTERM, terminated)
            try:
                code = execute(plan, report, sink)
            finally:
                signal.signal(signal.SIGTERM, previous)
    print(json.dumps({"report": str(output), "status": report["status"], "deviceTestsExecuted": report["deviceTestsExecuted"],
                      "plannedCommands": len(plan), "recordedCommands": len(report["commands"])}, ensure_ascii=False, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
