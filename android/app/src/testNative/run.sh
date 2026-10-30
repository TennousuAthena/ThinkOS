#!/bin/sh
set -eu
TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CPP_DIR="$TEST_DIR/../main/cpp"
TEST_TEMP=$(mktemp -d /tmp/thinkos-lua-tests.XXXXXX)
trap 'rm -rf "$TEST_TEMP"' EXIT
JAVA_TEST_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}
LUA_SOURCES=$(find "$CPP_DIR/lua" -name '*.c' ! -name lua.c ! -name luac.c)
clang -O2 -I"$JAVA_TEST_HOME/include" -I"$JAVA_TEST_HOME/include/darwin" -I"$CPP_DIR/lua" "$TEST_DIR/budget_test.c" $LUA_SOURCES "$CPP_DIR/json/yyjson.c" -lm -o "$TEST_TEMP/budget-test"
"$TEST_TEMP/budget-test"
clang -O2 -I"$CPP_DIR/lua" "$CPP_DIR/lua/lua.c" "$TEST_DIR/feedback_cli.c" $LUA_SOURCES -lm -o "$TEST_TEMP/lua"
"$TEST_TEMP/lua" "$TEST_DIR/../../../../tools/tests/settings_feedback_test.lua" "$TEST_DIR/../../../../plugins/settings/main.lua"
clang -O2 -dynamiclib -I"$JAVA_TEST_HOME/include" -I"$JAVA_TEST_HOME/include/darwin" -I"$CPP_DIR/lua" "$CPP_DIR/tos_bridge.c" $LUA_SOURCES "$CPP_DIR/json/yyjson.c" -lm -o "$TEST_TEMP/libtos.dylib"
python3 - "$TEST_DIR/../main/java/moe/kiwi/thinkos/plugin/LuaBootstrap.kt" "$TEST_TEMP/bootstrap.lua" <<'PY'
import pathlib,sys
source=pathlib.Path(sys.argv[1]).read_text()
pathlib.Path(sys.argv[2]).write_text(source.split('"""',2)[1].strip())
PY
"$JAVA_TEST_HOME/bin/javac" -d "$TEST_TEMP" "$TEST_DIR/NativeLua.java"
"$JAVA_TEST_HOME/bin/java" -cp "$TEST_TEMP" moe.kiwi.thinkos.plugin.NativeLua "$TEST_TEMP/libtos.dylib" "$TEST_TEMP/bootstrap.lua"
