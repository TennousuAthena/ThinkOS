#!/usr/bin/env bash
# The iptv plugin's Lua logic in the production sandbox (same allocator, hooks, 50 ms slice budget and JSON codec as the app),
# built for this Mac. `--device` builds the same test for armv7 and runs it on the device. Every module of the plugin is handed
# to the test as a global string `src_<module>` (dots become underscores); `manifest_json` and `strings_json` are its manifest
# and English strings.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cpp="$repo_root/android/app/src/main/cpp"
plugin="$repo_root/plugins/iptv"
test_dir="$(mktemp -d /tmp/thinkos-iptv-native.XXXXXX)"
trap 'rm -rf "$test_dir"' EXIT
lua_sources=()
for source in "$cpp"/lua/*.c; do
    case "$source" in */lua.c|*/luac.c) ;; *) lua_sources+=("$source");; esac
done
pairs=("manifest_json=$plugin/manifest.json" "strings_json=$plugin/i18n/en.json")
while IFS= read -r file; do
    module="${file#"$plugin"/}"; module="${module%.lua}"; module="${module//\//.}"
    pairs+=("src_${module//./_}=$file")
done < <(find "$plugin" -name '*.lua' | sort)
test_lua="$repo_root/tools/tests/iptv_test.lua"
if [ "${1:-}" = "--device" ]; then
    serial="${ANDROID_SERIAL:-HUA0BWUT}"
    ndk="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-commandlinetools/ndk/26.3.11579264}"
    compiler="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi27-clang"
    remote="/data/local/tmp/thinkos-iptv-native-$$"
    "$compiler" -O2 -I"$cpp/lua" "$repo_root/android/app/src/testNative/plugin_test.c" "${lua_sources[@]}" "$cpp/json/yyjson.c" -lm -o "$test_dir/plugin-test"
    adb -s "$serial" shell mkdir -p "$remote"
    args=()
    for pair in "${pairs[@]}"; do
        name="${pair%%=*}"; path="${pair#*=}"
        adb -s "$serial" push "$path" "$remote/$name" >/dev/null
        args+=("$name=$remote/$name")
    done
    adb -s "$serial" push "$test_dir/plugin-test" "$test_lua" "$remote/" >/dev/null
    adb -s "$serial" shell chmod 700 "$remote/plugin-test"
    adb -s "$serial" shell "cd $remote && ./plugin-test iptv iptv_test.lua ${args[*]}"
    adb -s "$serial" shell rm -rf "$remote"
else
    java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"   # tos_bridge.c includes jni.h
    clang -O2 -I"$java_home/include" -I"$java_home/include/darwin" -I"$cpp/lua" "$repo_root/android/app/src/testNative/plugin_test.c" "${lua_sources[@]}" "$cpp/json/yyjson.c" -lm -o "$test_dir/plugin-test"
    "$test_dir/plugin-test" iptv "$test_lua" "${pairs[@]}"
fi
