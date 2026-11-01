# 28 · 投屏接收（DLNA / AirPlay / Miracast）：计划与验证记录

> 2026-11-01（2026-11-02 更新）。ADR-031（草案）、ADR-033（系统层模块）、ADR-034（项目以 GPLv3 分发）。状态：**DLNA（C2）、AirPlay 视频推送（C3）、Miracast 投屏（C4）、AirPlay 屏幕镜像与声音（C5）都已实现，并在真机上用“脚本扮演的发送端”通过自动检查（34 / 37 / 27 / 45 项）；真实发送端中，macOS 与 iPhone Air（最新 iOS）的 AirPlay 屏幕镜像已连上并有画面（2026-11-02，延迟与声音没有量化核对）；华为手机的 Miracast、Windows、AirPlay 视频推送、各视频 App 的 DLNA 还没测**；C1 里的确认页与 `tos.cast` 还没做。
> 2026-11-01 更正：§8 ② 以前记的延迟数字偏小约 0.5 秒（测试源的读取预热没有算进去），已按补测结果改正，见 §8 ②。
> 先读：`AGENTS.md` → `08-decisions.md` ADR-015 / ADR-022 / ADR-030 → `27-streaming.md` → 本文。

## 1. 目标与边界

让 ThinkSmart View 作为投屏接收端：手机、电脑把视频、音乐、屏幕投到设备上，**播放一律走现有的 `MediaEngine` 和 `video` / `controls` / `seekbar` 组件**，不引入第二个播放器、不引入 WebView。

不做：DRM 内容、投屏发送端、在设备上跑转码、依赖 Server（ADR-015：投屏全部在设备本机完成，Server 不在线也能用）。

## 2. 参考项目（weekdayjast/MiracastReceiver，固定在提交 `d1aea7e`）

只当协议层零件库，不整体合入。结论来自读代码，**没有编译、没有运行**。

| 部分 | 体量 | 处置 |
|---|---|---|
| `dlna/`（SSDP、UPnP HTTP、XML、状态） | 约 50 KB | 移植，用我们的风格重写 |
| `miracast/` 的 `WifiDirectManager`、`WfdServer`、`WfdSessionHandler`、`WfdRootHelper` + `cpp/wfdctl.c` | 约 40 KB | 移植协议与发现；解码部分不用 |
| `miracast/` 的 `TsDemuxer`、`MiracastVideoRenderer`、`MiracastAudioPlayer`；`airplay/` 的 `VideoDecoder`、`AudioPlayer` | 约 80 KB | 不用，改交给 `MediaEngine`（延迟关口见 §5） |
| `airplay/`（RTSP、配对、镜像、音频、mDNS） | 约 300 KB + C 原生 | C3 先做视频推送；镜像与 FairPlay 见 §6 决策 3 |
| Leanback 界面、`CastReceiverService` 的广播接线、WebRTC 残留、开机自启、悬浮窗、前台服务类型 | — | 全部不要 |

注意：

- README 停在早期 WebRTC 规划，与代码不符，不能当依据。仓库只有一个测试文件（`TsDemuxerTest.kt`）。
- 仓库标 MIT，但 `cpp/playfair/` 没有任何许可证头，其上游 shairplay 的 README 写明 playfair 是 GPLv3。**MIT 标签不覆盖这部分。**
- 原项目 `minSdk 21 / targetSdk 36`，我们是 27；`NEARBY_WIFI_DEVICES`、`FOREGROUND_SERVICE_*` 等权限在 API 27 不存在，移植时逐项核对。
- 移植后保留 MIT 声明（`THIRD_PARTY_NOTICES`，写明来源提交）。

## 3. 设备事实（只读探测）

QCA9379（`ath10k_sdio`）、LineageOS 15.1 userdebug、`android.hardware.wifi.direct` 特性存在、`/sbin/su` 存在。**能否作 WFD sink、能否 P2P 与普通 Wi-Fi 并存、wpa_supplicant 认不认 `WFD_SUBELEM_SET`，都是未知，C0 实测。**

## 4. 架构

```
发送端 ─ DLNA / AirPlay / Miracast ─ CastHost（OS，协议层）─ CastSession（统一模型）
                                              │ load / play / pause / seek / volume / stop
                                              ▼
                          MediaEngine（owner = "cast:<协议>"）─ video / controls / seekbar
                                              │ 状态快照反向同步给发送端（位置、状态）
```

- **URL 推送**（DLNA、AirPlay 视频）：`engine.load`；请求头、字幕、重连、HLS / DASH 沿用。补：倍速、换清晰度地址时从原位置继续。
- **镜像**（Miracast、AirPlay 镜像）：引擎新增只能由 OS 创建的内部来源 `cast`（插件不能构造）。Miracast 的 RTP 去头后交给 Media3 的 `TsExtractor`。低延迟是另一组 `LoadControl` 参数（引擎已支持下次 `load` 按新缓冲参数重建播放器）。
- **插件**：新增权限 `cast` 与 `tos.cast.*`（API 8）：事件 `cast.request` / `cast.session` / `cast.end`，动作 `accept` / `deny` / `stop`。系统插件 `cast` 提供连接确认页、`live` 接管页（复用现有组件）、首页卡片。协议不进 Lua。
- **与现有机制衔接**：投屏到来唤醒 ASLEEP 屏（参照 `mediaPlaying`）；路由器的“30 秒本地操作优先”对投屏放行，设置页仍拦截；设置走 schema（总开关、设备名、是否需要确认、各协议开关）。
- **安全**：监听只接受私网与链路本地来源；推来的 URL 仍受 `media.public_http` 约束；日志与状态不出现凭据；SOAP / RTSP 解析器要有畸形输入测试。

