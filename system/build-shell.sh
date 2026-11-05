#!/bin/bash
# Build only. Magisk app/CLI installs plain module ZIPs; recovery flashing is not supported.
set -euo pipefail
cd "$(dirname "$0")"
python3 - <<'PY'
from pathlib import Path
import zipfile
source=Path('thinkos-shell')
version=dict(line.split('=',1) for line in (source/'module.prop').read_text().splitlines() if '=' in line)['version']
assert version=='v1.1'
out=Path('build')/f'thinkos-shell-{version}.zip';out.parent.mkdir(exist_ok=True)
names=['module.prop','customize.sh','service.sh','uninstall.sh','shell.sh','skip_mount']
with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED) as archive:
    for name in names:
        data=(source/name).read_bytes();assert b'\r' not in data
        entry=zipfile.ZipInfo(name,(2026,11,3,0,0,0));entry.create_system=3
        entry.external_attr=(0o100755 if name.endswith('.sh') else 0o100644)<<16
        archive.writestr(entry,data,compress_type=zipfile.ZIP_DEFLATED)
print(out)
PY
