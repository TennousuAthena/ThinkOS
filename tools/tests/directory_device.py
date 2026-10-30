#!/usr/bin/env python3
"""Application-only directory installer regression; uses two temporary development overrides."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/directory-validation/installer')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ['adb', '-s', args.serial]
    def adb(*values):
        return subprocess.check_output(base + list(values), timeout=30)
    def shell(*values):
        return adb('shell', *values)
    def logs():
        return adb('logcat', '-d', '-s', 'ThinkOS:V', 'AndroidRuntime:E').decode()
    def diagnostics():
        shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'state')
        return logs().split('DEBUG state=')[-1]
    def wait_for(text, baseline='', seconds=10):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            current = logs()
            delta = current[len(baseline):] if current.startswith(baseline) else current
            if text in delta:
                return delta
            time.sleep(.15)
        raise AssertionError('Missing log: ' + text)
    results = []
    def passed(message):
        results.append(message)
        print('PASS ' + message, flush=True)
    def pid():
        return shell('pidof', 'moe.kiwi.thinkos').decode().strip()
    code = '''local count=0
      tos.on("start",function()
        tos.log.info("DIR START %s")
        tos.timer.every(.2,function() count=count+1; tos.log.info("DIR TICK "..count) end)
      end)'''
    original_pid = pid()
    findings = {'initialPid': original_pid}
    try:
        with tempfile.TemporaryDirectory(prefix='thinkos-directory-') as temporary:
            source = Path(temporary) / 'probe'
            shutil.copytree(ROOT / 'tools/tests/fixtures/runtime-probe', source)
            manifest = json.loads((source / 'manifest.json').read_text())
            manifest['id'] = 'directory-probe'
            manifest['permissions'] = []
            def package(version, text=None):
                manifest['version'] = version
                (source / 'manifest.json').write_text(json.dumps(manifest))
                (source / 'main.lua').write_text(text if text is not None else code % version)
                return package_plugin(source, Path(temporary) / 'out')[0]
            baseline = logs()
            publish_directory(package('1.0.0'), args.serial)
            wait_for('loaded 1.0.0', baseline)
            assert pid() == original_pid
            passed('directory publication loads without restarting the process')

            baseline = logs()
            candidate = package('1.0.1')
            (candidate / 'main.lua').write_text('-- altered after index generation\n')
            publish_directory(candidate, args.serial)
            wait_for('file hash/size mismatch', baseline)
            assert 'id=directory-probe, version=1.0.0' in diagnostics()
            assert 'DIR START 1.0.1' not in logs()[len(baseline):]
            wait_for('DIR TICK ', baseline)
            passed('wrong file hash/size retains the running version and timers')

            baseline = logs()
            publish_directory(package('1.0.1'), args.serial)
            wait_for('loaded 1.0.1', baseline)
            assert pid() == original_pid
            passed('a valid directory replaces the previous version with unchanged PID')

            baseline = logs()
            publish_directory(package('1.0.2', 'tos.on("start",function() while true do end end)'), args.serial)
            wait_for('plugin CPU budget exceeded', baseline)
            assert 'id=directory-probe, version=1.0.1' in diagnostics()
            wait_for('DIR TICK ', baseline)
            passed('an infinite-loop candidate is budget-terminated while the previous version runs')

            baseline = logs()
            shell('run-as', 'moe.kiwi.thinkos', 'mv', 'files/dev-plugins/directory-probe', 'files/dev-plugins/.previous-directory-probe')
            # Publishing another plugin forces a scan during a deliberately extended rename gap.
            trigger = Path(temporary) / 'trigger'
            shutil.copytree(source, trigger)
            trigger_manifest = dict(manifest, id='directory-trigger', version='1.0.0')
            (trigger / 'manifest.json').write_text(json.dumps(trigger_manifest))
            (trigger / 'main.lua').write_text('tos.on("start",function() tos.log.info("DIR TRIGGER") end)')
            trigger_dir, _ = package_plugin(trigger, Path(temporary) / 'trigger-out')
            publish_directory(trigger_dir, args.serial)
            wait_for('DIR TRIGGER', baseline)
            assert 'id=directory-probe, version=1.0.1' in diagnostics()
            delta = wait_for('DIR TICK ', baseline)
            assert 'DIR START ' not in delta
            shell('run-as', 'moe.kiwi.thinkos', 'mv', 'files/dev-plugins/.previous-directory-probe', 'files/dev-plugins/directory-probe')
            passed('another publication during a prolonged rename gap does not unload or restart the old Lua instance')

            baseline = logs()
            publish_directory(package('1.0.3'), args.serial)
            wait_for('loaded 1.0.3', baseline)
            shell('run-as', 'moe.kiwi.thinkos', 'mv', 'files/dev-plugins/directory-probe', 'files/dev-plugins/.previous-directory-probe')
            shell('cmd', 'package', 'set-home-activity', 'org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher')
            shell('am', 'force-stop', 'moe.kiwi.thinkos')
            baseline = logs()
            shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity')
            wait_for('loaded 1.0.3', baseline)
            shell('run-as', 'moe.kiwi.thinkos', 'test', '-d', 'files/dev-plugins/directory-probe')
            shell('run-as', 'moe.kiwi.thinkos', 'test', '!', '-e', 'files/dev-plugins/.previous-directory-probe')
            passed('process restart restores the interrupted replacement backup before plugin loading')
            assert not shell('run-as', 'moe.kiwi.thinkos', 'find', 'files', '-name', '*.tpk').strip()
            passed('application storage contains no retired plugin archives')
            findings['restartPid'] = pid()
    finally:
        findings['passed'] = results
        (args.output / 'results.json').write_text(json.dumps(findings, ensure_ascii=False, indent=2) + '\n')
        (args.output / 'logcat.txt').write_text(logs())
        remove_plugin('directory-probe', args.serial)
        remove_plugin('directory-trigger', args.serial)
        shell('cmd', 'package', 'set-home-activity', 'moe.kiwi.thinkos/.MainActivity')
        shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity')


if __name__ == '__main__':
    main()
