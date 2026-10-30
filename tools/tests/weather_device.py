#!/usr/bin/env python3
"""Exercise production weather via real public HTTP, JNI, cache and hot directory replacement."""
import argparse
import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from device_app_files import read_app_file
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/weather/data')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    serial, probe = args.serial, 'weather-probe'
    adb_base = ['adb', '-s', serial]
    report = {'complete': False}
    requests = []
    marker = 'WEATHER PROBE ' + uuid.uuid4().hex + ' '

    def adb(*values):
        return subprocess.run(adb_base + list(values), capture_output=True, check=True, timeout=30).stdout

    def shell(*values): return adb('shell', '-T', shlex.join(map(str, values)))

    def debug(command, value=None):
        values = ['am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command', command]
        if value is not None: values += ['--es', 'value', value]
        return shell(*values)

    initial_pid = shell('pidof', 'moe.kiwi.thinkos').decode().strip()
    assert initial_pid, 'Start the debug APK first'
    assert not shell('run-as', 'moe.kiwi.thinkos', 'ls', 'files/dev-plugins').strip(), 'Preserve existing development overrides'
    saved = {path: read_app_file(serial, path) for path in ('files/settings/local.json', 'files/settings/server.json')}
    sample = json.loads((ROOT / 'tools/tests/fixtures/weather/forecast.json').read_text())
    delta = int(time.time() // 86400 * 86400 - 28800) - sample['daily']['time'][0]
    for kind in ('hourly', 'daily'):
        sample[kind]['time'] = [stamp + delta for stamp in sample[kind]['time']]
    sample['current']['time'] = int(time.time() // 900 * 900)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            route = urlsplit(self.path).path
            requests.append({'url': self.path, 'at': time.time()})
            if route == '/slow': time.sleep(3.5)
            if route == '/delayed': time.sleep(.5)
            body = copy.deepcopy(sample)
            if route == '/dst':
                delta = 1793332800 - body['daily']['time'][0]
                for kind in ('hourly', 'daily'):
                    body[kind]['time'] = [stamp + delta for stamp in body[kind]['time']]
                body.update(timezone='America/New_York', utc_offset_seconds=-14400)
                body['current']['time'] = 1793376000
            if route == '/missing': body['daily']['temperature_2m_min'][2] = None
            data = b'<html>bad gateway</html>' if route == '/html' else json.dumps(body).encode()
            self.send_response(503 if route == '/503' else 200)
            self.send_header('Content-Type', 'text/html' if route == '/html' else 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            try: self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError): pass
        def log_message(self, *_): pass

    server = ThreadingHTTPServer(('127.0.0.1', 8876), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    report['initialPid'] = initial_pid
    try:
        # Only this test's development directory will be published or removed.
        debug('hold_attention', 'AWAKE')
        adb('reverse', 'tcp:8876', 'tcp:8876')
        with tempfile.TemporaryDirectory(prefix='thinkos-weather-probe-') as temporary:
            source = Path(temporary) / 'source'
            shutil.copytree(ROOT / 'plugins/weather', source)
            (source / 'production.lua').write_text((source / 'main.lua').read_text())
            wrapper = (ROOT / 'tools/tests/fixtures/weather/probe.lua').read_text().replace('FIXTURE_ROOT', 'http://127.0.0.1:8876')
            wrapper = wrapper.replace('WEATHER PROBE ', marker)
            (source / 'main.lua').write_text(wrapper)
            manifest = json.loads((source / 'manifest.json').read_text())
            manifest['id'] = probe
            manifest['settings']['endpoint']['default'] = 'http://127.0.0.1:8876/good'
            manifest['settings']['retry_initial']['default'] = 10
            (source / 'manifest.json').write_text(json.dumps(manifest))
            directory, digest = package_plugin(source, Path(temporary) / 'output')
            report['digest'] = digest
            publish_directory(directory, serial)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            logs = adb('logcat', '-d', '--pid=' + initial_pid, '-s', 'ThinkOS:V', 'AndroidRuntime:E').decode()
            if marker + 'FAILED' in logs: raise AssertionError(logs.split(marker + 'FAILED', 1)[1])
            if marker + 'ALL PASSED' in logs: break
            time.sleep(.5)
        else: raise AssertionError('Weather probe did not finish')
        assert initial_pid == shell('pidof', 'moe.kiwi.thinkos').decode().strip(), 'Hot loading restarted the application'
        current_run = logs[logs.index(marker):]
        assert 'plugin CPU budget exceeded' not in current_run and 'FATAL EXCEPTION' not in current_run
        report['passed'] = [line.split(marker + 'PASS ', 1)[1] for line in logs.splitlines() if marker + 'PASS ' in line]
        debug('hold_attention', 'ASLEEP')
        requests_before_replace = len(requests)
        # Replace a running instance with the unwrapped production module. Its cached start
        # must finish before HTTP, and directory replacement must not restart Android.
        with tempfile.TemporaryDirectory(prefix='thinkos-weather-replace-') as temporary:
            source = Path(temporary) / 'source'
            shutil.copytree(ROOT / 'plugins/weather', source)
            manifest = json.loads((source / 'manifest.json').read_text())
            manifest['id'] = probe
            manifest['version'] = '0.1.1'
            manifest['settings']['endpoint']['default'] = 'http://127.0.0.1:8876/good'
            (source / 'manifest.json').write_text(json.dumps(manifest))
            directory, _ = package_plugin(source, Path(temporary) / 'output')
            publish_directory(directory, serial)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            logs = adb('logcat', '-d', '--pid=' + initial_pid, '-s', 'ThinkOS:V').decode()
            if '[plugin:weather-probe] loaded 0.1.1;' in logs: break
            time.sleep(.2)
        else: raise AssertionError('Production weather replacement did not finish')
        assert initial_pid == shell('pidof', 'moe.kiwi.thinkos').decode().strip(), 'Hot replacement restarted Android'
        report['passed'].append('hot replacement starts production weather from cache without changing PID')
        time.sleep(1.5)
        assert len(requests) == requests_before_replace, 'Replaced weather ignored the current ASLEEP state'
        debug('hold_attention', 'AWAKE')
        deadline = time.monotonic() + 4
        while len(requests) == requests_before_replace and time.monotonic() < deadline: time.sleep(.1)
        assert len(requests) > requests_before_replace, 'Replacement did not refresh on wake'
        while time.monotonic() < deadline:
            logs = adb('logcat', '-d', '--pid=' + initial_pid, '-s', 'ThinkOS:V').decode()
            replaced = logs.rsplit('[plugin:weather-probe] loaded 0.1.1;', 1)[-1]
            if '[plugin:weather-probe] weather refresh ok' in replaced: break
            time.sleep(.1)
        else: raise AssertionError('Replacement wake refresh did not complete')
        assert 'plugin CPU budget exceeded' not in logs[logs.index(marker):]
        report['passed'].append('hot replacement inherits ASLEEP and only refreshes after the next wake')
        report['httpRequests'] = requests
        debug('state')
        report['diagnostics'] = adb('logcat', '-d', '--pid=' + initial_pid, '-s', 'ThinkOS:V').decode().split('DEBUG state=')[-1].strip()
        report['complete'] = True
        print('\n'.join('PASS ' + item for item in report['passed']), flush=True)
    finally:
        report['settingsUnchanged'] = all(read_app_file(serial, path) == value for path, value in saved.items())
        report['logcat'] = 'logcat.txt'
        (args.output / 'logcat.txt').write_bytes(adb('logcat', '-d', '--pid=' + initial_pid, '-s', 'ThinkOS:V', 'AndroidRuntime:E'))
        remove_plugin(probe, serial)
        # Store writes coalesce for two seconds; drain them before removing the probe's KV.
        time.sleep(2.2)
        shell('run-as', 'moe.kiwi.thinkos', 'rm', '-f', 'files/store/' + probe + '.json')
        adb('reverse', '--remove', 'tcp:8876')
        debug('hold_attention')
        server.shutdown(); server.server_close()
        (args.output / 'results.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
        assert report['settingsUnchanged'], 'The read-only weather probe changed user settings'


if __name__ == '__main__': main()
