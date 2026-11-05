# 27 · 流媒体播放：实现、验收与已知限制

> 2026-11-01。ADR-030，API 7。目标：本地与网络音视频的完整播放（常见容器与编码、HLS / DASH / RTSP / 渐进式 HTTP），并在真机上完整验收。
> 标记：**已实测** = 在 Lenovo ThinkSmart View（HUA0BWUT，Android 8.1，armeabi-v7a）上跑过，命令和结果文件在 §9；**未验证**集中在 §8，没有出现在那里的都是已实测的。
> 先读：`AGENTS.md` → `08-decisions.md` ADR-022、ADR-030 → `06-plugins.md` §6.10 → 本文。本文取代 `22-player-plan.md` 中已实现的部分，没实现的部分仍以 22 为准（§7）。

## 1. 结论

- **已完成**：引擎接线（`RuntimeHost` → `PluginRuntime` → `tos.media.*`，存储权限静默授予）；HLS / DASH / RTSP / 渐进式 HTTP / 本地文件可在一个队列里混排；FFmpeg 音频扩展（AC-3、E-AC-3、DTS、ALAC）；请求头、旁路字幕、音轨与字幕切换；自动退避重连、直播恢复、卡死看门狗；API 7 组件 `video`（影院视图）、`controls`、`seekbar`；系统插件 `player`（本地音乐 / 视频、串流地址、M3U 播放列表、最近播放与续播）。
- **真机结果**：45 个样片，39 个能播（含 AC-3 / E-AC-3 / DTS / ALAC / MPEG-2 PS 等平台没有解码器的格式），6 个给出明确错误（WMV / WMA、MPEG-1 PS、裸 DTS、AV1、10-bit HEVC），0 个超时；拖动检查：36 个通过，3 个不可拖动或太短，0 个失败。
- **性能**：1080p60 H.264 全程硬解，全部进程合计 1.5–1.6 个核心（共 8 个），引擎没有丢过一帧；见 §5.7。
- **验收**（AGENTS.md 的五项，§5.9）：构建、安装、断网冷启动（首屏 0.85 秒，首页卡片齐全 2.1 秒）、待机 10 分钟（janky 0.91%，单核 3.1%）、只含 armv7 的原生库，全部达标；另有 160 次换片和 2 小时直播的稳定性测试（§5.8），内存曲线平，没有错误和冻结。，已知限制见 §8 与 `10-known-issues.md` §11。

## 2. 支持矩阵（已实测）

| 样片 | 结果 | 说明 |
|---|---|---|
| `video-h264-480p30.mp4` | 能播 | avc / mp4a-latm · 硬解 avc，拖动正常 |
| `video-h264-720p30.mp4` | 能播 | avc / mp4a-latm · 硬解 avc，拖动正常 |
| `video-h264-1080p30-high.mp4` | 能播 | avc / mp4a-latm · 硬解 avc，拖动正常 |
| `video-h264-1080p60.mp4` | 能播 | avc / mp4a-latm · 硬解 avc，拖动正常 |
| `video-hevc-1080p30-main.mp4` | 能播 | hevc / mp4a-latm · 硬解 hevc，拖动正常 |
| `video-hevc-1080p30-main10.mp4` | 报错（事先未知） | 明确报错：无法解码 video/hevc |
| `video-vp8-720p30.webm` | 能播 | on2.vp8 / opus · 硬解 vp8，拖动正常 |
| `video-vp9-1080p30.webm` | 能播 | on2.vp9 / opus · 硬解 vp9，拖动正常 |
| `video-av1-720p30.mp4` | 报错 | 明确报错：格式不支持 video/av01 |
| `video-mpeg2-576p25-mp2.ts` | 能播（事先未知） | mpeg2 / mpeg-L2 · 硬解 mpeg2，拖动正常 |
| `video-mpeg2-576p25-ac3.mpg` | 能播 | mpeg2 / ac3 · 硬解 mpeg2，拖动正常 |
| `video-mpeg1ps-vcd.mpg` | 报错 | 明确报错：格式不支持 |
| `video-mpeg4asp-480p30-mp3.avi` | 能播（事先未知） | mp4v-es / mpeg · 硬解 mpeg4，拖动正常 |
| `video-h264-480p30-aac.flv` | 能播 | avc / mp4a-latm · 硬解 avc |
| `video-wmv2-480p30.wmv` | 报错 | 明确报错：格式不支持 |
| `video-h264-480p30.mov` | 能播 | avc / mp4a-latm · 硬解 avc，拖动正常 |
| `video-h263-cif-aac.3gp` | 能播 | 3gpp / mp4a-latm · 硬解 h263，拖动正常 |
| `video-h264-480p30-ac3.mkv` | 能播 | avc / ac3 · 硬解 avc，拖动正常 |
| `video-h264-480p30-eac3.mkv` | 能播 | avc / eac3 · 硬解 avc，拖动正常 |
| `video-h264-480p30-dts.mkv` | 能播 | avc / dts · 硬解 avc，拖动正常 |
| `video-h264-480p30-alac.mp4` | 能播 | avc / alac · 硬解 avc，拖动正常 |
| `sub-embedded-srt.mkv` | 能播 | avc / mp4a-latm · 硬解 avc，拖动正常 |
| `sub-embedded-movtext.mp4` | 能播 | avc / mp4a-latm · 硬解 avc，拖动正常 |
| `video-2audio-embedded-sub.mkv` | 能播 | avc / mp4a-latm · 硬解 avc，拖动正常 |
| `sub-base.mp4` | 能播 | avc / mp4a-latm · 硬解 avc，拖动正常 |
| `video-h264-portrait-rot90.mp4` | 能播 | avc / mp4a-latm · 硬解 avc，拖动正常 |
| `video-h264-aspect-2.35.mp4` | 能播 | avc / mp4a-latm · 硬解 avc，拖动正常 |
| `audio-mp3-cbr192.mp3` | 能播 | mpeg · 软解 mp3.decoder，拖动正常 |
| `audio-mp3-vbr.mp3` | 能播 | mpeg · 软解 mp3.decoder，拖动正常 |
| `audio-aac-lc.m4a` | 能播 | mp4a-latm · 软解 aac.decoder，拖动正常 |
| `audio-aac-adts.aac` | 能播 | mp4a-latm · 软解 aac.decoder，拖动正常 |
| `audio-flac-16bit-44k.flac` | 能播 | flac · 软解 flac.decoder，拖动正常 |
| `audio-flac-24bit-48k.flac` | 能播 | flac · 软解 flac.decoder，拖动正常 |
| `audio-vorbis.ogg` | 能播 | vorbis · 软解 vorbis.decoder，拖动正常 |
| `audio-opus.opus` | 能播 | opus · 软解 opus.decoder，拖动正常 |
| `audio-opus.ogg` | 能播 | opus · 软解 opus.decoder，拖动正常 |
| `audio-wav-pcm16.wav` | 能播 | raw，拖动正常 |
| `audio-ac3.ac3` | 能播 | ac3 · FFmpeg ac3 |
| `audio-eac3.eac3` | 能播 | eac3 · FFmpeg eac3 |
| `audio-dts.dts` | 报错 | 明确报错：格式不支持 |
| `audio-dts.mka` | 能播 | dts · FFmpeg dca，拖动正常 |
| `audio-alac.m4a` | 能播 | alac · FFmpeg alac，拖动正常 |
| `audio-wma.wma` | 报错 | 明确报错：格式不支持 |
| `audio-mp3-id3v2-cover.mp3` | 能播 | mpeg · 软解 mp3.decoder，拖动正常 |
| `audio-flac-comments-cover.flac` | 能播 | flac · 软解 flac.decoder，拖动正常 |

