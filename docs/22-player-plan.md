# 22 · 播放器规划：媒体接口、player 系统插件与页面

> 2026-10-30。**这是策划与讨论稿，没有改动任何实现代码。** 新增的只有本文，以及 `figures/player/` 下的 HTML 原型和 19 张截图。
> 标记：**已核对** = 读过代码/文档，或实际发过请求、跑过命令；**提议** = 我的设计，可以推翻；**待确认** = 需要用户决定（§13）；**未核对** = 推断，开工前先验证。
> 先读：`AGENTS.md` → `14-handoff.md` §D → `08-decisions.md` ADR-022 → `21-gallery-handoff.md`（图片管线与 carousel 的做法是本文的样板）→ `06-plugins.md` §4 / §6 → 本文。
> **开工前提**：音视频功能此前按用户指示暂缓（`20-`、`21-`、`06` §4.1）。本次用户明确要求规划；实现要等用户解除暂缓（§13 #1）。

## 1. 目标与边界

用户要的是三件事：

1. 说清 ThinkOS 要为音乐和视频播放**新增哪些接口**。
2. 做一个**系统级插件**：音频 + 视频播放器。
3. 音频页面的**布局**要认真设计，要有**歌词**和**频谱**组件，页面 demo 都要能看到。

沿用的原则：设备自主（ADR-015，Server 不是前提）；插件只做数据层，布局、动画、手势、解码都归 OS（ADR-017，不为外观预留扩展点）；插件只走 `tos.*`；armv7 / API 27；无 WebView、Compose、GMS；可配置项走设置 schema。

本期不做：在线音乐账号（Spotify 等）、DRM、投屏、均衡器和音效、录音、HLS/DASH 在线流（要新依赖，§9）、逐字卡拉 OK 歌词。

## 2. 现状核对（已核对，2026-10-30）

2026-10-31 补记：下表现状保留核对当时的快照；API 4 / ADR-025 现已用于 tiles/tile，播放器提议顺延为 API 5 / ADR-026。

| 项 | 现状 | 依据 |
|---|---|---|
| 媒体引擎 | `MediaEngine`（355 行）已写：Media3 ExoPlayer 1.9.2 + OkHttp 数据源；一个会话、一个队列（≤512）、一个所有者；`load / play / pause / toggle / next / prev / seek / volume / setShuffle / setRepeat / stop / releaseOwner / attachVideo`；事件 `media.session / media.track / media.error / queue.end` | `media/MediaEngine.kt`、`MediaQueue.kt` |
| **接线** | **没有任何代码实例化 `MediaEngine` 或 `MediaComponents`**（grep 为空）；`RuntimeHost` 的组件工厂只接了图片。引擎与 `Controls`/`Video` 组件是未启用的代码 | `RuntimeHost.kt:59,79` |
| Lua | 没有 `tos.media`；`tos.library.list` 已有（gallery 用）。接线草稿在仓库外 `/tmp/thinkos-media-integration/`（易失，且其中的 `PluginRuntime`/`RuntimeHost` 是旧快照，**不能整文件覆盖**现在的版本，只能摘取 `MediaCapability`、`StoragePermission`、`CapabilityAwait`、`AuthorizedMediaDataSource`） | 该目录 |
| `media` 保留状态 | 未发布。引擎 `snapshot()` 有 19 个字段（status、owner、kind、index、count、title、artist、album、art、position、duration、progress、volume、shuffle、repeat、error、videoWidth、videoHeight、pixelRatio） | `MediaEngine.snapshot` |
| 词汇 | `controls`、`video` 有 schema 但标 Deferred、不在 `component.oneOf`；**没有** `nowplaying` 版式、`lyrics`、`spectrum`、`seekbar`、`volume`。`list` 行只有 `icon/title/subtitle/trailing/progress/max`，**没有"当前行"**。当前 API 版本 3（`image`、`carousel`） | `layouts.json`（`x-thinkos-api`） |
| 封面来源 | `MediaLibrary` 给音频项的 `art` 是 `embedded:<路径>`，但 `ImageSource.parse` 只认 `asset:`、http(s)、本地绝对路径/`file:`——**`embedded:` 会被拒绝** | `image/ImageSource.kt` |
| **手势冲突** | `ThinkStage.onInterceptTouchEvent`：落在原生组件上的触摸，只要横向位移超过 slop 且大于纵向，就**改由舞台接管翻页**。所以进度条/音量条**无法被拖动**，需要新的组件接口（§4.6） | `ui/ThinkStage.kt` |
| 帧率档 | `FrameGovernor`：有自绘动画且 AWAKE/ENGAGED → 60fps；AMBIENT 有动画 → 30；AMBIENT 空闲 → 3；ASLEEP → 1/5。**没有"组件自己要 30fps"的入口** | `anim/Motion.kt` |
| 实时活动 | `tos.page.focus(page,{live=true})` 要求页面声明 `live`；用户 30 秒内的本地操作优先；有 live 页时 AMBIENT 不会切到 gallery；ASLEEP 仍会切 sleep 页 | `core/DeckRouter.kt` |
| **ExoPlayer 缓冲** | `getPlayer()` 没设 `LoadControl`。javap 读到 Media3 1.9.2 默认：视频 **131,072,000 B（125MiB）**，本地播放的视频 19,660,800 B，音频 13,107,200 B，最长缓冲 50 s。`03` §3.10 写的是"约 15s / 8MB"，预算 ~10MB——**现状与文档不符** | javap `DefaultLoadControl`；`03` §3.10 |
| Media3 模块 | gradle 缓存里只有 `common / container / database / datasource / datasource-okhttp / decoder / exoplayer / extractor`，**没有 hls、dash、ui、session**。网络流只能是渐进式 HTTP | `~/.gradle/.../androidx.media3` |
| 解码器 | MediaCodec：avc / hevc / vp8 / vp9 / mpeg2 / mpeg4 / h263 / wmv 等硬解，**上限 1920×1088、489,600 blocks/s（≈1080p60）**，没有 4K；音频软解有 aac / mp3 / flac / vorbis / opus / amr，**没发现 AC3 / E-AC3 / DTS / ALAC** | `/vendor/etc/media_codecs*.xml`（只读） |
| 音频输出 | HAL 48kHz、16-bit PCM、扬声器；`STREAM_MUSIC` 当前 8/25 | `dumpsys`（只读） |
| 设备上的音乐 | `/sdcard/Music` 有 4 个文件：3 个 24-bit FLAC（44.1/48kHz，246–378 s，48–68MB）和 1 个 MP3；**读头部发现没有任何标签**（FLAC 的 Vorbis 注释为空、无内嵌封面；MP3 的 ID3v2 只有 `TSSE`，ID3v1 没查），没有同名 `.lrc`，`/sdcard/Movies` 为空。**文件在共享存储，读取需要 READ_EXTERNAL_STORAGE，manifest 未声明** | 只读 `adb`，`21-` §2 |
| Device Owner | **已设置**（`ThinkOSAdminReceiver`）。理论上可用 `setPermissionGrantState` 静默授予读权限，**没试过** | `dumpsys device_policy` |
| 性能基线 | AWAKE 静态数字时钟 ≈32% 单核；AMBIENT 首页 ≈2.9%；gallery 连续平移 15 秒样本 ≈51% 单核 | `13-`、`21-` |
| 愿景 | `01-vision.md`：音乐播放、频谱可视化、"握拳静音"手势、语音"下一首、静音" | `01-vision.md` |

