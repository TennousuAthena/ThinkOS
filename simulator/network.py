"""Bounded host transports mirroring ThinkOS's plugin and media policies.

Policy references: android/.../net/{PluginHttp,LocalNetworkPolicy,
PublicNetworkPolicy,PluginDnsResolver,MediaHttpClient}. No browser CORS bypass
or environment proxy is used. Host TLS/DNS remain the host's, not Android's.
"""
from contextlib import contextmanager
import http.client
import ipaddress
import queue
import re
import socket
import ssl
import threading
import time
from urllib.parse import urljoin, urlsplit


class NetworkError(ValueError):
    def __init__(self, code, message):
        self.code = code
        super().__init__(message)


def private_address(value):
    address = ipaddress.ip_address(value.split("%", 1)[0])
    if address.is_unspecified or address.is_multicast:
        return False
    if address.version == 4:
        return any(address in ipaddress.ip_network(network) for network in
                   ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16"))
    return address == ipaddress.ip_address("::1") or any(address in ipaddress.ip_network(network) for network in ("fc00::/7", "fe80::/10", "fec0::/10"))


def public_address(value):
    if "%" in value:
        return False
    address = ipaddress.ip_address(value)
    if private_address(value) or address.is_multicast or address.is_unspecified:
        return False
    b = address.packed
    if address.version == 4:
        return not (b[0] in (0, 10, 127) or b[0] >= 224 or b[0] == 100 and 64 <= b[1] <= 127 or
                    b[0] == 169 and b[1] == 254 or b[0] == 172 and 16 <= b[1] <= 31 or
                    b[0] == 192 and (b[1] == 168 or b[1] == 0 and b[2] in (0, 2) or b[1] == 88 and b[2] == 99) or
                    b[0] == 198 and (b[1] in (18, 19) or b[1] == 51 and b[2] == 100) or
                    b[0] == 203 and b[1] == 0 and b[2] == 113)
    return b[0] & 0xe0 == 0x20 and not (b[0] == 0x20 and b[1] == 1 and (b[2] < 2 or b[2] == 0x0d and b[3] == 0xb8) or
                                       b[0] == 0x20 and b[1] == 2 or b[0] == 0x3f and b[1] == 0xff and b[2] & 0xf0 == 0)


def normalize_host(host):
    if not isinstance(host, str) or not host or len(host) > 253 or any(ord(c) < 33 or ord(c) > 126 for c in host):
        raise NetworkError("dns_invalid", "dns invalid host")
    result = host.removesuffix(".")
    labels = result.split(".")
    if any(not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label) for label in labels) or all(re.fullmatch(r"(?:0[xX][0-9A-Fa-f]+|[0-9]+)", label) for label in labels):
        raise NetworkError("dns_invalid", "dns invalid host")
    return result.lower()


class DnsPool:
    """Two daemon workers and two waiting jobs; libc cannot grow threads on timeout."""
    def __init__(self, lookup=None, canonical=None):
        self.lookup = lookup or (lambda host: list(dict.fromkeys(row[4][0] for row in socket.getaddrinfo(host, None, type=socket.SOCK_STREAM))))
        self.canonical = canonical or (lambda address: socket.gethostbyaddr(address)[0])
        self.jobs = queue.Queue(2)
        self.closed = threading.Event()
        for index in range(2):
            threading.Thread(target=self._work, name="ThinkOS-simulator-DNS-" + str(index), daemon=True).start()

    def _work(self):
        while not self.closed.is_set():
            try:
                function, done, result, deadline = self.jobs.get(timeout=.2)
            except queue.Empty:
                continue
            try:
                if time.monotonic() < deadline:
                    result.append((True, function()))
            except Exception:
                result.append((False, None))
            finally:
                done.set()
                self.jobs.task_done()

    def call(self, function, timeout):
        done, result = threading.Event(), []
        deadline = time.monotonic() + timeout
        try:
            self.jobs.put_nowait((function, done, result, deadline))
        except queue.Full:
            raise NetworkError("dns_busy", "dns busy") from None
        if not done.wait(timeout):
            raise NetworkError("dns_timeout", "dns timeout")
        if not result or not result[0][0]:
            raise NetworkError("dns_unavailable", "dns unavailable")
        return result[0][1]

    def addresses(self, host, timeout=3):
        addresses = self.call(lambda: self.lookup(host), timeout)
        if not addresses or len(addresses) > 128:
            raise NetworkError("dns_unavailable", "dns unavailable")
        return addresses

    def resolve(self, host, timeout=3):
        host = normalize_host(host)
        def discover():
            unique = {}
            for value in self.lookup(host):
                address = ipaddress.ip_address(value)
                unique.setdefault(address.packed, value)
                if len(unique) == 8:
                    break
            values = list(unique.values())
            if not values:
                raise ValueError("no addresses")
            result = []
            for value in values:
                numeric = str(ipaddress.ip_address(value))
                entry = {"address": numeric}
                try:
                    name = normalize_host(self.canonical(value))
                    if any(ipaddress.ip_address(other) == ipaddress.ip_address(value) for other in self.lookup(name)):
                        entry["name"] = name
                except Exception:
                    pass
                result.append(entry)
            return result
        return self.call(discover, timeout)

    def close(self):
        self.closed.set()


