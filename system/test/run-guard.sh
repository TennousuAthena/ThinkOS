#!/bin/bash
# Boot launcher and installer tests for the external recovery guard: no device, no module installation.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 -I test/test_guard_launcher.py "$@"
