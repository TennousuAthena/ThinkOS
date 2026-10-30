# AGENTS.md — 给开发 Agent 的工作说明

本仓库是 **ThinkOS** 的文档与代码仓库。目标设备是一台已 root 的 Lenovo ThinkSmart View（Android 8.1 / 32 位）。

## 你的任务边界

- 你负责**应用层**开发：Kotlin 代码、JNI 桥与 Lua 运行时集成、资源、插件（Lua/JSON）、Go 服务端、构建脚本
- 你不负责**设备系统层**改造（刷机、改 Magisk 模块、改系统分区）——这些已由人工完成，见 `docs/02-device.md`
- 不要尝试联网下载模型/固件；本地已有资源见设备文档

## 硬性约束（违反会直接失败）

1. **只能 armv7（armeabi-v7a）**：设备是 32 位 Android。任何新增依赖必须先确认 AAR/so 里含 `armeabi-v7a`，否则换库。
2. **minSdk = 27，targetSdk = 27**：目标设备就是 API 27。不要按新系统特性写代码（无 Scoped Storage 新行为、无通知渠道强制等）。
3. **不要用 WebView 承载主界面**：性能不达标，产品定位明确排除。
4. **不要在设备上跑本地大模型**：2GB 内存 + A53，做不到。
5. **不要执行以下设备命令**（会冻屏或变砖）：
   - `adb root`
   - `adb disable-verity`
   - `adb remount`
   - 直接写 `/system` 分区
6. **不要依赖 Google 服务**：设备无 GMS、无 FCM。推送走自建的实时通道（WebSocket）。
8. **设备必须能脱离 Server 独立运行**：任何设备端功能都不能把 Server 在线作为前提；Server 只负责下发与管理（ADR-015）。
9. **插件只能通过 `tos.*` API 获取能力**：不要给自带插件开私有通道；Lua 沙箱不开放 `io/os/package/debug`（ADR-016）。
7. **不要依赖 Home Assistant**：本项目走独立架构（决策记录见 `docs/08-decisions.md`）。

## 仓库结构（Monorepo）

```
android/   设备端 OS（Gradle + NDK，产物 app-debug.apk；内含 Lua 5.4 运行时）
server/    Server 端下发与管理服务（Go，module thinkos-server，可选）
shared/    契约：通讯协议 + 插件包格式（JSON Schema + 代码生成脚本）
plugins/   插件源码（声明式视图 + Lua，运行在设备上）
assets/    Lottie 等设计资源
tools/     开发脚本
docs/      设计文档
```

两条构建链路互不影响：

```bash
# Android
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 服务端（Go）
cd server && go run ./cmd/thinkos-server --config config.toml
```

**改协议时**：先改 `shared/protocol/schema/`，再跑生成脚本产出两端数据类，不要手写。

## 设备连接

```bash
adb devices            # 期望看到 HUA0BWUT device
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n moe.kiwi.thinkos/.MainActivity
adb logcat -s ThinkOS:V AndroidRuntime:E
```

设备重启后 adb 可能 2–3 分钟才恢复，属正常现象（见 `docs/10-known-issues.md`）。

截屏检查界面：

```bash
adb exec-out screencap -p > /tmp/shot.png
```

性能测量：

```bash
adb shell dumpsys gfxinfo moe.kiwi.thinkos reset
adb shell dumpsys gfxinfo moe.kiwi.thinkos
```

## 代码约定

- 语言：Kotlin，协程做异步；不用 RxJava
- UI：View 体系 + 自定义 View，不用 Jetpack Compose（A53 上太重）
- 动画：Lottie + Canvas 自绘；循环动画开 `HARDWARE` 层
- 包名：`moe.kiwi.thinkos`，应用名 `ThinkOS`
- 目录结构见 `docs/03-architecture.md`
- 所有网络接口通过 `docs/05-protocol.md` 定义的 schema，不要自创格式
- 插件格式与 `tos.*` API 以 `docs/06-plugins.md` 为准；Lua 永远不在主线程执行
- **可配置项（阈值、计时、开关、地址）一律走设置 schema**（ADR-017，`docs/04-ui-spec.md` §9），不要写死在代码里，也不要另建 SharedPreferences；插件只读设置，不能写
- **不为页面外观预留扩展点**（ADR-017；ADR-025 仅允许 `tile` 磁贴底色 `color`，前景色由 OS 决定）：不要引入样式语言、表盘包、部件皮肤；需要新的版式或组件，按 `docs/06-plugins.md` §4.1 的扩展流程加词汇
- **插件不再用 zip / `.tpk` 分发**（ADR-018）：设备端安装单位是目录 + 文件清单；Git 只在 Server 上用，**设备端不要引入 Git / JGit**

## 验收要求

每个里程碑的验收标准写在 `docs/09-roadmap.md`。提交前自查：

1. `./gradlew assembleDebug` 通过
2. `adb install -r` 成功
3. 设备上冷启动 < 3s（**断网、无 Server 条件下**）
4. 待机界面连续运行 10 分钟无掉帧告警（`gfxinfo` 看 janky frames）
5. 不引入新的 arm64-only 依赖

## 遇到问题

- 设备异常 → `docs/10-known-issues.md`
- 设计取舍 → `docs/08-decisions.md`（已经有结论的不要重新讨论）
- 需要改设备系统 → 停下来，记录下来，交给人工