## 5. 阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| **C0 真机验证**（② 完成，① 部分，③ 未做） | ①Wi-Fi Direct / WFD sink：手工注入 WFD 信息，看设备能否被 Windows 与 Android 手机发现。②延迟：用 TS 流测我们引擎的低延迟档。③iPhone 推非 DRM 的 HLS：AirPlay 视频推送要不要 FairPlay | 三个明确结论，据此决定 C3–C5 |
| C1 基础设施（**部分**：宿主、会话、设置、`cast` 插件已做；确认页、`tos.cast`、API 8 没做） | `CastHost`、`CastSession`、ADR-031 与 API 8、`tos.cast`、设置 schema、接受策略、唤醒与接管、`cast` 插件骨架、来源地址校验 | 单元测试加脚本模拟发送端：连接、接受、拒绝、停止 |
| C2 DLNA（**已实现**，见 §9） | SSDP、AVTransport、RenderingControl、ConnectionManager，DIDL 元数据，SetNext 队列，Seek、倍速、音量，换清晰度，字幕 | `tools/cast/dlna_sender.py` 确定性测试；真机用 VLC、Windows“播放到设备”、B 站、优酷、爱奇艺、Emby |
| C3 AirPlay 视频推送（**已实现**，见 §11） | mDNS，配对，`/play`、`/rate`、`/scrub`、`/playback-info` | iPhone 投 HLS 与 MP4（**真机 iPhone 还没测**） |
| C4 Miracast 镜像（**已实现**，见 §10；系统层模块见 §12） | P2P 与 WFD 会话（M1–M7），RTP 收包，`cast` 来源，低延迟档 | Windows 11 `Win+K` 与 Android 手机，过延迟关口（**真机发送端还没测**） |
| C5 AirPlay 镜像与音频（**已实现**，见 §15） | FairPlay、镜像、AAC 音频 | iPhone 与 Mac 镜像（**Mac 与 iPhone Air 已连上有画面**，2026-11-02；声音与延迟没有量化） |
| C6 总验收 | AGENTS.md 五项；开着全部服务的 AMBIENT 待机 CPU 与 janky；投屏中时钟秒针不漏跳（方法同 `media_clock_device.py`）；2 小时投屏；解析器畸形输入；两个发送端同时到来、发送端中途消失 | 全部达标 |

**延迟关口（建议值，C0 ② 实测后定稿）**：镜像端到端（从源画面内容时间到设备屏幕）≤ 约 400 ms。达不到，才与原项目直连 `MediaCodec` 的路径做对比；再不行，只有镜像保留直连解码，其余仍走引擎。

## 6. 决策（状态）

| # | 决策 | 状态 |
|---|---|---|
| 1 | 镜像也走我们的引擎，带延迟关口，不行再退回直连解码 | 按建议执行；C0 ② 验证 |
| 2 | Miracast 权限：运行时 `su`（原项目做法），或系统层模块 | **已定（用户，2026-11-01）**：ThinkOS 不只是一个应用，也是一套对安卓的整体改动，系统层代码放进本仓库的 `system/`（ADR-033）。具体做法是 Magisk 模块 `thinkos-cast` 里的 root 守护进程：应用自己不 `su`。这台设备上 `CONFIGURE_WIFI_DISPLAY` 是 signature 级、应用没有平台签名，所以“用模块授予权限”走不通，只能由守护进程代为设置 |
| 3 | AirPlay 镜像与 FairPlay（GPLv3）：自用可接受，分发要么不做要么换实现 | **已定（用户，2026-11-01）**：“开源之后我会以 GPLv3 的许可分发”（ADR-034）。`playfair` 按其 GPLv3 原样纳入（`android/app/src/main/cpp/playfair/`，来源与许可证见该目录的 `ORIGIN.md`），C5 开做 |
| 4 | 公网明文 HTTP：沿用 `media.public_http`，不另设投屏开关 | 按建议执行 |
| 5 | 默认策略：总开关默认关；新发送端首次连接需屏幕确认；投屏到来接管并可打断本地播放 | 按建议执行 |
| 6 | 范围：先 DLNA + AirPlay 视频推送，Miracast 看 C0 | 按建议执行 |

## 7. 风险

| 风险 | 缓解 |
|---|---|
| 驱动或固件不支持 WFD sink / P2P 并存 | C0 ① 最先做；失败则取消 C4，不影响 DLNA |
| 镜像延迟超标 | C0 ② 先测；有直连解码的退路 |
| 移植来的协议代码质量与安全未知（无测试、局域网无认证监听） | 逐文件重写加解析器畸形输入测试；来源地址白名单；默认关 |
| 常开的 SSDP / mDNS 影响待机与时钟 | C6 实测；总开关默认关；服务线程低优先级 |
| GPL 代码进入分发包 | §6 决策 3；`THIRD_PARTY_NOTICES` 逐项标注 |

## 8. C0 记录

### ① Wi-Fi Direct / WFD sink（进行中）

方法：把参考项目的 `cpp/wfdctl.c`（读过全文：只往 wpa_supplicant 控制 socket 发文本命令）编译成 armv7，放在设备的 `/data/local/tmp`，经 `su` 手工发命令。命令都不持久，`SET wifi_display 0` 或开关 Wi-Fi 即撤销。**没有改任何系统文件。**

已确认（2026-11-01 14:40，真机）：

- 驱动已建 `p2p0`；`android.hardware.wifi.direct` 存在；SELinux 为 permissive；`su` 对 adb shell 放行。
- 控制 socket 在 `/data/misc/wifi/sockets/p2p0`（`/data/vendor/wifi/...` 在这个 ROM 上不存在）；`PING → PONG`。
- `SET wifi_display 1`、`WFD_SUBELEM_SET 0 000600111c440032`（primary sink、控制端口 7236、50 Mbps）都返回 OK，`WFD_SUBELEM_GET 0` 读回一致：**这个 wpa_supplicant 带 WFD 支持**。
- `SET device_name`、`P2P_LISTEN` 返回 OK，设备以 `ThinkOS-Cast` 进入监听。

待确认：Windows 与 Android 手机能否在列表里发现它；连接时 GO 协商能否到达设备；P2P 监听与已连接的 Wi-Fi 并存时的表现（监听期间到设备的 ping 抖动 11–110 ms，需与不监听时对比）。

### ② 延迟（已完成；数字已更正）

**更正（2026-11-01）**：这一节原来写的是 1196 ms 与 353 ms，并据此判定“通过”。补测时发现测试源有约 0.5 秒的偏差：ffmpeg 的 `-re` 允许开头 0.5 秒的突发，所以它生成画面的时刻比“第一个字节发出的时刻 + 内容时间”早半秒，旧算法把这半秒当成了“没有延迟”。改为读 ffmpeg 自己逐帧报告的内容时间（`showinfo`），定出“内容时钟读零的时刻”，重测结果：

| 引擎档位 | 内容到屏幕的延迟（更正后） | 旧记录 |
|---|---|---|
| 默认（缓冲 15–30 s 上限，起播 1.5 s，音频输出缓冲默认） | **约 1714 ms**（1690–1726），10 个样本 | 1196 |
| `lowLatency`，经 HTTP 渐进式读取，没有追赶 | **约 870 ms**（855–892），10 个样本 | 353 |
| 镜像来源（Miracast 路径：UDP，低延迟档，加追赶再生，见 §10） | **约 320 ms**（271–379），12 个样本 | — |

