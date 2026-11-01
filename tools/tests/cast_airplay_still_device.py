#!/usr/bin/env python3
"""A phone that mirrors a screen which does not change, on the device (docs/28-casting.md §15, docs/10-known-issues.md).

    tools/media/gen_samples.sh && python3 -I tools/tests/cast_airplay_still_device.py [--serial HUA0BWUT] [--still 40]

A phone sends no pictures while its screen is still, and no sound while nothing plays. What the device must do then:
  - go on showing the last picture, not reset the player (the stall watchdog used to, after 30 seconds) and not show a loading wheel;
  - show the next picture when it comes, not after the time the screen was still (the player's clock stops with the last picture
    while the next one is stamped with the real time that went by).
The scripted sender (tools/cast/airplay_mirror_sender.py) plays the phone, video only, and `still` stops its pictures for a while.
The settings file is put back byte for byte at the end.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
sys.path.insert(0, str(ROOT / 'tools/cast'))
sys.path.insert(0, str(ROOT / 'tools'))
from device_app_files import read_app_file
from media_device import Device
from airplay_mirror_sender import MirrorSender, TEST_KEY

SETTINGS = 'files/settings/local.json'
NAME = 'ThinkOS-Still-Test'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--still', type=int, default=40, help='seconds the screen is still (more than the 30 the engine waits for a stalled stream)')
    args = parser.parse_args()
    device = Device(args.serial)
    device.start_log()
    results = []
    original = read_app_file(args.serial, SETTINGS)

    def check(name, ok, detail=''):
        results.append(ok)
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def write_settings(**values):
        document = json.loads(original.decode()) if original else {}
        document.setdefault('system', {}).update(values)
        device.shell('am', 'force-stop', 'moe.kiwi.thinkos')
        subprocess.run(device.base + ['shell', '-T', 'run-as moe.kiwi.thinkos sh -c "cat > %s"' % SETTINGS], input=json.dumps(document).encode(), capture_output=True, timeout=25)
        device.shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity')
        time.sleep(10)
        device.debug('hold_attention', value='AWAKE')
        device.debug('airplay_testkey', value=TEST_KEY.hex())

    def state(): return device.state() or {}

    def ahead(): s = state(); return s.get('buffered', 0) - s.get('position', 0)

    def until(test, seconds=15, interval=0.4):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            value = test()
            if value: return value
            time.sleep(interval)
        return None

    sender = None
    try:
        device.prepare_display()
        ip = re.search(r'inet (\d+\.\d+\.\d+\.\d+)', device.shell('ip', '-4', 'addr', 'show', 'wlan0')).group(1)
        device.debug('media_stop')
        write_settings(**{'cast.airplay': True, 'cast.airplay_mirror': True, 'cast.name': NAME})
        sender = MirrorSender(ip)
        status, info = sender.http.info()
        check('the device answers /info', status == 200, status)
        sender.http.pair(); sender.fp_setup()
        sender.setup_keys(); sender.setup_streams(video=True, audio=False)
        sender.record(); sender.start_media(video=True, audio=False)
        playing = until(lambda: (lambda s: s.get('mirror') is True and s.get('status') == 'playing' and s.get('videoWidth', 0) > 0)(state()), 30)
        check('the mirror plays: live, 720p, video only', bool(playing), {k: state().get(k) for k in ('status', 'mirror', 'videoWidth')})
        settled = until(lambda: 0 <= ahead() < 1000, 20)
        check('and the player holds under a second beyond the playhead', bool(settled), ahead())

        # --- the screen goes still -------------------------------------------------------------------------------------
        mark = device.log_mark()
        sender.still.set()
        seen = set(); widths = set(); end = time.monotonic() + args.still
        while time.monotonic() < end:
            s = state(); seen.add(s.get('status')); widths.add(s.get('videoWidth'))
            time.sleep(1)
        log = '\n'.join(device.log_since(mark))
        check('a still screen of %d seconds does not make the engine reset the player' % args.still, 'reconnecting' not in seen and 'stalled at' not in log, sorted(map(str, seen)))
        check('the picture is not thrown away: the stream keeps its 720p format', widths == {1280}, sorted(map(str, widths)))
        check('and the session is still the sender\'s (the screen is still the mirror)', state().get('mirror') is True and state().get('owner') == 'cast:airplay', state().get('owner'))

        # --- and moves again -----------------------------------------------------------------------------------------------
        sender.still.clear()
        began = time.monotonic()
        shown = until(lambda: state().get('status') == 'playing' and 0 <= ahead() < 1500, 25, 0.25)
        took = time.monotonic() - began
        check('the next picture is due within two seconds, not after the time the screen was still', bool(shown) and took < 2.0, 'ahead %d ms after %.1f s (status %s)' % (ahead(), took, state().get('status')))
        time.sleep(3)
        check('and the delay does not build up afterwards (under a second beyond the playhead)', 0 <= ahead() < 1000, ahead())
        s = state()
        check('the stream kept its format all along', s.get('videoWidth') == 1280, s.get('videoWidth'))
    finally:
        device.debug('airplay_testkey')
        device.debug('media_stop')
        if sender: sender.close()
        device.shell('am', 'force-stop', 'moe.kiwi.thinkos')
        if original is not None:
            subprocess.run(device.base + ['shell', '-T', 'run-as moe.kiwi.thinkos sh -c "cat > %s"' % SETTINGS], input=original, capture_output=True, timeout=25)
        device.shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity')
        device.debug('hold_attention')
        device.stop_log()
    failed = results.count(False)
    print('\n%d checks, %d failed' % (len(results), failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
