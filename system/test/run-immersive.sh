#!/bin/bash
# thinkos-immersive 的主机测试：编 Xposed API 的替身、钩子和测试，跑一遍。不需要设备。
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
MODULE=thinkos-immersive
OUT=$(mktemp -d "${TMPDIR:-/tmp}/thinkos-immersive-test.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$OUT" $(find "$MODULE/stubs" "$MODULE/src" "$MODULE/test" -name '*.java')
"$JAVA_HOME/bin/java" -ea -cp "$OUT" moe.kiwi.thinkos.immersive.BarsHookTest
"$JAVA_HOME/bin/java" -ea -cp "$OUT" moe.kiwi.thinkos.immersive.BottomSwipeHookTest
"$JAVA_HOME/bin/java" -ea -cp "$OUT" moe.kiwi.thinkos.immersive.GlobalGestureTest