## 3. 页面与交互设计

### 3.1 参考与取舍

| 参考 | 我取的 | 来源与可信度 |
|---|---|---|
| iPhone StandBy 全屏播放 | 一屏一个主角；封面大、控制轻 | 搜索摘要，**入口是顶部小标还是居中图标，几篇说法不一致，没采用** |
| Eversolo DMP-A6 | 封面 / VU / 频谱可切换，VU 与频谱各 4 种布局 | 读到 HiFiReport 原文 |
| WiiM Ultra | 播放时显示封面或指针式 VU | 读到 Son-Vidéo 原文；评测说 VU **有延迟，削弱了真实感** → 同步是体验关键（§8.2） |
| Apple Music 歌词 | 带时间戳的歌词自动出现；点一行跳转 | 官方支持页摘要；"点击跳转"来自第三方文章摘要 |
| Spotify Car Thing（800×480） | 点大封面暂停/播放 | 社区帖摘要，没核对 |

由此定下的原则：① 封面、歌词、频谱各占一个变体，队列是第四个，**一屏只有一个主角**；② 控件平时隐藏，轻点出现 3 秒（沿用 `04` §4.4）；③ **点封面 = 播放/暂停**；④ 歌词当前行放大，邻行渐隐，长间奏用三个点；⑤ 档位和夜间模式改变信息密度；⑥ 频谱与进度条取封面主色。

### 3.2 页面集合（提议）

| 页面 | 版式 | 说明 |
|---|---|---|
| `player.mini`（首页卡片） | `glance` | 封面、曲名、迷你频谱、上一首/播放/下一首；点击打开 `player.now` |
| `player.now` | **新增 `nowplaying`**，变体 `cover / lyrics / spectrum / queue` | 左右滑翻页、上下滑切变体；`live: true`，开始播放时 `tos.page.focus` 接管，停止后回到原页面 |
| `player.video` | 同一个 `nowplaying` 版式，只声明 `video` 槽位 → 影院排布 | 没有变体；也是 `live` 页。原型把它画成独立页面 |
| `player.library` | 现有 `split` + `list` | `lead` 放"继续播放"卡，`main` 是曲目列表；"音乐 / 视频"筛选用上下滑变体，不新增组件 |

### 3.3 正在播放的四个变体（横屏 788×492dp，数字取自原型）

| 变体 | 排布 | 截图 |
|---|---|---|
| `cover` | 封面 452×452 在左；右栏 268dp：曲名 32sp（2 行内自动缩到最小 68%）、艺术家 22sp、格式行 14sp、频谱、进度条 | [AWAKE 深色](figures/player/now-cover-awake.png)、[ENGAGED 浅色](figures/player/now-cover-engaged.png)、[生成封面](figures/player/now-cover-generated.png) |
| `lyrics` | 左栏 208dp（封面、曲名、进度），右栏 516dp 歌词。当前行 36sp/Medium，其余 24sp，译文 15/18sp，行高 86dp，当前行锚在 40% 高度，邻行不透明度 .62/.4/.24/.12 | [歌词](figures/player/now-lyrics.png)、[无歌词](figures/player/now-lyrics-none.png) |
| `spectrum` | 频谱铺满 748×358，小封面 + 曲名 + 进度在下方。样式：条形 / 镜像 / 双指针 VU | [条形](figures/player/now-spectrum-bars.png)、[VU](figures/player/now-spectrum-vu.png) |
| `queue` | 左栏同 `lyrics`，右栏 `list`，行高 64dp，当前行用 `surface` 底色 + 小均衡器图标 + `accentText` | [队列](figures/player/now-queue.png) |

