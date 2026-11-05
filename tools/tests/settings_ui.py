#!/usr/bin/env python3
"""Native settings UI regression using pure declarations. Restores only this probe's changes."""
import argparse
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from device_hierarchy import dump_hierarchy, visible_nodes
from device_recovery import capture, restore
from media_device import Device
from ui_walkthrough import broadcast_json
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin

PROBE = "settings-ui-probe"
LONG_LABEL = "很长的地址标签用于检查当前值是否挤占文字"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="HUA0BWUT")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/ui-validation/settings")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ["adb", "-s", args.serial]
    result = {"complete": False, "passed": [], "issues": []}
    began = time.monotonic()

    def adb(*values, data=None, check=True):
        run = subprocess.run(base + list(values), input=data, capture_output=True, timeout=25)
        if check and run.returncode:
            raise RuntimeError(run.stderr.decode() + run.stdout.decode())
        return run.stdout

    def shell(*values, **kwargs):
        return adb("shell", shlex.join(map(str, values)), **kwargs)

    def debug(command, value=None, kind="--es"):
        values = ["am", "broadcast", "-n", "moe.kiwi.thinkos/.DebugReceiver", "--es", "command", command]
        if value is not None:
            values += [kind, "value", str(value)]
        shell(*values)

    def settings():
        data = adb("exec-out", "run-as", "moe.kiwi.thinkos", "cat", "files/settings/local.json", check=False)
        return json.loads(data) if data else {}

    def wait_saved(key, value, seconds=8):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if settings().get(PROBE, {}).get(key) == value:
                return
            time.sleep(.2)
        raise AssertionError("Setting did not persist: " + key)

    def snapshot():
        path = "/sdcard/thinkos-settings-ui.xml"
        try:
            dump_hierarchy(args.serial, path)
            data = shell("cat", path, check=False)
            if not data:
                raise AssertionError("The native UI tree is not ready yet")
            (args.output / "last-ui.xml").write_bytes(data)
            return ET.fromstring(data)
        finally:
            shell("rm", "-f", path)

    def box(node):
        return tuple(map(int, re.findall(r"\d+", node.attrib["bounds"])))

    def tap(node):
        a, b, c, d = box(node)
        shell("input", "tap", (a + c) // 2, (b + d) // 2)
        time.sleep(.4)

    def find(text=None, resource=None, scroll=False):
        for _ in range(8 if scroll else 1):
            tree = snapshot()
            viewport = box(tree.find("node"))
            found = next((n for n in tree.iter("node") if
                          (text is None or n.attrib.get("text") == text) and
                          (resource is None or n.attrib.get("resource-id") == resource) and
                          box(n)[2] > box(n)[0] and box(n)[3] > box(n)[1] and
                          viewport[0] <= (box(n)[0] + box(n)[2]) // 2 < viewport[2] and
                          viewport[1] <= (box(n)[1] + box(n)[3]) // 2 < viewport[3]), None)
            if found is not None:
                return found
            if scroll:
                w, h = viewport[2] - viewport[0], viewport[3] - viewport[1]
                shell("input", "swipe", w // 2, int(h * .84), w // 2, int(h * .28), 450)
                time.sleep(.25)
        raise AssertionError("Missing UI element: " + str(text or resource))

    def wait_text(text, seconds=10):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                return find(text=text)
            except AssertionError:
                time.sleep(.1)
        raise AssertionError("Missing UI text: " + text)

    def shot(name):
        (args.output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))

    def passed(text):
        result["passed"].append(text)
        print("PASS " + text, flush=True)

    def issue(text):
        result["issues"].append(text)
        print("ISSUE " + text, flush=True)

    def go_home():
        tap(find(text="完成"))

    def open_owner_settings(x=300):
        shell("input", "swipe", x, 380, x, 380, 900)
        time.sleep(.3)
        wait_text("设置UI样例" if x < 640 else "显示")

    def edit(label, text, secret=False):
        tap(find(text=label, scroll=True))
        tree = snapshot()
        input_node = next(n for n in tree.iter("node") if n.attrib.get("class") == "android.widget.EditText")
        if secret:
            assert input_node.attrib.get("password") == "true"
        tap(input_node)
        if not secret:
            existing = input_node.attrib.get("text", "")
            shell("input", "keyevent", 123)
            if existing:
                shell("input", "keyevent", *([67] * len(existing)))
        shell("input", "text", text)
        tap(find(resource="android:id/button1"))
        time.sleep(.3)

    initial_settings = settings()
    assert not shell("run-as", "moe.kiwi.thinkos", "ls", "files/dev-plugins").strip(), "Keep existing development overrides intact"
    device = Device(args.serial)
    assert "moe.kiwi.thinkos/" in device.shell('dumpsys', 'activity', 'activities').split('mResumedActivity')[-1]
    journal = capture(device, broadcast_json(device, 'ui_state'))
    print('Private recovery journal: '+str(journal), flush=True)
    try:
        shell("am", "start", "-W", "-n", "moe.kiwi.thinkos/.MainActivity")
        initial_pid = shell("pidof", "moe.kiwi.thinkos").strip()
        assert initial_pid
        result["initialPid"] = initial_pid.decode()
        with tempfile.TemporaryDirectory(prefix="thinkos-settings-ui-") as temporary:
            out = Path(temporary) / "packages"
            for name in (PROBE, "plain-ui-probe"):
                source, _ = package_plugin(ROOT / "tools/tests/fixtures" / name, out)
                publish_directory(source, args.serial)
            home = Path(temporary) / "home"
            shutil.copytree(ROOT / "plugins/home", home)
            manifest = json.loads((home / "manifest.json").read_text())
            manifest["version"] = "0.1.1-ui-probe"
            (home / "manifest.json").write_text(json.dumps(manifest))
            (home / "views/main.json").write_text(json.dumps({"layout": "stacks", "slots": {
                "left": [{"widget": PROBE + ".preview"}], "right": [{"widget": "plain-ui-probe.preview"}]}}))
            source, _ = package_plugin(home, out)
            publish_directory(source, args.serial)

        debug("hold_attention", "AWAKE")
        debug("orientation", 0, "--ei")
        debug("theme", "DARK")
        debug("lux", 20, "--ef")
        debug("page", "home.main")
        wait_text("有设置的卡片")
        shot("home-card-owners")
        open_owner_settings()
        shot("settings-landscape")
        debug("state")
        logs = adb("logcat", "-d", "-s", "ThinkOS:V").decode().split("DEBUG state=")[-1]
        assert re.search(r"id=settings-ui-probe, version=1.0.0, bytes=0", logs)
        passed("long press on a home card opens its owner's generated settings without any Lua process")

        tap(find(text="开关演示"))
        wait_saved("enabled", True)
        tree = snapshot()
        switch = next((n for n in tree.iter("node") if n.attrib.get("class") == "android.widget.Switch"), None)
        if switch is None or switch.attrib.get("checkable") != "true" or switch.attrib.get("checked") != "true":
            issue("boolean setting does not expose its checked state to accessibility")
        result["switchAccessibility"] = switch.attrib if switch is not None else None
        (args.output / "switch-and-reset.xml").write_bytes(ET.tostring(tree, encoding="utf-8"))
        reset = find(text="恢复默认", scroll=True)
        targets = [n for n in snapshot().iter("node") if n.get("clickable") == "true"
                   and any(child.get("text") == "恢复默认" for child in n.iter("node"))]
        assert targets, "The page reset must expose a clickable row"
        reset = min(targets, key=lambda n: (box(n)[2] - box(n)[0]) * (box(n)[3] - box(n)[1]))
        a, b, c, d = box(reset)
        result["resetTargetPx"] = [c-a, d-b]
        if min(c-a, d-b) < 78:
            issue("reset hit target is smaller than 48dp on the 260dpi device")
        tap(reset)
        find(text="恢复这一页的默认设置？")
        tap(find(resource="android:id/button2"))
        assert settings().get(PROBE, {}).get("enabled") is True
        passed("canceling the page reset preserves the edit")
        tap(find(text="恢复默认", scroll=True))
        tap(find(resource="android:id/button1"))
        # Resetting the only override removes the key rather than storing its default.
        deadline = time.monotonic()+8
        while time.monotonic()<deadline and "enabled" in settings().get(PROBE, {}):
            time.sleep(.2)
        assert "enabled" not in settings().get(PROBE, {})
        go_home()
        open_owner_settings()
        tree = snapshot()
        switch = next((n for n in tree.iter("node") if n.attrib.get("class") == "android.widget.Switch"), None)
        if switch is None or switch.attrib.get("checked") != "false":
            issue("reset boolean does not expose its unchecked state to accessibility")
        assert not any(n.get("text") == "恢复默认" for n in tree.iter("node"))
        passed("the confirmed page reset clears the override and removes its reset link")

        tap(find(text="强度演示", scroll=True))
        assert any(n.get('text') == '+' for n in snapshot().iter('node'))
        tap(find(text="+"))
        wait_saved("level", 6)
        controls = {n.get('text'): box(n) for n in snapshot().iter('node') if n.get('text') in ('−', '+')}
        left, right = controls['−'][2], controls['+'][0]
        y = (controls['−'][1]+controls['−'][3])//2
        row_before = box(find(text='强度演示'))
        shell('input', 'swipe', (left+right)//2, y, (left+right)//2, max(100, y-160), 500)
        assert settings().get(PROBE, {}).get('level') == 6, 'A vertical start on the slider must not commit a number'
        moved = next((n for n in visible_nodes(snapshot()) if n.get('text') == '强度演示'), None)
        assert moved is None or box(moved) != row_before, 'A vertical start must scroll the form'
        passed('a vertical start on the expanded number scrolls the form without saving a value')
        tap(find(text="方案演示", scroll=True))
        assert not any(n.get('text') == '+' for n in snapshot().iter('node')), 'Opening a choice must collapse the number control'
        tap(find(text="方案二"))
        wait_saved("mode", "second")
        edit("名称演示", "UI-CHANGED")
        wait_saved("name", "UI-CHANGED")
        edit("密钥演示", "DEMO_SECRET_123", secret=True)
        wait_saved("secret", "DEMO_SECRET_123")
        tree = snapshot()
        assert not any("DEMO_SECRET_123" in n.attrib.get("text", "") or "DEMO_SECRET_123" in n.attrib.get("content-desc", "") for n in tree.iter("node"))
        passed("stepper, choice and native text/password dialogs work; saved secrets stay masked")

        label = find(text=LONG_LABEL, scroll=True)
        a, b, c, d = box(label)
        result["longLabelPx"] = [c-a, d-b]
        if c-a < 180 or d-b > 90:
            issue("a long address value squeezes its setting label into a narrow or oversized column")
        shot("long-value-layout")
        debug("orientation", 1, "--ei")
        time.sleep(.5)
        label = find(text=LONG_LABEL, scroll=True)
        a, b, c, d = box(label)
        result["portraitLongLabelPx"] = [c-a, d-b]
        if c-a < 180 or d-b > 90:
            issue("the long address label is squeezed or oversized in portrait")
        shot("long-value-portrait")
        debug("orientation", 0, "--ei")
        time.sleep(.5)
        passed("long setting labels stay within two lines in landscape and portrait")

        go_home()
        wait_text("UI-CHANGED")
        tap(find(text="UI-CHANGED"))
        wait_text("名称：UI-CHANGED")
        open_owner_settings()
        passed("a pure declaration card and page update immediately and the page long press keeps the correct settings owner")
        go_home()
        debug("page", "home.main")
        time.sleep(.7)
        wait_text("有设置的卡片")
        open_owner_settings(950)
        passed("long pressing a card with no declared settings falls back to system settings")
        go_home()
        assert initial_pid == shell("pidof", "moe.kiwi.thinkos").strip()

        shell("am", "force-stop", "moe.kiwi.thinkos")
        shell("am", "start", "-W", "-n", "moe.kiwi.thinkos/.MainActivity")
        debug("theme", "DARK")
        debug("hold_attention", "AWAKE")
        debug("orientation", 0, "--ei")
        debug("lux", 20, "--ef")
        wait_text("UI-CHANGED")
        assert settings()[PROBE]["level"] == 6 and settings()[PROBE]["mode"] == "second"
        result["restartedPid"] = shell("pidof", "moe.kiwi.thinkos").strip().decode()
        debug("state")
        state = adb("logcat", "-d", "-s", "ThinkOS:V").decode().split("DEBUG state=")[-1]
        assert "safeMode=false" in state and "id=settings-ui-probe, version=1.0.0, bytes=0" in state
        shot("settings-retained-after-restart")
        passed("the generated settings and declaration-only card survive an application restart")
        result["complete"] = not result["issues"]
    except Exception as error:
        result["failure"] = str(error)
        try:
            shot("failed-screen")
        except RuntimeError:
            pass
        raise
    finally:
        (args.output / "logcat.txt").write_bytes(adb("logcat", "-d", "-s", "ThinkOS:V", "AndroidRuntime:E"))
        try:
            shell("am", "force-stop", "moe.kiwi.thinkos")
            for name in ("home", PROBE, "plain-ui-probe"):
                remove_plugin(name, args.serial)
            result.update(restore(journal, device))
        except Exception as error:
            result['recovery_error'] = str(error)
            result['private_recovery_journal'] = str(journal)
        result["settingsRestored"] = settings() == initial_settings
        result["elapsedSeconds"] = round(time.monotonic()-began, 2)
        (args.output / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n")
        assert result["settingsRestored"]
        assert not result.get('recovery_error'), result.get('recovery_error')
    assert result["complete"], result["issues"]


if __name__ == "__main__":
    main()
