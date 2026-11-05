#!/usr/bin/env python3
"""Host-only signed APK fixtures for native archive verification; NEVER install.

Builds the next version from this worktree, restores the normal build afterwards,
and uses only the existing public debug key plus an ephemeral test-only key.
No production keys, device actions, network downloads, or publishing.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT/'artifacts/update/OTA-2/packages/fixtures')
    parser.add_argument('--newer-than', help='Explicit installed version floor during an ongoing native trial; never edits source version')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if list(output.iterdir()):
        parser.error('Fixture directory must be empty')
    logs = output.parent
    java = subprocess.run(['/usr/libexec/java_home', '-v', '17'], capture_output=True, text=True, check=True).stdout.strip()
    env = dict(os.environ, JAVA_HOME=java)
    properties = dict(line.split('=', 1) for line in (ROOT/'android/gradle.properties').read_text().splitlines() if '=' in line)
    version = properties['thinkos.version']
    parts = [int(item) for item in version.split('.')]
    current = parts[0]*1_000_000+parts[1]*1000+parts[2]
    floor = current
    if args.newer_than is not None:
        if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', args.newer_than):
            parser.error('Invalid --newer-than version')
        installed = [int(part) for part in args.newer_than.split('.')]
        if installed[0] > 2147 or installed[1] > 999 or installed[2] > 999:
            parser.error('--newer-than component overflow')
        floor = max(current, installed[0]*1_000_000+installed[1]*1000+installed[2])
    code = floor+1
    if code > 2_147_483_647:
        parser.error('Fixture version overflow')
    candidate = f'{code//1_000_000}.{code//1000%1000}.{code%1000}'
    sdk = Path(next(line.split('=', 1)[1] for line in (ROOT/'android/local.properties').read_text().splitlines() if line.startswith('sdk.dir=')))
    tools = sdk/'build-tools/35.0.0'
    main_apk = ROOT/'android/app/build/outputs/apk/debug/app-debug.apk'
    report = {'complete': False, 'fixture_only_never_install': True, 'version': candidate, 'code': code,
              'normal_version': version, 'normal_code': current, 'ephemeral_private_key_retained': False}
    report['fixture_version_floor'] = floor

    def run(command, label, cwd=ROOT, timeout=300):
        with (logs/(label+'.log')).open('w') as log:
            subprocess.run([str(arg) for arg in command], cwd=cwd, stdout=log, stderr=subprocess.STDOUT,
                           timeout=timeout, check=True, env=env)

    def sign(source, target, key, password='android'):
        run([tools/'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:'+password, '--key-pass', 'pass:'+password,
             '--v4-signing-enabled', 'false', '--out', target, source], target.stem+'-sign')

    def signer(path):
        raw = subprocess.run([str(tools/'apksigner'), 'verify', '--print-certs', str(path)],
                             capture_output=True, text=True, check=True, timeout=60, env=env).stdout
        value = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})', raw)
        assert len(value) == 1
        return value[0]

    try:
        run(['./gradlew', '--offline', '-PupdateProbe=true', '-Pthinkos.version='+candidate, 'assembleDebug'],
            'candidate-build', ROOT/'android')
        shutil.copyfile(main_apk, output/'valid.apk')
        report['signer'] = signer(output/'valid.apk')
        debug_key = Path.home()/'.android/debug.keystore'
        with tempfile.TemporaryDirectory(prefix='thinkos-apk-fixtures-') as name:
            work = Path(name)
            # A resource-only API27 package, with a different package name.
            manifest = work/'AndroidManifest.xml'
            manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
 package="moe.kiwi.thinkos.wrongfixture" android:versionCode="{code}" android:versionName="{candidate}">
 <uses-sdk android:minSdkVersion="27" android:targetSdkVersion="27"/>
 <application android:hasCode="false" android:label="Archive fixture"/>
</manifest>''')
            resource = work/'wrong-package-unsigned.apk'
            run([tools/'aapt2', 'link', '--manifest', manifest, '-I', sdk/'platforms/android-35/android.jar',
                 '--min-sdk-version', '27', '--target-sdk-version', '27', '-o', resource], 'wrong-package-aapt')
            aligned = work/'wrong-package-aligned.apk'
            run([tools/'zipalign', '-f', '4', resource, aligned], 'wrong-package-align')
            sign(aligned, output/'wrong-package.apk', debug_key)
            assert signer(output/'wrong-package.apk') == report['signer'], 'Fixtures support the existing debug signer only'
            # The only generated key is private to this TemporaryDirectory.
            other_key = work/'fixture-only.p12'
            password = 'fixture-only-not-production'
            run([Path(java)/'bin/keytool', '-genkeypair', '-keystore', other_key, '-storetype', 'PKCS12', '-alias', 'fixture',
                 '-storepass', password, '-keypass', password, '-keyalg', 'EC', '-groupname', 'secp256r1', '-validity', '2',
                 '-dname', 'CN=ThinkOS disposable archive test'], 'wrong-signer-key')
            sign(output/'valid.apk', output/'wrong-signer.apk', other_key, password)
            assert signer(output/'wrong-signer.apk') != report['signer']
            # Preserve the original v2 block while changing a signed ZIP byte.
            data = bytearray((output/'valid.apk').read_bytes())
            with zipfile.ZipFile(output/'valid.apk') as archive:
                info = archive.getinfo('classes.dex')
                filename_size, extra_size = struct.unpack_from('<HH', data, info.header_offset+26)
                start = info.header_offset+30+filename_size+extra_size
                data[start+info.compress_size//2] ^= 1
            (output/'tampered.apk').write_bytes(data)
            bad = subprocess.run([str(tools/'apksigner'), 'verify', str(output/'tampered.apk')],
                                 capture_output=True, timeout=60, env=env)
            assert bad.returncode != 0
            # Re-sign a structurally valid mixed-ABI archive to reach our ABI gate.
            unsigned = work/'abi-unsigned.apk'
            with zipfile.ZipFile(output/'valid.apk') as source, zipfile.ZipFile(unsigned, 'w') as target:
                for info in source.infolist():
                    upper = info.filename.upper()
                    if upper.startswith('META-INF/') and (upper.endswith(('.RSA', '.DSA', '.EC', '.SF')) or upper == 'META-INF/MANIFEST.MF'):
                        continue
                    target.writestr(info, source.read(info.filename))
                target.writestr('lib/x86/libfixture.so', b'nonexecuted ABI rejection fixture')
            aligned = work/'abi-aligned.apk'
            run([tools/'zipalign', '-f', '4', unsigned, aligned], 'wrong-abi-align')
            sign(aligned, output/'wrong-abi.apk', debug_key)
            assert signer(output/'wrong-abi.apk') == report['signer']
        (output/'invalid.apk').write_bytes(b'Not an Android APK\n')
        report['files'] = {p.name: {'size': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                           for p in sorted(output.glob('*.apk'))}
        (output/'fixtures.json').write_text(json.dumps({key: report[key] for key in ('version', 'code', 'files')}, indent=2)+'\n')
        report['complete'] = True
    finally:
        # Leave the workspace build at its real version, including the test runner.
        run(['./gradlew', '--offline', '-PupdateProbe=true', 'assembleDebug', 'assembleDebugAndroidTest'],
            'normal-build-restored', ROOT/'android')
        report['normal_apk_sha256'] = hashlib.sha256(main_apk.read_bytes()).hexdigest()
        (logs/'fixtures-build.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
