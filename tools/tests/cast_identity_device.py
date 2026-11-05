#!/usr/bin/env python3
"""WEL-4: installed production identity code on API27/armv7, in disposable app-private directories.

Does not clear app data, reboot, change settings, or touch the actual discovery/
pairing seeds. Uses two separate ART processes for a persistence check.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'tools'), str(ROOT / 'tools/tests')]
from device_identity_probe import identity_probe_jar
from media_device import APP, Device


def main():
    os.environ['ADB_MDNS'] = '0'
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/welcome/WEL-4/device-identity')
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    jar = identity_probe_jar(); device = Device(args.serial)
    assert device.shell('getprop', 'ro.serialno').strip() == 'HUA0BWUT'
    assert device.shell('getprop', 'ro.build.version.sdk').strip() == '27'
    assert re.search(r'volume is 0\b', device.shell('media', 'volume', '--stream', 3, '--get'))
    apk = device.shell('pm', 'path', APP).strip().removeprefix('package:')
    data = device.shell('run-as', APP, 'pwd').strip()
    assert re.fullmatch(r'/data/(?:user/0|data)/moe\.kiwi\.thinkos', data)
    marker = uuid.uuid4().hex
    directory = data + '/cache/cast-identity-probe-' + marker
    remote = '/data/local/tmp/thinkos-cast-identity-' + marker + '.jar'
    report = {'complete': False, 'apk_sha256': device.shell('sha256sum', apk).split()[0],
              'scope': 'Installed production classes in temporary private files, including separate ART processes',
              'app_data_cleared': False, 'device_rebooted': False, 'phases': []}
    owns_jar = owns_directory = False
    try:
        device.push(jar, remote); owns_jar = True; device.shell('chmod', '644', remote)
        device.shell('run-as', APP, 'mkdir', '-m', '700', directory); owns_directory = True
        for phase in ('create', 'reload'):
            command = 'CLASSPATH=' + shlex.quote(remote) + ' ' + shlex.join(['app_process', '/system/bin', 'ThinkCastIdentity', phase, apk, directory])
            raw = device.shell('run-as', APP, 'sh', '-c', command)
            match = re.search(r'THINKOS_CAST_IDENTITY (\{.*\})', raw); assert match, raw
            result = json.loads(match[1]); result['phase'] = phase; report['phases'].append(result)
            assert result['checks'] and all(item['passed'] for item in result['checks'])
            for item in result['checks']: print('PASS ' + item['name'], flush=True)
        assert report['phases'][0]['pid'] != report['phases'][1]['pid']
        report['complete'] = True
    except Exception as error:
        report['failure'] = str(error)
        raise
    finally:
        cleanup = []
        for owned, command in ((owns_directory, ('run-as', APP, 'rm', '-rf', directory)), (owns_jar, ('rm', '-f', remote))):
            if owned:
                try: device.shell(*command)
                except Exception as error: cleanup.append(str(error))
        try:
            report['temporary_directory_removed'] = not device.shell('run-as', APP, 'ls', '-d', directory, check=False).strip()
            report['temporary_jar_removed'] = not device.shell('ls', remote, check=False).strip()
            report['media_volume_zero'] = bool(re.search(r'volume is 0\b', device.shell('media', 'volume', '--stream', 3, '--get')))
        except Exception as error: cleanup.append(str(error))
        if cleanup: report.update(cleanup_errors=cleanup, owned_directory=directory, owned_jar=remote)
        (args.output / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    assert not cleanup and report['temporary_directory_removed'] and report['temporary_jar_removed'] and report['media_volume_zero']


if __name__ == '__main__':
    main()
