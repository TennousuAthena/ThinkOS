# 07 · 构建、安装与 Kiosk 接管

## 1. 环境要求

| 项 | 要求 |
|---|---|
| JDK | 17 或 21（Gradle/AGP 运行用）。**本机默认的 JDK 27 过新**，构建时需用 `JAVA_HOME` 或 `org.gradle.java.home` 指向 17/21 |
| Android SDK | `platforms;android-<compileSdk>`、`build-tools`、`platform-tools`；compileSdk 按依赖要求（Media3 新版需要 34–35），设备跑 API 27 |
| NDK + CMake | 编译 Lua 5.4 与 JNI 桥（`armeabi-v7a`）；SDK 目录下已有 `ndk/`，CMake 通过 sdkmanager 安装 |
| Gradle | 8.x，用 wrapper |
| AGP / Kotlin | 选择与 JDK 17/21 匹配的稳定版本，首次搭建时确定并写回本表 |

> 2026-10-29 实现时发现完整 SDK 实际位于 `/opt/homebrew/share/android-commandlinetools`：已具备 platform 35、build-tools 35.0.0、NDK 26.3.11579264。复用本地 Gradle 8.13 / AGP 8.10.1 / Kotlin 2.2.0 / Corretto JDK 17 / Homebrew CMake 4.4.5，无需补装 SDK。
>
> 固定 `compileSdk=35`、`minSdk=targetSdk=27`。`libtos.so` 包含自编译 Lua 5.4.9、JNI 桥与 yyjson 0.12.0；Kotlin 和 coroutines 为 JVM 依赖。全部 C 源码已存入仓库，构建不在线拉取。

`local.properties` 指向本机 SDK：

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
cmake.dir=/opt/homebrew/opt/cmake
```

## 2. 构建

```bash
# 本机默认 JDK 27 不能运行所固定的 Gradle/AGP；deploy.sh 会自动选择 17/21。
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

**ABI 检查（每次加依赖后都要做）**

```bash
# 解开 APK 看 so
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep 'lib/'
# 期望只看到 lib/armeabi-v7a/
```

若出现 `arm64-v8a` 或缺少 `armeabi-v7a`，说明依赖不兼容，必须换库。

**Lua 与 JNI 桥**

- Lua 5.4 官方源码放在 `android/app/src/main/cpp/lua/`；仅 `lstrlib.c` 有模式匹配预算补丁，升级时必须保留。`tos_bridge.c` 为类型化 JNI 与协程桥
- `cpp/json/` 固定 yyjson 0.12.0（MIT），来源与 SHA-256 在 `SOURCE.json`；Lua 与 yyjson 同 ABI 编译，无外部二进制依赖。补丁说明见 `cpp/PATCHES.md`
- `app/build.gradle.kts` 中 `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }`，`ndk { abiFilters += "armeabi-v7a" }`
- 构建后应能在 APK 中看到 `lib/armeabi-v7a/libtos.so`
- 修改桥接或升级 C 源码后运行 `android/app/src/testNative/run.sh`，覆盖模式匹配、预算保护、JSON、大响应、跨挂起恢复/取消及内存限制；再运行 `./gradlew testDebugUnitTest lintDebug assembleDebug`

**内置插件**

- Gradle 任务在 `preBuild` 前生成插件 Schema Kotlin 校验器，并调用 `tools/package_plugins.py` 处理 `plugins/home`、`plugins/clock`、`plugins/weather`、`plugins/settings`，输出到 `app/src/main/assets/plugins/`（生成物，不提交）
- 校验后把每个插件复制为目录 `assets/plugins/<id>/…`，并生成 `assets/plugins/index.json`（每个插件的 `digest` 与 `{path, sha256, size}` 文件清单）；各插件目录也有独立 `index.json`。不再生成 zip / `.tpk`（ADR-018）
- 校验规则与 Server 的 registry 模块一致（同一份 `shared/schema/plugin/`）

## 3. 安装到设备

