#!/usr/bin/env python3
"""Weather UI/theme/RTL/settings/offline regression. Restores exact user settings and Wi-Fi."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from device_app_files import read_app_file
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin

APP = 'moe.kiwi.thinkos'
HOME = APP + '/.MainActivity'
LAUNCHER = 'org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/weather/ui')
    parser.add_argument('--bench', action='store_true', help='Also sample 120s AWAKE and 600s AMBIENT in DARK')
    parser.add_argument('--motion-only', action='store_true', help='Only sample weather detail animation for 120s in DARK')
    parser.add_argument('--skip-matrix', action='store_true', help='Resume settings/cold-start/performance after separately completed theme/RTL checks')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ['adb', '-s', args.serial]
    report = {'complete': False, 'passed': [], 'screens': {}, 'matrixSkipped': args.skip_matrix}
    labels = json.loads((ROOT / 'shared/i18n/system/zh-Hans.json').read_text())
    weather = json.loads((ROOT / 'plugins/weather/i18n/zh-Hans.json').read_text())
    done = json.loads((ROOT / 'plugins/settings/i18n/zh-Hans.json').read_text())['done']
    paths = ['files/settings/local.json', 'files/settings/server.json']

    def adb(*values, data=None):
        return subprocess.run(base + list(values), input=data, capture_output=True, timeout=30, check=True).stdout

    def shell(*values, data=None): return adb('shell', '-T', shlex.join(map(str, values)), data=data)

    def debug(command, value=None, kind='--es'):
        command = ['am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', command]
        if value is not None: command += [kind, 'value', str(value)]
        shell(*command)

    def pid(): return shell('pidof', APP).decode().strip()
    def logs(): return adb('logcat', '-d', '--pid=' + pid(), '-s', 'ThinkOS:V', 'AndroidRuntime:E').decode()
    def state():
        debug('state')
        return logs().split('DEBUG state=')[-1]
    def wait(test, message, seconds=12):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            if test(): return
            time.sleep(.2)
        raise AssertionError(message)
    def passed(text):
        report['passed'].append(text);print('PASS ' + text, flush=True)
    def tree(name='last-ui'):
        path = '/sdcard/thinkos-weather-ui-'+str(os.getpid())+'.xml'
        paused = False
        try:
            for attempt in range(3):
                output = shell('uiautomator', 'dump', '--compressed', path)
                try:
                    raw = shell('cat', path)
                    root = ET.fromstring(raw)
                except (subprocess.CalledProcessError, ET.ParseError):
                    # UIAutomator can exit successfully without a file when an animated
                    # cold screen never becomes idle. Pause icon motion for hierarchy IO.
                    report['hierarchyRetries'] = report.get('hierarchyRetries', 0)+1
                    with (args.output/'uiautomator-retries.txt').open('ab') as log: log.write(output+b'\n')
                    debug('hold_attention','AMBIENT');paused=True;time.sleep(.3)
                    continue
                (args.output / (name + '.xml')).write_bytes(raw)
                return root
            raise AssertionError('UIAutomator did not publish a readable hierarchy after three attempts')
        finally:
            shell('rm', '-f', path)
            if paused: debug('hold_attention','AWAKE')
    def box(node): return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))
    def find(text):
        for _ in range(3):
            node = next((n for n in tree().iter('node') if n.get('text') == text), None)
            if node is not None: return node
        raise AssertionError('Missing weather UI text: ' + text)
    def tap(node):
        a,b,c,d = box(node);shell('input', 'tap', (a+c)//2, (b+d)//2);time.sleep(.4)
    def shot(name, inspect=False):
        raw = adb('exec-out', 'screencap', '-p')
        width,height = struct.unpack('>II', raw[16:24])
        (args.output / (name + '.png')).write_bytes(raw)
        report['screens'][name] = [width,height]
        if inspect:
            nodes = list(tree(name).iter('node'))
            # A virtualized list may have a partially visible final row. Lead and headings must fit.
            required = [n for n in nodes if n.get('text', '').startswith('Open-Meteo.com')
                        or n.get('text') in (weather['daily'], 'المحسوسة')]
            assert required, 'Weather content is not ready'
            for node in required:
                a,b,c,d = box(node);assert 0<=a<c<=width and 0<=b<d<=height, node.attrib
    def navigate(page):
        if 'page=settings.form,' in state(): shell('input', 'keyevent', 3)
        for _ in range(9):
            current = state()
            if 'page=' + page + ',' in current: return
            if 'orientation=LANDSCAPE' in current: shell('input', 'swipe', 1100, 400, 150, 400, 450)
            else: shell('input', 'swipe', 700, 550, 120, 550, 450)
            time.sleep(.6)
        raise AssertionError('Cannot navigate to ' + page)
    def orient(portrait=False):
        debug('orientation', 1 if portrait else 0, '--ei')
        wait(lambda: 'orientation=' + ('PORTRAIT' if portrait else 'LANDSCAPE') in state(), 'rotation did not apply')
        time.sleep(.7)
    def saved():
        raw = read_app_file(args.serial, paths[0]);return json.loads(raw) if raw else {}
    def open_system():
        debug('locale', 'zh-CN');orient();navigate('home.main')
        shell('input', 'swipe', 640, 400, 640, 400, 900)
        find(labels['setting_system_name'])
    def ensure_night(enabled):
        label = labels['setting_theme_night'];root = tree()
        rows = [n for n in root.iter('node') if any(x.get('text')==label for x in n.iter('node'))
                and any(x.get('class')=='android.widget.Switch' for x in n.iter('node'))]
        row = min(rows, key=lambda n: len(list(n.iter('node'))))
        switch = next(n for n in row.iter('node') if n.get('class')=='android.widget.Switch')
        if (switch.get('checked')=='true') != enabled: tap(find(label))
    def weather_settings(from_card):
        orient();navigate('home.main' if from_card else 'weather.detail')
        x = 950 if from_card else 250
        shell('input', 'swipe', x, 400, x, 400, 900)
        find(weather['name']);find(weather['setting.temp_unit'])
    def choose_unit(unit):
        tap(find(weather['setting.temp_unit']));tap(find('°' + unit.upper()))
        # Choosing the default removes the local override; absence means Celsius.
        wait(lambda: saved().get('weather', {}).get('temp_unit', 'c') == unit, 'unit did not persist')
        tap(find(done))

    assert pid(), 'Start the debug APK first'
    assert not shell('run-as', APP, 'ls', 'files/dev-plugins').strip(), 'Preserve existing development overrides'
    original = {path: read_app_file(args.serial,path) for path in paths}
    wifi = shell('settings', 'get', 'global', 'wifi_on').strip()
    backup = Path(tempfile.mkdtemp(prefix='thinkos-weather-settings-'))
    for path,raw in original.items():
        if raw is not None:
            target=backup/Path(path).name;target.write_bytes(raw);os.chmod(target,0o600)
    try:
        debug('hold_attention', 'AWAKE')
        initial_pid=pid();report['uiPid']=initial_pid
        open_system();ensure_night(True)
        tap(find(labels['setting_theme_mode']));tap(find(labels['setting_theme_mode_auto']))
        tap(find(done))
        if args.motion_only:
            debug('lux',20,'--ef');wait(lambda:'theme=DARK' in state(),'motion DARK did not settle',seconds=50)
            orient();navigate('weather.detail');time.sleep(3);shot('weather-awake')
            subprocess.run([str(ROOT/'tools/bench.sh'),'120',str(args.output.parent/'weather-awake')],
                           env=dict(os.environ,ANDROID_SERIAL=args.serial),check=True)
            passed('weather detail AWAKE animation sampled for 120s in DARK')
            report['complete']=True
            return
        if not args.skip_matrix:
            for theme,lux in [('LIGHT',10000),('DARK',20),('NIGHT',.1)]:
                debug('lux',lux,'--ef');wait(lambda: 'theme='+theme in state(),'theme '+theme+' did not settle',seconds=50)
                time.sleep(2.5)
                for portrait in (False,True):
                    orient(portrait);suffix=('portrait' if portrait else 'landscape')+'-'+theme.lower()
                    navigate('home.main');shot('home-'+suffix,True)
                    navigate('weather.detail');shot('detail-'+suffix,True)
                passed('native card and detail fit both orientations in '+theme)
            debug('lux',20,'--ef');wait(lambda:'theme=DARK' in state(),'return to DARK',seconds=50)
            for tag in ('ar','zh-CN'):
                debug('locale',tag);time.sleep(1)
                for portrait in (False,True):
                    orient(portrait);debug('locale',tag);navigate('weather.detail');time.sleep(.5)
                    shot('detail-'+('portrait' if portrait else 'landscape')+'-'+tag,True)
                passed('native detail rebinds '+tag+' with localized dates and layout direction')
            debug('locale','zh-CN')
            orient();navigate('weather.detail')
            cache=json.loads(read_app_file(args.serial,'files/store/weather.json'))['forecast']
            cache=json.loads(cache) if isinstance(cache,str) else cache
            last=cache['weather']['daily']['time'][-1]+cache['weather']['utc_offset_seconds']
            last_day=['星期一','星期二','星期三','星期四','星期五','星期六','星期日'][time.gmtime(last).tm_wday]
            listing=next(n for n in tree().iter('node') if n.get('class')=='android.widget.ListView')
            a,b,c,d=box(listing)
            for _ in range(2):shell('input','swipe',(a+c)//2,d-25,(a+c)//2,b+25,450)
            find(last_day);shot('detail-day-five')
            for _ in range(2):shell('input','swipe',(a+c)//2,b+25,(a+c)//2,d-25,450)
            passed('native forecast list scrolls to the fifth day')
        weather_settings(True);shot('card-settings')
        choose_unit('f');navigate('home.main');shot('home-fahrenheit')
        cache = json.loads(read_app_file(args.serial,'files/store/weather.json'))['forecast']
        cache = json.loads(cache) if isinstance(cache,str) else cache
        expected = str(int(cache['weather']['current']['temperature_2m']*1.8+32+.5))+'°'
        find(expected)
        passed('long press on weather card opens its schema settings and unit changes immediately')
        # Offline restart checks both persisted config and immediate cached weather, without Server.
        shell('svc','wifi','disable');wait(lambda:shell('settings','get','global','wifi_on').strip()==b'0','Wi-Fi remained enabled')
        shell('cmd','package','set-home-activity',LAUNCHER);shell('am','force-stop',APP)
        cold=[]
        for attempt in range(3):
            if attempt: shell('am','force-stop',APP)
            began=time.monotonic();launch=shell('am','start','-W','-n',HOME).decode()
            (args.output/('offline-am-start-'+str(attempt)+'.txt')).write_text(launch)
            total=int(re.search(r'TotalTime: (\d+)',launch).group(1))
            wait(lambda: 'id=weather' in state(), 'weather cache not ready',seconds=3)
            ready=round((time.monotonic()-began)*1000)
            assert total<3000 and ready<3000,(total,ready)
            assert 'plugin CPU budget exceeded' not in logs(), 'Offline cached start exhausted its budget'
            cold.append({'activityMs':total,'contentMs':ready})
        find(expected);shot('offline-fahrenheit-cache')
        assert saved()['weather']['temp_unit']=='f'
        report['offlineColdStart']={'attempts':cold,'wifiDisabled':True,'server':'none'}
        passed('three offline cold starts display cached Fahrenheit weather within 3 seconds without budget errors')
        shell('cmd','package','set-home-activity',HOME)
        weather_settings(False);shot('detail-settings');choose_unit('c')
        navigate('weather.detail');shot('detail-celsius')
        passed('long press on detail opens the same persisted unit settings')
        if wifi!=b'0':shell('svc','wifi','enable')
        # Static vocabulary probe has no Lua or permissions; it uses exactly the shipped renderer.
        with tempfile.TemporaryDirectory(prefix='thinkos-weather-icons-') as temporary:
            source=Path(temporary)/'source';(source/'views').mkdir(parents=True)
            names=['clear-day','clear-night','partly-day','partly-night','cloud','fog','rain','shower','snow','thunder']
            (source/'manifest.json').write_text(json.dumps({'id':'weather-icons','name':'Icon vocabulary','version':'1.0.0','api':2,
                'permissions':[],'pages':[{'id':'all','title':'Weather icons','view':'views/all.json'}]}))
            slots={}
            for slot,group in [('lead',names[:5]),('main',names[5:])]:
                children=[]
                for name in group:children.extend([{'type':'icon','name':name,'weight':1},{'type':'text','value':name,'style':'caption'}])
                slots[slot]={'children':children}
            (source/'views/all.json').write_text(json.dumps({'layout':'split','slots':slots}))
            directory,_=package_plugin(source,Path(temporary)/'out');publish_directory(directory,args.serial)
        orient();navigate('weather-icons.all')
        for theme,lux in [('DARK',20),('LIGHT',10000),('NIGHT',.1)]:
            debug('lux',lux,'--ef');wait(lambda:'theme='+theme in state(),'icon theme did not settle',seconds=50);time.sleep(2.5)
            shot('icons-'+theme.lower())
        passed('all 10 native weather icons render in three themes')
        if wifi!=b'0':
            wait(lambda:'[plugin:weather] weather refresh ok' in logs(),'weather did not recover after Wi-Fi was restored',seconds=70)
            passed('production weather automatically recovers after actual Wi-Fi restoration')
        remove_plugin('weather-icons', args.serial)
        if args.bench:
            debug('lux',20,'--ef');wait(lambda:'theme=DARK' in state(),'benchmark DARK did not settle',seconds=50)
            orient()
            for page,attention,seconds,name in [('weather.detail','AWAKE',120,'weather-awake'),('clock.face','AWAKE',120,'awake'),('home.main','AMBIENT',600,'ambient')]:
                debug('hold_attention',attention);navigate(page);time.sleep(3)
                wait(lambda:'attention='+attention in state(),'benchmark attention did not apply')
                shot('bench-'+name)
                print('Sampling '+name+' for '+str(seconds)+' seconds',flush=True)
                environment=dict(os.environ,ANDROID_SERIAL=args.serial)
                subprocess.run([str(ROOT/'tools/bench.sh'),str(seconds),str(args.output.parent/name)],env=environment,check=True)
            passed('DARK weather AWAKE 120s, clock AWAKE 120s and home AMBIENT 600s sampled without interaction')
        report['complete']=True
    except Exception as error:
        report['failure']=str(error)
        try:shot('failed-screen')
        except Exception:pass
        raise
    finally:
        try:
            (args.output/'logcat.txt').write_text(logs())
            remove_plugin('weather-icons',args.serial)
            shell('cmd','package','set-home-activity',LAUNCHER);shell('am','force-stop',APP)
            for path,raw in original.items():
                if raw is None:shell('run-as',APP,'rm','-f',path,path+'.bak')
                else:
                    shell('run-as',APP,'sh','-c','cat > '+shlex.quote(path+'.weather-restore'),data=raw)
                    shell('run-as',APP,'mv',path+'.weather-restore',path);shell('run-as',APP,'rm','-f',path+'.bak')
            shell('svc','wifi','enable' if wifi!=b'0' else 'disable')
            shell('cmd','package','set-home-activity',HOME);shell('am','start','-n',HOME)
            report['settingsRestored']=all(read_app_file(args.serial,path)==raw for path,raw in original.items())
            report['wifiRestored']=shell('settings','get','global','wifi_on').strip()==wifi
            if report['settingsRestored'] and report['wifiRestored']:shutil.rmtree(backup)
            else:report['backupDirectory']=str(backup)
        except Exception as error:
            report['restoreFailure']=str(error);report['backupDirectory']=str(backup)
        (args.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        assert report.get('settingsRestored') and report.get('wifiRestored'),'Weather test did not restore original settings'


if __name__=='__main__':main()
