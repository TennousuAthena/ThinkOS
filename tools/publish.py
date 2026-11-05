"""MIT. Owner-run publication of a release prepared by tools/release.py to GitHub and CNB.

Nothing here signs anything: it uploads the exact files that `release.sh prepare` already built and signed, reads each
one back anonymously (the way a device will fetch it) and only then replaces the channel catalog. It never creates a
repository, changes visibility, pushes code or creates the version tag; those stay owner actions. Tokens come from the
environment (CNB) or the owner's `gh` login (GitHub), never from arguments, and are only ever sent to the API host.

Default is a dry run that prints the plan and touches no network."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import quote, urlsplit
import urllib.error
import urllib.request

from release_tools import digest, no_symlink_path, regular, require_owner_host

CNB_TOKEN_ENV = 'THINKOS_CNB_TOKEN'
NAME = re.compile(r'[A-Za-z0-9][A-Za-z0-9._+-]{0,127}')
SUFFIX = re.compile(r'[A-Za-z0-9._-]{1,20}')
REPO = re.compile(r'[A-Za-z0-9._-]{1,100}(/[A-Za-z0-9._-]{1,100}){1,3}')
MAX_FILE = 1024 * 1024 * 1024
LARGE = 64 * 1024 * 1024  # above this a full read-back is skipped: length plus first and last MiB are compared instead
SAMPLE = 1024 * 1024
LOOPBACK = ('127.0.0.1', 'localhost', '::1')


class PublishError(Exception):
    """Messages carry only host, status and a short server reason: never headers, tokens or request bodies."""


def https_or_loopback(url):
    parts = urlsplit(url)
    if parts.username or parts.password or not parts.hostname:
        raise PublishError('Refusing a URL with credentials or without a host')
    if parts.scheme != 'https' and not (parts.scheme == 'http' and parts.hostname in LOOPBACK):
        raise PublishError('Only HTTPS (or loopback in tests) is accepted for ' + parts.hostname)
    return url


def short(raw):
    return re.sub(r'[^\x20-\x7e]', '?', raw.decode('utf-8', 'replace'))[:160]


# --------------------------------------------------------------------------- prepared release

class Prepared:
    def __init__(self, directory):
        self.root = no_symlink_path(Path(directory))
        report = json.loads(regular(self.root / 'release-preparation.json', 1024 * 1024).read_text('utf-8'))
        if report.get('stage') != 'prepared' or report.get('published') is not False:
            raise PublishError('Not a freshly prepared, unpublished release directory')
        self.report = report
        self.version = report['version']
        if not re.fullmatch(r'[0-9]+(\.[0-9]+){1,3}', self.version):
            raise PublishError('Unexpected version')
        self.sums = self._sums()
        catalog = self.root / report['catalog']['path']
        self.catalog_name = catalog.name
        self.catalog_raw = regular(catalog, 1024 * 1024).read_bytes()
        self.signature_name = catalog.name + '.sig'
        self.signature_raw = regular(self.root / self.signature_name, 1024).read_bytes()
        self.catalog = json.loads(self.catalog_raw.decode('utf-8'))
        self.channel = self.catalog['channel']
        if self.catalog_name != self.channel + '.json' or self.channel not in ('stable', 'beta'):
            raise PublishError('Catalog file name must be <channel>.json')
        if hashlib.sha256(self.catalog_raw).hexdigest() != report['catalog']['sha256']:
            raise PublishError('Catalog differs from the preparation report')
        self.files = self._versioned_files()
        notes = self.catalog['release'].get('notes', {})
        self.notes = (notes.get('en') or notes.get('zh-Hans') or next(iter(notes.values()), ''))[:2048]

    def _sums(self):
        result = {}
        for line in regular(self.root / 'SHA256SUMS', 1024 * 1024).read_text('ascii').splitlines():
            match = re.fullmatch(r'([a-f0-9]{64})  (' + NAME.pattern + ')', line)
            if not match or match[2] in result:
                raise PublishError('Malformed SHA256SUMS')
            result[match[2]] = match[1]
        for name, expected in result.items():
            if digest(self.root / name) != expected:
                raise PublishError('File differs from SHA256SUMS: ' + name)
        return result

    def _versioned_files(self):
        names = {entry['path'] for entry in self.report.get('attachments', [])}
        for module in self.report.get('modules', []):
            for url in module['urls']:
                names.add(url.rsplit('/', 1)[1])
            for url in module['manifest_urls']:
                manifest = url.rsplit('/', 1)[1]
                names.update((manifest, manifest + '.sig'))
        names.update(('SHA256SUMS', 'release-preparation.json'))
        for name in names:
            if not NAME.fullmatch(name):
                raise PublishError('Unsafe asset name: ' + name)
            if name not in self.sums and name != 'SHA256SUMS':
                raise PublishError('Asset not covered by SHA256SUMS: ' + name)
            regular(self.root / name, MAX_FILE)
        apk = 'thinkos-' + self.version + '.apk'
        if apk not in names:
            raise PublishError('The release APK is missing from the prepared attachments')
        return sorted(names)

    def expected(self, name):
        path = self.root / name
        return path.stat().st_size, digest(path)


# --------------------------------------------------------------------------- sites

class Site:
    name = ''

    def asset_url(self, tag, name):
        raise NotImplementedError

    def ensure_release(self, tag, title, notes, prerelease, target, rolling):
        raise NotImplementedError

    def upload(self, release, path):
        raise NotImplementedError

    def delete_release(self, tag):
        raise NotImplementedError


class CnbSite(Site):
    name = 'cnb'

    def __init__(self, repo, token, api='https://api.cnb.cool', web='https://cnb.cool', sleep=time.sleep):
        if not REPO.fullmatch(repo):
            raise PublishError('Invalid CNB repository name')
        self.repo, self.token, self.sleep = repo, token, sleep
        self.api = https_or_loopback(api).rstrip('/')
        self.web = https_or_loopback(web).rstrip('/')

    def asset_url(self, tag, name):
        return '%s/%s/-/releases/download/%s/%s' % (self.web, self.repo, quote(tag, safe=''), quote(name, safe=''))

    def _request(self, method, url, body=None, content_type='application/json', bearer=True):
        if bearer and not (url == self.api or url.startswith(self.api + '/')):
            raise PublishError('Refusing to send the CNB token to a host other than the API host')
        data = body if isinstance(body, (bytes, type(None))) else json.dumps(body).encode('utf-8')
        headers = {'User-Agent': 'ThinkOS-publish/1', 'Accept': 'application/json'}
        if data is not None:
            headers['Content-Type'] = content_type
        if bearer:
            headers['Authorization'] = 'Bearer ' + self.token
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                raw = response.read(8 * 1024 * 1024)
                return response.status, raw
        except urllib.error.HTTPError as error:
            return error.code, error.read(4096)

    def _put(self, url, path):
        """Stream the file (the source archive is hundreds of MB). Never carries the bearer token: this is storage."""
        with path.open('rb') as stream:
            request = urllib.request.Request(url, data=stream, method='PUT', headers={
                'User-Agent': 'ThinkOS-publish/1', 'Content-Type': 'application/octet-stream', 'Content-Length': str(path.stat().st_size)})
            try:
                with urllib.request.urlopen(request, timeout=600) as response:
                    return response.status, response.read(4096)
            except urllib.error.HTTPError as error:
                return error.code, error.read(4096)

    def _json(self, method, path, body=None, expect=(200, 201, 204)):
        status, raw = self._request(method, self.api + path, body)
        if status not in expect:
            raise PublishError('CNB %s ...%s -> HTTP %d %s' % (method, path.split('?')[0][-48:], status, short(raw)))
        return json.loads(raw.decode('utf-8')) if raw.strip() else {}

    def _release_id(self, tag):
        status, raw = self._request('GET', '%s/%s/-/releases/tags/%s' % (self.api, self.repo, quote(tag, safe='')))
        if status == 404:
            return None
        if status != 200:
            raise PublishError('CNB release lookup -> HTTP %d' % status)
        return str(json.loads(raw.decode('utf-8'))['id'])

    def ensure_release(self, tag, title, notes, prerelease, target, rolling):
        existing = self._release_id(tag)
        if existing:
            return existing
        document = self._json('POST', '/%s/-/releases' % self.repo, {
            'tag_name': tag, 'target_commitish': target, 'name': title, 'body': notes,
            'prerelease': prerelease, 'draft': False, 'make_latest': 'false'})
        return str(document['id'])

    def upload(self, release, path):
        size = path.stat().st_size
        last = None
        for attempt in range(3):
            try:
                # The pre-signed address expires within seconds, so every attempt asks for a fresh one.
                target = self._json('POST', '/%s/-/releases/%s/asset-upload-url' % (self.repo, release),
                                    {'asset_name': path.name, 'size': size, 'overwrite': True, 'ttl': 0})
                upload_url, verify_url = https_or_loopback(target['upload_url']), target['verify_url']
                status, raw = self._put(upload_url, path)
                if status not in (200, 201, 204):
                    raise PublishError('CNB upload of %s -> HTTP %d %s' % (path.name, status, short(raw)))
                separator = '&' if '?' in verify_url else '?'
                status, raw = self._request('POST', verify_url + separator + 'ttl=0', b'')
                if status not in (200, 201, 204):
                    raise PublishError('CNB upload confirmation of %s -> HTTP %d %s' % (path.name, status, short(raw)))
                return
            except (PublishError, OSError) as error:
                last = error
                self.sleep(2 * (attempt + 1))
        raise PublishError('CNB upload failed for %s: %s' % (path.name, last))

    def delete_release(self, tag):
        release = self._release_id(tag)
        if release:
            self._json('DELETE', '/%s/-/releases/%s' % (self.repo, release))


class GitHubSite(Site):
    name = 'github'

    def __init__(self, repo, gh='gh', web='https://github.com'):
        if not REPO.fullmatch(repo):
            raise PublishError('Invalid GitHub repository name')
        self.repo, self.gh = repo, gh
        self.web = https_or_loopback(web).rstrip('/')

    def asset_url(self, tag, name):
        return '%s/%s/releases/download/%s/%s' % (self.web, self.repo, quote(tag, safe=''), quote(name, safe=''))

    def _gh(self, *args, check=True):
        result = subprocess.run([self.gh, *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        if check and result.returncode:
            raise PublishError('gh %s failed: %s' % (' '.join(args[:2]), short(result.stderr)))
        return result

    def ensure_release(self, tag, title, notes, prerelease, target, rolling):
        if self._gh('release', 'view', tag, '--repo', self.repo, '--json', 'tagName', check=False).returncode == 0:
            return tag
        arguments = ['release', 'create', tag, '--repo', self.repo, '--title', title, '--notes', notes, '--latest=false']
        if prerelease:
            arguments.append('--prerelease')
        # The version tag is the owner's to create and push; only the rolling channel release may create its own tag.
        arguments += ['--target', target] if rolling else ['--verify-tag']
        self._gh(*arguments)
        return tag

    def upload(self, release, path):
        self._gh('release', 'upload', release, str(path), '--repo', self.repo, '--clobber')

    def delete_release(self, tag):
        self._gh('release', 'delete', tag, '--repo', self.repo, '--yes', '--cleanup-tag', check=False)


# --------------------------------------------------------------------------- publication

def read_back(url, size, sha256, attempts=4, sleep=time.sleep, path=None):
    """Fetch like a device would: anonymous, following redirects, nothing but the URL. Very large files (the GPL source
    archive) are checked by length plus their first and last MiB against the local file instead of being downloaded again."""
    https_or_loopback(url)
    reason = 'unknown'
    for attempt in range(attempts):
        try:
            if size > LARGE and path is not None:
                sampled(url, size, path)
                return
            request = urllib.request.Request(url, headers={'User-Agent': 'ThinkOS-publish/1'})
            with urllib.request.urlopen(request, timeout=120) as response:
                hasher, total = hashlib.sha256(), 0
                for block in iter(lambda: response.read(1024 * 1024), b''):
                    total += len(block)
                    if total > size:
                        break
                    hasher.update(block)
            if total == size and hasher.hexdigest() == sha256:
                return
            reason = 'content differs'
        except (OSError, ValueError) as error:
            reason = type(error).__name__
        sleep(3 * (attempt + 1))
    raise PublishError('Read-back failed for %s (%s)' % (url.rsplit('/', 1)[-1], reason))


def sampled(url, size, path):
    head = urllib.request.Request(url, method='HEAD', headers={'User-Agent': 'ThinkOS-publish/1'})
    with urllib.request.urlopen(head, timeout=120) as response:
        if int(response.headers.get('Content-Length', -1)) != size:
            raise ValueError('length differs')
    with path.open('rb') as local:
        for offset in (0, size - SAMPLE):
            local.seek(offset)
            expected = local.read(SAMPLE)
            request = urllib.request.Request(url, headers={'User-Agent': 'ThinkOS-publish/1',
                                                           'Range': 'bytes=%d-%d' % (offset, offset + SAMPLE - 1)})
            with urllib.request.urlopen(request, timeout=120) as response:
                if response.status != 206 or response.read(SAMPLE + 1) != expected:
                    raise ValueError('sample differs')


def plan(prepared, sites, suffix):
    version_tag = 'v' + prepared.version + suffix
    rolling_tag = 'ota-' + prepared.channel + suffix
    for site in sites:
        listed = site.asset_url(version_tag, 'thinkos-' + prepared.version + '.apk')
        # A rehearsal (--tag-suffix) uploads under other tags that no device is pointed at, so only a real
        # publication has to match the URLs already signed into the catalog.
        if not suffix and listed not in prepared.catalog['release']['urls']:
            raise PublishError('%s: the signed catalog does not list %s. Re-run prepare with --url-base %s'
                               % (site.name, listed, listed.rsplit('/', 1)[0]))
    return version_tag, rolling_tag


def publish(prepared, sites, suffix='', target='main', execute=False, out=print, sleep=time.sleep):
    version_tag, rolling_tag = plan(prepared, sites, suffix)
    prerelease_version = prepared.channel == 'beta'
    for site in sites:
        out('%s: release %s (%d files), then %s <- %s + %s' % (
            site.name, version_tag, len(prepared.files), rolling_tag, prepared.catalog_name, prepared.signature_name))
    if not execute:
        out('Dry run: nothing was sent. Use --execute to publish.')
        return {}
    done = {}
    for site in sites:
        title = 'ThinkOS %s%s' % (prepared.version, suffix)
        # If the owner has not pushed the version tag, CNB creates it at exactly the prepared commit, never at a branch tip.
        release = site.ensure_release(version_tag, title, prepared.notes, prerelease_version, prepared.report['source_commit'], False)
        for name in prepared.files:
            site.upload(release, prepared.root / name)
            out('%s: uploaded %s' % (site.name, name))
        for name in prepared.files:
            size, sha256 = prepared.expected(name)
            read_back(site.asset_url(version_tag, name), size, sha256, sleep=sleep, path=prepared.root / name)
        out('%s: all %d version assets read back and match SHA256SUMS (files over %d MiB: length and ends only)' % (
            site.name, len(prepared.files), LARGE >> 20))
        # The catalog is the last thing replaced, so no device is ever pointed at a file that is not yet downloadable.
        rolling = site.ensure_release(rolling_tag, 'ThinkOS %s channel%s' % (prepared.channel, suffix),
                                      'Signed update catalog. Do not download manually.', True, target, True)
        for name in (prepared.catalog_name, prepared.signature_name):
            site.upload(rolling, prepared.root / name)
        for name, raw in ((prepared.catalog_name, prepared.catalog_raw), (prepared.signature_name, prepared.signature_raw)):
            read_back(site.asset_url(rolling_tag, name), len(raw), hashlib.sha256(raw).hexdigest(), sleep=sleep)
        out('%s: channel catalog %s seq %d is live' % (site.name, prepared.channel, prepared.catalog['seq']))
        done[site.name] = site.asset_url(rolling_tag, prepared.catalog_name)
    return done


def cleanup(prepared, sites, suffix, out=print):
    if not suffix:
        raise PublishError('--cleanup only removes rehearsal releases, so it needs a non-empty --tag-suffix')
    version_tag, rolling_tag = plan(prepared, sites, suffix)
    for site in sites:
        for tag in (version_tag, rolling_tag):
            site.delete_release(tag)
            out('%s: deleted release %s' % (site.name, tag))


def parser():
    result = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    result.add_argument('prepared', type=Path, help='Directory produced by tools/release.sh prepare')
    result.add_argument('--site', action='append', choices=('cnb', 'github'), required=True)
    result.add_argument('--cnb-repo', default='kiwimoe/ThinkOS')
    result.add_argument('--github-repo', help='owner/name of the public GitHub repository')
    result.add_argument('--target', default='main', help='Branch or commit the rolling channel tag is created at')
    result.add_argument('--tag-suffix', default='', help='Rehearsal only: appended to both release tags, e.g. -test1')
    result.add_argument('--execute', action='store_true', help='Actually publish; without it only the plan is printed')
    result.add_argument('--cleanup', action='store_true', help='Delete the rehearsal releases made with --tag-suffix')
    result.add_argument('--yes', action='store_true', help='Skip the typed confirmation')
    result.add_argument('--cnb-api', default='https://api.cnb.cool', help=argparse.SUPPRESS)
    result.add_argument('--cnb-web', default='https://cnb.cool', help=argparse.SUPPRESS)
    result.add_argument('--github-web', default='https://github.com', help=argparse.SUPPRESS)
    result.add_argument('--gh', default='gh', help=argparse.SUPPRESS)
    return result


def build_sites(args):
    sites = []
    for name in dict.fromkeys(args.site):
        if name == 'cnb':
            token = os.environ.get(CNB_TOKEN_ENV, '')
            if (args.execute or args.cleanup) and not token:
                raise PublishError('Set %s to a CNB access token with repo-release:rw' % CNB_TOKEN_ENV)
            sites.append(CnbSite(args.cnb_repo, token, args.cnb_api, args.cnb_web))
        else:
            if not args.github_repo:
                raise PublishError('--github-repo is required for --site github')
            sites.append(GitHubSite(args.github_repo, args.gh, args.github_web))
    return sites


def main(argv=None, out=print):
    args = parser().parse_args(argv)
    if args.tag_suffix and not SUFFIX.fullmatch(args.tag_suffix):
        raise PublishError('Invalid --tag-suffix')
    if args.execute or args.cleanup:
        require_owner_host()
    prepared = Prepared(args.prepared)
    sites = build_sites(args)
    if args.cleanup:
        cleanup(prepared, sites, args.tag_suffix, out)
        return 0
    if args.execute and not args.yes:
        typed = input('Type %s to publish to %s: ' % (prepared.version + args.tag_suffix, ', '.join(site.name for site in sites)))
        if typed.strip() != prepared.version + args.tag_suffix:
            raise PublishError('Confirmation did not match; nothing was published')
    publish(prepared, sites, args.tag_suffix, args.target, args.execute, out)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (PublishError, ValueError, OSError, KeyError, json.JSONDecodeError) as error:
        print('Publication stopped: %s. Review the prepared directory and the target sites.' % (
            error if isinstance(error, PublishError) else type(error).__name__), file=sys.stderr)
        sys.exit(1)
