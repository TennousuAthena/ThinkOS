#!/bin/bash
# 断电保护守护进程的主机测试：先编主机版，再按样本文件回放、对着假的 sysfs 跑。
set -euo pipefail
cd "$(dirname "$0")/.."
./build-power.sh --host >/dev/null
exec python3 -I -W ignore test/test_power.py "$@"
