#!/usr/bin/env python3
"""App-only debug-device regression. Requires http_fixture.py on localhost:8769.

Exercises public plugin APIs, cancellation, startup rollback and persisted native
exit attribution. Native exits are simulated by an orphaned marker, not SIGSEGV.
Only the runtime-probe override and its test store are removed on completion.
"""
import argparse
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin
from device_app_files import read_app_file


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/pre-m1/device-runtime')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = ['adb', '-s', args.serial]

    def adb(*values, data=None, check=True):
        result = subprocess.run(base + list(values), input=data, capture_output=True, timeout=20)
        if check and result.returncode:
            raise RuntimeError(result.stderr.decode() + result.stdout.decode())
        return result.stdout

    def shell(*values, **kw):
        return adb('shell', *values, **kw)

    def logs():
        return adb('logcat', '-d', '-s', 'ThinkOS:V', 'AndroidRuntime:E').decode()

    def state():
        shell('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', 'state')
        return logs().split('DEBUG state=')[-1]

    def wait_for(text, baseline='', seconds=10):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            current = logs()
            delta = current[len(baseline):] if current.startswith(baseline) else current
            if text in delta:
                return delta
            time.sleep(.2)
        raise AssertionError('Missing log: ' + text)

    def upload(relative, data):
        shell(f"run-as moe.kiwi.thinkos sh -c 'cat > {relative}'", data=data)

    saved_history = read_app_file(args.serial, 'files/native-failures.json')
    findings = []

    def passed(message):
        findings.append(message)
        print('PASS ' + message, flush=True)

    try:
        adb('reverse', 'tcp:8769', 'tcp:8769')
        with tempfile.TemporaryDirectory(prefix='thinkos-device-') as temporary:
            source = Path(temporary) / 'runtime-probe'
            shutil.copytree(ROOT / 'tools/tests/fixtures/runtime-probe', source)
            manifest = json.loads((source / 'manifest.json').read_text())

            def push(version, code, permissions=None):
                manifest['version'] = version
                manifest['permissions'] = ['network'] if permissions is None else permissions
                (source / 'manifest.json').write_text(json.dumps(manifest))
                (source / 'main.lua').write_text(code)
                package, digest = package_plugin(source, Path(temporary) / 'out')
                publish_directory(package, args.serial)
                return digest

            baseline = logs()
            push('1.1.0', (ROOT / 'tools/tests/fixtures/runtime-probe/main.lua').read_text())
            delta = wait_for('PROBE ALL PASSED', baseline)
            assert delta.count('[store:runtime-probe] persisted coalesced snapshot') == 1
            assert delta.count('[plugin:runtime-probe] loaded 1.1.0') == 1
            passed('59KB HTTP JSON, timer fairness, pcall await, ordinary error retention, LAN redirects, 1MiB cap, one store flush')

            baseline = logs()
            push('1.1.1', '''tos.on("start", function()
              tos.timer.after(.05, function()
                tos.log.info("PROBE old HTTP armed")
                tos.http.get("http://127.0.0.1:8769/slow")
                tos.log.error("PROBE LATE HTTP callback")
              end)
              tos.timer.after(.05, function()
                tos.timer.sleep(2)
                tos.log.error("PROBE LATE sleep callback")
              end)
            end)''')
            wait_for('PROBE old HTTP armed', baseline)
            push('1.1.2', 'tos.on("start", function() tos.state.set{status="stable"} end)')
            wait_for('loaded 1.1.2', baseline)
            time.sleep(2.2)
            assert 'PROBE LATE' not in logs()[len(baseline):]
            passed('hot replacement cancels HTTP and sleep continuations')

            baseline = logs()
            push('1.1.3', 'tos.on("start", function() tos.timer.sleep(.1); error("PROBE expected startup rollback") end)')
            wait_for('PROBE expected startup rollback', baseline)
            assert 'id=runtime-probe, version=1.1.2' in state()
            passed('asynchronous startup failure retains previous version')

            baseline = logs()
            push('1.1.4', '''tos.on("start", function()
              local ok = pcall(function() tos.http.get("http://127.0.0.1:8769/weather") end)
              assert(not ok)
              tos.timer.sleep(.1)
              tos.log.info("PROBE permission and async start PASSED")
            end)''', permissions=[])
            wait_for('loaded 1.1.4', baseline)
            assert 'PROBE permission and async start PASSED' in logs()[len(baseline):]
            passed('network permission enforced and asynchronous startup commits')

            baseline = logs()
            push('1.1.5', 'tos.on("start", function() tos.log.info("PROBE pending start armed"); tos.timer.sleep(2); tos.log.error("PROBE LATE removed startup") end)')
            wait_for('PROBE pending start armed', baseline)
            remove_plugin('runtime-probe', args.serial)
            time.sleep(2.5)
            assert 'PROBE LATE' not in logs()[len(baseline):]
            assert 'id=runtime-probe' not in state()
            passed('removing a suspended startup cancels candidate and active instance')

            baseline = logs()
            digest = push('1.1.6', 'tos.on("start", function() assert(tos.store.get("burst")==20); tos.state.set{status="native marker probe"} end)')
            wait_for('loaded 1.1.6', baseline)
            shell('cmd', 'package', 'set-home-activity', 'org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher')
            for index in range(3):
                shell('am', 'force-stop', 'moe.kiwi.thinkos')
                if index == 0:
                    shell('run-as', 'moe.kiwi.thinkos', 'cp', 'files/store/runtime-probe.json', 'files/store/runtime-probe.json.bak')
                    upload('files/store/runtime-probe.json', b'{interrupted-write')
                key = ('runtime-probe@' + digest).encode()
                marker = struct.pack('>II', 0x544F534E, len(key)) + key
                upload('files/native-flight.bin', marker.ljust(512, b'\0'))
                baseline = logs()
                shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity')
                wait_for('Unclean native execution attributed to plugin runtime-probe', baseline)
                if index < 2:
                    wait_for('[plugin:runtime-probe] loaded 1.1.6', baseline)
                else:
                    deadline = time.monotonic() + 10
                    while 'id=runtime-probe, version=1.1.6, bytes=0, disabled=true' not in state():
                        if time.monotonic() > deadline:
                            raise AssertionError('quarantined plugin did not reach the catalog')
                        time.sleep(.2)
                if index == 0:
                    restored = json.loads(adb('exec-out', 'run-as', 'moe.kiwi.thinkos', 'cat', 'files/store/runtime-probe.json'))
                    assert restored['burst'] == 20
                    passed('interrupted AtomicFile store write restores the backup before plugin startup')
            result = state()
            assert 'id=runtime-probe, version=1.1.6, bytes=0, disabled=true' in result
            assert 'id=clock' in result and 'id=home' in result and 'safeMode=false' in result
            passed('three persisted orphan markers quarantine only the failing digest; clock/home remain healthy')
    finally:
        (args.output / 'logcat.txt').write_text(logs())
        # Stop before restoring the saved attribution history, then restore the application HOME.
        shell('cmd', 'package', 'set-home-activity', 'org.lineageos.trebuchet/com.android.launcher3.searchlauncher.SearchLauncher', check=False)
        shell('am', 'force-stop', 'moe.kiwi.thinkos', check=False)
        remove_plugin('runtime-probe', args.serial)
        shell('run-as', 'moe.kiwi.thinkos', 'rm', '-f', 'files/native-flight.bin', 'files/store/runtime-probe.json', 'files/store/runtime-probe.json.bak', check=False)
        if saved_history:
            upload('files/native-failures.json', saved_history)
        else:
            shell('run-as', 'moe.kiwi.thinkos', 'rm', '-f', 'files/native-failures.json', check=False)
        shell('cmd', 'package', 'set-home-activity', 'moe.kiwi.thinkos/.MainActivity', check=False)
        shell('am', 'start', '-n', 'moe.kiwi.thinkos/.MainActivity', check=False)
        adb('reverse', '--remove', 'tcp:8769', check=False)
        (args.output / 'results.json').write_text(json.dumps({'passed': findings}, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
