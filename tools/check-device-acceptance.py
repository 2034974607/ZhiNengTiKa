#!/usr/bin/env python3
"""Check acceptance-runner accounting with synthetic text and mocked processes only.

Never executes ADB, installs APKs, or writes a synthetic device acceptance result.
"""
import json
from pathlib import Path
import runpy
import subprocess
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parent.parent


def main():
    module = runpy.run_path(str(ROOT / "tools/acceptance-device.py"))
    checks = []

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        checks.append(name)

    def bundle(method, code, klass=None):
        return ("INSTRUMENTATION_STATUS: class=" + (klass or module["TEST_CLASS"]) + "\n"
                "INSTRUMENTATION_STATUS: test=" + method + "\n"
                "INSTRUMENTATION_STATUS: numtests=2\n"
                "INSTRUMENTATION_STATUS_CODE: " + str(code) + "\n")

    methods = sorted(module["TEST_METHODS"])
    good = "".join(bundle(method, 1) + bundle(method, 0) for method in methods) + "OK (2 tests)\nINSTRUMENTATION_CODE: -1\n"
    parse = module["instrumentation_summary"]
    check("two exact synthetic methods pass with terminal confirmation", parse(good)["complete"])
    check("crash text is rejected despite OK text", not parse(good + "INSTRUMENTATION_RESULT: shortMsg=Process crashed\n")["complete"])
    check("unknown status code is rejected", not parse("INSTRUMENTATION_STATUS_CODE: -5\n" + good)["complete"])
    check("wrong class is rejected", not parse(good.replace(module["TEST_CLASS"], "example.OtherTest"))["complete"])
    check("wrong method is rejected", not parse(good.replace(methods[0], "anotherTest"))["complete"])
    check("duplicate test does not substitute for the second", not parse(good.replace(methods[1], methods[0]))["complete"])
    check("success without a start is rejected", not parse(bundle(methods[0], 0) + bundle(methods[1], 0) + "OK (2 tests)\nINSTRUMENTATION_CODE: -1\n")["complete"])
    check("missing instrumentation terminal is rejected", not parse(good.replace("INSTRUMENTATION_CODE: -1\n", ""))["complete"])
    check("skipped instrumentation cannot be complete", not parse(good.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -3", 1))["complete"])
    check("an empty instrumentation output executes no tests", parse("")["started"] == 0 and not parse("")["complete"])

    launch = "Status: ok\nLaunchState: HOT\nActivity: " + module["RELEASE"] + "/.MainActivity\nTotalTime: 12\nWaitTime: 18\n"
    check("expected component and timing parse", module["launch_summary"](launch)["complete"])
    check("different activity does not pass", not module["launch_summary"](launch.replace("/.MainActivity", "/.OtherActivity"))["complete"])
    check("missing launch timing does not pass", not module["launch_summary"](launch.replace("TotalTime: 12\n", ""))["complete"])
    task_return = ("Warning: Activity not started, its current task has been brought to the front\n"
                   "Status: ok\nLaunchState: UNKNOWN (0)\nActivity: " + module["RELEASE"]
                   + "/.MainActivity\nWaitTime: 39\nComplete\n")
    returned = module["launch_summary"](task_return, allow_task_return=True)
    check("task return preserves wait-only timing and full launch state", returned["complete"] and returned["totalTimeMs"] is None and returned["waitTimeMs"] == 39 and returned["launchState"] == "UNKNOWN (0)")
    check("task return cannot substitute for cold launch", not module["launch_summary"](task_return)["complete"])
    check("wait-only result needs explicit task-return confirmation", not module["launch_summary"](task_return.replace("Warning: Activity not started, its current task has been brought to the front\n", ""), allow_task_return=True)["complete"])
    check("both memory report formats parse", module["memory_pss"](" TOTAL PSS: 4567 TOTAL RSS: 8900\n") == 4567 and module["memory_pss"](" TOTAL 4567 123 0\n") == 4567)
    check("missing memory is not zero", module["memory_pss"]("No process found\n") is None)

    def blank_report():
        return {"kind": "synthetic-host-selfcheck", "status": "starting", "deviceTestsExecuted": False,
                "device": {}, "installedPackages": [], "commands": [], "launches": []}

    evaluate = module["evaluate"]
    check("non-owner user rejected", not evaluate({"check": "owner"}, "10\n", blank_report()))
    check("multiple users rejected", not evaluate({"check": "single-user"}, "Users:\nUserInfo{0:Owner:13}\nUserInfo{10:Work:30}\n", blank_report()))
    check("retained package output rejected", not evaluate({"check": "absent"}, "package:" + module["DEBUG"] + "\n", blank_report()))
    check("package query error rejected", not evaluate({"check": "absent"}, "Error: permission denied\n", blank_report()))
    check("changed PID rejected", not evaluate({"check": "pid-continuity"}, "4321\n", {"resumeProcessPid": 1234}))
    check("cold start cannot count as foreground return", not evaluate({"check": "launch", "id": "resume-launch-1"}, launch.replace("HOT", "COLD"), blank_report()))
    check("cold state with details cannot count as wait-only return", not evaluate({"check": "launch", "id": "resume-launch-1"}, task_return.replace("UNKNOWN (0)", "COLD (1)"), blank_report()))
    check("foreground total time remains supported", evaluate({"check": "launch", "id": "resume-launch-1"}, launch, blank_report()))

    apks = [{"package": package, "path": "/synthetic/" + name, "sha256": "0" * 64} for package, name in module["APKS"]]
    plan = module["build_plan"]("/must-not-run/adb", "synthetic-device", apks, 3)
    installs = [index for index, step in enumerate(plan) if step["check"] == "install"]
    guards = [index for index, step in enumerate(plan) if step["check"] in {"absent", "owner", "single-user"}]
    check("all freshness guards precede installs", max(guards) < min(installs))
    check("retained-package query uses minus-u", all("-u" in step["argv"] for step in plan if step["check"] == "absent"))
    check("mutating app commands select owner user", all("--user" in step["argv"] and step["argv"][step["argv"].index("--user") + 1] == "0" for step in plan if step["check"] in {"install", "instrumentation", "launch"} or step["id"].startswith("cold-stop-")))
    check("no replacement deletion or reconnect command", all(not any(word in step["argv"] for word in ["-r", "uninstall", "clear", "connect", "reconnect", "pair"]) for step in plan if step["check"] != "instrumentation"))

    def mock_output(step):
        kind = step["check"]
        if kind == "device":
            return "device\n"
        if kind == "owner":
            return "0\n"
        if kind == "single-user":
            return "Users:\nUserInfo{0:Owner:13} running\n"
        if kind == "property":
            return "35\n" if step["id"].endswith("sdk") else "synthetic-value\n"
        if kind == "install":
            return "Success\n"
        if kind == "instrumentation":
            return good
        if kind in {"pid-baseline", "pid-continuity"}:
            return "1234\n"
        if kind == "launch":
            return task_return if step["id"].startswith("resume-") else launch.replace("HOT", "COLD")
        if kind == "memory":
            return "TOTAL PSS: 4567\n"
        return ""

    pending = iter(plan)

    def process_factory(argv, **kwargs):
        step = next(pending)
        assert argv == step["argv"]
        process = Mock(returncode=0)
        process.communicate.return_value = (mock_output(step), None)
        return process

    sink = Mock()
    sink.fileno.return_value = -1
    with patch("subprocess.Popen", side_effect=process_factory) as popen, patch("os.fsync"):
        report = blank_report()
        code = module["execute"](plan, report, sink)
        check("mocked full plan records all commands exactly once", code == 0 and popen.call_count == len(plan))
        check("mocked timings retain separate sample classes", len(report["coldProcessLaunch"]["totalTime"]["samplesMs"]) == 3 and len(report["sameProcessForegroundReturn"]["waitTime"]["samplesMs"]) == 3)
        check("wait-only aggregation never invents total times", report["sameProcessForegroundReturn"]["totalTime"]["samplesMs"] == [] and report["sameProcessForegroundReturn"]["totalTime"]["medianMs"] is None and report["sameProcessForegroundReturn"]["missingTotalTimeSamples"] == 3)
        snapshots = [json.loads(call.args[0]) for call in sink.write.call_args_list]
        check("in-progress snapshot precedes completed record", snapshots[1]["commands"][0]["status"] == "in-progress")
    failure_plan = plan[:2]
    for name, error in [("timeout", subprocess.TimeoutExpired("synthetic-adb", 1)),
                        ("Ctrl-C", KeyboardInterrupt()), ("SIGTERM handler", module["RunInterrupted"]("synthetic")),
                        ("post-spawn IO failure", OSError("synthetic read failure"))]:
        process = Mock(returncode=None)
        process.communicate.side_effect = [error, ("", None)]
        with patch("subprocess.Popen", return_value=process) as popen, patch("os.fsync"):
            report = blank_report()
            code = module["execute"](failure_plan, report, Mock())
            check(name + " stops without another command", code == 2 and popen.call_count == 1 and report["commands"][0]["status"] == "unknown")
    with patch("subprocess.Popen", side_effect=FileNotFoundError("synthetic missing executable")) as popen, patch("os.fsync"):
        report = blank_report()
        code = module["execute"](failure_plan, report, Mock())
        check("missing executable is not-started and not retried", code == 2 and popen.call_count == 1 and report["commands"][0]["status"] == "not-started")
    instrumentation_plan = [step for step in plan if step["check"] == "instrumentation"]
    process = Mock(returncode=1)
    process.communicate.return_value = (good, None)
    with patch("subprocess.Popen", return_value=process) as popen, patch("os.fsync"):
        report = blank_report()
        code = module["execute"](instrumentation_plan, report, Mock())
        check("nonzero instrumentation exit retains observed tests without passing", code == 1 and popen.call_count == 1 and report["deviceTestsExecuted"] and report["instrumentation"]["started"] == 2 and not report["instrumentation"]["complete"])
    process = Mock(returncode=None)
    process.communicate.side_effect = [subprocess.TimeoutExpired("synthetic-adb", 1, output=bundle(methods[0], 1).encode()), ("", None)]
    with patch("subprocess.Popen", return_value=process) as popen, patch("os.fsync"):
        report = blank_report()
        code = module["execute"](instrumentation_plan, report, Mock())
        check("instrumentation timeout retains partial start with unknown result", code == 2 and popen.call_count == 1 and report["deviceTestsExecuted"] and report["instrumentation"]["started"] == 1 and report["instrumentation"]["commandResultUnknown"] and not report["instrumentation"]["complete"])
    print(json.dumps({"kind": "synthetic-host-runner-checks", "passedChecks": len(checks), "checks": checks,
                      "adbProcessesStarted": 0, "deviceTestsExecuted": False}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
