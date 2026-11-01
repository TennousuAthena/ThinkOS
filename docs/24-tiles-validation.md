# 24 · 磁贴实现与真机验收

设备：Lenovo ThinkSmart View，Android 8.1 / API 27 / armeabi-v7a。2026-10-31 开始验收；按用户要求使用无线 ADB `192.168.3.167:5555`。基线 `31ae3dc`，开发分支 `feat/tiles`。

## 已确认的设计修订

- Windows Phone / Windows 8.1 Metro 风格：直角、平面纯色、基础单元正方形、底部标签、大号轻字重数字；不分发额外字体。
- API 4 与 ADR-025 用于磁贴；用户追加的原生 Aero 使用 API 5 / ADR-026；播放器仍是未实现的 API 6 / ADR-027 提案。
- 夜间红色矩阵会使中灰底原有黑白前景不够清楚。现在按合成后的颜色选前景；必要时仅夜间补偿底色到最近的可读亮/暗端。白天浅/深主题保留插件原色。JVM 对 4096 色 × 5 个夜间过渡阶段检查 ≥4.5:1。
- 无线验收不能关闭 Wi-Fi；离线场景需保留 ADB 局域网、阻断应用外网、无 Server。

## 执行状态

| 验收 | 状态 | 证据 |
|---|---|---|
| T0 | 通过 | 既有改动提交 `31ae3dc`，然后创建 feat/tiles；当时 git status 干净 |
| A1 契约 | 通过 | `artifacts/tiles/a1-*`；生成器重复输出一致；Python/Go/JVM 拒绝 API 1–3 使用 tile/tiles；颜色、content、size 的边界测试 |
| A2 纯逻辑 | 通过 | `artifacts/tiles/a2-jvm.log`；随机排布、镜像、不重叠、不越界、溢出丢弃、4096 色对比度；夜间补偿测试见 t3-jvm |
| A3–A5 | 通过 | 最终 APK 的 `artifacts/ui-validation/tiles/report.json`；6 组 × 2 页面、4 语言、12 图标、三种色源、设置页改色、原 View 平滑动画、点击/长按/横纵手势 |
| A6 翻面与设置 | 通过 | `artifacts/ui-validation/tiles-live/report.json`，真机单调度器、间隔、关闭即取消、AMBIENT/离页、恢复、空面与减少动画；227 个 JVM 测试 |
| A7 插件 | 通过 | `artifacts/ui-validation/tiles-plugins/`，目录热加载、跨插件设置、移除与恢复 |
| A8 性能 | 通过 | `artifacts/tiles/performance-final/report.json`；两轮各 600 秒、完整帧覆盖、无输入/告警、离线冷启动 3 次 |
| A9 回归 | 通过 | `artifacts/tiles/regression/report.json` 三套旧 UI 全通过；API 1 / 2 探针与 API 3 Lua/图片探针；仅 armeabi-v7a，构建/安装/lint 通过 |

Go 目录此前只有生成物和测试，没有 go.mod。已补与仓库约定一致的 `module thinkos-server`，无第三方依赖，原计划命令现在可直接运行。

默认 deck 顺序来自 `PluginRuntime.publishCatalog`：home 在前、sleep 次之、其它页面按 key 排序，settings 不参与横向导航。磁贴页作为普通插件加入，不改变 home 角色。

A7 的天气是 APK 自带插件：删除开发覆盖目录会按既有逻辑回退工厂版本，不会卸载自带插件。因此测试在运行时工作线程模拟 catalog 移除/恢复 weather，走真实的 publishCatalog → PageView.update 路径；另用目录推送验证 tiles 热加载。该测试没有新增插件私有 API，也没有为 Lua 开后门。

## 开发中修复

- 横屏方向：用户确认本机正确摆放时，Android 默认 landscape 会倒置。固定横屏改为 REVERSE_LANDSCAPE=8，旧 debug 值 0 统一映射为 8；真机 SurfaceOrientation=3。无系统分区或传感器修改。
- 冷启动：无线 ADB 的轮询开销曾导致 host 计时误报；改读设备 ActivityManager 的 Fully drawn 时长。当前一次 Activity 897ms、完整首帧 2531ms（应用 IPv4/IPv6 被拒绝，无 Server，Wi-Fi 保持连接）。另外批量落盘启动安装记录，避免逐插件 fsync 和清理。
- schema oneOf 用 required/const 预筛不可能的分支，避免大量异常用于匹配；仍遍历所有可能分支并检查唯一性。正则缓存，生成器是唯一改动源。
- 旧 UI 脚本的主题自动切换受 120 秒防抖影响，UIAutomator 也可能等不到动画空闲。debug theme 现在可稳定保持并显式清除；组件树读取增加重试/暂时 AMBIENT。正式主题逻辑与测试用 DEBUG 入口分开。

