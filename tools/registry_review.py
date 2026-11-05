#!/usr/bin/env python3
"""MIT. Trusted-main review PRs/issues only; never sign, merge or publish a registry."""
import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import stat
import sys
import time
from urllib.parse import quote, urlencode

ID = re.compile(r'[a-z][a-z0-9-]{0,63}')
REPO = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}')
VERSION = r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[A-Za-z0-9.-]+)?'
TAG = re.compile(r'(?:[a-z][a-z0-9-]{0,63}/)?v(' + VERSION + r')')
PERMISSION = re.compile(r'[a-z][a-z0-9_.]{0,63}')
APPROVAL_PATH = 'approvals.json'  # Registry main branch root, not the ThinkOS monorepo path.
BOT = 'github-actions[bot]'
LIMIT = 1024 * 1024


class Stopped(ValueError):
    pass


def require(condition):
    if not condition:
        raise Stopped('Review boundary refused')


def decode(raw, limit=LIMIT):
    require(len(raw) <= limit)
    def pairs(items):
        value = {}
        for key, item in items:
            require(key not in value)
            value[key] = item
        return value
    def invalid(_):
        raise Stopped('Invalid number')
    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs, parse_float=invalid, parse_constant=invalid)


def encode(value):
    return (json.dumps(value, ensure_ascii=True, sort_keys=True, indent=2) + '\n').encode('ascii')


def text(value, pattern):
    require(isinstance(value, str) and pattern.fullmatch(value) is not None)
    return value


def sha(value, length=40):
    return text(value, re.compile('[a-f0-9]{' + str(length) + '}'))


def permissions(value):
    require(isinstance(value, list) and len(value) <= 32)
    for item in value:
        text(item, PERMISSION)
        require(not item.startswith('system.'))
    require(value == sorted(set(value)))
    return value


def approval(value):
    require(isinstance(value, dict) and set(value) == {'id', 'version', 'commit', 'digest', 'permissions'})
    text(value['id'], ID)
    require(isinstance(value['version'], str) and len(value['version']) <= 64)
    text(value['version'], re.compile(VERSION))
    sha(value['commit']); sha(value['digest'], 64); permissions(value['permissions'])
    return value


def requests(value):
    require(isinstance(value, dict) and set(value) == {'schema', 'requests', 'remote_actions_performed'})
    require(type(value['schema']) is int and value['schema'] == 1 and value['remote_actions_performed'] is False)
    rows = value['requests']
    require(isinstance(rows, list) and len(rows) <= 256)
    seen = set()
    for row in rows:
        require(isinstance(row, dict))
        kind = row.get('kind')
        common = {'kind', 'requested_action', 'id', 'tag', 'remote_action_performed'}
        extra = {'commit', 'digest', 'permissions', 'added'} if kind == 'permissions_increased' else (
            {'expected_commit', 'observed_commit'} if kind == 'tag_moved' else set())
        require(kind in {'permissions_increased', 'tag_moved', 'published_content_changed'} and set(row) == common | extra)
        text(row['id'], ID)
        tag = text(row['tag'], TAG)
        require(len(tag) <= 160 and len(TAG.fullmatch(tag)[1]) <= 64 and row['remote_action_performed'] is False)
        require(row['requested_action'] == ('pull_request' if kind == 'permissions_increased' else 'issue'))
        if kind == 'permissions_increased':
            permissions(row['permissions']); permissions(row['added'])
            require(row['added'] and set(row['added']) <= set(row['permissions']))
            approval(approval_for(row))
        elif kind == 'tag_moved':
            sha(row['expected_commit']); sha(row['observed_commit'])
            require(row['expected_commit'] != row['observed_commit'])
        key = (kind, row['id'], TAG.fullmatch(tag)[1])
        require(key not in seen); seen.add(key)
    return rows


def approval_for(row):
    return {'id': row['id'], 'version': TAG.fullmatch(row['tag'])[1], 'commit': row['commit'],
            'digest': row['digest'], 'permissions': row['permissions']}


def read_requests(path):
    absolute = path.absolute()
    require(absolute == absolute.resolve() and stat.S_ISREG(absolute.stat().st_mode))
    with absolute.open('rb') as stream:
        return requests(decode(stream.read(LIMIT + 1)))


def trusted(environment, repository, commit):
    text(repository, REPO); sha(commit)
    require(environment.get('GITHUB_ACTIONS') == 'true')
    require(environment.get('GITHUB_EVENT_NAME') in {'schedule', 'workflow_dispatch'})
    require(environment.get('GITHUB_REF') == 'refs/heads/main')
    require(environment.get('GITHUB_REPOSITORY') == repository and environment.get('GITHUB_SHA') == commit)
    require(environment.get('GITHUB_WORKFLOW_REF') == repository + '/.github/workflows/scan-tags.yml@refs/heads/main')
    require(environment.get('GITHUB_SERVER_URL') == 'https://github.com')
    require(environment.get('GITHUB_API_URL') == 'https://api.github.com')
    token = environment.get('GITHUB_TOKEN', '')
    require(1 <= len(token) <= 4096 and all(33 <= ord(char) <= 126 for char in token))
    return token


