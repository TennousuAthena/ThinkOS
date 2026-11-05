#!/usr/bin/env python3
"""MIT. Owner-only local LAN rebasing of an already signed release preparation.

This is not release publication or a device runner. Production release.prepare
keeps its HTTPS-only rules. No network, device, builder or key generator runs.
"""
import argparse
import copy
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import time
from urllib.parse import urlsplit
import zipfile

import release
from release_tools import (ROOT, PRIVATE_PASSWORD_ENV, create_directory, digest,
                           exclusive_write, json_bytes, no_symlink_path,
                           outside_repository, private_file, read_password,
                           regular, require_owner_host, secret_environment)

NAME = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,159}')
HASH = re.compile(r'[a-f0-9]{64}')
MAX_FILES = 256
MAX_INPUT_FILE = 512 * 1024 * 1024
MAX_INPUT_TOTAL = 2 * 1024 * 1024 * 1024
MAX_APK = 64 * 1024 * 1024
MAX_MODULE = 16 * 1024 * 1024
MAX_ZIP_ENTRIES = 20000
MAX_ZIP_EXPANDED = 256 * 1024 * 1024
PRIVATE_LANS = tuple(ipaddress.ip_network(value) for value in
                    ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', 'fc00::/7'))


def lan_root(value):
    if not isinstance(value, str) or re.search(r'[\x00-\x20\x7f%\\]', value):
        raise ValueError('Expected a literal private-LAN HTTP root')
    parsed = urlsplit(value)
    port = parsed.port
    if (parsed.scheme != 'http' or parsed.username is not None or parsed.password is not None or
            parsed.query or parsed.fragment or not parsed.hostname or port == 0):
        raise ValueError('Expected a plain private-LAN HTTP root without credentials/query/fragment')
    address = ipaddress.ip_address(parsed.hostname)
    if not any(address.version == network.version and address in network for network in PRIVATE_LANS):
        raise ValueError('Only numeric RFC1918 IPv4 or IPv6 ULA is accepted')
    path = parsed.path.rstrip('/')
    if path and (not path.startswith('/') or any(not NAME.fullmatch(part) or part in ('.', '..')
                                               for part in path[1:].split('/'))):
        raise ValueError('LAN root contains an unsafe path')
    host = '[' + address.compressed + ']' if address.version == 6 else address.compressed
    result = 'http://' + host + (':' + str(port) if port is not None else '') + path
    if len(result) > 1800:
        raise ValueError('LAN root exceeds its bound')
    return result


def _filename(value):
    if not isinstance(value, str) or not NAME.fullmatch(value) or value in ('.', '..'):
        raise ValueError('Prepared files must have bounded simple basenames')
    return value


def _urls_basename(values):
    if not isinstance(values, list) or not 1 <= len(values) <= 8:
        raise ValueError('Missing signed artifact URLs')
    names = set()
    for value in values:
        if not isinstance(value, str) or re.search(r'[\x00-\x20\x7f%\\]', value):
            raise ValueError('Prepared artifact URL is unsafe')
        parsed = urlsplit(value)
        parsed.port
        if (parsed.scheme != 'https' or not parsed.hostname or parsed.username is not None or
                parsed.password is not None or parsed.query or parsed.fragment):
            raise ValueError('Original release preparation must retain HTTPS artifact URLs')
        if any(part in ('.', '..') for part in parsed.path.split('/')):
            raise ValueError('Prepared artifact URL traverses a parent')
        names.add(_filename(parsed.path.rsplit('/', 1)[-1]))
    if len(names) != 1:
        raise ValueError('Signed candidates must identify the same prepared basename')
    return next(iter(names))


def _archive(path, *, arm_libraries=False, require_native=None):
    """Bound ZIP inspection before the existing streaming public-artifact scan.
    Native libraries are read, never loaded: ELF32/little-endian/ARM, not a name
    under armeabi-v7a concealing an arm64 binary.
    """
    require_native = arm_libraries if require_native is None else require_native
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > MAX_ZIP_ENTRIES:
            raise ValueError('Archive has too many entries')
        names, folded, total, native = set(), {}, 0, 0
        for info in entries:
            name = info.filename
            pieces = name.rstrip('/').split('/')
            if (not name or name.startswith('/') or '\\' in name or '\x00' in name or
                    any(part in ('', '.', '..') for part in pieces) or info.flag_bits & 1):
                raise ValueError('Archive contains an unsafe/encrypted path')
            if name in names:
                raise ValueError('Archive contains duplicate entries')
            names.add(name)
            for end in range(1, len(pieces) + 1):
                prefix = '/'.join(pieces[:end])
                if prefix.casefold() in folded and folded[prefix.casefold()] != prefix:
                    raise ValueError('Archive contains case-colliding paths')
                folded[prefix.casefold()] = prefix
            mode = info.external_attr >> 16
            if stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR):
                raise ValueError('Archive contains a link or special file')
            total += info.file_size
            if info.file_size > MAX_APK or total > MAX_ZIP_EXPANDED:
                raise ValueError('Archive expanded bytes exceed their bound')
            if arm_libraries and name.startswith('lib/') and not info.is_dir():
                if not re.fullmatch(r'lib/armeabi-v7a/[^/]+\.so', name):
                    raise ValueError('APK libraries must be armv7 only')
                with archive.open(info) as stream:
                    header = stream.read(52)
                if (len(header) != 52 or header[:6] != b'\x7fELF\x01\x01' or
                        int.from_bytes(header[16:18], 'little') != 3 or
                        int.from_bytes(header[18:20], 'little') != 40):
                    raise ValueError('APK contains a non-ARM32 shared object')
                native += 1
        if require_native and native == 0:
            raise ValueError('APK has no ARM32 native library')
        release.public_archive(archive)