竖屏同一套元素竖排：封面 452×452 在上，[ENGAGED](figures/player/p-now-cover-engaged.png)、[歌词](figures/player/p-now-lyrics.png)、[频谱](figures/player/p-now-spectrum.png)。其它页面：[首页卡片](figures/player/home-mini.png)（[竖屏](figures/player/p-home-mini.png)）、[视频](figures/player/video-engaged.png)（[竖屏](figures/player/p-video-engaged.png)）、[音乐库](figures/player/library.png)。

### 3.4 档位与主题改变信息密度（提议）

| 状态 | 变化 |
|---|---|
| ENGAGED | 显示完整控件（随机/上一首/播放/下一首/循环）、音量条、带拖动柄的进度条 |
| AWAKE | 无控件；频谱 + 进度条 |
| AMBIENT | 背光降低（OS）；去掉进度条和控件；歌词变**单行聚焦**（当前行 + 下一行淡出，[截图](figures/player/now-lyrics-ambient.png)）；频谱降帧（§8.3） |
| NIGHT | 红色滤镜（OS）；**不画封面**（红色封面没有观赏价值，同 `04` §1.2 的理由），曲名居中放大，频谱缩小，[截图](figures/player/now-night.png)。页面不退出，因为音乐还在放 |
| 视频 | 全出血画面；点击出现遮罩、标题、进度、控件 |

无歌词时显示"暂无歌词 + 已查过的来源"，纯音乐显示"纯音乐"，两种情况都用频谱补位。

### 3.5 手势

OS 保留：左右滑翻页、长按进设置。点封面 = 播放/暂停；点别处 = 唤出控件 3 秒；点歌词行 = 跳转；点进度条/拖动 = seek；点队列行 = 发 `tap` 事件给插件（由它 `tos.media.jump`）。**控件点击由 OS 直接调引擎，不经过 Lua**（既有 `MediaComponents.Controls` 的做法）。视频双击左右半屏 ±10 秒是可选项（P5）。

### 3.6 配色（已按公式校核）

频谱、进度条取封面主色 `mood`，按主题映射明度（`04` §1.1）。原型里 5 首曲子的主色 × 浅/深两套主题，各算 6 项对比度（图形 ≥3:1、文字 ≥4.5:1）：初稿有 1 项不过（浅色下青绿色强调文字对白底 **4.24**），把浅色 `accentText` 明度从 .34 降到 .29 后 **全部通过**；最薄的两项是浅色青绿图形 **3.18 / 3.49**，只比 3:1 多一点余量。

- 原型里"AMBIENT 下 2.8:1"是用 CSS `brightness` 模拟背光造成的**假象**；真机降背光不改变像素对比度。**低背光下的实际可读性没有测过。**
- **OS 目前不消费 `mood`**（`20-` §2：`relevance`/`mood` 只发布，不调度、不染背景），所以背景染色要先实现。封面主色由谁算（`ImageLoader` 是否已顺带算）**未核对**。

### 3.7 原型（只用于讨论，不进 APK）

`figures/player/prototype.html`，浏览器直接打开：1 CSS px = 1dp，画布 788×492 / 492×788，颜色取自 `Palette.kt`。左侧面板切换页面、变体、频谱样式、曲目、方向、主题、档位；"组件边界"按钮把每块区域标上它对应的词汇（[截图](figures/player/now-outline.png)）；"自动巡演"循环全部状态；面板底部有这一页的 view JSON 草案。**频谱是模拟数据，不代表真机效果和开销；示例曲的歌词是我原创的；曲目列表取自设备上的 4 个真实文件名。** 它是浏览器渲染，不是设备渲染。

原型没覆盖的细节（落地时要补）：① **长歌词行**：原型滚动模式用了单行不换行，真实的长英文行会被裁掉，需要换行或自动缩字；② 进度条拖动（原型只有点击）；③ 竖屏歌词底部的三点间奏标记被容器裁了半个。

## 4. ThinkOS 要新增的接口

### 4.1 Lua API（`tos.*`）