字幕：SubRip / WebVTT 旁路字幕（本地与 HTTP）、MKV 内嵌 SubRip、MP4 内嵌 mov_text、HLS / DASH 自带字幕轨交给 Media3；ASS / SSA 只保留基础样式，不引入 libass。画面按 `fit` 缩放，竖屏视频（旋转 90°）和 2.35:1 都显示正确。

**网络与协议**（`tools/tests/media_stream_device.py`，§5.3）：

| 能力 | 结果 |
|---|---|
| 渐进式 HTTP | Range 起播、`start` 偏移、限速链路先缓冲再播、连接被中途切断后自动续传 |
| 请求头 | `Authorization`、`Referer`、`User-Agent` 到达源站；缺鉴权是一次干净的 401，不重试 |
| 重定向 | 3 次内跟随；4 次明确报错，不当作临时故障反复重试 |
| 错误分类 | 404 / 403 / 401 → `http`（带状态码）；公网明文 → `policy`；非媒体文件 → `format`；本地文件不存在 → `notfound` |
| 临时故障 | 503 按 1、2、4、8、15、15 秒退避重试六次后报 `http 503`；服务端重启后自动恢复播放 |
| HLS | VOD（TS 与 fMP4 / CMAF 分片）、AES-128、主列表自适应（选中的画面不超过 1088 行）、直播、掉出直播窗口后回到直播点 |
| DASH | VOD、直播 |
| RTSP | RTP over TCP 交织、URL 里的账号密码、错误密码是最终的 401、服务端重启后重连；公网地址被拒；密码不进日志 |
| HTTPS | 通过：渐进式 MP4、HLS 主列表（Apple bipbop）、DASH（Shaka 示例，VP9 硬解）各一，Android 8.1 的 TLS 与公共 CDN 握手正常；Mux 测试流所在主机从这个局域网连不上，用例自动跳过（见 §6） |

## 3. 实现

### 3.1 引擎（`media/MediaEngine.kt`）

- 进程级会话，主线程使用；ExoPlayer 在自己的线程里解码和加载。`MediaSource` 由自己的工厂按队列项创建：`rtsp:` 走 `RtspMediaSource`，`type = progressive` 走 `ProgressiveMediaSource`，其余走 `DefaultMediaSourceFactory`（按后缀或 MIME 选 HLS / DASH / 渐进式，并合并旁路字幕）；同一来源和请求头的项共用工厂。
- 渲染器：平台硬解优先，FFmpeg 扩展排在后面只补平台没有的音频格式；打开解码器回退。轨道选择限制在 1920×1088。
- `LoadControl`：缓冲 ≤30 秒、≤48 MB（系统设置），起播阈值 1.5 秒、卡顿后 3 秒。缓冲参数变了就在下一次 `load` 重建播放器。
- 音频属性按 `kind` 区分音乐与影片；拔耳机停播、音频焦点沿用。
- 日志：播放中途的卡顿（`[media] buffering at … buffer ahead … ms`）、丢帧批次（`[media] dropped N frames in … ms`）、重试与卡死重连都有一行 `ThinkOS` 日志；ExoPlayer 一次攒够 50 帧才报告丢帧，所以 `elapsed` 是攒这批帧花的时间，不是丢帧发生的时间窗口。
- 快照在每秒和每个播放器事件时发布；进度条读 `positionNow()`，不依赖它。快照里包含解码器名与丢帧数，用来区分硬解与软解。

### 3.2 网络与安全

- 明文 HTTP / RTSP 只到私网、环回、链路本地地址（DNS 结果与实际连接地址都校验，沿用 ADR-022）；`media.public_http` 默认关，只放宽明文地址，HTTPS→HTTP 重定向仍拒绝。
- 请求头：每个条目最多 8 个，禁止 `Host`、`Range`、`Content-Length`、`Connection`、`Transfer-Encoding`、`Accept-Encoding`、`If-*`、`Content-Type` 等；除 `User-Agent`、`Referer`、`Accept`、`Accept-Language`、`Origin` 外的头（`Authorization`、`Cookie`、`X-Api-Key`……）通过 `ResolvingDataSource` 只发给条目 URL 的同源地址，分片、密钥、字幕在别的主机上也不会带过去，跨源重定向时 `MediaHttpClient` 也只保留框架头和这几个无害的头。
- 队列：ExoPlayer 在 `prepare()` 时同时准备队列里的所有项，HLS / DASH 项会立刻去取清单；所以一个队列最多 16 个 HLS / DASH 项（`MediaQueue.MAX_ADAPTIVE`），player 点播频道时一次只放一个。
- 错误文本、日志、事件里出现的 URL 都过一遍 `MediaEntry.redact`，`user:pass@` 不外泄；player 插件的标题与副标题用去掉账号密码的主机名。
- RTSP 自己开 socket，`MediaHttpClient` 管不到：`PrivateSocketFactory` 在每次连接前校验并固定地址，并强制 RTP over TCP，让 RTP 数据不会绕过校验。URL 里的 `user:pass@` 只在 URL 里，不进状态、事件和日志（日志里的错误文本还会过一遍脱敏）。
- 插件只能通过 `tos.media.*` 控制；视图里的 `video` / `controls` / `seekbar` 需要 `media` 权限，安装器和打包器都检查。

