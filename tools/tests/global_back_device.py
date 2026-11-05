#!/usr/bin/env python3
"""ADR-046 real API27 Back emitter/permission boundary and private Home receiver.

Runs the exact module emitter through temporary app_process code as shell UID
and the ordinary debug-app UID. Does not load/install a system module or claim
that the framework's gesture hook or global bar suppression is active.
--native-gestures adds actual physical edges after a HUMAN has installed both
modules and rebooted; it never installs modules or changes their policy.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
from device_back_probe import back_probe_jar
from device_external_fixture import fixture_apk,PACKAGE
from device_hierarchy import dump_hierarchy,node_bounds,visible_nodes
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from ui_walkthrough import broadcast_json


def main():
    os.environ.setdefault('ADB_MDNS','0');parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/app/APP-8/global/back-api')
    parser.add_argument('--native-gestures',action='store_true',help='Require the manually loaded v1.2 + shell protocol2 and use physical global Back/Home')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    jar=back_probe_jar();apk=fixture_apk();d=Device(args.serial)
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT'
    def focus():return d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    assert APP+'/' in focus() and re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    assert broadcast_json(d,'media_state')['status']=='stopped'
    assert not d.shell('pm','path',PACKAGE,check=False).strip()
    if args.native_gestures:
        installed_module=d.shell('dumpsys','package','moe.kiwi.thinkos.immersive')
        version=re.search(r'\bversionCode=(\d+)',installed_module)
        assert version and int(version[1])>=3,'Human module update/reboot required; no module is installed by this driver'
        assert d.shell('getprop','sys.thinkos.shell').strip()=='2','Human shell installation/reboot required'
        assert 'immersive.full=*' in d.shell('settings','get','global','policy_control').strip().split(':')
        assert re.search(r'(?m)^\s*SYSTEM_ALERT_WINDOW: allow\b',d.shell('appops','get',APP,'SYSTEM_ALERT_WINDOW'))
    journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
    (args.output/'recovery.json').write_text(json.dumps({'private_journal':str(journal)})+'\n')
    print('Private recovery journal: '+str(journal),flush=True)
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    report={'complete':False,'passed':[],'apk_sha256':d.shell('sha256sum',installed).split()[0],
            'system_hook_loaded':False,'global_bars_verified':False,'home_receiver_fixture':not args.native_gestures,
            'native_gestures_requested':args.native_gestures}
    remote='/data/local/tmp/thinkos-fixed-back-'+uuid.uuid4().hex+'.jar';owns_apk=owns_jar=False;d.start_log()
    def wait(test,seconds=30):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            value=test()
            if value is not None and value is not False:return value
            time.sleep(.25)
        raise AssertionError('Global Back/Home API did not reach the expected state')
    def check(name,ok=True):
        assert ok,name
        report['passed'].append(name);print('PASS '+name,flush=True)
    def fixture(command='state',**extras):
        values=['am','broadcast','-n',PACKAGE+'/.FixtureReceiver','--es','command',command]
        for key,value in extras.items():values+=['--ez' if isinstance(value,bool) else '--ei' if isinstance(value,int) else '--es',key,str(value).lower() if isinstance(value,bool) else str(value)]
        raw=d.shell(*values);match=re.search(r'data="(\{.*\})"',raw);assert match,raw
        state=json.loads(match[1]);assert state;return state
    def listing():d.debug('page',value='apps.main');return broadcast_json(d,'ui_state')['page']=='apps.main'
    def button(label):
        path='/sdcard/thinkos-global-back.xml'
        try:
            dump_hierarchy(args.serial,path,compressed=True);root=ET.fromstring(d.shell('cat',path))
            return next((n for n in visible_nodes(root) if label in (n.get('content-desc'),n.get('text'))),None)
        finally:d.shell('rm','-f',path)
    def tap(node):
        a,b,c,e=node_bounds(node);d.tap((a+c)//2,(b+e)//2)
    def open_fixture():
        wait(listing)
        tap(wait(lambda:button('外部应用测试')));wait(lambda:PACKAGE+'/' in focus())
    def probe(as_app):
        command='CLASSPATH='+shlex.quote(remote)+' app_process /system/bin ThinkBackProbe'
        raw=d.shell('run-as',APP,'sh','-c',command) if as_app else d.shell('sh','-c',command)
        (args.output/('ordinary-app-probe.txt' if as_app else 'shell-probe.txt')).write_text(raw)
        match=re.search(r'THINKOS_BACK_PROBE (\{.*\})',raw);assert match,raw
        return json.loads(match[1])
    def home():
        if args.native_gestures:
            screen=fixture();d.shell('input','swipe',screen['width']//2,screen['height']-2,screen['width']//2,int(screen['height']*.45),250)
        else:d.debug('edge_return')
        wait(lambda:APP+'/' in focus());wait(lambda:broadcast_json(d,'ui_state')['page']=='home.main')
        wait(lambda:'mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities'))
    def bars(name):
        raw=d.shell('dumpsys','window','policy');(args.output/(name+'-policy.txt')).write_text(raw)
        transient=re.findall(r'mTransientBar=(\w+)',raw);states=re.findall(r'\bmState=(WINDOW_STATE_\w+)',raw)
        flags=re.search(r'\bmLastSystemUiFlags=0x([0-9a-fA-F]+)',raw)
        assert len(transient)==2 and set(transient)=={'TRANSIENT_BAR_NONE'},name+' shows transient Android bars'
        assert len(states)==2 and set(states)=={'WINDOW_STATE_HIDDEN'},name+' shows ordinary Android bars'
        assert flags and int(flags[1],16)&0x1006==0x1006,name+' lost the global hidden flags'
        d.screenshot(args.output/(name+'.png'))
    try:
        assert 'Success' in d.adb('install',str(apk),timeout=120).decode();owns_apk=True
        d.push(jar,remote);owns_jar=True;d.shell('chmod','644',remote)
        d.shell('am','start','-W','-n',APP+'/.MainActivity');d.debug('locale',value='zh-CN');d.debug('hold_attention',value='ENGAGED')
        d.debug('setting',key='apps.android',value='true');d.debug('setting',key='apps.hidden',value='');d.debug('setting',key='apps.return_after_s',value='0')
        d.debug('setting',key='nav.up_enabled',value='true')
        open_fixture();fixture('ignore_back',value=True);before=fixture()['backPresses'];task=broadcast_json(d,'kiosk_state')['taskId']
        report['shell_probe']=probe(False)
        wait(lambda:fixture()['backPresses']==before+1)
        check('The production emitter sends exactly one real Back through the API27 input service',report['shell_probe']['uid']==2000 and report['shell_probe']['accepted'] and PACKAGE+'/' in focus())
        before=fixture()['backPresses'];report['run_as_thinkos_probe']=probe(True);time.sleep(.7)
        check('An asynchronous acceptance from the unprivileged UID does not deliver Back into the foreign app',fixture()['backPresses']==before)
        if args.native_gestures:
            for shape,orientation in (('portrait',1),('landscape',8)):
                fixture('orientation',value=orientation);fixture('video_start');wait(lambda:fixture()['videoPlaying'])
                wait(lambda:broadcast_json(d,'foreign_panel')['attached']);time.sleep(.8)
                screen=fixture();backs=screen['backPresses'];pauses=screen['pauses'];pid=d.shell('pidof',APP).strip()
                d.shell('input','swipe',1,screen['height']//2,int(screen['width']*.55),screen['height']//2,250)
                wait(lambda:fixture()['backPresses']==backs+1);time.sleep(.4)
                check(shape+' native physical left edge sends exactly one Back',fixture()['backPresses']==backs+1)
                bars(shape+'-external-back')
                d.shell('input','swipe',screen['width']//2,1,screen['width']//2,int(screen['height']*.42),250)
                wait(lambda:broadcast_json(d,'foreign_panel')['progress']>.99)
                before_video=fixture()['videoPosition'];time.sleep(1.1)
                check(shape+' native top edge opens the non-focusable panel over advancing video',fixture()['pauses']==pauses and (fixture()['videoPosition']-before_video)%6000>300)
                bars(shape+'-external-panel')
                d.shell('input','swipe',1,screen['height']//2,int(screen['width']*.55),screen['height']//2,250)
                wait(lambda:not broadcast_json(d,'foreign_panel')['showing'])
                check(shape+' native left Back closes only the overlay panel',fixture()['backPresses']==backs+1)
                d.shell('input','swipe',screen['width']//2,1,screen['width']//2,int(screen['height']*.42),250)
                wait(lambda:broadcast_json(d,'foreign_panel')['progress']>.99);home()
                check(shape+' native bottom Home leaves the expanded foreign panel and reuses ThinkOS',d.shell('pidof',APP).strip()==pid and broadcast_json(d,'kiosk_state')['taskId']==task and not broadcast_json(d,'stage_state')['canGoBack'])
                bars(shape+'-native-home');open_fixture()
            report['system_hook_loaded']=True
            report['sampled_global_bars_verified']=True
            report['remaining_native_acceptance']=['IME and permission-window bar visibility/order','physical corner gestures']
        home();check('The warm private Home receiver clears the app stack and keeps its task',broadcast_json(d,'kiosk_state')['taskId']==task and not broadcast_json(d,'stage_state')['canGoBack'])
        d.debug('settings',value='system');wait(lambda:broadcast_json(d,'ui_state')['page']=='settings.form' and broadcast_json(d,'stage_state')['canGoBack'])
        report['ordinary_app_probe']=fixture('input_probe',value=remote)['inputProbe'];time.sleep(1)
        ordinary=report['ordinary_app_probe']
        raw=d.shell('logcat','-d','-s','InputDispatcher:W','InputManager:E')
        denials=[line for line in raw.splitlines() if 'Permission denied: injecting event' in line and re.search(r'\buid '+str(ordinary['uid'])+r'\b',line) and APP+'/' in line]
        (args.output/'cross-app-denial.txt').write_text('\n'.join(denials)+'\n')
        report['cross_app_denied_events']=len(denials);report['cross_app_target']=APP
        check('The independent ordinary Activity cannot deliver Back into ThinkOS',not ordinary['deviceOwner'] and not ordinary['hasInjectPermission'] and len(denials)==2 and broadcast_json(d,'ui_state')['page']=='settings.form' and broadcast_json(d,'stage_state')['canGoBack'])
        home()
        base=focus();d.debug('maintenance');tap(wait(lambda:button('查看诊断')))
        wait(lambda:button('设备诊断'))
        home();wait(lambda:focus()==base)
        check('Explicit Home closes the native diagnostic dialog above the home page')
        d.debug('maintenance');tap(wait(lambda:button('打开安卓设置')));wait(lambda:'com.android.settings/' in focus())
        assert not broadcast_json(d,'kiosk_state')['foreign'] and not broadcast_json(d,'kiosk_state')['recoverable']
        if args.native_gestures:bars('android-maintenance-settings')
        home();check('An explicit Home signal also leaves maintenance Android settings and relocks')
        open_fixture();old=d.shell('pidof',APP).strip();d.shell('am','force-stop',APP);assert PACKAGE+'/' in focus()
        home();check('The cold private Home receiver starts at Home and relocks',d.shell('pidof',APP).strip()!=old)
        report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:d.screenshot(args.output/'failure.png')
        except Exception:pass
        raise
    finally:
        cleanup=[]
        if owns_apk:
            try:d.shell('am','force-stop',PACKAGE);assert 'Success' in d.adb('uninstall',PACKAGE,timeout=60).decode()
            except Exception as error:cleanup.append('fixture: '+str(error))
        if owns_jar:
            try:d.shell('rm','-f',remote)
            except Exception as error:cleanup.append('jar: '+str(error))
        try:report.update(restore(journal,d))
        except Exception as error:cleanup.append('app: '+str(error))
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
        if cleanup:report.update(cleanup_errors=cleanup,private_journal=str(journal))
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    assert not cleanup and report['media_volume_zero']


if __name__=='__main__':main()
