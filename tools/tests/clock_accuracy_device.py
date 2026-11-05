#!/usr/bin/env python3
"""ADR-029 device check of the clock: each second change on the clock face, the time against a time server,
a restart, and recovery after a stalled main thread. Restores the seconds setting and debug holds afterwards."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tools/tests')]
from device_recovery import capture, restore
from media_device import Device
from ui_walkthrough import broadcast_json
APP = 'moe.kiwi.thinkos'
SETTINGS = 'files/settings/local.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/clock/accuracy')
    parser.add_argument('--seconds', type=int, default=60, help='length of the steady run')
    parser.add_argument('--face', choices=('digital','analog'), default='digital', help='Pin the tested face instead of inheriting a saved variant')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ['adb', '-s', args.serial]
    report = {'complete': False, 'passed': [], 'face': args.face}

    def adb(*values, data=None):
        return subprocess.run(base + list(map(str, values)), input=data, capture_output=True, timeout=60).stdout
    def shell(*values, data=None): return adb('shell', '-T', shlex.join(list(map(str, values))), data=data)
    def debug(command, value=None):
        values = ['am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', command]
        if value is not None: values += ['--ei', 'value', str(value)]
        shell(*values)
    def passed(text): report['passed'].append(text); print('PASS ' + text, flush=True)
    def logs(): return adb('logcat', '-d', '-s', 'ThinkOS:V', 'AndroidRuntime:E').decode(errors='replace')
    def stats(label):
        adb('logcat', '-c'); debug('clock_report'); time.sleep(1.2)
        found = re.findall(r'DEBUG clock n=(\d+)(?: p50=(-?\d+)ms p95=(-?\d+)ms p99=(-?\d+)ms min=(-?\d+)ms max=(-?\d+)ms skipped=(\d+))?', logs())
        assert found, 'No clock report'
        n, p50, p95, p99, low, high, skipped = found[-1]
        result = dict(n=int(n), p50=int(p50 or 0), p95=int(p95 or 0), max=int(high or 0), skipped=int(skipped or 0))
        print(f'  {label}: {result}', flush=True)
        return result
    def error():
        adb('logcat', '-c'); debug('clock_error')
        until = time.monotonic() + 12
        while time.monotonic() < until:
            found = re.findall(r'DEBUG clock_error ms=(-?\d+) rtt=(\d+) synced=(\w+)', logs())
            if found: return int(found[-1][0]), int(found[-1][1]), found[-1][2] == 'true'
            time.sleep(.5)
        raise AssertionError('clock_error gave no answer')
    def stop(): shell('am', 'force-stop', APP); time.sleep(1)
    def start(): shell('am', 'start', '-n', APP + '/.MainActivity'); time.sleep(10)
    def prepare():
        debug('hold_attention'); shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'hold_attention', '--es', 'value', 'AWAKE')
        shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'page', '--es', 'value', 'clock.face')
        shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'variant', '--es', 'value', args.face); time.sleep(3)
    def read_settings(): return adb('shell', '-T', shlex.join(['run-as', APP, 'cat', SETTINGS]))
    def write_settings(raw):
        shell('run-as', APP, 'sh', '-c', f'cat > {SETTINGS}.new && mv {SETTINGS}.new {SETTINGS} && rm -f {SETTINGS}.bak', data=raw)

    original = read_settings()
    device = Device(args.serial)
    assert any(APP+'/' in line for line in device.shell('dumpsys', 'activity', 'activities').splitlines() if 'mResumedActivity' in line)
    journal = capture(device, broadcast_json(device, 'ui_state'))
    print('Private recovery journal: '+str(journal), flush=True)
    try:
        current = json.loads(original or b'{}')
        current.setdefault('clock', {})['seconds'] = True
        stop(); write_settings(json.dumps(current, separators=(',', ':'), ensure_ascii=False).encode()); start(); prepare()

        debug('clock_reset'); time.sleep(args.seconds)
        steady = stats(f'{args.seconds} s on the clock face')
        assert steady['n'] >= args.seconds - 5, steady
        assert steady['skipped'] == 0, steady
        assert steady['p95'] <= 40 and steady['max'] <= 80, steady
        passed(f'{steady["n"]} consecutive second changes: p50 {steady["p50"]} ms, p95 {steady["p95"]} ms, max {steady["max"]} ms late, none skipped')

        errors = [error() for _ in range(3)]
        print('  against a time server (ms, rtt, synced):', errors, flush=True)
        assert all(synced and abs(ms) <= 100 for ms, _, synced in errors), errors
        passed('the displayed time agrees with fresh time-server readings: ' + ', '.join(f'{ms:+d} ms' for ms, _, _ in errors))

        stop(); start()
        ms, rtt, synced = error()
        print('  right after a restart:', (ms, rtt, synced), flush=True)
        assert synced and abs(ms) <= 100, (ms, rtt, synced)
        passed(f'after a process restart the first reading is already right ({ms:+d} ms)')

        prepare(); debug('clock_reset'); time.sleep(3)
        debug('clock_stall', 700); time.sleep(1.5)
        stalled = stats('after a 700 ms stalled main thread')
        # The stall ran from just before a boundary to 700 ms past it: that one second must be shown late by about the stall, and no more.
        assert 650 <= stalled['max'] <= 900 and stalled['skipped'] <= 1, stalled
        debug('clock_reset'); time.sleep(12)
        recovered = stats('the 12 s after it')
        assert recovered['n'] >= 10 and recovered['skipped'] == 0 and recovered['p95'] <= 40, recovered
        passed(f'a main thread stalled 700 ms past a boundary shows the true second right after it (late by {stalled["max"]} ms, never stale); the next seconds are back to p95 {recovered["p95"]} ms')
        report['complete'] = True
    except Exception as error:
        report['failure'] = str(error)
        raise
    finally:
        (args.output / 'logcat.txt').write_text(logs())
        try: report.update(restore(journal, device))
        except Exception as error:
            report['recovery_error'] = str(error); report['private_recovery_journal'] = str(journal)
        (args.output / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    assert not report.get('recovery_error'), report.get('recovery_error')


if __name__ == '__main__':
    main()