### 3.3 恢复

| 情形 | 处理 |
|---|---|
| 超时、连接失败、HTTP 408 / 429 / 5xx、RTSP 5xx | 退避 1 / 2 / 4 / 8 / 15 / 15 秒，期间 `status = reconnecting`；网络回来（`ConnectivityManager` 回调）时立即重试；之后给出 `error` |
| 401 / 403 / 404、策略拒绝、重定向循环、格式或解码错误 | 不重试，直接 `error` |
| 直播被对端断开 | 同样退避重连 |
| 掉出直播窗口 | 回到直播点，最多 5 次 |
| 缓冲 30 秒没有增长（对端静默） | 重连；仍不行报 `STALLED`。**镜像除外**：静止的屏幕本来就没有新数据，由发送端结束会话（`docs/28-casting.md` §15） |
| 画面编码不支持 | `error`（`format`，带编码名）；只是音频不支持而画面可播 → 继续播放，`warning = audio-unsupported:<mime>` |

### 3.4 影院视图与手势

- `video`：TextureView + 描边字幕 + 缓冲转圈（镜像不显示）/ 错误卡片 + 触摸时浮出的标题、进度条、上一首 / ±10 秒 / 播放暂停 / 下一首、音轨与字幕按钮；无操作 15 秒淡出（`attention.engaged_idle_s`），字幕随之让位。
- `seekbar`：读 `positionNow()` 每 200 毫秒重绘；点按跳转，拖动时显示目标时间，直播显示“直播”或“落后多久”。
- 手势：进度条在 `ACTION_DOWN` 请求父视图不要拦截，`ThinkStage` 认出这是横向可拖动的组件后解除长按计时；页面翻页、长按设置、两指维护手势仍归 OS。
- **画面上的触摸手势**（2026-11-01，规则在 `ui/VideoGestures.kt`，不依赖 Android，有 JVM 测试）：
  - **单击画面**：控件显示着就收起，没有就唤出（之前只能等 15 秒自动淡出）。手一按下注意力就升到 ENGAGED，所以“按下前控件是否在显示”要在注意力上升的那一刻记下，单击要等不到双击的窗口（系统双击超时，约 300 ms）才确认；报错、播完、暂停时控件一直在，收不起来。
  - **双击**：中间一半暂停 / 继续；左边四分之一快退 10 秒，右边四分之一快进 10 秒（直播里快进回到直播点）；连续双击的秒数累加显示（“+20 s”）。两次点击要在双击超时内、位置相距不超过系统的双击容差，否则是两次单击。
  - **竖向拖动**：左半屏调**亮度**，右半屏调**音量**，手指移动屏幕高度的 70% 走完整个范围，松手后提示条再停留一会儿；不翻页、不改变体。亮度改的就是设置里的 `display.brightness_max`（所以设置页显示同一个数、退出视频后依然生效，下限取设置声明的 20%，步长 5%）；音量改的是系统媒体音量（和音量键同一个）。
  - 页面翻页（横滑）、长按设置、进度条拖动照旧归 OS；单击、双击、竖拖只在有视频时生效，且需要 `media` 权限。
  - 投屏里 DLNA 的音量滑块调的是引擎自己的增益（0–100），和上面的系统音量是两个量，最终音量是两者之积；两者统一留作后续（见 `10-known-issues.md`）。
- 注意力：视频播放时 `SensorController` 把注意力至少抬到 AWAKE（`media.keep_awake_video`），暂停后恢复正常变暗；点一下仍可升到 ENGAGED。
- 页面：`live` 页面不进横向翻页序列，只能被接管进入，返回（最左缘右滑或 Back 键，ADR-040；原为左右滑）回到接管前的页面；插件在用户点按自己页面的 5 秒内 `tos.page.focus` 不受“30 秒本地操作优先”限制，但 ASLEEP 与设置页仍然拦截。

### 3.5 `player` 插件

- 页面：`library`（顶部四个**标签**：音乐 / 视频 / 最近 / 网络流；正在播放的那一行高亮；底部是迷你播放条）、`audio`（OS 的音乐播放器视图 `nowplaying`，变体“封面”和“队列”，见 `30-player-ui.md`）、`video`（影院视图）。后两个是 `live` 页面。
- 设置：音乐目录（默认 `/sdcard/Music`）、视频目录（`/sdcard/Movies`）、续播、串流地址、播放列表地址。串流地址一保存就开始播放，并记入“已保存”；播放列表地址的频道出现在“网络流”，缓存在 `tos.store`。
- 播放列表：`m3u.lua` 解析 `#EXTINF`（含属性里带逗号的 `group-title`）、`#EXTVLCOPT:http-user-agent` / `http-referrer`、`#EXTGRP`、相对地址；含 `#EXT-X-` 的是 HLS 流而不是频道列表；带空格或标记的行（HTML 错误页）不算地址；一次最多 300 个频道，解析每 400 行让出一次 CPU（插件一个切片只有 50 毫秒）。`stream_url` 填的如果是 `.m3u` 列表，会当作频道列表采纳而不是去播它。
- 首页卡片 `player.now`（首页右栏堆叠的最后一张）：卡片样式的 `nowplaying`（封面、曲名、艺术家、进度线、上一首 / 播放暂停 / 下一首），没有播放时写“没有在播放”，点卡片按钮以外的地方进入资料库；播放中相关度 0.9，其它 0。卡片跟随全局 `media` 状态（2 秒一次，值没变不重绘），因为事件只发给会话所有者，别的插件（语音指令）开的会话也要显示。
- 历史：最近 30 条，每 30 秒记一次位置（直播与已看完的不记），再次点开从上次位置续播；离开视频页会暂停（没人看的电影不占解码器）。
- 语言：11 种语言，错误文字与 OS 自己的措辞一致。

### 3.6 FFmpeg 与许可证

`org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1`（含 armv7 `libffmpegJNI.so`，GPL-3.0）。它比 Media3（1.9.2）低一个小版本，真机实测 AC-3、E-AC-3、DTS（MKA / MKV）、ALAC 都能解。**已定（ADR-043）**：完整版 APK 带着它，整体按 GPLv3 发布，发布时要一并提供它的源码（`jellyfin/jellyfin-androidx-media` 标签 `v1.9.0+1`）。想要不含它的构建用 `-Pthinkos.ffmpeg=false`（这些音频会提示无法解码，画面照播）。

