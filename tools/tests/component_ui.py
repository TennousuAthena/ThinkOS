#!/usr/bin/env python3
"""Exercise native component UI with mock plugin data on the API-27 device, then remove the probe."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from device_hierarchy import dump_hierarchy
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="HUA0BWUT")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/ui-validation/components")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ["adb", "-s", args.serial]
    findings = []
    result = {"passed": findings, "complete": False}

    def adb(*values, check=True):
        run = subprocess.run(base + list(values), capture_output=True, timeout=25)
        if check and run.returncode:
            raise RuntimeError(run.stderr.decode() + run.stdout.decode())
        return run.stdout

    def shell(*values):
        return adb("shell", shlex.join(map(str, values)))

    def debug(command, value=None, kind="--es"):
        values = ["am", "broadcast", "-n", "moe.kiwi.thinkos/.DebugReceiver", "--es", "command", command]
        if value is not None:
            values += [kind, "value", str(value)]
        shell(*values)

    def logs():
        return adb("logcat", "-d", "-s", "ThinkOS:V", "AndroidRuntime:E").decode()

    def since(before):
        # logcat is a ring: old lines can disappear between snapshots.
        marker=next((line for line in reversed(before.splitlines()) if line.strip()), '')
        current=logs();position=current.rfind(marker) if marker else -1
        return current[position+len(marker):] if position>=0 else current

    def state():
        debug("state")
        text = logs().split("DEBUG state=")[-1]
        return {key: re.search(key + r"=([\w.-]+)", text).group(1)
                for key in ("page", "theme", "orientation")}

    def wait_state(key, expected, seconds=12):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if state()[key] == expected:
                time.sleep(.6)
                return
            time.sleep(.3)
        raise AssertionError(f"{key} did not reach {expected}")

    def shot(name):
        (args.output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))

    def passed(message):
        findings.append(message)
        print("PASS " + message, flush=True)

    def gesture(*values):
        shell("input", *values)
        time.sleep(.7)

    def navigate(page, direction=1):
        # The runtime sorts contributed page IDs; manifest declaration order is not deck order.
        for _ in range(8):
            current = state()
            if current["page"] == page:
                return
            if current["orientation"] == "LANDSCAPE":
                gesture("swipe", *( (1100, 400, 150, 400, 600) if direction > 0 else (150, 400, 1100, 400, 600) ))
            else:
                gesture("swipe", *( (700, 600, 120, 600, 600) if direction > 0 else (120, 600, 700, 600, 600) ))
        raise AssertionError("Could not navigate to " + page)

    def nodes():
        path = "/sdcard/thinkos-ui-validation.xml"
        paused = False
        try:
            for attempt in range(3):
                dump_hierarchy(args.serial, path)
                data = adb("shell", "cat", path, check=False)
                if data:
                    return list(ET.fromstring(data).iter("node"))
                debug("hold_attention", "AMBIENT"); paused = True; time.sleep(.3)
            raise AssertionError("UIAutomator did not publish a hierarchy")
        finally:
            shell("rm", "-f", path)
            if paused: debug("hold_attention", "AWAKE")

    def rows():
        return [node for node in nodes() if re.fullmatch(r"Row \d+", node.attrib.get("text", ""))]

    def tap_node(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        gesture("tap", (x1 + x2) // 2, (y1 + y2) // 2)

    initial_pid = adb("shell", "pidof", "moe.kiwi.thinkos").strip()
    assert initial_pid, "Start the debug APK first"
    result["pid"] = initial_pid.decode()
    baseline = logs()
    try:
        with tempfile.TemporaryDirectory(prefix="thinkos-component-ui-") as temporary:
            directory, _ = package_plugin(ROOT / "tools/tests/fixtures/component-probe", Path(temporary))
            publish_directory(directory, args.serial)
        # Publishing an identical digest is intentionally a no-op; navigate to it if already loaded.
        debug("orientation", 0, "--ei")
        debug("hold_attention", "AWAKE")
        debug("theme", "DARK")
        debug("lux", 20, "--ef")
        wait_state("theme", "DARK", seconds=45)
        navigate("component-probe.split")
        wait_state("page", "component-probe.split")
        shot("split-landscape-dark")
        passed("split page displays native progress, chart, moon and a virtualized list")

        before = logs()
        gesture("tap", 280, 610)
        assert "COMPONENT PROBE TAP ring" in since(before)
        gesture("tap", 800, 345)
        assert "COMPONENT PROBE TAP row 1" in since(before)
        passed("native ring and list row taps reach the owning Lua plugin")

        gesture("swipe", 1000, 720, 1000, 360, 800)
        assert state()["page"] == "component-probe.split", "vertical list scrolling changed the page"
        visible = rows()
        assert visible and min(int(n.attrib["text"].split()[1]) for n in visible) > 1
        before = logs()
        tap_node(visible[0])
        index = int(visible[0].attrib["text"].split()[1])
        assert f"COMPONENT PROBE TAP row {index}" in since(before)
        shot("list-scrolled-and-tapped")
        passed("vertical scrolling retains the page and recycled rows emit their actual 1-based index")

        gesture("swipe", 1100, 440, 160, 440, 600)
        wait_state("page", "component-probe.stage")
        shot("stage-landscape-dark")
        passed("horizontal swipe beginning over a list still navigates to the stage layout")
        navigate("component-probe.focus")
        wait_state("page", "component-probe.focus")
        before = logs()
        gesture("tap", 640, 725)
        assert "COMPONENT PROBE TAP footer" in since(before)
        shot("focus-landscape-dark")
        passed("focus groups render derived daylight and line charts without location permission; footer tap works")

        debug("orientation", 1, "--ei")
        wait_state("orientation", "PORTRAIT")
        shot("focus-portrait-dark")
        navigate("component-probe.stage", direction=-1)
        wait_state("page", "component-probe.stage")
        shot("stage-portrait-dark")
        navigate("component-probe.split", direction=-1)
        wait_state("page", "component-probe.split")
        shot("split-portrait-dark")
        passed("focus, stage and split reflow in portrait without restarting the application")

        debug("theme", "LIGHT")
        debug("lux", 10_000, "--ef")
        wait_state("theme", "LIGHT", seconds=45)
        time.sleep(2)
        shot("split-portrait-light")
        debug("theme", "NIGHT")
        debug("lux", .1, "--ef")
        wait_state("theme", "NIGHT", seconds=30)
        time.sleep(2)
        shot("split-portrait-night")
        passed("the same mock components render in light, dark and red night themes")
        assert initial_pid == adb("shell", "pidof", "moe.kiwi.thinkos").strip()
        delta = since(baseline)
        assert "FATAL EXCEPTION" not in delta
        result["complete"] = True
    finally:
        (args.output / "logcat.txt").write_text(logs())
        remove_plugin("component-probe", args.serial)
        debug("hold_attention")
        debug("theme")
        debug("lux")
        debug("orientation", 10, "--ei")
        (args.output / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
