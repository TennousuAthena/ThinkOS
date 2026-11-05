#!/usr/bin/env python3
"""MIT. Owner-only registry signing and exact-CAS publication to GitHub dist.

The CLI has no arbitrary remote URL or transport option. Tests inject a local
bare transport into publish(); author repositories are never checked out.
"""
import argparse
import base64
import copy
import getpass
import hashlib
import os
from pathlib import Path
import re
import resource
import signal
import stat
import subprocess
import sys
import tempfile
import time

import registry as catalog
import registry_sources as sources
from release_tools import (PRIVATE_PASSWORD_ENV, exclusive_write, json_bytes,
                           no_symlink_path, regular, require_owner_host,
                           validate_keyring)

REF = 'refs/heads/dist'
TOKEN_ENV = 'THINKOS_REGISTRY_GITHUB_TOKEN'
SHA = re.compile(r'[a-f0-9]{40}')
MAX_BYTES = 128 * 1024 * 1024
MAX_ENTRIES = 100000
REPORTS = {'build-report.json', 'review-requests.json', 'ci-context.json',
           'owner-signature-report.json'}
ROOT_FILES = {'registry/index.json', 'registry/tag-ledger.json'}
SIGNED_FILES = {path + '.sig' for path in ROOT_FILES}
PRIVATE_MARKER = re.compile(rb'^-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----\r?$', re.MULTILINE)


class PublicationUnknown(ValueError):
    def __init__(self, report):
        super().__init__('Publication outcome is unknown; do not retry automatically')
        self.report = report


class PublicationRejected(ValueError):
    def __init__(self, report):
        super().__init__('Publication did not advance the remote dist reference')
        self.report = report


def _git_environment(protocol, token=None, timestamp=None):
    # Inherited GIT_CONFIG_*, tracing, replacements, identities, askpass and
    # author-supplied configuration must never influence this isolated bare repo.
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith('GIT_') and key not in (TOKEN_ENV, PRIVATE_PASSWORD_ENV)}
    environment.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_SYSTEM='/dev/null',
                       GIT_CONFIG_GLOBAL='/dev/null', GIT_ALLOW_PROTOCOL=protocol,
                       GIT_TERMINAL_PROMPT='0', GIT_NO_LAZY_FETCH='1',
                       GIT_NO_REPLACE_OBJECTS='1', GIT_ATTR_NOSYSTEM='1',
                       GIT_ASKPASS='/usr/bin/false', SSH_ASKPASS='/usr/bin/false')
    if token is not None:
        encoded = base64.b64encode(('x-access-token:' + token).encode('ascii')).decode('ascii')
        # A secret is supplied only through the child's environment, never argv.
        environment.update(GIT_CONFIG_COUNT='1',
                           GIT_CONFIG_KEY_0='http.https://github.com/.extraheader',
                           GIT_CONFIG_VALUE_0='Authorization: Basic ' + encoded)
    if timestamp is not None:
        environment.update(GIT_AUTHOR_NAME='ThinkOS Registry Owner',
                           GIT_AUTHOR_EMAIL='registry@thinkos.invalid',
                           GIT_COMMITTER_NAME='ThinkOS Registry Owner',
                           GIT_COMMITTER_EMAIL='registry@thinkos.invalid',
                           GIT_AUTHOR_DATE='@' + str(timestamp) + ' +0000',
                           GIT_COMMITTER_DATE='@' + str(timestamp) + ' +0000')
    return environment


def _limits():
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_BYTES, MAX_BYTES))
    resource.setrlimit(resource.RLIMIT_CPU, (60, 60))