## 4. 接口

接口的权威描述在 `06-plugins.md` §6.10；决定与理由在 ADR-030。变更一览：API 7；组件 `video` / `controls`（`mode`）/ `seekbar`；`tos.media.*` 增加 `jump` / `live` / `audioTrack` / `textTrack`，条目增加 `type` / `headers` / `subtitles`，选项增加 `start`；`media` 保留状态（含直播、解码器、轨道列表）；事件 `media.session` / `media.track` / `media.error` / `media.warning` / `queue.end`；系统设置 `media.public_http`、`media.keep_awake_video`、隐藏的 `media.buffer_s` / `media.buffer_mb`；Manifest 的 `api` 上限提到 7。

## 5. 验收记录

### 5.1 自动化测试

- Kotlin 单元测试 **278 个全部通过**（新增 `MediaEntryTest`、`MediaPolicyTest`、`MediaTimeTest`、`MediaViewsTest`，以及 `MediaQueueTest`、`MediaHttpClientTest`、`PluginSchemaTest`、`SystemSettingsVisibilityTest` 里的新用例）。
- Python 39 个、Go `protocol` 包通过；`lintDebug` 无错误；`gen_android_strings.py --check`：11 种语言 × 132 个键一致；`package_plugins.py --validate-only`：全部有效。
- Lua：player 的 23 个用例在与 App 相同的沙箱里通过（`tools/tests/player_native.sh`），Mac 与 armv7 设备各跑一遍。

### 5.2 格式矩阵

`python3 tools/tests/media_matrix_device.py`：45 个样片，45 个符合预期，0 个不符。每个样片的结局只有三种：能播（播放头走了 ≥2.5 秒）、明确报错、超时；**超时算失败**，一个都没有。结果表在 `artifacts/streaming/format-matrix.tsv`，含解码器名与分类。

### 5.3 串流

`python3 tools/tests/media_stream_device.py`：36 个用例，36 个通过，0 个失败；另有 1 例（Mux 的 HTTPS HLS 测试流）因设备连不上该主机而跳过，见 §6。设备的 Wi-Fi（192.168.3.x）与 Mac（192.168.2.x）不在同一网段，所以用 `adb reverse` 走 USB 访问 Mac 上的 `tools/media/serve.py`，环回地址属于私网，明文规则照常生效。

### 5.4 界面与手势

画面上的单击 / 双击 / 竖拖手势见 §3.4，真机测试是 `tools/tests/video_gestures_device.py`（24 项全过：单击切换控件、中间双击暂停与继续、左右四分之一各跳 10 秒与连续累加、四个区域边界、相隔很远的两次点击不算双击、右半屏竖拖调音量、左半屏竖拖调亮度并停在 20% 与 100%、拖动不翻页不暂停）。

`python3 tools/tests/media_ui_device.py --attention`：18 项全部通过（最终套件）。设备上用 UIAutomator 读控件、`input` 注入手势，片源是本机 `serve.py` 上的 720p 短片（循环播放）：

- 没人碰时覆盖层不显示；一次触摸浮出进度条与上一首 / −10 秒 / 暂停 / +10 秒 / 下一首；15 秒后淡出。
- 在进度条上横向拖动会 seek（落在 ≥6.5 秒处），页面**不翻**；点按进度条跳到点按位置。
- 暂停 / 播放按钮切换状态；+10 秒让播放头前进（短片循环时按回绕计算）。
- 带两条音轨（en、ja）和一条内嵌字幕的样片：只有存在可选项时才出现“音轨”“字幕”按钮；音轨按钮轮换并回到第一条，字幕按钮打开、关闭内嵌字幕。
- 画面上长按仍打开设置页；在画面上横向滑动**不再**离开视频页（影院视图是应用式的整屏，ADR-040），用返回离开（回到接管前的页面）。
- 注意力：视频播放时显示器保持 AWAKE；暂停 200 秒后恢复正常变暗（ASLEEP）。
- 拖动进度条会触发长按、打开设置页的问题是这套测试发现并修好的，见 §6。

### 5.5 player 插件整条流程

`python3 tools/tests/player_device.py`：17 项全部通过（最终套件；另在显示器自然 ASLEEP 的状态下单独起跑一次，同样 17 / 17）。像用户一样只用触摸：

- 资料库打开，点标题切到“音乐”，扫描出推送的 FLAC；点一行开始播放并进入音频页；音频页的暂停 / 播放按钮可用；按 Back 回到资料库，音乐继续。
- 点标题切到“视频”，列出推送的视频；点一行在视频页播放；离开视频页会暂停影片并回到资料库。
- “最近播放”列出刚播过的两项。
- 长按进入插件设置（音乐文件夹、视频文件夹、续播开关）；保存串流地址立即开始播放，并出现在“网络流 → 已保存”；设置里填播放列表地址后，频道出现在“网络流”。
- 系统设置里有“媒体”分组，含“允许互联网明文 HTTP”和“播放视频时保持亮屏”两个开关。
- 首页卡片：真机截图确认——空闲时写“没有在播放”、控件灰；别的会话播放时显示标题与暂停按钮；点标题打开资料库（后两项在设备上手动验证，没有写进自动化）。

### 5.6 Lua API

`python3 tools/tests/media_api_device.py`：5 项检查全部通过。两个探针插件（`tools/tests/fixtures/media-probe`、`media-probe-denied`，只在测试时推到设备上）在真机的沙箱里跑：

- 没有 `media` / `library` / `network` 权限的插件，每一种媒体调用都被拒绝并带原因，4 种拒绝都真正触发过；
- 有权限的插件走完整个 `tos.media.*`：加载与读状态、编码与格式、`play` / `pause` / `toggle` / `seek` / `volume` / `next` / `prev` / `jump` / `shuffle` / `repeat`（含 `repeatMode`）/ `audioTrack` / `textTrack` / `live`，非法参数（负数 seek、音量 2、`jump(0)` / `jump(9)`、非布尔随机、未知 repeat 值）、非法队列（ftp 地址、禁用的请求头、带头的 RTSP、空队列、越界序号、未知选项）都被拒绝且之后会话完好；不能写保留的 `media` 状态；`queue.end` 只来一次；缺失文件报 `notfound`、文本文件报 `format`；`start` 偏移；`stop` 清空会话并通知所有者；
- `library.list` 能看到推送的音频，拒绝媒体根之外的目录；
- 探针内共 **44 条断言，0 条失败**。；Lua 逻辑另有 23 个用例，在与 App 相同的沙箱里跑（`tools/tests/player_native.sh`，Mac 与 armv7 设备都通过，包括最坏情形 512 首、长中文名的渲染 34 毫秒）。

