import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import json
import os
from pathlib import Path
from unittest.mock import patch
import socket
import tempfile
import threading
import time
import unittest
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from simulator.contracts import loads, request, validate
from simulator.generated.protocol import SCHEMA
from simulator.network import DnsPool, Network, NetworkError, checked_url, normalize_host, private_address, public_address
from simulator.library import PRIVATE_ROOT
from simulator.places import parse as parse_places, query_variants, search as search_places
from simulator.server import Gateway, Server, settings
from simulator.snapshots import Snapshots, make_snapshot


def plugin(root, permissions=None):
    root.mkdir()
    (root / "manifest.json").write_text(json.dumps({"id": "probe", "name": "Probe", "version": "1.0.0", "api": 10,
        "main": "main.lua", "permissions": permissions if permissions is not None else ["network", "media"],
        "settings": {"refresh": {"type": "number", "label": "Refresh", "default": 10, "min": 1, "max": 100}}}))
    (root / "main.lua").write_text('tos.state.set({hello="world"})')
    return root


class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.source = plugin(self.root / "plugin")

    def tearDown(self):
        self.temp.cleanup()

    def test_fixed_factory_digest_and_final_bytes(self):
        first = make_snapshot(self.source, {})
        self.assertFalse(first["factory"])
        trusted = make_snapshot(self.source, {"probe": first["digest"]})
        self.assertTrue(trusted["factory"])
        self.assertEqual(base64.b64decode(trusted["files"]["main.lua"]), (self.source / "main.lua").read_bytes())
        (self.source / "main.lua").write_text('tos.state.set({hello="changed"})')
        self.assertFalse(make_snapshot(self.source, {"probe": first["digest"]})["factory"])

    def test_bad_candidate_and_id_change_preserve_last_snapshot(self):
        snapshots = Snapshots([self.source])
        original = snapshots.plugins["probe"]
        (self.source / "manifest.json").write_text('{"id":')
        snapshots.refresh()
        self.assertEqual(snapshots.plugins["probe"], original)
        self.assertTrue(snapshots.diagnostics)
        manifest = original["manifest"].copy()
        manifest["id"] = "changed"
        (self.source / "manifest.json").write_text(json.dumps(manifest))
        snapshots.refresh()
        self.assertEqual(list(snapshots.plugins), ["probe"])

    def test_watch_publishes_new_revision_once(self):
        snapshots = Snapshots([self.source])
        before = snapshots.revision
        snapshots.refresh()
        self.assertEqual(before, snapshots.revision)
        (self.source / "main.lua").write_text('tos.state.set({new=true})')
        snapshots.refresh()
        self.assertEqual(before + 1, snapshots.revision)
        snapshots.refresh()
        self.assertEqual(before + 1, snapshots.revision)

    def test_last_committed_generation_survives_candidates_until_commit(self):
        snapshots = Snapshots([self.source])
        old = snapshots.plugins["probe"]["digest"]
        for index in range(3):
            (self.source / "main.lua").write_text('tos.state.set({revision=' + str(index) + '})')
            snapshots.refresh()
            self.assertEqual(snapshots.identity("probe", old)["digest"], old)
        latest = snapshots.plugins["probe"]["digest"]
        snapshots.runtime("probe", latest, "commit")
        snapshots.runtime("probe", old, "release")
        self.assertEqual(snapshots.committed["probe"]["digest"], latest)
        with self.assertRaises(ValueError):
            snapshots.identity("probe", old)

    def test_symlinks_special_files_case_collisions_and_oversize_rejected(self):
        secret = self.root / "secret.lua"
        secret.write_text("not a plugin")
        target = self.source / "link.lua"
        target.symlink_to(secret)
        with self.assertRaises(ValueError):
            make_snapshot(self.source, {})
        target.unlink()
        os.mkfifo(target)
        with self.assertRaises(ValueError):
            make_snapshot(self.source, {})
        target.unlink()
        target.write_bytes(b"x" * (256 * 1024 + 1))
        with self.assertRaises(ValueError):
            make_snapshot(self.source, {})

    def test_duplicate_and_nonfinite_json_rejected(self):
        for content in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":Infinity}'):
            with self.assertRaises(ValueError):
                loads(content)
        with self.assertRaises(ValueError):
            validate(["network", "network"], SCHEMA["$defs"]["RegistryCatalogItem"]["properties"]["permissions"])

    def test_duplicate_source_id_fails_initially_and_never_shadows_running_owner(self):
        second = plugin(self.root / "second")
        with self.assertRaisesRegex(ValueError, "duplicate plugin id"):
            Snapshots([self.source, second])
        manifest = (second / "manifest.json").read_text()
        (second / "manifest.json").write_text('{broken')
        snapshots = Snapshots([self.source, second])
        before = snapshots.plugins["probe"]
        (second / "manifest.json").write_text(manifest)
        snapshots.refresh()
        self.assertEqual(snapshots.plugins["probe"], before)
        self.assertTrue(any("duplicate plugin id" in error for error in snapshots.diagnostics))


