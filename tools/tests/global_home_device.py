#!/usr/bin/env python3
"""ADR-046 first step: real bottom edge goes to the home role in ThinkOS.

Chinese functional checks only; no performance, volume/mute gesture, module
installation or reboot. External/system-wide gestures are separate acceptance.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from ui_walkthrough import broadcast_json


def main():
    os.environ.setdefault('ADB_MDNS','0')
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/app/APP-8/global/home')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    d=Device(args.serial)
    assert APP+'/' in d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    assert broadcast_json(d,'media_state')['status']=='stopped'
    journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
    (args.output/'recovery.json').write_text(json.dumps({'private_journal':str(journal)})+'\n')
    print('Private recovery journal: '+str(journal),flush=True)
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    report={'complete':False,'passed':[],'apk_sha256':d.shell('sha256sum',installed).split()[0],'scope':'ThinkOS bottom-to-home only; global module pending'}
    def wait(test,seconds=25):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            value=test()
            if value is not None and value is not False:return value
            time.sleep(.25)
        raise AssertionError('Bottom-to-home did not reach the required state')
    def ui():return broadcast_json(d,'ui_state')
    def stage():return broadcast_json(d,'stage_state')
    def page(key):d.debug('page',value=key);return ui()['page']==key
    def check(name,ok=True):
        assert ok,name
        report['passed'].append(name);print('PASS '+name,flush=True)
    d.start_log()
    try:
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('hold_attention',value='ENGAGED');d.debug('locale',value='zh-CN');d.debug('theme',value='DARK')
        d.debug('setting',key='nav.up_enabled',value='true')
        d.debug('setting',key='nav.back_enabled',value='true')
        d.debug('setting',key='panel.enabled',value='true')
        d.debug('setting',key='panel.idle_s',value='15')
        for shape,orientation in (('portrait',1),('landscape',0)):
            d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',orientation)
            time.sleep(.8)
            for target in ('apps.main','player.library','cast.home','settings.form'):
                if target=='settings.form':d.debug('settings',value='system');wait(lambda:ui()['page']==target)
                else:wait(lambda:page(target))
                time.sleep(.5);before=stage();mark=d.log_mark()
                d.shell('input','swipe',before['width']//2,before['height']-3,before['width']//2,int(before['height']*.48),300)
                wait(lambda:ui()['page']=='home.main' and not stage()['canGoBack'])
                check(shape+' '+target+' bottom edge clears the stack and returns to Home',
                      any('page=home.main ' in line and 'reason=home_gesture' in line for line in d.log_since(mark)))
            def pull():
                size=stage();d.shell('input','swipe',size['width']//2,1,size['width']//2,int(size['height']*.42),300)
                wait(lambda:stage()['panelOpen']);time.sleep(.6)
            wait(lambda:page('apps.main'));time.sleep(.5);pull();prior=stage()
            d.shell('input','swipe',1,int(prior['height']*.42),int(prior['width']*.55),int(prior['height']*.42),300)
            wait(lambda:not stage()['panelOpen'])
            check(shape+' left Back closes the own panel before the page stack',ui()['page']=='apps.main' and stage()['canGoBack']==prior['canGoBack'])
            pull();size=stage()
            d.shell('input','swipe',size['width']//2,size['height']-3,size['width']//2,int(size['height']*.48),300)
            wait(lambda:ui()['page']=='home.main' and not stage()['canGoBack'] and not stage()['panelOpen'])
            check(shape+' bottom Home also leaves the open own panel')
            pull();size=stage()
            d.shell('input','swipe',size['width']//2,size['height']-3,size['width']//2,int(size['height']*.48),300)
            wait(lambda:not stage()['panelOpen'] and abs(stage()['translationY'])<.5)
            check(shape+' bottom Home on the home page closes the panel and settles in place',ui()['page']=='home.main' and not stage()['canGoBack'])
            d.screenshot(args.output/(shape+'-home.png'))
        check('The home return keeps Lock Task active','mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities'))
        report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:d.screenshot(args.output/'failure.png')
        except Exception:pass
        raise
    finally:
        try:report.update(restore(journal,d))
        except Exception as error:report.update(recovery_error=str(error),private_journal=str(journal))
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    assert not report.get('recovery_error') and report['media_volume_zero']


if __name__=='__main__':main()
