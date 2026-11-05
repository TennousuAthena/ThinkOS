#!/usr/bin/env python3
"""Native, system-only CCADB TLS samples; no module activation or audible tests.

Installs the current debug/test APKs as needed and restores application files/UI.
Run baseline now; add/replace only after manual module installation and reboot.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'), str(ROOT/'tools/tests')]
from device_app_files import read_app_file
from device_recovery import capture, restore, PATHS, OPTIONAL_PATHS
from media_device import APP, Device
from ui_walkthrough import broadcast_json


def validate_samples(mode, observations, sites):
    expected = {site['id']: site for site in sites}
    assert mode in ('baseline', 'add', 'replace')
    assert len(observations) == len(expected) and {item['id'] for item in observations} == set(expected), 'Missing or duplicate sample'
    for item in observations:
        assert item['expected_root_sha256'] == expected[item['id']]['root_sha256'], 'Unexpected sample root'
    control = next(item for item in observations if item['id'] == 'isrg-x1')
    def direct(item):
        return (item['outcome'] == 'accepted' and item.get('expected_root_is_anchor') is True
                and item.get('anchor_sha256') == expected[item['id']]['root_sha256'])
    assert direct(control), 'Old-root control did not verify'
    if mode != 'baseline':
        assert all(direct(item) for item in observations), 'A sample did not verify to its intended root'
    return mode != 'baseline'


def main(argv=None):
    os.environ['ADB_MDNS'] = '0'
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--expect', choices=('baseline', 'add', 'replace'), default='baseline')
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/certs/CERT-3/tls-current')
    args = parser.parse_args(argv)
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output/'results.json').exists():
        parser.error('Use a fresh output directory to preserve previous evidence')
    d = Device(args.serial)

    def quiet():
        assert re.search(r'volume is 0\b', d.shell('media', 'volume', '--stream', 3, '--get')), 'Keep media volume zero'

    quiet()
    ui = broadcast_json(d, 'ui_state')
    seeds = {p: read_app_file(args.serial, p) for p in ('files/cast/identity-seed', 'files/cast/airplay-identity')}
    journal = capture(d, ui, PATHS + OPTIONAL_PATHS)
    marker = uuid.uuid4().hex
    directory = 'cache/update-device-' + marker
    had_test = bool(d.shell('pm', 'path', APP+'.test', check=False).strip())
    q = {'complete': False, 'mode': args.expect, 'recovery_journal': str(journal),
         'test_apk_initially_present': had_test,
         'scope': 'Application-UID native system-only TLS, no module install/reboot; baseline is observation, not overlay acceptance'}
    active = None

    def write():
        (args.output/'results.json').write_text(json.dumps(q, ensure_ascii=False, indent=2)+'\n')

    write()
    try:
        apk = ROOT/'android/app/build/outputs/apk/debug/app-debug.apk'
        expected = hashlib.sha256(apk.read_bytes()).hexdigest()
        installed = d.shell('pm', 'path', APP).strip().removeprefix('package:')
        if d.shell('sha256sum', installed).split()[0] != expected:
            assert 'Success' in d.adb('install', '-r', apk, timeout=300).decode()
            installed = d.shell('pm', 'path', APP).strip().removeprefix('package:')
        q['apk_sha256'] = d.shell('sha256sum', installed).split()[0]
        assert q['apk_sha256'] == expected
        # This also proves the selected mode against actual files and AndroidCAStore.
        inventory = subprocess.run([sys.executable, '-I', str(ROOT/'tools/tests/certs_device.py'),
                                    '--serial', args.serial, '--expect', args.expect, '--output', str(args.output/'inventory')],
                                   capture_output=True, text=True, timeout=120)
        (args.output/'inventory.log').write_text(inventory.stdout+inventory.stderr)
        assert inventory.returncode == 0, 'Store verification failed; see inventory.log'
        fixture = json.loads((ROOT/'tools/tests/fixtures/certs-tls/sites.json').read_text())
        q['tcp'] = []
        for site in fixture['sites']:
            host = urlsplit(site['url']).hostname
            assert re.fullmatch(r'[a-z0-9.-]{1,253}', host)
            result = subprocess.run(d.base+['shell', 'nc', '-w', '5', '-z', host, '443'],
                                    capture_output=True, text=True, timeout=12)
            q['tcp'].append({'id': site['id'], 'host': host, 'passed': result.returncode == 0,
                             'exit_code': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr})
        assert all(item['passed'] for item in q['tcp']), 'Unreachable sample endpoint; no certificate conclusion'
        test_apk = ROOT/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
        assert 'Success' in d.adb('install', '-r', test_apk, timeout=180).decode()
        q['test_apk_sha256'] = hashlib.sha256(test_apk.read_bytes()).hexdigest()
        component = APP+'.test/moe.kiwi.thinkos.UpdateInstrumentation'
        assert component in d.shell('pm', 'list', 'instrumentation')
        with (args.output/'instrumentation.log').open('w') as log:
            active = subprocess.Popen(d.base+['shell', '-T', 'am', 'instrument', '-w', '-e', 'run_id', marker,
                                              '-e', 'phase', 'certs_tls', component], stdout=log, stderr=subprocess.STDOUT)
        active.wait(timeout=120); active = None
        raw = (args.output/'instrumentation.log').read_text()
        match = re.search(r'^INSTRUMENTATION_RESULT: result=(\{.*\})$', raw, re.M)
        assert match, 'No native TLS observation result'
        result = json.loads(match[1]); q['native'] = result
        assert result.get('complete'), 'Native diagnostic failed'
        observations = result['tls_observations']
        q['overlay_tls_verified'] = validate_samples(args.expect, observations, fixture['sites'])
        for item in observations:
            print(item['id']+': '+item['outcome']+'; expected root is anchor='+str(item.get('expected_root_is_anchor')), flush=True)
        q['complete'] = True
    except Exception as error:
        q['failure'] = str(error)
    finally:
        if active is not None and active.poll() is None:
            d.shell('am', 'force-stop', APP); active.wait(timeout=20)
        d.shell('am', 'force-stop', APP)
        d.shell('run-as', APP, 'rm', '-rf', directory)
        if not had_test:
            d.adb('uninstall', APP+'.test', check=False)
        q['restoration'] = restore(journal, d); q.pop('recovery_journal', None)
        q['identity_files_unchanged'] = {p: read_app_file(args.serial, p) == value for p, value in seeds.items()}
        assert all(q['identity_files_unchanged'].values())
        q['temporary_cache_removed'] = not bool(d.shell('run-as', APP, 'ls', '-d', directory, check=False).strip())
        quiet(); q['media_volume_zero'] = True
        write()
    return 0 if q['complete'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
