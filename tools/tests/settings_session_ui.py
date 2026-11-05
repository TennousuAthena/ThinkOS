#!/usr/bin/env python3
"""Check settings attention, idle exit and dialog activity without saving any edits."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from device_hierarchy import dump_hierarchy, visible_nodes
from device_recovery import capture, restore
from media_device import Device
from ui_walkthrough import broadcast_json
PACKAGE = "moe.kiwi.thinkos"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="HUA0BWUT")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/ui-validation/settings-session")
    parser.add_argument("--editor-only", action="store_true", help="Check only native dialog activity, using the row edge to scroll")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ["adb", "-s", args.serial]
    report = {"mode": "editor" if args.editor_only else "full", "complete": False, "passed": [], "issues": []}

    def adb(*values, check=True, data=None):
        run = subprocess.run(base + list(values), input=data, capture_output=True, timeout=25)
        if check and run.returncode:
            raise RuntimeError(run.stderr.decode() + run.stdout.decode())
        return run.stdout

    def shell(*values, **kwargs):
        return adb("shell", shlex.join(map(str, values)), **kwargs)

    def debug(command, value=None, kind="--es"):
        values = ["am", "broadcast", "-n", PACKAGE + "/.DebugReceiver", "--es", "command", command]
        if value is not None:
            values += [kind, "value", str(value)]
        shell(*values)

    shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    pid = shell("pidof", PACKAGE).decode().strip()
    assert pid

    def state():
        raw = shell("am", "broadcast", "-n", PACKAGE + "/.DebugReceiver", "--es", "command", "benchmark_state").decode()
        match = re.search(r'data="([^"]*)"', raw)
        assert match, "No benchmark state: " + raw
        values = match.group(1).split('|')
        return dict(zip(("page", "attention", "theme", "orientation"), values[:4]))

    def tree():
        path = "/sdcard/thinkos-settings-session.xml"
        try:
            dump_hierarchy(args.serial, path)
            data = shell("cat", path, check=False)
            if not data:
                raise AssertionError("Native UI tree is not ready")
            (args.output / "last-ui.xml").write_bytes(data)
            return ET.fromstring(data)
        finally:
            shell("rm", "-f", path)

    def find(text=None, class_name=None, scroll=False):
        for _ in range(9 if scroll else 1):
            found = next((n for n in visible_nodes(tree())
                          if (text is None or n.get("text") == text)
                          and (class_name is None or n.get("class") == class_name)), None)
            if found is not None:
                return found
            if scroll:
                x = 210 if args.editor_only else 650
                shell("input", "swipe", x, 685, x, 210, 450)
        raise AssertionError("Missing UI element: " + str(text or class_name))

    def tap(node):
        a, b, c, d = map(int, re.findall(r"\d+", node.get("bounds")))
        shell("input", "tap", (a+c)//2, (b+d)//2)

    def shot(name):
        (args.output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))

    def check(condition, description):
        report["passed" if condition else "issues"].append(description)
        print(("PASS " if condition else "ISSUE ") + description, flush=True)

    def wait_page(page, seconds=6):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if state()["page"] == page:
                return
            time.sleep(.2)
        raise AssertionError("Page did not become " + page)

    def open_settings():
        shell("input", "keyevent", "KEYCODE_HOME")
        wait_page("home.main")
        # The home page's outer gutter belongs to home, which has no settings of its own.
        shell("input", "swipe", 1270, 380, 1270, 380, 900)
        touched = time.monotonic()
        wait_page("settings.form")
        find(text="显示")
        return touched

    def open_editor():
        open_settings()
        # Exercise a real vertical drag across a number row before leaving its category.
        tap(find(text="显示"))
        label = find(text="最大亮度", scroll=True)
        a,b,c,d = map(int, re.findall(r'-?\d+', label.get('bounds')))
        shell("input", "swipe", (a+c)//2, (b+d)//2, (a+c)//2, min(730, (b+d)//2+180), 450)
        if not any(n.get('selected') == 'true' and n.get('text') == '显示' for n in tree().iter('node')):
            shell("input", "keyevent", "KEYCODE_BACK")
        tap(find(text="插件"))
        player = json.loads((ROOT / "plugins/player/i18n/zh-Hans.json").read_text())
        tap(find(text=player["name"], scroll=True))
        tap(find(text=player["setting.playlist_url"], scroll=True))
        find(class_name="android.widget.EditText")

    def horizontal_slider():
        tap(find(text="显示"))
        label_text = "最大亮度"
        label = find(text=label_text)
        tap(label)
        _, top, _, bottom = map(int, re.findall(r"\d+", label.get("bounds")))
        controls = []
        for n in tree().iter("node"):
            if n.get("text") in ("−", "+"):
                a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
                if b >= bottom and b-bottom < 150:
                    controls.append((n.get("text"), (a, b, c, d)))
        minus = next(bounds for text, bounds in controls if text == "−")
        plus = next(bounds for text, bounds in controls if text == "+")
        schema = json.loads((ROOT / "shared/schema/settings/system.json").read_text())["fields"]
        key = "display.brightness_max"
        field = schema[key]
        original = before_values.get("system", {}).get(key, field["default"])
        target = original + field["step"] if original + field["step"] <= field["max"] else original - field["step"]
        assert field["min"] <= target <= field["max"]
        inset = (minus[2]-minus[0]) / 48 * 14
        left, right = minus[2]+inset, plus[0]-inset

        def drag(value):
            current = adb("exec-out", "run-as", PACKAGE, "cat", "files/settings/local.json", check=False)
            saved = (json.loads(current) if current else {}).get("system", {}).get(key, field["default"])
            x1 = left + (right-left)*(saved-field["min"])/(field["max"]-field["min"])
            x2 = left + (right-left)*(value-field["min"])/(field["max"]-field["min"])
            y = (minus[1]+minus[3])//2
            shell("input", "swipe", round(x1), y, round(x2), y, 800)
            time.sleep(.4)

        drag(target)
        stored = json.loads(adb("exec-out", "run-as", PACKAGE, "cat", "files/settings/local.json"))
        check(stored.get("system", {}).get(key) == target,
              "a horizontal slider gesture commits the chosen step on release")
        drag(original)
        restored = json.loads(adb("exec-out", "run-as", PACKAGE, "cat", "files/settings/local.json"))
        assert restored == before_values

    before = adb("exec-out", "run-as", PACKAGE, "cat", "files/settings/local.json", check=False)
    before_values = json.loads(before) if before else {}
    assert not shell("run-as", PACKAGE, "ls", "files/dev-plugins").strip()
    device = Device(args.serial)
    assert PACKAGE+'/' in device.shell('dumpsys', 'activity', 'activities').split('mResumedActivity')[-1]
    journal = capture(device, broadcast_json(device, 'ui_state'))
    print('Private recovery journal: '+str(journal), flush=True)
    try:
        shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        pid = shell("pidof", PACKAGE).decode().strip()
        shell("input", "keyevent", 3)
        debug("orientation", 0, "--ei")
        debug("lux", 20, "--ef")
        debug("theme", "DARK")
        debug("hold_attention", "ENGAGED")
        debug("presence", "false", "--ez")
        debug("page", "home.main")
        # The fixture must start with a fresh real touch: merely dropping an old
        # attention hold can immediately return to the sleep clock.
        shell("input", "tap", 1270, 380)
        debug("hold_attention")
        wait_page("home.main")

        if not args.editor_only:
            open_settings()
            # Longer than the default engaged idle interval, shorter than the settings idle exit.
            time.sleep(18)
            observed = state()
            report["quietSettingsState"] = observed
            shot("settings-after-18-seconds")
            check(observed["page"] == "settings.form" and observed["attention"] == "ENGAGED",
                  "an open settings page stays ENGAGED past the normal interaction timeout")
            shell("input", "keyevent", 3)
            wait_page("home.main")
            check(True, "Home exits settings and returns to the previous page")

            open_settings()
            horizontal_slider()
            shell("input", "keyevent", 3)
            wait_page("home.main")

            touched = open_settings()
            while state()["page"] == "settings.form" and time.monotonic()-touched < 34:
                time.sleep(.5)
            elapsed = time.monotonic()-touched
            report["idleExitSeconds"] = round(elapsed, 2)
            check(state()["page"] == "home.main" and 28 <= elapsed <= 33,
                  "settings closes after 30 seconds without interaction")

        open_editor()
        current = adb("exec-out", "run-as", PACKAGE, "cat", "files/settings/local.json", check=False)
        check((json.loads(current) if current else {}) == before_values,
              "vertical scrolling across numeric rows does not change their values")
        typed_at = time.monotonic()
        shell("input", "text", "UI_SESSION_DEMO")
        for index in range(3):
            time.sleep(12)
            if state()["page"] != "settings.form":
                break
            shell("input", "text", str(index))
        elapsed = time.monotonic()-typed_at
        observed = state()
        report["typingSeconds"] = round(elapsed, 2)
        report["typingState"] = observed
        editor = next((n for n in tree().iter("node") if n.get("class") == "android.widget.EditText"), None)
        shot("active-text-editor")
        check(elapsed >= 36 and observed["page"] == "settings.form" and editor is not None,
              "typing in a native dialog keeps settings open for more than 30 seconds")
        if editor is not None:
            tap(find(text="取消"))
            open_editor()
            shell("input", "text", "CANCEL_ON_HOME")
            shell("input", "keyevent", 3)
            wait_page("home.main")
            # Detaching the settings form also dismisses its native text dialog.
            time.sleep(.5)
            check(not any(n.get("class") == "android.widget.EditText" for n in tree().iter("node")),
                  "Home closes a text dialog without saving its draft")
        check(pid == shell("pidof", PACKAGE).decode().strip(), "UI session checks do not restart the application")
        report["complete"] = not report["issues"]
    except Exception as error:
        report["failure"] = str(error)
        shot("failed-screen")
        raise
    finally:
        shell("input", "keyevent", 3)
        debug("hold_attention")
        debug("lux")
        debug("theme")
        debug("orientation", 10, "--ei")
        after = adb("exec-out", "run-as", PACKAGE, "cat", "files/settings/local.json", check=False)
        after_values = json.loads(after) if after else {}
        report["settingsUnchanged"] = after_values == before_values
        (args.output / "logcat.txt").write_bytes(adb("logcat", "-d", "--pid=" + pid, "-s", "ThinkOS:V", "AndroidRuntime:E"))
        try:
            report.update(restore(journal, device))
            raw = adb("exec-out", "run-as", PACKAGE, "cat", "files/settings/local.json", check=False)
            report["settingsRestored"] = (json.loads(raw) if raw else {}) == before_values
        except Exception as error:
            report['recovery_error'] = str(error)
            report['private_recovery_journal'] = str(journal)
        (args.output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n")
        assert report["settingsUnchanged"]
        assert not report.get('recovery_error'), report.get('recovery_error')
    assert report["complete"], report["issues"]


if __name__ == "__main__":
    main()
