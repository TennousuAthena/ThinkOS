# 20 · 天气插件交接与验收

> 2026-10-30 续作。用户明确选择：**解除天气的数据接线暂缓，完成 W0–W2，同时接入实时天气、缓存和断网恢复**。本条优先于原交接文档的“仅测试 UI”；媒体、相册等其它功能仍沿用原有暂缓指示。
> **W0–W2 已完成开发、调试与真机验收，APK 已安装。** 汇总见 [`artifacts/weather/validation.json`](../artifacts/weather/validation.json)。

## 1. 已实现范围

- `weather.now`：首页天气卡片，点击进入 `weather.detail`。
- `weather.detail`：实时温度、体感、湿度、风速、地点与更新时间，24 小时温度曲线、可滚动的 5 日预报。
- 10 个天气图标：`clear-day`、`clear-night`、`partly-day`、`partly-night`、`cloud`、`fog`、`rain`、`shower`、`snow`、`thunder`。未知 WMO 代码使用明确占位（`unknown`，一个带问号的圆）。
  > **2026-11-02 更新**：最初是 Canvas 手画的零件（太阳、射线、云、雨滴……）拼成，再预渲染成帧表播动画；现在全部换成取自 Iconfont 的 Lucide 矢量图标（`assets/README.md`），一个名字一条路径，按显示的实际大小直接画，不再从位图缩小，帧表（`WeatherAtlas`）随之删除。对应：clear-day→sun、clear-night→moon、partly-day→cloud-sun、partly-night→cloud-moon、cloud→cloudy、fog→cloud-fog、rain→cloud-rain、shower→cloud-rain-wind、snow→cloud-snow、thunder→cloud-lightning、unknown→circle-question-mark。**动画的变化**：太阳整体来回摆 ±9°（圆形的太阳整体转动就只有射线在动，和原来一样）、带云的图标左右轻飘 ±2/128 的图标宽度，保留；原来雨滴、雪花的逐粒下落和多云里太阳射线的单独摆动没有了，因为现在一个图标是一整条路径，拆不开。月相图标（`moon-phase-*`）按亮面比例画，不是固定图形，仍由代码绘制。
- Open-Meteo 直连、完整数据校验、原始数据持久缓存、失败重试、断网显示旧数据、恢复联网后自动更新。
- 温度与风速单位跟随系统设置 `units.system` / `units.wind`（`tos.units`），本地换算。单位和语言变化不产生请求。`heat` / `cold` / `high_wind` 阈值固定按 °C、km/h 填写。
- 11 种语言、阿拉伯语 RTL、横竖屏、LIGHT / DARK / NIGHT，自动生成的插件设置页。

天气仅通过公开 `tos.*` 获取能力，Lua 不在主线程执行。无 Server、GMS、WebView、设备 Git 或新原生依赖；不修改设备系统层。

W3 / W4 未纳入本次：数字滚动与图标交叉淡入、`weather.sky`、降雨通知、独立风速单位、备用供应商和插件私有地点覆盖。位置统一来自系统设置。

## 2. 文件与接线

```text
plugins/weather/
├── manifest.json                 API 2，network / location
├── main.lua                      事件、调度、缓存与状态派生
├── forecast.lua                  严格校验、URL、位置与单位换算
├── codes.lua                     WMO → 图标 / 翻译键 / mood
├── i18n/{11 种语言}.json           每种 56 键
└── views/{now,detail}.json         glance / split，原生 chart / list
```

- `plugins/home/views/main.json` 将 `weather.now` 放在右栈首位，日历保留在其后。
- `tools/package_plugins.py` 默认打包 `home / clock / weather / settings`。
- `IconView.kt` 增加天气词汇与共享的 256px ALPHA_8 图层；主题只改变 tint，几何图像不反复生成。alpha 位图合计低于 1MiB。
- `PageView.kt` 在高度有限的组件组内使用更紧凑的原生排版，避免竖屏 lead 区截断。
- `MotionClock / FrameGovernor` 驱动太阳、云、雨、雪的小幅运动，AWAKE / ENGAGED 为 30fps；AMBIENT / ASLEEP 静止，遵循系统减少动画设置。静态晴夜图标不持续请求帧。
- `ThinkStage.localeChanged()` 更新布局方向；debug locale 输入只改变进程语言，不修改 Android 已保存的语言。

`icon.name` 在现有 schema 中是字符串而非枚举，因此未新增或手改生成协议。`relevance` / `mood` 已按契约发布；OS 尚未消费它们做智能轮换、降权或背景染色，这些调度/视觉能力仍待实现，见 `06-plugins.md` §4.4。

## 3. 数据与时间语义

