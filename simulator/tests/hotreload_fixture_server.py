"""Private gateway and HTTP origin for hotreload-browser.mjs."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import signal
import sys
import threading

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from simulator.server import Gateway, Server


def main():
    root = Path(sys.argv[1]).resolve()
    class Origin(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_GET(self):
            body = b"authorized"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    origin = ThreadingHTTPServer(("127.0.0.1", 0), Origin)
    app = Gateway([root / "plugin"], {"profile_dir": str(root / "profiles"), "profile": "hotreload"}, dist=root / "dist", factory_path=root / "missing-factory.json")
    if set(app.snapshots.plugins) != {"hotreload"}:
        raise ValueError("Invalid test plugin: " + str(app.snapshots.diagnostics))
    gateway = Server(app)
    for server in (origin, gateway):
        threading.Thread(target=server.serve_forever, daemon=True).start()
    threading.Thread(target=app.watch, daemon=True).start()
    print(json.dumps({"url": "http://127.0.0.1:" + str(gateway.server_port), "origin": "http://127.0.0.1:" + str(origin.server_port)}), flush=True)
    try:
        signal.pause()
    finally:
        app.close()
        for server in (origin, gateway):
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
