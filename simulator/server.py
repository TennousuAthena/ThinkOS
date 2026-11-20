"""Loopback development gateway. It never installs an APK or changes Android."""
import base64
import copy
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import socket
import ssl
import tempfile
import threading
import time
from urllib.parse import urlsplit, unquote
from xml.etree import ElementTree

from .contracts import loads, request, validate
from .generated.protocol import SCHEMA
from .network import Network, NetworkError
from .library import Library
from .places import search as search_places
from .snapshots import ROOT, Snapshots, load_factory, packages, read_regular
from .scripts.build_wasm import BOOTSTRAP, extract_bootstrap, verify_sources
from schema_tools import validate_setting

SIMULATOR = ROOT / "simulator"
SETTING_SCHEMA = json.loads((SIMULATOR / "settings.schema.json").read_text())


def settings(overrides=None):
    result = {name: copy.deepcopy((SCHEMA["$defs"][spec["$ref"].rsplit("/", 1)[-1]] if "$ref" in spec else spec)["default"])
              for name, spec in SETTING_SCHEMA["properties"].items()}
    result.update(overrides or {})
    validate(result, SETTING_SCHEMA)
    return result


def default_environment():
    return {name: copy.deepcopy(spec["default"]) for name, spec in SCHEMA["$defs"]["Environment"]["properties"].items() if "default" in spec}


