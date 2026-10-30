# 24 · 磁贴实现与真机验收

设备：Lenovo ThinkSmart View，Android 8.1 / API 27 / armeabi-v7a。2026-10-31 开始验收；按用户要求使用无线 ADB `192.168.3.167:5555`。基线 `31ae3dc`，开发分支 `feat/tiles`。

## 已确认的设计修订

- Windows Phone / Windows 8.1 Metro 风格：直角、平面纯色、基础单元正方形、底部标签、大号轻字重数字；不分发额外字体。
- API 4 与 ADR-025 用于磁贴；播放器仍是未实现的 API 5 / ADR-026 提案。
- 夜间红色矩阵会使中灰底原有黑白前景不够清楚。现在按合成后的颜色选前景；必要时仅夜间补偿底色到最近的可读亮/暗端。白天浅/深主题保留插件原色。JVM 对 4096 色 × 5 个夜间过渡阶段检查 ≥4.5:1。
- 无线验收不能关闭 Wi-Fi；离线场景需保留 ADB 局域网、阻断应用外网、无 Server。

## 执行状态

| 验收 | 状态 | 证据 |
|---|---|---|
| T0 | 通过 | 既有改动提交 `31ae3dc`，然后创建 feat/tiles；当时 git status 干净 |
| A1 契约 | 通过 | `artifacts/tiles/a1-*`；生成器重复输出一致；Python/Go/JVM 拒绝 API 1–3 使用 tile/tiles；颜色、content、size 的边界测试 |
| A2 纯逻辑 | 通过 | `artifacts/tiles/a2-jvm.log`；随机排布、镜像、不重叠、不越界、溢出丢弃、4096 色对比度；夜间补偿测试见 t3-jvm |
| A3–A5 | 通过 | 最终 APK 的 `artifacts/ui-validation/tiles/report.json`；6 组 × 2 页面、4 语言、12 图标、三种色源、设置页改色、原 View 平滑动画、点击/长按/横纵手势 |
| A6 翻面与设置 | 通过 | `artifacts/ui-validation/tiles-live/report.json`，真机单调度器、间隔、关闭即取消、AMBIENT/离页、恢复、空面与减少动画；226 个 JVM 测试 |
| A7 插件 | 通过 | `artifacts/ui-validation/tiles-plugins/`，目录热加载、跨插件设置、移除与恢复 |
| A8 性能 | 待执行 | |
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

- Python：33 项；JVM：226 项；Go protocol：通过；Lua / JNI / 24MiB 预算、gallery 与 Bing 原生测试：通过。lintDebug 与 assembleDebug 通过。
- APK 唯一 so 为 `lib/armeabi-v7a/libtos.so`；minSdk / targetSdk 保持 27；没有新增生产依赖。
- `component_ui.py`、`weather_ui.py --wireless-offline`、`settings_ui.py` 均通过。天气三次离线冷启动 Activity 822 / 807 / 776ms，完整首帧 2600 / 2590 / 2560ms，保留 Fahrenheit 配置与最后缓存数据。
- 现有 home 的 ambient 角色在 NIGHT 会切到 sleep 时钟，原回归脚本误以为夜间仍可翻入 home；已修正测试为核对既有夜间策略，未改变产品行为。新增 tiles 没有 ambient 角色，夜间可正常查看。
- 旧脚本假设 settings 探针下一页就是 home；新增 tiles 后该假设失效，回归现在明确返回 home 再测设置归属。
- 视觉矩阵已逐图检查横/竖屏的浅、深、红色夜间；文本边界同时由真机 View 树断言。ar 网格与内容镜像，hi 字形上下边界完整。
- 动画测试显式设倍率 1，结束后按原始值恢复；缺省键使用 delete 恢复，不写入字面量 null。

### 性能预采样与修正

`artifacts/tiles/performance-initial/` 为提前中止的预采样，不计入完整 10 分钟验收。一次阶段读数为 1243 帧中 90 janky（7.24%）；DrawStart→SyncQueued 最大约 5.07ms，但部分 PerformTraversalsStart→DrawStart 达 22ms。定位到颜色绑定未改变文字时仍 setText/requestLayout，以及每次测量重新设置自动字号。

修正后：只有文字或图标有实际变化才改对应 View；自动字号只在声明/尺寸变动时设置；隐藏的文字不参与实际测量；角标也避免重复赋值。图标仅在 accent 变化时重绘，并在页面其它动画升至 60fps 时把天气图标自身保持在 30fps。后续重新跑完整 UI 与两轮 600 秒性能记录。
