"""Read-only source capture and immutable, validated plugin snapshots."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("thinkos_package_plugins", ROOT / "tools/package_plugins.py")
packages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packages)
MAX_ENTRIES = 2048


def read_regular(path, limit):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode) or before.st_size > limit:
            raise ValueError("not a bounded regular file")
        chunks, size = [], 0
        while True:
            block = os.read(descriptor, min(65536, limit + 1 - size))
            if not block:
                break
            chunks.append(block)
            size += len(block)
            if size > limit:
                raise ValueError("file size limit")
        after = os.fstat(descriptor)
        if (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise ValueError("file changed during snapshot")
        return b"".join(chunks)
    finally:
        os.close(descriptor)


def capture(source):
    """Open each component relative to its parent's FD; never follow a symlink."""
    result, counts = {}, [0, 0]
    root = os.open(source, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)

    def walk(fd, prefix="", depth=0):
        if depth > 16:
            raise ValueError("plugin directory depth exceeded")
        with os.scandir(fd) as entries:
            names = sorted(entry.name for entry in entries)
        for name in names:
            counts[0] += 1
            if counts[0] > MAX_ENTRIES:
                raise ValueError("plugin directory entry limit exceeded")
            relative = prefix + name
            packages.safe_relative(relative)
            info = os.stat(name, dir_fd=fd, follow_symlinks=False)
            if stat.S_ISLNK(info.st_mode):
                raise ValueError("plugin symlinks are forbidden")
            if name.startswith("."):
                continue
            if stat.S_ISDIR(info.st_mode):
                child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                try:
                    walk(child, relative + "/", depth + 1)
                finally:
                    os.close(child)
            elif packages.allowed_file(relative):
                if not stat.S_ISREG(info.st_mode):
                    raise ValueError("plugin contains a special file")
                limit = packages.MAX_JSON_SCRIPT_BYTES if Path(name).suffix.lower() in (".lua", ".json") else packages.MAX_FILE_BYTES
                child = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
                try:
                    before = os.fstat(child)
                    if not stat.S_ISREG(before.st_mode) or before.st_size > limit:
                        raise ValueError("plugin file exceeds limit")
                    data = bytearray()
                    while len(data) <= limit:
                        chunk = os.read(child, min(65536, limit + 1 - len(data)))
                        if not chunk:
                            break
                        data.extend(chunk)
                    after = os.fstat(child)
                    if len(data) > limit or (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
                        raise ValueError("plugin changed during snapshot")
                finally:
                    os.close(child)
                result[relative] = bytes(data)
                counts[1] += len(data)
                if len(result) > packages.MAX_FILES or counts[1] > packages.MAX_PACKAGE_BYTES:
                    raise ValueError("plugin exceeds 128 files / 8 MiB")
    try:
        walk(root)
    finally:
        os.close(root)
    return result


def make_snapshot(source, factory, captured=None):
    captured = capture(source) if captured is None else captured
    # Existing packaging validates precisely these copied bytes, never a live tree.
    with tempfile.TemporaryDirectory(prefix="thinkos-simulator-snapshot-") as temporary:
        source_copy, output = Path(temporary) / "source", Path(temporary) / "out"
        source_copy.mkdir()
        for name, data in captured.items():
            path = source_copy / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        target, digest = packages.package_plugin(source_copy, output)
        manifest, files = packages.validate_plugin(target)
        final_index = packages.file_index(manifest, files)
        if final_index["digest"] != digest or final_index["id"] != manifest["id"]:
            raise ValueError("snapshot final-byte validation failed")
        encoded = {name: base64.b64encode(read_regular(path, packages.MAX_FILE_BYTES)).decode("ascii") for name, path in files}
        encoded["index.json"] = base64.b64encode(packages.index_bytes(final_index)).decode("ascii")
        return {"id": manifest["id"], "digest": digest, "manifest": manifest,
                "files": encoded, "factory": factory.get(manifest["id"]) == digest}


def load_factory(path):
    if not path.is_file():
        return {}
    value = json.loads(read_regular(path, packages.MAX_INDEX_BYTES))
    entries = value.get("plugins", [])
    if not isinstance(entries, list) or len(entries) > 32:
        raise ValueError("invalid fixed factory index")
    result = {}
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str) or not isinstance(entry.get("digest"), str):
            raise ValueError("invalid fixed factory index entry")
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", entry["id"]) or not re.fullmatch(r"[a-f0-9]{64}", entry["digest"]):
            raise ValueError("invalid fixed factory identity")
        if entry["id"] in result:
            raise ValueError("duplicate fixed factory id")
        result[entry["id"]] = entry["digest"]
    return result


