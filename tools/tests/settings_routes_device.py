#!/usr/bin/env python3
"""SET-03/05 native routes, duration persistence, related-only forms and Android return.

Uses the installed debug build and real native taps. Keeps audio at zero and restores
settings/navigation from a private durable journal; no default launcher or system module changes.
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
from media_device import APP, Device
from ui_walkthrough import broadcast_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/set/SET-03/routes-device')
    parser.add_argument('--weather-only', action='store_true', help='Only check the related city/units form and native city edit')
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    assert any(APP+'/' in line for line in device.shell('dumpsys', 'activity', 'activities').splitlines() if 'mResumedActivity' in line)
    assert re.search(r'volume is 0\b', device.shell('media', 'volume', '--stream', 3, '--get'))
    journal = capture(device, broadcast_json(device, 'ui_state'))
    print('Private recovery journal: '+str(journal), flush=True)
    report = {'complete': False, 'passed': []}
    labels = json.loads((ROOT/'shared/i18n/system/zh-Hans.json').read_text())
    defaults = json.loads((ROOT/'shared/schema/settings/system.json').read_text())['fields']

    def wait(test, seconds=15):
        until = time.monotonic()+seconds
        while time.monotonic() < until:
            result = test()
            if result is not None and result is not False: return result
            time.sleep(.25)
        raise AssertionError('Timed out waiting for native settings route')

    def tree():
        remote = '/sdcard/thinkos-settings-routes.xml'
        try:
            dump_hierarchy(args.serial, remote, compressed=True)
            raw = device.shell('cat', remote); (args.output/'last-ui.xml').write_text(raw)
            return ET.fromstring(raw)
        finally: device.shell('rm', '-f', remote)

    def nodes(text): return [n for n in visible_nodes(tree()) if n.get('text') == text]
    def find(text): return wait(lambda: next(iter(nodes(text)), None))
    def tap(text, last=False):
        choices = wait(lambda: nodes(text) or None)
        a,b,c,d = node_bounds(choices[-1] if last else choices[0]); device.tap((a+c)//2, (b+d)//2); time.sleep(.4)
    def back(): device.shell('input', 'keyevent', 'KEYCODE_BACK'); time.sleep(.5)
    def saved(key):
        return json.loads(read_app_file(args.serial, 'files/settings/local.json') or b'{}').get('system', {}).get(key, defaults[key]['default'])
    def check(name, ok=True):
        assert ok, name
        report['passed'].append(name); print('PASS '+name, flush=True)
    def setup():
        device.shell('am', 'start', '-W', '-n', APP+'/.MainActivity')
        device.debug('locale', value='zh-CN'); device.debug('theme', value='DARK'); device.debug('hold_attention', value='ENGAGED')
        device.debug('drift', x=0.0, y=0.0)
        device.shell('am', 'broadcast', '-n', APP+'/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', 1)
    def page(id):
        device.debug('page', value=id)
        wait(lambda: broadcast_json(device, 'ui_state')['page'] == id)
        time.sleep(.5)
    def root():
        until = time.monotonic()+25
        while time.monotonic() < until:
            device.debug('page', value='home.main'); device.debug('settings', value='system')
            if broadcast_json(device, 'ui_state')['page'] == 'settings.form': break
            time.sleep(.4)
        else: raise AssertionError('Settings catalog did not become available')
        find(labels['setting_section_display'])
    def choose(key, value, display):
        tap(labels['setting_'+key.replace('.', '_')]); tap(display, last=True)
        wait(lambda: saved(key) == value)
    def focused(): return device.shell('dumpsys', 'window', 'windows').split('mCurrentFocus=')[-1].splitlines()[0]
    def locked(): return 'mLockTaskModeState=LOCKED' in device.shell('dumpsys', 'activity', 'activities')

    def weather_related():
        page('home.main'); device.debug('settings', value='weather')
        wait(lambda: broadcast_json(device, 'ui_state')['page'] == 'settings.form')
        fields=[labels['setting_location_name'], labels['setting_units_system'], labels['setting_units_wind']]
        tops=[node_bounds(find(label))[1] for label in fields]
        interval=json.loads((ROOT/'plugins/weather/i18n/zh-Hans.json').read_text())['setting.interval']
        check('weather starts with city and both related system units', tops == sorted(tops) and max(tops) < node_bounds(find(interval))[1])
        tap(fields[0]); tap(labels['setting_location_mode_manual'])
        edits=[n for n in visible_nodes(tree()) if n.get('class') == 'android.widget.EditText']
        editor=min(edits,key=lambda n: node_bounds(n)[1]); a,b,c,d=node_bounds(editor)
        device.tap((a+c)//2,(b+d)//2); device.shell('input','keyevent',123)
        if editor.get('text'): device.shell('input','keyevent',*([67]*len(editor.get('text'))))
        device.shell('input','text','UI-RELATED-CITY')
        if 'mInputShown=true' in device.shell('dumpsys','input_method'): back()
        positive=next(n for n in visible_nodes(tree()) if n.get('resource-id') == 'android:id/button1')
        a,b,c,d=node_bounds(positive); device.tap((a+c)//2,(b+d)//2)
        wait(lambda: saved('location.name') == 'UI-RELATED-CITY')
        local=json.loads(read_app_file(args.serial,'files/settings/local.json') or b'{}')
        check('the related city editor persists in system scope', not any(key.startswith('location.') for key in local.get('weather',{})))
        tap('完成'); page('weather.detail'); find('UI-RELATED-CITY')
        device.screenshot(args.output/'weather-city-applied.png')
        check('the weather detail immediately displays the edited system city')

    def standard_routes():
        setup(); root()
        categories = [labels['setting_section_'+key] for key in ('display','location','desktop','media','system','plugins')]
        visible = {n.get('text') for n in visible_nodes(tree())}
        check('all six portrait categories fit without scrolling', all(name in visible for name in categories))
        device.screenshot(args.output/'portrait-categories.png')
        tap(categories[0])
        choose('attention.awake_to_ambient_s', 30, '30 秒')
        choose('attention.to_asleep_s', 60, '1 分钟')
        device.shell('am', 'force-stop', APP); setup(); root(); tap(categories[0])
        find('30 秒'); find('1 分钟')
        check('native timing choices persist across an application restart', saved('attention.awake_to_ambient_s') == 30 and saved('attention.to_asleep_s') == 60)
        device.debug('setting', key='attention.awake_to_ambient_s', value='137')
        wait(lambda: saved('attention.awake_to_ambient_s') == 137)
        # A remote update is displayed on the next describe; debug writes do not emit form.change.
        back(); tap(categories[0])
        find('137 秒'); device.screenshot(args.output/'non-preset-duration.png')
        check('a valid non-preset duration is displayed without rounding', saved('attention.awake_to_ambient_s') == 137)
        back(); tap(categories[4])
        choose('apps.return_after_s', 900, '15 分钟')
        find('15 分钟')
        choose('apps.return_after_s', 0, '关闭'); find('关闭')
        check('automatic return uses localized minutes and its zero label', saved('apps.return_after_s') == 0 and not any(n.get('text') == '900 s' for n in visible_nodes(tree())))
        check('ThinkOS starts with the kiosk lock active', wait(locked))
        tap('打开安卓设置'); wait(lambda: 'com.android.settings' in focused())
        device.screenshot(args.output/'android-settings.png')
        check('the system category opens the real Android settings activity')
        back(); wait(lambda: APP in focused()); wait(locked)
        check('Back returns from Android settings and restores Lock Task')
        for owner, page_id, expected in (
            ('tiles', 'tiles.main', [labels['setting_tiles_live'], labels['setting_tiles_interval'], labels['setting_tiles_background']]),
            ('cast', 'cast.home', [labels['setting_cast_enabled'], labels['setting_cast_airplay'], labels['setting_cast_miracast']]),
        ):
            page(page_id)
            device.shell('input', 'swipe', 400, 130, 400, 130, 900)
            wait(lambda: broadcast_json(device, 'ui_state')['page'] == 'settings.form')
            for label in expected: find(label)
            check(owner+' long press opens a related-only settings page')
            device.screenshot(args.output/(owner+'-related.png'))
            back(); wait(lambda: broadcast_json(device, 'ui_state')['page'] == page_id)
        page('cast.home'); tap('DLNA')
        find(labels['setting_cast_enabled'])
        check('a casting row opens the casting settings directly')
    try:
        if args.weather_only:
            setup(); weather_related()
        else: standard_routes()
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
        report['media_volume_zero'] = bool(re.search(r'volume is 0\b', device.shell('media', 'volume', '--stream', 3, '--get')))
        (args.output/'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    assert not report.get('recovery_error'), report.get('recovery_error')
    assert report['media_volume_zero']


if __name__ == '__main__': main()
