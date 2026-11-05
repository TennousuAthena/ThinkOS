#!/bin/bash
# Root agent runs the unified qualification; this script is only the reproducible entry point.
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 -I test/test_updater.py "$@"