```bash
adb devices                       # 确认 HUA0BWUT
adb install -r app-debug.apk
adb shell am start -n moe.kiwi.thinkos/.MainActivity
adb logcat -s ThinkOS:V AndroidRuntime:E
```

### 3.1 调试安装插件（仅 debug 构建，不需要 Server）

没有 Server 时，用 `run-as` 把插件目录放进应用的开发目录，运行时监听目录发布事件并热加载：

```bash
tools/plugin-push.sh plugins/clock
tools/plugin-push.sh --remove clock
```

- `files/dev-plugins/<id>/` 中的插件优先于内置版本，删除即恢复。
- release 构建不启用该通道；安全模式也不读取开发插件。
- 脚本校验后在一次 `run-as sh` 会话内用 base64 逐文件写入隐藏 `.stage-<id>-<nonce>/`，最后发布整个目录。替换时旧目录短暂移到隐藏 `.previous-<id>/`，随后把新目录改名为 `<id>/`；只监听新目录发布与显式删除，避免替换间隙触发卸载；此前已排队的扫描也会把 `.previous-<id>/` 视为仍已发布的版本。失败会恢复旧目录，应用启动也会恢复中断替换的备份。
- 文件按清单校验大小、SHA-256、路径、白名单与总量，再做 manifest / view 校验。坏文件和启动错误保留正在运行的版本；错误写入 ThinkOS 日志。Lua 死循环仍由原预算终止，热更新不重启应用。
- 旧开发通道的 `.tpk` 在启动时移除；已有开发覆盖请从源码目录重新推送。
- 有 Server 之后（M2）更推荐走 Git：把插件放进仓库，`thinkos-server plugin add <url>`（`06-plugins.md` §12）；跟踪分支的插件每次 push 后设备自动更新

## 4. 接管桌面（Kiosk）

### 4.1 注册为 Launcher

`AndroidManifest.xml`：

```xml
<activity android:name=".MainActivity" android:exported="true"
          android:launchMode="singleTask"
          android:configChanges="orientation|screenSize|keyboardHidden"
          android:screenOrientation="fullSensor">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

设为默认桌面：

```bash
adb shell cmd package set-home-activity moe.kiwi.thinkos/.MainActivity
```

### 4.2 全屏沉浸

```kotlin
window.decorView.systemUiVisibility =
    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
    View.SYSTEM_UI_FLAG_FULLSCREEN or
    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
```

### 4.3 设备所有者（可选，最强 Kiosk）

> 属于系统级操作，**由人工执行**，Agent 不要自动运行。

```bash
adb shell dpm set-device-owner moe.kiwi.thinkos/.admin.ThinkOSAdminReceiver
```

设置后可用：

```kotlin
dpm.setLockTaskPackages(admin, arrayOf("moe.kiwi.thinkos"))
dpm.setStatusBarDisabled(admin, true)
dpm.setKeyguardDisabled(admin, true)
```

应用侧已实现（`shell/KioskPolicy`）：设置项 `kiosk.lock`（默认开）为开且应用是 Device Owner 时，`MainActivity` 在 `onResume` 与设置变化时调用 `setLockTaskPackages` / `setStatusBarDisabled` / `setKeyguardDisabled` 并 `startLockTask()`。API 27 的 Lock Task 会整体屏蔽状态栏下拉、Home、Recents 和导航手势。不是 Device Owner 时这些调用都是空操作。

验证：

```bash
adb shell dpm list-owners                                # 应列出 moe.kiwi.thinkos
adb shell dumpsys activity activities | grep -i lockTask # 应看到 mLockTaskModeState=LOCKED
```

退出锁定：在设备上关闭设置项 `kiosk.lock`；救援菜单“打开系统设置”会先释放锁定，回到 ThinkOS 后自动重新锁定。

**恢复方法（务必记在文档里）**：

`dpm remove-active-admin` 只对声明了 `android:testOnly` 的 APK 有效，本项目的 APK 没有，**不要依赖它**。已验证的做法（debug 构建）：

```bash
adb shell am broadcast -n moe.kiwi.thinkos/.DebugReceiver --es command kiosk_surrender
# 日志出现 "kiosk_surrender done; owner=false" 即已放弃 Device Owner，并恢复状态栏与锁屏
```

release 构建没有这个入口；设备已 root，可作为未验证的兜底：停应用后删除 `/data/system/device_owner_2.xml` 与 `device_policies.xml` 再重启。

注意：

- `adb install -r` 会清掉“默认桌面”偏好，每次安装后要重新执行 `adb shell cmd package set-home-activity moe.kiwi.thinkos/.MainActivity`（`tools/deploy.sh` 已包含）。否则退出锁定时系统会把原厂桌面（Trebuchet）提到前台。
- 退出锁定（`kiosk.lock` 关闭）后，应用会自己把 `MainActivity` 拉回前台；锁屏在 Device Owner 下始终保持关闭，不随 `kiosk.lock` 变化。

⚠️ 设置 device owner 要求设备上**没有已登录账号**。若设置失败，先确认这一点。

## 5. 权限清单

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />        <!-- 可选，语音模块 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />           <!-- 谨慎使用 -->
```

