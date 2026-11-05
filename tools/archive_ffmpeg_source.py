#!/usr/bin/env python3
"""Archive the exact FFmpeg extension source trees recorded in ffmpeg-source.lock.json.

Only reads upstream Git objects, never checks out or runs upstream code. The three
repositories are cached outside the workspace. --offline recreates the archive
without network access once those objects are present.
"""
import argparse
from dataclasses import dataclass
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = Path(__file__).with_name('ffmpeg-source.lock.json')


def git(repo, *args, check=True):
    command = ['git', '--git-dir', str(repo), *args]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    try:
        stdout, stderr = process.communicate(timeout=3600)
    except subprocess.TimeoutExpired:
        # fetch can have remote-https/index-pack children; clean up only this tool's process group.
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate()
        raise RuntimeError(f'Git operation timed out: {args[0]}') from None
    result = subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
    if check:
        result.check_returncode()
    return result


def obtain(cache, record, offline):
    commit = record['commit']
    if not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Expected a pinned full Git commit')
    name = record['path'] or 'jellyfin'
    repo = cache / f'{name}-{commit}.git'
    if not repo.exists():
        if offline:
            raise RuntimeError(f'Offline cache is missing {name} {commit}')
        git(repo, 'init', '--bare', str(repo))
    if git(repo, 'cat-file', '-e', commit + '^{commit}', check=False).returncode:
        if offline:
            raise RuntimeError(f'Offline cache is missing objects for {name} {commit}')
        print(f'Fetching {name}: {commit}', flush=True)
        result = git(repo, '-c', 'http.lowSpeedLimit=1024', '-c', 'http.lowSpeedTime=60',
                     'fetch', '--no-tags', '--depth=1', record.get('fetch_url', record['url']), commit, check=False)
        if result.returncode:
            raise RuntimeError(f'Fetch failed for {name}: {result.stderr.decode(errors="replace")}')
    actual = git(repo, 'rev-parse', '--verify', commit + '^{commit}').stdout.decode().strip()
    if actual != commit:
        raise ValueError(f'Commit mismatch for {name}')
    return repo


@dataclass(frozen=True)
class Blob:
    repository: Path
    oid: str
    mode: str


def source_files(lock, repositories):
    files = {}
    gitlinks = {}
    for record in lock['repositories']:
        prefix = record['path']
        repo = repositories[prefix]
        for raw in git(repo, 'ls-tree', '-rz', '--full-tree', record['commit']).stdout.split(b'\0'):
            if not raw:
                continue
            header, name = raw.split(b'\t', 1)
            mode, kind, oid = header.decode('ascii').split()
            relative = PurePosixPath(name.decode('utf-8'))
            if relative.is_absolute() or any(p in ('..', '.git') for p in relative.parts):
                raise ValueError('Unsafe source path')
            path = str(PurePosixPath(prefix) / relative)
            if kind == 'commit' and mode == '160000':
                if prefix:
                    raise ValueError(f'Unrecorded nested submodule: {path}')
                gitlinks[path] = oid
                continue
            if kind != 'blob' or mode not in ('100644', '100755', '120000') or path in files:
                raise ValueError(f'Unsupported or duplicate source entry: {path}')
            files[path] = Blob(repo, oid, mode)
    expected = {r['path']: r['commit'] for r in lock['repositories'] if r['path']}
    if gitlinks != expected:
        raise ValueError(f'Submodule pins differ from parent Git tree: {gitlinks}')
    return files


def archive(lock, repositories, destination):
    files = source_files(lock, repositories)
    required = ('build.sh', 'build.gradle.kts', 'gradlew', 'LICENSE',
                'media/libraries/decoder_ffmpeg/src/main/jni/build_ffmpeg.sh',
                'ffmpeg/configure', 'ffmpeg/COPYING.GPLv3')
    missing = set(required) - files.keys()
    if missing:
        raise ValueError(f'Missing corresponding source files: {sorted(missing)}')
    root = lock['root']
    if not re.fullmatch(r'[A-Za-z0-9_.+-]+', root):
        raise ValueError('Invalid archive root')
    epoch = lock['epoch']
    parent = lock['repositories'][0]
    actual_epoch = int(git(repositories[''], 'show', '-s', '--format=%ct', parent['commit']).stdout)
    if actual_epoch != epoch:
        raise ValueError('Archive epoch must equal the pinned parent commit time')
    metadata = (json.dumps(lock, sort_keys=True, indent=2) + '\n').encode()
    if 'SOURCE-MANIFEST.json' in files:
        raise ValueError('Upstream collides with archive metadata')
    processes = {}
    payload = 0
    try:
        for repo in set(repositories.values()):
            processes[repo] = subprocess.Popen(['git', '--git-dir', str(repo), 'cat-file', '--batch'],
                                              stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        with destination.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0, compresslevel=9) as gz:
            with tarfile.open(fileobj=gz, mode='w|', format=tarfile.PAX_FORMAT) as output:
                directories = {root}
                for name in files:
                    directories.update(str(p) for p in (PurePosixPath(root) / name).parents if str(p) != '.')
                entries = {str(PurePosixPath(root) / name): blob for name, blob in files.items()}
                entries[root + '/SOURCE-MANIFEST.json'] = metadata
                for name in sorted(directories | entries.keys()):
                    member = tarfile.TarInfo(name)
                    member.mtime = epoch
                    member.uid = member.gid = 0
                    member.uname = member.gname = ''
                    if name in directories:
                        member.type = tarfile.DIRTYPE
                        member.mode = 0o755
                        output.addfile(member)
                        continue
                    item = entries[name]
                    if isinstance(item, bytes):
                        data, mode = item, '100644'
                    else:
                        process = processes[item.repository]
                        process.stdin.write(item.oid.encode() + b'\n')
                        process.stdin.flush()
                        oid, kind, size = process.stdout.readline().split()
                        if oid.decode() != item.oid or kind != b'blob':
                            raise ValueError('Unexpected Git object')
                        data = process.stdout.read(int(size))
                        if len(data) != int(size) or process.stdout.read(1) != b'\n':
                            raise ValueError('Truncated Git object')
                        mode = item.mode
                    member.mode = 0o755 if mode == '100755' else 0o644
                    if mode == '120000':
                        member.type = tarfile.SYMTYPE
                        member.linkname = data.decode('utf-8')
                        output.addfile(member)
                    else:
                        member.size = len(data)
                        output.addfile(member, io.BytesIO(data))
                        payload += len(data)
    finally:
        for process in processes.values():
            process.stdin.close()
            process.stdout.close()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            process.stderr.close()
    return {'files': len(files) + 1, 'source_bytes': payload, 'required_files': list(required),
            'root': root, 'repositories': lock['repositories']}


