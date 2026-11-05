"""MIT. Shared, host-only checks for owner-managed signing and local release preparation."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
P256_PREFIX = bytes.fromhex('3059301306072a8648ce3d020106082a8648ce3d03010703420004')
KEY_ROLES = ('channel', 'system', 'registry')
PRIVATE_PASSWORD_ENV = 'THINKOS_OWNER_KEY_PASSWORD'


def require_owner_host():
    if any(os.environ.get(key, '').lower() not in ('', '0', 'false')
           for key in ('CI', 'GITHUB_ACTIONS', 'GITLAB_CI', 'BUILD_BUILDID')):
        raise ValueError('Signing material is owner-run only; CI is refused')


def regular(path, limit=None):
    path = Path(path)
    if not stat.S_ISREG(path.lstat().st_mode):
        raise ValueError('Expected a regular file, not a link: ' + str(path))
    if limit is not None and path.stat().st_size > limit:
        raise ValueError('File exceeds its size limit: ' + path.name)
    return path


def no_symlink_path(path):
    path = Path(path)
    if not path.is_absolute() or '..' in path.parts:
        raise ValueError('An absolute path without parent traversal is required')
    for candidate in (path, *path.parents):
        if candidate.is_symlink():
            raise ValueError('Symlink paths are refused: ' + str(candidate))
    return path


def outside_repository(path, root=ROOT):
    path = no_symlink_path(path)
    if path == root or root in path.parents:
        raise ValueError('Private material/output must be outside the source repository')
    for ancestor in (path, *path.parents):
        if (ancestor / '.git').exists():
            raise ValueError('Private material/output cannot be inside any Git repository')
    return path


def private_directory(path):
    path = outside_repository(Path(path))
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('Private key directory must be owner-owned mode 0700')
    return path


def private_file(path):
    path = no_symlink_path(path)
    regular(path)
    info = path.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('Private files must be owner-owned mode 0600')
    return path


def create_directory(path, private=False):
    path = outside_repository(Path(path)) if private else no_symlink_path(Path(path))
    if not path.parent.is_dir():
        raise ValueError('Destination parent must already exist')
    # mkdir is intentionally exclusive: never replace, merge or repair an old keyring.
    path.mkdir(mode=0o700 if private else 0o755)
    if private:
        private_directory(path)
    return path


def exclusive_write(path, raw, private=False):
    path = no_symlink_path(Path(path))
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0),
                         0o600 if private else 0o644)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode('utf-8')


def digest(path):
    value = hashlib.sha256()
    with regular(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def run(command, *, cwd=ROOT, env=None, raw=None):
    # Child failures are deliberately not echoed: a signer must never include
    # stderr, argv or environment values in an exception/report.
    # A tool must never read the caller's stdin: OpenSSL 4.1 fails to read an encrypted key when the inherited stdin is a
    # socket (as under some IDE/automation shells), while the same call succeeds with a pipe, a file or /dev/null.
    result = subprocess.run([str(part) for part in command], cwd=cwd, env=env, input=raw,
                            stdin=subprocess.DEVNULL if raw is None else None,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise RuntimeError('Host tool failed: ' + Path(str(command[0])).name)
    return result.stdout


def secret_environment(password):
    if not isinstance(password, str) or len(password) < 12 or '\n' in password or '\x00' in password:
        raise ValueError('Use a passphrase of at least 12 characters without newlines')
    result = os.environ.copy()
    result[PRIVATE_PASSWORD_ENV] = password
    return result


def read_password(descriptor=None):
    import getpass
    import sys
    if descriptor is None:
        if not sys.stdin.isatty():
            raise ValueError('Use an interactive owner terminal or an explicit private --password-fd')
        password = getpass.getpass('Offline key passphrase: ')
        if password != getpass.getpass('Repeat passphrase: '):
            raise ValueError('Passphrases do not match')
    else:
        if descriptor < 3:
            raise ValueError('Password descriptor must be 3 or higher')
        info = os.fstat(descriptor)
        if stat.S_ISREG(info.st_mode) and (info.st_uid != os.getuid() or info.st_mode & 0o077):
            raise ValueError('Password descriptor file is not private to its owner')
        raw = os.read(descriptor, 4097)
        if len(raw) > 4096:
            raise ValueError('Passphrase exceeds the input bound')
        password = raw.decode('utf-8').removesuffix('\n')
    secret_environment(password)
    return password


def p256_xy(spki):
    try:
        raw = base64.b64decode(spki, validate=True)
    except (ValueError, TypeError) as error:
        raise ValueError('Invalid public SPKI') from error
    if base64.b64encode(raw).decode('ascii') != spki or not raw.startswith(P256_PREFIX) or len(raw) != len(P256_PREFIX) + 64:
        raise ValueError('Expected canonical uncompressed P-256 DER SubjectPublicKeyInfo')
    return raw[len(P256_PREFIX):]


def public_key(identifier, raw):
    if not re.fullmatch(r'[a-z][a-z0-9._-]{0,63}', identifier):
        raise ValueError('Invalid public key ID')
    encoded = base64.b64encode(raw).decode('ascii')
    p256_xy(encoded)
    return {'id': identifier, 'algorithm': 'SHA256withECDSA', 'spki': encoded}


def validate_keyring(value, roles=('channel', 'registry')):
    if set(value) != {'schema', *roles} or value['schema'] != 1:
        raise ValueError('Invalid public key document')
    ids, keys = [], []
    for role in roles:
        pair = value[role]
        if set(pair) != {'active', 'backup'}:
            raise ValueError('Each role needs active and backup')
        for key in (pair['active'], pair['backup']):
            if set(key) != {'id', 'algorithm', 'spki'} or key['algorithm'] != 'SHA256withECDSA':
                raise ValueError('Invalid public key entry')
            public_key(key['id'], base64.b64decode(key['spki'], validate=True))
            ids.append(key['id']); keys.append(key['spki'])
    if len(set(ids)) != len(ids) or len(set(keys)) != len(keys):
        raise ValueError('Active/backup and signing roles must use distinct keys')
    return value


def system_header(pair):
    entries = [pair['active'], pair['backup']]
    if entries[0]['spki'] == entries[1]['spki']:
        raise ValueError('System active and backup keys must differ')
    lines = ['/* Generated public P-256 trust roots only. No private material. */',
             '#ifndef THINKOS_UPDATER_KEYS_H', '#define THINKOS_UPDATER_KEYS_H',
             '#define UP_SYSTEM_KEY_COUNT 2', 'static const unsigned char up_system_keys[][64] = {']
    for entry in entries:
        xy = p256_xy(entry['spki'])
        lines.append('  {' + ','.join('0x%02x' % byte for byte in xy) + '},')
    return ('\n'.join(lines + ['};', '#endif', ''])).encode('ascii')


def version_code(version):
    if not re.fullmatch(r'(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})', version):
        raise ValueError('Invalid release version')
    major, minor, patch = map(int, version.split('.'))
    result = major * 1000000 + minor * 1000 + patch
    if result < 1 or result > 2147483647:
        raise ValueError('Release version exceeds the Android integer range')
    return result


def distribution_base(value):
    if not isinstance(value, str) or re.search(r'[\x00-\x20\x7f]', value):
        raise ValueError('Distribution URLs cannot contain whitespace/control characters')
    parsed = urlsplit(value)
    parsed.port  # Validate malformed/overflowing ports as well as the hostname.
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Distribution bases must be plain HTTPS URLs without credentials/query/fragment')
    return value.rstrip('/')
