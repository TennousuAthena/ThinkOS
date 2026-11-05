#!/usr/bin/env python3
"""ThinkOS Immersive on the device: a swipe in from a screen edge must not bring up Android's bars while ThinkOS has the screen.

    python3 tools/tests/immersive_device.py [--output artifacts/immersive]

Needs system/thinkos-immersive installed, enabled for the system framework in Vector, and the device restarted afterwards
(system/install-immersive.sh). Without it the edge swipes show the bars and the checks fail, which is how this test tells.
It reads the window manager's own state (`dumpsys window policy`: mTransientBar of the status bar and of the navigation bar)
and swipes on whatever page is showing; it changes nothing and leaves nothing behind.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
from media_device import Device


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/immersive')
    parser.add_argument('--external-returns', action='store_true', help='Also validate ADR-046 physical global gestures; immersive1.2/shell1.1 and a manual reboot are required')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    results = []

    def check(name, ok, detail=''):
        results.append(bool(ok))
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def bars():
        out = device.shell('dumpsys', 'window', 'policy')
        return re.findall(r'mTransientBar=(\w+)', out)

    def settle():
        for _ in range(20):
            if set(bars()) <= {'TRANSIENT_BAR_NONE'}: return
            time.sleep(0.5)

    def swipe(x1, y1, x2, y2, ms=160): device.shell('input', 'swipe', x1, y1, x2, y2, ms)

    log = device.shell('logcat', '-d', '-s', 'LSPosed-Bridge:*', 'Xposed:*', 'Vector:*', check=False) + device.shell('logcat', '-d', check=False)
    line = next((l for l in log.splitlines() if 'ThinkOS-immersive' in l), None)
    print('INFO module log: ' + (line.split(': ', 1)[-1].strip() if line else 'no line found (the log may have rolled over since boot)'))

    try:
        device.debug('hold_attention', value='ENGAGED')
        device.shell('input', 'keyevent', 'KEYCODE_HOME')
        time.sleep(1.2)
        settle()
        focus = device.shell('dumpsys', 'window', 'windows')
        check('ThinkOS has the screen', re.search(r'mCurrentFocus=Window\{\S+ \S+ moe\.kiwi\.thinkos/', focus) is not None)
        check('no bar is up to begin with', set(bars()) == {'TRANSIENT_BAR_NONE'}, bars())

        cases = [('from the top edge', (400, 3, 400, 520)), ('from just inside the top edge', (400, 30, 400, 520)),
                 ('from the bottom edge', (400, 1277, 400, 760)), ('from just inside the bottom edge', (400, 1250, 400, 760))]
        for name, points in cases:
            swipe(*points)
            time.sleep(0.45)
            seen = bars()
            if set(seen) != {'TRANSIENT_BAR_NONE'}: device.screenshot(args.output / (name.replace(' ', '-') + '.png'))
            check(f'a swipe {name} brings up no bar', set(seen) == {'TRANSIENT_BAR_NONE'} and len(seen) == 2, seen)
            device.shell('input', 'keyevent', 'KEYCODE_HOME')
            settle()
            time.sleep(1.0)
    finally:
        device.debug('hold_attention')
        device.shell('input', 'keyevent', 'KEYCODE_HOME', check=False)
    failed = results.count(False)
    print(f'\n{len(results) - failed}/{len(results)} checks passed')
    if args.external_returns and not failed:
        subprocess.run([sys.executable,str(ROOT/'tools/tests/global_back_device.py'),'--serial',args.serial,
                        '--native-gestures','--output',str(args.output/'external-returns')],check=True)
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