### 5.7 性能

`python3 tools/tests/media_perf_device.py --seconds 45`。**1.0 核 = 一个核心满载**，设备有 8 个核心；“全部”是所有进程的合计，因为解码在别的进程里。

| 场景 | 全部进程 | 应用进程 | 丢帧 | 界面掉帧 | PSS | 解码器 |
|---|---|---|---|---|---|---|
| idle: clock face, nothing playing | 0.91 核 | 0.43 核 | 0 | 19 / 3094 | 78 MB | — |
| audio: FLAC 24-bit, clock face visible | 1.22 核 | 0.70 核 | 0 | 21 / 2732 | 84 MB | 软解 flac.decoder |
| audio: MP3, clock face visible | 1.20 核 | 0.66 核 | 0 | 27 / 2732 | 81 MB | 软解 mp3.decoder |
| video: H.264 480p30 | 1.48 核 | 0.77 核 | 0 | 0 / 2731 | 94 MB | 硬解 avc |
| video: H.264 720p30 | 1.46 核 | 0.75 核 | 0 | 0 / 2733 | 105 MB | 硬解 avc |
| video: H.264 1080p30 | 1.48 核 | 0.74 核 | 0 | 0 / 2732 | 119 MB | 硬解 avc |
| video: H.264 1080p60 | 1.56 核 | 0.76 核 | 0 | 1 / 2734 | 123 MB | 硬解 avc |
| video: H.264 1080p60, controls shown | 1.62 核 | 0.72 核 | 0 | 0 / 2734 | 122 MB | 硬解 avc |
| video: HEVC 1080p30 | 1.48 核 | 0.74 核 | 0 | 0 / 2733 | 126 MB | 硬解 hevc |
| video: VP9 1080p30 | 1.47 核 | 0.75 核 | 0 | 0 / 2735 | 133 MB | 硬解 vp9 |
| video: VP8 720p30 | 1.59 核 | 0.76 核 | 0 | 0 / 2730 | 105 MB | 硬解 vp8 |

- 没有一档出现丢帧（引擎的 `droppedFrames`）。界面掉帧（`gfxinfo` 的 janky）：视频档 0–1 帧 / 2734，音乐档 21–27 / 2732（<1%），空闲时钟 19 / 3094（0.6%）；视频档几乎为 0，是因为画面由解码器直接写入 TextureView，界面线程基本无事可做，音乐档的少量 janky 与不播放时的空闲时钟档（19 帧）同量级，所以不是播放造成的。
- 时钟页面空闲时 0.91 核，音乐播放时 1.20–1.22 核：FLAC 24-bit 的软解约占 0.3 核；应用进程自己在 1080p60 下只用 0.76 核，其余是解码器所在的进程与合成器。
- 内存最高 PSS 133 MB（VP9 1080p30），预算是 350 MB。
- TextureView 在 1080p60 下没有问题，所以没有改成 SurfaceView；这个选择留在 ADR-030 的边界里，除非以后出现丢帧。

### 5.8 内存与稳定

`python3 tools/tests/media_soak_device.py --switches 160 --live-minutes 120`，结果在 `artifacts/streaming/soak.json`。内存按 `dumpsys meminfo` 的 App Summary 分类记录（Java / Native / Code / Graphics / Other）。

**160 次换片**（8 个片源轮换：1080p H.264 / HEVC / VP9、AC-3 的 MKV、24-bit FLAC、MP3、AC-3 音频、MOV；每次加载到播放头 >0.7 秒再停止）：

- 160 次全部起播，没有一次失败。
- 每 16 次（两轮片单、同一部片子之后、解码器释放后等 3 秒）采一次样：PSS 101.4 → 108.3 MB，峰值 114.5 MB（预算 350 MB）；Java 堆在 9–19 MB 之间随 GC 起伏；Code 恒为 10.7 MB；Graphics 恒为约 55.6 MB。
- Native 堆 16.5 → 19.4 MB，约每次换片涨 16 KB：未查明是分配器碎片还是极小的泄漏，列入观察项（`10-known-issues.md` §11）。
- 离开视频页后 Graphics 没有回落（仍是 55.6 MB）：这是 GL / EGL 缓冲，进程里保留着，不随换片增长。进程刚启动时 PSS 约 65 MB，第一次播视频后会长到 100 MB 上下，之后就在这个水平附近。

**2 小时直播**（本机 `serve.py` 的 HLS 直播，720p H.264 + AAC，2 秒分片；屏幕保持由真实的注意力策略管理，没有强制 AWAKE，视频播放自己把屏幕保持清醒）：

- 237 个样本（每 30 秒一个），**错误样本 0，播放头冻结样本 0**。
- PSS 100.9 → 102.4 MB，峰值 103.0 MB；Native 堆 10.1 → 11.0 MB；Graphics 60.4 → 60.5 MB。曲线是平的。
- `liveOffset` 3.4–6.4 秒，中位数 5.0 秒。
- 测试源站的 ffmpeg 自带 `-t 3600`，约第 59 分钟结束并被下一个请求重新启动，播放列表重置（`PlaylistResetException`）：引擎在 1 秒后重试，30 秒内回到播放，是整个 2 小时里唯一一个“缓冲中”的样本。这等于顺带验证了直播重连。
- 累计 `droppedFrames` 292（约 0.13%），成组出现，约每 2 分钟 4–5 帧并伴随一次短暂缓冲。原因查明是**测试源站**循环样片时每次回绕少了两个 AAC 帧，累计到 213 ms 后被 ExoPlayer 的音频输出判为不连续（见 §6）。这 2 小时的数据是在源站修好之前跑的，修好之后的结果见下。

**源站修好之后**（`serve.py` 的直播 ffmpeg 加 `-af aresample=async=1`）：在设备上重放 25 分钟，期间没有任何 adb 往来，`droppedFrames` 为 0，日志里没有 `Audio sink error`，也没有中途缓冲。修好前同一套探针在 40 分钟里记到 19 次音频输出错误（每 2 分钟一次）与两批丢帧。2 小时的完整重跑没有做，所以“修好后 2 小时零丢帧”不是实测结论，只有这 25 分钟。

