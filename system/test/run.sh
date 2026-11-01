#!/bin/bash
# 守护进程的主机测试：先编主机版，再对着假的 wpa_supplicant 跑。
set -euo pipefail
cd "$(dirname "$0")/.."
./build.sh --host >/dev/null
exec python3 -I -W ignore test/test_wfdd.py "$@"
