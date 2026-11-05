#!/bin/sh
# MIT. Local preparation only; pushing tags/channels and creating Releases remain owner actions.
set -eu
tool_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$tool_dir/release.py" "$@"
