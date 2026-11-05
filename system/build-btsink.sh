#!/bin/bash
# Build only. Requires the exact reviewed Bluetooth APK outside this repository.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export PATH="$JAVA_HOME/bin:$PATH"
exec python3 -I btsink_build.py "$@"
