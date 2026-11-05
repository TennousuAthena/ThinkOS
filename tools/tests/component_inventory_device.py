#!/usr/bin/env python3
"""Observe the public 0.1.9 system-component inventory; never update anything.

  python3 -B tools/tests/component_inventory_device.py --apk APP.apk \
    --test-apk TEST.apk --output /tmp/component-inventory-019
  # Optional B, after A: existing, keyless armv7 host-test daemon only.
  # Add --daemon system/build/updaterd-test-armv7.

A opens settings through its debug navigation shortcut, then actually touches
系统 / 系统组件. Without an updater it requires the honest 无法自动更新 notice,
an empty inventory, and no invented targets, progress or results. B uses the
same public entry with a temporary keyless updater and requires seven actual
installed rows in portrait and landscape. CHECK/DOWNLOAD/INSTALL are never
touched. No APK/module installation, reboot, Device Owner operation, user
setting write, performance test, network rule, forwarding or logcat clearing.

The hierarchy jar must already be cached. B alone compiles a tiny fixed
HELLO/STATUS-only probe with the existing local NDK; it never downloads tools.
Root probe evidence and actual application-UID inventory evidence are distinct.
All original UI/raw private snapshots stay in a 0700 journal, outside artifacts.
Five immutable files are compared, never overwritten. Human input, foreground
loss or nonzero volume stops without reclaiming the UI. After an interruption,
inspect the private journal, then explicitly use --restore JOURNAL when idle.
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
import struct
import subprocess
import sys
import tempfile
import time
import uuid

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools/tests")]
from device_app_files import read_app_file
from device_hierarchy import node_bounds, visible_nodes
from media_device import APP, Device
from registry_device import (ExternalInput, Guard, Hierarchy, PHYSICAL, ProbeError,
                             TEST_APP, TouchWatch, app_exists, cached_hierarchy,
                             digest, file_digest, foreground, installed_sha,
                             private_json, quiet, require)
from component_targets_device import home_component, main_foreground
from ui_walkthrough import broadcast_json

APK_SHA = "74cc263027c07d85a7b41ad0bddba23a5899e324b7d91576bcf1c761af619f41"
TEST_SHA = "9dc76dfbec4666f737ef951cb81f6437c3d1d6346fa29301214eb96bf2f4583d"
IDS = ("thinkos-updater", "thinkos-cast", "thinkos-immersive", "thinkos-shell",
       "thinkos-certs", "thinkos-power", "timesync")
NAMES = dict(zip(IDS, ("系统更新器", "投屏", "系统界面", "全局手势", "安全证书", "电源保护", "时间同步")))
IMMUTABLE = ("files/settings/local.json", "files/store/welcome.json",
             "files/cast/identity-seed", "files/cast/airplay-identity", "files/management-exit")
PENDING = ("files/update/pending.json", "files/update/pending.json.bak",
           "files/update/module-pending.json", "files/update/module-pending.json.bak")
UI_KEYS = ("theme", "attention", "presence", "lux", "locale", "orientation")
MAX_PRIVATE = 8 * 1024 * 1024

# No argv, caller-supplied path/socket, shell, received FD, or mutation command.
# Each exchange has one real monotonic deadline spanning connect/write/read.
READONLY_PROBE = r'''
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
static int64_t now_ms(void) {
    struct timespec t;
    return clock_gettime(CLOCK_MONOTONIC, &t) ? -1 : (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static int ready(int fd, short events, int64_t end) {
    for (;;) {
        int64_t n = now_ms();
        if (n < 0 || n >= end) return -1;
        struct pollfd p = {fd, events, 0};
        int r = poll(&p, 1, (int)(end - n));
        if (r < 0 && errno == EINTR) continue;
        return r > 0 && (p.revents & events) ? 0 : -1;
    }
}
static int exchange(const char *line, char out[4097], pid_t *server) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0), rc = -1;
    if (fd < 0) return -1;
    int64_t n = now_ms(), end = n + 2000;
    if (n < 0) goto done;
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address)); address.sun_family = AF_UNIX;
    memcpy(address.sun_path + 1, "thinkos-updater", 15);
    socklen_t size = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + 15);
    if (connect(fd, (struct sockaddr *)&address, size)) {
        if (errno != EINPROGRESS || ready(fd, POLLOUT, end)) goto done;
        int error = 0; socklen_t len = sizeof(error);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &len) || error) goto done;
    }
    struct ucred peer; socklen_t len = sizeof(peer);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &len) || len != sizeof(peer) || peer.uid != 0 || peer.pid <= 0) goto done;
    *server = peer.pid;
    size_t sent = 0, total = strlen(line), used = 0;
    while (sent < total) {
        if (ready(fd, POLLOUT, end)) goto done;
        ssize_t r = send(fd, line + sent, total - sent, MSG_NOSIGNAL);
        if (r < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (r <= 0) goto done;
        sent += (size_t)r;
    }
    for (;;) {
        if (ready(fd, POLLIN, end)) goto done;
        ssize_t r = recv(fd, out + used, 4096 - used, 0);
        if (r < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (r <= 0) goto done;
        used += (size_t)r;
        for (size_t i = 0; i < used; ++i) {
            unsigned char c = (unsigned char)out[i];
            if (c == '\n') {
                if (i + 1 != used) goto done;
                out[i] = 0; rc = 0; goto done;
            }
            if (c < 32 || c > 126) goto done;
        }
        if (used == 4096) goto done;
    }
done:
    close(fd); return rc;
}
int main(int argc, char **argv) {
    (void)argv;
    if (argc != 1 || getuid() != 0 || geteuid() != 0) return 2;
    char hello[4097], status[4097]; pid_t a = 0, b = 0;
    if (exchange("HELLO\n", hello, &a) || strcmp(hello, "OK thinkos-updater 1") ||
        exchange("STATUS\n", status, &b) || a != b || strncmp(status, "OK {", 4)) return 3;
    printf("{\"uid\":0,\"peer_uid\":0,\"peer_pid\":%ld,\"status\":%s}\n", (long)b, status + 3);
    return 0;
}
'''


def root_read(device, *args):
    """Arguments below are fixed or random-token/proven-PID derived."""
    return device.shell("su", "-c", shlex.join([str(arg) for arg in args]))


def socket_absent(device):
    return not re.search(r"\s@thinkos-updater\s*$", device.shell("cat", "/proc/net/unix"), re.M)


def no_transaction(device):
    require(not any(app_exists(device, path) for path in PENDING),
            "Preserve an existing application/module update record; do not run this fixture")


def base_idle(device):
    main_foreground(device)
    ui, stage = broadcast_json(device, "ui_state"), broadcast_json(device, "stage_state")
    require(ui.get("page") and not ui.get("onboardingOpen") and not ui.get("safeMode"),
            "Preserve an existing onboarding/recovery session")
    require(not any(stage.get(key) for key in ("canGoBack", "pageBack", "panelOpen", "touching", "practice")),
            "An idle base page, empty return stack and closed panel/practice are required")
    require(not any(broadcast_json(device, name).get("showing") for name in ("update_state", "legal_state")),
            "Preserve an already-open native window")
    media = broadcast_json(device, "media_state")
    require(media.get("status") == "stopped" and not any(media.get(key) for key in ("owner", "kind", "count")),
            "Preserve an active media or casting session")
    no_transaction(device)
    return ui


def contained(bounds, viewport):
    return len(bounds) == 4 and len(viewport) == 4 and viewport[0] <= bounds[0] < bounds[2] <= viewport[2] and \
        viewport[1] <= bounds[1] < bounds[3] <= viewport[3]


def compile_readonly_probe(private):
    props = dict(line.split("=", 1) for line in (ROOT / "android/local.properties").read_text().splitlines() if "=" in line)
    match = re.search(r'ndkVersion\s*=\s*"([0-9.]+)"', (ROOT / "android/app/build.gradle.kts").read_text())
    require(match and "sdk.dir" in props, "B requires the existing configured local NDK")
    prebuilt = Path(props["sdk.dir"]) / "ndk" / match[1] / "toolchains/llvm/prebuilt"
    compilers = sorted(prebuilt.glob(("darwin-*" if sys.platform == "darwin" else "linux-*") + "/bin/armv7a-linux-androideabi27-clang"))
    require(len(compilers) == 1 and compilers[0].is_file(), "B requires one existing local API27 armv7 NDK compiler; no downloads")
    source, binary = private / "readonly-probe.c", private / "readonly-probe"
    with os.fdopen(os.open(source, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        stream.write(READONLY_PROBE)
    command = [str(compilers[0]), "-std=c11", "-Os", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", str(source), "-o", str(binary)]
    with os.fdopen(os.open(private / "compiler.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=60)
    require(result.returncode == 0, "The fixed read-only NDK probe did not compile; see the private compiler log")
    os.chmod(binary, 0o700)
    raw = binary.read_bytes()[:20]
    require(raw[:6] == b"\x7fELF\x01\x01" and struct.unpack("<H", raw[18:20])[0] == 40, "The fixed probe must be armv7 ELF32")
    return binary


class InventoryDriver:
    def __init__(self, device, guard, output, jar, state, private):
        self.device, self.guard, self.output, self.jar = device, guard, output, jar
        self.state, self.private = state, private
        self.token = state["token"]
        self.remote = "/data/local/tmp/thinkos-registry-" + self.token
        self.hierarchy = None
        self.result = {"complete": False, "apk_sha256": APK_SHA, "test_apk_sha256": TEST_SHA,
                       "checks": [], "scenarios": {}, "private_recovery_journal": str(private),
                       "scope": "Actual public inventory entry only; no target check/download/install or update transaction"}

    @classmethod
    def capture(cls, device, guard, output, jar):
        guard.check()
        ui = base_idle(device)
        require(ui.get("locale", "").startswith("zh"), "Start with the existing Chinese locale; do not change a saved language")
        private = Path(tempfile.mkdtemp(prefix="thinkos-inventory-recovery-"))
        os.chmod(private, 0o700)
        state = {"version": 1, "kind": "component-inventory-019", "serial": device.serial,
                 "token": uuid.uuid4().hex, "stage": "capturing", "ui": ui, "expected_ui": dict(ui),
                 "apk_sha256": APK_SHA, "test_apk_sha256": TEST_SHA, "home": home_component(device),
                 "files": {}, "actions": [], "daemon": None, "own_window": False}
        driver = cls(device, guard, output, jar, state, private)
        driver.save_state()
        print("Private inventory journal: " + str(private), flush=True)
        for path in IMMUTABLE:
            guard.check()
            raw = read_app_file(device.serial, path)
            require(raw is None or len(raw) <= MAX_PRIVATE, "An immutable file exceeds the private snapshot bound")
            state["files"][path] = None if raw is None else {"base64": base64.b64encode(raw).decode("ascii"), "sha256": digest(raw)}
            driver.save_state()
        state["stage"] = "captured"
        driver.save_state()
        driver.identity()
        driver.immutable()
        return driver

    @classmethod
    def load(cls, device, guard, output, directory, jar):
        require(directory.is_dir() and not directory.is_symlink() and directory.stat().st_uid == os.getuid() and
                directory.stat().st_mode & 0o077 == 0, "Recovery requires this user's private 0700 journal")
        path = directory / "state.json"
        require(path.is_file() and not path.is_symlink() and path.stat().st_uid == os.getuid() and
                path.stat().st_mode & 0o077 == 0 and path.stat().st_size < 64 * 1024 * 1024,
                "Invalid private recovery state")
        state = json.loads(path.read_text())
        require(state.get("version") == 1 and state.get("kind") == "component-inventory-019" and
                state.get("serial") == device.serial and re.fullmatch(r"[0-9a-f]{32}", state.get("token", "")) and
                state.get("apk_sha256") == APK_SHA and state.get("test_apk_sha256") == TEST_SHA and
                set(state.get("files", {})) == set(IMMUTABLE), "Recovery journal does not describe this fixed run")
        require(isinstance(state.get("ui"), dict) and isinstance(state.get("expected_ui"), dict) and
                re.fullmatch(r"[a-z][a-z0-9_-]*\.[a-zA-Z0-9_.-]+", state["ui"].get("page", "")) and
                type(state["ui"].get("orientation")) is int and not state["ui"].get("safeMode") and
                not state["ui"].get("onboardingOpen"), "Invalid captured base-page state")
        driver = cls(device, guard, output, jar, state, directory)
        driver.identity()
        driver.immutable()
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

    def identity(self):
        require(installed_sha(self.device, APP) == APK_SHA and installed_sha(self.device, TEST_APP) == TEST_SHA,
                "The already-installed fixed APK identity changed; do not install or continue")

    def immutable(self):
        for path, item in self.state["files"].items():
            original = None if item is None else base64.b64decode(item["base64"], validate=True)
            require(original is None or (len(original) <= MAX_PRIVATE and digest(original) == item["sha256"]),
                    "Invalid private immutable snapshot")
            require(read_app_file(self.device.serial, path) == original,
                    "An immutable user/welcome/cast/management file changed; preserve it and the journal")

    def wait(self, predicate, seconds=25):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            self.guard.check()
            value = predicate()
            if value:
                return value
            time.sleep(.25)
        raise ProbeError("A bounded public-inventory UI wait expired")

    def ui(self):
        return broadcast_json(self.device, "ui_state")

    def geometry(self):
        state = broadcast_json(self.device, "stage_state")
        width, height = state.get("width", 0), state.get("height", 0)
        require(width > 0 and height > 0, "Actual stage geometry is unavailable")
        return width, height

    def input(self, *args):
        self.guard.check()
        self.device.shell("input", *args)
        self.guard.check()
        time.sleep(.25)

    def nodes(self):
        require(self.hierarchy is not None, "An existing hierarchy helper is required")
        return [dict(node.attrib) for node in visible_nodes(self.hierarchy.tree()) if node.get("package") == APP]

    def tap(self, label):
        def target():
            matches = [node for node in self.nodes() if label in (node.get("text"), node.get("content-desc"))]
            return min(matches, key=lambda node: node_bounds(node)[1]) if matches else None
        node = self.wait(target)
        left, top, right, bottom = node_bounds(node)
        width, height = self.geometry()
        require(contained((left, top, right, bottom), (0, 0, width, height)), "The actual settings action is clipped")
        self.input("touchscreen", "tap", (left + right) // 2, (top + bottom) // 2)

    def home(self):
        width, height = self.geometry()
        self.input("touchscreen", "swipe", width // 2, height - 2, width // 2, height // 3, 400)
        self.wait(lambda: self.ui().get("page") == "home.main")
        require(not broadcast_json(self.device, "stage_state").get("canGoBack"), "The actual Home gesture must clear the return stack")

    def override(self, command, key, value):
        self.guard.check()
        self.record("override", key=key, value=value)
        # Save the intended value before the reversible UI write, for recovery
        # if the host is interrupted between dispatch and the next observation.
        self.state["expected_ui"][key] = value
        self.save_state()
        if command == "orientation":
            self.device.shell("am", "broadcast", "-n", APP + "/.DebugReceiver", "--es", "command", command, "--ei", "value", value)
        else:
            self.device.debug(command, **({"value": value} if value is not None else {}))
        self.wait(lambda: self.ui().get(key) == value)

    def start(self):
        self.hierarchy = Hierarchy(self.device, self.guard, self.token, self.jar)
        self.state["remote_created"] = True
        self.save_state()
        self.device.start_log(clear=False)
        self.override("hold_attention", "attention", "ENGAGED")
        self.home()

    def open_inventory(self):
        self.guard.check()
        require(not broadcast_json(self.device, "update_state").get("showing"), "Preserve a pre-existing update window")
        self.record("open-public-settings")
        # Navigation only: no debug update-open/check/install/activate channel.
        self.device.debug("settings", value="system")
        self.wait(lambda: self.ui().get("page") == "settings.form")
        self.tap("系统")
        self.state["own_window"] = True
        self.save_state()
        self.tap("系统组件")
        self.wait(lambda: broadcast_json(self.device, "update_state").get("showing") is True)

    def close_inventory(self):
        self.guard.check()
        if self.state.get("own_window"):
            page = self.ui().get("page")
            # Settings has its own 30-second idle timeout. Reading a native
            # window can legitimately expose its captured Home underneath;
            # the showing owned window still holds focus throughout.
            require(page in ("settings.form", "home.main"), "The owned inventory has an unexpected underlying page")
            if page == "home.main":
                require(not broadcast_json(self.device, "stage_state").get("canGoBack"),
                        "The idle settings return must have an empty Home navigation stack")
            if broadcast_json(self.device, "update_state").get("showing") is True:
                self.input("keyevent", "4")
                self.wait(lambda: not broadcast_json(self.device, "update_state").get("showing"))
            else:
                # An owned opening intent can expire with the settings idle
                # timer before the native window is created. Observe only;
                # never send Back to an unrelated native confirmation.
                nodes = self.nodes()
                require(not any(node.get("resource-id") in
                                ("android:id/alertTitle", "android:id/button1", "android:id/button2") for node in nodes),
                        "An unknown native window replaced the owned inventory intent")
            self.state["own_window"] = False
            self.save_state()
        self.home()

    def public_status(self, mode, expected=None):
        status = broadcast_json(self.device, "update_status")
        window = broadcast_json(self.device, "update_state")
        require(window.get("showing") is True and {key: value for key, value in window.items() if key != "showing"} == status,
                "The actual public status and visible native window disagree")
        require(status.get("current_version") == "0.1.9" and status.get("current_code") == 1009 and
                status.get("configured") is False and status.get("phase") == "unavailable" and
                all(status.get(key) is False for key in ("can_check", "can_download", "can_install")) and
                status.get("downloaded") == status.get("size") == 0 and status.get("components") == [] and
                not any(key in status for key in ("version", "code", "notes", "result")),
                "An unconfigured inventory must not invent catalog targets, transfer progress, results or admission")
        inventory = status.get("inventory")
        require(isinstance(inventory, dict) and set(inventory) == {"updater", "modules"}, "The public inventory contract is missing")
        if mode == "A":
            require(inventory == {"updater": "unavailable", "modules": []}, "No updater must produce an honest empty unavailable inventory")
        else:
            modules = inventory.get("modules", [])
            def matches_installed(item):
                code = expected[item["id"]]
                actual = item.get("installed_code")
                # Unknown is optional in the public schema. A missing/null
                # value and explicit -1 both render unknown, never absent/0.
                return (actual is None or (type(actual) is int and actual == -1)) if code == -1 else \
                    type(actual) is int and actual == code
            require(inventory.get("updater") == "read_only" and len(modules) == 7 and
                    {item.get("id") for item in modules} == set(IDS) and
                    all(set(item) <= {"id", "installed_code"} and matches_installed(item) for item in modules),
                    "Actual application-UID read-only inventory must match root-observed installed builds")
        return status

    def observation(self, mode, expected):
        self.public_status(mode, expected)
        nodes = self.nodes()
        labels = [node.get("text", "") for node in nodes]
        require("软件更新" in labels, "The actual native public inventory window is not visible")
        require(not any(node.get("class") == "android.widget.ProgressBar" for node in nodes) and
                not any(text.startswith(("目标：", "下载：", "结果：")) for text in labels),
                "Inventory-only rows must hide target/progress/result widgets")
        require(not any(text in ("下载更新", "立即安装") for text in labels), "Inventory-only UI must hide transfer/install actions")
        checks = [node for node in nodes if node.get("text") == "检查更新"]
        require(len(checks) == 1 and checks[0].get("enabled") == "false", "The unconfigured CHECK action must be disabled; never tap it")
        scrolls = [node for node in nodes if node.get("class") == "android.widget.ScrollView"]
        require(len(scrolls) == 1, "One actual native inventory scroll viewport is required")
        viewport = node_bounds(scrolls[0])
        require(contained(viewport, (0, 0, *self.geometry())), "The native scroll viewport is clipped")
        seen = set()
        for ident, name in NAMES.items():
            names = [node for node in nodes if node.get("text") == name and contained(node_bounds(node), viewport)]
            if not names:
                continue
            require(mode == "B", "An unavailable updater must not invent installed-component rows")
            for node in names:
                bottom = node_bounds(node)[3]
                next_name = min((node_bounds(other)[1] for other in nodes if other.get("text") in NAMES.values() and
                                 node_bounds(other)[1] >= bottom), default=viewport[3])
                code = expected[ident]
                installed = "已装：" + ("未知" if code < 0 else "未安装" if code == 0 else "构建 " + str(code))
                if any(other.get("text") == installed and contained(node_bounds(other), viewport) and
                       bottom <= node_bounds(other)[1] < next_name for other in nodes):
                    seen.add(ident)
        return viewport, seen, "无法自动更新" in labels, nodes

    def scroll(self, viewport, upward=True):
        left, top, right, bottom = viewport
        require(bottom - top > 100, "A safe actual content swipe viewport is required")
        start, end = top + int((bottom - top) * .82), top + int((bottom - top) * .38)
        if not upward:
            start, end = end, start
        self.input("touchscreen", "swipe", (left + right) // 2, start, (left + right) // 2, end, 350)

    def photo(self, name):
        self.guard.check()
        require(self.state.get("own_window") and broadcast_json(self.device, "update_state").get("showing"),
                "Only the owned fullscreen native inventory may be captured")
        path = self.output / (name + ".png")
        self.device.screenshot(path)
        os.chmod(path, 0o600)
        raw = path.read_bytes()
        require(raw[:8] == b"\x89PNG\r\n\x1a\n" and len(raw) >= 24 and
                struct.unpack(">II", raw[16:24]) == self.geometry(), "The screenshot must be the actual fullscreen native inventory")
        self.guard.check()

    def orientation(self, mode, name, code, expected):
        self.override("orientation", "orientation", code)
        self.wait(lambda: (self.geometry()[0] > self.geometry()[1]) == (code == 0))
        # A window rotation may retain its previous scroll offset. Return to
        # top with content-only real swipes; never touch the footer actions.
        for _ in range(24):
            viewport, _, notice, _ = self.observation(mode, expected)
            if notice:
                break
            self.scroll(viewport, upward=False)
        require(notice, "The honest updater capability notice must be reachable")
        require(any(node.get("text") == "系统组件" for node in self.nodes()), "The actual native system-components heading is missing at the top")
        seen, observations, previous, unchanged = set(), [], None, 0
        for index in range(32):
            viewport, visible, notice, nodes = self.observation(mode, expected)
            signature = tuple((node.get("text", ""), node.get("bounds", "")) for node in nodes)
            unchanged = unchanged + 1 if signature == previous else 0
            observations.append({"visible_installed": sorted(visible), "capability_notice": notice})
            if index == 0 or visible - seen:
                self.photo(mode + "-" + name + "-" + str(index).zfill(2))
            seen.update(visible)
            if unchanged >= 2:
                break
            previous = signature
            self.scroll(viewport)
        require(unchanged >= 2, "Actual native scrolling did not reach a stable bounded bottom")
        require(seen == (set(IDS) if mode == "B" else set()), "Real scrolling must expose exactly the expected installed rows")
        result = {"geometry": list(self.geometry()), "seen_installed": sorted(seen), "observations": observations,
                  "actual_scroll_to_bottom": True, "no_target_progress_result": True,
                  "check_disabled_never_touched": True, "download_install_hidden": True}
        self.result["scenarios"].setdefault(mode, {})[name] = result
        self.check(mode + " " + name + " actual public inventory and content scrolling are honest", True)

    def scenario(self, mode, expected=None):
        self.open_inventory()
        # Inventory refresh is triggered by the real window and is asynchronous.
        self.wait(lambda: broadcast_json(self.device, "update_status").get("inventory", {}).get("updater") ==
                  ("unavailable" if mode == "A" else "read_only"), seconds=30)
        self.public_status(mode, expected)
        self.orientation(mode, "portrait", 1, expected)
        self.orientation(mode, "landscape", 0, expected)
        self.close_inventory()
        self.immutable()
        no_transaction(self.device)

    def daemon_argv(self):
        return [self.remote + "/updaterd-test-armv7", "--root", self.remote + "/root", "--listen", "@thinkos-updater", "--test-sdk", "28"]

    def process_identity(self, pid):
        require(type(pid) is int and pid > 1, "Invalid owned daemon PID")
        raw = root_read(self.device, "cat", "/proc/" + str(pid) + "/cmdline")
        require(raw.endswith("\x00") and raw[:-1].split("\x00") == self.daemon_argv(), "The PID no longer belongs to this exact temporary daemon argv")
        status = root_read(self.device, "cat", "/proc/" + str(pid) + "/status")
        require(re.search(r"^Uid:\s+0\s+0\s+0\s+0\s*$", status, re.M), "The temporary daemon must really run as root")
        stat = root_read(self.device, "cat", "/proc/" + str(pid) + "/stat").rsplit(")", 1)[-1].split()
        require(len(stat) > 19 and stat[19].isdigit(), "Cannot establish the temporary daemon's process start time")
        executable = root_read(self.device, "readlink", "/proc/" + str(pid) + "/exe").strip()
        require(executable == self.daemon_argv()[0], "The owned daemon executable identity changed")
        return {"pid": pid, "starttime": stat[19], "executable": executable}

    def root_probe(self):
        self.guard.check()
        raw = root_read(self.device, self.remote + "/readonly-probe")
        require(len(raw.encode()) <= 4608, "The fixed read-only helper exceeded its response bound")
        value = json.loads(raw)
        require(set(value) == {"uid", "peer_uid", "peer_pid", "status"} and value["uid"] == value["peer_uid"] == 0 and
                value["peer_pid"] == self.state["daemon"]["pid"], "The root-only probe did not reach this exact temporary daemon")
        status = value["status"]
        require(status.get("protocol") == 1 and status.get("keys") == 0 and status.get("transaction") is False and
                status.get("id") == "" and status.get("phase") == status.get("result") == 0 and
                status.get("rollback_attempted") is False and
                status.get("setup") == {"timezone": False, "language": False, "device_owner": False},
                "B requires genuinely keyless, setup-disabled, idle root capability; do not attempt mutation")
        modules = status.get("modules", [])
        require(len(modules) == 7 and {item.get("id") for item in modules} == set(IDS) and
                all(type(item.get("code")) is int and -1 <= item["code"] <= 2147483647 and
                    item.get("floor") == item.get("staged") == 0 for item in modules),
                "The readonly root inventory must have seven actual builds and no update floor/staged target")
        codes = {item["id"]: item["code"] for item in modules}
        require(codes["thinkos-certs"] == 20261020 and codes["thinkos-immersive"] == 1 and codes["timesync"] == 1,
                "The actual CA/Vector/timesync installed builds do not match this device baseline")
        self.guard.check()
        return codes

    def start_daemon(self, source):
        self.guard.check()
        require(source.resolve() == (ROOT / "system/build/updaterd-test-armv7").resolve(), "Only the fixed existing test-armv7 daemon artifact is permitted")
        sha = file_digest(source)
        header = source.read_bytes()[:20]
        require(header[:6] == b"\x7fELF\x01\x01" and struct.unpack("<H", header[18:20])[0] == 40, "B requires the existing armv7 ELF32 daemon")
        require(socket_absent(self.device), "An updater already owns the default socket; never replace it")
        # A persisted updater, even if temporarily stopped, is not our fixture.
        checks = "for p in /data/adb/modules/thinkos-updater /data/adb/modules_update/thinkos-updater /data/adb/thinkos/state.bin /data/adb/thinkos/transaction; do if [ -e \"$p\" ] || [ -L \"$p\" ]; then echo occupied; fi; done"
        require(not self.device.shell("su", "-c", checks).strip(), "A real updater installation/transaction exists; preserve it")
        no_transaction(self.device)
        helper = compile_readonly_probe(self.private)
        self.guard.check()
        self.device.adb("push", source, self.daemon_argv()[0], timeout=30)
        self.device.adb("push", helper, self.remote + "/readonly-probe", timeout=30)
        self.device.shell("chmod", "700", self.daemon_argv()[0], self.remote + "/readonly-probe")
        self.record("daemon-launch-intent", daemon_sha256=sha)
        launch = ("umask 077; " + shlex.join(self.daemon_argv()) + " > " + shlex.quote(self.remote + "/daemon.stdout") +
                  " 2> " + shlex.quote(self.remote + "/daemon.stderr") + " < /dev/null & inventory_pid=$!; echo \"$inventory_pid\" > " +
                  shlex.quote(self.remote + "/daemon.pid"))
        self.device.shell("su", "-c", launch)
        text = root_read(self.device, "cat", self.remote + "/daemon.pid").strip()
        require(re.fullmatch(r"[1-9][0-9]{0,9}", text), "The own daemon PID record is invalid")
        self.state["daemon"] = self.process_identity(int(text))
        self.save_state()
        self.wait(lambda: not socket_absent(self.device), seconds=10)
        codes = self.root_probe()
        self.result["root_probe"] = {"keys": 0, "setup_disabled": True, "idle": True, "root_peer_verified": True,
                                     "purpose": "Root peer/capabilities only; actual application UID evidence is scenario B", "codes": codes}
        self.save()
        return codes

    def stop_daemon(self):
        owned = self.state.get("daemon")
        if owned is None and (self.state.get("daemon_stopped") or self.state.get("daemon_exit_verified")):
            # A prior cleanup already proved its PID gone. Its remaining PID
            # file is historical evidence, never authority to adopt/kill a
            # reused PID on a later recovery attempt.
            require(socket_absent(self.device), "Another updater appeared after the owned daemon stopped; preserve it")
            self.state["daemon_exit_verified"] = True
            self.save_state()
            return
        if owned is None and self.state.get("remote_created"):
            # Recover the narrow launch/host-crash window, using only our fixed
            # PID file then the exact executable/argv identity, never pidof/name.
            raw = self.device.shell("su", "-c", "if [ -f " + shlex.quote(self.remote + "/daemon.pid") +
                                    " ]; then cat " + shlex.quote(self.remote + "/daemon.pid") + "; fi").strip()
            if raw:
                require(re.fullmatch(r"[1-9][0-9]{0,9}", raw), "The recovery PID record is invalid")
                owned = self.process_identity(int(raw))
                self.state["daemon"] = owned
                self.save_state()
        if owned is None:
            return
        pid = owned["pid"]
        alive = root_read(self.device, "sh", "-c", "if [ -d /proc/" + str(pid) + " ]; then echo alive; else echo gone; fi").strip()
        if alive == "alive":
            require(self.process_identity(pid) == owned, "The saved daemon PID was reused; never kill it")
            root_read(self.device, "/system/bin/kill", "-TERM", str(pid))
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                alive = root_read(self.device, "sh", "-c", "if [ -d /proc/" + str(pid) + " ]; then echo alive; else echo gone; fi").strip()
                if alive == "gone":
                    break
                time.sleep(.2)
            require(alive == "gone", "The owned daemon did not stop; keep the private cleanup journal")
        else:
            require(alive == "gone", "Cannot inspect the owned daemon lifecycle")
        require(socket_absent(self.device), "A different updater now owns the socket; preserve its state")
        self.state["daemon"] = None
        self.state["daemon_stopped"] = True
        self.state["daemon_exit_verified"] = True
        self.save_state()

    def refresh_after_daemon_stop(self):
        # Cleanup is separate from the public-entry acceptance above. The
        # ordinary maintenance window triggers the same bounded inventory
        # refresh without re-entering a settings page with a 30-second timer.
        # Its list is only observed; Back is the sole action inside it.
        self.guard.check()
        require(not broadcast_json(self.device, "update_state").get("showing"), "Close the owned inventory before cleanup")
        if not self.state.get("cleanup_menu"):
            require(not any(node.get("resource-id") in ("android:id/alertTitle", "android:id/button1", "android:id/button2")
                            for node in self.nodes()), "Preserve an unknown existing native window")
            self.state["cleanup_menu"] = True
            self.record("open-maintenance-for-inventory-cleanup")
            self.device.debug("maintenance")
        def menu():
            nodes = self.nodes()
            return nodes if any(node.get("text") == "维护" for node in nodes) and \
                any(node.get("class") == "android.widget.ListView" for node in nodes) and \
                not any(node.get("resource-id") == "android:id/button1" for node in nodes) else None
        self.wait(menu)
        self.wait(lambda: broadcast_json(self.device, "update_status").get("inventory") == {"updater": "unavailable", "modules": []})
        status = broadcast_json(self.device, "update_status")
        require(status.get("components") == [] and not any(status.get(key) for key in ("can_check", "can_download", "can_install")),
                "Cleanup must not create update targets or authority")
        require(menu() is not None, "The owned cleanup menu changed; never act on an unknown confirmation")
        self.input("keyevent", "4")
        self.wait(lambda: not any(node.get("resource-id") in ("android:id/alertTitle", "android:id/button1", "android:id/button2")
                                 for node in self.nodes()))
        self.state["cleanup_menu"] = False
        self.state["daemon_stopped"] = False
        self.result["post_daemon_inventory_unavailable"] = True
        self.save_state()

    def cleanup_remote(self):
        if not self.state.get("remote_created"):
            return
        children = self.device.shell("su", "-c", "if [ -d " + shlex.quote(self.remote) + " ]; then ls -A " + shlex.quote(self.remote) + "; fi").splitlines()
        require(set(children) <= {"hierarchy.jar", "oat", "updaterd-test-armv7", "readonly-probe", "root", "daemon.pid", "daemon.stdout", "daemon.stderr"},
                "Unknown content appeared inside the own temporary directory; preserve it")
        if "oat" in children:
            # Android 8.1 dexopt generates these alongside our helper jar.
            # Accept that exact arm cache layout, never arbitrary extra files.
            oat = self.remote + "/oat"
            require(not root_read(self.device, "find", oat, "-type", "l").strip() and
                    set(root_read(self.device, "ls", "-A", oat).splitlines()) <= {"arm"},
                    "Unknown content appeared in the helper's Android oat cache")
            if root_read(self.device, "ls", "-A", oat).strip():
                require(set(root_read(self.device, "ls", "-A", oat + "/arm").splitlines()) <=
                        {"hierarchy.odex", "hierarchy.vdex"}, "Unknown helper dex cache file; preserve it")
        if "root" in children:
            entries = root_read(self.device, "ls", "-A", self.remote + "/root").splitlines()
            require(set(entries) <= {"updater.lock"}, "A mutation/unknown file appeared in the temporary root; preserve it")
        root_read(self.device, "rm", "-rf", self.remote)
        self.state["remote_created"] = False
        self.save_state()

    def restore(self):
        self.guard.check()
        self.identity()
        self.immutable()
        current = self.ui()
        require(all(current.get(key) in (self.state["expected_ui"].get(key), self.state["ui"].get(key)) for key in UI_KEYS),
                "A process-local hold/locale/orientation changed outside this driver; preserve it")
        self.record("restoration-started")
        if self.state.get("own_window"):
            self.close_inventory()
        elif not self.state.get("cleanup_menu") and current.get("page") != self.state["ui"]["page"]:
            require(current.get("page") in ("settings.form", "home.main"), "Unexpected navigation; do not take over the UI")
            self.home()
        self.stop_daemon()
        # Refresh only our publicly opened window after removing B, so the
        # process does not retain a fixture inventory after cleanup.
        if self.state.get("daemon_stopped"):
            self.refresh_after_daemon_stop()
        original = self.state["ui"]
        # The baseline was a base page with an empty return stack. This explicit
        # restoration route cannot discard somebody else's application stack.
        self.guard.check()
        self.device.debug("page", value=original["page"])
        self.wait(lambda: self.ui().get("page") == original["page"])
        self.device.debug("variant", value=original.get("variant", ""))
        self.wait(lambda: self.ui().get("variant") == original.get("variant"))
        # Only attention/orientation were changed by this driver. Other holds
        # and locale were verified above and are never written unnecessarily.
        self.override("orientation", "orientation", original["orientation"])
        self.override("hold_attention", "attention", original.get("attention"))
        keys = UI_KEYS + ("page", "variant", "onboardingOpen", "safeMode")
        def restored():
            value = self.ui()
            return all(value.get(key) == original.get(key) for key in keys)
        self.wait(restored)
        base_idle(self.device)
        require(home_component(self.device) == self.state["home"], "The original Android HOME preference changed")
        self.identity()
        self.immutable()
        self.guard.check()
        self.cleanup_remote()
        self.state["stage"] = "restored"
        self.save_state()
        self.result["restoration"] = {"five_immutable_files_byte_equal": True, "original_page_variant_holds_orientation": True,
                                      "home_preference_unchanged": True, "audio_streams_1_to_5_zero": True,
                                      "owned_temporary_process_directory_removed": True, "no_forward_created": True}
        self.check("original base page/variant/holds/orientation and five immutable files are preserved", True)

    def close_log(self):
        if self.device._logcat is not None:
            path = self.private / ("logcat-" + uuid.uuid4().hex + ".txt")
            with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
                stream.write("\n".join(self.device.log_since(0)) + "\n")
            self.device.stop_log()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default=PHYSICAL)
    parser.add_argument("--apk", type=Path, help="Fixed, already-installed 0.1.9 APK bytes; never installed by this script")
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--hierarchy-jar", type=Path, help="Existing jar only; default existing source-addressed cache")
    parser.add_argument("--daemon", type=Path, help="Optional fixed system/build/updaterd-test-armv7; B fails closed without required capabilities")
    parser.add_argument("--scenario", choices=("both", "unavailable", "read-only"), default="both",
                        help="Run independent inventory scenarios; skipped scenarios are explicitly recorded")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--restore", type=Path)
    args = parser.parse_args()
    if args.restore is None and (args.apk is None or args.test_apk is None):
        parser.error("--apk and --test-apk are required for a new fixed-0.1.9 run")
    if args.restore is not None and any((args.apk, args.test_apk, args.daemon)):
        parser.error("--restore uses only its private journal; omit APK/daemon inputs")
    if args.restore is None and args.scenario == "read-only" and args.daemon is None:
        parser.error("--scenario read-only requires the fixed --daemon artifact")
    if args.restore is None and args.scenario == "unavailable" and args.daemon is not None:
        parser.error("--scenario unavailable must not start a daemon")
    require(not args.output.exists(), "Use a fresh public output directory")
    jar = args.hierarchy_jar or cached_hierarchy()
    file_digest(jar)  # Deliberately never calls hierarchy_jar(), which builds.
    require(args.serial == PHYSICAL, "Only the authorized physical serial may be used")
    device = Device(args.serial)
    require(device.shell("getprop", "ro.serialno").strip() == PHYSICAL and foreground(device) and quiet(device),
            "ThinkOS must already be foreground on the physical device with all five audio streams zero")
    main_foreground(device)
    require(installed_sha(device, APP) == APK_SHA and installed_sha(device, TEST_APP) == TEST_SHA,
            "Installed APKs are not the exact fixed 0.1.9 main/test pair; install separately")
    if args.restore is None:
        require(file_digest(args.apk) == APK_SHA and file_digest(args.test_apk) == TEST_SHA,
                "APK inputs must match the exact fixed installed 0.1.9 bytes")
        require(socket_absent(device), "A default updater socket already exists; never replace or disrupt it")
    if args.restore is not None:
        resolved = args.restore.resolve()
        require(args.output.resolve() != resolved and resolved not in args.output.resolve().parents,
                "Public artifacts must stay outside the private recovery journal")
    args.output.mkdir(parents=True, mode=0o700)
    watch = TouchWatch(device)
    guard = Guard(device, watch)
    driver = None
    try:
        if args.restore is not None:
            driver = InventoryDriver.load(device, guard, args.output, args.restore, jar)
            if driver.state.get("remote_created"):
                # Attach to only the original existing helper; do not create a
                # replacement directory over uncertain recovery content.
                driver.hierarchy = object.__new__(Hierarchy)
                driver.hierarchy.device, driver.hierarchy.guard = device, guard
                driver.hierarchy.token, driver.hierarchy.counter = driver.token, 0
                driver.hierarchy.remote, driver.hierarchy.jar = driver.remote, driver.remote + "/hierarchy.jar"
            driver.restore()
        else:
            driver = InventoryDriver.capture(device, guard, args.output, jar)
            driver.start()
            if args.scenario != "read-only":
                driver.scenario("A")
            else:
                driver.result["scenarios"]["A"] = {"executed": False, "reason": "Explicit read-only scenario; no unavailable portrait/landscape claim"}
            if args.daemon is not None:
                codes = driver.start_daemon(args.daemon)
                driver.scenario("B", codes)
                require(driver.root_probe() == codes, "The actual root installed inventory changed during UI observation")
            else:
                driver.result["scenarios"]["B"] = {"executed": False, "reason": "No temporary keyless daemon requested; no app-UID read_only claim"}
            driver.restore()
        driver.result["complete"] = True
        driver.result["private_recovery_journal"] = None
        driver.save()
        driver.close_log()
        shutil.rmtree(driver.private)
        print("Public inventory checks and private byte/UI restoration completed; all five streams remain silent.", flush=True)
        return 0
    except BaseException as error:
        # Never take back foreground or restore display state after human input.
        # A proven owned root process can be stopped without touching the UI;
        # preserve every uncertain file/window for the explicit recovery run.
        if driver is not None:
            if isinstance(error, ExternalInput):
                try:
                    driver.stop_daemon()
                except Exception:
                    driver.state["cleanup_pending"] = True
            driver.state["stage"] = "interrupted"
            driver.save_state()
            driver.result["failure"] = type(error).__name__
            driver.result["complete"] = False
            driver.save()
            driver.close_log()
            print("Stopped; private recovery journal retained at " + str(driver.private), file=sys.stderr)
        print("Inventory run did not complete: " + (str(error) if isinstance(error, ProbeError) else type(error).__name__), file=sys.stderr)
        return 2
    finally:
        watch.close()
        device.stop_log()


if __name__ == "__main__":
    sys.exit(main())
