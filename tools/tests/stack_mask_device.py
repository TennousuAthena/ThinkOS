#!/usr/bin/env python3
"""The home page's card stacks mask their cards: while one stack is dragged, nothing outside it may change.

    python3 tools/tests/stack_mask_device.py [--output artifacts/stacks]

Drags the second stack (the lower one in portrait, the right one in landscape) slowly up and then down, takes a screenshot with the
finger still on the screen, and compares it with the ones taken before. A card that slides over the other stack, or out over the
margin and the screen edge, makes pixels outside the dragged stack's rectangle differ. The stack's own area has to change, or no
drag happened. It changes nothing on the device: the screen orientation, the attention hold and the burn-in nudge are put back.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
from media_device import APP, Device

# docs/04-ui-spec.md §4.1: 20 dp around the page, 16 dp between the two stacks (LayoutSpec.stack).
EDGE_DP, GAP_DP = 20, 16
# The clock breathes and the background shifts a little, so "unchanged" cannot mean pixel-identical: a pixel only counts as changed when
# it leaves the range it moved in over several screenshots of the page at rest, by more than this.
TOLERANCE = 8
# Anti-aliasing and the burn-in nudge (a few pixels) around the dragged stack's rectangle.
SLACK_PX = 12
REST_SHOTS = 6
REST_GAP_S = 0.9


def stacks(width, height, density):
    edge, gap = int(EDGE_DP * density), int(GAP_DP * density)
    if width > height:
        cell = (width - edge * 2 - gap) // 2
        return [(edge + i * (cell + gap), edge, edge + i * (cell + gap) + cell, height - edge) for i in (0, 1)]
    cell = (height - edge * 2 - gap) // 2
    return [(edge, edge + i * (cell + gap), width - edge, edge + i * (cell + gap) + cell) for i in (0, 1)]


def envelope(shots):
    """The darkest and the brightest each pixel was across the screenshots."""
    low = high = shots[0].convert('L')
    for shot in shots[1:]:
        gray = shot.convert('L')
        low, high = ImageChops.darker(low, gray), ImageChops.lighter(high, gray)
    return low, high


def outside_range(low, high, image, box=None, outside=None):
    """A mask of the pixels of [image] that left the [low, high] range by more than the tolerance."""
    gray = image.convert('L')
    away = ImageChops.lighter(ImageChops.subtract(low, gray), ImageChops.subtract(gray, high)).point(lambda v: 255 if v > TOLERANCE else 0)
    if outside is not None:
        l, t, r, bottom = outside
        away.paste(0, (max(l - SLACK_PX, 0), max(t - SLACK_PX, 0), min(r + SLACK_PX, away.width), min(bottom + SLACK_PX, away.height)))
    return away.crop(box) if box is not None else away


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/stacks')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    results = []
    match = re.search(r'data="(\{.*\})"', device.debug('ui_state'))
    original = json.loads(match.group(1)) if match else {}

    def check(name, ok, detail=''):
        results.append(bool(ok))
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def shot(name):
        path = args.output / (name + '.png')
        device.screenshot(path)
        return Image.open(path).convert('RGB')

    def orient(value):
        # The receiver reads an int; Device.debug sends integers as longs.
        device.shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', value)

    def quiet_minute():
        # The clock card shows hours and minutes: a screenshot pair that straddles a minute change differs by the digits alone.
        seconds = int(device.shell('date', '+%S').strip())
        # The rest shots, the drag and the screenshot in it take about 15 s.
        if seconds > 30: time.sleep(60 - seconds + 2)

    density = int(re.search(r'(\d+)', device.shell('wm', 'density').split('Physical density:')[-1]).group(1)) / 160
    try:
        device.debug('hold_attention', value='ENGAGED')
        device.debug('theme', value='DARK')
        device.debug('drift', x=0.0, y=0.0)   # the burn-in nudge moves everything by a pixel now and then
        for label, orientation in (('portrait', 1), ('landscape', 8)):
            orient(orientation)
            time.sleep(2.5)
            device.debug('page', value='home.main')
            time.sleep(3.0)
            raw = device.debug('benchmark_state')
            state = re.search(r'data="([^"]*)"', raw)
            assert state and state.group(1).split('|')[0] == 'home.main', 'The stack fixture must actually show home.main: ' + raw
            width, height = None, None
            for way in ('up', 'down'):
                quiet_minute()
                rests = []
                for k in range(REST_SHOTS):
                    rests.append(shot(f'{label}-{way}-rest-{k}'))
                    time.sleep(REST_GAP_S)
                width, height = rests[0].size
                if (label == 'landscape') != (width > height):
                    check(f'{label}: the screen is in that orientation', False, f'{width}x{height}')
                    break
                box = stacks(width, height, density)[1]
                cx, cy = (box[0] + box[2]) // 2, (box[1] + box[3]) // 2
                start, end = (cy + 120, cy - 200) if way == 'up' else (cy - 120, cy + 200)
                low, high = envelope(rests)
                # A slow drag, photographed while the finger is still down.
                drag = subprocess.Popen(device.base + ['shell', 'input', 'swipe', str(cx), str(start), str(cx), str(end), '4000'],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                time.sleep(1.9)
                mid = shot(f'{label}-{way}-mid')
                drag.wait()
                time.sleep(1.5)
                total = width * height
                outside = outside_range(low, high, mid, outside=box).histogram()[255]
                if outside: outside_range(low, high, mid, outside=box).save(args.output / f'{label}-{way}-outside.png')
                inside = outside_range(low, high, mid, box=box).histogram()[255]
                check(f'{label}, dragged {way}: the stack\'s own card moved', inside > total * 0.01, f'{inside} px changed inside')
                check(f'{label}, dragged {way}: nothing outside the stack changed', outside <= total * 0.001, f'{outside} px changed outside')
                device.debug('page', value='home.main')   # back to a stack at rest for the next drag
                time.sleep(2.5)
    finally:
        device.debug('drift')
        for command, key in (('hold_attention', 'attention'), ('theme', 'theme')):
            device.debug(command, **({'value': original[key]} if original.get(key) is not None else {}))
        orient(original.get('orientation', 10))
    failed = results.count(False)
    print(f'\n{len(results) - failed}/{len(results)} checks passed')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