| API | 权限 | 状态 | 说明 |
|---|---|---|---|
| `tos.media.load(items, opts)` | `media` | 引擎有，Lua 未接 | `items[i] = {url,title,artist,album,art,kind,mime}`，新增 `subtitles`（§9）；`opts = {index,shuffle,repeat,autoplay,volume}` |
| `tos.media.play / pause / toggle / next / prev / seek(ms) / stop / shuffle(b) / repeat(mode)` | `media` | 引擎有 | 同上 |
| **`tos.media.jump(index)`** | `media` | **新增** | 引擎没有按序号跳转（`seekItem` 是私有的）；队列行点击要用 |
| `tos.media.volume(v)` | `media` | 引擎有 | 这是**播放器增益**，建议只用于闪避（通知时压低），不给用户的音量条用 |
| **`tos.audio.volume() / setVolume(v) / mute(b) / muted()`** | `media`（提议） | **新增** | 系统 `STREAM_MUSIC`（`AudioManager`，无需危险权限）。UI 音量条、物理音量键、"静音"语音/手势（`01-vision`）应一致 → 以系统音量为准 |
| **`tos.library.read{path}`** | `library` | **新增** | 读同授权根内的小文本（`.lrc .txt`，≤256KiB），返回 UTF-8 字符串。中文 `.lrc` 常见 GBK，需 BOM 检测并回退 GBK，**要实测** |
| `tos.library.list{kind=…}` | `library` | 已有 | 新增 `kind = "lyrics"`（`.lrc`）和 `"subtitle"`（`.srt .vtt`），插件按同名文件匹配 |
| `tos.http.get(url,{headers})` | `network` | 已有 | LRCLIB、封面、Navidrome 用；`User-Agent` 可自定义（不在禁止头里） |
| `tos.page.focus / release` | — | 已有 | 开始播放时 `focus("player.now",{live=true})` |
| 追加/删除队列项 | `media` | 可选（P3 之后） | 一次 `load` 最多 512 项，够用 |

### 4.2 保留状态 `media`（OS 发布，只读）

有 `media` 权限的插件视图可绑定 `{{media.*}}`，`tos.state.set` 拒绝写 `media`（和 `config` 一样）。在引擎 19 个字段之外**新增**：`color`（封面主色 `#RRGGBB`）、`hasVideo`、`subtitle`（当前字幕是否可用）。`position` 约 1 秒更新一次（引擎的 `tick` 是 1000ms）——**进度条、歌词、频谱不能靠它**，要读 OS 内部的 `MediaClock`（§4.7）。音频格式行（"FLAC · 24bit/48kHz"）能否从解码器 `Format` 拿到位深**未核对**，退路是插件按扩展名和 mime 拼。

### 4.3 事件

保留引擎已有的四个，Lua 侧命名 `media.session / media.track / media.error / queue.end`，只发给会话所有者；替换 `06` §6.1 里没有实现的 `playback`。播放器插件靠 `media.track` 触发歌词/封面解析。

### 4.4 权限

不新增权限名：`media`（会话与 `media` 状态）、`library`（本地读取）、`network`（流、歌词、封面）。**运行时读共享存储（READ_EXTERNAL_STORAGE）是个决定**（§13 #4）：用户的音乐就在 `/sdcard/Music`。

### 4.5 词汇（提议 API 5）

| 词汇 | 说明 |
|---|---|
| 版式 `nowplaying` | 槽位 `art title sub meta visual lyrics queue seek ctrl volume video`，插件声明哪些槽位有内容，OS 按 **变体 × 方向 × 档位 × 主题** 决定每个槽位的矩形，状态切换时各槽位补间过去（原型的 `compute()`/`place()` 就是这个逻辑，原生用现有 `Motion.spring`）。有 `video` 槽位时走影院排布。也接受 `mood` |
| 组件 `lyrics` | `lines: "{{lyrics}}"`（`[{t(ms), text, sub?}]`）、`empty` 文本；**当前行由 OS 按 `MediaClock` 算**，点击一行 seek，行内换行、RTL |
| 组件 `spectrum` | `style: bars \| mirror \| vu`（可绑定）、`bands`；数据来自 OS 的 `SpectrumTap`，**不经过 Lua**；无 `media` 权限不画 |
| 组件 `seekbar` | 点击/拖动、已播/剩余时间 |
| 组件 `volume` | 驱动系统音量 |
| `controls`、`video` | 解除 Deferred；`controls.mode = compact \| full \| video`（现在只有上一首/播放/下一首）；`video` 加字幕 |
| `list.current` | `"current": "{{media.index}}"` 高亮当前行并显示均衡器小图标 |
| `image.src = embedded:…` | 取内嵌封面（`MediaMetadataRetriever.getEmbeddedPicture`）；同目录 `cover.jpg / folder.jpg` 走普通本地路径 |

```jsonc
// plugins/player/views/now.json（草案；变体标题是否支持 @key 翻译未核对，clock 目前是写死的英文）
{ "layout": "nowplaying", "mood": "{{media.color}}",
  "variants": [{"id":"cover","title":"Cover"},{"id":"lyrics","title":"Lyrics"},
               {"id":"spectrum","title":"Spectrum"},{"id":"queue","title":"Queue"}],
  "slots": {
    "art": {"type":"image","src":"{{cover}}"},
    "title": {"type":"text","value":"{{media.title}}"}, "sub": {"type":"text","value":"{{media.artist}}"},
    "meta": {"type":"text","value":"{{format}}"},
    "visual": {"type":"spectrum","style":"{{config.spectrum}}"},
    "lyrics": {"type":"lyrics","lines":"{{lyrics}}","empty":"{{lyricsNotice}}"},
    "queue": {"type":"list","items":"{{queue}}","current":"{{media.index}}","item":{"title":"{{.title}}","subtitle":"{{.artist}}","trailing":"{{.dur}}"}},
    "seek": {"type":"seekbar"}, "ctrl": {"type":"controls"}, "volume": {"type":"volume"} } }
```

### 4.6 组件与舞台接口

