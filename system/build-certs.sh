#!/bin/bash
# MIT. Reproducible host-only Magisk ZIP; does not install or access a device.
set -euo pipefail
cd "$(dirname "$0")"
MODE=replace
if [ "$#" -gt 0 ]; then
  [ "$#" -eq 2 ] && [ "$1" = --mode ] || { echo 'Usage: build-certs.sh [--mode add|replace]' >&2; exit 1; }
  MODE=$2
fi
case "$MODE" in add|replace) ;; *) echo 'Invalid mode' >&2; exit 1 ;; esac
python3 -I - "$MODE" <<'PY'
from datetime import date
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import zipfile

module = Path('thinkos-certs')
metadata = json.loads((module/'certs-source.json').read_text())
properties = dict(line.split('=', 1) for line in (module/'module.prop').read_text().splitlines() if '=' in line)
stamp = date.fromisoformat(metadata['source_date'])
expected_version = 'v' + stamp.strftime('%Y.%m.%d')
assert properties['id'] == 'thinkos-certs'
assert properties['version'] == expected_version and properties['versionCode'] == stamp.strftime('%Y%m%d')
certificates = metadata['certificates']
assert 100 <= len(certificates) <= 512 and len(certificates) == metadata['certificate_count']
prefix = 'system/etc/security/cacerts/'
data = {}
spec = importlib.util.spec_from_file_location('thinkos_ca_layout', '../tools/vendor_system_cacerts.py')
ca = importlib.util.module_from_spec(spec); spec.loader.exec_module(ca)
baseline = {row[0]: row[1] for line in Path('../artifacts/certs/CERT-0/device-store-2026-11-03.tsv').read_text().splitlines()
            if line and not line.startswith('#') for row in [line.split('\t')]}
layout = ca.installation_layout(certificates, baseline if sys.argv[1] == 'add' else None)
targets = {item['source_file']: item['file'] for item in layout}
for item in certificates:
    name = item['file']
    assert re.fullmatch(r'[0-9a-f]{8}\.(0|[1-9][0-9]*)', name)
    path = module / prefix / name
    assert path.is_file() and not path.is_symlink()
    raw = path.read_bytes()
    assert raw.startswith(b'-----BEGIN CERTIFICATE-----\n') and raw.endswith(b'-----END CERTIFICATE-----\n')
    assert raw.count(b'-----BEGIN CERTIFICATE-----') == 1 and len(raw) < 32*1024
    assert hashlib.sha256(raw).hexdigest() == item['pem_sha256']
    target = targets[name]
    assert prefix+target not in data
    data[prefix+target] = raw
assert {p.name for p in (module/prefix).iterdir()} == {c['file'] for c in certificates}
for name in ('module.prop', 'customize.sh', 'certs-source.json', 'LICENSE-MPL-2.0.txt',
             'LICENSE-GPL-3.0.md', 'LICENSE', 'META-INF/com/google/android/update-binary',
             'META-INF/com/google/android/updater-script', 'META-INF/ORIGIN.json', 'baseline-sha256.txt'):
    path = module/name
    assert path.is_file() and not path.is_symlink()
    data[name] = path.read_bytes()
assert data['META-INF/com/google/android/update-binary'] == Path('thinkos-cast/META-INF/com/google/android/update-binary').read_bytes()
assert data['LICENSE-MPL-2.0.txt'] == Path('../LICENSE-MPL-2.0.txt').read_bytes()
assert data['LICENSE-GPL-3.0.md'] == Path('../LICENSE-GPL-3.0.md').read_bytes()
if sys.argv[1] == 'replace':
    data[prefix+'.replace'] = b''
data['install-mode'] = (sys.argv[1]+'\n').encode('ascii')
data['install-layout.json'] = (json.dumps({'schema': 1, 'mode': sys.argv[1], 'files': layout,
    'expected_total_count': len(baseline | {c['file']: c['der_sha256'] for c in layout}) if sys.argv[1] == 'add' else len(layout)},
    indent=2)+'\n').encode('utf-8')
output = Path('build') / f'thinkos-certs-{expected_version}-{sys.argv[1]}.zip'
output.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for name, raw in sorted(data.items()):
        entry = zipfile.ZipInfo(name, (stamp.year, stamp.month, stamp.day, 0, 0, 0))
        entry.create_system = 3
        entry.external_attr = (0o100755 if name.endswith('/update-binary') else 0o100644) << 16
        archive.writestr(entry, raw, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
assert output.stat().st_size < 16*1024*1024
print(output)
print(hashlib.sha256(output.read_bytes()).hexdigest())
PY
