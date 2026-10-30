#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 -I "$repo_root/tools/plugin_push.py" --serial "${ANDROID_SERIAL:-HUA0BWUT}" "$@"
