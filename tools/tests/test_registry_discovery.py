"""Local HTTP and real bare-object fixtures. Never rely on public GitHub or publish."""
import argparse
import contextlib
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import io
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import unittest
from urllib.parse import urlsplit
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
import registry as registry_tool
import registry_discovery as discovery
import registry_ci
import test_registry_sources as existing
import release_tools


def repo(name='alice/fixture', number=1, **patch):
    return {'id': number, 'full_name': name, 'owner': {'login': name.split('/')[0]}, 'private': False,
            'archived': False, 'disabled': False, 'fork': False, 'topics': ['thinkos-plugin'], **patch}


def body(items, total=None, incomplete=False):
    return json.dumps({'items': items, 'total_count': len(items) if total is None else total,
                       'incomplete_results': incomplete}).encode()


@contextlib.contextmanager
def local_api(replies):
    calls = []
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            calls.append({'path': self.path, 'etag': self.headers.get('If-None-Match'),
                          'authorization': self.headers.get('Authorization')})
            status, headers, raw = replies[len(calls)-1]
            self.send_response(status)
            for key, value in headers.items(): self.send_header(key, value)
            self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)
        def log_message(self, *_): pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    def transport(url, headers, remaining):
        # Only tests map the fixed logical GitHub endpoint to this owned server.
        parsed = urlsplit(discovery.api_url(url))
        connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=min(2, remaining))
        try:
            connection.request('GET', parsed.path+'?'+parsed.query, headers=headers)
            response = connection.getresponse()
            raw = response.read(discovery.BODY_MAX+1)
            return response.status, {k.lower(): v for k, v in response.getheaders()}, raw
        finally: connection.close()
    try: yield transport, calls
    finally: server.shutdown(); server.server_close(); thread.join(timeout=5)


class DiscoveryHttpTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name).resolve()
    def tearDown(self): self.temp.cleanup()
    def scan(self, replies, cache='cache', maximum=32, token=None):
        with local_api(replies) as (transport, calls):
            result = discovery.discover(self.root/cache, maximum, token, transport, sleep=lambda _: None)
        return result, calls

    def test_public_api_float_score_projection_and_conditional_cache_never_store_auth(self):
        secret = 'fixture_not_a_real_token_'+'x'*20
        first, calls = self.scan([(200, {'ETag': '"page-one"'}, body([repo(score=1.25)]))], token=secret)
        self.assertTrue(first['complete']); self.assertEqual('alice/fixture', first['repositories'][0]['repo'])
        second, calls = self.scan([(304, {}, b'')], token=secret)
        self.assertTrue(second['complete']); self.assertEqual(1, second['conditional_hits'])
        self.assertEqual('"page-one"', calls[0]['etag'])
        self.assertFalse(any(secret.encode() in path.read_bytes() for path in self.root.rglob('*') if path.is_file()))

    def test_actual_link_pagination_is_followed_and_checked(self):
        page2 = discovery.FIRST.rsplit('page=1', 1)[0]+'page=2'
        rows = [repo('alice/p'+str(index), index+1) for index in range(100)]
        result, calls = self.scan([(200, {'Link': '<'+page2+'>; rel="next"'}, body(rows, 101)),
                                  (200, {}, body([repo('bob/last', 101)], 101))], maximum=128)
        self.assertTrue(result['complete']); self.assertEqual(101, len(result['repositories']))
        self.assertTrue(calls[1]['path'].endswith('page=2'))

    def test_public_fork_archived_and_disabled_metadata_are_not_admission_filters(self):
        items = [repo('alice/forked', 1, fork=True), repo('bob/archived', 2, archived=True),
                 repo('carol/disabled', 3, disabled=True), repo('dave/ordinary', 4)]
        result, calls = self.scan([(200, {'ETag': '"all-public"'}, body(items))], cache='all-public')
        self.assertTrue(result['complete']); self.assertEqual(4, len(result['repositories']))
        self.assertEqual('topic:thinkos-plugin is:public fork:true', discovery.QUERY)
        self.assertIn('fork%3Atrue', calls[0]['path']); self.assertNotIn('archived', calls[0]['path'])
        again, _ = self.scan([(304, {}, b'')], cache='all-public')
        self.assertTrue(again['complete']); self.assertEqual(result['repositories'], again['repositories'])
        private, _ = self.scan([(200, {}, body([repo(private=True)]))], cache='private')
        self.assertEqual([], private['repositories'])

    def test_old_filtered_query_cache_cannot_hide_forks_or_archived_projects(self):
        cache = self.root/'query-change'; cache.mkdir(mode=0o700)
        old_url = discovery.FIRST.replace('fork%3Atrue', 'archived%3Afalse')
        self.assertNotEqual(discovery.FIRST, old_url)
        old = cache/(hashlib.sha256(old_url.encode()).hexdigest()+'.json')
        raw = json.dumps({'schema': 1, 'url': old_url, 'etag': '"old-filter"',
                          'page': {'total': 0, 'incomplete': False, 'items': [], 'raw_count': 0}, 'next': None}).encode()
        old.write_bytes(raw)
        result, calls = self.scan([(200, {}, body([repo(fork=True, archived=True)]))], cache='query-change')
        self.assertTrue(result['complete']); self.assertEqual(1, len(result['repositories']))
        self.assertIsNone(calls[0]['etag']); self.assertEqual(raw, old.read_bytes())

    def test_incomplete_limit_bad_json_duplicate_and_foreign_link_import_nothing(self):
        for response in ((200, {}, body([repo()], incomplete=True)), (200, {}, body([repo()], 1001)),
                         (200, {}, b'{"items":[],"items":[]}'), (200, {}, body([repo(), repo()])),
                         (200, {'Link': '<https://attacker.invalid/>; rel="next"'}, body([repo()], 2))):
            with self.subTest(response=response[0:2]):
                result, calls = self.scan([response], cache='cache-'+str(len(list(self.root.iterdir()))))
                self.assertFalse(result['complete']); self.assertEqual([], result['repositories']); self.assertEqual(1, len(calls))

    def test_rate_errors_stop_without_retry_or_stale_success_and_report_only_bounded_delay(self):
        for status in (403, 429):
            result, calls = self.scan([(status, {'Retry-After': '120'}, b'never log this body')], cache='rate-'+str(status))
            self.assertFalse(result['complete']); self.assertEqual('github_rate_limited', result['error'])
            self.assertEqual({'seconds': 120}, result['retry_after']); self.assertEqual(1, len(calls))
        result, _ = self.scan([(304, {}, b'')], cache='missing')
        self.assertEqual('cache_miss_304', result['error'])
        result, calls = self.scan([], cache='rate-429')
        self.assertEqual('github_rate_limited', result['error']); self.assertEqual([], calls)

    def test_no_secret_is_put_in_curl_argv_environment_or_diagnostics(self):
        secret = 'fixture_token_'+'a'*30
        def fake(arguments, **kwargs):
            self.assertNotIn(secret, str(arguments)); self.assertIn(secret.encode(), kwargs['input'])
            self.assertNotIn(secret, str(kwargs['env']))
            raise TimeoutError('do not expose '+secret)
        with patch.dict('os.environ', {'UNUSUAL_AUTH': secret}), patch.object(discovery.subprocess, 'run', side_effect=fake):
            with self.assertRaises(discovery.DiscoveryError) as error:
                discovery.curl_get(discovery.FIRST, {'Authorization': 'Bearer '+secret}, 15)
        self.assertEqual('github_transport', str(error.exception))

    def test_real_discovery_cli_emits_the_bounded_complete_document(self):
        real = discovery.discover
        output = self.root/'discovery.json'
        with local_api([(200, {}, body([repo()]))]) as (transport, _), \
                patch.object(discovery, 'discover', side_effect=lambda cache, maximum, token: real(cache, maximum, token, transport)), \
                contextlib.redirect_stdout(io.StringIO()) as status:
            code = discovery.main(['--cache', str(self.root/'cli-cache'), '--output', str(output)])
        self.assertEqual(0, code); self.assertTrue(json.loads(output.read_text())['complete'])
        self.assertEqual(1, json.loads(status.getvalue())['repositories'])


