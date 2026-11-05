#!/usr/bin/env python3
"""APP-4 real non-focusable panel over a separate-UID silent video app.

Runs only shell.sh apply/restore from a private /data/local/tmp directory, with
its root-private recovery state there. Does not install a persistent module or
reboot. Never touches volume or mute controls; all sources contain silence.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import sys
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests'),str(ROOT/'tools/media')]
from device_external_fixture import fixture_apk,PACKAGE
from device_hierarchy import dump_hierarchy,node_bounds
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from ui_walkthrough import broadcast_json
from icy import Station,silent_mp3

PROFILE=(('global','policy_control'),('global','stay_on_while_plugged_in'),('system','screen_brightness'),('system','screen_brightness_mode'))
LOCALES={'zh-CN':'zh-Hans','en-US':'en','ar-EG':'ar','zh-TW':'zh-Hant','es-ES':'es','fr-FR':'fr','pt-BR':'pt','ru-RU':'ru','hi-IN':'hi','bn-BD':'bn','ur-PK':'ur'}


def main():
    os.environ.setdefault('ADB_MDNS','0')
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/app/APP-4/device')
    parser.add_argument('--all-locales',action='store_true',help='Optional extended language sweep; normal acceptance prioritizes Chinese')
    parser.add_argument('--home-signal',action='store_true',help='Exercise the private Home receiver through DUMP instead of module hardware; reports this limited scope')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    apk=fixture_apk();d=Device(args.serial)
    assert d.shell('getprop','ro.serialno').strip()=='HUA0BWUT' and d.shell('getprop','ro.build.version.sdk').strip()=='27'
    def focus():return d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    def zero():return bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
    assert APP+'/' in focus() and zero()
    assert broadcast_json(d,'media_state')['status']=='stopped'
    assert not d.shell('pm','path',PACKAGE,check=False).strip()
    assert not d.shell('run-as',APP,'ls','files/dev-plugins').strip()
    assert not d.shell('su','-c','ls -d /data/adb/modules/thinkos-shell 2>/dev/null',check=False).strip()
    assert d.shell('getprop','sys.thinkos.shell').strip() in ('','0'),'Preserve an active temporary shell session'
    assert b'tcp:8893' not in d.adb('reverse','--list')
    def appop():
        raw=d.shell('appops','get',APP,'SYSTEM_ALERT_WINDOW')
        match=re.search(r'(?m)^\s*SYSTEM_ALERT_WINDOW: (allow|ignore|deny|default)\b',raw)
        assert match or 'No operations' in raw,raw
        return match[1] if match else 'default'
    original={(scope,key):d.shell('settings','get',scope,key).strip() for scope,key in PROFILE}
    original_property=d.shell('getprop','sys.thinkos.shell').strip();original_appop=appop()
    journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS)
    private=Path(tempfile.mkdtemp(prefix='thinkos-foreign-panel-recovery-'))
    remote='/data/local/tmp/thinkos-shell-probe-'+uuid.uuid4().hex
    with os.fdopen(os.open(private/'system.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as stream:
        json.dump({'physical':'HUA0BWUT','app_journal':str(journal),'profile':[{'scope':s,'key':k,'value':v} for (s,k),v in original.items()],
                   'shell_property':original_property,'appop':original_appop,'remote_directory':remote,'owned_package':PACKAGE,'reverse':'tcp:8893'},stream)
        stream.flush();os.fsync(stream.fileno())
    (args.output/'recovery.json').write_text(json.dumps({'private_app_journal':str(journal),'private_system_journal':str(private)})+'\n')
    print('Private recovery journals: '+str(journal)+' '+str(private),flush=True)
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    report={'complete':False,'passed':[],'cases':{},'apk_sha256':d.shell('sha256sum',installed).split()[0],
            'shell_script_sha256':hashlib.sha256((ROOT/'system/thinkos-shell/shell.sh').read_bytes()).hexdigest(),
            'persistent_module_installed':False,'temporary_module_transaction':True}
    report['home_signal_fixture']=args.home_signal
    owns_apk=owns_remote=owns_reverse=False;station=None;d.start_log()
    def wait(test,seconds=25):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            result=test()
            if result is not None and result is not False:return result
            time.sleep(.3)
        raise AssertionError('Foreign panel did not reach the expected state')
    def check(name,value=True):
        assert value,name
        report['passed'].append(name);print('PASS '+name,flush=True)
    def fixture(command='state',**extras):
        argv=['am','broadcast','-n',PACKAGE+'/.FixtureReceiver','--es','command',command]
        for key,value in extras.items():argv+=['--ez' if isinstance(value,bool) else '--ei' if isinstance(value,int) else '--es',key,str(value).lower() if isinstance(value,bool) else str(value)]
        raw=d.shell(*argv);match=re.search(r'data="(\{.*\})"',raw);assert match,raw
        value=json.loads(match[1]);assert value;return value
    def panel():return broadcast_json(d,'foreign_panel')
    def setting(key,value):d.debug('setting',key=key,value=str(value).lower() if isinstance(value,bool) else str(value))
    def tree(name):
        path='/sdcard/thinkos-foreign-panel.xml'
        try:
            dump_hierarchy(args.serial,path,compressed=True,all_windows=True)
            raw=d.shell('cat',path);(args.output/(name+'.xml')).write_text(raw);return ET.fromstring(raw)
        finally:d.shell('rm','-f',path)
    def find(label,package=APP):
        nodes=[n for n in tree('last-ui').iter('node') if n.get('package')==package and label in (n.get('text'),n.get('content-desc')) and node_bounds(n)[2]>node_bounds(n)[0]]
        return max(nodes,key=lambda n:(n.get('clickable')=='true',n.get('content-desc')==label),default=None)
    def tap(node):
        a,b,c,e=node_bounds(node);assert a<c and b<e;d.tap((a+c)//2,(b+e)//2)
    def locked():return 'mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities')
    def home(name):
        if d.shell('getprop','sys.thinkos.shell').strip()=='2':
            if args.home_signal:d.debug('edge_return')
            else:
                screen=fixture();d.shell('input','swipe',screen['width']//2,screen['height']-2,screen['width']//2,int(screen['height']*.45),250)
            wait(lambda:APP+'/' in focus());wait(locked);wait(lambda:not panel()['attached'] and not panel()['leftAttached'])
            assert broadcast_json(d,'ui_state')['page']=='home.main'
            return
        node=next((n for n in tree(name+'-home').iter('node') if n.get('resource-id')=='com.android.systemui:id/home' and n.get('enabled')=='true'),None)
        assert node is not None,'The actual navigation Home must stay available';tap(node)
        wait(lambda:APP+'/' in focus());wait(locked);wait(lambda:not panel()['attached'])
    def page(key):d.debug('page',value=key);return broadcast_json(d,'ui_state')['page']==key
    def open_fixture():
        wait(lambda:page('apps.main'));tap(wait(lambda:find('外部应用测试')))
        wait(lambda:PACKAGE+'/' in focus())
    def start_video(orientation):
        fixture('orientation',value=orientation);time.sleep(.8);fixture('video_start')
        wait(lambda:fixture()['videoPlaying'] and fixture()['videoWidth']>0)
    def settle_legacy_bars(name):
        # --home-signal deliberately runs without the new framework hook. On
        # reverse landscape, testing the left strip can reveal Android's old
        # transient navigation bar, which intercepts the NEXT touch. Record
        # that limitation and isolate the app-window checks after it closes.
        raw=d.shell('dumpsys','window','policy')
        seen=re.findall(r'mTransientBar=(\w+)',raw)
        report.setdefault('legacy_transient_bar_samples',{})[name]=seen
        if seen!=['TRANSIENT_BAR_NONE','TRANSIENT_BAR_NONE']:
            (args.output/(name+'-legacy-bars.txt')).write_text(raw)
            wait(lambda:re.findall(r'mTransientBar=(\w+)',d.shell('dumpsys','window','policy'))==['TRANSIENT_BAR_NONE','TRANSIENT_BAR_NONE'],seconds=15)
    def pull():
        if args.home_signal:settle_legacy_bars('before-pull-'+str(len(report.get('legacy_transient_bar_samples',{}))))
        state=fixture();d.shell('input','swipe',state['width']//2,1,state['width']//2,int(state['height']*.42),300)
        wait(lambda:panel()['showing'] and panel()['progress']>.99)
    def close_by_outside():
        state=fixture();d.tap(state['width']//2,state['height']-110)
        wait(lambda:not panel()['showing']);wait(lambda:panel()['height']>0)
    def shell_action(action):return d.shell('su','-c',shlex.join(['sh',remote+'/shell.sh',action,remote+'/state']))
    def continues(before):
        first=fixture();time.sleep(1.15);last=fixture()
        result=last['resumed'] and last['pauses']==before['pauses'] and last['videoPlaying'] and last['videoWidth']>0 and (last['videoPosition']-first['videoPosition'])%6000>300
        report.setdefault('video_checks',[]).append({'before':before,'first':first,'last':last,'passed':result})
        return result
    try:
        assert 'Success' in d.adb('install',str(apk),timeout=120).decode();owns_apk=True
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('locale',value='zh-CN');d.debug('theme',value='DARK');d.debug('hold_attention',value='ENGAGED')
        d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',1)
        for key,value in (('apps.android',True),('apps.hidden',''),('apps.return_after_s',0),('panel.enabled',True),('panel.idle_s',15),('kiosk.lock',True)):setting(key,value)
        open_fixture();start_video(1);before=fixture()
        check('With no shell module the foreign edges stay absent',not panel()['attached'] and not panel()['leftAttached'] and not panel()['allowed'])
        d.shell('input','swipe',400,1,400,450,300)
        check('No-module top swipe leaves the ordinary app and silent video running',not panel()['attached'] and continues(before))
        home('without-module');check('Without the module physical Home returns and relocks',locked())

        d.shell('mkdir',remote);owns_remote=True;d.shell('chmod','700',remote)
        d.push(ROOT/'system/thinkos-shell/shell.sh',remote+'/shell.sh')
        open_fixture();start_video(1);ready_before=fixture()
        (args.output/'temporary-apply.log').write_text(shell_action('apply'))
        check('The production shell transaction readies only the temporary test session',d.shell('getprop','sys.thinkos.shell').strip()=='2' and appop()=='allow')
        wait(lambda:panel()['attached'] and panel()['leftAttached'])
        check('The fixed ready notification attaches both edges above an already-foreground app',continues(ready_before) and PACKAGE+'/' in focus())
        home('ready-notification')
        setting('nav.back_enabled',False);setting('nav.up_enabled',False)
        d.debug('settings',value='system');wait(lambda:broadcast_json(d,'ui_state')['page']=='settings.form')
        own=broadcast_json(d,'stage_state');d.shell('input','swipe',1,own['height']//2,int(own['width']*.6),own['height']//2,300)
        wait(lambda:broadcast_json(d,'ui_state')['page']=='home.main')
        check('Legacy disabled Back cannot disable required global navigation')
        wait(lambda:page('apps.main'));time.sleep(.5);own=broadcast_json(d,'stage_state')
        d.shell('input','swipe',own['width']//2,own['height']-3,own['width']//2,int(own['height']*.48),300)
        wait(lambda:broadcast_json(d,'ui_state')['page']=='home.main')
        check('Legacy disabled Up cannot disable required global Home')
        locales=list(LOCALES) if args.all_locales else ['zh-CN']
        for shape,orientation in (('portrait',1),('landscape',8)):
            open_fixture();start_video(orientation);wait(lambda:panel()['attached']);before=fixture()
            check(shape+' the narrow window leaves the separate app resumed',fixture()['resumed'] and not panel()['showing'] and panel()['height']<60)
            wait(lambda:panel()['leftAttached']);screen=fixture();touches=screen['touches'];count=panel()['leftTouches']
            d.shell('input','swipe',1,screen['height']//2,int(screen['width']*.55),screen['height']//2,300)
            check(shape+' the reserved left stream cannot also open an external app drawer',fixture()['touches']==touches and panel()['leftTouches']==count+1)
            if args.home_signal:settle_legacy_bars(shape+'-after-left')
            for locale in locales:
                fixture('video_start');wait(lambda:fixture()['videoPlaying'])
                d.debug('locale',value=locale);table=LOCALES[locale]
                system=json.loads((ROOT/'shared/i18n/system'/f'{table}.json').read_text())
                plugin=json.loads((ROOT/'plugins/panel/i18n'/f'{table}.json').read_text())
                pull();tap_node=wait(lambda:find(system['media_brightness']))
                labels=[plugin['settings'],plugin['apps'],plugin['network.footer'],system['media_volume']]
                check(shape+' '+locale+' real top pull shows the shared translated panel',all(find(label) is not None for label in labels))
                metrics=panel()['textMetrics'];assert set(metrics)=={'header','footer'}
                check(shape+' '+locale+' native date and network text fit without clipping or ellipsis',
                      metrics['header']['lines']==1 and all(m['layoutHeight']<=m['availableHeight'] and m['ellipsis']==0 for m in metrics.values()))
                check(shape+' '+locale+' panel keeps the foreign video advancing without an Activity pause',PACKAGE+'/' in focus() and continues(before))
                report['cases'][shape+'-'+locale]={'before':before,'during':fixture(),'panel':panel(),'brightness_bounds':node_bounds(tap_node)}
                tree(shape+'-'+locale);d.screenshot(args.output/(shape+'-'+locale+'.png'))
                screen=fixture();backs=screen['backPresses']
                d.shell('input','swipe',1,int(screen['height']*.42),int(screen['width']*.55),int(screen['height']*.42),300)
                wait(lambda:not panel()['showing'])
                check(shape+' '+locale+' left Back closes the external panel without navigating the app',fixture()['backPresses']==backs and continues(before))
                pull()
                close_by_outside()
                prior_touches=fixture()['touches'];screen=fixture()
                d.tap(int(screen['width']*.75),int(screen['height']*.75))
                wait(lambda:fixture()['touches']>prior_touches)
                check(shape+' '+locale+' outside tap returns to the narrow edge and external touches',not panel()['expanded'] and PACKAGE+'/' in focus())
            d.debug('locale',value='zh-CN');pull()
            slider=wait(lambda:find('亮度'));a,b,c,e=node_bounds(slider);density=float(d.shell('wm','density').strip().split()[-1])/160
            left=a+64*density;right=c-20*density;y=(b+e)//2
            d.shell('input','swipe',int(left+(right-left)*.25),y,int(left+(right-left)*.75),y,400)
            wait(lambda:panel()['brightness']>=.7)
            raw=d.shell('dumpsys','display');(args.output/(shape+'-backlight.txt')).write_text(raw)
            backlight=int(re.search(r'\bmScreenBrightness=(\d+)',raw)[1]);report['cases'][shape+'-brightness']={'cap':panel()['brightness'],'backlight':backlight}
            check(shape+' real brightness slider applies to the external display',.7<=panel()['brightness']<=.9 and abs(backlight-round(panel()['brightness']*255))<=2 and continues(before))
            close_by_outside();home(shape+'-done')
        # The actual controls still address the one OS MediaEngine while the
        # other application's independent, silent video keeps its Activity.
        with tempfile.TemporaryDirectory(prefix='thinkos-panel-audio-') as work:
            station=Station(8893,silent_mp3(work));threading.Thread(target=station.serve_forever,daemon=True).start()
            d.adb('reverse','tcp:8893','tcp:8893');owns_reverse=True
            open_fixture();start_video(1);before=fixture()
            d.load('http://127.0.0.1:8893/stream.mp3',kind='audio',title='Silent panel source')
            wait(lambda:d.state()['status']=='playing');pull();tap(wait(lambda:find('暂停')));wait(lambda:d.state()['status']=='paused')
            check('External panel pauses the existing OS media session',continues(before))
            tap(wait(lambda:find('播放')));wait(lambda:d.state()['status']=='playing')
            check('External panel resumes the same OS media session at media volume zero',zero() and continues(before))
            d.debug('media_stop');close_by_outside()
        station.close();station=None;d.adb('reverse','--remove','tcp:8893');owns_reverse=False
        setting('panel.idle_s',5);pull();wait(lambda:not panel()['showing'],seconds=12)
        check('The open external panel closes on the real configured idle deadline',panel()['attached'] and not panel()['expanded'] and continues(before))
        setting('panel.idle_s',15);pull();tap(wait(lambda:find('应用')))
        wait(lambda:APP+'/' in focus());wait(locked)
        check('The panel Applications button returns to the launcher in ThinkOS',broadcast_json(d,'ui_state')['page']=='tiles.apps' and not panel()['attached'])
        open_fixture();start_video(1);pull();tap(wait(lambda:find('设置')));wait(lambda:APP+'/' in focus());wait(locked)
        check('The panel Settings button shows native settings and relocks',broadcast_json(d,'ui_state')['page']=='settings.form' and not panel()['attached'])
        wait(lambda:page('apps.main'));open_fixture();start_video(1);pull();tap(wait(lambda:find('网络设置')))
        wait(lambda:'com.android.settings/' in focus())
        check('The external panel network action opens the real Android Wi-Fi screen',broadcast_json(d,'kiosk_state')['foreign'] and panel()['attached'])
        home('network-done')

        d.debug('maintenance');tap(wait(lambda:find('打开安卓设置')))
        wait(lambda:'com.android.settings/' in focus());wait(lambda:panel()['attached'] and panel()['leftAttached'])
        maintenance=broadcast_json(d,'kiosk_state');pull()
        check('Global control edges also work above maintenance Android settings',not maintenance['foreign'] and not maintenance['recoverable'] and 'com.android.settings/' in focus())
        home('maintenance-done')
        check('Explicit global Home leaves maintenance and clears the stack',broadcast_json(d,'ui_state')['page']=='home.main' and not broadcast_json(d,'stage_state')['canGoBack'])

        open_fixture();start_video(1);before=fixture();pull()
        d.shell('appops','set',APP,'SYSTEM_ALERT_WINDOW','ignore');wait(lambda:not panel()['attached'])
        check('Revoking overlay permission removes both windows without pausing the foreign app',not panel()['leftAttached'] and continues(before))
        d.shell('appops','set',APP,'SYSTEM_ALERT_WINDOW','allow');wait(lambda:panel()['attached'])
        check('Restoring the permission restores only the narrow edge',not panel()['showing'] and panel()['height']>0)
        policy_mark=d.log_mark();setting('panel.enabled',False);wait(lambda:not panel()['attached'])
        check('Disabling the optional panel preserves the required global Back edge',panel()['leftAttached'] and PACKAGE+'/' in focus() and continues(before))
        setting('panel.enabled',True);wait(lambda:panel()['attached'])
        check('Changing the external panel policy keeps Lock Task off without a hidden-Activity relock error',
              'mLockTaskModeState=NONE' in d.shell('dumpsys','activity','activities') and not any('Unable to apply kiosk policy' in line for line in d.log_since(policy_mark)))
        (args.output/'temporary-restore.log').write_text(shell_action('restore'));wait(lambda:not panel()['attached'])
        check('Restoring the temporary module profile removes both external edges',not panel()['leftAttached'] and d.shell('getprop','sys.thinkos.shell').strip()==original_property and appop()==original_appop)
        home('restored-home');check('After module removal Home returns and Lock Task still works',locked())
        report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:d.screenshot(args.output/'failure.png');tree('failure')
        except Exception:pass
        raise
    finally:
        cleanup=[]
        if owns_remote:
            try:shell_action('restore')
            except Exception as error:cleanup.append('shell profile: '+str(error))
        try:d.debug('media_stop')
        except Exception as error:cleanup.append('media: '+str(error))
        if station:station.close()
        if owns_reverse:
            try:d.adb('reverse','--remove','tcp:8893')
            except Exception as error:cleanup.append('reverse: '+str(error))
        if owns_apk:
            try:d.shell('am','force-stop',PACKAGE);assert 'Success' in d.adb('uninstall',PACKAGE,timeout=60).decode()
            except Exception as error:cleanup.append('fixture: '+str(error))
        try:
            report['system_restored']=all(d.shell('settings','get',scope,key).strip()==value for (scope,key),value in original.items())
            report['module_profile_restored']=d.shell('getprop','sys.thinkos.shell').strip()==original_property and appop()==original_appop
            assert report['system_restored'] and report['module_profile_restored']
        except Exception as error:cleanup.append('system verification: '+str(error))
        if owns_remote and not cleanup:
            try:d.shell('rm','-f',remote+'/shell.sh');d.shell('rmdir',remote)
            except Exception as error:cleanup.append('temporary directory: '+str(error))
        try:report.update(restore(journal,d))
        except Exception as error:cleanup.append('app restore: '+str(error))
        report['fixture_removed']=not d.shell('pm','path',PACKAGE,check=False).strip();report['media_volume_zero']=zero()
        if cleanup:report.update(cleanup_errors=cleanup,private_app_journal=str(journal),private_system_journal=str(private))
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        if not cleanup and report['fixture_removed']:shutil.rmtree(private)
    assert not cleanup and report['fixture_removed'] and report['media_volume_zero']


if __name__=='__main__':main()
