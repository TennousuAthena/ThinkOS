#!/bin/bash
# Pure host checks only; never builds, installs, or enables a Bluetooth overlay.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 -I test/test_btsink.py