### 5.9 AGENTS.md 的提交前自查

| 项 | 结果 | 证据 |
|---|---|---|
| 1. `./gradlew assembleDebug` 通过 | 通过；`testDebugUnitTest` 278 个全部通过，`lintDebug` 0 个错误；`-Pthinkos.ffmpeg=false` 的分发构建也通过（APK 14.2 MB → 9.5 MB，不含 `libffmpegJNI.so`） | `/tmp` 构建日志；`android/app/build/` |
| 2. `adb install -r` 成功 | 成功（有线 USB，序列号 HUA0BWUT） | 安装输出 `Success` |
| 3. 设备上冷启动 < 3 s（断网、无 Server） | **通过**：Wi-Fi 关闭，首屏 847 毫秒，完整首页卡片 2117 毫秒（`artifacts/streaming/cold-start/`）。加入 player 后一度是 3.0–3.1 秒，超线；原因是每次启动都重复拷贝并哈希全部内置插件文件，已修（§6） | `artifacts/streaming/cold-start/result.json`、`offline-home.png`、`restored.json`（Wi-Fi、旋转、Home 都已还原） |
| 4. 待机界面连续 10 分钟无掉帧告警 | **通过**：首页、AMBIENT、横屏、深色，600 秒，1971 帧里 18 帧 janky（**0.91%**，阈值 5%），单核平均 CPU **3.12%**（阈值 8%；上一版同场景 2.92%，增幅 7%，低于“增长 >20% 必须调查”的线），App 进程全程不变、没有崩溃 | `artifacts/streaming/idle-bench/`（`cpu.json`、`gfx-after.txt`、`memory.txt`、`logcat.txt`） |
| 5. 不引入新的 arm64-only 依赖 | **通过**：APK 的 `lib/` 里只有 armv7 一个 ABI 目录：`libtos.so`（自己的 JNI 与 Lua）和 `libffmpegJNI.so`（Jellyfin 的 Media3 FFmpeg 解码器，含 armv7，GPL-3.0，见 §3.6）。Media3 的 HLS / DASH / RTSP 模块是纯 Java | `unzip -l app-debug.apk \| grep " lib/"` |

## 6. 测试里发现并修好的问题

| 问题 | 怎么发现 | 修法 |
|---|---|---|
| 重定向循环被当作临时故障，反复重试 | 串流矩阵：4 次重定向 | 重定向类错误不重试，归 `network` |
| RTSP 密码错误（`DESCRIBE 401`）被当作临时故障 | RTSP 用例 | 把 RTSP 应答码与 HTTP 状态码同样分类 |
| RTSP 服务端消失后永远“缓冲中”，既不恢复也不报错 | RTSP 重启用例 | 看门狗：缓冲 30 秒没有增长就重连，再不行报错；对端断开的直播自动重连 |
| 拖动进度条会触发长按 → 打开设置页 | 手势用例 | 进度条拿到手势后立即解除长按计时 |
| 眠屏时开始播视频，视频被路由器赶回首页，插件又因离开视频页把它暂停，一直停着 | 性能测试里的一次错误操作暴露 | “ASLEEP 回首页”在 live 页面已占着屏幕时不再执行 |
| 本地文件不存在被报成“网络异常” | Lua API 巡检 | 本地数据源按 `ENOENT` / `EACCES` 报 `notfound` / `permission` |
| 垂直滑动切换列表在 `list` 上不可能（列表占用纵向拖动） | player 流程测试 | 改成点标题切换 |
| **视频页两条边露出页面底色，浅色主题下是白边**（播放器一直就有，投屏时被发现） | 用户看到投屏画面旁有白边；在设备上用最大漂移复现：左右各 8 列、上下各 6 行共 14032 个像素是页面底色，两个视频页、两个主题都有 | 防烧屏漂移把整页推开，视频舞台没有被钉在屏幕边缘；现在视频舞台与照片舞台一样钉住（`PageView.videoStage`，布局里也不再加漂移偏移），漂移公式抽成 `ui/BurnIn.kt` 并给调试包加了强制偏移；`tools/tests/video_edges_device.py` 16 项全过 |
| 复制出来的 FLAC 样片播到第一圈末尾报错 | 性能素材 | 样片需要重新编码；写入已知问题 |
| **加入 player 后离线冷启动“内容就绪”从 2.1–2.5 秒升到 3.0–3.1 秒，超过 AGENTS.md 的 3 秒线** | 最终验收：`pre_m1_cold_start.py` | 在 `PluginRuntime` 的内置插件循环里按阶段计时：每次启动都把每个插件的所有文件从 APK 拷到暂存目录并逐个哈希，8 个插件合计约 1.2 秒（clock 528、gallery 281、player 212、weather 195 毫秒……），Lua 本身只占几十毫秒。已发布的同 digest 目录不再重复拷贝（`install()`；见 `06-plugins.md` §2）。现在 2.07–2.13 秒；目录被删掉后下次启动会重新安装（真机验证） |
| `player_device.py` 在 `ui --attention` 之后必现失败：库页一直是时钟 | 三次同一顺序失败后加日志复现：页面日志显示库页在 ASLEEP 下被打开，3.9 秒后测试去点标题，触摸唤醒屏幕并按设计回到首页，点击落在首页的时钟卡上 | 是测试的错，不是产品的错：测试把“保持清醒”误写成了“解除保持”。改为全程保持 AWAKE；并从自然 ASLEEP 开始重跑，17 / 17 通过。`DeckRouter.select` 与 `debug page` 现在各留一行日志，以后同类问题一眼能看出 |
| 首页卡片在别人开的会话（调试、别的插件）播放时仍写“没有在播放” | 在真机上用调试会话播放并看首页截图 | 事件只发给所有者，卡片改为随 `media` 状态每 2 秒更新一次（`main.lua` `renderNow` 值没变不重发）；新增 Lua 用例 |
| HTTPS HLS（Mux 测试流）有时失败：设备到 `test-streams.mux.dev:443` 的 TCP 连不上（Mac 可以，设备上 `nc` 6 秒不通，其它主机 0.8 秒通） | 完整套件第二轮失败，单独重现，再用 `nc` 排除播放器 | 网络环境问题，不是播放器。HTTPS 用例现在先让设备自己试连，连不上就跳过并说明；另外加了设备连得上的 Apple bipbop（HLS 主列表）与 Shaka 示例（DASH，VP9 硬解），都通过 |
| 2 小时直播里 `droppedFrames` 累计 292 帧（0.13%），每约 2 分钟一次、一次约 5 帧并伴随一次短暂缓冲；`liveOffset` 随之跳 +1.8 秒 | 引擎加了丢帧与中途缓冲日志，另开 40 分钟直播探针；设备日志里每 2 分钟一条 `Audio sink error: UnexpectedDiscontinuityException: expected …029334, got …242666`（差 213 ms），源站一侧在主机上抓分片分析 | 是**测试源站**的问题，不是播放器：`serve.py` 用 `ffmpeg -stream_loop` 循环 12 秒的样片，每次回绕音频少两个 AAC 帧（43 ms），约 5 次累计 213 ms，超过 ExoPlayer 音频输出 200 ms 的容忍线，它判为不连续并追赶画面（丢约 5 帧）。给源站的 ffmpeg 加 `-af aresample=async=1` 补静音，主机上抓到的 190 秒分片音频不再有缺口；设备上重跑 25 分钟：`droppedFrames` 0，没有一条 `Audio sink error`，没有中途缓冲（修好前同一套探针每 2 分钟一次） |
| 同一套直播里 TS 分片每个都在日志里留下两条 `PesReader: Unexpected start code prefix` | 40 分钟探针的日志里发现；再用别的 TS 源对照 | 不是源站专有：本机 ffmpeg 的 VOD HLS、直播 HLS、Apple 的 bipbop（HTTPS）都有，fMP4 的 HLS 没有；Media3 的 TS 解析器在分片起点打印警告，测试里没有看到对应的画面或声音问题。只是日志噪声（每 2 秒 2 行，会把 256 KB 的 logcat 环形缓冲在约 40 分钟内冲掉），列入已知问题 |
| `media_clock_device.py` 的两个检查依赖上一个测试留下的列表状态；`owner` 比较写成 `player`，引擎里是 `player:5` | 最终套件 | 标题循环点击直到出现目标曲目；owner 取冒号前的部分 |
| 内存采样落在不同的片子上，且 `Java Heap` / `Graphics` 的正则对不上 Android 8.1 的输出，数字不可比 | 内存曲线读不出趋势 | 每 16 次切换（两轮片单）在同一部片子之后、解码器释放后等 3 秒采样；按 App Summary 分 Java / Native / Code / Graphics / Other 记录；再加一个“离开视频页之后”的读数 |
| `StringFormatMatches`：lint 错误 33 条（3 个文案 × 11 语言） | `lintDebug` | 参数转成字符串 |
| 测试脚本自己的错：把 `hold_attention` 当作“保持”（无参数其实是“解除”）；XML 元素真值判断；变量名遮蔽函数 | 各自第一次运行 | 修了脚本；解除保持的语义写进了脚本注释 |

