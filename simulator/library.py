"""Read-only virtual Android media paths backed by explicit simulator mounts."""
from contextlib import contextmanager
import json
import os
from pathlib import Path, PurePosixPath
import stat
from urllib.parse import quote, unquote, urlsplit

PRIVATE_ROOT = "/sdcard/Android/data/moe.kiwi.thinkos/files"
FOLDERS = {"image": "Pictures", "audio": "Music", "video": "Movies"}
MIMES = {
    "image": {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "webp": "image/webp", "gif": "image/gif", "bmp": "image/bmp"},
    "audio": {"mp3": "audio/mpeg", "flac": "audio/flac", "ogg": "audio/ogg", "oga": "audio/ogg", "opus": "audio/ogg", "m4a": "audio/mp4", "aac": "audio/aac", "wav": "audio/wav", "amr": "audio/amr", "mka": "audio/x-matroska", "ac3": "audio/ac3", "eac3": "audio/eac3", "dts": "audio/vnd.dts"},
    "video": {"mp4": "video/mp4", "m4v": "video/mp4", "3gp": "video/3gpp", "webm": "video/webm", "mkv": "video/x-matroska", "ts": "video/mp2t", "mov": "video/quicktime", "avi": "video/x-msvideo", "flv": "video/x-flv", "mpg": "video/mpeg", "mpeg": "video/mpeg", "vob": "video/mpeg"},
}


class Library:
    def __init__(self, profiles, config, simulator):
        self.config = config
        self.mounts = {PRIVATE_ROOT: profiles.directory / config["profile"] / "library"}
        self.mounts.update({"/sdcard/" + name: self.mounts[PRIVATE_ROOT] / name for name in FOLDERS.values()})
        if (profiles.directory / config["profile"]).is_symlink():
            raise ValueError("profile library parent cannot be a symlink")
        for virtual, host in config["library_mounts"].items():
            if virtual.rstrip("/") == PRIVATE_ROOT or not self.virtual(virtual):
                raise ValueError("the private profile library mount cannot be replaced")
            self.virtual(virtual)
            root = Path(host).expanduser()
            self.mounts[virtual.rstrip("/")] = root if root.is_absolute() else simulator / root
        for virtual, root in self.mounts.items():
            if root.is_symlink():
                raise ValueError("library mount cannot be a symlink")
            # Lexically normalize only: resolving here would follow a symlink
            # in a parent directory before opened() can enforce O_NOFOLLOW.
            self.mounts[virtual] = Path(os.path.abspath(root))

    @staticmethod
    def virtual(value):
        if not isinstance(value, str) or len(value.encode()) > 4096 or any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise ValueError("invalid virtual library path")
        if value.startswith("file:"):
            uri = urlsplit(value)
            if uri.netloc or uri.query or uri.fragment:
                raise PermissionError("invalid virtual file URI")
            value = unquote(uri.path)
        if not value.startswith("/") or "\\" in value or any(part in (".", "..") for part in value.split("/")):
            raise PermissionError("only mapped absolute virtual media paths are supported")
        return value.rstrip("/")

    def resolve(self, value):
        virtual = self.virtual(value)
        for mount in sorted(self.mounts, key=len, reverse=True):
            if virtual == mount or virtual.startswith(mount + "/"):
                parts = tuple(part for part in virtual[len(mount):].split("/") if part)
                return virtual, self.mounts[mount], parts
        raise PermissionError("media path is outside configured virtual library mounts")

    @contextmanager
    def opened(self, value, directory=False):
        virtual, root, parts = self.resolve(value)
        descriptor = os.open(root.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            components = root.parts[1:] + parts
            for index, part in enumerate(components):
                flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
                if directory or index < len(components) - 1:
                    flags |= os.O_DIRECTORY
                child = os.open(part, flags, dir_fd=descriptor)
                os.close(descriptor)
                descriptor = child
            info = os.fstat(descriptor)
            if not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)):
                raise PermissionError("library resource is not a regular file")
            yield descriptor, info, virtual
        finally:
            os.close(descriptor)

    def list(self, options):
        kind = options["kind"]
        path = options.get("dir") or PRIVATE_ROOT + "/" + FOLDERS[kind]
        virtual = self.virtual(path)
        result = {"items": [], "truncated": False, "scanned": 0, "directory": virtual}
        cost = 0
        def visit(fd, parent, depth):
            nonlocal cost
            with os.scandir(fd) as entries:
                for entry in entries:
                    if result["scanned"] >= self.config["library_entries"] or len(result["items"]) >= self.config["library_items"]:
                        result["truncated"] = True
                        return
                    result["scanned"] += 1
                    if entry.is_symlink():
                        continue
                    location = parent + "/" + entry.name
                    if entry.is_dir(follow_symlinks=False):
                        if options.get("recursive", False):
                            if depth >= self.config["library_depth"]:
                                result["truncated"] = True
                            else:
                                try:
                                    child = os.open(entry.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                                    try:
                                        visit(child, location, depth + 1)
                                    finally:
                                        os.close(child)
                                except OSError:
                                    continue
                        continue
                    if not entry.is_file(follow_symlinks=False):
                        continue
                    suffix = Path(entry.name).suffix.removeprefix(".").lower()
                    mime = MIMES[kind].get(suffix)
                    if not mime:
                        continue
                    url = "file://" + quote(location, safe="/")
                    if len(url) > 8192:
                        continue
                    item = {"url": url, "src": url, "kind": kind, "title": Path(entry.name).stem[:256],
                            "artist": "", "album": "", "art": "", "mime": mime, "duration": None,
                            "width": None, "height": None, "rotation": None}
                    size = len(json.dumps(item, ensure_ascii=False).encode())
                    if cost + size > self.config["body_bytes"]:
                        result["truncated"] = True
                        return
                    cost += size
                    result["items"].append(item)
        try:
            with self.opened(path, directory=True) as (descriptor, _, _):
                visit(descriptor, virtual, 0)
        except FileNotFoundError:
            if "dir" in options and virtual not in self.mounts and virtual not in (PRIVATE_ROOT + "/" + name for name in FOLDERS.values()):
                raise ValueError("virtual library directory does not exist") from None
        result["items"].sort(key=lambda item: item["title"].lower())
        return result

    def mime(self, value, kind):
        suffix = PurePosixPath(self.virtual(value)).suffix.removeprefix(".").lower()
        choices = MIMES["image"] if kind == "image" else {**MIMES["audio"], **MIMES["video"]}
        if suffix not in choices:
            raise PermissionError("unsupported local media extension")
        return choices[suffix]
