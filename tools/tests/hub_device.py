#!/usr/bin/env python3
"""Native hub entry/tabs/settings regression; no registry keys, installs or audio changes."""
import argparse
import json
from pathlib import Path
import re
import sys
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
from device_hierarchy import dump_hierarchy,node_bounds,visible_nodes
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS,RECOVERY_PATHS
from media_device import APP,Device
from ui_walkthrough import broadcast_json


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default='HUA0BWUT')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    d=Device(args.serial)
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert APP+'/' in d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    def quiet():return all(re.search(r'volume is 0\b',d.shell('media','volume','--stream',i,'--get')) for i in range(1,6))
    assert quiet()
    original=broadcast_json(d,'ui_state');assert not original.get('onboardingOpen')
    journal=capture(d,original,PATHS+OPTIONAL_PATHS+RECOVERY_PATHS)
    result={'complete':False,'checks':[],'serial':d.serial,'scope':'Factory hub public native UI without registry installation','private_recovery_journal':str(journal)}
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    result['apk_sha256']=d.shell('sha256sum',installed).split()[0]
    def save():(args.output/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    def check(name,ok):
        result['checks'].append({'name':name,'passed':bool(ok)});save()
        print(('PASS ' if ok else 'FAIL ')+name,flush=True);assert ok,name
    def wait(fn):
        until=time.monotonic()+25
        while time.monotonic()<until:
            found=fn()
            if found:return found
            time.sleep(.25)
        return None
    def ui():return broadcast_json(d,'ui_state')
    def tree():
        remote='/sdcard/thinkos-hub.xml'
        try:
            dump_hierarchy(d.serial,remote);raw=d.shell('cat',remote)
            (args.output/'last-ui.xml').write_text(raw);return ET.fromstring(raw)
        finally:d.shell('rm','-f',remote)
    def node(label):return next((dict(n.attrib) for n in reversed(list(visible_nodes(tree()))) if label in (n.get('text'),n.get('content-desc'))),None)
    def tap(label):
        view=wait(lambda:node(label));assert view is not None,'Missing '+label
        a,b,c,e=node_bounds(view);d.tap((a+c)//2,(b+e)//2);time.sleep(.3)
    def back():d.shell('input','keyevent','KEYCODE_BACK');time.sleep(.4)
    def screenshot(name):d.screenshot(args.output/(name+'.png'))
    def home():
        state=broadcast_json(d,'stage_state');w,h=state['width'],state['height']
        d.shell('input','swipe',w//2,h-2,w//2,h//3,400)
        return wait(lambda:ui().get('page')=='home.main')
    d.start_log();save()
    try:
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('locale',value='zh-CN');d.debug('theme',value='DARK');d.debug('hold_attention',value='ENGAGED')
        d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',1)
        check('factory catalog is ready',wait(lambda:ui().get('page')))
        d.debug('page',value='tiles.apps');tap('插件中心')
        check('the real app tile opens hub',wait(lambda:ui().get('page')=='hub.main'))
        check('discover shows a truthful unconfigured state',wait(lambda:node('尚未配置插件目录')))
        screenshot('discover')
        tap('已安装');check('installed has a native empty state',wait(lambda:node('尚未安装插件')))
        tap('更新');tap('软件更新')
        check('system update opens the real native dialog',wait(lambda:broadcast_json(d,'update_state').get('showing')))
        back();check('Back from native updates retains hub',ui().get('page')=='hub.main')
        tap('发现');tap('目录设置')
        check('catalog settings open the real plugin settings',wait(lambda:ui().get('page','').startswith('settings.')))
        check('catalog settings expose the OS update policy',wait(lambda:node('自动更新插件')))
        back();check('Back from plugin settings returns to hub',wait(lambda:ui().get('page')=='hub.main'))
        check('global bottom edge exits hub to home',home())
        d.debug('settings',value='system');tap('系统');tap('插件中心')
        check('the real system settings entry opens hub',wait(lambda:ui().get('page')=='hub.main'))
        screenshot('from-system-settings')
        result['complete']=True
    except Exception as error:
        result['error']=repr(error)
        try:screenshot('failure')
        except Exception:pass
        raise
    finally:
        try:result['restoration']=restore(journal,d);result.pop('private_recovery_journal',None)
        except Exception as error:result['recovery_error']=repr(error)
        result['audio_zero']=quiet()
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log();save()
    assert result['complete'] and result['audio_zero'] and not result.get('recovery_error')


if __name__=='__main__':main()