def checked_url(url, public_only=False):
    if not isinstance(url, str) or not 1 <= len(url) <= 8192 or any(ord(c) < 33 or ord(c) == 127 for c in url):
        raise NetworkError("policy", "Invalid URL")
    parsed = urlsplit(url)
    try:
        if not url.startswith(("http://", "https://")) or not parsed.hostname or parsed.username is not None or parsed.password is not None or parsed.port is not None and not 1 <= parsed.port <= 65535:
            raise ValueError()
        parsed.hostname.encode("ascii")
    except (ValueError, UnicodeError):
        raise NetworkError("policy", "Only HTTP(S) URLs without credentials are supported") from None
    if public_only:
        host = parsed.hostname.rstrip(".").lower()
        if parsed.scheme != "https" or parsed.fragment or "%" in host or any(host == suffix or host.endswith("." + suffix) for suffix in ("localhost", "local", "internal", "lan", "home", "onion")):
            raise NetworkError("policy", "Public-only media requires public HTTPS")
        if re.fullmatch(r"[0-9.]+", host):
            try:
                if str(ipaddress.IPv4Address(host)) != host or not public_address(host):
                    raise ValueError()
            except ValueError:
                raise NetworkError("policy", "Public-only media rejects non-public address") from None
    return parsed


class PinnedConnection(http.client.HTTPConnection):
    def __init__(self, host, port, address, timeout, secure=False, deadline=None):
        super().__init__(host, port, timeout=timeout)
        self.address, self.secure = address, secure
        self.transport = None
        self.expired = False
        self.timer = threading.Timer(max(.001, deadline - time.monotonic()), self.abort) if deadline is not None else None
        if self.timer:
            self.timer.daemon = True
            self.timer.start()

    def abort(self):
        self.expired = True
        if self.transport is not None:
            try:
                self.transport.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def finish(self):
        if self.timer:
            self.timer.cancel()
        super().close()

    def connect(self):
        self.sock = socket.create_connection((self.address, self.port), self.timeout)
        self.transport = self.sock
        if self.secure:
            try:
                self.sock = ssl.create_default_context().wrap_socket(self.sock, server_hostname=self.host)
                self.transport = self.sock
            except Exception:
                self.sock.close()
                raise
        if self.expired:
            self.sock.close()
            raise NetworkError("timeout", "network deadline exceeded")