所以“低延迟档的缓冲参数就能过 400 ms 关口”**不成立**：单靠它是 870 ms。真正把延迟压下来的是 `MirrorPace`：播放器起播时（解码器还在启动的一两秒里）已经把到达的数据攒在了前面，领先播放头约 1–1.5 秒，按 1 倍速这份领先永远不会消掉；追赶再生让它在约 10 秒内降到 300 ms 左右，之后保持。

**档位内容**（`MediaEngine.lowLatency`，下次 `load` 重建播放器；调试命令 `media_lowlatency`；带 `MirrorSource` 的队列总是用这个档）：`LoadControl` 1–2 s 上限、起播 0 ms、重缓冲 100 ms、不留回看缓冲；音频输出缓冲压到 20–100 ms（音频时钟决定画面节奏）。

**方法**：`tools/cast/latency_source.py` 用 ffmpeg 实时生成 720p30 H.264 基线档（4 Mbps）+ AAC 的 MPEG-TS，画面顶部用 20 个白方块二进制编码“这一帧的内容时间（毫秒）”。`tools/cast/latency_probe.py`（HTTP）与 `tools/tests/cast_miracast_device.py`（Miracast，用 `tools/cast/wfd_source.py` 发 RTP）连续抓设备屏幕并解码方块；延迟 = 抓帧时刻 −（内容时钟读零的时刻 + 内容时间）。抓帧时刻取原始 `screencap` 第一个字节到达的时刻（它先抓帧再写头部）。内容时钟读零的时刻取 ffmpeg 每帧报告的“到达时刻 − 帧时间”的最小值。

**踩到的坑**（以后复测要知道）：

- 用第一个字节的到达时刻当内容时钟的起点会少算 0.5 秒（见上）。
- 一开始用 `screencap -p` 并取耗时窗口的中点，得到的数比原始截图第一个字节的读数大 175 ms：PNG 压缩与 USB 传输都在抓帧之后。
- `adb reverse` 在两条传输同时在线（USB 加无线）时报 `more than one device/emulator`；只剩 USB 时正常，且必须用 `-s`，不认 `ANDROID_SERIAL`。Miracast 的测试不用它（Mac 与设备已在同一网段）。

**这个数字没有包含的东西**（所以真实发送端上要复测）：真实 Miracast 源自己的采集与编码延迟；UDP 丢包后到下一个 IDR 之间的花屏（已实现丢包后请求 IDR，但真实编码器怎么响应没测）；1080p60 与 CBR 码流；Wi-Fi Direct 链路的抖动。测试源是理想的本机编码、2–4 Mbps。


## 9. C2 DLNA：实现与验证（2026-11-01）

**能做什么**：设备在同一网络里作为 DLNA / UPnP 媒体渲染器（`MediaRenderer:1`）出现，控制端可以推送 http / https 的音视频地址、播放、暂停、跳转、调音量、静音、停止，设备把状态（播放中、暂停、位置、时长、错误）如实报告，并支持事件订阅。播放用 OS 的媒体会话（owner `cast:dlna`），画面由系统插件 `cast`（`video` 与 `audio` 两个 `live` 页面，复用 `video` / `controls` / `seekbar`）显示，投屏来到时唤醒屏幕并接管，停止时还回去。

**怎么打开**：系统设置 → 滑到底部 → “用 DLNA 接收投屏”（默认关；说明里写明网络里任何人都能发起）。“投屏时显示的设备名称”默认 `ThinkOS`。

**代码**（`android/.../cast/`）：

| 文件 | 内容 |
|---|---|
| `MiniXml.kt` | 自带的小 XML 读取器：拒绝 DOCTYPE / ENTITY，限制大小、深度、元素数；容忍地址里没转义的 `&` |
| `dlna/Soap.kt`、`Didl.kt` | SOAP 请求与应答、UPnP 错误；DIDL-Lite 元数据（标题、艺术家、封面、类型、MIME、字幕） |
| `dlna/Upnp.kt` | 三个服务的动作表，设备描述与 SCPD 都由它生成，保证与实现一致 |
| `dlna/DlnaRenderer.kt` | AVTransport / RenderingControl / ConnectionManager 的逻辑，只依赖 `CastPlayer` |
| `dlna/HttpServer.kt`、`UpnpDevice.kt`、`EventHub.kt` | 小型 HTTP 服务（有界、逐连接）、路由、GENA 事件订阅 |
| `dlna/Ssdp.kt` | SSDP 搜索应答与通告（含按来源限速） |
| `CastPlayer.kt`、`EngineCastPlayer.kt` | 协议层对引擎的唯一接口，及其对 `MediaEngine` 的实现（在主线程执行） |
| `CastHost.kt` | 生命周期（开关、网络地址变化跟随、多播锁）、来源与地址策略、接管 / 归还屏幕 |

**安全与边界**：默认关；只服务私网来源（调试包另允许环回，仅为 adb 测试）；只收 http / https 地址，`file:`、`content:`、`rtsp:`、带账号密码的一律拒绝（SOAP 402）；调试包以外不允许指向本机的地址；事件订阅的回调地址必须就是发起订阅的那台机器，设备不会替人向第三方发请求；SSDP 每个来源 10 秒内最多应答 8 次；HTTP 同时最多 8 个连接、头 16 KiB、体 256 KiB；解析不信任发送端（XML 限制、畸形请求得到错误而不是崩溃）。拉取媒体本身仍受 `media.public_http` 约束。

**验证**：

- 单元测试 50 个（`cast/`：XML、SOAP、DIDL、渲染器逻辑加假引擎、真实 socket 上的 HTTP 与事件订阅、SSDP、状态映射），全套 328 个通过，`lintDebug` 0 个错误。
- 真机 `tools/tests/cast_dlna_device.py`：**34 项全部通过**。包括：单播搜索得到 6 个目标且都指向同一描述；描述与三个 SCPD；推送带元数据的地址、载入未开始读作 `STOPPED`；`cast.video` 页面接管屏幕；Play、位置推进、时长；Pause；Seek 与越界 711；音量按 0–100 与引擎一致；静音；`file:` / `content:` / `rtsp:` / 带账号密码的地址被拒；未知动作 401；订阅与取消订阅；Stop 后屏幕归还、引擎空闲；Stop 后 Play 重新开始；别人占用媒体会话后读作 `STOPPED`；关掉开关后既不应答搜索也不再监听。
- 空闲开销：开着接收服务，首页 AMBIENT 3 分钟，单核 CPU 平均 3.03%（未开时 3.12%），janky 8 / 575 帧（1.39%，样本短；10 分钟的正式数据在 C6）。

