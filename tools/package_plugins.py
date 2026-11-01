#!/usr/bin/env python3
"""Validate ThinkOS plugins and emit reproducible directories with SHA-256 file indexes."""
import argparse
import hashlib
import json
import re
from pathlib import Path, PurePosixPath
import sys
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'shared' / 'gen'))
from schema_tools import ValidationError, validate_manifest, validate_view, validate

MAX_FILE_BYTES = 1024 * 1024
MAX_PACKAGE_BYTES = 8 * 1024 * 1024
MAX_FILES = 128
MAX_JSON_SCRIPT_BYTES = 256 * 1024
MAX_INDEX_BYTES = 64 * 1024


def safe_relative(value):
    path = PurePosixPath(value)
    if not value or value.startswith('/') or '\\' in value or any(ord(c) < 32 or ord(c) == 127 for c in value) or len(value.encode('utf-8')) > 512 or any(p in ('', '.', '..') for p in value.split('/')):
        raise ValidationError(f'unsafe package path: {value!r}')
    return path


def read_json(path):
    if path.stat().st_size > MAX_JSON_SCRIPT_BYTES:
        raise ValidationError(f'JSON file too large: {path.name}')
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValidationError(f'duplicate JSON key: {key}')
            result[key] = value
        return result
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValidationError(f'non-finite JSON value: {value}')))


def allowed_file(relative):
    path = PurePosixPath(relative)
    if any(part.startswith('.') for part in path.parts):
        return False
    return (relative == 'manifest.json' or path.suffix == '.lua' or
            (path.parts[0] in ('views', 'i18n') and len(path.parts) > 1 and path.suffix == '.json') or
            (path.parts[0] == 'assets' and len(path.parts) > 1 and path.suffix.lower() in ('.png', '.jpg', '.jpeg', '.webp', '.ogg', '.wav')))


def tile_drops(sizes, cols, rows):
    occupied = set()
    dropped = []
    for i, size in enumerate(sizes):
        w, h = {'small': (1,1), 'medium': (2,2), 'wide': (4,2), 'large': (4,4)}[size]
        for y in range(rows-h+1):
            for x in range(cols-w+1):
                cells = {(a,b) for a in range(x,x+w) for b in range(y,y+h)}
                if not cells & occupied:
                    occupied |= cells
                    break
            else: continue
            break
        else: dropped.append(i)
    return set(dropped)


def check_use_bindings(manifest, entry, view):
    """A binding to uses.<alias> must name an alias this plugin declared; otherwise it stays silently blank."""
    declared = manifest.get('uses', {})
    def visit(value):
        if isinstance(value, dict):
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)
        elif isinstance(value, str):
            for alias in re.findall(r'(?:^|\{\{\s*)uses\.([A-Za-z0-9_-]+)', value):
                if alias not in declared:
                    raise ValidationError(f'{entry}: binding to undeclared uses.{alias}')
    visit(view)


def check_public_links(manifests):
    """Warn about links the packaged set cannot satisfy. Providers may also arrive later, so this never fails."""
    plugins = {manifest['id']: manifest for manifest in manifests}
    for manifest in manifests:
        for alias, use in manifest.get('uses', {}).items():
            provider, export = use['export'].split('.')
            declared = plugins.get(provider, {}).get('exports', {}).get(export)
            if provider in plugins and declared is None:
                print(f'warning: {manifest["id"]}: uses.{alias}: {use["export"]} is not exported', file=sys.stderr)
            elif declared is not None and declared['shape'] != use['shape']:
                print(f'warning: {manifest["id"]}: uses.{alias}: wants {use["shape"]}, {use["export"]} provides {declared["shape"]}', file=sys.stderr)
            elif declared is not None and not set(declared.get('requires', [])) <= set(manifest.get('permissions', [])):
                print(f'warning: {manifest["id"]}: uses.{alias}: missing permissions {sorted(set(declared["requires"]) - set(manifest.get("permissions", [])))}', file=sys.stderr)
        for target in manifest.get('calls', []):
            provider, command = target.split('.')
            if provider in plugins and not any(entry['id'] == command and entry.get('public') for entry in plugins[provider].get('commands', [])):
                print(f'warning: {manifest["id"]}: calls: {target} is not a public command', file=sys.stderr)


