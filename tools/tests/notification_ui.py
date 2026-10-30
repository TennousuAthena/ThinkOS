#!/usr/bin/env python3
"""Capture debug-only notification layouts with mock text. Starts no HTTP listener or service."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="HUA0BWUT")
    parser.add_argument("--output", type=Path, default=Path("artifacts/ui-validation/notifications"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ["adb", "-s", args.serial]
    cases = [
        ("banner-landscape-dark", 0, "banner", "dark", "normal", "zh-CN", "快递已送达", "包裹已放在门口，方便时请查收。"),
        ("banner-portrait-light-long", 1, "banner", "light", "normal", "en", "A longer notification title that needs to wrap comfortably",
         "This is a longer message for checking line wrapping, readable spacing and the close button on the portrait screen. " * 3),
        ("takeover-landscape-dark-long", 0, "takeover", "dark", "normal", "zh-CN", "会议将在十分钟后开始，请准备好参会资料",
         "这是全屏通知的长文案预览，用于检查横屏下的文字换行、边缘留白和关闭按钮。" * 4),
        ("takeover-portrait-light", 1, "takeover", "light", "normal", "zh-CN", "休息一下", "站起来活动几分钟，让眼睛看看远处。"),
        ("takeover-portrait-night", 1, "takeover", "night", "normal", "zh-CN", "明天的日程已更新", "上午十点，项目讨论。"),
        ("takeover-landscape-night-urgent", 0, "takeover", "night", "urgent", "zh-CN", "门口有人", "请查看门口情况。"),
        ("banner-portrait-arabic", 1, "banner", "dark", "normal", "ar", "تم تحديث موعد الاجتماع", "سيبدأ الاجتماع بعد عشر دقائق. يرجى تجهيز المستندات اللازمة."),
    ]
    report = {"complete": False, "backend": "mock text only; no listener", "cases": []}

    def adb(*values, check=True):
        run = subprocess.run(base + list(values), capture_output=True, timeout=25)
        if check and run.returncode:
            raise RuntimeError(run.stderr.decode() + run.stdout.decode())
        return run.stdout

    def shell(*values):
        return adb("shell", shlex.join(map(str, values)))

    def bounds(node):
        return tuple(map(int, re.findall(r"\d+", node.attrib["bounds"])))

    # An APK install also starts the Kiosk service. Wait for its initial HOME recovery to finish
    # before opening another Activity, or that startup recovery legitimately replaces the preview.
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        shell("am", "broadcast", "-n", "moe.kiwi.thinkos/.DebugReceiver", "--es", "command", "state")
        state = adb("logcat", "-d", "-s", "ThinkOS:V").decode().split("DEBUG state=")[-1]
        if all("id=" + name in state for name in ("clock", "home", "settings")):
            break
        time.sleep(.2)
    else:
        raise AssertionError("Factory UI not ready before opening the preview")
    initial_pid = shell("pidof", "moe.kiwi.thinkos").strip()
    try:
        for name, orientation, mode, theme, level, locale, title, body in cases:
            shell("am", "start", "-n", "moe.kiwi.thinkos/.NotificationPreviewActivity",
                  "--ei", "orientation", orientation, "--es", "mode", mode, "--es", "theme", theme,
                  "--es", "level", level, "--es", "locale", locale, "--es", "title", title, "--es", "text", body)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                assert initial_pid == shell("pidof", "moe.kiwi.thinkos").strip(), "Preview crashed the app process"
                if re.search(r"mResumedActivity:.*NotificationPreviewActivity", shell("dumpsys", "activity", "activities").decode()):
                    break
                time.sleep(.1)
            else:
                raise AssertionError("Preview did not become visible")
            time.sleep(1)
            (args.output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
            path = "/sdcard/thinkos-notification-ui.xml"
            try:
                shell("uiautomator", "dump", path)
                document = shell("cat", path)
                (args.output / (name + ".xml")).write_bytes(document)
                nodes = list(ET.fromstring(document).iter("node"))
            finally:
                shell("rm", "-f", path)
            expected_w, expected_h = (1280, 800) if orientation == 0 else (800, 1280)
            targets = {key: next(n for n in nodes if n.attrib.get("text") == text)
                       for key, text in (("title", title), ("body", body), ("close", "×"))}
            for key, node in targets.items():
                left, top, right, bottom = bounds(node)
                assert 0 <= left < right <= expected_w and 0 <= top < bottom <= expected_h, (name, key, node.attrib)
            assert targets["close"].attrib.get("clickable") == "true"
            # The message, title and close affordance must have separate visible hit/text regions.
            title_box, body_box, close_box = (bounds(targets[key]) for key in ("title", "body", "close"))
            assert title_box[3] <= body_box[1], (name, "title overlaps body")
            if mode != "banner":
                assert body_box[3] <= close_box[1], (name, "body overlaps close button")
            report["cases"].append({"name": name, "title": title_box, "body": body_box, "close": close_box})
            print("PASS " + name + ": text and close button visible without overlap", flush=True)
            left, top, right, bottom = close_box
            shell("input", "tap", (left + right) // 2, (top + bottom) // 2)
            time.sleep(.5)
            resumed = shell("dumpsys", "activity", "activities").decode()
            assert not re.search(r"mResumedActivity:.*NotificationPreviewActivity", resumed), "Close did not dismiss preview"
        assert initial_pid == shell("pidof", "moe.kiwi.thinkos").strip(), "UI preview restarted the app process"
        report["complete"] = True
    finally:
        # Back closes only this debug Activity; MainActivity's own Back handler remains untouched.
        resumed = shell("dumpsys", "activity", "activities").decode()
        if re.search(r"mResumedActivity:.*NotificationPreviewActivity", resumed):
            shell("input", "keyevent", 4)
        if initial_pid != shell("pidof", "moe.kiwi.thinkos").strip():
            # A failed debug Activity must not remain the system's restart target.
            shell("am", "start", "--activity-clear-task", "-n", "moe.kiwi.thinkos/.MainActivity")
        (args.output / "logcat.txt").write_bytes(adb("logcat", "-d", "-s", "ThinkOS:V", "AndroidRuntime:E"))
        (args.output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