- **`RenderComponent` 新增"我接管横向拖动"**（seekbar、volume 用），否则 `ThinkStage` 会把拖动抢去翻页（§2）。
- **帧率提示**：组件可声明想要的帧率（频谱 30），`FrameGovernor` 取最大值；现在只有开关。
- **`MediaEngine.positionNow()`**：主线程同步取当前位置，供歌词、进度、频谱逐帧读取。
- **舞台背景消费 `mood`**。
- 视频播放时给 `AttentionPolicy` 一个"保持活跃"输入（§9）；`AttentionPolicy` 的实现细节我没读，只确认了它有 `touch()`。

### 4.7 OS 内部件（不对插件开放）

`SpectrumTap`（§8）、`MediaClock`、字幕叠加视图、`LoadControl`（§2）、`AuthorizedMediaDataSource`（本地播放走同一道文件描述符授权）。音频焦点和"拔耳机停播"引擎已开（`setAudioAttributes(…, true)`、`setHandleAudioBecomingNoisy(true)`）。屏幕熄灭/变暗时 CPU 休眠是否需要 `setWakeMode` **未核对**。

## 5. player 系统插件

### 5.1 定位（提议）

内置、固定安装（类似 `settings`），但**不拿系统权限**：它只是第一个使用 `media` 能力的插件。它取代计划中的 `music`（`06` §10、`09` M1、`14` §G），一个插件同时负责音频和视频。别的插件（电台等）也能开会话，`media` 状态和 `nowplaying` 页面照样显示；但歌词/封面解析只对 player 自己的队列做（§13 #2）。

```
plugins/player/
├── manifest.json        api 4；permissions: media, library, network
├── main.lua             会话、队列、事件、live 接管
├── library.lua          扫描、文件名解析、分组
├── lyrics.lua           LRC 解析 + 来源链
├── cover.lua            封面来源链
├── sources/navidrome.lua   （P6，可选）
├── i18n/{11 种语言}.json
└── views/{now,video,library,mini}.json
```

指令：`play / pause / next / prev / mute`，带 `phrases`（"下一首""静音"）和 `gesture`（manifest 已支持 `fist` 等）——"握拳静音"落在 `mute` 上。

### 5.2 设置（提议，全部走 schema）

`spectrum`（bars/mirror/vu）、`default_variant`、`lyrics_translation`（auto/off，见 §6.3）、`lyrics_online`（bool）、`cover_online`（bool）、`music_dir`、`video_dir`、`keep_awake_video`（bool）、`navidrome_url / user / password(secret)`（P6）。频谱条数、重试节奏作为 `hidden`。

### 5.3 状态与流程

状态：`queue`（≤512 项 × 约 150B ≈ 77KB）、`lyrics`、`lyricsNotice`、`cover`、`format`。`tos.state`、`tos.store` 各 ≤256KiB。

1. `start` 只读缓存（上次队列/曲目），1 秒后才扫描和联网（天气的做法，`20-` §4）。
2. 扫描 `tos.library.list{kind="audio",recursive=true}`（≤512 项，截断要提示）→ 解析文件名 → 写 `queue`。
3. 点播：`tos.media.load` → `tos.page.focus("player.now",{live=true})`。
4. `media.track`：用**代际计数**异步解析封面和歌词，快速切歌时丢弃迟到结果。
5. 停止：`release`，约 10 秒后回到原页面（`04` §4.4）。
6. ASLEEP 且在放音乐：默认继续播放、显示 sleep 页的时钟（`DeckRouter` 现有行为，**待确认**这是不是想要的）。

## 6. 歌词

### 6.1 来源链（提议）

① 同名 `.lrc`（`tos.library.read`）→ ② 内嵌标签（USLT / SYLT / Vorbis `LYRICS`；**P2 以后**，本机 4 个文件都没有；Media3 的 `Id3Decoder` 怎么处理 USLT **未核对**）→ ③ Navidrome → ④ LRCLIB（可选）。缓存放 `tos.store`：实测 3 首样本的同步歌词每首 1.2–2KB，缓存上限 64 首。

### 6.2 在线来源（已实测的部分）

- **LRCLIB** `GET /api/get?track_name&artist_name[&album_name][&duration]`：无需 Key，我拿**不在用户库里**的曲目试了一次，返回 **6.2KB、41 行同步歌词、0.8 秒**。`/api/search` 一次返回 120–190KB，不用。文档写时长容差 ±2 秒；我试"时长不符"那次遇到 **503 ServerOverloaded**，所以容差行为**没验证**，要做重试退避。限速（文档）：无标识约 45 次/分，带标识 User-Agent 约 90 次/分，429 要遵守 `Retry-After`，否则可能被临时封禁。**我读到的文档里没有歌词数据的许可条款。**
- **Navidrome**：OpenSubsonic `getLyricsBySongId` 返回 `structuredLyrics[]`（`synced`、`line[].start/value`、`lang`、`offset`）；`start` 的单位文档文字没写，示例像毫秒；服务端是否实现、`.lrc` 同名文件是否被识别，只有搜索摘要，**未核对**。
- **隐私与版权**：在线查词会把**曲名、艺术家、专辑、时长**发给第三方；歌词是有版权的作品。与 Bing 同一立场：仅个人设备展示，只缓存在设备上，不转发。**默认开还是关是需要用户拍板的事**（§13 #5）——设备上的 4 个文件没有任何歌词，关着的话歌词页对它们永远是空的。

### 6.3 解析与显示

