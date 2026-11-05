#!/usr/bin/env python3
"""Owner-present MODE-0 sysfs experiment. Never run unattended.

No input injection, window-brightness API, app restart, setting/file restoration,
Bluetooth, sound, installation or reboot. Visual/touch results are human reports.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
import uuid

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools/tests")]
from device_app_files import read_app_file
from management_exit_cancel_device import idle, policy_facts
from media_device import APP, Device
from registry_device import Guard, PHYSICAL, TouchWatch, home_component, installed_sha, private_json, require

BACKLIGHT = "/sys/class/leds/lcd-backlight/brightness"
MAXIMUM = "/sys/class/leds/lcd-backlight/max_brightness"
ACTOR = "/data/local/tmp/thinkos-mode-backlight.actor"
SECONDS = 20
IMMUTABLE = ("files/settings/local.json", "files/store/welcome.json", "files/cast/identity-seed",
             "files/cast/airplay-identity", "files/management-exit")


def number(raw):
    require(isinstance(raw, str) and re.fullmatch(r"(?:0|[1-9][0-9]{0,9})\n?", raw), "Unknown numeric sysfs format")
    value = int(raw)
    require(value <= 2147483647, "Out-of-range sysfs value")
    return value


def watchdog_script(token, original):
    require(re.fullmatch(r"[0-9a-f]{32}", token) and 0 < original <= 2147483647, "Invalid recovery identity")
    remote = "/data/local/tmp/thinkos-mode-backlight-" + token
    # The detached process alone owns write-zero and the deadline. A host which
    # resumes after the lease has ended can only leave an inert request file.
    return (r'''#!/system/bin/sh
set -u
umask 077
TOKEN=@TOKEN@
DIR=@REMOTE@
LIGHT=@BACKLIGHT@
ORIGINAL=@ORIGINAL@
finished=0
clock_now() { IFS=' ' read -r up rest < /proc/uptime || return 1; NOW=${up%%.*}; case "$NOW" in ''|*[!0-9]*) return 1;; esac; }
read_light() { CURRENT=$(cat "$LIGHT") || return 1; case "$CURRENT" in ''|*[!0-9]*) return 1;; esac; }
finish() {
  [ "$finished" = 0 ] || return
  finished=1
  result=conflict
  if read_light; then
    if [ "$CURRENT" = "$ORIGINAL" ]; then result=restored
    elif [ -f "$DIR/dark" ] && [ "$CURRENT" = 0 ]; then
      if printf '%s\n' "$ORIGINAL" > "$LIGHT" && read_light && [ "$CURRENT" = "$ORIGINAL" ]; then result=restored; fi
    fi
  fi
  printf '%s|%s\n' "$TOKEN" "$result" > "$DIR/done.new" && mv "$DIR/done.new" "$DIR/done"
}
trap finish EXIT
trap 'exit 1' HUP INT TERM
read_light && [ "$CURRENT" = "$ORIGINAL" ] || exit 2
# Prove write permission and a correct round trip BEFORE advertising readiness.
printf '%s\n' "$ORIGINAL" > "$LIGHT" || exit 3
read_light && [ "$CURRENT" = "$ORIGINAL" ] || exit 4
clock_now || exit 5
START=$NOW
DEADLINE=$((START + 20))
printf '%s|%s|%s|%s|%s\n' "$TOKEN" "$$" "$START" "$DEADLINE" "$ORIGINAL" > "$DIR/ready.new"
mv "$DIR/ready.new" "$DIR/ready" || exit 6
while clock_now && [ "$NOW" -lt "$DEADLINE" ]; do
  [ ! -e "$DIR/stop.request" ] || break
  if [ -e "$DIR/dark.request" ] && [ ! -e "$DIR/dark" ]; then
    read_light && [ "$CURRENT" = "$ORIGINAL" ] || exit 7
    clock_now && [ "$NOW" -lt "$DEADLINE" ] || break
    # Marker precedes the write so interruption between them remains recoverable.
    printf '%s\n' "$TOKEN" > "$DIR/dark" || exit 8
    printf '0\n' > "$LIGHT" || exit 9
    read_light && [ "$CURRENT" = 0 ] || exit 10
  fi
  sleep 1
done
'''.replace("@TOKEN@", token).replace("@REMOTE@", remote).replace("@BACKLIGHT@", BACKLIGHT)
            .replace("@ORIGINAL@", str(original))).encode()


class NoRetryDevice(Device):
    """A lost response is unknown, never permission to repeat a mutation."""
    def adb(self, *values, timeout=5, check=True, data=None):
        return subprocess.run(self.base + [str(value) for value in values], input=data, capture_output=True,
                              timeout=min(timeout, 5), check=check).stdout


class OwnerTouchWatch:
    def __init__(self, watch):
        self.watch, self.manual, self.hardware_seen = watch, False, False

    def check(self):
        if self.manual and self.watch.reason == "hardware input observed":
            require(self.watch.process.poll() is None and self.watch.reader.is_alive(), "Hardware monitor lost during manual observation")
            self.hardware_seen = True
        else:
            self.watch.check()

    def allow_manual_touch(self):
        self.watch.check()
        self.manual = True


class Operations:
    def __init__(self, device, token):
        self.device, self.token = device, token
        self.remote = "/data/local/tmp/thinkos-mode-backlight-" + token

    def root(self, script):
        return self.device.shell("su", "-c", script)

    def read(self):
        return self.device.shell("cat", BACKLIGHT)

    def create(self, script):
        q = shlex.quote
        command = ("umask 077; mkdir " + ACTOR + " && printf '%s\\n' " + q(self.token) + " > " + ACTOR +
                   "/owner && mkdir " + q(self.remote) + " && chmod 700 " + ACTOR + " " + q(self.remote))
        self.root(command)
        command = "umask 077; cat > " + q(self.remote + "/restore.sh")
        self.device.adb("shell", "-T", shlex.join(["su", "-c", command]), data=script)
        self.root("chmod 600 " + q(self.remote + "/restore.sh"))

    def start(self):
        q = shlex.quote
        self.root("test -x /data/adb/magisk/busybox && mkdir " + q(self.remote + "/started") +
                  " && /data/adb/magisk/busybox nohup /data/adb/magisk/busybox setsid /system/bin/sh " +
                  q(self.remote + "/restore.sh") + " </dev/null >/dev/null 2>&1 &")

    def state(self):
        q = shlex.quote
        return self.root("for name in ready done dark; do printf '%s=' \"$name\"; if [ -f " + q(self.remote) +
                         "/\"$name\" ]; then cat " + q(self.remote) + "/\"$name\"; else echo; fi; done")

    def verify_ready(self, raw, script_sha, original):
        lines = dict(line.split("=", 1) for line in raw.splitlines() if "=" in line)
        ready = lines.get("ready", "").split("|")
        require(len(ready) == 5 and ready[0] == self.token and all(value.isdigit() for value in ready[1:]), "Recovery worker has not reported readiness")
        pid, start, deadline, captured = map(int, ready[1:])
        require(pid > 1 and deadline - start == SECONDS and captured == original and not lines.get("done"), "Invalid or expired recovery lease")
        raw = self.root("test ! -L " + shlex.quote(self.remote + "/restore.sh") + " && sha256sum " +
                        shlex.quote(self.remote + "/restore.sh") + " && cat /proc/" + str(pid) + "/cmdline")
        # sha256sum itself prints the script path. Only the separate proc argv
        # can prove that this PID is the exact shell worker started above.
        hash_record, separator, cmdline = raw.partition("\n")
        worker_path = self.remote + "/restore.sh"
        require(separator and hash_record == script_sha + "  " + worker_path and
                cmdline.split("\0") == ["/system/bin/sh", worker_path, ""], "Recovery script/process identity differs")
        require(number(self.read()) == original, "Backlight changed before zero was authorized")

    def request(self, name):
        require(name in ("dark.request", "stop.request"), "Unknown experiment action")
        self.root("test \"$(cat " + ACTOR + "/owner)\" = " + self.token + " && test -d " + shlex.quote(self.remote) +
                  " && printf '%s\\n' " + self.token + " > " + shlex.quote(self.remote + "/" + name))

    def cleanup(self, script_sha):
        # No PID is killed. Refuse unknown files, symlinks, another token, or a
        # still-running script; leave uncertain recovery material for the owner.
        q = shlex.quote
        command = """set -eu
