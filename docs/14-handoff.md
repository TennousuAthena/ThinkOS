# 14 · 开发交接：目标、进度与下一步

> 写于 2026-10-30，交接时的真实状态。读完这份文档再读 `AGENTS.md`（硬性约束）、`08-decisions.md`（已定决策，不要重开）。
> 文中「已验证」指我在交接前亲自跑过并看到结果；「草案」指设计思路已想清楚但**没有评审、没有写进 ADR、没有实现**，接手人可以推翻。

> **接手续作（2026-10-30）**：本文件保留交接时快照。A 的日月/季节引擎、B 的位置设置与选择器、H 的目录安装现已实现；统一 JVM 测试、lint 和构建通过，真机验证正在补齐。当前实现见 ADR-019–021、`15-location.md`、`16-plugin-directory-validation.md`。C–G 的媒体/图片/组件/通知与四个内容插件仍须继续，目标范围不变。

> **用户最新指示（同日，优先于后面的原始待办顺序）**：暂不实现完整功能，仅测试 UI。当前验证原生组件、设置交互与模拟通知界面；媒体 Runtime/Lua/权限、图片轮播、内容插件数据接线和 HTTP 通知监听均暂缓。未落源码的媒体接线草稿在 `/tmp/thinkos-media-integration`，不是已实现功能。UI 记录见 `17-ui-validation.md`。

> **天气续作（同日）**：用户已明确解除天气数据接线暂缓，完成范围为 W0–W2；天气图标、卡片/详情、实时预报、缓存与恢复见 `20-weather-handoff.md` 的续作记录。媒体等其它暂缓项不受影响。

> **流媒体续作（2026-11-01）**：媒体暂缓已解除并完成：`MediaEngine` 接线、`tos.media.*`、`media` 状态与事件、API 7 的 `video` / `controls` / `seekbar`、HLS / DASH / RTSP、FFmpeg 音频扩展、系统插件 `player`（取代 `music`）。上文关于媒体“暂缓 / 未开始 / 接线草稿在 /tmp”的描述是当时的快照，现状与验收见 `27-streaming.md`、`08-decisions.md` ADR-030。

> **相册续作（同日）**：用户已解除相册与通用图片接口的暂缓，并补充要求实现微软每日一图。API 3 的 `image/carousel`、`tos.library.list`、通用网络图片/缓存和 `gallery` 已实现；默认 Bing，可选本地/混合，完整画面平移、不永久裁切。Server 下发/管理暂缓。媒体播放与 HTTP 通知仍暂缓。公开接口与验收见 `21-gallery-handoff.md`、`06-plugins.md`。

## 0. 接手后的第一小时

1. 读：`AGENTS.md` → `01-vision.md` → `03-architecture.md` → `06-plugins.md`（§11 设置、§12 Git 安装）→ `08-decisions.md`（ADR-013 到 018）→ 本文。
2. 跑一遍验证命令（§6），确认你的环境和我的结果一致：89 个 JVM 单元测试、18 个 Python 测试、原生 Lua 测试，全部通过。
3. `adb devices` 应看到 `HUA0BWUT device`；用 `tools/deploy.sh --no-build` 装当前 APK，长按首页进设置页，亲眼看一遍。
4. 从 §4 的 A 开始继续。不要先动 UI：天文引擎是纯函数、可单测，是最便宜的起点。

## 1. 产品目标（一句话和几条不变的原则）

**ThinkOS**：把一台已 root 的 Lenovo ThinkSmart View（Android 8.1 / API 27、armv7 32 位、800×1280、2 GB 内存、Cortex-A53）改造成桌面常亮的「智能摆件」操作系统。体验参照 iPhone「待机显示」（StandBy）。

用户反复确认过的原则（出处见 `08-decisions.md`）：

