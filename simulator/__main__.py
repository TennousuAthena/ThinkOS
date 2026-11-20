import argparse
import json
from pathlib import Path
import sys

from .server import ROOT, serve


def main():
    parser = argparse.ArgumentParser(description="ThinkOS local plugin simulator; no Android device required")
    commands = parser.add_subparsers(dest="command", required=True)
    launch = commands.add_parser("serve", help="Serve the built web simulator and watch plugin directories")
    launch.add_argument("--plugin", action="append", type=Path, default=[])
    launch.add_argument("--config", type=Path, help="Host settings validated by simulator/settings.schema.json")
    launch.add_argument("--port", type=int)
    launch.add_argument("--profile")
    launch.add_argument("--profile-dir")
    launch.add_argument("--no-builtins", action="store_true")
    launch.add_argument("--simulate-system", action="append", default=[], metavar="BUILTIN_ID")
    args = parser.parse_args()
    config = json.loads(args.config.read_text()) if args.config else {}
    for name in ("port", "profile", "profile_dir"):
        if getattr(args, name) is not None:
            config[name] = getattr(args, name)
    if args.simulate_system:
        config.update(fixture_mode=True, fixture_plugins=args.simulate_system)
    sources = [] if args.no_builtins else sorted(path.parent for path in (ROOT / "plugins").glob("*/manifest.json"))
    sources.extend(args.plugin)
    try:
        serve(sources, config)
    except (ValueError, OSError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
