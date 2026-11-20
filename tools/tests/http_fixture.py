#!/usr/bin/env python3
"""Local-only HTTP fixture for device runtime regression (adb reverse tcp:8769 tcp:8769)."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import time
from urllib.parse import urlsplit

BODY = json.dumps({'items': [{'value': i, 'text': 'Hong Kong 香港 weather sample'} for i in range(1000)], 'empty': [], 'null': None}, ensure_ascii=False).encode()
MAX_BODY = 1024 * 1024


def load_body(body, size, malformed=False):
    if not len(body) <= size <= MAX_BODY:
        raise ValueError('Fixture size outside response bounds')
    payload = body.ljust(size, b' ')
    return b'!' + payload[1:] if malformed else payload

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        route = urlsplit(self.path).path
        if route == '/shutdown':
            self.send_response(200); self.end_headers()
            threading.Thread(target=self.server.shutdown, daemon=True).start()
            return
        if route == '/slow': time.sleep(.65)
        if route == '/redirect-public':
            self.send_response(302); self.send_header('Location', 'http://8.8.8.8/'); self.end_headers(); return
        if route == '/redirect-private':
            self.send_response(302); self.send_header('Location', '/weather'); self.end_headers(); return
        data = b'x' * (MAX_BODY + 1) if route == '/large' else BODY
        if route == '/weather-load':
            with self.server.fixture_lock:
                self.server.requests += 1
                request = self.server.requests
            time.sleep(self.server.delay_ms / 1000)
            data = self.server.bad_body if request % 2 == 0 else self.server.body
        self.send_response(200)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
            if route == '/weather-load':
                with self.server.fixture_lock:
                    self.server.responses += 1
        except (BrokenPipeError, ConnectionResetError): pass


def make_server(port, *, delay_ms=350, response_bytes=MAX_BODY, body=BODY, malformed=True):
    if not 0 <= delay_ms <= 5000:
        raise ValueError('Fixture delay outside test bounds')
    full = load_body(body, response_bytes)
    bad = load_body(body, response_bytes, malformed=malformed)
    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.body, server.bad_body, server.delay_ms = full, bad, delay_ms
    server.fixture_lock, server.requests, server.responses = threading.Lock(), 0, 0
    return server

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8769)
    parser.add_argument('--delay-ms', type=int, default=350)
    parser.add_argument('--response-bytes', type=int, default=MAX_BODY)
    parser.add_argument('--malformed', action='store_true')
    args = parser.parse_args()
    with make_server(args.port, delay_ms=args.delay_ms, response_bytes=args.response_bytes, malformed=args.malformed) as server:
        print(f'fixture ready on 127.0.0.1:{server.server_port}', flush=True)
        server.serve_forever()
