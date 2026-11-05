#!/usr/bin/env python3
"""Drive the already-installed components_ui native-window probe; never install.

Example (use the full SHA-256 values of the fixed, installed APKs):
  python3 -B tools/tests/component_targets_device.py --apk-sha256 HEX64 \
    --test-apk-sha256 HEX64 --output /tmp/component-targets-019

Requires a Chinese system locale, an idle MainActivity/base page, empty media
and update transactions, no optional updater, and audio streams 1..5 at zero.
Only real scrolls are injected. CHECK/DOWNLOAD/INSTALL are never touch-clicked;
the probe's fixed accessibility negative check must leave callbacks/HTTP alone.
The AVAILABLE state legitimately hides INSTALL and disables DOWNLOAD.

Uses only an existing ThinkHierarchy jar/cache (no compilation). Raw original
UI, five immutable files, instrumentation output and restoration journal stay
in a 0700 private temporary directory, with 0600 files. A human touch, changed
foreground or raised volume stops without reclaiming the screen. Retain that
journal for manual recovery when interrupted; original file bytes are never
written back over possibly newer data. No logcat clearing, root RPC, network
rule, APK/module install, Device Owner change, reboot or performance sampling.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import sys
import tempfile
import time
import uuid

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools/tests")]
from device_app_files import read_app_file, restore_app_file
from device_hierarchy import node_bounds, visible_nodes
from media_device import APP, Device
from registry_device import (ExternalInput, Guard, Hierarchy, PHYSICAL, ProbeError,
                             RUNNER, TEST_APP, TouchWatch, app_exists,
                             cached_hierarchy, digest, file_digest, foreground,
                             installed_sha, private_json, quiet, read_json, require)
from ui_walkthrough import broadcast_json

IDS = ("thinkos-updater", "thinkos-cast", "thinkos-immersive", "thinkos-shell",
       "thinkos-certs", "thinkos-power", "timesync")
IMMUTABLE = ("files/settings/local.json", "files/store/welcome.json",
             "files/cast/identity-seed", "files/cast/airplay-identity",
             "files/management-exit")
PENDING = ("files/update/pending.json", "files/update/pending.json.bak",
           "files/update/module-pending.json", "files/update/module-pending.json.bak")
UI_KEYS = ("theme", "attention", "presence", "lux", "locale", "orientation")
MAX_PRIVATE = 8 * 1024 * 1024


def focused_component(device):
    line = re.search(r"mCurrentFocus=(.*)", device.shell("dumpsys", "window", "windows"))
    match = re.search(r"\b([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\b", line[1]) if line else None
    return match[1] if match else None


def home_component(device):
    raw = device.shell("cmd", "package", "resolve-activity", "--brief", "-a",
                       "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    lines = [line.strip() for line in raw.splitlines()
             if re.fullmatch(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+", line.strip())]
    require(len(lines) == 1, "An unambiguous existing HOME preference is required")
    return lines[0]


def home_candidates(device):
    raw = device.shell("cmd", "package", "query-activities", "--brief", "-a",
                       "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    return sorted({line.strip() for line in raw.splitlines()
                   if re.fullmatch(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+", line.strip())})


def main_foreground(device):
    require(focused_component(device) in (APP + "/.MainActivity", APP + "/" + APP + ".MainActivity"),
            "Begin on the normal ThinkOS MainActivity, not another application or native window")
    activities = device.shell("dumpsys", "activity", "activities")
    require(any(APP + "/" in line and "MainActivity" in line for line in activities.splitlines()
                if "mResumedActivity" in line or "ResumedActivity:" in line),
            "ThinkOS MainActivity is not the actual resumed activity")


def idle(device):
    ui, stage = broadcast_json(device, "ui_state"), broadcast_json(device, "stage_state")
    require(ui.get("page") and not ui.get("safeMode") and not ui.get("onboardingOpen"),
            "Preserve an existing welcome/recovery session")
    require(not any(stage.get(key) for key in ("canGoBack", "pageBack", "panelOpen", "touching", "practice")),
            "An idle base page with empty navigation and no panel/practice is required")
    require(not any(broadcast_json(device, command).get("showing") for command in ("update_state", "legal_state")),
            "Preserve an existing native legal/update window")
    media = broadcast_json(device, "media_state")
    require(not media.get("owner") and not media.get("kind") and not media.get("count") and media.get("status") == "stopped",
            "Preserve an existing media/cast session")
    require(not any(app_exists(device, path) for path in PENDING), "Preserve an existing application/module update transaction")
    sockets = device.shell("cat", "/proc/net/unix")
    require(not re.search(r"\s@thinkos-updater\s*$", sockets, re.M),
            "This unavailable-updater fixture requires no optional updater socket")
    return ui


def contained(bounds, outer):
    return len(bounds) == 4 and len(outer) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1] and \
        outer[0] <= bounds[0] < bounds[2] <= outer[2] and outer[1] <= bounds[1] < bounds[3] <= outer[3]


class Driver:
    def __init__(self, device, guard, output, ui, main_sha, test_sha, jar):
        self.device, self.guard, self.output, self.jar = device, guard, output, jar
        self.token = uuid.uuid4().hex
        self.base = "cache/update-device-" + self.token
        self.area = self.base + "/components/"
        require(not app_exists(device, self.base), "Refusing to overwrite another component fixture")
        self.private = Path(tempfile.mkdtemp(prefix="thinkos-component-targets-"))
        os.chmod(self.private, 0o700)
        self.state = {"version": 1, "token": self.token, "serial": device.serial, "created": time.time(),
                      "stage": "captured", "ui": ui, "apk_sha256": main_sha, "test_apk_sha256": test_sha,
                      "home": home_component(device), "launchers": home_candidates(device), "files": {}, "actions": []}
        for path in IMMUTABLE:
            guard.check()
            raw = read_app_file(device.serial, path)
            require(raw is None or len(raw) <= MAX_PRIVATE, "Immutable snapshot exceeds its fixed bound")
            self.state["files"][path] = None if raw is None else {"base64": base64.b64encode(raw).decode("ascii"), "sha256": digest(raw)}
        self.active = None
        self.generation = 0
        self.hierarchy = None
        self.ready = None
        self.changed = False
        self.completed = False
        self.expected_ui = None
        self.result = {"complete": False, "run_id": self.token, "apk_sha256": main_sha, "test_apk_sha256": test_sha,
                       "checks": [], "orientations": {}, "private_recovery_journal": str(self.private),
                       "scope": "Real signed seven-target catalog CHECK, native Chinese window, scroll/rotation and accessibility negative check; metadata only"}
        self.save_state()
        self.immutable()
        self.save()
        print("Private component journal: " + str(self.private), flush=True)

    def save_state(self):
        private_json(self.private / "state.json", self.state)

    def save(self):
        private_json(self.output / "results.json", self.result)

    def record(self, action, **values):
        self.state["actions"].append({"at": time.time(), "action": action, **values})
        self.save_state()

    def check(self, name, passed):
        self.result["checks"].append({"name": name, "passed": bool(passed)})
        self.save()
        print(("PASS " if passed else "FAIL ") + name, flush=True)
        require(passed, name)

    def immutable(self):
        for path, item in self.state["files"].items():
            raw = None if item is None else base64.b64decode(item["base64"], validate=True)
            require(digest(raw) == (item["sha256"] if item else None), "Invalid private immutable snapshot")
            require(read_app_file(self.device.serial, path) == raw,
                    "An immutable local/welcome/cast/management file changed; preserve the journal")

    def identity(self):
        require(installed_sha(self.device, APP) == self.state["apk_sha256"] and
                installed_sha(self.device, TEST_APP) == self.state["test_apk_sha256"],
                "The fixed installed APK bytes changed; this driver never installs")

    def guarded(self, controlled=False):
        self.guard.check(focused=not controlled)
        if controlled:
            component = focused_component(self.device)
            require(component is not None and (component.startswith(APP + "/") or component in self.state["launchers"]),
                    "Unexpected foreground in the controlled start/end window; do not reclaim it")
        self.guard.watch.check()

    def wait(self, predicate, seconds=30, controlled=False, allow_exit=False):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            self.guarded(controlled)
            value = predicate()
            if value:
                return value
            if not allow_exit and self.active is not None and self.active.poll() is not None:
                raise ProbeError("Component instrumentation exited before its requested finish")
            time.sleep(.2)
        raise ProbeError("The bounded component fixture wait expired")

    def controlled_main(self, reason):
        # Only our known instrumentation start/end can temporarily uncover HOME.
        # This helper is never used for an unexpected mid-test focus loss.
        self.guarded(controlled=True)
        if not foreground(self.device):
            require(focused_component(self.device) in self.state["launchers"], "Only the uncovered HOME may be reopened")
            self.record("controlled-main", reason=reason)
            self.device.shell("am", "start", "-W", "-n", APP + "/.MainActivity")
        self.wait(lambda: foreground(self.device), controlled=True, allow_exit=True)
        self.guard.check()

    def start(self):
        self.state["stage"] = "instrumentation-starting"
        self.save_state()
        self.changed = True  # Instrumentation replaces the process and its debug holds.
        self.device.start_log(clear=False)
        log = self.private / "instrumentation.log"
        with os.fdopen(os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            self.active = subprocess.Popen(self.device.base + ["shell", "-T", "am", "instrument", "-w", "-e",
                "run_id", self.token, "-e", "phase", "components_ui", RUNNER], stdout=stream, stderr=subprocess.STDOUT)
        self.ready = self.wait(lambda: read_json(self.device, self.area + "ready.json"), seconds=90, controlled=True)
        require(self.ready.get("run_id") == self.token and type(self.ready.get("pid")) is int and
                str(self.ready["pid"]) in self.device.shell("pidof", APP).split(), "Ready does not belong to this actual process/run")
        self.controlled_main("instrumentation-start")
        self.expected_ui = broadcast_json(self.device, "ui_state")
        require(self.expected_ui.get("locale", "").startswith("zh"), "The actual probe process did not retain Chinese")
        self.verify_status(self.ready)
        self.state["fixture_pid"] = self.ready["pid"]
        self.state["stage"] = "fixture-running"
        self.save_state()
        self.hierarchy = Hierarchy(self.device, self.guard, self.token, self.jar)
        self.override("theme", "theme", "DARK")
        self.override("hold_attention", "attention", "ENGAGED")
        self.check("actual production-UID native dialog has seven authenticated targets with unavailable updater", True)

    def override(self, command, key, value):
        self.guard.check()
        self.record("override", key=key, value=value)
        if command == "orientation":
            self.device.shell("am", "broadcast", "-n", APP + "/.DebugReceiver", "--es", "command", command, "--ei", "value", value)
        else:
            self.device.debug(command, value=value)
        self.expected_ui[key] = value
        self.state["expected_ui"] = {key: self.expected_ui.get(key) for key in UI_KEYS}
        self.save_state()

    def control(self, action):
        require(action in ("checkpoint", "accessibility_click", "finish"), "Unknown fixed component control")
        finishing = action == "finish"
        self.guarded(controlled=finishing)
        self.generation += 1
        raw = json.dumps({"generation": self.generation, "action": action}, separators=(",", ":")).encode()
        temporary = self.area + "control.json.host-new"
        restore_app_file(self.device.serial, temporary, raw)
        self.device.shell("run-as", APP, "mv", temporary, self.area + "control.json")
        def response():
            value = read_json(self.device, self.area + "response.json")
            return value if value and value.get("generation") == self.generation else None
        result = self.wait(response, controlled=finishing, allow_exit=finishing)
        require(result.get("handled") is True and not result.get("error"), "The fixed component control failed")
        return result

    def checkpoint(self):
        self.guard.check()
        require(str(self.ready["pid"]) in self.device.shell("pidof", APP).split(), "The fixture process changed during native UI observation")
        value = self.control("checkpoint").get("checkpoint")
        require(isinstance(value, dict) and value.get("showing") is True and value.get("width", 0) > 0 and value.get("height", 0) > 0,
                "No actual showing native component window")
        self.verify_status(value)
        return value

    def verify_status(self, checkpoint):
        status = checkpoint.get("status", {})
        require(status.get("phase") == "available" and status.get("downloaded") == 0 and status.get("can_check") is True and
                status.get("can_download") is False and status.get("can_install") is False,
                "Metadata fixture must not gain download/install capability or fabricate READY")
        values = status.get("components", [])
        require([value.get("id") for value in values] == list(IDS), "The seven authenticated targets differ")
        require(all(value.get("state") == "requires_updater" and value.get("installed_code") is None and
                    value.get("downloaded") is None and value.get("result") is None for value in values),
                "The unavailable-updater fixture invented installed versions/progress/results")

    def observation(self, checkpoint):
        self.guard.check()
        nodes = [node for node in visible_nodes(self.hierarchy.tree()) if node.get("package") == APP]
        require(any(node.get("text") == "软件更新" for node in nodes), "The active native hierarchy is not the component update window")
        scrolls = [node_bounds(node) for node in nodes if node.get("class") == "android.widget.ScrollView"]
        require(len(scrolls) == 1, "The real native content ScrollView must be unambiguous")
        viewport = scrolls[0]
        screen = (0, 0, checkpoint["width"], checkpoint["height"])
        buttons = checkpoint.get("buttons", [])
        require({value.get("text") for value in buttons} == {"检查更新", "下载更新", "立即安装"},
                "The native checkpoint must account for all three original actions")
        for button in buttons:
            if button.get("visible"):
                require(contained(button.get("bounds", []), screen), "A visible footer action is clipped/offscreen")
                matches = [node for node in nodes if node.get("text") == button["text"]]
                require(len(matches) == 1 and node_bounds(matches[0]) == tuple(button["bounds"]) and
                        node_bounds(matches[0])[1] >= viewport[3], "A visible footer action is obscured by scroll content")
        require(next(value for value in buttons if value["text"] == "检查更新")["visible"] and
                next(value for value in buttons if value["text"] == "下载更新")["visible"] and
                not next(value for value in buttons if value["text"] == "立即安装")["visible"],
                "Record AVAILABLE-state action visibility honestly")
        seen = set()
        for component in checkpoint["status"]["components"]:
            row = next(value for value in checkpoint["rows"] if value.get("id") == component["id"])
            if not row.get("visible"):
                continue
            names = [node for node in nodes if node.get("text") == row["text"]]
            expected = "目标：%s (构建 %s)" % (component["version"], component["code"])
            targets = [node for node in nodes if node.get("text") == expected]
            if len(names) != 1 or len(targets) != 1:
                continue
            name, target = node_bounds(names[0]), node_bounds(targets[0])
            if not contained(name, viewport) or not contained(target, viewport) or target[1] < name[3]:
                continue
            installed = [node for node in nodes if node.get("text") == "已装：未知" and
                         name[3] <= node_bounds(node)[1] < target[1] and contained(node_bounds(node), viewport)]
            states = [node for node in nodes if node.get("text") == "无法自动更新" and
                      name[1] <= (node_bounds(node)[1] + node_bounds(node)[3]) / 2 <= name[3] and
                      contained(node_bounds(node), viewport)]
            if installed and states:
                seen.add(component["id"])
        return viewport, seen

    def photo(self, name, checkpoint):
        # The real dialog is fullscreen; refuse any partial/native-background capture.
        self.guard.check()
        require(checkpoint.get("showing") is True, "Only the showing owned native dialog may be captured")
        path = self.output / (name + ".png")
        self.device.screenshot(path)
        os.chmod(path, 0o600)
        raw = path.read_bytes()
        require(raw[:8] == b"\x89PNG\r\n\x1a\n" and len(raw) >= 24 and
                struct.unpack(">II", raw[16:24]) == (checkpoint["width"], checkpoint["height"]),
                "Refuse a screenshot containing pixels outside the fullscreen native fixture")
        self.guard.check()

    def scroll(self, viewport, upward=True):
        self.guard.check()
        left, top, right, bottom = viewport
        height = bottom - top
        require(height > 100, "No safe native content swipe area")
        start, end = (top + int(height * .82), top + int(height * .44))
        if not upward:
            start, end = end, start
        self.device.shell("input", "touchscreen", "swipe", (left + right) // 2, start,
                          (left + right) // 2, end, 350)
        self.guard.check()
        time.sleep(.3)

    def orientation(self, name, code):
        self.override("orientation", "orientation", code)
        def rotated():
            checkpoint = self.checkpoint()
            return checkpoint if (checkpoint["width"] > checkpoint["height"]) == (code == 0) else None
        checkpoint = self.wait(rotated, seconds=20)
        # Rotation may retain scroll; go to the actual top using real content swipes.
        for _ in range(24):
            viewport, _ = self.observation(checkpoint)
            if not checkpoint.get("can_scroll_up"):
                break
            self.scroll(viewport, upward=False)
            checkpoint = self.checkpoint()
        require(not checkpoint.get("can_scroll_up"), "Could not return the actual content to its top")
        seen, observations = set(), []
        for index in range(32):
            viewport, visible = self.observation(checkpoint)
            observations.append({"scroll_y": checkpoint["scroll_y"], "seen": sorted(visible), "buttons": checkpoint["buttons"]})
            if index == 0 or visible - seen or not checkpoint.get("can_scroll_down"):
                self.photo(name + "-" + str(index).zfill(2), checkpoint)
            seen.update(visible)
            if not checkpoint.get("can_scroll_down"):
                break
            before = checkpoint["scroll_y"]
            self.scroll(viewport)
            checkpoint = self.checkpoint()
            require(checkpoint["scroll_y"] > before, "A real native content swipe did not advance")
        self.result["orientations"][name] = {"complete": seen == set(IDS) and not checkpoint.get("can_scroll_down"),
            "seen_targets_with_unknown_installed": sorted(seen), "observations": observations,
            "width": checkpoint["width"], "height": checkpoint["height"],
            "action_scope": "Visible AVAILABLE actions checked; INSTALL hidden, DOWNLOAD disabled; no artifact/READY fixture"}
        self.check(name + " real scrolling exposes all seven signed target/unknown-installed rows and complete visible footer actions",
                   self.result["orientations"][name]["complete"])

    def accessibility(self):
        before = self.checkpoint()
        response = self.control("accessibility_click")
        require(response.get("automation_destroyed") is True, "The API27 test automation connection was not destroyed")
        after = self.checkpoint()
        self.result["accessibility"] = {"action_returned": response.get("action_returned"),
            "automation_destroyed": True, "callbacks_before": before["callback_calls"], "callbacks_after": after["callback_calls"],
            "http_unchanged": "Asserted in actual app probe check; verified again in final report"}
        self.check("ACTION_CLICK has no native button credit and releases the global automation connection",
                   before["callback_calls"] == after["callback_calls"] == 0)
        self.observation(after)  # A real shell hierarchy must work after UiAutomation.destroy.

    def finish(self):
        self.record("controlled-finish")
        self.control("finish")
        report = self.wait(lambda: read_json(self.device, self.base + "/report.json"), seconds=45, controlled=True, allow_exit=True)
        self.wait(lambda: self.active.poll() is not None, seconds=30, controlled=True, allow_exit=True)
        checks = report.get("checks", [])
        require(report.get("phase") == "components_ui" and report.get("complete") is True and checks and
                all(check.get("passed") is True for check in checks), "The actual application did not pass every component fixture check")
        require(any("ACTION_CLICK" in check.get("name", "") for check in checks) and report.get("artifact_requests") == 0 and
                report.get("apk_unchanged") is True and report.get("user_settings_unchanged") is True and
                re.fullmatch(r"[a-f0-9]{64}", report.get("authenticated_catalog_digest", "")),
                "The final app report lacks authenticated metadata-only/no-artifact/unchanged proof")
        self.verify_status({"status": report.get("last_status", {})})
        self.result["app_report"] = report
        self.completed = True
        self.state["stage"] = "fixture-finished"
        self.save_state()
        self.identity()
        self.immutable()
        self.check("actual app report confirms all checks, authenticated metadata only and no artifact requests", True)

    def restore(self):
        self.guarded(controlled=True)
        self.identity()
        self.immutable()
        require(self.active is None or self.active.poll() is not None, "Do not restore UI while instrumentation still owns a native window")
        self.controlled_main("instrumentation-end")
        current = broadcast_json(self.device, "ui_state")
        # A live fixture PID must still have exactly the holds we own. A normal
        # instrumentation-end process replacement loses only process-local holds.
        if self.ready and str(self.ready["pid"]) in self.device.shell("pidof", APP).split() and self.expected_ui:
            require(all(current.get(key) == self.expected_ui.get(key) for key in UI_KEYS),
                    "An override changed outside this driver; preserve it and the journal")
        original = self.state["ui"]
        self.record("restoration-started")
        for command, key in (("theme", "theme"), ("hold_attention", "attention"), ("lux", "lux")):
            self.guard.check()
            self.device.debug(command, **({"value": original[key]} if original.get(key) is not None else {}))
        self.guard.check()
        if original.get("presence") is None:
            self.device.debug("hold_presence")
        else:
            self.device.shell("am", "broadcast", "-n", APP + "/.DebugReceiver", "--es", "command", "hold_presence",
                              "--ez", "value", str(bool(original["presence"])).lower())
        self.device.debug("locale", value=original["locale"])
        self.guard.check()
        self.device.shell("am", "broadcast", "-n", APP + "/.DebugReceiver", "--es", "command", "orientation",
                          "--ei", "value", original["orientation"])
        def page():
            state = broadcast_json(self.device, "ui_state")
            if state.get("page") != original["page"]:
                self.device.debug("page", value=original["page"])
                return None
            return state
        self.wait(page, allow_exit=True)
        self.guard.check()
        self.device.debug("variant", value=original.get("variant", ""))
        final = self.wait(lambda: (value if all(value.get(key) == original.get(key) for key in UI_KEYS + ("page", "variant", "onboardingOpen", "safeMode")) else None)
                          if isinstance(value := broadcast_json(self.device, "ui_state"), dict) else None, allow_exit=True)
        idle(self.device)
        require(home_component(self.device) == self.state["home"], "The original HOME preference changed")
        self.guard.check()
        self.immutable()
        self.identity()
        # Only the random run directory proven absent before instrumentation,
        # and Hierarchy's exact random-token helper directory, belong to us.
        self.device.shell("run-as", APP, "rm", "-rf", self.base)
        if self.hierarchy is not None:
            self.device.shell("rm", "-rf", self.hierarchy.remote)
        self.guard.check()
        self.state["stage"] = "restored"
        self.state["restored_ui"] = final
        self.save_state()
        self.result["restoration"] = {"five_immutable_files_byte_equal": True, "original_ui_holds_orientation_page_variant": True,
            "home_preference_unchanged": True, "media_idle": True, "audio_streams_1_to_5_zero": True,
            "fixed_apks_unchanged": True, "owned_fixture_removed": not app_exists(self.device, self.base)}
        self.check("original UI/holds/orientation/HOME restored with immutable bytes, idle media and five silent streams", True)

    def close(self):
        if self.device._logcat is not None:
            # Keep raw logs private; never copy unrelated application data into screenshots/artifacts.
            path = self.private / "logcat.txt"
            with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
                stream.write("\n".join(self.device.log_since(0)) + "\n")
            self.device.stop_log()


def sha_arg(value):
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise argparse.ArgumentTypeError("Use the full lowercase fixed APK SHA-256")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default=PHYSICAL)
    parser.add_argument("--apk-sha256", type=sha_arg, required=True)
    parser.add_argument("--test-apk-sha256", type=sha_arg, required=True)
    parser.add_argument("--hierarchy-jar", type=Path, help="Existing helper jar only; defaults to source-addressed /tmp cache")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(not args.output.exists(), "Use a new output directory")
    jar = args.hierarchy_jar or cached_hierarchy()
    file_digest(jar)  # Existence check only: never compile helpers or APKs here.
    device = Device(args.serial)
    require(device.shell("getprop", "ro.serialno").strip() == PHYSICAL, "Only the authorized physical device may be used")
    require(quiet(device), "Audio streams 1..5 must already be zero; this driver never restores audible volume")
    main_foreground(device)
    require(installed_sha(device, APP) == args.apk_sha256 and installed_sha(device, TEST_APP) == args.test_apk_sha256,
            "Already-installed APKs do not match the explicit fixed hashes; install separately")
    require("instrumentation:" + RUNNER + " (target=" + APP + ")" in device.shell("pm", "list", "instrumentation").splitlines(),
            "The fixed test APK does not expose the existing update probe runner")
    watch = TouchWatch(device)
    guard = Guard(device, watch)
    driver = None
    try:
        guard.check()
        ui = idle(device)
        system_locale = device.shell("getprop", "persist.sys.locale").strip() or device.shell("getprop", "persist.sys.language").strip()
        require(ui.get("locale", "").startswith("zh") and system_locale.startswith("zh"),
                "Start in a Chinese system/process locale; this driver does not change Android's language")
        args.output.mkdir(mode=0o700, parents=True)
        os.chmod(args.output, 0o700)
        driver = Driver(device, guard, args.output, ui, args.apk_sha256, args.test_apk_sha256, jar)
        driver.start()
        driver.orientation("landscape", 0)
        driver.accessibility()
        driver.orientation("portrait", 1)
        driver.finish()
        driver.restore()
        driver.result["complete"] = True
        driver.save()
        print("Component native-window functional checks passed; no artifacts downloaded and original UI restored.", flush=True)
    except BaseException as error:
        if driver is not None:
            driver.result["complete"] = False
            driver.result["error"] = str(error)
            driver.record("stopped", error=type(error).__name__)
            # Never send another UI action after physical input, a focus loss or
            # changed volume. Other functional failures may close our own live
            # window and restore only while all ownership/foreground guards hold.
            if not isinstance(error, ExternalInput):
                try:
                    driver.guard.check()
                    if driver.active is not None and driver.active.poll() is None and driver.ready is not None:
                        driver.finish()
                    if driver.changed and (driver.active is None or driver.active.poll() is not None):
                        driver.restore()
                except BaseException as recovery_error:
                    driver.result["restoration_error"] = str(recovery_error)
                    driver.record("restoration-stopped", error=type(recovery_error).__name__)
            driver.save()
            print("Private recovery journal retained: " + str(driver.private), file=sys.stderr)
        raise
    finally:
        if driver is not None:
            driver.close()
        watch.close()


if __name__ == "__main__":
    main()
