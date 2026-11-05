#!/usr/bin/env python3
"""Verify generated help and refused-setting feedback; remove only this probe's changes."""
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
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin
from device_hierarchy import dump_hierarchy, visible_nodes

PACKAGE = "moe.kiwi.thinkos"
PROBE = "feedback-ui-probe"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="HUA0BWUT")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/ui-validation/settings-feedback")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ["adb", "-s", args.serial]
    report = {"complete": False, "passed": [], "issues": []}

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
        raw = adb("exec-out", "run-as", PACKAGE, "cat", "files/settings/local.json", check=False)
        return json.loads(raw) if raw else {}

    shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    pid = shell("pidof", PACKAGE).decode().strip()
    assert pid and not shell("run-as", PACKAGE, "ls", "files/dev-plugins").strip()
    before = saved()
    manifest = json.loads((ROOT / "tools/tests/fixtures" / PROBE / "manifest.json").read_text())

    def state():
        raw = shell("am", "broadcast", "-n", PACKAGE + "/.DebugReceiver", "--es", "command", "benchmark_state").decode()
        match = re.search(r'data="([^"]*)"', raw)
        assert match, "No benchmark state: " + raw
        values = match.group(1).split('|')
        return {"page": values[0], "theme": values[2]}

    def snapshot(name=None):
        path = "/sdcard/thinkos-feedback-ui.xml"
        try:
            dump_hierarchy(args.serial, path)
            data = shell("cat", path)
            (args.output / ((name or "last-ui") + ".xml")).write_bytes(data)
            return ET.fromstring(data)
        finally:
            shell("rm", "-f", path)

    def box(node):
        return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))

    def tap(node):
        a, b, c, d = box(node)
        shell("input", "tap", (a+c)//2, (b+d)//2)
        time.sleep(.2)

    def find(text):
        for _ in range(5):
            node = next((n for n in visible_nodes(snapshot()) if n.get("text") == text), None)
            if node is not None:
                return node
            shell("input", "swipe", 650, 685, 650, 210, 400)
        raise AssertionError("Missing UI text: " + text)

    def shot(name):
        (args.output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))

    def check(ok, description):
        report["passed" if ok else "issues"].append(description)
        print(("PASS " if ok else "ISSUE ") + description, flush=True)

    def navigate(page):
        for _ in range(8):
            if state()["page"] == page:
                return
            shell("input", "swipe", 1100, 400, 150, 400, 450)
            time.sleep(.4)
        raise AssertionError("Page not reachable: " + page)

    def help_layout(name):
        tree = snapshot(name)
        nodes = list(tree.iter("node"))
        descriptions, geometry = True, True
        found = []
        for field in manifest["settings"].values():
            label = next((n for n in nodes if n.get("text") == field["label"]), None)
            help_node = next((n for n in nodes if n.get("text") == field["help"]), None)
            if label is None or help_node is None:
                geometry = descriptions = False
                continue
            l, t, r, b = box(help_node)
            geometry &= box(label)[3] <= t and r > l and b > t
            descriptions &= any(field["label"] in n.get("content-desc", "") and field["help"] in n.get("content-desc", "") for n in nodes)
            found.append((t, b))
        geometry &= all(a[1] <= b[0] for a, b in zip(found, found[1:]))
        shot(name)
        check(geometry and descriptions and len(found) == len(manifest["settings"]),
              name + ": help is visible below labels, does not overlap and is included in accessibility descriptions")

    def refused_edit():
        # Sensor tuning stays hidden. A shell-only debug hook sends an invalid
        # editor result through the actual settings plugin, store and error timer.
        find("最大亮度")
        shell("am", "broadcast", "-n", PACKAGE + "/.DebugReceiver", "--es", "command", "form_change",
              "--es", "key", "display.brightness_max", "--es", "value_json", "101")
        touched = time.monotonic()
        early = snapshot("error-visible")
        warning = next((n for n in early.iter("node") if n.get("text", "").startswith("不能大于")), None)
        check(warning is not None and saved().get("system", {}) == before.get("system", {}),
              "an invalid editor result is refused without saving and shows a translated error")
        report["warningText"] = warning.get("text") if warning is not None else None
        shot("error-visible")
        time.sleep(max(0, touched+4-time.monotonic()))
        late = snapshot("error-after-wait")
        report["warningAbsentObservationSeconds"] = round(time.monotonic()-touched, 2)
        shot("error-after-wait")
        check(not any(n.get("text", "").startswith("不能大于") for n in late.iter("node")),
              "the refused-edit explanation disappears after its display interval")

    try:
        with tempfile.TemporaryDirectory(prefix="thinkos-feedback-ui-") as temporary:
            source, _ = package_plugin(ROOT / "tools/tests/fixtures" / PROBE, Path(temporary))
            publish_directory(source, args.serial)
        shell("input", "keyevent", 3)
        debug("orientation", 0, "--ei")
        debug("hold_attention", "AWAKE")
        debug("lux", 20, "--ef")
        debug("theme")
        deadline = time.monotonic()+16
        while (state()["theme"] != "DARK" or not state()["page"]) and time.monotonic() < deadline:
            time.sleep(.5)
        assert state()["theme"] == "DARK"
        navigate(PROBE + ".main")
        shell("input", "swipe", 650, 380, 650, 380, 900)
        find("反馈UI样例")
        help_layout("help-landscape")
        debug("orientation", 1, "--ei")
        time.sleep(.5)
        help_layout("help-portrait")
        debug("orientation", 0, "--ei")
        time.sleep(.5)
        for _ in range(5):
            tap(find("+"))
        assert saved().get(PROBE, {}).get("level") == 4
        check(True, "native step controls clamp at the declared maximum")
        tap(find("系统设置"))
        find("系统设置")
        refused_edit()
        check(pid == shell("pidof", PACKAGE).decode().strip(), "feedback checks do not restart the application")
        report["complete"] = not report["issues"]
    except Exception as error:
        report["failure"] = str(error)
        shot("failed-screen")
        raise
    finally:
        (args.output / "logcat.txt").write_bytes(adb("logcat", "-d", "--pid=" + pid, "-s", "ThinkOS:V", "AndroidRuntime:E"))
        shell("input", "keyevent", 3)
        shell("cmd", "package", "set-home-activity", "org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher")
        shell("am", "force-stop", PACKAGE)
        remove_plugin(PROBE, args.serial)
        restored = saved()
        if PROBE in before:
            restored[PROBE] = before[PROBE]
        else:
            restored.pop(PROBE, None)
        key = "theme.dark_below_lux"
        if key in before.get("system", {}):
            restored.setdefault("system", {})[key] = before["system"][key]
        else:
            restored.get("system", {}).pop(key, None)
        encoded = json.dumps(restored, ensure_ascii=False, separators=(",", ":")).encode()
        shell("run-as", PACKAGE, "sh", "-c", "cat > files/settings/local.json.ui-restore", data=encoded)
        shell("run-as", PACKAGE, "mv", "files/settings/local.json.ui-restore", "files/settings/local.json")
        shell("run-as", PACKAGE, "rm", "-f", "files/settings/local.json.bak")
        shell("cmd", "package", "set-home-activity", PACKAGE + "/.MainActivity")
        shell("am", "start", "-n", PACKAGE + "/.MainActivity")
        report["settingsRestored"] = saved() == before
        (args.output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n")
        assert report["settingsRestored"]
    assert report["complete"], report["issues"]


if __name__ == "__main__":
    main()
