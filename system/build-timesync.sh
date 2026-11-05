#!/bin/sh
# MIT. Package the pinned owner-authored module; never installs or reboots.
set -eu
module_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
package_out="$module_dir/build"
if [ "$#" -gt 0 ]; then
  [ "$#" -eq 2 ] && [ "$1" = --output ] || { echo 'Usage: build-timesync.sh [--output directory]' >&2; exit 1; }
  package_out=$2
fi
python3 - "$module_dir" "$package_out" <<'PY'
import hashlib,json,re,sys,zipfile
from pathlib import Path
root=Path(sys.argv[1]);source=root/'timesync';output=Path(sys.argv[2])
origin=json.loads((source/'ORIGIN.json').read_text())
files={name:(source/name).read_bytes() for name in ('module.prop','service.sh','ORIGIN.json')}
for name,digest in origin['unmodified_files'].items():
    if hashlib.sha256(files[name]).hexdigest()!=digest:raise SystemExit('Pinned timesync source changed: '+name)
prop=dict(line.split('=',1) for line in files['module.prop'].decode().splitlines() if '=' in line)
if prop.get('id')!='timesync' or not re.fullmatch(r'v[0-9]+\.[0-9]+',prop.get('version','')):
    raise SystemExit('Invalid timesync identity/version')
files['LICENSE']=(root.parent/'LICENSE').read_bytes()
output.mkdir(parents=True,exist_ok=True)
target=output/('timesync-'+prop['version']+'.zip')
with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for name in sorted(files):
        info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));info.create_system=3
        info.external_attr=(0o100755 if name=='service.sh' else 0o100644)<<16
        info.compress_type=zipfile.ZIP_DEFLATED
        z.writestr(info,files[name])
print(target)
PY
