# 13 · M1 前设备端加固

验证日期：2026-10-29–30（Asia/Hong_Kong）。设备：`HUA0BWUT`，Android 8.1 / API 27，armv7。APK `0.1.1` / versionCode 2。本次承接 M0 审计，不包含音乐/相册/通知插件或 Server 实现；M0 的原始测量保留在 [12-m0-validation.md](12-m0-validation.md)。

## 运行时改动

| 审计问题 | 当前实现 |
|---|---|
| 每事件编译源码、全量 GC | `_dispatch` / `_timer` 保存注册表引用，类型化 JNI 参数直接调用；Lua 5.4 分代 GC |
| JSON 在 Kotlin/Lua 之间重复编码 | JNI 直接转换 Map/List/标量；C 侧 yyjson 0.12.0 编解码，包含 Unicode、int64、null、空数组类型、嵌入 NUL 与严格 JSON 校验 |
| 正常 yield 被误判 | 原生任务保留协程引用；WAITING 与脚本/CPU/内存/墙钟错误有不同状态；HTTP/sleep 完成后恢复同一任务，每次恢复重新计预算 |
| 普通错误重置整个插件 | 普通事件错误保留实例；频繁错误/资源错误才退避重启，五次重启后冷却一小时再自动试运行，保留状态和 KV |
| fsync 阻塞共享线程 | store 内存立即可见，最后修改两秒后合并快照，由独立 StoreIO 原子落盘；停止时提交。状态大小增量核算，每批发布只做一次快照 |
| IO 可绕过 CPU 预算 | 执行切片同时受 50ms 线程 CPU、200ms 墙钟、500 万指令限制；HTTP 和 sleep 必须挂起；每万条指令读一次时钟 |
| 原生崩溃重复加载同一插件 | 原生执行前写 mmap 插件/包摘要标记，正常返回/挂起后清除；下次启动对遗留标记计数，三次不洁退出隔离故障摘要一小时 |
| 模式匹配被禁用 | 仅修改上游 `lstrlib.c`，在回溯、匹配字符、平衡扫描及字面搜索循环检查预算；恢复 `find/match/gmatch/gsub` |

Lua 与 JSON 临时分配共用每插件 4MiB / 合计 24MiB 上限。JSON 深度 ≤24、文本 ≤1MiB；状态与 KV 各 ≤256KiB。只开放运行时管理的事件/定时器根协程调用异步 API，避免用户子协程产生无人恢复的 IO。

热更新保留旧版直到新版 `start` 完成。失败则继续旧版；替换、删除覆盖包、停止插件时同时取消候选实例、定时器和挂起任务，迟到结果不再发布。JSON 库来源、许可、哈希和唯一 Lua 补丁见 `android/app/src/main/cpp/PATCHES.md`、`json/SOURCE.json`、`lua-pattern-budget.patch`。

## 界面与交互

- 背景圆一次绘入硬件层，只变 scale/alpha；数字时钟只变 View alpha。持续呼吸使用没有逐帧监听器或结束动作的 ViewPropertyAnimator，主线程每半周期换向一次，允许 API 27 的 RenderThread 后端执行。依据 [Android 8.1 ViewPropertyAnimatorRT 源码](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-8.1.0_r1/core/java/android/view/ViewPropertyAnimatorRT.java)。
- 模拟表盘缓存刻度 bitmap，秒级只重画指针；主题插值调整 tint。无活动自绘动画时，场景时钟按 1/3/1Hz（AWAKE/AMBIENT/ASLEEP）唤醒；属性动画独立由系统调度。
- 加载时编译模板和格式化器，状态变化只刷新相关路径。布局槽位/角色由 `shared/schema/plugin/layouts.json` 生成到 `LayoutVocabulary`；图标用精确名称注册表。
- 拖页同时保留当前页与相邻页，按完整手指位移预览，松手继承速度做阻尼弹簧吸附；卡片栈同样跟手，短拖返回。旋转/新手势可取消旧弹簧，不保留第三个隐藏页面/卡片。
- 同页热更新原地更新组件，移除当前变体时只做局部淡入；走近有波纹和交错浮现，离开交错沉下。停止舞台时暂停持续动画。
- ThinkStage/PageView 拆分为显式生命周期、绑定、手势和绘制职责，减少原先密集的语句混排。

## 网络边界

新增 INTERNET 权限，但内置 home/clock 仍完全离线。`tos.http` 需要插件声明 `network`；IO 池执行请求，连接/读取超时各 3s，请求/响应 ≤1MiB，最多三跳重定向，禁止 HTTPS 降级。

Android Network Security Configuration 无法用 CIDR 表达任意局域网。XML 允许明文，公共 HTTP 客户端负责校验全部 DNS 地址为私网/回环/链路本地地址、固定连接 IP 并保留 Host 与原始 URL 编码；逐跳重验。HTTPS 保持系统证书校验。未来音频/Server 客户端必须复用这条策略，不能仅依赖 XML。

## 自动化与真机回归

