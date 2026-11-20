#!/bin/bash
# Build only. No installation, system mutation or restart.
set -euo pipefail
cd "$(dirname "$0")"
MODULE=thinkos-updater
OUT=build
HOST=false
DEVICE_TEST=false
KEYS_HEADER=
POLICY_ARGS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --host) HOST=true ;;
    --device-test) DEVICE_TEST=true ;;
    --keys-header) shift; KEYS_HEADER=${1:?Missing public keys header} ;;
    --guard-health-s) shift; POLICY_ARGS+=(--health-s "${1:?Missing seconds}") ;;
    --guard-poll-s) shift; POLICY_ARGS+=(--poll-s "${1:?Missing seconds}") ;;
    *) echo "usage: $0 [--host|--device-test] [--keys-header /owner/public/updater_keys.h] [--guard-health-s N] [--guard-poll-s N]" >&2; exit 2 ;;
  esac
  shift
done
mkdir -p "$OUT"
ARGS=(-Wall -Wextra -Werror -O1)
if [ -n "$KEYS_HEADER" ]; then
  [ -f "$KEYS_HEADER" ] || { echo "Public keys header unavailable" >&2; exit 1; }
  KEYS_HEADER=$(cd "$(dirname "$KEYS_HEADER")" && pwd)/$(basename "$KEYS_HEADER")
  ARGS+=("-DUP_SYSTEM_KEYS_HEADER=\"$KEYS_HEADER\"")
else
  echo "No owner system public keys: resulting daemon refuses distribution transactions" >&2
fi
SOURCES=("$MODULE/src/updaterd.c" "$MODULE/src/guard_policy.c" "$MODULE/src/module_verify.c" "$MODULE/src/micro_ecc.c" "$MODULE/vendor/sha-2/sha-256.c")
if $HOST; then
  "${CC:-clang}" "${ARGS[@]}" -DUPDATER_HOST_TEST -o "$OUT/updaterd-host" "${SOURCES[@]}"
  echo "$OUT/updaterd-host"
  exit 0
fi
SDK=$(sed -n 's/^sdk.dir=//p' ../android/local.properties)
NDK_VERSION=$(sed -n 's/.*ndkVersion = "\([^"]*\)".*/\1/p' ../android/app/build.gradle.kts)
[ -n "$SDK" ] && [ -n "$NDK_VERSION" ] || { echo "sdk.dir or ndkVersion missing" >&2; exit 1; }
BIN=$(echo "$SDK/ndk/$NDK_VERSION"/toolchains/llvm/prebuilt/*/bin)
CC_ANDROID="$BIN/armv7a-linux-androideabi27-clang"
[ -x "$CC_ANDROID" ] || { echo "API27 armv7 NDK compiler unavailable" >&2; exit 1; }
ARGS+=(-Os -fPIE -pie -Wl,--gc-sections -Wl,-z,relro,-z,now)
if $DEVICE_TEST; then
  "$CC_ANDROID" "${ARGS[@]}" -DUPDATER_HOST_TEST -o "$OUT/updaterd-test-armv7" "${SOURCES[@]}"
  echo "$OUT/updaterd-test-armv7 (temporary testing only; never packaged)"
  exit 0
fi
STAGE=$(mktemp -d "${TMPDIR:-/tmp}/thinkos-updater.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
cp -R "$MODULE/META-INF" "$MODULE/module.prop" "$MODULE/customize.sh" "$MODULE/service.sh" "$MODULE/sepolicy.rule" "$STAGE/"
mkdir -p "$STAGE/bin" "$STAGE/licenses" "$STAGE/guard"
cp "$MODULE/guard/post-fs-data.sh" "$MODULE/guard/service.sh" "$STAGE/guard/"
# The guard's policy comes from the same settings schema as the app's (defaults unless overridden above); the installer places it once.
python3 -I gen_guard_policy.py --output "$STAGE/guard/policy" "${POLICY_ARGS[@]+"${POLICY_ARGS[@]}"}" >/dev/null
"$CC_ANDROID" "${ARGS[@]}" -o "$STAGE/bin/updaterd" "${SOURCES[@]}"
"$BIN/llvm-strip" "$STAGE/bin/updaterd"
cp "$MODULE/vendor/micro-ecc/LICENSE.txt" "$STAGE/licenses/micro-ecc.txt"
cp "$MODULE/vendor/sha-2/LICENSE.md" "$STAGE/licenses/sha-2.md"
for DOCUMENT in "$MODULE/vendor/ORIGIN.md" "$MODULE/vendor/sources.lock.json"; do
  if [ -f "$DOCUMENT" ]; then cp "$DOCUMENT" "$STAGE/licenses/"; fi
done
chmod 755 "$STAGE/bin/updaterd" "$STAGE/service.sh" "$STAGE/customize.sh" "$STAGE/guard/post-fs-data.sh" "$STAGE/guard/service.sh" "$STAGE/META-INF/com/google/android/update-binary"
VERSION=$(sed -n 's/^version=//p' "$MODULE/module.prop")
[[ "$VERSION" =~ ^v[0-9]+\.[0-9]+$ ]] || { echo "Invalid module version" >&2; exit 1; }
ZIP="$PWD/$OUT/$MODULE-$VERSION.zip"
rm -f "$ZIP"
python3 -I "$PWD/pack_module.py" "$STAGE" "$ZIP"
cp "$STAGE/bin/updaterd" "$OUT/updaterd-armv7"
echo "$ZIP"