class Snapshots:
    def __init__(self, sources, factory=None):
        if len(sources) > 64:
            raise ValueError("too many source directories")
        self.sources = list(dict.fromkeys(Path(path).absolute() for path in sources))
        self.factory = factory or {}
        self.lock = threading.RLock()
        self.changed = threading.Condition(self.lock)
        self.revision = 0
        self.by_source = {}
        self.source_fingerprints = {}
        self.plugins = {}
        self.committed = {}
        self.diagnostics = []
        self.refresh()

    def refresh(self):
        candidates, errors = {}, []
        for source in self.sources:
            try:
                captured = capture(source)
                fingerprint = hashlib.sha256(b"".join(name.encode() + b"\0" + hashlib.sha256(data).digest() for name, data in sorted(captured.items()))).hexdigest()
                if self.source_fingerprints.get(source) == fingerprint and source in self.by_source:
                    candidates[source] = self.by_source[source]
                    continue
                snapshot = make_snapshot(source, self.factory, captured)
                previous = self.by_source.get(source)
                if previous and previous["id"] != snapshot["id"]:
                    raise ValueError("a watched plugin cannot change its id; restart with the new directory")
                candidates[source] = snapshot
                self.source_fingerprints[source] = fingerprint
            except (OSError, ValueError, KeyError, RecursionError) as error:
                errors.append(source.name + ": " + str(error)[:300])
                if source in self.by_source:
                    candidates[source] = self.by_source[source]
        owners = {}
        for source, snapshot in candidates.items():
            owners.setdefault(snapshot["id"], []).append(source)
        for plugin_id, sources in owners.items():
            if len(sources) < 2:
                continue
            message = "duplicate plugin id " + plugin_id + " in watched directories"
            if not self.by_source:
                raise ValueError(message)
            errors.append(message)
            # A newly valid directory must not shadow an already published owner.
            for source in sources:
                if source in self.by_source:
                    candidates[source] = self.by_source[source]
                else:
                    del candidates[source]
        plugins = {}
        for snapshot in candidates.values():
            plugins[snapshot["id"]] = snapshot
        if len(plugins) > 32:
            raise ValueError("more than 32 active plugins")
        with self.changed:
            if plugins != self.plugins or errors != self.diagnostics:
                for plugin_id, snapshot in plugins.items():
                    if plugin_id not in self.plugins:
                        self.committed.setdefault(plugin_id, snapshot)
                self.by_source, self.plugins, self.diagnostics = candidates, plugins, errors
                self.revision += 1
                self.changed.notify_all()

    def identity(self, plugin_id, digest, permission=None):
        with self.lock:
            plugin = self.plugins.get(plugin_id)
            if not plugin or plugin["digest"] != digest:
                plugin = self.committed.get(plugin_id)
            if not plugin or plugin["digest"] != digest:
                raise ValueError("stale or unknown plugin snapshot")
            if permission and permission not in plugin["manifest"].get("permissions", []):
                raise PermissionError(permission + " permission required")
            return plugin

    def runtime(self, plugin_id, digest, action):
        with self.lock:
            if action == "release":
                if self.committed.get(plugin_id, {}).get("digest") == digest:
                    del self.committed[plugin_id]
                return
            candidate = self.plugins.get(plugin_id)
            if candidate is None or candidate["digest"] != digest:
                if self.committed.get(plugin_id, {}).get("digest") == digest:
                    return
                raise ValueError("candidate changed before runtime commit")
            self.committed[plugin_id] = candidate
