#!/usr/bin/env python3
"""APP-9 local directory UI/cache qualification; never plays or installs anything.

Both installed APK digests must be supplied explicitly. No app instrumentation
runner is used. ThinkHierarchy must already exist; this driver never builds it.
Raw snapshots/logs stay in a private recovery journal. Human input, another
foreground, raised volume or unknown data stops without reclaiming the screen.
"""
import argparse
import base64
import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shlex
import sys
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools/tests"), str(ROOT / "tools/media")]
from device_app_files import read_app_file, restore_app_file
from device_hierarchy import node_bounds, visible_nodes
from media_device import APP, Device
from radio_browser import RadioBrowserFixture
from registry_device import (ExternalInput, Guard, Hierarchy, PHYSICAL, ProbeError, TEST_APP, TouchWatch,
                             app_exists, begin_controlled, cached_hierarchy, controlled_check,
                             controlled_main, digest, file_digest, home_candidates, home_component,
                             installed_sha, private_json, quiet, require)
from management_exit_cancel_device import ADMIN, idle, policy_facts
from ui_walkthrough import broadcast_json

MUTABLE = ("files/settings/local.json", "files/settings/local.json.bak",
           "files/store/radio.json", "files/store/radio.json.bak")
IMMUTABLE = ("files/settings/server.json", "files/settings/server.json.bak",
             "files/store/welcome.json", "files/store/welcome.json.bak",
             "files/cast/identity-seed", "files/cast/airplay-identity", "files/management-exit")
AUDIT = ("shared_prefs/deck.xml", "shared_prefs/deck.xml.bak",
         "shared_prefs/recovery.xml", "shared_prefs/recovery.xml.bak")
OWN_KEYS = {"directory", "directory_url", "test", "click", "country_auto", "country"}
HOLDS = ("theme", "attention", "presence", "lux", "locale", "orientation")
MAX_FILE = 8 * 1024 * 1024
SHA = re.compile(r"[0-9a-f]{64}")
UUID = re.compile(r"00000000-0000-4000-8000-[0-9a-f]{12}")


def document(raw):
    value = {} if raw is None else json.loads(raw)
    require(isinstance(value, dict), "A fixed private JSON object is required")
    return value


def preferences(raw):
    if raw is None:
        return {}
    require(len(raw) <= MAX_FILE, "A preference audit exceeds the fixed byte bound")
    root = ET.fromstring(raw)
    require(root.tag == "map", "Unknown preferences audit format")
    values = {}
    for item in root:
        name = item.get("name")
        require(name and name not in values, "Unknown or duplicate preference key")
        values[name] = (item.tag, item.get("value"), item.text or "", tuple((child.tag, child.text or "") for child in item))
    return values


def reverses(device):
    rows = set()
    for line in device.adb("reverse", "--list").decode().splitlines():
        if not line.strip():
            continue
        parts = line.split()
        require(len(parts) in (2, 3) and all(re.fullmatch(r"tcp:[0-9]+", x) for x in parts[-2:]),
                "Unknown existing reverse format; do not alter it")
        rows.add(tuple(parts[-2:]))
    return rows


class Origin:
    """One owned loopback listener using only the existing fixed fixture."""
    def __init__(self):
        self.fixture = RadioBrowserFixture("duplicates")
        self.lock, self.active = threading.Lock(), 0
        origin = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_GET(self):
                with origin.lock:
                    origin.active += 1
                try:
                    reply = origin.fixture.reply(self.path, "http://127.0.0.1:" + str(self.server.server_port),
                                                 self.headers.get("User-Agent", ""))
                    self.send_response(reply.status)
                    self.send_header("Content-Type", reply.mime)
                    self.send_header("Content-Length", str(len(reply.body)))
                    self.send_header("Connection", "close")
                    self.end_headers()
                    self.wfile.write(reply.body)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                finally:
                    with origin.lock:
                        origin.active -= 1

            def log_message(self, *_):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.closed = False

    def scenario(self, name):
        require(name in {"normal", "duplicates", "bad-addresses", "5xx"}, "Unknown fixed local scenario")
        with self.lock:
            require(self.active == 0, "Preserve an in-flight fixture response before switching scenario")
            self.fixture.scenario = name

    def requests(self):
        with self.fixture.lock:
            return copy.deepcopy(self.fixture.requests)

    def close(self):
        if not self.closed:
            self.server.shutdown()
            self.server.server_close()
            self.thread.join(timeout=5)
            self.closed = True


