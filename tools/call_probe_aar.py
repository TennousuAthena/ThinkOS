"""Opt-in CALL-0 test APK input. No network, code execution or device access."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import struct
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = Path('android/app/build/generated/call-probe')
LIMIT = 64 * 1024 * 1024
PINS = {
    'android': (49068339, '1872dda4e3bf7d6ea6f7c6e11861332081c57081a1e62fc82fab72415c69c84c',
        6791820, '5415e7a02ce83e983329575e7c2e46b8ced6c75baea3c41aa52b5699ccb54eda'),
    'android-prefixed': (23113908, '96f1ebbd7128984a4ca8abb6fba68f212adf75ee6606c5f3cb60ad8f7db5d8b3',
        6795276, '97bb214698d717d67c7221270a89b615a8571ecc0782b9a5a360d5a5e463d734'),
    'android-prefixed-stripped': (14265935, '89bb34fd3f16041c1b4c83aa42d5b6d94e5a2d8218c864a58d08160622bcd7a4',
        4471732, '15cffe4bcb30a535816109c01e1d3eee19ec7031ebd584fb895c4be1fe62dd1d'),
}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def regular(path, limit):
    path = Path(path)
    if not path.is_absolute() or path.is_symlink():
        raise ValueError('Use an absolute nonsymlink local input')
    # Resolve macOS /var and /tmp aliases in parents, but never resolve the leaf
    # before O_NOFOLLOW. Recheck the named inode after reading.
    path = path.parent.resolve(strict=True) / path.name
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as source:
        before = os.fstat(source.fileno())
        if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= limit:
            raise ValueError('Bounded nonempty regular input required')
        raw = source.read(limit + 1)
        after, named = os.fstat(source.fileno()), path.lstat()
        def stamp(s):
            return s.st_dev, s.st_ino, s.st_mode, s.st_size, s.st_mtime_ns, s.st_ctime_ns
        if len(raw) != before.st_size or len(raw) > limit or stamp(before) != stamp(after) or stamp(after) != stamp(named):
            raise ValueError('Input changed while reading')
    return path, raw


def inspect(aar, variant):
    if variant not in PINS:
        raise ValueError('Select one fixed CALL-0 variant')
    path, raw = regular(aar, LIMIT)
    try:
        path.relative_to(ROOT.resolve())
    except ValueError:
        pass
    else:
        raise ValueError('AAR must remain outside the repository')
    size, sha, so_size, so_sha = PINS[variant]
    if len(raw) != size or digest(raw) != sha:
        raise ValueError('Local AAR is not the fixed statically inspected 150.7871.04 artifact')
    prefixed = variant != 'android'
    filename = 'liblkjingle_peerconnection_so.so' if prefixed else 'libjingle_peerconnection_so.so'
    wanted = 'jni/armeabi-v7a/' + filename
    target = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(raw)) as original, zipfile.ZipFile(target, 'w') as filtered:
        entries = original.infolist()
        if not 1 <= len(entries) <= 1024 or len({e.filename for e in entries}) != len(entries):
            raise ValueError('Duplicate or excessive AAR entries')
        total = 0
        native = []
        for entry in entries:
            name = entry.filename
            parts = name.rstrip('/').split('/')
            mode = (entry.external_attr >> 16) & 0o170000
            if not name or len(name) > 256 or name.startswith('/') or '\\' in name or \
                    any(p in ('', '.', '..') for p in parts) or any(ord(c) < 32 or ord(c) == 127 for c in name) or \
                    mode == stat.S_IFLNK or entry.flag_bits & 1 or not 0 <= entry.file_size <= 32*1024*1024:
                raise ValueError('Unsafe/oversized AAR entry')
            total += entry.file_size
            if total > 128 * 1024 * 1024:
                raise ValueError('Uncompressed AAR exceeds bound')
            if name.startswith('jni/armeabi-v7a/') and not entry.is_dir():
                native.append(name)
            if name.startswith('jni/') and name != 'jni/' and not name.startswith('jni/armeabi-v7a/'):
                continue
            data = original.read(entry)
            if len(data) != entry.file_size:
                raise ValueError('Truncated AAR member')
            if name == wanted:
                if len(data) != so_size or digest(data) != so_sha or len(data) < 52 or \
                        data[:7] != b'\x7fELF\x01\x01\x01' or struct.unpack_from('<H', data, 18)[0] != 40:
                    raise ValueError('Expected the fixed ELF32 little-endian ARM library')
            filtered.writestr(entry, data)
        if native != [wanted] or not any(e.filename == 'classes.jar' for e in entries):
            raise ValueError('Exactly one pinned armv7 library and classes.jar required')
    result = target.getvalue()
    return result, {'schema': 1, 'variant': variant, 'version': '150.7871.04',
        'aar_sha256': sha, 'aar_size': size, 'so_filename': filename,
        'so_sha256': so_sha, 'so_size': so_size,
        'namespace': 'livekit.org.webrtc' if prefixed else 'org.webrtc',
        'filtered_aar_sha256': digest(result)}


def atomic(path, data):
    if path.is_symlink() or path.exists() and not path.is_file():
        raise ValueError('Unexpected generated output type')
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.call-probe-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name): os.unlink(name)


def generate(aar, variant, output):
    output = Path(output)
    expected = ROOT / OUTPUT
    if not output.is_absolute() or output != expected:
        raise ValueError('Only the dedicated test-generated build directory is writable')
    for part in (output, *output.parents):
        if part.is_symlink():
            raise ValueError('Generated output symlinks refused')
    allowed = {output: {'aar', 'assets'}, output/'aar': {'webrtc-call-probe.aar'},
        output/'assets': {'call-probe'}, output/'assets/call-probe': {'webrtc.json','NOTICE.txt'}}
    for directory, names in allowed.items():
        if directory.exists() and (not directory.is_dir() or any(p.name not in names or p.is_symlink() for p in directory.iterdir())):
            raise ValueError('Unknown content in dedicated test output')
    filtered, receipt = inspect(aar, variant)
    _, notice = regular(ROOT/'tools/call-probe-NOTICE.txt', 64*1024)
    atomic(output/'aar/webrtc-call-probe.aar', filtered)
    atomic(output/'assets/call-probe/NOTICE.txt', notice)
    atomic(output/'assets/call-probe/webrtc.json', (json.dumps(receipt, sort_keys=True, indent=2)+'\n').encode())
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aar', type=Path, required=True)
    parser.add_argument('--variant', choices=tuple(PINS), required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = generate(args.aar, args.variant, args.output)
        print('CALL-0 test-only armv7 input: ' + result['variant'] + ' / ' + result['aar_sha256'])
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(2, 'CALL-0 local input refused: ' + str(error) + '\n')


if __name__ == '__main__': main()
