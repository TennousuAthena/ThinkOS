#!/usr/bin/env python3
"""Exercise native recovery with the real settings digest quarantined; restore app data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from device_app_files import read_app_file

PACKAGE = "moe.kiwi.thinkos"
HOME = PACKAGE + "/.MainActivity"
LAUNCHER = "org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher"
FILES = ("files/settings/local.json", "files/settings/server.json", "files/native-failures.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="HUA0BWUT")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/ui-validation/rescue")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ["adb", "-s", args.serial]
    labels = json.loads((ROOT / "shared/i18n/system/zh-Hans.json").read_text())
    report = {"complete": False, "passed": [], "issues": [], "method": "three orphan flight markers; no real crash"}

    def adb(*values, data=None, check=True):
        run = subprocess.run(base + list(values), input=data, capture_output=True, timeout=25)
        if check and run.returncode:
            raise RuntimeError(run.stderr.decode() + run.stdout.decode())
        return run.stdout if run.returncode == 0 else None

    def shell(*values, **kwargs):
        return adb("shell", shlex.join(map(str, values)), **kwargs)

    def debug(command, value=None, kind="--es"):
        values = ["am", "broadcast", "-n", PACKAGE + "/.DebugReceiver", "--es", "command", command]
        if value is not None:
            values += [kind, "value", str(value)]
        shell(*values)

    def read_app(path):
        return read_app_file(args.serial, path)

    def write_app(path, value):
        if value is None:
            shell("run-as", PACKAGE, "rm", "-f", path, path + ".bak")
        else:
            shell("run-as", PACKAGE, "sh", "-c", "cat > " + shlex.quote(path + ".ui-restore"), data=value)
            shell("run-as", PACKAGE, "mv", path + ".ui-restore", path)
            shell("run-as", PACKAGE, "rm", "-f", path + ".bak")

    def saved():
        raw = read_app(FILES[0])
        return json.loads(raw) if raw else {}

    def pid():
        return shell("pidof", PACKAGE).decode().strip()

    def state():
        debug("state")
        log = adb("logcat", "-d", "--pid=" + pid(), "-s", "ThinkOS:V").decode()
        return log.split("DEBUG state=")[-1]

    def settings_diagnostic():
        match = re.search(r"id=settings, version=([^,]+), bytes=(\d+), disabled=(true|false)", state())
        return {"version": match[1], "bytes": int(match[2]), "disabled": match[3] == "true"} if match else None

    def wait_for(test, description, seconds=10):
        deadline = time.monotonic()+seconds
        while time.monotonic() < deadline:
            if test():
                return
            time.sleep(.2)
        raise AssertionError(description)

    def snapshot(name=None):
        path = "/sdcard/thinkos-rescue-ui.xml"
        try:
            shell("uiautomator", "dump", "--compressed", path)
            data = shell("cat", path)
            (args.output / ((name or "last-ui") + ".xml")).write_bytes(data)
            return ET.fromstring(data)
        finally:
            shell("rm", "-f", path)

    def find(text, seconds=6):
        deadline = time.monotonic()+seconds
        while time.monotonic() < deadline:
            node = next((n for n in snapshot().iter("node") if n.get("text") == text), None)
            if node is not None:
                return node
        raise AssertionError("Missing native UI text: " + text)

    def box(node):
        return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))

    def tap(node):
        a, b, c, d = box(node)
        shell("input", "tap", (a+c)//2, (b+d)//2)
        time.sleep(.3)

    def shot(name):
        data = adb("exec-out", "screencap", "-p")
        (args.output / (name + ".png")).write_bytes(data)
        return struct.unpack(">II", data[16:24])

    def passed(message):
        report["passed"].append(message)
        print("PASS " + message, flush=True)

    def go_home():
        for _ in range(5):
            if "page=home.main," in state():
                return
            shell("input", "swipe", 1100, 400, 150, 400, 450)
            time.sleep(.4)
        raise AssertionError("Home page not reachable")

    def open_menu():
        go_home()
        shell("input", "swipe", 1270, 380, 1270, 380, 900)
        find(labels["maintenance_title"])

    def check_menu(name):
        tree = snapshot(name)
        w, h = shot(name)
        items = [labels[key] for key in ("maintenance_diagnostics", "maintenance_restore_plugins", "maintenance_reset_settings", "maintenance_android_settings", "maintenance_exit_safe_mode", "maintenance_back")]
        rectangles = []
        for text in items:
            node = next(n for n in tree.iter("node") if n.get("text") == text)
            a, b, c, d = box(node)
            assert 0 <= a < c <= w and 0 <= b < d <= h
            assert min(c-a, d-b) >= 78, "Native menu target smaller than 48dp: " + text
            assert all(max(a, x1) >= min(c, x2) or max(b, y1) >= min(d, y2) for x1, y1, x2, y2 in rectangles)
            rectangles.append((a, b, c, d))
        passed(name + ": all native recovery actions fit and have at least 48dp touch targets")

    assert pid()
    assert not shell("run-as", PACKAGE, "ls", "files/dev-plugins").strip()
    assert PACKAGE in shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").decode()
    original = {path: read_app(path) for path in FILES}
    for value in original.values():
        if value: json.loads(value)
    backup = Path(tempfile.mkdtemp(prefix="thinkos-rescue-backup-"))
    for path, value in original.items():
        if value is not None:
            file = backup / Path(path).name
            with file.open("wb") as stream:
                os.chmod(file, 0o600)
                stream.write(value); stream.flush(); os.fsync(stream.fileno())
    apk = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
    with zipfile.ZipFile(apk) as archive:
        index = json.loads(archive.read("assets/plugins/settings/index.json"))
    report["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
    key = ("settings@" + index["digest"]).encode()
    try:
        debug("hold_attention", "AWAKE")
        debug("orientation", 0, "--ei")
        debug("lux", 20, "--ef")
        shell("input", "keyevent", 3)
        go_home()
        shell("input", "swipe", 1270, 380, 1270, 380, 900)
        find(labels["setting_system_name"])
        # Seed visible, valid device edits before making the settings Lua unavailable.
        if saved().get("system", {}).get("theme.mode", "auto") != "dark":
            tap(find(labels["setting_theme_mode"]))
            tap(find(labels["setting_theme_mode_dark"]))
        if saved().get("system", {}).get("theme.night", True):
            tap(find(labels["setting_theme_night"]))
        wait_for(lambda: saved().get("system", {}).get("theme.mode") == "dark"
                 and saved().get("system", {}).get("theme.night") is False, "test overrides did not persist")
        assert saved()
        passed("valid test edits are saved through the generated native settings page")

        shell("cmd", "package", "set-home-activity", LAUNCHER)
        shell("am", "force-stop", PACKAGE)
        history = json.loads(original[FILES[2]]) if original[FILES[2]] else {}
        history.pop(key.decode(), None)
        write_app(FILES[2], json.dumps(history).encode())
        for number in range(3):
            if number: shell("am", "force-stop", PACKAGE)
            write_app("files/native-flight.bin", (struct.pack(">II", 0x544F534E, len(key))+key).ljust(512, b"\0"))
            shell("am", "start", "-W", "-n", HOME)
            wait_for(lambda: (d := settings_diagnostic()) is not None and d["disabled"] == (number == 2), "quarantine state did not reach the catalog")
        shell("cmd", "package", "set-home-activity", HOME)
        debug("hold_attention", "AWAKE")
        debug("orientation", 0, "--ei")
        debug("lux", 20, "--ef")
        report["quarantinedSettings"] = settings_diagnostic()
        report["uiPid"] = pid()
        assert report["quarantinedSettings"]["disabled"] and report["quarantinedSettings"]["bytes"] == 0
        assert "safeMode=false" in state()
        passed("three orphan markers quarantine the real settings digest without crashing the application")

        open_menu()
        passed("long press falls back to native recovery when the settings Lua is disabled")
        check_menu("menu-landscape")
        tap(find(labels["maintenance_diagnostics"]))
        find(labels["maintenance_diag_title"])
        diagnostic = snapshot("diagnostics")
        body = next(n for n in diagnostic.iter("node") if "本地运行 · 无需 Server" in n.get("text", ""))
        assert "id=settings" in body.get("text") and "disabled=true" in body.get("text")
        shot("diagnostics")
        passed("native diagnostics shows the disabled plugin and works without Server")
        tap(find(labels["maintenance_back"]))
        open_menu()
        debug("orientation", 1, "--ei")
        time.sleep(.5)
        check_menu("menu-portrait")
        tap(find(labels["maintenance_back"]))
        passed("the native return button closes recovery")

        debug("orientation", 0, "--ei")
        time.sleep(.5)
        open_menu()
        tap(find(labels["maintenance_reset_settings"]))
        wait_for(lambda: not saved(), "native reset did not clear device overrides")
        server = read_app(FILES[1])
        assert (json.loads(server) if server else {}) == (json.loads(original[FILES[1]]) if original[FILES[1]] else {})
        passed("native reset clears device overrides with settings Lua disabled and preserves the Server layer")
        open_menu()
        tap(find(labels["maintenance_restore_plugins"]))
        wait_for(lambda: (d := settings_diagnostic()) is not None and not d["disabled"] and d["bytes"] > 0, "factory settings Lua did not recover")
        open_state = state()
        assert pid() == report["uiPid"] and "safeMode=false" in open_state
        go_home()
        shell("input", "swipe", 1270, 380, 1270, 380, 900)
        find(labels["setting_system_name"])
        shot("settings-restored")
        passed("factory restore makes generated settings usable again without restarting the process")
        report["complete"] = True
    except Exception as error:
        report["failure"] = str(error)
        try: shot("failed-screen")
        except RuntimeError: pass
        raise
    finally:
        try:
            (args.output / "logcat.txt").write_bytes(adb("logcat", "-d", "-s", "ThinkOS:V", "AndroidRuntime:E"))
            shell("cmd", "package", "set-home-activity", LAUNCHER)
            shell("am", "force-stop", PACKAGE)
            for path, value in original.items(): write_app(path, value)
            shell("run-as", PACKAGE, "rm", "-f", "files/native-flight.bin")
            shell("cmd", "package", "set-home-activity", HOME)
            shell("am", "start", "-n", HOME)
            wait_for(lambda: (d := settings_diagnostic()) is not None and not d["disabled"] and d["bytes"] > 0, "original settings runtime did not return")
            report["settingsRestored"] = all(read_app(path) == original[path] for path in FILES[:2])
            history = read_app(FILES[2])
            report["failureHistoryRestored"] = (json.loads(history) if history else {}) == (json.loads(original[FILES[2]]) if original[FILES[2]] else {})
            if report["settingsRestored"] and report["failureHistoryRestored"]:
                shutil.rmtree(backup)
                report["privateBackupDeleted"] = True
            else:
                report["privateBackupDirectory"] = str(backup)
        except Exception as error:
            report["restoreFailure"] = str(error)
            report["privateBackupDirectory"] = str(backup)
        report["complete"] = report["complete"] and report.get("settingsRestored", False) and report.get("failureHistoryRestored", False)
        (args.output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n")
        assert report.get("settingsRestored") and report.get("failureHistoryRestored"), report
    assert report["complete"], report


if __name__ == "__main__":
    main()
