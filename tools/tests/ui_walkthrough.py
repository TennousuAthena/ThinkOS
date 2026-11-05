#!/usr/bin/env python3
"""Repeatable UI-00/UI-17 screenshots, with local media and restoration in finally.

    python3 tools/tests/ui_walkthrough.py --output artifacts/ui/UI-00

The language/theme/orientation holds are process-local. Settings, saved tabs and
remembered variants are restored byte for byte; no Android language/time setting
or recovery flag is changed. A banner preview is labelled as a preview in index.json.
"""
import argparse
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'tools'), str(ROOT / 'tools/tests')]
from device_app_files import read_app_file
from device_hierarchy import dump_hierarchy
from media_device import APP, Device, wait_for
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin
from iptv_device import Station, make_streams

COMBINATIONS = [('portrait', 1, 'zh-CN', 'DARK'), ('landscape', 8, 'zh-CN', 'DARK'),
                ('portrait', 1, 'en', 'DARK'), ('portrait', 1, 'ar', 'DARK'),
                ('portrait', 1, 'zh-CN', 'LIGHT'), ('portrait', 1, 'zh-CN', 'NIGHT')]
BACKUPS = ['files/settings/local.json', 'files/settings/local.json.bak', 'shared_prefs/deck.xml', 'shared_prefs/deck.xml.bak'] + [
    'files/store/' + name + suffix for name in ('player', 'iptv') for suffix in ('.json', '.json.bak')]