- **解析放 Lua**（`lyrics.lua`），OS 不加解析接口：支持 `[mm:ss.xx]`、`[mm:ss.xxx]`、`[mm:ss]`、一行多个时间戳、`[offset:±ms]`、元数据行（`[ti:]` 等）跳过、去掉逐字 `<mm:ss.xx>`、CRLF 与 BOM。字符串模式匹配已开放（`06` §6）。
- **双语约定**：同一时间戳出现两行，视为"原文 + 译文"。这是**启发式**（中文歌词文件常这么写），所以做成设置可关。
- 当前行 = 在 `lines` 上按 `MediaClock` 二分；滚动用 `Motion.spring`（520ms）；两行相距 ≥9 秒显示三点间奏；点一行 `seek(line.t)`；减少动画时无滚动动画。逐字高亮不做。

## 7. 封面与元数据

- **文件名解析**：设备上 4 个文件都是 `艺术家 - 曲名 [数字]` 的形式 → 以首个 " - " 拆分、去掉末尾 `[数字]`，拆不开就用文件名。库里读到的标签优先。
- **封面来源链**：内嵌图（`embedded:`）→ 同目录 `cover.jpg / folder.jpg` → （可选）iTunes Search → **生成封面**（艺术家名 + 首字大字，用 `mood` 底色，原型已画）。本机 4 个文件既无标签也无封面，所以**默认体验就是文件名 + 生成封面**。
- **iTunes**：`artworkUrl100` 把 `100x100bb` 改成 `600x600bb` 实测可用（HEAD 看到 2,706 B → 52,582 B，`image/jpeg`）。第一次请求遇到过一次 TLS 重置，重试成功。**Apple 对封面的使用条款我没读。**

## 8. 频谱与性能

### 8.1 管线（提议）

在 ExoPlayer 的音频链路里插入 `TeeAudioProcessor(AudioBufferSink)`（javap 确认接口是 `flush(sampleRate, channels, encoding)` + `handleBuffer(ByteBuffer)`；`DefaultAudioSink.Builder.setAudioProcessors` 与 `DefaultRenderersFactory.buildAudioSink` 都存在，但**处理器链顺序的影响未核对**）。**不需要 `RECORD_AUDIO`**。流程：下混单声道 → 环形缓冲 → 单独线程 1024 点 Hann 窗 FFT，每 ≈33ms 一帧 → 64 个对数频段（约 40Hz–16kHz）→ 起音/衰减平滑 + 峰值保持 → 带媒体时间戳的帧队列。`flush()` 报告的编码会变（16-bit / 24-bit / 32-bit / float），都要处理。

只有存在**已挂载且活跃**的 `spectrum` 组件时才开（引用计数）；换页、ASLEEP 时零开销。FFT 本身约 1.5 MFLOP/s 量级，**是估算，不是实测，预计不是瓶颈**。

### 8.2 同步——纠正 `03` 的说法

`03` §3.10 说"与播放严格同步"，这**不成立**：Tee 点在 `AudioTrack` 之前，`DefaultAudioSink` 的 PCM 缓冲在 250–750ms 之间（javap 常量 `MIN/MAX_PCM_BUFFER_DURATION_US`），直接画会比声音早。做法：每一帧带媒体时间戳，绘制时取**最接近 `player.currentPosition` 的那一帧**（ExoPlayer 的位置基于 `AudioTrack` 时间戳，已扣掉缓冲，**这一点未核对**，真机验证）。验收见 §11。WiiM 的评测恰好批评了 VU 表的延迟。

### 8.3 帧率与 CPU

- AWAKE 下连续自绘就是 60fps 档，一个**静态**数字时钟就要 ≈32% 单核；gallery 的连续平移 ≈51%。所以频谱要有**组件级帧率提示**（§4.6），默认 **30fps**，AMBIENT 更低，条数 64→32 降级（`03` 已有掉帧降级的设计）。
- **口径要统一**：`03` §3.15 和 `09` M1 的"播放 + 频谱 CPU <25%"表头是"8 核总占比"（≈2 个核），而 `13-`、`20-`、`21-` 的实测都按"单核 100%"。按前者几乎没有约束。建议验收按**单核口径**写（§13 #8）。
- **先量再建**：P1 用假频谱数据实测 30/60fps 的成本，再决定 P4 的预算。
- 原型里"低频能量推动封面和背景圆缩放"是逐帧改属性，**默认关闭**，量过再说。

## 9. 视频

- **来源**：本地 `/sdcard/Movies`（现为空）+ 渐进式 HTTP(S)（私网 http、公网 https，沿用 `MediaHttpClient`）。**不做 HLS/DASH**：要新增 media3 模块，且要先确认 AAR 的 ABI（AGENTS.md）。
- **格式**：硬解 H.264 / HEVC / VP8 / VP9 / MPEG-2 / MPEG-4 等，**最高 1080p60**；没有 AC3 / E-AC3 / DTS / ALAC 解码器，遇到要给出明确错误，不要静默无声。HEVC 10-bit、VP9 是否真能流畅播放**没测**。
- **画面**：`TextureView`（现有 `Video` 组件），`contain/cover`，用 `pixelRatio` 和旋转修正。
- **字幕**：侧载 SRT/VTT（`MediaItem.SubtitleConfiguration`，javap 确认存在），`Player.Listener.onCues(CueGroup)`（javap 确认存在）由 OS 画带描边的文字；不引入 media3-ui。
- **注意力**：视频播放时不应降到 AMBIENT/ASLEEP（设置 `keep_awake_video`，提议默认开）；纯音频不阻止变暗。
- **内存**：必须设 `LoadControl`，不然远程视频缓冲默认 125MiB（§2）。
- **测试素材**：设备上没有视频。开发 Mac 有 `ffmpeg`，用它生成合成 H.264 1080p30 / HEVC / VP9 样片和 SRT，不下载素材。