| 原则 | 含义 |
|---|---|
| 设备自主（ADR-015） | 设备端任何功能都不能以 Server 在线为前提；Server 只下发和管理，而且是可选的 |
| 一切皆插件（ADR-013） | OS 不实现任何具体功能；时钟、天气、相册、音乐、通知、首页、**设置页本身**都是插件 |
| 插件 = 声明式视图 + Lua 5.4（ADR-016） | Lua 只在插件线程执行；沙箱不开放 `io/os/package/debug`；能力只能通过 `tos.*` 取得 |
| StandBy 式交互（ADR-014） | 左右滑换页面，上下滑换变体；白天浅色、夜里深色，环境光自动切换；极暗时整屏变红 |
| **外观不开放接口（ADR-017）** | OS 只开放系统级和页面级**设置**；不做样式语言、表盘包、部件皮肤。用户想要别的外观，让 AI 写一个页面插件。新版式/组件按 `06-plugins.md` §4.1 加词汇 |
| Git 仓库分发插件（ADR-018） | tag = 版本，topic `thinkos-plugin`；设备端不引入 Git/JGit。只设计完成，属于 M2，尚未实现 |
| **最新指示（本阶段目标）** | OS 提供**功能接口**（音视频播放器、图片轮播器——要有切换动画，图片装不下时要有平移动画）、**常用组件库**（进度条等），以及常见功能的**通用接口**（表盘、时区本地化等）；插件只负责**解耦的数据层和显示层**。要把下表六个自带插件全部做完 |

自带插件（用户原话的表）：

| id | 提供 |
|---|---|
| `home` | 首页双栈小组件（纯声明，无进程） |
| `clock` | 时间卡片 + 全屏时钟页面（多种表盘） |
| `weather` | 天气卡片 + 详情页 |
| `gallery` | 相册轮播页面 + 预览卡片 |
| `music` | 正在播放页面 + 迷你播放卡片 + 播放指令 |
| `notify` | 外部 HTTP 通知转为屏幕通知 |

另有三项用户明确提出的要求，已有两项做完：**多语言（前 10 大语言）** ✅、**日期时间格式以系统设置为准** ✅、**日照/天文，位置可设到城市（区县）级，参考开源项目 Suntimes** ⏳（只开了头，见 §3）。

## 2. 硬性约束（摘要，原文在 `AGENTS.md`，违反会直接失败）

- 只能 armv7 ABI；新增依赖必须先确认 AAR/so 含该 ABI。`minSdk = targetSdk = 27`。
- 不用 WebView、不用 Compose、不在设备上跑本地大模型、不依赖 GMS/FCM/Home Assistant。
- **禁止的设备命令**：`adb root`、`adb disable-verity`、`adb remount`、直接写 `/system`。需要改设备系统层 → 停下，记录，交给人工。
- 设备必须能脱离 Server 运行；插件只能走 `tos.*`；可配置项一律走设置 schema，不要硬编码，也不要另建 SharedPreferences。
- 改协议/schema：先改 `shared/`，跑生成脚本，**不要手改** `PluginSchema.kt` / `plugin_generated.go`。
- 本机环境约定：下载来的文件按不可信处理，放在单独的新目录，脚本放在别处，读下载数据的 Python 一律 `python3 -I`。

## 3. 当前进度

### 3.1 总表