**没有验证的**（所以“能不能被你的手机搜到”要你来试）：

- ~~多播发现在真实网络里的表现~~：**已测（2026-11-01 晚）**。Mac 与设备换到同一网段后，从 Mac 发多播 `M-SEARCH`（`ST: MediaRenderer:1`），设备回了描述地址（`http://<设备>:49152/dlna/description.xml`）。注意到它回了两份相同的应答，原因没查，对控制端无害。
- **真实发送端**：VLC、Windows 的“投放到设备”、B 站 / 优酷 / 爱奇艺 / Emby 都还没试。DLNA 的兼容性问题几乎都出在各家控制端的小差异上（元数据写法、是否订阅事件、动作顺序），一定会有要调的地方。
- **事件通知真的送达控制端**：设备到 Mac 所在网段不通，真机上只验证了订阅被接受；送达在环回上的单元测试里验证过。
- **倍速**：`Play` 的 `Speed` 参数被忽略（引擎还没有倍速接口）。
- **队列与 `Previous`**：只支持一个“下一个”地址（`SetNextAVTransportURI`），`Previous` 返回 701。
- **首次连接的屏幕确认、`tos.cast` 事件与动作、API 8**：C1 里还没做；现在“开关打开”就是唯一的许可。


## 10. C4 Miracast：实现与验证（2026-11-01）

**能做什么**：设备以无线显示器（WFD Sink）的身份出现，安卓手机与 Windows 电脑可以把整块屏幕共享上来；画面与声音进 OS 的媒体会话（owner `cast:miracast`），用 `cast.video` 页面显示，没有控制条（共享屏幕无法暂停、快进），亮度与音量手势保留；离开该页面会结束共享，对方会收到 TEARDOWN。

**分工**（为什么要系统层模块见 §12）：

1. **系统层模块 `thinkos-cast` 的 root 守护进程**让设备在 Wi-Fi Direct 里被发现：设 WFD 信息元素（主 Sink、控制端口 7236）、P2P 设备名、空闲时周期性监听。
2. **Android 自己的 Wi-Fi Direct 栈**做配对、组协商、发 IP；连接时由系统问一次“是否允许”（是否会在 kiosk 上显示并能点，**真机还没验证**）。
3. **应用里的 `MiracastService`** 看到 P2P 接口出现后，在组里找 Source（先看内核 ARP 表与组主地址，再扫 /24），**主动连它的 7236 端口**（WFD 里 Source 是监听方），跑 Sink 一侧的 M1–M7（`WfdSink`），用 `RtpReceiver` 收 RTP（payload 33，每包 7 个 TS 包），排序后交给引擎的 `MirrorSource`。

**引擎侧**：`MediaEntry.mirror` 只有 OS 内部能设（Lua 的 `fromMap` 没有这个字段，`thinkos-mirror` 这个 scheme 在所有插件提供的地址里都被拒绝）。`MirrorSource` 是 384 KB 的有界队列，满了丢最旧的字节（TS 解复用会在下一个包重新同步），所以画面不会越落越远。带它的队列总是用低延迟档，并且由 `MirrorPace` 追赶：播放头前面的领先超过 500 ms 就按 1.06–1.3 倍速播放，降到 330 ms 以下恢复 1 倍（播放器自己留不到约 270 ms 以下，门槛必须在它之上，否则加速永远不结束）。镜像不参与“缓冲 30 秒没有增长就重连”的看门狗，也不显示缓冲转圈：静止的屏幕本来就没有新数据（§15）。

**设备声明的能力**（`WfdSink.Video`）：H.264 CBP + CHP，级别 4.0，CEA 位图 `0x61`（640×480p60、1280×720p30、1280×720p60），不声明 1080p（面板 1280×800 用不上）；音频只声明 `AAC 00000001 00`。**不声明 LPCM**：WFD 的 LPCM 装在 TS 的私有流里，ExoPlayer 的 TS 解复用读不了，宣称了反而会让只会 LPCM 的发送端选它然后没有声音。不做 HDCP、UIBC（反向触控）、H.265、4K。

**丢包**：序号乱序在 25 ms 内排回，超过则放弃那一段并计数；丢包后用 `wfd_idr_request`（M13）请求新的 IDR，至多每 500 ms 一次。

**验证**：

- 单元测试 56 个：RTSP 读写与边界（`RtspTest`）、RTP 解析与重排（`RtpTest`）、Sink 对着脚本化 Source 的整个握手、保活、IDR、各种结束方式与畸形输入（`WfdSinkTest`）、与系统层模块的客户端（`WfdControlTest`）、找 Source 的地址（`P2pPeersTest`）共 44 个，另有 `MirrorSource` 与 `MirrorPace`（`MirrorSourceTest`）12 个。
- 守护进程：主机上对着假的 wpa_supplicant 18 项（`system/test/test_wfdd.py`，连跑 3 次稳定），设备上用同一份 armv7 程序读写真实的 wpa_supplicant（WFD 元素读回一致，Wi-Fi 往返时间在开与不开监听时没有可见差别：平均 22.6 与 19.4 ms，噪声之内）。
- 真机 `tools/tests/cast_miracast_device.py`：**27 项全部通过**。`tools/cast/wfd_source.py` 扮演手机或电脑，调试命令 `miracast_dial` 让设备去连它：握手与设备的声明；引擎里是 `cast:miracast`、live、不可跳转、1280×720、有 AAC 声音；页面接管；延迟约 320 ms；2% 丢包下画面继续并请求了 IDR（7 次，丢了 8 个包，没有超过每秒两次）；乱序下不计丢包；离开页面、对方 TEARDOWN、别人占用会话三种结束方式；没人监听时报“nothing is listening”；对方说 HTTP 而不是 RTSP 时不崩。

**没有验证的**（要你的华为手机、Windows 电脑）：

- **真实 Source 能不能发现并连上设备**（Wi-Fi Direct 发现、组协商、系统的确认框）。守护进程已经能让设备带着 WFD 元素以 “ThinkOS” 出现，但没有任何真实发送端看过它。
- **设备在组里的角色**（组主还是客户端）与 Source 的地址怎么找到：现在是 ARP 表加组主地址加扫 /24，没有读 Source 的 WFD 信息里宣告的控制端口（默认 7236）。
- **P2P 监听与已连接的普通 Wi-Fi 并存**：只测了往返时间，没有测吞吐。
- **真实 Source 的编码行为**：1080p 被我们不声明所以不会来；真实编码器的 GOP、码率、对 `wfd_idr_request` 的响应、PES 里有没有 AUD。
- **SELinux enforcing**：设备是 permissive。模块带了一条 `sepolicy.rule`（日志里观察到 wpa_supplicant 回复守护进程被拒），没有在 enforcing 下验证。