class GitHubTransport:
    """Production has no URL input, proxy, redirect handling or credential in its URL."""
    def request(self, method, path, headers, body, timeout):
        connection = http.client.HTTPSConnection('api.github.com', timeout=timeout)
        try:
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            return response.status, response.read(LIMIT + 1)
        finally:
            connection.close()


class API:
    def __init__(self, repository, token, transport):
        self.prefix = '/repos/' + repository
        self.repository = repository
        self.transport = transport
        self.headers = {'Accept': 'application/vnd.github+json', 'Authorization': 'Bearer ' + token,
                        'X-GitHub-Api-Version': '2026-03-10', 'User-Agent': 'ThinkOS-registry-review',
                        'Content-Type': 'application/json'}
        self.deadline = time.monotonic() + 600
        self.calls = 0

    def call(self, method, path, value=None, missing=False):
        require(method in {'GET', 'POST', 'PATCH', 'PUT'} and path.startswith(self.prefix + '/'))
        require(all(33 <= ord(char) <= 126 for char in path) and '://' not in path)
        self.calls += 1
        remaining = self.deadline - time.monotonic()
        require(self.calls <= 4096 and remaining > 0)
        status, raw = self.transport.request(method, path, self.headers, None if value is None else encode(value), min(15, remaining))
        require(len(raw) <= LIMIT)
        if status == 404 and missing:
            return None
        # In particular, no redirect is followed and no API error body is logged.
        require(status in {200, 201})
        return decode(raw)

    def pages(self, suffix):
        result = []
        for page in range(1, 21):
            rows = self.call('GET', self.prefix + suffix + ('&' if '?' in suffix else '?') + urlencode({'per_page': 100, 'page': page}))
            require(isinstance(rows, list) and len(rows) <= 100)
            result.extend(rows)
            if len(rows) < 100:
                return result
        raise Stopped('Review history exceeds bound')

    def head(self, branch, missing=False):
        value = self.call('GET', self.prefix + '/git/ref/heads/' + quote(branch, safe='/'), missing=missing)
        if value is None:
            return None
        require(value['ref'] == 'refs/heads/' + branch and value['object']['type'] == 'commit')
        return sha(value['object']['sha'])

    def approvals(self, ref):
        value = self.call('GET', self.prefix + '/contents/' + APPROVAL_PATH + '?' + urlencode({'ref': ref}))
        require(value['type'] == 'file' and value['path'] == APPROVAL_PATH and value['encoding'] == 'base64')
        require(type(value['size']) is int and 0 <= value['size'] <= 65536)
        sha(value['sha'])
        raw = base64.b64decode(value['content'].replace('\n', ''), validate=True)
        require(len(raw) == value['size'])
        rows = decode(raw, 65536)
        require(isinstance(rows, list) and len(rows) <= 256)
        for row in rows:
            approval(row)
        require(len({json.dumps(row, sort_keys=True) for row in rows}) == len(rows))
        return rows, value['sha']


def marker(key):
    return '<!-- thinkos-registry-review:v1:' + hashlib.sha256(encode(key)).hexdigest() + ' -->'


def owned(value, stamp):
    require(value.get('user', {}).get('login') == BOT and value.get('user', {}).get('type') == 'Bot')
    require(isinstance(value.get('body'), str) and value['body'].startswith(stamp + '\n'))
    require(type(value.get('number')) is int and value['number'] > 0)


def review_text(target):
    body = marker(target) + '\nReview this exact permission grant before merging. No registry was signed or published.\n\n' + \
        '```json\n' + encode(target).decode('ascii') + '```\n'
    return 'Registry permission review: ' + target['id'] + ' ' + target['version'], body


def issue_key(row):
    return {'kind': row['kind'], 'id': row['id'], 'version': TAG.fullmatch(row['tag'])[1]}


def issue_text(row):
    key = issue_key(row)
    body = marker(key) + '\nPublished content was retained unchanged. Investigate the source integrity mismatch.\n\n```json\n' + encode(row).decode('ascii') + '```\n'
    return 'Registry integrity: ' + row['id'] + ' ' + key['version'] + ' ' + row['kind'], body


def only_approval_changes(api, commit, branch):
    comparison = api.call('GET', api.prefix + '/compare/' + commit + '...' + branch + '?per_page=100')
    require(type(comparison['total_commits']) is int and 0 <= comparison['total_commits'] <= 100)
    files = comparison['files']
    require(isinstance(files, list) and len(files) <= 1)
    require(all(item.get('filename') == APPROVAL_PATH and item.get('previous_filename', APPROVAL_PATH) == APPROVAL_PATH for item in files))