MEDIA_COMPONENTS = {'controls', 'video', 'seekbar'}


def uses_media_components(value):
    if isinstance(value, dict):
        return value.get('type') in MEDIA_COMPONENTS or any(uses_media_components(item) for item in value.values())
    if isinstance(value, list):
        return any(uses_media_components(item) for item in value)
    return False


def check_media_permission(manifest, entry_id, view):
    # The OS draws these against the media session; without the grant the renderer would have nothing to attach to.
    if uses_media_components(view) and 'media' not in manifest.get('permissions', []):
        raise ValidationError(f'{entry_id}: controls, video and seekbar need the media permission')


def validate_plugin(source):
    if Path(source).is_symlink():
        raise ValidationError('plugin root cannot be a symlink')
    source = Path(source).resolve()
    if not source.is_dir():
        raise ValidationError(f'plugin directory not found: {source}')
    files = []
    total = 0
    folded = {}
    for file in sorted(source.rglob('*')):
        relative = file.relative_to(source).as_posix()
        safe_relative(relative)
        if file.is_symlink():
            raise ValidationError(f'symlinks are forbidden: {relative}')
        if file.is_dir():
            continue
        if not allowed_file(relative):
            continue
        for end in range(1, len(PurePosixPath(relative).parts) + 1):
            prefix = '/'.join(PurePosixPath(relative).parts[:end])
            lower = prefix.lower()
            if lower in folded and folded[lower] != prefix:
                raise ValidationError(f'case-colliding package path: {relative}')
            folded[lower] = prefix
        if not file.is_file():
            raise ValidationError(f'not a regular file: {relative}')
        size = file.stat().st_size
        if size > MAX_FILE_BYTES:
            raise ValidationError(f'file exceeds 1 MiB: {relative}')
        if file.suffix.lower() in ('.json', '.lua') and size > MAX_JSON_SCRIPT_BYTES:
            raise ValidationError(f'JSON/Lua exceeds 256 KiB: {relative}')
        total += size
        files.append((relative, file))
    if total > MAX_PACKAGE_BYTES or len(files) > MAX_FILES:
        raise ValidationError('package exceeds 8 MiB / 128 files')
    manifest_path = source / 'manifest.json'
    if not manifest_path.is_file():
        raise ValidationError('missing manifest.json')
    manifest = read_json(manifest_path)
    validate_manifest(manifest)
    names = {name for name, _ in files}
    for key in ('main', 'icon'):
        if key in manifest:
            safe_relative(manifest[key])
            if manifest[key] not in names:
                raise ValidationError(f'missing {key}: {manifest[key]}')
    for category in ('pages', 'widgets'):
        for entry in manifest.get(category, []):
            safe_relative(entry['view'])
            if entry['view'] not in names:
                raise ValidationError(f'missing view: {entry["view"]}')
            view = read_json(source / entry['view'])
            validate_view(view, manifest['api'])
            check_media_permission(manifest, entry['id'], view)
            if view.get('layout') == 'tiles':
                sizes = [item['size'] for item in view['slots']['tiles']]
                dropped = tile_drops(sizes, 8, 6) & tile_drops(sizes, 6, 8)
                if dropped: print(f'warning: {manifest["id"]}.{entry["id"]}: tiles {sorted(dropped)} do not fit in either orientation', file=sys.stderr)
            if ('layout' in view) != (category == 'pages'):
                raise ValidationError(f'{category}.{entry["id"]}: wrong view kind')
            declared = entry.get('variants')
            rendered = view.get('variants')
            if declared and rendered and declared != rendered:
                raise ValidationError(f'{entry["id"]}: manifest/view variants disagree')
            check_use_bindings(manifest, entry['id'], view)
            for template, alternate in entry.get('alternates', {}).items():
                safe_relative(alternate)
                if alternate not in names:
                    raise ValidationError(f'missing alternate view: {alternate}')
                other = read_json(source / alternate)
                validate_view(other, manifest['api'])
                check_media_permission(manifest, entry['id'], other)
                if other.get('template') != template:
                    raise ValidationError(f'widget {entry["id"]}: alternate {template} has a different template')
                check_use_bindings(manifest, entry['id'], other)
    return manifest, sorted(files, key=lambda item: item[0].encode("utf-8"))


