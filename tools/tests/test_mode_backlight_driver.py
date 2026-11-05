"""Fake host/device/worker boundaries only; never executes adb or real sysfs."""
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("thinkos_mode_backlight", Path(__file__).with_name("mode_backlight_device.py"))
MODE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODE)
TOKEN = "a" * 32


class Clock:
    now = 0
    def time(self): return self.now
    def sleep(self, seconds): self.now += seconds


class FakeWorkerDevice:
    def __init__(self, clock, ready=True, disconnect=False):
        self.clock, self.ready, self.disconnect = clock, ready, disconnect
        self.events, self.light, self.dark, self.done, self.lost = [], 204, False, False, False
        self.deadline = None

    def create(self, script):
        self.events.append("create")
        self.script = script

    def start(self):
        self.events += ["detached-start", "write-original-probe", "read-original-probe", "ready"]
        self.deadline = self.clock.now + MODE.SECONDS

    def step(self):
        if not self.done and self.clock.now >= self.deadline:
            if self.dark and self.light == 0: self.light = 204
            self.done = True
            self.events.append("timer-restored")

    def state(self):
        self.step()
        if self.lost: raise ConnectionError("fake disconnected transport")
        if self.dark and self.disconnect:
            self.lost = True
            raise ConnectionError("fake disconnected transport")
        return "ready=" + (TOKEN + "|123|0|20|204" if self.ready else "") + "\ndark=" + (TOKEN if self.dark else "") + \
            "\ndone=" + (TOKEN + "|restored" if self.done else "") + "\n"

    def verify_ready(self, raw, sha, original):
        MODE.require(self.ready and hashlib.sha256(self.script).hexdigest() == sha and self.light == original,
                     "fake readiness mismatch")
        self.events.append("verified-ready")

    def request(self, name):
        if self.lost: raise ConnectionError("fake disconnected transport")
        self.step()
        self.events.append(name)
        if name == "dark.request" and not self.done:
            assert "verified-ready" in self.events
            self.dark, self.light = True, 0
            self.events.append("write-zero")
        elif name == "stop.request":
            if self.dark and self.light == 0: self.light = 204
            self.done = True

    def read(self): return str(self.light) + "\n"
    def cleanup(self, sha):
        assert self.done and self.light == 204
        self.events.append("cleanup-owned-only")


class FakeWatch:
    hardware_seen = False
    def allow_manual_touch(self): self.hardware_seen = True


class FakeGuard:
    def check(self): pass