def update_review(api, row, commit, base_approvals):
    target = approval_for(row)
    if target in base_approvals:
        return False
    stamp = marker(target)
    title, body = review_text(target)
    branch = 'codex/registry-review-' + hashlib.sha256(encode(target)).hexdigest()[:24]
    query = urlencode({'state': 'all', 'head': api.repository.split('/')[0] + ':' + branch, 'base': 'main'})
    candidates = api.pages('/pulls?' + query)
    require(len(candidates) <= 1)
    previous = candidates[0] if candidates else None
    if previous:
        owned(previous, stamp)
        require(previous['head']['ref'] == branch and previous['head']['repo']['full_name'] == api.repository and previous['base']['ref'] == 'main')
        # A maintainer's rejected or merged review is never silently reopened.
        if previous['state'] == 'closed':
            return False
        require(previous['state'] == 'open')
        # An owner append/edit is not machine-owned text. Preserve it and stop
        # before touching the branch, rather than replacing a maintainer note.
        require(previous.get('title') == title and previous['body'] == body)
    if api.head(branch, missing=True) is None:
        require(previous is None and api.head('main') == commit)
        api.call('POST', api.prefix + '/git/refs', {'ref': 'refs/heads/' + branch, 'sha': commit})
    only_approval_changes(api, commit, branch)
    current, current_sha = api.approvals(branch)
    desired = base_approvals + [target]
    require(all(item in desired for item in current))
    desired.sort(key=lambda item: (item['id'], item['version'], item['commit'], item['digest']))
    require(len(encode(desired)) <= 65536)
    if current != desired:
        require(api.head('main') == commit)
        api.call('PUT', api.prefix + '/contents/' + APPROVAL_PATH, {'branch': branch, 'sha': current_sha,
                 'message': 'Review registry permissions for ' + row['id'], 'content': base64.b64encode(encode(desired)).decode('ascii')})
    require(api.head('main') == commit)
    # Repeat after the file commit, immediately before making a new PR visible.
    # This is another snapshot, not an OS/GitHub-wide transaction guarantee.
    only_approval_changes(api, commit, branch)
    if previous is None:
        api.call('POST', api.prefix + '/pulls', {'title': title, 'body': body, 'head': branch, 'base': 'main', 'maintainer_can_modify': False})
    return True


def update_issue(api, row, issues):
    key = issue_key(row)
    stamp = marker(key)
    title, body = issue_text(row)
    found = [item for item in issues if not item.get('pull_request') and
             (item.get('title') == title or isinstance(item.get('body'), str) and stamp in item['body'])]
    require(len(found) <= 1)
    if found:
        owned(found[0], stamp)
        prefix = stamp + '\nPublished content was retained unchanged. Investigate the source integrity mismatch.\n\n```json\n'
        old_body = found[0]['body']
        require(old_body.startswith(prefix) and old_body.endswith('```\n'))
        previous_row = decode(old_body[len(prefix):-4].encode('ascii'))
        requests({'schema': 1, 'requests': [previous_row], 'remote_actions_performed': False})
        require(issue_key(previous_row) == key)
        old_title, canonical = issue_text(previous_row)
        require(found[0].get('title') == old_title and old_body == canonical)
        if found[0]['body'] != body or found[0].get('title') != title:
            api.call('PATCH', api.prefix + '/issues/' + str(found[0]['number']), {'title': title, 'body': body})
    else:
        created = api.call('POST', api.prefix + '/issues', {'title': title, 'body': body})
        issues.append(created)


def main(argv=None, *, _transport=None, _environment=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--requests', type=Path, required=True)
    args = parser.parse_args(argv)
    environment = os.environ if _environment is None else _environment
    token = trusted(environment, args.repository, args.commit)
    rows = read_requests(args.requests)  # Validate every request before any HTTP/write.
    api = API(args.repository, token, GitHubTransport() if _transport is None else _transport)
    require(api.head('main') == args.commit)
    base_approvals, _ = api.approvals(args.commit)
    issues = api.pages('/issues?state=all') if any(row['kind'] != 'permissions_increased' for row in rows) else []
    prs = 0; integrity = 0
    for row in rows:
        require(api.head('main') == args.commit)
        if row['kind'] == 'permissions_increased':
            prs += int(update_review(api, row, args.commit, base_approvals))
        else:
            update_issue(api, row, issues); integrity += 1
    print(json.dumps({'approval_reviews': prs, 'integrity_reviews': integrity, 'signed': False, 'published': False}))


def cli(argv=None, *, _transport=None, _environment=None):
    try:
        main(argv, _transport=_transport, _environment=_environment)
        return 0
    except (ValueError, OSError, KeyError, TypeError, http.client.HTTPException):
        print('Registry review stopped; no credentials or remote response bodies are logged', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(cli())
