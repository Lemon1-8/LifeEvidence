"""Run the optimized APK's instrumentation and collect reproducible emulator evidence."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME", Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"))
ADB = str(SDK / "platform-tools/adb.exe")
PACKAGE = "app.shenghuoyouju"


def run(serial, *args, check=True):
    result = subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--classes", default="app.shenghuoyouju.CoreIntegrationTest,app.shenghuoyouju.UiFlowTest,app.shenghuoyouju.ResultStabilityTest,app.shenghuoyouju.DirectoryNavigationTest")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--clear", action="store_true")
    args = parser.parse_args()
    stamp = time.strftime("%Y%m%d-%H%M%S")
    output = ROOT / f"artifacts/test-results/{args.serial}/{stamp}"
    output.mkdir(parents=True, exist_ok=True)
    app_apk = ROOT / "app/build/outputs/apk/release/app-x86_64-release.apk"
    test_apk = ROOT / "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"
    package_hashes = {apk.name: hashlib.sha256(apk.read_bytes()).hexdigest() for apk in (app_apk, test_apk)}
    for apk in [app_apk, test_apk]:
        installed = run(args.serial, "install", "-r", str(apk))
        (output / (apk.stem + "-install.txt")).write_text(installed.stdout, encoding="utf-8")
    run(args.serial, "shell", "am", "force-stop", PACKAGE)
    if args.clear:
        run(args.serial, "shell", "pm", "clear", PACKAGE)
    network = {}
    if args.offline:
        for command in [("cmd", "connectivity", "airplane-mode", "enable"), ("svc", "wifi", "disable"), ("svc", "data", "disable")]:
            network[" ".join(command)] = run(args.serial, "shell", *command, check=False).stdout
        probe = run(args.serial, "shell", "ping", "-c", "1", "-W", "3", "1.1.1.1", check=False)
        network["probe"] = {"exit": probe.returncode, "output": probe.stdout + probe.stderr}
        assert probe.returncode != 0, "Offline test aborted: external network remains reachable"
    (output / "network.json").write_text(json.dumps(network, ensure_ascii=False, indent=2), encoding="utf-8")
    run(args.serial, "logcat", "-c")
    started = time.monotonic()
    result = run(args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", args.classes,
                 PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", check=False)
    log = result.stdout + result.stderr
    (output / "instrumentation.txt").write_text(log, encoding="utf-8")
    (output / "logcat.txt").write_text(run(args.serial, "logcat", "-d", check=False).stdout, encoding="utf-8")
    (output / "crashes.txt").write_text(run(args.serial, "logcat", "-d", "-b", "crash", check=False).stdout, encoding="utf-8")
    run(args.serial, "pull", f"/sdcard/Android/data/{PACKAGE}/files/test-results", str(output / "device-reports"), check=False)
    passed = result.returncode == 0 and "OK (" in log and not any(marker in log for marker in ["FAILURES!!!", "INSTRUMENTATION_FAILED", "shortMsg=Process crashed"])
    report = {"serial": args.serial, "api": run(args.serial, "shell", "getprop", "ro.build.version.sdk").stdout.strip(),
              "pageSize": run(args.serial, "shell", "getconf", "PAGE_SIZE").stdout.strip(), "offline": args.offline,
              "clearData": args.clear, "durationSeconds": round(time.monotonic() - started, 2), "passed": passed,
              "classes": args.classes, "apkSha256": package_hashes, "artifactDirectory": str(output)}
    (output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(log[-16000:])
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
