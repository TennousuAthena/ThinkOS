#!/usr/bin/env python3
"""MIT. Public topic discovery -> validated community-only unsigned source plans.
GitHub is discovery, never an official identity, signature or permission grant.
"""
import argparse
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import re
import resource
import stat
import subprocess
import sys
import tempfile
import time
from urllib.parse import parse_qsl, urlencode, urlsplit

import registry_sources as sources
from release_tools import exclusive_write, json_bytes, no_symlink_path, regular

QUERY = 'topic:thinkos-plugin is:public fork:true'
API_VERSION = '2022-11-28'
ORIGIN = 'https://api.github.com'
FIRST = ORIGIN + '/search/repositories?' + urlencode({'q': QUERY, 'per_page': 100, 'page': 1})
BODY_MAX = 2 * 1024 * 1024
PAGE_MAX = 2
REPO_MAX = 128


class DiscoveryError(ValueError):
    def __init__(self, code, retry_after=None):
        super().__init__(code)
        self.code, self.retry_after = code, retry_after


def api_url(value):
    if not isinstance(value, str) or len(value) > 2048 or any(ord(c) < 33 or ord(c) > 126 for c in value):
        raise DiscoveryError('pagination_url')
    url = urlsplit(value)
    pairs = parse_qsl(url.query, strict_parsing=True)
    query = dict(pairs)
    if url.scheme != 'https' or url.netloc != 'api.github.com' or url.path != '/search/repositories' or url.fragment or \
            len(pairs) != len(query) or set(query) != {'q', 'per_page', 'page'} or query['q'] != QUERY or \
            query['per_page'] != '100' or not re.fullmatch(r'[1-9][0-9]?', query['page']) or int(query['page']) > PAGE_MAX:
        raise DiscoveryError('pagination_url')
    return value


def next_link(value):
    if value is None: return None
    if len(value) > 8192: raise DiscoveryError('pagination_header')
    found = []
    for part in value.split(','):
        match = re.fullmatch(r'\s*<([^<>]+)>;\s*rel="(next|prev|first|last)"\s*', part)
        if not match: raise DiscoveryError('pagination_header')
        if match[2] == 'next': found.append(api_url(match[1]))
    if len(found) > 1: raise DiscoveryError('pagination_header')
    return found[0] if found else None


def json_document(raw):
    if not isinstance(raw, bytes) or len(raw) > BODY_MAX: raise DiscoveryError('response_size')
    def pairs(items):
        value = {}
        for key, item in items:
            if key in value: raise DiscoveryError('response_json')
            value[key] = item
        return value
    def finite(text):
        value = float(text)
        if not math.isfinite(value): raise DiscoveryError('response_json')
        return value
    try:
        return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs, parse_float=finite,
                          parse_constant=lambda _: (_ for _ in ()).throw(DiscoveryError('response_json')))
    except (ValueError, UnicodeError, RecursionError): raise DiscoveryError('response_json') from None


def project(raw):
    value = json_document(raw)
    if not isinstance(value, dict) or type(value.get('total_count')) is not int or not 0 <= value['total_count'] <= 1_000_000 or \
            type(value.get('incomplete_results')) is not bool or not isinstance(value.get('items'), list) or len(value['items']) > 100:
        raise DiscoveryError('response_shape')
    items = []
    for item in value['items']:
        if not isinstance(item, dict) or type(item.get('id')) is not int or not 1 <= item['id'] <= 9007199254740991 or \
                not isinstance(item.get('full_name'), str) or not sources.REPO.fullmatch(item['full_name']):
            raise DiscoveryError('repository_identity')
        owner = item.get('owner', {}).get('login') if isinstance(item.get('owner'), dict) else None
        if not isinstance(owner, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,38}', owner) or \
                owner.casefold() != item['full_name'].split('/')[0].casefold(): raise DiscoveryError('repository_identity')
        # Authentication must never expand this public-only query into private repos.
        if type(item.get('private')) is not bool:
            raise DiscoveryError('repository_flags')
        if item['private']: continue
        # Fork/archive/disabled metadata is not an admission policy. Every public
        # topic result goes through the same bare fetch, validation and ID rules.
        topics = item.get('topics')
        if topics is not None and (not isinstance(topics, list) or len(topics) > 100 or
                                   not all(isinstance(x, str) and len(x) <= 64 for x in topics) or 'thinkos-plugin' not in topics):
            raise DiscoveryError('repository_topics')
        items.append({'repo': item['full_name'], 'owner': owner, 'repository_id': item['id']})
    return {'total': value['total_count'], 'incomplete': value['incomplete_results'], 'items': items, 'raw_count': len(value['items'])}


