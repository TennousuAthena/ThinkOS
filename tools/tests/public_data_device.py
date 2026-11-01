#!/usr/bin/env python3
"""ADR-027 device regression: public data, image grants, commands and widget reuse; restores overrides."""
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
sys.path.insert(0, str(ROOT / 'tools'))
from app_network_block import AppNetworkBlock
from device_hierarchy import dump_hierarchy
from package_plugins import file_index, package_plugin, validate_plugin
from plugin_push import publish_directory, remove_plugin

APP = 'moe.kiwi.thinkos'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/public-data/device')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ['adb', '-s', args.serial]
    report = {'complete': False, 'passed': []}
    fixtures = ROOT / 'tools/tests/fixtures'

    def adb(*values):
        return subprocess.run(base + list(map(str, values)), capture_output=True, timeout=60, check=True).stdout
    def shell(*values): return adb('shell', '-T', shlex.join(list(map(str, values))))
    def debug(command, value=None, kind='--es'):
        values = ['am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', command]
        if value is not None: values += [kind, 'value', value]
        shell(*values)
    def pid():
        # pidof exits non-zero while the app is not running, e.g. right after an install.
        try: return shell('pidof', APP).decode().strip()
        except subprocess.CalledProcessError: return ''
    def logs(): return adb('logcat', '-d', '--pid=' + pid(), '-s', 'ThinkOS:V', 'AndroidRuntime:E').decode(errors='replace')
    def passed(text): report['passed'].append(text); print('PASS ' + text, flush=True)
    def wait(test, message, seconds=25):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            if test(): return
            time.sleep(.3)
        raise AssertionError(message)
    def publish(source):
        with tempfile.TemporaryDirectory(prefix='thinkos-public-') as out:
            directory, _ = package_plugin(source, Path(out))
            publish_directory(directory, args.serial)
    def page(key):
        debug('page', key)
        wait(lambda: state_has('page=' + key + ','), 'Cannot open ' + key)
    def state_has(text):
        debug('state')
        return text in logs().split('DEBUG state=')[-1]
    def shot(name): (args.output / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    def screen_text():
        path = '/data/local/tmp/thinkos-public-hierarchy.xml'
        dump_hierarchy(args.serial, path)
        try: return shell('cat', path).decode(errors='replace')
        finally: shell('rm', '-f', path)
    def active_digest(plugin):
        # The logcat ring can evict lines on this device, so never infer a reload by counting them.
        return json.loads(shell('run-as', APP, 'cat', 'files/plugins/installed.json'))[plugin]['sha256']
    def bundled_digest(plugin):
        manifest, files = validate_plugin(ROOT / 'plugins' / plugin)
        return file_index(manifest, files)['digest']
    def probe(label):
        found = re.findall(r'\[plugin:public-script\] public-probe ' + re.escape(label) + r' ?(.*)', logs())
        return found[-1].strip() if found else None

    subprocess.run(base + ['install', '-r', str(ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk')], check=True, capture_output=True)
    shell('am', 'start', '-n', APP + '/.MainActivity')
    wait(pid, 'App did not start')
    assert not shell('run-as', APP, 'ls', 'files/dev-plugins').strip(), 'Preserve existing development overrides'
    adb('logcat', '-c')
    try:
        debug('hold_attention', 'AWAKE'); debug('lux', '20', '--ef'); debug('theme', 'DARK'); debug('orientation', '0', '--ei')
        publish(fixtures / 'public-provider'); publish(fixtures / 'public-reader'); publish(fixtures / 'public-script')
        wait(lambda: all(f'[plugin:{name}] loaded' in logs() for name in ('public-provider', 'public-reader', 'public-script')), 'Fixtures did not load')
        passed('consumers install and load without any network or library permission')

        # Lua surface: every refusal is an error value, never a crash.
        wait(lambda: probe('reserved') is not None, 'Script probe did not run')
        assert probe('undeclared') == 'err=undeclared', probe('undeclared')
        assert probe('wrong') == 'err=shape_mismatch', probe('wrong')
        assert probe('call-private-weather') == 'nil not_public', probe('call-private-weather')
        assert probe('call-undeclared') == 'nil undeclared', probe('call-undeclared')
        assert probe('reserved') == 'false', probe('reserved')
        passed('tos.data and tos.command refuse undeclared, mismatched and private targets; uses cannot be overwritten')
        # A hostile provider cannot throw into, or exhaust, the consumer that reads it.
        wait(lambda: probe('bomb') is not None and probe('deep') is not None and probe('secure') is not None, 'Provider probes did not run')
        assert probe('deep') == 'err=invalid', probe('deep')
        assert probe('bomb') == 'err=too_large', probe('bomb')
        assert probe('secure') == 'err=permission_required', probe('secure')
        assert 'public-script] Lua' not in logs() and 'script error' not in logs()
        passed('nested or oversized exports return errors to the consumer; requires gates a permissionless reader')
        wait(lambda: re.search(r'public-probe data daily photos=[1-9]', logs()), 'Daily list never reached Lua', 90)
        passed('tos.data.get reads the Bing list after a data event')

        # Declarative consumer: no Lua and no permissions, yet it shows the provider's images.
        page('public-reader.stage')
        wait(lambda: '[image:public-reader] displayed' in logs(), 'Consumer carousel did not display', 60)
        time.sleep(1); shot('stage-carousel')
        text = screen_text()
        assert 'no-shape no-provider' in text, 'Unusable aliases must stay blank and take their defaults'
        wait(lambda: 'uses.wrong -> gallery.daily: shape_mismatch' in logs() and 'uses.absent -> no-such.thing: missing' in logs(), 'Unusable links were not logged')
        debug('state')
        wait(lambda: "unresolved=[wrong:shape_mismatch, absent:missing]" in logs(), 'Diagnostics do not name unusable links')
        passed('a permissionless view binds another plugin\'s data and images; unusable links stay blank and are diagnosed')

        # A composed URL is the consumer's own request and cannot borrow the provider's network grant.
        page('public-reader.composed')
        wait(lambda: 'network permission required' in logs() or '[image:public-reader] network' in logs(), 'Composed URL borrowed the provider grant')
        passed('a composed image URL does not borrow the provider\'s network permission')

        # Widgets reused in a slot and through a tile alternate.
        page('public-reader.embed'); time.sleep(2); shot('embed-split')
        wait(lambda: '[image:gallery] displayed' in logs(), 'Referenced gallery widget did not display under its own grant', 45)
        passed('focus/split/stage slots embed another plugin\'s widget under that plugin\'s state and grant')
        page('public-reader.grid'); time.sleep(2); shot('grid-alternates')
        assert 'glance in tiles' not in logs(), 'Tile container fell back to the glance view'
        passed('a tile container picks the widget\'s tile alternate instead of an empty tile')

        # Commands reach the provider through its cooldown.
        page('public-script.main')
        wait(lambda: probe('call-cooldown') is not None, 'Command probe did not run')
        assert probe('call-public') == 'true', probe('call-public')
        assert probe('call-cooldown') == 'nil cooldown', probe('call-cooldown')
        assert probe('call-args') == 'true', probe('call-args')
        assert probe('call-provider-private') == 'nil not_public', probe('call-provider-private')
        wait(lambda: 'public-probe received ping 7 plugin public-script' in logs(), 'Provider did not receive the arguments and caller')
        wait(lambda: 'Bing gallery refreshed' in logs(), 'Public command did not reach the provider', 45)
        passed('a declared public command reaches its provider once; the second call is refused by the cooldown')

        # A same-contract upgrade must not disturb consumers: values persist and images keep advancing.
        page('public-reader.stage')
        wait(lambda: '[image:public-reader] displayed' in logs(), 'Consumer carousel did not return', 45)
        with tempfile.TemporaryDirectory(prefix='thinkos-public-upgrade-') as temporary:
            source = Path(temporary) / 'gallery'
            shutil.copytree(ROOT / 'plugins/gallery', source)
            (source / 'main.lua').write_text((source / 'main.lua').read_text() + '\n-- upgraded\n')
            adb('logcat', '-c')
            publish(source)
            wait(lambda: active_digest('gallery') != bundled_digest('gallery'), 'Upgrade did not load')
            wait(lambda: '[image:public-reader] displayed' in logs(), 'Consumer images stalled after the provider upgraded', 120)
            assert 'public-probe data daily' not in logs(), 'Unchanged data must not notify consumers across an upgrade'
            assert 'uses.daily ->' not in logs(), 'Consumer lost its provider during the upgrade'
            remove_plugin('gallery', args.serial)
            wait(lambda: active_digest('gallery') == bundled_digest('gallery'), 'Provider did not return to its bundled version')
        passed('a provider upgrade with the same contract keeps values and the consumer carousel keeps advancing')

        # Upgrading the provider to a new shape disconnects consumers; restoring reconnects them.
        with tempfile.TemporaryDirectory(prefix='thinkos-public-shape-') as temporary:
            source = Path(temporary) / 'gallery'
            shutil.copytree(ROOT / 'plugins/gallery', source)
            manifest = json.loads((source / 'manifest.json').read_text())
            manifest['exports']['daily']['shape'] = 'photos/2'
            (source / 'manifest.json').write_text(json.dumps(manifest))
            adb('logcat', '-c')
            publish(source)
            wait(lambda: 'uses.daily -> gallery.daily: shape_mismatch' in logs(), 'Consumer kept reading an incompatible export')
            wait(lambda: 'public-probe data daily err=shape_mismatch' in logs(), 'Script consumer was not told the export changed')
        passed('a provider upgrade with a new shape disconnects consumers and tells them')
        adb('logcat', '-c')
        remove_plugin('gallery', args.serial)
        wait(lambda: re.search(r'public-probe data daily photos=[1-9]', logs()), 'Consumers did not reconnect after the provider returned')
        passed('restoring the provider reconnects consumers without restarting them')

        # All of it works with no route out of the app, from local state.
        network = AppNetworkBlock(args.serial).start()
        try:
            adb('logcat', '-c')
            page('public-reader.stage')
            time.sleep(2); shot('stage-offline')
            assert 'FATAL' not in logs()
            debug('state')
            wait(lambda: 'id=public-reader' in logs(), 'Diagnostics unavailable offline')
            shell('am', 'force-stop', APP)
            adb('logcat', '-c')
            shell('am', 'start', '-n', APP + '/.MainActivity')
            wait(pid, 'App did not restart offline')
            debug('hold_attention', 'AWAKE'); debug('lux', '20', '--ef'); debug('theme', 'DARK'); debug('orientation', '0', '--ei')
            wait(lambda: re.search(r'public-probe data daily photos=[1-9]', logs()), 'Cached public data missing after an offline restart', 45)
            page('public-reader.stage')
            wait(lambda: '[image:public-reader] displayed' in logs(), 'Cached images missing after an offline restart', 45)
            time.sleep(1); shot('stage-offline-restart')
        finally:
            network.stop()
        passed('after an offline restart the consumer reads cached public data and shows cached images')
        report['complete'] = True
    finally:
        (args.output / 'logcat.txt').write_text(logs() if pid() else '')
        for plugin in ('public-reader', 'public-script', 'public-provider', 'gallery'):
            remove_plugin(plugin, args.serial)
        debug('orientation', '10', '--ei'); debug('hold_attention'); debug('lux'); debug('theme')
        (args.output / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    main()
