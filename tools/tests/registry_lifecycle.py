"""REG-2/REG-3 public-UI lifecycle extension; importing never contacts a device.

Driver integration (owned by registry_device.py): bind Driver.lifecycle to
lifecycle, allow --mode lifecycle, and make Driver.finish recognize
`lifecycle_fixture_finished`: require empty records, then skip a second fixture
finish request. This module intentionally finishes the RAM-only fixture before
the final real process restart, validates its report, and retains its checks.

Only RegistryProbe's fixed signed publications/control files are written. No
registry record, cache, permission, health timer, settings file or plugin file is
injected. Settings/installation/upgrade/removal use actual native/public UI.
Temporary per-UID offline rules use the existing private recovery journal. A
human touch, unexpected foreground or nonzero volume stops the run without
cleanup or taking the screen back. No APK/module install, reboot or data clear.

Real 600-second trials are mandatory. Safe-mode entry and native unclean-exit
attribution lack a reversible public entry and remain explicitly unverified.
"""
import base64
import hashlib
import json
import re
import sys
import time

TRIAL_SECONDS = 600
NAME = "仓库测试"
REASONS = {"network": "读取本地测试台内容", "media": "建立静音暂停会话", "location": "读取测试城市设置"}
PENDING = [
    "Real safe-mode startup ignores registry plugins: no reversible public enter-safe-mode action is available.",
    "Native unclean-exit attribution: deliberately causing a fatal native crash is outside this run.",
    "Blocked-digest automatic scheduler retry: the baseline permits off/official only; a reviewed fixture is not an automatic-update attempt.",
    "Cold-start/clock performance remains separate and is not measured by this functional run.",
]