def _sums(directory):
    directory = no_symlink_path(Path(directory))
    if not directory.is_dir():
        raise ValueError('Prepared release must be a directory')
    paths = {}
    with os.scandir(directory) as entries:
        for entry in entries:
            if len(paths) >= MAX_FILES or not entry.is_file(follow_symlinks=False) or entry.is_symlink():
                raise ValueError('Only a bounded flat release.prepare directory is supported')
            name = _filename(entry.name)
            paths[name] = regular(no_symlink_path(Path(entry.path)), MAX_INPUT_FILE)
    raw = regular(directory / 'SHA256SUMS', 65536).read_bytes()
    if not raw or not raw.endswith(b'\n'):
        raise ValueError('Invalid prepared checksum list')
    expected = {}
    for line in raw.decode('ascii').splitlines():
        match = re.fullmatch(r'([a-f0-9]{64})  ([A-Za-z0-9][A-Za-z0-9._-]{0,159})', line)
        if not match or match[2] == 'SHA256SUMS' or match[2] in expected:
            raise ValueError('Unsafe/duplicate checksum entry')
        expected[_filename(match[2])] = match[1]
    if set(expected) != set(paths) - {'SHA256SUMS'}:
        raise ValueError('Checksums must cover every prepared top-level file exactly')
    actual = {'SHA256SUMS': hashlib.sha256(raw).hexdigest()}
    total = len(raw)
    for name, sha in expected.items():
        total += paths[name].stat().st_size
        if total > MAX_INPUT_TOTAL or digest(paths[name]) != sha:
            raise ValueError('Prepared checksum/aggregate size mismatch')
        actual[name] = sha
    return paths, actual


def _trusted_public(public, source_root):
    trust = release.public_trust(public, root=source_root)
    inventory = release.document(no_symlink_path(public / 'key-inventory.json'), 65536)
    certificate = regular(no_symlink_path(public / 'apk-certificate.der'), 65536)
    signer = inventory['apk']['sha256']
    if not isinstance(signer, str) or not HASH.fullmatch(signer) or digest(certificate) != signer or signer == release.DEBUG_CERTIFICATE:
        raise ValueError('Invalid owner APK certificate inventory')
    return trust, signer, {**{name: hashlib.sha256(raw).hexdigest() for name, raw in trust['files'].items()},
                           'key-inventory.json': digest(public / 'key-inventory.json'),
                           'apk-certificate.der': digest(certificate)}


def _vector(path, module, signer, tools):
    aapt, apksigner = tools
    verification = release.run([apksigner, 'verify', '--verbose', '--print-certs', path]).decode('utf-8')
    fingerprints = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$', verification, re.MULTILINE)
    if [value.lower() for value in fingerprints] != [signer] or 'CN=Android Debug' in verification:
        raise ValueError('Vector APK must have the owner certificate')
    badging = release.run([aapt, 'dump', 'badging', path]).decode('utf-8')
    identity = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    if (not identity or identity[1] != 'moe.kiwi.thinkos.immersive' or int(identity[2]) != module['code'] or
            identity[3] != module['version'] or release.apk_sdk_versions(badging) != (27, 27) or
            'application-debuggable' in badging):
        raise ValueError('Vector APK differs from the signed module identity')