**不要**申请 `SYSTEM_ALERT_WINDOW`（我们不做浮层）、不要用 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 引导弹窗（设备已 root，可由人工用命令加白名单）。

## 6. 后台保活

Android 8.1 后台限制较松，但仍需：

1. 前台服务（`startForeground` + 常驻通知，通知可设为低优先级）
2. 开机广播拉起：`BOOT_COMPLETED`
3. 人工一次性加入电池优化白名单：

```bash
adb shell dumpsys deviceidle whitelist +moe.kiwi.thinkos
```

4. 看门狗：`KioskService` 每 30s 检查主 Activity 是否在前台，不在则 `startActivity`（注意 `singleTask` + `FLAG_ACTIVITY_NEW_TASK`）

## 7. 调试命令速查

```bash
# 截屏
adb exec-out screencap -p > /tmp/shot.png

# 当前前台 Activity
adb shell dumpsys activity activities | grep mResumedActivity

# 帧率/卡顿
adb shell dumpsys gfxinfo moe.kiwi.thinkos reset
adb shell dumpsys gfxinfo moe.kiwi.thinkos

# 内存
adb shell dumpsys meminfo moe.kiwi.thinkos

# 进程
adb shell ps -A | grep thinkos

# 时间（确认校时是否完成）
adb shell date

# 屏幕状态
adb shell dumpsys power | grep -E "mWakefulness|Display Power"

# 展示真实原生维护菜单（仅 debug APK、受 DUMP 权限保护的 USB shell 入口）
adb shell am broadcast -n moe.kiwi.thinkos/.DebugReceiver --es command maintenance
```

## 8. 发布

- 调试阶段用 `assembleDebug` + `adb install -r`
- 稳定后 `assembleRelease`（本地签名 keystore 自建，不要提交到仓库）
- 版本号规则：`0.<里程碑>.<构建号>`，例如 `0.1.3` 表示 W1 的第 3 次构建

## 9. M0 开发检查

```bash
cd android
./gradlew testDebugUnitTest lintDebug
cd ..
python3 -m unittest discover -s tools/tests
android/app/src/testNative/run.sh
```

`ExpiredTargetSdkVersion` 的 Play 发布检查按 ADR-010 显式排除，其余 lint 错误仍阻断构建。`testNative/run.sh` 用本机 JDK/clang 测试实际 JNI 与沙箱；真机帧率与 CPU 仍以 `tools/bench.sh 600` 为准。

维护入口：左上区域双指长按 5 秒，可查看诊断、恢复出厂插件、退出安全模式或打开系统设置（暂停看门狗 5 分钟）。调试接收器仅在 debug APK 存在，并要求 shell 的 `android.permission.DUMP` 权限；不要把调试 APK 当作公开分发版。
