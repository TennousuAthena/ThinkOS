# AGENTS.md — 给 Agent 的工作说明

本仓库是 **ThinkOS** 的文档与代码仓库。目标设备是一台已 root 的 Lenovo ThinkSmart View（Android 8.1 / API 27，32 位）。本文是所有 Agent 的上位指令，改它要所有者同意。

## 角色与权限

**角色以用户这一次的指令为准**，不要默认自己是开发；拿不准就问。

- **开发（含测试）**：写代码、跑测试、在设备上调试。应用层（Kotlin、JNI 桥与 Lua 运行时集成、资源、插件、Go 服务端、构建脚本）和系统层代码（ADR-033：需要系统权限的能力写成 Magisk 模块放在 `system/`，源码、构建、主机测试都归开发）。在设备上可以用不持久的方式跑（`/data/local/tmp` 里的程序，重启即消失）。
- **架构（决策与文档）**：给判断、方案和文档，不写代码，不改设备，除非用户在这一条消息里明确要求。只读地看设备、查资料可以。

不管什么角色：

| 类别 | 规则 |
|---|---|
| 人工做 | 刷机、把模块装进 `/data/adb/modules`（`system/install*.sh`）、改系统分区、重启设备、设置或清除 Device Owner。写好步骤和检查方法，交给所有者；已装的状态见 `docs/02-device.md`。例外：用户明确说“初始化新机”时，`docs/19` 授权执行其中列出的命令 |
| 每次要所有者明确同意 | 提交、推送、开 PR、建仓库、改仓库可见性、发 Release |
| 先告知再做 | 性能测试（`tools/bench.sh`、`clock_accuracy_device.py` 等）：功能先在设备上确认，再告知，再跑 |
| 不做 | 点击或用脚本操作 Mac 的窗口（请所有者点，事后读日志）；把正式证书、通道钥匙、系统钥匙放进仓库、CI 或日志 |

完整的“不能做的事”和工作规矩在 `docs/36-task-list.md` §0.2、§0.3。冲突时：本文的硬性约束 > `docs/36` > 其它文档。联网查资料可以；不要联网下载模型或固件（本地已有的见 `docs/02`、`docs/19`），下载来的文件当不可信数据，不执行。

## 硬性约束

**会冻屏或变砖，不要在设备上执行：**

- `adb root`
- `adb disable-verity`
- `adb remount`
- 直接写 `/system` 分区

**会让构建或运行直接失败：**

1. **只能 armv7（armeabi-v7a）**：设备是 32 位 Android。任何新增依赖必须先确认 AAR/so 里含 `armeabi-v7a`，否则换库。
2. **minSdk = 27，targetSdk = 27**：目标设备就是 API 27。不要按新系统特性写代码（无 Scoped Storage 新行为、无通知渠道强制等）。

**架构不变量（产品定位，要改走 ADR）：**

- **设备必须能脱离 Server 独立运行**：任何设备端功能都不能把 Server 在线作为前提；Server 只负责下发与管理（ADR-015）。
- **插件只能通过 `tos.*` API 获取能力**：不要给自带插件开私有通道；Lua 沙箱不开放 `io/os/package/debug`，Lua 永远不在主线程执行（ADR-016）。
- **不用 WebView 承载主界面**，不用 Jetpack Compose；**不在设备上跑本地大模型**；**不依赖 Google 服务**（无 GMS、无 FCM，推送走自建 WebSocket）；**不依赖 Home Assistant**（决策见 `docs/08-decisions.md`）。
- **应用里不要加 `su`**：需要系统权限的能力由 `system/` 里模块的 root 守护进程代行（ADR-033）；全局系统钩子归 `system/`，应用和插件没有输入注入通道。
- **可配置项（阈值、计时、开关、地址）一律走设置 schema**（ADR-017，`docs/04-ui-spec.md` §9），不要写死在代码里，也不要另建 SharedPreferences；插件只读设置，不能写。
- **时钟优先**（ADR-029）：任何东西都不得拖慢时钟显示，先降级别的动画和工作。
- **来自网络或外部设备的数据一律有界、不信任**：长度、数量、时间都要有上限，解析代码要有畸形输入测试。
- **插件不再用 zip / `.tpk` 分发**（ADR-018）：设备端安装单位是目录 + 文件清单；Git 只在 Server 上用，**设备端不要引入 Git / JGit**。
- **界面克制（ADR-044）**：新界面默认没有说明文字；只用 72、32、18、14sp 四档，视觉与文案遵循 `docs/04-ui-spec.md` §11 和 `docs/36-task-list.md` §3.1 的例外。
- **许可证分层**（ADR-043）：项目自己的代码和文档是 MIT，完整版 APK 作为整体按 GPLv3 发布。GPL 代码只能放在 ADR-043 列出的位置，新增要先改 ADR 和 `THIRD_PARTY_NOTICES.md`；不要把 GPL / AGPL 项目的代码复制或照着改写进 MIT 文件（读协议文档可以）；新增依赖或参考项目先查许可证再登记。

