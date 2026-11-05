#!/bin/bash
# 构建 thinkos-immersive：一个 Vector（Xposed）模块 APK → system/build/thinkos-immersive-<版本>.apk
#
#   system/build-immersive.sh
#
# 不用 Gradle；Xposed API 的类由设备上的 Vector 提供，所以 stubs/ 只参与编译，不进 APK。
# 工具取自 android/local.properties 的 sdk.dir（最新的 build-tools，compileSdk 对应的 android.jar），签名用 Android 调试密钥。
set -euo pipefail
cd "$(dirname "$0")"
MODULE=thinkos-immersive
OUT=build
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export PATH="$JAVA_HOME/bin:$PATH"   # apksigner 用 PATH 里的 java，别让太新的 JDK 刷警告

SDK=$(sed -n 's/^sdk.dir=//p' ../android/local.properties)
COMPILE_SDK=$(sed -n 's/.*compileSdk = \([0-9]*\).*/\1/p' ../android/app/build.gradle.kts)
[ -n "$SDK" ] && [ -n "$COMPILE_SDK" ] || { echo "找不到 sdk.dir 或 compileSdk" >&2; exit 1; }
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
ANDROID_JAR="$SDK/platforms/android-$COMPILE_SDK/android.jar"
KEYSTORE="${THINKOS_KEYSTORE:-$HOME/.android/debug.keystore}"
for need in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$ANDROID_JAR" "$KEYSTORE"; do
  [ -e "$need" ] || { echo "缺少 $need" >&2; exit 1; }
done

VERSION=1.2
STAGE=$(mktemp -d "${TMPDIR:-/tmp}/thinkos-immersive.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$OUT" "$STAGE/stubs" "$STAGE/classes" "$STAGE/dex"

# 替身先编，再用它们编钩子；只有钩子的类进 dex。
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all,-options -Werror -d "$STAGE/stubs" $(find "$MODULE/stubs" -name '*.java')
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all,-options -Werror -cp "$STAGE/stubs:$ANDROID_JAR" -d "$STAGE/classes" $(find "$MODULE/src" -name '*.java')
"$BT/d8" --release --min-api 27 --lib "$ANDROID_JAR" --output "$STAGE/dex" $(find "$STAGE/classes" -name '*.class')

"$BT/aapt2" compile --dir "$MODULE/res" -o "$STAGE/res.zip"
"$BT/aapt2" link -o "$STAGE/base.apk" -I "$ANDROID_JAR" --manifest "$MODULE/AndroidManifest.xml" \
  --min-sdk-version 27 --target-sdk-version 27 --version-code 3 --version-name "$VERSION" -A "$MODULE/assets" "$STAGE/res.zip"
(cd "$STAGE/dex" && zip -q -X "$STAGE/base.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$STAGE/base.apk" "$STAGE/aligned.apk"

APK="$OUT/$MODULE-$VERSION.apk"
rm -f "$APK" "$APK.idsig"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --out "$APK" "$STAGE/aligned.apk"
"$BT/apksigner" verify "$APK"
echo "$APK"
unzip -l "$APK" | tail -n +4
