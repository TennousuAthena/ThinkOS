"""CLI/parser -> real local HTTP fixtures. No external account, Git, keys or publication."""
import base64
import contextlib
import copy
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.parse import parse_qs, unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('registry_review_under_test', ROOT / 'tools/registry_review.py')
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)
COMMIT = 'a' * 40
REPOSITORY = 'owner/registry'
TOKEN = 'local-fixture-token-not-a-credential'


def permission_request():
    return {'kind': 'permissions_increased', 'requested_action': 'pull_request', 'id': 'sample', 'tag': 'v1.2.3',
            'commit': 'b' * 40, 'digest': 'c' * 64, 'permissions': ['location', 'network'], 'added': ['location'],
            'remote_action_performed': False}


def integrity_request(kind='tag_moved'):
    value = {'kind': kind, 'requested_action': 'issue', 'id': 'sample', 'tag': 'v1.2.3', 'remote_action_performed': False}
    if kind == 'tag_moved':
        value.update(expected_commit='d' * 40, observed_commit='e' * 40)
    return value


def expected_approval():
    return {'id': 'sample', 'version': '1.2.3', 'commit': 'b' * 40,
            'digest': 'c' * 64, 'permissions': ['location', 'network']}


def blob_sha(rows):
    raw = review.encode(rows)
    return hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()


class Fixture:
    def __init__(self):
        self.refs = {'main': COMMIT}
        self.files = {'main': []}
        self.prs = []; self.issues = []; self.log = []
        self.extra_files = []
        self.failure = None
        self.redirect = False
        self.conflict = False
        self.extra_after_put = False

    def route(self, method, raw_path, value):
        url = urlsplit(raw_path); path = unquote(url.path); query = parse_qs(url.query)
        prefix = '/repos/' + REPOSITORY
        if self.redirect:
            return 302, {}
        if self.failure == (method, path):
            self.failure = None
            return 503, {'message': TOKEN}  # API response bodies must never reach CLI diagnostics.
        if path.startswith(prefix + '/git/ref/heads/') and method == 'GET':
            branch = path.split('/git/ref/heads/', 1)[1]
            if branch not in self.refs:
                return 404, {}
            return 200, {'ref': 'refs/heads/' + branch, 'object': {'type': 'commit', 'sha': self.refs[branch]}}
        if path == prefix + '/git/refs' and method == 'POST':
            branch = value['ref'].removeprefix('refs/heads/')
            if branch in self.refs:
                return 422, {}
            if value['sha'] != self.refs['main']:
                return 409, {}
            self.refs[branch] = value['sha']; self.files[branch] = copy.deepcopy(self.files['main'])
            return 201, {'ref': value['ref'], 'object': {'type': 'commit', 'sha': value['sha']}}
        if path == prefix + '/contents/approvals.json' and method == 'GET':
            ref = query['ref'][0]; branch = 'main' if ref == COMMIT else ref
            rows = self.files[branch]; raw = review.encode(rows)
            return 200, {'type': 'file', 'path': 'approvals.json', 'encoding': 'base64', 'size': len(raw),
                         'content': base64.b64encode(raw).decode(), 'sha': blob_sha(rows)}
        if path == prefix + '/contents/approvals.json' and method == 'PUT':
            branch = value['branch']
            if self.conflict or value['sha'] != blob_sha(self.files[branch]):
                return 409, {}
            self.files[branch] = json.loads(base64.b64decode(value['content']))
            self.refs[branch] = hashlib.sha1(review.encode(self.files[branch])).hexdigest()
            if self.extra_after_put:
                self.extra_files = [{'filename': '.github/workflows/concurrent.yml'}]
            return 200, {'commit': {'sha': self.refs[branch]}}
        if path.startswith(prefix + '/compare/') and method == 'GET':
            branch = path.split('...', 1)[1]
            files = [] if self.files[branch] == self.files['main'] else [{'filename': 'approvals.json'}]
            return 200, {'total_commits': len(files), 'files': files + self.extra_files}
        if path == prefix + '/pulls' and method == 'GET':
            branch = query['head'][0].split(':', 1)[1]
            rows = [item for item in self.prs if item['head']['ref'] == branch]
            page = int(query['page'][0]); return 200, rows[(page - 1) * 100:page * 100]
        if path == prefix + '/issues' and method == 'GET':
            page = int(query['page'][0]); return 200, self.issues[(page - 1) * 100:page * 100]
        if path in {prefix + '/pulls', prefix + '/issues'} and method == 'POST':
            is_pr = path.endswith('/pulls'); collection = self.prs if is_pr else self.issues
            item = {'number': len(collection) + 1, 'title': value['title'], 'body': value['body'], 'state': 'open',
                    'user': {'login': 'github-actions[bot]', 'type': 'Bot'}}
            if is_pr:
                item.update(head={'ref': value['head'], 'repo': {'full_name': REPOSITORY}}, base={'ref': value['base']})
            collection.append(item); return 201, copy.deepcopy(item)
        for suffix, collection in (('/pulls/', self.prs), ('/issues/', self.issues)):
            if path.startswith(prefix + suffix) and method == 'PATCH':
                number = int(path.rsplit('/', 1)[1]); item = next(item for item in collection if item['number'] == number)
                item.update(value); return 200, copy.deepcopy(item)
        return 500, {'unexpected': raw_path}


class RegistryReviewTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='thinkos-registry-review-cli-')
        self.requests_file = Path(self.temp.name).resolve() / 'review-requests.json'
        self.fixture = Fixture()
        fixture = self.fixture
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def dispatch(self):
                raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                value = json.loads(raw) if raw else None
                fixture.log.append((self.command, self.path, value, dict(self.headers)))
                status, document = fixture.route(self.command, self.path, value)
                body = json.dumps(document).encode()
                self.send_response(status)
                if status == 302:
                    self.send_header('Location', 'https://untrusted.invalid/steal')
                self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(body)))
                self.end_headers(); self.wfile.write(body)
            do_GET = do_POST = do_PUT = do_PATCH = dispatch
        self.server = HTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
        port = self.server.server_address[1]
        class LocalTransport:
            def request(self, method, path, headers, body, timeout):
                # This endpoint exists only inside this test, never as a CLI/env option.
                connection = http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)
                try:
                    connection.request(method, path, body=body, headers=headers)
                    response = connection.getresponse(); return response.status, response.read(review.LIMIT + 1)
                finally:
                    connection.close()
        self.transport = LocalTransport()
        self.environment = {'GITHUB_ACTIONS': 'true', 'GITHUB_EVENT_NAME': 'schedule', 'GITHUB_REF': 'refs/heads/main',
                            'GITHUB_REPOSITORY': REPOSITORY, 'GITHUB_SHA': COMMIT,
                            'GITHUB_WORKFLOW_REF': REPOSITORY + '/.github/workflows/scan-tags.yml@refs/heads/main',
                            'GITHUB_SERVER_URL': 'https://github.com', 'GITHUB_API_URL': 'https://api.github.com',
                            'GITHUB_TOKEN': TOKEN}

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(); self.temp.cleanup()

    def run_cli(self, rows, environment=None):
        self.requests_file.write_text(json.dumps({'schema': 1, 'requests': rows, 'remote_actions_performed': False}))
        output = io.StringIO(); error = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
            result = review.cli(['--repository', REPOSITORY, '--commit', COMMIT, '--requests', str(self.requests_file)],
                                _transport=self.transport, _environment=self.environment if environment is None else environment)
        self.assertNotIn(TOKEN, output.getvalue() + error.getvalue())
        return result, output.getvalue(), error.getvalue()

    def writes(self):
        return [item for item in self.fixture.log if item[0] != 'GET']

    def test_cli_creates_a_real_approval_file_change_pr_and_repeats_without_new_writes(self):
        unrelated = {'id': 'other', 'version': '1.0.0', 'commit': '1' * 40, 'digest': '2' * 64, 'permissions': ['network']}
        self.fixture.files['main'] = [unrelated]
        request = permission_request(); request['tag'] = 'sample/v1.2.3'
        self.assertEqual(0, self.run_cli([request])[0])
        self.assertEqual(1, len(self.fixture.prs))
        pr = self.fixture.prs[0]; branch = pr['head']['ref']
        self.assertTrue(branch.startswith('codex/registry-review-'))
        self.assertEqual('main', pr['base']['ref'])
        self.assertEqual([unrelated, expected_approval()], self.fixture.files[branch])
        self.assertEqual([unrelated], self.fixture.files['main'])
        writes = len(self.writes())
        self.assertEqual(0, self.run_cli([request])[0])
        self.assertEqual(writes, len(self.writes())); self.assertEqual(1, len(self.fixture.prs))
        self.assertTrue(all(item[3]['Authorization'] == 'Bearer ' + TOKEN for item in self.fixture.log))
        self.assertTrue(all(TOKEN not in item[1] + json.dumps(item[2]) for item in self.fixture.log))
        self.assertTrue(all('/contents/approvals.json' in item[1] for item in self.writes() if item[0] == 'PUT'))
        self.assertFalse(any('merge' in item[1] or '/dist' in item[1] for item in self.fixture.log))

    def test_exact_changed_digest_requires_an_independent_review_and_closed_pr_is_not_reopened(self):
        request = permission_request()
        self.assertEqual(0, self.run_cli([request])[0])
        self.fixture.prs[0]['state'] = 'closed'
        before = len(self.writes())
        self.assertEqual(0, self.run_cli([request])[0]); self.assertEqual(before, len(self.writes()))
        request['digest'] = 'f' * 64
        self.assertEqual(0, self.run_cli([request])[0])
        self.assertEqual(2, len(self.fixture.prs))
        self.assertNotEqual(self.fixture.prs[0]['head']['ref'], self.fixture.prs[1]['head']['ref'])

    def test_merged_approval_on_main_needs_no_branch_or_pr(self):
        request = permission_request(); self.fixture.files['main'] = [expected_approval()]
        self.assertEqual(0, self.run_cli([request])[0]); self.assertEqual([], self.writes())

    def test_tag_move_and_published_change_only_create_idempotent_integrity_issues(self):
        rows = [integrity_request(), integrity_request('published_content_changed')]
        self.assertEqual(0, self.run_cli(rows)[0]); self.assertEqual(2, len(self.fixture.issues))
        before = len(self.writes())
        self.assertEqual(0, self.run_cli(rows)[0]); self.assertEqual(before, len(self.writes()))
        rows[0]['observed_commit'] = 'f' * 40
        self.assertEqual(0, self.run_cli(rows)[0]); self.assertEqual(2, len(self.fixture.issues))
        self.assertEqual(before + 1, len(self.writes()))
        self.assertIn('f' * 40, self.fixture.issues[0]['body'])
        self.assertEqual([], self.fixture.files['main']); self.assertEqual([], self.fixture.prs)
        self.assertTrue(all('/issues' in item[1] for item in self.writes()))

    def test_issue_lookup_paginates_and_never_adopts_a_human_marker(self):
        row = integrity_request()
        self.assertEqual(0, self.run_cli([row])[0]); existing = self.fixture.issues.pop()
        self.fixture.issues = [{'number': n + 2, 'body': '', 'title': '', 'user': {'login': 'human', 'type': 'User'}} for n in range(100)] + [existing]
        before = len(self.writes())
        self.assertEqual(0, self.run_cli([row])[0]); self.assertEqual(before, len(self.writes()))
        existing['body'] = 'Owner note\n' + existing['body']
        self.assertEqual(1, self.run_cli([row])[0]); self.assertEqual(before, len(self.writes()))
        existing['user'] = {'login': 'human', 'type': 'User'}
        self.assertEqual(1, self.run_cli([row])[0]); self.assertEqual(before, len(self.writes()))

    def test_write_after_file_commit_interruption_resumes_the_same_branch(self):
        self.fixture.failure = ('POST', '/repos/' + REPOSITORY + '/pulls')
        self.assertEqual(1, self.run_cli([permission_request()])[0]); self.assertEqual(0, len(self.fixture.prs))
        puts = len([item for item in self.writes() if item[0] == 'PUT'])
        self.assertEqual(0, self.run_cli([permission_request()])[0]); self.assertEqual(1, len(self.fixture.prs))
        self.assertEqual(puts, len([item for item in self.writes() if item[0] == 'PUT']))

    def test_owner_appended_pr_and_issue_notes_are_preserved_and_stop_updates(self):
        request = permission_request()
        self.assertEqual(0, self.run_cli([request])[0])
        note = '\nMaintainer note: do not remove this review context\n'
        original_pr_body = self.fixture.prs[0]['body']
        self.fixture.prs[0]['body'] += note
        before = len(self.writes())
        body = self.fixture.prs[0]['body']
        self.assertEqual(1, self.run_cli([request])[0])
        self.assertEqual(before, len(self.writes())); self.assertEqual(body, self.fixture.prs[0]['body'])
        self.fixture.prs[0]['body'] = original_pr_body
        self.fixture.prs[0]['title'] = 'Maintainer review title'
        self.assertEqual(1, self.run_cli([request])[0])
        self.assertEqual(before, len(self.writes()))
        self.assertEqual('Maintainer review title', self.fixture.prs[0]['title'])
        row = integrity_request()
        self.assertEqual(0, self.run_cli([row])[0])
        original_issue_body = self.fixture.issues[0]['body']
        self.fixture.issues[0]['body'] += note
        before = len(self.writes()); body = self.fixture.issues[0]['body']
        row['observed_commit'] = 'f' * 40
        self.assertEqual(1, self.run_cli([row])[0])
        self.assertEqual(before, len(self.writes())); self.assertEqual(body, self.fixture.issues[0]['body'])
        self.fixture.issues[0]['body'] = original_issue_body
        self.fixture.issues[0]['title'] = 'Maintainer integrity title'
        self.assertEqual(1, self.run_cli([row])[0])
        self.assertEqual(before, len(self.writes()))
        self.assertEqual('Maintainer integrity title', self.fixture.issues[0]['title'])

    def test_file_added_during_the_approval_commit_is_rejected_before_pr_creation(self):
        self.fixture.extra_after_put = True
        self.assertEqual(1, self.run_cli([permission_request()])[0])
        self.assertTrue(any(item[0] == 'PUT' for item in self.writes()))
        self.assertEqual([], self.fixture.prs)
        comparisons = [item for item in self.fixture.log if item[0] == 'GET' and '/compare/' in item[1]]
        self.assertEqual(2, len(comparisons))

    def test_extra_branch_files_conflicting_sha_and_redirect_are_refused(self):
        self.fixture.extra_files = [{'filename': '.github/workflows/untrusted.yml'}]
        self.assertEqual(1, self.run_cli([permission_request()])[0]); self.assertEqual([], self.fixture.prs)
        self.assertFalse(any(item[0] == 'PUT' for item in self.writes()))
        self.fixture.extra_files = []; self.fixture.conflict = True
        self.assertEqual(1, self.run_cli([permission_request()])[0]); self.assertEqual([], self.fixture.prs)
        self.fixture.conflict = False; self.fixture.redirect = True
        before = len(self.fixture.log)
        self.assertEqual(1, self.run_cli([permission_request()])[0]); self.assertEqual(before + 1, len(self.fixture.log))

    def test_fork_pr_nonmain_wrong_workflow_and_api_override_fail_before_http(self):
        for key, value in [('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_REF', 'refs/pull/7/merge'),
                           ('GITHUB_WORKFLOW_REF', REPOSITORY + '/.github/workflows/untrusted.yml@refs/heads/main'),
                           ('GITHUB_API_URL', 'https://untrusted.invalid'), ('GITHUB_TOKEN', 'bad\nheader')]:
            environment = {**self.environment, key: value}
            self.assertEqual(1, self.run_cli([permission_request()], environment)[0])
        self.assertEqual([], self.fixture.log)

    def test_untrusted_mentions_paths_unknown_fields_and_versions_are_data_rejections(self):
        for key, value in [('id', '../escape'), ('id', '@everyone'), ('tag', 'v1.2.3@everyone'),
                           ('permissions', ['@reviewers']), ('tag', 'v01.2.3'), ('api_url', 'https://bad.invalid')]:
            request = {**permission_request(), key: value}
            self.assertEqual(1, self.run_cli([permission_request(), request])[0])
        self.assertEqual([], self.fixture.log)

    def test_duplicate_boolean_and_oversized_request_documents_fail_before_http(self):
        valid = {'schema': 1, 'requests': [], 'remote_actions_performed': False}
        raws = [json.dumps(valid).replace('"schema": 1', '"schema": 1, "schema": 1').encode(),
                json.dumps({**valid, 'schema': True}).encode(), b' ' * (review.LIMIT + 1)]
        for raw in raws:
            self.requests_file.write_bytes(raw)
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                code = review.cli(['--repository', REPOSITORY, '--commit', COMMIT, '--requests', str(self.requests_file)],
                                  _transport=self.transport, _environment=self.environment)
            self.assertEqual(1, code)
        self.assertEqual([], self.fixture.log)

    def test_cli_has_no_production_endpoint_override_option(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            review.cli(['--repository', REPOSITORY, '--commit', COMMIT, '--requests', str(self.requests_file),
                        '--api-url', 'http://127.0.0.1:1'], _transport=self.transport, _environment=self.environment)
        self.assertEqual([], self.fixture.log)


if __name__ == '__main__':
    unittest.main()