| 模块 | 状态 | 验证 |
|---|---|---|
| M0 设备端基底（Kiosk 外壳、状态模型、舞台、主题、注意力、插件运行时、`home`/`clock`） | ✅ 完成 | 真机，记录见 `12-m0-validation.md`、`13-pre-m1-hardening.md`。M0 里「遮光主题切换」「接近传感器唤醒」两项当时就未勾选，至今未补测 |
| M0.5 设置体系（SettingsStore、系统级 14 项、`tos.settings`、`form` 版式、`settings` 插件、长按入口、救援菜单重置） | ✅ 完成，**插件目录化除外** | 单元测试 + 真机走通：设置页、长按进入、插件级设置、时区选择器、覆盖值持久化。路线图里 M0.5 的验收框我**没有逐项勾**，需要接手人按 `09-roadmap.md` 逐项在真机上确认后再勾 |
| 时区引擎（自带 IANA tzdata 2026e + 自写 TZif/POSIX 解析） | ✅ | 419 个 zone、110,519 行黄金向量（由 CPython `zoneinfo` 独立生成）全部吻合；设备 ROM 自带 tzdata 是 2021a，已过期，所以必须自带 |
| 多语言（11 张表） | ✅ | 系统字符串 59 条 × 11 种语言；插件按 `i18n/<表>.json`；`tools/gen_android_strings.py --check` 校验键与占位符 |
| 命名格式（`@time`、`@dateFull`…）跟随系统语言与 12/24 小时 | ✅ | `FormatsTest`；真机 locale zh-CN、`time_12_24=24` |
| 世界时钟可调（4 个城市，可搜索，显示 DST） | ✅ | 真机 |
| **天文引擎** | ⏳ 刚开头，**未接线** | 见 §3.3 |
| Media3 依赖 | ⏳ 已加入，**没有任何代码使用** | 构建通过，APK 仍只含 armv7 的 `libtos.so`，无新增原生库 |
| 媒体引擎、图片轮播、组件库、位置设置、通知叠层 | ❌ 未开始（只有设计草案，见 §4） | — |
| 插件 `weather` | ✅ W0–W2 已落地 | 图标、卡片/详情、直连预报与缓存，详见 `20-weather-handoff.md` 续作验收 |
| 插件 `gallery` `music` `notify` | ❌ 完整功能暂缓 | 沿用用户 UI 阶段指示 |
| 插件目录化（`.tpk` → 目录 + `index.json`） | ❌ | 设备端仍用 `.tpk` 解包 |
| Server（Go） | 仅骨架 | M2，不在当前范围 |
| 文档 | ⚠️ 落后于代码 | 见 §7 |

### 3.2 已验证的构建状态（交接前最后一次）

- `./gradlew --offline testDebugUnitTest lintDebug assembleDebug`：成功。**89 个单元测试，0 失败**。
- `python3 -I -m unittest discover -s tools/tests`：18 个，OK。
- `android/app/src/testNative/run.sh`：全部 PASS。
- `app-debug.apk` 7,393,159 字节，`lib/` 下只有 armv7 的 `libtos.so`，`assets/plugins/` 里是 `home`、`clock`、`settings` 三个 `.tpk`。
- **设备上装的不是这个 APK**：设备上是 02:02 的构建（还没加 Media3）。两者行为一致（Media3 尚未被调用），但交接后请自己重新安装一次。

### 3.3 进行中：天文引擎（已写 2 个文件，未测试，未被任何代码引用）

位置：`android/app/src/main/java/moe/kiwi/thinkos/astro/`

- `Astro.kt`：`Place(lat, lon, elevation)`、`Horizontal`、儒略日、恒星时、黄赤交角、赤道/地平坐标换算、地平线俯角（海拔修正）。
- `Sun.kt`：太阳视位置（NOAA/Meeus 第 25 章，约 0.01° 精度）、太阳高度/方位、时角、上缘贴地平线时的中心高度（`−(34′ + 16′/日距 + 海拔修正)`）。

**没写的部分**与算法选择见 §4-A。这两个文件能编译（已通过构建），但没有测试、没有对照真值，**不要当成已验证的代码**。

## 4. 待办与设计草案（按建议顺序）

> 本节所有「草案」都是我在这轮工作中想清楚的思路，**没有被用户评审过**。落地时请先写 ADR（下一个空号是 ADR-019；代码里 `Formats.kt` 已经引用了不存在的 ADR-020，建议：019 时区数据来源、020 多语言与系统格式、021 位置与天文、022 媒体框架）。

### A. 天文引擎收尾（纯函数，最先做）

