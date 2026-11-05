#!/usr/bin/env python3
"""APP-1/5/7 Android grid, permissions, package refresh and idle return.

Requires media volume zero. Restores settings/navigation from a private durable
journal and restores the calculator's original enabled state. No modules, reboot,
default launcher, Device Owner or audio levels are changed.
--scroll-only is a separate performance run: announce it after functional checks.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
from device_hierarchy import dump_hierarchy,node_bounds,visible_nodes
from device_app_files import read_app_file
from device_recovery import capture,restore,PATHS,OPTIONAL_PATHS
from media_device import APP,Device
from package_plugins import package_plugin
from plugin_push import publish_directory,remove_plugin
from ui_walkthrough import broadcast_json

CALCULATOR='com.android.calculator2'
PROBE='apps-denied-probe'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL','HUA0BWUT'))
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/app/APP-5/device')
    parser.add_argument('--scroll-only',action='store_true',help='Announced 120-second grid-probe scrolling performance')
    parser.add_argument('--grid-only',action='store_true',help='Only the 100-item grid tap/scroll/recycling functional checks')
    parser.add_argument('--catalog-only',action='store_true',help='Only check both API catalog kinds and the Android switch')
    parser.add_argument('--return-only',action='store_true',help='APP-7: real 70-second Back/Home/Wi-Fi returns and watchdog expiry; no performance measurements')
    parser.add_argument('--return-case',choices=('all','watchdog'),default='all')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    assert sum((args.scroll_only,args.grid_only,args.catalog_only,args.return_only))<=1, 'Choose one probe mode'
    d=Device(args.serial)
    assert any(APP+'/' in line for line in d.shell('dumpsys','activity','activities').splitlines() if 'mResumedActivity' in line)
    assert re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get'))
    assert not d.shell('run-as',APP,'ls','files/dev-plugins').strip(),'Preserve existing development plugins'
    installed=d.shell('pm','path',APP).strip().removeprefix('package:')
    apk_hash=d.shell('sha256sum',installed).split()[0]
    journal=capture(d,broadcast_json(d,'ui_state'),paths=PATHS+OPTIONAL_PATHS if args.return_only else PATHS)
    print('Private recovery journal: '+str(journal),flush=True)
    report={'complete':False,'passed':[],'apk_sha256':apk_hash,'screens':{},'scroll_only':args.scroll_only,'grid_only':args.grid_only,'return_only':args.return_only}
    package_original=None
    d.start_log()

    def wait(test,seconds=20):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            value=test()
            if value is not None and value is not False:return value
            time.sleep(.3)
        raise AssertionError('Timed out waiting for application UI')
    def tree():
        remote='/sdcard/thinkos-apps-check.xml'
        try:
            dump_hierarchy(args.serial,remote,compressed=True)
            raw=d.shell('cat',remote);(args.output/'last-ui.xml').write_text(raw)
            return ET.fromstring(raw)
        finally:d.shell('rm','-f',remote)
    def grid(root=None):
        return next((n for n in (root if root is not None else tree()).iter('node') if n.get('class')=='android.widget.GridView'),None)
    def cells():
        root=tree();body=grid(root)
        if body is None:return []
        visible=set(visible_nodes(root))
        return [n for n in body.iter('node') if n in visible and n.get('content-desc') and n.get('class')!='android.widget.GridView']
    def titles():return [n.get('content-desc') for n in cells()]
    def find(text):return wait(lambda:next((n for n in visible_nodes(tree()) if text in (n.get('text'),n.get('content-desc'))),None))
    def tap(node):
        a,b,c,e=node_bounds(node);d.tap((a+c)//2,(b+e)//2);time.sleep(.4)
    def back():d.shell('input','keyevent','KEYCODE_BACK');time.sleep(.5)
    def page(id):
        until=time.monotonic()+25
        while time.monotonic()<until:
            d.debug('page',value=id)
            if broadcast_json(d,'ui_state')['page']==id:time.sleep(.5);return
            time.sleep(.3)
        raise AssertionError('Page did not become available: '+id)
    def setting(key,value):d.debug('setting',key=key,value=str(value).lower() if isinstance(value,bool) else str(value))
    def check(name,ok=True):
        assert ok,name
        report['passed'].append(name);print('PASS '+name,flush=True)
    def orient(portrait):
        d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','orientation','--ei','value',1 if portrait else 0)
        time.sleep(.8)
    def shot(name):
        d.screenshot(args.output/(name+'.png'))
        (args.output/(name+'.xml')).write_text(ET.tostring(tree(),encoding='unicode'))
    def focused():return d.shell('dumpsys','window','windows').split('mCurrentFocus=')[-1].splitlines()[0]
    def return_probe():
        def state():
            match=re.search(r'data="([^"]*)"',d.debug('benchmark_state'))
            assert match,'Missing actual attention state'
            values=match[1].split('|')
            return dict(zip(('page','attention','theme','orientation','pid','touches','reason','lua_bytes','generation'),values))
        def activities(name):
            raw=d.shell('dumpsys','activity','activities')
            (args.output/(name+'-activities.txt')).write_text(raw)
            found=re.findall(r'Hist #\d+: ActivityRecord\{[^\n]* '+re.escape(APP)+r'/\.MainActivity t(\d+)',raw)
            return sorted(set(found))
        def brightness():
            raw=d.shell('dumpsys','window','windows')
            focus=re.search(r'mCurrentFocus=Window\{(\S+)',raw)
            assert focus,'No focused window'
            block=re.search(r'(?ms)^  Window #\d+ Window\{'+re.escape(focus[1])+r'[^\n]*MainActivity[^\n]*\}:\n(.*?)(?=^  Window #|\Z)',raw)
            assert block,'ThinkOS is not the focused window'
            return float(re.search(r'\bsbrt=([0-9.]+)',block[1])[1])
        def locked():return 'mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities')
        def presence_off():
            d.shell('am','broadcast','-n',APP+'/.DebugReceiver','--es','command','hold_presence','--ez','value','false')
        def setup():
            d.debug('hold_attention',value='ENGAGED');presence_off()
            page('apps.main');find('计算器')
        def leave_calculator():
            tap(find('计算器'));wait(lambda:CALCULATOR in focused())
            began=time.monotonic();presence_off();d.debug('hold_attention')
            return began
        def sleep_outside(began,package):
            # This is the feature's real idle deadline, not a performance run or a forced ASLEEP state.
            next_note=20
            while time.monotonic()-began<70:
                elapsed=time.monotonic()-began
                assert package in focused(),'External app was covered before the user returned'
                if elapsed>=next_note:
                    print('WAIT external idle '+str(int(elapsed))+'s; '+state()['attention'],flush=True);next_note+=20
                time.sleep(min(2,max(.05,70-elapsed)))
            value=state()
            assert value['attention']=='ASLEEP',value
            assert package in focused()
            return value
        def returned(name,key,expected,began,before_brightness,before_tasks,allow_home=False):
            before=state();d.shell('input','keyevent',key)
            wait(lambda:APP in focused());wait(lambda:state()['page']==expected);wait(locked)
            after=state()
            # Brightness deliberately eases from ASLEEP over Motion.THEME (2 s).
            wait(lambda:abs(brightness()-before_brightness)<.03,seconds=4)
            after_brightness=brightness();tasks=activities(name)
            report['returns'][name]={'outside_seconds':time.monotonic()-began,'before':before,'after':after,
                'brightness_before':before_brightness,'brightness_after':after_brightness,'tasks_before':before_tasks,'tasks_after':tasks}
            check(name+' returns to '+expected+' with live attention and normal brightness',after['attention']=='ENGAGED' and abs(after_brightness-before_brightness)<.03)
            check(name+' restores Lock Task',locked())
            if allow_home:
                check('the first Home adds at most its separate HOME task',len(tasks)<=len(before_tasks)+1)
            else:check(name+' does not add a MainActivity',tasks==before_tasks)
            shot(name);return tasks

        resolver=d.shell('cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
        assert APP+'/.MainActivity' in resolver,'Preserve the existing default launcher; this probe requires ThinkOS already selected'
        report['home_resolver']=resolver.strip();report['returns']={}
        configured=(('apps.android',True),('apps.hidden',''),('apps.return_after_s',0),
                          ('attention.engaged_idle_s',5),('attention.awake_to_ambient_s',30),('attention.to_asleep_s',60),
                          ('attention.ambient_page_after_s',60),('panel.enabled',True),('kiosk.lock',True))
        for key,value in configured:
            setting(key,value)
        fields=json.loads((ROOT/'shared/schema/settings/system.json').read_text())['fields']
        def configured_settings():
            saved=json.loads(read_app_file(args.serial,'files/settings/local.json'))['system']
            values={key:saved.get(key,fields[key]['default']) for key,_ in configured}
            return values if all(values[key]==value for key,value in configured) else None
        effective=wait(configured_settings,seconds=8)
        report['test_settings']=effective
        report['timing_settings_verified']=True
        d.debug('lux',value=10000.0)
        setup();tasks=activities('initial-application');report['initial_tasks']=tasks
        assert tasks,'No MainActivity found'
        manual_cases=(('application-back','KEYCODE_BACK','apps.main'),('application-home','KEYCODE_HOME','home.main'),
                      ('home-back','KEYCODE_BACK','apps.main'),('home-home','KEYCODE_HOME','home.main'))
        for name,key,expected in manual_cases if args.return_case=='all' else ():
            setup();before_brightness=brightness();before_tasks=activities(name+'-before')
            began=leave_calculator();sleep_outside(began,CALCULATOR)
            tasks=returned(name,key,expected,began,before_brightness,before_tasks,allow_home=name=='application-home')

        if args.return_case=='all':
            d.debug('hold_attention',value='ENGAGED');presence_off();page('tiles.apps')
            before_brightness=brightness();before_tasks=activities('wifi-before')
            root=tree();a,b,c,e=node_bounds(root.find('node'))
            d.shell('input','swipe',(a+c)//2,b+6,(a+c)//2,int(e*.45),300)
            footer=json.loads((ROOT/'plugins/panel/i18n/zh-Hans.json').read_text())['network.footer']
            tap(find(footer));wait(lambda:'com.android.settings' in focused())
            began=time.monotonic();presence_off();d.debug('hold_attention');sleep_outside(began,'com.android.settings')
            returned('wifi-back','KEYCODE_BACK','tiles.apps',began,before_brightness,before_tasks)

        setting('apps.return_after_s',60);setup();before_tasks=activities('watchdog-before');began=leave_calculator()
        wait(lambda:APP in focused(),seconds=100)
        report['watchdog_foreground']={'seconds':time.monotonic()-began,'state':state()}
        wait(lambda:state()['attention']=='ASLEEP' and state()['page']=='clock.face')
        after=state();elapsed=time.monotonic()-began;tasks=activities('watchdog-after')
        report['watchdog']={'seconds':elapsed,'state':after,'tasks_before':before_tasks,'tasks_after':tasks,'brightness':brightness()}
        check('watchdog expiry returns without waking and follows the normal ASLEEP clock route',55<=elapsed<=100 and brightness()<.1)
        check('watchdog return does not add a MainActivity',tasks==before_tasks)
        wait(locked);shot('watchdog-asleep')
    def package_state():
        raw=d.shell('dumpsys','package',CALCULATOR)
        match=re.search(r'User 0:.*?\benabled=(\d+)',raw)
        assert match,'Calculator is missing or its enabled state is unavailable'
        return int(match[1])
    def catalog_counts(mark,android):
        for line in reversed(d.log_since(mark)):
            found=re.search(r'apps catalog pages=(\d+) android=(\d+)',line)
            if found and int(found[2])==android:return {'page':int(found[1]),'android':int(found[2])}
        return None
    def catalog_probe():
        setting('apps.android',True);setting('apps.hidden','')
        mark=d.log_mark();page('apps.main');wait(lambda:len(cells())==18)
        counts=wait(lambda:catalog_counts(mark,18));report['api_catalog_initial']=counts
        check('the public API returns ThinkOS pages alongside Android apps',counts['page']>0)
        mark=d.log_mark();setting('apps.android',False);find('没有安卓应用')
        counts=wait(lambda:catalog_counts(mark,0));report['api_catalog_disabled']=counts
        check('the Android switch leaves ThinkOS apps in the public API',counts['page']>0 and not cells())
    def permissions():
        with tempfile.TemporaryDirectory(prefix='thinkos-apps-denied-') as temporary:
            source=Path(temporary)/'source';(source/'views').mkdir(parents=True)
            (source/'manifest.json').write_text(json.dumps({'id':PROBE,'name':'Permission probe','version':'1.0.0','api':9,'permissions':['system.apps'],'main':'main.lua','pages':[{'id':'main','title':'Permission','view':'views/main.json'}]}))
            (source/'main.lua').write_text('''tos.on("start",function()
local ok,value=pcall(tos.apps.list)
tos.log.info("APP_LIST_DENIED="..tostring(not ok or value==nil))
local opened,result=pcall(tos.apps.open,"android:com.android.settings")
tos.log.info("APP_OPEN_DENIED="..tostring(not opened or not result))
end)
''')
            (source/'views/main.json').write_text(json.dumps({'layout':'focus','slots':{'hero':{'type':'grid','items':'icons','item':{'title':'Denied','image':'app:com.android.settings'}}}}))
            # The image must really be requested, in addition to both public Lua calls.
            with (source/'main.lua').open('a') as stream:stream.write('tos.state.set { icons = {{ title = "Denied" }} }\n')
            bundle,_=package_plugin(source,Path(temporary)/'package');mark=d.log_mark();publish_directory(bundle,args.serial)
        page(PROBE+'.main')
        wait(lambda:any('APP_LIST_DENIED=true' in line for line in d.log_since(mark)))
        wait(lambda:any('APP_OPEN_DENIED=true' in line for line in d.log_since(mark)))
        wait(lambda:any('[image:'+PROBE+'] system.apps permission required for app icons' in line for line in d.log_since(mark)))
        check('third-party Lua list/open and app icons are denied by actual grants')
        check('a denied open leaves ThinkOS in front',APP in focused())
        shot('permission-denied')
        remove_plugin(PROBE,args.serial)
    def scroll_probe():
        with tempfile.TemporaryDirectory(prefix='thinkos-grid-probe-') as temporary:
            bundle,_=package_plugin(ROOT/'tools/tests/fixtures/grid-probe',Path(temporary));publish_directory(bundle,args.serial)
        page('grid-probe.main');wait(lambda:len(cells())>0)
        tap(find('Item 001'));find('Selected 1')
        check('grid taps expose one-based indices')
        body=grid();a,b,c,e=node_bounds(body);x=(a+c)//2
        if args.grid_only:
            for _ in range(12):
                if 'Item 100' in titles():break
                d.shell('input','swipe',x,e-20,x,b+20,450)
            tap(find('Item 100'));find('Selected 100')
            check('vertical scrolling and recycled cells retain their original indices')
            shot('grid-bottom')
            for _ in range(12):
                if 'Item 001' in titles():break
                d.shell('input','swipe',x,b+20,x,e-20,450)
            find('Item 001');find('Selected 100')
            check('scrolling back to the start does not turn a drag into a tap')
            shot('grid-top')
            return
        before=d.shell('pidof',APP).strip();d.shell('dumpsys','gfxinfo',APP,'reset')
        began=time.monotonic();direction=True;count=0
        while time.monotonic()-began<120:
            d.shell('input','swipe',x,e-20 if direction else b+20,x,b+20 if direction else e-20,500)
            count+=1
            if count%4==0:direction=not direction
        raw=d.shell('dumpsys','gfxinfo',APP);(args.output/'grid-gfx.txt').write_text(raw)
        frames=int(re.search(r'Total frames rendered: (\d+)',raw)[1]);janky=int(re.search(r'Janky frames: (\d+)',raw)[1])
        report['scroll']={'seconds':time.monotonic()-began,'swipes':count,'frames':frames,'janky_frames':janky,'janky_pct':100*janky/max(frames,1)}
        check('120-second grid scrolling stays below five percent jank',frames>60 and 100*janky/frames<5 and d.shell('pidof',APP).strip()==before)
        shot('grid-scrolled')
    try:
        d.shell('am','start','-W','-n',APP+'/.MainActivity')
        d.debug('locale',value='zh-CN');d.debug('theme',value='DARK');d.debug('hold_attention',value='ENGAGED');d.debug('drift',x=0.0,y=0.0)
        orient(True)
        wait(lambda:bool(broadcast_json(d,'ui_state')['page']))
        if args.return_only:return_probe()
        elif args.catalog_only:catalog_probe()
        elif args.scroll_only or args.grid_only:scroll_probe()
        else:
            setting('apps.android',True);setting('apps.hidden','')
            page('tiles.apps');tap(find('安卓应用'));wait(lambda:broadcast_json(d,'ui_state')['page']=='apps.main')
            initial=wait(lambda:cells() or None)
            report['initial_titles']=[n.get('content-desc') for n in initial]
            check('the Android tile opens a native grid of eighteen launchers',len(initial)==18 and 'ThinkOS' not in report['initial_titles'])
            shot('grid-initial')
            d.shell('input','swipe',650,700,180,700,400)
            check('a horizontal drag neither leaves the grid nor launches a cell',broadcast_json(d,'ui_state')['page']=='apps.main' and APP in focused())
            back();check('Back returns to the application tile page',broadcast_json(d,'ui_state')['page']=='tiles.apps')
            page('apps.main')
            for locale in ('zh-CN','en','ar'):
                for theme in ('DARK','LIGHT','NIGHT'):
                    d.debug('locale',value=locale);d.debug('theme',value=theme)
                    for portrait in (True,False):
                        orient(portrait);wait(lambda:len(cells())==18)
                        observed=cells();a,b,c,e=node_bounds(grid())
                        check(locale+' '+theme+' '+('portrait' if portrait else 'landscape')+' keeps eighteen cells inside the grid',all(a<=node_bounds(n)[0]<node_bounds(n)[2]<=c and b<=node_bounds(n)[1]<node_bounds(n)[3]<=e for n in observed))
                        first,second=node_bounds(observed[0]),node_bounds(observed[1])
                        check(locale+' '+theme+' '+('portrait' if portrait else 'landscape')+' uses the expected reading direction',first[0]>second[0] if locale=='ar' else first[0]<second[0])
                        name=locale+'-'+theme+'-'+('portrait' if portrait else 'landscape');shot(name)
                        report['screens'][name]={'count':len(observed),'scroll_needed':False,'columns':len({node_bounds(n)[0] for n in observed})}
            d.debug('locale',value='zh-CN');d.debug('theme',value='DARK');orient(True)
            calc='计算器';tap(find(calc));wait(lambda:CALCULATOR in focused())
            back();wait(lambda:APP in focused());wait(lambda:len(cells())==18)
            check('a calculator icon opens Android and Back returns to the same grid',broadcast_json(d,'ui_state')['page']=='apps.main')
            wait(lambda:'mLockTaskModeState=LOCKED' in d.shell('dumpsys','activity','activities'))
            check('Lock Task is restored after the Android app')
            setting('apps.hidden',CALCULATOR);wait(lambda:calc not in titles())
            check('hidden packages disappear without reopening the page',len(cells())==17)
            setting('apps.hidden','');wait(lambda:len(cells())==18)
            setting('apps.android',False);find('没有安卓应用')
            check('disabling Android apps clears the grid to its empty state',not cells())
            back();page('tiles.apps');find('安卓应用')
            check('the tile remains available while Android apps are disabled')
            setting('apps.android',True);page('apps.main');wait(lambda:len(cells())==18)
            assert 'default-state' in d.shell('sh','-c','pm help 2>&1',check=False),'Cannot restore the exact enabled state on this ROM'
            package_original=package_state();assert package_original in (0,1),'Calculator must initially be enabled'
            report['original_calculator_enabled']=package_original
            (args.output/'package-recovery.json').write_text(json.dumps({'package':CALCULATOR,'original_enabled':package_original,'restore_command':['pm','default-state' if package_original==0 else 'enable','--user','0',CALCULATOR]},indent=2)+'\n')
            d.shell('pm','disable-user','--user',0,CALCULATOR);wait(lambda:calc not in titles())
            check('disabling a launcher package refreshes the live grid',len(cells())==17)
            d.shell('pm','default-state' if package_original==0 else 'enable','--user',0,CALCULATOR);wait(lambda:len(cells())==18)
            check('restoring the launcher package refreshes the live grid',package_state()==package_original)
            permissions()
        report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:d.screenshot(args.output/'failure.png')
        except Exception:pass
        raise
    finally:
        try:
            if package_original is not None:
                d.shell('pm','default-state' if package_original==0 else 'enable','--user',0,CALCULATOR)
                report['calculator_restored']=package_state()==package_original
            d.shell('am','force-stop',APP)
            for name in (PROBE,'grid-probe'):remove_plugin(name,args.serial)
            report.update(restore(journal,d))
        except Exception as error:report['recovery_error']=str(error);report['private_recovery_journal']=str(journal)
        (args.output/'logcat.txt').write_text('\n'.join(d.log_since(0))+'\n');d.stop_log()
        report['media_volume_zero']=bool(re.search(r'volume is 0\b',d.shell('media','volume','--stream',3,'--get')))
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    assert not report.get('recovery_error'),report.get('recovery_error')
    assert report['media_volume_zero']


if __name__=='__main__':main()