def verify_archive(lock, repositories, source):
    """Check every archived source blob against its pinned Git object id before publishing it."""
    expected = source_files(lock, repositories)
    seen = set()
    metadata_seen = False
    root = lock['root']
    with tarfile.open(source, mode='r|gz') as incoming:
        for member in incoming:
            if member.mtime != lock['epoch'] or (member.uid, member.gid, member.uname, member.gname) != (0, 0, '', ''):
                raise ValueError('Non-canonical archive metadata')
            if member.isdir():
                if member.name != root and not member.name.startswith(root + '/'):
                    raise ValueError('Archive directory outside root')
                continue
            if member.name == root + '/SOURCE-MANIFEST.json':
                if metadata_seen or json.load(incoming.extractfile(member)) != lock:
                    raise ValueError('Source manifest differs from lock')
                metadata_seen = True
                continue
            if not member.name.startswith(root + '/'):
                raise ValueError('Archive file outside root')
            name = member.name[len(root) + 1:]
            blob = expected.get(name)
            if blob is None or name in seen:
                raise ValueError(f'Unexpected or duplicate source file: {name}')
            seen.add(name)
            if blob.mode == '120000':
                if not member.issym():
                    raise ValueError('Source symlink type changed')
                data = member.linkname.encode('utf-8')
            else:
                if not member.isfile() or member.mode != (0o755 if blob.mode == '100755' else 0o644):
                    raise ValueError('Source file type or executable bit changed')
                data = incoming.extractfile(member).read()
            oid = hashlib.sha1(b'blob ' + str(len(data)).encode('ascii') + b'\0' + data).hexdigest()
            if oid != blob.oid:
                raise ValueError(f'Archived source differs from pinned Git blob: {name}')
    if seen != expected.keys() or not metadata_seen:
        raise ValueError('Incomplete source archive')
    return len(seen)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/lic/LIC-04')
    parser.add_argument('--cache-dir', type=Path, default=Path.home() / 'Library/Caches/ThinkOS/ffmpeg-source')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text())
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    repositories = {r['path']: obtain(args.cache_dir, r, args.offline) for r in lock['repositories']}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    name = lock['root'] + '.tar.gz'
    with tempfile.NamedTemporaryFile(prefix=name + '.partial-', dir=args.output_dir, delete=False) as temp:
        partial = Path(temp.name)
    try:
        result = archive(lock, repositories, partial)
        result['verified_source_blobs'] = verify_archive(lock, repositories, partial)
        hasher = hashlib.sha256()
        with partial.open('rb') as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b''):
                hasher.update(chunk)
        digest = hasher.hexdigest()
        os.replace(partial, args.output_dir / name)
    finally:
        partial.unlink(missing_ok=True)
    result.update(sha256=digest, archive=name, archive_bytes=(args.output_dir / name).stat().st_size)
    (args.output_dir / (name + '.sha256')).write_text(f'{digest}  {name}\n')
    (args.output_dir / 'source-manifest.json').write_text(json.dumps(lock, indent=2) + '\n')
    (args.output_dir / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
    license_source = ROOT / 'LICENSE-GPL-3.0.md'
    license_output = args.output_dir / license_source.name
    if license_source.resolve() != license_output.resolve():
        shutil.copyfile(license_source, license_output)
    attachments = [{'path': name, 'sha256': digest},
                   {'path': name + '.sha256'},
                   {'path': license_output.name, 'sha256': hashlib.sha256(license_source.read_bytes()).hexdigest()}]
    (args.output_dir / 'release-attachments.json').write_text(json.dumps(attachments, indent=2) + '\n')
    for record in lock['repositories']:
        print(f'{record["path"] or "jellyfin"}: {record["commit"]}')
    print(f'{digest}  {args.output_dir / name} ({result["files"]} files)', flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
