#!/usr/bin/env python3
"""Exercise the native timezone picker using a declaration-only plugin; restore settings bytes."""
import argparse
from collections import Counter
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from device_app_files import read_app_file
from device_hierarchy import dump_hierarchy, visible_nodes
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin

PACKAGE = "moe.kiwi.thinkos"
PROBE = "timezone-ui-probe"
HOME = PACKAGE + "/.MainActivity"
LAUNCHER = "org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher"
FILES = ("files/settings/local.json", "files/settings/server.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="HUA0BWUT")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/ui-validation/timezones")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ["adb", "-s", args.serial]
    report = {"complete": False, "passed": [], "issues": [], "chips": {}}

    def adb(*values, data=None):
        run = subprocess.run(base + list(values), input=data, capture_output=True, timeout=25, check=True)
        return run.stdout

    def shell(*values, **kwargs):
        return adb("shell", "-T", shlex.join(map(str, values)), **kwargs)

    def debug(command, value=None, kind="--es"):
        values = ["am", "broadcast", "-n", PACKAGE + "/.DebugReceiver", "--es", "command", command]
        if value is not None: values += [kind, "value", str(value)]
        shell(*values)

    def pid():
        return shell("pidof", PACKAGE).decode().strip()

    def state():
        debug("state")
        return adb("logcat", "-d", "--pid=" + pid(), "-s", "ThinkOS:V").decode().split("DEBUG state=")[-1]

    def saved():
        raw = read_app_file(args.serial, FILES[0])
        return json.loads(raw) if raw else {}

    def keyboard_shown():
        match = re.search(r"\bmInputShown=(true|false)", shell("dumpsys", "input_method").decode())
        assert match, "IME visibility unavailable"
        return match[1] == "true"

    def wait_for(test, message, seconds=8):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if test(): return
            time.sleep(.2)
        raise AssertionError(message)

    def snapshot(name="last-ui", compressed=False):
        path = "/sdcard/thinkos-timezone-ui.xml"
        try:
            dump_hierarchy(args.serial, path, compressed=compressed)
            raw = shell("cat", path)
            (args.output / (name + ".xml")).write_bytes(raw)
            return ET.fromstring(raw)
        finally:
            shell("rm", "-f", path)

    def find(text=None, contains=None, description=None, class_name=None):
        for _ in range(3):
            node = next((n for n in visible_nodes(snapshot(compressed=True)) if
                         (text is None or n.get("text") == text) and
                         (contains is None or contains in n.get("text", "")) and
                         (description is None or n.get("content-desc") == description) and
                         (class_name is None or n.get("class") == class_name)), None)
            if node is not None: return node
        raise AssertionError("Missing native timezone UI: " + str(text or contains or description or class_name))

    def box(node):
        return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))

    def tap(node):
        a, b, c, d = box(node)
        x = (a+c)//2
        # The ROM's reverse-landscape nav strip can cover the first 78px during
        # automation. Use the still-visible inside of the target, not that strip.
        if x < 100: x = a + (c-a)*4//5
        shell("input", "tap", x, (b+d)//2)
        time.sleep(.2)

    def shot(name):
        raw = adb("exec-out", "screencap", "-p")
        (args.output / (name + ".png")).write_bytes(raw)
        return Image.open(io.BytesIO(raw)).convert("RGB")

    def check(ok, description):
        report["passed" if ok else "issues"].append(description)
        print(("PASS " if ok else "ISSUE ") + description, flush=True)

    def navigate():
        shell("input", "keyevent", 3)
        debug("orientation", 0, "--ei")
        for _ in range(6):
            if "page=" + PROBE + ".main," in state(): return
            shell("input", "swipe", 1100, 400, 150, 400, 450)
            time.sleep(.3)
        raise AssertionError("Timezone probe page is not reachable")

    def open_required():
        tap(find(text="必选时区"))
        find(text="亚洲")

    def set_theme(theme):
        # This test targets the native picker. Global long-press routing and
        # persistent theme controls are covered by NAV and system_settings_ui.
        shell("input", "keyevent", 4)
        shell("input", "keyevent", 4)
        debug("orientation", 0, "--ei")
        debug("theme", theme)
        debug("settings", PROBE)
        find(text="时区UI样例")
        open_required()
        wait_for(lambda: "theme=" + theme in state(), "selected theme did not take effect")

    def replace_search(value):
        node = find(class_name="android.widget.EditText")
        tap(node)
        shell("input", "keyevent", 123)
        if node.get("text"): shell("input", "keyevent", *([67] * len(node.get("text"))))
        shell("input", "text", value)

    def contrast(a, b):
        def luminance(rgb):
            linear = [v/255/12.92 if v/255 <= .04045 else ((v/255+.055)/1.055)**2.4 for v in rgb]
            return sum(x*y for x, y in zip(linear, (.2126, .7152, .0722)))
        x, y = sorted((luminance(a), luminance(b)))
        return (y+.05)/(x+.05)

    def check_appearance(name):
        tree = snapshot(name)
        chip = next(n for n in tree.iter("node") if n.get("text") == "亚洲")
        a, b, c, d = box(chip)
        image = shot(name)
        # Sample inside the pill, excluding its border and rounded corners. The two most common
        # exact colors are its flat background and solid glyph interiors, rather than edge blends.
        raw = image.crop((a+12, b+12, c-12, d-12)).tobytes()
        colors = Counter(zip(raw[0::3], raw[1::3], raw[2::3])).most_common(2)
        ratio = contrast(colors[0][0], colors[1][0])
        report["chips"][name] = {"size_px": [c-a, d-b], "selected": chip.get("selected"),
                                  "sampled_colors": [list(x[0]) for x in colors], "contrast": round(ratio, 2),
                                  "actual_theme": re.search(r"theme=(\w+)", state())[1]}
        assert report["chips"][name]["actual_theme"].lower() == name.split("-")[-1]
        check(min(c-a, d-b) >= 78 and ratio >= 4.5 and chip.get("selected") == "true",
              name + ": selected continent has a 48dp target, 4.5:1 text contrast and selected accessibility state")

    assert pid() and not shell("run-as", PACKAGE, "ls", "files/dev-plugins").strip()
    assert PACKAGE in shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").decode()
    original = {path: read_app_file(args.serial, path) for path in FILES}
    assert PROBE not in saved(), "Do not overwrite an existing probe settings scope"
    for value in original.values():
        if value: json.loads(value)
    backup = Path(tempfile.mkdtemp(prefix="thinkos-timezone-backup-"))
    for path, value in original.items():
        if value is not None:
            with (backup / Path(path).name).open("wb") as stream:
                os.chmod(stream.name, 0o600)
                stream.write(value); stream.flush(); os.fsync(stream.fileno())
    installed = shell("pm", "path", PACKAGE).decode().strip().removeprefix("package:")
    report["apk_sha256"] = shell("sha256sum", installed).decode().split()[0]
    report["uiPid"] = pid()
    try:
        with tempfile.TemporaryDirectory(prefix="thinkos-timezone-ui-") as temporary:
            directory, _ = package_plugin(ROOT / "tools/tests/fixtures" / PROBE, temporary)
            publish_directory(directory, args.serial)
        debug("hold_attention", "AWAKE")
        debug("lux", 20, "--ef")
        set_theme("DARK")
        check(not any(n.get("text") == "无" for n in snapshot().iter("node")), "a required timezone does not offer an empty selection")
        check(not any(n.get("text") == "时区UI样例" for n in snapshot(compressed=True).iter("node")),
              "the picker hides the covered form from accessibility navigation")
        for name, orientation, theme in (("landscape-dark", 0, "DARK"), ("portrait-dark", 1, "DARK"),
                                         ("portrait-light", 1, "LIGHT"), ("landscape-light", 0, "LIGHT")):
            if "theme=" + theme not in state(): set_theme(theme)
            debug("orientation", orientation, "--ei")
            time.sleep(2.3)
            check_appearance(name)

        tap(find(text="欧洲"))
        tree = snapshot("europe-filter")
        row_texts = [n.get("text", "") for n in tree.iter("node")]
        check(any("Europe/" in t for t in row_texts) and not any("Asia/" in t for t in row_texts),
              "continent filtering reuses the native list and excludes other continents")
        tap(find(text="亚洲"))
        replace_search("x")
        began = time.monotonic()
        while time.monotonic()-began < 37:
            time.sleep(3)
            if "page=settings.form," not in state(): break
            shell("input", "text", "x")
        report["activeTypingSeconds"] = round(time.monotonic()-began, 2)
        stayed = "page=settings.form," in state()
        shot("active-search")
        check(stayed and report["activeTypingSeconds"] >= 37,
              "typing in timezone search keeps settings open beyond its 30-second idle timeout")
        if not stayed:
            navigate()
            shell("input", "swipe", 600, 380, 600, 380, 900)
            find(text="时区UI样例")
            open_required()
        replace_search("Kathmandu")
        row = find(contains="Asia/Kathmandu")
        tree = snapshot("kathmandu-search")
        check(any(re.search(r"UTC\+0?5:45", n.get("text", "")) for n in tree.iter("node")),
              "search matches the IANA name and shows Kathmandu's fractional UTC offset")
        shot("kathmandu-search")
        tap(row)
        find(text="时区UI样例")
        wait_for(lambda: saved().get(PROBE, {}).get("required") == "Asia/Kathmandu", "picked timezone was not saved")
        snapshot("picked-timezone")
        deadline = time.monotonic()+3
        while keyboard_shown() and time.monotonic() < deadline: time.sleep(.2)
        report["keyboardClosedAfterPick"] = not keyboard_shown()
        shot("picked-timezone")
        check(report["keyboardClosedAfterPick"],
              "selecting a search result returns to the form and dismisses the keyboard")
        open_required()
        time.sleep(3.5)  # Let the transient immersive navigation strip hide after dismissing the IME.
        tap(find(description="必选时区", text="‹"))
        find(text="时区UI样例")
        check(saved().get(PROBE, {}).get("required") == "Asia/Kathmandu", "picker back returns without changing the selected timezone")
        tap(find(text="可空时区"))
        tap(find(text="亚洲"))
        tap(find(text="无"))
        wait_for(lambda: saved().get(PROBE, {}).get("optional") == "", "optional timezone did not clear")
        tap(find(text="可空时区"))
        tree = snapshot("optional-empty")
        shot("optional-empty")
        check(any(n.get("text") == "✓" for n in tree.iter("node")), "an optional timezone can be cleared and reopens with its empty row selected")
        check(pid() == report["uiPid"], "picker interactions do not restart the application")
        report["complete"] = not report["issues"]
    except Exception as error:
        report["failure"] = str(error)
        try: shot("failed-screen")
        except subprocess.SubprocessError: pass
        raise
    finally:
        try:
            (args.output / "logcat.txt").write_bytes(adb("logcat", "-d", "--pid=" + report["uiPid"], "-s", "ThinkOS:V", "AndroidRuntime:E"))
            shell("cmd", "package", "set-home-activity", LAUNCHER)
            shell("am", "force-stop", PACKAGE)
            remove_plugin(PROBE, args.serial)
            for path, value in original.items():
                if value is None: shell("run-as", PACKAGE, "rm", "-f", path, path + ".bak")
                else:
                    shell("run-as", PACKAGE, "sh", "-c", "cat > " + shlex.quote(path + ".ui-restore"), data=value)
                    shell("run-as", PACKAGE, "mv", path + ".ui-restore", path)
                    shell("run-as", PACKAGE, "rm", "-f", path + ".bak")
            shell("cmd", "package", "set-home-activity", HOME)
            shell("am", "start", "-n", HOME)
            wait_for(lambda: "id=settings" in state() and "safeMode=false" in state(), "factory UI did not recover")
            report["settingsRestored"] = all(read_app_file(args.serial, path) == value for path, value in original.items())
            report["developmentOverridesEmpty"] = not shell("run-as", PACKAGE, "ls", "files/dev-plugins").strip()
            if report["settingsRestored"] and report["developmentOverridesEmpty"]:
                shutil.rmtree(backup)
                report["privateBackupDeleted"] = True
            else: report["privateBackupDirectory"] = str(backup)
        except Exception as error:
            report["restoreFailure"] = str(error)
            report["privateBackupDirectory"] = str(backup)
        report["complete"] = report["complete"] and report.get("settingsRestored", False) and report.get("developmentOverridesEmpty", False)
        (args.output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n")
        assert report.get("settingsRestored") and report.get("developmentOverridesEmpty"), report
    assert report["complete"], report["issues"]


if __name__ == "__main__":
    main()
