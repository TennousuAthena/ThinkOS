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
    assert all(re.search(r'volume is 0\b', device.shell('media','volume','--stream',i,'--get')) for i in range(1,6)), 'Keep all audio streams at zero'
    for path in ('/sdcard/Movies/matrix','/sdcard/Music/matrix'):
        assert not device.shell('ls','-d',path,check=False).strip(), 'Preserve an existing media directory: '+path
    device.shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity')
    device.start_log()
    device.adb('reverse', 'tcp:%d' % PORT, 'tcp:%d' % PORT)
    origin = Origin(args.samples)
    origin.start()
    results = []
    locale = device.shell('getprop', 'persist.sys.locale').strip() or 'en-US'
    table = 'zh-Hant' if re.search('TW|HK|Hant', locale) else 'zh-Hans' if locale.startswith('zh') else locale.split('-')[0]
    if not (ROOT / 'plugins/player/i18n' / (table + '.json')).exists(): table = 'en'
    labels = json.loads((ROOT / 'plugins/player/i18n' / (table + '.json')).read_text(encoding='utf-8'))
    original_settings = read_app_file(args.serial, SETTINGS)
    original_backup = read_app_file(args.serial, SETTINGS + '.bak')

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
        root = root if root is not None else tree()
        viewport = list(map(int, re.findall(r'\d+', root.find('node').get('bounds', ''))))
        for node in root.iter('node'):
            bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
            if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]: continue
            x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
            if len(viewport) == 4 and not (viewport[0] <= x < viewport[2] and viewport[1] <= y < viewport[3]): continue
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

    def screen():
        root = tree().find('node')
        left, top, right, bottom = map(int, re.findall(r'\d+', root.get('bounds')))
        return right - left, bottom - top

    def tab_nodes():
        """The four tabs along the top of the library page, by list name; each is a labelled view that reports whether it is selected."""
        names = {labels['variant.' + name]: name for name in ('music', 'video', 'recent', 'streams')}
        found = {}
        for node in tree().iter('node'):
            name = names.get(node.get('text'))
            if name and int(re.findall(r'\d+', node.get('bounds'))[1]) < 140: found[name] = node
        return found

    def show(name):
        for _ in range(5):
            node = tab_nodes().get(name)
            if node is None: time.sleep(0.8); continue
            if node.get('selected') == 'true': return True
            device.tap(*box(node))
            time.sleep(1.2)
        return False

    def tap_text(text):
        node = None
        for _ in range(10):
            node = find(text=text)
            if node is not None: break
            width, height = screen()
            device.shell('input', 'swipe', width // 2, int(height * .82), width // 2, int(height * .3), 350)
            time.sleep(.4)
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
        check('the library opens, and its Music tab is selected', show('music'), texts()[:6])
        rows = wait(lambda: find(text='audio-flac-16bit-44k'), 20)
        check('local music is listed (the scan found the pushed file)', rows is not None, texts()[:10])
        assert rows is not None, 'local music row is missing'
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
        device.shell('input', 'keyevent', 'KEYCODE_BACK')    # the way out of a live page is Back (ADR-040), not a sideways swipe
        time.sleep(1.5)
        check('Back off the audio page returns to the library and the music goes on', page() == 'player.library' and state().get('status') == 'playing',
              (page(), state().get('status')))
        device.debug('media_stop')

        # --- the video list, the video page, leaving ---------------------------------------------------------
        check('tapping the Videos tab switches to the Videos list', show('video'), texts()[:6])
        row = wait(lambda: find(text='video-h264-480p30'), 20)
        check('local video is listed', row is not None, texts()[:10])
        if row is not None:
            device.tap(*box(row))
            playing = wait(lambda: state().get('status') == 'playing' and state().get('kind') == 'video', 20)
            check('tapping a video plays it on the video page', bool(playing) and page() == 'player.video', (page(), state().get('status')))
            time.sleep(1.5)
            device.shell('input', 'keyevent', 'KEYCODE_BACK')
            time.sleep(2)
            check('leaving the video page by Back pauses the film and returns to the library', page() == 'player.library' and state().get('status') == 'paused',
                  (page(), state().get('status')))
            device.debug('media_stop')

        # --- history -----------------------------------------------------------------------------------------
        recent_shown = show('recent')
        check('Recent lists what was played', recent_shown and wait(lambda: find(text='video-h264-480p30')) is not None, texts()[:8])

        # --- settings: a stream address starts playback --------------------------------------------------------
        def open_settings():
            width, height = screen()
            device.shell('input', 'swipe', width // 2, height // 2, width // 2, height // 2, 900)
            return wait(lambda: either(lambda: find(text=labels['setting.stream_url']), lambda: find(text=labels['group.streams'])), 10)

        def edit(label, value):
            tap_text(label)
            field = wait(lambda: next((n for n in tree().iter('node') if n.get('class') == 'android.widget.EditText'), None), 8)
            if field is None:
                evidence = ROOT / 'artifacts/base/BASE-02'
                evidence.mkdir(parents=True, exist_ok=True)
                ET.ElementTree(tree()).write(evidence / 'player-editor-failure.xml', encoding='utf-8')
                device.screenshot(evidence / 'player-editor-failure.png')
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
        edit(labels['setting.stream_url'], '%s/files/video-h264-480p30.mp4' % BASE)
        playing = wait(lambda: state().get('status') in ('playing', 'buffering') and state().get('kind') == 'video', 20)
        check('saving a stream address starts playback', bool(playing), state().get('status'))
        device.debug('page', value='player.library')
        time.sleep(1.5)
        device.debug('media_stop')
        show('streams')
        check('the address is kept under Streams as Saved', wait(lambda: find(text=labels['group.saved'])) is not None, texts()[:10])

        # --- settings: a playlist fills Streams ---------------------------------------------------------------
        device.debug('page', value='player.library')
        time.sleep(1)
        check('long press opens the plugin settings again', open_settings() is not None)
        edit(labels['setting.playlist_url'], '%s/m3u/channels.m3u' % BASE)
        time.sleep(1)
        device.debug('page', value='player.library')
        time.sleep(1.5)
        show('streams')
        channels = [t for t in texts() if t and t not in (labels['variant.music'], labels['variant.video'], labels['variant.recent'], labels['variant.streams'], labels['group.saved'])]
        check('the playlist channels are listed', len(channels) >= 4, channels[:8])

        # --- the system settings carry the media switches ----------------------------------------------------
        system = json.loads((ROOT / 'shared/i18n/system' / (table + '.json')).read_text(encoding='utf-8'))
        device.debug('page', value='home.main')
        time.sleep(1.5)
        width, height = screen()
        device.shell('input', 'swipe', width - 5, height // 2, width - 5, height // 2, 900)
        opened = wait(lambda: either(lambda: find(text=system['setting_section_display']), lambda: find(text=system['setting_section_media'])), 10)
        # SET-03 moved the cleartext-network switch to System. Walk the real
        # category navigation instead of expecting the former flat form.
        def reveal(label):
            for _ in range(8):
                if find(text=label) is not None: return True
                device.shell('input', 'swipe', width // 2, int(height * .8), width // 2, int(height * .3), 350)
                time.sleep(.3)
            return False
        tap_text(system['setting_section_media'])
        check('Media settings retain keep-video-awake', opened is not None and reveal(system['setting_media_keep_awake_video']))
        device.shell('input','keyevent','KEYCODE_BACK');time.sleep(.4)
        tap_text(system['setting_section_system'])
        check('System settings retain the public HTTP switch', reveal(system['setting_media_public_http']))
        device.debug('page', value='home.main')
    finally:
        device.debug('media_stop')
        device.shell('rm', '-rf', '/sdcard/Movies/matrix', '/sdcard/Music/matrix', check=False)
        # Put the settings file back exactly, then restart so the app reads it again.
        device.shell('am', 'force-stop', 'moe.kiwi.thinkos')
        for path, content in ((SETTINGS, original_settings), (SETTINGS + '.bak', original_backup)):
            if content is None:
                device.shell('run-as', 'moe.kiwi.thinkos', 'rm', '-f', path)
            else:
                subprocess.run(device.base + ['shell', '-T', 'run-as moe.kiwi.thinkos sh -c "cat > %s"' % path],
                               input=content, capture_output=True, check=True, timeout=25)
        device.shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity')
        assert all(re.search(r'volume is 0\b', device.shell('media','volume','--stream',i,'--get')) for i in range(1,6)), 'Audio changed during the player test'
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
