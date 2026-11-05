#!/usr/bin/env python3
"""REG-2 public hub/native-touch device checks; never builds or installs an APK.

Example (both APKs must already be installed, with these exact bytes):
  python3 -B tools/tests/registry_device.py --mode basic --apk app-debug.apk \
    --test-apk app-debug-androidTest.apk --output /tmp/registry-basic

Modes basic, malicious, limits and lifecycle use only public native/plugin UI.
Lifecycle retains explicit partial coverage when native crash history prevents
the destructive factory-reset phase, or other documented cases remain pending.
The prebuilt ThinkHierarchy jar may be supplied with --hierarchy-jar; otherwise
only its existing source-addressed /tmp cache is used. No helper is compiled.
Private snapshots and raw instrumentation output live in a mode-0700 journal,
outside artifacts. After interruption inspect it, then use --restore JOURNAL.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import uuid
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools/tests")]
from device_app_files import read_app_file, restore_app_file
from device_hierarchy import node_bounds, visible_nodes
from device_recovery import PATHS, OPTIONAL_PATHS, RECOVERY_PATHS
from media_device import APP, Device
from ui_walkthrough import broadcast_json

PHYSICAL = "HUA0BWUT"
TEST_APP = APP + ".test"
RUNNER = TEST_APP + "/moe.kiwi.thinkos.UpdateInstrumentation"
STATE = "files/plugins/registry-state.json"
INSTALLED = "files/plugins/installed.json"
EXTRA = ("files/store/welcome.json", "files/store/welcome.json.bak",
         "files/cast/identity-seed", "files/cast/airplay-identity", STATE, INSTALLED)
SNAPSHOTS = PATHS + OPTIONAL_PATHS + RECOVERY_PATHS + EXTRA
MAX_PRIVATE_FILE = 8 * 1024 * 1024
PENDING_UPDATES = ("files/update/pending.json", "files/update/pending.json.bak",
                   "files/update/module-pending.json", "files/update/module-pending.json.bak")
CONTROLLED_WINDOWS = {"capture-stop", "instrumentation-start", "instrumentation-end",
                      "lifecycle-restart", "restore-stop"}


def same_iptv_preferences(before, after):
    """Public list/health caches may refresh; every user preference must match."""
    try:
        old, new = json.loads(before), json.loads(after)
        return (isinstance(old, dict) and isinstance(new, dict) and set(old) == set(new) and
                all(old[key] == new[key] for key in old if key not in {"list", "health", "prefs"}) and
                json.loads(old["prefs"]) == json.loads(new["prefs"]))
    except (ValueError, TypeError, KeyError):
        return False


class ProbeError(RuntimeError):
    """Messages are fixed test descriptions, safe to copy to public artifacts."""


class ExternalInput(ProbeError):
    pass


def require(value, message):
    if not value:
        raise ProbeError(message)
    return value


def digest(raw):
    return hashlib.sha256(raw).hexdigest() if raw is not None else None


def file_digest(path):
    require(path.is_file() and not path.is_symlink(), "A fixed regular input file is required")
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def private_json(path, value):
    temporary = path.with_name(path.name + ".new")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def app_exists(device, path):
    # The caller uses fixed paths or validated fixture ids, never author paths.
    raw = device.shell("run-as", APP, "sh", "-c",
                       "if [ -e " + shlex.quote(path) + " ] || [ -L " + shlex.quote(path) +
                       " ]; then echo present; else echo absent; fi").strip()
    require(raw in ("present", "absent"), "Cannot inspect an application path")
    return raw == "present"


def read_json(device, path):
    raw = read_app_file(device.serial, path)
    return json.loads(raw) if raw is not None else None


def installed_sha(device, package):
    paths = device.shell("pm", "path", package).strip().splitlines()
    require(len(paths) == 1 and paths[0].startswith("package:/data/app/") and
            paths[0].endswith(".apk"), "One already-installed, unsplit APK is required")
    actual = device.shell("sha256sum", paths[0][8:]).split()
    require(actual and re.fullmatch(r"[0-9a-f]{64}", actual[0]), "Cannot hash the installed APK")
    return actual[0]


def parse_audio_quiet(raw):
    """Tri-state API27 dump parser; unknown formats keep the old query path.

    Current also lists inactive routes with remembered, possibly nonzero
    indices. Only routes explicitly named by Devices are active. Muted is
    never a substitute for a zero current index. This intentionally recognizes
    a narrow set of exact route names/IDs; other devices fall back to media.
    """
    if not isinstance(raw, str) or len(raw) > 1024 * 1024:
        return None
    wanted = {"STREAM_SYSTEM", "STREAM_RING", "STREAM_MUSIC", "STREAM_ALARM", "STREAM_NOTIFICATION"}
    routes = {"earpiece": 0x1, "speaker": 0x2, "headset": 0x4, "headphone": 0x8,
              "usb_headset": 0x4000000, "default": 0x40000000}
    sections = {}
    current = None
    for line in raw.splitlines():
        if re.match(r"^[ \t]*-[ \t]+STREAM_", line):
            header = re.fullmatch(r"[ \t]*-[ \t]+(STREAM_[A-Z0-9_]+):[ \t]*", line)
            if header is None:
                return None
            name = header[1]
            if name in wanted:
                if name in sections:
                    return None
                current = sections[name] = []
            else:
                current = None
        elif current is not None:
            current.append(line)
    if set(sections) != wanted:
        return None
    active_indices = []
    for lines in sections.values():
        fields = {}
        for line in lines:
            match = re.fullmatch(r"[ \t]*(Muted|Min|Max|Current|Devices):[ \t]*(.*?)[ \t]*", line)
            if match is not None:
                if match[1] in fields:
                    return None
                fields[match[1]] = match[2]
        if set(fields) != {"Muted", "Min", "Max", "Current", "Devices"} or fields["Muted"] not in {"true", "false"}:
            return None
        if not all(re.fullmatch(r"0|[1-9][0-9]{0,9}", fields[key]) for key in ("Min", "Max")):
            return None
        minimum, maximum = int(fields["Min"]), int(fields["Max"])
        if not 0 <= minimum <= maximum <= 2147483647:
            return None
        indices, ids = {}, set()
        for entry in fields["Current"].split(","):
            match = re.fullmatch(r"[ \t]*([0-9a-fA-F]{1,8})[ \t]+\(([a-z0-9_]+)\):[ \t]*(0|[1-9][0-9]{0,9})[ \t]*", entry)
            if match is None:
                return None
            ident, route, index = int(match[1], 16), match[2], int(match[3])
            if route not in routes or ident != routes[route] or route in indices or ident in ids or not 0 <= index <= maximum:
                return None
            indices[route] = index
            ids.add(ident)
        active = [route.strip() for route in fields["Devices"].split(",")]
        if not active or len(set(active)) != len(active) or any(route == "default" or route not in indices for route in active):
            return None
        active_indices.extend(indices[route] for route in active)
    # Validate every required block before returning either decision. A known
    # positive stream plus an unknown/missing stream still needs the fallback.
    return all(index == 0 for index in active_indices)


def quiet(device):
    try:
        parsed = parse_audio_quiet(device.shell("dumpsys", "audio"))
    except (OSError, subprocess.SubprocessError):
        parsed = None
    if parsed is not None:
        return parsed
    # One bounded adb exchange still queries every stream independently. This
    # avoids five USB round trips between observing a short-lived native row
    # and touching it; missing/error/extra observations all fail closed.
    raw = device.shell("sh", "-c", 'for thinkos_probe_stream in 1 2 3 4 5; do media volume --stream "$thinkos_probe_stream" --get || exit 1; done')
    return re.findall(r"\bvolume is ([0-9]+)\b", raw) == ["0"] * 5


def foreground(device):
    windows = device.shell("dumpsys", "window", "windows")
    match = re.search(r"mCurrentFocus=(.*)", windows)
    return bool(match and APP + "/" in match[1])


def focused_component(device):
    line = re.search(r"mCurrentFocus=(.*)", device.shell("dumpsys", "window", "windows"))
    match = re.search(r"\b([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\b", line[1]) if line else None
    return match[1] if match else None


def home_candidates(device):
    raw = device.shell("cmd", "package", "query-activities", "--brief", "-a",
                       "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    result = sorted({line.strip() for line in raw.splitlines()
                     if re.fullmatch(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+", line.strip())})
    require(len(result) <= 16, "Unexpected HOME activity inventory")
    return result


def home_component(device):
    raw = device.shell("cmd", "package", "resolve-activity", "--brief", "-a",
                       "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    result = [line.strip() for line in raw.splitlines()
              if re.fullmatch(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+", line.strip())]
    require(len(result) == 1, "An unambiguous existing HOME preference is required")
    return result[0]


def controlled_check(device, guard, journal):
    """A stopped/ending owned process may expose only a previously recorded HOME.

    A temporary null focus is observed without taking any action. A foreign
    activity, human input, lost monitor or raised audio always stops the run.
    Old journals without a recorded start/end window can recover only from APP.
    """
    deadline = time.monotonic() + 5
    while True:
        guard.check(focused=False)
        component = focused_component(device)
        if component is None and time.monotonic() < deadline:
            time.sleep(.1)
            continue
        app = component is not None and component.startswith(APP + "/")
        own_home = (journal.data.get("controlled_foreground") in CONTROLLED_WINDOWS and
                    not journal.data.get("external_input") and component in journal.data.get("home_candidates", []))
        if not app and not own_home:
            raise ExternalInput("Unexpected foreground outside the owned start/end window; run stopped without reclaiming it")
        guard.watch.check()
        return component


def begin_controlled(journal, device, guard, reason):
    require(reason in CONTROLLED_WINDOWS, "Unknown controlled foreground window")
    controlled_check(device, guard, journal)
    journal.data["controlled_foreground"] = reason
    journal.save()


def controlled_main(journal, device, guard):
    component = controlled_check(device, guard, journal)
    if not component.startswith(APP + "/") or not device.shell("pidof", APP, check=False).strip():
        # The recorded window was entered before our stop/finish, and the
        # component is an original HOME candidate, never an arbitrary package.
        controlled_check(device, guard, journal)
        device.shell("am", "start", "-W", "-n", APP + "/.MainActivity")
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        controlled_check(device, guard, journal)
        if foreground(device):
            journal.data["controlled_foreground"] = None
            journal.save()
            guard.check()
            return
        time.sleep(.25)
    raise ProbeError("The explicitly restarted MainActivity did not return normally")


class TouchWatch:
    """Kernel touchscreen events identify a human; adb InputManager injections do not.

    All detected touch panels are watched. An inaccessible/lost monitor fails
    closed. No input event or raw hardware inventory is copied to artifacts.
    """
    def __init__(self, device):
        inventory = device.shell("getevent", "-lp", check=False)
        prefix = []
        if "ABS_MT_POSITION_X" not in inventory:
            # Fixed read-only fallback for ROMs that deny shell access to evdev.
            # This never installs a service or writes an input device.
            inventory = device.shell("su", "-c", "getevent -lp", check=False)
            prefix = ["su", "-c"]
        paths = []
        for block in re.split(r"add device \d+: ", inventory)[1:]:
            path = block.splitlines()[0].strip()
            axes = "ABS_MT_POSITION_X" in block and "ABS_MT_POSITION_Y" in block
            if re.fullmatch(r"/dev/input/event[0-9]+", path) and axes:
                paths.append(path)
        require(paths, "No readable touchscreen inventory; refusing an unmonitored run")
        self.interrupted = threading.Event()
        self.reason = None
        command = shlex.join(["getevent", "-lt"] + paths)
        if prefix:
            command = shlex.join(prefix + [command])
        self.process = subprocess.Popen(device.base + ["shell", "-T", command],
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
        self.stopping = False
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
        time.sleep(.25)
        self.check()

    def _read(self):
        for line in self.process.stdout:
            if re.search(r"\[\s*\d+\.\d+\].*\bEV_(?:ABS|KEY)\b", line):
                self.reason = self.reason or "hardware input observed"
                self.interrupted.set()
        if not self.stopping:
            self.reason = self.reason or "input monitor stream ended"
            self.interrupted.set()

    def check(self):
        if self.interrupted.is_set() or self.process.poll() is not None:
            raise ExternalInput((self.reason or "input monitor exited") + "; run stopped")

    def close(self):
        self.stopping = True
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)


class Guard:
    def __init__(self, device, watch):
        self.device, self.watch = device, watch

    def check(self, focused=True):
        self.watch.check()
        if not quiet(self.device):
            raise ExternalInput("An audio stream is no longer zero; run stopped")
        if focused and not foreground(self.device):
            raise ExternalInput("ThinkOS lost the foreground; run stopped without taking it back")
        self.watch.check()


def owned_ids(token):
    prefix = "reg-probe-" + token[:12]
    return {prefix, prefix + "-home", prefix + "-settings", prefix + "-system", prefix + "-tampered"} | {
        prefix + "-limit-" + str(index).zfill(2) for index in range(17)}


class Journal:
    def __init__(self, directory, data):
        self.directory, self.data = directory, data

    @classmethod
    def capture(cls, device, guard, ui, apk_sha, test_sha):
        guard.check()
        token = uuid.uuid4().hex
        directory = Path(tempfile.mkdtemp(prefix="thinkos-registry-recovery-"))
        os.chmod(directory, 0o700)
        journal = cls(directory, {"version": 1, "physical": PHYSICAL, "serial": device.serial, "token": token,
                                 "stage": "snapshot-started", "created": time.time(), "ui": ui,
                                 "apk_sha256": apk_sha, "test_apk_sha256": test_sha, "files": {},
                                 "home_candidates": home_candidates(device), "home": home_component(device)})
        journal.save()
        print("Private recovery journal: " + str(directory), flush=True)
        begin_controlled(journal, device, guard, "capture-stop")
        device.shell("am", "force-stop", APP)
        controlled_check(device, guard, journal)
        for path in SNAPSHOTS:
            controlled_check(device, guard, journal)
            raw = read_app_file(device.serial, path)
            require(raw is None or len(raw) <= MAX_PRIVATE_FILE, "A private snapshot exceeds its size limit")
            journal.data["files"][path] = None if raw is None else {
                "base64": base64.b64encode(raw).decode("ascii"), "sha256": digest(raw)}
            journal.save()
        require(not app_exists(device, "files/registry"), "Registry cache appeared before fixture startup")
        paths = ["cache/update-device-" + token]
        for ident in sorted(owned_ids(token)):
            paths.extend(("files/plugins/" + ident, "files/store/" + ident + ".json", "files/store/" + ident + ".json.bak"))
        script = ('for probe_path in ' + shlex.join(paths) +
                  '; do if [ -e "$probe_path" ] || [ -L "$probe_path" ]; then echo "$probe_path"; fi; done')
        require(not device.shell("run-as", APP, "sh", "-c", script).strip(),
                "A fixture-owned path already exists; preserve it rather than overwriting")
        controlled_check(device, guard, journal)
        journal.data["owned_absent"] = sorted(owned_ids(token))
        iptv = journal.original("files/store/iptv.json")
        if iptv is not None and same_iptv_preferences(iptv, iptv):
            journal.data["preserved_caches"] = ["files/store/iptv.json"]
        journal.data["stage"] = "snapshot-complete"
        journal.checkpoint(device)
        return journal

    @classmethod
    def load(cls, directory):
        require(directory.is_dir() and not directory.is_symlink(), "A private recovery directory is required")
        require(directory.stat().st_uid == os.getuid() and directory.stat().st_mode & 0o077 == 0,
                "Recovery directory must belong to this user with mode 0700")
        path = directory / "state.json"
        require(path.is_file() and not path.is_symlink() and path.stat().st_uid == os.getuid() and
                path.stat().st_mode & 0o077 == 0 and path.stat().st_size < 64 * 1024 * 1024,
                "Invalid private recovery file")
        data = json.loads(path.read_text())
        require(data.get("version") == 1 and data.get("physical") == PHYSICAL and
                re.fullmatch(r"[0-9a-f]{32}", data.get("token", "")) and
                set(data.get("files", {})) == set(SNAPSHOTS), "Invalid recovery contract")
        require(data.get("owned_absent") == sorted(owned_ids(data["token"])),
                "Recovery lacks proof that its disposable plugin/store paths were initially absent")
        require(set(data.get("firewall", [])) <= {"iptables", "ip6tables"} and
                (not data.get("firewall") or type(data.get("uid")) is int and
                 10000 <= data["uid"] <= 199999), "Invalid recovery network scope")
        require(set(data.get("preserved_caches", [])) <= {"files/store/iptv.json"}, "Invalid preserved cache scope")
        homes = data.get("home_candidates", [])
        require(isinstance(homes, list) and len(homes) <= 16 and all(isinstance(value, str) and
                re.fullmatch(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+", value) for value in homes),
                "Invalid recorded HOME candidates")
        require(data.get("controlled_foreground") is None or data["controlled_foreground"] in CONTROLLED_WINDOWS,
                "Invalid controlled foreground window")
        require(data.get("home") is None or isinstance(data["home"], str) and
                re.fullmatch(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+", data["home"]), "Invalid HOME preference")
        for key in ("apk_sha256", "test_apk_sha256"):
            require(re.fullmatch(r"[0-9a-f]{64}", data.get(key, "")), "Invalid recovery APK identity")
        journal = cls(directory, data)
        for path in SNAPSHOTS:
            journal.original(path)
        return journal

    def original(self, path):
        item = self.data["files"][path]
        if item is None:
            return None
        raw = base64.b64decode(item["base64"], validate=True)
        require(len(raw) <= MAX_PRIVATE_FILE and digest(raw) == item["sha256"], "Recovery checksum differs")
        return raw

    def save(self):
        private_json(self.directory / "state.json", self.data)

    def checkpoint(self, device):
        self.data["expected"] = {path: digest(read_app_file(device.serial, path)) for path in SNAPSHOTS}
        self.data["observed_at"] = time.time()
        self.save()

    def unchanged_since_checkpoint(self, device):
        require(set(self.data.get("expected", {})) == set(SNAPSHOTS), "Recovery lacks a complete last observation")
        restoring = self.data.get("stage") == "restoration-started"
        # Owned registry records can naturally finish their original trial while
        # the script is stopped. Their ids and all non-fixture installed entries
        # receive stricter structural checks in restore_run instead.
        for path in self.data.get("preserved_caches", []):
            require(same_iptv_preferences(self.original(path), read_app_file(device.serial, path)),
                    "IPTV user preferences changed; preserve the recovery journal")
        require(all(digest(read_app_file(device.serial, path)) in
                    ({value, digest(self.original(path))} if restoring else {value})
                    for path, value in self.data["expected"].items() if path not in (STATE, INSTALLED) and
                    path not in self.data.get("preserved_caches", [])),
                "Private files changed after the last observation; audit the journal before recovery")


def ensure_clean_baseline(device):
    require(focused_component(device) in (APP + "/.MainActivity", APP + "/" + APP + ".MainActivity"),
            "Begin on the normal MainActivity")
    require(not any(app_exists(device, path) for path in PENDING_UPDATES),
            "Preserve an existing application/module update transaction")
    require(not any(broadcast_json(device, command).get("showing") for command in ("update_state", "legal_state")),
            "Preserve an existing native legal/update window")
    require(not app_exists(device, "files/registry"), "Preserve existing authenticated registry cache and sequence floor")
    state = read_json(device, STATE)
    require(state is None or state == {"schema": 1, "disabled": False, "installed": [], "blocked": []},
            "Preserve existing registry records, disabled state and rollback history")
    if app_exists(device, "files/dev-plugins"):
        require(not device.shell("run-as", APP, "ls", "-A", "files/dev-plugins").strip(),
                "Development plugin overrides would invalidate the factory hub test")
    ui = broadcast_json(device, "ui_state")
    stage = broadcast_json(device, "stage_state")
    require(not ui.get("safeMode") and not ui.get("onboardingOpen") and
            not any(stage.get(key) for key in ("canGoBack", "pageBack", "panelOpen", "touching", "practice")) and
            ui.get("page"), "Begin on an idle base page with no onboarding or return stack")
    media = broadcast_json(device, "media_state")
    require(not any(media.get(key) for key in ("owner", "kind", "count")) and media.get("status") == "stopped",
            "Do not interrupt an existing media/cast session or queued playlist")
    schema = json.loads((ROOT / "shared/schema/settings/system.json").read_text())["fields"]
    layers = [read_json(device, "files/settings/" + name + ".json") or {} for name in ("local", "server")]
    def setting(key):
        return next((layer["system"][key] for layer in layers if key in layer.get("system", {})), schema[key]["default"])
    # Preserve a previously unfinished setup instead of silently completing it.
    # The actual foreground must stay outside onboarding throughout this run.
    require(setting("plugins.auto_update") in ("off", "official"), "Disable reviewed/community automatic updates through settings first")
    require(setting("plugins.max_installed") == 16 and setting("plugins.trial_s") == 600,
            "Acceptance requires the real 16-install limit and 600-second trial; thresholds are never changed")
    return ui


class Hierarchy:
    def __init__(self, device, guard, token, jar):
        self.device, self.guard = device, guard
        self.token, self.counter = token, 0
        self.remote = "/data/local/tmp/thinkos-registry-" + token
        self.jar = self.remote + "/hierarchy.jar"
        guard.check()
        require(device.shell("sh", "-c", "if [ -e " + shlex.quote(self.remote) +
                             " ]; then echo present; else echo absent; fi").strip() == "absent",
                "A shell fixture directory already exists; preserve it")
        device.shell("mkdir", "-m", "700", self.remote)
        guard.check()
        device.shell("mkdir", "-p", "/data/local/tmp/local/tmp")
        guard.check()
        device.adb("push", jar, self.jar, timeout=30)

    def tree(self, *, all_windows=False):
        self.guard.check()
        self.counter += 1
        name = "registry-" + self.token + "-" + str(self.counter) + ".xml"
        source = "/data/local/tmp/local/tmp/" + name
        try:
            arguments = ["uiautomator", "runtest", self.jar, "-c", "ThinkHierarchy",
                         "-e", "path", name, "-e", "compressed", "false"]
            if all_windows: arguments.extend(("-e", "allWindows", "true"))
            command = shlex.join(arguments)
            subprocess.run(self.device.base + ["shell", "-T", command], capture_output=True, check=True, timeout=40)
            raw = self.device.shell("cat", source)
            require(len(raw.encode()) <= 2 * 1024 * 1024, "Native hierarchy exceeds the bounded observation limit")
            root = ET.fromstring(raw)
            self.guard.check()
            return root
        finally:
            self.guard.check()
            self.device.shell("rm", "-f", source, source + ".writing")


class Driver:
    def __init__(self, device, guard, journal, output, jar, mode):
        self.device, self.guard, self.journal = device, guard, journal
        self.output, self.mode = output, mode
        self.token = journal.data["token"]
        self.area = "cache/update-device-" + self.token + "/registry/"
        self.ids = owned_ids(self.token)
        self.generation = 0
        self.active = None
        self.hierarchy = None
        self.fixture_paths = set()
        self.jar = jar
        self.result = {"complete": False, "mode": mode, "physical_serial": PHYSICAL,
                       "apk_sha256": journal.data["apk_sha256"], "test_apk_sha256": journal.data["test_apk_sha256"],
                       "checks": [], "private_recovery_journal": str(journal.directory),
                       "scope": "Actual public factory hub and native touchscreen confirmation; no performance, health or lifecycle claim"}

    def save(self):
        path = self.output / "results.json"
        temporary = path.with_name("results.json.new")
        temporary.write_text(json.dumps(self.result, ensure_ascii=False, indent=2) + "\n")
        os.replace(temporary, path)

    def check(self, name, value):
        self.result["checks"].append({"name": name, "passed": bool(value)})
        self.save()
        print(("PASS " if value else "FAIL ") + name, flush=True)
        require(value, name)
        return value

    def wait(self, predicate, seconds=35, focused=True, allow_exit=False):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            if focused:
                self.guard.check()
            else:
                controlled_check(self.device, self.guard, self.journal)
            if not allow_exit and self.active is not None and self.active.poll() is not None:
                raise ProbeError("Instrumentation exited before its requested finish")
            value = predicate()
            if value:
                return value
            time.sleep(.25)
        return None

    def ui(self):
        return broadcast_json(self.device, "ui_state")

    def screen(self):
        state = broadcast_json(self.device, "stage_state")
        require(state.get("width", 0) > 0 and state.get("height", 0) > 0, "No real stage geometry")
        return state["width"], state["height"]

    def nodes(self):
        return [dict(node.attrib) for node in visible_nodes(self.hierarchy.tree()) if node.get("package") == APP]

    def node(self, label=None, resource=None, tab=False):
        matches = [node for node in self.nodes() if (resource and node.get("resource-id") == resource) or
                   (label and label in (node.get("text"), node.get("content-desc")))]
        if tab:
            _, height = self.screen()
            matches = [node for node in matches if node_bounds(node)[1] < height * .30]
        return min(matches, key=lambda node: node_bounds(node)[1]) if matches else None

    def input(self, *args):
        self.guard.check()
        submitted = time.monotonic()
        self.device.shell("input", *args)
        self.guard.check()
        time.sleep(.25)
        return submitted

    def photo(self, name):
        self.guard.check()
        page = self.ui().get("page", "")
        require(page == "hub.main" or page in {ident + ".main" for ident in self.ids},
                "Only fixture-owned UI may be captured as a public artifact")
        self.device.screenshot(self.output / (name + ".png"))
        self.guard.check()

    def tap_node(self, node):
        left, top, right, bottom = node_bounds(node)
        require(left >= 0 and top >= 0 and right > left and bottom > top, "Native target is not visible")
        return self.input("touchscreen", "tap", (left + right) // 2, (top + bottom) // 2)

    def scroll(self, upward=True):
        width, height = self.screen()
        self.input("touchscreen", "swipe", width // 2, int(height * (.78 if upward else .32)),
                   width // 2, int(height * (.32 if upward else .78)), 380)

    def tap(self, label, scroll=False, tab=False):
        node = self.wait(lambda: self.node(label=label, tab=tab), seconds=12)
        if node is None and scroll:
            for _ in range(10):
                self.scroll()
                node = self.node(label=label, tab=tab)
                if node:
                    break
        require(node is not None, "A required native hub label is not visible: " + label)
        self.tap_node(node)

    def native(self):
        return self.node(resource="android:id/button1")

    def home(self):
        width, height = self.screen()
        self.input("touchscreen", "swipe", width // 2, height - 2, width // 2, height // 3, 400)
        return self.wait(lambda: self.ui().get("page") == "home.main")

    def open_hub(self):
        require(self.home(), "The real bottom-edge gesture must reach factory home")
        seen = set()
        for _ in range(16):
            page = self.ui().get("page")
            if page == "tiles.apps":
                break
            require(page not in seen, "The apps tile page is absent from the actual page sequence")
            seen.add(page)
            width, height = self.screen()
            self.input("touchscreen", "swipe", int(width * .86), height // 2, int(width * .15), height // 2, 400)
            require(self.wait(lambda: self.ui().get("page") != page), "The real horizontal gesture did not change a base page")
        require(self.ui().get("page") == "tiles.apps", "Cannot reach the real apps tile page")
        self.tap("插件中心", scroll=True)
        self.check("the actual factory app tile opens hub", self.wait(lambda: self.ui().get("page") == "hub.main"))

    def control(self, action, **values):
        self.guard.check()
        if action == "finish":
            begin_controlled(self.journal, self.device, self.guard, "instrumentation-end")
        self.generation += 1
        command = {"generation": self.generation, "action": action, **values}
        raw = json.dumps(command, separators=(",", ":")).encode()
        require(len(raw) <= 4096, "Fixture control exceeds its fixed bound")
        temporary = self.area + "control.json.host-new"
        restore_app_file(self.device.serial, temporary, raw)
        self.device.shell("run-as", APP, "mv", temporary, self.area + "control.json")
        response = self.wait(lambda: self.response(), seconds=30, focused=action != "finish", allow_exit=action == "finish")
        require(response is not None and response.get("handled") is True and not response.get("error"),
                "The fixed fixture control did not complete")
        return response

    def response(self):
        value = read_json(self.device, self.area + "response.json")
        return value if value and value.get("generation") == self.generation else None

    def refresh(self, expected_seq):
        self.tap("发现", tab=True)  # Real hub tab clears selected detail and notice.
        def refresh_action():
            labels = {"刷新", "目录刷新失败", "目录可能过旧"}
            return next((node for node in self.nodes() if
                         node.get("text") in labels or node.get("content-desc") in labels), None)
        footer = self.wait(refresh_action)
        require(footer is not None, "The real hub refresh action is not available")
        self.tap_node(footer)
        require(self.wait(lambda: (read_json(self.device, "files/registry/ledger.json") or {}).get("seq") == expected_seq),
                "A real hub refresh must authenticate and commit the published sequence")
        require(self.wait(lambda: self.node(label="刷新")), "Hub did not finish its actual refresh")
        self.scroll(upward=False)

    def publish(self, sample="normal", **values):
        response = self.control("publish", sample=sample, **values)
        self.refresh(response["seq"])

    def requests(self):
        response = self.control("checkpoint")
        hits = response.get("requests", [])
        denied = {"cookie", "accept-language", "x-device-id", "x-android-id", "x-serial", "authorization"}
        self.check("registry requests contain no cookies, language, identity or authorization headers",
                   all(not (set(map(str.lower, hit.get("headers", {}))) & denied) for hit in hits))
        paths = [hit["path"] for hit in hits]
        self.fixture_paths.update(paths)
        return paths

    def records(self):
        value = read_json(self.device, STATE)
        return value.get("installed", []) if value else []

    def record(self, ident):
        return next((item for item in self.records() if item["id"] == ident), None)

    def diagnostics(self):
        mark = self.device.log_mark()
        self.device.debug("state")  # Read-only OS diagnostics; raw AppState stays private.
        def observed():
            return next((line for line in self.device.log_since(mark) if "DEBUG state=" in line and " plugins=[" in line), None)
        line = self.wait(observed, seconds=10)
        require(line is not None, "No actual runtime diagnostic checkpoint")
        entries = re.findall(r"PluginDiagnostic\(id=([^,]+), version=([^,]+), bytes=(\d+), disabled=(true|false)", line.split(" plugins=", 1)[1])
        require(entries, "The actual runtime diagnostic checkpoint is empty")
        return {ident: {"version": version, "bytes": int(size), "disabled": disabled == "true"} for ident, version, size, disabled in entries}

    def logs(self, ident, phrase):
        return [line for line in self.device.log_since(0) if "[plugin:" + ident + "]" in line and phrase in line]

    def no_leak(self):
        return not any("REGISTRY_EVENT_LEAK" in line for line in self.device.log_since(0))

    def confirm(self, ident, name, accessibility=False, permissions=("network",)):
        require(self.wait(self.native), "A real native permission confirmation must appear")
        texts = "\n".join(node.get("text", "") for node in self.nodes())
        commit = hashlib.sha256((ident + "@1.0.0").encode()).hexdigest()[:40]
        self.check(name + " native confirmation pins the real source, tier and permission reason",
                   name in texts and "thinkos-tests/registry-probe@" + commit in texts and "已审核" in texts and
                   all(label in texts for permission, label in (("network", "读取本地测试台内容"),) if permission in permissions))
        self.photo("confirm-" + ident)
        before = read_app_file(self.device.serial, STATE)
        if accessibility:
            before_hits = self.requests()
            self.control("accessibility_accept")
            self.check("accessibility ACTION_CLICK neither approves nor dismisses the real native confirmation",
                       before == read_app_file(self.device.serial, STATE) and self.native() is not None)
            new_paths = self.requests()[len(before_hits):]
            self.check("accessibility approval attempts do not download a package",
                       not any(path.startswith("/blobs/") or path.startswith("/registry/files/") for path in new_paths))
            self.photo("accessibility-not-approved")
        # No performAction/click shortcut: SOURCE_TOUCHSCREEN enters Android's
        # real input dispatch and gives the native button fresh down/up credit.
        self.tap_node(require(self.native(), "Native confirmation disappeared before touchscreen approval"))
        require(self.wait(lambda: self.native() is None), "Native touchscreen approval did not dismiss the dialog")

    def detail(self, name):
        self.tap(name, scroll=True)
        # Installed entries have extra actions: Back can be below the viewport.
        require(self.wait(lambda: self.node(label="来源")), "Hub did not enter the actual plugin detail")

    def installed_ok(self, ident, expected_digest, confirmed):
        record = self.record(ident)
        diagnostic = self.diagnostics().get(ident)
        return (record is not None and record.get("enabled") is True and record.get("trial") is True and
                record.get("version") == "1.0.0" and record.get("digest") == expected_digest and
                set(record.get("confirmed", [])) == set(confirmed) and diagnostic is not None and
                diagnostic["bytes"] > 0 and not diagnostic["disabled"])

    def uninstall(self, ident, name):
        self.tap("已安装", tab=True)
        self.detail(name)
        self.tap("卸载", scroll=True)
        self.check(name + " is removed through the real public hub uninstall action",
                   self.wait(lambda: self.record(ident) is None) and self.wait(lambda: ident not in self.diagnostics()))

    def start(self):
        self.save()
        self.device.start_log(clear=False)
        begin_controlled(self.journal, self.device, self.guard, "instrumentation-start")
        log_path = self.journal.directory / "instrumentation.log"
        with os.fdopen(os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            self.active = subprocess.Popen(self.device.base + ["shell", "-T", "am", "instrument", "-w", "-e",
                "run_id", self.token, "-e", "phase", "registry", RUNNER], stdout=stream, stderr=subprocess.STDOUT)
        ready = self.wait(lambda: read_json(self.device, self.area + "ready.json"), seconds=90, focused=False)
        require(ready is not None and ready.get("uid", 0) >= 10000 and ready.get("id") in self.ids,
                "RAM-only production-UID registry fixture did not become ready")
        prefix = "reg-probe-" + self.token[:12]
        require(ready["id"] == prefix and set(ready.get("samples", {})) ==
                {"home", "settings", "system", "tampered-blob"} and
                all(ready["samples"][sample]["id"] == prefix + suffix for sample, suffix in
                    (("home", "-home"), ("settings", "-settings"), ("system", "-system"), ("tampered-blob", "-tampered"))) and
                [item["id"] for item in ready.get("limits", [])] == [prefix + "-limit-" + str(index).zfill(2) for index in range(17)] and
                all(re.fullmatch(r"[0-9a-f]{64}", value) for value in ready["digests"].values()),
                "The fixture identity set does not match this run token")
        self.ready = ready
        self.check("main process uses the supplied APK and RAM-only authenticated registry fixture",
                   installed_sha(self.device, APP) == self.journal.data["apk_sha256"] and
                   ready["uid"] == int(self.device.shell("run-as", APP, "id", "-u").strip()) and
                   str(ready["pid"]) in self.device.shell("pidof", APP).split())
        self.journal.data["fixture_pid"] = ready["pid"]
        require(self.wait(lambda: foreground(self.device), focused=False), "The fixture did not start ThinkOS normally")
        self.journal.data["controlled_foreground"] = None
        self.journal.save()
        self.guard.check()
        self.device.debug("locale", value="zh-CN")
        self.guard.check()
        self.device.debug("hold_attention", value="ENGAGED")
        self.guard.check()
        self.device.shell("am", "broadcast", "-n", APP + "/.DebugReceiver", "--es", "command", "orientation", "--ei", "value", 1)
        self.hierarchy = Hierarchy(self.device, self.guard, self.token, self.jar)
        self.journal.data["stage"] = "fixture-running"
        self.journal.checkpoint(self.device)
        self.open_hub()
        self.publish(level=1)

    def basic(self):
        ident = self.ready["id"]
        self.detail("仓库测试")
        self.tap("安装", scroll=True)
        self.confirm(ident, "仓库测试", accessibility=True)
        require(self.wait(lambda: self.record(ident) is not None), "The real install never committed its record")
        self.check("v1 is an actual enabled Lua VM with only the native-confirmed network grant",
                   self.wait(lambda: self.installed_ok(ident, self.ready["digests"]["1.0.0"], {"network"}), seconds=60))
        self.check("the unprivileged registry Lua denies all seven management APIs",
                   self.wait(lambda: any(re.search(r"REGISTRY_DENIED\s+1\.0\.0\s+7\b", line) for line in self.logs(ident, "REGISTRY_DENIED"))))
        self.tap("打开", scroll=True)
        self.check("the real hub open action enters the installed app page",
                   self.wait(lambda: self.ui().get("page") == ident + ".main") and self.wait(lambda: self.node(label="仓库测试 1.0.0")))
        self.photo("basic-open")
        self.input("keyevent", "KEYCODE_BACK")
        self.check("the public Back path returns from the registry app to hub", self.wait(lambda: self.ui().get("page") == "hub.main"))
        self.uninstall(ident, "仓库测试")
        self.check("the public installed tab is genuinely empty after uninstall", self.wait(lambda: self.node(label="尚未安装插件")))
        self.check("management install/uninstall events never reach the unprivileged registry Lua", self.no_leak())
        self.requests()

    def malicious(self):
        for sample in ("home", "settings", "system", "tampered-blob"):
            entry = self.ready["samples"][sample]
            ident, name = entry["id"], entry["name"]
            self.publish(sample)
            self.detail(name)
            before_state = read_app_file(self.device.serial, STATE)
            before_hits = self.requests()
            self.tap("安装", scroll=True)
            self.confirm(ident, name)
            self.check(sample + " is rejected by the actual device after real native approval",
                       self.wait(lambda: self.node(label="操作失败")))
            self.photo("rejected-" + sample)
            after_hits = self.requests()
            new_hits = after_hits[len(before_hits):]
            self.check(sample + " reached the signed payload validation path",
                       "/registry/files/" + entry["digest"] + ".json" in new_hits and any(path.startswith("/blobs/") for path in new_hits))
            self.check(sample + " has no permission journal, active VM or executed Lua",
                       read_app_file(self.device.serial, STATE) == before_state and self.record(ident) is None and
                       ident not in self.diagnostics() and ident not in broadcast_json(self.device, "plugin_cpu") and
                       not self.logs(ident, "REGISTRY_STARTED") and
                       read_app_file(self.device.serial, "files/store/" + ident + ".json") is None)
            if sample == "tampered-blob":
                sha = self.ready["tampered_blob_sha256"]
                self.check("the same-digest, same-size corrupted Lua blob was actually requested",
                           "/blobs/" + sha[:2] + "/" + sha in new_hits)
            self.check(sample + " does not replace the factory home role", self.home())
            self.check("malicious source cannot receive private management events", self.no_leak())
            self.open_hub()
            self.journal.checkpoint(self.device)

    def limits(self):
        self.publish("limits", limit_count=17)
        installed = []
        for entry in self.ready["limits"][:16]:
            ident, name = entry["id"], entry["name"]
            self.tap("发现", tab=True)
            self.scroll(upward=False)
            self.detail(name)
            self.tap("安装", scroll=True)
            self.confirm(ident, name, permissions=())
            require(self.wait(lambda: self.record(ident) is not None), "A real limit fixture install did not commit")
            self.check(name + " has a real VM and persisted native-confirmed record",
                       self.wait(lambda: self.installed_ok(ident, entry["digest"], set()), seconds=60))
            installed.append(entry)
            self.check("every installed fixture still denies all management APIs",
                       self.wait(lambda: any(re.search(r"REGISTRY_DENIED\s+1\.0\.0\s+7\b", line) for line in self.logs(ident, "REGISTRY_DENIED"))))
        before = read_json(self.device, STATE)
        before_hits = self.requests()
        entry = self.ready["limits"][16]
        self.tap("发现", tab=True)
        self.scroll(upward=False)
        self.detail(entry["name"])
        self.tap("安装", scroll=True)
        self.check("the seventeenth real hub install fails before requesting another native grant",
                   self.wait(lambda: self.node(label="操作失败")) and self.native() is None)
        paths = self.requests()[len(before_hits):]
        after = read_json(self.device, STATE)
        def without_health(value):
            return {**value, "installed": [{key: data for key, data in record.items() if key != "trial"}
                                          for record in value["installed"]]}
        diagnostics = self.diagnostics()
        self.check("the actual limit preserves all sixteen digests and grants and makes no seventeenth download or VM",
                   len(self.records()) == 16 and without_health(after) == without_health(before) and
                   all(value["id"] in diagnostics and diagnostics[value["id"]]["bytes"] > 0 and
                       not diagnostics[value["id"]]["disabled"] for value in installed) and
                   entry["id"] not in diagnostics and entry["id"] not in broadcast_json(self.device, "plugin_cpu") and
                   not any(path.startswith("/blobs/") or path.startswith("/registry/files/") for path in paths))
        self.check("all sixteen actual running instances preserve management event isolation", self.no_leak())
        for entry in installed:
            self.uninstall(entry["id"], entry["name"])
        self.check("all sixteen are publicly uninstalled with no remaining registry VM or record",
                   not self.records() and not (self.ids & self.diagnostics().keys()))

    def finish(self):
        require(not self.records(), "Public uninstall must finish before the fixture is stopped")
        if getattr(self, "lifecycle_fixture_finished", False):
            checks = self.result.get("instrumentation_checks", [])
            require(self.active is None and checks and all(item.get("passed") is True for item in checks),
                    "Lifecycle must prove its original instrumentation completed before restart")
            self.result["fixture_paths"] = sorted(self.fixture_paths)
            self.result["complete"] = True
            self.save()
            return
        response = self.control("finish")
        require(response["handled"], "Fixture finish was not acknowledged")
        require(self.active is not None, "Instrumentation process is absent")
        self.active.wait(timeout=30)
        controlled_check(self.device, self.guard, self.journal)
        report = read_json(self.device, "cache/update-device-" + self.token + "/report.json")
        self.check("the real instrumentation completed every fixture and negative-approval assertion",
                   report is not None and report.get("complete") is True and report.get("checks") and
                   all(item.get("passed") is True for item in report["checks"]))
        self.result["instrumentation_checks"] = [{"name": item["name"], "passed": item["passed"]} for item in report["checks"]]
        self.result["fixture_paths"] = sorted(self.fixture_paths)
        self.result["complete"] = True
        self.save()

    def preserve(self, error):
        self.result["error"] = str(error) if isinstance(error, ProbeError) else type(error).__name__
        self.result["recovery_required"] = True
        # A physical interruption must never be followed by force-stop/restart,
        # taps, cleanup or a stale snapshot write. Raw failure details stay private.
        with os.fdopen(os.open(self.journal.directory / "failure.txt", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            traceback.print_exc(file=stream)
        restoring = self.journal.data.get("stage") == "restoration-started"
        if not restoring:
            self.journal.data["stage"] = "interrupted"
        self.journal.data["external_input"] = isinstance(error, ExternalInput)
        self.journal.data["live_instrumentation"] = self.active is not None and self.active.poll() is None
        self.result["live_instrumentation_retained"] = self.journal.data["live_instrumentation"]
        if not isinstance(error, ExternalInput) and not restoring:
            # A failed assertion is recoverable without endorsing later user
            # changes. External input instead preserves the previous observation.
            try:
                self.guard.check()
                time.sleep(1)
                self.guard.check()
                self.journal.checkpoint(self.device)
            except Exception:
                pass
        self.journal.save()
        self.save()

    def close(self):
        if self.device._logcat:
            # Raw AppState and unrelated plugin logs must not enter artifacts.
            lines = [line for line in self.device.log_since(0) if "REGISTRY_" in line and
                     any("[plugin:" + ident + "]" in line for ident in self.ids)]
            (self.output / "fixture-logcat.txt").write_text("\n".join(lines) + "\n")
            self.device.stop_log()


def recovery_network(journal, device, blocked, guard):
    """Own only two temporary per-UID rules during byte verification."""
    if blocked:
        controlled_check(device, guard, journal)
        match = re.search(r"\buserId=(\d+)", device.shell("dumpsys", "package", APP))
        require(match and 10000 <= int(match[1]) <= 199999, "Unexpected recovery UID")
        require(journal.data.get("uid", int(match[1])) == int(match[1]), "Recovery UID changed")
        journal.data["uid"] = int(match[1])
    programs = ("iptables", "ip6tables") if blocked else tuple(journal.data.get("firewall", []))
    for program in programs:
        rule = ["OUTPUT", "-m", "owner", "--uid-owner", str(journal.data["uid"]),
                "-m", "comment", "--comment", "thinkos-registry-" + journal.data["token"], "-j", "REJECT"]
        def run(arguments):
            controlled_check(device, guard, journal)
            return subprocess.run(device.base + ["shell", "-T", shlex.join(["su", "-c",
                shlex.join([program] + arguments)])], capture_output=True, timeout=25)
        current = run(["-C"] + rule)
        require(current.returncode in (0, 1), "Cannot inspect the owned recovery network rule")
        if blocked:
            if program not in journal.data.setdefault("firewall", []):
                journal.data["firewall"].append(program)
                journal.save()
            if current.returncode == 1:
                require(run(["-I", "OUTPUT", "1"] + rule[1:]).returncode == 0,
                        "Cannot install the owned recovery network rule")
        else:
            if current.returncode == 0:
                require(run(["-D"] + rule).returncode == 0, "Cannot remove the owned recovery network rule")
            require(run(["-C"] + rule).returncode == 1, "The owned recovery network rule remains")
            journal.data["firewall"].remove(program)
            journal.save()


def restore_run(journal, device, guard, *, continuing=False):
    """Restore only a proven private baseline; preserve uncertain or newer data."""
    controlled_check(device, guard, journal)
    require(device.shell("getprop", "ro.serialno").strip() == PHYSICAL, "Recovery requires the authorized physical device")
    require(installed_sha(device, APP) == journal.data["apk_sha256"] and
            installed_sha(device, TEST_APP) == journal.data["test_apk_sha256"], "Recovery APK identity changed; audit before restoring")
    # Success is not permission to overwrite changes after the last owned
    # observation. The registry's natural health fields have their own checks.
    journal.unchanged_since_checkpoint(device)
    allowed = owned_ids(journal.data["token"])
    state = read_json(device, STATE)
    require(state is None or (all(item.get("id") in allowed for item in state.get("installed", [])) and
                              all(item.get("id") in allowed for item in state.get("blocked", []))),
            "A non-fixture registry record appeared; leave user data untouched")
    installed = read_json(device, INSTALLED) or {}
    original_installed_raw = journal.original(INSTALLED)
    original_installed = json.loads(original_installed_raw) if original_installed_raw is not None else {}
    require({key: value for key, value in installed.items() if key not in allowed} == original_installed,
            "Non-fixture installed plugin metadata changed; audit before restoring")
    cache = read_json(device, "files/registry/cache.json")
    if app_exists(device, "files/registry"):
        children = device.shell("run-as", APP, "ls", "-A", "files/registry").splitlines()
        require(set(children) <= {"blobs", "cache.json", "cache.json.bak", "cache.json.new",
                                  "ledger.json", "ledger.json.bak", "ledger.json.new"},
                "Unknown files appeared inside the disposable registry cache; preserve them")
    if cache is not None:
        catalog = json.loads(base64.b64decode(cache["document"], validate=True))
        require(all(item.get("id") in allowed for item in catalog.get("plugins", [])),
                "A non-fixture authenticated catalog appeared; preserve it")
    if journal.data.get("external_input"):
        # An explicit recovery invocation may continue only after the owner
        # returned to APP and every last-observation byte check passed above.
        require(foreground(device), "Return to ThinkOS before recovering an externally interrupted run")
        journal.data["external_input"] = False
        journal.save()
    begin_controlled(journal, device, guard, "restore-stop")
    device.shell("am", "force-stop", APP)
    controlled_check(device, guard, journal)
    journal.data["stage"] = "restoration-started"
    journal.checkpoint(device)
    recovery_network(journal, device, True, guard)
    # The production process is now stopped, and every deletable id derives from
    # this journal's validated random token. No wildcard or user source is used.
    for ident in sorted(allowed):
        controlled_check(device, guard, journal)
        device.shell("run-as", APP, "rm", "-rf", "files/plugins/" + ident)
        for suffix in (".json", ".json.bak"):
            controlled_check(device, guard, journal)
            restore_app_file(device.serial, "files/store/" + ident + suffix, None)
    controlled_check(device, guard, journal)
    device.shell("run-as", APP, "rm", "-rf", "files/registry", "cache/update-device-" + journal.data["token"])
    restored_paths = tuple(path for path in SNAPSHOTS if path not in journal.data.get("preserved_caches", []))
    for path in restored_paths:
        controlled_check(device, guard, journal)
        restore_app_file(device.serial, path, journal.original(path))
        require(read_app_file(device.serial, path) == journal.original(path), "A private restore write did not complete")
        journal.data["expected"][path] = digest(journal.original(path))
        journal.save()
    require(all(read_app_file(device.serial, path) == journal.original(path) for path in restored_paths),
            "Restored private bytes differ; retain the recovery journal")
    remote = "/data/local/tmp/thinkos-registry-" + journal.data["token"]
    controlled_check(device, guard, journal)
    device.shell("rm", "-rf", remote)
    # Startup is authorized only here after exact-byte restoration, never in
    # response to an unexpected foreground change during the UI test.
    controlled_main(journal, device, guard)
    ui = journal.data["ui"]
    device.debug("locale", value=ui["locale"])
    for command, key in (("theme", "theme"), ("hold_attention", "attention"), ("lux", "lux")):
        guard.check()
        device.debug(command, **({"value": ui[key]} if ui.get(key) is not None else {}))
    guard.check()
    if ui.get("presence") is None:
        device.debug("hold_presence")
    else:
        device.shell("am", "broadcast", "-n", APP + "/.DebugReceiver", "--es", "command", "hold_presence",
                     "--ez", "value", str(bool(ui["presence"])).lower())
    guard.check()
    device.shell("am", "broadcast", "-n", APP + "/.DebugReceiver", "--es", "command", "orientation",
                 "--ei", "value", ui["orientation"])
    until = time.monotonic() + 30
    while time.monotonic() < until:
        guard.check()
        current = broadcast_json(device, "ui_state")
        if current.get("page") == ui["page"]:
            break
        # The captured baseline had no return stack, so restoring its base page
        # cannot silently lose application navigation.
        device.debug("page", value=ui["page"])
        time.sleep(.4)
    guard.check()
    device.debug("variant", value=ui.get("variant", ""))
    until = time.monotonic() + 20
    keys = ("page", "variant", "locale", "orientation", "theme", "attention", "lux", "presence", "safeMode", "onboardingOpen")
    while time.monotonic() < until:
        guard.check()
        current = broadcast_json(device, "ui_state")
        if all(current.get(key) == ui.get(key) for key in keys):
            break
        time.sleep(.25)
    require(all(current.get(key) == ui.get(key) for key in keys), "The original UI/page/variant/holds did not return")
    if journal.data.get("home") is not None:
        require(home_component(device) == journal.data["home"], "The original HOME preference changed")
    # Startup can reserialize cached public data. Flush that work before the
    # final proof; the network remains blocked and physical input is watched.
    time.sleep(2.5)
    guard.check()
    # Never write old store bytes over an unknown late runtime/user mutation.
    require(all(read_app_file(device.serial, path) == journal.original(path) for path in restored_paths),
            "A late runtime write changed restored private bytes; retain the journal")
    guard.check()
    require(not app_exists(device, "files/registry"), "The disposable registry cache was not removed")
    media = broadcast_json(device, "media_state")
    require(not any(media.get(key) for key in ("owner", "kind", "count")) and media.get("status") == "stopped",
            "Unexpected media session after restoration")
    recovery_network(journal, device, False, guard)
    guard.check()
    journal.data["stage"] = "restored"
    journal.save()
    shutil.rmtree(journal.directory)
    return {"exact_private_files_verified_while_blocked": len(restored_paths),
            "refreshed_caches_preserved": journal.data.get("preserved_caches", []), "owned_firewall_removed": True,
            "navigation_restored": True,
            "welcome_and_cast_identity_restored": True, "all_audio_streams_zero": True}


def cached_hierarchy():
    source = ROOT / "tools/tests/android/ThinkHierarchy.java"
    return Path(tempfile.gettempdir()) / ("thinkos-hierarchy-" + hashlib.sha256(source.read_bytes()).hexdigest()[:12]) / "hierarchy.jar"


from registry_lifecycle import lifecycle
Driver.lifecycle = lifecycle


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default=PHYSICAL)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--hierarchy-jar", type=Path)
    parser.add_argument("--mode", choices=("basic", "malicious", "limits", "lifecycle"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--restore", type=Path)
    args = parser.parse_args()
    if args.restore is None and (args.mode is None or args.apk is None or args.test_apk is None):
        parser.error("--mode, --apk and --test-apk are required for a new run")
    if args.restore is not None and any((args.mode, args.apk, args.test_apk, args.hierarchy_jar)):
        parser.error("--restore uses only its fixed private journal; omit mode/APK/helper inputs")
    args.output.mkdir(parents=True, exist_ok=True)
    require(not (args.output / "results.json").exists(), "Use a fresh output directory")
    device = Device(args.serial)
    require(device.shell("getprop", "ro.serialno").strip() == PHYSICAL, "Only the authorized physical device may be used")
    require(quiet(device) and (args.restore is not None or foreground(device)),
            "ThinkOS must already be foreground and all five audio streams zero")
    watch = TouchWatch(device)
    guard = Guard(device, watch)
    driver = None
    journal = None
    try:
        if args.restore is not None:
            journal = Journal.load(args.restore)
            require(args.output.resolve() != journal.directory.resolve() and
                    journal.directory.resolve() not in args.output.resolve().parents,
                    "Recovery artifacts must remain outside the private journal")
            require(journal.data["serial"] == device.serial, "Recovery serial differs from the journal")
            controlled_check(device, guard, journal)
            result = restore_run(journal, device, guard)
            (args.output / "results.json").write_text(json.dumps({"complete": True, "restoration": result}, indent=2) + "\n")
            print("Private registry recovery completed; snapshot verification and zero volume confirmed.", flush=True)
            return
        apk_sha, test_sha = file_digest(args.apk), file_digest(args.test_apk)
        require(installed_sha(device, APP) == apk_sha, "Installed main APK differs from --apk; this driver never installs it")
        require(installed_sha(device, TEST_APP) == test_sha, "Installed instrumentation APK differs from --test-apk; install it separately")
        require("instrumentation:" + RUNNER + " (target=" + APP + ")" in
                device.shell("pm", "list", "instrumentation").splitlines(),
                "Build the test APK with -PupdateProbe=true before starting this driver")
        jar = args.hierarchy_jar or cached_hierarchy()
        file_digest(jar)  # Refuse a missing cache instead of building a helper.
        ui = ensure_clean_baseline(device)
        guard.check()
        journal = Journal.capture(device, guard, ui, apk_sha, test_sha)
        driver = Driver(device, guard, journal, args.output, jar, args.mode)
        driver.start()
        getattr(driver, args.mode)()
        driver.finish()
        driver.result["restoration"] = restore_run(journal, device, guard, continuing=True)
        driver.result.pop("private_recovery_journal", None)
        driver.save()
        print("REG-2 " + args.mode + " passed and private device state was restored.", flush=True)
    except BaseException as error:
        if driver is not None:
            driver.result["complete"] = False
            driver.preserve(error)
        elif journal is not None:
            print("Recovery journal retained: " + str(journal.directory), file=sys.stderr)
        raise
    finally:
        if driver is not None:
            driver.close()
        watch.close()


if __name__ == "__main__":
    main()
