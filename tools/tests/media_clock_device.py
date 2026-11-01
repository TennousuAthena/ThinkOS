#!/usr/bin/env python3
"""The clock comes first: its seconds must not slip while music plays (memory: clock-has-top-priority).

    python3 tools/tests/media_clock_device.py [--seconds 60]

Three runs on the clock face, each measuring how late every second change reaches the screen (the app's own
ClockStats, same thresholds as tools/tests/clock_accuracy_device.py): nothing playing; music started through the
engine; music started the way a person does it, from the player plugin's list (which also feeds the plugin a media
state update every second).
"""
import argparse
import os
from pathlib import Path
import re
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
sys.path.insert(0, str(ROOT / 'tools'))
from device_hierarchy import dump_hierarchy
from media_device import Device


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--seconds', type=int, default=60)
    args = parser.parse_args()
    device = Device(args.serial)
    device.start_log()
    results = []

    def check(name, ok, detail=''):
        results.append(ok)
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def measure(label):
        device.debug('clock_reset')
        time.sleep(args.seconds)
        mark = device.log_mark()
        device.debug('clock_report')
        time.sleep(1.5)
        text = '\n'.join(device.log_since(mark))
        found = re.findall(r'DEBUG clock n=(\d+)(?: p50=(-?\d+)ms p95=(-?\d+)ms p99=(-?\d+)ms min=(-?\d+)ms max=(-?\d+)ms skipped=(\d+))?', text)
        assert found, 'no clock report'
        n, p50, p95, _, _, high, skipped = found[-1]
        stats = dict(n=int(n), p50=int(p50 or 0), p95=int(p95 or 0), max=int(high or 0), skipped=int(skipped or 0))
        check('%s: %d second changes, none skipped, p95 <= 40 ms, worst <= 80 ms' % (label, stats['n']),
              stats['n'] >= args.seconds - 5 and stats['skipped'] == 0 and stats['p95'] <= 40 and stats['max'] <= 80, stats)
        return stats

    def tree():
        path = '/sdcard/thinkos-clock-ui.xml'
        dump_hierarchy(args.serial, path)
        xml = device.shell('cat', path)
        device.shell('rm', '-f', path)
        return ET.fromstring(xml)

    def find_text(text):
        for node in tree().iter('node'):
            if node.get('text') == text:
                left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
                return (left + right) // 2, (top + bottom) // 2
        return None

    device.prepare_display()
    device.open_page('clock.face')
    time.sleep(3)
    measure('nothing playing')

    device.load('/sdcard/Music/Hans Zimmer - Drive Fast [2718994112].flac', kind='audio', repeat='one')
    time.sleep(4)
    state = device.state() or {}
    check('the engine plays the 24-bit FLAC', state.get('status') == 'playing', state.get('status'))
    measure('music through the engine')
    device.debug('media_stop')
    time.sleep(2)

    device.open_page('player.library')
    time.sleep(3)
    # The library opens on the list used last (the player test leaves it on Streams); tapping the title steps through
    # Music, Videos, Recent, Streams, so keep tapping until the track is listed.
    headings = ['音乐', '视频', '最近播放', '网络流', 'Music', 'Videos', 'Recent', 'Streams']
    spot = None
    for _ in range(6):
        spot = find_text('Drive Fast')
        if spot: break
        heading = next((p for p in map(find_text, headings) if p and p[1] < 140), None)
        if heading: device.tap(*heading)
        time.sleep(1.5)
    check('the player list shows the track', spot is not None)
    if spot:
        device.tap(*spot)
        time.sleep(5)
        state = device.state() or {}
        check('tapping it plays through the plugin', state.get('status') == 'playing' and str(state.get('owner')).split(':')[0] == 'player', (state.get('status'), state.get('owner')))
        device.open_page('clock.face')
        time.sleep(3)
        measure('music through the player plugin')
    device.debug('media_stop')
    device.stop_log()
    failed = results.count(False)
    print('\n%d checks, %d failed' % (len(results), failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