- **日出日落等**：以「当地日」为窗口，先求太阳中天（时角过零），再在中天前 12 小时内找上升穿越、后 12 小时内找下降穿越。穿越点用 10 分钟步长扫描符号变化 + 二分到 1 秒。没有穿越时按中天高度判断极昼/极夜。这样天然处理极地、夏令时日（23/25 小时）、时区与太阳时偏差很大的地区。
- **阈值**：地平线 `Sun.horizonAltitude`；民用/航海/天文曙暮光 −6°/−12°/−18°（取太阳中心，不含大气折射）；黄金时刻 −4°～+6°；蓝调时刻 −8°～−4°（与 Suntimes 一致）。
- **当地日边界**：对 `ZoneClock.fields(t).days` 做二分，得到「当天 00:00」（遇到 DST 空档就取过渡点）。
- **月亮**：Schlyter 轨道根数法（月球 6 个根数 + 经度 12 项、纬度 5 项、距离 2 项摄动，约 2′），再做视差改正得到站心高度；月出月落的目标高度 = `−(34′ + 半径)`；月相用日月黄经差，照亮比例按 Meeus 48.4 用相位角算；盈亏与 8 个月相名按 45° 分段。月龄和新月/满月/上下弦用根求解，不用平均朔望月。
- **二分二至**：Meeus 第 27 章（均值多项式 + 24 项周期修正），我是凭记忆写的表，**必须**用 PyEphem 的 `next_equinox/next_solstice` 对照后才能信。
- **ΔT** 先用常数 69.2 s（2020 年代），写进注释。
- **对照真值**：PyEphem 4.2.1（独立实现 libastro）。环境：`python3 -m venv /tmp/thinkos-venv/astro && /tmp/thinkos-venv/astro/bin/pip install ephem`（venv 在 /tmp，会丢，需要重建）。日出日落按 USNO 约定：`obs.pressure = 0; obs.horizon = '-0:34'; use_center=False`；曙暮光：`horizon='-6'`（等）、`use_center=True`。
- **黄金向量**：新写 `tools/gen_astro_golden.py` → `android/app/src/test/resources/astro-golden.tsv`，仿 `tools/gen_tz_golden.py`。地点覆盖：赤道、中纬南北、65°–70°、斯瓦尔巴（极昼极夜）、乌斯怀亚、奥克兰、基里巴斯（日期变更线）、乌鲁木齐（时区与太阳时差 2 小时以上）、加德满都（+5:45）；日期覆盖二分二至与随机日。**容差建议**：日出日落/中天 ≤ 90 s，曙暮光 ≤ 120 s，月出月落 ≤ 3 min，照亮比例 ≤ 1 个百分点，朔望 ≤ 10 min。如果月亮达不到，再补 Meeus 第 47 章的项，不要放宽容差来凑。
- **对外形态**：`SunDay`（日出、日落、中天、日长、各曙暮光、黄金/蓝调、`polar` 标志、最大高度、与昨日日长之差）、`MoonDay`、`MoonPhase`。

### B. 位置设置与地点搜索

- **系统设置新增**（走 `shared/schema/settings/system.json`，11 种语言的标签同步加，`tools/gen_android_strings.py --check` 要过）：`location.mode`（auto/manual）、`location.name`、`location.latitude`、`location.longitude`、`location.elevation`。`auto` 取设备时区对应城市的坐标（`zones.tsv` 里已有经纬度，`tos.tz.info` 已暴露）。设备没有 GPS，不要假装有。
- **城市（区县）级搜索**：Open-Meteo Geocoding API（`geocoding-api.open-meteo.com/v1/search`，基于 GeoNames，返回 admin1–admin3 即省/市/区县，带坐标、海拔、时区、`language` 参数）。无 API Key。需要先在设备上验证 TLS 能通，并在文档里注明服务条款和限流。离线或失败时回退到手填经纬度——设备自主原则不能因为搜索服务不可用而破坏。
- **选择器 UI**：仿 `ui/form/TimezonePicker.kt`（搜索框 + 结果列表）。待定问题：`SettingField` 目前只有 bool/choice/number/text 四种类型，选一个地点要同时写四个字段。草案是给 `choice` 加 `source: "place"`，选择器在 `form.change` 里一次性提交，由 `settings` 插件拆写成多个键；另一条路是新增 `place` 类型（改 schema、校验、Go 生成、测试向量，工作量大）。
- **插件接口**：权限 `location`；`tos.location.get()` → `{name, latitude, longitude, elevation, timezone, source}`；`tos.astro.sun(ms[, place])`、`tos.astro.moon(ms[, place])`、`tos.astro.seasons(year)`。绘制日光弧的组件**不需要**权限（只显示派生结果，不暴露坐标）。