class _BareTransport:
    """Internal injection seam. Production constructs only GitHubTransport."""
    def __init__(self, workspace, address, *, protocol, token=None):
        self.directory = no_symlink_path(Path(workspace)) / 'publication.git'
        self.hooks = Path(workspace) / 'empty-hooks'
        self.hooks.mkdir()
        self.address, self.protocol, self.token = address, protocol, token
        self.config = ['-c', 'init.templateDir=', '-c', 'core.hooksPath=' + str(self.hooks),
                       '-c', 'core.fsmonitor=false', '-c', 'core.attributesFile=/dev/null',
                       '-c', 'commit.gpgsign=false', '-c', 'tag.gpgsign=false',
                       '-c', 'credential.helper=', '-c', 'credential.interactive=false',
                       '-c', 'http.followRedirects=false',
                       '-c', 'gc.auto=0', '-c', 'maintenance.auto=false',
                       '-c', 'fetch.fsckObjects=true', '-c', 'transfer.fsckObjects=true',
                       '-c', 'protocol.allow=never', '-c', 'protocol.' + protocol + '.allow=always']
        self._run(['init', '--bare', '--template=' + str(self.hooks), self.directory], local=False)

    def _run(self, arguments, *, raw=None, limit=1024 * 1024, timeout=90,
             local=True, remote=False, timestamp=None):
        command = ['git', '--no-pager', *self.config]
        if local:
            command.append('--git-dir=' + str(self.directory))
        command.extend(str(value) for value in arguments)
        environment = _git_environment(self.protocol, self.token if remote else None, timestamp)
        # Files bound captured output without accumulating attacker-sized pipes.
        # stderr is never echoed, including HTTP errors or credential failures.
        with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
            process = subprocess.Popen(command, stdin=subprocess.PIPE if raw is not None else subprocess.DEVNULL,
                                       stdout=output, stderr=errors, env=environment,
                                       cwd=self.directory.parent, preexec_fn=_limits,
                                       start_new_session=True)
            try:
                process.communicate(input=raw, timeout=timeout)
            except BaseException:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
                raise
            finally:
                environment.pop('GIT_CONFIG_VALUE_0', None)
            if process.returncode:
                raise ValueError('Registry Git operation failed')
            output.seek(0)
            result = output.read(limit + 1)
            if len(result) > limit:
                raise ValueError('Registry Git output exceeded its bound')
            return result

    def read_ref(self):
        raw = self._run(['ls-remote', '--refs', self.address, REF], limit=256, remote=True)
        if not raw:
            return None
        rows = raw.decode('ascii').splitlines()
        if len(rows) != 1:
            raise ValueError('Expected exactly one dist reference')
        parts = rows[0].split('\t')
        if len(parts) != 2 or not SHA.fullmatch(parts[0]) or parts[1] != REF:
            raise ValueError('Invalid remote dist reference')
        return parts[0]

    def fetch(self, expected):
        self._run(['fetch', '--no-recurse-submodules', '--no-tags', '--depth=1',
                   self.address, REF], limit=65536, remote=True)
        observed = self._run(['rev-parse', '--verify', 'FETCH_HEAD^{commit}'], limit=128).decode('ascii').strip()
        if observed != expected:
            raise ValueError('Remote dist changed while fetching; review again')

    def entries(self, commit):
        raw = self._run(['ls-tree', '-r', '-z', commit], limit=MAX_ENTRIES * 256)
        rows = raw.split(b'\0')
        if len(rows) > MAX_ENTRIES + 1:
            raise ValueError('Published tree has too many entries')
        result = {}
        folded = {}
        for row in rows:
            if not row:
                continue
            header, name = row.split(b'\t', 1)
            mode, kind, oid = header.decode('ascii').split()
            path = name.decode('utf-8')
            if mode not in ('100644', '100755') or kind != 'blob' or not SHA.fullmatch(oid):
                raise ValueError('Published distribution contains a non-file object')
            catalog.packages.safe_relative(path)
            for end in range(1, len(path.split('/')) + 1):
                prefix = '/'.join(path.split('/')[:end])
                if prefix.casefold() in folded and folded[prefix.casefold()] != prefix:
                    raise ValueError('Published tree contains case-colliding paths')
                folded[prefix.casefold()] = prefix
            if path in result:
                raise ValueError('Published tree contains duplicate paths')
            result[path] = oid
        return result

    def blob(self, oid, limit):
        if not SHA.fullmatch(oid):
            raise ValueError('Invalid Git object identity')
        size = int(self._run(['cat-file', '-s', oid], limit=128))
        if size > limit:
            raise ValueError('Published file exceeds its bound')
        raw = self._run(['cat-file', 'blob', oid], limit=limit)
        if len(raw) != size:
            raise ValueError('Published Git object changed')
        return raw

    def store(self, raw):
        oid = self._run(['hash-object', '-w', '--stdin'], raw=raw, limit=128).decode('ascii').strip()
        if not SHA.fullmatch(oid):
            raise ValueError('Invalid written Git object identity')
        return oid

    def tree(self, files):
        root = {}
        for path, oid in files.items():
            catalog.packages.safe_relative(path)
            parts = path.split('/')
            node = root
            for part in parts[:-1]:
                node = node.setdefault(part, {})
                if not isinstance(node, dict):
                    raise ValueError('Distribution tree path collision')
            if parts[-1] in node or not SHA.fullmatch(oid):
                raise ValueError('Distribution tree identity collision')
            node[parts[-1]] = oid
        def emit(node):
            rows = []
            for name, item in sorted(node.items()):
                kind, mode, oid = ('tree', '040000', emit(item)) if isinstance(item, dict) else ('blob', '100644', item)
                rows.append((mode + ' ' + kind + ' ' + oid + '\t' + name).encode('utf-8') + b'\0')
            return self._run(['mktree', '-z'], raw=b''.join(rows), limit=128).decode('ascii').strip()
        return emit(root)

    def commit(self, files, parent, message, timestamp):
        arguments = ['commit-tree', self.tree(files)]
        if parent is not None:
            arguments.extend(['-p', parent])
        oid = self._run(arguments, raw=(message + '\n').encode('ascii'), limit=128,
                        timestamp=timestamp).decode('ascii').strip()
        if not SHA.fullmatch(oid):
            raise ValueError('Invalid publication commit identity')
        return oid

    def push(self, commit, expected):
        # An explicit lease is mandatory even though B descends from the old ref.
        self._run(['push', '--porcelain', '--no-verify',
                   '--force-with-lease=' + REF + ':' + (expected or ''),
                   self.address, commit + ':' + REF], limit=65536, remote=True)


