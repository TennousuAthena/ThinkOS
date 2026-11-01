#!/usr/bin/env python3
"""The cinema view's touch gestures on the device: tap to put the controls away, double taps, vertical drags.

    python3 tools/tests/video_gestures_device.py

  - one tap on the picture shows the controls, the next one puts them away (while the finger's attention is still up)
  - a double tap in the middle half pauses and resumes; in the left / right quarter it goes back / ahead 10 s
  - a vertical drag on the left half changes the brightness, on the right half the volume; neither turns the page

The clip is plain dark grey (nothing flashes) and is pushed to the device, so no network is needed. Double taps are
injected by the debug build's `touch` command, because `input tap` takes 0.7 s a tap here. Brightness and volume are
put back as they were. Use the debug build, landscape.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
sys.path.insert(0, str(ROOT / 'tools'))
from device_hierarchy import dump_hierarchy
from media_device import Device

REMOTE = '/sdcard/Movies/gesture-test.mp4'
W, H = 1280, 800


def make_clip(directory):
    path = directory / 'video-solid-grey-120s.mp4'
    if not path.exists():
        directory.mkdir(parents=True, exist_ok=True)
        subprocess.run(['ffmpeg', '-nostdin', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=0x303030:s=1280x720:r=30:d=120',
                        '-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=stereo', '-t', '120', '-c:v', 'libx264', '-preset', 'veryfast', '-pix_fmt', 'yuv420p',
                        '-g', '30', '-c:a', 'aac', '-b:a', '32k', '-shortest', '-movflags', '+faststart', str(path)], check=True)
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--samples', type=Path, default=Path('/tmp/thinkos-media-samples'))
    args = parser.parse_args()
    device = Device(args.serial)
    device.start_log()
    results = []

    def check(name, ok, detail=''):
        results.append(ok)
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def controls_up():
        """The seek bar is in the accessibility tree only while the controls are up."""
        path = '/sdcard/thinkos-gesture-ui.xml'
        dump_hierarchy(args.serial, path)
        xml = device.shell('cat', path)
        device.shell('rm', '-f', path)
        return any(n.get('content-desc') == SEEK for n in ET.fromstring(xml).iter('node'))

    def string_table():
        table = {}
        res = ROOT / 'android/app/src/main/res'
        tag = device.shell('getprop', 'persist.sys.locale').strip() or 'en-US'
        language, _, region = tag.partition('-')
        for name in reversed(['values-%s-r%s' % (language, region), 'values-%s' % language, 'values']):
            path = res / name / 'strings.xml'
            if path.exists(): table.update({n.get('name'): (n.text or '') for n in ET.parse(path).getroot().iter('string')})
        return table
    SEEK = string_table()['media_seek']

    def page():
        out = device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'benchmark_state')
        match = re.search(r'data="([^"]*)"', out)
        return match.group(1).split('|') if match else []

    def state(): return device.state() or {}

    def tap(x, y, taps=1, gap=120, then=None):
        """[taps] taps [gap] ms apart at (x, y); with [then] = (x2, y2) every second tap lands there instead."""
        extra = {'x2': float(then[0]), 'y2': float(then[1])} if then else {}
        device.debug('touch', x=float(x), y=float(y), taps=taps, gap=gap, **extra)

    def swipe(x, y0, y1, ms=450):
        device.shell('input', 'swipe', x, y0, x, y1, ms)

    def levels(**extra):
        """Sets what is asked (brightness / volume as fractions), and returns what the device now has."""
        for attempt in (1, 2):
            mark = device.log_mark()
            device.debug('levels', **extra)
            end = time.monotonic() + 8
            while time.monotonic() < end:
                for line in device.log_since(mark):
                    m = re.search(r'DEBUG levels brightness=([\d.]+) volume=([\d.]+)', line)
                    if m: return float(m.group(1)), float(m.group(2))
                time.sleep(0.2)
        raise RuntimeError('the device did not report its brightness and volume')

    def position(): return state().get('position', 0)

    clip = make_clip(args.samples)
    original = None
    try:
        device.push(clip, REMOTE)
        original = levels()
        device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', '0')
        device.debug('theme', value='DARK'); device.debug('lux', value=5.0)
        device.debug('hold_attention')                           # the real attention policy: a touch raises it, 15 s of quiet lowers it
        device.open_page('player.video')
        device.load(REMOTE, kind='video', repeat='one')
        time.sleep(4)
        check('the clip plays on the video page', state().get('status') == 'playing' and page()[0] == 'player.video', (state().get('status'), page()[:2]))

        # --- tap to show and hide the controls -----------------------------------------------------------------
        time.sleep(17)
        check('the controls are away when nobody has touched the screen', not controls_up())
        tap(W // 2, H // 2)
        time.sleep(1.0)
        check('one tap on the picture brings the controls up', controls_up())
        tap(W // 2, H // 2)
        time.sleep(1.0)
        check('the next tap puts them away again, although the finger was just there', not controls_up() and page()[1] == 'ENGAGED', page()[:2])
        tap(W // 2, H // 2)
        time.sleep(1.0)
        check('and one more brings them back', controls_up())
        time.sleep(17)
        check('left alone they go away by themselves', not controls_up())

        # --- double taps ------------------------------------------------------------------------------------
        tap(W // 2, H // 2, taps=2)
        time.sleep(1.2)
        check('a double tap in the middle pauses', state().get('status') == 'paused', state().get('status'))
        check('and the controls stay up while paused', controls_up())
        tap(W // 2, H // 2, taps=2)
        time.sleep(1.2)
        check('a second double tap resumes', state().get('status') == 'playing', state().get('status'))

        device.debug('media_seek', value=40_000)
        time.sleep(1.5)
        before = position()
        tap(1100, 400, taps=2)
        time.sleep(1.2)
        gained = position() - before
        check('a double tap in the right quarter goes ahead 10 s', 9_000 <= gained <= 13_000, '%d ms' % gained)
        before = position()
        tap(150, 400, taps=2)
        time.sleep(1.2)
        lost = before - position()
        check('a double tap in the left quarter goes back 10 s', 7_000 <= lost <= 11_000, '%d ms' % lost)
        before = position()
        tap(1100, 400, taps=2); time.sleep(0.5); tap(1100, 400, taps=2)
        time.sleep(1.2)
        gained = position() - before
        check('two in a row go ahead 20 s', 19_000 <= gained <= 24_000, '%d ms' % gained)

        # --- where the zones begin and end -------------------------------------------------------------------
        before = position(); tap(310, 400, taps=2); time.sleep(1.2)
        check('x=310 (just inside the left quarter) goes back', position() < before - 5_000, position() - before)
        status = state().get('status'); tap(330, 400, taps=2); time.sleep(1.2)
        check('x=330 (just inside the middle) toggles playback instead', state().get('status') != status, (status, state().get('status')))
        tap(330, 400, taps=2); time.sleep(1.2)
        before = position(); tap(970, 400, taps=2); time.sleep(1.2)
        check('x=970 (just inside the right quarter) goes ahead', position() > before + 5_000, position() - before)
        status = state().get('status'); tap(950, 400, taps=2); time.sleep(1.2)
        check('x=950 (just inside the middle) toggles playback instead', state().get('status') != status, (status, state().get('status')))
        tap(950, 400, taps=2); time.sleep(1.2)

        # --- two single taps far apart are not a double tap --------------------------------------------------
        before, status = position(), state().get('status')
        tap(200, 400, taps=2, gap=120, then=(1100, 400))
        time.sleep(1.5)
        check('two taps far apart are two single taps: no skip and no pause', abs(position() - before - 1_700) < 2_500 and state().get('status') == status,
              (position() - before, state().get('status')))

        # --- vertical drags --------------------------------------------------------------------------------
        b0, v0 = levels(brightness=0.6, volume=0.4)
        swipe(1000, 600, 400)
        time.sleep(0.8)
        _, v1 = levels()
        check('an upward drag on the right half raises the volume', v1 - v0 > 0.2, '%.2f -> %.2f' % (v0, v1))
        swipe(1000, 400, 600)
        time.sleep(0.8)
        _, v2 = levels()
        check('a downward drag lowers it again', v1 - v2 > 0.2, '%.2f -> %.2f' % (v1, v2))
        swipe(200, 600, 400)
        time.sleep(0.8)
        b1, _ = levels()
        check('an upward drag on the left half raises the brightness', b1 - b0 > 0.2, '%.2f -> %.2f' % (b0, b1))
        swipe(200, 100, 780)
        time.sleep(0.8)
        b2, _ = levels()
        check('and a long drag down stops at the lowest brightness the settings allow', abs(b2 - 0.2) < 0.001, b2)
        swipe(200, 780, 100)
        time.sleep(0.8)
        b3, _ = levels()
        check('a long drag up stops at full', abs(b3 - 1.0) < 0.001, b3)
        check('none of the drags turned the page', page()[0] == 'player.video', page()[:2])
        check('and the film is still playing', state().get('status') == 'playing', state().get('status'))
    finally:
        device.debug('media_stop')
        if original: levels(brightness=original[0], volume=original[1])
        for command in ('lux', 'theme', 'hold_attention'):
            device.debug(command)
        device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', '0')
        device.shell('rm', '-f', REMOTE, check=False)
        device.stop_log()
    failed = results.count(False)
    print('\n%d checks, %d failed' % (len(results), failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
