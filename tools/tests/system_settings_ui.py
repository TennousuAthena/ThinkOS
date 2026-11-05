#!/usr/bin/env python3
"""Change system settings through native UI, verify behavior, restore only touched keys."""
import argparse
import json
from pathlib import Path
import re
import shlex
import struct
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
OWNED = {
    "theme.mode", "theme.night", "display.orientation", "attention.awake_to_ambient_s",
    "attention.to_asleep_s", "attention.engaged_idle_s",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="HUA0BWUT")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/ui-validation/system-settings")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ["adb", "-s", args.serial]
    fields = json.loads((ROOT / "shared/schema/settings/system.json").read_text())["fields"]
    texts = json.loads((ROOT / "shared/i18n/system/zh-Hans.json").read_text())
    settings_texts = json.loads((ROOT / "plugins/settings/i18n/zh-Hans.json").read_text())
    report = {"complete": False, "passed": [], "issues": [], "controlled_inputs": "debug lux and presence; normal attention timing"}
    viewport = [1280, 800]

    def adb(*values, data=None, check=True):
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

    def saved():
        data = adb("exec-out", "run-as", PACKAGE, "cat", "files/settings/local.json", check=False)
        return json.loads(data) if data else {}

    def effective(key):
        return saved().get("system", {}).get(key, fields[key]["default"])

    def translated(value):
        return texts[value[1:].replace(".", "_")] if value.startswith("@") else value

    def label(key):
        return translated(fields[key]["label"])

    recovery_device = Device(args.serial)
    assert shell("getprop", "persist.sys.locale").decode().strip().startswith("zh")
    journal = capture(recovery_device, broadcast_json(recovery_device, 'ui_state'))
    print('Private recovery journal: '+str(journal), flush=True)
    shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    pid = shell("pidof", PACKAGE).decode().strip()
    assert pid and not shell("run-as", PACKAGE, "ls", "files/dev-plugins").strip()
    assert shell("getprop", "persist.sys.locale").decode().strip().startswith("zh")
    before = saved()
    touched = set()

    def state():
        raw = shell("am", "broadcast", "-n", PACKAGE + "/.DebugReceiver", "--es", "command", "benchmark_state").decode()
        match = re.search(r'data="([^"]*)"', raw)
        assert match, "No benchmark state: " + raw
        return dict(zip(("page", "attention", "theme", "orientation"), match.group(1).split('|')[:4]))

    def wait_state(key, value, seconds=6):
        deadline = time.monotonic()+seconds
        while time.monotonic() < deadline:
            if state()[key] == value:
                return
            time.sleep(.2)
        raise AssertionError(key + " did not become " + value)

    def snapshot():
        path = "/sdcard/thinkos-system-settings-ui.xml"
        try:
            dump_hierarchy(args.serial, path)
            data = shell("cat", path)
            (args.output / "last-ui.xml").write_bytes(data)
            return ET.fromstring(data)
        finally:
            shell("rm", "-f", path)

    def box(node):
        return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))

    def scroll(toward_top=False):
        w, h = viewport
        start, end = (h*.30, h*.84) if toward_top else (h*.84, h*.30)
        shell("input", "swipe", w//2, round(start), w//2, round(end), 450)

    def find(text):
        order = {label(key): i for i, key in enumerate(fields)}
        for i, field in enumerate(fields.values()):
            for choice in field.get("choices", []):
                order[translated(choice["label"])] = i
        for _ in range(14):
            nodes = list(visible_nodes(snapshot()))
            node = next((n for n in nodes if n.get("text") == text), None)
            if node is not None:
                return node
            visible = [order[n.get("text")] for n in nodes if n.get("text") in order]
            scroll(toward_top=bool(visible) and order.get(text, len(fields)) < min(visible))
        raise AssertionError("Missing UI text: " + text)

    def tap(node):
        a, b, c, d = box(node)
        shell("input", "tap", (a+c)//2, (b+d)//2)
        time.sleep(.25)

    def shot(name):
        data = adb("exec-out", "screencap", "-p")
        (args.output / (name + ".png")).write_bytes(data)
        viewport[:] = struct.unpack(">II", data[16:24])
        return list(viewport)

    def check(ok, description):
        report["passed" if ok else "issues"].append(description)
        print(("PASS " if ok else "ISSUE ") + description, flush=True)

    def wait_saved(key, value):
        deadline = time.monotonic()+6
        while time.monotonic() < deadline:
            if effective(key) == value:
                return
            time.sleep(.2)
        raise AssertionError("UI edit did not persist: " + key)

    def set_bool(key, value):
        open_system(key)
        touched.add(key)
        if effective(key) != value:
            tap(find(label(key)))
        wait_saved(key, value)

    def set_choice(key, value):
        open_system(key)
        touched.add(key)
        if effective(key) != value:
            tap(find(label(key)))
            choice = next(c for c in fields[key]["choices"] if c["value"] == value)
            tap(find(translated(choice["label"])))
        wait_saved(key, value)

    def set_number(key, value):
        open_system(key)
        touched.add(key)
        field = fields[key]
        if field.get("presets"):
            expected = {30: "30 秒", 60: "1 分钟"}[value]
            if effective(key) != value:
                tap(find(label(key)))
                tap(find(expected))
            wait_saved(key, value)
            find(expected)
            report.setdefault("nativeTimingChoices", []).append({"key": key, "value": value, "display": expected})
            return
        tap(find(label(key)))
        for _ in range(4):
            row = find(label(key))
            bottom = box(row)[3]
            controls = [(n.get("text"), box(n)) for n in snapshot().iter("node")
                        if n.get("text") in ("−", "+") and bottom <= box(n)[1] < bottom+150]
            if len(controls) == 2 and all(b[3] < viewport[1]-8 for _, b in controls):
                break
            scroll()
        else:
            raise AssertionError("Slider controls are not fully visible: " + key)
        minus = next(b for t, b in controls if t == "−")
        plus = next(b for t, b in controls if t == "+")
        inset = (minus[2]-minus[0])/48*14
        left, right = minus[2]+inset, plus[0]-inset
        current = effective(key)
        x1 = left+(right-left)*(current-field["min"])/(field["max"]-field["min"])
        x2 = left+(right-left)*(value-field["min"])/(field["max"]-field["min"])
        y = (minus[1]+minus[3])//2
        shell("input", "swipe", round(x1), y, round(x2), y, 650)
        wait_saved(key, value)

    def open_system(key=None):
        # Entry ownership and long presses have their own regression. Keep this
        # control check valid when NIGHT policy shows the sleep clock underneath.
        shell("input", "keyevent", "KEYCODE_HOME")
        debug("settings", "system")
        wait_state("page", "settings.form")
        find(texts["setting_section_display"])
        if key is not None:
            field = fields[key]
            tap(find(texts["setting_section_" + field["section"]]))
            if field.get("level") == "advanced": tap(find(settings_texts["advanced"]))

    def close_system():
        tap(find("完成"))
        shell("input", "keyevent", "KEYCODE_HOME")
        if state()["theme"] != "NIGHT": wait_state("page", "home.main")

    def stable_dark(lux, name):
        debug("lux", lux, "--ef")
        began = time.monotonic()
        samples = []
        while time.monotonic()-began < 34:
            current = state()
            samples.append({"seconds": round(time.monotonic()-began, 2), "theme": current["theme"]})
            assert current["page"] == "home.main", "Theme must be tested outside settings"
            time.sleep(1)
        report[name] = {"lux": lux, "samples": samples}
        shot(name)
        check(all(s["theme"] == "DARK" for s in samples),
              name + ": a pinned dark theme stays dark beyond the sensor dwell interval")

    def brightness(name):
        window = shell("dumpsys", "window", "windows").decode()
        match = re.search(r"(?ms)^  Window #\d+ Window\{[^\n]*moe\.kiwi\.thinkos/[^\n]*MainActivity[^\n]*\}:\n(.*?)(?=^  Window #|\Z)", window)
        assert match, "ThinkOS window missing from diagnostic output"
        block = match.group(1)
        display = shell("dumpsys", "display").decode()
        (args.output / (name + "-window.txt")).write_text(block)
        (args.output / (name + "-display.txt")).write_text(display)
        value = float(re.search(r"\bsbrt=([0-9.]+)", block).group(1))
        backlight = int(re.search(r"\bmScreenBrightness=(\d+)", display).group(1))
        shot(name)
        return {"window": value, "backlight": backlight}

    try:
        shell("input", "keyevent", 3)
        debug("hold_attention", "AWAKE")
        debug("orientation", 0, "--ei")
        wait_state("orientation", "LANDSCAPE")
        deadline = time.monotonic() + 8
        while not state()["page"] and time.monotonic() < deadline:
            time.sleep(.2)
        assert state()["page"], "The local page catalog did not load"
        # Make disabling night observable, with normal ENGAGED behavior while settings is open.
        open_system()
        set_choice("theme.mode", "dark")
        set_bool("theme.night", True)
        close_system()
        debug("lux", .1, "--ef")
        wait_state("theme", "NIGHT", seconds=15)
        shell("input", "tap", viewport[0]-5, viewport[1]//2)
        debug("hold_attention")
        open_system()
        set_bool("theme.night", False)
        wait_state("theme", "DARK", seconds=3)
        check(state()["attention"] == "ENGAGED", "turning night mode off updates the theme while settings remains ENGAGED")
        close_system()
        debug("hold_attention", "AWAKE")
        stable_dark(.1, "dark-low-lux")
        stable_dark(10000, "dark-high-lux")

        open_system()
        first = "landscape" if effective("display.orientation") == "portrait" else "portrait"
        orientations = [first, "portrait" if first == "landscape" else "landscape"]
        report["orientations"] = {}
        for value in orientations:
            set_choice("display.orientation", value)
            wait_state("orientation", value.upper())
            dimensions = shot("orientation-" + value)
            report["orientations"][value] = dimensions
            check((dimensions[0] > dimensions[1]) == (value == "landscape"),
                  "selecting " + value + " immediately rearranges the native UI")
        set_choice("display.orientation", "landscape")
        wait_state("orientation", "LANDSCAPE")
        shot("orientation-landscape")
        set_number("attention.awake_to_ambient_s", 30)
        set_number("attention.to_asleep_s", 60)
        set_number("attention.engaged_idle_s", 5)
        close_system()
        debug("presence", "false", "--ez")
        debug("hold_attention")
        w, h = viewport
        shell("input", "tap", w-5, h//2)
        began = time.monotonic()
        transitions = {}
        outputs = {}
        deadline = began+68
        while time.monotonic() < deadline:
            current = state()
            level = current["attention"]
            if level not in transitions:
                transitions[level] = round(time.monotonic()-began, 2)
                print("OBSERVED " + level + " at " + str(transitions[level]) + "s", flush=True)
                if level in ("ENGAGED", "AMBIENT", "ASLEEP"):
                    time.sleep(2.3)
                    outputs[level] = brightness("attention-" + level.lower())
            if level == "ASLEEP":
                break
            time.sleep(.45)
        report["attentionTransitionsSeconds"] = transitions
        report["brightness"] = outputs
        for level, earliest, latest in (("AWAKE", 4.5, 8), ("AMBIENT", 29.5, 34), ("ASLEEP", 59.5, 64)):
            check(earliest <= transitions.get(level, -1) <= latest,
                  "the next idle cycle reaches " + level + " using the edited timing")
        cap = effective("display.brightness_max")/100
        check(abs(outputs["ENGAGED"]["window"]-cap) < .02
              and abs(outputs["AMBIENT"]["window"]-cap*.47) < .02
              and abs(outputs["ASLEEP"]["window"]-.03) < .01
              and outputs["ENGAGED"]["backlight"] > outputs["AMBIENT"]["backlight"] > outputs["ASLEEP"]["backlight"],
              "attention timing changes the actual window brightness and display backlight")
        check(pid == shell("pidof", PACKAGE).decode().strip(), "system setting edits apply without restarting the application")
        report["complete"] = not report["issues"]
    except Exception as error:
        report["failure"] = str(error)
        shot("failed-screen")
        raise
    finally:
        (args.output / "logcat.txt").write_bytes(adb("logcat", "-d", "--pid=" + pid, "-s", "ThinkOS:V", "AndroidRuntime:E"))
        try: report.update(restore(journal, recovery_device))
        except Exception as error:
            report['recovery_error'] = str(error); report['private_recovery_journal'] = str(journal)
        report["settingsRestored"] = saved() == before
        report["restoredKeys"] = sorted(touched)
        (args.output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n")
        assert report["settingsRestored"]
        assert not report.get('recovery_error'), report.get('recovery_error')
    assert report["complete"], report["issues"]


if __name__ == "__main__":
    main()
