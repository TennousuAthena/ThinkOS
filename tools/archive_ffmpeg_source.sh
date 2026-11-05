#!/bin/sh
# Project-authored source archive tooling (MIT); does not run upstream build scripts.
set -eu
task_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$task_dir/archive_ffmpeg_source.py" "$@"
