#!/usr/bin/env python3
"""Exercise the installed factory onboarding through actual native controls, in silence."""
import argparse
import base64
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
from device_recovery import capture, restore, PATHS, OPTIONAL_PATHS, RECOVERY_PATHS
from media_device import APP, Device
from ui_walkthrough import broadcast_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--leave-open', action='store_true')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    d = Device(args.serial)
    assert d.shell('getprop', 'ro.serialno').strip() == 'HUA0BWUT'
    assert APP in next(s for s in d.shell('dumpsys', 'activity', 'activities').splitlines()
                       if 'ResumedActivity:' in s or 'mResumedActivity' in s)
    original = broadcast_json(d, 'ui_state')
    original_wifi=d.shell('settings','get','global','wifi_on').strip()
    journal = capture(d, original, PATHS+OPTIONAL_PATHS+RECOVERY_PATHS)
    welcome_paths = ('files/store/welcome.json', 'files/store/welcome.json.bak')
    saved = {p: read_app_file(d.serial, p) for p in welcome_paths}
    with os.fdopen(os.open(journal/'welcome.json', os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600), 'w') as stream:
        json.dump({p: None if raw is None else base64.b64encode(raw).decode() for p,raw in saved.items()}, stream)
    result = {'complete': False, 'physical_serial': d.serial, 'checks': [],
              'scope': 'WEL-1 actual factory plugin and controls; no root, device-owner mutation, system timezone change or performance measurement',
              'private_recovery_journal': str(journal)}
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    result['apk_sha256']=d.shell('sha256sum',installed).split()[0]
    d.start_log()

    def save(): (args.output/'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    def check(name, ok):
        result['checks'].append({'name': name, 'passed': bool(ok)})
        save(); print(('PASS ' if ok else 'FAIL ')+name, flush=True)
        assert ok, name
    def wait(predicate, timeout=20):
        until = time.monotonic()+timeout
        while time.monotonic()<until:
            value = predicate()
            if value: return value
            time.sleep(.2)
        return None
    def ui(): return broadcast_json(d, 'ui_state')
    def tree():
        remote='/sdcard/thinkos-onboarding.xml'
        try:
            dump_hierarchy(d.serial, remote)
            raw=d.shell('cat', remote)
            (args.output/'last-ui.xml').write_text(raw)
            return ET.fromstring(raw)
        finally: d.shell('rm','-f',remote)
    def find(label, contains=False):
        return next((dict(node.attrib) for node in reversed(list(visible_nodes(tree())))
                     if node.get('text')==label or node.get('content-desc')==label or
                     contains and label in node.get('text','')), None)
    def tap(label, contains=False):
        node=wait(lambda: find(label,contains)); assert node is not None, 'Missing '+label
        left,top,right,bottom=node_bounds(node)
        d.tap((left+right)//2,(top+bottom)//2)
        time.sleep(.3)
    def back(): d.shell('input','keyevent','KEYCODE_BACK'); time.sleep(.3)
    def opened(): return ui().get('onboardingOpen') is True
    def photo(name):
        root=tree()
        screen=broadcast_json(d,'stage_state')
        width,height=screen['width'],screen['height']
        texts=[n for n in visible_nodes(root) if n.get('package')==APP and n.get('text')]
        check(name+' visible text stays inside the screen', all(
            0<=a<c<=width and 0<=b<e<=height for a,b,c,e in map(node_bounds,texts)))
        d.screenshot(args.output/(name+'.png'))
    def quiet():
        return all(re.search(r'volume is 0\b',d.shell('media','volume','--stream',i,'--get')) for i in range(1,6))
    def setting(key):
        # The store omits an override equal to its underlying value. Inspect
        # the actual persisted layers in the same order as SettingsStore.
        for name in ('local','server'):
            layer=json.loads(read_app_file(d.serial,'files/settings/'+name+'.json') or b'{}')
            if key in layer.get('system',{}):return layer['system'][key]
        return json.loads((ROOT/'shared/schema/settings/system.json').read_text())['fields'][key]['default']

    save()
    try:
        check('all five audio streams are zero before testing',quiet())
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        assert wait(lambda:bool(ui().get('page'))), 'Factory catalog must be published before a manual entry request'
        if args.offline:
            d.shell('svc','wifi','disable')
            check('Wi-Fi is actually disabled for the offline flow',wait(lambda:d.shell('settings','get','global','wifi_on').strip()=='0'))
            def network_down():
                default=next((s.strip() for s in d.shell('dumpsys','connectivity').splitlines() if 'Active default network:' in s),'')
                return 'none' in default.lower()
            check('the offline flow has no active Android default network',wait(network_down))
        d.debug('locale',value='zh-CN')
        d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value','1')
        d.debug('onboarding_open',value='restart')
        check('the installed factory welcome page opens in the real onboarding layer',wait(opened))
        check('manual restart resets an exhausted automatic prompt budget',wait(lambda:setting('onboarding.attempts')==0))
        time.sleep(3)  # Let Android's pinning toast leave the reference image.
        photo('hello')
        stage=broadcast_json(d,'stage_state')
        d.tap(int(stage['width']*.1),int(stage['height']*.2))
        check('tapping the greeting advances to language instead of closing onboarding',wait(lambda:find('语言')) and opened())
        photo('language')
        back()
        check('Back returns from language to the greeting while keeping onboarding open',wait(lambda:find('跳过设置')) and opened())
        stage=broadcast_json(d,'stage_state');d.tap(stage['width']//2,int(stage['height']*.44))
        tap('简体中文')
        check('the current language advances to region without leaving ThinkOS',wait(lambda:find('地区')) and opened())
        photo('region')
        tap('地区')
        check('the native region picker has offline Chinese region choices',wait(lambda:find('香港',True)) is not None)
        back()
        check('Back closes only the region picker',opened() and find('香港',True) is None and find('地区') is not None)
        tap('地区');tap('香港',True)
        if args.offline:check('offline setup offers the real Wi-Fi step',wait(lambda:find('连接 Wi-Fi')) is not None)
        if wait(lambda:find('Wi-Fi') or find('数据与隐私')) is not None and find('跳过') is not None:
            tap('跳过')
        check('region selection reaches privacy and does not invent timezone capability',wait(lambda:find('不收集任何数据')) is not None)
        photo('privacy');tap('许可证')
        check('the real native legal page opens from onboarding',wait(lambda:broadcast_json(d,'legal_state').get('showing')))
        back();tap('继续')
        check('software update is a real next screen',wait(lambda:find('只自动下载')) is not None)
        photo('updates');tap('只自动下载')
        check('only download writes the real automatic-install setting',wait(lambda:setting('update.auto_install') is False))
        check('location appears with its time hero and timezone action',wait(lambda:find('更改时区')) is not None)
        photo('location');back()
        check('Back from location returns to the update screen',wait(lambda:find('只自动下载')) is not None)
        tap('继续')
        check('the update primary button writes automatic installation on',wait(lambda:setting('update.auto_install') is True))
        tap('跳过')
        check('appearance is reached through actual controls',wait(lambda:find('外观')) is not None)
        photo('appearance-portrait')
        d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value','0');time.sleep(.5)
        photo('appearance-landscape')
        d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value','1');time.sleep(.5)
        tap('继续')
        def stage():return broadcast_json(d,'stage_state')
        def practice(name):return wait(lambda:stage().get('practice')==name)
        def swipe(x,y,a,b):d.shell('input','swipe',int(x),int(y),int(a),int(b),'400');time.sleep(.4)
        check('gesture instruction starts the real OS practice mode',practice('swipe'))
        w,h=stage()['width'],stage()['height']
        swipe(w*.5,h*.65,w*.5,h*.3)
        check('a wrong gesture does not advance or change pages',stage().get('practice')=='swipe' and ui()['page']=='welcome.gestures')
        swipe(w*.2,h*.5,w*.8,h*.5)
        check('a real sideways swipe advances without navigating the deck',practice('variant') and ui()['page']=='welcome.gestures')
        swipe(w*.5,h*.7,w*.5,h*.25)
        check('a real vertical swipe advances without changing the page variant',practice('panel'))
        swipe(w*.5,2,w*.5,h*.7)
        check('top-edge practice advances without opening the control panel',practice('back') and not stage()['panelOpen'])
        swipe(2,h*.5,w*.7,h*.5)
        check('left-edge practice advances while keeping the onboarding layer',practice('home') and opened())
        swipe(w*.5,h-2,w*.5,h*.35)
        check('bottom-edge Home remains an actual exit',wait(lambda:not opened()))
        time.sleep(2.5)  # Let the normal coalesced plugin-store write commit.
        d.shell('am','force-stop',APP);d.shell('am','start','-W','-n',APP+'/.MainActivity')
        check('a new process automatically resumes after Home at the next unfinished exercise',practice('long-press'))
        w,h=stage()['width'],stage()['height']
        d.shell('input','swipe',w//2,h//2,w//2,h//2,'1000')
        check('a real long press completes practice without opening settings',wait(lambda:find('双指长按左上角5秒打开维护菜单')) and ui()['page']=='welcome.gestures')
        photo('maintenance-instruction');tap('继续')
        check('the final welcome screen is reachable',wait(lambda:find('开始使用')) is not None)
        photo('welcome');tap('开始使用')
        check('Start completes onboarding and returns home',wait(lambda:not opened()) and wait(lambda:json.loads(read_app_file(d.serial,'files/settings/local.json') or b'{}').get('system',{}).get('onboarding.done')==1))
        d.shell('am','force-stop',APP);d.shell('am','start','-W','-n',APP+'/.MainActivity')
        check('completed onboarding stays closed in a new process',wait(lambda:bool(ui().get('page'))) and not opened())
        d.debug('settings',value='system');tap('系统');tap('重新运行新手引导')
        check('the actual settings action reopens the factory welcome page',wait(lambda:find('跳过设置')) and opened())
        tap('跳过设置')
        check('Skip setup closes the flow without trapping the user',wait(lambda:not opened()))
        d.debug('settings',value='system');tap('完成设置')
        check('the public-data completion action opens only unfinished screens',wait(lambda:find('语言')) and opened())
        check('the completed walk has no welcome-plugin or Android runtime crash',not any(
            ('E ThinkOS' in line and '[plugin:welcome]' in line) or 'FATAL EXCEPTION' in line for line in d.log_since(0)))
        result['complete']=True
    except Exception as error:
        result['error']=repr(error)
        try:d.screenshot(args.output/'failure.png')
        except Exception:pass
        raise
    finally:
        d.shell('am','force-stop',APP)
        for path,raw in saved.items():restore_app_file(d.serial,path,raw)
        result['welcome_store_restored']=all(read_app_file(d.serial,p)==v for p,v in saved.items())
        try:
            result['restoration']=restore(journal,d)
            result.pop('private_recovery_journal',None)
        except Exception as error:result['recovery_error']=repr(error)
        result['audio_zero_after']=quiet()
        if args.offline and original_wifi in ('1','2'):d.shell('svc','wifi','enable')
        result['wifi_restored']=not args.offline or wait(lambda:d.shell('settings','get','global','wifi_on').strip() in (('1','2') if original_wifi in ('1','2') else (original_wifi,))) is not None
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n')
        d.stop_log()
        save()
    assert result['complete'] and result['welcome_store_restored'] and result['audio_zero_after'] and result['wifi_restored'] and not result.get('recovery_error')
    if args.leave_open:
        d.debug('onboarding_open',value='restart')
        assert wait(opened)
        result['left_open_for_owner']=True;save()


if __name__=='__main__':main()
