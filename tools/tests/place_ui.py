#!/usr/bin/env python3
"""Verify offline native place editing; restore exact settings bytes and the Wi-Fi state."""
import argparse
from collections import Counter
import io
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
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from device_app_files import read_app_file
from device_hierarchy import dump_hierarchy, visible_nodes

PACKAGE = "moe.kiwi.thinkos"
HOME = PACKAGE + "/.MainActivity"
LAUNCHER = "org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher"
FILES = ("files/settings/local.json", "files/settings/server.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="HUA0BWUT")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/ui-validation/places")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--baseline", action="store_true", help="Only reproduce the two input-session failures; do not save a location")
    mode.add_argument("--timezone-retention", action="store_true", help="Check blank and explicit timezone intent across reopening and saving")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    labels = json.loads((ROOT / "shared/i18n/system/zh-Hans.json").read_text())
    fields = json.loads((ROOT / "shared/schema/settings/system.json").read_text())["fields"]
    order = {labels[f["label"][1:].replace(".", "_")]: i for i, f in enumerate(fields.values())}
    report = {"complete": False, "passed": [], "issues": [], "dialogs": {},
              "scope": "timezone-retention UI" if args.timezone_retention else "input-session baseline" if args.baseline else "offline place UI"}
    base = ["adb", "-s", args.serial]

    def adb(*values, data=None):
        return subprocess.run(base + list(values), input=data, capture_output=True, timeout=25, check=True).stdout

    def shell(*values, **kwargs):
        return adb("shell", "-T", shlex.join(map(str, values)), **kwargs)

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

    def location(): return {k: v for k, v in saved().get("system", {}).items() if k.startswith("location.")}

    def wait_for(test, message, seconds=8):
        end = time.monotonic()+seconds
        while time.monotonic() < end:
            if test(): return
            time.sleep(.2)
        raise AssertionError(message)

    def snapshot(name="last-ui", compressed=False):
        path = "/sdcard/thinkos-place-ui.xml"
        try:
            dump_hierarchy(args.serial, path, compressed=compressed)
            raw = shell("cat", path)
            (args.output / (name + ".xml")).write_bytes(raw)
            return ET.fromstring(raw)
        finally: shell("rm", "-f", path)

    def box(node): return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))

    def scroll(tree, target):
        view = next(n for n in tree.iter("node") if n.get("class") == "android.widget.ScrollView")
        a, b, c, d = box(view)
        # The native dialog title repeats the location-name label. Only rows
        # inside this scroll view decide which way its content needs to move.
        visible = [order[n.get("text")] for n in view.iter("node") if n.get("text") in order
                   and len(box(n)) == 4 and box(n)[3] > box(n)[1]]
        up = visible and order.get(target, len(fields)) <= min(visible)
        y1, y2 = b+(d-b)*.8, b+(d-b)*.25
        if up: y1, y2 = y2, y1
        shell("input", "swipe", (a+c)//2, round(y1), (a+c)//2, round(y2), 400)

    def find(text=None, resource=None, class_name=None, scroll_to=False):
        for _ in range(10 if scroll_to else 3):
            tree = snapshot(compressed=True)
            node = next((n for n in visible_nodes(tree) if (text is None or n.get("text") == text)
                         and (resource is None or n.get("resource-id") == resource)
                         and (class_name is None or n.get("class") == class_name)), None)
            if node is not None: return node
            if scroll_to: scroll(tree, text)
        raise AssertionError("Missing native place UI: " + str(text or resource or class_name))

    def tap(node):
        a, b, c, d = box(node)
        shell("input", "tap", (a+c)//2, (b+d)//2)
        time.sleep(.25)

    def shot(name):
        raw = adb("exec-out", "screencap", "-p")
        (args.output / (name + ".png")).write_bytes(raw)
        return struct.unpack(">II", raw[16:24])

    def check(ok, text):
        report["passed" if ok else "issues"].append(text)
        print(("PASS " if ok else "ISSUE ") + text, flush=True)

    def hide_keyboard():
        def shown():
            match = re.search(r"\bmInputShown=(true|false)", shell("dumpsys", "input_method").decode())
            assert match, "IME visibility unavailable"
            return match[1] == "true"
        if shown():
            shell("input", "keyevent", 4)
            wait_for(lambda: not shown(), "keyboard did not close")
            time.sleep(.3)

    def open_system():
        shell("input", "keyevent", 4)
        shell("input", "keyevent", 4)
        debug("orientation", 0, "--ei")
        debug("page", "home.main")
        debug("settings", "system")
        tap(find(text=labels["setting_section_location"]))

    def set_theme(theme):
        debug("theme", theme)
        open_system()
        wait_for(lambda: "theme=" + theme in state(), "fixed theme did not take effect")
        time.sleep(2.3)

    def open_place():
        tap(find(text=labels["setting_location_name"], scroll_to=True))
        find(text=labels["setting_location_mode_manual"])

    def reopen_place():
        open_system()
        open_place()

    def manual():
        hide_keyboard()
        tap(find(text=labels["setting_location_mode_manual"]))
        find(resource="android:id/button1")

    def input_for(label):
        hide_keyboard()
        for _ in range(10):
            tree = snapshot()
            body = next(n for n in tree.iter('node') if n.get('class') == 'android.widget.ScrollView'
                        and any(c.get('class') == 'android.widget.EditText' for c in n.iter('node')))
            node = next((n for n in body.iter("node") if n.get("text") == label and box(n)[3] > box(n)[1]), None)
            if node is not None:
                bottom = box(node)[3]
                candidates = [n for n in body.iter("node") if n.get("class") == "android.widget.EditText"
                              and bottom <= box(n)[1] < bottom+100 and box(n)[3]-box(n)[1] >= 50]
                if candidates: return min(candidates, key=lambda n: box(n)[1])
            scroll(tree, label)
        raise AssertionError("Input not reachable: " + label)

    def replace(node, value):
        tap(node)
        shell("input", "keyevent", 123)
        if node.get("text"): shell("input", "keyevent", *([67] * len(node.get("text"))))
        if value: shell("input", "text", value)

    def edit(label, value): replace(input_for(label), value)

    def active_input(name, node):
        replace(node, "UI")
        began = time.monotonic()
        while time.monotonic()-began < 37:
            time.sleep(3)
            if "page=settings.form," not in state(): break
            shell("input", "text", "x")
        elapsed = round(time.monotonic()-began, 2)
        report[name + "Seconds"] = elapsed
        kept = "page=settings.form," in state()
        shot(name)
        check(kept and elapsed >= 37, name + ": continuous typing keeps settings open beyond 30 seconds")
        return kept

    def picker_layout(name):
        # A keyboard/back transition briefly reveals the ROM's immersive nav bar.
        # Measure the controls once it has hidden again, without an overlay clipping them.
        time.sleep(3.5)
        tree = snapshot(name)
        width, height = shot(name)
        viewport = box(tree.find('node'))
        targets = [n for n in tree.iter("node") if n.get("text") in ("‹", "⌕", labels["setting_location_mode_manual"])]
        inside = len(targets) == 3
        clipped = []
        for node in targets:
            a, b, c, d = box(node)
            inside &= 0 <= a < c <= width and 0 <= b < d <= height and d-b >= 78
            # API-27's legacy dumper clips right-hand controls at its app-display
            # rectangle even when the screenshot includes the immersive area.
            # Record that limitation; don't report a clipped width as a measurement
            # of the full native target (manual has a 64dp minimum in the View).
            if (c == viewport[2] and c < width) or (a == viewport[0] and a > 0):
                clipped.append({'text':node.get('text'),'bounds':node.get('bounds')})
            else: inside &= c-a >= 78
        report[name + 'ClippedTargets'] = clipped
        check(inside and any(n.get("text") == labels["form_place_attribution"] for n in tree.iter("node")),
              name + ": attribution and action targets are visible; unoccluded targets meet 48dp")

    def dialog_appearance(name):
        hide_keyboard()
        tree = snapshot(name)
        node = next(n for n in tree.iter("node") if n.get("text") == labels["setting_location_latitude"])
        a, b, c, d = box(node)
        hide_keyboard()
        raw = adb("exec-out", "screencap", "-p")
        (args.output / (name + ".png")).write_bytes(raw)
        image = Image.open(io.BytesIO(raw)).convert("RGB")
        def luminance(rgb):
            values = [v/255/12.92 if v/255 <= .04045 else ((v/255+.055)/1.055)**2.4 for v in rgb]
            return sum(x*y for x, y in zip(values, (.2126, .7152, .0722)))
        def sample(bounds):
            pixels = image.crop(bounds).tobytes()
            colors = Counter(zip(pixels[0::3], pixels[1::3], pixels[2::3])).most_common(2)
            low, high = sorted(luminance(x[0]) for x in colors)
            return colors, (high+.05)/(low+.05)
        colors, ratio = sample((a, b, c, d))
        report["dialogs"][name] = {"sampled_colors": [list(x[0]) for x in colors], "label_contrast": round(ratio, 2)}
        check(ratio >= 4.5, name + ": coordinate labels have at least 4.5:1 text contrast")
        buttons = []
        for node in tree.iter("node"):
            if node.get("resource-id") in ("android:id/button1", "android:id/button2"):
                colors, ratio = sample(box(node))
                buttons.append({"resource": node.get("resource-id"), "sampled_colors": [list(x[0]) for x in colors], "contrast": round(ratio, 2)})
        report["dialogs"][name]["buttons"] = buttons
        check(len(buttons) == 2 and all(b["contrast"] >= 4.5 for b in buttons),
              name + ": confirm and cancel text each meet 4.5:1 contrast")

    def timezone_retention():
        def blank():
            return input_for(labels["setting_location_timezone"]).get("text", "") in ("", labels["form_place_timezone_empty"])
        manual()
        check(blank(), "the automatic location editor offers device-timezone inheritance as a blank field")
        edit(labels["setting_location_name"], "UI-TIMEZONE-RETENTION")
        edit(labels["setting_location_latitude"], "27.717")
        edit(labels["setting_location_longitude"], "85.324")
        edit(labels["setting_location_elevation"], "1400")
        edit(labels["setting_location_timezone"], "")
        hide_keyboard(); tap(find(resource="android:id/button1"))
        wait_for(lambda: location().get("location.name") == "UI-TIMEZONE-RETENTION", "retention probe did not save")
        open_place(); manual()
        check(blank() and location().get("location.timezone", "") == "",
              "a manually saved blank timezone remains blank after reopening")
        snapshot("empty-timezone-reopened"); hide_keyboard(); shot("empty-timezone-reopened")
        before_save = location()
        hide_keyboard(); tap(find(resource="android:id/button1"))
        find(text=labels["setting_system_name"])
        time.sleep(3)
        check(location() == before_save, "saving an unchanged place does not pin the resolved device timezone")
        open_place(); manual()
        edit(labels["setting_location_timezone"], "Asia/Kathmandu")
        hide_keyboard(); tap(find(resource="android:id/button1"))
        wait_for(lambda: location().get("location.timezone") == "Asia/Kathmandu", "explicit timezone did not save")
        open_place(); manual()
        check(input_for(labels["setting_location_timezone"]).get("text") == "Asia/Kathmandu",
              "an explicit place timezone remains explicit after reopening")
        snapshot("explicit-timezone-reopened"); hide_keyboard(); shot("explicit-timezone-reopened")
        tap(find(resource="android:id/button2")); tap(find(text="‹"))
        tap(find(text=labels["setting_location_mode"], scroll_to=True))
        tap(find(text=labels["setting_location_mode_auto"]))
        open_place(); manual()
        check(blank(), "automatic location uses the device timezone despite an inactive manual timezone override")

    assert pid() and not shell("run-as", PACKAGE, "ls", "files/dev-plugins").strip()
    assert PACKAGE in shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").decode()
    original = {p: read_app_file(args.serial, p) for p in FILES}
    for raw in original.values():
        if raw: json.loads(raw)
    wifi = shell("settings", "get", "global", "wifi_on").decode().strip()
    timezone = shell("getprop", "persist.sys.timezone").decode().strip()
    installed = shell("pm", "path", PACKAGE).decode().strip().removeprefix("package:")
    report["apk_sha256"] = shell("sha256sum", installed).decode().split()[0]
    report["uiPid"] = pid()
    backup = Path(tempfile.mkdtemp(prefix="thinkos-place-backup-"))
    for path, value in original.items():
        if value is not None:
            with (backup / Path(path).name).open("wb") as stream:
                os.chmod(stream.name, 0o600)
                stream.write(value); stream.flush(); os.fsync(stream.fileno())
    try:
        debug("hold_attention", "AWAKE"); debug("lux", 20, "--ef")
        set_theme("DARK")
        shell("svc", "wifi", "disable")
        wait_for(lambda: shell("settings", "get", "global", "wifi_on").strip() == b"0", "Wi-Fi did not turn off")
        report["wifiDisabled"] = True
        if args.timezone_retention and saved().get("system", {}).get("location.mode", "auto") != "auto":
            tap(find(text=labels["setting_location_mode"], scroll_to=True))
            tap(find(text=labels["setting_location_mode_auto"]))
        open_place()
        if args.timezone_retention:
            timezone_retention()
            check(pid() == report["uiPid"], "place UI interactions do not restart the application")
            check(shell("getprop", "persist.sys.timezone").decode().strip() == timezone, "place editing does not modify Android's system timezone")
            report["complete"] = not report["issues"]
            assert report["complete"], report["issues"]
            return
        picker_layout("picker-landscape-dark")
        kept = active_input("active-search", find(class_name="android.widget.EditText"))
        if not kept: reopen_place()
        manual()
        dialog_appearance("manual-landscape-dark")
        before = location()
        kept = active_input("active-manual", input_for(labels["setting_location_name"]))
        check(location() == before, "editing an unsaved manual draft does not change the stored location")
        if not kept: reopen_place(); manual()
        if not args.baseline:
            edit(labels["setting_location_name"], "UI-OFFLINE-SOUTH")
            edit(labels["setting_location_latitude"], "91")
            edit(labels["setting_location_longitude"], "-70.669")
            edit(labels["setting_location_elevation"], "520")
            edit(labels["setting_location_timezone"], "America/Santiago")
            hide_keyboard(); tap(find(resource="android:id/button1"))
            find(text=labels["form_place_invalid_values"], scroll_to=True)
            invalid_tree = snapshot("invalid-latitude"); shot("invalid-latitude")
            warning = next(n for n in invalid_tree.iter("node") if n.get("text") == labels["form_place_invalid_values"])
            caption = next(n for n in visible_nodes(invalid_tree) if n.get("text") in (
                labels["setting_location_latitude"], labels["setting_location_longitude"],
                labels["setting_location_elevation"], labels["setting_location_timezone"]))
            check(box(warning)[3]-box(warning)[1] >= box(caption)[3]-box(caption)[1],
                  "the invalid-place explanation has a full line visible above the action bar")
            check(location() == before, "out-of-range latitude shows an explanation without saving any field")
            edit(labels["setting_location_latitude"], "-33.449")
            edit(labels["setting_location_timezone"], "Not/A_Zone")
            hide_keyboard(); tap(find(resource="android:id/button1"))
            find(text=labels["form_place_invalid_values"], scroll_to=True)
            check(location() == before, "an unknown IANA timezone leaves the complete stored location unchanged")
            edit(labels["setting_location_timezone"], "")
            hide_keyboard(); tap(find(resource="android:id/button1"))
            expected = {"location.mode": "manual", "location.name": "UI-OFFLINE-SOUTH", "location.latitude": -33.449,
                        "location.longitude": -70.669, "location.elevation": 520, "location.timezone": ""}
            wait_for(lambda: all(saved().get("system", {}).get(k, fields[k]["default"]) == v for k, v in expected.items()), "manual location did not persist")
            check(True, "offline save commits negative coordinates, elevation and an empty device-timezone choice together")
            snapshot("saved-location"); shot("saved-location")
            open_place(); manual()
            check(input_for(labels["setting_location_latitude"]).get("text") == "-33.449", "reopening manual entry retains the saved negative latitude")
            edit(labels["setting_location_name"], "CANCELLED-DRAFT")
            hide_keyboard(); tap(find(resource="android:id/button2"))
            check(all(saved().get("system", {}).get(k, fields[k]["default"]) == v for k, v in expected.items()), "Cancel discards a manual draft")
            manual(); edit(labels["setting_location_name"], "HOME-DRAFT")
            shell("input", "keyevent", 3)
            wait_for(lambda: "page=home.main," in state(), "Home did not leave the manual editor")
            check(all(saved().get("system", {}).get(k, fields[k]["default"]) == v for k, v in expected.items()), "Home closes the editor and discards its draft")
            reopen_place(); debug("orientation", 1, "--ei"); time.sleep(.5)
            picker_layout("picker-portrait-dark")
            set_theme("LIGHT"); open_place()
            picker_layout("picker-landscape-light")
            debug("orientation", 1, "--ei"); time.sleep(.5)
            picker_layout("picker-portrait-light")
            manual(); dialog_appearance("manual-portrait-light")
            check(input_for(labels["setting_location_latitude"]).get("text") == "-33.449", "the portrait light editor keeps saved values reachable by scrolling")
        check(pid() == report["uiPid"], "place UI interactions do not restart the application")
        check(shell("getprop", "persist.sys.timezone").decode().strip() == timezone, "place editing does not modify Android's system timezone")
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
            for path, value in original.items():
                if value is None: shell("run-as", PACKAGE, "rm", "-f", path, path + ".bak")
                else:
                    shell("run-as", PACKAGE, "sh", "-c", "cat > " + shlex.quote(path + ".ui-restore"), data=value)
                    shell("run-as", PACKAGE, "mv", path + ".ui-restore", path)
                    shell("run-as", PACKAGE, "rm", "-f", path + ".bak")
            shell("svc", "wifi", "enable" if wifi != "0" else "disable")
            shell("cmd", "package", "set-home-activity", HOME)
            shell("am", "start", "-n", HOME)
            wait_for(lambda: "id=settings" in state() and "safeMode=false" in state(), "factory UI did not recover")
            report["settingsRestored"] = all(read_app_file(args.serial, path) == value for path, value in original.items())
            report["wifiRestored"] = shell("settings", "get", "global", "wifi_on").decode().strip() == wifi
            report["developmentOverridesEmpty"] = not shell("run-as", PACKAGE, "ls", "files/dev-plugins").strip()
            if report["settingsRestored"] and report["wifiRestored"]:
                shutil.rmtree(backup); report["privateBackupDeleted"] = True
            else: report["privateBackupDirectory"] = str(backup)
        except Exception as error:
            report["restoreFailure"] = str(error); report["privateBackupDirectory"] = str(backup)
        report["complete"] = report["complete"] and report.get("settingsRestored", False) and report.get("wifiRestored", False) and report.get("developmentOverridesEmpty", False)
        (args.output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n")
        assert report.get("settingsRestored") and report.get("wifiRestored") and report.get("developmentOverridesEmpty"), report
    assert report["complete"], report["issues"]


if __name__ == "__main__": main()
