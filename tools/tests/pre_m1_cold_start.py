#!/usr/bin/env python3
"""Verify offline cold start and restore HOME/Wi-Fi/debug controls in a finally block."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from app_network_block import AppNetworkBlock


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--output', type=Path, default=Path('artifacts/pre-m1/cold-start'))
    parser.add_argument('--wireless-offline',action='store_true',help='Block application IPv4/IPv6 traffic while keeping wireless ADB connected')
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
    network=AppNetworkBlock(args.serial) if args.wireless_offline else None
    try:
        debug('hold_attention')
        debug('lux')
        debug('orientation', '--ei', 'value', '10')
        adb('reverse', '--remove', 'tcp:8769', check=False)
        adb('shell', 'cmd', 'package', 'set-home-activity', 'org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher')
        if network:
            network.start()
            (args.output/'network-block.txt').write_text(network.evidence())
        else:
            adb('shell', 'svc', 'wifi', 'disable')
            time.sleep(1)
            assert adb('shell', 'settings', 'get', 'global', 'wifi_on').decode().strip() == '0'
        adb('shell', 'am', 'force-stop', 'moe.kiwi.thinkos')
        launched = time.monotonic()
        start = adb('shell', 'am', 'start', '-W', '-n', 'moe.kiwi.thinkos/.MainActivity').decode()
        (args.output / 'am-start.txt').write_text(start)
        total = int(re.search(r'TotalTime: (\d+)', start).group(1))
        assert 0 < total < 3000, start
        # Wireless ADB polling can take hundreds of milliseconds. Measure the device's
        # reportFullyDrawn duration rather than counting host transport as application work.
        fully_drawn = None
        while time.monotonic() - launched < 8:
            timings = adb('logcat', '-d', '-t', '200', '-s', 'ActivityManager:I').decode()
            lines = timings.splitlines()
            displayed = max((i for i, line in enumerate(lines) if 'Displayed moe.kiwi.thinkos/.MainActivity:' in line), default=-1)
            for line in lines[displayed+1:]:
                m = re.search(r'Fully drawn moe\.kiwi\.thinkos/\.MainActivity: \+(?:(\d+)s)?(\d+)ms', line)
                if m: fully_drawn = int(m[1] or 0)*1000 + int(m[2])
            if fully_drawn is not None: break
            time.sleep(.1)
        assert fully_drawn is not None and fully_drawn < 3000, 'Device fully drawn duration: ' + str(fully_drawn)
        debug('state')
        current = adb('logcat', '-d', '-t', '25', '-s', 'ThinkOS:V').decode().split('DEBUG state=')[-1]
        content_ready_ms = fully_drawn
        host_observed_ms = round((time.monotonic()-launched)*1000)
        log = adb('logcat', '-d', '-s', 'ActivityManager:I', 'ThinkOS:V', 'AndroidRuntime:E').decode()
        (args.output / 'logcat.txt').write_text(log)
        (args.output / 'offline-home.png').write_bytes(adb('exec-out', 'screencap', '-p'))
        assert 'page=home.main' in current and 'safeMode=false' in current
        assert 'id=clock' in current and 'id=home' in current and 'id=runtime-probe' not in current
        (args.output / 'result.json').write_text(json.dumps({'total_ms': total, 'content_ready_ms': content_ready_ms, 'host_observed_ms': host_observed_ms, 'wifi_disabled': network is None, 'app_network_blocked': network is not None, 'server': 'none', 'state': current.strip()}, indent=2, ensure_ascii=False) + '\n')
        print('PASS offline cold start ' + str(total) + 'ms; complete home cards ' + str(content_ready_ms) + 'ms')
    finally:
        if network:network.stop()
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
