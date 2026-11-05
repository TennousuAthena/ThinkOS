#!/usr/bin/env python3
"""Compare portrait pixels with a separately built pre-SET-06 form, then reinstall the current APK.

Both APKs must be local, owned debug builds. Captures root/display/location/weather in Chinese,
English and Arabic. Keeps media volume at zero and restores exact settings/navigation/store bytes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tools/tests')]
from device_hierarchy import dump_hierarchy, node_bounds, visible_nodes
from device_recovery import capture, restore, PATHS, OPTIONAL_PATHS
from media_device import APP, Device
from ui_walkthrough import broadcast_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--reference-apk', required=True, type=Path)
    parser.add_argument('--current-apk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--locale', action='append', choices=('zh-Hans','en','ar'))
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    locales = args.locale or ['zh-Hans','en','ar']
    device = Device(args.serial)
    assert device.shell('getprop', 'ro.serialno').strip() == 'HUA0BWUT'
    assert any(APP+'/' in line for line in device.shell('dumpsys', 'activity', 'activities').splitlines() if 'mResumedActivity' in line)
    apks = {'reference':args.reference_apk.resolve(), 'current':args.current_apk.resolve()}
    report = {'complete':False, 'apks':{}, 'comparisons':{}}
    for name, apk in apks.items():
        with zipfile.ZipFile(apk) as archive:
            abis = sorted({p.split('/')[1] for p in archive.namelist() if p.startswith('lib/')})
        assert abis == ['armeabi-v7a']
        report['apks'][name] = {'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(), 'abis':abis}
    journal = capture(device, broadcast_json(device, 'ui_state'), PATHS+OPTIONAL_PATHS)
    print('Private recovery journal: '+str(journal), flush=True)

    def tree():
        remote = '/sdcard/thinkos-portrait-comparison.xml'
        try:
            dump_hierarchy(args.serial, remote, compressed=True)
            raw = device.shell('cat', remote); (args.output/'last-ui.xml').write_text(raw)
            return ET.fromstring(raw)
        finally: device.shell('rm', '-f', remote)

    def find(label):
        until = time.monotonic()+15
        while time.monotonic() < until:
            found = next((n for n in visible_nodes(tree()) if n.get('text') == label), None)
            if found is not None: return found
            time.sleep(.2)
        raise AssertionError('Missing portrait control: '+label)

    def tap(label):
        a,b,c,d = node_bounds(find(label)); device.tap((a+c)//2, (b+d)//2); time.sleep(.5)

    def enter(scope):
        until = time.monotonic()+25
        while time.monotonic() < until:
            device.debug('page', value='home.main'); device.debug('settings', value=scope)
            if broadcast_json(device, 'ui_state')['page'] == 'settings.form': return
            time.sleep(.35)
        raise AssertionError('Settings page not ready')

    def install(apk):
        result = device.adb('install', '-r', str(apk), timeout=300).decode()
        assert 'Success' in result
        device.shell('am', 'start', '-W', '-n', APP+'/.MainActivity')

    try:
        for version, apk in apks.items():
            install(apk)
            device.debug('theme', value='DARK'); device.debug('hold_attention', value='ENGAGED')
            device.debug('drift', x=0.0, y=0.0)
            device.shell('am', 'broadcast', '-n', APP+'/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', 1)
            for locale in locales:
                tag = 'zh-CN' if locale == 'zh-Hans' else locale
                system = json.loads((ROOT/'shared/i18n/system'/f'{locale}.json').read_text())
                settings = json.loads((ROOT/'plugins/settings/i18n'/f'{locale}.json').read_text())
                weather = json.loads((ROOT/'plugins/weather/i18n'/f'{locale}.json').read_text())
                device.debug('locale', value=tag)
                for page in ('root','display','location','weather'):
                    enter('weather' if page == 'weather' else 'system')
                    if page in ('display','location'): tap(system['setting_section_'+page])
                    expected = {'root':settings['title'], 'display':system['setting_display_brightness_max'],
                                'location':system['setting_units_system'], 'weather':weather['setting.interval']}[page]
                    # Lock Task's "screen pinned" toast can arrive after the first catalog.
                    # Compare the settled app, including the full frame, without masking it.
                    find(expected); time.sleep(4 if page == 'root' else 1.5)
                    (args.output/f'{version}-{locale}-{page}.xml').write_bytes(ET.tostring(tree(), encoding='utf-8'))
                    device.screenshot(args.output/f'{version}-{locale}-{page}.png')
                    print('CAPTURE '+version+' '+locale+' '+page, flush=True)
        from PIL import Image, ImageChops
        for locale in locales:
            for page in ('root','display','location','weather'):
                name = locale+'-'+page
                a = Image.open(args.output/f'reference-{name}.png').convert('RGB')
                b = Image.open(args.output/f'current-{name}.png').convert('RGB')
                assert a.size == b.size == (800,1280)
                different = sum(any(pixel) for pixel in ImageChops.difference(a,b).getdata())
                report['comparisons'][name] = {'different_pixels':different, 'total_pixels':a.width*a.height}
                print(('PASS ' if different == 0 else 'FAIL ')+name+' pixels='+str(different), flush=True)
        report['complete'] = all(x['different_pixels'] == 0 for x in report['comparisons'].values())
        assert report['complete'], 'Portrait pixels changed; inspect both captures'
    except Exception as error:
        report['failure'] = str(error)
        raise
    finally:
        try:
            install(apks['current'])
            report['current_apk_reinstalled'] = True
            report.update(restore(journal, device))
            volume = device.shell('media','volume','--stream','3','--get')
            report['volume_zero'] = bool(re.search(r'volume is 0 in range', volume))
        except Exception as error:
            report['recovery_error'] = str(error); report['private_recovery_journal'] = str(journal)
        (args.output/'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    if report.get('recovery_error'): raise RuntimeError(report['recovery_error'])


if __name__ == '__main__': main()