数据源：[Open-Meteo Forecast API](https://open-meteo.com/en/docs)。界面显示 `Open-Meteo.com`，详情同时显示 `CC BY 4.0`；使用条件见[条款](https://open-meteo.com/en/terms)与[许可](https://open-meteo.com/en/licence)。

请求始终使用摄氏度和 km/h：

```text
/v1/forecast?latitude=…&longitude=…
  &current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m
  &hourly=temperature_2m,precipitation_probability,weather_code
  &daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max
  &temperature_unit=celsius&wind_speed_unit=kmh
  &forecast_days=5&timezone=auto&timeformat=unixtime
```

- JSON 必须同时包含有效的 current / hourly / daily。验证有限数值、字段类型、数组长度、时间步长、日温度上下限；任一部分错误就丢弃整次响应。
- 缓存保存摄氏原始数据、WMO 代码、位置与成功取数时间，不保存翻译或显示单位。
- `tos.store` 内的 `forecast` 为 JSON 字符串，由 C/yyjson 编解码，避免上百个原始标量反复经过 typed JNI；兼容读取旧对象形式，损坏缓存被忽略。
- API 的 Unix 时间戳本身为 UTC。小时和更新时间将原始时间戳交给 `tos.time.format(ms, "@time", timezone)`，保留真实时区。日预报边界采用响应中固定的 `utc_offset_seconds`；先加该偏移还原名义当地日期，再在 UTC 用 `@weekday` 格式化，避免跨 DST 时日标签退回前一天。当前小时/日期分桶也沿用响应固定偏移。
- 未校时不显示相对年龄、不据此计算过期；校时后最迟在下个分钟 tick 发起刷新。

## 4. 刷新与恢复

- `start` 只读缓存并发布状态，1 秒后发起网络请求，不让热替换等待 HTTP。
- 默认每 15 分钟刷新；每分钟对齐更新年龄。数据超过默认 180 分钟标记“已过期”，继续保留数值。
- 5xx、超时、非 JSON、缺失字段都保留完整旧数据，显示离线/重试信息，退避默认 1 → 2 → 5 → 15 分钟。成功后重置退避。
- 未校时期间默认 30 秒重试，避免因证书日期失败进入长退避。
- ASLEEP 不发起请求；唤醒时缓存仍新鲜就等待剩余间隔，过期或有待处理请求才立即补拉。
- 同时只允许一个请求；后续刷新合并。位置/endpoint 变化通过 generation 丢弃迟到结果，随后请求最新位置。停止后不允许迟到写入。
- 降水、雷暴、大风、冷热阈值升高相关度；同值不重复写入。日期/天气/单位的重复文本有缓存，locale 变化会清理。

manifest 声明的设置：

| 设置 | 默认值 | 作用 |
|---|---|---|
| `interval` | `15` | `10 / 15 / 30 / 60` 分钟 |
| `stale_after` | 180 min | 标注数据过期 |
| `endpoint` | Open-Meteo HTTPS URL | 兼容接口，不能含查询串或 fragment |
| `retry_initial` / `retry_max` | 60s / 900s | 正常失败退避 |
| `clock_retry` | 30s | 未校时短重试 |
| `high_wind` | 40 km/h | 大风关注阈值 |
| `heat` / `cold` | 35°C / 0°C | 冷热关注阈值 |

这些值统一由 OS 设置 schema 校验、持久化。天气插件只读；长按卡片或详情进入同一个设置页，选回默认值时本地覆盖会被删除。

## 5. 调试中修复的问题

1. C 的文本 `load` 在三参数调用时，把编译后压入 slot 4 的函数误认为显式 `_ENV`，导致 `require("codes")` 无法访问 `math`。现于编译前记录是否真正传入第四参数，覆盖无第四参数、显式环境与显式 nil 的回归。
2. 原始 forecast 对象经过 JNI 的成本导致缓存写入/冷启动超限，改用上述 JSON 文本缓存，未放宽 50ms CPU / 200ms 墙钟预算。
3. 多语言格式化反复建立 NumberFormat，在冷进程天气渲染中出现预算错误。StringTable 按线程和 locale 复用格式器，仅数字参数建立格式器；天气同时复用重复单位/温度文案。
4. 晴夜静态图标不再持续唤醒 30fps；短暂休眠后不重复拉取新鲜数据。
5. 热替换实例原先不会继承已发生的 attention 事件，休眠中安装后可能仍联网。运行时现在向新实例重发当前注意力状态；集成测试验证睡眠中替换、唤醒补拉。
6. RTL 时间区间通过 Unicode isolate 保留与曲线一致的顺序，并保留本地 AM/PM 格式。
7. Open-Meteo 日预报边界跨 DST 仍沿用请求时的固定偏移。实测纽约 2026-10-30 起的五天，原 IANA 格式化会重复星期日；改为按响应偏移还原名义日期，以 UTC 命名格式输出。主机、armv7 与实际 JNI 日期格式器均覆盖该样例。
8. UI 测试将“选择默认值后覆盖项不存在”误判为单位未保存，现按设置存储语义检查。

已有工作区中的时钟动效设置和翻译修改被保留。JVM 的系统设置可见项断言同步了已有 `motion.clock` 设置。

## 6. 验收记录（2026-10-30，HUA0BWUT / API 27 / armv7）

| 项目 | 结果与证据 |
|---|---|
| Android 构建、JVM、lint | `testDebugUnitTest lintDebug assembleDebug` 通过，195 项 JVM 测试；最终摘要见 `artifacts/weather/build.json` |
| ABI / SDK | APK 仅 `lib/armeabi-v7a/libtos.so`，minSdk / targetSdk 均 27；无新增依赖 |
| Python / 打包 / 国际化 | 30 项 Python 测试；4 个默认插件校验；11 张系统翻译表一致；天气 11 × 56 键与占位参数一致 |
| 真实 armv7 Lua 预算 | 15 组通过，首次刷新 4.96ms、缓存读取加渲染 9.54ms；整组最大 28.37ms，观测分配峰值 370,614 bytes（约 362KiB）；`artifacts/weather/armv7-native-tests.txt` |
| 公共 HTTP / JNI 集成 | 19 项通过，含 API 27 真实 TLS、坏响应、自动恢复、位置竞态、休眠中热替换与纽约 DST 日期；`artifacts/weather/data/results.json` |
| 原生 UI / 单位 / 冷启动 | 主题/方向/RTL/第 5 日见 `artifacts/weather/ui/`；单位、冷启动、图标与性能续跑见 `artifacts/weather/ui-final/`（同一 APK） |
| 天气/时钟各 120s AWAKE / 600s AMBIENT | 全部通过，三段采样 PID 均为 31599；`artifacts/weather/{weather-awake,awake,ambient}/` 与 `performance.json` |

| 场景（DARK / 横屏） | 时长 | 单核平均 CPU | janky | PSS |
|---|---:|---:|---:|---:|
| 天气详情 / AWAKE | 120.08s | 29.255% | 2/3625（0.055%） | 70.54MiB |
| 数字时钟 / AWAKE | 120.08s | 32.095% | 2/7214（0.028%） | 67.41MiB |
| 首页天气 / AMBIENT | 600.07s | 2.916% | 17/2172（0.783%） | 73.64MiB |

时钟与待机 CPU 相对 `13-pre-m1-hardening.md` 基线分别为 −0.053% / −0.239%，未超过 20% 增长限制；这些小差异视为采样波动。待机掉帧率由基线 0.63% 到 0.783%，仍低于 5% 门槛。采样日志没有当前进程的预算错误或崩溃。

断网、无 Server 连续三次冷启动：Activity **701 / 689 / 713ms**；包含启动命令和调试读取开销的缓存就绪确认 **2483 / 2444 / 2455ms**，均小于 3 秒。华氏设置与缓存跨重启保留，详情页切回摄氏即时生效；恢复 Wi-Fi 后生产插件自动刷新。

最终 APK SHA-256：`96c9b48b8f0dc87fdb3f40bac98abedd95394df4e4ef7937c8c5648d947bc9d0`，7,397,507 bytes。安装包与本地构建哈希一致；设置原始字节、Wi-Fi=1、ThinkOS HOME 均已恢复，临时插件、测试 KV 与 adb reverse 已清除，见 `artifacts/weather/restored.json`。原有时钟设置/翻译修改未被回退。

预算测试包括首次渲染、缓存读取、单位与语言、四类坏响应、退避、睡眠与唤醒、未校时、旧缓存、位置竞态、并发合并、停止、坏数组、全部 WMO 组、非整点时区、跨 DST 日预报和睡眠中请求完成。固定样例不包含 JVM/JNI 宿主耗时；实际 PluginHttp / JNI 另由实机集成与冷启动回归覆盖。

未校时和位置竞态通过测试插件对公开 `tos.time.now` / `tos.location.get` 的测试替身注入，不改设备时钟或用户位置。主题、方向、语言使用 debug 输入；实体遮光/旋转不列为此次已验证项。

## 7. 复现

```bash
# 构建与主机回归（JDK 17）
cd android
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --offline testDebugUnitTest lintDebug assembleDebug
cd ..
JAVA_HOME=$(/usr/libexec/java_home -v 17) android/app/src/testNative/run.sh
python3 -I -m unittest discover -s tools/tests -v
python3 -I tools/package_plugins.py --validate-only
python3 -I tools/gen_android_strings.py --check

# 真机：按顺序运行；UI 脚本含 14 分钟性能采样
bash tools/tests/weather_native_device.sh
bash tools/deploy.sh --no-build
python3 -I tools/tests/weather_device.py
python3 -I tools/tests/weather_ui.py --bench
python3 -I tools/tests/weather_perf.py
```

数据测试使用临时 localhost fixture 与 adb reverse，最后删除测试插件、KV 与反向端口。一次冷启动后的 UIAutomator dump 返回成功但未生成 XML；应用截图仍显示缓存华氏数据。脚本现在对界面树读取最多重试三次，必要时暂用 AMBIENT 暂停图标运动，读取后恢复 AWAKE。已通过的主题/RTL 矩阵保留原始记录，续跑使用 `--skip-matrix --output artifacts/weather/ui-final --bench`。

UI 脚本先私下备份设置，在 finally 恢复 local/server 的原始字节、Wi-Fi 与 HOME，测试期间不修改设备系统分区。

## 8. 已同步文档

`05-protocol.md` 的卡片/设置示例、`06-plugins.md` 的完整天气实现说明、`07-build-deploy.md` 的内置包、`09-roadmap.md` 的天气交付、`14-handoff.md` 的后续授权、`shared/schema/plugin/README.md` 的当前词汇与范围。