def broadcast_json(device, command):
    raw = device.debug(command)
    # am quotes resultData without escaping JSON's inner quotes on this ROM.
    match = re.search(r'data="(\{.*\})"', raw)
    if not match:
        raise RuntimeError('The debug APK needs the ' + command + ' command: ' + raw)
    return json.loads(match.group(1))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--only', help='one combination, e.g. portrait-zh-CN-DARK')
    parser.add_argument('--tiles-only', action='store_true', help='only tiles.main/apps, in all eleven languages')
    parser.add_argument('--live-only', action='store_true', help='retake only working local television playback, preserving other captures')
    parser.add_argument('--port', type=int, default=8772)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    foreground = device.shell('dumpsys', 'activity', 'activities')
    resumed = next((s for s in foreground.splitlines() if 'mResumedActivity' in s), '')
    if APP not in resumed:
        raise RuntimeError('Device is in use outside ThinkOS: ' + resumed)
    original = broadcast_json(device, 'ui_state')
    apk_path = device.shell('pm', 'path', APP).strip().removeprefix('package:')
    apk_sha256 = device.shell('sha256sum', apk_path).split()[0]
    saved = {}
    captures = []
    failures = []
    redirects = []
    station = None
    published = False
    device.start_log()

    def tree():
        path = '/sdcard/thinkos-walkthrough.xml'
        dump_hierarchy(args.serial, path)
        try:
            return ET.fromstring(device.shell('cat', path))
        finally:
            device.shell('rm', '-f', path)

    def bounds(node):
        return list(map(int, re.findall(r'\d+', node.get('bounds', ''))))

    def screen():
        left, top, right, bottom = bounds(tree().find('node'))
        return right - left, bottom - top

    def tap(label, scroll=False):
        for _ in range(10 if scroll else 1):
            for node in tree().iter('node'):
                if label in (node.get('text'), node.get('content-desc')):
                    box = bounds(node)
                    if len(box) == 4 and box[2] > box[0] and box[3] > box[1]:
                        device.tap((box[0] + box[2]) // 2, (box[1] + box[3]) // 2)
                        time.sleep(.7)
                        return
            if not scroll:
                break
            width, height = screen()
            device.shell('input', 'swipe', width // 2, int(height * .82), width // 2, int(height * .28), 350)
        raise AssertionError('Missing visible control: ' + label)

    def back():
        device.shell('input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(.6)

    def page(key):
        device.open_page(key)
        time.sleep(.9)
        actual = broadcast_json(device, 'ui_state')['page']
        # NIGHT suppresses ambient/photo pages by product policy. Record the actual
        # result instead of bypassing that policy or pretending the requested page was shown.
        if theme == 'NIGHT' and actual == 'clock.face' and key in ('home.main', 'gallery.slideshow'):
            record = {'combination': prefix, 'requested': key, 'shown': actual,
                      'reason': 'NIGHT uses the sleep clock instead of ambient/photo pages'}
            if record not in redirects: redirects.append(record)
            return
        if actual != key:
            raise AssertionError('Requested ' + key + ', showing ' + actual)

    def shot(name, preview=False):
        filename = prefix + '-' + name + '.png'
        device.screenshot(args.output / filename)
        captures.append({'file': filename, 'page': name, 'actual_page': broadcast_json(device, 'ui_state')['page'],
                         'preview': preview, 'apk_sha256': apk_sha256})
        print(filename, flush=True)

    def attempt(name, action):
        try:
            action()
        except Exception as error:
            failures.append({'combination': prefix, 'page': name, 'error': str(error)})
            print('FAIL', prefix, name, str(error), flush=True)
            # Native dialogs outlive router page jumps. Close them before the next
            # observation so a failed label lookup cannot obscure the rest of a run.
            back()
            back()
            device.open_page('home.main')

    def tables(tag, plugin=None):
        language = 'zh-Hans' if tag.startswith('zh') else tag
        directory = ROOT / 'plugins' / plugin / 'i18n' if plugin else ROOT / 'shared/i18n/system'
        return json.loads((directory / (language + '.json')).read_text())

    def restore_file(path, content):
        if content is None:
            device.shell('run-as', APP, 'rm', '-f', path)
        else:
            parent = str(Path(path).parent)
            command = 'mkdir -p ' + shlex.quote(parent) + ' && base64 -d > ' + shlex.quote(path)
            subprocess.run(device.base + ['shell', '-T', shlex.join(['run-as', APP, 'sh', '-c', command])],
                           input=base64.b64encode(content), capture_output=True, check=True, timeout=30)

    with tempfile.TemporaryDirectory(prefix='thinkos-ui-walkthrough-') as temp:
        work = Path(temp)
        audio_directory = '/sdcard/Music/' + work.name
        try:
            device.shell('am', 'force-stop', APP)
            saved = {path: read_app_file(args.serial, path) for path in BACKUPS}
            device.shell('am', 'start', '-n', APP + '/.MainActivity')
            time.sleep(3)
            device.debug('hold_attention', value='ENGAGED')
            device.shell('mkdir', '-p', audio_directory)
            device.push('/tmp/thinkos-media-samples/audio-flac-16bit-44k.flac', audio_directory + '/sample.flac')
            fixture = work / 'ui-walkthrough'
            fixture.mkdir()
            (fixture / 'views').mkdir()
            pages = []
            for widget in ('sunrise', 'moon', 'calendar'):
                pages.append({'id': widget, 'view': 'views/' + widget + '.json', 'title': widget})
                (fixture / 'views' / (widget + '.json')).write_text(json.dumps({'layout': 'stacks', 'slots': {
                    'left': [{'widget': 'clock.' + widget}], 'right': [{'widget': 'clock.now'}]}}))
            (fixture / 'manifest.json').write_text(json.dumps({'id': 'ui-walkthrough', 'name': 'UI walkthrough',
                'version': '0.1.0', 'api': 8, 'permissions': [], 'pages': pages}))
            directory, _ = package_plugin(fixture, work / 'packages')
            publish_directory(directory, args.serial)
            published = True
            # The same fixture station used by iptv_device.py; no public channel list.
            streams = work / 'streams'
            streams.mkdir()
            make_streams(streams)
            station = Station(args.port, streams, lambda: datetime.now(timezone.utc))
            threading.Thread(target=station.serve_forever, daemon=True).start()
            device.adb('reverse', 'tcp:%d' % args.port, 'tcp:%d' % args.port)
            for key, value in {'source_1': f'http://127.0.0.1:{args.port}/lists/a.m3u', 'source_2': '',
                               'source_3': '', 'source_4': '', 'test': 'false', 'epg_url': ''}.items():
                device.debug('setting', scope='iptv', key=key, value=value)
            combinations = [('portrait', 1, tag, 'DARK') for tag in (
                'zh-CN', 'zh-TW', 'en', 'es', 'fr', 'pt', 'ru', 'ar', 'hi', 'bn', 'ur')] if args.tiles_only else COMBINATIONS
            for orientation, code, locale, theme in combinations:
                prefix = orientation + '-' + locale + '-' + theme
                if args.only and prefix != args.only:
                    continue
                device.shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', code)
                device.debug('locale', value=locale)
                device.debug('theme', value=theme)
                time.sleep(2)
                system = tables(locale)
                iptv = tables(locale, 'iptv')
                def live():
                    page('iptv.channels')
                    tap(iptv['tab.all'])
                    tap('2  Channel Two')  # list A's first channel deliberately has only a dead line.
                    playing, snapshot = wait_for(device, lambda state: state.get('status') == 'playing' and state.get('videoWidth', 0) > 0, 15, .3)
                    if not playing:
                        raise AssertionError('Fixture channel did not produce video: '+str(snapshot))
                    if broadcast_json(device, 'ui_state')['page'] != 'iptv.live':
                        raise AssertionError('Fixture channel did not open the live page')
                    width, height = screen()
                    device.tap(width // 2, height // 3)
                    time.sleep(.3)
                    shot('iptv.live')
                    device.debug('media_stop')
                if args.live_only:
                    attempt('iptv.live', live)
                    continue
                pages = ('tiles.main', 'tiles.apps') if args.tiles_only else (
                    'home.main', 'weather.detail', 'gallery.slideshow', 'tiles.main', 'tiles.apps', 'aero.main', 'cast.home', 'apps.main')
                for key in pages:
                    attempt(key, lambda key=key: (page(key), shot(key)))
                if args.tiles_only:
                    continue
                for variant in ('digital', 'analog', 'solar', 'world'):
                    attempt('clock.face-' + variant, lambda variant=variant: (
                        page('clock.face'), device.debug('variant', value=variant), time.sleep(.8), shot('clock.face-' + variant)))
                for widget in ('sunrise', 'moon', 'calendar'):
                    attempt(widget, lambda widget=widget: (page('ui-walkthrough.' + widget), shot('clock.' + widget)))
                player = tables(locale, 'player')
                for tab in ('music', 'video', 'recent', 'streams'):
                    attempt('player.library-' + tab, lambda tab=tab: (
                        page('player.library'), tap(player['variant.' + tab]), shot('player.library-' + tab)))
                attempt('player.audio', lambda: (device.load(audio_directory + '/sample.flac', kind='audio',
                    title='ThinkOS Test Track', repeat='one'), page('player.audio'), time.sleep(1),
                    shot('player.audio'), device.debug('media_stop')))
                iptv = tables(locale, 'iptv')
                for tab in ('all', 'groups', 'favorites', 'recent'):
                    attempt('iptv.channels-' + tab, lambda tab=tab: (
                        page('iptv.channels'), tap(iptv['tab.' + tab]), shot('iptv.channels-' + tab)))

                attempt('iptv.live', live)

                def panel():
                    page('home.main')
                    width, height = screen()
                    device.shell('input', 'swipe', width // 2, 2, width // 2, int(height * .55), 350)
                    time.sleep(.8)
                    shot('panel')
                    back()
                attempt('panel', panel)
                for scope in ('system', 'clock', 'weather', 'gallery', 'player', 'iptv'):
                    def settings(scope=scope):
                        page('home.main')
                        device.debug('settings', value=scope)
                        time.sleep(.8)
                        previous = None
                        for n in range(12):
                            current = tuple(node.get('text') for node in tree().iter('node') if node.get('text'))
                            if current == previous:
                                break
                            shot('settings-' + scope + '-' + str(n))
                            previous = current
                            width, height = screen()
                            device.shell('input', 'swipe', width // 2, int(height * .83), width // 2, int(height * .27), 350)
                            time.sleep(.3)
                    attempt('settings-' + scope, settings)

                def picker(scope, label, name):
                    page('home.main')
                    device.debug('settings', value=scope)
                    time.sleep(.6)
                    if scope == 'system': tap(system['setting_section_location'])
                    tap(label, scroll=True)
                    shot(name)
                    back()
                attempt('timezone', lambda: picker('clock', tables(locale, 'clock')['setting.zone1'], 'timezone'))
                attempt('place', lambda: picker('system', system['setting_location_name'], 'place'))

                def maintenance():
                    page('home.main')
                    device.debug('maintenance')
                    time.sleep(.5)
                    shot('maintenance')
                    candidates = {json.loads(path.read_text())['maintenance_diagnostics']
                                  for path in (ROOT / 'shared/i18n/system').glob('*.json')}
                    visible = {n.get('text') for n in tree().iter('node')}
                    diagnostic = next((text for text in candidates if text in visible), None)
                    if diagnostic is None:
                        raise AssertionError('Missing native diagnostics menu item')
                    tap(diagnostic)
                    shot('diagnostics')
                    back()
                attempt('maintenance', maintenance)
                attempt('safe-mode-banner', lambda: (page('clock.face'), device.debug('preview_safe_mode', value='true'),
                    time.sleep(.5), shot('safe-mode-banner', preview=True), device.debug('preview_safe_mode', value='false')))
        finally:
            if published:
                remove_plugin('ui-walkthrough', args.serial)
            device.debug('media_stop')
            device.shell('rm', '-f', audio_directory + '/sample.flac')
            device.shell('rmdir', audio_directory, check=False)
            device.shell('am', 'force-stop', APP)
            for path, content in saved.items():
                restore_file(path, content)
            # Verify saved user bytes before a restarted plugin legitimately updates a cache.
            restored = {path: read_app_file(args.serial, path) == content for path, content in saved.items()}
            device.shell('am', 'start', '-n', APP + '/.MainActivity')
            time.sleep(3)
            device.debug('locale', value=original['locale'])
            device.shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', original['orientation'])
            device.debug('theme', **({'value': original['theme']} if original['theme'] else {}))
            device.debug('lux', **({'value': float(original['lux'])} if original['lux'] is not None else {}))
            device.debug('hold_attention', **({'value': original['attention']} if original['attention'] else {}))
            device.debug('page', value=original['page'])
            device.debug('variant', value=original['variant'])
            device.debug('preview_safe_mode', value=str(original['safeMode']).lower())
            if station:
                station.shutdown()
                station.server_close()
            device.adb('reverse', '--remove', 'tcp:%d' % args.port, check=False)
            final = broadcast_json(device, 'ui_state')
            index_path = args.output / 'index.json'
            previous = json.loads(index_path.read_text()) if (args.only or args.live_only) and index_path.exists() else {}
            selected = args.only + '-' if args.only else ''
            if args.live_only:
                replaced = {c['file'] for c in captures}
                selected_combos = {c['file'].removesuffix('-iptv.live.png') for c in captures}
                old_captures = [c for c in previous.get('captures', []) if c['file'] not in replaced]
                old_failures = [f for f in previous.get('failures', []) if not (f['page']=='iptv.live' and f['combination'] in selected_combos)]
                old_redirects = previous.get('policy_redirects', [])
            else:
                old_captures = [c for c in previous.get('captures', []) if not c['file'].startswith(selected)]
                old_failures = [f for f in previous.get('failures', []) if f['combination'] != args.only]
                old_redirects = [r for r in previous.get('policy_redirects', []) if r['combination'] != args.only]
            index_path.write_text(json.dumps({'captures': old_captures + captures, 'failures': old_failures + failures,
                'policy_redirects': old_redirects + redirects, 'before': original, 'after': final,
                'restored_files': restored}, ensure_ascii=False, indent=2) + '\n')
            log_path = args.output / ('logcat-live.txt' if args.live_only else 'logcat.txt')
            log_path.write_text('\n'.join(device.log_since(0)) + '\n')
            device.stop_log()
            if not all(restored.values()):
                raise AssertionError('Saved user state did not restore: ' + str(restored))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