性能口径：单帧绘制用 framestats 的 DrawStart→SyncQueued（≤12ms），同时记录完整帧耗时。gfxinfo 掉帧率沿用仓库 weather/gallery 已采用的 <5% 门槛，并要求采样中当前进程 Skipped-frames / Davey / 崩溃 / 预算告警为 0；不把“没有告警”写成零个 janky frame。原始帧记录、janky 数与 PSS/Lua 峰值均保留。

## 最终软件检查

- Python：34 项；JVM：227 项；Go protocol：通过；Lua / JNI / 24MiB 预算、gallery 与 Bing 原生测试：通过。lintDebug 与 assembleDebug 通过。
- APK 唯一 so 为 `lib/armeabi-v7a/libtos.so`；minSdk / targetSdk 保持 27；没有新增生产依赖。
- `component_ui.py`、`weather_ui.py --wireless-offline`、`settings_ui.py` 均通过。天气三次离线冷启动 Activity 822 / 807 / 776ms，完整首帧 2600 / 2590 / 2560ms，保留 Fahrenheit 配置与最后缓存数据。
- 现有 home 的 ambient 角色在 NIGHT 会切到 sleep 时钟，原回归脚本误以为夜间仍可翻入 home；已修正测试为核对既有夜间策略，未改变产品行为。新增 tiles 没有 ambient 角色，夜间可正常查看。
- 旧脚本假设 settings 探针下一页就是 home；新增 tiles 后该假设失效，回归现在明确返回 home 再测设置归属。
- 视觉矩阵已逐图检查横/竖屏的浅、深、红色夜间；文本边界同时由真机 View 树断言。ar 网格与内容镜像，hi 字形上下边界完整。
- 动画测试显式设倍率 1，结束后按原始值恢复；缺省键使用 delete 恢复，不写入字面量 null。

### 性能预采样与修正

`artifacts/tiles/performance-initial/` 为提前中止的预采样，不计入完整 10 分钟验收。一次阶段读数为 1243 帧中 90 janky（7.24%）；DrawStart→SyncQueued 最大约 5.07ms，但部分 PerformTraversalsStart→DrawStart 达 22ms。定位到颜色绑定未改变文字时仍 setText/requestLayout，以及每次测量重新设置自动字号。

修正后：只有文字或图标有实际变化才改对应 View；自动字号只在声明/尺寸变动时设置；隐藏的文字不参与实际测量；角标也避免重复赋值。图标仅在 accent 变化时重绘，并在页面其它动画升至 60fps 时把天气图标自身保持在 30fps。后续重新跑完整 UI 与两轮 600 秒性能记录。


### 复核后性能修复与 Aero

- `performance-before-review/builtin` 在约 362 秒发生输入并进入 gallery.slideshow，原十分钟 builtin 数据无效；不裁去切页帧冒充完整磁贴验收。输入来源未知。切页路径会同步构建邻页，输入窗口的长帧作为独立交互问题保留；仅凭 framestats 不能排除同时发生的 GC 或调度等待。
- 受控短测：cloud 79.47% / rain 84.11% jank；分离 cloud/drop 硬件属性层后仍 60.63%；完整帧图集后 7.34%。方法采样确认每四秒切换黑白前景时 TextView 重复绘制引起 UI draw 尖峰，增加有界 alpha 字形缓存后 draw 回到 5.19ms。
- 进一步把图集 sprite 直接挂到 IconView，移除 fallback 层级，并完成最终 64dp 小磁贴图形与填充式 glyph。不同早期候选的图形尺寸/翻面视觉有变化，因此不把跨候选差值归因于单个改动；最终候选始终保留 30fps 天气、完整纵向翻面和同一原始 fixture。最终 60 秒预检为 2073 帧、64 janky（3.087%）、max draw 4.001ms、PSS 137.632MiB、Lua 0.315MiB；帧覆盖率 100%、输入与告警均 0。预检不能替代完整 600 秒。
- 最终 APK 三次应用网络隔离冷启动：完整内容 2733 / 2641 / 2687ms；Activity 898 / 843 / 900ms。无线 ADB 保持连接，IPv4/IPv6 应用出站被拒绝，无 Server。证据见 `artifacts/tiles/cold-start-final/`。
- Aero 增量验收：`artifacts/ui-validation/aero/report.json` 通过 6 组横竖屏/主题、4 语言、原生开始菜单打开设置、窗口打开详情、所属插件长按与横滑切页。夜间标题采用合成后黑白前景，照片完整 contain，署名在桌面可占四行。普通页面不变。
- 核图发现纯图标 small 曾误进入 all-empty 文本回退，已修复并强化断言：12 张磁贴内真实 IconView 均可见，截图显示图形而不是名称；没有取消真正空面的 label 回退。
- 基准现校验 PID、page、attention、theme、触摸计数和导航代数；出现输入帧或离开场景立即判无效。每秒取 120 帧环形缓冲，内存探针前后补抓；最终总数与最后一次 framestats 来自同一 dump。保留有效非零 Flags 帧及最大值，记录覆盖缺口，不删异常值。meminfo --local 减少应用侧探针干扰，但探针仍如实保留在数据里。


