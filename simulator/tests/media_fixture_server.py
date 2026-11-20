"""Synthetic, local-only fixtures for media-browser.mjs (not a production server)."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import signal
import struct
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from simulator.server import Gateway, Server


def main():
    root = Path(sys.argv[1]).resolve()
    origin = root / "origin"
    music = root / "profiles/media/library/Music"
    movies = root / "profiles/media/library/Movies"
    plugin = root / "plugin"
    for directory in (origin, music, movies, plugin, origin / "hls", origin / "dash"):
        directory.mkdir(parents=True, exist_ok=True)
    for name, frequency in (("01-tone", 440), ("02-tone", 660)):
        with wave.open(str(music / (name + ".wav")), "wb") as output:
            output.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
            output.writeframes(b"".join(struct.pack("<h", int(1000 * math.sin(2 * math.pi * frequency * sample / 16000))) for sample in range(16000 * 8)))
    def ffmpeg(*args):
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True, stdout=subprocess.DEVNULL)
    ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=15", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "8", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "30", "-c:a", "aac", "-movflags", "+faststart", str(origin / "clip.mp4"))
    (movies / "clip.mp4").write_bytes((origin / "clip.mp4").read_bytes())
    ffmpeg("-i", str(origin / "clip.mp4"), "-c", "copy", "-hls_time", "2", "-hls_playlist_type", "vod", "-hls_segment_filename", str(origin / "hls/segment%02d.ts"), str(origin / "hls/playlist.m3u8"))
    ffmpeg("-i", str(origin / "clip.mp4"), "-c", "copy", "-seg_duration", "2", "-use_template", "1", "-use_timeline", "1", "-f", "dash", str(origin / "dash/manifest.mpd"))
    (plugin / "manifest.json").write_text(json.dumps({"id": "media-test", "name": "Media test", "version": "1.0.0", "api": 10, "main": "main.lua", "permissions": ["network", "media", "library"]}))
    (plugin / "main.lua").write_text('tos.state.set({ready=true})')
    observations = {"requests": [], "cancelled": 0}
    class Origin(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(origin), **kwargs)

        def log_message(self, *_args):
            pass

        def do_GET(self):
            path = urlsplit(self.path).path
            if path == "/stats":
                data = json.dumps(observations).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            observations["requests"].append({"path": path, "range": self.headers.get("Range")})
            if path == "/slow.m3u8":
                body = (origin / "hls/playlist.m3u8").read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.apple.mpegurl")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body[:1])
                    self.wfile.flush()
                    time.sleep(2)
                    self.wfile.write(body[1:])
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    observations["cancelled"] += 1
                return
            super().do_GET()

    upstream = ThreadingHTTPServer(("127.0.0.1", 0), Origin)
    app = Gateway([plugin], {"profile_dir": str(root / "profiles"), "profile": "media"}, dist=root / "dist")
    gateway = Server(app)
    for server in (upstream, gateway):
        threading.Thread(target=server.serve_forever, daemon=True).start()
    print(json.dumps({"url": "http://127.0.0.1:" + str(gateway.server_port), "origin": "http://127.0.0.1:" + str(upstream.server_port)}), flush=True)
    try:
        signal.pause()
    finally:
        app.close()
        for server in (upstream, gateway):
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
