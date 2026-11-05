#!/usr/bin/env python3
"""Measure real native dialog buttons and exercise their actions; restore settings bytes."""
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
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin

PACKAGE = "moe.kiwi.thinkos"
PROBE = "dialog-ui-probe"
HOME = PACKAGE + "/.MainActivity"
LAUNCHER = "org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher"
FILES = ("files/settings/local.json", "files/settings/server.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="HUA0BWUT")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/ui-validation/native-dialogs")
    parser.add_argument("--editors-only", action="store_true", help="Skip the debug maintenance entry (for pre-fix APKs)")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ["adb", "-s", args.serial]
    labels = json.loads((ROOT / "shared/i18n/system/zh-Hans.json").read_text())
    fields = json.loads((ROOT / "shared/schema/settings/system.json").read_text())["fields"]
    order = {labels[f["label"][1:].replace(".", "_")]: i for i, f in enumerate(fields.values())}
    report = {"complete": False, "passed": [], "issues": [], "dialogs": {}, "mode": "editors" if args.editors_only else "full"}

    def adb(*values, data=None):
        return subprocess.run(base + list(values), input=data, capture_output=True, timeout=25, check=True).stdout

    def shell(*values, **kwargs): return adb("shell", "-T", shlex.join(map(str, values)), **kwargs)

    def debug(command, value=None, kind="--es"):
        values = ["am", "broadcast", "-n", PACKAGE + "/.DebugReceiver", "--es", "command", command]
        if value is not None: values += [kind, "value", str(value)]
        shell(*values)

    def pid(): return shell("pidof", PACKAGE).decode().strip()

    def state():
        debug("state")
        return adb("logcat", "-d", "--pid=" + pid(), "-s", "ThinkOS:V").decode().split("DEBUG state=")[-1]

    def saved():
        raw = read_app_file(args.serial, FILES[0])
        return json.loads(raw) if raw else {}

    def wait_for(test, message, seconds=8):
        end = time.monotonic()+seconds
        while time.monotonic() < end:
            if test(): return
            time.sleep(.2)
        raise AssertionError(message)

    def snapshot(name="last-ui"):
        path = "/sdcard/thinkos-native-dialog-ui.xml"
        try:
            shell("uiautomator", "dump", "--compressed", path)
            raw = shell("cat", path)
            (args.output / (name + ".xml")).write_bytes(raw)
            return ET.fromstring(raw)
        finally: shell("rm", "-f", path)

    def box(node): return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))

    def find(text=None, resource=None, class_name=None, scroll_to=False):
        for _ in range(10 if scroll_to else 3):
            tree = snapshot()
            node = next((n for n in tree.iter("node") if (text is None or n.get("text") == text)
                         and (resource is None or n.get("resource-id") == resource)
                         and (class_name is None or n.get("class") == class_name)), None)
            if node is not None: return node
            if scroll_to:
                view = next(n for n in tree.iter("node") if n.get("class") == "android.widget.ScrollView")
                a, b, c, d = box(view)
                visible = [order[n.get("text")] for n in tree.iter("node") if n.get("text") in order]
                up = visible and order.get(text, len(fields)) < min(visible)
                y1, y2 = b+(d-b)*.8, b+(d-b)*.25
                if up: y1, y2 = y2, y1
                shell("input", "swipe", (a+c)//2, round(y1), (a+c)//2, round(y2), 400)
        raise AssertionError("Missing native dialog UI: " + str(text or resource or class_name))

    def tap(node):
        a, b, c, d = box(node)
        shell("input", "tap", (a+c)//2, (b+d)//2)
        time.sleep(.25)

    def hide_keyboard():
        def shown():
            match = re.search(r"\bmInputShown=(true|false)", shell("dumpsys", "input_method").decode())
            assert match, "IME visibility unavailable"
            return match[1] == "true"
        if shown():
            shell("input", "keyevent", 4)
            wait_for(lambda: not shown(), "keyboard did not close")
            time.sleep(.3)

    def check(ok, message):
        report["passed" if ok else "issues"].append(message)
        print(("PASS " if ok else "ISSUE ") + message, flush=True)

    def home():
        shell("input", "keyevent", 3)
        debug("orientation", 0, "--ei")
        wait_for(lambda: "orientation=LANDSCAPE" in state(), "landscape is not ready")
        for _ in range(6):
            if "page=home.main," in state(): return
            shell("input", "swipe", 1100, 400, 150, 400, 450)
            time.sleep(.3)
        raise AssertionError("Home is not reachable")

    def open_system():
        home()
        tree = snapshot()
        root = next(n for n in tree.iter("node") if n.get("package") == PACKAGE)
        width = box(root)[2]
        leaves = [box(n) for n in tree.iter("node") if n.get("package") == PACKAGE and not len(n)]
        left = [r for r in leaves if (r[0]+r[2])/2 < width/2]
        right = [r for r in leaves if (r[0]+r[2])/2 >= width/2]
        assert left and right, "Home stack content is not ready"
        x = (max(r[2] for r in left)+min(r[0] for r in right))//2
        y = (box(root)[1]+box(root)[3])//2
        shell("input", "swipe", x, y, x, y, 900)
        find(text=labels["setting_system_name"])

    def theme(value):
        open_system()
        if saved().get("system", {}).get("theme.night", True): tap(find(text=labels["setting_theme_night"]))
        wait_for(lambda: saved().get("system", {}).get("theme.night") is False, "night mode did not turn off")
        time.sleep(2.5)
        if saved().get("system", {}).get("theme.mode") != value.lower():
            tap(find(text=labels["setting_theme_mode"]))
            tap(find(text=labels["setting_theme_mode_" + value.lower()]))
        wait_for(lambda: "theme=" + value in state(), "fixed theme did not take effect")
        time.sleep(2.3)

    def probe_settings(orientation):
        home()
        for _ in range(6):
            if "page=" + PROBE + ".main," in state(): break
            shell("input", "swipe", 1100, 400, 150, 400, 450)
            time.sleep(.3)
        else: raise AssertionError("Dialog probe is not reachable")
        shell("input", "swipe", 600, 380, 600, 380, 900)
        find(text="原生弹窗样例")
        debug("orientation", orientation, "--ei")
        time.sleep(.5)

    def replace(node, value):
        tap(node)
        shell("input", "keyevent", 123)
        if node.get("text"): shell("input", "keyevent", *([67] * len(node.get("text"))))
        shell("input", "text", value)

    def capture(name, expected_buttons):
        hide_keyboard()
        tree = snapshot(name)
        hide_keyboard()
        raw = adb("exec-out", "screencap", "-p")
        (args.output / (name + ".png")).write_bytes(raw)
        image = Image.open(io.BytesIO(raw)).convert("RGB")
        def luminance(rgb):
            values = [v/255/12.92 if v/255 <= .04045 else ((v/255+.055)/1.055)**2.4 for v in rgb]
            return sum(x*y for x, y in zip(values, (.2126, .7152, .0722)))
        buttons = []
        for node in tree.iter("node"):
            if node.get("resource-id") in ("android:id/button1", "android:id/button2", "android:id/button3"):
                a, b, c, d = box(node)
                pixels = image.crop((a, b, c, d)).tobytes()
                colors = Counter(zip(pixels[0::3], pixels[1::3], pixels[2::3])).most_common(2)
                assert len(colors) == 2, "Button glyphs are not visible in the sampled bounds"
                low, high = sorted(luminance(x[0]) for x in colors)
                buttons.append({"resource": node.get("resource-id"), "bounds": [a, b, c, d],
                                "sampled_colors": [list(x[0]) for x in colors], "contrast": round((high+.05)/(low+.05), 2)})
        report["dialogs"][name] = buttons
        check(len(buttons) == expected_buttons and all(x["contrast"] >= 4.5 and min(x["bounds"][2]-x["bounds"][0], x["bounds"][3]-x["bounds"][1]) >= 78 for x in buttons),
              name + ": all native dialog buttons meet 4.5:1 contrast and 48dp targets")
        return tree

    assert pid() and not shell("run-as", PACKAGE, "ls", "files/dev-plugins").strip()
    assert PACKAGE in shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").decode()
    original = {p: read_app_file(args.serial, p) for p in FILES}
    for raw in original.values():
        if raw: json.loads(raw)
    assert PROBE not in saved(), "Do not overwrite an existing probe scope"
    installed = shell("pm", "path", PACKAGE).decode().strip().removeprefix("package:")
    report["apk_sha256"] = shell("sha256sum", installed).decode().split()[0]
    report["uiPid"] = pid()
    backup = Path(tempfile.mkdtemp(prefix="thinkos-dialog-backup-"))
    for path, value in original.items():
        if value is not None:
            with (backup / Path(path).name).open("wb") as stream:
                os.chmod(stream.name, 0o600)
                stream.write(value); stream.flush(); os.fsync(stream.fileno())
    try:
        with tempfile.TemporaryDirectory(prefix="thinkos-dialog-ui-") as temporary:
            directory, _ = package_plugin(ROOT / "tools/tests/fixtures" / PROBE, temporary)
            publish_directory(directory, args.serial)
        debug("hold_attention", "AWAKE"); debug("lux", 20, "--ef")
        for mode, orientation in (("DARK", 0), ("LIGHT", 1)):
            theme(mode)
            probe_settings(orientation)
            tap(find(text="普通文字"))
            value = "UI-" + mode
            replace(find(class_name="android.widget.EditText"), value)
            capture("text-" + mode.lower(), 2)
            tap(find(resource="android:id/button1"))
            wait_for(lambda: saved().get(PROBE, {}).get("plain") == value, "plain edit did not persist")
            check(True, mode + ": confirm saves the probe's plain text")
            tap(find(text="密码文字"))
            secret = find(class_name="android.widget.EditText")
            assert secret.get("password") == "true"
            replace(secret, "UI-DEMO-SECRET")
            tree = capture("password-" + mode.lower(), 2)
            check(not any("UI-DEMO-SECRET" in n.get("text", "") or "UI-DEMO-SECRET" in n.get("content-desc", "") for n in tree.iter("node")),
                  mode + ": the password editor keeps its synthetic draft masked")
            tap(find(resource="android:id/button2")); find(text="原生弹窗样例")
            check("secret" not in saved().get(PROBE, {}), mode + ": cancel discards the password draft")
            before_place = {k: v for k, v in saved().get("system", {}).items() if k.startswith("location.")}
            open_system()
            tap(find(text=labels["setting_location_name"], scroll_to=True))
            tap(find(text=labels["setting_location_mode_manual"]))
            debug("orientation", orientation, "--ei"); time.sleep(.5)
            capture("place-" + mode.lower(), 2)
            tap(find(resource="android:id/button2")); find(text=labels["setting_location_mode_manual"])
            check(before_place == {k: v for k, v in saved().get("system", {}).items() if k.startswith("location.")},
                  mode + ": cancelling coordinate entry preserves the configured location")
            if not args.editors_only:
                home(); debug("orientation", orientation, "--ei"); time.sleep(.5)
                debug("maintenance")
                find(text=labels["maintenance_title"])
                capture("maintenance-" + mode.lower(), 1)
                tap(find(text=labels["maintenance_diagnostics"]))
                find(text=labels["maintenance_diag_title"])
                capture("diagnostics-" + mode.lower(), 1)
                tap(find(text=labels["maintenance_back"]))
                debug("maintenance"); find(text=labels["maintenance_title"])
                tap(find(text=labels["maintenance_back"]))
                check(not any(n.get("text") in (labels["maintenance_title"], labels["maintenance_diag_title"]) for n in snapshot().iter("node")),
                      mode + ": diagnostics and maintenance return buttons close their dialogs")
        check(pid() == report["uiPid"], "native dialog checks do not restart the application")
        report["complete"] = not report["issues"]
    except Exception as error:
        report["failure"] = str(error)
        try: (args.output / "failed-screen.png").write_bytes(adb("exec-out", "screencap", "-p"))
        except subprocess.SubprocessError: pass
        raise
    finally:
        try:
            (args.output / "logcat.txt").write_bytes(adb("logcat", "-d", "--pid=" + report["uiPid"], "-s", "ThinkOS:V", "AndroidRuntime:E"))
            shell("cmd", "package", "set-home-activity", LAUNCHER); shell("am", "force-stop", PACKAGE)
            remove_plugin(PROBE, args.serial)
            for path, value in original.items():
                if value is None: shell("run-as", PACKAGE, "rm", "-f", path, path + ".bak")
                else:
                    shell("run-as", PACKAGE, "sh", "-c", "cat > " + shlex.quote(path + ".ui-restore"), data=value)
                    shell("run-as", PACKAGE, "mv", path + ".ui-restore", path)
                    shell("run-as", PACKAGE, "rm", "-f", path + ".bak")
            shell("cmd", "package", "set-home-activity", HOME); shell("am", "start", "-n", HOME)
            wait_for(lambda: "id=settings" in state() and "safeMode=false" in state(), "factory UI did not recover")
            report["settingsRestored"] = all(read_app_file(args.serial, path) == value for path, value in original.items())
            report["developmentOverridesEmpty"] = not shell("run-as", PACKAGE, "ls", "files/dev-plugins").strip()
            if report["settingsRestored"] and report["developmentOverridesEmpty"]:
                shutil.rmtree(backup); report["privateBackupDeleted"] = True
            else: report["privateBackupDirectory"] = str(backup)
        except Exception as error:
            report["restoreFailure"] = str(error); report["privateBackupDirectory"] = str(backup)
        report["complete"] = report["complete"] and report.get("settingsRestored", False) and report.get("developmentOverridesEmpty", False)
        (args.output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n")
        assert report.get("settingsRestored") and report.get("developmentOverridesEmpty"), report
    assert report["complete"], report["issues"]


if __name__ == "__main__": main()
