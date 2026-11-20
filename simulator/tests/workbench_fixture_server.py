"""Isolated profile and loopback gateway for full workbench browser tests."""
import json
from pathlib import Path
import signal
import sys
import threading

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from simulator.server import Gateway, ROOT, Server


def main():
    directory = Path(sys.argv[1]).resolve()
    sources = sorted(path.parent for path in (ROOT / "plugins").glob("*/manifest.json"))
    app = Gateway(sources, {"profile_dir": str(directory / "profiles"), "profile": "workbench"})
    app.profile({"environment": {"networkMode": "fixture"}})
    server = Server(app)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    threading.Thread(target=app.watch, daemon=True).start()
    print(json.dumps({"url": "http://127.0.0.1:" + str(server.server_port)}), flush=True)
    try:
        signal.pause()
    finally:
        app.close()
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