def _magisk(path, module):
    with zipfile.ZipFile(path) as archive:
        info = archive.getinfo('module.prop')
        if info.file_size > 65536:
            raise ValueError('Module properties exceed their bound')
        properties = {}
        for line in archive.read(info).decode('utf-8').splitlines():
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            if '=' not in line:
                raise ValueError('Invalid module property')
            key, value = line.split('=', 1)
            if key in properties:
                raise ValueError('Duplicate module property')
            properties[key] = value
        if any(properties.get(key) != value for key, value in
               (('id', module['id']), ('version', module['version']), ('versionCode', str(module['code'])))):
            raise ValueError('Magisk module properties differ from the signed tuple')


def _previous(args, trust, original):
    if args.initial:
        if args.previous_catalog is not None:
            raise ValueError('Initial use cannot supply a prior LAN catalog')
        return original['seq'], None
    if args.previous_catalog is None:
        raise ValueError('Provide a previous authenticated LAN catalog or explicit --initial')
    path = no_symlink_path(args.previous_catalog)
    raw = regular(path, 1024 * 1024).read_bytes()
    signature = regular(no_symlink_path(Path(str(path) + '.sig')), 1024).read_bytes()
    release.verify(path, signature, list(trust['keys']['channel'].values()))
    previous = release.validate_catalog(release.parse_document(raw))
    if previous['channel'] != original['channel'] or previous['release']['code'] > original['release']['code']:
        raise ValueError('Previous channel/version cannot be rolled back')
    if previous['release']['code'] == original['release']['code']:
        if any(previous['release'][key] != original['release'][key] for key in ('version', 'code', 'sha256', 'size', 'signer', 'sdk')):
            raise ValueError('An existing APK version cannot be replaced')
    known = {module['id']: module for module in previous['release']['modules']}
    for module in original['release']['modules']:
        prior = known.get(module['id'])
        if prior and (module['code'] < prior['code'] or module['code'] == prior['code'] and
                      any(module[key] != prior[key] for key in ('kind', 'version', 'sha256', 'size'))):
            raise ValueError('An existing module version cannot be replaced or rolled back')
    return max(previous['seq'], original['seq']), {'document': hashlib.sha256(raw).hexdigest(),
                                                 'signature': hashlib.sha256(signature).hexdigest()}


