#!/usr/bin/env python3
"""Read-only CA overlay verification, after MANUAL module installation/reboot.

baseline is safe without an installed module. add/replace merely assert the
expected existing store; this script never installs, remounts, or reboots.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
APP = 'moe.kiwi.thinkos'
SPEC = importlib.util.spec_from_file_location('thinkos_ca_layout', ROOT/'tools/vendor_system_cacerts.py')
ca = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ca)


def baseline_files():
    rows = [line.split('\t') for line in (ROOT/'artifacts/certs/CERT-0/device-store-2026-11-03.tsv').read_text().splitlines()
            if line and not line.startswith('#')]
    return {row[0]: row[1] for row in rows}


def expected_files(mode, certificates):
    old = baseline_files()
    layout = ca.installation_layout(certificates, old if mode == 'add' else None)
    new = {item['file']: item['der_sha256'] for item in layout}
    if mode == 'baseline':
        return old
    if mode == 'replace':
        return new
    if mode != 'add':
        raise ValueError('Unknown CA mode')
    return old | new


def fingerprint_digest(values):
    return hashlib.sha256(('\n'.join(sorted(values)) + '\n').encode('ascii')).hexdigest()


def parse_listing(raw):
    result = {}
    for line in raw.splitlines():
        if not line or line.startswith('total '):
            continue
        fields = line.split()
        if len(fields) < 9:
            raise ValueError('Malformed CA directory listing')
        name = fields[-1].rsplit('/', 1)[-1]
        if not re.fullmatch(r'[0-9a-f]{8}\.[0-9]+', name) or name in result:
            raise ValueError('Unexpected or duplicate CA filename')
        if fields[:1] != ['-rw-r--r--'] or fields[2:5] != ['0', '0', 'u:object_r:system_file:s0']:
            raise ValueError('Unexpected CA permission/owner/context: ' + name)
        result[name] = {'mode': fields[0], 'uid': fields[2], 'gid': fields[3], 'context': fields[4]}
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--expect', choices=('baseline', 'add', 'replace'), default='baseline')
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/certs/CERT-3/current')
    args = parser.parse_args(argv)
    args.output.mkdir(parents=True, exist_ok=True)
    base = ['adb', '-s', args.serial]
    report = {'complete': False, 'read_only': True, 'serial': args.serial, 'expected_mode': args.expect,
              'manual_activation_verified': False,
              'not_performed': ['Manual Magisk revocation rehearsal', 'Module installation/reboot', 'Public-site TLS and media regressions']}

    def command(*parts, timeout=30):
        value = subprocess.run([*base, *parts], capture_output=True, timeout=timeout,
                               env=dict(os.environ, ADB_MDNS='0'), check=True).stdout
        if len(value) > 1024*1024:
            raise ValueError('Oversized adb response')
        return value.decode('utf-8')

    def quiet():
        if not re.search(r'volume is 0\b', command('shell', 'media', 'volume', '--stream', '3', '--get')):
            raise ValueError('Media volume must remain zero')

    try:
        quiet()
        report['sdk'] = command('shell', 'getprop', 'ro.build.version.sdk').strip()
        if report['sdk'] != '27':
            raise ValueError('Expected Android API 27')
        source = json.loads((ROOT/'system/thinkos-certs/certs-source.json').read_text())
        expected = expected_files(args.expect, source['certificates'])
        listing = command('shell', 'ls', '-lnZ', '/system/etc/security/cacerts')
        (args.output/'ls-lnZ.txt').write_text(listing)
        permissions = parse_listing(listing)
        if set(permissions) != set(expected):
            raise ValueError('CA filenames do not match expected mode')
        store = args.output/'store'
        if store.exists():
            raise ValueError('Use a fresh output directory to preserve the previous raw inventory')
        command('pull', '/system/etc/security/cacerts', str(store), timeout=90)
        actual = {}
        for name in sorted(expected):
            path = store/name
            if not path.is_file() or path.is_symlink() or path.stat().st_size > 32*1024:
                raise ValueError('Missing/oversized/nonregular certificate')
            raw = path.read_bytes()
            der = subprocess.run(['openssl', 'x509', '-outform', 'DER'], input=raw, capture_output=True,
                                 timeout=15, check=True).stdout
            digest = hashlib.sha256(der).hexdigest()
            if digest != expected[name]:
                raise ValueError('CA content mismatch: ' + name)
            actual[name] = {'der_sha256': digest, 'file_sha256': hashlib.sha256(raw).hexdigest(), **permissions[name]}
        if args.expect != 'baseline':
            layout = ca.installation_layout(source['certificates'], baseline_files() if args.expect == 'add' else None)
            for item in layout:
                if actual[item['file']]['file_sha256'] != item['pem_sha256']:
                    raise ValueError('Overlay PEM bytes differ from the generated file')
        reply = command('shell', 'am', 'broadcast', '--receiver-foreground', '-n', APP+'/.DebugReceiver',
                        '-a', APP+'.DEBUG', '--es', 'command', 'trust_roots', timeout=45)
        (args.output/'trust-roots-broadcast.txt').write_text(reply)
        match = re.search(r'data="(\{.*\})"', reply)
        if not match:
            raise ValueError('No trust_roots reply; install the current debug APK')
        # am broadcast encloses the result in literal quotes; JSON quotes inside
        # resultData are not shell escapes. Parse the whole bounded {...} body.
        app = json.loads(match[1])
        if (app.get('system_count') != len(expected) or app.get('unique_system_count') != len(set(expected.values()))
                or app.get('system_fingerprints_sha256') != fingerprint_digest(expected.values())):
            raise ValueError('AndroidCAStore differs from the expected actual files')
        report.update({'files': actual, 'app': app, 'file_count': len(actual),
                       'expected_fingerprints_sha256': fingerprint_digest(expected.values()),
                       'manual_activation_verified': args.expect != 'baseline', 'complete': True})
        quiet()
        report['media_volume_zero'] = True
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        report['failure'] = str(error)
    (args.output/'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'files'}, ensure_ascii=False, indent=2))
    return 0 if report['complete'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
