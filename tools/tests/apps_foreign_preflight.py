#!/usr/bin/env python3
"""APP-2/4 prerequisites on API27: physical Home, separate-UID overlay and audio focus.

Only temporary test APKs and the fixed policy_control setting are changed. Both
APKs, their appops and the test reverse mapping are removed; original system
values and ten application files are restored. No module, Device Owner or reboot.
All sound sources contain silence; system media volume must already be zero.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests'),str(ROOT/'tools/media')]
from device_external_fixture import fixture_apk,PACKAGE,OVERLAY_PACKAGE
from device_hierarchy import dump_hierarchy,node_bounds,visible_nodes
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from ui_walkthrough import broadcast_json
from icy import Station,silent_mp3

PROFILE=(('global','policy_control'),('global','stay_on_while_plugged_in'),('system','screen_brightness'),('system','screen_brightness_mode'))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/app/APP-2/preflight')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    apks={PACKAGE:fixture_apk(),OVERLAY_PACKAGE:fixture_apk(True)}
    d=Device(args.serial)
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    assert d.shell('getprop','ro.build.version.sdk').strip()=='27'
    def focused():return d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    assert APP+'/' in focused()
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    assert broadcast_json(d,'media_state')['status']=='stopped','Preserve an existing media session'
    assert not d.shell('run-as',APP,'ls','files/dev-plugins').strip()
    for package in apks:assert not d.shell('pm','path',package,check=False).strip(),'Preserve a pre-existing fixture installation'
    assert b'tcp:8892' not in d.adb('reverse','--list')
    original={(scope,key):d.shell('settings','get',scope,key).strip() for scope,key in PROFILE}
    journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
    recovery=Path(tempfile.mkdtemp(prefix='thinkos-foreign-system-recovery-'))
    with os.fdopen(os.open(recovery/'system.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as stream:
        json.dump({'physical':'HUA0BWUT','app_journal':str(journal),'profile':[{'scope':s,'key':k,'value':v} for (s,k),v in original.items()],'owned_packages':list(apks),'reverse':'tcp:8892'},stream)
        stream.flush();os.fsync(stream.fileno())
    (args.output/'recovery.json').write_text(json.dumps({'private_app_journal':str(journal),'private_system_journal':str(recovery)})+'\n')
    print('Private recovery journals: '+str(journal)+' '+str(recovery),flush=True)
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    report={'preflight_complete':False,'passed':[],'apk_sha256':d.shell('sha256sum',installed).split()[0]}
    station=None;reverse=False;owned=[];d.start_log()
    def wait(test,seconds=25):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            value=test()
            if value is not None and value is not False:return value
            time.sleep(.3)
        raise AssertionError('Timed out waiting for external-app prerequisite')
    def check(name,ok=True):
        assert ok,name
        report['passed'].append(name);print('PASS '+name,flush=True)
    def command(package,receiver,name,**extras):
        argv=['am','broadcast','-n',package+'/'+receiver,'--es','command',name]
        for key,value in extras.items():
            flag='--ez' if isinstance(value,bool) else '--ei' if isinstance(value,int) else '--ef' if isinstance(value,float) else '--es'
            argv += [flag,key,str(value).lower() if isinstance(value,bool) else str(value)]
        raw=d.shell(*argv);match=re.search(r'data="(\{.*\})"',raw)
        assert match,'Fixture did not return state: '+raw
        result=json.loads(match[1]);assert result,'Fixture command failed: '+name
        return result
    def fixture(name='state',**extras):return command(PACKAGE,'.FixtureReceiver',name,**extras)
    def overlay(name='state',**extras):return command(OVERLAY_PACKAGE,'.OverlayReceiver',name,**extras)
    def tree(name,windows=False):
        remote='/sdcard/thinkos-foreign-preflight.xml'
        try:
            dump_hierarchy(args.serial,remote,compressed=True,all_windows=windows)
            raw=d.shell('cat',remote);(args.output/(name+'.xml')).write_text(raw);return ET.fromstring(raw)
        finally:d.shell('rm','-f',remote)
    def tap(node):
        assert node is not None
        a,b,c,e=node_bounds(node);assert a<c and b<e;d.tap((a+c)//2,(b+e)//2)
    def open_fixture():
        def listing():
            d.debug('page',value='apps.main')
            return broadcast_json(d,'ui_state')['page']=='apps.main'
        wait(listing)
        item=wait(lambda:next((n for n in visible_nodes(tree('android-apps')) if n.get('content-desc')=='外部应用测试'),None))
        tap(item);wait(lambda:PACKAGE+'/' in focused());time.sleep(1)
    def home(name):
        root=tree(name+'-windows',True)
        button=next((n for n in root.iter('node') if n.get('resource-id')=='com.android.systemui:id/home' and n.get('enabled')=='true'),None)
        assert button is not None,'No visible physical navigation-bar Home control'
        report[name+'_home_bounds']=node_bounds(button);d.screenshot(args.output/(name+'-before-home.png'))
        tap(button);wait(lambda:APP+'/' in focused())
        wait(lambda:'mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities'))
    def backlight(name):
        raw=d.shell('dumpsys','display');(args.output/(name+'-display.txt')).write_text(raw)
        match=re.search(r'\bmScreenBrightness=(\d+)',raw);assert match,'No actual display backlight reading'
        return int(match[1])
    def setting(key,value):d.debug('setting',key=key,value=str(value).lower() if isinstance(value,bool) else str(value))
    try:
        for package,apk in apks.items():
            result=d.adb('install',str(apk),timeout=120).decode();assert 'Success' in result
            owned.append(package)
        d.shell('appops','set',OVERLAY_PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('locale',value='zh-CN');d.debug('hold_attention',value='ENGAGED');d.debug('theme',value='DARK')
        d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',1)
        for key,value in (('apps.android',True),('apps.hidden',''),('apps.return_after_s',0),('kiosk.lock',True),('panel.enabled',True)):setting(key,value)
        report['home_resolver']=d.shell('cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME').strip()
        assert APP+'/.MainActivity' in report['home_resolver'],'Preserve the existing default launcher'
        for name,orientation in (('portrait',1),('landscape',8)):
            open_fixture();fixture('orientation',value=orientation);time.sleep(1)
            check(name+' stops Lock Task while retaining the foreign state','mLockTaskModeState=NONE' in d.shell('dumpsys','activity','activities') and broadcast_json(d,'kiosk_state')['foreign'])
            fixture('ignore_back',value=True);before=fixture()['backPresses'];d.shell('input','keyevent','KEYCODE_BACK')
            check(name+' the test application really ignores Back',PACKAGE+'/' in focused() and fixture()['backPresses']>before)
            time.sleep(2);check(name+' launch is not covered by a stock-launcher retry',PACKAGE+'/' in focused())
            home(name)
            check(name+' physical Home returns under the existing status-bar policy and relocks',broadcast_json(d,'ui_state')['page']=='home.main')
        open_fixture();fixture('orientation',value=1);fixture('ignore_back',value=False);time.sleep(1)
        before=fixture();low=overlay('show',brightness=0.1);time.sleep(1.5)
        check('Overlay and foreground app use distinct UIDs and do not pause the app',low['uid']!=before['uid'] and fixture()['resumed'] and fixture()['pauses']==before['pauses'])
        low_light=backlight('overlay-low');overlay('show',brightness=0.6);time.sleep(1.5);high_light=backlight('overlay-high')
        report['overlay_brightness']={'low':low_light,'high':high_light,'applies':high_light>low_light+30}
        print('OBSERVE overlay brightness applies='+str(report['overlay_brightness']['applies'])+' values='+str((low_light,high_light)),flush=True)
        old=overlay();d.shell('input','swipe',400,1,400,400,300);time.sleep(.3);report['top_before_policy']=overlay()['down']-old['down']
        d.shell('settings','put','global','policy_control','immersive.status=*');time.sleep(2)
        old=overlay();d.shell('input','swipe',400,1,400,400,300);time.sleep(.3);after=overlay()
        report['top_after_policy']=after['down']-old['down'];report['overlay_top_reachable']=after['down']>old['down'] and after['up']>old['up']
        check('The overlay gesture leaves the separate foreground application resumed',fixture()['resumed'] and fixture()['pauses']==before['pauses'])
        d.screenshot(args.output/'overlay-policy.png');tree('overlay-policy',True)
        print('OBSERVE physical top edge reachable='+str(report['overlay_top_reachable']),flush=True)
        overlay('hide');home('after-policy')
        with tempfile.TemporaryDirectory(prefix='thinkos-foreign-audio-') as work:
            station=Station(8892,silent_mp3(work));threading.Thread(target=station.serve_forever,daemon=True).start()
            d.adb('reverse','tcp:8892','tcp:8892');reverse=True
            d.load('http://127.0.0.1:8892/stream.mp3',kind='audio',title='Silent focus probe')
            wait(lambda:d.state()['status']=='playing')
            open_fixture();check('Audio continues before the foreign app requests focus',d.state()['status']=='playing')
            state=fixture('focus_gain');check('The separate application obtains audio focus',state['focusResult']==1)
            wait(lambda:d.state()['status']=='paused');check('MediaEngine pauses on real external audio-focus gain')
            fixture('focus_abandon');time.sleep(.5);report['after_focus_abandon']=d.state()['status']
        report['preflight_complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:d.screenshot(args.output/'failure.png')
        except Exception:pass
        raise
    finally:
        cleanup=[]
        try:d.debug('media_stop')
        except Exception as error:cleanup.append('media: '+str(error))
        if station:station.close()
        if reverse:
            try:d.adb('reverse','--remove','tcp:8892')
            except Exception as error:cleanup.append('reverse: '+str(error))
        for package in owned:
            try:d.shell('am','force-stop',package);assert 'Success' in d.adb('uninstall',package,timeout=60).decode()
            except Exception as error:cleanup.append('fixture: '+str(error))
        try:
            for (scope,key),value in original.items():
                if d.shell('settings','get',scope,key).strip()!=value:
                    if value=='null':d.shell('settings','delete',scope,key)
                    else:d.shell('settings','put',scope,key,value)
            report['system_restored']=all(d.shell('settings','get',s,k).strip()==v for (s,k),v in original.items())
            assert report['system_restored']
        except Exception as error:cleanup.append('system: '+str(error))
        try:report.update(restore(journal,d))
        except Exception as error:cleanup.append('app: '+str(error))
        report['fixture_packages_removed']=all(not d.shell('pm','path',p,check=False).strip() for p in apks)
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
        if cleanup:report.update(cleanup_errors=cleanup,private_system_journal=str(recovery),private_app_journal=str(journal))
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        if not cleanup and report['fixture_packages_removed']:shutil.rmtree(recovery)
    assert not cleanup and report['media_volume_zero'] and report['fixture_packages_removed']


if __name__=='__main__':main()
