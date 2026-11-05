"""MIT. Prepare a signed, reviewable release locally. Never push, tag, publish or install modules."""
import argparse
import base64
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile

from release_tools import (PRIVATE_PASSWORD_ENV, ROOT, create_directory, digest, distribution_base,
                           exclusive_write, json_bytes, no_symlink_path, outside_repository, p256_xy,
                           private_directory, private_file, read_password, regular, require_owner_host,
                           run, secret_environment, system_header, validate_keyring, version_code)

MODULE_BUILDERS = {
    'thinkos-updater': 'system/build-updater.sh', 'thinkos-cast': 'system/build.sh',
    'thinkos-immersive': 'system/build-immersive.sh', 'thinkos-shell': 'system/build-shell.sh',
    'thinkos-certs': 'system/build-certs.sh', 'thinkos-power': 'system/build-power.sh',
    'timesync': 'system/build-timesync.sh',
}
DEBUG_CERTIFICATE = 'f528265172b40b1ce0dfa520896c02fe72cc7566b8e81a9cee74e9a9cf7cf80d'
LANGUAGES = {'zh-Hans', 'zh-Hant', 'en', 'es', 'fr', 'pt', 'ru', 'ar', 'hi', 'bn', 'ur'}


def parse_document(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate JSON member')
            result[key] = value
        return result
    def invalid_number(_):
        raise ValueError('JSON requires decimal integer tokens')
    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs,
                      parse_float=invalid_number, parse_constant=invalid_number)


def document(path, limit=1024 * 1024):
    return parse_document(regular(path, limit).read_bytes())


def public_trust(public, root=None):
    """Read-only, before any key access/build/sign: one owner-approved trust set.

    Source assets must retain the public exports' exact bytes, not merely an
    equivalent JSON projection. No downloaded/debug/fallback root is accepted,
    and this function never repairs or copies an APK input.
    """
    public = no_symlink_path(Path(public))
    root = ROOT if root is None else Path(root)
    files = {name: regular(no_symlink_path(public / name), 64 * 1024).read_bytes()
             for name in ('keys.json', 'system-keys.json', 'updater_keys.h')}
    keys = parse_document(files['keys.json'])
    system = parse_document(files['system-keys.json'])
    try:
        if not isinstance(keys, dict) or type(keys.get('schema')) is not int or keys['schema'] != 1:
            raise ValueError('Invalid APK public trust document')
        validate_keyring(keys)
        if not isinstance(system, dict) or set(system) != {'schema', 'active', 'backup'} or type(system['schema']) is not int or system['schema'] != 1:
            raise ValueError('System public document must be flat schema/active/backup')
        pair = {slot: system[slot] for slot in ('active', 'backup')}
        validate_keyring({'schema': 1, 'system': pair}, roles=('system',))
        entries = [keys[role][slot] for role in ('channel', 'registry') for slot in ('active', 'backup')]
        entries += [pair[slot] for slot in ('active', 'backup')]
        if len({entry['id'] for entry in entries}) != 6 or len({entry['spki'] for entry in entries}) != 6:
            raise ValueError('All six system/channel/registry public roots and IDs must be distinct')
        if files['updater_keys.h'] != system_header(pair):
            raise ValueError('Updater public header differs from the reviewed system roots')
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError('Invalid owner public trust structure') from error
    for name in ('keys.json', 'system-keys.json'):
        asset = no_symlink_path(root / 'android/app/src/main/assets/update' / name)
        if regular(asset, 64 * 1024).read_bytes() != files[name]:
            raise ValueError('APK public trust asset differs from the owner export: ' + name)
        for variant in ('debug', 'release'):
            override = no_symlink_path(root / 'android/app/src' / variant / 'assets/update' / name)
            if override.exists() and regular(override, 64 * 1024).read_bytes() != files[name]:
                raise ValueError('APK variant overrides a reviewed public trust asset: ' + variant + '/' + name)
    return {'keys': keys, 'system_pair': pair, 'files': files}


def unchanged_public_trust(public, expected):
    current = public_trust(public)
    if current != expected:
        raise ValueError('Public trust changed during local preparation; start again after owner review')


