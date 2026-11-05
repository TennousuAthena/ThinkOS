"""Static contract and pure local-origin fixtures. No device, service or network."""
import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("thinkos_radio_browser_fixture", ROOT / "tools/media/radio_browser.py")
FIXTURE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = FIXTURE
SPEC.loader.exec_module(FIXTURE)


class RadioDirectoryTest(unittest.TestCase):
    def test_all_locales_have_the_same_keys_and_categories(self):
        folder = ROOT / "plugins/radio/i18n"
        tables = {path.stem: json.loads(path.read_text()) for path in folder.glob("*.json")}
        self.assertEqual({"zh-Hans", "zh-Hant", "en", "es", "fr", "pt", "ru", "ar", "hi", "bn", "ur"}, set(tables))
        for locale, table in tables.items():
            self.assertEqual(set(tables["zh-Hans"]), set(table), locale)
            self.assertEqual(12, sum(key.startswith("category.") for key in table), locale)

    def test_undecided_defaults_and_no_system_capability(self):
        manifest = json.loads((ROOT / "plugins/radio/manifest.json").read_text())
        self.assertEqual(10, manifest["api"])
        self.assertEqual(["media", "network"], manifest["permissions"])
        for key in ("directory", "click", "host_cooldown"):
            self.assertIs(False, manifest["settings"][key]["default"])
        for key in ("source", "stream_url"):
            self.assertEqual("", manifest["settings"][key]["default"])
        self.assertEqual({"export": "welcome.setup", "shape": "onboarding/1"}, manifest["uses"]["onboarding"])

    def test_fixture_paginates_and_preserves_uuid_and_multilingual_tags(self):
        server = FIXTURE.RadioBrowserFixture()
        first = json.loads(server.reply("/json/stations/search?limit=100&offset=0&tag=actualit%C3%A9s", "http://127.0.0.1:8894").body)
        second = json.loads(server.reply("/json/stations/search?limit=100&offset=100", "http://127.0.0.1:8894").body)
        self.assertEqual(100, len(first))
        self.assertEqual("actualités", first[0]["tags"])
        self.assertNotEqual(first[0]["stationuuid"], second[0]["stationuuid"])
        self.assertEqual(400, server.reply("/json/stations/search?limit=100000", "http://127.0.0.1:8894").status)

    def test_fixture_includes_explicit_failures_not_fake_success(self):
        for scenario in ("malformed", "oversize", "slow", "5xx", "duplicates", "broken", "bad-addresses"):
            result = FIXTURE.RadioBrowserFixture(scenario).reply("/json/stations/search?limit=100", "http://127.0.0.1:8894")
            if scenario == "malformed":
                with self.assertRaises(ValueError):
                    json.loads(result.body)
            elif scenario == "oversize":
                self.assertGreater(len(result.body), 1024 * 1024)
            elif scenario == "slow":
                self.assertGreater(result.delay, 3)
            elif scenario == "5xx":
                self.assertEqual(503, result.status)
            else:
                rows = json.loads(result.body)
                if scenario == "duplicates":
                    self.assertEqual(rows[0]["stationuuid"], rows[-1]["stationuuid"])
                elif scenario == "broken":
                    self.assertEqual(0, rows[0]["lastcheckok"])
                    self.assertEqual(1, rows[1]["ssl_error"])
                else:
                    self.assertTrue(any(row["url"].startswith("http:") for row in rows))
                    self.assertTrue(any("127.0.0.1" in row["url"] for row in rows))

    def test_fixture_click_log_can_prove_no_reporting(self):
        server = FIXTURE.RadioBrowserFixture()
        server.reply("/json/stations/search", "http://127.0.0.1:8894", "ThinkOS-Radio/0.1.0")
        requests = json.loads(server.reply("/test/requests", "http://127.0.0.1:8894").body)
        self.assertFalse(any(item["path"].startswith("/json/url/") for item in requests))
        uuid = FIXTURE.station(1)["stationuuid"]
        self.assertEqual(200, server.reply("/json/url/" + uuid, "http://127.0.0.1:8894").status)
        self.assertEqual(400, server.reply("/json/url/not-an-id", "http://127.0.0.1:8894").status)

    def test_m3u_uuid_comments_are_real_protocol_data(self):
        reply = FIXTURE.RadioBrowserFixture().reply("/m3u/stations/search?limit=2", "http://127.0.0.1:8894")
        self.assertIn(b"#RADIOBROWSERUUID:", reply.body)
        self.assertEqual("audio/mpegurl", reply.mime)
        self.assertIn(b"http://127.0.0.1:8894/local-audio/", reply.body)

    def test_optional_audio_routes_need_explicit_local_samples(self):
        empty = FIXTURE.RadioBrowserFixture()
        self.assertEqual(404, empty.reply("/local-audio/silent.mp3", "http://127.0.0.1:8894").status)
        # Byte routing only; these deliberately tiny host fixtures do not claim decodability.
        sample = FIXTURE.RadioBrowserFixture(audio={"silent.mp3": b"mp3", "silent.aac": b"aac", "segment.ts": b"ts"})
        self.assertEqual(b"mp3", sample.reply("/local-audio/silent.mp3", "http://127.0.0.1:8894").body)
        self.assertEqual("audio/aac", sample.reply("/local-audio/silent.aac", "http://127.0.0.1:8894").mime)
        self.assertIn(b"segment.ts", sample.reply("/local-audio/live.m3u8", "http://127.0.0.1:8894").body)
        self.assertEqual(404, sample.reply("/local-audio/../secret", "http://127.0.0.1:8894").status)


if __name__ == "__main__":
    unittest.main()
