#!/usr/bin/env python3
"""MIT. Check a downloaded input-method APK and, only with --apply, install it on one device over adb.

The file is untrusted data: it is read with the SDK's apksigner and aapt, never executed on this machine. It is accepted
only when its package name and signing certificate are a pair on the allow list below. The default run changes nothing.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# package -> (signer SHA-256 fingerprints, where they come from)
TRUSTED = {
    'com.osfans.trime': (
        {'5bc77b5d6d6cb89cbc736d7ddc151fa79f62c45b8aac5413f502b46cd9d0792c'},
        'F-Droid build (GPL-3.0); fingerprint seen on the APK downloaded from f-droid.org/repo over TLS, 3.3.13, 2026-11-06'),
    'com.google.android.inputmethod.pinyin': (
        {'f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83',
         '1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00'},
        'Google first-party release keys, as published in https://www.google.com/.well-known/assetlinks.json (2026-11-06)'),
}
MAX_SDK = 27
ABI = 'armeabi-v7a'


def sdk_tools():
    local = (ROOT / 'android/local.properties').read_text(encoding='utf-8')
    sdk = next(line.split('=', 1)[1] for line in local.splitlines() if line.startswith('sdk.dir='))
    build = Path(sdk) / 'build-tools/35.0.0'
    for name in ('aapt', 'apksigner'):
        if not (build / name).is_file():
            raise SystemExit('Android build tools are unavailable: ' + str(build / name))
    return build / 'aapt', build / 'apksigner'


def run(command, **kwargs):
    return subprocess.run([str(part) for part in command], capture_output=True, text=True, stdin=subprocess.DEVNULL, **kwargs)


def parse_certificates(output):
    return {line.split(':', 1)[1].strip().replace(':', '').lower()
            for line in output.splitlines() if 'certificate SHA-256 digest' in line}


def parse_badging(text):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", text, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", text, re.M)
    abis = re.search(r"^native-code: (.*)$", text, re.M)
    return {
        'package': package.group(1) if package else None,
        'version_code': int(package.group(2)) if package else None,
        'version_name': package.group(3) if package else None,
        'min_sdk': int(sdk.group(1)) if sdk else None,
        'abis': re.findall(r"'([^']+)'", abis.group(1)) if abis else [],
        'permissions': sorted(set(re.findall(r"^uses-permission(?:-sdk-23)?: name='([^']+)'", text, re.M))),
    }


def judge(info, signers, verifies):
    """Return the reasons to refuse; an empty list means the file passes every check."""
    problems = []
    if not verifies:
        problems.append('the signature does not verify')
    trusted = TRUSTED.get(info['package'])
    if trusted is None:
        problems.append('package %s is not on the allow list' % info['package'])
    elif not signers & trusted[0]:
        problems.append('signer %s is not an allowed signer of %s' % (sorted(signers), info['package']))
    if info['min_sdk'] is None or info['min_sdk'] > MAX_SDK:
        problems.append('minSdk %s is above the device API %d' % (info['min_sdk'], MAX_SDK))
    if info['abis'] and ABI not in info['abis']:
        problems.append('no %s native code (has %s)' % (ABI, ', '.join(info['abis'])))
    return problems


def inspect(apk):
    aapt, apksigner = sdk_tools()
    data = apk.read_bytes()
    badging = run([aapt, 'dump', 'badging', apk])
    info = parse_badging(badging.stdout)
    verify = run([apksigner, 'verify', '--verbose', '--print-certs', apk])
    signers = parse_certificates(verify.stdout + verify.stderr)
    verifies = verify.returncode == 0
    problems = judge(info, signers, verifies)
    source = TRUSTED.get(info['package'], (None, None))[1]
    return {'file': str(apk), 'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data), 'signers': sorted(signers),
            'signer_source': source, **info, 'problems': problems}


def adb(serial, *args):
    result = run(['adb', '-s', serial, *args])
    return result.returncode, (result.stdout + result.stderr).strip()


def apply(report, serial, make_default):
    steps = []
    code, out = adb(serial, 'install', '-r', report['file'])
    steps.append({'step': 'install', 'ok': code == 0 and 'Success' in out, 'output': out[-200:]})
    if not steps[-1]['ok']:
        return steps
    code, out = adb(serial, 'shell', 'ime', 'list', '-a', '-s')
    ids = [line.strip() for line in out.splitlines() if line.strip().startswith(report['package'] + '/')]
    steps.append({'step': 'find', 'ok': bool(ids), 'ids': ids})
    for identifier in ids:
        code, out = adb(serial, 'shell', 'ime', 'enable', identifier)
        steps.append({'step': 'enable ' + identifier, 'ok': code == 0, 'output': out[-120:]})
    if make_default and ids:
        code, out = adb(serial, 'shell', 'ime', 'set', ids[0])
        steps.append({'step': 'set ' + ids[0], 'ok': code == 0, 'output': out[-120:]})
    return steps


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--serial', default='HUA0BWUT')
    parser.add_argument('--apply', action='store_true', help='install and enable it; without this nothing is changed')
    parser.add_argument('--make-default', action='store_true', help='also make it the default input method')
    args = parser.parse_args(argv)
    report = inspect(args.apk)
    if report['problems']:
        print(json.dumps(report, indent=2, ensure_ascii=False))
        return 1
    if args.apply:
        report['applied'] = apply(report, args.serial, args.make_default)
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0 if all(step['ok'] for step in report.get('applied', [])) else 1


if __name__ == '__main__':
    sys.exit(main())