| 检查 | 结果 |
|---|---|
| assembleDebug / testDebugUnitTest / lintDebug | 通过；JVM 30 项，lint 0 errors（保留目标设备与同步崩溃持久化等已知警告） |
| 原生 Lua/JNI | 32 项通过，含双重挂起、每次恢复预算、pcall 跨 yield、取消、墙钟、模式回溯、分代 GC 与累计内存 |
| 插件 Schema/打包 | Python 14 项通过；两端代码重新生成，Go 生成结构独立编译通过 |
| ABI / SDK | 仅 `lib/armeabi-v7a/libtos.so`，ELF32 ARM；minSdk/targetSdk 均 27 |
| 大响应与并发 | 真机 58,928 字节/1000 条 JSON 解析通过；650ms HTTP 等待期间另一计时器继续，局部变量与 pcall 跨挂起保留 |
| 普通错误与存储 | 单次普通错误后实例不重启；20 次 KV 写入立即返回，最终值 20，仅一次延迟落盘；中断写入备份恢复另有真机模拟 |
| 请求限制 | 真机拒绝无 network 权限、公网明文重定向与超过 1MiB 响应；允许私网重定向 |
| 更新/取消 | 异步启动失败保留旧版、成功提交；替换中断旧 HTTP/sleep，删除挂起候选不会复活 |
| 原生退出标记 | 模拟三次遗留 mmap 标记后隔离对应摘要，clock/home 健康；未主动制造 SIGSEGV。冷却、跨开机与无 RTC 时间由虚拟时钟单测覆盖 |
| 界面 | 页面、变体、栈跟手与短拖取消、原地热更新、横竖屏、三主题、唤醒波纹截图；调试方向控制不替代物理旋转验收 |

### 性能

性能数字来自 `artifacts/pre-m1/`。第一轮仍以每帧 ValueAnimator 更新属性时，AWAKE 数字时钟约 33% 单核 CPU；该数据用于定位问题，不作为最终验收，也不与 M0 未标记档位的 10 秒样本直接比较。

| 场景 | 时长 | 单核平均 CPU | janky | PSS |
|---|---:|---:|---:|---:|
| AWAKE 数字时钟 / DARK | 120.08s | 32.112% | 0/7200（0%） | 78.5MiB |
| ENGAGED 数字时钟 / DARK | 120.09s | 31.618% | 2/7202（0.03%） | 65.3MiB |
| AMBIENT 首页 / DARK | 600.07s | 2.923% | 15/2392（0.63%） | 66.6MiB |
| 无网络/无 Server 冷启动 | TotalTime 479ms；Fully drawn 771ms | — | — | — |

CPU 用 `/proc/<pid>/stat` 累积 ticks 与设备 AT_CLKTCK 计算，100% 为占满一个核心，不除以核数。注意力档位通过 debug 输入固定，主题/页面在日志中核对；不在采样期间操控界面。实体接近/遮光、物理旋转、实际 SIGSEGV 后的系统进程恢复和 72 小时稳定性仍需后续验收。

高帧率两档均约 60fps，P90 8ms。AWAKE CPU 改善有限，连续合成仍需约 32% 单核 CPU；后续按明确档位/场景记录基线。AMBIENT 的最大 10s 窗口为 17.262%，包含进入采样时的转场；600s 平均值满足 <8% 要求。三段采样 PID 均为 7024，前后没有重启。

采样 APK 的哈希保存在 `performance-apk.json`。采样后仅补充 AtomicFile 读取备份恢复，UI/动效代码与调度保持一致。最终包已安装，全部运行时真机回归再次通过，新增的中断写入模拟成功从 `.bak` 恢复 KV；最终包离线冷启动为 479ms、完整绘制为 771ms。

最终包另采样 20.08s 的 AWAKE 数字时钟：总 CPU 32.221%，RenderThread 22.859%、主线程 9.313%、插件线程 0.149%（短样本含启动后 JIT，不用于替代两分钟稳态结果）。这表明剩余开销主要位于原生界面的连续渲染路径；桥接与插件执行不是该场景的主要 CPU 消耗。

## 复现与产物

```bash
android/app/src/testNative/run.sh
cd android && ./gradlew assembleDebug testDebugUnitTest lintDebug
# 回到仓库根目录；启动本地 HTTP fixture，再安装 APK
python3 tools/tests/http_fixture.py
python3 tools/tests/pre_m1_device.py
python3 tools/tests/pre_m1_ui.py
bash tools/tests/pre_m1_bench.sh
python3 tools/tests/pre_m1_cold_start.py
```

原始日志/截图在 `artifacts/pre-m1/`：`device-runtime/`、`device-runtime-final.log`、`ui/`、`awake/`、`engaged/`、`ambient/`、`cold-start/`、`awake-threads.json`、`native-tests.txt`、`jvm-tests.json`、`package-tests.txt`、`build-check.txt`、`apk-check.json`。测试使用普通 adb/run-as 和公共 `tos.*`，无设备系统分区操作。APK：`android/app/build/outputs/apk/debug/app-debug.apk`。

最终 APK SHA-256：`a1497d552c954b79975f647964e1794304e93fdc7469cf94a2c159434f7addd9`，2,399,939 bytes。开发覆盖包及测试 KV 已移除；恢复 Wi-Fi=1、自动旋转=1、fullSensor、ThinkOS 默认 HOME，解除调试注意力/环境光输入，本地 HTTP fixture 已关闭。恢复快照见 `cold-start/restored.json`。

store 的延迟写入不是同步事务保证，进程突然死亡可能丢失尚未提交的两秒修改。mmap 标记表示进程在插件执行中消失，不能区分 SIGSEGV 与该切片内被外部强杀；隔离按包摘要进行，避免影响其它插件。200ms 墙钟在可信宿主调用返回后检查，不能抢占一个永不返回的 C/宿主函数，因此新增 IO API 必须遵守挂起模型。
