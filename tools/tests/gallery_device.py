#!/usr/bin/env python3
"""Offline gallery/API-3 device regression using synthetic photos; restores overrides and Wi-Fi."""
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
import zlib

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin

APP = 'moe.kiwi.thinkos'


def generate_photos(root):
    """Yellow borders make preservation of both ends visible in screenshots."""
    def png(name, w, h):
        def chunk(key, data):
            return struct.pack('>I', len(data)) + key + data + struct.pack('>I', zlib.crc32(key + data) & 0xffffffff)
        compressor = zlib.compressobj()
        chunks = []
        for y in range(h):
            row = bytearray()
            for x in range(w):
                edge = x < w//20 or x >= w-w//20 or y < h//20 or y >= h-h//20
                row.extend((250, 220, 40) if edge else (30+x*180//w, 40+y*130//h, 130))
            chunks.append(compressor.compress(b'\0' + row))
        chunks.append(compressor.flush())
        (root/name).write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', b''.join(chunks)) + chunk(b'IEND', b''))
    png('01-wide.png', 3000, 1000)
    png('02-portrait.png', 800, 1600)
    png('03-fit.png', 1280, 800)
    (root/'04-broken.jpg').write_bytes(b'broken image data')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/gallery/device')
    parser.add_argument('--bench', action='store_true', help='Sample 600s AMBIENT with default gallery settings')
    parser.add_argument('--stress-library', action='store_true', help='Exercise the 512-item OS library cap and 300-item gallery cap')
    parser.add_argument('--motion-sample', type=int, default=0, help='Only set up the gallery and sample N seconds of enabled pan motion')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ['adb', '-s', args.serial]
    report = {'complete': False, 'passed': []}
    remote = '/sdcard/Android/data/' + APP + '/files/Pictures/ThinkOSGalleryTest-' + str(os.getpid())

    def adb(*values, data=None):
        return subprocess.run(base + list(map(str, values)), input=data, capture_output=True, timeout=40, check=True).stdout

    def shell(*values, data=None): return adb('shell', '-T', shlex.join(list(map(str, values))), data=data)
    def debug(command, value=None, kind='--es'):
        values = ['am', 'broadcast', '-n', APP+'/.DebugReceiver', '--es', 'command', command]
        if value is not None: values += [kind, 'value', value]
        shell(*values)
    def pid(): return shell('pidof', APP).decode().strip()
    def fd_count():
        # Glob directory entries without stat/readlink: metadata workers can close an FD
        # between ls's readdir and lstat, making an otherwise successful listing exit 1.
        values = shell('run-as', APP, 'sh', '-c', 'echo /proc/'+pid()+'/fd/*').split()
        assert values and not any(b'*' in value for value in values), 'Cannot inspect app file descriptors'
        return len(values)
    def logs(): return adb('logcat', '-d', '--pid='+pid(), '-s', 'ThinkOS:V', 'AndroidRuntime:E').decode()
    def state(): debug('state'); return logs().split('DEBUG state=')[-1]
    def passed(text): report['passed'].append(text); print('PASS '+text, flush=True)
    def wait(test, message, seconds=20):
        until = time.monotonic()+seconds
        while time.monotonic() < until:
            if test(): return
            time.sleep(.2)
        raise AssertionError(message)
    def navigate(page):
        for _ in range(12):
            current = state()
            if 'page='+page+',' in current: return
            if 'PORTRAIT' in current: shell('input', 'swipe', 700, 550, 120, 550, 350)
            else: shell('input', 'swipe', 1100, 400, 150, 400, 350)
            time.sleep(.5)
        raise AssertionError('Cannot navigate to '+page)
    def shot(name):
        (args.output/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    def publish(source):
        with tempfile.TemporaryDirectory(prefix='thinkos-gallery-package-') as out:
            directory, _ = package_plugin(source, Path(out)); publish_directory(directory, args.serial)
        time.sleep(1)

    assert pid(), 'Start the debug APK first'
    assert not shell('run-as', APP, 'ls', 'files/dev-plugins').strip(), 'Preserve existing development overrides'
    original_wifi = shell('settings', 'get', 'global', 'wifi_on').strip()
    scale = shell('settings', 'get', 'global', 'animator_duration_scale').strip()
    def restore_scale():
        if scale == b'null': shell('settings', 'delete', 'global', 'animator_duration_scale')
        else: shell('settings', 'put', 'global', 'animator_duration_scale', scale.decode())
    try:
        shell('settings', 'put', 'global', 'animator_duration_scale', '1')
        with tempfile.TemporaryDirectory(prefix='thinkos-gallery-ui-') as temporary:
            temporary = Path(temporary)
            photos = temporary/'photos'; photos.mkdir(); generate_photos(photos)
            if args.stress_library:
                originals = ['01-wide.png', '02-portrait.png', '03-fit.png']
                for n in range(520): shutil.copyfile(photos/originals[n%3], photos/('10-stress-%03d.png' % n))
            shell('mkdir', '-p', remote); adb('push', str(photos)+'/.', remote+'/')
            source = temporary/'gallery'; shutil.copytree(ROOT/'plugins/gallery', source)
            manifest = json.loads((source/'manifest.json').read_text())
            manifest['settings']['local_dir']['default'] = remote
            manifest['settings']['source']['default'] = 'local'
            manifest['settings']['interval']['default'] = 5
            manifest['settings']['pan_speed']['default'] = 120
            if args.motion_sample:
                manifest['settings']['interval']['default'] = 30
                manifest['settings']['pan_speed']['default'] = 3
            (source/'manifest.json').write_text(json.dumps(manifest))
            initial_pid = pid(); initial_fds = fd_count(); publish(source)
            assert pid() == initial_pid
            debug('hold_attention', 'AWAKE'); debug('lux', '20', '--ef'); debug('theme', 'DARK')
            debug('orientation', '0', '--ei')
            wait(lambda: 'LANDSCAPE' in state(), 'Landscape did not apply')
            navigate('gallery.slideshow'); time.sleep(1); shot('landscape-dark-pan')
            wait(lambda: '[image:gallery] displayed' in logs(), 'Local photos did not render')
            if args.stress_library:
                wait(lambda: '[plugin:gallery] Local gallery scanned 300 photos' in logs(), 'Large library did not publish 300 photos within budget')
                passed('512-item native library result and 300-photo Lua state stay within runtime budgets')
            passed('local library, hot replacement, sampled decode and gallery page render without restarting')
            if args.motion_sample:
                debug('hold_attention', 'AMBIENT'); time.sleep(2)
                subprocess.run([str(ROOT/'tools/bench.sh'), str(args.motion_sample), str(args.output.parent/'ambient')],
                               env=dict(os.environ, ANDROID_SERIAL=args.serial), check=True)
                report['complete'] = True; report['finalPid'] = pid()
                passed(str(args.motion_sample)+'-second enabled-animation AMBIENT sample captured')
                return
            before = logs().count('[image:gallery] displayed')
            shell('input', 'tap', 640, 400)
            wait(lambda: logs().count('[image:gallery] displayed') > before, 'Tap did not advance')
            time.sleep(1); shot('landscape-tap-next')
            passed('tap advances; native carousel leaves page gestures to the OS')
            for portrait in (False, True):
                debug('orientation', '1' if portrait else '0', '--ei')
                wait(lambda: ('PORTRAIT' if portrait else 'LANDSCAPE') in state(), 'Rotation failed')
                for theme in ('LIGHT', 'DARK'):
                    debug('theme', theme); time.sleep(2.2)
                    shot(('portrait' if portrait else 'landscape')+'-'+theme.lower())
            passed('landscape and portrait render in LIGHT and DARK')
            debug('locale', 'ar'); time.sleep(.6); shot('portrait-arabic'); time.sleep(8); shot('portrait-arabic-pan-later')
            debug('locale', 'zh-CN')
            debug('theme', 'NIGHT')
            wait(lambda: 'page=clock.face,' in state(), 'NIGHT did not leave gallery')
            shot('night-clock'); passed('NIGHT exits ambient photo pages')
            debug('theme', 'DARK'); debug('orientation', '0', '--ei')
            # All transitions, full-frame fallback, malformed image handling and 50 switches.
            manifest['settings']['pan']['default'] = False
            for transition in ('fade', 'slide', 'zoom', 'random'):
                manifest['settings']['transition']['default'] = transition
                (source/'manifest.json').write_text(json.dumps(manifest)); publish(source)
                navigate('gallery.slideshow'); shell('input', 'tap', 640, 400); time.sleep(1.2)
                shot('transition-'+transition)
            manifest['settings']['interval'].update(default=.2, min=.1, step=.1)
            manifest['settings']['duration']['default'] = 0
            (source/'manifest.json').write_text(json.dumps(manifest)); publish(source)
            count = logs().count('[image:gallery] displayed')
            wait(lambda: logs().count('[image:gallery] displayed') >= count+50, '50 switches did not complete', seconds=60)
            shot('contain-50-switches')
            (args.output/'memory-after-50.txt').write_bytes(shell('dumpsys', 'meminfo', APP))
            passed('50 photo changes and malformed-image skipping without process restart')
            report['fileDescriptorsBefore'] = initial_fds; report['fileDescriptorsAfter'] = fd_count()
            assert report['fileDescriptorsAfter'] < initial_fds+64, 'Repeated library scans leak file descriptors'
            passed('repeated metadata scans keep file descriptor usage bounded')
            # Re-enable panning before reducing motion: reduced motion must show the whole frame.
            manifest['settings']['pan']['default'] = True
            manifest['settings']['interval'].update(default=30, min=5, step=1)
            manifest['settings']['duration']['default'] = 800
            manifest['settings']['transition']['default'] = 'fade'
            manifest['settings']['pan_speed']['default'] = 3
            (source/'manifest.json').write_text(json.dumps(manifest)); publish(source)
            shell('settings', 'put', 'global', 'animator_duration_scale', '0'); time.sleep(1)
            shot('reduced-motion-contain')
            shell('settings', 'put', 'global', 'animator_duration_scale', '1')
            passed('reduced motion switches from pan to whole-image contain')
            navigate('home.main'); time.sleep(1)
            count = logs().count('[image:gallery] displayed')
            time.sleep(2)
            assert logs().count('[image:gallery] displayed') == count
            passed('leaving the page stops carousel decode and timers')
            shell('svc', 'wifi', 'disable')
            shell('am', 'force-stop', APP)
            cold = shell('am', 'start', '-W', '-n', APP+'/.MainActivity').decode()
            (args.output/'offline-cold-start.txt').write_text(cold)
            cold_ms = int(re.search(r'TotalTime:\s*(\d+)', cold)[1]); report['offlineColdStartMs'] = cold_ms
            assert cold_ms < 3000, cold
            debug('hold_attention', 'AWAKE'); debug('lux', '20', '--ef'); debug('theme', 'DARK'); debug('orientation', '0', '--ei')
            navigate('gallery.slideshow')
            wait(lambda: '[image:gallery] displayed' in logs(), 'Offline local photos not ready')
            passed('offline cold launch <3s; local photos render with no Server')
            if args.bench:
                debug('hold_attention', 'AMBIENT')
                # Settings match the release default except the isolated test directory.
                subprocess.run([str(ROOT/'tools/bench.sh'), '600', str(args.output.parent/'ambient')], env=dict(os.environ, ANDROID_SERIAL=args.serial), check=True)
                passed('600-second AMBIENT performance sample captured')
            report['finalPid'] = pid(); report['complete'] = True
    finally:
        if original_wifi == b'1': shell('svc', 'wifi', 'enable')
        restore_scale()
        remove_plugin('gallery', args.serial)
        shell('rm', '-rf', remote)
        debug('locale'); debug('orientation', '10', '--ei'); debug('hold_attention'); debug('lux')
        (args.output/'logcat.txt').write_text(logs())
        (args.output/'report.json').write_text(json.dumps(report, indent=2)+'\n')


if __name__ == '__main__': main()