### C. 组件库与版式（OS 提供功能，插件只填数据）

现有词汇：版式 `stacks`、`focus`、`glance`、`form`；组件 `text`、`icon`、`clock`、`form`。

草案新增：

| 组件 | 要点 |
|---|---|
| `progress` | 条形/圆环；`value`、`max`；可绑定 `{{media.progress}}` |
| `chart` | 折线/柱状/面积；数值数组，可高亮一个点（天气逐小时） |
| `list` | 行：图标、标题、副标题、尾部文本、可选迷你进度条；数据来自状态里的数组，行模板用 `{{字段}}` 绑定每个元素 |
| `image` | `src`（`file://`、`/sdcard/…`、`https://`、`embedded:<音频路径>` 取内嵌封面）；`fit`；解码用降采样 + LRU，长边限 4096 |
| `carousel` | 见 §4-E |
| `video` | TextureView 承载播放画面 |
| `controls` | 上一首/播放暂停/下一首，**点击直接由 OS 调用媒体引擎**，不经过 Lua |
| `daylight` | 日光弧：日出日落、当前太阳位置、曙暮光色带；用 OS 位置设置，插件可用 `latitude/longitude` 覆盖 |
| `moon` | 月相圆盘 + 照亮比例；南半球镜像 |
| 所有组件 | 可选 `tap: "事件名"` → 以 `tap` 事件发给所属插件 |

版式草案：`split`（槽位 `lead` + `main`，横屏左右约 40/60，竖屏上下）、`stage`（`stage` 槽位全出血，`over`/`under` 叠在上面，给相册和视频用）；`focus` 的槽位允许放「组件组」；可伸缩组件加 `weight`。`PageView.rectangle()` 按槽位角色算矩形；`ComponentGroup.onMeasure` 目前对 `clock`/`form` 平分剩余高度，其它组件按内容高度，需要推广为按 `weight` 分配。

**契约改动清单**：`shared/schema/plugin/layouts.json`（新组件、新版式、`x-thinkos-slot-roles`）、`manifest.json`（权限枚举见下）、重新生成 Kotlin/Go、给 `tools/tests/test_plugin_packages.py` 和 `PluginSchemaTest` 加用例、`docs/06-plugins.md` §4 与 `shared/schema/plugin/README.md`。

**权限枚举调整草案**：现有 `audio`、`photos` 改名为 `media`（播放）和 `library`（列出媒体文件）；新增 `location`、`listen`；`maxItems` 7 → 10。尚无内容插件使用旧名，没有迁移成本。

### D. 媒体引擎

- 位置：新包 `media/`。ExoPlayer 在主线程；OS 持有队列；`queue.end` 之类事件发给会话所有者插件。
- **状态怎么到界面**：仿 `config` 的做法，`PluginRuntime.publishState` 往有 `media` 权限的插件快照里加保留键 `media`：`{status, kind, index, count, title, artist, album, art, position, duration, progress, volume, shuffle, repeat}`。position 每秒由 OS 更新，不经过 Lua；视图直接写 `{{media.title}}`、`"value": "{{media.progress}}"`。同一时刻只有一个会话所有者，换人时通知前一个。`state.set` 要像拒绝 `config` 一样拒绝 `media`。
- **Lua API 草案**：`tos.media.load(queue, {index, shuffle, repeat})`、`play/pause/toggle/next/prev/seek(ms)/volume(v)/stop`；队列元素 `{url, title, artist, album, art}`。
- **网络**：数据源用 `media3-datasource-okhttp`（已在依赖里）。要复用 `net/LocalNetworkPolicy` 的规则（明文 HTTP 只允许私网地址）：自定义 DNS 记录校验结果，network interceptor 检查实际连上的地址，`followSslRedirects(false)` 防 https→http 降级。`PluginHttp` 用的是 `HttpURLConnection`，所以这是第二套 HTTP 栈，注意别让两处的策略漂移。
- **频谱**：`TeeAudioProcessor` 取 PCM 做 FFT，不要申请 `RECORD_AUDIO`。可后做。
- **本地文件**：设备 `/sdcard/Music` 有 4 个 mp3/flac，`/sdcard/DCIM`、`/sdcard/Pictures` 存在。API 27 读外部存储是运行时危险权限（`READ_EXTERNAL_STORAGE`）：应用自己的外部目录不需要；其它目录需要在界面上请求，或开发时用 `adb shell pm grant`（这是应用层权限，不在禁止清单里，但产品上必须有自己的申请流程）。`tos.library.list{kind, dir}` 在 IO 线程枚举并读元数据（`MediaMetadataRetriever`），异步让出，和 `tos.http` 同一机制。
- **依赖约束**：Media3 `1.9.2` 是 `compileSdk 35` 能用的最高线，1.10+ 要 36；1.8.0 的 AAR 没有 `jni/`。`android.useAndroidX` 已改为 `true`，会引入传递依赖，请看一眼 APK 大小和方法数。若最终不用，就把依赖和这项配置一起撤掉。

