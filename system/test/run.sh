#!/bin/bash
# 守护进程的主机测试：先编主机版，再对着假的 wpa_supplicant 跑。
set -euo pipefail
cd "$(dirname "$0")/.."
./build.sh --host >/dev/null
# Every module's own host suite is part of the gate, so a change to any of them (and to what OTA installs) is caught here.
for runner in btsink power guard shell certs immersive timesync; do
  bash "test/run-$runner.sh"
done
# The updater fixture is a compiled host program. Every suite runs exactly once;
# a failure must stop this gate and retain its original evidence, never retry.
bash test/run-updater.sh
exec python3 -I -W ignore test/test_wfdd.py "$@"