def packaged_public_trust(apk, expected):
    """Recheck both actual APK assets after Gradle, before signing documents."""
    with zipfile.ZipFile(regular(apk, 64 * 1024 * 1024)) as archive:
        for name in ('keys.json', 'system-keys.json'):
            path = 'assets/update/' + name
            entries = [item for item in archive.infolist() if item.filename == path]
            if len(entries) != 1 or entries[0].is_dir() or entries[0].file_size > 64 * 1024:
                raise ValueError('Built APK public trust asset is missing, duplicate or oversized: ' + name)
            with archive.open(entries[0]) as stream:
                raw = stream.read(64 * 1024 + 1)
            if raw != expected['files'][name]:
                raise ValueError('Built APK public trust asset differs from the owner export: ' + name)


def validate_catalog(value):
    spec = importlib.util.spec_from_file_location('thinkos_release_schema', ROOT / 'shared/gen/schema_tools.py')
    validator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(validator)
    validator.SCHEMAS = {path.name: json.loads(path.read_text()) for path in (ROOT / 'shared/schema/update').glob('*.json')}
    validator.validate(value, document='catalog.json')
    release = value['release']
    if release['code'] != version_code(release['version']) or value['expires'] <= value['published']:
        raise ValueError('Catalog version/code or dates disagree')
    modules = release['modules']
    if len({item['id'] for item in modules}) != len(modules):
        raise ValueError('Duplicate module IDs')
    for item in modules:
        if item['kind'] != ('vector' if item['id'] == 'thinkos-immersive' else 'magisk'):
            raise ValueError('Module kind does not match its allowed ID')
    return value


def catalog_urls(values, channel):
    """Further catalogs of THIS channel that devices should ask after their configured sources (signed `mirrors`)."""
    result = []
    for value in values or []:
        url = distribution_base(value)
        if not url.endswith('/' + channel + '.json'):
            raise ValueError('A catalog mirror must end with /' + channel + '.json')
        if url in result:
            raise ValueError('Duplicate catalog mirror')
        result.append(url)
    if len(result) > 8:
        raise ValueError('At most eight catalog mirrors')
    return result


def check_clean(root=ROOT):
    if run(['git', 'status', '--porcelain=v1', '--untracked-files=all'], cwd=root).strip():
        raise ValueError('Release preparation requires a clean committed worktree, including untracked files')
    return run(['git', 'rev-parse', 'HEAD'], cwd=root).decode('ascii').strip()


def scan_tracked_secrets(root=ROOT):
    marker = rb'^-----BEGIN (?:[A-Z]+ )*'
    marker += rb'PRIVATE KEY-----\r?$'
    pattern = re.compile(marker, re.MULTILINE)
    names = run(['git', 'ls-files', '-z'], cwd=root).split(b'\x00')
    for raw_name in names:
        if not raw_name:
            continue
        relative = Path(os.fsdecode(raw_name))
        if relative.name == 'keystore.properties' or relative.suffix.lower() in {'.p12', '.jks', '.keystore', '.key'}:
            raise ValueError('Tracked signing material is forbidden')
        path = root / relative
        if path.is_file():
            # Line overlap handles a PEM delimiter crossing a chunk boundary.
            previous = b''
            with path.open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    if pattern.search(previous + block):
                        raise ValueError('Tracked private PEM material is forbidden')
                    previous = block[-256:]


def sdk_tools():
    sdk = next((line.split('=', 1)[1] for line in (ROOT / 'android/local.properties').read_text().splitlines()
                if line.startswith('sdk.dir=')), None)
    if not sdk:
        raise ValueError('android/local.properties must identify the SDK')
    build = Path(sdk) / 'build-tools/35.0.0'
    for name in ('aapt', 'apksigner'):
        if not (build / name).is_file():
            raise ValueError('Android build tools are unavailable')
    return build / 'aapt', build / 'apksigner'


def public_archive(archive):
    marker = rb'^-----BEGIN (?:[A-Z]+ )*'
    marker += rb'PRIVATE KEY-----\r?$'
    pattern = re.compile(marker, re.MULTILINE)
    for info in archive.infolist():
        path = PurePosixPath(info.filename)
        if path.is_absolute() or '..' in path.parts or path.name == 'keystore.properties' or path.suffix.lower() in {'.p12', '.jks', '.keystore', '.key'}:
            raise ValueError('An artifact contains an unsafe path or signing material')
        if info.is_dir():
            continue
        previous = b''
        with archive.open(info) as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                if pattern.search(previous + block):
                    raise ValueError('An artifact contains private PEM material')
                previous = block[-256:]


