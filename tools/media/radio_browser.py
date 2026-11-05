#!/usr/bin/env python3
"""Local Radio Browser protocol fixture, never a public-service proxy.

Only 127.0.0.1 is bound. No network fetch, process execution or media generation.
Directory JSON uses public example hostnames so it can be parsed; those names do
not supply audio. public_only must reject real loopback streams, including this
fixture's M3U streams. Use the existing radio M3U tests for local codec playback.
"""
import argparse
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import threading
import time
from urllib.parse import parse_qs, urlsplit

SCENARIOS = ("normal", "duplicates", "bad-addresses", "broken", "malformed", "oversize", "slow", "5xx")
TAGS = ("news", "noticias", "actualités", "pop", "pop music", "música pop", "rock", "classical", "jazz", "dance")


def station(index, url=None, tag="news"):
    return {"stationuuid": f"00000000-0000-4000-8000-{index:012x}",
            "name": f"测试电台 {index}", "url": url or f"https://stream.example.org/{index}",
            "url_resolved": url or f"https://stream.example.org/{index}", "tags": tag,
            "countrycode": "CN", "lastcheckok": 1, "ssl_error": 0,
            "clickcount": 10000 - index, "codec": ("MP3", "AAC", "AAC+")[index % 3],
            "hls": int(index % 3 == 0), "favicon": "https://tracker.example.org/unused.png"}


@dataclass(frozen=True)
class Reply:
    status: int
    mime: str
    body: bytes
    delay: float = 0


class RadioBrowserFixture:
    def __init__(self, scenario="normal", audio=None):
        if scenario not in SCENARIOS:
            raise ValueError("unknown fixed scenario")
        self.scenario = scenario
        self.audio = audio or {}
        self.requests = []
        self.lock = threading.Lock()

    def reply(self, path, origin, agent=""):
        parsed = urlsplit(path)
        query = parse_qs(parsed.query, keep_blank_values=True, max_num_fields=16)
        with self.lock:
            self.requests.append({"path": parsed.path, "query": query, "agent": agent[:160]})
            self.requests[:] = self.requests[-1000:]
        if parsed.path.startswith("/local-audio/"):
            name = parsed.path.removeprefix("/local-audio/")
            if name == "live.m3u8" and "segment.ts" in self.audio:
                return Reply(200, "application/vnd.apple.mpegurl",
                             b"#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:12\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:12,\nsegment.ts\n#EXT-X-ENDLIST\n")
            mime = {"silent.mp3": "audio/mpeg", "silent.aac": "audio/aac", "segment.ts": "video/mp2t"}.get(name)
            if mime and name in self.audio:
                return Reply(200, mime, self.audio[name])
            return Reply(404, "text/plain", b"local sample not supplied")
        if parsed.path == "/test/requests":
            with self.lock:
                raw = json.dumps(self.requests, ensure_ascii=False).encode()
            return Reply(200, "application/json", raw)
        if parsed.path.startswith("/json/url/"):
            uuid = parsed.path.removeprefix("/json/url/")
            valid = re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", uuid)
            return Reply(200 if valid else 400, "application/json", b'{"ok":true}' if valid else b'{}')
        if parsed.path not in ("/json/stations/search", "/m3u/stations/search", "/json/tags"):
            return Reply(404, "text/plain", b"not found")
        if self.scenario == "5xx":
            return Reply(503, "application/json", b'{"error":"fixture"}')
        if self.scenario == "malformed":
            return Reply(200, "application/json", b'[{"stationuuid":')
        if self.scenario == "oversize":
            return Reply(200, "application/json", b"[" + b" " * (1024 * 1024) + b"]")
        if parsed.path == "/json/tags":
            return Reply(200, "application/json", json.dumps([{"name": tag, "stationcount": 20} for tag in TAGS], ensure_ascii=False).encode())
        try:
            offset = int(query.get("offset", ["0"])[0])
            limit = int(query.get("limit", ["100"])[0])
        except ValueError:
            return Reply(400, "application/json", b"[]")
        if not 0 <= offset <= 10000 or not 1 <= limit <= 100:
            return Reply(400, "application/json", b"[]")
        tag = query.get("tag", ["news"])[0][:80]
        # All three category requests overlap, making UUID merge observable.
        rows = [station(index, tag=tag) for index in range(offset + 1, min(offset + limit + 1, 221))]
        if self.scenario == "duplicates" and rows:
            rows[-1] = rows[0]
        if self.scenario == "broken" and rows:
            rows[0]["lastcheckok"] = 0
            if len(rows) > 1:
                rows[1]["ssl_error"] = 1
        if self.scenario == "bad-addresses":
            rows = [station(index + 1, url) for index, url in enumerate((
                "http://stream.example.org/live", "https://127.0.0.1/live", "https://10.0.0.1/live",
                "https://169.254.1.1/live", "https://[::1]/live", "https://radio.local/live",
                "https://user:secret@stream.example.org/live", "https://stream.example.org/live#fragment",
                "https://stream.example.org/live"))]
        delay = 4.0 if self.scenario == "slow" else 0
        if parsed.path.startswith("/m3u/"):
            lines = ["#EXTM3U"]
            for index, row in enumerate(rows):
                lines += ["#RADIOBROWSERUUID:" + row["stationuuid"], "#EXTINF:1," + row["name"],
                          origin + "/local-audio/" + ("live.m3u8", "silent.mp3", "silent.aac")[index % 3]]
            return Reply(200, "audio/mpegurl", ("\n".join(lines) + "\n").encode(), delay)
        return Reply(200, "application/json", json.dumps(rows, ensure_ascii=False).encode(), delay)


def load_audio(directory):
    if directory is None:
        return {}
    directory = Path(directory)
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError("audio root must be an existing ordinary directory")
    audio = {}
    for name in ("silent.mp3", "silent.aac", "segment.ts"):
        path = directory / name
        if path.exists():
            if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= 16 * 1024 * 1024:
                raise ValueError("local audio must be a bounded ordinary sample file")
            audio[name] = path.read_bytes()
    return audio


def serve(port, scenario, audio=None):
    fixture = RadioBrowserFixture(scenario, audio)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            if len(self.path) > 4096:
                self.send_error(414)
                return
            try:
                reply = fixture.reply(self.path, f"http://127.0.0.1:{self.server.server_port}", self.headers.get("User-Agent", ""))
            except (ValueError, TypeError):
                reply = Reply(400, "application/json", b"[]")
            if reply.delay:
                time.sleep(reply.delay)
            self.send_response(reply.status)
            self.send_header("Content-Type", reply.mime)
            self.send_header("Content-Length", str(len(reply.body)))
            self.send_header("Connection", "close")
            self.end_headers()
            try:
                self.wfile.write(reply.body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, *_):
            pass  # Only bounded protocol request metadata is available at /test/requests.

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    print(f"Radio Browser fixture http://127.0.0.1:{server.server_port} ({scenario})", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8894)
    parser.add_argument("--scenario", choices=SCENARIOS, default="normal")
    parser.add_argument("--audio-root", type=Path, help="optional existing silent.mp3, silent.aac and segment.ts; never generated or downloaded")
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error("invalid port")
    serve(args.port, args.scenario, load_audio(args.audio_root))