## 10. 开发分解

依赖：P0 → (P1 ∥ P2) → P3；P4、P5 依赖 P2。**P1 不依赖引擎和网络**：用一个仅 debug 的假会话（合成 `media` 状态、时钟、频谱、示例歌词）就能在真机上复现原型的全部画面，与天气、相册"先 UI 后数据"的节奏一致。

| 阶段 | 内容 | 验证 |
|---|---|---|
| **P0 契约与决策（S）** | 新 ADR（建议 ADR-026；08 里 ADR-023 留给 HTTP 通知提案、024 已用）；`layouts.json` 加 `nowplaying` 与新组件，`controls/video` 解除 Deferred，`list.current`，`x-thinkos-api` → 5；跑 `gen_kotlin.py` / `gen_go.py`，**不手改生成文件** | `PluginSchemaTest`、`test_plugin_packages.py` |
| **P1 渲染器 + 假会话（L）** | `nowplaying` 版式与补间；`lyrics / spectrum / seekbar / volume`；横向拖动接管、帧率提示、`mood` 染色；`list.current`、`embedded:` 封面；debug 假会话 + 推送测试插件 | 设备截图矩阵；**假频谱 30/60fps 的 CPU 基线** |
| **P2 引擎接线（L）** | `RuntimeHost` 实例化引擎；摘取草稿的 `MediaCapability` 等；`tos.media.*`、`tos.audio.*`、`tos.library.read`；`media` 状态与事件；`MediaClock`、`LoadControl`、`jump`；存储权限路径 | JVM + 原生 Lua 测试；权限拒绝、迟到结果丢弃 |
| **P3 player 插件·音频（L）** | §5 的文件；扫描、文件名解析、队列、live 接管、歌词（同名 `.lrc` + LRCLIB）、封面链、11 种语言、首页卡片；`package_plugins.py` 默认列表加入 | Lua 单测、armv7 预算、`--validate-only` |
| **P4 SpectrumTap（M）** | §8；同步校准 | 帧时间戳 vs `currentPosition` 日志 |
| **P5 视频（M）** | 影院排布、字幕、保持活跃、格式错误提示 | 合成样片 |
| **P6 Navidrome（S–M，可选）** | 流 URL、封面、歌词。Subsonic 令牌认证 = `md5(密码 + 盐)`（官方 API 页已核对）；Lua 沙箱没有 md5，纯 Lua 实现即可，不加 OS 接口 | 需要先确认用户有 Navidrome |
| **P7 验收与文档（M）** | §11；记录放 `artifacts/player/`；本文改写成"交接与验收" | — |

## 11. 验收

**U 界面**：每个变体 × 横/竖 × 浅/深/夜间 × ENGAGED/AWAKE/AMBIENT 截图；长曲名、长歌词行（换行不裁）、RTL、11 种语言；变体切换和状态切换补间无跳变；点封面、点别处、点歌词行、拖进度条、拖音量条、翻页不被抢。
**A 音频**：24-bit FLAC（44.1/48kHz）与 MP3 都能放；连续 30 分钟不断流；暂停/续播/seek/上一首/下一首/随机/循环/队列跳转；换歌或所有者被替换时旧回调不乱写；音频焦点、拔耳机停播。
**L 歌词**：行切换与时间戳一致（日志实测偏差）；seek 后立刻跳行；双语、间奏、纯音乐、无歌词；GBK 与 UTF-8 的 `.lrc`；LRCLIB 的 404 / 503 / 超时。
**S 频谱**：帧时间戳与可听位置的差值在日志里 ≤100ms（我定的阈值，先量再改），再用 120BPM 节拍音频做人工比对；换页后线程停止；采样率/位深变化（flush）不崩。
**P 性能**：AWAKE / AMBIENT / ASLEEP 各一组（播放中、有无频谱）的单核 CPU、janky、PSS，对比 §2 基线；50 次切歌无泄漏，PSS 峰值 <350MB；视频 1080p30 的丢帧数与内存。
**V 视频**：合成 H.264 / HEVC / VP9 + SRT；不支持的编码给出可见错误。
**F 失败与安全**：404 流、坏文件、无读权限、断网、`library.read` 越界路径与超大文件、`media` 权限缺失。
**通用**：`./gradlew assembleDebug`、lint、`adb install -r`、APK 只有 `lib/armeabi-v7a/`、断网无 Server 冷启动 <3s、`package_plugins.py --validate-only`、`gen_android_strings.py --check`。

## 12. 风险