def header_text(value, maximum=256):
    if not isinstance(value, str) or not 1 <= len(value) <= maximum or any(ord(c) < 32 or ord(c) > 126 for c in value):
        raise DiscoveryError('response_header')
    return value


def curl_get(url, headers, remaining):
    """No credential in argv, inherited environment, cache, report or exception.
    curl plus parent timeout bounds DNS/connect/TLS/headers/body as one process.
    No redirect, user curlrc, response execution or HTTP fallback.
    """
    api_url(url)
    with tempfile.TemporaryDirectory(prefix='thinkos-topic-http-') as name:
        area = Path(name)
        body, head = area/'body', area/'headers'
        config = 'url = ' + json.dumps(url) + '\n'
        for key, value in headers.items():
            config += 'header = ' + json.dumps(key + ': ' + header_text(value, 512)) + '\n'
        secret = headers.get('Authorization', '').removeprefix('Bearer ')
        environment = {key: value for key, value in os.environ.items()
                       if not any(word in key.upper() for word in ('TOKEN', 'PASSWORD', 'SECRET')) and not (secret and secret in value)}
        def limits(): resource.setrlimit(resource.RLIMIT_FSIZE, (BODY_MAX, BODY_MAX))
        try:
            done = subprocess.run(['curl', '--disable', '--config', '-', '--silent', '--show-error', '--request', 'GET',
                '--proto', '=https', '--proto-redir', '=https', '--max-redirs', '0', '--connect-timeout', '5',
                '--max-time', str(min(15, remaining)), '--max-filesize', str(BODY_MAX), '--dump-header', str(head), '--output', str(body)],
                input=config.encode(), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=environment,
                preexec_fn=limits, timeout=min(18, remaining))
        except (OSError, subprocess.SubprocessError): raise DiscoveryError('github_transport') from None
        if done.returncode != 0: raise DiscoveryError('github_transport')
        raw = regular(head, 65536).read_bytes()
        # HTTPS proxies/100 Continue may prepend header blocks; retain only the final response.
        blocks = [part for part in raw.replace(b'\r\n', b'\n').split(b'\n\n') if part.startswith(b'HTTP/')]
        if not blocks: raise DiscoveryError('response_header')
        lines = blocks[-1].decode('iso-8859-1').splitlines()
        match = re.fullmatch(r'HTTP/[0-9.]+ ([0-9]{3})(?: .*)?', lines[0])
        if not match: raise DiscoveryError('response_header')
        fields = {}
        for line in lines[1:]:
            if ':' not in line: raise DiscoveryError('response_header')
            key, value = line.split(':', 1); key = key.lower(); value = value.strip()
            if key in ('etag', 'link', 'retry-after', 'x-ratelimit-remaining', 'x-ratelimit-reset'):
                if key in fields: raise DiscoveryError('response_header')
                fields[key] = header_text(value, 8192 if key == 'link' else 256)
        return int(match[1]), fields, regular(body, BODY_MAX).read_bytes() if body.exists() else b''


def atomic(path, value):
    path = no_symlink_path(path)
    temporary = path.with_name(path.name + '.new')
    exclusive_write(temporary, json_bytes(value), private=True)
    try:
        os.replace(temporary, path)
    finally:
        if temporary.exists(): temporary.unlink()