def apk_sdk_versions(badging):
    minimum, target = [], []
    for line in badging.splitlines():
        match = re.fullmatch(r"(?:sdkVersion|minSdkVersion):'([0-9]+)'", line)
        if match:
            minimum.append(int(match[1]))
        match = re.fullmatch(r"targetSdkVersion:'([0-9]+)'", line)
        if match:
            target.append(int(match[1]))
    if len(minimum) != 1 or len(target) != 1:
        raise ValueError('APK badging must contain one exact min/target SDK field')
    return minimum[0], target[0]


def inspect_apk(path, expected_signer, version=None, debuggable=False, tools=None, fairplay=False):
    path = regular(path, 64 * 1024 * 1024)
    aapt, signer = tools or sdk_tools()
    verification = run([signer, 'verify', '--verbose', '--print-certs', path]).decode('utf-8')
    fingerprints = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$', verification, re.MULTILINE)
    if len(fingerprints) != 1 or fingerprints[0].lower() != expected_signer or expected_signer == DEBUG_CERTIFICATE:
        raise ValueError('APK must have the owner certificate as its single signer')
    if 'CN=Android Debug' in verification:
        raise ValueError('A debug certificate cannot qualify a distribution APK')
    badging = run([aapt, 'dump', 'badging', path]).decode('utf-8')
    package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    if not package or package[1] != 'moe.kiwi.thinkos':
        raise ValueError('Unexpected application package')
    if version is not None and (package[3] != version or int(package[2]) != version_code(version)):
        raise ValueError('APK version differs from the committed source version')
    if apk_sdk_versions(badging) != (27, 27):
        raise ValueError('APK must keep minSdk/targetSdk 27')
    if ('application-debuggable' in badging) != debuggable:
        raise ValueError('Unexpected APK debuggability')
    with zipfile.ZipFile(path) as archive:
        public_archive(archive)
        libraries = [name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')]
        if not libraries or any(not name.startswith('lib/armeabi-v7a/') for name in libraries):
            raise ValueError('Application native libraries must be armv7 only')
        if not debuggable and 'assets/update/debug-keys.json' in archive.namelist():
            raise ValueError('Debug-only signing roots leaked into release assets')
        # OWN-32: the GPLv3 FairPlay library ships only when the owner asks for it with --fairplay.
        if ('lib/armeabi-v7a/libthinkos_playfair.so' in libraries) != fairplay:
            raise ValueError('APK FairPlay content differs from the requested build option')
    return {'version': package[3], 'code': int(package[2]), 'sha256': digest(path),
            'size': path.stat().st_size, 'signer': expected_signer, 'sdk': 27}


def public_pem(entry, openssl='openssl'):
    p256_xy(entry['spki'])
    return run([openssl, 'pkey', '-pubin', '-inform', 'DER', '-outform', 'PEM'], raw=base64.b64decode(entry['spki']))


def verify(path, signature, entries, openssl='openssl'):
    if not signature or len(signature) > 1024:
        raise ValueError('Invalid signature size')
    with tempfile.TemporaryDirectory(prefix='thinkos-release-verify-') as name:
        temporary = Path(name)
        (temporary / 'signature.der').write_bytes(signature)
        for entry in entries:
            (temporary / 'public.pem').write_bytes(public_pem(entry, openssl))
            try:
                run([openssl, 'dgst', '-sha256', '-verify', temporary / 'public.pem',
                     '-signature', temporary / 'signature.der', path])
                return
            except RuntimeError:
                pass
    raise ValueError('Signature does not match the compiled public trust roots')


def sign(path, key, entry, environment, openssl='openssl'):
    key = private_file(key)
    encrypted_header = b'-----BEGIN ENCRYPTED '
    encrypted_header += b'PRIVATE KEY-----\n'
    if not key.read_bytes().startswith(encrypted_header):
        raise ValueError('Release signing requires encrypted PKCS8 private material')
    public = run([openssl, 'pkey', '-in', key, '-passin', 'env:' + PRIVATE_PASSWORD_ENV,
                  '-pubout', '-outform', 'DER'], env=environment)
    if base64.b64encode(public).decode('ascii') != entry['spki']:
        raise ValueError('Private key does not match its selected public role')
    signature = run([openssl, 'dgst', '-sha256', '-sign', key, '-passin', 'env:' + PRIVATE_PASSWORD_ENV, path], env=environment)
    verify(path, signature, [entry], openssl)
    return signature


def module_manifest(identifier, kind, code, version, sha256, size):
    if identifier not in MODULE_BUILDERS or kind != ('vector' if identifier == 'thinkos-immersive' else 'magisk'):
        raise ValueError('Unsupported module identity')
    if not isinstance(code, int) or isinstance(code, bool) or not 1 <= code <= 2147483647:
        raise ValueError('Invalid module version code')
    if not isinstance(version, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]{0,47}', version) or not isinstance(sha256, str) or not re.fullmatch('[a-f0-9]{64}', sha256):
        raise ValueError('Invalid module version/hash')
    if not isinstance(size, int) or isinstance(size, bool) or not 1 <= size <= 16 * 1024 * 1024:
        raise ValueError('Invalid module size')
    # Catalog vocabulary stays "vector"; the root daemon's seven-line wire
    # protocol spells this fixed component kind "vector-apk".
    wire_kind = 'vector-apk' if kind == 'vector' else 'magisk'
    return (f'thinkos-module 1\nid={identifier}\nkind={wire_kind}\ncode={code}\nversion={version}\n'
            f'sha256={sha256}\nsize={size}\n').encode('ascii')


def module_requests(path):
    values = document(path, 64 * 1024) if path else []
    if not isinstance(values, list) or len(values) > 5:
        raise ValueError('Select at most five modules per channel document')
    seen = set()
    for value in values:
        if not isinstance(value, dict) or set(value) - {'id', 'policy', 'mode'} or not {'id', 'policy'} <= set(value):
            raise ValueError('Module selection supports only id/policy and certificate mode')
        identifier = value['id']
        if identifier not in MODULE_BUILDERS or identifier in seen or value['policy'] not in ('always', 'installed', 'feature'):
            raise ValueError('Invalid or repeated module selection')
        if 'mode' in value and (identifier != 'thinkos-certs' or value['mode'] not in ('add', 'replace')):
            raise ValueError('Only the certificate module accepts add/replace mode')
        seen.add(identifier)
    return values


def copy_artifact(source, destination):
    source = regular(source)
    exclusive_write(destination, source.read_bytes())
    if digest(source) != digest(destination):
        raise ValueError('Artifact copy changed its bytes')


def gpl_attachments(manifest, destination):
    manifest = regular(manifest, 64 * 1024)
    entries = document(manifest, 64 * 1024)
    lock = document(ROOT / 'tools/ffmpeg-source.lock.json', 64 * 1024)
    archive_name = lock['root'] + '.tar.gz'
    expected = {archive_name, archive_name + '.sha256', 'LICENSE-GPL-3.0.md'}
    if not isinstance(entries, list) or {entry.get('path') for entry in entries} != expected or len(entries) != 3:
        raise ValueError('LIC-04 must provide the exact FFmpeg source/checksum and GPLv3 attachment set')
    attachments = []
    for entry in entries:
        if Path(entry['path']).name != entry['path']:
            raise ValueError('Attachment paths must be basenames')
        source = regular(manifest.parent / entry['path'])
        actual = digest(source)
        if 'sha256' in entry and entry['sha256'] != actual:
            raise ValueError('LIC-04 attachment hash mismatch')
        if source.name == 'LICENSE-GPL-3.0.md' and source.read_bytes() != (ROOT / source.name).read_bytes():
            raise ValueError('GPLv3 attachment must equal the repository license')
        copy_artifact(source, destination / source.name)
        attachments.append({'path': source.name, 'sha256': actual, 'size': source.stat().st_size})
    checksum = (manifest.parent / (archive_name + '.sha256')).read_text().strip().split()
    if len(checksum) != 2 or checksum[0] != digest(manifest.parent / archive_name) or checksum[1] != archive_name:
        raise ValueError('FFmpeg source checksum sidecar disagrees')
    with tarfile.open(manifest.parent / archive_name, 'r:gz') as archive:
        names = archive.getnames()
        if not any(name.endswith('/ffmpeg/configure') for name in names) or not any('/decoder_ffmpeg/' in name for name in names):
            raise ValueError('Corresponding FFmpeg extension/build source is incomplete')
    return attachments


def source_archive(output, version, commit):
    destination = output / ('thinkos-source-v' + version + '.tar.gz')
    # Stream the committed tree; no staging directory, private config or untracked build output.
    with tempfile.TemporaryFile() as stderr, destination.open('xb') as stream:
        process = subprocess.Popen(['git', 'archive', '--format=tar', '--prefix=thinkos-v' + version + '/', commit],
                                   cwd=ROOT, stdout=subprocess.PIPE, stderr=stderr)
        try:
            with gzip.GzipFile(filename='', mode='wb', fileobj=stream, mtime=0) as zipped:
                shutil.copyfileobj(process.stdout, zipped)
        finally:
            process.stdout.close()
        if process.wait():
            raise RuntimeError('Host tool failed: git archive')
    expected = {('thinkos-v' + version + '/' + name.decode('utf-8'))
                for name in run(['git', 'ls-tree', '-r', '--name-only', '-z', commit], cwd=ROOT).split(b'\x00') if name}
    with tarfile.open(destination, 'r:gz') as archive:
        actual = {entry.name for entry in archive.getmembers() if not entry.isdir()}
    if actual != expected:
        raise ValueError('Corresponding source archive omitted tracked paths; review export-ignore or submodule source requirements')
    return {'path': destination.name, 'sha256': digest(destination), 'size': destination.stat().st_size, 'commit': commit}


def build_modules(requests, output, bases, private, public, system_pair, slot, environment, previous, tools, *, trust):
    result = []
    aapt, apksigner = tools
    prior = {entry['id']: entry for entry in previous.get('release', {}).get('modules', [])}
    for request in requests:
        unchanged_public_trust(public, trust)
        identifier = request['id']
        command = ['bash', ROOT / MODULE_BUILDERS[identifier]]
        if identifier == 'thinkos-updater':
            command += ['--keys-header', public / 'updater_keys.h']
        elif identifier == 'thinkos-certs':
            command += ['--mode', request.get('mode', 'replace')]
        run(command)
        kind = 'vector' if identifier == 'thinkos-immersive' else 'magisk'
        if kind == 'magisk':
            properties = dict(line.split('=', 1) for line in (ROOT / 'system' / identifier / 'module.prop').read_text().splitlines()
                              if '=' in line and not line.startswith('#'))
            if properties.get('id') != identifier:
                raise ValueError('Module source ID mismatch')
            version, code = properties['version'], int(properties['versionCode'])
            suffix = '-' + request.get('mode', 'replace') if identifier == 'thinkos-certs' else ''
            built = ROOT / 'system/build' / (identifier + '-' + version + suffix + '.zip')
            with zipfile.ZipFile(built) as archive:
                public_archive(archive)
        else:
            # Build output name is declared by the fixed script, not chosen by a downloaded manifest.
            version_match = re.search(r'^VERSION=([0-9.]+)$', (ROOT / MODULE_BUILDERS[identifier]).read_text(), re.MULTILINE)
            if not version_match:
                raise ValueError('Vector build must declare its release version')
            built = ROOT / 'system/build' / (identifier + '-' + version_match[1] + '.apk')
            signed = output / (identifier + '-' + version_match[1] + '.apk')
            unchanged_public_trust(public, trust)
            run([apksigner, 'sign', '--ks', private / 'apk-signing.p12', '--ks-key-alias', 'thinkos',
                 '--ks-pass', 'env:' + PRIVATE_PASSWORD_ENV, '--key-pass', 'env:' + PRIVATE_PASSWORD_ENV,
                 '--out', signed, built], env=environment)
            built = signed
            signed_info = run([apksigner, 'verify', '--verbose', '--print-certs', built]).decode('utf-8')
            actual_signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$', signed_info, re.MULTILINE)
            if [value.lower() for value in actual_signers] != [digest(public / 'apk-certificate.der')]:
                raise ValueError('Vector module does not have the owner APK certificate')
            badging = run([aapt, 'dump', 'badging', built]).decode('utf-8')
            identity = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
            if not identity or identity[1] != 'moe.kiwi.thinkos.immersive':
                raise ValueError('Unexpected Vector package')
            code, version = int(identity[2]), identity[3]
        raw_hash = digest(built)
        prior_entry = prior.get(identifier)
        if prior_entry and code <= prior_entry['code']:
            if code == prior_entry['code'] and raw_hash == prior_entry['sha256']:
                continue
            raise ValueError('Changed module content requires a larger versionCode: ' + identifier)
        destination = output / built.name
        if built != destination:
            copy_artifact(built, destination)
        manifest = module_manifest(identifier, kind, code, version, raw_hash, destination.stat().st_size)
        manifest_path = output / (identifier + '-' + str(code) + '.manifest')
        exclusive_write(manifest_path, manifest)
        unchanged_public_trust(public, trust)
        signature = sign(manifest_path, private / ('system-' + slot + '.key.pem'), system_pair[slot], environment)
        exclusive_write(Path(str(manifest_path) + '.sig'), signature)
        result.append({'id': identifier, 'kind': kind, 'version': version, 'code': code, 'policy': request['policy'],
                       'sha256': raw_hash, 'size': destination.stat().st_size,
                       'urls': [base + '/' + destination.name for base in bases],
                       'manifest_urls': [base + '/' + manifest_path.name for base in bases]})
    return result


def prepare(args):
    require_owner_host()
    commit = check_clean()
    scan_tracked_secrets()
    public = no_symlink_path(args.public_dir)
    trust = public_trust(public)
    keys, system_pair = trust['keys'], trust['system_pair']
    private = private_directory(args.private_dir)
    private_file(private / 'apk-signing.p12')
    for role in ('channel', 'system', 'registry'):
        for slot in ('active', 'backup'):
            private_file(private / (role + '-' + slot + '.key.pem'))
    inventory = document(public / 'key-inventory.json', 64 * 1024)
    signer = inventory['apk']['sha256']
    if not re.fullmatch('[a-f0-9]{64}', signer) or signer == DEBUG_CERTIFICATE or digest(public / 'apk-certificate.der') != signer:
        raise ValueError('Formal APK certificate inventory is invalid')
    signing_config = private_file(ROOT / 'android/keystore.properties')
    run(['git', 'check-ignore', '--quiet', str(signing_config)])
    previous = {}
    if args.previous_catalog:
        previous = validate_catalog(document(args.previous_catalog))
        if previous['channel'] != args.channel:
            raise ValueError('Previous catalog belongs to another channel')
        signature = regular(Path(str(args.previous_catalog) + '.sig'), 1024).read_bytes()
        verify(args.previous_catalog, signature, list(keys['channel'].values()))
        previous_code, previous_seq = previous['release']['code'], previous['seq']
    else:
        if args.bootstrap_code is None or args.bootstrap_code < 0:
            raise ValueError('First local preparation needs an explicit owner --bootstrap-code baseline')
        previous_code, previous_seq = args.bootstrap_code, 0
    text = (ROOT / 'android/gradle.properties').read_text()
    version = re.search(r'^thinkos\.version=([0-9.]+)$', text, re.MULTILINE)[1]
    code = version_code(version)
    if code <= previous_code or args.seq <= previous_seq or not 0 < args.seq <= 9223372036854775807:
        raise ValueError('Version code and channel sequence must both increase')
    bases = [distribution_base(value) for value in args.url_base]
    mirrors = catalog_urls(args.catalog_url, args.channel)
    if not 1 <= len(set(bases)) == len(bases) <= 8:
        raise ValueError('Supply one to eight distinct distribution bases')
    source = distribution_base(args.source_url)
    if not source.endswith('/tree/v' + version) and not source.endswith('/tree/' + commit):
        raise ValueError('Corresponding source URL must identify this exact version tag or commit')
    notes = document(args.notes, 32 * 1024)
    if not isinstance(notes, dict) or not notes or set(notes) - LANGUAGES or any(not isinstance(value, str) or len(value) > 2048 for value in notes.values()):
        raise ValueError('Release notes must use supported locales and stay within 2048 characters each')
    requests = module_requests(args.modules)
    output = create_directory(outside_repository(args.output))
    report = {'schema': 1, 'stage': 'preparing', 'source_commit': commit, 'version': version,
              'published': False, 'device_smoke': {'requested': bool(args.smoke_serial), 'complete': False},
              'build': {'fairplay': bool(args.fairplay)},
              'public_trust': {name: hashlib.sha256(raw).hexdigest() for name, raw in trust['files'].items()}}
    exclusive_write(output / 'preparation-started.json', json_bytes(report))
    # Every check is host-only; device control is a separate explicit option below.
    gradle = ['./gradlew', '--offline', 'testDebugUnitTest', 'lintDebug', 'lintRelease', 'assembleDebug', 'assembleRelease']
    if not args.fairplay:
        gradle.append('-Pthinkos.fairplay=false')
    checks = [(gradle, ROOT / 'android'),
              ([sys.executable, '-I', '-m', 'unittest', 'discover', '-s', 'tools/tests'], ROOT),
              (['bash', 'android/app/src/testNative/run.sh'], ROOT),
              ([sys.executable, 'tools/package_plugins.py', '--validate-only'], ROOT),
              ([sys.executable, 'tools/gen_android_strings.py', '--check'], ROOT),
              (['go', 'test', './...'], ROOT / 'server'), (['bash', 'system/test/run.sh'], ROOT)]
    for index, (command, directory) in enumerate(checks):
        unchanged_public_trust(public, trust)
        exclusive_write(output / ('check-' + str(index + 1) + '.log'), run(command, cwd=directory))
    tools = sdk_tools()
    release_apk = ROOT / 'android/app/build/outputs/apk/release/app-release.apk'
    debug_apk = ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'
    apk = inspect_apk(release_apk, signer, version, tools=tools, fairplay=args.fairplay)
    inspect_apk(debug_apk, signer, version, debuggable=True, tools=tools, fairplay=args.fairplay)
    unchanged_public_trust(public, trust)
    packaged_public_trust(release_apk, trust)
    packaged_public_trust(debug_apk, trust)
    artifact = output / ('thinkos-' + version + '.apk')
    copy_artifact(release_apk, artifact)
    password = read_password(args.password_fd)
    environment = secret_environment(password)
    password = None
    try:
        modules = build_modules(requests, output, bases, private, public, system_pair, args.slot, environment, previous, tools, trust=trust)
        attachments = gpl_attachments(args.attachments, output)
        attachments.append(source_archive(output, version, commit))
        if args.smoke_serial:
            run([sys.executable, 'tools/tests/release_smoke.py', '--serial', args.smoke_serial,
                 '--apk', artifact, '--restore-debug-apk', debug_apk, '--output', output / 'device-smoke'])
            smoke = document(output / 'device-smoke/results.json')
            if not smoke.get('complete') or not smoke.get('debug_restored'):
                raise ValueError('Explicit device smoke did not complete and restore the debug build')
            report['device_smoke']['complete'] = True
        published = int(time.time() * 1000)
        api = json.loads((ROOT / 'shared/schema/plugin/layouts.json').read_text())['x-thinkos-api']['current']
        catalog = validate_catalog({'schema': 1, 'channel': args.channel, 'seq': args.seq,
                    'published': published, 'expires': published + args.expires_days * 86400000,
                    'release': {**apk, 'api': api, 'device': ['starfire'], 'urls': [base + '/' + artifact.name for base in bases],
                                'modules': modules, 'notes': notes, 'source': source},
                    **({'mirrors': mirrors} if mirrors else {})})
        catalog_path = output / (args.channel + '.json')
        exclusive_write(catalog_path, json_bytes(catalog))
        unchanged_public_trust(public, trust)
        packaged_public_trust(artifact, trust)
        signature = sign(catalog_path, private / ('channel-' + args.slot + '.key.pem'), keys['channel'][args.slot], environment)
        exclusive_write(Path(str(catalog_path) + '.sig'), signature)
        if check_clean() != commit:
            raise ValueError('The source tree changed during preparation; generated files need review and a new commit')
        attachments.append({'path': artifact.name, 'sha256': apk['sha256'], 'size': apk['size']})
        report.update(stage='prepared', catalog={'path': catalog_path.name, 'sha256': digest(catalog_path), 'seq': args.seq},
                      modules=modules, attachments=attachments, source_tag='v' + version,
                      qualification='Local preparation only; device smoke is separately recorded, external publication is pending owner action')
        exclusive_write(output / 'OWNER-PUBLISH.md', (
            '# Owner publication remains pending\n\n'
            'This directory is local preparation. No tag, push, Release, channel upload or module installation was performed.\n\n'
            'Review release-preparation.json, SHA256SUMS and the raw channel/module signatures. Check the explicit device-smoke result; '
            'an omitted smoke is not an accepted release. Complete the remaining physical soak and certificate migration requirements.\n\n'
            'Only after owner approval: create version tag v' + version + ' at ' + commit + '; push that exact tag; create an immutable Release '
            'with the APK, selected modules and their manifest/signature pairs, GPLv3 license, FFmpeg source/checksum, and ThinkOS source archive; '
            'publish ' + args.channel + '.json and its .sig to the reviewed distribution repository. Promote stable only with a higher seq after soak.\n'
        ).encode('utf-8'))
        completed_report = json_bytes(report)
        names = sorted(path.name for path in output.iterdir() if path.is_file())
        sums = ''.join(digest(output / name) + '  ' + name + '\n' for name in names)
        sums += hashlib.sha256(completed_report).hexdigest() + '  release-preparation.json\n'
        exclusive_write(output / 'SHA256SUMS', sums.encode('ascii'))
        # The success marker is last. Any earlier failure leaves only the
        # preparing marker and partial review artifacts, never a false success.
        exclusive_write(output / 'release-preparation.json', completed_report)
        return report
    finally:
        environment.pop(PRIVATE_PASSWORD_ENV, None)


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('action', choices=('prepare',))
    result.add_argument('--private-dir', type=Path, required=True)
    result.add_argument('--public-dir', type=Path, required=True)
    result.add_argument('--output', type=Path, required=True, help='New absolute directory outside any source repository')
    baseline = result.add_mutually_exclusive_group(required=True)
    baseline.add_argument('--previous-catalog', type=Path, help='Previous authenticated same-channel document plus adjacent .sig')
    baseline.add_argument('--bootstrap-code', type=int, help='Owner-supplied previous versionCode for the first signed channel document')
    result.add_argument('--channel', choices=('beta', 'stable'), default='beta')
    result.add_argument('--seq', type=int, required=True)
    result.add_argument('--slot', choices=('active', 'backup'), default='active')
    result.add_argument('--url-base', action='append', required=True)
    result.add_argument('--catalog-url', action='append', default=[],
                        help='URL of this channel\'s catalog on a source (repeat; at most eight). Signed into the catalog so devices learn the source')
    result.add_argument('--source-url', required=True)
    result.add_argument('--notes', type=Path, required=True)
    result.add_argument('--modules', type=Path, help='JSON array of allowed id/policy selections; no commands or arbitrary paths')
    result.add_argument('--attachments', type=Path, default=ROOT / 'artifacts/lic/LIC-04/release-attachments.json')
    result.add_argument('--expires-days', type=int, choices=range(1, 366), default=90, metavar='1..365')
    result.add_argument('--fairplay', action='store_true',
                        help='Include the GPLv3 FairPlay library; off by default until the owner settles the legal review (OWN-12)')
    result.add_argument('--password-fd', type=int)
    result.add_argument('--smoke-serial', help='Explicitly authorize the existing same-signer debug/release device smoke')
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    report = prepare(args)
    print(json.dumps({'stage': report['stage'], 'version': report['version'], 'published': False,
                      'device_smoke': report['device_smoke']}, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, RuntimeError, KeyError, zipfile.BadZipFile, tarfile.TarError) as error:
        # Do not echo arbitrary child output or a JSON value that could contain a secret.
        print('Release preparation stopped (' + type(error).__name__ + '). Review local inputs; no publication was performed.', file=sys.stderr)
        sys.exit(1)