class ModeBacklightDriverTest(unittest.TestCase):
    def data(self):
        return {"kind": "mode-backlight-1", "serial": MODE.PHYSICAL, "token": TOKEN, "original": "204\n",
                "original_sha256": hashlib.sha256(b"204\n").hexdigest(), "restored": False, "hardware_input_seen": False}

    def test_owner_and_single_actor_gates_refuse_before_device_construction(self):
        for owner, actor in ((False, False), (False, True), (True, False)):
            with self.assertRaises(Exception): MODE.present(owner, actor)
        MODE.present(True, True)
        with patch.object(MODE, "NoRetryDevice") as device, patch("sys.argv", ["probe", "--single-device-actor"]):
            with self.assertRaises(Exception): MODE.main()
            device.assert_not_called()

    def test_original_roundtrip_and_ready_proof_precede_zero_and_only_owned_cleanup_follows_restore(self):
        clock = Clock()
        device = FakeWorkerDevice(clock)
        with tempfile.TemporaryDirectory() as directory:
            data = self.data()
            MODE.Experiment(device, FakeGuard(), FakeWatch(), directory, data, clock.sleep, clock.time).run()
            self.assertTrue(data["restored"] and data["zero_observed"] and data["remote_cleaned"])
            self.assertLess(device.events.index("read-original-probe"), device.events.index("verified-ready"))
            self.assertLess(device.events.index("verified-ready"), device.events.index("write-zero"))
            self.assertLess(device.events.index("timer-restored"), device.events.index("cleanup-owned-only"))

    def test_unconfirmed_recovery_never_authorizes_zero(self):
        clock = Clock()
        device = FakeWorkerDevice(clock, ready=False)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(Exception):
                MODE.Experiment(device, FakeGuard(), FakeWatch(), directory, self.data(), clock.sleep, clock.time).run()
        self.assertNotIn("write-zero", device.events)
        self.assertEqual(204, device.light)

    def test_correct_hash_cannot_authorize_an_unrelated_or_inexact_worker_cmdline(self):
        sha = hashlib.sha256(MODE.watchdog_script(TOKEN, 204)).hexdigest()
        worker = "/data/local/tmp/thinkos-mode-backlight-" + TOKEN + "/restore.sh"
        class ProcessDevice:
            cmdline = ""
            reads = 0
            def shell(self, *args):
                if args == ("cat", MODE.BACKLIGHT):
                    self.reads += 1
                    return "204\n"
                assert args[:2] == ("su", "-c")
                return sha + "  " + worker + "\n" + self.cmdline
        device = ProcessDevice()
        ops = MODE.Operations(device, TOKEN)
        ready = "ready=" + TOKEN + "|123|0|20|204\ndark=\ndone=\n"
        for argv in ("/system/bin/sleep\00020\0", "", "/system/bin/sh\0/other/restore.sh\0",
                     "/system/bin/sh\0" + worker + ".other\0", "/system/bin/sh\0-c\0" + worker + "\0",
                     "/system/bin/sh\0" + worker, "/system/bin/sh\0" + worker + "\0extra\0"):
            device.cmdline = argv
            with self.assertRaises(Exception): ops.verify_ready(ready, sha, 204)
        self.assertEqual(0, device.reads, "A hash-line path must not impersonate the PID's worker argv")
        device.cmdline = "/system/bin/sh\0" + worker + "\0"
        ops.verify_ready(ready, sha, 204)
        self.assertEqual(1, device.reads)

    def test_disconnected_host_does_not_cancel_the_detached_twenty_second_restore(self):
        clock = Clock()
        device = FakeWorkerDevice(clock, disconnect=True)
        with tempfile.TemporaryDirectory() as directory:
            data = self.data()
            with self.assertRaises(ConnectionError):
                MODE.Experiment(device, FakeGuard(), FakeWatch(), directory, data, clock.sleep, clock.time).run()
            self.assertTrue(data["transport_recovery_pending"])
        self.assertEqual(0, device.light)
        clock.sleep(MODE.SECONDS)
        device.step()
        self.assertEqual(204, device.light)
        self.assertNotIn("cleanup-owned-only", device.events)

    def actual_host_worker(self, initial=204, late=False, conflicting=False):
        # Run only a local shell with a temporary regular file and fake uptime.
        # The actual fixed-path device script is never started by these tests.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            light = root / "fake-brightness"
            light.write_text(str(initial) + "\n")
            (root / "clock").write_text("100\n")
            if not late: (root / "dark.request").write_text(TOKEN)
            script = MODE.watchdog_script(TOKEN, 204).decode()
            script = script.replace("DIR=/data/local/tmp/thinkos-mode-backlight-" + TOKEN, "DIR='" + str(root) + "'")
            script = script.replace("LIGHT=" + MODE.BACKLIGHT, "LIGHT='" + str(light) + "'")
            old_clock = "clock_now() { IFS=' ' read -r up rest < /proc/uptime || return 1; NOW=${up%%.*}; case \"$NOW\" in ''|*[!0-9]*) return 1;; esac; }"
            self.assertEqual(1, script.count(old_clock))
            script = script.replace(old_clock, 'clock_now() { NOW=$(cat "$DIR/clock"); }')
            tick = '  printf \'%s\\n\' "$((NOW + 1))" > "$DIR/clock"\n'
            if late: tick += '  if [ "$NOW" = 119 ]; then echo late > "$DIR/dark.request"; fi\n'
            if conflicting: tick += '  if [ "$NOW" = 105 ]; then echo 77 > "$LIGHT"; fi\n'
            script = script.replace("  sleep 1\n", tick)
            result = subprocess.run(["sh", "-c", script], text=True, capture_output=True, timeout=5)
            return result, light.read_text(), (root / "done").read_text(), (root / "dark").exists(), (root / "ready").exists()

    def test_real_script_on_fake_sysfs_restores_after_clock_deadline(self):
        result, light, done, dark, ready = self.actual_host_worker()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(ready and dark)
        self.assertEqual("204\n", light)
        self.assertEqual(TOKEN + "|restored\n", done)

    def test_real_script_does_not_blacken_after_expired_lease_or_overwrite_unknown_brightness(self):
        result, light, done, dark, ready = self.actual_host_worker(late=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("204\n", light)
        self.assertTrue(ready)
        self.assertFalse(dark)
        result, light, done, dark, ready = self.actual_host_worker(conflicting=True)
        self.assertEqual("77\n", light)
        self.assertEqual(TOKEN + "|conflict\n", done)
        result, light, done, dark, ready = self.actual_host_worker(initial=77)
        self.assertEqual("77\n", light)
        self.assertFalse(dark or ready)

    def test_record_cannot_claim_hardware_touch_without_an_observed_hardware_event(self):
        with tempfile.TemporaryDirectory() as directory:
            data = self.data()
            data.update(script_sha256=hashlib.sha256(MODE.watchdog_script(TOKEN, 204)).hexdigest(), restored=True, zero_observed=True)
            MODE.private_json(Path(directory) / "state.json", data)
            with self.assertRaises(Exception): MODE.record(directory, "yes", "works", "not-tested")
            with self.assertRaises(Exception): MODE.record(directory, "yes", "not-tested", "works")
            MODE.record(directory, "unknown", "not-tested", "not-tested")

    def test_cleanup_preserves_unknown_file_and_live_process_then_removes_only_owned_token(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            remote, actor, proc = root / "token", root / "actor", root / "proc"
            remote.mkdir(); actor.mkdir(); proc.mkdir()
            script = MODE.watchdog_script(TOKEN, 204)
            (remote / "restore.sh").write_bytes(script)
            (remote / "ready").write_text(TOKEN + "|123|0|20|204\n")
            (remote / "done").write_text(TOKEN + "|restored\n")
            (remote / "started").mkdir()
            (actor / "owner").write_text(TOKEN + "\n")
            class HostOnlyDevice:
                def shell(self, *args):
                    assert args[:2] == ("su", "-c")
                    source = args[2].replace(MODE.ACTOR, str(actor)).replace('"/proc/$pid"', '"' + str(proc) + '/$pid"')
                    return subprocess.run(["sh", "-c", source], capture_output=True, check=True, text=True).stdout
            ops = MODE.Operations(HostOnlyDevice(), TOKEN)
            ops.remote = str(remote)
            sha = hashlib.sha256(script).hexdigest()
            (remote / "unknown").write_text("new owner data")
            with self.assertRaises(subprocess.CalledProcessError): ops.cleanup(sha)
            self.assertTrue((remote / "restore.sh").exists() and (remote / "unknown").exists())
            (remote / "unknown").unlink()
            (proc / "123").mkdir()
            with self.assertRaises(subprocess.CalledProcessError): ops.cleanup(sha)
            self.assertTrue((remote / "restore.sh").exists())
            (proc / "123").rmdir()
            ops.cleanup(sha)
            self.assertFalse(remote.exists() or actor.exists())


if __name__ == "__main__": unittest.main()
