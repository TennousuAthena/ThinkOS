#!/bin/bash
# MIT. Host checks only; never invokes adb, Magisk, su or module installers.
set -euo pipefail
cd "$(dirname "$0")/../.."
python3 -I -m unittest discover -s tools/tests -p test_system_cacerts.py -v