def _prepare_snapshot(args, original_args, *, source_root=None, tools=None):
    require_owner_host()
    source_root = ROOT if source_root is None else Path(source_root)
    original_directory = no_symlink_path(original_args.release_dir)
    original_public = no_symlink_path(original_args.public_dir)
    directory = no_symlink_path(args.release_dir)
    public = no_symlink_path(args.public_dir)
    output = outside_repository(args.output)
    if output.exists() or not output.parent.is_dir():
        raise ValueError('Output must be new with an existing parent')
    if any(output == root or output in root.parents or root in output.parents for root in (directory, public)):
        raise ValueError('Output cannot overlap release or public inputs')
    base = lan_root(args.lan_root)
    paths, hashes = _sums(directory)
    report = release.document(paths['release-preparation.json'])
    if (report.get('schema') != 1 or report.get('stage') != 'prepared' or report.get('published') is not False or
            not re.fullmatch('[a-f0-9]{40}', report.get('source_commit', ''))):
        raise ValueError('A completed release.prepare report is required')
    trust, signer, public_hashes = _trusted_public(public, source_root)
    if report['public_trust'] != {name: public_hashes[name] for name in trust['files']}:
        raise ValueError('Prepared public roots differ from the owner export/assets')
    channel_name = _filename(report['catalog']['path'])
    raw_path = paths[channel_name]
    raw = regular(raw_path, 1024 * 1024).read_bytes()
    if hashlib.sha256(raw).hexdigest() != report['catalog']['sha256']:
        raise ValueError('Prepared catalog raw bytes differ from the report')
    release.verify(raw_path, regular(paths[channel_name + '.sig'], 1024).read_bytes(), list(trust['keys']['channel'].values()))
    original = release.validate_catalog(release.parse_document(raw))
    package = original['release']
    if (channel_name != original['channel'] + '.json' or report['catalog']['seq'] != original['seq'] or
            report['version'] != package['version'] or report['source_tag'] != 'v' + package['version'] or
            report['modules'] != package['modules'] or package['signer'] != signer or package['sdk'] != 27):
        raise ValueError('Prepared report/catalog identities disagree')
    if original['expires'] <= int(time.time() * 1000):
        raise ValueError('LAN preparation refuses an expired original catalog')
    release.distribution_base(package['source'])
    if not package['source'].endswith('/tree/' + report['source_commit']) and not package['source'].endswith('/tree/v' + package['version']):
        raise ValueError('Corresponding source does not bind the prepared version/commit')
    minimum, previous_hashes = _previous(args, trust, original)
    if type(args.seq) is not int or not minimum < args.seq <= 9223372036854775807:
        raise ValueError('LAN sequence must exceed original and prior authenticated sequences')
    tools = release.sdk_tools() if tools is None else tools
    apk_name = _urls_basename(package['urls'])
    if apk_name != 'thinkos-' + package['version'] + '.apk':
        raise ValueError('Expected the release.prepare APK filename')
    apk = regular(paths[apk_name], MAX_APK)
    if apk.stat().st_size != package['size'] or hashes[apk_name] != package['sha256']:
        raise ValueError('APK bytes differ from the signed catalog')
    _archive(apk, arm_libraries=True)
    actual = release.inspect_apk(apk, signer, package['version'], debuggable=False, tools=tools)
    if any(actual[key] != package[key] for key in actual):
        raise ValueError('Actual formal APK differs from the signed catalog tuple')
    release.packaged_public_trust(apk, trust)
    whitelist = {apk_name: apk}
    rebased = copy.deepcopy(original)
    rebased['seq'] = args.seq
    rebased['release']['urls'] = [base + '/' + apk_name]
    for module, changed in zip(package['modules'], rebased['release']['modules']):
        payload_name = _urls_basename(module['urls'])
        suffix = '.apk' if module['kind'] == 'vector' else '.zip'
        if not payload_name.startswith(module['id'] + '-') or not payload_name.endswith(suffix):
            raise ValueError('Expected a prepared module payload filename')
        manifest_name = _urls_basename(module['manifest_urls'])
        if manifest_name != module['id'] + '-' + str(module['code']) + '.manifest':
            raise ValueError('Unexpected prepared module manifest name')
        signature_name = manifest_name + '.sig'
        payload = regular(paths[payload_name], MAX_MODULE)
        manifest = regular(paths[manifest_name], 65536)
        signature = regular(paths[signature_name], 1024)
        if payload.stat().st_size != module['size'] or hashes[payload_name] != module['sha256']:
            raise ValueError('Module bytes differ from the signed catalog')
        expected = release.module_manifest(module['id'], module['kind'], module['code'], module['version'], module['sha256'], module['size'])
        if manifest.read_bytes() != expected:
            raise ValueError('System manifest is not the exact signed seven-line tuple')
        release.verify(manifest, signature.read_bytes(), list(trust['system_pair'].values()))
        _archive(payload, arm_libraries=module['kind'] == 'vector', require_native=False)
        if module['kind'] == 'magisk':
            _magisk(payload, module)
        else:
            _vector(payload, module, signer, tools)
        for name, path in ((payload_name, payload), (manifest_name, manifest), (signature_name, signature)):
            if name in whitelist:
                raise ValueError('Publication whitelist filenames collide')
            whitelist[name] = path
        changed['urls'] = [base + '/' + payload_name]
        changed['manifest_urls'] = [base + '/' + manifest_name]
    release.validate_catalog(rebased)
    if channel_name in whitelist or channel_name + '.sig' in whitelist:
        raise ValueError('Catalog filenames collide with artifact names')
    final_bytes = json_bytes(rebased)
    input_fingerprint = {'release_files': hashes, 'public_files': public_hashes, 'previous': previous_hashes,
                         'initial': args.initial, 'lan_root': base, 'seq': args.seq,
                         'slot': args.slot, 'output': str(output), 'kind': 'release'}
    reviewed = hashlib.sha256(json_bytes(input_fingerprint)).hexdigest()
    result = {'schema': 1, 'stage': 'planned', 'artifact_kind': 'release', 'executed': False, 'published': False,
              'device_changed': False, 'input_sha256': reviewed, 'original_catalog_sha256': hashlib.sha256(raw).hexdigest(),
              'catalog_sha256': hashlib.sha256(final_bytes).hexdigest(), 'previous_seq': minimum, 'seq': args.seq,
              'channel': original['channel'], 'mirror': base + '/' + channel_name,
              'files': sorted([*whitelist, channel_name, channel_name + '.sig']),
              'key_id': trust['keys']['channel'][args.slot]['id'],
              'scope': 'Local signed LAN materials only; complete OTA-5 device runner and acceptance remain pending'}
    if args.reviewed_sha256 is not None and args.reviewed_sha256 != reviewed:
        raise ValueError('Inputs or LAN plan changed since owner review')
    if not args.execute:
        return result
    if args.reviewed_sha256 is None:
        raise ValueError('Execution requires the input SHA-256 from the reviewed plan')
    # All artifacts/public roots have been authenticated before private material
    # is opened. Copy only data referenced by the signed catalog, never reports,
    # source archives, keys, build logs or private material into the HTTP root.
    create_directory(output)
    for name, source in whitelist.items():
        raw_file = regular(no_symlink_path(source), MAX_APK).read_bytes()
        if hashlib.sha256(raw_file).hexdigest() != hashes[name]:
            raise ValueError('An artifact changed during LAN preparation')
        exclusive_write(output / name, raw_file)
    if _trusted_public(original_public, source_root) != (trust, signer, public_hashes) or _sums(original_directory)[1] != hashes:
        raise ValueError('Owner trust or release preparation changed before signing')
    _require_previous_unchanged(original_args.previous_catalog, previous_hashes)
    key = private_file(outside_repository(args.private_key))
    password = read_password(args.password_fd)
    environment = secret_environment(password)
    password = None
    try:
        with tempfile.TemporaryDirectory(prefix='thinkos-owner-lan-catalog-') as name:
            document = Path(name).resolve() / channel_name
            exclusive_write(document, final_bytes)
            signature = release.sign(document, key, trust['keys']['channel'][args.slot], environment)
            release.verify(document, signature, list(trust['keys']['channel'].values()))
        if _trusted_public(original_public, source_root) != (trust, signer, public_hashes) or _sums(original_directory)[1] != hashes:
            raise ValueError('Owner trust or source changed while waiting for signing')
        _require_previous_unchanged(original_args.previous_catalog, previous_hashes)
        if {path.name for path in output.iterdir()} != set(whitelist):
            raise ValueError('Unexpected output content appeared before the catalog was committed')
        for name in whitelist:
            if digest(regular(no_symlink_path(output / name), MAX_APK)) != hashes[name]:
                raise ValueError('A copied artifact changed before the catalog was committed')
        exclusive_write(output / (channel_name + '.sig'), signature)
        # Catalog is last: an incomplete directory never offers an unsigned or
        # not-yet-verified catalog to a mistakenly started local HTTP server.
        exclusive_write(output / channel_name, final_bytes)
    finally:
        environment.pop(PRIVATE_PASSWORD_ENV, None)
    result.update(stage='prepared', executed=True,
                  output_sha256={path.name: digest(path) for path in output.iterdir()})
    return result


