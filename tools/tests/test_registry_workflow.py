"""Execute the actual workflow's Python bindings against owned local fixtures."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / 'registry/main/.github/workflows/scan-tags.yml'


class RegistryWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='thinkos-registry-workflow-')
        self.area = Path(self.temp.name).resolve()
        self.addCleanup(self.temp.cleanup)
        source = WORKFLOW.read_text()
        self.scripts = [textwrap.dedent(match[1]) for match in re.finditer(
            r"(?m)^          python3 -I - <<'PY'\n(.*?)^          PY$", source, re.S)]
        self.assertEqual(4, len(self.scripts))
        self.tools = self.area / 'tools'
        hooks = self.area / 'empty-hooks'; hooks.mkdir()
        self.git_env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
        self.git_env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null',
                            GIT_CONFIG_SYSTEM='/dev/null', GIT_TERMINAL_PROMPT='0',
                            GIT_AUTHOR_NAME='ThinkOS fixture', GIT_AUTHOR_EMAIL='fixture@thinkos.invalid',
                            GIT_COMMITTER_NAME='ThinkOS fixture', GIT_COMMITTER_EMAIL='fixture@thinkos.invalid')
        self.git('init', '--bare', '--template='+str(hooks), str(self.tools), outside=True)
        blob = self.git('hash-object', '-w', '--stdin', raw=b'owned fixture').strip()
        tree = self.git('mktree', raw=b'100644 blob '+blob+b'\tfixture.txt\n').strip()
        self.commit = self.git('commit-tree', tree.decode(), raw=b'workflow fixture\n').decode().strip()
        self.git('update-ref', 'refs/heads/main', self.commit)
        self.git('symbolic-ref', 'HEAD', 'refs/heads/main')
        self.output = self.area / 'github-output'; self.output.write_text('')
        self.environment = dict(self.git_env, RUNNER_TEMP=str(self.area), TOOLS_COMMIT=self.commit,
                                GITHUB_REPOSITORY='owner/registry', GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1',
                                GITHUB_SHA=self.commit, GITHUB_OUTPUT=str(self.output))
        self.data = self.area / 'registry-ci/output'; self.data.mkdir(parents=True)
        self.context = {'schema':1, 'mode':'community', 'registry_commit':self.commit,
                        'signed':False, 'published':False, 'remote_actions_performed':False}
        (self.data/'review-requests.json').write_text(json.dumps({'schema':1,'requests':[], 'remote_actions_performed':False}))
        (self.data/'ci-context.json').write_text(json.dumps(self.context))

    def git(self, *args, raw=None, outside=False):
        command = ['git']
        if not outside: command += ['--git-dir='+str(self.tools)]
        return subprocess.run(command+list(args), input=raw, capture_output=True, check=True,
                              env=self.git_env, timeout=10).stdout

    def run_block(self, number, environment=None, success=True):
        result = subprocess.run([sys.executable, '-I', '-B', '-c', self.scripts[number]],
                                env=self.environment if environment is None else environment,
                                capture_output=True, text=True, timeout=10)
        if success: self.assertEqual(0, result.returncode, result.stderr)
        else: self.assertNotEqual(0, result.returncode)
        return result

    def bind(self):
        self.run_block(0)
        bound = dict(line.split('=',1) for line in self.output.read_text().splitlines())
        for key, value in bound.items(): self.environment['REVIEW_'+key.upper()] = value
        self.environment['REVIEW_ARTIFACT_ID'] = '456'
        folder = self.area/'registry-review-input'; folder.mkdir()
        for name in ('review-requests.json','ci-context.json'):
            (folder/name).write_bytes((self.data/name).read_bytes())
        return folder

    def test_real_producer_and_review_binding_accept_the_actual_community_mode(self):
        self.bind()
        self.run_block(1); self.run_block(2); self.run_block(3)
        source = WORKFLOW.read_text()
        prepare, review = source.split('\n  review:\n',1)
        self.assertIn('    permissions:\n      contents: read\n', prepare)
        self.assertNotIn('contents: write', prepare)
        self.assertIn('needs: prepare', review)
        self.assertIn('      contents: write\n      pull-requests: write\n      issues: write', review)
        self.assertIn('artifact-ids: ${{ needs.prepare.outputs.review_artifact_id }}', review)
        self.assertNotIn('pull_request_target', source)

    def test_identity_mismatch_is_rejected_before_artifact_content_can_authorize(self):
        self.bind()
        for key in ('REVIEW_REPOSITORY','REVIEW_RUN_ID','REVIEW_RUN_ATTEMPT','REVIEW_COMMIT','TOOLS_COMMIT','REVIEW_ARTIFACT_ID'):
            changed = dict(self.environment); changed[key] = 'different'
            self.run_block(1, changed, success=False)

    def test_tampered_bytes_and_symlinks_are_refused_by_the_actual_payload_check(self):
        folder = self.bind(); path = folder/'review-requests.json'; original = path.read_bytes()
        path.write_bytes(original+b' '); self.run_block(2, success=False)
        path.unlink(); outside = self.area/'other-requests.json'; outside.write_bytes(original); path.symlink_to(outside)
        self.run_block(2, success=False)

    def test_hash_matching_wrong_context_still_cannot_cross_mode_commit_and_state_gates(self):
        folder = self.bind()
        for key,value in (('mode','validate'),('registry_commit','b'*40),('signed',True),('published',True),('remote_actions_performed',True)):
            context = dict(self.context); context[key] = value
            raw = json.dumps(context).encode(); (folder/'ci-context.json').write_bytes(raw)
            env = dict(self.environment); env['REVIEW_CONTEXT_SHA'] = hashlib.sha256(raw).hexdigest()
            self.run_block(2, env, success=False)