class Network:
    def __init__(self, settings, dns=None):
        self.settings, self.dns = settings, dns or DnsPool()
        self.lock, self.active, self.total = threading.Lock(), {}, 0
        self.forbidden_port = None

    @contextmanager
    def slot(self, plugin):
        with self.lock:
            if self.total >= self.settings["network_global"] or self.active.get(plugin, 0) >= self.settings["network_per_plugin"]:
                raise NetworkError("busy", "network busy")
            self.total += 1
            self.active[plugin] = self.active.get(plugin, 0) + 1
        try:
            yield
        finally:
            with self.lock:
                self.total -= 1
                self.active[plugin] -= 1

    def _open(self, method, url, body=None, headers=None, resource=False, public_only=False, allow_public=False, timeout_ms=None, same_origin=False):
        budget = self.settings["resource_total_timeout_s" if resource else "http_total_timeout_s"]
        deadline = time.monotonic() + (min(budget, timeout_ms / 1000) if timeout_ms else budget)
        headers = dict(headers or {})
        forbidden = {"host", "connection", "content-length", "accept-encoding"}
        if resource:
            forbidden |= {"transfer-encoding", "content-type"}
        for key, value in headers.items():
            if not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", key) or key.lower() in forbidden or any(ord(c) < 32 or ord(c) == 127 for c in value):
                raise NetworkError("policy", "Invalid request header")
        payload = body.encode("utf-8") if body is not None else None
        if payload is not None and len(payload) > self.settings["body_bytes"]:
            raise NetworkError("limit", "HTTP request body exceeds 1MB")
        for redirect in range(4):
            if time.monotonic() >= deadline:
                raise NetworkError("timeout", "network deadline exceeded")
            parsed = checked_url(url, public_only)
            addresses = self.dns.addresses(parsed.hostname, min(self.settings["network_timeout_s"], deadline - time.monotonic()))
            if public_only and not all(public_address(address) for address in addresses):
                raise NetworkError("policy", "Public-only media rejects non-public address")
            if parsed.scheme == "http" and not allow_public and not all(private_address(address) for address in addresses):
                raise NetworkError("policy", "Cleartext HTTP is restricted to local/private addresses")
            port = parsed.port or (443 if parsed.scheme == "https" else 80)
            if port == self.forbidden_port and any(ipaddress.ip_address(address).is_loopback for address in addresses):
                raise NetworkError("policy", "Simulator control service is not a plugin resource")
            connection = PinnedConnection(parsed.hostname, port, addresses[0], min(self.settings["network_timeout_s"], max(.001, deadline - time.monotonic())), parsed.scheme == "https", deadline)
            try:
                outgoing = {"Accept-Encoding": "identity", "Host": parsed.netloc, **headers}
                target = (parsed.path or "/") + ("?" + parsed.query if parsed.query else "")
                connection.request(method, target, payload, outgoing)
                if connection.sock:
                    connection.sock.settimeout(self.settings["resource_timeout_s"] if resource else self.settings["network_timeout_s"])
                response = connection.getresponse()
                # Other simulator profiles have distinct tokens and stores. A
                # plugin's LAN permission must not become a control-plane relay.
                if response.getheader("Server", "").startswith("ThinkOS-Simulator/"):
                    response.close()
                    raise NetworkError("policy", "Simulator control services are not plugin resources")
                if response.status not in (301, 302, 303, 307, 308):
                    if connection.expired:
                        raise NetworkError("timeout", "network deadline exceeded")
                    return connection, response
                if redirect >= 3:
                    raise NetworkError("policy", "Too many redirects")
                location = response.getheader("Location")
                if not location:
                    raise NetworkError("policy", "Redirect missing Location")
                next_url = urljoin(url, location)
                next_parsed = checked_url(next_url, public_only)
                if same_origin:
                    def origin(uri):
                        return uri.scheme, uri.hostname, uri.port or (443 if uri.scheme == 'https' else 80)
                    if origin(parsed) != origin(next_parsed):
                        raise NetworkError('policy', 'Cross-origin redirect rejected')
                if parsed.scheme == "https" and next_parsed.scheme == "http":
                    raise NetworkError("policy", "HTTPS downgrade rejected")
                if resource and (parsed.scheme, parsed.hostname, parsed.port) != (next_parsed.scheme, next_parsed.hostname, next_parsed.port):
                    portable = {"host", "user-agent", "accept", "accept-encoding", "accept-language", "range", "if-range", "referer", "origin", "connection", "content-length", "content-type"}
                    headers = {key: value for key, value in headers.items() if key.lower() in portable}
                if response.status == 303 or response.status in (301, 302) and method == "POST":
                    method, payload = "GET", None
                url = next_url
                response.close()
                connection.finish()
            except Exception:
                connection.finish()
                raise
        raise NetworkError("policy", "Too many redirects")

    def http(self, data):
        with self.slot(data["pluginId"]):
            connection, response = self._open(data["method"], data["url"], data.get("body"), data.get("headers"), timeout_ms=data.get("timeoutMs"), same_origin=data.get("sameOrigin", False))
            try:
                limit = min(data.get("maxBytes", self.settings["body_bytes"]), self.settings["body_bytes"])
                content = response.read(limit + 1)
                if connection.expired:
                    raise NetworkError("timeout", "network deadline exceeded")
                if len(content) > limit:
                    raise NetworkError("limit", "HTTP response exceeds byte limit")
                headers = {}
                for name, value in response.getheaders():
                    headers[name] = headers[name] + ", " + value if name in headers else value
                return {"status": response.status, "headers": headers, "body": content.decode("utf-8", errors="replace")}
            finally:
                response.close()
                connection.finish()

    @contextmanager
    def resource(self, data, allow_public=False):
        headers = dict(data.get("headers", {}))
        if data.get("originUrl"):
            origin, target = checked_url(data["originUrl"]), checked_url(data["url"])
            def authority(url):
                return url.scheme, url.hostname, url.port or (443 if url.scheme == "https" else 80)
            if authority(origin) != authority(target):
                portable = {"user-agent", "accept", "accept-language", "referer", "origin"}
                headers = {key: value for key, value in headers.items() if key.lower() in portable}
        if data.get("range"):
            headers["Range"] = data["range"]
        with self.slot(data["pluginId"]):
            connection, response = self._open("GET", data["url"], headers=headers, resource=True,
                                             public_only=data.get("publicOnly", False),
                                             allow_public=allow_public and data["kind"] == "media")
            try:
                limit = self.settings["image_bytes" if data["kind"] == "image" else "media_bytes"]
                length = response.getheader("Content-Length")
                if length and (not length.isdigit() or int(length) > limit):
                    raise NetworkError("limit", "Resource exceeds byte limit")
                if data["kind"] == "image" and not response.getheader("Content-Type", "").lower().startswith("image/"):
                    raise NetworkError("format", "Image response must have an image Content-Type")
                yield response, limit
            finally:
                response.close()
                connection.finish()