def _copy_open(directory_fd, name, target, limit):
    try:
        info = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        if not stat.S_ISREG(info.st_mode):
            raise ValueError('Snapshot source is not a regular file')
        descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | getattr(os, 'O_CLOEXEC', 0), dir_fd=directory_fd)
    except OSError as error:
        raise ValueError('Snapshot source is unavailable or changed before open') from error
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode) or before.st_size > limit:
            raise ValueError('Snapshot source must be a bounded regular file')
        target_fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        count = 0
        with os.fdopen(target_fd, 'wb') as output:
            while True:
                block = os.read(descriptor, min(1024 * 1024, limit - count + 1))
                if not block:
                    break
                count += len(block)
                if count > limit:
                    raise ValueError('Snapshot source grew beyond its bound')
                output.write(block)
        after = os.fstat(descriptor)
        if ((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) !=
                (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) or count != before.st_size):
            raise ValueError('Snapshot source changed while copying')
        return count
    finally:
        os.close(descriptor)


def _snapshot_flat(source, target, names=None):
    source = no_symlink_path(Path(source))
    target.mkdir(mode=0o700)
    directory_fd = os.open(source, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        names = os.listdir(directory_fd) if names is None else list(names)
        if len(names) > MAX_FILES:
            raise ValueError('Snapshot has too many input files')
        total = 0
        for name in sorted(names):
            _filename(name)
            # These never belong in release.prepare output or its public exports.
            if name == 'keystore.properties' or Path(name).suffix.lower() in {'.p12', '.jks', '.keystore', '.key', '.pem'}:
                raise ValueError('Private-material filenames are not accepted as release input')
            total += _copy_open(directory_fd, name, target / name, MAX_INPUT_FILE)
            if total > MAX_INPUT_TOTAL:
                raise ValueError('Snapshot aggregate bytes exceed their bound')
    finally:
        os.close(directory_fd)


def _snapshot_previous(path, target):
    source = no_symlink_path(Path(path))
    target.parent.mkdir(mode=0o700)
    directory_fd = os.open(source.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        _copy_open(directory_fd, source.name, target, 1024 * 1024)
        _copy_open(directory_fd, source.name + '.sig', Path(str(target) + '.sig'), 1024)
    finally:
        os.close(directory_fd)


def _require_previous_unchanged(source, expected):
    if source is None:
        return
    with tempfile.TemporaryDirectory(prefix='thinkos-owner-lan-previous-check-') as name:
        current = Path(name).resolve() / 'previous/catalog.json'
        _snapshot_previous(source, current)
        if {'document': digest(current), 'signature': digest(Path(str(current) + '.sig'))} != expected:
            raise ValueError('Previous authenticated input changed while preparing LAN materials')


def prepare(args, *, source_root=None, tools=None):
    require_owner_host()
    # Validate public destination semantics before reading/copying large inputs.
    directory = no_symlink_path(args.release_dir)
    public = no_symlink_path(args.public_dir)
    output = outside_repository(args.output)
    lan_root(args.lan_root)
    if output.exists() or not output.parent.is_dir():
        raise ValueError('Output must be new with an existing parent')
    if any(output == root or output in root.parents or root in output.parents for root in (directory, public)):
        raise ValueError('Output cannot overlap release or public inputs')
    # SDK tools, JSON readers, signature checks and later copies all use the same
    # private snapshot bytes. An input rename cannot mix one APK's identity with
    # another APK's digest after it has been verified.
    with tempfile.TemporaryDirectory(prefix='thinkos-owner-lan-inputs-') as name:
        workspace = Path(name).resolve()
        snapshot = copy.copy(args)
        snapshot.release_dir, snapshot.public_dir = workspace / 'release', workspace / 'public'
        _snapshot_flat(directory, snapshot.release_dir)
        _snapshot_flat(public, snapshot.public_dir,
                       ('keys.json', 'system-keys.json', 'updater_keys.h', 'key-inventory.json', 'apk-certificate.der'))
        if args.previous_catalog is not None:
            snapshot.previous_catalog = workspace / 'previous/catalog.json'
            _snapshot_previous(args.previous_catalog, snapshot.previous_catalog)
        return _prepare_snapshot(snapshot, args, source_root=source_root, tools=tools)


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('--release-dir', type=Path, required=True)
    result.add_argument('--public-dir', type=Path, required=True)
    result.add_argument('--output', type=Path, required=True)
    result.add_argument('--lan-root', required=True)
    result.add_argument('--seq', type=int, required=True)
    previous = result.add_mutually_exclusive_group(required=True)
    previous.add_argument('--initial', action='store_true')
    previous.add_argument('--previous-catalog', type=Path)
    result.add_argument('--private-key', type=Path, required=True)
    result.add_argument('--slot', choices=('active', 'backup'), default='active')
    result.add_argument('--password-fd', type=int)
    result.add_argument('--reviewed-sha256')
    result.add_argument('--execute', action='store_true')
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    print(json_bytes(prepare(args)).decode('utf-8'), end='')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, RuntimeError, KeyError, TypeError, zipfile.BadZipFile, UnicodeError) as error:
        # No command lines, private filenames, child diagnostics or environments.
        print('LAN preparation stopped: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
