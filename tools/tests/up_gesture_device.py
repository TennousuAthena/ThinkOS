#!/usr/bin/env python3
"""APP-6 physical bottom-edge preflight and functional gestures, without performance sampling.

Requires ThinkOS in front and media volume zero. Keeps the volume at zero and
restores eight app files and debug UI overrides from a private durable journal.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'tools'), str(ROOT / 'tools/tests')]
from device_recovery import capture, restore, PATHS, OPTIONAL_PATHS
from device_app_files import read_app_file
from device_hierarchy import dump_hierarchy, node_bounds, visible_nodes
from device_touch import drag
from media_device import APP, Device
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin
from ui_walkthrough import broadcast_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/app/APP-6/device')
    parser.add_argument('--preflight-only', action='store_true')
    parser.add_argument('--receiver-only', action='store_true', help='APP-8 same-UID receiver checks; does not install a system module')
    parser.add_argument('--module', action='store_true', help='APP-8 physical external-app swipes; requires module 1.1 manually installed and rebooted')
    parser.add_argument('--cold-only', action='store_true', help='APP-8 cold-process receiver fallback through the debug same-UID fixture')
    parser.add_argument('--content-only', action='store_true', help='APP-6 list scrolling, TV bottom button and left-side brightness; media volume stays zero')
    args = parser.parse_args()
    assert sum((args.preflight_only,args.receiver_only,args.module,args.cold_only,args.content_only)) <= 1, 'Choose one probe mode'
    args.output.mkdir(parents=True, exist_ok=True)
    d = Device(args.serial)
    assert any(APP + '/' in line for line in d.shell('dumpsys', 'activity', 'activities').splitlines() if 'mResumedActivity' in line)
    assert re.search(r'volume is 0\b', d.shell('media', 'volume', '--stream', 3, '--get'))
    assert not d.shell('run-as', APP, 'ls', 'files/dev-plugins').strip(), 'Preserve existing development plugins'
    journal = capture(d, broadcast_json(d, 'ui_state'), paths=PATHS + OPTIONAL_PATHS)
    print('Private recovery journal: ' + str(journal), flush=True)
    installed = d.shell('pm', 'path', APP).strip().removeprefix('package:')
    report = {'complete': False, 'apk_sha256': d.shell('sha256sum', installed).split()[0], 'passed': [], 'samples': {},
              'receiver_only':args.receiver_only,'module':args.module,'cold_only':args.cold_only}
    d.start_log()
    temporary = tempfile.TemporaryDirectory(prefix='thinkos-up-test-')
    work = Path(temporary.name)
    video_dir = '/sdcard/Movies/' + work.name
    video_created = False
    probe_published = False
    content_published = False

    def wait(test, seconds=25):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            result = test()
            if result is not None and result is not False: return result
            time.sleep(.3)
        raise AssertionError('Timed out waiting for the physical-edge preflight')

    def ui(): return broadcast_json(d, 'ui_state')
    def stage(): return broadcast_json(d, 'stage_state')
    def page(key):
        wait(lambda: d.debug('page', value=key) and ui()['page'] == key)
        time.sleep(.7)
    def setting(key, value, scope='system'):
        d.debug('setting', key=key, value=str(value).lower() if isinstance(value, bool) else str(value), scope=scope)
    def check(name, ok=True):
        assert ok, name
        report['passed'].append(name); print('PASS ' + name, flush=True)
    def tree():
        remote = '/sdcard/thinkos-up-check.xml'
        try:
            dump_hierarchy(args.serial, remote, compressed=True)
            raw = d.shell('cat', remote); (args.output / 'last-ui.xml').write_text(raw)
            return ET.fromstring(raw)
        finally: d.shell('rm', '-f', remote)
    def find(text): return wait(lambda: next((n for n in visible_nodes(tree()) if text in (n.get('text'), n.get('content-desc'))), None))
    def tap(node):
        a, b, c, e = node_bounds(node); d.tap((a+c)//2, (b+e)//2)
    def back(): d.shell('input', 'keyevent', 'KEYCODE_BACK'); time.sleep(.5)
    def up(width, height, x=None):
        x = width//2 if x is None else x
        d.shell('input', 'swipe', x, height-3, x, int(height*.52), 250)
        wait(lambda: ui()['page'] == 'tiles.apps')
        wait(lambda: abs(stage()['translationY']) < 1)
    def reason(): return re.search(r'data="([^"]*)"', d.debug('benchmark_state'))[1].split('|')[6]
    def open_panel(width, height):
        d.shell('input', 'swipe', width//2, 3, width//2, int(height*.45), 250)
        find('亮度')
    def shot(name): d.screenshot(args.output / (name + '.png'))

    def content():
        nonlocal content_published
        bundle,_=package_plugin(ROOT/'tools/tests/fixtures/edge-content-probe',work/'content-package')
        publish_directory(bundle,args.serial);content_published=True
        setting('nav.up_enabled',True);setting('nav.up_edge_dp',24)
        def brightness():
            saved=json.loads(read_app_file(args.serial,'files/settings/local.json'))['system']
            return saved.get('display.brightness_max',100)
        density=int(re.findall(r'density:\s*(\d+)',d.shell('wm','density'))[-1])/160
        report['content']={}
        for name,orientation in (('portrait',1),('landscape',0)):
            d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',orientation)
            time.sleep(1);width,height=stage()['width'],stage()['height']
            page('edge-content-probe.list');find('Item 001')
            d.shell('input','swipe',width//2,int(height*.75),width//2,int(height*.25),450)
            titles=[n.get('text') for n in visible_nodes(tree()) if n.get('text','').startswith('Item ')]
            check(name+' a list scroll outside the edge stays with its content',ui()['page']=='edge-content-probe.list' and titles and 'Item 001' not in titles)
            page('edge-content-probe.tv')
            d.tap(width//2,height//2)  # Reveal the ordinary TV controls before testing their bottom-edge hit region.
            label=json.loads((ROOT/'shared/i18n/system/zh-Hans.json').read_text())['media.tv_list']
            node=find(label);a,b,c,e=node_bounds(node)
            x,y=(a+c)//2,e-2
            assert 0<height-y<=24*density,'The TV hit must be inside the bottom gesture strip'
            mark=d.log_mark();d.tap(x,y)
            wait(lambda:any('EDGE_TV_ACTION=list' in line for line in d.log_since(mark)))
            check(name+' a bottom-strip TV tap delivers its action once',sum('EDGE_TV_ACTION=list' in line for line in d.log_since(mark))==1 and ui()['page']=='edge-content-probe.tv')
            setting('display.brightness_max',80);wait(lambda:brightness()==80)
            d.shell('input','swipe',int(width*.25),int(height*.4),int(width*.25),int(height*.65),450)
            wait(lambda:brightness()<80)
            report['content'][name]={'tv_tap':[x,y],'edge_px':24*density,'list_titles_after_scroll':titles,'brightness_after':brightness()}
            check(name+' the left video gesture outside the edge still adjusts brightness',ui()['page']=='edge-content-probe.tv' and brightness()<80)
            shot(name+'-tv')

    def cold_return():
        def focus(): return d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
        wanted={'theme.mode':'dark','theme.night':False,'nav.up_enabled':True,'apps.android':True,'apps.hidden':'','apps.return_after_s':0}
        for key,value in wanted.items(): setting(key,value)
        defaults=json.loads((ROOT/'shared/schema/settings/system.json').read_text())['fields']
        def written():
            saved=json.loads(read_app_file(args.serial,'files/settings/local.json'))['system']
            return all(saved.get(key,defaults[key]['default'])==value for key,value in wanted.items())
        wait(written)
        page('home.main');page('apps.main');tap(find('计算器'));wait(lambda:'com.android.calculator2' in focus())
        old_pid=d.shell('pidof',APP).strip();d.shell('am','force-stop',APP)
        assert 'com.android.calculator2' in focus(),'The stopped-app fixture must leave the external app visible'
        mark=d.log_mark();d.debug('edge_return')
        wait(lambda:APP in focus());wait(lambda:ui()['page']=='home.main')
        wait(lambda:'mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities'))
        wait(lambda:any('edge return task=-1 moved=false' in line for line in d.log_since(mark)))
        new_pid=d.shell('pidof',APP).strip();report['cold_fallback']={'old_pid':old_pid,'new_pid':new_pid,'page':ui()['page']}
        check('a fresh process with no remembered task starts at Home and restores Lock Task',new_pid!=old_pid)
        shot('cold-receiver-home')

    def external_returns():
        def focus(): return d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
        def tasks(label):
            raw=d.shell('dumpsys','activity','activities');(args.output/(label+'-activities.txt')).write_text(raw)
            return sorted(set(re.findall(r'Hist #\d+: ActivityRecord\{[^\n]* '+re.escape(APP)+r'/\.MainActivity t(\d+)',raw)))
        def locked(): return 'mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities')
        def trigger(width,height):
            if args.module: d.shell('input','swipe',width//2,height-3,width//2,int(height*.55),200)
            else: d.debug('edge_return')
        def launch_calculator():
            page('home.main');page('apps.main');tap(find('计算器'))
            wait(lambda:'com.android.calculator2' in focus())
        module=d.shell('dumpsys','package','moe.kiwi.thinkos.immersive')
        match=re.search(r'\bversionName=([^\s]+)',module);report['installed_module_version']=match[1] if match else None
        if args.module: assert report['installed_module_version']=='1.1','Module 1.1 must be manually installed and the device restarted first'
        for key,value in (('nav.up_enabled',True),('apps.android',True),('apps.hidden',''),('apps.return_after_s',0),('panel.enabled',True)):
            setting(key,value)
        report['external_returns']={}
        for kind in ('application','home'):
            if kind=='home':
                launch_calculator();d.shell('input','keyevent','KEYCODE_HOME');wait(lambda:APP in focus() and ui()['page']=='home.main');wait(locked)
            for name,orientation in (('portrait',1),('landscape',0)):
                d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',orientation)
                time.sleep(1);width,height=stage()['width'],stage()['height']
                label=kind+'-'+name;before_tasks=tasks(label+'-before');before_task=broadcast_json(d,'kiosk_state')['taskId']
                launch_calculator();mark=d.log_mark();trigger(width,height)
                wait(lambda:APP in focus());wait(lambda:ui()['page']=='apps.main');wait(locked)
                after_task=broadcast_json(d,'kiosk_state')['taskId'];after_tasks=tasks(label+'-after')
                report['external_returns'][label]={'tasks_before':before_tasks,'tasks_after':after_tasks,'task_before':before_task,'task_after':after_task}
                check(label+' external return keeps the Android grid and its original task',after_task==before_task and after_tasks==before_tasks)
                check(label+' external return restores Lock Task',locked())
                wait(lambda:any('edge return task='+str(before_task)+' moved=true' in line for line in d.log_since(mark)))
                check(label+' returns by moving its task rather than launching another Activity')
                shot(label+'-return')
        width,height=stage()['width'],stage()['height']
        page('home.main');open_panel(width,height);tap(find('网络设置'))
        wait(lambda:'com.android.settings' in focus());trigger(width,height)
        wait(lambda:APP in focus());wait(lambda:ui()['page']=='home.main');wait(locked)
        check('the Wi-Fi page returns to the page underneath the panel')

        setting('nav.up_enabled',False);launch_calculator();trigger(width,height);time.sleep(1)
        check('the disabled receiver leaves an intentional Android app in front','com.android.calculator2' in focus())
        back();wait(lambda:APP in focus());setting('nav.up_enabled',True)
        page('cast.home');before=stage();d.debug('edge_return');time.sleep(.6)
        check('a signal while ThinkOS is already in front preserves its navigation layer',ui()['page']=='cast.home' and stage()['canGoBack']==before['canGoBack'])

        page('home.main');d.debug('maintenance');tap(find('打开安卓设置'));wait(lambda:'com.android.settings' in focus())
        policy=broadcast_json(d,'kiosk_state');assert not policy['foreign'] and not policy['recoverable']
        trigger(width,height);time.sleep(1)
        check('maintenance Android settings are not pulled back by the receiver','com.android.settings' in focus())
        back();wait(lambda:APP in focus());wait(locked)
        launch_calculator();assert broadcast_json(d,'kiosk_state')['foreign']
        direct=subprocess.run(d.base+['shell','am','broadcast','-n',APP+'/.shell.EdgeReturnReceiver'],capture_output=True,text=True,timeout=30)
        # API27 `am` reports result=0 even when BroadcastQueue refuses delivery.
        system_log=d.shell('logcat','-d','-s','BroadcastQueue:W','ActivityManager:W')
        denial=[line for line in system_log.splitlines() if '.shell.EdgeReturnReceiver' in line and 'not exported' in line and 'uid=2000' in line]
        raw=direct.stdout+direct.stderr+'\n'+'\n'.join(denial)+'\n';(args.output/'shell-receiver-denial.txt').write_text(raw)
        time.sleep(.6)
        check('Android refuses a shell broadcast to the non-exported receiver',bool(denial) and 'com.android.calculator2' in focus())
        back();wait(lambda:APP in focus());wait(locked)

    def functional():
        nonlocal video_created, probe_published
        setting('nav.up_enabled', True); setting('panel.enabled', True)
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'color=c=blue:s=320x180:r=10', '-t', '90',
                        '-c:v', 'libx264', '-preset', 'ultrafast', '-profile:v', 'baseline', '-pix_fmt', 'yuv420p', '-an',
                        str(work/'Up gesture test.mp4')], check=True, timeout=45)
        assert d.shell('sh','-c','if [ -e '+video_dir+' ]; then echo exists; fi').strip()!='exists', 'Temporary video directory must be unused'
        d.shell('mkdir', '-p', video_dir); video_created = True
        d.push(work/'Up gesture test.mp4', video_dir+'/Up gesture test.mp4')
        setting('video_dir', video_dir, 'player')
        report['gestures'] = {}
        for name, orientation in (('portrait', 1), ('landscape', 0)):
            d.shell('am', 'broadcast', '-n', APP+'/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', orientation)
            time.sleep(1); width, height = stage()['width'], stage()['height']
            for source in ('home.main', 'clock.face', 'tiles.main'):
                page(source); up(width, height)
                check(name+' '+source+' bottom swipe reaches launcher with its navigation reason', reason() == 'launcher' and not stage()['canGoBack'])
            page('cast.home'); up(width, height); back()
            check(name+' cast bottom swipe clears the return stack', ui()['page']=='tiles.apps' and not stage()['canGoBack'])
            page('home.main'); d.debug('settings', value='system'); wait(lambda: ui()['page']=='settings.form')
            up(width, height)
            check(name+' settings bottom swipe closes settings and clears the stack', not stage()['canGoBack'])

            page('clock.face'); before=stage()
            sample, raw=drag(d, (width//2,height-3), (width//2,height*.88), cancel=True, observe=stage)
            (args.output/(name+'-cancel-touch.log')).write_text(raw)
            wait(lambda: abs(stage()['translationY'])<1)
            report['gestures'][name+'-held-cancel']={'before':before,'held':sample,'after':stage()}
            check(name+' held up gesture builds no target page',sample['edge']=='UP' and sample['upProgress']>0 and sample['children']==before['children'])
            check(name+' cancelled short drag settles without navigating',ui()['page']=='clock.face')

            page('home.main')
            _, raw=drag(d,(width//2,height-3),(width//2,height*.9),observe=stage)
            (args.output/(name+'-short-touch.log')).write_text(raw)
            check(name+' short slow release stays on its original page',ui()['page']=='home.main')
            page('home.main');page('cast.home'); up(width,height,x=2)
            check(name+' bottom-left upward motion chooses launcher',ui()['page']=='tiles.apps')
            page('home.main');page('cast.home');d.shell('input','swipe',2,height-3,int(width*.65),height-3,300)
            wait(lambda:ui()['page']=='home.main')
            check(name+' bottom-left rightward motion chooses Back')

            page('home.main'); d.shell('input','swipe',int(width*.8),height-3,int(width*.2),height-3,300)
            wait(lambda:ui()['page']!='home.main')
            check(name+' horizontal motion in the bottom strip still flips the deck',reason()=='swipe')
            page('clock.face'); d.debug('variant',value='digital');time.sleep(.4)
            d.shell('input','swipe',width//2,int(height*.8),width//2,int(height*.4),300)
            wait(lambda:ui()['variant']!='digital')
            check(name+' a vertical swipe outside the strip retains variant switching',ui()['page']=='clock.face')

            page('aero.main');d.tap(40,height-20);wait(lambda:stage()['pageBack'])
            check(name+' a bottom-strip tap opens the Aero start menu');back()
            d.shell('input','swipe',width//2,height-3,width//2,height-3,650)
            wait(lambda:ui()['page']=='settings.form')
            check(name+' an undecided bottom press retains the 600ms settings long press')

            page('home.main');setting('nav.up_enabled',False)
            d.shell('input','swipe',width//2,height-3,width//2,int(height*.5),250);time.sleep(.7)
            check(name+' disabling up gestures leaves navigation to the page',ui()['page']=='home.main')
            setting('nav.up_enabled',True);page('tiles.apps')
            sample,raw=drag(d,(width//2,height-3),(width//2,height*.85),cancel=True,observe=stage)
            (args.output/(name+'-launcher-touch.log')).write_text(raw)
            check(name+' the launcher with an empty stack does not claim the bottom strip',sample['edge']=='IDLE' and sample['upProgress']==0)

            page('home.main');open_panel(width,height)
            d.shell('input','swipe',width//2,height-3,width//2,int(height*.5),250);time.sleep(.5)
            check(name+' an open control panel prevents launcher interception',ui()['page']=='home.main')
            back();page('home.main');open_panel(width,height);tap(find('应用'))
            wait(lambda:ui()['page']=='tiles.apps')
            check(name+' the panel apps button opens the launcher',reason()=='launcher')

            page('home.main');d.debug('preview_safe_mode',value='true')
            d.shell('input','swipe',width//2,height-3,width//2,int(height*.5),250);time.sleep(.6)
            check(name+' safe mode ignores the gesture without crashing',ui()['page']=='home.main')
            d.debug('preview_safe_mode',value='false')
            page('home.main');d.debug('locale',value='ar');up(width,height)
            check(name+' Arabic keeps the same physical bottom gesture');d.debug('locale',value='zh-CN')
            page('home.main');d.debug('motion_scale',value=0.0)
            assert not stage()['animatorsEnabled']
            d.shell('input','swipe',width//2,height-3,width//2,int(height*.5),250)
            immediate=stage();report['gestures'][name+'-animations-off']=immediate
            check(name+' disabled animations switch directly without a visible outgoing page',immediate['page']=='tiles.apps' and immediate['translationY']==0 and immediate['visibleChildren']==1)
            d.debug('motion_scale',value=1.0)

            page('home.main')
            sample,raw=drag(d,(width//2,height-3),(width//2,height*.55),chord=True,observe=stage)
            (args.output/(name+'-two-finger-touch.log')).write_text(raw)
            check(name+' a second pointer cancels an active up gesture',sample['edge']=='SWALLOW' and ui()['page']=='home.main')
            page('player.library');tap(find('视频'));tap(find('Up gesture test'))
            wait(lambda:ui()['page']=='player.video');wait(lambda:(d.state() or {}).get('status')=='playing')
            up(width,height);wait(lambda:(d.state() or {}).get('status')=='paused')
            check(name+' leaving the actual player video through launcher pauses playback')
            shot(name+'-launcher')

        # The hidden rescue chord still works; no maintenance action is selected.
        page('home.main')
        _,raw=drag(d,(25,25),(25,25),chord=True,hold_ms=5500,observe=lambda:time.sleep(5.2))
        (args.output/'rescue-touch.log').write_text(raw)
        find('维护');check('the top-left two-finger rescue hold still opens maintenance');back()

        source=work/'source';(source/'views').mkdir(parents=True)
        (source/'manifest.json').write_text(json.dumps({'id':'up-role-probe','name':'Role probe','version':'1.0.0','api':9,'main':'main.lua',
            'permissions':['system.apps'],'pages':[{'id':'blocked','title':'Blocked','view':'views/main.json','roles':['launcher']},
                                                  {'id':'allowed','title':'Marker','view':'views/main.json'}]}))
        (source/'views/main.json').write_text(json.dumps({'layout':'focus','slots':{'hero':{'type':'text','value':'Role marker'}}}))
        (source/'main.lua').write_text('tos.on("start", function() local ok,value=pcall(tos.apps.open,"launcher"); tos.log.info("LAUNCHER_DENIED="..tostring(not ok or not value)) end)\n')
        bundle,_=package_plugin(source,work/'package');publish_directory(bundle,args.serial);probe_published=True
        page('up-role-probe.allowed');d.debug('page',value='up-role-probe.blocked')
        check('a third-party launcher declaration is excluded from the runtime catalog',ui()['page']!='up-role-probe.blocked')
        wait(lambda:any('LAUNCHER_DENIED=true' in line for line in d.log_since(0)))
        check('a third-party caller cannot open the reserved launcher target')

    try:
        d.shell('am', 'start', '-W', '-n', APP + '/.MainActivity')
        d.debug('hold_attention', value='ENGAGED')
        d.debug('locale', value='zh-CN')
        d.debug('theme', value='DARK')
        d.debug('drift', x=0.0, y=0.0)
        for name, orientation in (('portrait', 1), ('landscape', 0)):
            d.shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', orientation)
            time.sleep(1)
            wait(lambda: d.debug('page', value='home.main') and broadcast_json(d, 'ui_state')['page'] == 'home.main')
            before = broadcast_json(d, 'stage_state')
            assert before['height'] > 0 and before['width'] > 0
            x, y = before['width'] // 2, before['height'] - 3
            d.shell('input', 'swipe', x, y, x, int(before['height'] * .7), 200)
            after = broadcast_json(d, 'stage_state')
            report['samples'][name] = {'before': before, 'after': after, 'physical_down': [x, y]}
            assert after['downCount'] == before['downCount'] + 1, 'The physical bottom touch never reached ThinkStage'
            assert abs(after['downX'] - x) < 1 and abs(after['downY'] - y) < 1, 'Bottom-edge coordinates were intercepted or shifted'
            report['passed'].append(name + ' physical bottom DOWN reaches ThinkStage unchanged')
            print('PASS ' + report['passed'][-1], flush=True)
        if args.content_only: content()
        elif args.cold_only: cold_return()
        elif args.receiver_only or args.module: external_returns()
        elif not args.preflight_only: functional()
        report['complete'] = True
    except Exception as error:
        report['failure'] = str(error)
        try: shot('failure')
        except Exception: pass
        raise
    finally:
        try:
            d.shell('am','force-stop',APP)
            if probe_published: remove_plugin('up-role-probe',args.serial)
            if content_published: remove_plugin('edge-content-probe',args.serial)
            if video_created: d.shell('rm','-rf',video_dir)
        except Exception as error: report['fixture_cleanup_error']=str(error)
        try: report.update(restore(journal, d))
        except Exception as error: report.update(recovery_error=str(error), private_recovery_journal=str(journal))
        (args.output / 'logcat.txt').write_text('\n'.join(d.log_since(0)) + '\n')
        d.stop_log()
        report['media_volume_zero'] = bool(re.search(r'volume is 0\b', d.shell('media', 'volume', '--stream', 3, '--get')))
        (args.output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
        temporary.cleanup()
    assert not report.get('recovery_error'), report.get('recovery_error')
    assert not report.get('fixture_cleanup_error'), report.get('fixture_cleanup_error')
    assert report['media_volume_zero']


if __name__ == '__main__': main()