## 11. C3 AirPlay 视频推送：实现与验证（2026-11-01）

**能做什么**：iPhone、iPad、Mac 上提供 AirPlay 的 App（视频 App、浏览器里的视频）把一个 http / https 视频地址推到设备，设备用引擎播放（owner `cast:airplay`，页面 `cast.video`），并把播放状态（`loading` / `playing` / `paused` / `stopped`）通过事件通道回报；发送端可以暂停、跳转、停止。**不做**：音频（Apple Music 那类）、照片、FairPlay 保护的视频（Apple TV+ 等）、`mlhls://`（发送端中转 HLS，YouTube 等用它）。屏幕镜像见 §15。

**怎么打开**：设置 → 投屏 → “用 AirPlay 接收投屏”（默认关）。设备名与 DLNA 共用 `cast.name`。“AirPlay 屏幕镜像”（`cast.airplay_mirror`，默认开，只在上一项开着时有意义）关掉时，设备按本节的样子以纯视频接收端出现（features `0x19`，没有 `_raop._tcp`，`/fp-setup` 回 404）；开着时按 §15 以 Apple TV 的身份出现。

**代码**（`android/.../cast/airplay/`）：

| 文件 | 内容 |
|---|---|
| `Plist.kt` | 属性列表：二进制（`bplist00`）与 XML 的读和写，读侧有大小、对象数、嵌套、总工作量的上限，XML 只认标准的 DOCTYPE |
| `Curve25519.kt` | X25519 与 Ed25519（BigInteger 实现，对着 RFC 7748 / 8032 的向量测过）；安卓 8.1 没有这两样，一次握手也不值得引入密码库 |
| `AirPlayPairing.kt` | 设备身份（Ed25519 密钥，存在 `files/cast/airplay-identity`，仅本应用可读）与匿名配对（`/pair-setup`、`/pair-verify` M1/M2） |
| `AirPlayServer.kt` | 7000 端口的 HTTP 服务：长连接、有界（头 16 KiB、体 1 MiB、最多 8 个连接、空闲 5 分钟断开），路由，`POST /reverse` 升级成 PTTH 事件通道 |
| `AirPlayService.kt` | mDNS 记录（`NsdManager`）、`EngineAirPlayControl`（把请求变成对播放器的操作，并把引擎状态变成事件） |

**通告的内容**（镜像关闭时；开启时见 §15）：服务类型 `_airplay._tcp`，端口 7000，TXT：`deviceid`（由设备 ID 派生的、本地管理的单播“MAC”）、`features=0x19`（视频、视频音量控制、HTTP 直播流；**没有** FairPlay、照片、音频、镜像）、`flags=0x4`、`model=AppleTV3,2`、`srcvers=220.68`。**没有 `pk`**：不承诺 AirPlay 2 的配对；`/pair-setup` 与 `/pair-verify` 仍然实现，以防 iOS 先走它们。

**安全**：只服务私网来源；`/play` 的地址只收 http / https，且经过与 DLNA 相同的地址规则（`media.public_http`、调试包以外不能指向本机、不带账号密码）；日志里不写完整地址（地址里常有令牌）；配对是匿名的，它保护不了什么，访问许可就是那个设置开关。

**验证**：

- 单元测试 55 个：Plist（含畸形输入、自引用、DOCTYPE/实体）、Curve25519（RFC 向量）、配对（测试扮演发送端）、HTTP 服务（路由、三种 `/play` 正文格式、事件通道顺序、各种上限、连接数上限）、`EngineAirPlayControl` 与 mDNS 记录。
- 真机 `tools/tests/cast_airplay_device.py`：**37 项全部通过**。包括：**从 Mac 用 `dns-sd` 真正发现了设备**（通告、解析到 7000 端口、TXT 内容）；`/server-info` 与 `/info`；配对成功与错签名被拒；事件通道；`/play`、`loading` → `playing` 各一次、`playback-info`、位置随时间走、`/rate` 暂停与恢复并收到事件、`/scrub`、`/stop`；起始位置 0.5；影片播完时报 `stopped` 并归还屏幕；`mlhls`、`file`、`content`、`rtsp`、带账号密码、非地址一律 400 并报 `stopped`；畸形请求、超大正文（413）、未知路由（404）、`/fp-setup`（404）、14 个空闲连接；运行时关掉开关后不再监听、不再通告。

**没有验证的**（要你的 iPhone）：

- **AirPlay 视频推送在真实 iPhone 上没有测过**（屏幕镜像在 Mac 与 iPhone 上已走通，见 §15）。测试发送端是照协议笔记写的替身，只能证明设备做了笔记里说的事，不能证明 iOS 会这样走。已知可能出入的地方：各 App 推的是 http 地址还是 `mlhls`；是否会要求 FairPlay；镜像开着时设备的身份不含视频位（§15），要测视频推送先关掉 `cast.airplay_mirror`。设备端的日志把每个请求写成一行（方法、路径、协议、类型、会话号、正文大小），方便对着真机调。
- AirPlay 2 的流程（加密的 HTTP 通道）与多房间；这里只做 AirPlay 1 式的视频推送。
- 第一次连接的屏幕确认：没做，开关打开就是唯一的许可。

## 12. 系统层：Magisk 模块 `thinkos-cast`（ADR-033）

ThinkOS 不只是一个安卓应用，也是一套对安卓的整体改动：需要系统权限的能力，由仓库里的 `system/` 提供。第一个模块 `thinkos-cast`：

- `system/thinkos-cast/src/wfdd.c`：root 守护进程。只懂三个命令（`SINK_ON <端口> <名字>`、`SINK_OFF`、`STATUS`），不是通用的 wpa 代理；名字只收可见字符，不会拼出多余的 wpa 命令。
- 谁可以说话：用 `SO_PEERCRED` 查对端 uid，只接受 root 与 ThinkOS 应用（按 `/data/system/packages.list` 里的包名查，每次连接重新查）。应用一侧也只相信 root 回的答案（抽象 socket 的名字谁都能抢先绑定）。
- 自愈：wpa_supplicant 重启（开关 Wi-Fi）会丢掉 WFD 元素，Sink 打开时每 5 秒检查一次，掉了就补上；应用每 15 秒也核对一次。守护进程退出时撤销 Sink。
- 没有这个模块时，应用里什么都不发生（一行日志），设备照常工作（ADR-015）。