class GitHubTransport(_BareTransport):
    def __init__(self, repository, workspace, token=None):
        if not isinstance(repository, str) or not sources.REPO.fullmatch(repository):
            raise ValueError('Expected a GitHub owner/repository identity')
        super().__init__(workspace, 'https://github.com/' + repository + '.git',
                         protocol='https', token=token)


def _distribution_path(path, *, signed=False):
    blob = re.fullmatch(r'blobs/([a-f0-9]{2})/([a-f0-9]{64})', path)
    return (path in ROOT_FILES or (signed and path in SIGNED_FILES) or
            re.fullmatch(r'registry/files/[a-f0-9]{64}\.json', path) is not None or
            (blob is not None and blob[1] == blob[2][:2]))


def _unsigned_snapshot(source, destination):
    source = no_symlink_path(Path(source))
    if not source.is_dir():
        raise ValueError('Unsigned distribution must be a directory')
    destination.mkdir()
    records = []
    total = count = 0
    pending = [(source, '')]
    while pending:
        directory, prefix = pending.pop()
        with os.scandir(directory) as entries:
            for entry in entries:
                count += 1
                if count > MAX_ENTRIES:
                    raise ValueError('Unsigned distribution has too many entries')
                relative = prefix + entry.name
                catalog.packages.safe_relative(relative)
                if entry.is_symlink():
                    raise ValueError('Unsigned distribution contains a symlink')
                if entry.is_dir(follow_symlinks=False):
                    if relative not in ('registry', 'registry/files', 'blobs') and not re.fullmatch(r'blobs/[a-f0-9]{2}', relative):
                        raise ValueError('Unexpected unsigned distribution directory')
                    pending.append((Path(entry.path), relative + '/'))
                    continue
                if not entry.is_file(follow_symlinks=False) or not (_distribution_path(relative) or relative in REPORTS):
                    raise ValueError('Unexpected unsigned distribution file')
                raw = regular(Path(entry.path), 1024 * 1024).read_bytes()
                total += len(raw)
                if total > MAX_BYTES:
                    raise ValueError('Unsigned distribution exceeds its aggregate bound')
                if PRIVATE_MARKER.search(raw):
                    raise ValueError('Unsigned distribution contains private signing material')
                records.append((relative, hashlib.sha256(raw).hexdigest()))
                if relative not in REPORTS:
                    target = destination / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    exclusive_write(target, raw)
    canonical = b''.join((path + '\0' + sha + '\n').encode('utf-8') for path, sha in sorted(records))
    return hashlib.sha256(canonical).hexdigest()