def discover(cache, max_repositories=32, token=None, transport=curl_get, sleep=time.sleep, clock=time.monotonic):
    if type(max_repositories) is not int or not 1 <= max_repositories <= REPO_MAX: raise DiscoveryError('repository_limit')
    if token is not None and (not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_]{20,255}', token)):
        raise DiscoveryError('authentication_format')
    cache = no_symlink_path(Path(cache)); cache.mkdir(mode=0o700, exist_ok=True)
    info = cache.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise DiscoveryError('cache_directory')
    report = {'schema': 1, 'query': QUERY, 'api_version': API_VERSION, 'complete': False, 'repositories': [],
              'pages': 0, 'conditional_hits': 0, 'signed': False, 'published': False, 'remote_actions_performed': False}
    lock = no_symlink_path(cache/'.lock')
    if lock.exists(): regular(lock, 0)
    with lock.open('a') as handle:
        os.chmod(lock, 0o600)
        try: fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError: raise DiscoveryError('cache_busy') from None
        try:
            url, seen_urls, seen_ids, seen_names, expected = FIRST, set(), set(), set(), None
            covered = 0
            cooldown = cache/'not-before.json'
            if cooldown.exists():
                prior = sources.read_json(regular(cooldown, 1024).read_bytes(), 1024)
                if set(prior) != {'schema', 'epoch'} or prior['schema'] != 1 or type(prior['epoch']) is not int or not 0 <= prior['epoch'] <= 999999999999:
                    raise DiscoveryError('cache_invalid')
                if time.time() < prior['epoch']: raise DiscoveryError('github_rate_limited', {'epoch': prior['epoch']})
            deadline = clock() + 60
            while url:
                if report['pages'] >= PAGE_MAX or url in seen_urls: raise DiscoveryError('pagination_limit')
                api_url(url); seen_urls.add(url)
                if report['pages']:
                    pause = 2.1 if token else 6.1
                    if clock() + pause >= deadline: raise DiscoveryError('discovery_timeout')
                    sleep(pause)
                remaining = deadline - clock()
                if remaining <= 0: raise DiscoveryError('discovery_timeout')
                filename = cache/(hashlib.sha256(url.encode()).hexdigest()+'.json')
                cached = sources.read_json(regular(filename, BODY_MAX).read_bytes(), BODY_MAX) if filename.exists() else None
                headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'ThinkOS-Registry-Discovery/1', 'X-GitHub-Api-Version': API_VERSION}
                if token: headers['Authorization'] = 'Bearer ' + token
                if cached:
                    if set(cached) != {'schema', 'url', 'etag', 'page', 'next'} or cached['schema'] != 1 or cached['url'] != url:
                        raise DiscoveryError('cache_invalid')
                    if cached['etag']: headers['If-None-Match'] = header_text(cached['etag'])
                status, response, body = transport(url, headers, remaining)
                report['pages'] += 1
                if status in (403, 429):
                    retry = response.get('retry-after')
                    reset = response.get('x-ratelimit-reset') if response.get('x-ratelimit-remaining') == '0' else None
                    wait = {'seconds': int(retry)} if retry and re.fullmatch(r'[0-9]{1,9}', retry) else \
                           {'epoch': int(reset)} if reset and re.fullmatch(r'[0-9]{1,12}', reset) else {'seconds': 60}
                    not_before = wait.get('epoch', int(time.time()) + wait.get('seconds', 60))
                    atomic(cooldown, {'schema': 1, 'epoch': max(int(time.time()) + 1, not_before)})
                    raise DiscoveryError('github_rate_limited', wait)
                if status == 304:
                    if not cached or not cached['etag']: raise DiscoveryError('cache_miss_304')
                    page, following = cached['page'], cached['next']; report['conditional_hits'] += 1
                    # Revalidate the sanitized projection before it can affect a plan.
                    if not isinstance(page, dict) or set(page) != {'total', 'incomplete', 'items', 'raw_count'} or \
                            type(page['raw_count']) is not int or not len(page['items']) <= page['raw_count'] <= 100:
                        raise DiscoveryError('cache_invalid')
                    reconstructed = {'total_count': page['total'], 'incomplete_results': page['incomplete'], 'items': [
                        {'id': row['repository_id'], 'full_name': row['repo'], 'owner': {'login': row['owner']},
                         'private': False} for row in page['items']]}
                    page = dict(project(json_bytes(reconstructed)), raw_count=page['raw_count'])
                    if following: api_url(following)
                elif status == 200:
                    page, following = project(body), next_link(response.get('link'))
                    etag = response.get('etag')
                    if etag: header_text(etag)
                    atomic(filename, {'schema': 1, 'url': url, 'etag': etag, 'page': page, 'next': following})
                else: raise DiscoveryError('github_http_' + str(status) if type(status) is int and 100 <= status <= 599 else 'github_http')
                if page['incomplete']: raise DiscoveryError('search_incomplete')
                if page['total'] > max_repositories or page['total'] > 1000: raise DiscoveryError('repository_limit')
                if expected is not None and page['total'] != expected: raise DiscoveryError('search_changed')
                expected = page['total']
                covered += page['raw_count']
                for row in page['items']:
                    if row['repository_id'] in seen_ids or row['repo'].casefold() in seen_names: raise DiscoveryError('search_changed')
                    seen_ids.add(row['repository_id']); seen_names.add(row['repo'].casefold()); report['repositories'].append(row)
                url = following
            # Filtered/shifted pages are not claimed to be a complete discoverable set.
            if covered != expected: raise DiscoveryError('search_changed')
            report['repositories'].sort(key=lambda row: row['repo'].casefold()); report['complete'] = True
        except (DiscoveryError, ValueError, OSError, KeyError, TypeError, RecursionError) as error:
            report['error'] = error.code if isinstance(error, DiscoveryError) else 'discovery_data'
            if isinstance(error, DiscoveryError) and error.retry_after: report['retry_after'] = error.retry_after
            report['repositories'] = [] # Never import a partial/stale search on errors.
    return report