class CommunityPipelineTest(unittest.TestCase):
    setUpClass = existing.RegistrySourceTest.__dict__['setUpClass']
    tearDownClass = existing.RegistrySourceTest.__dict__['tearDownClass']
    setUp = existing.RegistrySourceTest.setUp
    tearDown = existing.RegistrySourceTest.tearDown
    git = existing.RegistrySourceTest.git
    commit = existing.RegistrySourceTest.commit
    manifest = existing.RegistrySourceTest.manifest
    metadata = existing.RegistrySourceTest.metadata

    def candidates(self, names, previous=None, ledger=None, registered=()):
        report = {'complete': True, 'repositories': [{'repo': name, 'owner': name.split('/')[0], 'repository_id': i+1}
                                                   for i, name in enumerate(names)]}
        area = self.work/('community-'+str(len(list(self.work.iterdir()))))
        return discovery.community_plan(report, area, previous or {'plugins': []}, ledger or {'tags': {}}, registered, {}, self.compiler,
                                        fetch=lambda record, target: shutil.copytree(self.repo, target))

    def build(self, plan, previous=None, ledger=None):
        source = self.work/('plan-'+str(len(list(self.work.iterdir())))+'.json'); source.write_text(json.dumps(plan))
        output = self.work/('unsigned-'+str(len(list(self.work.iterdir()))))
        args = argparse.Namespace(output=output, previous=None, keys=None, seq=(previous['seq'] if previous is not None else 0)+1, blob_base=['https://example.invalid/immutable'],
            sources=source, approvals=None, revocations=None, official_allowlist=None, luac=self.compiler, expires_days=30)
        if previous is None: return output, registry_tool.build(args)
        with patch.object(registry_tool, 'previous_state', return_value=(previous, ledger or {'schema': 1, 'tags': {}})), \
                patch.object(registry_tool, 'retention'):
            return output, registry_tool.build(args)

    def test_topic_source_automatically_enters_unsigned_catalog_as_community_without_per_project_approval(self):
        self.commit()
        with local_api([(200, {}, body([repo()]))]) as (transport, _):
            found = discovery.discover(self.work/'http-cache', transport=transport)
        plan, report = discovery.community_plan(found, self.work/'http-discovered', {'plugins': []}, {'tags': {}}, [], {}, self.compiler,
                                                fetch=lambda record, target: shutil.copytree(self.repo, target))
        self.assertTrue(report['results'][0]['admitted']); self.assertEqual('topic-community', plan[0]['kind'])
        output, catalog = self.build(plan)
        self.assertEqual('community', catalog['plugins'][0]['tier']); self.assertEqual('fixture', catalog['plugins'][0]['id'])
        self.assertFalse((output/'registry/index.json.sig').exists()); self.assertFalse(report['signed'])

    def test_real_ci_cli_community_mode_discovers_validates_and_builds_without_per_project_registration(self):
        commit = self.commit()
        real = discovery.discover
        workspace = self.work/'ci-work'
        def registry_ref(repository, target, ref, hooks):
            self.assertEqual('maintainer/registry-fixture', repository); self.assertEqual(commit, ref)
            # This actual bare registry fixture has no plugins/ registrations;
            # the root plugin is admitted solely through topic discovery.
            return existing.source_tool.BareReader(self.repo, hooks), commit
        with local_api([(200, {}, body([repo()]))]) as (transport, calls), \
                patch.object(registry_ci, 'fetch_ref', side_effect=registry_ref), \
                patch.object(registry_ci.sources, 'fetch', side_effect=lambda record, target: shutil.copytree(self.repo, target)), \
                patch.object(registry_ci.sources, 'trusted_luac', side_effect=lambda work, supplied=None: self.compiler), \
                patch.object(discovery, 'discover', side_effect=lambda cache, maximum, token: real(cache, maximum, token, transport)):
            registry_ci.main(['--mode', 'community', '--repository', 'maintainer/registry-fixture', '--commit', commit,
                '--workspace', str(workspace), '--blob-base', 'https://example.invalid/immutable', '--initial'])
        catalog = json.loads((workspace/'output/registry/index.json').read_text())
        self.assertEqual([('fixture', 'community')], [(item['id'], item['tier']) for item in catalog['plugins']])
        context = json.loads((workspace/'output/ci-context.json').read_text())
        self.assertEqual('community', context['mode']); self.assertFalse(context['signed']); self.assertFalse(context['published'])
        self.assertEqual(1, len(calls)); self.assertFalse((workspace/'output/registry/index.json.sig').exists())
        self.assertTrue((workspace/'reports/community-validation.json').is_file())

    def test_fork_and_archived_public_projects_use_the_actual_validator_and_normal_id_conflict_rules(self):
        self.commit()
        for label, flags in (('fork', {'fork': True}), ('archive', {'archived': True}),
                             ('both', {'fork': True, 'archived': True})):
            with self.subTest(label=label), local_api([(200, {}, body([repo(**flags)]))]) as (transport, _):
                found = discovery.discover(self.work/('cache-'+label), transport=transport)
            plan, report = discovery.community_plan(found, self.work/('source-'+label), {'plugins': []}, {'tags': {}}, [], {}, self.compiler,
                                                    fetch=lambda record, target: shutil.copytree(self.repo, target))
            self.assertTrue(report['results'][0]['admitted'])
            _, catalog = self.build(plan)
            self.assertEqual('community', catalog['plugins'][0]['tier'])
        with local_api([(200, {}, body([repo('alice/original', 1), repo('bob/fork', 2, fork=True, archived=True)]))]) as (transport, _):
            found = discovery.discover(self.work/'cache-conflict', transport=transport)
        plan, report = discovery.community_plan(found, self.work/'source-conflict', {'plugins': []}, {'tags': {}}, [], {}, self.compiler,
                                                fetch=lambda record, target: shutil.copytree(self.repo, target))
        self.assertEqual([], plan)
        self.assertTrue(all(item['reason'] == 'ambiguous-id' for item in report['results']))

    def test_disabled_public_project_attempts_fetch_and_reports_real_unreadability(self):
        with local_api([(200, {}, body([repo(disabled=True)]))]) as (transport, _):
            found = discovery.discover(self.work/'disabled-cache', transport=transport)
        with patch.object(discovery.sources, 'fetch', side_effect=OSError('owned simulated unreadable source')) as fetch:
            plan, report = discovery.community_plan(found, self.work/'disabled-source', {'plugins': []}, {'tags': {}}, [], {}, self.compiler, fetch=fetch)
        self.assertEqual(1, fetch.call_count); self.assertEqual([], plan)
        self.assertEqual('validation-failed', report['results'][0]['reason'])

    def test_ambiguous_occupied_factory_and_registered_sources_do_not_get_topic_authority(self):
        self.commit()
        plan, report = self.candidates(['alice/a', 'bob/b'])
        self.assertEqual([], plan); self.assertTrue(all(item['reason'] == 'ambiguous-id' for item in report['results']))
        old = {'plugins': [{'id': 'fixture', 'repo': 'other/source', 'tier': 'reviewed'}]}
        plan, report = self.candidates(['alice/a'], previous=old)
        self.assertEqual([], plan); self.assertEqual('occupied-id', report['results'][0]['reason'])
        plan, report = self.candidates(['alice/a'], registered=[(self.registration, {'id': 'fixture', 'repo': 'alice/a'})])
        self.assertEqual([], plan); self.assertEqual('registered-source', report['results'][0]['reason'])
        self.manifest(id='hub'); self.commit()
        plan, report = self.candidates(['alice/a'])
        self.assertEqual([], plan); self.assertFalse(report['results'][0]['admitted'])

    def test_real_symlink_and_author_executable_are_rejected_or_never_run(self):
        marker = self.work/'author-ran'
        self.files['main.lua'] = ('100755', ('os.execute('+json.dumps('touch '+str(marker))+')\n').encode())
        self.commit(); plan, _ = self.candidates(['alice/a'])
        self.assertTrue(plan); self.assertFalse(marker.exists())
        self.files['unsafe'] = ('120000', b'/outside/secret'); self.commit()
        plan, report = self.candidates(['alice/a'])
        self.assertEqual([], plan); self.assertFalse(report['results'][0]['admitted']); self.assertFalse(marker.exists())

    def test_moved_tag_and_permission_increase_keep_existing_build_review_rules(self):
        commit = self.commit(); plan, _ = self.candidates(['alice/fixture'])
        _, first = self.build(plan)
        ledger = {'schema': 1, 'tags': {'fixture': {'v1.0.0': commit}}}
        self.files['main.lua'] = b'function ( invalid\n'; self.commit()
        moved, _ = self.candidates(['alice/fixture'], previous=first, ledger=ledger)
        output, catalog = self.build(moved, first, ledger)
        requests = json.loads((output/'review-requests.json').read_text())['requests']
        self.assertEqual('tag_moved', requests[0]['kind']); self.assertEqual(first['plugins'], catalog['plugins'])
        self.files['main.lua'] = b'tos.on("page.enter", function() end)\n'
        self.manifest(version='1.1.0', permissions=['network', 'alert'])
        self.metadata(why={'network': {'en': 'Read'}, 'alert': {'en': 'Notify'}}); self.commit(tag='v1.1.0')
        newer, _ = self.candidates(['alice/fixture'], previous=first, ledger=ledger)
        output, catalog = self.build(newer, first, ledger)
        requests = json.loads((output/'review-requests.json').read_text())['requests']
        self.assertIn('permissions_increased', [item['kind'] for item in requests])
        self.assertEqual(first['plugins'], catalog['plugins'])

    def test_incomplete_discovery_cannot_start_author_fetch_or_remove_previous(self):
        with self.assertRaises(discovery.DiscoveryError), patch.object(discovery.sources, 'fetch') as fetch:
            discovery.community_plan({'complete': False, 'repositories': []}, self.work/'unused', {'plugins': []}, {'tags': {}}, [], {}, self.compiler)
        fetch.assert_not_called()
