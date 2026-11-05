#!/usr/bin/env python3
"""MIT. Disposable REG-2b host origin, independent of the device process.

Only prepare/publish CLI or Python calls sign. HTTP accepts fixed GET paths;
it has no signing/control/upload interface and never reads device state.
"""
import argparse
import base64
import fcntl
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import socketserver
import stat
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from release_tools import no_symlink_path, outside_repository, validate_keyring

MAX_BODY = 1024 * 1024
MAX_METADATA = 256 * 1024
MAX_RESPONSES = 256
MAX_TOTAL = 4 * 1024 * 1024
RUN = re.compile(r'[a-f0-9]{32}')
SHA = re.compile(r'[a-f0-9]{64}')
SAMPLES = ('normal', 'home', 'settings', 'system', 'tampered-blob', 'limits')
ROLES = ('channel', 'registry')
SLOTS = ('active', 'backup')
WHY = {'network': '读取本地测试台内容', 'media': '建立静音暂停会话', 'location': '读取测试城市设置'}


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def decoded(raw, limit=MAX_METADATA):
    if len(raw) > limit:
        raise ValueError('Fixture metadata exceeds its bound')
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate fixture member')
            result[key] = value
        return result
    def invalid(_):
        raise ValueError('Expected integer JSON numbers')
    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs, parse_float=invalid, parse_constant=invalid)


def regular(path, limit=MAX_METADATA, private=True):
    path = no_symlink_path(Path(path))
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | getattr(os, 'O_CLOEXEC', 0))
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid() or private and before.st_mode & 0o077 or not 0 <= before.st_size <= limit:
            raise ValueError('Expected a bounded owned regular fixture file')
        raw = bytearray()
        while len(raw) <= limit:
            part = os.read(descriptor, min(65536, limit + 1 - len(raw)))
            if not part:
                break
            raw.extend(part)
        after = os.fstat(descriptor)
        named = path.lstat()
        def identity(info):
            return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
        if len(raw) > limit or len(raw) != before.st_size or identity(before) != identity(after) or identity(after) != identity(named):
            raise ValueError('Fixture file changed during bounded read')
        return bytes(raw)
    finally:
        os.close(descriptor)


def write_new(path, raw):
    path = no_symlink_path(Path(path))
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb') as stream:
        stream.write(raw); stream.flush(); os.fsync(stream.fileno())


def atomic(path, raw):
    temporary = path.with_name(path.name + '.new')
    write_new(temporary, raw)
    os.replace(temporary, path)
    descriptor = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def openssl(*arguments, raw=None, success=True):
    result = subprocess.run(['openssl', *map(str, arguments)], input=raw, capture_output=True,
                            timeout=10, check=False)
    if len(result.stdout) > 65536 or len(result.stderr) > 65536 or success and result.returncode:
        # Never include OpenSSL output/private PEM or argument values in reports.
        raise ValueError('Fixture OpenSSL operation failed')
    return result


def endpoint(value, run_id):
    if not isinstance(value, str) or any(ord(c) <= 32 or ord(c) == 127 for c in value):
        raise ValueError('Invalid fixture origin')
    parsed = urlsplit(value)
    address = ipaddress.IPv4Address(parsed.hostname or '')
    private = any(address in ipaddress.IPv4Network(network) for network in
                  ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '127.0.0.0/8'))
    if not private or parsed.scheme != 'http' or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment or \
            parsed.port is None or not 1 <= parsed.port <= 65535 or parsed.path != '/reg2b/' + run_id + '/':
        raise ValueError('Use an explicit private/loopback IPv4 port and run path')
    return str(address), parsed.port, parsed.path


