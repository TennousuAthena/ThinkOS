#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -I -m unittest discover -s test -p test_shell.py "$@"
./build-shell.sh