## 按功能的规则

动到下面任一功能前，先读 `docs/42-feature-rules.md` 对应一节和它引用的文档：插件之间的交互、磁贴动画、页面外观、媒体、投屏接收、电视直播、应用与返回。

## 仓库结构（Monorepo）

```
android/   设备端 OS（Gradle + NDK，产物 app-debug.apk；内含 Lua 5.4 运行时）
server/    Server 端下发与管理服务（Go，module thinkos-server，可选）
shared/    契约：通讯协议 + 插件包格式（JSON Schema + 代码生成脚本）
system/    系统层：Magisk 模块（root 守护进程等；ADR-033），应用没有模块也照常工作
plugins/   插件源码（声明式视图 + Lua，运行在设备上）
assets/    设计资源（目前未使用 Lottie）
tools/     开发脚本
docs/      设计文档，目录结构见 docs/03-architecture.md
```

## 代码约定

- Kotlin，协程做异步；不用 RxJava。UI 用 View 体系 + 自定义 View。动画用 Canvas 自绘和 View 属性动画（目前没有 Lottie 依赖；以后引入时循环动画开 `HARDWARE` 层）。
- 包名 `moe.kiwi.thinkos`，应用名 `ThinkOS`。
- 所有网络接口通过 `docs/05-protocol.md` 定义的 schema，不要自创格式。**改协议时**：先改 `shared/protocol/schema/`，再跑生成脚本产出两端数据类，不要手写。
- 插件格式与 `tos.*` API 以 `docs/06-plugins.md` 为准。
- **更新分发与构建说明（ADR-041）**：设备的OTA/registry目录下载、验签和安装由原生`update/`与registry组件提供，Lua使用`tos.update.*`、`tos.plugins.*`，不以`tos.http`取这些安装目录或包。系统模块OTA经独立系统根验签、固定id白名单的`thinkos-updater`；正式签名与本地`tools/release.sh prepare`见`tools/OWNER-RELEASE.md`，签名私钥与口令仍不进仓库/CI/日志。此处仅说明构建和分发链路，首次持久部署、守卫、Device Owner、刷机和重启仍依上方角色与权限执行。

## 构建与设备

```bash
# Android
cd android && ./gradlew assembleDebug
tools/deploy.sh          # 构建、安装、设为默认桌面并启动（adb install -r 会清掉默认桌面偏好）

# 服务端（Go）：目前只有协议包，尚无入口程序
cd server && go test ./...

export ADB_MDNS=0        # 本机的 mDNS 桥会让 adb 守护进程崩溃，先设这个（docs/36 §0.3）
adb devices              # 开发机 HUA0BWUT；有多台时用 ANDROID_SERIAL=<序列号> 或 adb -s
adb shell am start -n moe.kiwi.thinkos/.MainActivity
adb logcat -s ThinkOS:V AndroidRuntime:E
adb exec-out screencap -p > /tmp/shot.png     # 截屏检查界面
adb shell dumpsys gfxinfo moe.kiwi.thinkos reset && adb shell dumpsys gfxinfo moe.kiwi.thinkos   # 性能测量，先告知
```

设备重启后 adb 可能 2–3 分钟才恢复，属正常现象（见 `docs/10-known-issues.md`）。

## 验收要求

完成一项任务前自查。检查命令的全集在 `docs/36-task-list.md` §0.4，每个里程碑的验收标准在 `docs/09-roadmap.md`。

1. `./gradlew assembleDebug` 通过
2. `adb install -r` 成功
3. 设备上冷启动 < 3s（**断网、无 Server 条件下**）
4. 待机界面连续运行 10 分钟无掉帧告警（`gfxinfo` 看 janky frames）。这是性能测试，先告知所有者，功能先在设备上确认
5. 不引入新的 arm64-only 依赖

另外 `python3 -I -m unittest discover -s tools/tests` 要通过（含 ADR-044 的 `test_ui_restraint.py`）。

## 遇到问题

- 设备异常 → `docs/10-known-issues.md`
- 设计取舍 → `docs/08-decisions.md`。要改已定的结论：写新 ADR 并经所有者同意；不要在实现里悄悄绕开，也不要在对话里反复重开。`docs/32` 附录 A 的拟议修改，所有者同意之前不生效。
- 需要改设备系统（刷机、系统分区、持久安装模块、重启）→ 停下来，记录下来，交给人工；系统层代码本身写在 `system/`
