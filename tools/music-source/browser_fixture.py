"""A private simulator profile and silent audio on a separate loopback origin."""
from http.server import ThreadingHTTPServer
import json
import zlib
import struct
from pathlib import Path
import signal
import sys
import threading
import wave
from io import BytesIO
from urllib.parse import urlsplit
from urllib.request import urlopen

from serve import handler, load_catalog

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from simulator.server import Gateway, Server


def main():
    output = Path(sys.argv[1])
    out = BytesIO()
    with wave.open(out, 'wb') as audio:
        audio.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        audio.writeframes(bytes(16000 * 2 * 8))
    wav = out.getvalue()
    # A generated square checkerboard identifies this image as test artwork.
    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data))
    pixels = b''.join(b'\0' + b''.join(bytes((36, 111, 176) if (x//8+y//8)%2 else (236, 184, 72)) for x in range(32)) for y in range(32))
    cover = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 32,32,8,2,0,0,0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')
    requests = []
    origin = ThreadingHTTPServer(('127.0.0.1', 0), None)
    base = f'http://127.0.0.1:{origin.server_port}'
    catalog = output / 'catalog.json'
    catalog.write_text(json.dumps([
        {'id': 'one', 'title': 'Demo One', 'artist': 'Fixture', 'album': 'Fixture Collection',
         'art': base + '/sample.png', 'durationMs': 8000, 'url': base + '/redirect.wav'},
        {'id': 'two', 'title': 'Demo Two', 'artist': 'Fixture', 'url': base + '/two.wav'},
    ]))

    class Fixture(handler(load_catalog(catalog))):
        def do_GET(self):
            path = urlsplit(self.path).path
            requests.append(path)
            if path == '/sample.png':
                self.send_response(200)
                self.send_header('Content-Type', 'image/png')
                self.send_header('Content-Length', str(len(cover)))
                self.end_headers()
                self.wfile.write(cover)
            elif path == '/redirect.wav':
                self.send_response(302)
                self.send_header('Location', '/one.wav')
                self.end_headers()
            elif path in ('/one.wav', '/two.wav'):
                self.send_response(200)
                self.send_header('Content-Type', 'audio/wav')
                self.send_header('Content-Length', str(len(wav)))
                self.end_headers()
                try:
                    self.wfile.write(wav)
                except (BrokenPipeError, ConnectionResetError):
                    pass
            elif path == '/observations':
                self.reply(200, requests)
            else:
                super().do_GET()

    origin.RequestHandlerClass = Fixture
    threading.Thread(target=origin.serve_forever, daemon=True).start()
    sources = [ROOT / 'plugins/player', ROOT / 'plugins/clock']
    app = Gateway(sources, {'profile_dir': str(output / 'profiles'), 'profile': 'music'}, dist=output / 'dist')
    app.profile({'settings': {'plugins': {'player': {'music_source_url': base}}}, 'environment': {'locale': 'en'}})
    gateway = Server(app)
    threading.Thread(target=gateway.serve_forever, daemon=True).start()
    with urlopen(base + '/v1/search?q=Demo', timeout=5) as search, urlopen(base + '/v1/resolve?ids=one,two', timeout=5) as resolve:
        (output / 'source-replies.json').write_text(json.dumps({'search': json.load(search), 'resolve': json.load(resolve)}, indent=2))
    requests.clear()
    print(json.dumps({'workbench': f'http://127.0.0.1:{gateway.server_port}', 'source': base}), flush=True)
    try:
        signal.pause()
    finally:
        app.close()
        gateway.server_close()
        origin.server_close()


if __name__ == '__main__':
    main()