class PlaceTests(unittest.TestCase):
    def test_native_parse_limits_hierarchy_and_malformed_rows(self):
        valid = {"name": "海淀区", "latitude": 39.96, "longitude": 116.3, "elevation": 48, "timezone": "Asia/Shanghai", "admin1": "北京市", "admin2": "北京市", "admin3": "海淀区", "country": "中国"}
        row = parse_places(json.dumps({"results": [valid]}))[0]
        self.assertEqual(row["detail"], "北京市, 中国")
        self.assertEqual(row["location"]["source"], "manual")
        invalid = [{**valid, "latitude": 91}, {**valid, "longitude": -181}, {**valid, "latitude": None}, {**valid, "name": ""}, {**valid, "timezone": ""}, {**valid, "elevation": 10001}, 3]
        self.assertEqual(parse_places(json.dumps({"results": invalid})), [])
        self.assertEqual(len(parse_places(json.dumps({"results": [valid] * 30}))), 20)
        self.assertEqual(parse_places(json.dumps({"results": [{**valid, "name": "\n" + "界" * 200}]}))[0]["location"]["name"], "界" * 160)
        for bad in ('[]', '{"error":true}', '{"results":[NaN]}', '{"results":[],"results":[]}'):
            with self.assertRaises(ValueError):
                parse_places(bad)

    def test_native_chinese_variants_dedup_first_failure_and_later_failure(self):
        self.assertEqual(query_variants("阜新"), ["阜新", "阜新市", "阜新县", "阜新区"])
        for query in ("海淀区", "阜新 shi", "New York"):
            self.assertEqual(query_variants(query), [query])
        asked = []
        class Transport:
            def http(self, data):
                from urllib.parse import parse_qs, urlsplit
                query = parse_qs(urlsplit(data["url"]).query)
                asked.append(query)
                if query["name"] == ["阜新县"]:
                    raise OSError("fixture")
                return {"status": 200, "body": json.dumps({"results": [{"name": "阜新市", "latitude": 42, "longitude": 121, "timezone": "Asia/Shanghai"}]})}
        rows = search_places(Transport(), "https://example.invalid/search?existing=1", "阜新", "zh-Hans")
        self.assertEqual(len(rows), 1)
        self.assertEqual([query["name"][0] for query in asked], query_variants("阜新"))
        self.assertTrue(all(query["language"] == ["zh"] and query["count"] == ["20"] for query in asked))
        with patch.object(Transport, "http", side_effect=OSError("offline")):
            with self.assertRaises(OSError):
                search_places(Transport(), "https://example.invalid/search", "阜新", "zh")
        with self.assertRaises(ValueError):
            search_places(Transport(), "https://example.invalid/search#fragment", "London", "en")