### E. 图片轮播（用户点名的需求）

- 切换动画：淡入淡出、平推、缩放淡入、随机；时长约 800 ms。
- **图片装不下**时的平移（用户点名）：算裁切比例 `c = 1 − min(图比/屏比, 屏比/图比)`。`c ≤ 0.2` → 铺满裁切 + 轻微 Ken Burns；否则 → 沿被裁掉的那一轴匀速平移，使整张图在停留时间内被扫过一遍；关闭平移时退回 contain。`fit` 取 `cover | contain | smart`。
- 内存：同时最多持有当前、下一张（预取）、上一张三张位图；全屏位图约 4 MB；全景图长边限 4096。验收：连续切换 50 张不 OOM，`dumpsys meminfo` 峰值 < 350 MB（`09-roadmap.md` M1）。
- 帧调度：用现有 `MotionClock` 的 tick 驱动，并设置 `FrameGovernor.activeAnimations`，静止时降到 1 fps；`ASLEEP` 下不播动画。

### F. 通知叠层与外部 HTTP 通知

- `tos.alert{title, text, icon, level, duration}`（权限 `alert`，枚举里已有）。`OverlayState` 现在只有一个 `message`，需要扩展：用户在看（AWAKE/ENGAGED）时顶部横幅，AMBIENT/ASLEEP 时大图标接管（StandBy 的做法），`urgent` 级别可唤醒屏幕。
- 「外部 HTTP 通知」草案分两种来源，都在 `notify` 插件里：
  1. **推**：OS 提供 `tos.http.listen{port, token}`（权限 `listen`），内置极小 HTTP 接收端——必须带 Bearer token、默认关闭、只接受私网/回环对端（复用 `LocalNetworkPolicy`）、请求体 ≤ 16 KiB、并发 ≤ 4、5 秒读超时；请求以 `http.request` 事件交给 Lua，Lua 返回状态码。这是一个会在局域网里监听的端口，**写 ADR 并让用户确认后再做**。
  2. **拉**：轮询 ntfy 兼容地址（`?poll=1&since=`），用现有 `tos.http.get` 即可，不开端口。
- 与 Server 的关系：M2 的 `POST /notify` 中转会走实时通道，不是这里的内容；设备必须在没有 Server 时也能收通知。

### G. 六个自带插件

- `weather`：卡片（图标、温度、状况、地点）+ 详情页（逐小时 `chart`、逐日 `list`、`daylight`、`moon`）。数据源草案 Open-Meteo `/v1/forecast`（无 Key、全球）。设置：地点（用系统位置，可覆盖）、温度单位、刷新间隔。断网保留最后数据并显示更新时间（M1 验收）。需要一批天气图标（`IconView` 目前只有 calendar/moon/clock/sun 四个）。
- `gallery`：`stage` 版式 + `carousel`；预览卡片用 `image`。设置：来源目录/URL 列表、间隔、转场、`fit`、平移开关、随机。
- `music`：`split` 版式（封面 + 信息/进度/控制）、迷你卡片（`glance`，绑定 `{{media.*}}`）；本地库 + 可配置的在线电台/URL；「播放指令」指插件对外暴露的 `play/pause/next` 等事件，供首页、手势、将来的 Server 调用。
- `notify`：见 §F。
- `clock`：已有 digital/analog/solar/world/float。**solar 表盘要改用真实日出日落**（目前是按 24 小时线性摆的假弧线）。`ClockView` 里有 `solar()` 的绘制代码，抽成与 `daylight` 共用。
- `home`：更新为引用 `weather.now`、`music.now`、`gallery.preview` 等卡片（`stacks` 左右两栈）。
- 每个插件：11 种语言的 `i18n/*.json`、`settings` 声明、权限最小化；`tools/package_plugins.py` 默认列表目前是 `home clock settings`，要加上新插件。

