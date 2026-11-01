"""Shared helpers for the media device tests: adb, the DebugReceiver and the engine's `media_state` dump."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import threading
import time

# adb 37 on macOS can abort in its mDNS bridge; the tests never need mDNS (the device is on USB).
os.environ.setdefault('ADB_MDNS', '0')

APP = 'moe.kiwi.thinkos'
STATE_MARK = 'DEBUG media '


class Device:
    """One connected device. A single logcat reader runs for the life of the object."""

    def __init__(self, serial=None):
        self.serial = serial or os.environ.get('ANDROID_SERIAL', 'HUA0BWUT')
        self.base = ['adb', '-s', self.serial]
        self.lines = []
        self._lock = threading.Lock()
        self._logcat = None

    # -- adb -------------------------------------------------------------------------------------------
    def adb(self, *values, timeout=60, check=True):
        command = self.base + [str(v) for v in values]
        for attempt in (1, 2):
            try:
                return subprocess.run(command, capture_output=True, timeout=timeout, check=check).stdout
            except subprocess.CalledProcessError:
                if attempt == 2: raise
                # The adb server dies now and then; start it again once and repeat the call.
                subprocess.run(['adb', 'start-server'], capture_output=True, timeout=30)
                time.sleep(1)

    def shell(self, *values, check=True):
        return self.adb('shell', '-T', shlex.join([str(v) for v in values]), check=check).decode(errors='replace')

    def push(self, source, destination):
        self.adb('push', source, destination, timeout=300)

    def screenshot(self, path):
        data = self.adb('exec-out', 'screencap', '-p')
        Path(path).write_bytes(data)
        return path

    def tap(self, x, y):
        self.shell('input', 'tap', int(x), int(y))

    # -- logcat ----------------------------------------------------------------------------------------
    def start_log(self):
        self.adb('logcat', '-c', check=False)   # best effort: logd sometimes refuses; the reader only looks at new lines anyway
        self._logcat = subprocess.Popen(self.base + ['logcat', '-v', 'epoch', '-s', 'ThinkOS:V', 'AndroidRuntime:E'],
                                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors='replace')
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self._logcat.stdout:
            with self._lock:
                self.lines.append(line.rstrip('\n'))

    def stop_log(self):
        if self._logcat:
            self._logcat.terminate()
            self._logcat = None

    def log_since(self, mark):
        with self._lock:
            return list(self.lines[mark:])

    def log_mark(self):
        with self._lock:
            return len(self.lines)

    # -- DebugReceiver ---------------------------------------------------------------------------------
    def debug(self, command, **extras):
        """Floats go as --ef, integers as --el (long), everything else as --es."""
        values = ['am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', command]
        for key, value in extras.items():
            if isinstance(value, float): values += ['--ef', key, repr(value)]
            elif isinstance(value, int) and not isinstance(value, bool): values += ['--el', key, str(value)]
            else: values += ['--es', key, str(value)]
        return self.shell(*values)

    def load(self, url, kind='audio', **extras):
        mark = self.log_mark()
        self.debug('media_load', url=url, kind=kind, **extras)
        time.sleep(0.15)
        return mark

    def state(self, timeout=6):
        """Ask the app to dump the engine snapshot and return it as a dict (None if nothing arrived)."""
        mark = self.log_mark()
        self.debug('media_state')
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            for line in self.log_since(mark):
                if STATE_MARK in line:
                    return json.loads(line[line.index(STATE_MARK) + len(STATE_MARK):])
            time.sleep(0.1)
        return None

    def media_log(self, mark):
        """Warnings and errors the engine reported to the owner since [mark]."""
        return [line for line in self.log_since(mark) if '[media:' in line or 'DEBUG media_load rejected' in line]

    def prepare_display(self):
        """Dark theme and an awake display, so screenshots are normal and playback is never dimmed."""
        self.debug('theme', value='DARK')
        self.debug('lux', value=200.0)
        self.debug('hold_attention', value='AWAKE')

    def open_page(self, key):
        self.debug('page', value=key)


def wait_for(device, test, seconds, interval=1.0):
    """Poll `test(state)` until it returns something truthy; returns (result, last_state)."""
    until = time.monotonic() + seconds
    last = None
    while time.monotonic() < until:
        last = device.state()
        if last is not None:
            result = test(last)
            if result: return result, last
        time.sleep(interval)
    return None, last