**构建与测试**：`system/build.sh` 出 armv7 的模块包 `system/build/thinkos-cast-v1.0.zip`；`system/build.sh --host` 出本机可跑的版本，`python3 -I system/test/test_wfdd.py` 对着假的 wpa_supplicant 跑 18 项。

**安装是人工的一步**：模块会在 `/data/adb/modules` 里留下开机启动的 root 进程，所以它由你来装（`system/install.sh`，或把 zip 放到设备上用 Magisk 装），装完重启；应用里打开“用 Miracast 接收投屏”才会开始广播。本次开发中为了测试，用一个不持久的方式在 `/data/local/tmp` 里跑了同一份程序（重启后消失）。见 `system/README.md`。

## 13. 用你的手机验收

**华为手机（Miracast）**：模块要先装好（或设备上有一个临时的守护进程在跑），设备上 “用 Miracast 接收投屏” 已打开。在手机上：设置 → 更多连接 → 无线投屏（不同机型叫法略有差别），应当列出设备名（默认 “ThinkOS”，可在设置里改）。点它，设备屏幕上可能出现系统的“是否允许连接”确认框，点允许。几秒后手机屏幕出现在设备上。看什么：列表里有没有设备；确认框有没有出现、能不能点；画面与声音；延迟；退出方式（手机上断开，或在设备上滑开页面）。出问题时抓日志：`adb logcat -s ThinkOS:V ThinkOS-wfdd:V | grep -i -E "miracast|wfdd"`。

**iPhone（AirPlay）**：设备上 “用 AirPlay 接收投屏” 已打开，与 iPhone 在同一个 Wi-Fi。**视频推送**：在一个能 AirPlay 的视频 App（或 Safari 里的视频）里点 AirPlay 图标，应当列出设备名；选它，视频应当在设备上开始播放，手机上可以暂停、拖动、停止。**屏幕镜像**：控制中心 → 屏幕镜像，应当列出设备名；选它，手机屏幕出现在设备上（有声音时有声音）。出问题时抓日志：`adb logcat -s ThinkOS:V | grep -i airplay`，每个请求一行。要退回只接收视频的身份：设置里关掉“AirPlay 屏幕镜像”。

## 14. 总验收（AGENTS.md 五项，2026-11-01）

三个协议同时打开（`cast.enabled`、`cast.airplay`（含 `cast.airplay_mirror`）、`cast.miracast`），最终的调试包。**下表的待机数据是 AirPlay 镜像加入之后重测的（23:30），第一次的数据在表后**：

| 项 | 结果 |
|---|---|
| `./gradlew assembleDebug` | 通过 |
| `adb install -r` | 成功（Device Owner 保留） |
| 冷启动 | 到首页被选中 **1.91–1.94 秒**（三次；加了 FairPlay 原生库与 `_raop._tcp` 之后）。不是“断网”条件下测的：断网会让投屏服务根本不启动，最坏情况是它们都起来的时候 |
| 待机 10 分钟（`hold_attention AMBIENT`，三个协议都开、镜像开） | 渲染 1952 帧，**janky 34（1.74%）**，99 分位 20 ms，漏 vsync 0；CPU 平均 **3.06%**（最大 6.26%，单核）；进程没有重启，没有 AndroidRuntime 错误。对照（镜像加入之前，同样的条件）：2.98%、janky 24（1.22%）；只开 DLNA 是 3.03%、1.39%；三个协议都不开是 3.12%：**监听服务都开着对待机没有可见代价**，janky 的差别在测量的噪声之内（一两百帧里的 10 帧） |
| 没有新的 arm64-only 依赖 | 没有新增任何第三方库（曲线算法、属性列表、TS 封装自己写）；新增的原生库 `libthinkos_playfair.so` 只有 armeabi-v7a（`playfair` 的 C 源码，GPLv3）；系统层守护进程是 armv7 |

附带：最终包上重跑 `cast_miracast_device`（27 项）、`cast_airplay_device`（37 项，镜像关闭时的纯视频身份）、`cast_dlna_device`（34 项）、`cast_airplay_mirror_device`（45 项）全部通过（这四项是在 2026-11-02 的路由修复之后的包上重跑的；之后的“静止屏幕”改动 §15 没有重跑它们）；镜像加入之前通过的 `video_gestures_device`（24）、`video_edges_device`（16）、`media_ui_device`（16）、`player_device`（17）没有重跑（之后只改过镜像分支，转圈与停滞看门狗对镜像不再起作用，它们覆盖的普通视频路径没有变，所以只是“未重跑”，不是“重跑过”）；单元测试 497 个全部通过，`lintDebug` 0 个错误（154 个警告）（后两项是 2026-11-02 最终代码上的结果）；`system/test/run.sh` 18 项（连跑三次，每次重新编译）；Python 39 项；字符串表 11 × 154 一致。FairPlay 原生库另外在设备上对 30000 组随机输入跑过（`fairplay_fuzz`），没有崩溃。

一次不干净的测量也记在这里：第一轮 10 分钟中间屏幕被唤醒过（眠屏 → 被碰或有人经过 → 唤醒），CPU 平均 15%、janky 1.55%，那一轮混了 AWAKE 与 AMBIENT，不能与上表比较，所以又在固定 AMBIENT 下重测了一次。

**没有做的验收**：华为手机的 Miracast、iPhone 的 AirPlay 视频推送、Windows 的 `Win+K`、各视频 App 的 DLNA。AirPlay 屏幕镜像已在 Mac 与 iPhone Air 上手动试过（§15）。见 §13。

## 15. C5 AirPlay 屏幕镜像与声音：实现与验证（2026-11-01）

**能做什么**：iPhone、iPad、Mac 把整个屏幕和声音共享上来，在 `cast.video` 页面上以实时的“镜像”显示（owner `cast:airplay`，`mirror` 为真：没有控制条、不能暂停或跳转，亮度与音量手势保留；离开页面结束共享，设备断开发送端的控制连接，发送端就停止镜像）。画面与声音和 Miracast 走同一条路：重新封装成 MPEG-TS，进引擎的 `MirrorSource`，低延迟档加追赶再生。

