#!/bin/bash
# 构建断电保护模块（thinkos-power）。
#
#   system/build-power.sh            出 Android（armeabi-v7a）的 Magisk 模块包 → system/build/thinkos-power-<版本>.zip，并留一份 build/powerd-armv7
#   system/build-power.sh --host     只编一份本机可跑的 powerd（测试用）→ system/build/powerd-host
#
# NDK 取 android/local.properties 的 sdk.dir 下与 android/app/build.gradle.kts 里 ndkVersion 相同的那一份。
set -euo pipefail
cd "$(dirname "$0")"
MODULE=thinkos-power
OUT=build
mkdir -p "$OUT"

if [ "${1:-}" = "--host" ]; then
  ${CC:-clang} -Wall -Wextra -Werror -O1 -o "$OUT/powerd-host" "$MODULE/src/powerd.c"
  echo "$OUT/powerd-host"
  exit 0
fi

SDK=$(sed -n 's/^sdk.dir=//p' ../android/local.properties)
NDK_VERSION=$(sed -n 's/.*ndkVersion = "\(.*\)".*/\1/p' ../android/app/build.gradle.kts)
[ -n "$SDK" ] && [ -n "$NDK_VERSION" ] || { echo "找不到 sdk.dir 或 ndkVersion" >&2; exit 1; }
BIN=$(echo "$SDK/ndk/$NDK_VERSION"/toolchains/llvm/prebuilt/*/bin)
[ -x "$BIN/armv7a-linux-androideabi27-clang" ] || { echo "NDK $NDK_VERSION 不在 $SDK" >&2; exit 1; }

STAGE=$(mktemp -d "${TMPDIR:-/tmp}/thinkos-power.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
cp -R "$MODULE/META-INF" "$MODULE/module.prop" "$MODULE/customize.sh" "$MODULE/post-fs-data.sh" "$STAGE/"
mkdir "$STAGE/bin"
"$BIN/armv7a-linux-androideabi27-clang" -Wall -Wextra -Werror -Os -fPIE -pie -Wl,--gc-sections -Wl,-z,relro,-z,now \
  -o "$STAGE/bin/powerd" "$MODULE/src/powerd.c" -llog
"$BIN/llvm-strip" "$STAGE/bin/powerd"
chmod 755 "$STAGE/bin/powerd" "$STAGE/post-fs-data.sh" "$STAGE/customize.sh" "$STAGE/META-INF/com/google/android/update-binary"
VERSION=$(sed -n 's/^version=//p' "$MODULE/module.prop")
ZIP="$OUT/$MODULE-$VERSION.zip"
rm -f "$ZIP"
(cd "$STAGE" && zip -q -X -r "$OLDPWD/$ZIP" .)
cp "$STAGE/bin/powerd" "$OUT/powerd-armv7"
echo "$ZIP"
unzip -l "$ZIP" | tail -n +4
