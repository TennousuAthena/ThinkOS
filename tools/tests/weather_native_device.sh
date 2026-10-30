#!/usr/bin/env bash
# Run deterministic weather logic with the production C codec/budgets on the armv7 device.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
serial="${ANDROID_SERIAL:-HUA0BWUT}"
ndk="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-commandlinetools/ndk/26.3.11579264}"
compiler="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi27-clang"
test_dir="$(mktemp -d /tmp/thinkos-weather-native.XXXXXX)"
remote="/data/local/tmp/thinkos-weather-native-$$"
cleanup() { adb -s "$serial" shell rm -rf "$remote" >/dev/null; rm -rf "$test_dir"; }
trap cleanup EXIT
cpp="$repo_root/android/app/src/main/cpp"
lua_sources=()
for source in "$cpp"/lua/*.c; do
    case "$source" in */lua.c|*/luac.c) ;; *) lua_sources+=("$source");; esac
done
"$compiler" -O2 -I"$cpp/lua" "$repo_root/android/app/src/testNative/weather_test.c" "${lua_sources[@]}" "$cpp/json/yyjson.c" -lm -o "$test_dir/weather-test"
adb -s "$serial" shell mkdir -p "$remote"
adb -s "$serial" push "$test_dir/weather-test" "$repo_root/tools/tests/weather_test.lua" "$repo_root/plugins/weather/main.lua" "$repo_root/plugins/weather/forecast.lua" "$repo_root/plugins/weather/codes.lua" "$repo_root/tools/tests/fixtures/weather/forecast.json" "$repo_root/plugins/weather/manifest.json" "$repo_root/plugins/weather/i18n/en.json" "$remote/" >/dev/null
adb -s "$serial" shell chmod 700 "$remote/weather-test"
adb -s "$serial" shell "$remote/weather-test" "$remote/weather_test.lua" "$remote/main.lua" "$remote/forecast.lua" "$remote/codes.lua" "$remote/forecast.json" "$remote/manifest.json" "$remote/en.json"