**FairPlay**：镜像要求接收端用 FairPlay 握手拿到解密密钥。`playfair`（逆向苹果得来，GPLv3）按原样放在 `android/app/src/main/cpp/playfair/`，来自 RPiPlay 的固定提交 `64d0341e`，同目录的 `ORIGIN.md` 写明来源与许可证；`fairplay_jni.c` 是我们自己的胶水（握手两步的固定回答表也来自 RPiPlay）。项目以 GPLv3 分发（ADR-034），所以没有构建开关。

**协议流程**（`android/.../cast/airplay/`）：`GET /info`（Apple TV 的身份，1280×720 的显示器）→ `/pair-setup`、`/pair-verify` → `/fp-setup` 两步 → RTSP `SETUP`（带 FairPlay 包起来的密钥 `ekey`、`eiv`、发送端的 `timingPort`）→ `SETUP`（视频流 type 110、音频流 type 96）→ `RECORD` → 数据。

| 部分 | 内容 |
|---|---|
| `AirPlayServer` | 同一个 7000 端口既讲 HTTP 也讲 RTSP：回应的协议名跟请求行一致，请求按动词分发——真实发送端的 `GET /info`、`POST /pair-setup`、`POST /fp-setup`、`POST /feedback` 请求行里写的是 `RTSP/1.0`，仍按路由表回答；只有 `OPTIONS`、`SETUP`、`RECORD`、`TEARDOWN`、`GET/SET_PARAMETER`、`FLUSH`、`PAUSE`、`ANNOUNCE` 进 RTSP 处理器（没有镜像一侧时回 501）；新增 `/fp-setup`、`SETUP`、`RECORD`、`TEARDOWN`、`GET/SET_PARAMETER`（音量）、`/feedback`；镜像开着时绑定在通配地址上（发送端可能走 IPv6 链路本地） |
| `AirPlayMirror` / `MirrorSession` | 一个会话：密钥、事件端口、对发送端定时端口的 NTP 请求（发送端要求接收端先问时间，否则不往下走）、视频流、音频流、重新封装 |
| `VideoStream` | 发送端连我们的 TCP 端口，每包 128 字节头（载荷大小、类型、NTP 时间）加载荷；类型 1 是 `avcC`，类型 0 是 AES-CTR 加密的 H.264（4 字节长度前缀）；整条连接共用一条密钥流，每一包都要解（哪怕不用） |
| `AudioStream` | RTP over UDP（双栈），每包 AES-CBC（每包同一个 IV，尾部不足 16 字节的部分不加密），发送端每包发两三遍（按序号去重）；AAC-LC 直接加 ADTS 头；AAC-ELD（iPhone 镜像用的）先由 `AacTranscoder` 用系统的编解码器解成 PCM 再编成 AAC-LC |
| `TsMuxer` | H.264 与 ADTS 封成 TS；每个关键帧前补 SPS/PPS 与 AUD；PAT/PMT 与 PCR 定期重发。用 ffprobe 与 ffmpeg 验证过（视频 640×360、音频 44100/2，解码无报错，帧数相符，起始时间等于给的 PTS） |
| `MirrorTimeline` | 画面与声音都安静超过 0.15 秒时，把这段空白从时间戳里去掉（见“屏幕静止”）；声音在放的时候不动 |
| `MirrorCrypto` | 视频密钥：SHA-512(“AirPlayStreamKey<id>” ‖ SHA-512(aesKey ‖ ECDH 秘密)[:16])[:16]，IV 类似；音频密钥：SHA-512(aesKey ‖ ECDH 秘密)[:16]，IV 取 `eiv` |

**为什么 ELD 要转码**：ExoPlayer 的 TS 与 LATM 读取器按“每帧 1024 个采样”给音频打时间，ELD 每帧 480 个，时间会走得不对。所以先解成 PCM 再编成 AAC-LC。真机上用平台自己的 ELD 编码器做了一段测试流，经转码器出来 85 帧 LC（86 帧进）。**iPhone 实际发的 ELD 配置（`F8E85000`，480 采样、无 SBR）没有真机验证**；这个常数来自参考实现，它在电视上用系统解码器放通过。

**时间**：画面的时间由发送端每帧头里的 NTP 时间决定间距，落在“第一帧到达时我们的钟”上；声音由 RTP 时间戳决定间距，落在“第一包到达时我们的钟”上。两路各自对齐到到达时刻，没有用 RTP 与 NTP 的同步包做精确音画同步。画面丢了一段后（发送端时间跳变超过 2 秒）重新对齐。屏幕静止时的处理见下面的“屏幕静止”。

**一个会话里流会来来去去**：iPhone 在镜像中播放全屏视频时，会拆掉镜像的流、用另一条连接 `/play` 视频、播完后在同一个会话上重新 `SETUP` 镜像流。设备保留密钥，视频被拆掉时丢弃旧的传输流（不碰屏幕），新的第一帧到来时重新建传输流并显示；声音晚到或提前消失时传输流重新开始（PMT 不能承诺没有的声音），并把最后一个关键帧起的画面重放进去。

**设置**：`cast.airplay_mirror`（默认开，在 `cast.airplay` 下面）。运行时关掉：AirPlay 立刻按纯视频接收端重新通告（features `0x19`），`_raop._tcp` 撤销，`/fp-setup` 回 404。

**验证**：

- 单元测试 98 个（AirPlay 全部）：包括对着 ffmpeg 的整条路径（测试扮演发送端：握手后用同样的密钥算术加密一路 H.264 与一路 AAC-LC，发两遍；出来的传输流 ffprobe 认得、ffmpeg 解码无报错、帧数相符、音频每帧一次）、流被拆走又回来时重新显示、引擎状态变化何时结束镜像、RTSP 路由与 `/fp-setup`、`Plist`、密钥派生、`TsMuxer`、`MirrorTimeline`、请求行写 `RTSP/1.0` 的 `GET /info` 等。
- 真机 `tools/tests/cast_airplay_mirror_device.py`：**45 项全部通过**。`tools/cast/airplay_mirror_sender.py` 扮演 iPhone：mDNS 记录（从 Mac 用 `dns-sd` 看到 `_airplay._tcp` 与 `_raop._tcp`）、`/info`（含带 `qualifier` 的问法：回答的 `txtAirPlay` 与 `dns-sd` 看到的记录逐项相同，`pk` 与 `/pair-setup` 给的公钥一致）、配对、`/fp-setup` 两步（设备本机的 FairPlay 库的回答与预期逐字节相符）、`SETUP` 两步、`RECORD`、设备对发送端定时端口的轮询、画面与声音、延迟 **约 310 ms**（258–461，10 个样本，追赶之后）、音量（−15 dB → 0.5）、`/feedback`、播放全屏视频期间会话保留并在流重新建立后镜像回来、离开页面时设备断开发送端、发送端消失、第二个发送端接管、TEARDOWN、畸形输入（流先于密钥、SETUP 不是属性列表、`/fp-setup` 大小不对、两 GB 的画面包）、运行时关闭镜像。
- 因为只有苹果的代码能造出 FairPlay 包起来的密钥，调试包有一条 `airplay_testkey` 命令，让每次解包都回同一个密钥，测试发送端用它加密。真实的 `playfair_decrypt` 另外在设备上用 30000 组随机输入跑过（`fairplay_fuzz`，没有崩溃），并被真实的 Mac 与 iPhone Air 会话用过：解出来的密钥能解开画面，画面显示出来了。

