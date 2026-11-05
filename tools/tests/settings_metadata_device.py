#!/usr/bin/env python3
"""UI-03/UI-14: conditional warning, editor hint and confirmed scope reset.

Uses normal native controls. Local settings and their backup are restored byte
for byte in finally; Android language, Device Owner and system files are untouched.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'tools'), str(ROOT / 'tools/tests')]
from device_app_files import read_app_file, restore_app_file
from device_hierarchy import dump_hierarchy
from media_device import APP, Device
from ui_walkthrough import broadcast_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/ui/UI-03')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    original = broadcast_json(device, 'ui_state')
    saved = {p: read_app_file(args.serial, p) for p in (
        'files/settings/local.json', 'files/settings/local.json.bak',
        'files/store/iptv.json', 'files/store/iptv.json.bak')}
    report = {'passed': [], 'complete': False}
    device.start_log()

    def tree():
        path = '/sdcard/thinkos-settings-metadata.xml'
        dump_hierarchy(args.serial, path)
        try:
            raw = device.shell('cat', path)
            (args.output / 'last-ui.xml').write_text(raw)
            return ET.fromstring(raw)
        finally:
            device.shell('rm', '-f', path)

    def bounds(node): return list(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))

    def find(text=None, resource=None, editor=False, scroll=False):
        for _ in range(12 if scroll else 1):
            root = tree()
            viewport = bounds(root.find('node'))
            for node in root.iter('node'):
                if text is not None and node.get('text') != text: continue
                if resource is not None and node.get('resource-id') != resource: continue
                if editor and node.get('class') != 'android.widget.EditText': continue
                b = bounds(node)
                if len(b) == 4 and b[2] > b[0] and b[3] > b[1] and viewport[0] <= (b[0]+b[2])/2 < viewport[2] and viewport[1] <= (b[1]+b[3])/2 < viewport[3]:
                    return node
            if scroll:
                w, h = viewport[2], viewport[3]
                device.shell('input', 'swipe', w//2, int(h*.84), w//2, int(h*.28), 350)
        raise AssertionError('Missing control: ' + str(text or resource or 'editor'))

    def tap(node):
        a,b,c,d = bounds(node)
        device.tap((a+c)//2, (b+d)//2)
        time.sleep(.5)

    def row(label):
        candidates = [n for n in tree().iter('node') if n.get('clickable') == 'true'
                      and any(c.get('text') == label for c in n.iter('node'))]
        return min(candidates, key=lambda n: (bounds(n)[2]-bounds(n)[0])*(bounds(n)[3]-bounds(n)[1]))

    def check(name, ok):
        if not ok: raise AssertionError(name)
        report['passed'].append(name)
        print('PASS ' + name, flush=True)

    def scope(name):
        device.debug('page', value='home.main')
        device.debug('settings', value=name)
        time.sleep(1)

    def stored():
        raw = read_app_file(args.serial, 'files/settings/local.json')
        return json.loads(raw or b'{}')

    try:
        device.debug('hold_attention', value='ENGAGED')
        device.debug('theme', value='DARK')
        device.debug('locale', value='zh-CN')
        device.debug('setting', key='cast.enabled', value='false')
        scope('system')
        tap(find(text='媒体与投屏'))
        tap_target = find(text='DLNA', scroll=True)
        check('disabled DLNA has no warning', not any(n.get('text') == '同一网络里的任何人都能投屏' for n in row('DLNA').iter('node')))
        tap(tap_target)
        check('enabling DLNA reveals the warning', any(n.get('text') == '同一网络里的任何人都能投屏' for n in row('DLNA').iter('node')))
        device.screenshot(args.output / 'dlna-enabled.png')
        tap(find(text='DLNA'))
        check('disabling DLNA removes the warning', not any(n.get('text') == '同一网络里的任何人都能投屏' for n in row('DLNA').iter('node')))
        device.screenshot(args.output / 'dlna-disabled.png')

        device.debug('setting', scope='iptv', key='epg_url', value='')
        scope('iptv')
        tap(find(text='高级', scroll=True))
        labels = json.loads((ROOT / 'plugins/iptv/i18n/zh-Hans.json').read_text())
        tap(find(text=labels['setting.epg_url'], scroll=True))
        editor = find(editor=True)
        check('empty programme guide editor has its format hint', editor.get('text') == labels['placeholder.epg_url'])
        device.screenshot(args.output / 'epg-placeholder.png')
        device.shell('input', 'keyevent', 'KEYCODE_BACK')
        device.shell('input', 'keyevent', 'KEYCODE_BACK')

        patch = {'mode':'manual', 'name':'UI Reset Place', 'latitude':'22.3', 'longitude':'114.2', 'elevation':'12', 'timezone':'Asia/Hong_Kong'}
        for key, value in patch.items(): device.debug('setting', key='location.'+key, value=value)
        scope('system')
        tap(find(text='位置与单位'))
        tap(find(text='恢复默认', scroll=True))
        find(text='恢复这一页的默认设置？')
        tap(find(resource='android:id/button2'))
        check('canceling reset preserves all six location edits', all('location.'+key in stored().get('system', {}) for key in patch))
        tap(find(text='恢复默认', scroll=True))
        tap(find(resource='android:id/button1'))
        time.sleep(.8)
        check('confirmed page reset clears all six location keys', all('location.'+key not in stored().get('system', {}) for key in patch))
        check('reset row disappears when the scope has no edits', not any(n.get('text') == '恢复默认' for n in tree().iter('node')))
        device.screenshot(args.output / 'reset-complete.png')
        report['complete'] = True
    except Exception as error:
        report['failure'] = str(error)
        device.screenshot(args.output / 'failed-screen.png')
        raise
    finally:
        device.shell('am', 'force-stop', APP)
        for path, content in saved.items(): restore_app_file(args.serial, path, content)
        report['restored_files'] = {path: read_app_file(args.serial, path) == content for path, content in saved.items()}
        device.shell('am', 'start', '-n', APP+'/.MainActivity')
        time.sleep(3)
        device.debug('locale', value=original['locale'])
        for command, key in (('theme','theme'), ('hold_attention','attention')):
            device.debug(command, **({'value':original[key]} if original[key] is not None else {}))
        device.debug('page', value=original['page'] or 'home.main')
        (args.output / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
        (args.output / 'logcat.txt').write_text('\n'.join(device.log_since(0))+'\n')
        device.stop_log()
        assert all(report['restored_files'].values())
    return 0


if __name__ == '__main__':
    sys.exit(main())
