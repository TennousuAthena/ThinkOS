# ThinkOS

> 一块好看、会动、能听会看的桌面屏幕。（原名 ThinkBase）
> 目标设备：Lenovo ThinkSmart View CD-18781Y（Android 8.1 / armv7 / 2GB）

## 这是什么

ThinkOS 是运行在 ThinkSmart View 上的 **Kiosk 主应用（设备外壳）**：

- 开机自启，接管桌面（注册为 HOME Launcher）
- 全屏沉浸，无状态栏、无导航栏、无多任务
- **自主运行**：插件（声明式视图 + Lua）在设备上执行，没有 Server 也能完整使用
- 负责**渲染**（页面、动画、相册、频谱）、**感知**（传感器、摄像头）与**播放**
- 局域网内的 Server 主机负责**下发与管理**（插件、配置、照片集）和可选的重活
- 语音助手是可选模块，不是主线

## 当前状态

**M0 基础框架已实现，并安装到目标真机。** Android 工程包含 Kiosk 外壳、四轴状态、原生舞台、传感器主题/注意力、Lua 5.4 沙箱、插件热加载，以及内置首页和时钟。完全本地运行，Server 尚未实现。

**M0.5 设置体系与目录安装已实现，验收持续补齐。** 现已加入自带 IANA 时区、多语言、系统日期格式、本地日月/季节计算，以及城市/区县搜索与离线位置设置。下一步继续 M1 的媒体能力、组件库和内容插件；M2 再接入 Server 管理。

当前开发与验收以 [任务清单](docs/36-task-list.md) 为准。组件、设置交互和模拟通知的历史真机结果见 [UI 验证记录](docs/17-ui-validation.md)；HTTP 通知仍暂缓。

**流媒体播放已实现并在真机验收（2026-11-01，API 7 / ADR-030）**：本地音视频与 HLS / DASH / RTSP / 渐进式 HTTP，常见容器与编码（含 AC-3 / DTS / ALAC 音频），旁路与内嵌字幕，音轨切换，直播与断线自动恢复，系统插件 `player`。设计、支持矩阵、验收数据和已知限制见 [流媒体播放](docs/27-streaming.md)。

**投屏接收已实现，真实发送端待验收（2026-11-01，ADR-031 / ADR-033）**：DLNA、AirPlay 视频推送与屏幕镜像、Miracast 屏幕共享都走 OS 的同一个播放器；Miracast 需要系统层模块 `system/thinkos-cast`（root 守护进程，由人工装进设备）。在真机上用脚本扮演的发送端通过了自动检查，华为手机与 iPhone 的实测还没做（AirPlay 镜像用到 GPLv3 的 FairPlay 代码，所以完整版 APK 按 GPLv3 发布，见文末“许可证”）。记录、限制与验收步骤见 [投屏接收](docs/28-casting.md)，系统层说明见 [system/README.md](system/README.md)。

**API 8 已有操作栏、音乐界面和电视**：顶部下拉操作栏提供媒体、亮度、音量和网络入口；原生音乐界面包含封面、队列和迷你播放条；`iptv` 插件提供频道列表、线路检测、收藏和节目单。沉浸式系统模块与应用返回手势也已实现。实现与验收边界分别见 [操作栏](docs/29-panel-and-apps.md)、[音乐界面](docs/30-player-ui.md)、[电视](docs/31-iptv.md)、[应用与返回](docs/33-apps-and-back.md)。

构建与安装：`tools/deploy.sh`。插件迭代：`tools/plugin-push.sh plugins/clock`。具体验证结果与尚待人工确认的项目见 [M0 验证记录](docs/12-m0-validation.md)。

已完成 M1 前运行时加固：类型化 JNI、原生 JSON、异步 HTTP/协程恢复、插件错误冷却重试、延迟存储、原生退出归因，以及跟手翻页/翻栈与缓存属性动画。实现与新增验证见 [M1 前加固记录](docs/13-pre-m1-hardening.md)。

