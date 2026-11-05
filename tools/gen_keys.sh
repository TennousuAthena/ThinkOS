#!/bin/sh
# MIT. Owner-run host tool; creates no device state and never publishes.
set -eu
tool_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$tool_dir/gen_keys.py" "$@"
