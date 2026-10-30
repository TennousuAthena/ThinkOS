#!/usr/bin/env python3
"""Verify offline cold start and restore HOME/Wi-Fi/debug controls in a finally block."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--output', type=Path, default=Path('artifacts/pre-m1/cold-start'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ['adb', '-s', args.serial]

    def adb(*values, check=True):
        result = subprocess.run(base + list(values), capture_output=True, timeout=20)
        if check and result.returncode:
            raise RuntimeError(result.stderr.decode() + result.stdout.decode())
        return result.stdout

    def debug(command, *extra):
        return adb('shell', 'am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', command, *extra)

    wifi = adb('shell', 'settings', 'get', 'global', 'wifi_on').decode().strip()
    try:
        debug('hold_attention')
        debug('lux')
        debug('orientation', '--ei', 'value', '10')
        adb('reverse', '--remove', 'tcp:8769', check=False)
        adb('shell', 'cmd', 'package', 'set-home-activity', 'org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher')
        adb('shell', 'svc', 'wifi', 'disable')
        time.sleep(1)
        assert adb('shell', 'settings', 'get', 'global', 'wifi_on').decode().strip() == '0'
        adb('shell', 'am', 'force-stop', 'moe.kiwi.thinkos')
        launched = time.monotonic()
        start = adb('shell', 'am', 'start', '-W', '-n', 'moe.kiwi.thinkos/.MainActivity').decode()
        (args.output / 'am-start.txt').write_text(start)
        total = int(re.search(r'TotalTime: (\d+)', start).group(1))
        assert 0 < total < 3000, start
        # An Activity may draw before asynchronous plugin startup has supplied the home cards.
        # Measure complete factory content too; shell/ADB overhead makes this conservative.
        current = ''
        while time.monotonic() - launched < 3:
            debug('state')
            plugin_log = adb('logcat', '-d', '-s', 'ThinkOS:V').decode()
            current = plugin_log.split('DEBUG state=')[-1]
            if 'page=home.main' in current and 'id=clock' in current and 'id=home' in current:
                break
            time.sleep(.1)
        content_ready_ms = round((time.monotonic() - launched) * 1000)
        assert content_ready_ms < 3000, 'Home cards not ready within 3s: ' + current
        log = adb('logcat', '-d', '-s', 'ActivityManager:I', 'ThinkOS:V', 'AndroidRuntime:E').decode()
        (args.output / 'logcat.txt').write_text(log)
        (args.output / 'offline-home.png').write_bytes(adb('exec-out', 'screencap', '-p'))
        assert 'page=home.main' in current and 'safeMode=false' in current
        assert 'id=clock' in current and 'id=home' in current and 'id=runtime-probe' not in current
        (args.output / 'result.json').write_text(json.dumps({'total_ms': total, 'content_ready_ms': content_ready_ms, 'wifi_disabled': True, 'server': 'none', 'state': current.strip()}, indent=2, ensure_ascii=False) + '\n')
        print('PASS offline cold start ' + str(total) + 'ms; complete home cards ' + str(content_ready_ms) + 'ms')
    finally:
        if wifi != '0':
            adb('shell', 'svc', 'wifi', 'enable', check=False)
        adb('shell', 'cmd', 'package', 'set-home-activity', 'moe.kiwi.thinkos/.MainActivity', check=False)
        adb('shell', 'am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity', check=False)
        debug('hold_attention')
        debug('lux')
        debug('orientation', '--ei', 'value', '10')
        restored = {
            'wifi': adb('shell', 'settings', 'get', 'global', 'wifi_on').decode().strip(),
            'rotation': adb('shell', 'settings', 'get', 'system', 'accelerometer_rotation').decode().strip(),
            'home': adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME').decode().strip(),
            'development_overrides': adb('shell', 'run-as', 'moe.kiwi.thinkos', 'ls', 'files/dev-plugins').decode().strip(),
        }
        (args.output / 'restored.json').write_text(json.dumps(restored, indent=2) + '\n')


if __name__ == '__main__':
    main()
