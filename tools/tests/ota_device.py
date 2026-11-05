#!/usr/bin/env python3
"""Real API27 update components in the installed APK, including process-kill resume.

No app-data clear, reboot, system-module install, formal keys, package self-update,
or nonzero volume. Optional runtime phase plays a silent video at stream volume 0.
Test signatures/keys are ephemeral on-device RAM.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'tools'), str(ROOT / 'tools/tests')]
from device_app_files import read_app_file
from device_recovery import capture, restore, PATHS, OPTIONAL_PATHS, RECOVERY_PATHS
from media_device import APP, Device
from ui_walkthrough import broadcast_json


def main():
    os.environ['ADB_MDNS'] = '0'
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/update/OTA-2/device-20261104/ota')
    parser.add_argument('--test-apk', type=Path, default=ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    parser.add_argument('--apk', type=Path, default=ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--packages', type=Path, help='Host-built signed APK fixtures; read/verify only, never installed')
    parser.add_argument('--workflow', action='store_true', help='Kill/reopen the durable cache/state/full-APK workflow; requires --packages')
    parser.add_argument('--runtime', action='store_true', help='Real RuntimeHost/video/cast-session/foreground gating and automatic resume; requires --packages')
    parser.add_argument('--install-transaction', action='store_true', help='Native backup/journal/platform-stage/cancel tests; no APK commit, requires --packages')
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    assert not args.workflow or args.packages, '--workflow requires signed APK fixtures'
    assert not args.runtime or args.packages, '--runtime requires signed APK fixtures'
    assert not args.install_transaction or args.packages, '--install-transaction requires signed APK fixtures'
    package_names = {'fixtures.json', 'valid.apk', 'wrong-package.apk', 'wrong-signer.apk', 'tampered.apk', 'wrong-abi.apk', 'invalid.apk'}
    if args.packages:
        actual_names = {path.name for path in args.packages.iterdir()}
        assert actual_names == package_names, 'Unexpected fixture files: ' + repr(actual_names)
        assert sum(path.stat().st_size for path in args.packages.iterdir()) < 256 * 1024 * 1024
    d = Device(args.serial)
    assert d.shell('getprop', 'ro.serialno').strip() == 'HUA0BWUT'
    assert d.shell('getprop', 'ro.build.version.sdk').strip() == '27'
    assert re.search(r'volume is 0\b', d.shell('media', 'volume', '--stream', 3, '--get'))
    ui = broadcast_json(d, 'ui_state')
    seeds = {p: read_app_file(d.serial, p) for p in ('files/cast/identity-seed', 'files/cast/airplay-identity')}
    journal = capture(d, ui, PATHS + OPTIONAL_PATHS + RECOVERY_PATHS)
    marker = uuid.uuid4().hex
    directory = 'cache/update-device-' + marker
    component = APP + '.test/moe.kiwi.thinkos.UpdateInstrumentation'
    had_test = bool(d.shell('pm', 'path', APP + '.test', check=False).strip())
    q = {'complete': False, 'physical_serial': 'HUA0BWUT', 'test_apk_initially_present': had_test,
         'recovery_journal': str(journal), 'scope': 'Installed production classes, app UID, real Android providers and local sockets; no installation engine or physical-power validation'}
    (args.output / 'results.json').write_text(json.dumps(q, ensure_ascii=False, indent=2) + '\n')
    active = None
    remote_packages = '/data/local/tmp/thinkos-package-' + marker

    def begin(phase):
        path = args.output / (phase + '.log')
        with path.open('w') as log:
            process = subprocess.Popen(d.base + ['shell', '-T', 'am', 'instrument', '-w', '-e', 'run_id', marker,
                                                 '-e', 'phase', phase, component], stdout=log, stderr=subprocess.STDOUT)
        print('Running device update phase: ' + phase, flush=True)
        return process

    def finish(phase, process):
        process.wait(timeout=240)
        raw = (args.output / (phase + '.log')).read_text()
        match = re.search(r'^INSTRUMENTATION_RESULT: result=(\{.*\})$', raw, re.M)
        if not match:
            raise RuntimeError('No instrumentation result in ' + phase + '.log')
        result = json.loads(match[1]); q[phase] = result
        for item in result.get('checks', []): print(('PASS ' if item['passed'] else 'FAIL ') + item['name'], flush=True)
        if not result.get('complete'):
            raise RuntimeError('Device phase failed: ' + phase + '; see raw log and results.json')
        return result

    try:
        apk = args.apk
        expected = hashlib.sha256(apk.read_bytes()).hexdigest()
        installed = d.shell('pm', 'path', APP).strip().removeprefix('package:')
        if d.shell('sha256sum', installed).split()[0] != expected:
            output = d.adb('install', '-r', apk, timeout=300).decode()
            assert 'Success' in output
            installed = d.shell('pm', 'path', APP).strip().removeprefix('package:')
        q['apk_sha256'] = d.shell('sha256sum', installed).split()[0]; assert q['apk_sha256'] == expected
        output = d.adb('install', '-r', args.test_apk, timeout=180).decode(); assert 'Success' in output
        q['test_apk_sha256'] = hashlib.sha256(args.test_apk.read_bytes()).hexdigest()
        assert component in d.shell('pm', 'list', 'instrumentation')
        active = begin('suite'); finish('suite', active); active = None
        if args.packages:
            d.shell('mkdir', remote_packages)
            d.shell('chmod', '755', remote_packages)
            d.shell('run-as', APP, 'mkdir', '-p', directory+'/packages')
            for name in sorted(package_names):
                path = args.packages/name
                assert path.is_file() and not path.is_symlink()
                d.adb('push', path, remote_packages+'/'+name, timeout=120)
                d.shell('chmod', '644', remote_packages+'/'+name)
                d.shell('run-as', APP, 'cp', remote_packages+'/'+name, directory+'/packages/'+name)
            active = begin('packages'); finish('packages', active); active = None
            if args.install_transaction:
                active = begin('install_transaction'); finish('install_transaction', active); active = None
            if args.runtime:
                active = begin('runtime'); finish('runtime', active); active = None
            if args.workflow:
                active = begin('state_prepare')
                deadline = time.monotonic() + 45
                checkpoint = None
                while time.monotonic() < deadline:
                    raw = d.shell('run-as', APP, 'cat', directory+'/workflow-checkpoint', check=False).strip()
                    if raw:
                        checkpoint = json.loads(raw); break
                    if active.poll() is not None:
                        raise RuntimeError('Workflow prepare ended before its durable checkpoint')
                    time.sleep(.2)
                assert checkpoint and checkpoint['bytes'] == 65536 and checkpoint['seq'] == 101
                part = directory+'/workflow/downloads/'+checkpoint['sha256']+'.apk.part'
                assert d.shell('run-as', APP, 'stat', '-c', '%s', part).strip() == '65536'
                q['workflow_kill_checkpoint'] = checkpoint
                d.shell('am', 'force-stop', APP); active.wait(timeout=20); active = None
                active = begin('state_resume'); resumed = finish('state_resume', active); active = None
                assert resumed['pid'] != checkpoint['pid']
        active = begin('prepare')
        deadline = time.monotonic() + 25
        checkpoint = None
        while time.monotonic() < deadline:
            raw = d.shell('run-as', APP, 'cat', directory + '/checkpoint', check=False).strip()
            if raw:
                checkpoint = json.loads(raw)
                break
            if active.poll() is not None:
                raise RuntimeError('Prepare process ended before kill checkpoint')
            time.sleep(.2)
        assert checkpoint and checkpoint['bytes'] == 65536 and re.fullmatch('[a-f0-9]{64}', checkpoint['sha256'])
        part = directory + '/process-resume/' + checkpoint['sha256'] + '.apk.part'
        assert d.shell('run-as', APP, 'stat', '-c', '%s', part).strip() == '65536'
        q['intentional_kill_checkpoint'] = checkpoint
        d.shell('am', 'force-stop', APP)
        active.wait(timeout=20); active = None
        assert d.shell('run-as', APP, 'stat', '-c', '%s', part).strip() == '65536'
        active = begin('resume'); resumed = finish('resume', active); active = None
        assert resumed['pid'] != checkpoint['pid']
        q['different_art_processes'] = True
        q['complete'] = True
    except Exception as error:
        q['failure'] = str(error)
        raise
    finally:
        if active is not None and active.poll() is None:
            d.shell('am', 'force-stop', APP)
            active.wait(timeout=20)
        d.shell('am', 'force-stop', APP)
        d.shell('run-as', APP, 'rm', '-rf', directory)
        if args.packages:
            d.shell('rm', '-rf', remote_packages)
            q['temporary_package_fixtures_removed'] = not d.shell('ls', '-d', remote_packages, check=False).strip()
        if not had_test:
            d.adb('uninstall', APP + '.test', check=False)
        q['restoration'] = restore(journal, d); q.pop('recovery_journal', None)
        q['identity_files_unchanged'] = {p: read_app_file(d.serial, p) == raw for p, raw in seeds.items()}
        q['temporary_cache_removed'] = not d.shell('run-as', APP, 'ls', '-d', directory, check=False).strip()
        q['media_volume_zero'] = bool(re.search(r'volume is 0\b', d.shell('media', 'volume', '--stream', 3, '--get')))
        q['restored_foreground'] = d.shell('dumpsys', 'activity', 'activities').split('mResumedActivity:')[-1].splitlines()[0].strip()
        (args.output / 'results.json').write_text(json.dumps(q, ensure_ascii=False, indent=2) + '\n')
    assert all(q['identity_files_unchanged'].values()) and q['temporary_cache_removed'] and q['media_volume_zero']


if __name__ == '__main__':
    main()