def community_plan(report, work, previous, ledger, registered, caches, compiler, fetch=sources.fetch):
    """Default root manifest/store + vSemver. Registered paths stay on their old flow.
    Every valid automatic candidate is community; owner signs only the final batch.
    """
    import registry as catalog
    if report.get('complete') is not True: raise DiscoveryError('discovery_incomplete')
    rows = report.get('repositories')
    if not isinstance(rows, list) or len(rows) > REPO_MAX: raise DiscoveryError('repository_limit')
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get('repo'), str) or not sources.REPO.fullmatch(row['repo']) or \
                not isinstance(row.get('owner'), str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,38}', row['owner']) or \
                row['repo'].split('/')[0].casefold() != row['owner'].casefold(): raise DiscoveryError('repository_identity')
    if len({row['repo'].casefold() for row in rows}) != len(rows): raise DiscoveryError('ambiguous-repository')
    work = no_symlink_path(Path(work)); work.mkdir()
    hooks = work/'empty-hooks'; hooks.mkdir()
    registered_repos = {record['repo'].casefold() for _, record in registered}
    occupied = {record['id'] for _, record in registered}
    historical = {item['id']: item for item in previous['plugins']}
    results, candidates, claims = [], [], {}
    deadline = time.monotonic() + 600
    for row in report['repositories']:
        repo = row['repo']; outcome = {'repo': repo, 'admitted': False}
        results.append(outcome)
        if repo.casefold() in registered_repos: outcome['reason'] = 'registered-source'; continue
        if time.monotonic() >= deadline: raise DiscoveryError('validation_timeout')
        try:
            path = work/('repo-' + hashlib.sha256(repo.encode()).hexdigest())
            fetch({'repo': repo}, path); caches[repo] = path
            reader = sources.BareReader(path, hooks)
            tags = reader.git('for-each-ref', '--format=%(refname:strip=2)', 'refs/tags', limit=65536).decode('utf-8').splitlines()
            if len(tags) > 256: raise DiscoveryError('tag_limit')
            tags = [tag for tag in tags if len(tag) <= 80 and re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', tag)]
            tags.sort(key=lambda tag: catalog.semver(tag[1:]), reverse=True)
            if not tags: raise DiscoveryError('no-version-tag')
            known = [item['id'] for item in previous['plugins'] if item['tier'] == 'community' and item['repo'].casefold() == repo.casefold()]
            if len(known) > 1: raise DiscoveryError('ambiguous-repository')
            identifier = known[0] if known else None
            if identifier is None:
                ids = set()
                for tag in tags[:3]:
                    commit = reader.commit(tag)
                    entries = reader.tree(commit, '')
                    if 'manifest.json' not in entries or 'store.json' not in entries: raise DiscoveryError('root-plugin-required')
                    manifest = json_document(reader.blob(entries['manifest.json'], sources.packages.MAX_JSON_SCRIPT_BYTES))
                    sources.packages.validate_manifest(manifest)
                    ids.add(manifest['id'])
                if len(ids) != 1: raise DiscoveryError('ambiguous-id')
                identifier = ids.pop()
            if identifier in sources.RESERVED or identifier in occupied: raise DiscoveryError('occupied-id')
            old = historical.get(identifier)
            if old and (old['tier'] != 'community' or old['repo'].casefold() != repo.casefold()): raise DiscoveryError('occupied-id')
            claims[identifier] = claims.get(identifier, 0) + 1
            # registration() derives id from its filename; use an isolated per-repo subdir.
            registrations = work/('registration-'+hashlib.sha256(repo.encode()).hexdigest()); registrations.mkdir()
            registration = registrations/(identifier+'.json')
            exclusive_write(registration, json_bytes({'repo': repo, 'path': '', 'owners': [row['owner']]}))
            record = sources.registration(registration); record['tier'] = 'community'
            selected = set(tags[:3])
            for tag, expected in ledger['tags'].get(identifier, {}).items():
                if time.monotonic() >= deadline: raise DiscoveryError('validation_timeout')
                if tag in tags and reader.commit(tag) != expected: selected.add(tag)
            plan, validated, moved = [], 0, 0
            for index, tag in enumerate(sorted(selected)):
                if time.monotonic() >= deadline: raise DiscoveryError('validation_timeout')
                expected = ledger['tags'].get(identifier, {}).get(tag)
                if not expected or expected == reader.commit(tag):
                    staging = path.parent/('validation-'+hashlib.sha256((repo+tag).encode()).hexdigest()); staging.mkdir()
                    sources.validate_source(record, reader, tag, compiler, staging)
                    validated += 1
                else:
                    moved += 1
                # Moved historical tags are intentionally passed to existing build(),
                # which retains their old bytes and emits its ordinary issue request.
                plan.append({'registration': str(registration), 'git_dir': str(path), 'tag': tag, 'kind': 'topic-community'})
            candidates.append((identifier, repo, plan, outcome, validated, moved))
        except (ValueError, OSError, KeyError, TypeError, UnicodeError, RecursionError) as error:
            outcome['reason'] = error.code if isinstance(error, DiscoveryError) else 'validation-failed'
    plan = []
    for identifier, _, entries, outcome, validated, moved in candidates:
        if claims[identifier] != 1: outcome['reason'] = 'ambiguous-id'; continue
        outcome.update(id=identifier, admitted=True, tier='community', planned_tags=len(entries),
                       validated_tags=validated, moved_tag_requests=moved); plan.extend(entries)
    return plan, {'schema': 1, 'complete': True, 'results': results, 'signed': False, 'published': False, 'remote_actions_performed': False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True); parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--max-repositories', type=int, choices=range(1, REPO_MAX+1), default=32)
    parser.add_argument('--token-env', help='Environment variable NAME only; omit for public unauthenticated requests')
    args = parser.parse_args(argv)
    if args.token_env and not re.fullmatch(r'[A-Z][A-Z0-9_]{0,63}', args.token_env): raise DiscoveryError('authentication_environment')
    report = discover(args.cache, args.max_repositories, os.environ.get(args.token_env) if args.token_env else None)
    exclusive_write(no_symlink_path(args.output), json_bytes(report))
    print(json.dumps({'complete': report['complete'], 'repositories': len(report['repositories']), 'error': report.get('error')}))
    return 0 if report['complete'] else 1


if __name__ == '__main__':
    try: sys.exit(main())
    except (ValueError, OSError, KeyError, TypeError):
        print('Community discovery stopped: invalid input or unavailable public API', file=sys.stderr); sys.exit(1)