def schema(value, document):
    spec = importlib.util.spec_from_file_location('reg2b_fixture_schema', ROOT / 'shared/gen/schema_tools.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    module.SCHEMAS = {p.name: json.loads(p.read_text()) for p in (ROOT / 'shared/schema/registry').glob('*.json')}
    module.validate(value, document=document)


def package(ident, version, patch, media_url, license_bytes, *, name='仓库测试', role=None,
            hidden_permission=None, permissions=None):
    permissions = permissions if permissions is not None else (['network', 'media', 'location'] if patch >= 2 else ['network'])
    page = {'id': 'main', 'title': name, 'view': 'views/main.json'}
    if role:
        page['roles'] = [role]
    manifest = {'id': ident, 'name': name, 'version': version, 'api': 10, 'main': 'main.lua',
                'permissions': permissions + ([hidden_permission] if hidden_permission else []),
                'apps': [] if role else [{'id': 'main', 'page': 'main', 'title': name}],
                'settings': {'remember': {'type': 'bool', 'label': '保留测试设置', 'default': False, 'level': 'normal'}},
                'pages': [page]}
    view = {'layout': 'focus', 'slots': {'hero': {'type': 'text', 'value': '{{label}}', 'style': 'title'},
            'under': {'type': 'text', 'value': '触发错误' if patch == 4 else '停止媒体', 'tap': 'fault' if patch == 4 else 'stop'}}}
    failed = 'error("Deliberate registry startup failure")' if patch == 1 else ''
    media = 'tos.media.load({{url=' + json.dumps(media_url) + ',kind="video",title="Silent registry fixture"}},{autoplay=false})' if patch == 2 else ''
    stop = 'tos.media.stop()' if patch >= 2 else 'tos.log.info("REGISTRY_TAP",VERSION)'
    fault = 'if name=="fault" then error("Deliberate registry runtime failure") end' if patch == 4 else ''
    lua = '''local VERSION = %s
local function authority()
 local denied=0
 for _,fn in ipairs{
  function() return tos.plugins.catalog{} end,
  tos.plugins.installed,
  function() return tos.plugins.open(%s) end,
  function() return tos.plugins.install(%s) end,
  function() return tos.plugins.update(%s) end,
  function() return tos.plugins.uninstall(%s) end,
  function() return tos.plugins.setAuto(%s,true) end
 } do if not pcall(fn) then denied=denied+1 end end
 tos.log.info("REGISTRY_DENIED",VERSION,denied)
 return denied
end
tos.on("plugins",function() tos.log.error("REGISTRY_EVENT_LEAK",VERSION) end)
tos.on("start",function()
 %s
 local denied=authority()
 tos.store.set("probe.version",VERSION)
 tos.state.set{label=%s.." "..VERSION,version=VERSION,denied=denied}
 tos.log.info("REGISTRY_SETTING",VERSION,tostring(tos.config.remember))
 %s
 tos.log.info("REGISTRY_STARTED",VERSION)
end)
tos.on("tap",function(name)
 if name=="stop" then %s end
 %s
end)
''' % (json.dumps(version), *(json.dumps(ident) for _ in range(5)), failed,
       json.dumps(name, ensure_ascii=False), media, stop, fault)
    files = {'manifest.json': encoded(manifest), 'views/main.json': encoded(view), 'main.lua': lua.encode(), 'LICENSE': license_bytes}
    entries = [{'path': path, 'size': len(raw), 'sha256': digest(raw)} for path, raw in sorted(files.items())]
    sha = digest(b''.join((item['path'] + '\0' + item['sha256'] + '\n').encode() for item in entries))
    index = {'id': ident, 'version': version, 'digest': sha, 'files': entries}
    schema(index, 'files.json')
    return {'id': ident, 'name': name, 'version': version, 'digest': sha, 'permissions': permissions,
            'index': encoded(index), 'blobs': {digest(raw): raw for raw in files.values()}}


def prepare(directory, run_id, base_url, media_file=None):
    if not RUN.fullmatch(run_id):
        raise ValueError('Expected a fresh 32hex run id')
    endpoint(base_url, run_id)
    directory = outside_repository(Path(directory))
    roots = (Path('/private/tmp'), Path(tempfile.gettempdir()).resolve())
    if directory.name != 'reg2b-' + run_id or not any(root in directory.parents for root in roots):
        raise ValueError('Use a fresh reg2b-runid directory under host temporary storage')
    if directory.exists():
        raise ValueError('Never overwrite existing fixture material')
    media = no_symlink_path(Path(media_file or ROOT / 'tools/tests/fixtures/ota/runtime-video.mp4'))
    media_bytes = regular(media, MAX_BODY, private=False)
    if not media_bytes:
        raise ValueError('Expected an existing bounded local silent media fixture')
    license_bytes = regular(ROOT / 'LICENSE', 16384, private=False)
    directory.mkdir(mode=0o700)
    for child in ('private', 'public', 'objects', 'publications'):
        (directory / child).mkdir(mode=0o700)
    keys = {'schema': 1}
    for role in ROLES:
        keys[role] = {}
        for slot in SLOTS:
            stem = role + '-' + slot
            key = openssl('genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:prime256v1').stdout
            write_new(directory / 'private' / (stem + '.pem'), key)
            private = directory / 'private' / (stem + '.pem')
            der = openssl('pkey', '-in', private, '-pubout', '-outform', 'DER').stdout
            write_new(directory / 'public' / (stem + '.pem'), openssl('pkey', '-in', private, '-pubout').stdout)
            keys[role][slot] = {'id': 'reg2b-' + run_id + '-' + stem, 'algorithm': 'SHA256withECDSA',
                                'spki': base64.b64encode(der).decode('ascii')}
    validate_keyring(keys)
    write_new(directory / 'public/keys.json', encoded(keys))
    ident = 'reg-probe-' + run_id[:12]
    versions = [package(ident, '1.0.' + str(n), n, base_url + 'silent.mp4', license_bytes) for n in range(5)]
    samples = {
        'home': package(ident + '-home', '1.0.0', 0, base_url + 'silent.mp4', license_bytes, role='home', name='首页角色拦截'),
        'settings': package(ident + '-settings', '1.0.0', 0, base_url + 'silent.mp4', license_bytes, role='settings', name='设置角色拦截'),
        'system': package(ident + '-system', '1.0.0', 0, base_url + 'silent.mp4', license_bytes, hidden_permission='system.settings', name='系统权限拦截'),
        'tampered-blob': package(ident + '-tampered', '1.0.0', 0, base_url + 'silent.mp4', license_bytes, name='文件摘要拦截')}
    limits = [package(ident + '-limit-' + str(n).zfill(2), '1.0.0', 0, base_url + 'silent.mp4', license_bytes,
                      permissions=[], name='数量测试 ' + str(n + 1).zfill(2)) for n in range(17)]
    responses = {}
    def put(route, raw):
        sha = digest(raw); path = directory / 'objects' / sha
        if not path.exists():
            write_new(path, raw)
        responses[route] = {'sha256': sha, 'size': len(raw)}
    packages = []
    for value in versions + list(samples.values()) + limits:
        put('registry/files/' + value['digest'] + '.json', value['index'])
        for sha, raw in value['blobs'].items():
            put('blobs/' + sha[:2] + '/' + sha, raw)
        packages.append({k: value[k] for k in ('id', 'name', 'version', 'digest', 'permissions')})
    negative = decoded(samples['tampered-blob']['index'])
    bad_sha = next(item['sha256'] for item in negative['files'] if item['path'] == 'main.lua')
    corrupt = bytearray(samples['tampered-blob']['blobs'][bad_sha]); corrupt[0] ^= 1
    put('blobs/' + bad_sha[:2] + '/' + bad_sha, bytes(corrupt))
    put('silent.mp4', media_bytes)
    write_new(directory / 'material.json', encoded({'schema': 1, 'run_id': run_id, 'base_url': base_url,
              'keys_sha256': digest(encoded(keys)), 'packages': packages, 'responses': responses, 'tampered_blob_sha256': bad_sha}))
    write_new(directory / 'lock', b'')
    material = Material.open(directory)
    material.publish()
    return material


class Material:
    @classmethod
    def open(cls, directory):
        self = cls(); self.directory = outside_repository(Path(directory))
        info = self.directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise ValueError('Fixture root is not owner-private')
        self.document = decoded(regular(self.directory / 'material.json'))
        if set(self.document) != {'schema', 'run_id', 'base_url', 'keys_sha256', 'packages', 'responses', 'tampered_blob_sha256'} or self.document['schema'] != 1:
            raise ValueError('Invalid fixture material document')
        self.run_id = self.document['run_id']
        if not isinstance(self.run_id, str) or not RUN.fullmatch(self.run_id) or self.directory.name != 'reg2b-' + self.run_id:
            raise ValueError('Fixture run identity differs')
        self.bind, self.port, self.prefix = endpoint(self.document['base_url'], self.run_id)
        self.base_url = self.document['base_url']
        key_bytes = regular(self.directory / 'public/keys.json', 65536)
        self.keys = decoded(key_bytes)
        validate_keyring(self.keys)
        if digest(key_bytes) != self.document['keys_sha256']:
            raise ValueError('Fixture public root changed')
        for role in ROLES:
            for slot in SLOTS:
                if self.keys[role][slot]['id'] != 'reg2b-' + self.run_id + '-' + role + '-' + slot:
                    raise ValueError('Fixture key id differs')
                regular(self.directory / 'private' / (role + '-' + slot + '.pem'), 8192)
                expected = base64.b64decode(self.keys[role][slot]['spki'], validate=True)
                actual = openssl('pkey', '-in', self.directory / 'private' / (role + '-' + slot + '.pem'), '-pubout', '-outform', 'DER').stdout
                if actual != expected:
                    raise ValueError('Fixture private/public roots differ')
        self.bodies = {}; total = 0
        entries = self.document['responses']
        if not isinstance(entries, dict) or not 1 <= len(entries) <= MAX_RESPONSES:
            raise ValueError('Too many fixture responses')
        for route, item in entries.items():
            if not re.fullmatch(r'(?:registry/files/[a-f0-9]{64}\.json|blobs/[a-f0-9]{2}/[a-f0-9]{64}|silent\.mp4)', route) or \
                    set(item) != {'sha256', 'size'} or not SHA.fullmatch(item['sha256']):
                raise ValueError('Unrecognized fixture response')
            body = regular(self.directory / 'objects' / item['sha256'], MAX_BODY)
            if len(body) != item['size'] or digest(body) != item['sha256']:
                raise ValueError('Fixture object changed')
            total += len(body)
            if total > MAX_TOTAL:
                raise ValueError('Fixture bodies exceed total bound')
            self.bodies[self.prefix + route] = body
        self.ident = 'reg-probe-' + self.run_id[:12]
        return self

    def append_recovery(self):
        # One fixed owner action; never rebuild roots, old packages or sequence.
        with open(self.directory/'lock','rb') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX)
            fresh = Material.open(self.directory)
            normal = [p for p in fresh.document['packages'] if p['id'] == fresh.ident]
            if [p['version'] for p in normal] != ['1.0.'+str(n) for n in range(5)]:
                raise ValueError('Recovery append requires exactly the original five versions')
            v3 = normal[2]
            if v3['permissions'] != ['network','media','location']:
                raise ValueError('Unexpected v3 permissions')
            index = decoded(fresh.bodies[fresh.prefix+'registry/files/'+v3['digest']+'.json'])
            license_rows = [row for row in index['files'] if row['path'] == 'LICENSE']
            if len(license_rows) != 1:
                raise ValueError('Expected one original v3 license object')
            license_row = license_rows[0]
            license_bytes = fresh.bodies[fresh.prefix+'blobs/'+license_row['sha256'][:2]+'/'+license_row['sha256']]
            value = package(fresh.ident,'1.0.5',5,fresh.base_url+'silent.mp4',license_bytes,permissions=list(v3['permissions']))
            document = decoded(encoded(fresh.document))
            additions = {'registry/files/'+value['digest']+'.json':value['index']}
            additions.update({'blobs/'+sha[:2]+'/'+sha:raw for sha,raw in value['blobs'].items()})
            writes = []
            for route,raw in additions.items():
                entry = {'sha256':digest(raw),'size':len(raw)}
                old = document['responses'].get(route)
                if len(raw)>MAX_BODY or old is not None and old != entry:
                    raise ValueError('Recovery append would replace an existing response')
                document['responses'][route] = entry
                path = self.directory/'objects'/entry['sha256']
                if path.exists() or path.is_symlink():
                    if regular(path,MAX_BODY) != raw:
                        raise ValueError('Recovery append would replace an existing object')
                else:
                    writes.append((path,raw))
            document['packages'].append({k:value[k] for k in ('id','name','version','digest','permissions')})
            if len(document['responses'])>MAX_RESPONSES or sum(item['size'] for item in document['responses'].values())>MAX_TOTAL:
                raise ValueError('Recovery material exceeds existing bounds')
            raw_document = encoded(document)
            if len(raw_document)>MAX_METADATA:
                raise ValueError('Recovery metadata exceeds existing bound')
            for path,raw in writes:
                write_new(path,raw)
            descriptor = os.open(self.directory/'objects',os.O_RDONLY)
            try: os.fsync(descriptor)
            finally: os.close(descriptor)
            atomic(self.directory/'material.json',raw_document)
            self.__dict__.update(Material.open(self.directory).__dict__)
            return self.meta

    def verify(self, raw, signature, role='registry', slot='active'):
        if role not in ROLES or slot not in SLOTS or len(raw) > MAX_METADATA or not 8 <= len(signature) <= 80:
            return False
        with tempfile.TemporaryDirectory(dir=self.directory / 'private', prefix='verify-') as temporary:
            path = Path(temporary) / 'signature'; write_new(path, signature)
            public = Path(temporary) / 'public.pem'
            der = base64.b64decode(self.keys[role][slot]['spki'], validate=True)
            write_new(public, openssl('pkey', '-pubin', '-inform', 'DER', '-pubout', raw=der).stdout)
            result = openssl('dgst', '-sha256', '-verify', public,
                             '-signature', path, raw=raw, success=False)
            return result.returncode == 0

    def ready(self):
        packages = self.document['packages']
        normal = [p for p in packages if p['id'] == self.ident]
        indexes = {p['digest']: self.bodies[self.prefix + 'registry/files/' + p['digest'] + '.json'] for p in packages}
        return {'run_id': self.run_id, 'id': self.ident, 'base_url': self.base_url,
                'source': self.base_url + 'registry/index.json', 'registry_url': self.base_url + 'registry/index.json',
                'public_keys': str(self.directory / 'public/keys.json'), 'public_keys_sha256': self.document['keys_sha256'],
                'setting_key': 'remember', 'setting_label': '保留测试设置', 'fault_label': '触发错误',
                'stop_label': '停止媒体', 'media_url': self.base_url + 'silent.mp4', 'permission_reasons': dict(WHY),
                'markers': {'started': 'REGISTRY_STARTED', 'denied': 'REGISTRY_DENIED', 'setting': 'REGISTRY_SETTING', 'event_leak': 'REGISTRY_EVENT_LEAK'},
                'digests': {p['version']: p['digest'] for p in normal},
                'versions': {p['version']: {'permissions': p['permissions'], 'index': decoded(indexes[p['digest']]),
                    'index_sha256': digest(indexes[p['digest']]), 'index_base64': base64.b64encode(indexes[p['digest']]).decode('ascii')} for p in normal},
                'pack_indexes': {sha: {'index': decoded(raw), 'index_sha256': digest(raw),
                    'index_base64': base64.b64encode(raw).decode('ascii')} for sha, raw in indexes.items()},
                'samples': {sample: next(p for p in packages if p['id'] == self.ident + '-' + ('tampered' if sample == 'tampered-blob' else sample)) for sample in SAMPLES[1:-1]},
                'limits': [p for p in packages if p['id'].startswith(self.ident + '-limit-')],
                'tampered_blob_sha256': self.document['tampered_blob_sha256'], 'trial_seconds': 600}

    @property
    def meta(self):
        return self.ready()

    def publish(self, level=1, sample='normal', limit_count=17, tier='reviewed', revoked=False,
                revoke_previous=False, bad_signature=False, old_sequence=False, recovery=False):
        if type(level) is not int or not 1 <= level <= 5 or sample not in SAMPLES or tier not in ('reviewed', 'community') or \
                type(limit_count) is not int or not 1 <= limit_count <= 17 or sample != 'normal' and tier != 'reviewed' or \
                revoke_previous and (sample != 'normal' or level < 2) or \
                any(type(value) is not bool for value in (revoked, revoke_previous, bad_signature, old_sequence)):
            raise ValueError('Unknown fixed fixture publication')
        if type(recovery) is not bool or recovery and (level != 1 or sample != 'normal' or tier != 'reviewed' or revoked or revoke_previous or bad_signature or old_sequence):
            raise ValueError('Recovery publication has fixed honest semantics')
        with open(self.directory / 'lock', 'rb') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            current_path = self.directory / 'current.json'
            previous = decoded(regular(current_path)) if current_path.exists() else {'allocated_seq': 0}
            if type(previous['allocated_seq']) is not int or not 0 <= previous['allocated_seq'] < 99999999:
                raise ValueError('Invalid fixture publication counter')
            sequence = previous['allocated_seq'] + 1
            packages = self.document['packages']
            if recovery:
                versions = {p['version']:p for p in packages if p['id'] == self.ident}
                if '1.0.5' not in versions:
                    raise ValueError('Explicit recovery append is required first')
                releases = [versions[v] for v in ('1.0.5','1.0.3','1.0.2')]
                selected = [(releases[0],releases)]
            elif sample == 'normal':
                releases = [p for p in packages if p['id'] == self.ident][:level][-3:][::-1]
                selected = [(releases[0], releases)]
            elif sample == 'limits':
                selected = [(p, [p]) for p in packages if p['id'].startswith(self.ident + '-limit-')][:limit_count]
            else:
                suffix = 'tampered' if sample == 'tampered-blob' else sample
                value = next(p for p in packages if p['id'] == self.ident + '-' + suffix)
                selected = [(value, [value])]
            def release(value):
                index = decoded(self.bodies[self.prefix + 'registry/files/' + value['digest'] + '.json'])
                why = {permission: {'zh-Hans': WHY[permission]} for permission in value['permissions']}
                return {'version': value['version'], 'commit': digest((value['id'] + '@' + value['version']).encode())[:40],
                        'digest': value['digest'], 'api': 10, 'size': sum(item['size'] for item in index['files']),
                        'permissions': value['permissions'], 'why': why, 'apps': ['main']}
            plugins = [{'id': value['id'], 'tier': tier, 'name': {'zh-Hans': value['name']}, 'summary': {'zh-Hans': '本地功能验收'},
                        'author': 'ThinkOS test', 'repo': 'thinkos-tests/registry-probe', 'license': 'MIT', 'tags': ['fixture'],
                        'releases': [release(p) for p in releases]} for value, releases in selected]
            revocations = []
            if revoked:
                revocations = [{'id': p['id'], 'digest': p['releases'][0]['digest'], 'reason': '测试台撤销此版本'} for p in plugins]
            if revoke_previous:
                prior = next(p for p in packages if p['id'] == self.ident and p['version'] == '1.0.' + str(level - 2))
                revocations.append({'id': prior['id'], 'digest': prior['digest'], 'reason': '测试台只撤销上一版本'})
            now = int(time.time() * 1000)
            document = {'schema': 1, 'seq': 1 if old_sequence else sequence, 'generated': now, 'expires': now + 86400000,
                        'blob_bases': [self.base_url], 'revoked': revocations, 'plugins': plugins}
            schema(document, 'catalog.json')
            raw = encoded(document)
            if len(raw) > MAX_METADATA:
                raise ValueError('Catalog exceeds bound')
            role = 'channel' if bad_signature else 'registry'
            signature = openssl('dgst', '-sha256', '-sign', self.directory / 'private' / (role + '-active.pem'), raw=raw).stdout
            if not self.verify(raw, signature, role):
                raise ValueError('Fixture signature did not verify')
            stem = str(sequence).zfill(8)
            write_new(self.directory / 'publications' / (stem + '.json'), raw)
            write_new(self.directory / 'publications' / (stem + '.sig'), signature)
            descriptor = os.open(self.directory / 'publications', os.O_RDONLY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
            pointer = {'schema': 1, 'allocated_seq': sequence, 'seq': document['seq'], 'stem': stem,
                       'catalog_sha256': digest(raw), 'signature_sha256': digest(signature), 'signing_role': role}
            atomic(current_path, encoded(pointer))
            return pointer

    def response(self, path):
        if path in (self.prefix + 'registry/index.json', self.prefix + 'registry/index.json.sig'):
            current = decoded(regular(self.directory / 'current.json', 4096))
            if not re.fullmatch(r'[0-9]{8}', current['stem']):
                raise ValueError('Invalid publication pointer')
            signature = path.endswith('.sig')
            raw = regular(self.directory / 'publications' / (current['stem'] + ('.sig' if signature else '.json')), MAX_METADATA)
            if digest(raw) != current['signature_sha256' if signature else 'catalog_sha256']:
                raise ValueError('Publication changed')
            return raw
        return self.bodies.get(path)


class _Handler(socketserver.StreamRequestHandler):
    def line(self):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Fixture request deadline')
        self.connection.settimeout(min(2, remaining))
        return self.rfile.readline(1025)

    def handle(self):
        self.deadline = time.monotonic() + 3
        try:
            line = self.line()
            if len(line) > 1024:
                return self.reply(414)
            match = re.fullmatch(rb'([A-Z]+) (/[A-Za-z0-9/_.-]*) HTTP/1\.[01]\r\n', line)
            if not match:
                return self.reply(400)
            if match[1] != b'GET':
                return self.reply(405)
            headers = {}; total = 0
            for _ in range(33):
                header = self.line(); total += len(header)
                if len(header) > 1024 or total > 8192:
                    return self.reply(431)
                if header == b'\r\n':
                    break
                key, separator, value = header.partition(b':')
                if not separator or not re.fullmatch(rb'[A-Za-z0-9-]+', key) or not header.endswith(b'\r\n'):
                    return self.reply(400)
                key = key.lower()
                if key in headers or any(c < 32 and c != 9 or c == 127 for c in value[:-2]):
                    return self.reply(400)
                headers[key] = value[:-2].strip()
            else:
                return self.reply(431)
            if b'transfer-encoding' in headers or headers.get(b'content-length', b'0') != b'0':
                return self.reply(400)
            path = match[2].decode('ascii')
            body = self.server.material.response(path)
            if body is None:
                return self.reply(404)
            self.server.record(path)
            return self.reply(200, body)
        except (OSError, ValueError, KeyError, UnicodeError):
            return

    def reply(self, status, body=b''):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Fixture response deadline')
        self.connection.settimeout(min(2, remaining))
        reasons = {200: 'OK', 400: 'Bad Request', 404: 'Not Found', 405: 'Method Not Allowed',
                   414: 'URI Too Long', 431: 'Request Header Fields Too Large'}
        self.wfile.write(('HTTP/1.0 %d %s\r\nContent-Length: %d\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n' %
                         (status, reasons[status], len(body))).encode('ascii') + body)


class _Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    daemon_threads = True
    allow_reuse_address = False
    def __init__(self, material):
        self.material = material; self.slots = threading.BoundedSemaphore(4)
        self.hits = {}; self.hit_lock = threading.Lock()
        super().__init__((material.bind, material.port), _Handler)
    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.slots.release(); raise
    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()
    def handle_error(self, request, client_address):
        pass  # Never dump request headers, peer data or private paths.
    def record(self, path):
        with self.hit_lock:
            self.hits[path] = min(self.hits.get(path, 0) + 1, 2147483647)


class Origin:
    def __init__(self, material):
        self.material = material; self.server = None; self.thread = None
    def start(self):
        if self.server is not None:
            raise ValueError('Origin already started')
        self.server = _Server(self.material)
        self.thread = threading.Thread(target=self.server.serve_forever, name='REG2b-origin', daemon=True)
        self.thread.start()
        return self
    def publish(self, **arguments):
        return self.material.publish(**arguments)
    def close(self):
        if self.server:
            self.server.shutdown(); self.server.server_close(); self.thread.join(3)
            self.server = None
    def __enter__(self):
        return self.start()
    def __exit__(self, *_):
        self.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    create = sub.add_parser('prepare')
    create.add_argument('--directory', type=Path, required=True); create.add_argument('--run-id', required=True)
    create.add_argument('--base-url', required=True); create.add_argument('--media-file', type=Path)
    serve = sub.add_parser('serve'); serve.add_argument('--directory', type=Path, required=True)
    for action in ('append-recovery','publish-recovery'):
        fixed = sub.add_parser(action); fixed.add_argument('--directory',type=Path,required=True)
    publish = sub.add_parser('publish'); publish.add_argument('--directory', type=Path, required=True)
    publish.add_argument('--level', type=int, choices=range(1, 6), default=1)
    publish.add_argument('--sample', choices=SAMPLES, default='normal')
    publish.add_argument('--limit-count', type=int, choices=range(1, 18), default=17)
    publish.add_argument('--tier', choices=('reviewed', 'community'), default='reviewed')
    for flag in ('revoked', 'revoke-previous', 'bad-signature', 'old-sequence'):
        publish.add_argument('--' + flag, action='store_true')
    args = parser.parse_args(argv)
    if args.action == 'prepare':
        value = prepare(args.directory, args.run_id, args.base_url, args.media_file).ready()
    else:
        material = Material.open(args.directory)
        if args.action == 'append-recovery':
            value = material.append_recovery()
        elif args.action == 'publish-recovery':
            value = material.publish(recovery=True)
        elif args.action == 'publish':
            value = material.publish(**{k: v for k, v in vars(args).items() if k not in ('action', 'directory')})
        else:
            with Origin(material):
                print(encoded(material.ready()).decode(), end='', flush=True)
                try:
                    threading.Event().wait()
                except KeyboardInterrupt:
                    pass
            return 0
    print(encoded(value).decode(), end='')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print('REG2b fixture stopped: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
