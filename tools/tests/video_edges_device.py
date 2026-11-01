#!/usr/bin/env python3
"""A full-screen video must reach every edge of the screen, whatever the burn-in drift is doing.

    python3 tools/tests/video_edges_device.py

The OS nudges the whole picture by up to 8 x 6 px so static content cannot burn in. A video stage that moved with it
showed a strip of the page background along two edges (white in the light theme). This test plays a plain dark grey
clip (no test pattern: nothing flashes on the screen) on each video page, pins the drift to its extremes in both
directions, takes a screenshot, and requires every pixel of the screen to be that grey or the black letterbox: the page
background colour, white, or anything else anywhere is a defect. Run it with the debug build; it releases every debug
switch it sets.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
from media_device import Device
from media_stream_device import BASE, Origin, PORT

GREY = 48                                                         # the clip's colour; the letterbox is black
CLIP = 'video-solid-grey-720p.mp4'


def make_clip(directory):
    path = directory / CLIP
    if not path.exists():
        directory.mkdir(parents=True, exist_ok=True)
        subprocess.run(['ffmpeg', '-nostdin', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=0x%02x%02x%02x:s=1280x720:r=30:d=12' % (GREY, GREY, GREY),
                        '-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=stereo', '-t', '12', '-c:v', 'libx264', '-preset', 'veryfast', '-pix_fmt', 'yuv420p',
                        '-g', '30', '-c:a', 'aac', '-b:a', '32k', '-shortest', '-movflags', '+faststart', str(path)], check=True)
    return path


def screenshot(serial):
    """Raw `screencap`: a header (width, height, format[, colour space]) and RGBA pixels."""
    raw = subprocess.run(['adb', '-s', serial, 'exec-out', 'screencap'], capture_output=True, timeout=60).stdout
    w, h = int.from_bytes(raw[0:4], 'little'), int.from_bytes(raw[4:8], 'little')
    head = len(raw) - w * h * 4
    assert head in (12, 16) and w > 0 and h > 0, 'unexpected screencap format'
    return w, h, raw, head


def stray_pixels(w, h, raw, head):
    """Pixels that are neither the clip's grey nor black, with the side of the screen they are nearest to."""
    stray = []
    for y in range(h):
        row = head + y * w * 4
        for x in range(w):
            r, g, b = raw[row + x * 4], raw[row + x * 4 + 1], raw[row + x * 4 + 2]
            if not (max(r, g, b) <= 3 or all(abs(c - GREY) <= 4 for c in (r, g, b))): stray.append((x, y))
    return stray


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--samples', type=Path, default=Path('/tmp/thinkos-media-samples'))
    args = parser.parse_args()
    device = Device(args.serial)
    device.start_log()
    device.adb('reverse', 'tcp:%d' % PORT, 'tcp:%d' % PORT)
    make_clip(args.samples)
    origin = Origin(args.samples)
    origin.start()
    results = []

    def check(name, ok, detail=''):
        results.append(ok)
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def orientation(value):
        device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', str(value))

    try:
        device.debug('hold_attention', value='AWAKE')            # no controls overlay: only the picture and the bars
        orientation(0)
        for page in ('player.video', 'cast.video'):
            device.open_page(page)
            device.load('%s/files/%s' % (BASE, CLIP), kind='video', repeat='one')
            time.sleep(4)
            for theme in ('LIGHT', 'DARK'):
                device.debug('theme', value=theme)
                device.debug('lux', value=800.0 if theme == 'LIGHT' else 5.0)
                for x, y in ((8.0, 6.0), (-8.0, -6.0), (8.0, -6.0), (-8.0, 6.0)):
                    device.debug('drift', x=x, y=y)
                    time.sleep(2.6)                                # the drift is applied once a second
                    w, h, raw, head = screenshot(args.serial)
                    bad = stray_pixels(w, h, raw, head)
                    xs, ys = {p[0] for p in bad}, {p[1] for p in bad}
                    where = 'x %d..%d, y %d..%d' % (min(xs), max(xs), min(ys), max(ys)) if bad else ''
                    check('%s, %s theme, drift %+d/%+d: only the clip and the black bars on screen' % (page, theme.lower(), x, y), not bad,
                          '%d stray pixels (%s)' % (len(bad), where) if bad else '')
            device.debug('media_stop')
    finally:
        device.debug('drift')
        for command in ('media_stop', 'hold_attention', 'lux', 'theme'):
            device.debug(command)
        orientation(0)          # this device is configured upright in landscape; 10 (follow the sensor) would leave it in portrait until a restart
        origin.stop()
        device.stop_log()
    failed = results.count(False)
    print('\n%d checks, %d failed' % (len(results), failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
