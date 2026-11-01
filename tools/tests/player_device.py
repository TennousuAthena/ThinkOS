#!/usr/bin/env python3
"""The player plugin from a person's point of view, on the device: library, playback, leaving, settings, playlists.

    tools/media/gen_samples.sh && python3 tools/tests/player_device.py

Only touch input and the UIAutomator tree are used. The test pushes a few samples, starts tools/media/serve.py
(reached over `adb reverse`), and puts the settings file back byte for byte when it is done.
"""
import argparse
import json
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
from device_app_files import read_app_file
from device_hierarchy import dump_hierarchy
from media_device import Device
from media_stream_device import BASE, Origin, PORT

SETTINGS = 'files/settings/local.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--samples', type=Path, default=Path('/tmp/thinkos-media-samples'))
    args = parser.parse_args()
    device = Device(args.serial)
    device.start_log()
    device.adb('reverse', 'tcp:%d' % PORT, 'tcp:%d' % PORT)
    origin = Origin(args.samples)
    origin.start()
    results = []
    zh = json.loads((ROOT / 'plugins/player/i18n/zh-Hans.json').read_text(encoding='utf-8'))
    original_settings = read_app_file(args.serial, SETTINGS)

    def check(name, ok, detail=''):
        results.append(ok)
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)
        if not ok and results.count(False) == 1:
            # The first failure explains the rest: say where the app was and what its plugins were doing.
            mark = device.log_mark()
            device.debug('state')
            time.sleep(1)
            print('  page/attention/theme: %s' % (device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'benchmark_state').split('data="')[-1].split('"')[0]))
            for line in device.log_since(mark)[-3:]: print('  ' + (line.split('plugins=')[-1][:1500] if 'plugins=' in line else line[-300:]))
            for line in device.log_since(0)[-400:]:
                if 'DEBUG page' in line or ' page=' in line or 'attention=' in line: print('  ' + line[-300:])

    def tree():
        path = '/sdcard/thinkos-player-ui.xml'
        dump_hierarchy(args.serial, path)
        xml = device.shell('cat', path)
        device.shell('rm', '-f', path)
        return ET.fromstring(xml)

    def box(node):
        left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
        return (left + right) // 2, (top + bottom) // 2

    def find(text=None, desc=None, root=None):
        for node in (root or tree()).iter('node'):
            if text is not None and node.get('text') == text: return node
            if desc is not None and node.get('content-desc') == desc: return node
        return None

    def texts(): return [n.get('text') for n in tree().iter('node') if n.get('text')]

    def present(value):
        # An XML element with no children is falsy; "found" means "not None", whatever else it is.
        return value is not None and value is not False and not (isinstance(value, (int, str, list, dict)) and not value)

    def wait(test, seconds=15, message=''):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            value = test()
            if present(value): return value
            time.sleep(0.7)
        return None

    def either(first, second):
        a = first()
        return a if a is not None else second()

    def page():
        out = device.shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'benchmark_state')
        match = re.search(r'data="([^"]*)"', out)
        return match.group(1).split('|')[0] if match else ''

    def title_node():
        """The title band of the library page: tapping it switches the list."""
        names = {zh['variant.' + name]: name for name in ('music', 'video', 'recent', 'streams')}
        for node in tree().iter('node'):
            if node.get('text') in names and int(re.findall(r'\d+', node.get('bounds'))[1]) < 140:
                return node, names[node.get('text')]
        return None, None

    def show(name):
        for _ in range(5):
            node, current = title_node()
            if current == name: return True
            if node is None: time.sleep(0.8); continue
            device.tap(*box(node))
            time.sleep(1.2)
        return False

    def tap_text(text):
        node = wait(lambda: find(text=text), 8)
        assert node is not None, 'missing on screen: ' + text
        device.tap(*box(node))

    def state(): return device.state() or {}

    def settle(): time.sleep(1.2)

    sample_video = args.samples / 'video-h264-480p30.mp4'
    sample_audio = args.samples / 'audio-flac-16bit-44k.flac'
    device.shell('mkdir', '-p', '/sdcard/Movies/matrix', '/sdcard/Music/matrix')
    device.push(sample_video, '/sdcard/Movies/matrix/video-h264-480p30.mp4')
    device.push(sample_audio, '/sdcard/Music/matrix/audio-flac-16bit-44k.flac')
    try:
        device.prepare_display()    # the display stays AWAKE: a touch on a sleeping display only wakes it and goes home
        time.sleep(16)    # a page visit rescans the folders when the last look is older than 15 s
        device.debug('page', value='player.library')
        time.sleep(2)

        # --- the music list and the audio page -------------------------------------------------------------
        # The page opens on the list used last, so the first thing is to ask for Music.
        check('the library opens, and its title switches to Music', show('music'), texts()[:6])
        rows = wait(lambda: find(text='audio-flac-16bit-44k'), 20)
        check('local music is listed (the scan found the pushed file)', rows is not None, texts()[:10])
        device.tap(*box(rows))
        playing = wait(lambda: state().get('status') == 'playing' and state().get('position', 0) > 1500, 20)
        check('tapping a row plays it and opens the audio page', bool(playing) and page() == 'player.audio', (page(), state().get('status')))
        pause = find(desc=read_label('media_pause'))
        if pause is not None:
            device.tap(*box(pause))
            settle()
            check('the pause button on the audio page pauses', state().get('status') == 'paused', state().get('status'))
            device.tap(*box(find(desc=read_label('media_play'))))
            settle()
            check('and play resumes', state().get('status') == 'playing', state().get('status'))
        device.shell('input', 'swipe', 1000, 250, 200, 250, 300)
        time.sleep(1.5)
        check('swiping off the audio page returns to the library and the music goes on', page() == 'player.library' and state().get('status') == 'playing',
              (page(), state().get('status')))
        device.debug('media_stop')

        # --- the video list, the video page, leaving ---------------------------------------------------------
        check('tapping the title switches to the Videos list', show('video'), texts()[:6])
        row = wait(lambda: find(text='video-h264-480p30'), 20)
        check('local video is listed', row is not None, texts()[:10])
        if row is not None:
            device.tap(*box(row))
            playing = wait(lambda: state().get('status') == 'playing' and state().get('kind') == 'video', 20)
            check('tapping a video plays it on the video page', bool(playing) and page() == 'player.video', (page(), state().get('status')))
            time.sleep(1.5)
            device.shell('input', 'swipe', 1000, 250, 200, 250, 300)
            time.sleep(2)
            check('leaving the video page pauses the film and returns to the library', page() == 'player.library' and state().get('status') == 'paused',
                  (page(), state().get('status')))
            device.debug('media_stop')

        # --- history -----------------------------------------------------------------------------------------
        show('recent')
        check('Recent lists what was played', wait(lambda: find(text=zh['variant.recent'])) is not None and find(text='video-h264-480p30') is not None, texts()[:8])

        # --- settings: a stream address starts playback --------------------------------------------------------
        def open_settings():
            device.shell('input', 'swipe', 900, 700, 900, 700, 900)
            return wait(lambda: either(lambda: find(text=zh['setting.stream_url']), lambda: find(text=zh['group.streams'])), 10)

        def edit(label, value):
            tap_text(label)
            field = wait(lambda: next((n for n in tree().iter('node') if n.get('class') == 'android.widget.EditText'), None), 8)
            assert field is not None, 'no input field for ' + label
            device.tap(*box(field))
            existing = field.get('text') or ''
            device.shell('input', 'keyevent', 123)
            if existing: device.shell('input', 'keyevent', *([67] * len(existing)))
            device.shell('input', 'text', value)
            device.tap(*box(ok_button()))
            time.sleep(0.6)

        def ok_button():
            return wait(lambda: next((n for n in tree().iter('node') if n.get('resource-id') == 'android:id/button1'), None), 8)

        check('long press opens the plugin settings', open_settings() is not None, texts()[:10])
        edit(zh['setting.stream_url'], '%s/files/video-h264-480p30.mp4' % BASE)
        playing = wait(lambda: state().get('status') in ('playing', 'buffering') and state().get('kind') == 'video', 20)
        check('saving a stream address starts playback', bool(playing), state().get('status'))
        device.debug('page', value='player.library')
        time.sleep(1.5)
        device.debug('media_stop')
        show('streams')
        check('the address is kept under Streams as Saved', wait(lambda: find(text=zh['group.saved'])) is not None, texts()[:10])

        # --- settings: a playlist fills Streams ---------------------------------------------------------------
        device.debug('page', value='player.library')
        time.sleep(1)
        check('long press opens the plugin settings again', open_settings() is not None)
        edit(zh['setting.playlist_url'], '%s/m3u/channels.m3u' % BASE)
        time.sleep(1)
        device.debug('page', value='player.library')
        time.sleep(1.5)
        show('streams')
        channels = [t for t in texts() if t and t not in (zh['variant.streams'], zh['group.saved'])]
        check('the playlist channels are listed', len(channels) >= 4, channels[:8])

        # --- the system settings carry the media switches ----------------------------------------------------
        system = json.loads((ROOT / 'shared/i18n/system/zh-Hans.json').read_text(encoding='utf-8'))
        device.debug('page', value='home.main')
        time.sleep(1.5)
        device.shell('input', 'swipe', 1275, 400, 1275, 400, 900)
        opened = wait(lambda: either(lambda: find(text=system['setting_group_theme']), lambda: find(text=system['setting_group_display'])), 10)
        wanted = [system['setting_group_media'], system['setting_media_public_http'], system['setting_media_keep_awake_video']]
        seen = set()
        for _ in range(6):
            seen.update(t for t in texts() if t in wanted)
            if len(seen) == len(wanted): break
            device.shell('input', 'swipe', 640, 650, 640, 250, 350)
            time.sleep(0.8)
        check('the system settings page has a Media group with both switches', opened is not None and len(seen) == len(wanted), sorted(seen))
        device.debug('page', value='home.main')
    finally:
        device.debug('media_stop')
        device.shell('rm', '-rf', '/sdcard/Movies/matrix', '/sdcard/Music/matrix', check=False)
        # Put the settings file back exactly, then restart so the app reads it again.
        device.shell('am', 'force-stop', 'moe.kiwi.thinkos')
        if original_settings is not None:
            subprocess.run(device.base + ['shell', '-T', 'run-as moe.kiwi.thinkos sh -c "cat > %s"' % SETTINGS], input=original_settings, capture_output=True, timeout=25)
        device.shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity')
        origin.stop()
        device.stop_log()
    failed = results.count(False)
    print('\n%d checks, %d failed' % (len(results), failed))
    return 1 if failed else 0


def read_label(name):
    """A control label in the device's language, from the same resources the app uses."""
    serial = os.environ.get('ANDROID_SERIAL', 'HUA0BWUT')
    tag = subprocess.run(['adb', '-s', serial, 'shell', 'getprop', 'persist.sys.locale'], capture_output=True, text=True).stdout.strip() or 'en-US'
    language, _, region = tag.partition('-')
    res = ROOT / 'android/app/src/main/res'
    table = {}
    for folder in reversed(['values-%s-r%s' % (language, region), 'values-%s' % language, 'values']):
        path = res / folder / 'strings.xml'
        if path.exists():
            table.update({node.get('name'): (node.text or '') for node in ET.parse(path).getroot().iter('string')})
    return table[name]


if __name__ == '__main__':
    sys.exit(main())
