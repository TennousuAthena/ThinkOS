#!/usr/bin/env python3
"""Fixed-0.1.9 management-removal CANCEL/Back device checks; never approve.

  python3 -B tools/tests/management_exit_cancel_device.py --apk APP.apk \
    --test-apk TEST.apk --output /tmp/management-cancel-019
  python3 -B tools/tests/management_exit_cancel_device.py --restore JOURNAL \
    --output /tmp/management-cancel-recovery

Both APKs must already match the pinned bytes. The existing cached hierarchy
jar is read-only; no helper/APK is compiled or installed. Only navigation-only
debug maintenance, the exact maintenance list entry, real negative-button
touch and Back are used. There is NO positive-button click/action, owner API,
Device Owner change, reboot, process restart, root RPC or volume write.

Source review: MainActivity.confirmManagementExit constructs/shows the dialog;
kiosk.surrender is only inside NativeTouchConfirmation's accepted callback.
This driver never calls or exercises that callback, even as a negative test.
Portrait tests touchscreen Cancel; landscape tests Back. Positive-button
bounds/text are observed only, and are an excluded touch region.

Original UI/holds/variant/orientation, HOME/DO/self-admin/LockTask and five
immutable files are preserved. Raw policy, UI and uncropped captures stay in a
0700/0600 private journal; public screenshots contain only the native dialog.
Human input, unexpected focus, process replacement or nonzero audio stops
without taking the screen back. Recovery is explicit and validates ownership;
unknown new bytes are never overwritten. No settings.form dependency.
"""
import argparse
import base64
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import uuid

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools/tests")]
from PIL import Image
from device_app_files import read_app_file
from device_hierarchy import node_bounds, visible_nodes
from media_device import APP, Device
from registry_device import (ExternalInput, Guard, Hierarchy, PHYSICAL, ProbeError,
                             TEST_APP, TouchWatch, app_exists, cached_hierarchy,
                             digest, file_digest, home_component, installed_sha,
                             private_json, quiet, require)
from component_targets_device import main_foreground
from ui_walkthrough import broadcast_json

APK_SHA = "74cc263027c07d85a7b41ad0bddba23a5899e324b7d91576bcf1c761af619f41"
TEST_SHA = "9dc76dfbec4666f737ef951cb81f6437c3d1d6346fa29301214eb96bf2f4583d"
ADMIN = APP + "/" + APP + ".admin.ThinkOSAdminReceiver"
TITLE, BODY = "移除设备管理", "退出锁定与自动恢复，设置和插件保留"
CANCEL, POSITIVE = "取消", "移除管理"
IMMUTABLE = ("files/settings/local.json", "files/store/welcome.json",
             "files/cast/identity-seed", "files/cast/airplay-identity", "files/management-exit")
PENDING = ("files/update/pending.json", "files/update/pending.json.bak",
           "files/update/module-pending.json", "files/update/module-pending.json.bak")
UI_KEYS = ("theme", "attention", "presence", "lux", "locale", "orientation")
MAX_PRIVATE = 8 * 1024 * 1024


def canonical_component(value):
    package, component = value.split("/", 1)
    return package + "/" + (package + component if component.startswith(".") else component)


