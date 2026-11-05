"""Build-only REG-2b public roots. No key generation, network or device access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from release_tools import no_symlink_path, outside_repository, p256_xy, validate_keyring

LIMIT = 64 * 1024
ROLES = ('channel', 'registry')
SLOTS = ('active', 'backup')
ASSETS = Path('android/app/build/generated/regFixture-assets')
# NIST P-256 field and coefficient. Validate points without executing a key tool.
P = 0xffffffff00000001000000000000000000000000ffffffffffffffffffffffff
B = 0x5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b


def read_regular(path, allow_empty=False):
    path = no_symlink_path(Path(path))
    if not stat.S_ISREG(path.lstat().st_mode):
        raise ValueError('Public document must be a regular file')
    descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    with os.fdopen(descriptor, 'rb') as source:
        before = os.fstat(source.fileno())
        minimum = 0 if allow_empty else 1
        if not stat.S_ISREG(before.st_mode) or not minimum <= before.st_size <= LIMIT:
            raise ValueError('Public document must be a nonempty regular file within 64 KiB')
        raw = source.read(LIMIT + 1)
        after = os.fstat(source.fileno())
        if len(raw) > LIMIT or len(raw) != before.st_size or (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise ValueError('Public document changed while reading or exceeds its bound')
    return raw


def validate_public(raw):
    if not 0 < len(raw) <= LIMIT or b'PRIVATE KEY' in raw:
        raise ValueError('Only bounded public JSON is accepted')
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate public JSON member')
            result[key] = value
        return result
    def number(_):
        raise ValueError('Public JSON requires integer tokens')
    try:
        value = json.loads(raw.decode('utf-8'), object_pairs_hook=pairs, parse_float=number, parse_constant=number)
        if not isinstance(value, dict) or set(value) != {'schema', *ROLES} or type(value['schema']) is not int or value['schema'] != 1:
            raise ValueError('Expected the exact UpdateKeyDocument')
        runs = set()
        for role in ROLES:
            pair = value[role]
            if not isinstance(pair, dict) or set(pair) != set(SLOTS):
                raise ValueError('Each signing role requires active and backup')
            for slot in SLOTS:
                entry = pair[slot]
                if not isinstance(entry, dict) or set(entry) != {'id', 'algorithm', 'spki'} or not all(isinstance(v, str) for v in entry.values()):
                    raise ValueError('Unexpected public key entry')
                match = re.fullmatch('reg2b-([0-9a-f]{32})-' + role + '-' + slot, entry['id'])
                if not match:
                    raise ValueError('Fixture IDs must bind their role, slot and a single reg2b run ID')
                runs.add(match[1])
                xy = p256_xy(entry['spki'])
                x, y = int.from_bytes(xy[:32], 'big'), int.from_bytes(xy[32:], 'big')
                if x >= P or y >= P or (y * y - (pow(x, 3, P) - 3 * x + B)) % P:
                    raise ValueError('Public SPKI is not a point on P-256')
        if len(runs) != 1:
            raise ValueError('All fixture public keys must use one run ID')
        validate_keyring(value)
        return next(iter(runs))
    except (KeyError, TypeError, UnicodeError) as error:
        raise ValueError('Invalid public fixture document') from error


def check_production_assets():
    # Only the fixture source set may carry this reserved trust namespace.
    # Check the known trust filenames, including alternate/debug trust assets.
    for source_set in ('main', 'debug', 'release'):
        base = ROOT / 'android/app/src' / source_set / 'assets/update'
        no_symlink_path(base)
        for name in ('keys.json', 'debug-keys.json', 'system-keys.json'):
            path = base / name
            if path.is_symlink():
                raise ValueError('Trust asset symlinks are refused')
            if path.exists():
                raw = read_regular(path, allow_empty=True)
                if b'reg2b-' in raw:
                    raise ValueError('REG fixture trust must not enter main/debug/release assets')


def generate(public, output):
    public = outside_repository(no_symlink_path(Path(public)), root=ROOT)
    if public.suffix != '.json':
        raise ValueError('Fixture public input must be a JSON file')
    expected = ROOT / ASSETS
    output = no_symlink_path(Path(output))
    if output != expected:
        raise ValueError('Only the dedicated regFixture generated-assets directory is writable')
    check_production_assets()
    raw = read_regular(public)
    run_id = validate_public(raw)
    target = output / 'update/keys.json'
    no_symlink_path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    no_symlink_path(target)
    if target.exists() and read_regular(target) == raw:
        return {'run_id': run_id, 'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)}
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix='.keys-', dir=target.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        if target.exists() and not stat.S_ISREG(target.lstat().st_mode):
            raise ValueError('Generated trust target must be a regular file')
        os.replace(temporary, target)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return {'run_id': run_id, 'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--check-production', action='store_true')
    action.add_argument('--public', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(argv)
    try:
        if args.check_production:
            if args.output is not None:
                raise ValueError('Read-only production check does not accept output')
            check_production_assets()
        else:
            if args.output is None:
                raise ValueError('A dedicated generated-assets output is required')
            print(json.dumps(generate(args.public, args.output), sort_keys=True))
    except (ValueError, OSError) as error:
        parser.exit(2, 'Registry fixture assets refused: ' + str(error) + '\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
