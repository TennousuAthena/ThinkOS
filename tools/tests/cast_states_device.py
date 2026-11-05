#!/usr/bin/env python3
"""UI-07's enabled, disabled and actually offline cast-page screenshots.

Temporarily toggles Android Wi-Fi, then restores its original switch and the app's
settings bytes. Does not install system modules or change Device Owner.
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
from device_app_files import read_app_file, restore_app_file
from device_hierarchy import dump_hierarchy, node_bounds, visible_nodes
from media_device import APP, Device
from ui_walkthrough import broadcast_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/ui/UI-07')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    original = broadcast_json(device, 'ui_state')
    wifi = device.shell('settings', 'get', 'global', 'wifi_on').strip() == '1'
    saved = {p:read_app_file(args.serial,p) for p in ('files/settings/local.json','files/settings/local.json.bak')}
    report = {'complete':False,'wifi_before':wifi,'passed':[]}
    device.start_log()

    def texts():
        path='/sdcard/thinkos-cast-states.xml'
        dump_hierarchy(args.serial,path)
        try:
            raw=device.shell('cat',path)
            (args.output/'last-ui.xml').write_text(raw)
            return [n.get('text') for n in visible_nodes(ET.fromstring(raw)) if n.get('text')]
        finally: device.shell('rm','-f',path)

    def wait(test, seconds=30):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            if test(): return True
            time.sleep(.5)
        return False

    def ip():
        return bool(re.search(r'\binet\s', device.shell('ip','-f','inet','addr','show','wlan0',check=False)))

    def check(name, ok):
        if not ok: raise AssertionError(name)
        report['passed'].append(name)
        print('PASS '+name,flush=True)

    def switches(value):
        for key in ('cast.enabled','cast.airplay','cast.miracast'):
            device.debug('setting',key=key,value=str(value).lower())
        time.sleep(1)

    try:
        device.debug('hold_attention',value='ENGAGED')
        device.debug('theme',value='DARK')
        device.debug('drift',x=0.0,y=0.0)
        device.debug('locale',value='zh-CN')
        if not wifi: device.shell('svc','wifi','enable')
        check('Wi-Fi has an address before the enabled-state capture',wait(ip))
        switches(False)
        device.debug('page',value='cast.home')
        time.sleep(1)
        check('all disabled has no explanatory footer','未连接 Wi-Fi' not in texts())
        check('all three receiver rows show disabled',texts().count('关')==3)
        device.screenshot(args.output/'all-disabled.png')
        switches(True)
        check('receiver switches refresh while the waiting page stays open',wait(lambda:texts().count('开')>=2 and '关' not in texts(),12))
        check('all enabled on Wi-Fi has no explanatory footer','未连接 Wi-Fi' not in texts())
        report['enabled_texts']=texts()
        enabled_tree=ET.parse(args.output/'last-ui.xml').getroot()
        enabled_rows={n.get('text'):n.get('bounds') for n in visible_nodes(enabled_tree) if n.get('text') in ('DLNA','AirPlay','Miracast')}
        report['enabled_rows']=enabled_rows
        (args.output/'enabled-ui.xml').write_bytes((args.output/'last-ui.xml').read_bytes())
        device.screenshot(args.output/'all-enabled.png')
        device.shell('svc','wifi','disable')
        check('Wi-Fi is actually disconnected',wait(lambda:not ip()))
        check('only the offline state has its network footer',wait(lambda:'未连接 Wi-Fi' in texts(),15))
        offline_tree=ET.parse(args.output/'last-ui.xml').getroot()
        offline_rows={n.get('text'):n.get('bounds') for n in visible_nodes(offline_tree) if n.get('text') in ('DLNA','AirPlay','Miracast')}
        report['offline_rows']=offline_rows
        # The trailing Miracast status can grow from one word to a module error;
        # that legitimately narrows the title's measured width. Row origins and
        # heights must stay fixed when the separate footer appears.
        def positions(tree):
            return {n.get('text'):(box[0],box[1],box[3]) for n in visible_nodes(tree)
                    if n.get('text') in ('DLNA','AirPlay','Miracast') and (box:=node_bounds(n))}
        check('receiver row positions do not jump when the footer appears',positions(enabled_tree)==positions(offline_tree))
        device.screenshot(args.output/'offline.png')
        (args.output/'offline-connectivity.txt').write_text(device.shell('dumpsys','connectivity'))
        report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        device.screenshot(args.output/'failed-screen.png')
        raise
    finally:
        device.shell('svc','wifi','enable' if wifi else 'disable')
        report['wifi_switch_restored']=wait(lambda:(device.shell('settings','get','global','wifi_on').strip()=='1')==wifi)
        if wifi: report['wifi_reconnected']=wait(ip)
        device.shell('am','force-stop',APP)
        for path,content in saved.items(): restore_app_file(args.serial,path,content)
        report['restored_files']={p:read_app_file(args.serial,p)==v for p,v in saved.items()}
        device.shell('am','start','-n',APP+'/.MainActivity')
        time.sleep(3)
        device.debug('locale',value=original['locale'])
        device.debug('drift')
        for command,key in (('theme','theme'),('hold_attention','attention')):
            device.debug(command,**({'value':original[key]} if original[key] is not None else {}))
        device.debug('page',value=original['page'] or 'home.main')
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        (args.output/'logcat.txt').write_text('\n'.join(device.log_since(0))+'\n')
        device.stop_log()
        assert report['wifi_switch_restored'] and (not wifi or report['wifi_reconnected']) and all(report['restored_files'].values())
    return 0


if __name__=='__main__': sys.exit(main())
