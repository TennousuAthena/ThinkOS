#!/usr/bin/env python3
"""Capture debug-device gesture/theme/rotation evidence; leaves AWAKE/DARK for benchmarking."""
import argparse
import json
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/pre-m1/ui')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ['adb', '-s', args.serial]

    def adb(*values, data=None):
        return subprocess.check_output(base + list(values), input=data, timeout=20)

    def debug(command, value=None, kind='--es'):
        values = ['shell', 'am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', command]
        if value is not None:
            values += [kind, 'value', str(value)]
        return adb(*values)

    def shot(name):
        (args.output / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))

    def state():
        debug('state')
        log = adb('logcat', '-d', '-s', 'ThinkOS:V').decode().split('DEBUG state=')[-1]
        return {key: re.search(key + r'=([\w.]+)', log).group(1) for key in ('page', 'attention', 'variant', 'theme')}

    def theme(lux, expected):
        debug('lux', lux, '--ef')
        deadline = time.monotonic() + 45
        while state()['theme'] != expected:
            if time.monotonic() > deadline:
                raise AssertionError('Theme did not reach ' + expected)
            time.sleep(1)
        time.sleep(2.2)  # Complete the theme interpolation before capture/measurement.

    def swipe(x1, y1, x2, y2, duration=500, preview=None):
        process = subprocess.Popen(base + ['shell', 'input', 'swipe', *map(str, (x1, y1, x2, y2, duration))], stdout=subprocess.DEVNULL)
        if preview:
            time.sleep(duration / 1800)
            shot(preview)
        process.wait(timeout=8)
        time.sleep(.7)

    debug('orientation', 0, '--ei')
    debug('hold_attention', 'AWAKE')
    theme(10000, 'LIGHT')
    # Installing an APK can restore the existing task on a remembered clock page.
    for _ in range(3):
        if state()['page'] == 'home.main':
            break
        swipe(1100, 400, 150, 400)
    assert state()['page'] == 'home.main'
    with tempfile.TemporaryDirectory(prefix='thinkos-ui-') as temporary:
        source = Path(temporary) / 'home'
        shutil.copytree(ROOT / 'plugins/home', source)
        manifest = json.loads((source / 'manifest.json').read_text())
        manifest['version'] = '0.1.1'
        (source / 'manifest.json').write_text(json.dumps(manifest))
        view = {'layout': 'stacks', 'slots': {
            'left': [{'widget': 'clock.now'}, {'widget': 'clock.calendar'}],
            'right': [{'widget': 'clock.calendar'}, {'widget': 'clock.now'}]}}
        (source / 'views/main.json').write_text(json.dumps(view))
        package, _ = package_plugin(source, Path(temporary) / 'out')
        try:
            publish_directory(package, args.serial)
            time.sleep(1)
            shot('home-light-hot-update')
            swipe(300, 600, 300, 200, 2100, 'stack-following-finger')
            shot('stack-settled')
            swipe(300, 500, 300, 460, 900)
            shot('stack-short-drag-cancelled')
            swipe(1100, 400, 150, 400, 2400, 'page-following-finger')
            assert state()['page'] == 'clock.face'
            for _ in range(4):
                if state()['variant'] == 'digital':
                    break
                swipe(700, 650, 700, 120)
            assert state()['variant'] == 'digital'
            shot('clock-digital-light')
            swipe(650, 400, 570, 400, 900)
            debug('state')
            swipe(700, 650, 700, 120, 1800, 'variant-following-finger')
            assert state()['variant'] == 'analog'
            shot('clock-analog-light')
            # NIGHT transitions bypass the two-minute day-theme switch hysteresis.
            theme(.1, 'NIGHT')
            shot('clock-analog-night')
            theme(20, 'DARK')
            shot('clock-analog-dark')
            debug('orientation', 1, '--ei')
            time.sleep(1)
            shot('clock-analog-portrait')
            swipe(400, 1050, 400, 250)
            swipe(400, 1050, 400, 250)
            assert state()['variant'] == 'world'
            shot('clock-world-portrait')
            theme(.1, 'NIGHT')
            shot('clock-world-night')
            debug('hold_attention', 'AMBIENT')
            time.sleep(.7)
            debug('hold_attention', 'AWAKE')
            time.sleep(.15)
            shot('wake-ripple')
            time.sleep(.7)
            debug('orientation', 0, '--ei')
            theme(20, 'DARK')
            # Return to the clock's digital face for a repeatable high-frame-rate benchmark.
            swipe(700, 650, 700, 120)
            shot('clock-digital-dark')
        finally:
            remove_plugin('home', args.serial)
            time.sleep(.5)
    debug('state')
    (args.output / 'logcat.txt').write_bytes(adb('logcat', '-d', '-s', 'ThinkOS:V', 'AndroidRuntime:E'))
    print('Captured gesture, variant, stack, rotation, theme and wake evidence in ' + str(args.output))


if __name__ == '__main__':
    main()
