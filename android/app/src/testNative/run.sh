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
clang -O2 -I"$JAVA_TEST_HOME/include" -I"$JAVA_TEST_HOME/include/darwin" -I"$CPP_DIR/lua" "$TEST_DIR/weather_test.c" $LUA_SOURCES "$CPP_DIR/json/yyjson.c" -lm -o "$TEST_TEMP/weather-test"
"$TEST_TEMP/weather-test" "$TEST_DIR/../../../../tools/tests/weather_test.lua" "$TEST_DIR/../../../../plugins/weather/main.lua" "$TEST_DIR/../../../../plugins/weather/forecast.lua" "$TEST_DIR/../../../../plugins/weather/codes.lua" "$TEST_DIR/../../../../tools/tests/fixtures/weather/forecast.json" "$TEST_DIR/../../../../plugins/weather/manifest.json" "$TEST_DIR/../../../../plugins/weather/i18n/en.json"
clang -O2 -I"$JAVA_TEST_HOME/include" -I"$JAVA_TEST_HOME/include/darwin" -I"$CPP_DIR/lua" "$TEST_DIR/player_test.c" $LUA_SOURCES "$CPP_DIR/json/yyjson.c" -lm -o "$TEST_TEMP/player-test"
"$TEST_TEMP/player-test" "$TEST_DIR/../../../../tools/tests/player_test.lua" "$TEST_DIR/../../../../plugins/player/main.lua" "$TEST_DIR/../../../../plugins/player/m3u.lua" "$TEST_DIR/../../../../plugins/player/library.lua" "$TEST_DIR/../../../../plugins/player/manifest.json" "$TEST_DIR/../../../../plugins/player/i18n/en.json"
"$TEST_TEMP/player-test" "$TEST_DIR/../../../../tools/tests/radio_test.lua" "$TEST_DIR/../../../../plugins/radio/main.lua" "$TEST_DIR/../../../../plugins/radio/m3u.lua" "$TEST_DIR/../../../../plugins/radio/playlist.lua" "$TEST_DIR/../../../../plugins/radio/manifest.json" "$TEST_DIR/../../../../plugins/radio/i18n/en.json" radio "$TEST_DIR/../../../../plugins/radio/sources/radiobrowser.lua" "$TEST_DIR/../../../../plugins/radio/categories.lua" "$TEST_DIR/../../../../plugins/radio/health.lua"
"$TEST_DIR/../../../../tools/tests/iptv_native.sh"
clang -O2 -I"$CPP_DIR/lua" "$CPP_DIR/lua/lua.c" "$TEST_DIR/feedback_cli.c" $LUA_SOURCES -lm -o "$TEST_TEMP/lua"
"$TEST_TEMP/lua" "$TEST_DIR/../../../../tools/tests/settings_feedback_test.lua" "$TEST_DIR/../../../../plugins/settings/main.lua"
"$TEST_TEMP/lua" "$TEST_DIR/../../../../tools/tests/settings_navigation_test.lua" "$TEST_DIR/../../../../plugins/settings/main.lua"
"$TEST_TEMP/lua" "$TEST_DIR/../../../../tools/tests/apps_test.lua" "$TEST_DIR/../../../../plugins/apps/main.lua"
"$TEST_TEMP/lua" "$TEST_DIR/../../../../tools/tests/welcome_test.lua" "$TEST_DIR/../../../../plugins/welcome/main.lua"
"$TEST_TEMP/lua" "$TEST_DIR/../../../../tools/tests/panel_test.lua" "$TEST_DIR/../../../../plugins/panel/main.lua"
"$TEST_TEMP/lua" "$TEST_DIR/../../../../tools/tests/hub_test.lua" "$TEST_DIR/../../../../plugins/hub/main.lua"
"$TEST_TEMP/lua" "$TEST_DIR/../../../../tools/tests/gallery_test.lua" "$TEST_DIR/../../../../plugins/gallery/main.lua"
"$TEST_TEMP/lua" "$TEST_DIR/../../../../tools/tests/bing_test.lua" "$TEST_DIR/../../../../plugins/gallery/sources/bing.lua" "$TEST_DIR/../../../../plugins/gallery/main.lua"
clang -O2 -dynamiclib -I"$JAVA_TEST_HOME/include" -I"$JAVA_TEST_HOME/include/darwin" -I"$CPP_DIR/lua" "$CPP_DIR/tos_bridge.c" $LUA_SOURCES "$CPP_DIR/json/yyjson.c" -lm -o "$TEST_TEMP/libtos.dylib"
python3 - "$TEST_DIR/../main/java/moe/kiwi/thinkos/plugin/LuaBootstrap.kt" "$TEST_TEMP/bootstrap.lua" <<'PY'
import pathlib,sys
source=pathlib.Path(sys.argv[1]).read_text()
pathlib.Path(sys.argv[2]).write_text(source.split('"""',2)[1].strip())
PY
"$JAVA_TEST_HOME/bin/javac" -d "$TEST_TEMP" "$TEST_DIR/NativeLua.java"
"$JAVA_TEST_HOME/bin/java" -cp "$TEST_TEMP" moe.kiwi.thinkos.plugin.NativeLua "$TEST_TEMP/libtos.dylib" "$TEST_TEMP/bootstrap.lua" "$TEST_DIR/../../../../plugins/gallery/main.lua" "$TEST_DIR/../../../../plugins/gallery/sources/bing.lua"