### 独立评审

测试都通过之后，另请一个只读的 agent 通读了引擎、组件、插件的全部代码，不看我的结论。它报了 14 条，我逐条对照代码核实，13 条属实并已修好（每条都补了测试），1 条是策略取舍：

| 评审发现 | 处理 |
|---|---|
| RTSP 点播（有时长的）播完后被当作“被对端挂断的直播”反复重放，`queue.end` 永远不来 | 只有播放器自己认定是直播（`isCurrentMediaItemLive`）才重连；重连期间不再重复触发，也不再误报结束 |
| 带账号密码的 RTSP 地址：主机名当标题时连 `user:pass@` 一起显示在影院视图、`media.title` 和列表副标题里；`URISyntaxException` 文本整段引用 URL，被插件显示在页脚 | player 的标题 / 副标题只用去掉账号密码的主机名；错误文本统一经 `MediaEntry.redact`；测试里断言整个状态和日志里不出现密码 |
| 播放列表里一个格式错误的地址（Kodi 式 `url|User-Agent=…`、含花括号 / 脱字符）会让整个队列被引擎拒绝，一个频道都播不了；RTSP 频道前有 `#EXTVLCOPT` 时会带上被 RTSP 条目禁止的请求头 | 解析时认 `|` 后缀并只保留合法地址；RTSP 不带头；整队被拒时改为只播所点的那一项 |
| 点一个频道会把 300 个频道整张排进队列，ExoPlayer 在 `prepare()` 时对每个 HLS / DASH 项都去取一次清单 | 频道一次只播一个；队列里 HLS / DASH 项上限 16（`MediaQueue.MAX_ADAPTIVE`），超出明确报错 |
| 音频页绑定的 `{{notice}}` 没有人写，音频流失败时页面一片空白 | 插件另写 `playNotice`，音频页绑定它 |
| 带 `alternates` 的卡片视图没有检查 `media` 权限，没有权限时 `PageView` 会因“没有渲染器”抛异常拉崩界面 | 安装器也检查 alternates；缺少渲染器的媒体组件显示为空而不是崩溃 |
| 只把 `Authorization` / `Cookie` 当作凭据；`X-Api-Key` 之类的令牌会跟着分片和跨源重定向去别的主机 | 反过来：只有 `User-Agent`、`Referer`、`Accept`、`Accept-Language`、`Origin` 可以去别的主机，其它头一律只给同源；`MediaHttpClient` 跨源重定向同样处理 |
| 失败重连在 ENDED 状态下每个事件都会再触发一次，六次后误报 `queue.end` | 重连中不再触发；结束只在没有重连挂起时才通知 |
| Lua 在 `load` 成功前就换了自己的队列，失败时之后的 `media.track` 会拿旧位置去查新队列、覆盖最近播放的续播位置 | 只有成功才换，失败恢复旧队列 |
| 卡死看门狗的计时在换曲、跳转、重连时不重置 | `clearRetry` 一并清零 |
| TLS 握手失败、证书不被接受也被当作临时故障重试 45 秒 | 不重试，直接报错 |
| 来自码流的标题、艺术家、轨道标签没有长度上限，每秒推送给所有媒体插件 | 文本 ≤256 字符，轨道标签 ≤64，轨道列表 ≤32 项 |
| 进度条多指触摸：第一指抬起后光标跳到另一指，最后一指抬起时再 seek 一次 | 只跟随按下时的那一根手指 |
| 任何有 `media` 权限的插件都能暂停 / 停止别人的会话（策略） | 保持：语音指令插件需要这个能力；写进 `06-plugins.md` §6.10 |

