# 48 跟手性与延迟优化、端到端仿真测试：实现交接

> 2026-11-19。R0–R4 已完成，R5 真机实测未通过整体验收。保留 O1、O2、O4、O5、O6、O7。O3 在对照中拖慢按下反馈，已回退。R5 后续增加 Aero 壁纸光栅缓存和双向邻页保留。O8 已独立对照，默认 0 ms。见 [R5 报告](../artifacts/perf/R5/summary.md) 和 [后续结果](../artifacts/perf/R5/follow-up/implementation/summary.md)。
> 本文不改变任何已定的 ADR；遇到要改 ADR 的地方，停下来问所有者。
> 行号对应起草时的工作区（含未提交的改动），实现前先按函数名重新定位。

2026-11-19 实施记录

- R0 的初始工作区、构建状态和设备 atrace 标签已写入 [R0](../artifacts/perf/R0/)。
- R1 在 HUA0BWUT 上确认拖动、磁贴按下与启动、播放器页签点按和 AMBIENT 唤醒均有配对帧。FrameMetrics 与 uptime 的时钟基核对为 0 ms。一次性功能检查和恢复记录见 [R1](../artifacts/perf/R1/)。
- R2 的场景脚本、轨迹注入和夹具已准备。S10 使用自带播放器的「最近播放」页签，离线也会改变绑定状态。S11 让 AMBIENT 测试保持在手指触发 ThinkStage 时解除，后续注意力由原有策略更新为 ENGAGED。S14 使用独立天气实例和有界回环 HTTP 夹具。
- 帧配对按 R1.3 的首个合格 vsync 计算窗口帧完成时刻，配对规则可能取到后续帧。R2 用 atrace 和 gfxinfo 交叉核对，并分别记录缺样本、丢记录和超限。一次性功能检查独立于 P95 和达标判断。
- R2 在 HUA0BWUT 上完成 14 个场景各 30 轮，共 780 个手势窗口和 3 个有效 atrace。所有功能终态通过，3 个窗口有局部缺测。横翻首帧、磁贴打开、空闲插件点按的 P95 分别为 262.06、112.29、117.79 ms。AMBIENT 唤醒 P95 为 36.34 ms。见 [基线与 F1–F9 判读](../artifacts/perf/R2/summary.md)。
- S14 使用 192 KiB 回环响应，避免 1 MiB 初试触发 Lua 预算。S5、S10 单独用较小缓冲补录 atrace。时钟有 657 次变化可供核对，跳秒为 0。AWAKE、AMBIENT CPU 的同条件对照在批次 1 分别测得 46.36%、6.63%。
- [R3 批次 1](../artifacts/perf/R3/batch-1/summary.md) 的 180 个设备功能窗口全部通过。D1 定位同步建页成本。D2 复现舞台把时钟写回前一秒的路径，O7 提前到 O5 之前。D3 在真机确认原生页签点击排入下一条主线程消息，欢迎页声明式点击在抬手处理中同步投递。
- [R3 批次 2](../artifacts/perf/R3/batch-2/summary.md) 的 190 个功能窗口均通过，S9 一轮缺 1 个 MOVE 配对。S9 跟随 P95 从 165.66 降到 67.40 ms，S10 往返从 117.79 降到 80.88 ms，仍高于暂定门槛。S1、S3、S13 的整周期迟到和跳秒均为 0，S1 逐场迟到 P95 57 ms 仍超限，时钟专项 4/4 通过。
- S11 唤醒 30 轮复测保留 D2 时钟明细，R2 的 303–357 ms 时钟迟到未复现，待观察。本次时钟迟到最大 32 ms，触摸到唤醒交帧 P95 为 79.51 ms。[复测原始数据](../artifacts/perf/R3/batch-2/wake-30/S11.json)
- 批次 2 关闭建页细分补测 20 轮。S10 往返 P95 69.01 ms 通过 70 ms 门槛，发布等待 15.71 ms 仍超限。S2 / S9 跟随 P95 为 63.20 / 62.53 ms，S14 负载往返 67.09 ms。[补测数据](../artifacts/perf/R3/batch-2/latency-20/summary.json)
- O2 同条件建页 P95，磁贴 77.26 → 58.61 ms，Aero 170.96 → 159.10 ms。Aero 和磁贴的重复导航快照少 5 个 View。关闭细分后 S1 / S5 / S6 的 P95 为 216.50 / 123.74 / 110.81 ms，均超暂定门槛。120 个功能终态通过，旧版磁贴通用回归三项失败，完整导航回归中断。[批次 4](../artifacts/perf/R3/batch-4/summary.md)
- 批次 3 的 160 个功能终态通过。O1 将 S6 首帧 P95 从 96.34 降至 46.87 ms，三页预热增 11.3 MiB。S1b 磁贴 DOWN 调度 P95 为 92.70 ms，预热附近仍可能延迟触摸。O3 预建层将 S5b 按下反馈 P95 从 195.23 推高到 266.87 ms，已单独回退。旧版组件导航与名称保存属于夹具问题，修正后通过。天气「完成」未复现，天气和图库离线冷启动仍超 3 秒。[批次 3](../artifacts/perf/R3/batch-3/summary.md)
- R5 的 14 个场景各 30 轮，780 个功能终态均正确，S9 有 4 个窗口各缺一次 MOVE 配对。S1 横翻首帧 P95 262.06 → 115.69 ms，S6 左缘返回 96.34 → 43.93 ms，S10 页签往返 117.79 → 60.85 ms。T1、T2、T3、T5 仍有超限。T2 的 S2 慢拖、S9 滑块跟随 P95 分别为 61.30、74.58 ms，均高于 40 ms。O5 已按确认改为拖动连续、松手吸附。[R5 场景表](../artifacts/perf/R5/summary.md#性能)
- R5 的 T4、T6、T8 达标。T7 跳秒、整周期迟到均为 0，S1、S3、S13 通过暂定门槛，S11 清醒时钟迟到 P95 54 ms 超过 40 ms。时钟专项 5/5 通过。T9 的待机掉帧率 0.73%、CPU 和峰值 PSS 达标。三轮离线冷启动首窗 4,179–4,327 ms，完整绘制 4,750–4,839 ms，按新门槛 5 秒通过，按原 3 秒口径超限。最慢一次完整绘制只余 161 ms。[专项与代价](../artifacts/perf/R5/summary.md#t9-与时钟专项)
- O1/O3 前 APK 的同条件冷启动首窗为 4,260–4,335 ms，两套门槛的判定与 R5 一致。旧首页断言在 NIGHT 下遇到系统路由到时钟页，耗时独立使用 ActivityManager 的启动记录判定。[冷启动对照](../artifacts/perf/R5/cold-control/report.json)
- R5 后补测了 S1、S2、S5、S1b 磁贴各 10 轮。S1 首轮 368.18 ms 的 trace 定位到 Aero 首次绘制与 GL 编译。S5 首轮 154.83 ms 的 trace 记录 UP 内的同步建页和列表布局。所有者批准按项目单独对照。[诊断与提案](../artifacts/perf/R5/follow-up/summary.md)
- S5 列表版复测打开 P95 209.51 ms，已回退。现页先翻候选 P95 135.52 ms，一轮 trace 首帧 40.20 ms 后接 83.34 ms 帧间隔。候选已移出后续构建，独立补丁与真机录屏留档。[短测与录屏](../artifacts/perf/R5/follow-up/implementation/s5-summary.md)
- O8 以同一 APK 交错对照 0/8 ms。S2 交帧位置误差 P95 19.28 → 15.74 px，跟随 45.90 → 44.10 ms，仍超 40 ms。S3 位置误差 P95 150.19 → 153.06 px。预测维持默认关闭，横拖反向、松手和取消在两档均通过。[O8 数据](../artifacts/perf/R5/follow-up/implementation/o8-ab/ab.json)
- 最终构建 S1 横翻首帧 P95 140.80 ms，首轮比同口径 R5 后补测的 368.18 ms 缩短。S2 普通慢拖 10 轮无建页，跨原点对照每轮少建一页，首帧/跟随 P95 仍为 61.25/44.49 ms。S1b DOWN P95 15.68 ms 达标，但越过 slop 到首帧仍为 286.83 ms，最慢轮一次建页耗时 157.87 ms。磁贴配色去重已回退，分片待所有者决定。[最终复验](../artifacts/perf/R5/follow-up/implementation/summary.md#最终构建复验)
- 核对运行记录的 APK 后确认，最终拖动复验、邻页对照和 O8 独立 A/B 均用回退列表改动后的构建。首轮探索拖动采样含该改动，保留作历史数据。用最终 APK 补测 S1b-Aero 10 轮，DOWN / 首帧 / 跟随 P95 为 19.64 / 91.20 / 68.13 ms，均超限。功能终态 10/10 正确，首轮时钟迟到最大 131 ms，跳秒与整周期迟到均为 0。[Aero 补测](../artifacts/perf/R5/follow-up/implementation/s1b-aero-clean/summary.json)
- 最终 APK 离线复验完整绘制 4,764 ms，AMBIENT 待机 600.1 秒掉帧率 0.60%，P95 帧耗时 10 ms。60 秒采样 AWAKE/AMBIENT 单核 CPU 45.12%/6.06%，性能场景 PSS 快照最高 142,575 KiB。冷启动、待机、CPU 和 PSS 通过。[冷启动与待机](../artifacts/perf/R5/follow-up/implementation/summary.md)

## 0. 开工前必读

### 0.1 规矩（来自 `AGENTS.md`、`docs/36` §0.2/§0.3，这里只列和本任务相关的）

- 角色：**开发**。可以改应用层代码、`tools/`、`docs/`；`system/` 模块只能写源码和主机测试，装进设备要人工。
- **性能测试先告知再跑**：先在设备上确认功能，再说明要测的内容与时长。所有者已授权本次批次短测和 R5，无需逐批确认。本文第 2、5 阶段的每一次设备测量都算性能测试。
- 提交、推送、开 PR 都要所有者逐次同意。
- 工作区起草时有约 119 个未提交文件（包括 `ThinkStage.kt`、`PageView.kt`、`FormView.kt`、`PluginRuntime.kt`）。**不要 stash、reset 或覆盖它们**；在它们之上改。开工时把 `git status --short` 和 `git diff --stat` 存到 `artifacts/perf/R0/worktree.txt`，作为基线对应的代码状态。
- 不要在设备上执行 `adb root`、`adb disable-verity`、`adb remount`，不要写 `/system`。
- `export ADB_MDNS=0`；设备 `HUA0BWUT`，竖屏 800×1280，60Hz，armv7，API 27。
- 时钟优先（ADR-029）：任何改动都不能让时钟迟到或跳秒。Lua 永远不在主线程（ADR-016）。
- 新增可配置的阈值或开关要走设置 schema（ADR-017，`settings/SystemSettings.kt`），不要另建 SharedPreferences；只是内部实现常量的（像现在的 50ms 合并）可以留在代码里，但要在本文 §7 登记。
- 不加依赖。trace 解析、统计全部自己写（Python 标准库）。

### 0.2 相关文档

`docs/04-ui-spec.md`（§9 设置、§11 视觉）、`docs/33-apps-and-back.md`（§8 没测的性能）、`docs/38-global-gestures.md`、`docs/42-feature-rules.md` §2 磁贴动画 / §3 页面外观 / §7 应用与返回、`docs/10-known-issues.md` §15。

## 1. 目标与判定标准

### 1.1 目标

1. 手指一动，画面在两帧内跟上；拖动过程中不掉帧，内容和手指之间不超过一帧的距离。
2. 点按的反馈（按下态）在下一帧出现；点按引起的页面变化（打开应用、插件状态更新）尽量快。
3. 有一套可重复、可对比的端到端测试，能抓出退化。

### 1.2 判定标准（草案，**第 2 阶段基线出来后和所有者确认再定稿**）

| 编号 | 指标 | 目标 |
|---|---|---|
| T1 | 拖动首帧响应：越过 slop 的那个 MOVE 的 `eventTime` → 画出它的那一帧交给 GPU（swap） | P95 ≤ 33ms |
| T2 | 拖动跟随滞后：每个 MOVE 的 `eventTime` → 反映它的帧 swap | P95 ≤ 25ms；拖动窗口内帧间隔 > 25ms 的帧，95% 的轮次为 0 |
| T3 | 点磁贴打开应用：ACTION_UP → 翻转第一帧 swap | P95 ≤ 50ms；翻转期间没有 > 33ms 的帧 |
| T4 | 按下反馈：ACTION_DOWN → 按下态那一帧 swap | P95 ≤ 33ms |
| T5 | 插件点按往返：ACTION_UP → 插件状态绑定到界面的那一帧 swap（自带插件，空闲时） | P95 ≤ 50ms |
| T6 | AMBIENT 中触摸唤醒：ACTION_DOWN → 第一帧 AWAKE 画面 | P95 ≤ 100ms |
| T7 | 所有手势场景期间时钟 | `ClockStats` 迟到 0、跳秒 0 |
| T8 | 手势窗口内阻塞式 GC | 0 次（容忍 1 次） |
| T9 | 不退化 | 冷启动 ≤ 5 s（断网、无 Server，原 < 3 s 门槛保留用于对照）；待机 10 分钟 janky < 5%；AWAKE、AMBIENT CPU 不超过基线 +20%；峰值 PSS < 350MB |

说明：swap 时间是“应用把帧交出去”的时间，不含显示合成和面板扫描（大约固定再加 1–2 个 vsync）。真正的“触摸到发光”只能用高速摄像机量，本计划不做；报告里写清楚口径。

2026-11-19 所有者确认，进入系统后的交互延迟优先。冷启动报告分开列 ActivityManager 首窗和完整绘制的逐轮数据、分布及最大值。可把门槛放宽得到的启动预算用于预建首页、邻页或提前加载资源，前提是时钟仍按 ADR-029 最先显示，冷启动总耗时 ≤ 5 s。实际可用预算以实测最慢一轮计算。

## 2. 现状：已读到的瓶颈和疑点

都是读代码得出的，**没有测过**；第 2 阶段的基线要逐条证实或否定，否定的就不做。

| 编号 | 位置 | 现状 | 推测的影响 |
|---|---|---|---|
| F1 | `ui/ThinkStage.kt` `tick()`（约 539–568 行）；`anim/Motion.kt` `FrameGovernor` | `activeAnimations` 只看主题、面板、翻转、页面自身动画，**不看手指是否在屏幕上**。拖动时 `MotionClock` 仍按 30fps（AWAKE 无动画）或 3fps（AMBIENT）走，`doFrame` 用 `handler.postDelayed` 隔帧。ACTION_DOWN 时也不 `clock.wake()` | 拖动中，被拖进来的邻页上的时钟、图标按低帧率更新，看上去一顿一顿；AMBIENT 下第一次触摸要等下一次 tick |
| F2 | `ThinkStage.dragTo()` → `prepareNeighbor()` → `newPage()`（约 494–506、412 行） | 越过 slop 的**第一个** MOVE 里同步构造邻页 `PageView`，下一次遍历再 measure/layout。空闲预备（`previewIdle`，约 152 行）只给照片页（`photoPage()`）预建 | 拖动开始的第一帧可能超时，正好是用户最在意的那一帧 |
| F3 | `ThinkStage.renderDeck()` LAUNCH 分支（约 437–446 行）；`ui/AppTransition.kt` `swingAway/swingIn`（约 106、118 行） | 打开应用时在 UP 之后才 `newPage` + 同步 measure/layout；翻转开始那一帧才 `setLayerType(HARDWARE)`，首帧要渲染硬件层 | 就是 `docs/10` §15 记的“打开应用时有一帧停顿” |
| F4 | `plugin/PluginRuntime.kt` `publishState()` = `schedulePublish(instance, 50)`（约 1436 行） | 插件每次改状态都固定等 50ms 合并再发布 | 点按 → Lua → 界面至少多 50ms，再加两次线程切换 |
| F5 | `PluginRuntime` 只有一个 `ThinkOS-Plugin` HandlerThread（约 101 行），所有插件的事件、定时器、状态发布排在一起 | 一个插件在做重活（解析大 JSON、天文计算回调等）时，另一个插件的 `tap` 要排队 | 点按往返的尾部延迟（P95/max）变长 |
| F6 | `ui/ThinkStage.kt` `updatePluginState()` → `Binding.changed()`（`ui/Binding.kt` 约 103 行） | 主线程上递归比较新旧整棵状态，每层建 key 集合；之后 `PageView.bind` 渲染模板 | 状态大（天气、播放列表）时占主线程，和拖动抢帧 |
| F7 | `ThinkStage.dispatchTouchEvent / onInterceptTouchEvent / onTouchEvent` 多处 `event.actionMasked in setOf(...)`；`FrameGovernor.fps` 每帧 `attention in setOf(...)` | 每个触摸事件、每帧都新建集合 | 拖动中的小分配累积成 GC；量一下再说，修起来很便宜 |
| F8 | `AppTransition` 每次动画开始才设硬件层，结束后撤掉 | 同 F3 | 同 F3 |
| F9 | `vision/SensorController.touch()` → `evaluate()` → `store.update` → `ThinkStage` 的 `collect` → `governor.attention` | 触摸唤醒要经过几次 post | T6 可能超标，先量 |

**不在本计划里改的**（属于交互设计，不是延迟问题；要改先问所有者）：
- 左缘 `BackGesture.EDGE_HOLD_MILLIS = 150`：边缘窄条里按下要等 150ms 或越过 slop 才交给页面（`docs/10` §15 已记为正常）。
- 长按 `LONG_PRESS_MILLIS = 600`、按压反馈 `FEEDBACK_AFTER_MILLIS = 300`。
- 越过 slop 时页面直接跳到手指位置（`offset = dx`，含 slop 那一段），这是“手指锁定”的设计，不改成减去 slop。
- 翻页提交阈值（22% 宽度或 700dp/s）和弹簧参数（`Motion.spring` 阻尼 0.86、频率 19）。

## 3. 阶段与工作包

顺序：R0 → R1 → R2（基线，要告知）→ 和所有者定稿 §1.2 → R3 各项（每项单独可回退）→ R4 → R5（要告知）。R4 的主机侧部分可以和 R1 并行。

### R0 准备（半天）

1. 存工作区快照（§0.1）。
2. `cd android && ./gradlew assembleDebug`、`python3 -I -m unittest discover -s tools/tests` 先跑一遍，记录起始状态（失败的先记下，不是本任务引起的不修，报告给所有者）。
3. 设备上确认 `atrace` 可用：`adb shell atrace --list_categories`，看有没有 `gfx view input sched freq`。Android 8.1 没有 perfetto，不要找它。
4. 确认 `FrameMetrics`（API 24 起有，API 26 起有 `VSYNC_TIMESTAMP`）在设备上能拿到数据：R1 的探针先只打一条日志验证。

产出：`artifacts/perf/R0/`（`worktree.txt`、`env.json`：APK 版本、git rev、是否 dirty、atrace 类别）。

### R1 测量探针（仅 debug 构建生效）

**R1.1 `InputTrace`（新文件 `android/app/src/main/java/moe/kiwi/thinkos/perf/InputTrace.kt`）**

- `object InputTrace`，`@Volatile var enabled = false`，只能由 debug 的 `DebugReceiver` 打开；release 构建里 `enabled` 恒为 false，所有方法第一行判断后返回。不要用反射，不要写文件。
- 固定大小环形缓冲：最多 2048 条记录（定长数组，预分配，不在记录时分配对象），满了覆盖最旧的。
- 记录类型（用 Int 常量，不要用枚举对象或字符串）：
  - `INPUT`：`action`、`eventTime`、`SystemClock.uptimeMillis()`（收到时）、x、y、手势编号
  - `APPLIED`：手势编号、当前帧被设置的 translation（px）、uptime
  - `FRAME`：从 `Window.addOnFrameMetricsAvailableListener` 拿 `INTENDED_VSYNC_TIMESTAMP`、`VSYNC_TIMESTAMP`、`TOTAL_DURATION`、`SWAP_BUFFERS_DURATION`，算出 swap 完成时刻（ns → ms，和 `eventTime` 同一时钟基：`SystemClock.uptimeMillis` 与 `System.nanoTime` 在 Android 上同源，实现时用一次实测校验）
  - `MARK`：命名阶段（`newPage`、`renderDeck`、`bind`、`publish`、`tapQueue`、`luaDispatch`、`stateDeliver`、`wake`），开始/结束 uptime
  - `GC`：手势开始和结束时 `Debug.getRuntimeStat("art.gc.blocking-gc-count")`、`"art.gc.gc-count"`
- `FrameMetrics` 监听在 `MainActivity` 里注册，listener 的 Handler 用一个专门的低优先级 HandlerThread，不要放主线程。
- 同时在关键函数外包 `android.os.Trace.beginSection("TOS:xxx")/endSection()`（不开 atrace 时开销可忽略），名字与 `MARK` 一致，供 R2 用 `atrace` 交叉验证。

**R1.2 打点位置**

| 位置 | 记录 |
|---|---|
| `ThinkStage.dispatchTouchEvent` 开头 | `INPUT`（DOWN、MOVE、UP、CANCEL、POINTER_*）；DOWN 时分配新手势编号 |
| `ThinkStage.dragTo` 设置 translation 之后；`moveBackSwipe`、`moveUpSwipe`、`panel.pullTo`、`current.dragVertical` 之后 | `APPLIED` |
| `ThinkStage.newPage`、`prepareNeighbor`、`renderDeck`、`takePrepared` | `MARK` |
| `PageView.pressAt`、`TileView.press` | `MARK press`（T4 用） |
| `PluginRuntime.event()`：`worker.post` 前、worker 里开始执行、`dispatch` 完 | `MARK tapQueue` / `luaDispatch`（只记 `tap`、`form.change`、`back`、`variant`、`page.enter`） |
| `PluginRuntime.schedulePublish`：排入时、实际发布时；`main.post` 到 `onState` 执行时 | `MARK publish` / `stateDeliver` |
| `ThinkStage.updatePluginState`：`Binding.changed` 与 `bind` | `MARK diff` / `bind` |
| `SensorController.touch()` 入口、`ThinkStage` 收到新 attention | `MARK wake` |

插件点按往返的关联：`PluginRuntime.event` 里给 `tap/form.change` 分配序号，存到该插件实例的“最近用户事件序号”；下一次发布时把序号带上（只在 `InputTrace.enabled` 时）。不改 `RuntimeObservers` 的公开接口，序号走 `InputTrace` 内部表。

**R1.3 debug 命令（`android/app/src/debug/java/moe/kiwi/thinkos/DebugReceiver.kt`）**

- `latency_on` / `latency_off` / `latency_reset`
- `latency_report`：`resultData` 返回 JSON。**设备端只做配对和汇总**，原始记录太大不走广播：
  ```json
  {"gestures":[{"id":3,"kind":"drag-h","down":123456,"firstMove":..,"firstApplied":..,"firstSwap":..,
    "followLagMs":[..最多 256 个..],"frameIntervalsMs":[..],"gcBlocking":0,"marks":{"newPage":[4.1],"renderDeck":[..]}}],
   "taps":[{"seq":7,"plugin":"weather","up":..,"queueMs":..,"luaMs":..,"publishWaitMs":..,"deliverMs":..,"bindMs":..,"swap":..}],
   "dropped":0}
  ```
  每个数组都有上限（`followLagMs` ≤ 256、`gestures` ≤ 64、`taps` ≤ 64），超了只计数到 `dropped`。整个 JSON 控制在 64KB 内（广播 resultData 的实际上限要实测，超了改成分页：`latency_report` 带 `page` 参数）。
- 配对规则：MOVE 的 `eventTime` → 第一个 `vsync ≥ 该 MOVE 被处理时刻` 的 FRAME 的 swap 完成时间。

**R1.4 主机侧测试（必须有，外部数据要有畸形输入测试）**

- JVM：`android/app/src/test/java/moe/kiwi/thinkos/perf/InputTraceTest.kt`：环形覆盖、上限、配对算法（用假的 FRAME/INPUT 序列）、`enabled=false` 时不记录。
- Python：`tools/tests/test_latency_report.py`：解析 `latency_report`，覆盖缺字段、负数、超长数组、非 JSON、`am` 返回格式的引号问题（参考 `ui_walkthrough.broadcast_json`）。

验收：`assembleDebug` 通过；release 构建（`assembleRelease` 或现有 release 流程的编译步骤）里 `InputTrace` 不产生任何记录；JVM、Python 测试通过；设备上一次手工 `latency_on` → 拖一次 → `latency_report` 有合理数据（这一步是功能确认，不是性能测试）。

### R2 基线测量（性能测试，**先告知所有者**）

用 R4.1 的脚本跑（R4.1 的最小版本要先完成；或者 R2 先用手写的短脚本，R4 再收编）。

**场景表**（竖屏，主题跟随默认，音量 0，断网；每个场景 30 轮，速度随机但用固定种子）

| 场景 | 起点 | 手势 | 主要指标 |
|---|---|---|---|
| S1 横向翻页 | 时钟页 | 快速横滑（250ms、位移 0.5 屏宽）提交 | T1、T2、T7 |
| S2 慢拖跟随 | 时钟页 | 1.5s 慢拖到 0.4 屏宽，中途反向，松手回弹 | T2、F1 |
| S3 纵向换样式 | 有多个 variant 的页 | 纵向滑动提交 | T1、T2 |
| S4 卡片栈拖动 | 有 stack 的页（`stack_mask_device.py` 用的那页） | 纵向拖卡片 | T2 |
| S5 打开 / 关闭应用 | 磁贴首页 | 点磁贴；等 1s；左缘右滑返回 | T3、T7 |
| S6 左缘返回 | 应用内 | 从 x=6 起手右滑过阈值 / 半途松手 | T1、T2 |
| S7 控制面板 | 任一页 | 顶部下拉，拖亮度滑块 2s，收起 | T1、T2 |
| S8 上滑手势 | 应用内 | 底部上滑 | T1、T2 |
| S9 设置滑块 | 设置页 | 拖一个数值滑块 2s | T2 |
| S10 插件点按 | 有 `tap` 的自带插件页（例如天气切换城市） | 点按 | T4、T5 |
| S11 触摸唤醒 | `hold_attention AMBIENT` | 点一下 | T6 |
| S12 进度条 | 播放器（本地样片，见 `tools/media/`） | 拖进度条 | T2 |
| S13 图库 | 照片页 | 连续左右滑 10 次 | T1、T2 |
| S14 后台繁忙 | 同 S10，同时另一插件在处理大状态（用 `http_fixture.py` 给天气喂 1MB 内的大 JSON，带延迟） | 点按 | T5 尾部、F5 |

每轮前后：`gfxinfo reset` / `gfxinfo framestats`、`latency_reset` / `latency_report`、`clock_reset` / `clock_report`、`stage_state`（核对结束页面对不对，场景失败要和性能失败分开报）。整轮前后各一次 `dumpsys meminfo`。另外对 S1、S5、S10 各录一次 `atrace`（`gfx view input sched freq` + 应用标签），用来确认 F1–F9 哪些成立。

产出：`artifacts/perf/R2/`，每个场景一个 JSON，外加 `summary.md`：每个场景 P50/P95/max、超时帧数、GC、时钟，以及 F1–F9 逐条“成立 / 不成立 / 证据”。**把 summary 发给所有者，确认 §1.2 的门槛后再进入 R3。**

### R3 优化方案（2026-11-19 依据 R2 基线修订，**以本节为准**）

本节取代下面的“R3 原始草案”。原始草案里的做法细节（R3.1–R3.9）仍然有效，本节引用时写“见草案 R3.x”。和草案不一致的地方以本节为准。

#### R3.A 基线归因：时间花在哪

数字都来自 `artifacts/perf/R2/`，是 P95，除非另外注明。

| 场景 | 总延迟 | 拆分 | 主因 |
|---|---|---|---|
| S1 横翻首帧 | 262ms | `prepareNeighbor` 129（其中 `newPage` 89，最大 164）+ View 遍历约 20 + 出帧下限约 33 | 拖动第一帧里同步建邻页（F2） |
| S2 慢拖 | 跟随 62ms | 96 个超限帧间隔全在每轮前 600ms，84 个在前 200ms；之后跟随 P50 33ms | 同 S1，邻页建好以后已接近下限 |
| S5 打开应用 | 112ms | `renderDeck` 60 + `newPage` 42，再翻转；29/30 轮翻转中有 > 33ms 的帧 | 抬手后同步建目标页（F3） |
| S6 左缘返回首帧 | 96ms | `beginBackSwipe` 里 `newPage` 43；同一函数先调 `clearPrepared()`，把可能已建好的页扔掉 | 同步建返回目标页 |
| S8 上滑回首页 | 跟随 81ms | `renderDeck` 41 + `newPage` 33 | 同步建首页 |
| S9 设置滑块 | 跟随 166ms | 每帧只要约 2ms，但帧间隔 117–133ms：`SliderView.update()` 只在吸附后的值变了才重画，滑块按步长一格一格跳 | **不是卡，是滑块按步长跳**（新发现） |
| S10 插件点按 | 118ms | 排队 2 + Lua 20 + **固定合并等待 51** + 差异/投递/绑定 3；余下约 40ms 是“抬手→事件投递”和“绑定→出帧” | 50ms 合并（F4） |
| T7 时钟 | 最大迟到 1017ms | S3 1 个窗口、S13 4 个窗口，**每次都正好 1017ms**（= 1000 + `PRESENT_LEAD_MILLIS` 17）；跳秒 0。S11 的 303–357ms 出现在 AMBIENT 唤醒 | 未定位，像是晚了整整一个周期或统计口径问题 |

**出帧下限**：S12（进度条）、S13（图库，邻页已预建）、S7（面板）主线程几乎没活，跟随 P50 仍是 31–34ms。API 27 上触摸按 vsync 批量处理，事件平均要等半帧才被处理，再加一帧绘制和交帧。**纯靠减少主线程工作，跟随延迟降不到 25ms 以下**。所以要么改门槛，要么做 O8 预测。

**结论**：最大的一项是**同步建 `PageView`**，S1、S2、S5、S6、S8 五个场景都受影响。方案以“少建、早建、建得便宜”为主线。

#### R3.B 门槛修订建议（所有者定稿后替换 §1.2）

| 编号 | 草案 | 建议 | 理由 |
|---|---|---|---|
| T1 首帧 | ≤ 33ms | **P95 ≤ 50ms** | 下限约 33ms，再留一帧余量 |
| T2 跟随 | ≤ 25ms | **P95 ≤ 40ms**；另加“拖动窗口内帧间隔 > 25ms 的帧数为 0”的轮次 ≥ 90% | 25ms 低于出帧下限；用帧间隔来管卡顿 |
| T2-S9 滑块 | 同 T2 | 同 T2，**按圆点位置**而不是吸附值来测（见 O5） | |
| T3 打开应用 | ≤ 50ms，翻转中无 > 33ms 的帧 | **P95 ≤ 66ms**（4 帧）；翻转中没有 > 33ms 帧的轮次 ≥ 90% | 抬手→翻转第一帧至少要一次布局和建硬件层 |
| T4 按下反馈 | ≤ 33ms | 不变 | 已达标 |
| T5 插件点按 | ≤ 50ms | **P95 ≤ 70ms**，且 `publishWaitMs` P95 ≤ 2ms | 拿掉 50ms 合并后，剩下的 Lua 时间 + 出帧约 60ms |
| T6 唤醒 | ≤ 100ms | 不变 | 已达标 |
| T7 时钟 | 迟到 0、跳秒 0 | **跳秒 0**；AWAKE/ENGAGED 下迟到 P99 ≤ 50ms、最大 ≤ 100ms；**1017ms 这类整周期迟到必须为 0** | “零迟到”测不出来（每帧都有几 ms）；整周期迟到是真问题 |
| T8 阻塞式 GC | 0 次（容忍 1 次） | 不变 | |
| T9 离线冷启动 | < 3 s | **≤ 5 s**，断网、无 Server | 进入系统后的交互延迟优先，报告同时保留原门槛判定 |

2026-11-19 所有者允许将冷启动门槛放宽提供的额外时间用于首页、邻页预建或加载资源。设计与对照测量均须确认时钟最先显示、完整绘制 ≤ 5 s，并记录首窗和完整绘制的最大值。R5 实测完整绘制最大 4,839 ms，离上限仅 161 ms，后续方案按这份余量评估。

后续基线补采最大 4,874 ms，余量为 126 ms。实现只在实际时钟窗口帧完成且完整绘制已报告后，后台预生成一份 Aero 静态壁纸。五轮验证的完整绘制最大 4,760 ms，并记录时钟帧先于壁纸就绪 130–145 ms。[离线冷启动分布](../artifacts/perf/R5/follow-up/implementation/summary.md#离线冷启动与时钟)

2026-11-19 暂定 T7 为跳秒 0、AWAKE/ENGAGED 迟到 P95 ≤ 40ms、最大 ≤ 80ms、整周期迟到 0。O7 随批次 2 一起验收，S1、S3、S13 查整周期迟到，S11 带详细记录重测 30 轮，另跑时钟专项。所有者已授权本计划的批次短测和 R5，功能确认后告知内容与时长即可运行。

#### R3.C 工作包总览

先做诊断（D），再按顺序做优化（O）。每个 O 单独一组改动，能单独回退；做完一项先做功能确认（不算性能测试），短测按 R3.F 的批次统一告知后再跑。

2026-11-19 批次 1 复核后，批次 2 先补优化前关闭建页细分探针的 S1/S5 各 20 轮。P50 若偏离 R2 超过 15%，先查差异。后续先做 O2 的磁贴和 Aero 建页优化，再做 O1、O3，随后批次 3、4 和 R5。后续受改动影响的场景各 20 轮，S1b 以及 R5 各 30 轮。CPU 对照取批次 1 深色时钟页、关闭探针的 60 秒采样，AWAKE 单核 46.36%，AMBIENT 6.63%。

2026-11-19 所有者确认 O2 结束后依次执行 O1、O3、批次 3 和 R5。批次 3 的 S1、S2、S5、S6、S8、S13 各 10 轮，同批采集 PSS。分片建页暂缓。

| 顺序 | 编号 | 内容 | 主要改善的场景 | 预期 |
|---|---|---|---|---|
| 1 | D1 | 拆分 `newPage` 耗时 | — | 决定 O1 / O2 的力度 |
| 1 | D2 | 时钟 1017ms 定位 | T7 | 决定 O7 怎么修 |
| 1 | D3 | 插件点按剩余 40ms 的拆分 | S10 | 决定 O6 的第二步做不做 |
| 2 | O7 | 修时钟整周期迟到（D2 确认后提前） | T7 | 0 次 |
| 3 | O5 | 滑块圆点连续跟手 | S9 | 跟随 166 → 约 35ms |
| 4 | O4 | 手指按着时提帧、触摸路径去分配 | S2、全部 | 减少帧间隔超限 |
| 5 | O6 | 用户事件后立即发布状态 | S10、S14 | 118 → 约 65ms |
| 6 | O2 | 先优化磁贴应用页和 Aero 的建页成本 | 冷路径、缓存未命中 | 两页建页 P95 ≤ 50ms |
| 7 | O1 | 页面预热缓存（邻页、返回目标、首页、打开目标） | S1、S2、S5、S6、S8 | 首帧去掉 30–130ms |
| 8 | O3 | 翻转硬件层提前建好 | S5 | 翻转首帧更稳 |
| 9 | O8（可选） | 拖动位置预测 | 全部拖动 | 跟随再降约半帧 |
| — | 不做 | 草案 R3.5（后台算差异）、R3.6（事件快速通道）、R3.8（触摸唤醒）：R2 显示没到启动条件。R3.9 系统提频推迟到 R5 之后再看 | | |

#### R3.D 诊断

**D1 建页耗时拆分**（只在 debug 构建、`InputTrace.enabled` 时记录）

- 在 `PageView` 构造和 `update(page, widgets)` 里加子标记：`page.parse`（读 `page.view`、`LayoutVocabulary`）、`page.slots`、每个组件的创建（标记名带组件类型，比如 `comp:clock`、`comp:tile`、`comp:image`、`comp:form`、`comp:list`）、`page.backdrop`（`TileBackdrop`）、`page.measure`、`page.layout`，以及第一次 `draw`（`page.firstDraw`，在 `dispatchDraw` 里记一次）。
- `TraceReport` 里按页面键（`page.key`）汇总：每页建页总时长，以及耗时最多的 5 个子标记。
- 只用一次短测采集（S1、S5、S6、S8 各 10 轮），对应 R3.F 批次 1。
- 产出 `artifacts/perf/R3/D1/summary.md`，表格列出：页面键、建页 P50/P95、最贵的三项、PSS 增量（建页前后各读一次 `Debug.getPss()`；只在诊断里读，它本身要几十 ms，不要放进正式测量）。
- **判断规则**：某页建页 P95 > 40ms，且最贵一项占一半以上，就在 O2 里专门处理那一项；否则只靠 O1 预热。

**D2 时钟整周期迟到**

- 在 `ClockStats.drawn()` 里，当 `late > 100` 时额外记录（上限 32 条，只在 debug）：`periodMillis`、`attention`、`isShown`、`windowVisibility`、挂到窗口后过了多久、所属页面键，以及最近一次 `showTime` 是谁调的（`ticker` / `stage tick` / `attach`）。`ClockView` 里加一个 debug 字段记录调用来源，不影响 release。
- 待验证的假设（按可能性排序）：
  1. **一个周期 60s 的表盘**（没有秒，比如磁贴页或世界时钟）跨分钟时，`ClockTicker` 的轮询帧刚好落在边界前，下一次机会就到了 1s 后的舞台 tick。S13 结束在 `tiles.apps`，S3 是 `world` 样式，都符合。
  2. 预热或刚取回的页（`prepared`、`neighbor`）上的表盘：不可见期间被 `tick(…, AMBIENT)`，重新可见那一帧画的是旧桶。
  3. 统计口径：同一 `identityHashCode` 被新建的表盘复用，或 `periodMillis` 在两次记录之间变了（attention 切换）。
- 再写一个 JVM 测试，按诊断记录复现（`ClockSchedule` 是纯函数，可以直接测）。
- S11 唤醒时的 303–357ms 约等于 AMBIENT 3fps 的一拍（333ms）。D2 要同时确认，唤醒那一刻表盘是不是在等舞台 tick，而不是等 `ClockTicker`。

**D3 插件点按剩余的约 40ms**

- 在现有 `taps` 记录里补两个时间点：`upToEvent`（ACTION_UP → `PluginRuntime.event()` 被调用；页签点击可能经过 Android 的 `performClick` 投递，多隔一条主线程消息）和 `bindToSwap`（绑定完成 → 下一次 swap）。
- 如果 `upToEvent` P95 > 10ms，在 O6 第二步处理；否则不动。

#### R3.E 优化工作包

**O1 页面预热缓存（解决 F2、F3，以及新发现的返回、上滑）**

按所有者的媒体停点决定，`player.library` 及含 nowplaying、媒体封面绑定（含间接 widget 引用）的页打开时现建。`PreparePolicyTest` 覆盖此排除规则。三页预热实测 PSS 增 11.3 MiB，缓存上限为三个。运行时 PSS 超预算只留 BACK 的分支仍待实现。[批次 3](../artifacts/perf/R3/batch-3/summary.md)

所有者已同意预热页可加载 Bing 封面。保留图片组件原有加载路径，验证联网失败可显示已有缓存，连续翻页静止 10 次时同 URL 在缓存有效期内最多请求一次。其他插件可见副作用仍是停点。预热只在页面静止至少 300 ms、手指未按下时逐页开始。S1b 用固定种子，在静止后 0–500 ms 内随机按下并横滑，在 Aero 与磁贴各测 30 轮，记录撞上预热的 DOWN 调度等待及首帧，门槛分别为 P95 ≤ 16 ms 和 T1。记录三个预热页完成布局、解码后的 PSS 增量，以及重复导航中的 Views、ViewRootImpl 和 Activities 数。

2026-11-19 所有者确认 `player.library` 和包含 `nowplaying` 或媒体封面绑定的页面在打开时新建，含通过 widget 间接引用的情况。因此引用 `player.now` 的 `home.main` 也走冷路径。磁贴、Aero、照片和符合条件的内容页继续按 O1 预热。隐藏期图片加载保留本地资源和图库的官方 Bing 图源，其他远程图片等到可见再加载。

1. **新类 `ui/PageCache.kt`**，替换 `ThinkStage` 里单个 `prepared: PageView?`。槽位：`LEFT`、`RIGHT`、`BACK`（返回 / 上滑的目标）、`LAUNCH`（按下磁贴时预测的目标）。每个槽最多一页，总数上限 `MAX_WARM = 3`（登记 §7）；超了按“最久没用”淘汰，`BACK` 最后才淘汰。
   - 接口：`take(page, variant): PageView?`、`put(slot, view)`、`drop(slot)`、`clear()`、`retainOnly(keys)`、`views`（给 `tick`、`colors`、`night`、`bind`、`localeChanged`、`renderSettingsChanged` 遍历用）。现在所有对 `prepared?.xxx` 的调用都改成遍历 `cache.views`。
   - 预热页的状态和现在的 `prepared` 一样：`INVISIBLE`、`active(false)`、按 AMBIENT tick、平移归零、已 measure/layout。
   - 纯逻辑（槽位、淘汰、上限）拆成不依赖 Android 的 `PageCachePolicy`，写 JVM 测试。
2. **哪些页可以预热**：纯函数 `PreparePolicy.allowed(page, reason)`，JVM 测试覆盖每种 layout。
   - 允许：`tiles`、`aero`、`stage` 的照片页、普通内容页（`focus` / `list` / 时钟页）。
   - 不允许：`videoStage`、投屏页、电视直播页、`layout == "panel"` 且含媒体的页、设置页、引导页（`onboarding` 角色）。
   - **开工前先核对**：`page.enter` / `page.leave` 由 `DeckRouter` 发出。确认构造、挂载和布局不触发媒体加载、插件事件、`tos.*` 回调或封面预取以外的网络请求。
3. **什么时候填各个槽**：
   - `LEFT` / `RIGHT`：页面静止后（现有 `queuePreview()` 的条件），用 IdleHandler **每次只建一页**，下一次空闲再建另一页，避免一个空闲回调里连建两页。
   - `BACK`：打开应用（LAUNCH）或进入接管页时，被翻走的那页（通常是 `tiles.apps`）在 `retire()` 里不移除，放进 `BACK`。条件：它等于 `router.previewBack()` 返回的页。
   - 上滑回首页（S8）的目标如果不是 `BACK` 里那页，空闲时预建首页放进 `BACK` 之外的空槽（上限内）。
   - `LAUNCH`：`beginTouch()` 时，如果 `current?.openAt(downX, downY)` 有目标、目标允许预热、`BACK`/`LEFT`/`RIGHT` 里都没有它，就挂一个 IdleHandler 来建。手指越过 slop、CANCEL、长按触发（`longPress`）时丢弃；UP 后由 `renderDeck` 取用。
4. **取用的地方**：`prepareNeighbor()`、`renderDeck()`（含 LAUNCH/RETURN 分支）、`beginBackSwipe()`、`beginUpSwipe()` 都先 `cache.take()`，取不到再 `newPage()`。**删掉 `beginBackSwipe()` 里无条件的 `clearPrepared()`**，改成只丢掉和返回目标不同的 `LAUNCH` 槽。
5. **什么时候清空**：保留现有 `clearPrepared()` 的全部调用场景：主题切换、尺寸变化、ASLEEP、目录更新（`updateCatalog` 时 `retainOnly` 新目录里的键）、语言切换（`localeChanged` 后可以留着，已经有遍历刷新）。新增：PSS 超过预算（见下）时只留 `BACK`。
6. **预热页上的时钟**：预热页的 `ClockView` 不能每秒被 `ClockTicker` 叫醒去 `invalidate`，白白耗电。做法：`ClockView` 在 `onVisibilityAggregated(false)`（API 24+）时 `ClockTicker.remove(this)`，变回可见时 `lastBucket = -1; showTime(now); ClockTicker.add(this)`。这一条同时可能修掉 D2 的假设 2。改完跑 `ClockScheduleTest` 和 `clock_accuracy_device.py`。
7. **内存预算**：用 D1 的每页 PSS 增量估算，3 个预热页加起来 ≤ 40MB（登记 §7）；R5 的峰值 PSS < 350MB 是硬门槛。
8. **JVM 测试**：`PageCachePolicyTest`（上限、淘汰顺序、`BACK` 优先、`retainOnly`）、`PreparePolicyTest`。
9. **功能确认**（真机，非性能测试）：`nav_back_device.py`、`up_gesture_device.py`、`gallery_device.py`、`gallery_short_swipe_device.py`、`stack_mask_device.py`、`tile_regression.py` 全过；再用 `stage_state` 确认预热页不可见、不在无障碍树里（`device_hierarchy.py`）。
10. **预期**：S1/S2/S6/S8 拖动窗口里不再出现 `newPage` 标记（缓存命中率 ≥ 95%）；S5 的 `renderDeck` P95 从 60 降到 < 10ms。

**O2 让建页本身变便宜（按 D1 结果，只做 D1 证实的）**

进一步拆分磁贴的文字排版、图标、背景、画笔和尺寸计算，优先复用可共享资源。Aero 先核对隐藏的开始菜单与其他装饰，延迟创建可以安全延期的子树。两页建页 P95 目标均为 50 ms。外观、无障碍和插件事件顺序照旧。若仍需分片建页，先汇报实测后等待决定。

候选项，D1 没证实的不做：
- 页面声明（`page.view` JSON）解析、`LayoutVocabulary` 查表、模板预编译按 `page.key + page digest` 缓存，新建同一页时复用。注意：插件热更新时 digest 变了要失效。
- `TileBackdrop`、字形（`GlyphResources`）、`Typeface`、`Paint` 用进程级共享缓存，不要每页各建一份。
- 同一页文字在构造时就 `measure` 一遍、布局时又量一遍的，去掉一次。
- 如果某页建页 P95 仍 > 40ms，而且耗时分散在很多组件上，可以分片建：IdleHandler 每次只建几个组件，`PageView` 记录建到哪了，可见前补完。**这一步改动大，做之前先问所有者。**
- **不做**：在后台线程构造 View。组件里有 `Handler`、`Choreographer`、动画器，而且已经出过在建页时就注册回调的情况，风险大。

**O3 翻转硬件层提前建好（F8）**

- 见草案 R3.3 第三点。`LAUNCH` / `BACK` 槽里的页放进缓存时，对 `AppTransition.flipTargets()` 会转的 view 先 `setLayerType(HARDWARE)` + `buildLayer()`；动画开始时只启动属性动画。
- 缓存被丢弃、`AppTransition.snap()`、动画取消、动画结束这四条路径，都要撤回 `LAYER_TYPE_NONE`，不然一直占显存。写一个计数器（debug 用），`stage_state` 里报“当前硬件层数”，功能确认时检查回到 0。
- 预期：S5 翻转中出现 > 33ms 帧的轮次 ≤ 10%。

**O4 手指按着时提帧、触摸路径去分配（F1、F7）**

- 做法见草案 R3.1、R3.7。补充：`beginBackSwipe`、`beginUpSwipe`、`panelGesture` 的 DOWN 也要设 `gestureActive` 并 `clock.wake()`，不只 `beginTouch`。
- `FrameGovernor` 加 `touching` 字段，规则写成 `when`。JVM 测试：AMBIENT + 触摸 → 60；ASLEEP + 触摸 → 不直接 60（先等 attention 变化）；减少动画开着 → 5。
- 触摸和节拍路径里所有 `in setOf(...)` 改成 `when` 或 `EnumSet` 常量；`SliderView.onDraw` 里每帧 `RectF(...)` 改成复用成员变量。

**O5 滑块圆点连续跟手（S9，新增）**

- `ui/form/FormWidgets.kt` 的 `SliderView`：加 `dragFraction: Float?`。拖动中 `onDraw` 用手指的原始位置（`valueAt` 吸附前的比例）画圆点和进度条；`current`（吸附后的值）只用来显示数字、做预览、提交。
  - `update(x)`：每次都设置 `dragFraction` 并 `invalidate()`；只有吸附值变了才调 `onPreview`（保持现在的行为，亮度预览不会更频繁）。
  - UP：`dragFraction = null`，圆点落到吸附值的位置。距离超过 2dp 时用 80ms 的 `Motion.EASE` 过渡，否则直接落位；系统动画关闭时直接落位。
  - CANCEL / 失焦 / detach：回到 `committed`，和现在一样。
  - RTL：原始比例同样镜像。
- 把“原始比例 ↔ 圆点 x ↔ 吸附值”抽成纯函数（`SliderMath`），JVM 测试：边界、步长、RTL、`maximum == minimum`。
- 检查其它滑块是否有同样问题：`DeviceComponents` 的面板滑块（S7 跟随 41ms，看起来没有）、`MediaWidgets` 进度条（S12 正常），代码里确认一遍，有就同样处理。
- 测量口径：`InputTrace.applied(SLIDER, ...)` 改成每次圆点移动都记（现在只在值变时记）。
- **视觉行为变化**：“拖动中圆点连续、松手吸附到刻度”。这是常见做法，但它改了 `docs/04-ui-spec.md` 的控件行为，做之前在 §6 里请所有者确认一次。

**O6 插件点按往返（F4，D3）**

- 第一步：见草案 R3.4（`PublishPolicy`：用户事件后 300ms 内 0ms 合并，窗口外仍 50ms）。补充一条防抖：窗口内第一次立即发布，之后同一窗口内的发布至少隔 16ms，避免插件连续改状态时主线程每帧都绑定。
- 第二步（2026-11-19 根据 D3 实测修订）：`TabsComponent` 的原生页签在单指有效 UP 同步调用 `performClick()`。欢迎页的 `PageView.tapAt` 已在 UP 内投递。无障碍仍走原生 ACTION_CLICK 路径，其他原生控件维持原事件路径。见 [回调路径实测](../artifacts/perf/R3/batch-2/O6-before/dispatch-findings.md)。
- JVM：`PublishPolicyTest` 覆盖 0 / 299 / 300 / 301ms 和防抖。
- 功能确认：`weather_device.py`、`settings_controls_device.py`、`player_ui_device.py` 行为不变。
- 预期：S10 `plugin_ms` P95 约 65ms，`publishWaitMs` < 2ms；S14 在负载下 P95 ≤ 80ms。

**O7 时钟整周期迟到（按 D2 结果）**

真机确认舞台在桶边界前 1 ms 写回旧桶。实现统一 `ClockSchedule.displayed(WallClock.now())` 时基，并在 `ClockView` 拒绝普通路径回写旧桶，保留校时与重新挂载的回退路径。[D2 诊断](../artifacts/perf/R3/D2/summary.md) · [批次 2 时钟结果](../artifacts/perf/R3/batch-2/summary.md#时钟)

S11 的 303–357 ms 迟到在打开明细的 30 轮中未复现，待观察。

- 如果是假设 1（分钟表盘错过边界）：`ClockTicker.deliver()` 的判断基准改成“显示时刻”，并在 `schedule()` 里，轮询帧落在边界前时继续轮询下一帧，而不是睡到下一次 tick。写 JVM 测试，构造“轮询帧在边界前 1–16ms”的情况。
- 如果是假设 2：O1 第 6 点（不可见时退出 `ClockTicker`，可见时立即 `showTime`）已经覆盖；再补一个测试。
- 如果是假设 3（统计口径）：修 `ClockStats`（按对象而不是 `identityHashCode` 记录，周期变化时重置该表盘的桶），并在报告里注明 R2 的 1017ms 是误报。
- 任何修改都要再跑 `clock_accuracy_device.py`（性能测试，先告知）。

**O8（可选）拖动位置预测**

- 只在 O1–O6 做完、R5 的 T2 仍未达标时考虑。
- 在 `ThinkStage.dragTo` 里，用 `VelocityTracker` 当前速度把位置外推 `predictMs`（上限 1 帧，约 16ms），反向或减速时立即收回预测量；松手时以真实位置为准。
- 新增隐藏设置 `gesture.predict_ms`（0–16，默认 0），按 ADR-017 加进设置 schema，Server 可下发。默认关，所有者手动试过再决定默认值。
- 风险：反向时“超出手指”。所有者主观确认必须通过。

#### R3.F 测量节奏（每批都是性能测试，跑之前告知所有者）

为了不让每改一项就告知一次，按批次申请：

| 批次 | 时机 | 内容 | 时长约 |
|---|---|---|---|
| 1 | D1–D3 代码完成、功能确认后 | S1、S3、S5、S6、S8、S10、S13 各 10 轮（含 D1 子标记、D2 时钟记录、D3 时间点） | 25 分钟 |
| 2 | O7、O5、O4、O6 完成后 | S2、S9、S10、S14 各 10 轮，S1、S3、S13 各 10 轮核对整周期迟到，S11 带明细 30 轮，另跑时钟专项 | 约 20 分钟 |
| 3 | O1、O3 完成后 | S1、S2、S5、S6、S8、S13 各 10 轮，外加一次 `dumpsys meminfo` 对照 | 25 分钟 |
| 4 | O2 完成后 | 相关场景 10 轮 | 视情况 |
| R5 | 全部完成 | 见 R5：全部场景 30 轮、冷启动、十分钟待机、CPU、时钟 | 约 2 小时 |

每批结果写到 `artifacts/perf/R3/batch-N/`，`summary.md` 里对照 R2 列出改了什么、对应指标的变化，以及代价（建页次数、PSS、CPU）。某项改完没有改善，就回退那一项并写明原因，不要留着。

#### R3.G 预期结果（用来判断方案是不是走偏，不是承诺）

| 场景 | R2 P95 | 做完 O1–O6 后预期 |
|---|---|---|
| S1 横翻首帧 | 262ms | ≤ 50ms |
| S2 慢拖跟随 | 62ms | ≤ 40ms，前 200ms 不再有超限帧 |
| S5 打开应用 | 112ms | ≤ 66ms |
| S6 左缘返回首帧 | 96ms | ≤ 50ms |
| S8 上滑跟随 | 81ms | ≤ 40ms |
| S9 设置滑块跟随 | 166ms | ≤ 40ms |
| S10 插件点按 | 118ms | ≤ 70ms |
| T7 整周期迟到 | 5 个窗口 | 0 |

### R3 原始草案（已被上一节取代，保留作参考）

按收益/风险排序。R2 证明不成立的项跳过并在报告里说明。

**R3.1 手指在屏幕上时提帧（F1）**

- `ThinkStage` 加 `gestureActive`：DOWN 置真；UP/CANCEL 后，等 `springs` 全部结束再置假。
- `tick()` 里 `governor.activeAnimations = ... || gestureActive`。
- `beginTouch()` 末尾调 `clock.wake()`，让节拍立即提到 60，不等下一次延迟调度。
- `FrameGovernor.fps` 里把 `attention in setOf(...)` 换成 `when` 或直接比较（顺带解决 F7 的一部分）。
- 抽出纯函数（例如 `FrameGovernor` 加 `touching` 字段），JVM 测试：AMBIENT + 触摸 → 60；ASLEEP + 触摸 → 由 attention 先变化决定，不在 ASLEEP 直接跑 60；减少动画开着 → 仍为 5。
- 风险：多耗电。只在手指按着和回弹期间，R5 用 AMBIENT CPU 门槛（T9）兜底。

**R3.2 邻页提前建好（F2）**

- 把 `prepared: PageView?` 改成最多两个（左、右各一）的小容器，例如 `PreparedPages`（新类，放 `ui/`），接口：`take(page, variant)`、`clear()`、`retain(keys)`。
- `previewIdle` 不再只认照片页：对 `router.previewPage(-1)`、`previewPage(1)` 都预建，但**跳过**：视频舞台（`videoStage`）、投屏和电视直播页、`layout == "panel"` 且含媒体的页、设置页、引导页。规则写成纯函数 `PreparePolicy.allowed(page)`，JVM 测试覆盖每种 layout。
- 预备页的约束沿用现有做法：`INVISIBLE`、`active(false)`、`tick(..., AMBIENT)`、不跑动画。**实现前必须确认构造 `PageView` 不会触发任何插件可见的副作用**（`page.enter`、媒体加载、网络、`tos.*` 回调）；如果有，就停在照片页的现状，报告给所有者。
- 内存预算：两个预备页都在时 PSS 增量记录下来；R5 的 T9（< 350MB）是硬门槛。`attention` 为 ASLEEP、主题切换、尺寸变化、目录更新时 `clear()`（现有 `clearPrepared()` 的调用点都要保留）。
- 预建时顺带 `measure/layout`（现有代码已经这样做）。
- 验收：S1、S3、S13 的首帧 `newPage` 标记从拖动窗口里消失；T1 达标。

**R3.3 打开应用不卡那一帧（F3、F8）**

- 按下时预测：`beginTouch()` 里如果 `current?.openAt(downX, downY)` 返回目标，且目标页允许预建（同 `PreparePolicy`），用 IdleHandler 预建目标页（最多 1 个“启动预备页”，和 R3.2 的容器分开或共用都可以，要有上限）。手指越过 slop、CANCEL、或 600ms 内没有抬起（长按）时丢弃。
- `renderDeck()` LAUNCH 分支先 `takePrepared`，没有再 `newPage`。
- `AppTransition`：在预建完成时就对要转的 view 调 `setLayerType(HARDWARE)` + `buildLayer()`，动画开始时只启动属性动画；动画结束照旧撤层。注意 `snap()` 和取消路径都要撤层，不然会一直占显存。
- 确认 UP → `router.open` → `store.update` → `ThinkStage` 收集 → `renderDeck` 是不是同一个主线程消息内完成（`Dispatchers.Main.immediate`）。如果中间隔了一次 post，记录下来；不要为此改 Store 的语义。
- 验收：S5 的 T3 达标；`docs/10` §15 那一行改成“已修，数据见 …”。

**R3.4 用户事件后的状态立即发布（F4）**

- `PluginRuntime`：实例里记 `userEventElapsed`（`tap`、`form.change`、`back`、`variant`、`page.enter`；现有的 `updateTapElapsed`、`onboardingTapElapsed` 不要挪用，它们另有用途）。
- `publishState(instance)`：距最近用户事件 ≤ 300ms 时用 0ms 合并（同一 worker 消息循环里多次 `set` 仍只发一次：`publishPending` 机制不变，只是延迟变成 0）；否则仍是 50ms。把选择延迟的逻辑抽成纯函数 `PublishPolicy.delay(nowElapsed, lastUserEventElapsed)`，JVM 测试边界值。
- 两个数字（0ms、300ms 窗口）是内部常量，登记在 §7。所有者若要可调，再加隐藏设置项。
- 风险：插件在点按后连续多次改状态 → 主线程多次 bind。用 R2/R5 的 `bind` 标记看主线程占用；如果拖动中出现了这种 bind，把立即发布限制为“窗口内第一次立即，之后回到 16ms”。
- 验收：S10 的 `publishWaitMs` 从约 50 降到 < 5；T5 达标；`tools/tests` 里涉及插件状态的设备测试（`weather_device.py`、`settings_controls_device.py`）功能不变。

**R3.5 状态差异在后台算（F6）**

- 先看 R2 的 `diff`、`bind` 标记：`diff` P95 < 1ms 就不做这一项。
- 做的话：在 `schedulePublish` 的 worker 里用 `published[id]`（上一次发布的快照）和新快照算 `Binding.changed`，把 `(snapshot, changedPaths)` 一起 post 到主线程；`RuntimeHost.onState` 和 `ThinkStage.updatePluginState` 增加带 `changed` 的重载，旧接口保留给其它观察者。注意 `ThinkStage.states` 的旧值和 worker 的 `published` 必须是同一份历史，否则会漏更新：插件重启、目录更新（`states.keys.retainAll`）时要强制全量（`setOf("")`）。
- JVM 测试：worker 侧和主线程侧算出的路径一致；重启后第一次是全量。

**R3.6 事件队列尾部延迟（F5）——先量，后定**

- R2 的 S14 里 `queueMs` P95 > 16ms 才处理。
- 方案（需所有者看过再做，因为会改插件事件的顺序语义）：给 worker 增加一个“用户事件”快速通道：`tap/form.change/back` 用 `postAtFrontOfQueue`，**但同一插件的用户事件之间保持先后顺序**，并且不能越过该插件自己还没执行的用户事件。写成纯类 `EventLanes`，JVM 测试顺序保证。
- 不要把 Lua 挪到主线程，不要为每个插件开线程（内存）。

**R3.7 触摸路径去分配（F7）**

- `ThinkStage` 里所有 `in setOf(...)` 换成 `when` 或 `==` 比较；`Attention`、`Transition` 的集合判断改成扩展属性或预建的 `EnumSet` 常量。
- 用 R1 的 GC 计数和 `atrace` 确认拖动期间阻塞 GC 为 0（T8）。

**R3.8 触摸唤醒（F9）——先量，后定**

- S11 的 T6 不达标才做。可能的做法：`ThinkStage.beginTouch` 在 AMBIENT/ASLEEP 时先本地把 `governor.attention` 视作 AWAKE 并 `clock.wake()`，不等 Store 回流；Store 仍是唯一真相，回流后以 Store 为准。改之前确认 `ModeController`/`SensorController` 对“触摸唤醒”有没有亮度渐变等要求（`docs/45-modes.md`）。

**R3.9（可选，要所有者决定）系统层触摸提频**

- 只在 R3.1–R3.7 做完、R5 仍有场景不达标、且 atrace 的 `freq` 显示手势开始时 CPU 还在低频时才考虑。
- 写在 `system/` 新模块里，root 守护进程监听触摸设备节点，按下时临时提高 `scaling_min_freq`，抬起后 N ms 恢复；N、频率都走设置 schema 下发。主机测试照 `system/` 现有模块的方式写；**装进设备、重启都是人工**，写好步骤交给所有者。应用里不加 `su`（ADR-033）。
- 这一项要写 ADR 或在 `docs/08` 补记，所有者同意后才开始。

### R4 端到端仿真测试

“仿真”指：真机 + 经系统输入管线注入的触摸 + 断网 / 本机假服务端 + 可控后台负载。模拟器不行（armv7、API 27、依赖 root 模块）。

**R4.1 触摸轨迹注入**

- `tools/tests/android/ThinkTouch.java` 增加 `testPath`：参数是一个推到设备上的轨迹文件路径（`/data/local/tmp/thinkos-path-*.txt`，每行 `t_ms x y`），按时间戳注入 DOWN / MOVE / UP（或 CANCEL），用 `injectInputEvent(event, false)`（异步，避免每个 MOVE 等待注入完成而拉长轨迹；DOWN/UP 用 `true`）。上限：≤ 2000 个点、≤ 10s、坐标在屏幕内，超了直接拒绝。
- `tools/device_touch.py` 增加 `path(device, points, *, finish='up')`，沿用现有的 jar 构建和推送方式；运行结束删除推送的轨迹文件。
- 轨迹生成（新文件 `tools/gestures.py`，纯函数，无设备依赖）：`fling(start, end, ms)`、`slow_drag(start, end, ms, reverse_at=None)`、`edge_swipe(...)`、`tap(x, y, hold_ms)`；按 60Hz 采样（真实触摸屏可能是 100Hz 以上，提供 `rate` 参数，默认 120），可选有界随机抖动（固定种子）。
- 主机测试 `tools/tests/test_gestures.py`：点数上限、时间单调、坐标边界、同种子同结果。

**R4.2 场景脚本 `tools/tests/responsiveness_device.py`**

- 参数：`--serial`（默认取 `ANDROID_SERIAL`）、`--scenario`（可多选，默认全部）、`--repeat`（默认 20，上限 100）、`--seed`、`--output`（必填）、`--baseline`（可选，之前的结果目录）、`--theme`（固定主题，与 R2 比较用 DARK）、`--online`（默认断网，用 `tools/app_network_block.py`）、`--fixture`（启动 `tools/tests/http_fixture.py` 供 S14）、`--atrace`（只对指定场景录）。
- 前置检查照 `clock_frame_probe.py`：应用在前台、媒体音量为 0；`device_recovery.capture()` 记下页面和设置，结束（含异常、Ctrl-C）一律 `restore()`。不改系统动画设置。
- 每个场景：进到起点（`debug page`/`settings`/`hold_attention`）→ `latency_reset`、`gfxinfo reset`、`clock_reset` → 注入 → 等稳定（弹簧结束，最多 2s）→ 收集 → 核对 `stage_state` 的结束页面。
- 结果：每场景一个 JSON（原始汇总 + P50/P95/max + 是否达标 + 环境信息：APK 版本、git rev、dirty、attention、主题、方向），以及总表 `summary.json`、`summary.md`。
- `--baseline` 时对比：同场景同指标 P95 退化 > 20% 判失败；功能失败（结束页面不对）单独列出。
- 退出码：0 全部达标；1 有性能不达标；2 有功能失败；3 环境问题（设备不在、应用不在前台）。

**R4.3 后台负载与网络**

- S14 的慢服务端：`http_fixture.py` 增加延迟和大响应选项（大小有上限，例如 1MB），并能返回畸形 JSON，确认插件解析出错不影响触摸。
- 可选 CPU 负载：在 `/data/local/tmp` 跑一个有时限的忙循环（`timeout 30 sh -c 'while :; do :; done'`），不持久，结束确认进程已退出。属于性能测试，先告知。

**R4.4 主机侧回归（不需要设备，随 `unittest discover` 一起跑）**

- 结果对比逻辑（`responsiveness_device.py` 里抽出 `compare(baseline, current)` 纯函数）的单元测试。
- `latency_report` / `gfxinfo framestats` 解析的畸形输入测试。
- JVM：`FrameGovernor`、`PreparePolicy`、`PublishPolicy`、`EventLanes`（如果做了）、`InputTrace` 配对。

### R5 验收（性能测试，**先告知**）

1. `cd android && ./gradlew assembleDebug`，JVM 测试全过。
2. `python3 -I -m unittest discover -s tools/tests` 全过（含 `test_ui_restraint.py`）。
3. `tools/deploy.sh`，`adb install -r` 成功。
4. 功能回归：`nav_back_device.py`、`up_gesture_device.py`、`panel_device.py`、`gallery_device.py`、`gallery_short_swipe_device.py`、`stack_mask_device.py`、`settings_controls_device.py`、`weather_device.py` 至少这些；需要媒体服务器的（`player_device.py`、`media_ui_device.py`）如果条件不具备，写明没跑。
5. `responsiveness_device.py --baseline artifacts/perf/R2` 全场景，T1–T8 达标。
6. T9：断网冷启动（`pre_m1_cold_start.py`）、`tools/bench.sh 600` 待机十分钟、AMBIENT CPU、`clock_accuracy_device.py` 覆盖时钟页翻走和返回。
7. 不新增依赖（`git diff` 里 `build.gradle.kts` 的 dependencies 没变）。

产出：`artifacts/perf/R5/`，`summary.md` 对照 R2 逐项列出改善和代价（CPU、内存）。

## 4. 文档更新（随对应改动一起）

- `docs/10-known-issues.md` §15“打开应用时有一帧停顿”：改为结果和数据位置。
- `docs/33-apps-and-back.md` §8 性能一行：补测量结果。
- `docs/36-task-list.md`：加一个任务条目（编号由所有者定），状态随进度更新。
- `tools/README.md`：登记 `responsiveness_device.py`、`gestures.py`、`device_touch.path`。
- `README.md` 文档索引：加本文一行（目前 47 号也没登记，一并问所有者要不要补）。

## 5. 风险

| 风险 | 处理 |
|---|---|
| 预建页面带来插件可见的副作用 | R3.2 开头先确认；有副作用就不扩大预建范围 |
| 预建页面占内存 | 最多 2+1 个；ASLEEP 清空；T9 硬门槛 |
| 立即发布让主线程 bind 变多 | 只在用户事件后 300ms 内；用 `bind` 标记监控 |
| 提帧多耗电 | 只在手指按着和回弹期间；AMBIENT CPU 门槛 |
| 探针影响被测对象 | 环形缓冲预分配、FrameMetrics 回调不在主线程；R5 再跑一轮关掉探针只看 gfxinfo，确认两者结论一致 |
| 和未提交的改动冲突 | 不覆盖；冲突时停下来问所有者 |
| 注入触摸和真实手指不同（采样率、压力） | 轨迹用 120Hz；报告注明；关键结论（S1、S5）请所有者用手指主观确认一次 |

## 6. 需要所有者决定的事

1. §1.2 的门槛：R2 之后的修订建议见 R3.B。
2. O5 滑块“拖动中连续、松手吸附”的视觉行为变化。
3. O2 的分片建页（只在 D1 证明需要时才会问）。
4. O8 拖动预测做不做，以及默认值（隐藏设置 `gesture.predict_ms`）。
5. R3.F 的测量批次：按批告知后再跑。
6. R3.6 是否改插件事件的排队方式：R2 显示不需要，暂不做。
7. R3.9 系统层触摸提频做不做：推迟到 R5 之后再看。
8. 新任务在 `docs/36` 里的编号，`README.md` 索引要不要补 47、48。

## 7. 内部常量登记

| 常量 | 值 | 位置 | 说明 |
|---|---|---|---|
| 用户事件后立即发布窗口 | 300ms | `PublishPolicy` | R3.4 |
| 立即发布延迟 | 0ms | `PublishPolicy` | R3.4；窗口外仍为 50ms |
| 预备页上限 | 邻页 2 + 启动 1 | `PreparedPages` | 草案 R3.2、R3.3，已被下一行取代 |
| 预热页上限 | 3 页（槽位 LEFT / RIGHT / BACK / LAUNCH，按最久未用淘汰，BACK 最后） | `PageCachePolicy` | O1 |
| 预热页内存预算 | 合计 ≤ 40MB PSS，超了只留 BACK | `PageCache` | O1 |
| 邻页空闲预热门槛 | 静止 300 ms 且手指未按下 | ThinkStage | O1，S1b 检查触摸等待 |
| Aero 静态壁纸 | 1 份 ARGB_8888，≤4 MiB，每次只光栅化 1 份 | AeroWallpapers | 800×1280 约 3.91 MiB。尺寸或明暗配色变化时弃旧结果，壁纸图样随 APK 更新。较大尺寸沿用原绘制。Android 渲染端的纹理计入实测 PSS |
| 立即发布后的最小间隔 | 16ms | `PublishPolicy` | O6 |
| 滑块松手落位过渡 | 80ms，距离 ≤ 2dp 时直接落位 | `SliderView` | O5 |
| 时钟诊断记录 | 迟到 > 100ms 时记，最多 32 条 | `ClockStats`（debug） | D2 |
| 拖动预测上限 | 16ms，默认 0（隐藏设置 `gesture.predict_ms`） | 设置 schema | O8，可选 |
| `InputTrace` 缓冲 | 2048 条 | `InputTrace` | R1 |
| 广播报告上限 | 64 KiB，手势和插件事件各 64 条，每数组 256 个值 | `TraceReport` | R1.3 |
| 原生点击关联 | UP 后 250ms，下一次 DOWN 后作废 | `InputTrace` | 覆盖 Android View 将点击回调投递到后续主线程消息的路径 |
| 探针配对上限 | 2s | `InputTrace`、`TraceReport`、`PluginRuntime` | 限制过期唤醒、帧和状态事件的关联 |
| 轨迹注入上限 | 2000 点 / 10s | `ThinkTouch.testPath` | R4.1 |