def file_index(manifest, files):
    entries = []
    total = 0
    for relative, file in files:
        limit = MAX_JSON_SCRIPT_BYTES if file.suffix.lower() in ('.json', '.lua') else MAX_FILE_BYTES
        with file.open('rb') as stream:
            data = stream.read(limit + 1)
        if len(data) > limit:
            raise ValidationError(f'plugin changed or exceeds file size budget: {relative}')
        total += len(data)
        if total > MAX_PACKAGE_BYTES:
            raise ValidationError('plugin exceeds 8 MiB')
        entries.append({'path': relative, 'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data)})
    canonical = b''.join((entry['path'] + '\0' + entry['sha256'] + '\n').encode('utf-8') for entry in entries)
    result = {'id': manifest['id'], 'version': manifest['version'],
              'digest': hashlib.sha256(canonical).hexdigest(), 'files': entries}
    validate(result, document='index.json')
    if len(index_bytes(result)) > MAX_INDEX_BYTES:
        raise ValidationError('plugin file index exceeds 64 KiB')
    return result


def index_bytes(index):
    return (json.dumps(index, ensure_ascii=False, separators=(',', ':'), sort_keys=True) + '\n').encode('utf-8')


def package_plugin(source, output):
    manifest, files = validate_plugin(source)
    index = file_index(manifest, files)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    target = output / manifest['id']
    with tempfile.TemporaryDirectory(prefix='.stage-', dir=output) as temporary:
        staging = Path(temporary) / manifest['id']
        staging.mkdir()
        for relative, file in files:
            destination = staging / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            entry = next(item for item in index['files'] if item['path'] == relative)
            with file.open('rb') as stream:
                data = stream.read(entry['size'] + 1)
            if len(data) != entry['size'] or hashlib.sha256(data).hexdigest() != entry['sha256']:
                raise ValidationError(f'plugin changed during packaging: {relative}')
            destination.write_bytes(data)
        (staging / 'index.json').write_bytes(index_bytes(index))
        if target.exists():
            shutil.rmtree(target)
        staging.replace(target)
    # Remove historical build output; it must never leak into a new APK.
    (output / (manifest['id'] + '.tpk')).unlink(missing_ok=True)
    return target, index['digest']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plugin', action='append', type=Path,
                        help='Plugin source directory; repeat to package multiple. Defaults to built-in home, clock, weather, gallery, tiles, aero, player, cast and settings.')
    parser.add_argument('--output', type=Path, default=ROOT / 'android/app/src/main/assets/plugins',
                        help='Output directory for <id>/ files and index.json.')
    parser.add_argument('--validate-only', action='store_true', help='Validate without writing directories.')
    args = parser.parse_args()
    sources = args.plugin or [ROOT / 'plugins/home', ROOT / 'plugins/clock', ROOT / 'plugins/weather', ROOT / 'plugins/gallery', ROOT / 'plugins/tiles', ROOT / 'plugins/aero', ROOT / 'plugins/player', ROOT / 'plugins/cast', ROOT / 'plugins/settings']
    try:
        manifests = [validate_plugin(source)[0] for source in sources]
        ids = [manifest['id'] for manifest in manifests]
        check_public_links(manifests)
        if len(set(ids)) != len(ids):
            raise ValidationError('duplicate plugin IDs in input set')
        catalog = []
        for source in sources:
            if args.validate_only:
                manifest, _ = validate_plugin(source)
                print(f'valid: {manifest["id"]} {manifest["version"]}')
            else:
                target, digest = package_plugin(source, args.output)
                catalog.append(read_json(target / 'index.json'))
                print(f'{target}  digest={digest}')
        if not args.validate_only:
            for legacy in args.output.glob('*.tpk'):
                legacy.unlink()
            root_index = {'plugins': sorted(catalog, key=lambda entry: entry['id'])}
            validate(root_index, document='catalog.json')
            (args.output / 'index.json').write_bytes(index_bytes(root_index))
    except (ValidationError, OSError, ValueError, KeyError, RecursionError) as exc:
        parser.exit(1, f'plugin package rejected: {exc}\n')

if __name__ == '__main__':
    main()
