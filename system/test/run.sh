#!/bin/bash
# 守护进程的主机测试：先编主机版，再对着假的 wpa_supplicant 跑。
set -euo pipefail
cd "$(dirname "$0")/.."
./build.sh --host >/dev/null
# Every module's own host suite is part of the gate, so a change to any of them (and to what OTA installs) is caught here.
for runner in btsink power guard shell certs immersive timesync; do
  bash "test/run-$runner.sh"
done
# The updater suite runs hundreds of short-lived fake executables, and about one run in eight on a busy Mac loses one of them with no
# trace (a different test each time; no refusal, no traceback, no timeout; see docs/36 MOD-1). A second attempt is allowed once and
# says so loudly; two failures in a row are a real failure.
if ! bash test/run-updater.sh; then
  echo "WARNING: the updater suite failed once (known macOS fake-executable flake, MOD-1); trying once more" >&2
  bash test/run-updater.sh
fi
exec python3 -I -W ignore test/test_wfdd.py "$@"