class Origin(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/drip":
            self.send_response(200)
            self.end_headers()
            for _ in range(30):
                try:
                    self.wfile.write(b"x")
                    self.wfile.flush()
                except OSError:
                    break
                time.sleep(.02)
        elif self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "/good")
            self.end_headers()
        elif self.path == "/loop":
            self.send_response(302)
            self.send_header("Location", "/loop")
            self.end_headers()
        elif self.path == "/large":
            data = b"x" * (1024 * 1024 + 1)
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            try:
                self.wfile.write(data)
            except BrokenPipeError:
                pass
        else:
            data = json.dumps({"path": self.path, "host": self.headers["Host"], "range": self.headers.get("Range"),
                               "authorization": self.headers.get("Authorization"), "custom": self.headers.get("X-Api-Key")}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "image/png" if self.path == "/image" else "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.send_response(303)
        self.send_header("Location", "/good")
        self.end_headers()


class NetworkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.origin = ThreadingHTTPServer(("127.0.0.1", 0), Origin)
        cls.thread = threading.Thread(target=cls.origin.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = "http://127.0.0.1:" + str(cls.origin.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.origin.shutdown()
        cls.origin.server_close()

    def setUp(self):
        self.network = Network(settings())

    def tearDown(self):
        self.network.dns.close()

    def test_http_pinned_path_redirect_and_post_semantics(self):
        identity = {"pluginId": "probe", "method": "GET", "url": self.url + "/a%2Fb?q=a%26b%3Dc"}
        body = json.loads(self.network.http(identity)["body"])
        self.assertEqual(body["path"], "/a%2Fb?q=a%26b%3Dc")
        self.assertEqual(body["host"], "127.0.0.1:" + str(self.origin.server_port))
        self.assertEqual(json.loads(self.network.http({**identity, "url": self.url + "/redirect"})["body"])["path"], "/good")
        self.assertEqual(json.loads(self.network.http({**identity, "method": "POST", "body": "hi", "url": self.url})["body"])["path"], "/good")

    def test_loop_limits_headers_and_body(self):
        for target in ("/loop", "/large"):
            with self.assertRaises(NetworkError):
                self.network.http({"pluginId": "probe", "method": "GET", "url": self.url + target})
        for headers in ({"Host": "evil"}, {"Accept": "x\r\nX: yes"}):
            with self.assertRaises(NetworkError):
                self.network.http({"pluginId": "probe", "method": "GET", "url": self.url, "headers": headers})

    def test_private_and_public_policy_matches_native_vectors(self):
        for address in ("127.0.0.1", "10.1.2.3", "172.16.1.1", "192.168.2.1", "169.254.1.1", "::1", "fd00::1", "fe80::1"):
            self.assertTrue(private_address(address), address)
        for address in ("0.0.0.0", "8.8.8.8", "172.32.1.1", "224.0.0.1", "2001:4860:4860::8888", "::"):
            self.assertFalse(private_address(address), address)
        for address in ("192.0.2.1", "198.51.100.1", "203.0.113.1", "100.64.0.1", "2001:db8::1", "2002::1", "64:ff9b::808:808"):
            self.assertFalse(public_address(address), address)
        self.assertTrue(public_address("8.8.8.8"))
        self.assertTrue(public_address("2001:4860:4860::8888"))

    def test_mixed_dns_and_control_endpoint_rejected(self):
        self.network.dns.close()
        self.network.dns = DnsPool(lookup=lambda host: ["127.0.0.1", "8.8.8.8"])
        with self.assertRaises(NetworkError):
            self.network.http({"pluginId": "probe", "method": "GET", "url": self.url})
        self.network.dns.close()
        self.network.dns = DnsPool(lookup=lambda host: ["127.0.0.1"])
        self.network.forbidden_port = self.origin.server_port
        with self.assertRaises(NetworkError):
            self.network.http({"pluginId": "probe", "method": "GET", "url": self.url})

    def test_dns_normalization_ptr_confirmation_and_limits(self):
        for host in ("", "127.1", "0177.0.0.1", "0x7f000001", "https://example.com", "a_b.example", "foo:443", "bücher.example"):
            with self.assertRaises(NetworkError):
                normalize_host(host)
        self.assertEqual(normalize_host("GOOD.Example."), "good.example")
        pool = DnsPool(lookup=lambda host: ["192.0.2.1"] if host == "good.example" else ["192.0.2." + str(i) for i in range(1, 15)], canonical=lambda ip: "GOOD.Example.")
        try:
            values = pool.resolve("pool.example")
            self.assertEqual(len(values), 8)
            self.assertEqual(values[0]["name"], "good.example")
            self.assertNotIn("name", values[1])
        finally:
            pool.close()

    def test_bounded_dns_timeout_does_not_spawn_replacement_threads(self):
        release = threading.Event()
        pool = DnsPool(lookup=lambda host: release.wait(1) or [])
        try:
            with self.assertRaises(NetworkError) as error:
                pool.addresses("slow.example", .01)
            self.assertEqual(error.exception.code, "dns_timeout")
        finally:
            release.set()
            pool.close()

    def test_resource_origin_strips_sensitive_headers_and_preserves_range(self):
        data = {"pluginId": "probe", "url": self.url + "/media", "originUrl": "https://different.example/manifest.mpd",
                "kind": "media", "headers": {"Authorization": "private", "X-Api-Key": "private"}, "range": "bytes=0-999"}
        with self.network.resource(data) as (response, limit):
            value = json.loads(response.read())
            self.assertEqual(value["range"], "bytes=0-999")
            self.assertIsNone(value["authorization"])
            self.assertIsNone(value["custom"])
            self.assertGreater(limit, 1024 * 1024)

    def test_media_public_only_never_accepts_cleartext_or_lan(self):
        with self.assertRaises(NetworkError):
            with self.network.resource({"pluginId": "probe", "url": self.url, "kind": "media", "publicOnly": True}, allow_public=True):
                self.fail("public-only media allowed HTTP")
        for url in ("file:///tmp/media", "http://user:pass@localhost", "https://localhost/x", "https://192.0.2.1/x"):
            with self.assertRaises(NetworkError):
                checked_url(url, public_only=True)

    def test_slow_drip_has_an_absolute_deadline(self):
        self.network.settings["http_total_timeout_s"] = .12
        began = time.monotonic()
        with self.assertRaises(NetworkError) as error:
            self.network.http({"pluginId": "probe", "method": "GET", "url": self.url + "/drip"})
        self.assertEqual(error.exception.code, "timeout")
        self.assertLess(time.monotonic() - began, .5)


class GatewayTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.source = plugin(self.root / "plugin")
        self.dist = self.root / "dist"
        self.dist.mkdir()
        (self.dist / "index.html").write_text("<!doctype html><title>Fixture</title>")
        self.app = Gateway([self.source], {"profile_dir": str(self.root / "profiles")}, dist=self.dist, factory_path=self.root / "missing.json")
        self.server = Server(self.app)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.identity = {"pluginId": "probe", "digest": self.app.snapshots.plugins["probe"]["digest"]}

    def tearDown(self):
        self.app.close()
        self.server.shutdown()
        self.server.server_close()
        self.temp.cleanup()

    def call(self, path, data=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        actual = {"X-ThinkOS-Session": self.app.token}
        actual.update(headers or {})
        body = None
        if data is not None:
            body = json.dumps(data).encode()
            actual["Content-Type"] = "application/json"
        connection.request("POST" if data is not None else "GET", path, body, actual)
        response = connection.getresponse()
        result = response.status, response.read()
        connection.close()
        return result

    def test_session_and_static_bootstrap(self):
        status, body = self.call("/api/session", headers={"X-ThinkOS-Session": ""})
        self.assertEqual(status, 200)
        value = json.loads(body)
        request("Session", value)
        self.assertEqual(value["plugins"][0]["id"], "probe")
        self.assertFalse(value["plugins"][0]["factory"])
        self.assertIn("tos.http", value["bootstrap"])
        self.assertEqual(self.call("/")[0], 200)

    def test_session_region_picker_strings_use_native_resources_for_all_locales(self):
        strings = self.app.session()["systemStrings"]
        self.assertEqual(len(strings), 11)
        self.assertEqual(strings["en"]["form_region_search"], "Search regions")
        self.assertEqual(strings["zh-Hans"]["form_region_all"], "所有地区")
        self.assertEqual(strings["zh-Hant"]["form_region_search"], "搜尋地區")
        for locale, values in strings.items():
            self.assertTrue(values["form_region_search"], locale)
            self.assertTrue(values["form_region_all"], locale)

    def test_host_origin_token_and_cross_site_protection(self):
        for headers in ({"Host": "evil.example"}, {"Origin": "https://evil.example"}, {"Origin": "null"}, {"Sec-Fetch-Site": "cross-site"}):
            self.assertEqual(self.call("/api/session", headers=headers)[0], 403)
        self.assertEqual(self.call("/api/storage", {**self.identity, "value": {}}, {"X-ThinkOS-Session": "wrong"})[0], 403)

    def test_hot_snapshot_is_authenticated_minimal_and_tracks_revision(self):
        self.assertEqual(self.call("/api/snapshot", headers={"X-ThinkOS-Session": ""})[0], 403)
        self.assertEqual(self.call("/api/snapshot", headers={"Origin": "https://evil.example"})[0], 403)
        status, body = self.call("/api/snapshot")
        self.assertEqual(status, 200)
        value = json.loads(body)
        request("SnapshotResponse", value)
        self.assertEqual(set(value), {"revision", "plugins", "diagnostics"})
        initial = self.app.session()
        self.assertEqual(value, {key: initial[key] for key in value})
        (self.source / "main.lua").write_text('tos.state.set({reload=true})')
        self.app.snapshots.refresh()
        status, body = self.call("/api/snapshot")
        latest = json.loads(body)
        self.assertEqual(latest["revision"], value["revision"] + 1)
        self.assertNotEqual(latest["plugins"][0]["digest"], value["plugins"][0]["digest"])
        (self.source / "manifest.json").write_text('{invalid')
        self.app.snapshots.refresh()
        invalid = json.loads(self.call("/api/snapshot")[1])
        self.assertEqual(invalid["plugins"], latest["plugins"])
        self.assertTrue(invalid["diagnostics"])

    def test_storage_profile_persistence_and_stale_identity(self):
        self.assertEqual(self.call("/api/storage", {**self.identity, "value": {"saved": 4}})[0], 200)
        self.assertEqual(self.call("/api/profile", {"settings": {"plugins": {"probe": {"refresh": 20}}}, "environment": {"orientation": "portrait"}})[0], 200)
        saved = json.loads((self.root / "profiles/default.json").read_text())
        self.assertEqual(saved["storage"]["probe"], {"saved": 4})
        self.assertEqual(saved["settings"]["plugins"]["probe"]["refresh"], 20)
        self.assertEqual(self.call("/api/storage", {**self.identity, "digest": "0" * 64, "value": {}})[0], 400)
        self.assertEqual(self.call("/api/profile", {"settings": {"plugins": {"probe": {"refresh": -1}}}})[0], 400)

    def test_profile_constraints_time_control_characters_and_atomic_rejection(self):
        before = self.app.profiles.read()
        for fields in ({"sleep.start": "12:00", "sleep.end": "12:00"}, {"sleep.start": "12:01"},
                       {"theme.dark_below_lux": 100, "theme.light_above_lux": 50}, {"location.name": "Place\nSecret"}):
            self.assertEqual(self.call("/api/profile", {"settings": {"system": fields}})[0], 400)
            self.assertEqual(self.app.profiles.read(), before)
        self.assertEqual(self.call("/api/profile", {"settings": {"plugins": {"probe": {"refresh": 20.25}}}})[0], 200)

    def test_system_fixture_and_unknown_environment_fields_rejected(self):
        self.assertEqual(self.call("/api/profile", {"environment": {"systemSimulation": ["probe"]}})[0], 400)
        self.assertEqual(self.call("/api/profile", {"environment": {"execute": "shell"}})[0], 400)
        self.assertEqual(self.call("/api/profile", {"environment": {"http": {"https://example.com": {"status": 200, "headers": {}, "body": "fixture"}}}})[0], 200)

    def test_development_settings_and_explicit_registry_fixture_bounds(self):
        self.assertEqual(self.app.session()["developmentSettings"], {"log_entries": 500, "worker_watchdog_ms": 10000, "watch_retry_ms": 1000, "worker_heartbeat_ms": 1000})
        self.assertEqual(settings({"log_entries": 1200})["log_entries"], 1200)
        with self.assertRaises(ValueError):
            settings({"worker_watchdog_ms": 999})
        record = {"id": "sample", "name": "Sample", "version": "1.0.0", "digest": "a" * 64, "repo": "owner/repo", "commit": "b" * 40,
                  "tier": "community", "auto": False, "enabled": True, "error": None, "revoked": False, "update": False, "available_version": None, "apps": []}
        environment = {"registryInstalled": [record], "hour24": False, "brightness": 20, "volume": 80,
                       "onboarding": {"greeting_seconds": 2.4}, "catalog": {"items": [], "total": 0, "offset": 0, "configured": True, "refreshing": False, "expired": False, "error": None}}
        self.assertEqual(self.call("/api/profile", {"environment": environment})[0], 200)
        before = self.app.profiles.read()
        self.assertEqual(self.call("/api/profile", {"environment": {"registryInstalled": [{**record, "command": "shell"}]}})[0], 400)
        self.assertEqual(self.call("/api/profile", {"environment": {"volume": 101}})[0], 400)
        self.assertEqual(self.app.profiles.read(), before)

    def test_profile_reset_removes_overrides_before_patch_atomically(self):
        self.call("/api/profile", {"settings": {"system": {"location.name": "Place"}, "plugins": {"probe": {"refresh": 20}}}})
        status, body = self.call("/api/profile", {"reset": [{"scope": "probe", "key": "refresh"}, {"scope": "system"}]})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["settings"], {"system": {}, "plugins": {"probe": {}}})
        status, body = self.call("/api/profile", {"reset": [{"scope": "probe"}], "settings": {"plugins": {"probe": {"refresh": 30}}}})
        self.assertEqual(json.loads(body)["settings"]["plugins"]["probe"]["refresh"], 30)
        before = self.app.profiles.read()
        self.assertEqual(self.call("/api/profile", {"reset": [{"scope": "probe", "key": "missing"}]})[0], 400)
        self.assertEqual(self.app.profiles.read(), before)

    def test_place_search_requires_session_uses_only_system_endpoint_and_fixture_is_offline(self):
        self.assertEqual(self.call("/api/place-search", {"query": "London"}, {"X-ThinkOS-Session": ""})[0], 403)
        for body in ({"query": "London", "endpoint": "https://evil.example"}, {"query": "London", "pluginId": "probe"}, {"query": "x" * 161}, {"query": "London", "locale": "en&url=evil"}):
            self.assertEqual(self.call("/api/place-search", body)[0], 400)
        self.call("/api/profile", {"environment": {"networkMode": "fixture"}})
        with patch.object(self.app.network, "http", side_effect=AssertionError("fixture contacted network")):
            status, body = self.call("/api/place-search", {"query": "London"})
            self.assertEqual((status, json.loads(body)), (200, {"items": [], "offline": True}))
        seen = []
        class Geocoder(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass
            def do_GET(self):
                seen.append({"path": self.path, "session": self.headers.get("X-ThinkOS-Session")})
                body = json.dumps({"results": [{"name": "London", "latitude": 51.5, "longitude": -0.1, "timezone": "Europe/London", "country": "United Kingdom"}]}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        origin = ThreadingHTTPServer(("127.0.0.1", 0), Geocoder)
        threading.Thread(target=origin.serve_forever, daemon=True).start()
        try:
            endpoint = "http://127.0.0.1:" + str(origin.server_port) + "/geocode"
            self.call("/api/profile", {"settings": {"system": {"location.search_url": endpoint}}, "environment": {"networkMode": "live"}})
            self.app.snapshots.plugins["probe"]["manifest"]["permissions"] = []
            status, body = self.call("/api/place-search", {"query": "  London ", "locale": "en-GB"})
            self.assertEqual(status, 200)
            result = json.loads(body)
            request("PlaceSearchResponse", result)
            self.assertEqual(result["items"][0]["location"]["name"], "London")
            self.assertNotIn("offline", result)
            self.assertEqual(seen, [{"path": "/geocode?name=London&count=20&language=en&format=json", "session": None}])
        finally:
            origin.shutdown()
            origin.server_close()

    def test_profile_environment_roundtrip_and_profile_isolation(self):
        environment = {"orientation": "portrait", "hour24": False, "now": 1700000000000, "networkMode": "fixture", "volume": 20,
                       "location": {"name": "London", "latitude": 51.5, "longitude": -0.1, "elevation": 0, "timezone": "Europe/London", "source": "manual"},
                       "http": {"https://example.invalid": {"status": 200, "headers": {}, "body": "fixture"}},
                       "operationResults": {"device.powerOff": {"ok": False, "error": "fixture", "delayMs": 100}}}
        self.call("/api/profile", {"environment": environment, "settings": {"system": {"location.name": "London"}, "plugins": {"probe": {"refresh": 20.25}}}})
        self.call("/api/storage", {**self.identity, "value": {"key": "persisted"}})
        expected = self.app.profiles.read()
        reloaded = Gateway([self.source], {"profile_dir": str(self.root / "profiles")}, dist=self.dist, factory_path=self.root / "missing.json")
        isolated = Gateway([self.source], {"profile_dir": str(self.root / "profiles"), "profile": "isolated"}, dist=self.dist, factory_path=self.root / "missing.json")
        try:
            self.assertEqual(reloaded.profiles.read(), expected)
            self.assertEqual(isolated.profiles.read()["storage"], {})
            self.assertEqual(isolated.profiles.read()["settings"], {"system": {}, "plugins": {}})
            self.assertNotIn("now", isolated.profiles.read()["environment"])
        finally:
            reloaded.close()
            isolated.close()

    def test_scene_replacement_removes_old_fixtures_and_returns_complete_environment(self):
        self.call("/api/profile", {"environment": {"now": 1000, "http": {"https://example.invalid": {"status": 200, "headers": {}, "body": "old"}}}, "settings": {"plugins": {"probe": {"refresh": 30}}}})
        self.call("/api/storage", {**self.identity, "value": {"persisted": True}})
        status, body = self.call("/api/profile", {"environment": {"orientation": "portrait"}, "replaceEnvironment": True})
        self.assertEqual(status, 200)
        result = json.loads(body)
        request("ProfileResponse", result)
        self.assertNotIn("now", result["environment"])
        self.assertNotIn("http", result["environment"])
        self.assertEqual(result["environment"]["orientation"], "portrait")
        self.assertEqual(result["environment"]["hour24"], True)
        self.assertEqual(result["settings"]["plugins"]["probe"]["refresh"], 30)
        self.assertEqual(self.app.profiles.read()["storage"]["probe"], {"persisted": True})
        before = self.app.profiles.read()
        self.assertEqual(self.call("/api/profile", {"replaceEnvironment": True})[0], 400)
        self.assertEqual(self.app.profiles.read(), before)

    def test_http_and_media_cannot_read_another_profile_control_service(self):
        other = Gateway([self.source], {"profile_dir": str(self.root / "profiles"), "profile": "other"}, dist=self.dist, factory_path=self.root / "missing.json")
        other.fonts = []  # Keep this below 1 MiB so the transport cap is not the protection.
        server = Server(other)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        url = "http://127.0.0.1:" + str(server.server_port) + "/api/session"
        try:
            for endpoint, data in (("/api/http", {"method": "GET"}), ("/api/resource", {"kind": "media"})):
                status, body = self.call(endpoint, {**self.identity, "url": url, **data})
                self.assertEqual(status, 400)
                self.assertEqual(json.loads(body)["error"]["code"], "policy")
                self.assertNotIn(other.token, body.decode())
        finally:
            other.close()
            server.shutdown()
            server.server_close()

    def test_permissions_and_static_traversal(self):
        self.app.snapshots.plugins["probe"]["manifest"]["permissions"] = []
        self.assertEqual(self.call("/api/http", {**self.identity, "method": "GET", "url": "http://localhost"})[0], 403)
        self.assertEqual(self.call("/../manifest.json")[0], 400)
        self.assertEqual(self.call("/%2e%2e/manifest.json")[0], 400)
        (self.dist / "leak.txt").symlink_to(self.source / "main.lua")
        self.assertEqual(self.call("/leak.txt")[0], 403)

    def test_ticket_is_bound_to_snapshot_and_does_not_contain_session_token(self):
        status, body = self.call("/api/resource-ticket", {**self.identity, "url": "https://example.com/media", "kind": "media"})
        self.assertEqual(status, 200)
        url = json.loads(body)["url"]
        self.assertNotIn(self.app.token, url)
        token = url.rsplit("/", 1)[-1]
        self.assertEqual(self.app.read_ticket(token)["pluginId"], "probe")
        self.app.snapshots.plugins["probe"]["digest"] = "1" * 64
        self.assertEqual(self.call(url)[0], 400)

    def test_resource_binary_ticket_range_and_cross_origin_denial(self):
        origin = ThreadingHTTPServer(("127.0.0.1", 0), Origin)
        threading.Thread(target=origin.serve_forever, daemon=True).start()
        try:
            data = {**self.identity, "url": "http://127.0.0.1:" + str(origin.server_port) + "/media", "kind": "media"}
            self.assertEqual(self.call("/api/resource", data)[0], 200)
            _, body = self.call("/api/resource-ticket", data)
            url = json.loads(body)["url"]
            status, payload = self.call(url, headers={"X-ThinkOS-Session": "", "Range": "bytes=2-8"})
            self.assertEqual(status, 200)
            self.assertEqual(json.loads(payload)["range"], "bytes=2-8")
            self.assertEqual(self.call(url, headers={"Origin": "https://evil.example"})[0], 403)
        finally:
            origin.shutdown()
            origin.server_close()

    def test_sse_reports_a_new_snapshot_revision(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        connection.request("GET", "/api/events", headers={"X-ThinkOS-Session": self.app.token})
        response = connection.getresponse()
        try:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.fp.readline(), b"event: revision\n")
            initial = json.loads(response.fp.readline().removeprefix(b"data: "))
            response.fp.readline()
            (self.source / "main.lua").write_text('tos.state.set({live="changed"})')
            self.app.snapshots.refresh()
            self.assertEqual(response.fp.readline(), b"event: revision\n")
            changed = json.loads(response.fp.readline().removeprefix(b"data: "))
            self.assertGreater(changed["revision"], initial["revision"])
        finally:
            response.close()
            connection.close()

    def test_local_library_is_readonly_profile_scoped_and_range_capable(self):
        self.app.snapshots.plugins["probe"]["manifest"]["permissions"].append("library")
        folder = self.app.library.mounts[PRIVATE_ROOT] / "Music"
        folder.mkdir(parents=True)
        song = folder / "中文 test.mp3"
        song.write_bytes(b"0123456789")
        (folder / "escape.mp3").symlink_to(self.source / "main.lua")
        status, body = self.call("/api/library", {**self.identity, "options": {"kind": "audio"}})
        self.assertEqual(status, 200)
        listing = json.loads(body)
        self.assertEqual(len(listing["items"]), 1)
        self.assertEqual(listing["items"][0]["title"], "中文 test")
        self.assertNotIn(str(self.root), body.decode())
        url = listing["items"][0]["url"]
        status, data = self.call("/api/resource", {**self.identity, "url": url, "kind": "media", "range": "bytes=2-5"})
        self.assertEqual((status, data), (206, b"2345"))
        self.assertEqual(song.read_bytes(), b"0123456789")
        self.assertEqual(self.call("/api/resource-ticket", {**self.identity, "url": "file:///etc/passwd", "kind": "media"})[0], 403)
        self.assertEqual(self.call("/api/resource", {**self.identity, "url": "file://" + PRIVATE_ROOT + "/Music/escape.mp3", "kind": "media"})[0], 502)
        self.assertEqual(self.call("/api/library", {**self.identity, "options": {"kind": "audio", "dir": "/sdcard/../../etc"}})[0], 403)
        status, data = self.call("/api/library", {**self.identity, "options": {"kind": "audio", "dir": "/sdcard/Music"}})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(data)["items"][0]["url"], "file:///sdcard/Music/%E4%B8%AD%E6%96%87%20test.mp3")
        self.assertEqual(self.call("/api/library", {**self.identity, "options": {"kind": "video", "dir": "/sdcard/Movies"}})[0], 200)

    def test_library_does_not_resolve_symlinked_mount_parent(self):
        from simulator.library import Library
        target = self.root / "outside"
        (target / "Music").mkdir(parents=True)
        (target / "Music/secret.mp3").write_bytes(b"secret")
        link = self.root / "alias"
        link.symlink_to(target, target_is_directory=True)
        library = Library(self.app.profiles, {**self.app.config, "library_mounts": {"/sdcard/Extra": str(link / "Music")}}, self.root)
        with self.assertRaises(OSError):
            library.list({"kind": "audio", "dir": "/sdcard/Extra"})

    def test_commit_then_old_release_preserves_new_generation_and_tickets(self):
        old = self.identity["digest"]
        (self.source / "main.lua").write_text('tos.state.set({changed=true})')
        self.app.snapshots.refresh()
        latest = self.app.snapshots.plugins["probe"]["digest"]
        self.assertEqual(self.call("/api/runtime", {"pluginId": "probe", "digest": latest, "action": "commit"})[0], 200)
        _, body = self.call("/api/resource-ticket", {"pluginId": "probe", "digest": latest, "url": "https://example.com/file", "kind": "media"})
        token = json.loads(body)["url"].rsplit("/", 1)[-1]
        self.assertEqual(self.call("/api/runtime", {"pluginId": "probe", "digest": old, "action": "release"})[0], 200)
        self.assertEqual(self.app.read_ticket(token)["digest"], latest)


if __name__ == "__main__":
    unittest.main()