## 文档索引

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 给开发 Agent 的工作说明与红线 |
| [docs/01-vision.md](docs/01-vision.md) | 产品定位与功能范围（v0.3） |
| [docs/02-device.md](docs/02-device.md) | 设备事实、系统状态、刷机与救援 |
| [docs/03-architecture.md](docs/03-architecture.md) | 总体架构：设备自主 OS、Lua 插件运行时、Server 下发与管理 |
| [docs/04-ui-spec.md](docs/04-ui-spec.md) | 视觉与动效规范、性能预算 |
| [docs/05-protocol.md](docs/05-protocol.md) | 设备 ↔ Server 通讯：期望状态同步、回报、可选实时通道 |
| [docs/06-plugins.md](docs/06-plugins.md) | 插件模型：声明式视图 + Lua、`tos.*` API、示例 |
| [docs/07-build-deploy.md](docs/07-build-deploy.md) | 构建、安装、Kiosk 接管、调试命令 |
| [docs/08-decisions.md](docs/08-decisions.md) | 已定决策与理由（ADR） |
| [docs/09-roadmap.md](docs/09-roadmap.md) | 里程碑与验收标准 |
| [docs/10-known-issues.md](docs/10-known-issues.md) | 已知坑与规避方案 |
| [docs/11-repo-layout.md](docs/11-repo-layout.md) | **Monorepo 架构**：android / server / shared / plugins 划分 |
| [docs/12-m0-validation.md](docs/12-m0-validation.md) | M0 历史真机结果 |
| [docs/13-pre-m1-hardening.md](docs/13-pre-m1-hardening.md) | M1 前运行时、交互与性能加固 |
| [docs/14-handoff.md](docs/14-handoff.md) | **开发交接**：目标、真实进度、待办与设计草案、环境命令 |
| [docs/15-location.md](docs/15-location.md) | 位置设置、城市/区县查询与离线手填 |
| [docs/16-plugin-directory-validation.md](docs/16-plugin-directory-validation.md) | 目录安装的校验、热更新与回归记录 |
| [docs/17-ui-validation.md](docs/17-ui-validation.md) | 组件、设置交互、横竖屏、主题与模拟通知真机截图 |
| [docs/18-notification-http-proposal.md](docs/18-notification-http-proposal.md) | HTTP 通知候选方案，当前仅做 UI 预览 |
| [docs/19-provisioning-for-agent.md](docs/19-provisioning-for-agent.md) | **新机刷机与设置**：Agent 全自动手册（EDL → LineageOS → Magisk → 系统设置 → ThinkOS） |
| [docs/20-weather-handoff.md](docs/20-weather-handoff.md) | 天气插件交接与验收 |
| [docs/21-gallery-handoff.md](docs/21-gallery-handoff.md) | 相册插件：接口、实现与开发交接 |
| [docs/22-player-plan.md](docs/22-player-plan.md) | 播放器规划：媒体接口、player 系统插件与页面 |
| [docs/23-tiles-plan.md](docs/23-tiles-plan.md) | 磁贴规划：`tiles` 版式、`tile` 卡片模板与 `tiles` 插件 |
| [docs/24-tiles-validation.md](docs/24-tiles-validation.md) | 磁贴实现与真机验收 |
| [docs/25-windows-design.md](docs/25-windows-design.md) | Windows 设计参考与原生 Aero 主页 |
| [docs/26-public-data.md](docs/26-public-data.md) | 插件公开数据：设计取舍与真机验收 |
| [docs/27-streaming.md](docs/27-streaming.md) | 流媒体播放：实现、验收与已知限制 |
| [docs/28-casting.md](docs/28-casting.md) | 投屏接收（DLNA / AirPlay / Miracast）：计划与验证记录 |
| [docs/29-panel-and-apps.md](docs/29-panel-and-apps.md) | 操作栏与应用：策划 |
| [docs/30-player-ui.md](docs/30-player-ui.md) | 播放器界面：音频页、资料库与首页卡片 |
| [docs/31-iptv.md](docs/31-iptv.md) | 电视直播：iptv 插件与 tv 组件 |
| [docs/32-open-ui.md](docs/32-open-ui.md) | 开放界面：系统 UI 与组件也是插件 |
| [docs/33-apps-and-back.md](docs/33-apps-and-back.md) | 应用与全局返回手势 |
| [docs/34-ota-and-plugin-registry.md](docs/34-ota-and-plugin-registry.md) | OTA 更新与插件仓库：策划 |
| [docs/35-onboarding.md](docs/35-onboarding.md) | 新用户启用引导：策划 |
| [docs/36-task-list.md](docs/36-task-list.md) | 未完成工作：任务清单与验收计划 |
| [docs/37-android-apps-and-up-gesture.md](docs/37-android-apps-and-up-gesture.md) | 安卓应用页与底边上滑：计划 |
| [docs/39-radio-directory.md](docs/39-radio-directory.md) | 电台目录 radio-browser：评估与风险 |
| [docs/40-power-loss.md](docs/40-power-loss.md) | 断电保护：拔电源线时怎么不伤分区 |
| [docs/41-ca-trust.md](docs/41-ca-trust.md) | 系统根证书更新：设备的 CA 库是 2015 年的，怎么换成 Mozilla 当前的 |
| [docs/42-feature-rules.md](docs/42-feature-rules.md) | 按功能的规则：插件交互、磁贴动画、外观、媒体、投屏、电视、应用与返回（原在 AGENTS.md） |
| [docs/43-video-call.md](docs/43-video-call.md) | 设备到设备的音视频通话：决定、实测、STUN 列表、会合渠道 |
| [docs/44-call-plan.md](docs/44-call-plan.md) | 通话实施方案（局域网版）：架构、决定、发现与信令、阶段与门槛、风险 |
| [docs/45-modes.md](docs/45-modes.md) | 模式：白天、红移、睡眠，以及怎么切换 |
| [docs/46-bluetooth-speaker.md](docs/46-bluetooth-speaker.md) | 蓝牙音箱：手机连上设备，用它的扬声器播放 |