class Profiles:
    def __init__(self, config):
        self.config, self.lock = config, threading.RLock()
        configured = Path(config["profile_dir"]).expanduser()
        self.directory = (configured if configured.is_absolute() else SIMULATOR / configured).absolute()
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        if self.directory.is_symlink() or not self.directory.is_dir():
            raise ValueError("profile directory must be a real directory")
        self.path = self.directory / (config["profile"] + ".json")
        self.value = {"storage": {}, "settings": {"system": {}, "plugins": {}}, "environment": default_environment()}
        if self.path.exists() or self.path.is_symlink():
            saved = loads(read_regular(self.path, config["profile_bytes"]))
            if not isinstance(saved, dict) or set(saved) != {"storage", "settings", "environment"} or not isinstance(saved["storage"], dict) or not isinstance(saved["settings"], dict):
                raise ValueError("invalid simulator profile")
            validate(saved["environment"], SCHEMA["$defs"]["Environment"])
            validate(saved["storage"], {"type": "object"})
            for plugin, value in saved["storage"].items():
                if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", plugin) or not isinstance(value, dict) or len(json.dumps(value, ensure_ascii=False).encode()) > config["storage_bytes"]:
                    raise ValueError("invalid or oversized plugin storage in profile")
            saved["environment"] = {**default_environment(), **saved["environment"]}
            self.value.update(saved)

    def read(self):
        with self.lock:
            return copy.deepcopy(self.value)

    def update(self, transform):
        with self.lock:
            candidate = copy.deepcopy(self.value)
            transform(candidate)
            data = json.dumps(candidate, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode()
            if len(data) > self.config["profile_bytes"]:
                raise ValueError("profile exceeds size budget")
            descriptor, temporary = tempfile.mkstemp(prefix=".profile-", dir=self.directory)
            try:
                os.fchmod(descriptor, 0o600)
                with os.fdopen(descriptor, "wb") as output:
                    output.write(data)
                    output.flush()
                    os.fsync(output.fileno())
                os.replace(temporary, self.path)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
            self.value = candidate


class Gateway:
    def __init__(self, sources, config=None, dist=None, factory_path=None):
        verify_sources()
        self.bootstrap = extract_bootstrap(BOOTSTRAP.read_text())
        version_match = re.search(r"^thinkos\.version=(\d+\.\d+\.\d+)\s*$", (ROOT / "android/gradle.properties").read_text(), re.MULTILINE)
        if not version_match:
            raise ValueError("missing Android build version property")
        version = version_match[1]
        major, minor, patch = map(int, version.split("."))
        self.build_info = {"version": version, "code": major * 1_000_000 + minor * 1000 + patch}
        self.config = settings(config)
        self.dist = (Path(dist) if dist else SIMULATOR / "dist").absolute()
        self.token = secrets.token_urlsafe(32)
        factory_path = Path(factory_path) if factory_path else SIMULATOR / "assets/factory-index.json"
        self.factory = load_factory(factory_path)
        self.snapshots = Snapshots(sources, self.factory)
        self.profiles = Profiles(self.config)
        selected = self.config["fixture_plugins"]
        if any(plugin not in self.factory for plugin in selected):
            raise ValueError("system simulation only accepts fixed factory plugin IDs")
        if selected:
            self.profiles.update(lambda value: value["environment"].__setitem__("systemSimulation", selected))
        if any(plugin not in self.factory for plugin in self.profiles.read()["environment"].get("systemSimulation", [])):
            raise ValueError("profile system simulation references a non-factory ID")
        self.profile({"settings": self.profiles.read()["settings"]}, persist=False)
        self.library = Library(self.profiles, self.config, SIMULATOR)
        self.network = Network(self.config)
        self.stopped = threading.Event()
        self.clients = threading.BoundedSemaphore(self.config["max_clients"])
        self.ticket_lock, self.tickets = threading.Lock(), {}
        self.httpd = None
        self.glyphs, self.fonts = {}, []
        self.system_strings = {path.stem: loads(read_regular(path, 262144)) for path in sorted((ROOT / "shared/i18n/system").glob("*.json"))}
        self._region_strings()
        self.asset_diagnostics = []
        self._assets()
        if not self.factory:
            self.asset_diagnostics.append("Fixed factory index is absent; system permissions remain disabled.")

    def _assets(self):
        icons = ROOT / "assets/icons"
        catalog = loads(read_regular(icons / "glyphs.json", 65536))
        for name, entry in catalog["glyphs"].items():
            relative = packages.safe_relative(entry["file"])
            self.glyphs[name] = read_regular(icons / str(relative), 65536).decode("utf-8")
        manifest = SIMULATOR / "assets/fonts/manifest.json"
        if not manifest.is_file():
            self.asset_diagnostics.append("Device-matched fonts are not configured; visual parity is unverified.")
            return
        entries = loads(read_regular(manifest, 65536)).get("fonts", [])
        if not isinstance(entries, list) or len(entries) > 32:
            raise ValueError("invalid font manifest")
        for entry in entries:
            relative = packages.safe_relative(entry["file"])
            data = read_regular(manifest.parent / str(relative), 32 * 1024 * 1024)
            digest = hashlib.sha256(data).hexdigest()
            if digest != entry["sha256"]:
                raise ValueError("font digest differs from its fixed manifest")
            self.fonts.append({"family": entry["family"], "sha256": digest, "data": base64.b64encode(data).decode("ascii")})

    def _region_strings(self):
        wanted = {"form_region_search", "form_region_all"}
        directories = {"en": "values", "zh-Hans": "values-zh-rCN", "zh-Hant": "values-zh-rTW"}
        for locale, strings in self.system_strings.items():
            folder = directories.get(locale, "values-" + locale)
            source = ROOT / "android/app/src/main/res" / folder / "strings.xml"
            root = ElementTree.fromstring(read_regular(source, 262144))
            values = {row.attrib["name"]: "".join(row.itertext()) for row in root.findall("string") if row.attrib.get("name") in wanted}
            if set(values) != wanted:
                raise ValueError("native region picker translations are incomplete")
            strings.update(values)

    def session(self):
        profile = self.profiles.read()
        with self.snapshots.lock:
            return {"protocol": 1, "token": self.token, "revision": self.snapshots.revision,
                    "profile": self.config["profile"], "plugins": list(self.snapshots.plugins.values()),
                    "settings": packages.SYSTEM_SETTINGS, "bootstrap": self.bootstrap,
                    "storage": profile["storage"], "profileSettings": profile["settings"], "environment": profile["environment"],
                    "fixtureMode": self.config["fixture_mode"], "fixturePlugins": self.config["fixture_plugins"],
                    "factoryPlugins": list(self.factory),
                    "buildInfo": self.build_info,
                    "developmentSettings": {name: self.config[name] for name in SCHEMA["$defs"]["DevelopmentSettings"]["properties"]},
                    "fonts": self.fonts, "glyphs": self.glyphs, "systemStrings": self.system_strings,
                    "diagnostics": self.asset_diagnostics + self.snapshots.diagnostics}

    def identity(self, data, permission=None):
        return self.snapshots.identity(data["pluginId"], data["digest"], permission)

    def snapshot(self):
        with self.snapshots.lock:
            return {"revision": self.snapshots.revision, "plugins": list(self.snapshots.plugins.values()),
                    "diagnostics": self.asset_diagnostics + self.snapshots.diagnostics}

    def resource_identity(self, data):
        if data["url"].startswith(("https://", "http://")):
            self.identity(data, "network")
        else:
            self.identity(data, "library")
            if data.get("publicOnly", False):
                raise PermissionError("public-only media does not accept local resources")
            self.library.resolve(data["url"])
        if data["kind"] == "media":
            self.identity(data, "media")
        return data

    def storage(self, data):
        self.identity(data)
        if len(json.dumps(data["value"], ensure_ascii=False).encode()) > self.config["storage_bytes"]:
            raise ValueError("plugin storage exceeds 256 KiB")
        self.profiles.update(lambda value: value["storage"].__setitem__(data["pluginId"], data["value"]))

    def place_search(self, data):
        profile = self.profiles.read()
        if profile["environment"].get("networkMode") == "fixture":
            return {"items": [], "offline": True}
        endpoint = profile["settings"]["system"].get("location.search_url", packages.SYSTEM_SETTINGS["fields"]["location.search_url"]["default"])
        items = search_places(self.network, endpoint, data["query"], data.get("locale", profile["environment"].get("locale", "en")))
        return request("PlaceSearchResponse", {"items": items})

    def profile(self, data, persist=True):
        if data.get("replaceEnvironment") and "environment" not in data:
            raise ValueError("environment is required for scene replacement")
        if any(plugin not in self.factory for plugin in data.get("environment", {}).get("systemSimulation", [])):
            raise ValueError("system simulation only accepts fixed factory plugin IDs")
        setting_patch = copy.deepcopy(data.get("settings", {}))
        resets = data.get("reset", [])
        for entry in resets:
            scope = entry["scope"]
            plugin = self.snapshots.committed.get(scope) or self.snapshots.plugins.get(scope)
            if scope != "system" and not plugin:
                raise ValueError("unknown reset scope")
            fields = packages.SYSTEM_SETTINGS["fields"] if scope == "system" else plugin["manifest"].get("settings", {})
            if "key" in entry and entry["key"] not in fields:
                raise ValueError("unknown reset setting")
        if set(setting_patch) - {"system", "plugins"}:
            raise ValueError("settings must contain system and/or plugins maps")
        for scope, fields in setting_patch.items():
            if not isinstance(fields, dict):
                raise ValueError("settings scope must be an object")
            groups = {"system": fields} if scope == "system" else fields
            for plugin_id, values in groups.items():
                if not isinstance(values, dict):
                    raise ValueError("settings must be an object")
                if plugin_id == "system":
                    schema_fields = packages.SYSTEM_SETTINGS["fields"]
                else:
                    plugin = self.snapshots.committed.get(plugin_id) or self.snapshots.plugins.get(plugin_id)
                    if not plugin:
                        if not persist:
                            continue
                        raise ValueError("unknown settings plugin")
                    schema_fields = plugin["manifest"].get("settings", {})
                for key, value in values.items():
                    if key not in schema_fields:
                        raise ValueError("unknown setting")
                    field = schema_fields[key]
                    validate_setting(key, {**field, "default": value}, plugin_id == "system")
                    if field["type"] == "text" and len(value.encode("utf-16-le")) // 2 > field.get("maxLength", 1024):
                        raise ValueError("setting exceeds native UTF-16 length limit")
                    if field.get("source") == "timezone" and value:
                        aliases = dict(line.split("\t", 1) for line in (ROOT / "shared/tzdata/links.tsv").read_text().splitlines() if line)
                        normalized = aliases.get(value, value)
                        if any(part in ("", ".", "..") for part in normalized.split("/")) or not (ROOT / "shared/tzdata/zoneinfo" / normalized).is_file():
                            raise ValueError("unknown timezone option")
                        values[key] = normalized
                    elif field.get("source") == "region" and value:
                        codes = {line.split("\t")[1] for line in (ROOT / "shared/tzdata/zones.tsv").read_text().splitlines() if line}
                        if value.upper() not in codes:
                            raise ValueError("unknown region option")
                        values[key] = value.upper()
                    elif field.get("source") == "place" and value:
                        normalized = value.strip()
                        if not 1 <= len(normalized.encode("utf-16-le")) // 2 <= 160 or any(ord(c) < 32 for c in normalized):
                            raise ValueError("invalid place option")
                        values[key] = normalized
        def merge(profile):
            for entry in resets:
                fields = profile["settings"]["system"] if entry["scope"] == "system" else profile["settings"]["plugins"].setdefault(entry["scope"], {})
                if "key" in entry:
                    fields.pop(entry["key"], None)
                else:
                    fields.clear()
            if data.get("replaceEnvironment"):
                profile["environment"] = default_environment()
            profile["environment"].update(data.get("environment", {}))
            profile["settings"]["system"].update(setting_patch.get("system", {}))
            for plugin, values in setting_patch.get("plugins", {}).items():
                profile["settings"]["plugins"].setdefault(plugin, {}).update(values)
            effective = {key: field["default"] for key, field in packages.SYSTEM_SETTINGS["fields"].items()}
            effective.update(profile["settings"]["system"])
            for low, high in packages.SYSTEM_SETTINGS.get("constraints", []):
                if packages.SYSTEM_SETTINGS["fields"][low]["type"] == "time":
                    if effective[low] == effective[high]:
                        raise ValueError("time window start and end must differ")
                elif effective[low] >= effective[high]:
                    raise ValueError("setting must remain below " + high)
        if persist:
            self.profiles.update(merge)
            profile = self.profiles.read()
            return {"ok": True, "settings": profile["settings"], "environment": profile["environment"]}
        else:
            merge(self.profiles.read())

    def ticket(self, data):
        self.resource_identity(data)
        with self.ticket_lock:
            now = time.monotonic()
            self.tickets = {key: value for key, value in self.tickets.items() if value[0] > now}
            if len(self.tickets) >= self.config["max_tickets"]:
                raise ValueError("resource ticket limit")
            token = secrets.token_urlsafe(24)
            self.tickets[token] = (now + self.config["ticket_lifetime_s"], copy.deepcopy(data))
        return {"url": "/api/resource/" + token}

    def read_ticket(self, token):
        with self.ticket_lock:
            value = self.tickets.get(token)
            if not value or value[0] < time.monotonic():
                raise PermissionError("expired resource ticket")
            return self.resource_identity(copy.deepcopy(value[1]))

    def watch(self):
        while not self.stopped.wait(self.config["watch_ms"] / 1000):
            try:
                self.snapshots.refresh()
            except Exception:
                # A malformed candidate never replaces the last published snapshot.
                continue

    def close(self):
        self.stopped.set()
        self.network.dns.close()
        with self.snapshots.changed:
            self.snapshots.changed.notify_all()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "ThinkOS-Simulator/1"

    def handle(self):
        try:
            super().handle()
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

    @property
    def app(self):
        return self.server.app

    def log_message(self, format, *args):
        # URLs can contain resource tickets; do not log request paths or tokens.
        return

    def guard(self, authenticated=True):
        if any(len(self.headers.get_all(name, [])) > 1 for name in ("Host", "Origin", "X-ThinkOS-Session", "Sec-Fetch-Site")):
            raise PermissionError("duplicate security header")
        host = self.headers.get("Host", "")
        port = self.server.server_port
        accepted = {"127.0.0.1:" + str(port), "localhost:" + str(port)}
        if host not in accepted:
            raise PermissionError("invalid host")
        origin = self.headers.get("Origin")
        if origin is not None and origin != "http://" + host:
            raise PermissionError("cross-origin request rejected")
        if self.headers.get("Sec-Fetch-Site") not in (None, "same-origin", "none"):
            raise PermissionError("cross-site request rejected")
        if authenticated and not secrets.compare_digest(self.headers.get("X-ThinkOS-Session", ""), self.app.token):
            raise PermissionError("invalid session")

    def common_headers(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self' blob:; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'")

    def json(self, status, value):
        body = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode()
        self.send_response(status)
        self.common_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def failure(self, error):
        if isinstance(error, PermissionError):
            self.json(403, {"error": {"code": "forbidden", "message": str(error)}})
        elif isinstance(error, NetworkError):
            self.json(429 if error.code in ("busy", "dns_busy") else 504 if error.code in ("timeout", "dns_timeout") else 400, {"error": {"code": error.code, "message": str(error)}})
        elif isinstance(error, (socket.timeout, TimeoutError)):
            self.json(504, {"error": {"code": "timeout", "message": "network timeout"}})
        elif isinstance(error, ssl.SSLError):
            self.json(502, {"error": {"code": "tls", "message": "TLS verification failed"}})
        elif isinstance(error, (ValueError, KeyError, RecursionError)):
            self.json(400, {"error": {"code": "invalid", "message": str(error)[:300]}})
        else:
            self.json(502, {"error": {"code": "unavailable", "message": "local service unavailable"}})

    def read_json(self):
        if len(self.headers.get_all("Content-Length", [])) != 1:
            raise ValueError("one Content-Length is required")
        if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Type", "").split(";", 1)[0].strip() != "application/json":
            raise ValueError("JSON with Content-Length is required")
        length = self.headers.get("Content-Length", "")
        if not length.isdigit() or not 0 < int(length) <= self.app.config["request_bytes"]:
            raise ValueError("request byte limit exceeded")
        raw = self.rfile.read(int(length))
        if len(raw) != int(length):
            raise ValueError("incomplete request body")
        return loads(raw)

    def do_GET(self):
        try:
            path = urlsplit(self.path).path
            self.guard(authenticated=path.startswith("/api/") and path != "/api/session" and not path.startswith("/api/resource/"))
            if path == "/api/session":
                self.json(200, self.app.session())
            elif path == "/api/snapshot":
                self.json(200, self.app.snapshot())
            elif path == "/api/events":
                self.events()
            elif path.startswith("/api/resource/"):
                data = self.app.read_ticket(path.removeprefix("/api/resource/"))
                if self.headers.get("Range"):
                    data["range"] = self.headers["Range"]
                request("ResourceRequest", data)
                self.resource(data)
            elif path.startswith("/api/"):
                self.json(404, {"error": {"code": "not_found", "message": "Unknown development endpoint"}})
            else:
                self.static(path)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception as error:
            self.failure(error)

    def do_POST(self):
        try:
            self.guard()
            path = urlsplit(self.path).path
            names = {"/api/http": "HttpRequest", "/api/dns": "DnsRequest", "/api/storage": "StorageRequest",
                     "/api/place-search": "PlaceSearchRequest",
                     "/api/profile": "ProfileRequest", "/api/resource": "ResourceRequest", "/api/resource-ticket": "ResourceRequest", "/api/library": "LibraryRequest", "/api/runtime": "RuntimeRequest"}
            if path not in names:
                raise ValueError("unknown development endpoint")
            data = request(names[path], self.read_json())
            if path == "/api/http":
                self.app.identity(data, "network")
                self.json(200, self.app.network.http(data))
            elif path == "/api/dns":
                plugin = self.app.identity(data, "network")
                if plugin["manifest"]["api"] < 10:
                    raise PermissionError("net.resolve requires API 10")
                with self.app.network.slot(data["pluginId"]):
                    entries = self.app.network.dns.resolve(data["host"], self.app.config["network_timeout_s"])
                self.json(200, {"entries": entries})
            elif path == "/api/storage":
                self.app.storage(data)
                self.json(200, {"ok": True})
            elif path == "/api/library":
                self.app.identity(data, "library")
                with self.app.network.slot(data["pluginId"]):
                    self.json(200, self.app.library.list(data["options"]))
            elif path == "/api/place-search":
                self.json(200, self.app.place_search(data))
            elif path == "/api/runtime":
                self.app.snapshots.runtime(data["pluginId"], data["digest"], data["action"])
                with self.app.ticket_lock:
                    self.app.tickets = {key: value for key, value in self.app.tickets.items()
                                        if value[1]["pluginId"] != data["pluginId"] or
                                        (value[1]["digest"] == data["digest"] if data["action"] == "commit" else value[1]["digest"] != data["digest"])}
                self.json(200, {"ok": True})
            elif path == "/api/profile":
                self.json(200, self.app.profile(data))
            elif path == "/api/resource-ticket":
                self.json(200, self.app.ticket(data))
            else:
                self.app.resource_identity(data)
                self.resource(data)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception as error:
            self.close_connection = True  # Reject unread/oversized bodies without HTTP desynchronization.
            self.failure(error)

    def static(self, path):
        path = unquote(path)
        if path == "/":
            path = "/index.html"
        relative = packages.safe_relative(path.removeprefix("/"))
        target = self.app.dist / str(relative)
        if not target.is_file():
            self.json(404, {"error": {"code": "not_found", "message": "Build the frontend with npm run build --prefix simulator"}})
            return
        if any((self.app.dist / Path(*relative.parts[:index])).is_symlink() for index in range(1, len(relative.parts) + 1)):
            raise PermissionError("static symlinks are forbidden")
        body = read_regular(target, 64 * 1024 * 1024)
        mime = "application/wasm" if target.suffix == ".wasm" else mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        self.send_response(200)
        self.common_headers()
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def events(self):
        if not self.app.clients.acquire(blocking=False):
            raise NetworkError("busy", "too many event clients")
        try:
            self.send_response(200)
            self.common_headers()
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Connection", "close")
            self.end_headers()
            revision = -1
            while not self.app.stopped.is_set():
                with self.app.snapshots.changed:
                    current = self.app.snapshots.revision
                    if current == revision:
                        self.app.snapshots.changed.wait(self.app.config["sse_heartbeat_s"])
                        current = self.app.snapshots.revision
                    diagnostics = list(self.app.snapshots.diagnostics)
                if current != revision:
                    self.wfile.write(("event: revision\ndata: " + json.dumps({"revision": current, "diagnostics": diagnostics}) + "\n\n").encode())
                    revision = current
                else:
                    self.wfile.write(b": heartbeat\n\n")
                self.wfile.flush()
        finally:
            self.close_connection = True
            self.app.clients.release()

    def resource(self, data):
        if not data["url"].startswith(("http://", "https://")):
            return self.local_resource(data)
        allow_public = self.app.profiles.read()["settings"].get("system", {}).get("media.public_http", False)
        sent = False
        try:
            with self.app.network.resource(data, allow_public) as (response, limit):
                self.send_response(response.status)
                self.common_headers()
                self.send_header("Content-Type", response.getheader("Content-Type", "application/octet-stream"))
                for name in ("Content-Range", "Accept-Ranges", "Content-Length"):
                    value = response.getheader(name)
                    if value:
                        self.send_header(name, value)
                self.send_header("Connection", "close")
                self.end_headers()
                sent = True
                size = 0
                while size <= limit:
                    self.app.resource_identity(data)  # A hot-replaced plugin cannot keep an old stream alive.
                    block = response.read1(min(65536, limit + 1 - size))
                    if not block:
                        break
                    size += len(block)
                    if size > limit:
                        raise NetworkError("limit", "Resource exceeds byte limit")
                    self.wfile.write(block)
                    self.wfile.flush()
        except Exception:
            if not sent:
                raise
        finally:
            self.close_connection = True

    def local_resource(self, data):
        mime = self.app.library.mime(data["url"], data["kind"])
        with self.app.network.slot(data["pluginId"]), self.app.library.opened(data["url"]) as (descriptor, info, _):
            limit = self.app.config["image_bytes" if data["kind"] == "image" else "media_bytes"]
            start, end, partial = 0, info.st_size - 1, False
            if data.get("range"):
                first, last = data["range"].removeprefix("bytes=").split("-", 1)
                if first:
                    start, end = int(first), min(end, int(last)) if last else end
                elif last:
                    start = max(0, info.st_size - int(last))
                else:
                    raise ValueError("invalid resource range")
                if start > end or start >= info.st_size:
                    self.send_response(416)
                    self.common_headers()
                    self.send_header("Content-Range", "bytes */" + str(info.st_size))
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                end = min(end, start + limit - 1)
                partial = True
            elif info.st_size > limit:
                raise NetworkError("limit", "Local resource exceeds byte limit; use Range")
            size = max(0, end - start + 1)
            self.send_response(206 if partial else 200)
            self.common_headers()
            self.send_header("Content-Type", mime)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(size))
            if partial:
                self.send_header("Content-Range", "bytes " + str(start) + "-" + str(end) + "/" + str(info.st_size))
            self.end_headers()
            os.lseek(descriptor, start, os.SEEK_SET)
            try:
                while size:
                    self.app.resource_identity(data)
                    block = os.read(descriptor, min(65536, size))
                    if not block:
                        break
                    self.wfile.write(block)
                    size -= len(block)
            except Exception:
                self.close_connection = True


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, app, port=0):
        self.app = app
        self.worker_slots = threading.BoundedSemaphore(app.config["max_workers"])
        super().__init__(("127.0.0.1", port), Handler)
        app.network.forbidden_port = self.server_port
        app.httpd = self

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(self.app.config["socket_timeout_s"])
        return connection, address

    def process_request(self, request, client_address):
        if not self.worker_slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.worker_slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.worker_slots.release()


def serve(sources, config=None):
    app = Gateway(sources, config)
    if not (app.dist / "index.html").is_file():
        app.close()
        raise ValueError("Frontend not built: run npm run build --prefix simulator")
    server = Server(app, app.config["port"])
    threading.Thread(target=app.watch, name="ThinkOS-simulator-watch", daemon=True).start()
    print("ThinkOS simulator: http://127.0.0.1:" + str(server.server_port), flush=True)
    for diagnostic in app.session()["diagnostics"]:
        print("  " + diagnostic, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        app.close()
        server.server_close()