1. **频谱同步和 CPU 是最大风险**（§8）。同步靠"可听位置"这个未核对的前提。
2. **默认 `LoadControl` 对视频是 125MiB**，会直接撞内存预算（§2）。
3. **草稿过期且易失**：在 `/tmp`，且和现在的 `PluginRuntime` 已经分叉。
4. **本机音乐没有任何标签**：默认体验靠文件名 + 生成封面；歌词、真封面要靠在线查询，牵涉隐私与版权（§6.2）。
5. **LRCLIB 稳定性与条款**：已见一次 503；歌词数据许可没读到。
6. **24-bit FLAC 的解码/重采样 CPU 没测**（HAL 是 48kHz/16-bit）。
7. **seek/音量拖动与翻页手势冲突**（§2）：接口一定要先做。
8. **GBK 的 `.lrc`** 乱码风险。
9. **共享存储权限**：系统弹窗在 Kiosk 下是否可用未测。
10. **视频格式覆盖有限**（无 AC3/DTS）、4K 不可播。
11. **文档不一致**：`03` 的"严格同步"、"缓冲 ≈15s/8MB"；`04` §4.4 的封面 300×300 和控件布局会被本文取代。
12. **原型 ≠ 真机**：字体、渲染、帧率都不同；对比度是公式结果，不是设备实测。

## 13. 待讨论

| # | 问题 | 我的建议 |
|---|---|---|
| 1 | 是否解除媒体暂缓，按 P0→P7 推进？ | 是；先 P0–P1（假会话的纯 UI），再接引擎 |
| 2 | `player` 取代 `music`，一个插件管音频和视频？ | 是；别的插件的会话只显示，不做歌词/封面解析 |
| 3 | 新增 `nowplaying` 版式（OS 决定排布），而不是用 `split`/`stage` 拼？ | 新增。状态切换要补间、档位要改密度，这些是外观，归 OS（ADR-017） |
| 4 | 读 `/sdcard/Music` 需要 READ_EXTERNAL_STORAGE：系统弹窗（草稿）/ Device Owner 静默授权 / 只读应用私有目录（要把歌拷过去）？ | Device Owner 静默授权，先在真机试；声明权限需要你确认 |
| 5 | 在线歌词和封面（会发送曲目信息）默认开还是关？ | 关，设置里一键开；空状态里提示"在线查词未开启" |
| 6 | 音量条驱动系统 `STREAM_MUSIC`，播放器增益只用于闪避？ | 是 |
| 7 | 夜间不画封面？ | 是 |
| 8 | CPU 验收统一按单核口径，频谱默认 30fps？ | 是；P1 量完再定 |
| 9 | 视频范围：本地 + 渐进式 HTTP + 侧载字幕，不做 HLS/DASH；视频播放时不降到 AMBIENT？ | 是 |
| 10 | 频谱样式保留 条形 / 镜像 / VU 三种？ | 保留，VU 最费事，可放最后 |
| 11 | 你有 Navidrome 吗？ | 没有就跳过 P6 |

## 14. 需要同步的文档（尚未修改）

`06-plugins.md`（§4.1 词汇、§6.1 的 `playback` 事件、§6.2 的 `tos.media/tos.audio/tos.library.read`、§10 的 `music`→`player`）；`03-architecture.md`（§3.10 同步说法与缓冲、§3.6 版式表、§3.15 CPU 口径）；`04-ui-spec.md`（§4.4 重写，新增歌词/频谱/队列/影院规格）；`08-decisions.md`（新 ADR，并让 ADR-022 指向它）；`09-roadmap.md` M1 的 `music`；`14-handoff.md` §D/§G；`shared/schema/plugin/README.md`；`plugins/README.md`。

## 15. 索引与来源

| 要看什么 | 位置 |
|---|---|
| 原型与截图 | `docs/figures/player/prototype.html`、`*.png` |
| 引擎、队列、控件、视频组件 | `android/.../media/MediaEngine.kt`、`MediaQueue.kt`、`ui/MediaComponents.kt` |
| 手势与舞台 | `ui/ThinkStage.kt`、`ui/RenderComponent.kt`、`anim/Motion.kt` |
| 图片管线样板 | `image/`、`ui/ImageComponents.kt`、`21-gallery-handoff.md` |
| 接线草稿（易失） | `/tmp/thinkos-media-integration/` |
| 页面角色与 live | `core/DeckRouter.kt` |

外部来源（未标"读到原文"的只看了搜索摘要）：[Eversolo DMP-A6（HiFiReport，读到原文）](https://www.hifireport.com/eversolo-dmp-a6-review/)；[WiiM Ultra（Son-Vidéo，读到原文）](https://blog.son-video.com/en/2024/12/wiim-ultra-review-putting-the-ultra-versatile-audio-streamer-to-the-test/)；[StandBy 全屏播放（allthings.how）](https://allthings.how/how-to-view-music-player-in-full-screen-on-iphone-standby-mode/)；[iOS 17 评测（MacStories）](https://www.macstories.net/stories/ios-and-ipados-17-the-macstories-review/5/)；[Apple Music 歌词](https://support.apple.com/en-us/105076)；[Car Thing 社区提示](https://community.spotify.com/t5/Car-Thing/Tips-for-using-Car-Thing/m-p/5209893/highlight/true)；[MacThing](https://github.com/srcurran/MacThing)；[LRCLIB 文档](https://lrclib.net/docs.md)（读到）；[OpenSubsonic getLyricsBySongId](https://opensubsonic.netlify.app/docs/endpoints/getlyricsbysongid/)（读到）；[Subsonic API](http://www.subsonic.org/pages/api.jsp)（读到）；[Navidrome 外部歌词讨论](https://github.com/navidrome/navidrome/discussions/4193)（摘要）。
