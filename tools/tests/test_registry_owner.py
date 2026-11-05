"""Owner publisher fixtures: real local bare Git/OpenSSL, no remote network writes."""
import argparse
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

# Reuse the existing disposable, encrypted P-256 test roots and Lua 5.4 compiler.
import test_registry_sources as fixture_tests
import registry as registry_tool
import registry_owner as owner


class RegistryOwnerTest(unittest.TestCase):
    git = fixture_tests.RegistryGenerationTest.git
    commit = fixture_tests.RegistryGenerationTest.commit
    manifest = fixture_tests.RegistryGenerationTest.manifest
    metadata = fixture_tests.RegistryGenerationTest.metadata
    build = fixture_tests.RegistryGenerationTest.build

    @classmethod
    def setUpClass(cls):
        fixture_tests.RegistryGenerationTest.setUpClass.__func__(cls)

    @classmethod
    def tearDownClass(cls):
        fixture_tests.RegistryGenerationTest.tearDownClass.__func__(cls)

    def setUp(self):
        fixture_tests.RegistryGenerationTest.setUp(self)
        self.remote = self.work / 'owner publication remote.git'
        self.remote_git('init', '--bare', '--template=' + str(self.hooks), self.remote, outside=True)
        self.calls = {'push': 0}
        self.instances = []
        self.commit()
        self.output, _ = self.build()
        self.owner_environment = patch.dict(os.environ, {
            'CI': '', 'GITHUB_ACTIONS': '', 'GITLAB_CI': '', 'BUILD_BUILDID': ''})
        self.owner_environment.start()
        self.addCleanup(self.owner_environment.stop)

    def tearDown(self):
        fixture_tests.RegistryGenerationTest.tearDown(self)

    def remote_git(self, *arguments, raw=None, outside=False):
        command = ['git', '-c', 'core.hooksPath=' + str(self.hooks),
                   '-c', 'commit.gpgsign=false', '-c', 'tag.gpgsign=false']
        if not outside:
            command.append('--git-dir=' + str(self.remote))
        command.extend(str(value) for value in arguments)
        environment = owner._git_environment('file', timestamp=1793620000)
        return subprocess.run(command, input=raw, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              check=True, env=environment).stdout

    def factory(self, repository, workspace, token=None):
        parent = self
        class FixtureTransport(owner._BareTransport):
            def __init__(self):
                super().__init__(workspace, str(parent.remote), protocol='file')

            def push(self, commit, expected):
                parent.calls['push'] += 1
                return super().push(commit, expected)
        transport = FixtureTransport()
        self.instances.append(transport)
        return transport

    def args(self, *, directory=None, expected=None, execute=False, reviewed=None):
        return argparse.Namespace(directory=directory or self.output, repository='alice/thinkos-registry',
                                  keys=self.public, private_key=self.keydir / 'registry-active.key.pem',
                                  expected_dist=expected, initial=expected is None, execute=execute,
                                  reviewed_sha256=reviewed, slot='active', password_fd=None,
                                  github_token_fd=None, official_allowlist=None, luac=self.compiler)

    def plan(self, args=None, factory=None):
        return owner.publish(args or self.args(), transport_factory=factory or self.factory)

    def execute(self, args=None, factory=None):
        args = args or self.args()
        if args.reviewed_sha256 is None:
            plan = self.plan(args, factory)
            args.reviewed_sha256 = plan['input_sha256']
        args.execute = True
        with patch.object(registry_tool, 'read_password', return_value=self.password):
            return owner.publish(args, transport_factory=factory or self.factory)

    def ref(self):
        raw = self.remote_git('show-ref', '--verify', '--hash', owner.REF)
        return raw.decode('ascii').strip()

    def has_ref(self):
        return bool(self.remote_git('for-each-ref', '--format=%(objectname)', owner.REF).strip())

    def show(self, commit, path):
        return self.remote_git('show', commit + ':' + path)

    def materialize(self, commit, path):
        # Use the same object-only extraction path; there is no fixture checkout.
        with tempfile.TemporaryDirectory(dir=self.work, prefix='previous transport ') as name:
            transport = self.factory('alice/thinkos-registry', Path(name))
            return owner._read_previous(transport, commit, path, self.public)[0]

    def advance_remote(self):
        before = self.ref() if self.has_ref() else None
        tree = (self.remote_git('rev-parse', before + '^{tree}').strip() if before else
                self.remote_git('mktree', raw=b'').strip())
        arguments = ['commit-tree', tree.decode('ascii')]
        if before:
            arguments.extend(['-p', before])
        commit = self.remote_git(*arguments, raw=b'concurrent owner fixture\n').decode('ascii').strip()
        self.remote_git('update-ref', owner.REF, commit)
        return commit

    def test_plan_does_not_open_private_key_sign_push_or_modify_input(self):
        args = self.args()
        args.private_key = self.work / 'missing formal key is not a plan prerequisite.pem'
        before = (self.output / 'registry/index.json').read_bytes()
        with patch.object(registry_tool, 'sign', side_effect=AssertionError('plan must not sign')):
            first = self.plan(args)
            second = self.plan(args)
        self.assertEqual(first, second)
        self.assertEqual('planned', first['status'])
        self.assertFalse(first['published'])
        self.assertFalse(first['remote_actions_performed'])
        self.assertEqual(0, self.calls['push'])
        self.assertFalse(self.has_ref())
        self.assertEqual(before, (self.output / 'registry/index.json').read_bytes())
        self.assertFalse((self.output / 'registry/index.json.sig').exists())

    def test_first_publish_has_real_signatures_immutable_urls_and_only_allowlisted_files(self):
        # Root reports can contain local metadata but can never enter either tree.
        (self.output / 'owner-signature-report.json').write_text('{"local_marker":"do-not-publish"}\n')
        result = self.execute()
        self.assertEqual('published', result['status'])
        self.assertTrue(result['published'])
        self.assertEqual(1, self.calls['push'])
        self.assertEqual(result['commit'], self.ref())
        self.assertEqual(result['content_commit'],
                         self.remote_git('rev-parse', result['commit'] + '^').decode().strip())
        previous = self.materialize(result['commit'], self.work / 'authenticated published')
        value, _ = registry_tool.previous_state(previous, self.public)
        self.assertEqual(['https://cdn.jsdelivr.net/gh/alice/thinkos-registry@' + result['content_commit'] + '/',
                          'https://raw.githubusercontent.com/alice/thinkos-registry/' + result['content_commit'] + '/'],
                         value['blob_bases'])
        self.assertEqual(result['catalog_sha256'], hashlib.sha256(self.show(result['commit'], 'registry/index.json')).hexdigest())
        names = self.remote_git('ls-tree', '-r', '--name-only', result['commit']).decode().splitlines()
        self.assertTrue(all(owner._distribution_path(path, signed=True) for path in names))
        self.assertFalse(set(names) & owner.REPORTS)
        self.assertNotIn('keys.json', names)
        self.assertNotIn('sources.json', names)
        content_names = self.remote_git('ls-tree', '-r', '--name-only', result['content_commit']).decode().splitlines()
        self.assertTrue(content_names)
        self.assertFalse(set(content_names) & (owner.ROOT_FILES | owner.SIGNED_FILES))
        for path in content_names:
            self.assertEqual(self.show(result['content_commit'], path), self.show(result['commit'], path))
        self.assertFalse((self.output / 'registry/index.json.sig').exists())

    def test_next_publish_uses_authenticated_current_remote_and_preserves_commit_parentage(self):
        first = self.execute()
        previous = self.materialize(first['commit'], self.work / 'previous for next build')
        self.manifest(version='1.0.1')
        self.commit(tag='v1.0.1')
        output, _ = self.build('v1.0.1', previous)
        result = self.execute(self.args(directory=output, expected=first['commit']))
        self.assertEqual(2, result['seq'])
        self.assertEqual(first['commit'], self.remote_git('rev-parse', result['content_commit'] + '^').decode().strip())
        self.assertEqual(result['commit'], self.ref())
        self.assertEqual(2, self.calls['push'])

    def test_execute_requires_review_digest_and_rejects_changed_artifact_without_signing(self):
        with patch.object(registry_tool, 'sign', side_effect=AssertionError('must not sign')):
            with self.assertRaises(ValueError):
                self.plan(self.args(execute=True))
            reviewed = self.plan()['input_sha256']
            (self.output / 'build-report.json').write_text('{"modified_after_review":true}\n')
            with self.assertRaises(ValueError):
                self.plan(self.args(execute=True, reviewed=reviewed))
        self.assertFalse(self.has_ref())
        self.assertEqual(0, self.calls['push'])

    def test_ci_is_refused_before_git_or_signing(self):
        with patch.dict(os.environ, {'CI': 'true'}), patch.object(registry_tool, 'sign') as signer:
            with self.assertRaises(ValueError):
                self.plan(factory=lambda *a, **k: self.fail('CI must not initialize transport'))
        signer.assert_not_called()

    def test_expired_catalog_is_not_signed_or_published(self):
        path = self.output / 'registry/index.json'
        value = registry_tool.document(path)
        value['generated'], value['expires'] = 1000, 2000
        path.write_bytes(owner.json_bytes(value))
        with patch.object(registry_tool, 'sign', side_effect=AssertionError('must not sign')):
            with self.assertRaises(ValueError):
                self.plan()
        self.assertFalse(self.has_ref())
        self.assertEqual(0, self.calls['push'])

    def test_stale_expected_and_initial_on_existing_remote_never_sign_or_push(self):
        first = self.execute()
        pushes = self.calls['push']
        with patch.object(registry_tool, 'sign', side_effect=AssertionError('must not sign')):
            for args in (self.args(execute=True, reviewed='0' * 64),
                         self.args(expected='1' * 40, execute=True, reviewed='0' * 64)):
                with self.assertRaises(ValueError):
                    self.plan(args)
        self.assertEqual(first['commit'], self.ref())
        self.assertEqual(pushes, self.calls['push'])

    def test_remote_is_rechecked_immediately_before_private_key_access(self):
        reviewed = self.plan()['input_sha256']
        def factory(repository, workspace, token=None):
            transport = self.factory(repository, workspace)
            original = transport.read_ref
            transport.reads = 0
            def read_ref():
                transport.reads += 1
                if transport.reads == 2:
                    self.advance_remote()
                return original()
            transport.read_ref = read_ref
            return transport
        with patch.object(registry_tool, 'sign', side_effect=AssertionError('must not sign')):
            with self.assertRaises(ValueError):
                self.plan(self.args(execute=True, reviewed=reviewed), factory)
        self.assertTrue(self.has_ref())
        self.assertEqual(0, self.calls['push'])

    def test_cas_rejects_a_race_after_the_final_pre_push_ref_check(self):
        reviewed = self.plan()['input_sha256']
        competing = []
        def factory(repository, workspace, token=None):
            transport = self.factory(repository, workspace)
            original = transport.push
            def push(commit, expected):
                competing.append(self.advance_remote())
                return original(commit, expected)
            transport.push = push
            return transport
        with self.assertRaises(owner.PublicationUnknown) as caught:
            self.execute(self.args(reviewed=reviewed), factory)
        self.assertEqual(competing[0], self.ref())
        self.assertEqual(1, self.calls['push'])
        self.assertIsNone(caught.exception.report['published'])
        self.assertEqual('unknown', caught.exception.report['status'])

    def test_push_failure_after_acceptance_is_success_only_when_remote_ref_readback_matches(self):
        reviewed = self.plan()['input_sha256']
        def factory(repository, workspace, token=None):
            transport = self.factory(repository, workspace)
            original = transport.push
            def push(commit, expected):
                original(commit, expected)
                raise ValueError('fixture connection failed after remote accepted')
            transport.push = push
            return transport
        result = self.execute(self.args(reviewed=reviewed), factory)
        self.assertTrue(result['published'])
        self.assertTrue(result['push_transport_failed'])
        self.assertEqual(result['commit'], self.ref())
        self.assertEqual(1, self.calls['push'])

    def test_missing_readback_is_unknown_and_is_never_retried(self):
        reviewed = self.plan()['input_sha256']
        def factory(repository, workspace, token=None):
            transport = self.factory(repository, workspace)
            original_push, original_read = transport.push, transport.read_ref
            transport.pushed = False
            def push(commit, expected):
                original_push(commit, expected)
                transport.pushed = True
            def read_ref():
                if transport.pushed:
                    raise ValueError('fixture readback unavailable')
                return original_read()
            transport.push, transport.read_ref = push, read_ref
            return transport
        with self.assertRaises(owner.PublicationUnknown) as caught:
            self.execute(self.args(reviewed=reviewed), factory)
        self.assertIsNone(caught.exception.report['published'])
        self.assertTrue(caught.exception.report['remote_actions_performed'])
        self.assertEqual('unavailable', caught.exception.report['observed_dist'])
        self.assertEqual(1, self.calls['push'])
        self.assertEqual(caught.exception.report['commit'], self.ref())

    def test_rejected_push_with_unchanged_ref_is_reported_without_retry(self):
        reviewed = self.plan()['input_sha256']
        calls = []
        def factory(repository, workspace, token=None):
            transport = self.factory(repository, workspace)
            def push(commit, expected):
                calls.append(commit)
                raise ValueError('fixture rejected push')
            transport.push = push
            return transport
        with self.assertRaises(owner.PublicationRejected) as caught:
            self.execute(self.args(reviewed=reviewed), factory)
        self.assertFalse(caught.exception.report['published'])
        self.assertEqual('rejected', caught.exception.report['status'])
        self.assertEqual(1, len(calls))
        self.assertFalse(self.has_ref())

    def test_tampered_signed_remote_is_rejected_before_new_signing(self):
        first = self.execute()
        value = self.show(first['commit'], 'registry/index.json') + b'\n'
        # Rewrite one tree object in the bare fixture; preserve its old signature.
        bad_blob = self.remote_git('hash-object', '-w', '--stdin', raw=value).decode().strip()
        registry_tree = self.remote_git('ls-tree', '-z', first['commit'] + ':registry')
        rows = []
        for row in registry_tree.split(b'\0'):
            if not row:
                continue
            header, path = row.split(b'\t', 1)
            if path == b'index.json':
                header = b'100644 blob ' + bad_blob.encode()
            rows.append(header + b'\t' + path + b'\0')
        new_registry = self.remote_git('mktree', '-z', raw=b''.join(rows)).decode().strip()
        root_rows = []
        for row in self.remote_git('ls-tree', '-z', first['commit']).split(b'\0'):
            if not row:
                continue
            header, path = row.split(b'\t', 1)
            if path == b'registry':
                header = b'040000 tree ' + new_registry.encode()
            root_rows.append(header + b'\t' + path + b'\0')
        tree = self.remote_git('mktree', '-z', raw=b''.join(root_rows)).decode().strip()
        tampered = self.remote_git('commit-tree', tree, '-p', first['commit'], raw=b'tampered fixture\n').decode().strip()
        self.remote_git('update-ref', owner.REF, tampered)
        with patch.object(registry_tool, 'sign', side_effect=AssertionError('must not sign')):
            with self.assertRaises(ValueError):
                self.plan(self.args(expected=tampered))
        self.assertEqual(tampered, self.ref())
        self.assertEqual(1, self.calls['push'])

    def test_unknown_files_symlinks_wrong_blob_prefix_and_tampered_blob_are_rejected(self):
        cases = ('private-file', 'symlink', 'wrong-prefix', 'tampered-blob')
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory(dir=self.work) as name:
                material = Path(name) / 'material'
                owner._unsigned_snapshot(self.output, material)
                if case == 'private-file':
                    (material / 'registry-active.key.pem').write_text('must never be published')
                elif case == 'symlink':
                    (material / 'untrusted-link').symlink_to(self.public)
                else:
                    blob = next(path for path in (material / 'blobs').rglob('*') if path.is_file())
                    if case == 'wrong-prefix':
                        prefix = 'ff' if blob.parent.name != 'ff' else '00'
                        target = material / 'blobs' / prefix / blob.name
                        target.parent.mkdir(exist_ok=True)
                        target.write_bytes(blob.read_bytes())
                    else:
                        blob.write_bytes(blob.read_bytes() + b'changed')
                with self.assertRaises(ValueError):
                    self.plan(self.args(directory=material))
        self.assertFalse(self.has_ref())
        self.assertEqual(0, self.calls['push'])

    def test_non_increasing_seq_and_forgotten_tag_history_are_rejected(self):
        first = self.execute()
        with self.assertRaises(ValueError):
            self.plan(self.args(expected=first['commit']))
        previous = self.materialize(first['commit'], self.work / 'history previous')
        self.manifest(version='1.0.1')
        self.commit(tag='v1.0.1')
        output, _ = self.build('v1.0.1', previous)
        (output / 'registry/tag-ledger.json').write_text('{"schema":1,"tags":{}}\n')
        with self.assertRaises(ValueError):
            self.plan(self.args(directory=output, expected=first['commit']))
        self.assertEqual(first['commit'], self.ref())
        self.assertEqual(1, self.calls['push'])

    def test_wrong_role_plaintext_or_non_private_signing_key_never_reaches_push(self):
        reviewed = self.plan()['input_sha256']
        weak = self.work / 'non-private.key.pem'
        weak.write_bytes((self.keydir / 'registry-active.key.pem').read_bytes())
        weak.chmod(0o644)
        plaintext = self.work / 'plaintext.key.pem'
        plaintext.write_text('-----BEGIN PRIVATE KEY-----\ninvalid fixture\n')
        plaintext.chmod(0o600)
        for private in (self.keydir / 'channel-active.key.pem', weak, plaintext):
            with self.subTest(private=private.name):
                args = self.args(reviewed=reviewed)
                args.private_key = private
                with self.assertRaises(ValueError):
                    self.execute(args)
        self.assertFalse(self.has_ref())
        self.assertEqual(0, self.calls['push'])

    def test_input_count_total_bytes_and_single_file_size_are_bounded(self):
        with tempfile.TemporaryDirectory(dir=self.work) as name:
            root = Path(name)
            with patch.object(owner, 'MAX_BYTES', 1):
                with self.assertRaises(ValueError):
                    owner._unsigned_snapshot(self.output, root / 'total')
            with patch.object(owner, 'MAX_ENTRIES', 1):
                with self.assertRaises(ValueError):
                    owner._unsigned_snapshot(self.output, root / 'count')
            oversized = root / 'oversized'
            oversized.mkdir()
            report = oversized / 'build-report.json'
            with report.open('wb') as stream:
                stream.seek(1024 * 1024)
                stream.write(b'x')
            with self.assertRaises(ValueError):
                owner._unsigned_snapshot(oversized, root / 'single')

    def test_hooks_author_scripts_and_inherited_git_trace_config_cannot_execute(self):
        marker = self.work / 'must-not-exist'
        hook = self.remote / 'hooks/pre-push'
        hook.parent.mkdir(exist_ok=True)
        hook.write_text('#!/bin/sh\ntouch "' + str(marker) + '"\n')
        hook.chmod(0o755)
        trace = self.work / 'must-not-exist.trace'
        with patch.dict(os.environ, {'GIT_TRACE': str(trace), 'GIT_CONFIG_COUNT': '1',
                                     'GIT_CONFIG_KEY_0': 'core.hooksPath',
                                     'GIT_CONFIG_VALUE_0': str(self.remote / 'hooks'),
                                     'GIT_AUTHOR_NAME': 'untrusted author',
                                     'GIT_COMMITTER_EMAIL': 'untrusted@example.invalid'}):
            result = self.execute()
        self.assertFalse(marker.exists())
        self.assertFalse(trace.exists())
        self.assertIn(b'author ThinkOS Registry Owner <registry@thinkos.invalid>',
                      self.remote_git('cat-file', 'commit', result['commit']))

    def test_cli_cannot_select_file_ssh_arbitrary_url_or_implicit_execution(self):
        parser = owner.parser()
        common = ['publish', '--directory', str(self.output), '--repository', 'alice/thinkos-registry',
                  '--keys', str(self.public), '--private-key', str(self.keydir / 'registry-active.key.pem'), '--initial']
        self.assertFalse(parser.parse_args(common).execute)
        with contextlib.redirect_stderr(io.StringIO()):
            for extra in (['--remote', str(self.remote)], ['--transport', 'file'], ['--github-token', 'SECRET']):
                with self.assertRaises(SystemExit):
                    parser.parse_args(common + extra)
        for repository in ('file:///tmp/evil', 'git@github.com:alice/x', 'https://github.com/alice/x', '../x'):
            with self.subTest(repository=repository), tempfile.TemporaryDirectory(dir=self.work) as name:
                with self.assertRaises(ValueError):
                    owner.GitHubTransport(repository, Path(name))

    def test_complete_cli_argv_plan_then_execute_signs_and_cas_publishes_to_real_bare(self):
        argv = ['publish', '--directory', str(self.output),
                '--repository', 'alice/thinkos-registry', '--keys', str(self.public),
                '--private-key', str(self.keydir / 'registry-active.key.pem'),
                '--luac', str(self.compiler), '--initial']
        token = 'disposable-complete-cli-token'
        received = []
        def factory(repository, workspace, token=None):
            received.append((repository, token))
            return self.factory(repository, workspace, token=token)
        with patch.dict(os.environ, {owner.TOKEN_ENV: token}):
            planned_output = io.StringIO()
            with contextlib.redirect_stdout(planned_output):
                self.assertEqual(0, owner.main(argv, transport_factory=factory))
            plan = json.loads(planned_output.getvalue())
            self.assertEqual('planned', plan['status'])
            self.assertFalse(self.has_ref())
            published_output = io.StringIO()
            with patch.object(registry_tool, 'read_password', return_value=self.password), \
                    contextlib.redirect_stdout(published_output):
                self.assertEqual(0, owner.main(argv + ['--reviewed-sha256', plan['input_sha256'], '--execute'],
                                               transport_factory=factory))
        result = json.loads(published_output.getvalue())
        self.assertEqual([('alice/thinkos-registry', token)] * 2, received)
        self.assertEqual('published', result['status'])
        self.assertTrue(result['published'])
        self.assertEqual(1, self.calls['push'])
        self.assertEqual(result['commit'], self.ref())
        previous = self.materialize(result['commit'], self.work / 'complete cli signed result')
        authenticated, _ = registry_tool.previous_state(previous, self.public)
        self.assertEqual(result['seq'], authenticated['seq'])
        self.assertEqual(result['content_commit'], self.remote_git('rev-parse', result['commit'] + '^').decode().strip())
        self.assertNotIn(token, planned_output.getvalue() + published_output.getvalue())
        self.assertNotIn(self.password, planned_output.getvalue() + published_output.getvalue())

    def test_token_is_only_in_child_environment_and_fd_must_be_owner_private(self):
        token = 'disposable-test-token'
        environment = owner._git_environment('https', token=token)
        self.assertNotIn(owner.TOKEN_ENV, environment)
        self.assertNotIn('GIT_TRACE', environment)
        decoded = environment['GIT_CONFIG_VALUE_0'].split('Basic ', 1)[1]
        self.assertEqual('x-access-token:' + token, owner.base64.b64decode(decoded).decode())
        with tempfile.TemporaryDirectory(dir=self.work) as name:
            path = Path(name) / 'token'
            path.write_text(token + '\n')
            path.chmod(0o600)
            with path.open('rb') as stream:
                self.assertEqual(token, owner._read_token(stream.fileno()))
            path.chmod(0o644)
            with path.open('rb') as stream:
                with self.assertRaises(ValueError):
                    owner._read_token(stream.fileno())
        with patch.dict(os.environ, {owner.TOKEN_ENV: 'bad\ntoken'}):
            with self.assertRaises(ValueError):
                owner._read_token()


if __name__ == '__main__':
    unittest.main()
