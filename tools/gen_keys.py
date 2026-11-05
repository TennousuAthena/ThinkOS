"""MIT. Owner-run offline APK keystore and distinct active/backup P-256 signing roles."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys

from release_tools import (KEY_ROLES, PRIVATE_PASSWORD_ENV, ROOT, create_directory, exclusive_write,
                           json_bytes, no_symlink_path, outside_repository, public_key, read_password,
                           private_file, require_owner_host, run, secret_environment, system_header, validate_keyring)


def generate(private, public, password, keytool='keytool', openssl='openssl', backups=()):
    private = outside_repository(Path(private))
    public = no_symlink_path(Path(public))
    destinations = [private, public, *[outside_repository(Path(path)) for path in backups]]
    for i, destination in enumerate(destinations):
        if destination.exists() or not destination.parent.is_dir():
            raise ValueError('Key destinations must be new with existing parents; no overwrite or merging')
        if any(destination == other or destination in other.parents or other in destination.parents for other in destinations[:i]):
            raise ValueError('Public, private and backup destinations must be separate trees')
    environment = secret_environment(password)
    private = create_directory(private, private=True)
    public = create_directory(public)
    pairs = {}
    old_umask = os.umask(0o077)
    try:
        for role in KEY_ROLES:
            pair = {}
            for slot in ('active', 'backup'):
                path = private / (role + '-' + slot + '.key.pem')
                run([openssl, 'genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256',
                     '-aes-256-cbc', '-pass', 'env:' + PRIVATE_PASSWORD_ENV, '-out', path], env=environment)
                os.chmod(path, 0o600)
                encrypted_header = b'-----BEGIN ENCRYPTED '
                encrypted_header += b'PRIVATE KEY-----\n'
                if not path.read_bytes().startswith(encrypted_header):
                    raise ValueError('OpenSSL did not produce encrypted PKCS8 private material')
                raw = run([openssl, 'pkey', '-in', path, '-passin', 'env:' + PRIVATE_PASSWORD_ENV,
                           '-pubout', '-outform', 'DER'], env=environment)
                pair[slot] = public_key(role + '-' + slot, raw)
            pairs[role] = pair
        all_entries = [entry['spki'] for pair in pairs.values() for entry in pair.values()]
        if len(set(all_entries)) != 6:
            raise ValueError('Generated signing roles were not distinct')
        store = private / 'apk-signing.p12'
        run([keytool, '-genkeypair', '-storetype', 'PKCS12', '-keystore', store, '-alias', 'thinkos',
             '-keyalg', 'EC', '-keysize', '256', '-sigalg', 'SHA256withECDSA', '-validity', '10000',
             '-dname', 'CN=ThinkOS,O=ThinkOS', '-storepass:env', PRIVATE_PASSWORD_ENV,
             '-keypass:env', PRIVATE_PASSWORD_ENV, '-noprompt'], env=environment)
        os.chmod(store, 0o600)
        certificate = run([keytool, '-exportcert', '-keystore', store, '-alias', 'thinkos',
                           '-storetype', 'PKCS12', '-storepass:env', PRIVATE_PASSWORD_ENV], env=environment)
        apk = validate_keyring({'schema': 1, 'channel': pairs['channel'], 'registry': pairs['registry']})
        system = {'schema': 1, **pairs['system']}
        exclusive_write(public / 'keys.json', json_bytes(apk))
        exclusive_write(public / 'system-keys.json', json_bytes(system))
        exclusive_write(public / 'updater_keys.h', system_header(pairs['system']))
        exclusive_write(public / 'apk-certificate.der', certificate)
        inventory = {'schema': 1, 'apk': {'alias': 'thinkos', 'sha256': hashlib.sha256(certificate).hexdigest()},
                     'private_files': ['apk-signing.p12'] + [role + '-' + slot + '.key.pem' for role in KEY_ROLES for slot in ('active', 'backup')],
                     'public_files': ['keys.json', 'system-keys.json', 'updater_keys.h', 'apk-certificate.der']}
        for backup_path in destinations[2:]:
            backup = create_directory(backup_path, private=True)
            for filename in inventory['private_files']:
                source = private_file(private / filename)
                exclusive_write(backup / filename, source.read_bytes(), private=True)
                if (backup / filename).read_bytes() != source.read_bytes():
                    raise ValueError('Offline key backup bytes differ from the original')
        inventory['offline_backups_created'] = len(destinations) - 2
        # Only public filenames/fingerprints. Never include passwords or private bytes.
        exclusive_write(public / 'key-inventory.json', json_bytes(inventory))
        return inventory
    finally:
        os.umask(old_umask)
        environment.pop(PRIVATE_PASSWORD_ENV, None)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--private-dir', required=True, type=Path, help='New absolute offline directory outside any repository')
    parser.add_argument('--public-dir', required=True, type=Path, help='New absolute directory containing public files only')
    parser.add_argument('--backup-dir', required=True, action='append', type=Path, help='Supply two separate new offline backup destinations')
    parser.add_argument('--password-fd', type=int, help='Owner-supplied private descriptor >=3, never a CLI password')
    args = parser.parse_args(argv)
    require_owner_host()
    if len(args.backup_dir) != 2:
        raise ValueError('Specify two offline --backup-dir destinations for the APK certificate and signing keys')
    # Validate paths before requesting a secret or creating anything.
    outside_repository(args.private_dir); no_symlink_path(args.public_dir)
    destinations = [args.private_dir, args.public_dir, *[outside_repository(path) for path in args.backup_dir]]
    for index, destination in enumerate(destinations):
        if destination.exists() or not destination.parent.is_dir():
            raise ValueError('All destinations must be new, with existing parents')
        if any(destination == other or destination in other.parents or other in destination.parents for other in destinations[:index]):
            raise ValueError('Key and backup destinations cannot overlap')
    password = read_password(args.password_fd)
    result = generate(args.private_dir, args.public_dir, password, backups=args.backup_dir)
    password = None
    print(json.dumps({'public_directory': str(args.public_dir), 'apk_certificate_sha256': result['apk']['sha256'],
                      'device_changed': False, 'published': False}, ensure_ascii=False))
    print('Verified two encrypted offline backup copies. Unmount the backup media after reviewing the public fingerprints.', file=sys.stderr)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, RuntimeError) as error:
        # No child diagnostics and no exception chaining carrying arguments/secrets.
        print('Key generation stopped: ' + str(error), file=sys.stderr)
        sys.exit(1)