### H. 插件目录化（M0.5 剩余）

按 `09-roadmap.md` M0.5：设备端安装器改为目录 + 文件清单（摘要 = 清单摘要）；APK 内置插件改为目录 + `index.json`；`plugin-push.sh` 推目录；删除 `PluginRuntime.unpack` 里的 zip 解包；`package_plugins.py` 改为校验并生成索引。改之前先读 `PluginRuntime.kt` 的 `start/unpack/scanDev/persistInstalled/cleanupVersions`，那里有热更新、回滚、隔离的细节，别破坏。

### I. 文档补齐

新 ADR（见上）；`03-architecture.md`（astro/media/tz/i18n 模块）、`04-ui-spec.md`（新组件与版式）、`06-plugins.md`（新 `tos.*` API、权限表、事件表）、`02-device.md`（§7）、`09-roadmap.md`（勾选与调整）、`shared/schema/plugin/README.md`；新增一份本轮的真机验证记录（仿 `12-`、`13-`）。

## 5. 现有 `tos.*` 与词汇（接手时的事实）

- **API**：`tos.config`（只读）、`tos.json`、`tos.on(name, fn)`、`tos.state.set/get`、`tos.store.get/set`、`tos.time.now/format(ms, fmt, tz)`、`tos.tz.home/info`、`tos.locale()`、`tos.timer.after/every/cancel/sleep`、`tos.http.get/post`（权限 `network`）、`tos.settings.*`（权限 `system.settings`，仅 APK 内置插件）、`tos.i18n.t`、`tos.page.focus/release`、`tos.log.*`、`require`。
- **事件**：`start`、`stop`、`config`、`locale`、`page.enter`、`attention`、`variant`、`presence`/`gesture`（权限 `presence`）、`form.change`/`form.reset`。
- **预算**：单次 50 ms CPU、200 ms 墙钟、500 万条指令；每插件 4 MiB、总计 24 MiB；受信任的主机调用有 100 ms 的 CPU 信用上限。
- **保留状态键**：`config`（有效设置）；`media` 为草案。
- **主题与注意力阈值、方向、亮度上限**全部来自系统设置（`SystemValues`），不要再硬编码。

## 6. 环境与命令（均已验证）

```bash
# 构建与全部检查（Gradle 缓存已含 Media3，可离线；首次解析依赖需要联网到 dl.google.com）
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
cd android && ./gradlew --offline testDebugUnitTest lintDebug assembleDebug
cd .. && python3 -I -m unittest discover -s tools/tests
android/app/src/testNative/run.sh

# 安装、启动、日志、截屏
tools/deploy.sh --no-build        # 或：adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n moe.kiwi.thinkos/.MainActivity
adb logcat -s ThinkOS:V AndroidRuntime:E
tools/screenshot.sh

# 插件热更新（仅 debug 构建）与性能
tools/plugin-push.sh plugins/clock
tools/bench.sh 600 /tmp/thinkos-bench

# 契约与资源生成：前三条已挂在 Gradle preBuild，改 schema 后也可单独跑；后两条只能手动
python3 shared/gen/gen_kotlin.py && python3 shared/gen/gen_go.py
python3 tools/package_plugins.py --validate-only
python3 tools/gen_android_strings.py --check      # 改了 shared/i18n/system/* 后去掉 --check 重新生成 strings.xml
python3 tools/vendor_tzdata.py                    # 更新 tzdata；默认读本机 /usr/share/zoneinfo（版本取决于本机），见 --help；随后重跑 tools/gen_tz_golden.py
```

