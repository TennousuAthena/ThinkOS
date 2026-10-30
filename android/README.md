# android/

ThinkOS 设备端 OS（自主运行，Server 可选）。**唯一产物**：`app-debug.apk` / `app-release.apk`（仅 armeabi-v7a）。

## 结构

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/          # 已提供 Gradle 8.13 wrapper
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/             Lua 5.4 源码 + JNI 桥（NDK，armeabi-v7a）
        ├── assets/plugins/  构建时生成：自带插件（目录 + index.json）
        ├── java/moe/kiwi/thinkos/
        │   ├── MainActivity.kt
        │   ├── shell/       Kiosk 接管、开机自启、崩溃自恢复
        │   ├── service/     前台服务、看门狗
        │   ├── core/        状态（四条轴）、DeckRouter、主题
        │   ├── settings/    设置存储（系统级 + 页面级，M0.5）
        │   ├── plugin/      Lua 插件运行时、tos.* API、视图绑定、插件安装
        │   ├── ui/          舞台、页面版式、卡片模板、组件、主题
        │   ├── anim/        MotionClock、自绘动画、Lottie
        │   ├── media/       播放器、频谱
        │   ├── vision/      传感器、摄像头与检测
        │   ├── sync/        期望状态同步（Server 可选）
        │   └── net/         发现、HTTP、实时通道
        └── res/
```

## 关键约束

- 包名 `moe.kiwi.thinkos`，`minSdk = 27`，`targetSdk = 27`
- `abiFilters` 只保留 `armeabi-v7a`
- 不用 Compose、不用 WebView
- 断网、无 Server 时必须能完整运行
- 详见 `../AGENTS.md`、`../docs/03-architecture.md`、`../docs/04-ui-spec.md`

## 构建

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```


## 当前实现与验证

M0 已有可构建应用。工具链、JDK 选择和验收结果分别见 `../docs/07-build-deploy.md`、`../docs/12-m0-validation.md`。本机可直接在仓库根运行 `tools/deploy.sh`；不需要 Server。

仅使用 Android 原生 View、Kotlin coroutines 与源码编译 Lua。API 27 的通知渠道仍需创建，前台服务已使用低重要度渠道。安全模式保留内置时钟，开发覆盖包不会被加载。
