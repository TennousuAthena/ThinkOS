#!/usr/bin/env python3
"""Real Bing history gestures, native pan cadence and photo-clock contrast on API 27."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin
APP = 'moe.kiwi.thinkos'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', default='HUA0BWUT')
    p.add_argument('--output', type=Path, default=ROOT/'artifacts/gallery/motion')
    p.add_argument('--bench', action='store_true')
    p.add_argument('--record', action='store_true')
    args = p.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    base = ['adb', '-s', args.serial]
    report = {'complete': False, 'passed': []}; override = False

    def adb(*a): return subprocess.run(base+list(map(str, a)), capture_output=True, check=True, timeout=45).stdout
    def shell(*a): return adb('shell', '-T', shlex.join(list(map(str, a))))
    def debug(cmd, val=None, kind='--es'):
        a = ['am', 'broadcast', '-n', APP+'/.DebugReceiver', '--es', 'command', cmd]
        if val is not None: a += [kind, 'value', str(val)]
        shell(*a)
    def pid(): return shell('pidof', APP).decode().strip()
    def logs(): return adb('logcat', '-d', '--pid='+pid(), '-s', 'ThinkOS:V', 'AndroidRuntime:E').decode()
    def state(): debug('state'); return logs().split('DEBUG state=')[-1]
    def ids(): return re.findall(r'\[image:gallery\] displayed (OHR\.\S+)', logs())
    def wait(fn, message, seconds=30):
        until = time.monotonic()+seconds
        while time.monotonic() < until:
            if fn(): return
            time.sleep(.2)
        raise AssertionError(message+'\n'+logs()[-5000:])
    def shot(name): (args.output/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    def passed(message): report['passed'].append(message); print('PASS '+message, flush=True)
    def navigate(page):
        for _ in range(12):
            current = state()
            if 'page='+page+',' in current: return
            if 'PORTRAIT' in current: shell('input', 'swipe', 700, 550, 120, 550, 300)
            else: shell('input', 'swipe', 1100, 400, 150, 400, 300)
            time.sleep(.4)
        raise AssertionError('Cannot navigate to '+page)
    def swipe(direction, portrait=False, distance=500, duration=300):
        x, y = (400, 640) if portrait else (640, 400)
        # Positive = up to older, negative = down to newer.
        shell('input', 'swipe', x, y+direction*distance//2, x, y-direction*distance//2, duration)
    def publish(source):
        with tempfile.TemporaryDirectory(prefix='thinkos-motion-package-') as temp:
            directory, _ = package_plugin(source, Path(temp)); publish_directory(directory, args.serial)
        time.sleep(1)

    shell('am', 'force-stop', APP)  # Reset in-process selection; preserve metadata and image caches.
    shell('am', 'start', '-n', APP+'/.MainActivity')
    assert not shell('run-as', APP, 'ls', 'files/dev-plugins').strip(), 'Preserve pre-existing plugin overrides'
    scale = shell('settings', 'get', 'global', 'animator_duration_scale').strip()
    wifi = shell('settings', 'get', 'global', 'wifi_on').strip()
    try:
        shell('settings', 'put', 'global', 'animator_duration_scale', '1')
        debug('locale', 'zh-CN'); debug('hold_attention', 'AWAKE'); debug('lux', 20, '--ef'); debug('theme', 'DARK'); debug('orientation', 0, '--ei')
        navigate('gallery.slideshow'); wait(lambda: bool(ids()), 'Bing daily photo missing')
        # On a cold launch MainActivity.onResume can apply its saved orientation after an early debug broadcast.
        debug('orientation', 0, '--ei'); debug('hold_attention', 'AWAKE')
        wait(lambda: 'orientation=LANDSCAPE' in state(), 'Landscape override did not apply')
        time.sleep(.5)
        with tempfile.TemporaryDirectory(prefix='thinkos-history-') as temp:
            source = Path(temp)/'gallery'; shutil.copytree(ROOT/'plugins/gallery', source)
            m = json.loads((source/'manifest.json').read_text()); m['settings']['interval']['default'] = 3600
            (source/'manifest.json').write_text(json.dumps(m))
            override = True; before = len(ids()); publish(source); navigate('gallery.slideshow')
            wait(lambda: len(ids()) > before, 'History test override did not render')
            first = ids()[-1]; before = len(ids()); swipe(-1); time.sleep(.6)
            assert ids()[-1] == first and len(ids()) == before, 'Newer boundary wrapped or changed photo'
            passed('down at newest photo stops at the history boundary')
            before = len(ids()); swipe(1)
            wait(lambda: len(ids()) > before and ids()[-1] != first, 'Up swipe did not show older photo')
            older = ids()[-1]; shot('older-landscape')
            before = len(ids()); swipe(-1)
            wait(lambda: len(ids()) > before and ids()[-1] == first, 'Down swipe did not return to newer photo')
            passed('up browses older and down returns to newer; gallery page stays selected')
            assert 'page=gallery.slideshow,' in state()
            before = len(ids()); swipe(1, distance=40, duration=500); time.sleep(.6)
            assert len(ids()) == before and ids()[-1] == first, 'Cancelled short drag changed photo'
            passed('short drag springs back without changing selection')
            for _ in range(3): swipe(1); time.sleep(.15); swipe(-1); time.sleep(.15)
            time.sleep(.7); assert 'page=gallery.slideshow,' in state()
            assert 'disabled=true' not in state()
            passed('rapid alternating swipes keep the current gallery and runtime healthy')
            time.sleep(.5)
            shell('input', 'swipe', 1100, 400, 150, 400, 350)
            wait(lambda: 'page=gallery.slideshow,' not in state(), 'Horizontal page navigation did not commit', seconds=4)
            navigate('gallery.slideshow')
            passed('horizontal swipes retain OS page navigation')
            shell('input', 'swipe', 640, 400, 640, 400, 850)
            wait(lambda: 'page=settings.form,' in state(), 'Long press did not open settings')
            path = '/sdcard/Android/data/'+APP+'/files/gallery-motion-ui.xml'
            try:
                shell('uiautomator','dump',path)
                tree = ET.fromstring(shell('cat',path))
                done = next(n for n in tree.iter('node') if n.get('text') in ('完成','Done'))
                left,top,right,bottom = map(int,re.findall(r'\d+',done.get('bounds')))
                shell('input','tap',(left+right)//2,(top+bottom)//2)
                wait(lambda: 'page=settings.form,' not in state(), 'Done did not close settings')
            finally: shell('rm','-f',path)
            navigate('gallery.slideshow')
            passed('long press still opens the native settings page')
            for portrait in (False, True):
                debug('orientation', int(portrait), '--ei')
                wait(lambda: ('orientation=PORTRAIT' if portrait else 'orientation=LANDSCAPE') in state(), 'Rotation did not apply')
                time.sleep(.5)
                before = len(ids()); swipe(1, portrait)
                wait(lambda: len(ids()) > before, 'History swipe failed after rotation')
                for theme in ('LIGHT','DARK'):
                    debug('theme', theme); time.sleep(1)
                    shot(('portrait' if portrait else 'landscape')+'-'+theme.lower()+'-outlined-clock')
            passed('history and outlined white time render in both orientations and themes')
            debug('orientation', 0, '--ei'); debug('theme', 'DARK'); time.sleep(1)
        remove_plugin('gallery', args.serial); override = False
        time.sleep(1); navigate('gallery.slideshow')
        if args.record:
            # Recording starts with a newly decoded photo and is excluded from CPU/frames benchmarks.
            shell('input', 'tap', 640, 400); time.sleep(.3)
            remote = '/sdcard/Android/data/'+APP+'/files/gallery-smooth-pan-test.mp4'
            try:
                subprocess.run(base+['shell','screenrecord','--time-limit','18','--bit-rate','2500000',remote], check=True, timeout=30)
                adb('pull', remote, args.output/'native-pan.mp4')
            finally: shell('rm', '-f', remote)
            passed('native panning recorded separately from performance samples')
        # The clock stays at the normal ambient cadence; moving image properties run at display refresh.
        debug('orientation', 1, '--ei'); time.sleep(.4); shell('input','tap',400,640); time.sleep(.5)
        debug('hold_attention', 'AMBIENT')
        subprocess.run([str(ROOT/'tools/bench.sh'),'15',str(args.output/'portrait-active')], env=dict(os.environ,ANDROID_SERIAL=args.serial),check=True)
        passed('portrait active sweep sampled with animations enabled')
        debug('orientation',0,'--ei'); time.sleep(1)
        if args.bench:
            report['benchmarkStartState'] = state().split(' plugins=')[0]
            subprocess.run([str(ROOT/'tools/bench.sh'),'600',str(args.output/'ambient')], env=dict(os.environ,ANDROID_SERIAL=args.serial),check=True)
            report['benchmarkEndState'] = state().split(' plugins=')[0]
            passed('600-second foreground sample captured; page residency and interaction must be checked separately')
        report['nativePanEvents'] = re.findall(r'\[image:gallery\] native-pan ([^\n]+)', logs())
        assert report['nativePanEvents'], 'Native pan never started'
        report['complete'] = True; report['pid'] = pid()
    finally:
        if override: remove_plugin('gallery', args.serial)
        shell('svc','wifi','enable' if wifi==b'1' else 'disable')
        if scale==b'null': shell('settings','delete','global','animator_duration_scale')
        else: shell('settings','put','global','animator_duration_scale',scale.decode())
        debug('orientation'); debug('locale'); debug('hold_attention'); debug('lux')
        (args.output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        (args.output/'logcat.txt').write_text(logs())


if __name__ == '__main__': main()