## 7. 文档与现实不一致的地方（接手后请修）

- `03`–`11` 号文档**没有**反映：自带 tzdata、11 种语言与命名格式、设置页的时区选择器、Media3、天文。`Formats.kt` 引用了不存在的 ADR-020。
- `02-device.md` 缺：设备上**有**输入法（AOSP LatinIME，文本类设置可以直接输入，原文第 118 行写的是「尚未确认」）；有阿拉伯语/孟加拉语/天城文/CJK/泰文/希伯来文字体；ROM 的 tzdata 是 2021a。
- `12-m0-validation.md` 写「M0 不申请 INTERNET 权限」是历史记录；现在 `AndroidManifest.xml` 已声明 `INTERNET`（`tos.http` 需要）。
- `shared/schema/plugin/README.md` 仍说 M0 只接受 `stacks/focus`、`text/icon/clock`，没提 `form`。

## 8. 已知问题与技术债

- 系统设置页标题显示「系统」，应为「系统设置」。
- 数字滑块行偏高。
- 文档里还有 ASCII 版式图。
- 路线图 M0 两项传感器验收（遮光切主题、接近唤醒）从未补测。
- 两套 HTTP 栈（`PluginHttp` 的 `HttpURLConnection` 与将来的 OkHttp）的私网策略要保持一致。
- `PluginRuntime.kt` 已近 700 行且还会继续长（媒体、位置、监听、告警都要加宿主方法）。建议先把 `host()` 的分支按能力拆成小对象，再往里加。
- 设备没有 RTC：开机后时间在年份 < 2020 时界面显示 `--:--`，新功能（日出日落、天气「更新于」）也要遵守，见 `10-known-issues.md` §2。

## 9. 验收口径（提交前自查，来自 `AGENTS.md`）

1. `./gradlew assembleDebug` 通过；2. `adb install -r` 成功；3. 设备冷启动 < 3 s（**断网、无 Server**）；4. 待机界面连续 10 分钟 `gfxinfo` 无掉帧告警；5. 不引入新的非 armv7 依赖。
M1 另有：切换 50 张照片不 OOM（峰值 < 350 MB）；播放 30 分钟不断流，播放 + 频谱 CPU < 25%、帧率 ≥ 50 fps；断网后天气卡片保留最后数据；所有插件 Lua 内存合计 < 24 MB。

## 10. 代码地图

```
android/app/src/main/java/moe/kiwi/thinkos/
  core/        DeviceState（四条轴、注意力/主题策略）、DeckRouter（翻页、变体、设置入口）
  plugin/      PluginRuntime（生命周期、宿主方法、热更新）、LuaBootstrap（tos.* 的 Lua 侧）、PluginSchema（生成，勿改）
  settings/    SettingField、SettingsStore、SettingsDescriber、SystemSettings
  tz/ i18n/    时区引擎与名称；语言链、复数、字符串表、命名格式
  astro/       Astro.kt、Sun.kt（进行中）
  net/         PluginHttp、LocalNetworkPolicy
  ui/          ThinkStage（手势、四层）、PageView/ComponentGroup（版式与组件）、Binding（模板）、ClockView、IconView、form/*
  vision/      SensorController（环境光、接近）
  RuntimeHost.kt  进程级单例：设置、插件运行时、路由、传感器
android/app/src/main/cpp/    Lua 5.4.9 + yyjson + JNI 桥（tos_bridge.c）
android/app/src/testNative/  原生测试（run.sh）
shared/{schema,gen,i18n,tzdata,protocol}/   契约与生成脚本
plugins/{home,clock,settings}/  已有；{weather,gallery,music,notify}/ 空目录
tools/       deploy / plugin-push / bench / screenshot / package_plugins / vendor_tzdata / gen_*_golden
```

2026-10-31 后续：API 4 磁贴开发与验收集中记录在 `24-tiles-validation.md`；播放器仍是 API 5 / ADR-026 提案。ThinkSmart View 固定横屏使用 REVERSE_LANDSCAPE=8，旧开发命令 0 已统一映射。