## 7. 与计划的偏离

- **没做歌词、频谱、封面主色 `mood` 背景染色、`tos.audio` 系统音量接口**（`22-player-plan.md` 的 P3 / P4 部分）。2026-11-02 补做了音乐界面：不是单独的 `nowplaying` 版式，而是一个 OS 组件 `nowplaying`（API 8，ADR-036，见 `30-player-ui.md`），含封面、队列变体、音量条、迷你条、首页卡片，以及 `tabs` 和 `list.current`。
- **没做 `tos.audio` 系统音量接口**：物理音量键可用；音量条与静音手势以后再说。
- **没做 Navidrome**（22 的 P6）与 WebDAV / SMB / Jellyfin 浏览。
- **ADR 编号用 030，不是 22 里写的 029**：`tools/tests/clock_accuracy_device.py` 已把 029 用于时钟精度。
- 计划里的“`controls.mode = compact | full | video`”实际是 `queue | seek | play`，因为影院视图自带完整控件，不需要 `video` 模式。
- 计划里的 `WAKE_LOCK`：不需要。设备有 `FLAG_KEEP_SCREEN_ON`，屏幕与 CPU 都不会睡；没有申请该权限。

## 8. 已知限制与未验证

已知限制（用户可见的那部分在 `10-known-issues.md` §11）：10-bit HEVC、AV1、4K、HDR 色调映射、WMV / ASF、MPEG-1 PS、裸 DTS、H.265 的 RTSP、只提供 UDP 的 RTSP 服务端、RTMP、DRM、组播、网页链接（B 站 / YouTube）解析；分发时 FFmpeg 的许可证。

**没有实测的**：

- **声音真的从扬声器出来**：自动化只验证播放头前进、解码器在工作、音轨被选中，没有录音比对；我听不到。样片里每秒一次的 1 kHz 蜂鸣和白条闪烁是为人工对音画同步准备的，没有仪器测过。
- **非 Device Owner 时的存储权限对话框**：本机是 Device Owner，启动时静默授予（`POLICY_FIXED`）；弹系统对话框的那条路径来自接线草稿，只做了编译与代码走读，没有在真机上走过。
- **打开 `media.public_http` 之后的真机行为**：只有单元测试（`MediaHttpClientTest`、`MediaPolicyTest`）；真机上只验证了默认关闭时公网明文被拒。
- **真实局域网路径**：设备 Wi-Fi（192.168.3.x）与 Mac（192.168.2.x）不互通，测试经 `adb reverse` 走环回地址；地址校验用的是同一套代码（私网 / 环回 / 链路本地），但没有在真实 LAN 上跑过。
- **真实片源**：没有接 IPTV、IP 摄像头、NAS 或 Jellyfin；源站是 `tools/media/serve.py` 与 `tools/media/rtsp_tcp.py`（后者把样片重编码成 H.264 + AAC）。HTTPS 只测了两个公共测试流。
- **其它语言与 RTL 下的界面**：影院视图、进度条与播放列表只在中文下用截图和 UIAutomator 看过；RTL（阿拉伯语、乌尔都语）下的影院视图没有肉眼检查（进度条本身强制从左到右）。竖屏下影院视图在真机上看过一次（16:9 直播居中、控件在底部，`artifacts/streaming/screens/video-live-portrait-overlay.png`），库页与首页卡片的竖屏没有看。
- **夜间模式的对比度**：夜间红色滤镜下只看过一次截图，没有按公式核对影院视图的对比度。
- **HDR、杜比视界、杜比全景声直通**：不支持也没有测。
- **SurfaceView 对比**：TextureView 在 1080p60 下没有丢帧，所以没有做 SurfaceView 的对照实验。

## 9. 重新验证

```bash
tools/media/gen_samples.sh                        # 样片，/tmp/thinkos-media-samples
cd android && ./gradlew assembleDebug testDebugUnitTest lintDebug && cd ..
python3 tools/tests/media_matrix_device.py        # 格式矩阵
python3 tools/tests/media_stream_device.py        # 串流（--only rtsp / hls / https / …，--no-live 跳过直播）
python3 tools/tests/media_ui_device.py [--attention]
python3 tools/tests/player_device.py
python3 tools/tests/media_api_device.py
python3 tools/tests/media_perf_device.py          # 性能
python3 tools/tests/media_soak_device.py --switches 160 --live-minutes 120
tools/tests/player_native.sh [--device]           # player 的 Lua 逻辑
python3 -m unittest discover -s tools/tests -p "test_*.py"; (cd server && go test ./...)
```

设备测试需要 debug 包；macOS 上 adb 不稳时先 `export ADB_MDNS=0`（`10-known-issues.md` §1）。

## 10. 文件索引

| 要看什么 | 位置 |
|---|---|
| 引擎、队列、条目、策略 | `android/.../media/MediaEngine.kt`、`MediaQueue.kt`、`MediaEntry.kt`、`MediaPolicy.kt`、`AuthorizedMediaDataSource.kt`、`StoragePermission.kt` |
| Lua 能力与权限 | `plugin/MediaCapability.kt`、`CapabilityAwait.kt`、`MediaViews.kt`；`PluginRuntime.kt`（`mediaState` / `mediaEvent` / `media.*`）；`LuaBootstrap.kt` |
| 组件 | `ui/MediaComponents.kt`、`ui/MediaWidgets.kt`；手势：`ui/ThinkStage.kt`、`ui/PageView.kt` |
| 注意力与页面 | `vision/SensorController.kt`（`mediaPlaying`）、`core/DeckRouter.kt`（live 页面） |
| 契约 | `shared/schema/plugin/layouts.json`（API 7）、`manifest.json`；生成：`PluginSchema.kt`、`plugin_generated.go` |
| 设置 | `shared/schema/settings/system.json`、`shared/i18n/system/*.json` |
| 插件 | `plugins/player/`（`main.lua`、`m3u.lua`、`library.lua`、`views/`、`i18n/`） |
| 测试工具与样片 | `tools/media/`、`tools/tests/media_*_device.py`、`player_device.py`、`player_native.sh`、`fixtures/media-probe*` |
| 结果文件 | `artifacts/streaming/format-matrix.tsv`、`stream-matrix.json`、`perf.json`、`soak.json` |
