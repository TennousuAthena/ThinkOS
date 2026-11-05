#!/usr/bin/env python3
"""Small real-UI WEL continuation check; no build, install or device reboot.

  python3 -B tools/tests/onboarding_resume_device.py --apk-sha256 HEX64 \
    --test-apk-sha256 HEX64 --output /tmp/welcome-resume --leave-open

Requires the fixed installed APK pair, existing Chinese (zh-CN) locale, idle
MainActivity, no updater or update/media session, and five silent streams.
Uses one Guard/TouchWatch and an existing cached ThinkHierarchy jar. All taps,
swipes and long presses are real InputManager touch events. The Home lesson
uses exactly one explicit root broadcast to the non-exported EdgeReturnReceiver:
this tests the real receiver path, NOT installation/loading of a system hook.

Only local settings and welcome main/bak bytes may be restored, after stopping
the owned app and checking a strict controlled-change allowlist. Server/identity/
management bytes, Android locale/timezone/Wi-Fi, HOME/DO/LockTask remain equal.
Recovery/deck/crash histories are NEVER written back. Human input, raised audio,
unexpected foreground/process replacement or unknown bytes retain the private
journal without reclaiming the screen. --leave-open runs only after restoration.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import shlex
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
                             TEST_APP, TouchWatch, app_exists, cached_hierarchy,
                             digest, file_digest, focused_component, home_candidates,
                             home_component, installed_sha, private_json, quiet, require)
from component_targets_device import main_foreground
from management_exit_cancel_device import policy_facts
from ui_walkthrough import broadcast_json

MUTABLE = ("files/settings/local.json", "files/settings/local.json.bak",
           "files/store/welcome.json", "files/store/welcome.json.bak")
IMMUTABLE = ("files/settings/server.json", "files/settings/server.json.bak",
             "files/cast/identity-seed", "files/cast/airplay-identity", "files/management-exit")
PENDING = ("files/update/pending.json", "files/update/pending.json.bak",
           "files/update/module-pending.json", "files/update/module-pending.json.bak")
HOLDS = ("theme", "attention", "presence", "lux", "locale", "orientation")
STEPS = {"hello", "language", "region", "timezone", "wifi", "privacy", "updates",
         "location", "appearance", "gestures", "welcome", "done"}
SETTING_KEYS = {"onboarding.done", "onboarding.attempts", "units.system", "update.auto_install"}
MAX_BYTES = 8 * 1024 * 1024
MAIN = {APP + "/.MainActivity", APP + "/" + APP + ".MainActivity"}


def document(raw):
    value = json.loads(raw) if raw is not None else {}
    require(isinstance(value, dict), "A private JSON document has an unknown shape")
    return value


class Driver:
    def __init__(self, device, guard, args, jar):
        self.d, self.guard, self.args, self.jar = device, guard, args, jar
        self.token = uuid.uuid4().hex
        self.private = Path(tempfile.mkdtemp(prefix="thinkos-onboarding-resume-"))
        os.chmod(self.private, 0o700)
        self.helper = None
        self.controlled = False
        self.pid = None
        self.state = {"version": 1, "kind": "onboarding-resume", "serial": device.serial,
                      "token": self.token, "stage": "capturing", "actions": [], "files": {}}
        self.result = {"complete": False, "checks": [], "apk_sha256": args.apk_sha256,
                       "test_apk_sha256": args.test_apk_sha256, "private_recovery_journal": str(self.private),
                       "scope": "Eight narrow WEL real-UI continuation checks; explicit receiver Home, no system_server hook claim",
                       "not_tested": ["cold-process continuation", "system_server hook loading", "privileged language/timezone/DO changes",
                                      "30-minute idle", "all languages/layouts", "performance"]}
        self.save()
        print("Private onboarding journal: " + str(self.private), flush=True)

    def save(self):
        private_json(self.private / "state.json", self.state)
        private_json(self.args.output / "results.json", self.result)

    def record(self, action, **values):
        self.state["actions"].append({"action": action, "at": time.time(), **values})
        self.save()

    def check(self, name, passed=True):
        self.result["checks"].append({"name": name, "passed": bool(passed)})
        self.save()
        print(("PASS " if passed else "FAIL ") + name, flush=True)
        require(passed, name)

    def guarded(self):
        self.guard.check(focused=not self.controlled)
        component = focused_component(self.d)
        allowed = MAIN | (set(self.state.get("launchers", [])) if self.controlled else set())
        if self.controlled and component is None:
            # Only our recorded force-stop/start window may have no focused
            # window briefly. Observe; never use this grace to start an app.
            deadline = time.monotonic() + 5
            while component is None and time.monotonic() < deadline:
                self.guard.check(focused=False)
                time.sleep(.2)
                component = focused_component(self.d)
        if component not in allowed:
            raise ExternalInput("Unexpected foreground; preserve the journal without reclaiming it")
        if not self.controlled and self.pid is not None:
            if self.d.shell("pidof", APP).split() != [self.pid]:
                raise ExternalInput("Unexpected application process replacement; preserve the journal")
        self.guard.watch.check()

    def wait(self, predicate, seconds=20):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            self.guarded()
            value = predicate()
            if value:
                return value
            time.sleep(.2)
        raise ProbeError("A bounded onboarding UI wait expired")

    def ui(self):
        return broadcast_json(self.d, "ui_state")

    def stage(self):
        return broadcast_json(self.d, "stage_state")

    def facts(self, label):
        self.guarded()
        policy = self.d.shell("dumpsys", "device_policy")
        activity = self.d.shell("dumpsys", "activity", "activities")
        number = len(self.state["actions"])
        for name, raw in (("policy", policy), ("activity", activity)):
            path = self.private / (str(number) + "-" + label + "-" + name + ".txt")
            with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
                stream.write(raw)
        return policy_facts(policy, activity, home_component(self.d))

    def system_facts(self):
        return {"locale": self.d.shell("getprop", "persist.sys.locale").strip(),
                "locales": self.d.shell("settings", "get", "system", "system_locales").strip(),
                "timezone": self.d.shell("getprop", "persist.sys.timezone").strip(),
                "wifi": self.d.shell("settings", "get", "global", "wifi_on").strip()}

    def read(self, path):
        raw = read_app_file(self.d.serial, path)
        require(raw is None or len(raw) <= MAX_BYTES, "A bounded private snapshot is required")
        return raw

    def original(self, path):
        item = self.state["files"][path]
        raw = None if item is None else base64.b64decode(item["base64"], validate=True)
        require(digest(raw) == (item["sha256"] if item else None), "Private snapshot checksum mismatch")
        return raw

    def immutable(self):
        for path in IMMUTABLE:
            self.guarded()
            require(self.read(path) == self.original(path), "Server/identity/management bytes changed; preserve the journal")
        require(not any(app_exists(self.d, path) for path in PENDING), "An update transaction appeared; stop")
        require(self.system_facts() == self.state["system"], "Android language/timezone/Wi-Fi changed; never restore it blindly")

    def capture(self):
        self.guarded()
        main_foreground(self.d)
        ui, stage = self.ui(), self.stage()
        require(ui.get("locale") == "zh-CN" and ui.get("page") and not ui.get("safeMode") and not ui.get("onboardingOpen"),
                "Begin at an idle Chinese base page, preserving existing onboarding/recovery")
        require(not any(stage.get(key) for key in ("canGoBack", "pageBack", "panelOpen", "practice", "touching")),
                "Begin with empty navigation, panel and practice")
        require(not any(broadcast_json(self.d, name).get("showing") for name in ("update_state", "legal_state")),
                "Preserve existing native dialogs")
        require(not re.search(r"\s@thinkos-updater\s*$", self.d.shell("cat", "/proc/net/unix"), re.M),
                "This narrow flow requires no updater; never exercise privileged setup")
        media = broadcast_json(self.d, "media_state")
        require(media.get("status") == "stopped" and not any(media.get(key) for key in ("owner", "count", "kind")),
                "Preserve any media/cast session or queue")
        pids = self.d.shell("pidof", APP).split()
        require(len(pids) == 1 and pids[0].isdigit(), "One stable application process is required")
        self.pid = pids[0]
        self.state.update(ui=ui, pid=self.pid, launchers=home_candidates(self.d), system=self.system_facts())
        for path in MUTABLE + IMMUTABLE:
            self.guarded()
            raw = self.read(path)
            self.state["files"][path] = None if raw is None else {"base64": base64.b64encode(raw).decode(), "sha256": digest(raw)}
        # No stop at capture: the main-process continuation below must be real.
        deadline = time.monotonic() + 2.3
        while time.monotonic() < deadline:
            self.guarded()
            time.sleep(.2)
        require(all(self.read(path) == self.original(path) for path in MUTABLE + IMMUTABLE),
                "Original private bytes are still changing; preserve them and retry later")
        self.state["policy"] = self.facts("baseline")
        self.state["stage"] = "captured"
        self.save()
        self.immutable()
        self.state["helper_remote"] = "/data/local/tmp/thinkos-registry-" + self.token
        self.save()
        self.helper = Hierarchy(self.d, self.guard, self.token, self.jar)
        self.d.start_log(clear=False)
        self.record("hold-attention", value="ENGAGED")
        self.d.debug("hold_attention", value="ENGAGED")
        self.check("1 fixed APK pair, idle Chinese UI, five silent streams and stable private snapshots", self.ui().get("attention") == "ENGAGED")

    def tree(self):
        self.guarded()
        require(self.helper is not None, "Use the existing cached hierarchy helper")
        return list(visible_nodes(self.helper.tree()))

    def tap(self, label):
        for _ in range(4):
            nodes = [node for node in self.tree() if node.get("package") == APP and
                     (node.get("text") == label or node.get("content-desc") == label)]
            if nodes:
                bounds = node_bounds(nodes[-1])
                width, height = self.geometry()
                require(0 <= bounds[0] < bounds[2] <= width and 0 <= bounds[1] < bounds[3] <= height,
                        "A native touch target is clipped")
                self.guarded()
                self.record("touch", label=label)
                self.d.tap((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)
                time.sleep(.35)
                return
            time.sleep(.25)
        raise ProbeError("Missing expected Chinese native control: " + label)

    def geometry(self):
        value = self.stage()
        require(value.get("width", 0) > 0 and value.get("height", 0) > 0, "No real stage geometry")
        return value["width"], value["height"]

    def swipe(self, x, y, end_x, end_y, duration=400):
        self.guarded()
        self.record("touch-swipe", duration_ms=duration)
        self.d.shell("input", "swipe", int(x), int(y), int(end_x), int(end_y), str(duration))
        time.sleep(.3)

    def back(self):
        self.guarded()
        self.record("back")
        self.d.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(.3)

    def receiver_home(self):
        self.guarded()
        self.record("explicit-root-receiver-home", system_server_hook_claim=False)
        command = shlex.join(["am", "broadcast", "--receiver-foreground", "--include-stopped-packages", "-n",
                              APP + "/moe.kiwi.thinkos.shell.EdgeReturnReceiver"])
        reply = self.d.shell("su", "-c", command)
        require("Broadcast completed" in reply and "Permission Denial" not in reply and "Error" not in reply,
                "The explicit authorized receiver broadcast did not complete")

    def photo(self, name):
        self.guarded()
        path = self.args.output / (name + ".png")
        self.d.screenshot(path)
        os.chmod(path, 0o600)

    def opened(self, page=None):
        value = self.ui()
        return value.get("onboardingOpen") is True and (page is None or value.get("page") == "welcome." + page)

    def practice(self, name):
        return self.wait(lambda: self.opened("gestures") and self.stage().get("practice") == name)

    def run(self):
        self.record("restart-via-official-debug-entry")
        self.d.debug("onboarding_open", value="restart")
        self.wait(lambda: self.opened("hello"))
        self.photo("hello")
        width, height = self.geometry()
        self.guarded()
        self.d.tap(int(width * .12), int(height * .28))  # Outside header/footer actions.
        self.wait(lambda: self.opened("setup"))
        self.tap("简体中文")  # Exact preflight zh-CN: production handler does not setLocale.
        self.check("2 real greeting tap enters language and current Chinese advances within onboarding", self.opened("setup"))
        self.tap("继续")  # Recommended current zh-CN region; no picker/timezone mutation.
        # No Wi-Fi toggle or Android settings: skip only when this actual screen exists.
        labels = {node.get("text") for node in self.tree() if node.get("package") == APP}
        if "Wi-Fi" in labels:
            self.tap("跳过")
        require(any(node.get("text") == "不收集任何数据" for node in self.tree()), "Region did not reach real privacy")
        self.tap("继续")
        self.tap("只自动下载")
        self.tap("跳过")  # Location; never invoke city search or Android date page.
        self.tap("继续")  # Appearance; do not change any appearance field.
        self.practice("swipe")
        self.check("3 recommended region, privacy, update choice and unchanged appearance reach real practice")
        width, height = self.geometry()
        self.swipe(width * .22, height * .5, width * .78, height * .5)
        self.practice("variant")
        self.swipe(width * .5, height * .72, width * .5, height * .28)
        self.practice("panel")
        self.swipe(width * .5, 2, width * .5, height * .7)
        self.practice("back")
        require(not self.stage().get("panelOpen"), "Panel practice opened the real panel")
        self.swipe(2, height * .5, width * .7, height * .5)
        self.practice("home")
        self.check("4 real horizontal, vertical, top and left-edge gestures advance without leaving the guide")
        self.receiver_home()
        self.wait(lambda: not self.ui().get("onboardingOpen"))
        self.check("5 explicit non-exported receiver Home exits without replacing the original process",
                   self.d.shell("pidof", APP).split() == [self.pid])
        self.guarded()
        self.d.debug("settings", value="system")  # Navigation only; continuation row is touched below.
        self.wait(lambda: self.ui().get("page") == "settings.form")
        self.tap("完成设置")
        self.practice("long-press")
        self.photo("continued-long-press")
        self.check("6 real Complete setup touch resumes the next long-press lesson in the same process",
                   self.d.shell("pidof", APP).split() == [self.pid])
        width, height = self.geometry()
        self.swipe(width * .5, height * .5, width * .5, height * .5, 1100)
        self.wait(lambda: self.opened("gestures") and self.stage().get("practice") is None)
        self.tap("继续")
        self.tap("开始使用")
        self.wait(lambda: not self.ui().get("onboardingOpen"))
        self.check("7 real long press and final Start finish without opening settings/maintenance")
        self.guarded()
        self.d.debug("onboarding_open", value="restart")
        self.wait(lambda: self.opened("hello"))
        self.back()
        self.wait(lambda: not self.ui().get("onboardingOpen"))
        self.d.debug("settings", value="system")
        self.wait(lambda: self.ui().get("page") == "settings.form")
        self.tap("完成设置")
        self.wait(lambda: self.opened("hello"))
        width, height = self.geometry()
        self.guarded()
        self.d.tap(int(width * .12), int(height * .28))
        self.wait(lambda: self.opened("setup"))
        require(any(node.get("text") == "简体中文" for node in self.tree()), "Reopened greeting tap did not reach language")
        self.check("8 cancel/reopen preserves the guide and a greeting tap reaches language again")
        self.immutable()
        require(self.facts("flow-end") == self.state["policy"], "HOME/DO/self-admin/LockTask changed")
        self.result["receiver_path_only"] = True
        self.state["stage"] = "flow-complete"
        self.save()

    def controlled_changes(self):
        """Reject unrelated local/welcome changes, including unknown backup contents."""
        originals = {path: document(self.original(path)) for path in MUTABLE}
        def without_settings(value):
            value = dict(value)
            system = dict(value.get("system", {}))
            for key in SETTING_KEYS:
                system.pop(key, None)
            if system:
                value["system"] = system
            else:
                value.pop("system", None)
            return value
        local_bases = [without_settings(originals[path]) for path in MUTABLE[:2]]
        welcome_bases = [{key: value for key, value in originals[path].items() if key != "progress"} for path in MUTABLE[2:]]
        current = {}
        for path in MUTABLE:
            raw = self.read(path)
            current[path] = raw
            if raw is None:
                require(path.endswith(".bak") or self.original(path) is None,
                        "A private main file disappeared unexpectedly; preserve the journal")
                continue
            if raw == self.original(path):
                continue
            value = document(raw)
            if "settings/" in path:
                require(without_settings(value) in local_bases, "Unrelated local setting changed; preserve it")
                changed = value.get("system", {})
                for key in SETTING_KEYS:
                    if key not in changed:
                        continue
                    allowed = {"onboarding.done": (0, 1), "onboarding.attempts": (0,),
                               "units.system": ("metric",), "update.auto_install": (False,)}[key]
                    old = [originals[base].get("system", {}).get(key) for base in MUTABLE[:2]]
                    require(any(type(changed[key]) is type(item) and changed[key] == item for item in list(allowed) + old),
                            "Unknown value appeared in a controlled setting")
            else:
                require({key: item for key, item in value.items() if key != "progress"} in welcome_bases,
                        "Unrelated welcome store field changed; preserve it")
                progress = value.get("progress")
                require(isinstance(progress, dict) and set(progress) <= {"schema", "step", "skipped", "gesture", "resumeOnly", "started", "region"}
                        and progress.get("schema") == 1 and progress.get("step") in STEPS
                        and type(progress.get("gesture")) is int and 1 <= progress["gesture"] <= 7
                        and type(progress.get("started")) is bool and type(progress.get("resumeOnly")) is bool
                        and progress.get("region") in (None, "CN")
                        and isinstance(progress.get("skipped"), dict)
                        and all(key in STEPS and type(item) is bool for key, item in progress["skipped"].items()),
                        "Unknown welcome progress changed; preserve the journal")
        return current

    def cleanup_helper(self):
        if self.helper is None:
            return
        self.guarded()
        remote = self.helper.remote
        require(not self.d.shell("find", remote, "-type", "l").strip(), "A helper symlink appeared; preserve it")
        children = set(self.d.shell("ls", "-A", remote).splitlines())
        require(children in ({"hierarchy.jar"}, {"hierarchy.jar", "oat"}), "Unknown helper contents; preserve them")
        directories, files = {remote}, {remote + "/hierarchy.jar"}
        if "oat" in children:
            require(set(self.d.shell("ls", "-A", remote + "/oat").splitlines()) == {"arm"} and
                    set(self.d.shell("ls", "-A", remote + "/oat/arm").splitlines()) == {"hierarchy.odex", "hierarchy.vdex"},
                    "Unknown Android helper oat layout")
            directories.update((remote + "/oat", remote + "/oat/arm"))
            files.update((remote + "/oat/arm/hierarchy.odex", remote + "/oat/arm/hierarchy.vdex"))
        require(set(self.d.shell("find", remote, "-type", "d").splitlines()) == directories and
                set(self.d.shell("find", remote, "-type", "f").splitlines()) == files, "Helper path types changed")
        require(self.d.shell("sha256sum", remote + "/hierarchy.jar").split()[0] == file_digest(self.jar),
                "The owned hierarchy helper bytes changed; preserve them")
        self.guarded()
        self.d.shell("rm", "-rf", remote)
        self.helper = None

    def restore(self):
        self.guarded()
        self.immutable()
        require(self.facts("restore-preflight") == self.state["policy"], "Actual device management changed")
        ui = self.ui()
        require(ui.get("page") in {self.state["ui"]["page"], "home.main", "settings.form", "welcome.hello", "welcome.setup", "welcome.gestures"}
                and ui.get("locale") == "zh-CN" and not ui.get("safeMode"), "Unknown navigation/recovery state; do not overwrite it")
        for key in HOLDS:
            require(ui.get(key) in ([self.state["ui"].get(key), "ENGAGED"] if key == "attention" else [self.state["ui"].get(key)]),
                    "An unowned hold changed; preserve it")
        self.controlled_changes()
        self.record("controlled-restore-stop")
        self.state["controlled_foreground"] = "restore-stop"
        self.save()
        self.controlled = True
        self.d.shell("am", "force-stop", APP)
        self.wait(lambda: not self.d.shell("pidof", APP, check=False).strip(), seconds=10)
        self.immutable()
        current = self.controlled_changes()
        # Freeze the accepted bytes while stopped, then write only our four files.
        for path in MUTABLE:
            self.guarded()
            require(self.read(path) == current[path], "Private bytes changed after stopping; never overwrite them")
        for path in MUTABLE:
            self.guarded()
            restore_app_file(self.d.serial, path, self.original(path))
        require(all(self.read(path) == self.original(path) for path in MUTABLE), "Restored local/welcome bytes differ")
        # The explicit Home also cancels first-catalog auto-prompting in this new
        # process; do not briefly enter welcome and rewrite its restored store.
        self.pid = None
        self.receiver_home()
        self.wait(lambda: focused_component(self.d) in MAIN and bool(self.ui().get("page")), seconds=30)
        pids = self.d.shell("pidof", APP).split()
        require(len(pids) == 1, "Restored runtime did not return as one process")
        self.pid = pids[0]
        self.controlled = False
        self.state["controlled_foreground"] = None
        self.save()
        original = self.state["ui"]
        for command, key in (("theme", "theme"), ("hold_attention", "attention"), ("lux", "lux")):
            self.guarded()
            value = original.get(key)
            self.d.debug(command, **({"value": value} if value is not None else {}))
        self.guarded()
        if original.get("presence") is None:
            self.d.debug("hold_presence")
        else:
            self.d.shell("am", "broadcast", "-n", APP + "/.DebugReceiver", "--es", "command", "hold_presence", "--ez", "value",
                         "true" if original["presence"] else "false")
        self.d.shell("am", "broadcast", "-n", APP + "/.DebugReceiver", "--es", "command", "orientation", "--ei", "value", original["orientation"])
        self.guarded()
        self.d.debug("page", value=original["page"])
        self.wait(lambda: self.ui().get("page") == original["page"])
        self.d.debug("variant", value=original.get("variant", ""))
        self.wait(lambda: all(self.ui().get(key) == original.get(key) for key in HOLDS + ("page", "variant", "safeMode", "onboardingOpen")))
        self.immutable()
        require(all(self.read(path) == self.original(path) for path in MUTABLE), "Restored application rewrote welcome/local bytes; retain journal")
        require(self.facts("restored") == self.state["policy"], "HOME/DO/self-admin/LockTask differs after restoration")
        require(installed_sha(self.d, APP) == self.args.apk_sha256 and installed_sha(self.d, TEST_APP) == self.args.test_apk_sha256,
                "Installed APK bytes changed")
        media = broadcast_json(self.d, "media_state")
        require(media.get("status") == "stopped" and not any(media.get(key) for key in ("owner", "kind", "count")), "Media is no longer idle")
        self.cleanup_helper()
        self.state["stage"] = "restored"
        self.result["restoration"] = {"local_welcome_main_bak_equal": True, "server_identity_management_equal": True,
            "home_do_selfadmin_locktask_equal": True, "system_locale_timezone_wifi_equal": True,
            "original_page_variant_holds_orientation": True, "media_idle_and_five_silent_streams": True,
            "recovery_deck_crash_histories_restored": False}
        self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default=PHYSICAL)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--test-apk-sha256", required=True)
    parser.add_argument("--hierarchy-jar", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--leave-open", action="store_true")
    args = parser.parse_args()
    require(args.serial == PHYSICAL and all(re.fullmatch(r"[a-f0-9]{64}", value) for value in (args.apk_sha256, args.test_apk_sha256)),
            "Explicit full SHA-256 pins and the authorized physical device are required")
    require(not args.output.exists(), "Use a fresh output directory")
    jar = args.hierarchy_jar or cached_hierarchy()
    file_digest(jar)  # Never compile or fetch a helper.
    args.output.mkdir(mode=0o700, parents=True)
    os.chmod(args.output, 0o700)
    device = Device(args.serial)
    require(device.shell("getprop", "ro.serialno").strip() == PHYSICAL and quiet(device), "Physical device must already have five zero audio streams")
    main_foreground(device)
    require(installed_sha(device, APP) == args.apk_sha256 and installed_sha(device, TEST_APP) == args.test_apk_sha256,
            "The installed APK pair differs from the explicit pins; never install here")
    watch = TouchWatch(device)
    guard = Guard(device, watch)
    driver = Driver(device, guard, args, jar)
    try:
        driver.capture()
        driver.run()
        driver.restore()
        if args.leave_open:
            driver.guarded()
            driver.record("owner-requested-leave-open-after-restoration")
            device.debug("onboarding_open", value="restart")
            driver.wait(lambda: driver.opened("hello"))
            driver.photo("left-open-for-owner")
            driver.result["left_open_for_owner"] = True
            driver.result["restoration"]["local_welcome_equal_before_explicit_leave_open"] = True
            driver.state["stage"] = "left-open-for-owner"
        driver.result["complete"] = True
        driver.save()
        return 0
    except BaseException as error:
        driver.result["error"] = str(error) if isinstance(error, ProbeError) else type(error).__name__
        driver.save()
        if not isinstance(error, (ExternalInput, KeyboardInterrupt)) and driver.state.get("stage") not in ("capturing", "restored", "left-open-for-owner"):
            try:
                driver.guarded()
                driver.restore()
            except BaseException as restore_error:
                driver.result["restoration_error"] = str(restore_error) if isinstance(restore_error, ProbeError) else type(restore_error).__name__
        driver.save()
        print("Onboarding check stopped; preserve private journal: " + str(driver.private), file=sys.stderr)
        return 2
    finally:
        if device._logcat is not None:
            path = driver.private / "logcat.txt"
            with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
                stream.write("\n".join(device.log_since(0)) + "\n")
        device.stop_log()
        watch.close()


if __name__ == "__main__":
    sys.exit(main())