class _Lifecycle:
    def __init__(self, driver):
        self.d = driver
        # Works when registry_device is either imported or executed as __main__;
        # avoid a circular import and preserve its exact ProbeError/Guard types.
        self.api = sys.modules[driver.__class__.__module__]
        self.require = self.api.require
        self.ident = driver.ready["id"]
        self.digests = driver.ready["digests"]
        self.started = time.monotonic()
        self.confirm_number = 0
        self.d.result["scope"] = "Actual signed-fixture public hub/native UI lifecycle, two real 600-second trials and real process restart; explicit remaining coverage is recorded."
        self.data = {"complete": False, "trial_seconds": TRIAL_SECONDS, "phases": [], "health": [], "pending": list(PENDING)}
        self.d.result["lifecycle"] = self.data

    def phase(self, name):
        self.d.guard.check()
        self.data["phase"] = name
        self.data["phases"].append({"name": name, "elapsed_s": round(time.monotonic() - self.started, 3)})
        self.d.journal.data["lifecycle_phase"] = name
        self.d.journal.checkpoint(self.d.device)
        self.d.save()
        print("LIFECYCLE " + name, flush=True)

    def read(self, path):
        return self.api.read_json(self.d.device, path)

    def raw(self, path):
        return self.api.read_app_file(self.d.device.serial, path)

    def setting(self, scope, key, default=False):
        for name in ("local", "server"):
            layer = self.read("files/settings/" + name + ".json") or {}
            if key in layer.get(scope, {}):
                return layer[scope][key]
        return default

    def observation(self):
        value = self.d.control("checkpoint")
        self.require(value.get("trial_s") == TRIAL_SECONDS and value.get("pid") == self.d.ready["pid"],
                     "Lifecycle requires the real 600-second policy in the original instrumentation process")
        return value

    def stable(self, version, *, trial=None):
        record = self.d.record(self.ident)
        return bool(record and record.get("digest") == self.digests[version] and record.get("version") == version and
                    record.get("enabled") is True and (trial is None or record.get("trial") is trial))

    def active(self, version):
        diagnostic = self.d.diagnostics().get(self.ident)
        return bool(diagnostic and diagnostic["version"] == version and diagnostic["bytes"] > 0 and not diagnostic["disabled"])

    def started_log(self, version):
        return any(re.search(r"REGISTRY_STARTED\s+" + re.escape(version) + r"\b", line)
                   for line in self.d.logs(self.ident, "REGISTRY_STARTED"))

    def denied_log(self, version):
        return any(re.search(r"REGISTRY_DENIED\s+" + re.escape(version) + r"\s+7\b", line)
                   for line in self.d.logs(self.ident, "REGISTRY_DENIED"))

    def blocked(self, version, reason=None):
        value = self.read(self.api.STATE) or {}
        return any(item.get("id") == self.ident and item.get("digest") == self.digests[version] and
                   (reason is None or item.get("reason") == reason) for item in value.get("blocked", []))

    def media(self):
        return self.api.broadcast_json(self.d.device, "media_state")

    def stopped(self):
        value = self.media()
        return not value.get("owner") and value.get("status") == "stopped"

    def settings_plugins(self):
        self.require(self.d.home(), "The actual Home gesture must leave hub before settings")
        width, height = self.d.screen()
        self.d.input("touchscreen", "swipe", width // 2, 2, width // 2, int(height * .72), 420)
        self.d.tap("设置")
        self.d.tap("插件", scroll=True)

    def community(self, enabled):
        self.settings_plugins()
        self.require(self.d.wait(lambda: self.d.node(label="显示社区插件")), "The public community setting is unavailable")
        if self.setting("system", "plugins.community") is not enabled:
            self.d.tap("显示社区插件")
        self.d.check("public community setting is " + ("enabled" if enabled else "disabled"),
                     self.d.wait(lambda: self.setting("system", "plugins.community") is enabled))
        self.d.open_hub()

    def remember(self):
        self.settings_plugins()
        self.d.tap(NAME, scroll=True)
        label = self.d.ready["settings_label"]
        self.require(self.d.wait(lambda: self.d.node(label=label)), "The signed fixture has no real settings page")
        if self.setting(self.ident, self.d.ready["settings_key"]) is not True:
            self.d.tap(label)
        self.d.check("a fixture setting is changed only through its actual public settings editor",
                     self.d.wait(lambda: self.setting(self.ident, self.d.ready["settings_key"]) is True))
        self.d.open_hub()

    def authenticated(self):
        cache = self.read("files/registry/cache.json")
        self.require(isinstance(cache, dict), "No authenticated registry cache exists")
        raw = base64.b64decode(cache["document"], validate=True)
        self.require(len(raw) <= 2 * 1024 * 1024, "The authenticated fixture catalog is oversized")
        return json.loads(raw)

    def footer_refresh(self):
        self.d.tap("发现", tab=True)
        labels = {"刷新", "目录刷新失败", "目录可能过旧"}
        node = self.d.wait(lambda: next((n for n in self.d.nodes() if n.get("text") in labels or n.get("content-desc") in labels), None))
        self.d.tap_node(self.require(node, "No actual hub refresh action is visible"))

    def confirm(self, version, *, tier="reviewed", cancel=False, accessibility=False):
        self.confirm_number += 1
        self.require(self.d.wait(self.d.native), "The actual native permission confirmation is absent")
        commit = hashlib.sha256((self.ident + "@" + version).encode()).hexdigest()[:40]
        wanted = {NAME, "thinkos-tests/registry-probe@" + commit, "社区" if tier == "community" else "已审核"}
        permissions = {"network"} if version in ("1.0.0", "1.0.1") else {"network", "media", "location"}
        wanted.update(REASONS[permission] for permission in permissions)
        observed = set()
        for page in range(4):
            texts = "\n".join(node.get("text", "") for node in self.d.nodes())
            observed.update(value for value in wanted if value in texts)
            self.d.photo("lifecycle-confirm-" + str(self.confirm_number) + "-" + version.replace(".", "-") + "-" + tier + "-" + str(page))
            if observed == wanted:
                break
            self.d.scroll()
        self.d.check("native " + version + " confirmation shows its exact source, tier and every permission reason", observed == wanted)
        before = self.raw(self.api.STATE)
        paths = self.d.requests()
        if accessibility:
            self.d.control("accessibility_accept")
            self.d.check("added permissions cannot be accepted by accessibility ACTION_CLICK",
                         self.raw(self.api.STATE) == before and self.d.native() is not None)
            self.d.check("unapproved added permissions do not fetch payloads",
                         not any(path.startswith(("/registry/files/", "/blobs/")) for path in self.d.requests()[len(paths):]))
        if cancel:
            self.d.tap_node(self.require(self.d.node(resource="android:id/button2"), "No native cancellation button"))
        else:
            # Host monotonic time immediately before the actual input command,
            # after guard checks. Time spent reading/scrolling the confirmation
            # must not count towards a candidate's 600-second health evidence.
            self.approval_started = self.d.tap_node(self.require(self.d.native(), "The native approval button disappeared"))
        self.require(self.d.wait(lambda: self.d.native() is None), "The native confirmation did not close")
        return before

    def install(self, *, tier="reviewed"):
        self.d.detail(NAME)
        self.d.tap("安装", scroll=True)
        self.confirm("1.0.0", tier=tier, accessibility=True)
        self.d.check("v1 is really installed and executing with the native-confirmed network permission",
                     self.d.wait(lambda: self.stable("1.0.0", trial=True) and self.active("1.0.0"), seconds=60))
        self.require(set(self.d.record(self.ident)["confirmed"]) == {"network"}, "Unexpected initial permission grant")
        self.d.check("the actual installed v1 denies every management API and logs its own startup",
                     self.d.wait(lambda: self.denied_log("1.0.0") and self.started_log("1.0.0")))
        return self.approval_started

    def healthy(self, version, earliest):
        self.phase("real-600-second-health-" + version)
        deadline = earliest + TRIAL_SECONDS + 120
        next_progress = time.monotonic()
        samples = []
        while time.monotonic() < deadline:
            self.d.guard.check()
            record = self.d.record(self.ident)
            self.require(record and record.get("digest") == self.digests[version] and record.get("enabled") is True,
                         "The original trial candidate changed while waiting for real health")
            elapsed = time.monotonic() - earliest
            if record.get("trial") is False:
                self.d.check(version + " cannot become healthy before 600 real seconds from the earliest possible activation",
                             elapsed >= TRIAL_SECONDS and self.active(version))
                self.data["health"].append({"version": version, "elapsed_s": round(elapsed, 3), "observations": samples})
                self.d.save()
                return
            self.require(record.get("trial") is True, "Trial is neither active nor genuinely healthy")
            if time.monotonic() >= next_progress:
                observed = self.observation()
                self.require(self.active(version), "The real candidate VM stopped during its 600-second trial")
                samples.append({"elapsed_s": round(elapsed, 3), "device_elapsed_ms": observed["elapsed"], "trial": True})
                self.data["health_wait"] = {"version": version, "elapsed_s": round(elapsed, 3)}
                self.d.journal.checkpoint(self.d.device)
                self.d.save()
                print("LIFECYCLE real health " + version + " " + str(int(elapsed)) + "/600s", flush=True)
                next_progress = time.monotonic() + 25
            time.sleep(1)
        self.require(False, "The original 600-second health trial did not complete within its bounded observation window")

    def permissions(self):
        self.phase("new-permissions-native-cancel-and-approval")
        self.d.publish(level=3)
        self.d.detail(NAME)
        previous = self.d.record(self.ident)
        self.d.tap("更新", scroll=True)
        self.confirm("1.0.2", cancel=True, accessibility=True)
        self.d.check("cancelling added permissions leaves the entire healthy v1 record and VM unchanged",
                     self.d.record(self.ident) == previous and self.active("1.0.0"))
        self.d.tap("更新", scroll=True)
        self.confirm("1.0.2")
        self.d.check("real native touch admits v3 and only its exact newly confirmed permissions",
                     self.d.wait(lambda: self.stable("1.0.2", trial=True) and self.active("1.0.2"), seconds=60) and
                     set(self.d.record(self.ident)["confirmed"]) == {"network", "media", "location"})
        self.d.check("v3 owns a real paused autoplay=false MediaEngine session at zero volume",
                     self.d.wait(lambda: self.media().get("owner") == self.ident and self.media().get("status") == "paused", seconds=60))
        return self.approval_started

    def replace_after_media_and_page(self, level, version):
        self.d.publish(level=level)
        self.d.detail(NAME)
        self.d.tap("更新", scroll=True)
        self.d.check(version + " with unchanged permissions requires no new native grant",
                     self.d.wait(lambda: self.stable(version, trial=True)) and self.d.native() is None)
        self.d.check("a real paused v3 media owner prevents " + version + " from replacing its VM",
                     self.active("1.0.2") and self.media().get("owner") == self.ident)
        self.d.tap("打开", scroll=True)
        self.require(self.d.wait(lambda: self.d.node(label=NAME + " 1.0.2")), "The queued candidate replaced a visible v3 too early")
        self.d.tap("停止媒体")
        self.d.check("stopping real media while its v3 page is visible still defers replacement",
                     self.d.wait(self.stopped) and self.active("1.0.2") and self.stable(version, trial=True))
        self.d.photo("lifecycle-visible-v3-defers-" + version.replace(".", "-"))
        self.require(self.d.home(), "Real Home must release the last visible-page replacement gate")
        self.d.check("only leaving v3 for real Home activates " + version,
                     self.d.wait(lambda: self.active(version) and self.stable(version, trial=True), seconds=60))

    def offline_while_fixture_lives(self):
        self.phase("offline-with-live-authenticated-cache")
        cache = self.raw("files/registry/cache.json")
        ledger = self.raw("files/registry/ledger.json")
        before = self.d.record(self.ident)
        paths = self.d.requests()
        self.d.guard.check()
        self.api.recovery_network(self.d.journal, self.d.device, True, self.d.guard)
        # No finally cleanup: after physical input the Guard must stop all
        # mutations. The existing private journal owns recovery of these rules.
        self.d.guard.check()
        self.d.open_hub()
        self.d.tap("发现", tab=True)
        self.d.check("an already authenticated catalog remains visible with all app-UID network traffic blocked",
                     self.d.wait(lambda: self.d.node(label=NAME)))
        self.d.tap("已安装", tab=True)
        self.d.detail(NAME)
        self.d.tap("打开", scroll=True)
        self.d.check("the actual installed v1 app works offline without Server",
                     self.d.wait(lambda: self.d.node(label=NAME + " 1.0.0")) and self.active("1.0.0"))
        self.d.input("keyevent", "KEYCODE_BACK")
        self.footer_refresh()
        self.d.check("a real blocked refresh reports failure and retains the authenticated cache and private sequence floor",
                     self.d.wait(lambda: self.d.node(label="目录刷新失败"), seconds=90) and
                     self.raw("files/registry/cache.json") == cache and self.raw("files/registry/ledger.json") == ledger and
                     self.d.record(self.ident) == before)
        self.d.check("offline browsing does not reach any fixture HTTP endpoint", self.d.requests() == paths)
        self.d.guard.check()
        self.api.recovery_network(self.d.journal, self.d.device, False, self.d.guard)
        self.d.guard.check()
        self.d.publish(level=1, tier="community")

    def community_and_revocation(self):
        self.phase("signed-community-visibility-and-revocation")
        self.community(False)
        self.d.publish(level=1, tier="community")
        catalog = self.authenticated()
        self.d.check("a signed community entry is authenticated but hidden while the public switch is off",
                     any(item.get("id") == self.ident and item.get("tier") == "community" for item in catalog.get("plugins", [])) and
                     self.d.node(label=NAME) is None)
        self.community(True)
        self.d.tap("发现", tab=True)
        self.d.check("the public switch reveals a clearly distinguished community entry",
                     self.d.wait(lambda: self.d.node(label=NAME)) and self.d.node(label="社区") is not None)
        self.d.photo("lifecycle-community-visible")
        self.install(tier="community")
        self.offline_while_fixture_lives()
        self.d.publish(level=1, tier="community", revoked=True)
        self.d.check("a signed revocation disables the exact installed digest and removes its real VM",
                     self.d.wait(lambda: self.d.record(self.ident) is not None and
                                 self.d.record(self.ident).get("digest") == self.digests["1.0.0"] and
                                 self.d.record(self.ident).get("enabled") is False and
                                 self.d.record(self.ident).get("error") == "revoked" and
                                 self.ident not in self.d.diagnostics(), seconds=60))
        self.d.detail(NAME)
        self.d.check("the actual hub displays the authenticated revocation reason",
                     self.d.wait(lambda: self.d.node(label="测试台撤销此版本")))
        self.d.photo("lifecycle-community-revoked")
        self.d.uninstall(self.ident, NAME)
        self.community(False)
        self.d.publish(level=1)

    def trial_overlap(self):
        self.phase("overlapping-trial-upgrade-is-rejected")
        before = self.d.record(self.ident)
        self.require(before and before.get("trial") is True, "v1 must still be in its original real trial")
        self.d.publish(level=2)
        self.d.detail(NAME)
        paths = self.d.requests()
        self.d.tap("更新", scroll=True)
        self.d.check("requesting an upgrade during v1 trial fails before any native prompt",
                     self.d.wait(lambda: self.d.node(label="操作失败")) and self.d.native() is None)
        new_paths = self.d.requests()[len(paths):]
        self.d.check("a rejected overlap neither resets the old trial nor downloads or starts v2",
                     self.d.record(self.ident) == before and self.active("1.0.0") and
                     not any(path.startswith(("/blobs/", "/registry/files/")) for path in new_paths) and
                     not self.started_log("1.0.1"))
        self.d.open_hub()

    def startup_rollback(self):
        self.phase("signed-v2-startup-failure-keeps-healthy-v1")
        self.d.publish(level=2)
        self.d.detail(NAME)
        previous = self.d.record(self.ident)
        self.d.tap("更新", scroll=True)
        self.d.check("a genuinely downloaded v2 startup error preserves the healthy v1 VM and grants",
                     self.d.wait(lambda: self.blocked("1.0.1") and self.stable("1.0.0", trial=False) and
                                 self.active("1.0.0"), seconds=60) and
                     self.d.record(self.ident).get("confirmed") == previous.get("confirmed"))
        paths = self.d.requests()
        self.d.check("v2 failure was reached through its signed file list rather than an early download rejection",
                     "/registry/files/" + self.digests["1.0.1"] + ".json" in paths and
                     any("Deliberate registry startup failure" in line for line in self.d.logs(self.ident, "Deliberate")))
        self.d.check("a bad startup digest is durably blocked without granting new permissions",
                     self.blocked("1.0.1") and set(self.d.record(self.ident)["confirmed"]) == {"network"} and
                     not self.started_log("1.0.1"))
        self.d.open_hub()

    def bad_catalogs(self):
        self.phase("bad-signature-and-sequence-cannot-replace-authenticated-cache")
        for mode in ("bad_signature", "old_seq"):
            cache = self.raw("files/registry/cache.json")
            ledger = self.raw("files/registry/ledger.json")
            previous = self.d.record(self.ident)
            before = len(self.d.requests())
            self.d.control("publish", sample="normal", level=2, **{mode: True})
            self.footer_refresh()
            def rejected():
                paths = self.d.requests()[before:]
                return "/registry/index.json" in paths and "/registry/index.json.sig" in paths and self.d.node(label="目录刷新失败")
            self.d.check(mode + " is actually fetched but cannot replace authenticated bytes, sequence or installed grants",
                         self.d.wait(rejected, seconds=60) and self.raw("files/registry/cache.json") == cache and
                         self.raw("files/registry/ledger.json") == ledger and self.d.record(self.ident) == previous and
                         self.active("1.0.0"))
            self.d.publish(level=2)

    def runtime_rollback(self):
        self.phase("real-v5-runtime-errors-trigger-trial-rollback")
        self.replace_after_media_and_page(5, "1.0.4")
        self.d.open_hub()
        self.d.tap("已安装", tab=True)
        self.d.detail(NAME)
        self.d.tap("打开", scroll=True)
        self.require(self.d.wait(lambda: self.d.node(label=NAME + " 1.0.4")), "The runtime-fault candidate never became a real visible app")
        taps = 0
        for _ in range(8):
            if self.blocked("1.0.4"):
                break
            self.d.tap(self.d.ready["fault_label"])
            taps += 1
        self.d.check("repeated real app taps reach Lua event failures and roll trial v5 back to healthy v3",
                     taps >= 5 and self.d.wait(lambda: self.blocked("1.0.4", "trial-failed") and
                                             self.stable("1.0.2", trial=False) and self.active("1.0.2"), seconds=60) and
                     any("Deliberate registry runtime failure" in line for line in self.d.logs(self.ident, "Deliberate")))
        self.data["runtime_error_taps"] = taps
        self.d.check("runtime rollback preserves the previous native-confirmed permission set and public setting",
                     set(self.d.record(self.ident)["confirmed"]) == {"network", "media", "location"} and
                     self.setting(self.ident, self.d.ready["settings_key"]) is True)
        self.d.open_hub()

    def finish_origin(self):
        self.require(self.d.home(), "Real Home must precede ending the fixture origin")
        self.require(self.stable("1.0.3", trial=True), "Only an actual unproven v4 may enter the restart test")
        previous = self.d.record(self.ident).get("previous", {})
        self.require(previous.get("digest") == self.digests["1.0.2"], "v4 must retain the genuinely healthy v3 predecessor")
        response = self.d.control("finish")
        self.require(response.get("handled") is True, "The signed fixture did not acknowledge its requested end")
        self.require(self.d.active is not None, "No owned instrumentation process is active")
        self.d.active.wait(timeout=30)
        self.api.controlled_check(self.d.device, self.d.guard, self.d.journal)
        report = self.read("cache/update-device-" + self.d.token + "/report.json")
        checks = (report or {}).get("checks", [])
        self.d.check("the original real fixture completed every local-signing and negative-approval check before process restart",
                     report is not None and report.get("complete") is True and checks and
                     all(item.get("passed") is True for item in checks))
        self.d.result["instrumentation_checks"] = [{"name": item["name"], "passed": item["passed"]} for item in checks]
        self.d.active = None
        self.d.lifecycle_fixture_finished = True
        self.d.journal.data["lifecycle_fixture_finished"] = True
        self.d.journal.save()

    def restart_offline(self):
        self.phase("real-process-restart-during-v4-trial")
        old_pid = str(self.d.ready["pid"])
        self.d.guard.check()
        self.api.recovery_network(self.d.journal, self.d.device, True, self.d.guard)
        self.finish_origin()
        self.api.begin_controlled(self.d.journal, self.d.device, self.d.guard, "lifecycle-restart")
        self.d.device.shell("am", "force-stop", self.api.APP)
        self.api.controlled_check(self.d.device, self.d.guard, self.d.journal)
        self.require(not self.d.device.shell("pidof", self.api.APP, check=False).strip(), "The old app process did not stop")
        # This foreground launch is permitted only in this explicitly expected
        # restart window, never as recovery from unrelated/user foreground loss.
        self.api.controlled_main(self.d.journal, self.d.device, self.d.guard)
        self.d.guard.check()
        new_pids = self.d.device.shell("pidof", self.api.APP).split()
        self.d.check("a genuinely new ART process starts while the signed origin and RAM-only roots are gone",
                     len(new_pids) == 1 and new_pids[0] != old_pid)
        self.data["restart"] = {"old_pid": int(old_pid), "new_pid": int(new_pids[0]), "network_blocked": True,
                                "old_origin_finished": True}
        self.d.device.debug("locale", value="zh-CN")
        self.d.guard.check()
        self.d.device.debug("hold_attention", value="ENGAGED")
        self.d.guard.check()
        self.d.device.shell("am", "broadcast", "-n", self.api.APP + "/.DebugReceiver", "--es", "command", "orientation", "--ei", "value", 1)
        self.d.check("restart restores the local healthy v3 and durably blocks interrupted v4 without Server or network",
                     self.d.wait(lambda: self.stable("1.0.2", trial=False) and self.active("1.0.2") and
                                 self.blocked("1.0.3", "trial_interrupted"), seconds=90, allow_exit=True))
        self.d.open_hub()
        self.d.tap("已安装", tab=True)
        self.d.detail(NAME)
        self.d.tap("打开", scroll=True)
        self.d.check("the restored local app page works after restart despite its old media origin being offline",
                     self.d.wait(lambda: self.d.node(label=NAME + " 1.0.2"), allow_exit=True))
        self.d.photo("lifecycle-new-process-offline-v3")
        self.d.tap("停止媒体")
        self.d.check("offline media failure is separate from local plugin execution and is stopped through its real app UI",
                     self.d.wait(self.stopped, allow_exit=True))
        self.d.journal.checkpoint(self.d.device)

    def native_history_empty(self):
        # restoreFactory resets NativeCrashTracker for every plugin, including
        # unrelated original history. The existing recovery journal cannot
        # distinguish fresh native failures from old history safely; never
        # overwrite/erase either to turn this destructive test green.
        for path in ("files/native-failures.json", "files/native-failures.json.bak"):
            self.d.guard.check()
            raw = self.raw(path)
            if raw is None:
                continue
            try:
                value = json.loads(raw) if len(raw) <= 256 * 1024 else None
                if not isinstance(value, dict) or value:
                    return False
            except (ValueError, TypeError):
                return False
        return True

    def skip_factory_history(self):
        pending = "Factory-restore UI was skipped because existing/unknown native crash history must be preserved; requires a separate empty-history acceptance device."
        self.data["pending"].append(pending)
        self.data["factory_restore"] = {"executed": False, "reason": "native_history_present_or_unknown", "coverage": "partial"}
        self.d.result["partial"] = True
        self.d.save()
        self.d.open_hub()
        self.d.uninstall(self.ident, NAME)
        self.require(self.stopped(), "Public fixture uninstall must leave no media session")
        self.require(self.d.home(), "Public uninstall must retain the real Home path")
        self.d.journal.checkpoint(self.d.device)

    def factory_restore(self):
        self.phase("native-restore-factory-plugins-preserves-settings")
        self.require(self.d.home(), "Real Home must precede the recovery menu")
        if not self.native_history_empty():
            self.skip_factory_history()
            return
        local = self.raw("files/settings/local.json")
        store = self.raw("files/store/" + self.ident + ".json")
        self.require(self.setting(self.ident, self.d.ready["settings_key"]) is True and store is not None,
                     "The fixture setting and real Lua store must exist before factory restore")
        # Navigation-only debug hook opens the same native maintenance menu;
        # the destructive menu choice itself is an actual touchscreen action.
        # This does not claim to test the separate two-finger entry gesture.
        self.d.guard.check()
        self.d.device.debug("maintenance")
        button = self.require(self.d.wait(lambda: self.d.node(label="恢复出厂插件")), "The actual native recovery menu is not visible")
        if not self.native_history_empty():
            self.d.input("keyevent", "KEYCODE_BACK")
            self.skip_factory_history()
            return
        self.d.tap_node(button)
        self.d.check("the real native restore-factory-plugins action removes registry records and VMs",
                     self.d.wait(lambda: not self.d.records() and not (self.d.ids & self.d.diagnostics().keys()), seconds=60, allow_exit=True))
        self.d.check("factory plugin restoration preserves the real custom plugin setting and Lua private store",
                     self.raw("files/settings/local.json") == local and
                     self.raw("files/store/" + self.ident + ".json") == store and
                     self.setting(self.ident, self.d.ready["settings_key"]) is True)
        self.require(self.d.home(), "Factory recovery must leave a working public Home path")
        self.d.check("the factory home remains available after repository removal", self.d.ui().get("page") == "home.main")
        self.data["factory_restore"] = {"executed": True, "native_history_precondition": "empty_or_absent"}

    def run(self):
        required = {"community-tier", "runtime-fault-v5", "persistent-bool-setting", "lifecycle-observation"}
        self.require(required <= set(self.d.ready.get("capabilities", [])) and "1.0.4" in self.digests,
                     "Lifecycle requires the enhanced preinstalled RegistryProbe; no old fixture is silently substituted")
        self.require(self.setting("system", "panel.enabled", True) is True,
                     "Public lifecycle settings navigation requires the already-enabled ThinkOS panel; the driver will not change it privately")
        self.observation()
        self.community_and_revocation()
        earliest = self.install()
        self.trial_overlap()
        self.healthy("1.0.0", earliest)
        self.remember()
        self.startup_rollback()
        self.bad_catalogs()
        self.d.open_hub()
        earliest = self.permissions()
        self.healthy("1.0.2", earliest)
        self.runtime_rollback()
        self.phase("v4-paused-media-and-visible-page-replacement-gates")
        self.replace_after_media_and_page(4, "1.0.3")
        self.restart_offline()
        self.factory_restore()
        self.d.check("all lifecycle management events remain private and all five audio streams remain zero",
                     self.d.no_leak() and self.api.quiet(self.d.device))
        self.require(not self.d.records(), "Lifecycle must finish with no installed test registry record")
        self.d.result["fixture_paths"] = sorted(self.d.fixture_paths)
        self.data.update(complete=True, coverage_complete=False, elapsed_s=round(time.monotonic() - self.started, 3),
                         recovery_network_rules_retained_for_exact_restore=bool(self.d.journal.data.get("firewall")))
        self.d.journal.checkpoint(self.d.device)
        self.d.save()


def lifecycle(self):
    """Called as Driver.lifecycle(self); all execution belongs to root acceptance."""
    return _Lifecycle(self).run()


if __name__ == "__main__":
    print("Import lifecycle and bind Driver.lifecycle in registry_device.py; this file never runs device checks by itself.")