def _read_previous(transport, commit, directory, keys):
    if commit is None:
        previous, ledger = catalog.previous_state(None, keys)
        return None, previous, ledger, {}
    transport.fetch(commit)
    entries = transport.entries(commit)
    directory.mkdir()
    total = 0
    def extract(path, limit):
        nonlocal total
        if path not in entries:
            raise ValueError('Published distribution is missing authenticated content')
        raw = transport.blob(entries[path], limit)
        total += len(raw)
        if total > MAX_BYTES:
            raise ValueError('Published distribution exceeds its aggregate bound')
        target = directory / path
        target.parent.mkdir(parents=True, exist_ok=True)
        exclusive_write(target, raw)
        return raw
    original = {}
    for path in sorted(ROOT_FILES | SIGNED_FILES):
        original[path] = extract(path, 1024 if path.endswith('.sig') else 1024 * 1024)
    previous, ledger = catalog.previous_state(directory, keys)
    live = set()
    for plugin in previous['plugins']:
        live.update(plugin.get('screenshots', []))
        if plugin.get('icon'):
            live.add(plugin['icon'])
        for release in plugin['releases']:
            path = 'registry/files/' + release['digest'] + '.json'
            if not (directory / path).exists():
                extract(path, 65536)
            index = catalog.check_index(catalog.document(directory / path, 65536))
            live.update(item['sha256'] for item in index['files'])
    for sha in sorted(live):
        extract('blobs/' + sha[:2] + '/' + sha, catalog.packages.MAX_FILE_BYTES)
    catalog.retention(directory, None, previous)
    return directory, previous, ledger, original


def _expected_ref(transport, expected):
    if transport.read_ref() != expected:
        raise ValueError('Remote dist is stale; review the current published directory again')


def _history(value, ledger, previous, old_ledger):
    if value['seq'] <= previous['seq']:
        raise ValueError('Registry sequence must increase over the current signed remote directory')
    for identifier, tags in old_ledger['tags'].items():
        for tag, commit in tags.items():
            if ledger['tags'].get(identifier, {}).get(tag) != commit:
                raise ValueError('Publication cannot replace or forget an authenticated tag')
    old = {(plugin['id'], release['version']): release
           for plugin in previous['plugins'] for release in plugin['releases']}
    for plugin in value['plugins']:
        for release in plugin['releases']:
            before = old.get((plugin['id'], release['version']))
            if before and any(release[key] != before[key] for key in ('commit', 'digest')):
                raise ValueError('Publication cannot replace an already published version')


