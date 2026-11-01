#!/usr/bin/env bash
# Player plugin logic in the production Lua sandbox (same allocator, hooks, 50 ms slice budget and JSON codec as the
# app), built for this Mac. `--device` builds the same test for armv7 and runs it on the device.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cpp="$repo_root/android/app/src/main/cpp"
test_dir="$(mktemp -d /tmp/thinkos-player-native.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
lua_sources=()
for source in "$cpp"/lua/*.c; do
    case "$source" in */lua.c|*/luac.c) ;; *) lua_sources+=("$source");; esac
done
files=("$repo_root/tools/tests/player_test.lua" "$repo_root/plugins/player/main.lua" "$repo_root/plugins/player/m3u.lua"
       "$repo_root/plugins/player/library.lua" "$repo_root/plugins/player/manifest.json" "$repo_root/plugins/player/i18n/en.json")
if [ "${1:-}" = "--device" ]; then
    serial="${ANDROID_SERIAL:-HUA0BWUT}"
    ndk="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-commandlinetools/ndk/26.3.11579264}"
    compiler="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi27-clang"
    remote="/data/local/tmp/thinkos-player-native-$$"
    "$compiler" -O2 -I"$cpp/lua" "$repo_root/android/app/src/testNative/player_test.c" "${lua_sources[@]}" "$cpp/json/yyjson.c" -lm -o "$test_dir/player-test"
    adb -s "$serial" shell mkdir -p "$remote"
    adb -s "$serial" push "$test_dir/player-test" "${files[@]}" "$remote/" >/dev/null
    adb -s "$serial" shell chmod 700 "$remote/player-test"
    adb -s "$serial" shell "cd $remote && ./player-test player_test.lua main.lua m3u.lua library.lua manifest.json en.json"
    adb -s "$serial" shell rm -rf "$remote"
else
    java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"   # tos_bridge.c includes jni.h
    clang -O2 -I"$java_home/include" -I"$java_home/include/darwin" -I"$cpp/lua" "$repo_root/android/app/src/testNative/player_test.c" "${lua_sources[@]}" "$cpp/json/yyjson.c" -lm -o "$test_dir/player-test"
    "$test_dir/player-test" "${files[@]}"
fi
