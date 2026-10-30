#!/usr/bin/env python3
"""Local-only HTTP fixture for device runtime regression (adb reverse tcp:8769 tcp:8769)."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import time

BODY = json.dumps({'items': [{'value': i, 'text': 'Hong Kong 香港 weather sample'} for i in range(1000)], 'empty': [], 'null': None}, ensure_ascii=False).encode()

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/shutdown':
            self.send_response(200); self.end_headers()
            threading.Thread(target=self.server.shutdown, daemon=True).start()
            return
        if self.path == '/slow': time.sleep(.65)
        if self.path == '/redirect-public':
            self.send_response(302); self.send_header('Location', 'http://8.8.8.8/'); self.end_headers(); return
        if self.path == '/redirect-private':
            self.send_response(302); self.send_header('Location', '/weather'); self.end_headers(); return
        data = b'x' * (1024 * 1024 + 1) if self.path == '/large' else BODY
        self.send_response(200)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        try: self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError): pass

if __name__ == '__main__':
    with ThreadingHTTPServer(('127.0.0.1', 8769), Handler) as server:
        print('fixture ready on 127.0.0.1:8769', flush=True)
        server.serve_forever()