dir=@DIR@
test ! -L "$dir" && test -d "$dir"
test "$(cat @ACTOR@/owner)" = @TOKEN@
test "$(cat "$dir/done")" = '@TOKEN@|restored'
test "$(sha256sum "$dir/restore.sh" | cut -d ' ' -f 1)" = @SHA@
pid=$(cut -d '|' -f 2 "$dir/ready")
case "$pid" in ''|*[!0-9]*) exit 1;; esac
test ! -e "/proc/$pid"
for entry in "$dir"/*; do
  test ! -L "$entry"
  case "${entry##*/}" in restore.sh|ready|done|dark|dark.request|stop.request) test -f "$entry";; started) test -d "$entry" && test -z "$(ls -A "$entry")";; *) exit 1;; esac
done
test "$(ls -A @ACTOR@)" = owner
rm -f "$dir/restore.sh" "$dir/ready" "$dir/done" "$dir/dark" "$dir/dark.request" "$dir/stop.request"
rmdir "$dir/started" "$dir"
rm @ACTOR@/owner
rmdir @ACTOR@
""".replace("@DIR@", q(self.remote)).replace("@ACTOR@", ACTOR).replace("@TOKEN@", self.token).replace("@SHA@", script_sha)
        self.root(command)


def state_fields(raw):
    return dict(line.split("=", 1) for line in raw.splitlines() if "=" in line)


class Experiment:
    def __init__(self, operations, guard, watch, private, journal, sleep=time.sleep, clock=time.monotonic):
        self.ops, self.guard, self.watch, self.private, self.data = operations, guard, watch, Path(private), journal
        self.sleep, self.clock = sleep, clock

    def save(self):
        private_json(self.private / "state.json", self.data)

    def run(self):
        original = number(self.data["original"])
        script = watchdog_script(self.data["token"], original)
        sha = hashlib.sha256(script).hexdigest()
        self.data.update(script_sha256=sha, stage="creating")
        self.save()
        try:
            self.ops.create(script)
            self.data["stage"] = "starting-recovery"
            self.save()
            self.ops.start()
            end = self.clock() + 5
            while not state_fields(raw := self.ops.state()).get("ready"):
                require(self.clock() < end, "Recovery process did not become ready; zero is forbidden")
                self.sleep(.1)
            self.ops.verify_ready(raw, sha, original)
            self.guard.check()
            self.data["stage"] = "recovery-ready"
            self.save()
            self.ops.request("dark.request")
            end = self.clock() + SECONDS + 5
            while self.clock() < end:
                raw = state_fields(self.ops.state())
                if raw.get("done"):
                    require(raw["done"] == self.data["token"] + "|restored", "Recovery reported a conflict; preserve the journal")
                    break
                current = number(self.ops.read())
                if raw.get("dark") == self.data["token"] and current == 0 and not self.data.get("zero_observed"):
                    self.watch.allow_manual_touch()
                    self.data.update(stage="manual-black-window", zero_observed=True)
                    self.save()
                    print("DARK_READY: 所有者可观察黑屏并用手指触摸；本脚本不注入触摸", flush=True)
                self.guard.check()
                self.data["hardware_input_seen"] = self.watch.hardware_seen
                self.sleep(.2)
            else:
                raise RuntimeError("Recovery deadline observation timed out; retain the private journal")
            require(number(self.ops.read()) == original, "Original backlight was not restored")
            self.data.update(stage="restored", restored=True)
            self.save()
        finally:
            if not self.data.get("restored"):
                self.data["recovery_pending"] = True
                # This asks only the owned worker to restore; it cannot target a
                # new app/media owner. A disconnected device still has its timer.
                try:
                    self.ops.request("stop.request")
                except Exception:
                    self.data["transport_recovery_pending"] = True
                self.save()
        self.sleep(.2)
        self.ops.cleanup(sha)
        self.data["remote_cleaned"] = True
        self.save()


def present(owner_present, single_actor):
    require(owner_present and single_actor, "Explicit --owner-present and --single-device-actor are required before device access")


def load_journal(directory):
    private = Path(directory)
    path = private / "state.json"
    require(private.is_dir() and not private.is_symlink() and (private.stat().st_mode & 0o077) == 0 and
            path.is_file() and not path.is_symlink() and path.stat().st_size <= 1024 * 1024,
            "Use the original private recovery journal")
    data = json.loads(path.read_text())
    require(data.get("kind") == "mode-backlight-1" and data.get("serial") == PHYSICAL and
            re.fullmatch(r"[0-9a-f]{32}", data.get("token", "")), "Unknown recovery scope")
    original = number(data.get("original"))
    require(original > 0 and hashlib.sha256(data["original"].encode()).hexdigest() == data.get("original_sha256") and
            hashlib.sha256(watchdog_script(data["token"], original)).hexdigest() == data.get("script_sha256"),
            "Original value or recovery script checksum differs")
    return data


def record(directory, black, touch, double):
    private = Path(directory)
    data = load_journal(private)
    require(data.get("kind") == "mode-backlight-1" and data.get("restored") and data.get("zero_observed"), "A restored actual zero run is required")
    require(not (touch == "works" or double == "works") or data.get("hardware_input_seen"), "No real hardware event was observed in the authorized window")
    data["human_observation"] = {"black_screen": black, "hardware_touch": touch, "double_tap": double, "source": "owner report, not input injection"}
    private_json(private / "state.json", data)
    return data


def recover(device, directory, guard, sleep=time.sleep, clock=time.monotonic):
    """Observe the original worker and remove only its proved completed files.

    No new backlight write, app recovery, PID kill, or restarted worker occurs.
    """
    private = Path(directory)
    data = load_journal(private)
    require(device.shell("getprop", "ro.serialno").strip() == PHYSICAL and
            installed_sha(device, APP) == data["apk_sha256"], "Recovery APK/device differs")
    ops = Operations(device, data["token"])
    end = clock() + SECONDS + 5
    while True:
        guard.check()
        fields = state_fields(ops.state())
        if fields.get("done"):
            require(fields["done"] == data["token"] + "|restored" and number(ops.read()) == number(data["original"]),
                    "Recovery has a conflicting value; do not overwrite it")
            break
        require(clock() < end, "Original worker did not prove recovery; retain its files")
        sleep(.2)
    sleep(.2)
    ops.cleanup(data["script_sha256"])
    data.update(restored=True, remote_cleaned=True, stage="restored", recovery_pending=False)
    private_json(private / "state.json", data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owner-present", action="store_true")
    parser.add_argument("--single-device-actor", action="store_true")
    parser.add_argument("--serial", default=PHYSICAL)
    parser.add_argument("--apk-sha256")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--record", type=Path, help="Only record owner observations locally; no device access")
    parser.add_argument("--recover", type=Path, help="Observe the original timed recovery and clean only its proved token files")
    parser.add_argument("--black-screen", choices=("yes", "no", "unknown"), default="unknown")
    parser.add_argument("--touch", choices=("works", "fails", "not-tested"), default="not-tested")
    parser.add_argument("--double-tap", choices=("works", "fails", "not-tested"), default="not-tested")
    args = parser.parse_args()
    present(args.owner_present, args.single_device_actor)
    require(not (args.record and args.recover), "Choose recording or recovery")
    if args.record:
        record(args.record, args.black_screen, args.touch, args.double_tap)
        return
    require(args.recover is not None or re.fullmatch(r"[0-9a-f]{64}", args.apk_sha256 or "") and
            args.output is not None and not args.output.exists(), "A pinned APK and fresh result directory are required")
    require(args.serial == PHYSICAL, "Only the designated physical device is supported")
    if args.output:
        args.output.mkdir(mode=0o700, parents=True)
    lock_path = Path(tempfile.gettempdir()) / ("thinkos-mode-backlight-" + PHYSICAL + ".lock")
    lock = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    watch = None
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        device = NoRetryDevice(args.serial)
        if args.recover:
            watch = TouchWatch(device)
            recover(device, args.recover, Guard(device, watch))
            return
        require(device.shell("getprop", "ro.serialno").strip() == PHYSICAL and device.shell("getprop", "ro.build.version.sdk").strip() == "27", "Physical device/API differs")
        require(installed_sha(device, APP) == args.apk_sha256, "Installed main APK differs")
        watch = TouchWatch(device)
        owner_watch = OwnerTouchWatch(watch)
        guard = Guard(device, owner_watch)
        guard.check()
        ui = idle(device)
        original = device.shell("cat", BACKLIGHT)
        maximum = number(device.shell("cat", MAXIMUM))
        require(0 < number(original) <= maximum, "Start with a positive real backlight; preserve an already black device")
        files = {path: hashlib.sha256(raw).hexdigest() if (raw := read_app_file(device.serial, path)) is not None else None for path in IMMUTABLE}
        private = Path(tempfile.mkdtemp(prefix="thinkos-mode-backlight-recovery-"))
        os.chmod(private, 0o700)
        facts = policy_facts(device.shell("dumpsys", "device_policy"), device.shell("dumpsys", "activity", "activities"), home_component(device))
        data = {"kind": "mode-backlight-1", "token": uuid.uuid4().hex, "serial": PHYSICAL, "apk_sha256": args.apk_sha256,
                "original": original, "original_sha256": hashlib.sha256(original.encode()).hexdigest(), "maximum": maximum,
                "ui": ui, "policy": facts, "immutable_sha256": files, "restored": False, "hardware_input_seen": False, "human_observation": None}
        private_json(private / "state.json", data)
        result = {"complete": False, "full_MODE0_complete": False, "private_journal": str(private), "method": "sysfs-only",
                  "pending": ["owner visual/hardware/double-tap report", "Activity window brightness=0 branch", "overnight sensor/proximity record", "performance deferred"]}
        private_json(args.output / "results.json", result)
        print("Private recovery journal: " + str(private), flush=True)
        try:
            Experiment(Operations(device, data["token"]), guard, owner_watch, private, data).run()
            guard.check()
            require(all((hashlib.sha256(raw).hexdigest() if (raw := read_app_file(device.serial, path)) is not None else None) == digest for path, digest in files.items()), "An application preference changed; preserve newer bytes")
            current_ui = idle(device)
            require(all(ui.get(key) == current_ui.get(key) for key in ("page", "variant", "locale", "orientation")), "The original UI changed; preserve it without navigation recovery")
            require(facts == policy_facts(device.shell("dumpsys", "device_policy"), device.shell("dumpsys", "activity", "activities"), home_component(device)),
                    "HOME, Device Owner or LockTask changed; do not restore it")
            result.update(restored=True, remote_cleaned=data.get("remote_cleaned", False), zero_observed=data.get("zero_observed", False),
                          hardware_input_seen=data["hardware_input_seen"])
        except Exception as error:
            result.update(restored=data.get("restored", False), failure=type(error).__name__, recovery_pending=data.get("recovery_pending", False))
            raise
        finally:
            private_json(args.output / "results.json", result)
    finally:
        if watch is not None:
            watch.close()
        os.close(lock)


if __name__ == "__main__":
    main()