def publish(args, *, transport_factory=GitHubTransport, token=None):
    """Prepare or execute one publication. This function never retries a push."""
    require_owner_host()
    if not sources.REPO.fullmatch(args.repository):
        raise ValueError('Expected a GitHub owner/repository identity')
    expected = None if args.initial else args.expected_dist
    if (args.initial and args.expected_dist is not None) or (not args.initial and not SHA.fullmatch(expected or '')):
        raise ValueError('Choose explicit first use or one exact expected dist commit')
    if args.execute and not re.fullmatch(r'[a-f0-9]{64}', args.reviewed_sha256 or ''):
        raise ValueError('Execution requires the unsigned input SHA-256 from an owner-reviewed plan')
    with tempfile.TemporaryDirectory(prefix='thinkos-owner-registry-publish-') as name:
        work = Path(name).resolve()
        # Snapshot trusted public roots once; a changing caller path cannot switch
        # the verifier or signer halfway through this invocation.
        keys = work / 'keys.json'
        key_bytes = regular(no_symlink_path(args.keys), 65536).read_bytes()
        validate_keyring(sources.read_json(key_bytes, 65536))
        exclusive_write(keys, key_bytes)
        transport = transport_factory(args.repository, work, token=token)
        _expected_ref(transport, expected)
        previous_path, previous, old_ledger, original = _read_previous(transport, expected, work / 'previous', keys)
        material = work / 'material'
        input_sha = _unsigned_snapshot(args.directory, material)
        if args.reviewed_sha256 is not None and input_sha != args.reviewed_sha256:
            raise ValueError('Unsigned input differs from the owner-reviewed plan')
        value = catalog.validate_catalog(catalog.document(material / 'registry/index.json'))
        ledger = catalog.validate_ledger(catalog.document(material / 'registry/tag-ledger.json'))
        _history(value, ledger, previous, old_ledger)
        if value['expires'] <= int(time.time() * 1000):
            raise ValueError('Cannot publish an expired directory')
        catalog.retention(material, None, value)
        compiler = sources.trusted_luac(work, args.luac)
        catalog.revalidate_distribution(material, value, args.official_allowlist, compiler)
        contents = {}
        for root in ('registry/files', 'blobs'):
            for path in sorted((material / root).rglob('*')):
                if path.is_file():
                    relative = path.relative_to(material).as_posix()
                    if not _distribution_path(relative):
                        raise ValueError('Unexpected retained publication content')
                    contents[relative] = transport.store(regular(path, 1024 * 1024).read_bytes())
        # A's tree has no catalog, avoiding a catalog/commit hash self-reference.
        timestamp = value['generated'] // 1000
        snapshot = transport.commit(contents, expected, 'Registry content seq ' + str(value['seq']), timestamp)
        final = copy.deepcopy(value)
        final['blob_bases'] = ['https://cdn.jsdelivr.net/gh/' + args.repository + '@' + snapshot + '/',
                               'https://raw.githubusercontent.com/' + args.repository + '/' + snapshot + '/']
        final = catalog.validate_catalog(final)
        catalog_path = material / 'registry/index.json'
        catalog_path.write_bytes(json_bytes(final))
        report = {'schema': 1, 'status': 'planned', 'published': False, 'remote_actions_performed': False,
                  'repository': args.repository, 'ref': REF, 'expected_dist': expected,
                  'input_sha256': input_sha, 'content_commit': snapshot,
                  'catalog_sha256': catalog.digest(catalog_path), 'seq': final['seq']}
        if not args.execute:
            return report
        # Immediately before opening the private key, fetch the remote again and
        # re-authenticate its exact signed bytes. A missing ref is never a fallback.
        _expected_ref(transport, expected)
        fresh_path, fresh, fresh_ledger, fresh_original = _read_previous(transport, expected, work / 'fresh-previous', keys)
        if fresh_original != original or fresh != previous or fresh_ledger != old_ledger:
            raise ValueError('Published signed bytes changed before signing')
        _history(final, ledger, fresh, fresh_ledger)
        signing = argparse.Namespace(directory=material, keys=keys, private_key=args.private_key,
                                     slot=args.slot, previous=fresh_path, password_fd=args.password_fd,
                                     official_allowlist=args.official_allowlist, luac=compiler)
        catalog.sign(signing)
        catalog.previous_state(material, keys)
        files = dict(contents)
        for path in sorted(ROOT_FILES | SIGNED_FILES):
            files[path] = transport.store(regular(material / path, 1024 * 1024).read_bytes())
        new_commit = transport.commit(files, snapshot, 'Signed registry seq ' + str(final['seq']), timestamp)
        report['commit'] = new_commit
        _expected_ref(transport, expected)
        # No subprocess, including a failed push, may cause an automatic retry.
        push_failed = False
        try:
            transport.push(new_commit, expected)
        except (ValueError, OSError, subprocess.SubprocessError, KeyboardInterrupt):
            push_failed = True
        report['remote_actions_performed'] = True
        try:
            observed = transport.read_ref()
        except (ValueError, OSError, subprocess.SubprocessError, KeyboardInterrupt):
            observed = 'unavailable'
        report['observed_dist'] = observed
        if observed == new_commit:
            report.update(status='published', published=True, push_transport_failed=push_failed)
            return report
        if observed == expected:
            report.update(status='rejected', published=False)
            raise PublicationRejected(report)
        report.update(status='unknown', published=None,
                      recovery='Do not retry automatically; inspect the remote dist ref and this commit first')
        raise PublicationUnknown(report)


