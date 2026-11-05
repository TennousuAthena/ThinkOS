#!/usr/bin/env python3
"""The music player's screens on the device: the audio page (cover and queue), the library tabs and footer, the home card.

    python3 tools/tests/player_ui_device.py [--output artifacts/player-ui]

Plays the real files in /sdcard/Music (it needs at least two), takes a screenshot of every screen in both orientations and both
themes, and checks what the UIAutomator tree and the engine can tell: the buttons, the tabs, a tap on the cover, a jump in the queue,
the volume bar. Nothing stays changed: theme, attention and orientation holds are released and the media volume is put back.
It changes the media volume while it runs, and plays music.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import shutil
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
sys.path.insert(0, str(ROOT / 'tools'))
from device_hierarchy import dump_hierarchy, visible_nodes
from device_app_files import read_app_file, restore_app_file
from media_device import APP, Device


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/player-ui')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    original_ui = json.loads(re.search(r'data="(\{.*\})"',device.debug('ui_state')).group(1))
    device.shell('am','force-stop',APP)
    saved_files = {p:read_app_file(args.serial,p) for p in ('files/settings/local.json','files/settings/local.json.bak',
        'files/store/player.json','files/store/player.json.bak','shared_prefs/deck.xml','shared_prefs/deck.xml.bak')}
    device.shell('am','start','-W','-n',APP+'/.MainActivity')
    device.start_log()
    results = []
    complete = False
    failure = None

    locale = original_ui['locale']
    table = 'zh-Hant' if re.search('TW|HK|Hant', locale) else 'zh-Hans' if locale.startswith('zh') else locale.split('-')[0]
    if not (ROOT / 'shared/i18n/system' / (table + '.json')).exists(): table = 'en'
    system = json.loads((ROOT / 'shared/i18n/system' / (table + '.json')).read_text(encoding='utf-8'))
    plugin = json.loads((ROOT / 'plugins/player/i18n' / (table + '.json')).read_text(encoding='utf-8'))

    def check(name, ok, detail=''):
        results.append(bool(ok))
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def tree():
        path = '/sdcard/thinkos-player-ui-check.xml'
        dump_hierarchy(args.serial, path)
        xml = device.shell('cat', path)
        device.shell('rm', '-f', path)
        return ET.fromstring(xml)

    def bounds(node): return list(map(int, re.findall(r'-?\d+', node.get('bounds'))))

    def center(node):
        left, top, right, bottom = bounds(node)
        return (left + right) // 2, (top + bottom) // 2

    def find(text=None, desc=None, root=None):
        for node in visible_nodes(root if root is not None else tree()):
            if text is not None and node.get('text') == text: return node
            if desc is not None and node.get('content-desc') == desc: return node
        return None

    def wait(test, seconds=15):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            value = test()
            if value is not None and value is not False: return value
            time.sleep(0.7)
        return None

    def shot(name): device.screenshot(args.output / (name + '.png'))

    def screen():
        left, top, right, bottom = bounds(tree().find('node'))
        return right - left, bottom - top

    def beat():
        out = device.debug('benchmark_state')
        match = re.search(r'data="([^"]*)"', out)
        return match.group(1).split('|') if match else []

    def orient(value):
        device.shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', value)
        time.sleep(1.5)

    def swipe(x1, y1, x2, y2): device.shell('input', 'swipe', x1, y1, x2, y2, 350); time.sleep(1.2)

    def volume():
        out = device.shell('media', 'volume', '--stream', '3', '--get')
        match = re.search(r'volume is (\d+) in range \[(\d+)\.\.(\d+)\]', out)
        return (int(match.group(1)), int(match.group(3))) if match else (None, None)

    def tab(name):
        node = find(text=plugin['variant.' + name])
        if node is None: return False
        if node.get('selected') != 'true':
            device.tap(*center(node)); time.sleep(1.2)
            node = find(text=plugin['variant.' + name])
        return node is not None and node.get('selected') == 'true'

    original_volume, volume_max = volume()
    recovery = Path(tempfile.mkdtemp(prefix='thinkos-player-ui-recovery-'))
    journal = {'serial':args.serial,'ui':original_ui,'volume':original_volume,
               'files':{p:base64.b64encode(v).decode() if v is not None else None for p,v in saved_files.items()}}
    with os.fdopen(os.open(recovery/'state.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as stream:
        json.dump(journal,stream)
    (args.output/'recovery-path.txt').write_text(str(recovery)+'\n')
    try:
        device.prepare_display()
        orient(0)

        # --- the library: tabs, rows, footer -----------------------------------------------------------------
        device.open_page('player.library')
        time.sleep(3)
        names = ['music', 'video', 'recent', 'streams']
        check('the library shows its four tabs', all(find(text=plugin['variant.' + n]) is not None for n in names))
        check('the Music tab can be selected', tab('music'))
        shot('library-landscape-dark-idle')
        width, height = screen()
        rows = [n for n in tree().iter('node') if n.get('text') and bounds(n)[1] > 170 and bounds(n)[3] < height - 90 and n.get('class') == 'android.widget.TextView']
        if len(rows) < 2:
            check('there is music to play in /sdcard/Music', False, 'push at least two tracks and run again')
            return
        rows.sort(key=lambda n: bounds(n)[1])
        device.tap(*center(rows[0]))

        # --- the audio page ----------------------------------------------------------------------------------
        check('a tapped row opens the audio page', wait(lambda: beat()[:1] == ['player.audio'] or None))
        time.sleep(2)
        state = device.state() or {}
        check('the engine plays and the queue holds the library', state.get('status') == 'playing' and state.get('count', 0) >= 2, (state.get('status'), state.get('count')))
        pause = find(desc=system['media.pause'])
        check('the audio page has a pause button', pause is not None)
        title = state.get('title', '')
        check('the title of the track is on the page', title != '' and find(text=title) is not None, title)
        check('the shuffle, repeat and volume controls are there', all(find(desc=system[k]) is not None for k in ('media.shuffle', 'media.repeat_off', 'media_volume')))
        shot('audio-cover-landscape-dark')

        # a tap on the cover pauses and resumes
        width, height = screen()
        device.tap(width // 4, height // 2); time.sleep(1.5)
        check('a tap on the cover pauses', (device.state() or {}).get('status') == 'paused')
        device.tap(width // 4, height // 2); time.sleep(1.5)
        check('another tap resumes', (device.state() or {}).get('status') == 'playing')

        # the volume bar moves the media volume
        bar = find(desc=system['media_volume'])
        if bar is not None and original_volume is not None:
            left, top, right, bottom = bounds(bar)
            y = (top + bottom) // 2
            device.tap(left + (right - left) * 15 // 100, y); time.sleep(1)
            low = volume()[0]
            device.tap(left + (right - left) * 90 // 100, y); time.sleep(1)
            high = volume()[0]
            check('the volume bar sets the media volume', low is not None and high is not None and high > low, (low, high))
        else:
            check('the volume bar can be found', False)

        # the queue variant
        swipe(width // 4, height * 3 // 4, width // 4, height // 4)
        listing = wait(lambda: next((n for n in tree().iter('node') if n.get('class') == 'android.widget.ListView'), None), 6)
        check('swiping up on the cover shows the queue', listing is not None)
        shot('audio-queue-landscape-dark')
        if listing is not None:
            left, top, right, bottom = bounds(listing)
            row = 104        # 64 dp at the device's density
            device.tap((left + right) // 2, top + row + row // 2); time.sleep(2)
            check('a tap on a queue row jumps to it', (device.state() or {}).get('index') == 2, (device.state() or {}).get('index'))
            shot('audio-queue-landscape-dark-second')
        swipe(width // 4, height // 4, width // 4, height * 3 // 4)

        # --- both orientations and both themes -----------------------------------------------------------------
        for orientation, label in ((1, 'portrait'), (0, 'landscape')):
            orient(orientation)
            width, height = screen()
            for theme in ('LIGHT', 'DARK'):
                device.debug('theme', value=theme); time.sleep(1.2)
                shot('audio-cover-%s-%s' % (label, theme.lower()))
            swipe(width // 4, height * 3 // 4, width // 4, height // 4)
            for theme in ('LIGHT', 'DARK'):
                device.debug('theme', value=theme); time.sleep(1.2)
                shot('audio-queue-%s-%s' % (label, theme.lower()))
            swipe(width // 4, height // 4, width // 4, height * 3 // 4)
        check('the pages kept playing through rotation and theme changes', (device.state() or {}).get('status') == 'playing')

        # --- the library while something plays; the home card ------------------------------------------------------
        title = (device.state() or {}).get('title', '')
        device.open_page('player.library'); time.sleep(2)
        count = sum(1 for n in tree().iter('node') if n.get('text') == title)
        check('the playing track is in the list and in the footer bar', count >= 2, (title, count))
        shot('library-landscape-dark-playing')
        device.open_page('home.main'); time.sleep(2)
        # The player's card is the fourth of the right stack (the OS does not turn the stack to it by itself yet): drag the
        # stack up three times, from its cover area so the buttons below are not what the finger lands on.
        width, height = screen()
        for _ in range(3): swipe(width * 3 // 4, height * 40 // 100, width * 3 // 4, height * 8 // 100)
        check('the home card names the track', find(text=title) is not None, title)
        shot('home-card-playing')
        device.debug('media_stop'); time.sleep(3)
        shot('home-card-idle')
        check('the home card falls back to its idle text', find(text=plugin['now.idle']) is not None)
        device.open_page('player.library'); time.sleep(2)
        shot('library-idle')
        check('the idle library footer has no transport controls',find(desc=system['media.play']) is None and find(desc=system['media.pause']) is None)
        complete = all(results)
    except Exception as error:
        failure = str(error)
        raise
    finally:
        restored={}
        cleanup_failure=None
        try:
            device.adb('get-state',timeout=3)
            device.debug('media_stop')
            if original_volume is not None: device.shell('media', 'volume', '--stream', '3', '--set', original_volume)
            device.shell('am','force-stop',APP)
            for path,content in saved_files.items():restore_app_file(args.serial,path,content)
            restored={p:read_app_file(args.serial,p)==v for p,v in saved_files.items()}
            device.shell('am','start','-W','-n',APP+'/.MainActivity')
            for command,key in (('theme','theme'),('hold_attention','attention'),('lux','lux')):
                device.debug(command,**({'value':original_ui[key]} if original_ui[key] is not None else {}))
            orient(original_ui['orientation'])
        except Exception as error:
            cleanup_failure=str(error)
        finally:
            recovered=len(restored)==len(saved_files) and all(restored.values()) and cleanup_failure is None
            (args.output/'results.json').write_text(json.dumps({'complete':complete and recovered,'failure':failure,
                'cleanup_failure':cleanup_failure,'checks':len(results),'failed':results.count(False),'restored_files':restored,
                'recovery_backup':None if recovered else str(recovery)},indent=2)+'\n')
            (args.output/'logcat.txt').write_text('\n'.join(device.log_since(0))+'\n')
            device.stop_log()
            if recovered:shutil.rmtree(recovery)
        assert recovered,'Test cleanup is pending; private recovery journal: '+str(recovery)
    print('%d checks, %d failed' % (len(results), results.count(False)))
    sys.exit(0 if all(results) else 1)


if __name__ == '__main__':
    main()