def policy_facts(policy, activities, home):
    """Parse actual API27 dumps, refusing unknown formats rather than assuming DO.

    The Enabled Device Admins section is independent of the Device Owner block;
    finding the owner name anywhere in the dump is not proof of active admin.
    """
    require(len(policy.encode()) <= MAX_PRIVATE and len(activities.encode()) <= MAX_PRIVATE,
            "A bounded policy/activity observation is required")
    lines = policy.splitlines()
    owners, admins, admin_section, saw_admins = [], set(), None, False
    for index, line in enumerate(lines):
        if line.strip() == "Device Owner:":
            indent = len(line) - len(line.lstrip())
            section = []
            for child in lines[index + 1:]:
                if child.strip() and len(child) - len(child.lstrip()) <= indent:
                    break
                section.append(child)
            matches = re.findall(r"admin=ComponentInfo\{([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\}", "\n".join(section))
            require(len(matches) == 1, "Unknown Device Owner dump format")
            owners.append(canonical_component(matches[0]))
        if re.match(r"\s*Enabled Device Admins \(User \d+,.*\):\s*$", line):
            admin_section = len(line) - len(line.lstrip())
            saw_admins = True
            continue
        if admin_section is not None:
            if line.strip() and len(line) - len(line.lstrip()) <= admin_section:
                admin_section = None
            else:
                match = re.fullmatch(r"\s+([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+):\s*", line)
                if match:
                    admins.add(canonical_component(match[1]))
    require(len(owners) == 1 and saw_admins, "An actual owner and active-admin list must be readable")
    modes = re.findall(r"\bmLockTaskModeState=(NONE|LOCKED|PINNED)\b", activities)
    require(len(modes) == 1, "Unknown real LockTask mode")
    package_header = re.search(r"(?m)^[ \t]*mLockTaskModeState=[^\r\n]*mLockTaskPackages \(userId:packages\)=[ \t]*$", activities)
    require(package_header is not None, "Unknown real LockTask package dump")
    packages, users, ended = [], set(), False
    for line in activities[package_header.end():].splitlines():
        if not line.strip():
            continue
        if re.fullmatch(r"\s*mLockTaskModeTasks\[.*\]\s*", line):
            ended = True
            break
        match = re.fullmatch(r"\s*(\d+):\[([A-Za-z0-9_., ]*)\]\s*", line)
        require(match is not None, "Unknown entry in the real LockTask package list")
        user = int(match[1])
        require(user <= 2147483647 and user not in users, "Unknown/duplicate LockTask user")
        users.add(user)
        names = [value.strip() for value in match[2].split(",")] if match[2] else []
        require(len(names) == len(set(names)) and all(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*", name) for name in names),
                "Unknown/duplicate package in the real LockTask whitelist")
        packages.append([user, sorted(names)])
    require(ended, "The real LockTask whitelist has no recognized end marker")
    return {"owner": owners[0], "self_admin": ADMIN in admins, "lock_mode": modes[0],
            "lock_packages": sorted(packages), "home": home}


def contained(bounds, outer):
    return len(bounds) == 4 and len(outer) == 4 and outer[0] <= bounds[0] < bounds[2] <= outer[2] and \
        outer[1] <= bounds[1] < bounds[3] <= outer[3]


def idle(device):
    main_foreground(device)
    ui, stage = broadcast_json(device, "ui_state"), broadcast_json(device, "stage_state")
    require(ui.get("page") and not ui.get("onboardingOpen") and not ui.get("safeMode"), "Preserve welcome/recovery state")
    require(not any(stage.get(key) for key in ("canGoBack", "pageBack", "panelOpen", "touching", "practice")),
            "An idle base page with empty navigation is required")
    require(not any(broadcast_json(device, command).get("showing") for command in ("update_state", "legal_state")),
            "Preserve an existing native legal/update window")
    media = broadcast_json(device, "media_state")
    require(media.get("status") == "stopped" and not any(media.get(key) for key in ("owner", "kind", "count")),
            "Preserve an active media/cast session or queue")
    require(not any(app_exists(device, path) for path in PENDING), "Preserve an existing APK/module update transaction")
    kiosk = broadcast_json(device, "kiosk_state")
    require(kiosk.get("resumed") is True and not kiosk.get("foreign") and not kiosk.get("recoverable"),
            "Begin in the normal owned foreground task")
    return ui


class Driver:
    def __init__(self, device, guard, output, jar, private, state):
        self.device, self.guard, self.output, self.jar = device, guard, output, jar
        self.private, self.state = private, state
        self.hierarchy = None
        self.token = state["token"]
        self.remote = "/data/local/tmp/thinkos-registry-" + self.token
        self.observations = state.get("observations", 0)
        self.result = {"complete": False, "apk_sha256": APK_SHA, "test_apk_sha256": TEST_SHA,
            "checks": [], "cases": [], "private_recovery_journal": str(private),
            "scope": "Native management-removal confirmation rendering, touchscreen cancellation and Back only; positive removal remains unexecuted"}

    @classmethod
    def capture(cls, device, guard, output, jar):
        guard.check()
        ui = idle(device)
        require(ui.get("locale", "").startswith("zh"), "Start in the existing Chinese process locale")
        pids = device.shell("pidof", APP).split()
        require(len(pids) == 1 and pids[0].isdigit(), "One stable application process is required")
        private = Path(tempfile.mkdtemp(prefix="thinkos-management-cancel-"))
        os.chmod(private, 0o700)
        state = {"version": 1, "kind": "management-cancel-019", "serial": device.serial, "token": uuid.uuid4().hex,
            "stage": "capturing", "ui": ui, "expected_ui": dict(ui), "pid": int(pids[0]), "files": {}, "actions": [],
            "own_window": None, "remote_intent": False, "pending_override": None}
        driver = cls(device, guard, output, jar, private, state)
        driver.save_state()
        print("Private management-cancel journal: " + str(private), flush=True)
        for path in IMMUTABLE:
            driver.guarded()
            raw = read_app_file(device.serial, path)
            require(raw is None or len(raw) <= MAX_PRIVATE, "Immutable snapshot exceeds its fixed bound")
            state["files"][path] = None if raw is None else {"base64": base64.b64encode(raw).decode("ascii"), "sha256": digest(raw)}
            driver.save_state()
        state["policy"] = driver.policy("baseline")
        require(state["policy"]["owner"] == ADMIN and state["policy"]["self_admin"] is True,
                "This cancellation test preserves an existing ThinkOS Device Owner and active self-admin")
        state["stage"] = "captured"
        driver.save_state()
        driver.identity()
        driver.immutable()
        return driver

    @classmethod
    def load(cls, device, guard, output, jar, private):
        require(private.is_dir() and not private.is_symlink() and private.stat().st_uid == os.getuid() and
                private.stat().st_mode & 0o077 == 0, "Recovery requires this user's private 0700 journal")
        path = private / "state.json"
        require(path.is_file() and not path.is_symlink() and path.stat().st_uid == os.getuid() and
                path.stat().st_mode & 0o077 == 0 and path.stat().st_size <= 64 * 1024 * 1024, "Invalid private recovery state")
        state = json.loads(path.read_text())
        require(state.get("version") == 1 and state.get("kind") == "management-cancel-019" and
                state.get("serial") == device.serial and re.fullmatch(r"[a-f0-9]{32}", state.get("token", "")) and
                type(state.get("pid")) is int and state["pid"] > 0 and set(state.get("files", {})) == set(IMMUTABLE) and
                state.get("policy", {}).get("owner") == ADMIN and state["policy"].get("self_admin") is True and
                state.get("own_window") in (None, "menu", "confirmation") and isinstance(state.get("ui"), dict) and
                isinstance(state.get("expected_ui"), dict), "Recovery journal does not describe this fixed cancellation run")
        require(re.fullmatch(r"[a-z][a-z0-9_-]*\.[A-Za-z0-9_.-]+", state["ui"].get("page", "")) and
                type(state["ui"].get("orientation")) is int and not state["ui"].get("safeMode") and
                not state["ui"].get("onboardingOpen"), "Invalid original base page")
        pending = state.get("pending_override")
        require(pending is None or isinstance(pending, dict) and pending.get("key") in ("attention", "orientation"),
                "Unknown pending reversible override")
        driver = cls(device, guard, output, jar, private, state)
        driver.identity()
        driver.immutable()
        driver.guarded()
        driver.same_policy("recovery-preflight")
        return driver

    def save_state(self):
        private_json(self.private / "state.json", self.state)

    def save(self):
        private_json(self.output / "results.json", self.result)

    def record(self, action, **values):
        self.state["actions"].append({"action": action, "at": time.time(), **values})
        self.save_state()

    def check(self, name, value):
        self.result["checks"].append({"name": name, "passed": bool(value)})
        self.save()
        print(("PASS " if value else "FAIL ") + name, flush=True)
        require(value, name)

    def guarded(self):
        self.guard.check()
        require(self.device.shell("pidof", APP).split() == [str(self.state["pid"])],
                "The application process changed; stop without restarting or taking foreground")
        self.guard.watch.check()

    def identity(self):
        self.guarded()
        require(installed_sha(self.device, APP) == APK_SHA and installed_sha(self.device, TEST_APP) == TEST_SHA,
                "Installed APK bytes changed; this driver never installs or replaces them")

    def immutable(self):
        for path, item in self.state["files"].items():
            self.guarded()
            raw = None if item is None else base64.b64decode(item["base64"], validate=True)
            require(raw is None or len(raw) <= MAX_PRIVATE and digest(raw) == item["sha256"], "Invalid immutable snapshot")
            require(read_app_file(self.device.serial, path) == raw, "An immutable local/welcome/cast/management byte changed; preserve the journal")

    def policy(self, label):
        self.guarded()
        require(re.fullmatch(r"[a-z-]+", label), "Unknown fixed policy observation label")
        self.observations += 1
        self.state["observations"] = self.observations
        self.save_state()
        policy = self.device.shell("dumpsys", "device_policy")
        self.guarded()
        activities = self.device.shell("dumpsys", "activity", "activities")
        home = home_component(self.device)
        for name, raw in (("device-policy", policy), ("activities", activities)):
            path = self.private / (str(self.observations).zfill(3) + "-" + label + "-" + name + ".txt")
            with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
                stream.write(raw)
        return policy_facts(policy, activities, home)

    def same_policy(self, label):
        value = self.policy(label)
        require(value == self.state["policy"], "Actual HOME/Device Owner/self-admin/LockTask changed; never repair or recreate ownership")
        return value

    def wait(self, predicate, seconds=25):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            self.guarded()
            value = predicate()
            if value:
                return value
            time.sleep(.2)
        raise ProbeError("A bounded native cancellation UI wait expired")

    def geometry(self):
        state = broadcast_json(self.device, "stage_state")
        require(state.get("width", 0) > 0 and state.get("height", 0) > 0, "Actual display geometry is unavailable")
        return state["width"], state["height"]

    def tree(self):
        require(self.hierarchy is not None, "The existing read-only hierarchy helper is required")
        return self.hierarchy.tree()

    def nodes(self, tree):
        return [dict(node.attrib) for node in visible_nodes(tree) if node.get("package") == APP]

    def start(self):
        self.guarded()
        self.state["remote_intent"] = True
        self.save_state()
        self.hierarchy = Hierarchy(self.device, self.guard, self.token, self.jar)
        require(not any(node.get("resource-id") in ("android:id/parentPanel", "android:id/alertTitle", "android:id/button1", "android:id/button2")
                        for node in self.nodes(self.tree())), "Preserve an existing native maintenance/confirmation window")
        self.device.start_log(clear=False)
        self.override("attention", "ENGAGED")

    def override(self, key, value):
        require(key in ("attention", "orientation"), "Only our two reversible debug holds may change")
        self.guarded()
        current = broadcast_json(self.device, "ui_state")
        self.state["pending_override"] = {"key": key, "before": current.get(key), "after": value}
        self.record("override", key=key, value=value)
        if key == "orientation":
            self.device.shell("am", "broadcast", "-n", APP + "/.DebugReceiver", "--es", "command", "orientation", "--ei", "value", value)
        else:
            self.device.debug("hold_attention", **({"value": value} if value is not None else {}))
        self.wait(lambda: broadcast_json(self.device, "ui_state").get(key) == value)
        self.state["expected_ui"][key] = value
        self.state["pending_override"] = None
        self.save_state()

    def menu(self, nodes):
        require(any(node.get("text") == "维护" for node in nodes) and
                not any(node.get("text") in (POSITIVE, BODY) for node in nodes), "Only the actual maintenance menu can be scrolled/selected")
        lists = [node for node in nodes if node.get("class") == "android.widget.ListView"]
        require(len(lists) == 1, "One native maintenance option list is required")
        return node_bounds(lists[0])

    def confirmation(self, nodes):
        def one(resource, text):
            found = [node for node in nodes if node.get("resource-id") == "android:id/" + resource and node.get("text") == text]
            require(len(found) == 1, "The exact Chinese native confirmation title/body/buttons must match")
            return found[0]
        values = {"title": one("alertTitle", TITLE), "body": one("message", BODY),
                  "positive": one("button1", POSITIVE), "cancel": one("button2", CANCEL)}
        panels = [node for node in nodes if node.get("resource-id") == "android:id/parentPanel"]
        require(len(panels) == 1, "One native confirmation panel is required")
        panel = node_bounds(panels[0])
        require(contained(panel, (0, 0, *self.geometry())) and
                all(contained(node_bounds(node), panel) for node in values.values()),
                "Chinese confirmation body or buttons are clipped/offscreen")
        positive, cancel = node_bounds(values["positive"]), node_bounds(values["cancel"])
        require(values["positive"].get("enabled") == values["cancel"].get("enabled") == "true" and
                (cancel[2] <= positive[0] or positive[2] <= cancel[0] or cancel[3] <= positive[1] or positive[3] <= cancel[1]),
                "The two actual native buttons must be fully visible and disjoint")
        return values, panel

    def safe_tap(self, role):
        # A closed vocabulary is deliberate: no function accepts arbitrary node
        # coordinates or button1. Positive UI is only read as an excluded region.
        require(role in ("menu-entry", "cancel"), "No positive or arbitrary native action is permitted")
        self.guarded()
        nodes = self.nodes(self.tree())
        if role == "menu-entry":
            viewport = self.menu(nodes)
            found = [node for node in nodes if node.get("text") == TITLE and node.get("resource-id") == "android:id/text1"]
            require(len(found) == 1 and contained(node_bounds(found[0]), viewport), "The exact maintenance menu entry must be fully visible")
            target = node_bounds(found[0])
            self.state["own_window"] = "confirmation"
        else:
            values, _ = self.confirmation(nodes)
            target = node_bounds(values["cancel"])
            forbidden = node_bounds(values["positive"])
            center = ((target[0] + target[2]) // 2, (target[1] + target[3]) // 2)
            require(not (forbidden[0] <= center[0] < forbidden[2] and forbidden[1] <= center[1] < forbidden[3]),
                    "Cancellation touch must never overlap the observed positive region")
        self.record("safe-touch", role=role, bounds=list(target))
        self.guarded()
        self.device.shell("input", "touchscreen", "tap", (target[0] + target[2]) // 2, (target[1] + target[3]) // 2)
        self.guarded()

    def back(self):
        self.guarded()
        self.record("back-only")
        self.device.shell("input", "keyevent", "KEYCODE_BACK")
        self.guarded()

    def open_confirmation(self):
        self.same_policy("before-open")
        self.immutable()
        self.guarded()
        self.state["own_window"] = "menu"
        self.record("open-maintenance-navigation")
        self.device.debug("maintenance")
        for _ in range(10):
            nodes = self.wait(lambda: (value if any(node.get("text") == "维护" for node in value) else None)
                              if isinstance(value := self.nodes(self.tree()), list) else None)
            viewport = self.menu(nodes)
            if any(node.get("text") == TITLE and contained(node_bounds(node), viewport) for node in nodes):
                break
            left, top, right, bottom = viewport
            self.guarded()
            self.record("maintenance-list-scroll")
            self.device.shell("input", "touchscreen", "swipe", (left + right) // 2, top + int((bottom - top) * .82),
                              (left + right) // 2, top + int((bottom - top) * .35), 350)
            self.guarded()
        else:
            raise ProbeError("The native management-removal entry is not reachable")
        self.safe_tap("menu-entry")
        self.wait(lambda: any(node.get("text") == BODY for node in self.nodes(self.tree())))
        self.confirmation(self.nodes(self.tree()))
        self.same_policy("opened-without-side-effects")
        self.immutable()

    def photo(self, name, panel):
        self.guarded()
        _, current_panel = self.confirmation(self.nodes(self.tree()))
        require(current_panel == panel, "The observed native confirmation moved before capture")
        raw = self.device.adb("exec-out", "screencap", "-p")
        require(len(raw) <= 12 * 1024 * 1024, "Screenshot exceeds its bound")
        path = self.private / (name + "-screen.png")
        with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as stream:
            stream.write(raw)
        with Image.open(io.BytesIO(raw)) as picture:
            require(picture.size == self.geometry() and contained(panel, (0, 0, *picture.size)), "Native screenshot geometry changed")
            picture.crop(panel).save(self.output / (name + ".png"))
        os.chmod(self.output / (name + ".png"), 0o600)
        self.guarded()
        _, current_panel = self.confirmation(self.nodes(self.tree()))
        require(current_panel == panel, "The native confirmation changed during capture")

    def closed(self):
        nodes = self.nodes(self.tree())
        return not any(node.get("text") in (BODY, POSITIVE, "维护") for node in nodes) and \
            not any(node.get("resource-id") in ("android:id/button1", "android:id/button2") for node in nodes)

    def case(self, name, orientation, cancel):
        self.override("orientation", orientation)
        self.wait(lambda: (self.geometry()[0] > self.geometry()[1]) == (orientation == 0))
        self.open_confirmation()
        values, panel = self.confirmation(self.nodes(self.tree()))
        self.photo(name, panel)
        self.result["cases"].append({"orientation": name, "body": BODY, "title": TITLE,
            "buttons": {role: {"text": values[role]["text"], "bounds": list(node_bounds(values[role]))} for role in ("positive", "cancel")},
            "dismissal": "real touchscreen cancellation" if cancel else "Back", "positive_observed_only": True})
        if cancel:
            self.safe_tap("cancel")
        else:
            self.confirmation(self.nodes(self.tree()))
            self.back()
        self.wait(self.closed)
        self.state["own_window"] = None
        self.save_state()
        self.same_policy("after-" + name)
        self.immutable()
        self.check(name + " actual Chinese confirmation is visible and cancellation preserves management", True)

    def attach_helper(self):
        require(self.state.get("remote_intent"), "No original owned helper was created")
        require(self.device.shell("sh", "-c", "if [ -f " + self.remote + "/hierarchy.jar ]; then echo present; fi").strip() == "present",
                "The original existing helper is required for recovery; never build one")
        self.hierarchy = object.__new__(Hierarchy)
        self.hierarchy.device, self.hierarchy.guard = self.device, self.guard
        self.hierarchy.token, self.hierarchy.counter = self.token, 10000
        self.hierarchy.remote, self.hierarchy.jar = self.remote, self.remote + "/hierarchy.jar"

    def restore(self):
        self.guarded()
        self.identity()
        self.immutable()
        self.same_policy("restore-preflight")
        current = broadcast_json(self.device, "ui_state")
        pending = self.state.get("pending_override")
        for key in UI_KEYS:
            allowed = [self.state["expected_ui"].get(key), self.state["ui"].get(key)]
            if pending and pending["key"] == key:
                allowed.extend((pending.get("before"), pending.get("after")))
            require(current.get(key) in allowed, "An unowned hold/locale/orientation changed; preserve it")
        if self.hierarchy is None:
            self.attach_helper()
        nodes = self.nodes(self.tree())
        owns_window = self.state.get("own_window") in ("menu", "confirmation")
        if owns_window and any(node.get("text") == BODY for node in nodes):
            self.confirmation(nodes)
            self.back()  # Never select a native button during recovery.
            self.wait(self.closed)
        elif owns_window and any(node.get("text") == "维护" for node in nodes):
            self.menu(nodes)
            self.back()
            self.wait(self.closed)
        else:
            require(self.closed(), "Unknown native window; stop without touching it")
        self.state["own_window"] = None
        self.record("restoration-started")
        original = self.state["ui"]
        current = broadcast_json(self.device, "ui_state")
        stage = broadcast_json(self.device, "stage_state")
        require(current.get("page") in (original["page"], "home.main") and not stage.get("canGoBack"),
                "Unexpected navigation; do not discard another application's stack")
        self.guarded()
        self.device.debug("page", value=original["page"])
        self.wait(lambda: broadcast_json(self.device, "ui_state").get("page") == original["page"])
        self.guarded()
        self.device.debug("variant", value=original.get("variant", ""))
        self.wait(lambda: broadcast_json(self.device, "ui_state").get("variant") == original.get("variant"))
        self.override("orientation", original["orientation"])
        self.override("attention", original.get("attention"))
        keys = UI_KEYS + ("page", "variant", "onboardingOpen", "safeMode")
        def restored():
            value = broadcast_json(self.device, "ui_state")
            return all(value.get(key) == original.get(key) for key in keys)
        self.wait(restored)
        idle(self.device)
        self.same_policy("restored")
        self.immutable()
        self.identity()
        self.guarded()
        require(not self.device.shell("find", self.remote, "-type", "l").strip(),
                "A symlink appeared inside the owned helper directory; preserve it")
        children = set(self.device.shell("ls", "-A", self.remote).splitlines())
        require(children in ({"hierarchy.jar"}, {"hierarchy.jar", "oat"}),
                "Unknown content appeared inside the owned helper directory; preserve it")
        directories = {self.remote}
        files = {self.remote + "/hierarchy.jar"}
        if "oat" in children:
            oat = self.remote + "/oat"
            self.guarded()
            require(set(self.device.shell("ls", "-A", oat).splitlines()) == {"arm"} and
                    set(self.device.shell("ls", "-A", oat + "/arm").splitlines()) == {"hierarchy.odex", "hierarchy.vdex"},
                    "Unknown Android helper oat layout; preserve it")
            directories.update((oat, oat + "/arm"))
            files.update((oat + "/arm/hierarchy.odex", oat + "/arm/hierarchy.vdex"))
        self.guarded()
        require(set(self.device.shell("find", self.remote, "-type", "d").splitlines()) == directories and
                set(self.device.shell("find", self.remote, "-type", "f").splitlines()) == files,
                "An expected helper/cache path changed file type; preserve it")
        self.guarded()
        self.device.shell("rm", "-rf", self.remote)
        self.state["remote_intent"] = False
        self.state["stage"] = "restored"
        self.save_state()
        self.result["restoration"] = {"five_immutable_files_byte_equal": True, "fixed_apks_unchanged": True,
            "original_page_variant_holds_orientation": True, "home_owner_self_admin_locktask_equal": True,
            "media_idle": True, "audio_streams_1_to_5_zero": True, "owned_helper_removed": True}
        self.check("original UI and actual HOME/DO/self-admin/LockTask remain unchanged; five files and silent media verified", True)

    def close_log(self):
        if self.device._logcat is not None:
            path = self.private / ("logcat-" + uuid.uuid4().hex + ".txt")
            with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
                stream.write("\n".join(self.device.log_since(0)) + "\n")
            self.device.stop_log()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default=PHYSICAL)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--hierarchy-jar", type=Path, help="Existing cached jar only; no compilation")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--restore", type=Path)
    args = parser.parse_args()
    if args.restore is None and (args.apk is None or args.test_apk is None):
        parser.error("--apk and --test-apk must identify the fixed already-installed pair")
    if args.restore is not None and any((args.apk, args.test_apk)):
        parser.error("--restore uses its original private journal; omit APK inputs")
    require(not args.output.exists(), "Use a fresh output directory")
    require(args.serial == PHYSICAL, "Only the authorized physical device may be used")
    jar = args.hierarchy_jar or cached_hierarchy()
    file_digest(jar)  # Never calls hierarchy_jar(), javac, Gradle or an installer.
    device = Device(args.serial)
    require(device.shell("getprop", "ro.serialno").strip() == PHYSICAL and quiet(device),
            "The physical device must already have all five audio streams zero")
    main_foreground(device)
    require(installed_sha(device, APP) == APK_SHA and installed_sha(device, TEST_APP) == TEST_SHA,
            "The fixed 0.1.9 installed APK hashes differ; install separately")
    if args.restore is None:
        require(file_digest(args.apk) == APK_SHA and file_digest(args.test_apk) == TEST_SHA, "APK inputs differ from the fixed installed bytes")
    else:
        require(args.output.resolve() != args.restore.resolve() and args.restore.resolve() not in args.output.resolve().parents,
                "Public artifacts must remain outside the private journal")
    args.output.mkdir(mode=0o700, parents=True)
    os.chmod(args.output, 0o700)
    watch = TouchWatch(device)
    guard = Guard(device, watch)
    driver = None
    try:
        if args.restore is not None:
            driver = Driver.load(device, guard, args.output, jar, args.restore)
            driver.restore()
        else:
            driver = Driver.capture(device, guard, args.output, jar)
            driver.start()
            driver.case("portrait", 1, cancel=True)
            driver.case("landscape", 0, cancel=False)
            driver.restore()
        driver.result["complete"] = True
        driver.save()
        print("Native management cancellation/Back checks completed; positive removal was never attempted.", flush=True)
        return 0
    except BaseException as error:
        if driver is not None:
            driver.result["complete"] = False
            driver.result["failure"] = str(error) if isinstance(error, ProbeError) else type(error).__name__
            driver.record("stopped", error=type(error).__name__)
            if not isinstance(error, ExternalInput):
                try:
                    driver.guarded()
                    driver.restore()
                except BaseException as recovery_error:
                    driver.result["restoration_failure"] = str(recovery_error) if isinstance(recovery_error, ProbeError) else type(recovery_error).__name__
            driver.save()
            print("Private recovery journal retained: " + str(driver.private), file=sys.stderr)
        print("Management cancellation check stopped; no ownership repair is attempted.", file=sys.stderr)
        return 2
    finally:
        if driver is not None:
            driver.close_log()
        watch.close()
        device.stop_log()


if __name__ == "__main__":
    sys.exit(main())