## 最终 A8：两轮有效的十分钟

两轮磁贴测试 APK SHA-256：`dcd8b8616ed55d6d653094bcdba66eb9e1969b17a2a00ac8f1995c501795c28a`，完整信息保存在 `performance-final/apk.json`。应用出站 IPv4/IPv6 均被拒绝、无 Server；无线 ADB 保持连接。场景为 LANDSCAPE / DARK / AWAKE，开始前经过 AMBIENT→AWAKE，翻面与天气动画开启。所有周期变色、翻面、内存探针及有效非零 Flags 帧均保留。

| 指标 | 内置时钟/天气/相册磁贴 | 16 张同屏 |
|---|---:|---:|
| 时长 | 600.62s | 601.03s |
| 总帧 | 10976 | 20533 |
| Janky | 309 / 2.815% | 573 / 2.791% |
| 最长 UI 绘制 | 11.355ms | 6.678ms |
| 最长完整帧 | 79.228ms | 42.327ms |
| 帧覆盖率 | 100% | 100% |
| PSS 峰值 | 100.720MiB | 158.003MiB |
| Lua 峰值 | 0.296MiB | 0.348MiB |
| 进程重启 / 输入帧 / 帧告警 | 0 / 0 / 0 | 0 / 0 / 0 |

单帧绘制验收继续按 `DrawStart→SyncQueued ≤12ms`；完整帧耗时含调度、提交和交换，列出其最大值而不冒称每帧都在 12ms 内。janky 均低于 5%，不是零 jank。采样未发生场景偏离、输入或 PID 变化，退出时设置与网络规则均恢复。


### Aero 额外性能检查

最初 Aero 静态页面在低频刷新时合成开销偏高，诊断记录保留于 performance-aero / probe-aero-*，中断或短测均不算十分钟通过。后续只调整 Aero 渲染路径：静态文字/窗口后台栅格化，真实图片/图标仍为原生 View；模拟钟使用窄硬件指针，由 RenderThread 驱动可见扫秒。Tiles 的网格、颜色、翻面与绘制路径未改变。

最终短测为 4.356% janky、最长 UI 绘制 3.963ms、PSS 70.048MiB；完整 Aero 600 秒及最终回归结果见后续记录。缓存版 6 组主题/方向、4 语言、原生交互与可见指针运动断言已全部通过。


## 最终 Aero 十分钟

新版 APK SHA-256：`e66a8877d7e19fff7e849d2a47668c3e2f4b8f457c59f27f1f5435c0836fcb3e`。新增改动只涉及 Aero 字形后台缓存、静态窗体和真实指针合成，普通磁贴的绘制路径未变。

- DARK / LANDSCAPE / AWAKE，离线、无 Server，经 AMBIENT→AWAKE；601.21 秒，35718 帧。
- 726 janky（2.033%）；最长 UI 绘制 11.542ms；完整帧最大 35.358ms。
- PSS 峰值 73.415MiB，Lua 0.295MiB；PID 不变、输入帧和告警均为 0。
- 去重有效帧 35717，覆盖率 99.99720%；最终 header 与帧记录相差 1 帧，如实保留计数差，没有裁去任何有效异常帧。
- 原始结果：`artifacts/tiles/performance-aero-verified/report.json`。此前 performance-aero-final 的 13.378ms 分钟文字尖峰不通过，未挪作成功数据；任务栏字形后台栅格化后重新跑完整十分钟。


## 原始数据归档

逐帧和内存原始文本完整保存在 `artifacts/tiles/performance-raw.tar.gz`，每个文件的 SHA-256 与路径列在 `performance-raw-index.json`。本地未压缩副本保留以便直接分析，Git 收入压缩包而非数千份重复缓冲快照；没有丢弃失败候选或异常帧。可从仓库根运行 `tar -xzf artifacts/tiles/performance-raw.tar.gz` 恢复原路径。


## 最终回归与交付状态

