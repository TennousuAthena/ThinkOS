#!/usr/bin/env python3
# MIT. Pack a staged Magisk module directory into a byte-reproducible zip.
# Same files in -> same bytes out: fixed timestamps, sorted names, only the
# executable bit kept. release.py refuses changed bytes under an unchanged
# versionCode, so packing must not depend on when or where it ran.
import os
import stat
import sys
import zipfile
from pathlib import Path

STAMP = (1980, 1, 1, 0, 0, 0)


def entries(stage):
    for path in sorted(stage.rglob('*'), key=lambda item: item.relative_to(stage).as_posix()):
        if path.is_symlink():
            raise SystemExit('Symlinks are not packed: ' + str(path))
        if path.is_file():
            yield path.relative_to(stage).as_posix(), path


def pack(stage, output):
    stage = Path(stage).resolve()
    output = Path(output)
    output.unlink(missing_ok=True)
    with zipfile.ZipFile(output, 'w') as archive:
        for name, path in entries(stage):
            info = zipfile.ZipInfo(name, STAMP)
            info.create_system = 3
            mode = 0o755 if os.stat(path).st_mode & stat.S_IXUSR else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('usage: pack_module.py STAGE_DIR OUTPUT.zip')
    pack(sys.argv[1], sys.argv[2])
