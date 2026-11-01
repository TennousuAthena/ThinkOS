#!/usr/bin/env python3
"""Every image layer must keep a real frame when its page returns: the poster path shows photos during layout."""
import argparse
import os
from pathlib import Path
import re
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
APP = 'moe.kiwi.thinkos'
BOUNDS = re.compile(r'\{\w+ (\S+) \S+ (-?\d+),(-?\d+)-(-?\d+),(-?\d+)')


def pictures(tree):
    """(root size, [layer sizes]) of each visible picture, read from the live view tree."""
    lines = tree.splitlines()
    found = []
    for i, line in enumerate(lines):
        if 'Picture$root' not in line:
            continue
        match = BOUNDS.search(line)
        if not match or match[1][0] != 'V':
            continue
        size = (int(match[4]) - int(match[2]), int(match[5]) - int(match[3]))
        indent = len(line) - len(line.lstrip())
        layers = []
        for child in lines[i + 1:]:
            depth = len(child) - len(child.lstrip())
            if depth <= indent:
                break
            if depth == indent + 2 and 'android.widget.FrameLayout' in child:
                inner = BOUNDS.search(child)
                layers.append((int(inner[4]) - int(inner[2]), int(inner[5]) - int(inner[3])))
        found.append((size, layers))
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/tiles/photo-device')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ['adb', '-s', args.serial]

    def shell(*values):
        return subprocess.run(base + ['shell', '-T', shlex.join(map(str, values))], capture_output=True, timeout=40, check=True).stdout
    def debug(command, value=None, kind='--es'):
        values = ['am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', command]
        if value is not None: values += [kind, 'value', value]
        shell(*values)
    def shot(name):
        (args.output / (name + '.png')).write_bytes(subprocess.run(base + ['exec-out', 'screencap', '-p'], capture_output=True, timeout=40, check=True).stdout)
    failures = []
    def verify(name, wanted):
        """Waits for a picture with a layer on this page, then requires every layer to fill its picture."""
        until = time.monotonic() + 12
        seen = []
        while time.monotonic() < until:
            seen = pictures(shell('dumpsys', 'activity', 'top').decode(errors='replace'))
            drawn = [item for item in seen if item[0][0] > 0 and item[1]]
            if len(drawn) >= wanted and all(all(layer == size for layer in layers) for size, layers in drawn):
                print('PASS ' + name, flush=True)
                shot(name)
                return
            time.sleep(.5)
        print(f'FAIL {name}: {seen}', flush=True)
        shot(name + '-failed')
        failures.append(name)
    def page(key):
        # A request sent while the app is still restarting is dropped, so confirm the page really opened.
        until = time.monotonic() + 20
        while time.monotonic() < until:
            debug('page', key)
            time.sleep(1.5)
            if ('data="' + key + '|').encode() in shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'benchmark_state'):
                return
        raise SystemExit('Cannot open ' + key)

    try:
        debug('hold_attention', 'AWAKE'); debug('lux', '20', '--ef'); debug('theme', 'DARK'); debug('orientation', '0', '--ei')
        page('tiles.main'); verify('tiles-first', 1)
        for round_ in range(1, 6):
            page('clock.face'); page('tiles.main'); verify(f'tiles-return-{round_}', 1)
        page('gallery.slideshow'); verify('slideshow-first', 1)
        page('tiles.main'); verify('tiles-from-slideshow', 1)
        page('gallery.slideshow'); verify('slideshow-return', 1)
        page('aero.main'); verify('aero-first', 1)
        page('tiles.main'); page('aero.main'); verify('aero-return', 1)
        page('tiles.main')
        debug('attention', 'AMBIENT'); debug('hold_attention', 'AMBIENT'); time.sleep(2); verify('tiles-ambient', 1)
        debug('hold_attention', 'ASLEEP'); time.sleep(2); debug('hold_attention', 'AWAKE'); time.sleep(2)
        # Waking returns to the home role by design, so the tile page has to be reopened.
        page('tiles.main'); verify('tiles-wake', 1)
        debug('orientation', '1', '--ei'); time.sleep(3); verify('tiles-portrait', 1)
        debug('orientation', '0', '--ei'); time.sleep(3); verify('tiles-landscape-again', 1)
        debug('theme', 'LIGHT'); time.sleep(2); verify('tiles-light', 1)
    finally:
        debug('theme'); debug('lux'); debug('hold_attention'); debug('orientation', '10', '--ei')
    if failures:
        raise SystemExit('Failed: ' + ', '.join(failures))


if __name__ == '__main__':
    main()