- `artifacts/tiles/final-summary.json` 的 complete=true：A1–A9 与 Aero 增量检查已完成。
- 最终包离线完整冷启动 2696 / 2705 / 2672ms（Activity 829 / 899 / 853ms）；应用出站被阻断、无 Server、无线 ADB 保持可用，设置与防火墙规则恢复。
- 组件、天气、设置三套回归均通过，API 1–3 页面/插件仍可运行，最后数据与用户设置在断网冷启动后保留。组件脚本曾因 logcat 环形缓冲滚动而误判已到达 Lua 的点击；改按最后日志标记取增量后，真实点击/滚动/翻页/方向/主题全部重跑通过，未改变产品行为。
- 最终软件检查：Python 34 项、JVM 227 项、Go protocol 通过；生成物重复生成一致，lintDebug 和 assembleDebug/安装成功。唯一 native so 是 lib/armeabi-v7a/libtos.so，minSdk/targetSdk 均为 27，无新增生产依赖。
- 磁贴角标/名称固定，图形为 OS 实心/加粗家族；纯图标小磁贴显示真实图形；颜色绑定平滑更新同一 View。Aero 为独立可切换原生页面，普通 home/ambient/sleep 角色沿用。

实现计划 T0–T6 全部完成。新接口与设计边界见 ADR-025 / ADR-026、`25-windows-design.md`；播放器仍为后续 API 6 / ADR-027 提案。

## Start 屏重设计（ADR-028，2026-10-31）

设备与方式同上：Lenovo ThinkSmart View，无线 ADB `192.168.3.167:5555`。概念图为 `artifacts/tiles/concept/`，用户确认后实现；真机截图见 `artifacts/tiles/regress/`、`artifacts/tiles/photo-device/`。

**实现范围**：8×6 / 6×8 网格与标题栏；OS 固定背景（靛蓝 / 深青 / 酒红，`tiles.background`）；白字优先；磁贴版式重写（图标、数值、名称栏，宽磁贴的 `detail` 与 `content`）；API 6 的 `faces[].content` 与磁贴全幅 `image`；实心的时钟 / 日历 / 相册 / 音乐图标与月相图标；按下倾斜、页面入场、`tos.tile.animate`；新增 `clock.date` / `clock.moon` / `clock.sunrise` 与 `weather.forecast` / `humidity` / `wind` / `rain` 共 7 张磁贴，开始页共 10 张。

**自动化检查**（均在真机或 JVM 上实际运行）：

| 项 | 结果 |
|---|---|
| Kotlin 单元测试 | 245 项通过，含 8×6 / 6×8 填满 48 格、标题栏不与磁贴相交、白字优先与夜间对比度 |
| Python 包校验 / Go 协议 / 相册 Lua | 通过；`faces[].content`、磁贴 `image`、槽位 widget 的 API 6 门槛三端一致 |
| `tile_ui.py` 基础 / `--plugins` / `--live` / `--aero` | 11 / 5 / 6 / 3 项通过（`--plugins` 的磁贴数 3→10，已更新） |
| `tile_ui.py --motion`（新） | 6 项通过：按下倾斜方向与回弹、舞台触摸只倾斜被触的磁贴且取消不打开页面、页面入场、插件 pulse / nudge / flip 与冷却、`tiles.live` 关闭后忽略、`tiles.background` 在设置存储里切换后三种背景都到达可见页面（截图 `artifacts/tiles/motion/start-backdrop-*.png`） |
| `tile_photo_device.py` | 16 项通过（切页返回、待机、唤醒、横竖屏、主题后图片层都铺满） |
| `tile_regression.py`（组件 / 天气 / 设置） | 通过 |
| `public_data_device.py` | 13 项通过 |
| 人工截图 | 横竖屏、阿拉伯语（RTL）、繁体中文、俄语；标题与月相名随语言更新 |

**修正的问题**：时钟磁贴的表盘被名称栏裁掉（内容区现在止于名称栏之上）；相册磁贴改为每日图；按下倾斜方向反了（按在右侧时右侧变大），已按截图改正；页面标题与月相名不随语言变化，已修；小磁贴 10sp 名称被裁，名称行高加到 16dp。

**未运行**：磁贴页与相册页的 10 分钟性能采样。开始页现在有 10 张磁贴、一张平移的全幅图片，加上入场和倾斜动画，功耗与掉帧均未知，需要先告知用户再跑。夜间模式只看了横屏靛蓝的截图：渐变平滑，未见色带；夜间滤镜把所有磁贴底色压成近似的暗红，只剩亮度差，这是现有夜间滤镜的行为，没有为开始屏另做处理。夜间竖屏、深青 / 酒红在夜间的样子、实体光照与旋转未人工验收。深青背景上的预报磁贴（#0F766E）和酒红背景上的日期磁贴（#B3173B）与背景同色系，边界仍清楚，但不如靛蓝背景上的对比明显。
