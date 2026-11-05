#!/bin/sh
set -eu
test_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 -I "$test_dir/test_timesync.py" "$@"