class Driver:
    def __init__(self, device, guard, directory, data, output, jar=None):
        self.device, self.guard, self.directory, self.data = device, guard, directory, data
        self.output, self.jar, self.hierarchy, self.origin = output, jar, None, None
        self.result = {"complete": False, "full_APP9_complete": False, "checks": [],
                       "apk_sha256": data["apk_sha256"], "test_apk_sha256": data["test_apk_sha256"],
                       "scope": "Real Lua and native directory UI with local metadata only; no media load or health claim",
                       "pending": ["strict-public MP3/AAC/HLS playback", "public API TLS and timeout", "health/reconnect device matrix"],
                       "private_recovery_journal": str(directory)}

    @classmethod
    def capture(cls, device, guard, output, jar, apk_sha, test_sha, port):
        guard.check()
        require(installed_sha(device, APP) == apk_sha and installed_sha(device, TEST_APP) == test_sha,
                "Installed APKs do not match the explicit candidate hashes")
        ui = idle(device)
        require(str(ui.get("locale", "")).startswith("zh"), "Preserve the existing language; begin in Chinese")
        directory = Path(tempfile.mkdtemp(prefix="thinkos-radio-directory-recovery-"))
        os.chmod(directory, 0o700)
        data = {"version": 1, "kind": "radio-directory", "serial": device.serial, "token": uuid.uuid4().hex,
                "stage": "capturing", "ui": ui, "expected_ui": {key: ui.get(key) for key in HOLDS},
                "apk_sha256": apk_sha, "test_apk_sha256": test_sha, "device_port": port,
                "home": home_component(device), "home_candidates": home_candidates(device),
                "reverses": sorted(reverses(device)), "files": {}, "actions": [], "remote_created": False}
        require(not any(row[0] == "tcp:" + str(port) for row in data["reverses"]), "Preserve an existing reverse on the chosen port")
        driver = cls(device, guard, directory, data, output, jar)
        driver.save()
        print("Private recovery journal: " + str(directory), flush=True)
        for path in MUTABLE + IMMUTABLE + AUDIT:
            guard.check()
            raw = read_app_file(device.serial, path)
            require(raw is None or len(raw) <= MAX_FILE, "A private snapshot exceeds the fixed byte bound")
            data["files"][path] = None if raw is None else {"base64": base64.b64encode(raw).decode(), "sha256": digest(raw)}
            driver.save()
        require(all(driver.original(path) is None for path in (MUTABLE[1], MUTABLE[3])),
                "Preserve an existing AtomicFile backup rather than running over it")
        data["settings"] = document(driver.original(MUTABLE[0]))
        server = document(driver.original("files/settings/server.json"))
        defaults = json.loads((ROOT / "plugins/radio/manifest.json").read_text())["settings"]
        effective = {key: server.get("radio", {}).get(key, field["default"]) for key, field in defaults.items()}
        effective.update(data["settings"].get("radio", {}))
        require(effective.get("directory") is False and effective.get("source") == effective.get("stream_url") == "",
                "The minimal fixture requires radio directory off and both old sources empty; preserve configured sources")
        data["policy"] = driver.policy()
        require(data["policy"]["owner"] == ADMIN and data["policy"]["self_admin"] and data["policy"]["lock_mode"] == "LOCKED",
                "Begin on the existing owned, locked device; never establish or repair ownership")
        driver.wait_stable()
        data["stage"] = "captured"
        driver.save()
        return driver

    @classmethod
    def load(cls, device, guard, directory, output, apk_sha, test_sha):
        require(directory.is_dir() and not directory.is_symlink() and directory.stat().st_uid == os.getuid() and
                directory.stat().st_mode & 0o077 == 0, "A mode-0700 journal belonging to this user is required")
        path = directory / "state.json"
        require(path.is_file() and not path.is_symlink() and path.stat().st_uid == os.getuid() and
                path.stat().st_mode & 0o077 == 0 and path.stat().st_size < 64 * 1024 * 1024, "Invalid private recovery file")
        data = json.loads(path.read_text())
        require(data.get("version") == 1 and data.get("kind") == "radio-directory" and data.get("serial") == device.serial and
                re.fullmatch(r"[0-9a-f]{32}", data.get("token", "")) and set(data.get("files", {})) == set(MUTABLE + IMMUTABLE + AUDIT) and
                data.get("apk_sha256") == apk_sha and data.get("test_apk_sha256") == test_sha and
                type(data.get("device_port")) is int and 1 <= data["device_port"] <= 65535,
                "The journal must describe this exact candidate and fixed recovery scope")
        driver = cls(device, guard, directory, data, output)
        for name in MUTABLE + IMMUTABLE + AUDIT:
            driver.original(name)
        require(re.fullmatch(r"[a-z][a-z0-9_-]*\.[A-Za-z0-9_.-]+", data["ui"].get("page", "")), "Invalid original base page")
        return driver

    def original(self, path):
        item = self.data["files"][path]
        raw = None if item is None else base64.b64decode(item["base64"], validate=True)
        require(raw is None or len(raw) <= MAX_FILE and digest(raw) == item["sha256"], "Private snapshot checksum differs")
        return raw

    def save(self):
        private_json(self.directory / "state.json", self.data)

    def report(self):
        private_json(self.output / "results.json", self.result)

    def check(self, name, value=True):
        self.result["checks"].append({"name": name, "passed": bool(value)})
        self.report()
        print(("PASS " if value else "FAIL ") + name, flush=True)
        require(value, name)

    def guarded(self):
        self.guard.check()
        ui = broadcast_json(self.device, "ui_state")
        if ui.get("safeMode") or ui.get("onboardingOpen"):
            raise ExternalInput("Preserve a newly opened recovery/welcome session; stop without restarting it")
        media = broadcast_json(self.device, "media_state")
        if media.get("status") != "stopped" or any(media.get(key) for key in ("owner", "kind", "count")):
            raise ExternalInput("Unexpected media/cast session; stop without touching it")
        require(all(ui.get(key) == value for key, value in self.data["expected_ui"].items()), "An unrelated UI hold or direction changed")
        return ui

    def wait(self, predicate, seconds=30):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            self.guarded()
            value = predicate()
            if value:
                return value
            time.sleep(.2)
        raise ProbeError("A bounded real radio-directory observation expired")

    def restore_original_page(self):
        target = self.data["ui"]["page"]

        def arrived():
            ui = self.guarded()
            if ui.get("page") == target:
                return True
            # MainActivity can resume before the plugin catalog is published.
            # DeckRouter clears page for an empty catalog; a nonempty native
            # page is the existing readiness signal, without a new debug API.
            # The original plugin may join a later catalog, so retry only this
            # already-authorized target until the exact page actually arrives.
            if ui.get("page"):
                self.device.debug("page", value=target)
            return False

        self.wait(arrived, seconds=30)

    def policy(self):
        if self.data.get("controlled_foreground"):
            controlled_check(self.device, self.guard, self)
        else:
            self.guard.check()
        return policy_facts(self.device.shell("dumpsys", "device_policy"),
                            self.device.shell("dumpsys", "activity", "activities"), home_component(self.device))

    def input(self, *args):
        self.guarded()
        self.device.shell("input", *args)
        self.guarded()

    def geometry(self):
        state = broadcast_json(self.device, "stage_state")
        require(state.get("width", 0) > 0 and state.get("height", 0) > 0, "Actual stage geometry is required")
        return state["width"], state["height"]

    def nodes(self):
        require(self.hierarchy is not None, "The existing hierarchy helper is required")
        return [dict(node.attrib) for node in visible_nodes(self.hierarchy.tree()) if node.get("package") == APP]

    def tap(self, label, find=False, toward_bottom=True):
        for _ in range(30 if find else 1):
            nodes = [node for node in self.nodes() if label in (node.get("text"), node.get("content-desc"))]
            if nodes:
                node = min(nodes, key=lambda item: node_bounds(item)[1])
                left, top, right, bottom = node_bounds(node)
                width, height = self.geometry()
                require(0 <= left < right <= width and 0 <= top < bottom <= height, "A real directory control is clipped")
                self.input("touchscreen", "tap", (left + right) // 2, (top + bottom) // 2)
                return
            require(find, "A real directory action is unavailable: " + label)
            width, height = self.geometry()
            start, end = (.76, .36) if toward_bottom else (.36, .76)
            self.input("touchscreen", "swipe", width // 2, int(height * start), width // 2, int(height * end), 350)
        raise ProbeError("The bounded real list did not expose its more control")

    def setting(self, key, value):
        require(key in OWN_KEYS, "Only the six named radio inputs may change")
        self.guarded()
        before = copy.deepcopy(self.data["settings"])
        below = document(self.original("files/settings/server.json")).get("radio", {}).get(key)
        if below is None:
            below = json.loads((ROOT / "plugins/radio/manifest.json").read_text())["settings"][key]["default"]
        after = copy.deepcopy(before)
        scope = after.setdefault("radio", {})
        if value == below:
            scope.pop(key, None)
        else:
            scope[key] = value
        if not scope:
            after.pop("radio", None)
        self.data["pending_settings"] = after
        self.data["actions"].append({"kind": "setting", "key": key, "value": value})
        self.save()
        self.device.debug("setting", scope="radio", key=key, value=str(value).lower() if isinstance(value, bool) else value)
        self.wait(lambda: document(read_app_file(self.device.serial, MUTABLE[0])) == after)
        self.data["settings"] = after
        self.data.pop("pending_settings", None)
        self.save()

    def cache(self, country="CN", category=""):
        raw = read_app_file(self.device.serial, MUTABLE[2])
        store = document(raw)
        cached = json.loads(store.get("directory_cache", "{}"))
        key = "rb\n" + self.data["endpoint"] + "\n" + country + "\n" + category
        return next((entry for entry in cached.get("entries", []) if entry.get("key") == key), None)

    def verify_owned(self):
        if self.data.get("stage") in ("capturing", "captured") and not self.data["actions"]:
            require(all(read_app_file(self.device.serial, path) == self.original(path) for path in MUTABLE),
                    "A baseline write was still pending before the fixture; preserve the newer bytes")
        for path in IMMUTABLE:
            require(read_app_file(self.device.serial, path) == self.original(path), "An unrelated private preference or history changed; preserve it")
        local = document(read_app_file(self.device.serial, MUTABLE[0]))
        original = document(self.original(MUTABLE[0]))
        allowed = [self.data["settings"], self.data.get("pending_settings")]
        if self.data.get("stage") in ("restoring", "restored"):
            allowed.append(original)
        require(local in allowed, "Unknown settings edits appeared")
        for scope in set(original) | set(local):
            if scope != "radio":
                require(original.get(scope) == local.get(scope), "A non-radio settings scope changed")
        old_radio, new_radio = original.get("radio", {}), local.get("radio", {})
        require(all(old_radio.get(key) == new_radio.get(key) for key in (set(old_radio) | set(new_radio)) - OWN_KEYS),
                "An unrelated radio setting changed")
        before, after = document(self.original(MUTABLE[2])), document(read_app_file(self.device.serial, MUTABLE[2]))
        require(set(after) <= set(before) | {"directory_cache"} and
                all(after.get(key) == value for key, value in before.items() if key != "directory_cache"),
                "Favorites, health or an unknown radio key changed; do not overwrite it")
        if "directory_cache" in after and after.get("directory_cache") != before.get("directory_cache"):
            cached = json.loads(after["directory_cache"])
            require(set(cached) == {"v", "entries"} and cached["v"] == 1 and isinstance(cached["entries"], list) and len(cached["entries"]) <= 4,
                    "An unknown directory-cache shape appeared")
            old_entries = json.loads(before.get("directory_cache", '{"entries":[]}')).get("entries", [])
            for entry in cached["entries"]:
                require(isinstance(entry, dict) and set(entry) <= {"key", "items", "more", "offsets"}, "Unknown cache-entry fields appeared")
                if entry in old_entries:
                    continue
                if entry.get("key") == "list\n\n\n":
                    require(not entry.get("items"), "Only the empty original manual-source cache may be refreshed")
                    continue
                require(entry.get("key") in {"rb\n" + self.data.get("endpoint", "") + "\n" + country + "\n" + category
                                             for country in ("CN", "FR") for category in ("", "news")}, "A non-fixture directory cache appeared")
                require(entry.get("items") == {} or isinstance(entry.get("items"), list) and len(entry["items"]) <= 200,
                        "Unknown cache item collection")
                for row in entry["items"]:
                    require(isinstance(row, dict) and UUID.fullmatch(row.get("uuid", "")) and
                            1 <= int(row["uuid"][-12:], 16) <= 220 and row.get("title") == "测试电台 " + str(int(row["uuid"][-12:], 16)) and
                            row.get("directory") is True and row.get("public_only") is True and row.get("country") == "CN" and
                            row.get("tags") in ("news", "noticias", "actualités") and set(row) <=
                            {"url", "title", "uuid", "directory", "public_only", "tags", "country", "clicks", "hls", "h"},
                            "Unknown or non-fixture station data appeared")
                    # A failed filter is still owned fixture data and can be restored;
                    # it must not be mistaken for a successful filtering assertion.
                    require(row.get("url") in {"https://stream.example.org/" + str(int(row["uuid"][-12:], 16)),
                            "http://stream.example.org/live", "https://127.0.0.1/live", "https://10.0.0.1/live",
                            "https://169.254.1.1/live", "https://[::1]/live", "https://radio.local/live",
                            "https://user:secret@stream.example.org/live", "https://stream.example.org/live#fragment",
                            "https://stream.example.org/live"}, "A non-fixture media URL appeared")
        require(all(read_app_file(self.device.serial, path) is None for path in (MUTABLE[1], MUTABLE[3])), "An unexpected AtomicFile backup remains")
        self.audit_preferences()

    def audit_preferences(self):
        observed = {}
        for path in AUDIT:
            raw = read_app_file(self.device.serial, path)
            observed[path] = None if raw is None else {"base64": base64.b64encode(raw).decode(), "sha256": digest(raw)}
        for path in AUDIT:
            before = preferences(self.original(path))
            item = observed[path]
            after = preferences(None if item is None else base64.b64decode(item["base64"]))
            if "deck.xml" in path:
                own = self.data["ui"]["page"]
                require(all(before.get(key) == after.get(key) for key in (set(before) | set(after)) - {own}),
                        "An unrelated remembered page variant changed")
                require(after.get(own) == before.get(own) or after.get(own) == ("string", None, self.data["ui"].get("variant", ""), ()),
                        "The original page variant changed outside its restoration")
            else:
                # This run has no update or reboot. Production changes boot_count
                # only with an uncaught crash, so no recovery key may be hidden.
                # XML whitespace/order may change; the complete map may not.
                require(before == after,
                        "Crash/safe-mode/health history or an unknown recovery key changed")
        self.data["preference_audit"] = observed
        self.save()

    def wait_stable(self):
        end = time.monotonic() + 3
        while time.monotonic() < end:
            self.guarded()
            time.sleep(.2)
        self.verify_owned()
        first = {name: digest(read_app_file(self.device.serial, name)) for name in MUTABLE}
        time.sleep(.3)
        self.guarded()
        require(first == {name: digest(read_app_file(self.device.serial, name)) for name in MUTABLE}, "Owned writes have not settled")
        self.data["expected_files"] = first
        self.save()

    def overrides(self, values):
        self.guard.check()
        for key in HOLDS:
            value = values.get(key)
            current = broadcast_json(self.device, "ui_state")
            if current.get(key) == value:
                continue
            self.data["expected_ui"][key] = value
            self.save()
            if key == "presence":
                if value is None:
                    self.device.debug("hold_presence")
                else:
                    self.device.shell("am", "broadcast", "-n", APP + "/.DebugReceiver", "--es", "command", "hold_presence", "--ez", "value", str(value).lower())
            elif key == "orientation":
                self.device.shell("am", "broadcast", "-n", APP + "/.DebugReceiver", "--es", "command", "orientation", "--ei", "value", value)
            else:
                command = "hold_attention" if key == "attention" else key
                self.device.debug(command, **({"value": value} if value is not None else {}))
        self.data["expected_ui"] = {key: values.get(key) for key in HOLDS}
        self.save()
        until = time.monotonic() + 20
        while time.monotonic() < until:
            self.guard.check()
            current = broadcast_json(self.device, "ui_state")
            if all(current.get(key) == values.get(key) for key in HOLDS):
                return
            time.sleep(.2)
        raise ProbeError("The planned process-local UI overrides did not apply")

    def open_radio(self):
        self.guarded()
        seen = set()
        for _ in range(16):
            page = broadcast_json(self.device, "ui_state").get("page")
            if page == "tiles.apps":
                break
            require(page not in seen, "Radio's actual application tile is absent from the base-page sequence")
            seen.add(page)
            width, height = self.geometry()
            self.input("touchscreen", "swipe", int(width * .86), height // 2, int(width * .15), height // 2, 350)
            self.wait(lambda: broadcast_json(self.device, "ui_state").get("page") != page)
        require(broadcast_json(self.device, "ui_state").get("page") == "tiles.apps", "The real application tile page is required")
        self.tap("广播", find=True)
        self.wait(lambda: broadcast_json(self.device, "ui_state").get("page") == "radio.stations")

    def refresh(self):
        labels = {"刷新", "列表加载失败"}
        node = self.wait(lambda: next((item for item in self.nodes() if item.get("text") in labels), None))
        self.tap(node["text"])

    def qualify(self):
        self.origin = Origin()
        self.data["host_port"] = self.origin.port
        self.data["endpoint"] = "http://127.0.0.1:" + str(self.data["device_port"])
        self.data["reverse_planned"] = True
        self.save()
        self.device.adb("reverse", "tcp:" + str(self.data["device_port"]), "tcp:" + str(self.origin.port))
        self.check("only the owned loopback reverse is added", reverses(self.device) ==
                   {tuple(row) for row in self.data["reverses"]} | {("tcp:" + str(self.data["device_port"]), "tcp:" + str(self.origin.port))})
        self.data["remote_created"] = True
        self.data["jar_sha256"] = file_digest(self.jar)
        self.save()
        self.hierarchy = Hierarchy(self.device, self.guard, self.data["token"], self.jar)
        holds = dict(self.data["expected_ui"], attention="ENGAGED")
        self.overrides(holds)
        for key, value in (("test", False), ("click", False), ("country_auto", False), ("country", "CN"),
                           ("directory_url", self.data["endpoint"]), ("directory", True)):
            self.setting(key, value)
        self.open_radio()
        self.wait(lambda: (entry := self.cache()) and len(entry["items"]) == 99)
        self.wait_stable()
        self.check("actual Lua deduplicates 100 local rows by UUID and persists 99", len({row["uuid"] for row in self.cache()["items"]}) == 99)
        self.check("the native directory page exposes fixture station text", any(node.get("text") == "测试电台 1" for node in self.nodes()))
        self.tap("更多", find=True)
        self.wait(lambda: (entry := self.cache()) and len(entry["items"]) == 198)
        self.wait_stable()
        calls = self.origin.requests()
        self.check("the real more action advances offset 100 without a station load", any(row["query"].get("offset") == ["100"] for row in calls))
        self.origin.scenario("normal")
        start = len(self.origin.requests())
        self.tap("分类");self.tap("新闻谈话", find=True, toward_bottom=False)
        self.wait(lambda: (entry := self.cache(category="news")) and len(entry["items"]) == 100)
        self.wait_stable()
        queries = self.origin.requests()[start:]
        self.check("one category performs exactly three OR-tag requests and merges UUIDs", len(queries) == 3 and
                   {row["query"].get("tag", [None])[0] for row in queries} == {"news", "noticias", "actualités"} and
                   all(row["query"].get("tagExact") == ["true"] for row in queries))
        self.input("keyevent", "4")
        self.wait(lambda: {"流行", "摇滚"} <= {node.get("text") for node in self.nodes()})
        self.check("real Back exposes category rows before leaving radio", broadcast_json(self.device, "ui_state").get("page") == "radio.stations")
        self.tap("全部")
        self.setting("country", "FR")
        self.wait(lambda: (entry := self.cache("FR")) is not None and not entry["items"])
        self.wait(lambda: any(node.get("text") == "列表里没有电台" for node in self.nodes()))
        self.wait_stable()
        self.check("a selected-country query filters dishonest CN rows from FR", any(row["query"].get("countrycode") == ["FR"] for row in self.origin.requests()))
        self.origin.scenario("bad-addresses")
        self.setting("country", "CN")
        self.wait(lambda: (entry := self.cache()) and len(entry["items"]) == 1 and entry["items"][0]["uuid"].endswith("000000000009"))
        self.wait_stable()
        self.check("HTTP, private, local, credentials and fragment addresses are absent", self.cache()["items"][0]["url"] == "https://stream.example.org/live")
        self.origin.scenario("normal");self.refresh()
        self.wait(lambda: (entry := self.cache()) and len(entry["items"]) == 100)
        self.wait_stable()
        cached = copy.deepcopy(self.cache())
        self.origin.scenario("5xx");self.refresh()
        self.wait(lambda: any(node.get("text") == "列表加载失败" for node in self.nodes()))
        self.wait_stable()
        self.check("a real HTTP 503 retains the committed directory list", self.cache() == cached)
        self.result["fixture_requests"] = self.origin.requests()
        self.check("click=false produces zero click-report requests", not any(row["path"].startswith("/json/url/") for row in self.origin.requests()))
        self.check("catalog requests identify ThinkOS and use bounded safe filters", all(row["agent"].startswith("ThinkOS-Radio/") and
                   row["query"].get("limit") == ["100"] and row["query"].get("hidebroken") == ["true"] and row["query"].get("is_https") == ["true"]
                   for row in self.origin.requests() if row["path"] == "/json/stations/search"))
        self.origin.close()
        old_pid = self.device.shell("pidof", APP).strip()
        self.data["stage"] = "offline-restart"
        begin_controlled(self, self.device, self.guard, "lifecycle-restart")
        self.device.shell("am", "force-stop", APP)
        controlled_check(self.device, self.guard, self)
        controlled_main(self, self.device, self.guard)
        self.overrides(holds)
        require(installed_sha(self.device, APP) == self.data["apk_sha256"] and installed_sha(self.device, TEST_APP) == self.data["test_apk_sha256"], "Candidate APK identity changed during restart")
        self.check("an actual new process starts with the directory origin offline", self.device.shell("pidof", APP).strip() not in ("", old_pid))
        self.open_radio()
        self.wait(lambda: any(node.get("text") == "测试电台 1" for node in self.nodes()))
        self.check("the real restarted Lua restores the committed offline list", self.cache() == cached)
        self.refresh()
        self.wait(lambda: any(node.get("text") == "列表加载失败" for node in self.nodes()))
        self.wait_stable()
        self.check("an offline refresh preserves the same real on-disk list", self.cache() == cached)
        self.check("HOME, Device Owner, own admin and LockTask stay unchanged", self.policy() == self.data["policy"])
        self.data["stage"] = "qualified"
        self.save()

    def restore(self):
        controlled_check(self.device, self.guard, self)
        require(installed_sha(self.device, APP) == self.data["apk_sha256"] and installed_sha(self.device, TEST_APP) == self.data["test_apk_sha256"], "Do not restore over a changed APK candidate")
        self.verify_owned()
        if self.data.get("controlled_foreground"):
            controlled_main(self, self.device, self.guard)
        require(self.policy() == self.data["policy"], "Ownership or HOME changed; never recreate or repair it")
        begin_controlled(self, self.device, self.guard, "restore-stop")
        self.device.shell("am", "force-stop", APP)
        controlled_check(self.device, self.guard, self)
        self.verify_owned()
        self.data["stage"] = "restoring"
        self.save()
        for path in MUTABLE:
            controlled_check(self.device, self.guard, self)
            restore_app_file(self.device.serial, path, self.original(path))
            if self.original(path) is not None:
                self.device.shell("run-as", APP, "chmod", "600", path)
        self.data["settings"] = document(self.original(MUTABLE[0]))
        self.data.pop("pending_settings", None)
        self.save()
        require(all(read_app_file(self.device.serial, path) == self.original(path) for path in MUTABLE + IMMUTABLE), "Exact original bytes were not restored")
        baseline = {tuple(row) for row in self.data["reverses"]}
        own = ("tcp:" + str(self.data["device_port"]), "tcp:" + str(self.data.get("host_port", 0)))
        actual = reverses(self.device)
        require(actual in (baseline, baseline | {own}), "An unknown reverse changed; preserve it")
        if own in actual and own not in baseline:
            self.device.adb("reverse", "--remove", own[0])
        remote = "/data/local/tmp/thinkos-registry-" + self.data["token"]
        if self.data.get("remote_created"):
            present = self.device.shell("sh", "-c", "if [ -e " + shlex.quote(remote) + " ]; then echo yes; fi").strip()
            if present:
                controlled_check(self.device, self.guard, self)
                require(not self.device.shell("find", remote, "-type", "l").strip(), "A helper symlink appeared; preserve it")
                children = set(self.device.shell("ls", "-A", remote).splitlines())
                require(children in ({"hierarchy.jar"}, {"hierarchy.jar", "oat"}), "Unknown files appeared in the owned helper directory")
                directories, files = {remote}, {remote + "/hierarchy.jar"}
                if "oat" in children:
                    oat = remote + "/oat"
                    require(set(self.device.shell("ls", "-A", oat).splitlines()) == {"arm"} and
                            set(self.device.shell("ls", "-A", oat + "/arm").splitlines()) == {"hierarchy.odex", "hierarchy.vdex"},
                            "Unknown Android8.1 helper oat layout; preserve it")
                    directories.update((oat, oat + "/arm"))
                    files.update((oat + "/arm/hierarchy.odex", oat + "/arm/hierarchy.vdex"))
                require(set(self.device.shell("find", remote, "-type", "d").splitlines()) == directories and
                        set(self.device.shell("find", remote, "-type", "f").splitlines()) == files,
                        "An owned helper/cache entry changed file type; preserve it")
                require(self.device.shell("sha256sum", remote + "/hierarchy.jar").split()[0] == self.data["jar_sha256"], "The owned helper bytes changed")
                controlled_check(self.device, self.guard, self)
                self.device.shell("rm", "-rf", remote)
        controlled_main(self, self.device, self.guard)
        self.overrides(self.data["ui"])
        self.restore_original_page()
        self.device.debug("variant", value=self.data["ui"].get("variant", ""))
        self.wait(lambda: broadcast_json(self.device, "ui_state").get("variant") == self.data["ui"].get("variant"))
        self.wait_stable()
        self.check("all four original settings/radio files and unrelated immutable files match exactly", all(
            read_app_file(self.device.serial, path) == self.original(path) for path in MUTABLE + IMMUTABLE))
        self.check("original UI holds, page, variant, direction, HOME and DO are preserved", self.policy() == self.data["policy"] and
                   all(broadcast_json(self.device, "ui_state").get(key) == self.data["ui"].get(key) for key in HOLDS + ("page", "variant")))
        self.check("only the owned reverse is removed and five audio streams remain zero", reverses(self.device) == baseline and quiet(self.device))
        self.data["stage"] = "restored"
        self.save()
        self.result["restored"] = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=PHYSICAL)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--test-apk-sha256", required=True)
    parser.add_argument("--apk", type=Path, help="optional local byte cross-check only; never installed")
    parser.add_argument("--test-apk", type=Path, help="optional local byte cross-check only; never installed")
    parser.add_argument("--hierarchy-jar", type=Path)
    parser.add_argument("--device-port", type=int, default=8894)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--restore", type=Path)
    args = parser.parse_args()
    require(SHA.fullmatch(args.apk_sha256) and SHA.fullmatch(args.test_apk_sha256), "Two explicit lowercase candidate SHA-256 values are required")
    require(1 <= args.device_port <= 65535 and args.serial == PHYSICAL, "Only the authorized physical device and a valid disposable port may be used")
    for path, expected in ((args.apk, args.apk_sha256), (args.test_apk, args.test_apk_sha256)):
        if path is not None:
            require(file_digest(path) == expected, "A local APK does not match its explicit hash; do not install it")
    jar = args.hierarchy_jar or cached_hierarchy()
    if args.restore is None:
        file_digest(jar)  # Must exist before the first device mutation; never compile a helper here.
    else:
        require(args.output.resolve() != args.restore.resolve() and args.restore.resolve() not in args.output.resolve().parents,
                "Public output must remain outside the private recovery journal")
    require(not args.output.is_symlink() and (not args.output.exists() or args.output.is_dir() and not any(args.output.iterdir())), "Use a fresh ordinary output directory")
    args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
    device = Device(args.serial)
    require(device.shell("getprop", "ro.serialno").strip() == PHYSICAL and quiet(device), "The fixed device must already have all five audio streams at zero")
    watch = TouchWatch(device)
    guard = Guard(device, watch)
    driver = None
    failed = False
    try:
        driver = Driver.load(device, guard, args.restore, args.output, args.apk_sha256, args.test_apk_sha256) if args.restore else Driver.capture(
            device, guard, args.output, jar, args.apk_sha256, args.test_apk_sha256, args.device_port)
        device.start_log(clear=False)
        if args.restore is None:
            driver.qualify()
        driver.restore()
        driver.result["complete"] = args.restore is None
    except ExternalInput as error:
        failed = True
        if driver:
            driver.data["external_input"] = True
            driver.data["failure"] = str(error)
            driver.save()
            driver.result["failure"] = str(error)
            driver.result["recovery_required"] = True
        raise
    except Exception as error:
        failed = True
        if driver:
            driver.result["failure"] = str(error)
            try:
                driver.restore()
            except Exception as recovery:
                driver.result["recovery_required"] = True
                driver.result["recovery_error"] = str(recovery)
        raise
    finally:
        try:
            if driver:
                if driver.origin:
                    driver.origin.close()  # Only the owned host socket; no UI is reclaimed after interruption.
                with os.fdopen(os.open(driver.directory / ("logcat-" + uuid.uuid4().hex + ".txt"), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
                    stream.write("\n".join(device.log_since(0)) + "\n")
                driver.result["complete"] = driver.result["complete"] and not failed and driver.result.get("restored") is True
                driver.report()
        finally:
            device.stop_log()
            watch.close()


if __name__ == "__main__":
    main()
