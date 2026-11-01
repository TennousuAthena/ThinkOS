#!/usr/bin/env python3
"""Video page gestures on the device: overlay, seek bar drag/tap, play/pause, long press, page swipe, attention hold.

    tools/media/gen_samples.sh && python3 tools/tests/media_ui_device.py [--attention]

The overlay controls expose content descriptions, so the test finds them through a UIAutomator dump
instead of hard-coded coordinates. Needs the debug build and the origin server from tools/media/serve.py
(started here, reached over `adb reverse`).
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
from media_stream_device import BASE, MOVIE_720, Origin, PORT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--samples', type=Path, default=Path('/tmp/thinkos-media-samples'))
    parser.add_argument('--attention', action='store_true', help='also run the 5 minute attention-hold check')
    args = parser.parse_args()
    device = Device(args.serial)
    device.start_log()
    device.adb('reverse', 'tcp:%d' % PORT, 'tcp:%d' % PORT)
    origin = Origin(args.samples)
    origin.start()
    results = []

    def check(name, ok, detail=''):
        results.append(ok)
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def nodes():
        path = '/sdcard/thinkos-media-ui.xml'
        dump_hierarchy(args.serial, path)
        xml = device.shell('cat', path)
        device.shell('rm', '-f', path)
        found = {}
        for node in ET.fromstring(xml).iter('node'):
            description = node.get('content-desc')
            bounds = re.findall(r'\d+', node.get('bounds', ''))
            if description and len(bounds) == 4: found[description] = tuple(map(int, bounds))
        return found

    def centre(box): return (box[0] + box[2]) // 2, (box[1] + box[3]) // 2

    def strings():
        """The control labels in the device's own language, read from the same resources the app uses."""
        tag = (device.shell('getprop', 'persist.sys.locale').strip() or device.shell('getprop', 'ro.product.locale').strip() or 'en-US')
        language, _, region = tag.partition('-')
        res = ROOT / 'android/app/src/main/res'
        candidates = ['values-%s-r%s' % (language, region), 'values-%s' % language, 'values']
        table = {}
        for name in reversed(candidates):
            path = res / name / 'strings.xml'
            if path.exists():
                table.update({node.get('name'): (node.text or '') for node in ET.parse(path).getroot().iter('string')})
        return table
    text = strings()
    SEEK, PAUSE, PLAY = text['media_seek'], text['media_pause'], text['media_play']
    BACK, FORWARD = text['media_rewind'], text['media_forward']
    AUDIO, SUBTITLES = text['media_audio'], text['media_subtitles']

    def page():
        out = device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'benchmark_state')
        match = re.search(r'data="([^"]*)"', out)
        return match.group(1).split('|') if match else []

    def state(): return device.state() or {}

    def settle(): time.sleep(1.2)

    def raise_controls():
        """A tap on the picture shows the controls or puts them away: tap only when they are not up."""
        if SEEK in nodes(): return
        device.tap(640, 300)
        settle()

    device.prepare_display()
    device.debug('hold_attention')                      # no forced level: the real attention policy runs
    device.open_page('player.video')
    time.sleep(1)
    device.load('%s/files/%s' % (BASE, MOVIE_720), kind='video', repeat='one')
    time.sleep(2.5)

    # --- overlay -----------------------------------------------------------------------------------------
    check('overlay is hidden while nobody touches the screen', SEEK not in nodes())
    device.tap(640, 300)
    settle()
    found = nodes()
    check('a touch raises the overlay (seek bar and transport buttons)', {SEEK, PAUSE, BACK, FORWARD} <= set(found), sorted(found))
    bar = found.get(SEEK)
    if bar:
        y = (bar[1] + bar[3]) // 2
        width = bar[2] - bar[0]
        device.shell('input', 'swipe', bar[0] + int(width * .15), y, bar[0] + int(width * .85), y, 450)
        time.sleep(0.4)           # the drag ends at 85% of a 12 s clip: do not wait long enough for repeat-one to wrap it round
        now = state()
        check('dragging the seek bar seeks and does not turn the page', page()[0] == 'player.video' and now.get('position', 0) >= 6500,
              {'page': page()[:2], 'position': now.get('position')})
        device.tap(bar[0] + int(width * .25), y)
        settle()
        position = state().get('position', 0)
        check('tapping the seek bar jumps to that point', 1500 <= position <= 5500, position)

    # --- transport ---------------------------------------------------------------------------------------
    found = nodes()
    if PAUSE in found:
        device.tap(*centre(found[PAUSE]))
        settle()
        check('Pause button pauses', state().get('status') == 'paused', state().get('status'))
        found = nodes()
        if PLAY in found:
            device.tap(*centre(found[PLAY]))
            settle()
            check('Play button resumes', state().get('status') == 'playing', state().get('status'))
        else: check('Play button appears while paused', False, sorted(found))
    else: check('Pause button is reachable', False, sorted(found))
    found = nodes()
    if FORWARD in found:
        before = state().get('position', 0)
        device.tap(*centre(found[FORWARD]))
        settle()
        after = state().get('position', 0)
        check('forward 10 s moves the playhead (repeat-one wraps a 12 s clip)', after != before, (before, after))

    # --- audio and subtitle tracks -----------------------------------------------------------------------
    device.load('%s/files/video-2audio-embedded-sub.mkv' % BASE, kind='video', repeat='one')
    time.sleep(3)
    raise_controls()
    first = state()
    check('two audio tracks and one subtitle track are listed', len(first.get('audioTracks', [])) == 2 and len(first.get('textTracks', [])) == 1,
          {'audio': [t['language'] for t in first.get('audioTracks', [])], 'text': len(first.get('textTracks', []))})
    found = nodes()
    check('the track buttons appear only when there is a choice', AUDIO in found and SUBTITLES in found, sorted(found))
    if AUDIO in found:
        device.tap(*centre(found[AUDIO]))
        settle()
        now = state()
        check('the audio button switches to the next track', [t['selected'] for t in now['audioTracks']] == [False, True], now['audioTracks'])
        device.tap(*centre(nodes()[AUDIO]))
        settle()
        check('and wraps around to the first', [t['selected'] for t in state()['audioTracks']] == [True, False])
    if SUBTITLES in nodes():
        device.tap(*centre(nodes()[SUBTITLES]))
        settle()
        check('the subtitle button turns the embedded track on', state()['textTracks'][0]['selected'] is True, state()['textTracks'])
        device.tap(*centre(nodes()[SUBTITLES]))
        settle()
        check('and off again', state()['textTracks'][0]['selected'] is False)

    # --- the overlay goes away on its own ---------------------------------------------------------------
    time.sleep(17)
    check('overlay fades out after the engaged period', SEEK not in nodes())

    # --- OS gestures stay with the OS --------------------------------------------------------------------
    device.shell('input', 'swipe', 640, 300, 640, 300, 900)   # a long press
    time.sleep(1.5)
    check('long press on the picture still opens settings', page()[0].startswith('settings'), page()[:2])
    device.open_page('player.video')
    time.sleep(1.2)
    device.load('%s/files/%s' % (BASE, MOVIE_720), kind='video', repeat='one')
    time.sleep(1.5)
    mark = device.log_mark()
    device.shell('input', 'swipe', 1000, 300, 200, 300, 300)
    time.sleep(1.5)
    check('a horizontal swipe on the picture leaves the video page', page()[0] != 'player.video',
          (page()[:2], [line[-80:] for line in device.log_since(mark) if 'page=' in line or 'DEBUG page' in line]))

    # --- attention ---------------------------------------------------------------------------------------
    if args.attention:
        device.open_page('player.video')
        device.load('%s/files/%s' % (BASE, MOVIE_720), kind='video', repeat='all')
        time.sleep(200)   # longer than the 120 s AWAKE -> AMBIENT setting
        check('video playing keeps the display awake', page()[1] in ('AWAKE', 'ENGAGED'), page()[:2])
        device.debug('media_pause')
        time.sleep(200)
        check('paused video lets the display ease down again', page()[1] in ('AMBIENT', 'ASLEEP'), page()[:2])

    device.debug('media_stop')
    origin.stop()
    device.stop_log()
    failed = results.count(False)
    print('\n%d checks, %d failed' % (len(results), failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