磁贴已接入 API 4：直角 Metro 网格、自定义底色与动态翻面，设备可离线使用。实现与实测结果见 [磁贴验收](docs/24-tiles-validation.md)。

API 5 增加可横滑切换的原生 Aero 桌面：玻璃窗口、任务栏、开始菜单，以及公开时钟/天气/相册卡片。设计参考与实现边界见 [Windows 设计](docs/25-windows-design.md)。

API 8 的**应用**：全屏、不在翻页序列里、左右滑归应用自己，用**最左缘右滑或 Back 键返回**；打开应用时磁贴像门一样翻走（Windows 8.1 风格）。设计与验证见 [应用与返回](docs/33-apps-and-back.md)。

API 6 让插件可以显式公开数据、指令和卡片排版：页面不写脚本、不申请权限也能直接显示相册的 Bing 每日图。见 [公开数据](docs/26-public-data.md)。

## 一句话架构

```
android/  —— 设备端 OS：Lua 插件运行时 + 渲染 + 感知 + 播放，可独立运行
server/   —— Server（Go，可选）：下发插件/配置/照片集、通知中转、管理与观测
shared/   —— 两端共享的协议契约（schema → 生成两端数据类）
plugins/  —— 所有功能都是插件（页面即插件）：声明式视图 + Lua，运行在设备上
```

Android 构建：`cd android && ./gradlew assembleDebug`。Go 协议包检查：`cd server && go test ./...`。Server 尚无入口程序，`server/cmd/thinkos-server` 为空。
详见 [docs/11-repo-layout.md](docs/11-repo-layout.md)。

## 关键事实速查

- 设备：CD-18781Y，代号 `starfire`，APQ8053，2GB RAM，8 英寸 800×1280
- 系统：LineageOS 15.1（Android 8.1 / API 27 / userdebug），去谷歌版
- ABI：**只有 armeabi-v7a**（32 位），任何依赖必须先确认有 32 位 so
- 权限：已 root（Magisk 27 + Vector 2.2）
- 时间：无 RTC，开机由 Magisk 模块 `timesync` 自动校时（已解决）
- 屏幕：已设常亮（`stay_on_while_plugged_in=7`）
- adb 序列号：`HUA0BWUT`（经 USB 2.0 hub 连接）

> 详细说明见 `docs/02-device.md`，遇到设备问题先看 `docs/10-known-issues.md`。

## 许可证

- 项目自己写的代码和文档：[MIT](LICENSE)。
- 完整版 APK：作为一个整体按 [GPLv3](LICENSE-GPL-3.0.md) 发布，因为里面有 GPLv3 的 FairPlay 代码和 FFmpeg 音频扩展。
- 哪些文件和依赖不是 MIT，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；决策见 ADR-043。
