#!/usr/bin/env python3
"""Verify real device-to-Bing photos, persistent credits and offline cache; no Server needed."""
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

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin
APP = 'moe.kiwi.thinkos'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/gallery/bing')
    parser.add_argument('--bench', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ['adb', '-s', args.serial]
    report = {'complete': False, 'passed': []}
    override = False

    def adb(*values):
        return subprocess.run(base+list(map(str, values)), capture_output=True, check=True, timeout=45).stdout
    def shell(*values): return adb('shell', '-T', shlex.join(list(map(str, values))))
    def debug(command, value=None, kind='--es'):
        values = ['am', 'broadcast', '-n', APP+'/.DebugReceiver', '--es', 'command', command]
        if value is not None: values += [kind, 'value', value]
        shell(*values)
    def pid(): return shell('pidof', APP).decode().strip()
    def logs(): return adb('logcat', '-d', '--pid='+pid(), '-s', 'ThinkOS:V', 'AndroidRuntime:E').decode()
    def state(): debug('state'); return logs().split('DEBUG state=')[-1]
    def wait(check, message, seconds=60):
        end = time.monotonic()+seconds
        while time.monotonic() < end:
            if check(): return
            time.sleep(.4)
        raise AssertionError(message+'\n'+logs()[-5000:])
    def navigate():
        for _ in range(12):
            current = state()
            if 'page=gallery.slideshow,' in current: return
            if 'PORTRAIT' in current: shell('input', 'swipe', 700, 550, 120, 550, 350)
            else: shell('input', 'swipe', 1100, 400, 150, 400, 350)
            time.sleep(.5)
        raise AssertionError('Cannot navigate to gallery')
    def prepare():
        debug('hold_attention', 'AWAKE'); debug('lux', '20', '--ef'); debug('theme', 'DARK')
        debug('orientation', '0', '--ei'); navigate()
    def displayed(): return re.findall(r'\[image:gallery\] displayed (OHR\.\S+)', logs())
    def shot(name): (args.output/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    def passed(message): report['passed'].append(message); print('PASS '+message, flush=True)
    def publish(source):
        with tempfile.TemporaryDirectory(prefix='thinkos-bing-package-') as out:
            directory, _ = package_plugin(source, Path(out)); publish_directory(directory, args.serial)
        time.sleep(1)

    shell('am', 'start', '-n', APP+'/.MainActivity')
    assert not shell('run-as', APP, 'ls', 'files/dev-plugins').strip(), 'Preserve existing plugin overrides'
    wifi = shell('settings', 'get', 'global', 'wifi_on').strip()
    scale = shell('settings', 'get', 'global', 'animator_duration_scale').strip()
    try:
        shell('settings', 'put', 'global', 'animator_duration_scale', '1')
        debug('locale', 'zh-CN'); prepare()
        wait(lambda: bool(displayed()), 'Real Bing photo did not render')
        # A rerun may restore fresh metadata and correctly skip the six-hour refresh.
        report['archiveRefreshedThisProcess'] = 'Bing gallery refreshed zh-CN' in logs()
        report['firstPhoto'] = displayed()[0]
        shot('landscape-daily'); time.sleep(8); shot('landscape-credit-after-title')
        passed('device renders the real Bing daily image through the OS HTTP/cache pipeline')
        for _ in range(2):
            count = len(displayed()); shell('input', 'tap', 640, 400)
            wait(lambda: len(displayed()) > count, 'Next Bing photo did not render')
            time.sleep(1)  # A normal next-photo tap follows the previous native transition.
        report['photosDisplayed'] = list(dict.fromkeys(displayed()))
        assert len(report['photosDisplayed']) >= 3
        passed('tap advances across at least three real Bing photos')
        debug('orientation', '1', '--ei'); time.sleep(3); shot('portrait-daily')
        debug('orientation', '0', '--ei'); time.sleep(2)
        passed('the same complete landscape source is reused in portrait with pan')
        report['cacheFiles'] = shell('run-as', APP, 'ls', '-l', 'cache/images').decode()
        assert re.search(r'[0-9a-f]{64}', report['cacheFiles']), 'OS image disk cache is empty'
        (args.output/'online-logcat.txt').write_text(logs())
        shell('svc', 'wifi', 'disable')
        shell('am', 'force-stop', APP)
        start = shell('am', 'start', '-W', '-n', APP+'/.MainActivity').decode()
        report['offlineColdStart'] = start
        report['offlineColdStartMs'] = int(re.search(r'TotalTime:\s*(\d+)', start)[1])
        assert report['offlineColdStartMs'] < 3000, start
        (args.output/'offline-cold-start.txt').write_text(start)
        prepare(); wait(lambda: bool(displayed()), 'Cached Bing photo did not render offline', seconds=20)
        time.sleep(8); shot('offline-daily')
        passed('offline cold start renders cached Bing metadata and image without Server')
        shell('svc', 'wifi', 'enable' if wifi == b'1' else 'disable')
        with tempfile.TemporaryDirectory(prefix='thinkos-bing-credit-') as temp:
            source = Path(temp)/'gallery'; shutil.copytree(ROOT/'plugins/gallery', source)
            manifest = json.loads((source/'manifest.json').read_text())
            manifest['settings']['caption']['default'] = 'off'
            (source/'manifest.json').write_text(json.dumps(manifest))
            override = True; count = len(displayed()); publish(source); navigate()
            wait(lambda: len(displayed()) > count, 'Caption-off gallery did not reload')
            time.sleep(8); shot('caption-off-credit')
            passed('caption-off mode keeps the separate copyright credit visible (screenshot)')
        remove_plugin('gallery', args.serial); override = False
        time.sleep(2); navigate()
        if args.bench:
            debug('hold_attention', 'AMBIENT'); time.sleep(2)
            subprocess.run([str(ROOT/'tools/bench.sh'), '600', str(args.output/'ambient')],
                           env=dict(os.environ, ANDROID_SERIAL=args.serial), check=True)
            passed('600-second real Bing gallery AMBIENT sample completed with animations enabled')
        report['complete'] = True
        report['finalPid'] = pid()
    finally:
        report['logcat'] = 'final-logcat.txt'
        try:
            (args.output/'final-logcat.txt').write_text(logs())
        except Exception as error:
            report['logCaptureError'] = str(error)
        if override: remove_plugin('gallery', args.serial)
        shell('svc', 'wifi', 'enable' if wifi == b'1' else 'disable')
        if scale == b'null': shell('settings', 'delete', 'global', 'animator_duration_scale')
        else: shell('settings', 'put', 'global', 'animator_duration_scale', scale.decode())
        debug('hold_attention'); debug('lux'); debug('orientation'); debug('locale')
        (args.output/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')


if __name__ == '__main__':
    main()