def _read_token(descriptor=None, *, required=False):
    if descriptor is not None:
        if descriptor < 3:
            raise ValueError('GitHub token descriptor must be at least 3')
        info = os.fstat(descriptor)
        if stat.S_ISREG(info.st_mode) and (info.st_uid != os.getuid() or info.st_mode & 0o077):
            raise ValueError('GitHub token descriptor is not owner-private')
        raw = os.read(descriptor, 4097)
        if len(raw) > 4096:
            raise ValueError('GitHub token exceeds its input bound')
        token = raw.decode('ascii').removesuffix('\n')
    else:
        token = os.environ.get(TOKEN_ENV)
        if token is None and required and sys.stdin.isatty():
            token = getpass.getpass('GitHub publication token: ')
    if token is None:
        if required:
            raise ValueError('Use an owner terminal, --github-token-fd or the private token environment variable')
        return None
    if not 1 <= len(token) <= 4096 or any(ord(char) < 33 or ord(char) > 126 for char in token):
        raise ValueError('Invalid GitHub token input')
    return token


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    sub = result.add_subparsers(dest='action', required=True)
    command = sub.add_parser('publish', help='Plan by default; sign and CAS-push only with --execute')
    command.add_argument('--directory', type=Path, required=True)
    command.add_argument('--repository', required=True, help='GitHub owner/repository, never a URL')
    command.add_argument('--keys', type=Path, required=True)
    command.add_argument('--private-key', type=Path, required=True)
    expected = command.add_mutually_exclusive_group(required=True)
    expected.add_argument('--expected-dist')
    expected.add_argument('--initial', action='store_true')
    command.add_argument('--reviewed-sha256')
    command.add_argument('--slot', choices=('active', 'backup'), default='active')
    command.add_argument('--password-fd', type=int)
    command.add_argument('--github-token-fd', type=int)
    command.add_argument('--official-allowlist', type=Path)
    command.add_argument('--luac', type=Path)
    command.add_argument('--execute', action='store_true')
    return result


def main(argv=None, *, transport_factory=GitHubTransport):
    args = parser().parse_args(argv)
    require_owner_host()
    token = _read_token(args.github_token_fd, required=args.execute)
    try:
        result = publish(args, token=token, transport_factory=transport_factory)
    finally:
        token = None
    print(json_bytes(result).decode('utf-8'), end='')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (PublicationUnknown, PublicationRejected) as error:
        print(json_bytes(error.report).decode('utf-8'), end='')
        print(str(error), file=sys.stderr)
        sys.exit(2)
    except (ValueError, OSError, RuntimeError, KeyError, TypeError, UnicodeError, subprocess.SubprocessError) as error:
        # No exception values, command arguments, stderr or environments are logged.
        print('Owner registry publication stopped: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