**真机上走通的**（2026-11-02）：macOS 27.2 与 iPhone Air（最新 iOS）的屏幕镜像都连上了并有画面。两者的请求行都写 `RTSP/1.0`，来源地址是 IPv6（`fe80::…`、`fd05::…`）；握手是 `/info`（带 `qualifier`）、`/pair-setup`、`/pair-verify` 两步、`/fp-setup` 两步、`SETUP` 两步、`RECORD`；镜像不要求 `fp-setup2`。Mac 上用户反映延迟偏高（没有测量）；两台的声音没有核对。

**真机上发现并修好的问题**：

- **“Unable to connect”（我们自己的路由 bug）。** iPhone Air 第一次连，提示 “Unable to connect”，设备日志只有 `GET /info` → `POST /pair-setup`，之后什么也没有。苹果的发送端把这些请求的请求行写成 `GET /info RTSP/1.0`、`POST /pair-setup RTSP/1.0`；服务器看到 `RTSP/1.0` 就把请求整个交给只认 SETUP、RECORD 等 RTSP 动词的处理器，其余一律回 **501**，发送端于是放弃（发送端日志里是 `Response received … Status 501`、`apsession_fetchInfo: got error 200501 Not Implemented`）。脚本发送端一直用 `HTTP/1.1`，所以 45 项设备检查全过，没有测到。修法：路由只按动词分发；脚本发送端的控制连接也改成全程 `RTSP/1.0`。
  - 怎么查出来的：用这台 Mac 当发送端（控制中心 → 屏幕镜像 → 设备名），同时在 Mac 上 `/usr/bin/log stream --predicate 'process IN {"airplayd","AirPlayXPCHelper","AirPlayUIAgent"}'`（zsh 里 `log` 是内置命令，要写全路径），发送端自己的日志会写明它为什么放弃。**排查发送端问题先看这份日志**，比猜 mDNS 字段有效。
- **镜像身份。** 发现路由问题之前，我按“iOS 不认我们的 mDNS 身份”的假设把身份换成了 UxPlay 的一组（`features=0x5A7FFEE6,0x0`、`AppleTV3,2`、`pk`、`statusFlags=68`，`/info` 的 `qualifier` 回 TXT 原字节）。这个假设是错的，真正的原因是上面的 501；但换后的身份在 Mac 与 iPhone 上都连得上，之前的一组（`0x5A7FFFF7,0x1E`、`AppleTV5,3`）没有再测。副作用：这组 features 不含视频与 HLS 位，镜像开着时设备不向发送端提供“推视频地址”的入口（要它就关 `cast.airplay_mirror`）；让两者共存没有试过。
- **屏幕静止（卡住、一直转圈、长时间冻结）。** 手机的屏幕不变时不发画面（不放声音时也不发声音），播放器的时钟随最后一个样本停住；下一个画面的时间戳却是真实经过的时间，播放器要把这段空白走完才轮到它。一次真机会话里，静止约 22 秒后新画面领先播放头 25 秒（日志 `buffering at 65753 ms, buffer ahead 25062 ms`），画面多停了二十几秒；另一次，停滞超过 30 秒时引擎的“缓冲没有增长就重连”看门狗把播放器重置了 6 次，每次丢掉视频格式（`videoWidth` 为 0）也没有恢复，一直到发送端结束，约 4 分钟，位置钉在 95379 ms（用 `media_state` 调试命令读到的）。处理有三部分：① `MirrorTimeline` 在画面与声音都静了 150 ms 以上时把空白从时间戳里去掉，接着的样本排在上一个之后 40 ms，之后的样本保持发送端给的间距（有声音在放时不动，音画关系不变；超过 1 秒的空白在日志里记一行 `the sender was quiet for N ms`）；② 引擎对镜像不跑停滞看门狗（代价：发送端悄悄断线时，要等 AirPlay 连接的空闲超时，按代码约 5 分钟，设备才收回画面，没有测）；③ `video` 组件对镜像不显示缓冲转圈。同时修了追赶再生：它的“已追上”门槛原来是 150 ms，而播放器自己留不到约 270 ms 以下（渲染器读在播放头前面，再少就断粮并缓冲），门槛够不到，加速永远不结束，播放器每秒几十次在“播放”与“缓冲”之间来回切（一次会话里 316 次）；现在超过 500 ms 才开始追，330 ms 以下恢复。**用户认可了这一处改动（2026-11-02）；没有量化的前后对比，也没有留下用 iPhone 试它的日志。**

**没有验证的**：

- **AirPlay 视频推送在真实 iPhone 上**（§11）。
- **“静止屏幕”修复的设备测试** `tools/tests/cast_airplay_still_device.py`（发送端的 `still` 开关：屏幕静止 40 秒再恢复，检查不重置播放器、不留领先量）**还没有在设备上跑过**：写好的时候 Mac 与设备不在同一个子网，连不上设备；覆盖到的只有单元测试（`MirrorTimelineTest`、追赶阈值）。
- **追赶阈值改成 500 / 330 ms 之后**，脚本发送端的延迟数字（约 310 ms，§8、§15）没有重测。
- **AAC-ELD 的实际配置**（见上；iPhone 的声音没有核对）、**视频 TCP 与音频 UDP 实际走哪个协议族**（控制连接走 IPv6 已验证）、**1080p**（我们宣告 1280×720，发送端是否按它编码没验证）。
- **音频只支持 AAC-LC 与 AAC-ELD**。ALAC（macOS 的系统声音，`ct=2`）和 Apple Music 的缓冲音频（type 103，参考实现里也没有放通）没有做，设备在 SETUP 的回答里省略这些流。
- 画面与声音没有用 RTP 与 NTP 的同步包精确对齐（见上）。
- 第一次连接的屏幕确认：没做，开关打开就是唯一的许可。

