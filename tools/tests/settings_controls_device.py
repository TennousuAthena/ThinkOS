#!/usr/bin/env python3
"""SET-04: native compact rows, held brightness preview, CANCEL and release-only commits.

Only app data and temporary process overrides change. A private, exact-byte recovery journal
is saved before the test; a disconnect retains it for tools/device_recovery.py.
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
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tools/tests')]
from device_app_files import read_app_file
from device_hierarchy import dump_hierarchy, node_bounds, visible_nodes
from device_recovery import capture, restore
from device_touch import drag
from media_device import APP, Device
from ui_walkthrough import broadcast_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/set/SET-04/device')
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    resumed = [line for line in device.shell('dumpsys', 'activity', 'activities').splitlines() if 'mResumedActivity' in line]
    assert any(APP+'/' in line for line in resumed), 'ThinkOS must be foreground'
    journal = capture(device, broadcast_json(device, 'ui_state'))
    report = {'complete': False, 'passed': []}
    print('Private recovery journal: '+str(journal), flush=True)
    fields = json.loads((ROOT/'shared/schema/settings/system.json').read_text())['fields']
    labels = json.loads((ROOT/'shared/i18n/system/zh-Hans.json').read_text())

    def tree():
        remote = '/sdcard/thinkos-settings-controls.xml'
        try:
            dump_hierarchy(args.serial, remote, compressed=True)
            raw = device.shell('cat', remote)
            (args.output/'last-ui.xml').write_text(raw)
            return ET.fromstring(raw)
        finally: device.shell('rm', '-f', remote)

    def find(label, scroll=False):
        for attempt in range(10):
            root = tree()
            node = next((n for n in visible_nodes(root) if n.get('text') == label), None)
            if node is not None: return node
            if scroll:
                a,b,c,d = node_bounds(root.find('node'))
                device.shell('input', 'swipe', (a+c)//2, int(d*.85), (a+c)//2, int(d*.3), 300)
            elif attempt < 9: time.sleep(.25)
        raise AssertionError('Missing control: '+label)

    def tap(label, scroll=False):
        a,b,c,d = node_bounds(find(label, scroll)); device.tap((a+c)//2, (b+d)//2); time.sleep(.35)

    def check(name, ok):
        if not ok: raise AssertionError(name)
        report['passed'].append(name); print('PASS '+name, flush=True)

    def effective(key):
        return json.loads(read_app_file(args.serial, 'files/settings/local.json') or b'{}').get('system', {}).get(key, fields[key]['default'])

    def brightness():
        raw = device.shell('dumpsys', 'window', 'windows')
        block = re.search(r'(?ms)^  Window #\d+ Window\{[^\n]*moe\.kiwi\.thinkos/[^\n]*MainActivity[^\n]*\}:\n(.*?)(?=^  Window #|\Z)', raw)
        assert block, 'ThinkOS window missing'
        return float(re.search(r'\bsbrt=([0-9.]+)', block[1])[1])

    def track():
        root = tree()
        minus = node_bounds(next(n for n in visible_nodes(root) if n.get('text') == '−'))
        plus = node_bounds(next(n for n in visible_nodes(root) if n.get('text') == '+'))
        inset = (minus[2]-minus[0])/48*14
        return minus[2]+inset, plus[0]-inset, (minus[1]+minus[3])/2

    def held(key, target, cancel=False):
        left,right,y = track(); field = fields[key]; original = effective(key)
        def x(value): return left+(right-left)*(value-field['min'])/(field['max']-field['min'])
        snapshot, raw = drag(device, (x(original), y), (x(target), y), cancel=cancel,
                             observe=lambda: {'saved': effective(key), 'window': brightness()})
        (args.output/(key+('-cancel' if cancel else '-up')+'.log')).write_text(raw)
        time.sleep(.6)
        return snapshot

    try:
        device.shell('am', 'start', '-W', '-n', APP+'/.MainActivity')
        device.debug('locale', value='zh-CN'); device.debug('theme', value='DARK'); device.debug('hold_attention', value='ENGAGED')
        device.shell('am', 'broadcast', '-n', APP+'/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', 1)
        device.debug('setting', key='display.brightness_max', value='60')
        until = time.monotonic()+25
        while time.monotonic() < until:
            device.debug('page', value='home.main'); device.debug('settings', value='system')
            if broadcast_json(device, 'ui_state')['page'] == 'settings.form': break
            time.sleep(.35)
        else: raise AssertionError('The factory settings page did not become available after app restart')
        tap(labels['setting_section_display'])
        check('numbers start as compact label/value rows', not any(n.get('text') == '+' for n in tree().iter('node')))
        tap(labels['setting_display_brightness_max'])
        find('+')
        tap(labels['setting_theme_mode'])
        check('a choice collapses the expanded number', not any(n.get('text') == '+' for n in tree().iter('node')))
        device.shell('input', 'keyevent', 'KEYCODE_BACK')
        tap(labels['setting_display_brightness_max'])
        sample = held('display.brightness_max', 35, cancel=True)
        report['cancel_sample'] = sample
        check('held brightness follows the pointer without persisting', sample['saved'] == 60 and abs(sample['window']-.35) < .02)
        check('CANCEL restores saved brightness and leaves settings unchanged', effective('display.brightness_max') == 60 and abs(brightness()-.6) < .02)
        sample = held('display.brightness_max', 85)
        report['commit_sample'] = sample
        check('brightness still is not saved before release', sample['saved'] == 60 and abs(sample['window']-.85) < .02)
        check('release commits and keeps the new brightness', effective('display.brightness_max') == 85 and abs(brightness()-.85) < .02)
        device.shell('input', 'keyevent', 'KEYCODE_BACK'); time.sleep(.25)
        check('Back collapses the number before leaving its category', find(labels['setting_section_display']) is not None and not any(n.get('text') == '+' for n in tree().iter('node')))
        tap('高级', scroll=True)
        key = 'attention.engaged_idle_s'; tap(labels['setting_attention_engaged_idle_s'])
        old = effective(key); target = fields[key]['min'] if old != fields[key]['min'] else fields[key]['max']
        sample = held(key, target)
        report['ordinary_sample'] = sample
        check('ordinary numbers do not persist or change brightness while held', sample['saved'] == old and abs(sample['window']-.85) < .02)
        check('ordinary numbers persist once released', effective(key) == target)
        device.screenshot(args.output/'advanced-number.png')
        report['complete'] = True
    except Exception as error:
        report['failure'] = str(error)
        try: device.screenshot(args.output/'failure.png')
        except Exception: pass
        raise
    finally:
        try: report.update(restore(journal, device))
        except Exception as error:
            report['recovery_error'] = str(error); report['private_recovery_journal'] = str(journal)
        (args.output/'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    if report.get('recovery_error'): raise RuntimeError(report['recovery_error'])


if __name__ == '__main__': main()
