#!/usr/bin/env python3
"""SET-06: eleven-language sidebars, RTL, independent scrolling and rotation/Back.

Uses native controls only. No performance test; private recovery preserves exact app files.
"""
import argparse
import json
import os
from pathlib import Path
import struct
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tools/tests')]
from device_hierarchy import dump_hierarchy, node_bounds, visible_nodes
from device_recovery import capture, restore
from media_device import APP, Device
from ui_walkthrough import broadcast_json

LOCALES = ('zh-Hans', 'zh-Hant', 'en', 'es', 'fr', 'pt', 'ru', 'ar', 'hi', 'bn', 'ur')
IDS = ('display', 'location', 'desktop', 'media', 'system', 'plugins')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/set/SET-06/device')
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    resumed = [line for line in device.shell('dumpsys', 'activity', 'activities').splitlines() if 'mResumedActivity' in line]
    assert any(APP+'/' in line for line in resumed), 'ThinkOS must be foreground'
    journal = capture(device, broadcast_json(device, 'ui_state'))
    device.start_log()
    print('Private recovery journal: '+str(journal), flush=True)
    report = {'complete': False, 'passed': [], 'screens': {}}
    labels = {}

    def tree():
        remote = '/sdcard/thinkos-settings-columns.xml'
        try:
            dump_hierarchy(args.serial, remote, compressed=True)
            raw = device.shell('cat', remote)
            (args.output/'last-ui.xml').write_text(raw)
            return ET.fromstring(raw)
        finally: device.shell('rm', '-f', remote)

    def check(name, ok):
        if not ok: raise AssertionError(name)
        report['passed'].append(name); print('PASS '+name, flush=True)

    def wait(test):
        until = time.monotonic()+12
        while time.monotonic() < until:
            result = test()
            if result is not None and result is not False: return result
            time.sleep(.2)
        raise AssertionError('Native form did not reach the expected state')

    def visible(label, root=None):
        return next((n for n in visible_nodes(root if root is not None else tree()) if n.get('text') == label), None)

    def sidebar(root=None):
        names = {labels['setting_section_'+id]: id for id in IDS}
        return {names[n.get('text')]: n for n in visible_nodes(root if root is not None else tree())
                if n.get('text') in names and n.get('class') == 'android.widget.TextView' and n.get('clickable') == 'true'}

    def tap_node(node):
        assert node is not None
        a,b,c,d = node_bounds(node); device.tap((a+c)//2, (b+d)//2); time.sleep(.4)

    def category(id): tap_node(sidebar().get(id))
    def back(): device.shell('input', 'keyevent', 'KEYCODE_BACK'); time.sleep(.4)
    def orient(value):
        device.shell('am', 'broadcast', '-n', APP+'/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', value)
        time.sleep(.8)

    def shot(name):
        raw = device.adb('exec-out', 'screencap', '-p')
        (args.output/(name+'.png')).write_bytes(raw)
        report['screens'][name] = list(struct.unpack('>II', raw[16:24]))

    try:
        device.shell('am', 'start', '-W', '-n', APP+'/.MainActivity')
        device.debug('theme', value='DARK'); device.debug('hold_attention', value='ENGAGED')
        device.debug('drift', x=0.0, y=0.0)
        report['burn_in_fixed_during_geometry_checks'] = True
        orient(0)
        for locale in LOCALES:
            labels = json.loads((ROOT/'shared/i18n/system'/f'{locale}.json').read_text())
            device.debug('locale', value={'zh-Hans':'zh-CN', 'zh-Hant':'zh-TW'}.get(locale, locale))
            until = time.monotonic()+25
            while time.monotonic() < until:
                device.debug('page', value='home.main'); device.debug('settings', value='system')
                if broadcast_json(device, 'ui_state')['page'] == 'settings.form': break
                time.sleep(.35)
            else: raise AssertionError('The factory settings page did not become available')
            wait(lambda: len(sidebar()) == 6)
            root = tree(); nav = sidebar(root)
            check(locale+' shows all six one-line categories', len(nav) == 6 and nav['display'].get('selected') == 'true')
            category('location')
            content = wait(lambda: visible(labels['setting_units_system']))
            na,nb,nc,nd = node_bounds(sidebar()['location']); ca,cb,cc,cd = node_bounds(content)
            check(locale+' mirrors the sidebar correctly', nc <= ca if locale not in ('ar', 'ur') else cc <= na)
            shot('landscape-'+locale)
            # Scroll only the content; the six category bounds must stay fixed.
            category('display')
            root = tree(); before = {id: node_bounds(n) for id,n in sidebar(root).items()}
            theme = visible(labels['setting_theme_mode'], root); assert theme is not None
            old_theme = node_bounds(theme)
            x = (old_theme[0]+old_theme[2])//2
            _,_,_,height = node_bounds(root.find('node'))
            device.shell('input', 'swipe', x, int(height*.84), x, int(height*.25), 450)
            root = tree(); moved = visible(labels['setting_theme_mode'], root)
            check(locale+' content scroll is independent of categories',
                  before == {id: node_bounds(n) for id,n in sidebar(root).items()} and
                  (moved is None or node_bounds(moved) != old_theme))

        labels = json.loads((ROOT/'shared/i18n/system/zh-Hans.json').read_text())
        device.debug('locale', value='zh-CN')
        category('location'); orient(1)
        wait(lambda: visible(labels['setting_units_system']))
        check('portrait retains the chosen category and hides the sidebar', not sidebar())
        shot('portrait-location')
        back()
        check('portrait Back returns to the six-category root', all(visible(labels['setting_section_'+id]) is not None for id in IDS))
        tap_node(visible(labels['setting_section_plugins']))
        weather = json.loads((ROOT/'plugins/weather/i18n/zh-Hans.json').read_text())
        tap_node(visible(weather['name']))
        orient(0)
        wait(lambda: visible(weather['setting.interval']))
        check('rotation preserves the plugin and highlights Plugins', sidebar()['plugins'].get('selected') == 'true')
        back()
        check('Back from the plugin returns to its list', any(
            n.get('text') == labels['setting_section_plugins'] and n.get('clickable') == 'false'
            for n in visible_nodes(tree())))
        category('location'); category('desktop'); category('location')
        back()
        check('repeated sidebar selections do not add return entries', broadcast_json(device, 'ui_state')['page'] == 'home.main')
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
        lines = [line for line in device.log_since(0) if '[plugin:settings]' in line or 'AndroidRuntime' in line]
        (args.output/'settings-logcat.txt').write_text('\n'.join(lines)+'\n')
        report['settings_budget_errors'] = [line for line in lines if 'budget exceeded' in line]
        device.stop_log()
        (args.output/'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    if report.get('recovery_error'): raise RuntimeError(report['recovery_error'])


if __name__ == '__main__': main()
