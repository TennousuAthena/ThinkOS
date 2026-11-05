"""MIT. Owner-run export of a clean-history public source tree (REL-3, OWN-31).

The development repository tracks bulky device evidence (`artifacts/`) and its history carries personal data, so the
public repository is a separate one whose history starts at the first export. This tool takes a COMMITTED revision of the
development repository, drops everything that is not source, refuses to continue while the tree still contains personal
paths, addresses or e-mail, and records the result as one commit under an author identity the owner passes explicitly
(never the global git identity). Run `tools/release.sh prepare` from the exported repository so that the corresponding
source archive, the unit tests and the build all see exactly what is published.

It never pushes, tags or contacts a network: the owner pushes the printed commands after reading the report."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

from release_tools import ROOT, no_symlink_path

# What the build and the unit tests read from artifacts/ (everything else there is evidence from a device run).
KEEP_ARTIFACTS = (
    'artifacts/certs/CERT-0/device-store-2026-11-03.tsv',
    'artifacts/certs/CERT-1/source/',
    'artifacts/certs/CERT-3/tls-sources/included-ca-20261104.csv',
)
DROP = ('.gradle/', 'build/', 'android/build/', 'android/app/build/', 'android/.gradle/', 'android/local.properties',
        'android/keystore.properties', '.idea/', '.DS_Store')
# (label, pattern, blocking). Blocking findings must be scrubbed (or reviewed one by one with --allow-findings); the others
# are counted and summarised because test fixtures legitimately contain private-range addresses and fake e-mail addresses.
SCANS = (
    ('home directory path', re.compile(r'(?<![\w.-])/(?:Users|home)/[A-Za-z0-9][A-Za-z0-9._-]*/'), True),
    ('private key material', re.compile(r'-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----\s*\n[A-Za-z0-9+/=\r\n]{60,}'), True),
    ('private IPv4 address', re.compile(r'\b(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b'), False),
    ('e-mail address', re.compile(r'\b[A-Za-z0-9._%+-]+@(?!example\.|users\.noreply\.|noreply\.)[A-Za-z0-9-]+\.[A-Za-z]{2,}\b'), False),
    ('Wi-Fi name', re.compile(r'\bssid\s*[=:]\s*["\']?[A-Za-z0-9]', re.IGNORECASE), False),
)
TEXT_SUFFIXES = {'.md', '.py', '.sh', '.kt', '.kts', '.java', '.json', '.lua', '.c', '.h', '.txt', '.xml', '.yml', '.yaml',
                 '.gradle', '.properties', '.toml', '.go', '.mod', '.cmake', '.rule', '.prop', '.html', '.css', ''}
MAX_SCAN = 4 * 1024 * 1024


def git(*args, cwd=ROOT, check=True):
    result = subprocess.run(['git', *args], cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if check and result.returncode:
        raise RuntimeError('git %s failed: %s' % (args[0], result.stderr.decode('utf-8', 'replace')[:200]))
    return result.stdout


def keep(path):
    if any(path == item or path.startswith(item) for item in DROP) or '/build/' in path or path.endswith('/.DS_Store'):
        return False
    if path.startswith('artifacts/'):
        return any(path == item or (item.endswith('/') and path.startswith(item)) for item in KEEP_ARTIFACTS)
    return True


def snapshot(revision, worktree, target, source=ROOT):
    """Materialize the tracked tree of REVISION (or, for a trial run only, the working tree) into TARGET."""
    if worktree:
        names = [n for n in git('ls-files', '-z', '--cached', '--others', '--exclude-standard', cwd=source).split(b'\0') if n]
        for raw in names:
            name = os.fsdecode(raw)
            origin = source / name
            if origin.is_symlink() or not origin.is_file() or not keep(name):
                continue
            (target / name).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(origin, target / name)
        return
    with subprocess.Popen(['git', 'archive', '--format=tar', revision], cwd=source, stdout=subprocess.PIPE) as archive:
        with tarfile.open(fileobj=archive.stdout, mode='r|') as tar:
            for member in tar:
                if not (member.isfile() or member.isdir()) or not keep(member.name):
                    continue
                if member.name.startswith('/') or '..' in Path(member.name).parts:
                    raise RuntimeError('Unsafe path in archive')
                tar.extract(member, target, **({'filter': 'data'} if hasattr(tarfile, 'data_filter') else {}))
        if archive.wait():
            raise RuntimeError('git archive failed')


def scan(root):
    """Returns (relative path, line, label, blocking). Multi-line patterns report the line where the match starts."""
    findings = []
    for path in sorted(root.rglob('*')):
        if '.git' in path.relative_to(root).parts or path.is_symlink() or not path.is_file():
            continue
        if path.suffix.lower() not in TEXT_SUFFIXES or path.stat().st_size > MAX_SCAN:
            continue
        try:
            text = path.read_text('utf-8')
        except (UnicodeDecodeError, OSError):
            continue
        relative = path.relative_to(root).as_posix()
        for label, pattern, blocking in SCANS:
            for match in pattern.finditer(text):
                findings.append((relative, text.count('\n', 0, match.start()) + 1, label, blocking))
    return findings


def export(revision, output, name, email, message, worktree=False, allow_findings=False, out=print, source=ROOT):
    if not re.fullmatch(r'[^<>\n]{1,80}', name) or not re.fullmatch(r'[^<>\s]{3,120}@[^<>\s]{3,120}', email):
        raise ValueError('Give an explicit author name and e-mail for the public history (a no-reply address is best)')
    if not worktree:
        git('rev-parse', '--verify', revision + '^{commit}', cwd=source)
        if git('status', '--porcelain=v1', '--untracked-files=all', cwd=source).strip():
            out('Note: the development tree has uncommitted changes; only the committed revision %s is exported.' % revision)
    output = no_symlink_path(output)
    if output == source or source in output.parents:
        raise ValueError('The public repository cannot live inside the development repository')
    if any((ancestor / '.git').exists() for ancestor in output.parents):
        raise ValueError('The public repository cannot be nested inside another Git repository')
    existing = (output / '.git').is_dir()
    if existing:
        if git('status', '--porcelain=v1', '--untracked-files=all', cwd=output).strip():
            raise ValueError('The public repository has uncommitted changes; commit or discard them first')
    elif output.exists() and any(output.iterdir()):
        raise ValueError('Output must be a new or empty directory, or an existing public repository')
    with tempfile.TemporaryDirectory(prefix='thinkos-export-') as staging_name:
        staging = Path(staging_name)
        snapshot(revision, worktree, staging, source)
        findings = scan(staging)
        blocking = [item for item in findings if item[3]]
        if findings:
            counts = {}
            for _, _, label, _ in findings:
                counts[label] = counts.get(label, 0) + 1
            for relative, number, label, _ in blocking[:40]:
                out('  BLOCKING %s:%d  %s' % (relative, number, label))
            out('%d findings: %s' % (len(findings), ', '.join('%s x%d' % item for item in sorted(counts.items()))))
            noisy = {}
            for relative, _, label, flag in findings:
                if not flag:
                    noisy[relative] = noisy.get(relative, 0) + 1
            if noisy:
                out('Advisory findings are mostly fixtures; most affected files: ' +
                    ', '.join('%s (%d)' % item for item in sorted(noisy.items(), key=lambda pair: -pair[1])[:6]))
            if blocking and not allow_findings:
                raise ValueError('%d blocking findings: scrub them or pass --allow-findings after reviewing each; nothing was written' % len(blocking))
        if worktree:
            out('TRIAL EXPORT from the working tree: do not publish this; export a committed revision instead.')
        output.mkdir(parents=True, exist_ok=True)
        if not existing:
            git('init', '-q', '-b', 'main', cwd=output)
        else:
            for child in output.iterdir():
                if child.name != '.git':
                    shutil.rmtree(child) if child.is_dir() and not child.is_symlink() else child.unlink()
        for child in staging.iterdir():
            shutil.move(str(child), str(output / child.name))
    git('add', '-A', cwd=output)
    if not git('status', '--porcelain=v1', cwd=output).strip():
        out('No changes since the previous export.')
        return None
    environment = {'GIT_AUTHOR_NAME': name, 'GIT_AUTHOR_EMAIL': email, 'GIT_COMMITTER_NAME': name, 'GIT_COMMITTER_EMAIL': email}
    result = subprocess.run(['git', '-c', 'commit.gpgsign=false', '-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', message],
                            cwd=output, env={**os.environ, **environment}, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise RuntimeError('git commit failed: ' + result.stderr.decode('utf-8', 'replace')[:200])
    head = git('rev-parse', 'HEAD', cwd=output).decode().strip()
    out('Exported %d files as %s in %s' % (len(git('ls-files', cwd=output).splitlines()), head[:12], output))
    return head


def parser():
    result = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    result.add_argument('--output', type=Path, required=True, help='New directory (or an existing public repository) outside every repository')
    result.add_argument('--rev', default='HEAD', help='Committed revision of the development repository to export')
    result.add_argument('--author-name', required=True)
    result.add_argument('--author-email', required=True)
    result.add_argument('--message', default='Source for the ThinkOS release')
    result.add_argument('--worktree', action='store_true', help='TRIAL ONLY: export the uncommitted working tree to test the result')
    result.add_argument('--allow-findings', action='store_true', help='Continue after you reviewed every reported finding')
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    head = export(args.rev, args.output, args.author_name, args.author_email, args.message, args.worktree, args.allow_findings)
    if head and not args.worktree:
        print('Next, as the owner (CNB takes HTTPS only: user name "cnb", your access token as the password): cd %s && '
              'git remote add cnb https://cnb.cool/kiwimoe/ThinkOS && git tag v<version> && git push cnb main v<version>, '
              'then the same for GitHub.' % args.output)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, RuntimeError, OSError) as error:
        print('Export stopped: %s' % error, file=sys.stderr)
        sys.exit(1)
