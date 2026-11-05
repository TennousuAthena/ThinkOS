# 37 · 安卓应用页与底边上滑：计划

> 最新修订：所有者改为全局屏蔽安卓系统栏，左缘返回、底缘回home首页、顶缘控制栏。本文旧的“底缘到应用页或离开前页”、外部保留安卓原行为和只发返回通知的方案已由ADR-046及`38-global-gestures.md`取代；网格、launcher公开目标和既有历史报告仍有效。

> 2026-11-03，ADR-045（草案）。需求和四个选择是用户定的；机制是提议，可以推翻。
> 现状核对于 2026-11-03 10:15（HKT）：读的是工作区代码和 AOSP `oreo-mr1-release` 源码，设备上只做了只读查询（`dumpsys`、`pm`），没有跑构建。别的 agent 还在同一棵树上改，行号以动手时为准。
> 标记：**已核对** = 读过代码或 AOSP 源码；**实测** = 在设备上查过；**未验证** = 动手或验收时要上真机。
> 先读：`AGENTS.md` → ADR-037 / 039 / 040 / 044 / 045 → `33-apps-and-back.md` → `29-panel-and-apps.md` §4、§5 → `36-task-list.md` §0（规矩和每个任务都要过的检查）→ 本文。

## 1. 用户定了什么

| 问题 | 决定 |
|---|---|
| 卡片 | “应用”磁贴页上加一张卡片，内容是安卓应用，点进去列出系统里的安卓应用 |
| 列表范围 | 有桌面入口的 App，去掉 ThinkOS 自己。设备上 104 个包里 19 个有入口，所以是 18 个（实测） |
| 列表样式 | 图标网格 |
| 手势 | 从最底下往上滑，按来处回：在安卓 App 里回到安卓应用列表，一点就能开下一个；在 ThinkOS 的其它页面回到“应用”磁贴页 |
| 安卓 App 里 | 也生效。扩展已经装在设备上的 `thinkos-immersive`，用户重装并重启一次 |

## 2. 开始时的代码核对（历史检查点，最新结果见 §4）

另一个 agent 按 `36` 的 APP-1 做了一部分，当时清单写的是“用 `list`，不实现 `grid`”（DEC-5，现已改）。

| 项 | 状态 | 位置 | 还差什么 |
|---|---|---|---|
| 插件 `apps`、应用页 `apps.main`、11 种语言 | 有 | `plugins/apps/` | 名字是“应用”，和磁贴页同名 |
| 卡片 `apps.tile` 放在“应用”磁贴页 | 有 | `plugins/tiles/views/apps.json` | 改名，加底色 |
| 枚举安卓 App：桌面入口、去重、去掉自己和隐藏项、按名字排序 | 有 | `apps/InstalledApps.kt`、`apps/AppCatalog.kt` | 范围和用户选的一致，不用改 |
| `tos.apps.list()`、`tos.apps.open(id)`、事件 `apps`、权限 `system.apps` | 有 | `plugin/LuaBootstrap.kt`、`plugin/PluginRuntime.kt` 的 `AppsCalls` | 加 `open("launcher")` |
| 应用图标 `app:<包名>`，像素不进 Lua | 有 | `image/ImageSource.kt`、`image/ImageLoader.kt` | 网格直接用 |
| 打开安卓 App、离开、看门狗 | 有 | `RuntimeHost`、`MainActivity.launchOutside`、`shell/KioskState.kt` | 回来时保不住页面（§3.6） |
| 设置 `apps.android`、`apps.hidden`、`apps.return_after_s` | 有 | `shared/schema/settings/system.json` | — |
| 操作栏的“应用”按钮 | 有 | `plugins/panel/` | 现在开 `apps.main`，改去磁贴页 |
| 页面的样子 | 不符 | `plugins/apps/views/main.json`、`main.lua` | 现在是 `list`，混着 ThinkOS 应用，分两组 |
| `list` 行的 `image`、`group`（API 9 新加） | 有 | `layouts.json`、`ui/BuiltinComponents.kt` | 改网格后没有别的使用者，撤掉 |
| `grid` 渲染器 | **没有**，只有 schema | `layouts.json` | APP-5 |
| 手势的目标页标识 | **没有** | — | APP-6 |
| 底边手势 | **没有**：`ThinkStage`、`BackGesture`、`DeckRouter`、`system.json` 里都没有 | — | APP-6 |
| 回来时保住页面 | **没有** | — | APP-7 |
| 安卓 App 里的手势 | **没有**：`BarsHook.java` 只钩 `requestTransientBars`，模块还是 1.0 | — | APP-8 |
| 设备验收 | **没有**：`artifacts/app/APP-1/` 只有构建和主机测试日志，`36` 里 APP-1 的验收一条没勾，`tools/tests/` 没有 apps 的设备脚本 | — | 并入 APP-5 |

## 3. 设计（提议）

### 3.1 卡片和页面

- 磁贴页 `tiles.apps` 标题还是“应用”。现有的 `apps.tile` 和 `apps.main` 改叫“安卓应用”（`plugins/apps/i18n/*` 的 `name`），插件 id 和页面 id 不变。
- 卡片加底色 `#107C10`，和旁边三块一样有自己的颜色（白字对比度约 5.4:1）。
- 页面只列 `kind == "android"` 的项。ThinkOS 自己的应用已经是旁边的磁贴，不列第二遍，所以删掉分组和组名。
- `tos.apps.list()` 不变，还是返回两种，过滤放在 `plugins/apps/main.lua`。
- 文字（ADR-044）：正常时底部没有字。空状态一句 `没有安卓应用`（`apps.android` 关着，或全被隐藏）。失败和打不开沿用现有的 `list.failed`、`open.failed`。
- 操作栏的“应用”按钮改去磁贴页，和手势到同一个地方：`tos.apps.open("launcher")`。

### 3.2 `grid` 组件

| 项 | 规则 |
|---|---|
| 实现 | `ui/GridComponent.kt`，基于 `GridView`（复用单元）。接入方式照 `ListComponent`：工厂分支、`interactive`、`handlesTap`、`images` 工厂、`active` 生命周期 |
| 契约 | 不改 schema：`items`、`item.icon`、`item.image`、`item.title`、`tap`、`weight`（API 8 就有）。点击发 `tap(名字, 序号)`，序号从 1 开始 |
| 列数 | 纯函数 `RenderGeometry.gridColumns`：可用宽度除以 96dp，至少 3 列。竖屏约 4 列，横屏约 7 列，18 个在两个方向都不用滚（估算，验收时记录） |
| 单元 | 图标 56dp，下面一行名字，`TextScale.CAPTION`，居中，放不下省略。有 `image` 用 `image`，没有用 `icon` |
| 反馈 | 按下用和设置行相同的水波纹。没有边框、分隔线、角标（ADR-044） |
| 方向和无障碍 | RTL 跟系统镜像；单元的 `contentDescription` 是名字 |
| 内存 | 图标沿用 `ImageLoader` 的 4MiB 缓存。56dp 约 91px，一个约 33KB，18 个约 600KB |

`list` 行的 `image`、`group` 一起撤掉：API 9 还没发布，现在撤没有兼容问题，也少两个要长期维护的词汇。

`grid` 是原生组件，和 `32` 号文档“冻结原生词汇”的提议方向相反，这是用户知情后选的。它本来就在 OPEN-P4 要移植的 16 个组件里，等 OWN-2 定了按同样的路走。

### 3.3 回到哪

| 手势发生在 | 结果 | 怎么做到 |
|---|---|---|
| 从安卓应用页打开的安卓 App | 回到安卓应用页 | 路由没动过，只把 ThinkOS 带回前台 |
| 从别处打开的安卓页面（操作栏的网络设置、设置里的“打开安卓设置”） | 回到打开它的那一页 | 同上。这些是临时去安卓页面办事，跳到应用网格反而奇怪 |
| ThinkOS 的翻页序列、应用、接管页、设置页 | “应用”磁贴页，返回栈清空 | `router.launcher()`，和 Home 键同样的做法 |
| 安卓应用页本身 | “应用”磁贴页 | 同上。所以从安卓 App 连滑两次就到磁贴页 |
| “应用”磁贴页，栈空 | 不接管，触摸归页面 | 条不生效 |
| 操作栏开着 | 不接管 | 和现有规则相同（`panelGesture` 第一步） |
| 引导中（WEL 做完后） | 不接管 | `ONBOARDING` 层 |
| 安全模式 | 不接管 | 没有持有 `launcher` 的页面 |
| ThinkOS 进程被杀后，安卓 App 里 | 回到 ThinkOS 首页 | 返回栈只在内存里；看门狗本来也会把它拉回来 |

接管页（视频、电视、投屏镜像）上也生效，片子按 `page.leave` 暂停、镜像结束，和 Home 键一样（ADR-040）。

### 3.4 目标页：角色 `launcher`

OS 不写死 `tiles.apps`（ADR-039）。`roles` 加 `launcher`，由 `plugins/tiles/manifest.json` 的 `apps` 页声明。

- 要 `system.apps`，校验照 `panel` 角色（`PluginRuntime.kt` 的安装检查和目录过滤），这样非内置插件抢不到手势目标。`tiles` 的 `permissions` 加 `system.apps`。
- API 9 才能声明，在 `layouts.json` 的 `x-thinkos-api` 登记。
- `DeckRouter`：`launcherPage`（`pages.firstOrNull { "launcher" in it.roles }`）和 `launcher()`。页面还在翻页序列里，`navigablePages` 不用改。
- OPEN-P1 的绑定表做出来后，它和其它角色一起进绑定表。

### 3.5 ThinkOS 里的手势

| 项 | 规则 |
|---|---|
| 起点 | 单指，按下点在物理最底 `nav.up_edge_dp` 内（默认 24dp，16–48），和系统判定用的高度相同。不随 RTL 镜像 |
| 判定 | 接进 `ThinkStage.panelGesture` 这套“先扣住、再重放”。`BackGesture.classify` 加 `up` 和 `Verdict.UP`：滑过 slop，向上且纵向占优。其它方向把整段触摸原样交还页面。左下角谁先过 slop 谁赢 |
| 停住 | 手指停在条内 150 ms 没过 slop，就把触摸交还页面。这条对所有页面生效，不这样条内的按下反馈和长按就都没了（顶缘、左缘条的同类问题见 `33` §9） |
| 第二根手指 | 取消，救援手势不受影响 |
| 跟手 | 不建目标页：当前页按 `offset * .16` 橡皮筋上移，舞台系统层在底部中间画一个向上的箭头（复用返回箭头的画法）。首次建页在主线程，不能在手指刚动时做，否则时钟页可能迟到（ADR-029，`33` §9） |
| 提交 | `BackGesture.commits`：22% 屏高，或向上甩超过 700dp/s。否则弹簧回位 |
| 切换 | `router.launcher()`；新增 `Transition.RISE`，目标页从下面进来，旧页往上走。`renderDeck` 现在把非 `SLIDE` 都当翻转动画，只有同页才算纵向，两处都要改。系统关闭动画时直接切 |
| 日志 | `page=tiles.apps from=… reason=launcher` |

系统设置（`system.json`，11 种语言，走 ADR-017）：

| key | 类型 | 默认 | 级别 | 标签（`zh-Hans`） |
|---|---|---|---|---|
| `nav.up_enabled` | bool | 开 | `normal`，分类 `desktop` | 底边上滑回到应用 |
| `nav.up_edge_dp` | number，16–48 | 24 | `hidden` | 上滑手势触发区高度 |

`33` §4.4 手势总表加一行：

| 手势 | 翻页序列里的页面 | 应用 | 设置页 | 接管页（视频、电视） |
|---|---|---|---|---|
| 从最底下向上滑 | 到“应用”磁贴页（已在这页时归页面） | 到磁贴页 | 关设置，到磁贴页 | 到磁贴页，片子暂停 |

代价：从条内起手的上滑，原来是切变体、翻栈、滚列表、调亮度和音量，现在都是这个手势。条外起手不变。

### 3.6 离开和回来

**回来时页面保不住（已核对）**。`SensorController` 一直在跑（`sensors.stop` 没有调用者），安卓 App 里的触摸 ThinkOS 收不到，注意力照常往下掉：默认约 2 分钟 AMBIENT，约 3 分钟 ASLEEP。`ThinkStage.stop()` 停止收集状态，所以离开期间路由不动；但回来时 `stage.start()` 重新收集，第一个值就是 ASLEEP，`router.applyAttention` 调 `showBase(clock.face)` 清栈，下一次触摸再 `showBase` 到首页。结果是在安卓 App 里待 3 分钟以上，回来就不在安卓应用页。按 Back 回来也一样，这条和模块无关。

修法：`MainActivity.onResume` 里，如果 `KioskState.foreign` 为真且没到 `foreignUntil`（用户自己回来的），在 `stage.start()` 之前调 `host.sensors.touch()`。这样第一个值是 ENGAGED，和离开前相同，`applyAttention` 什么都不做。判断要放在 `leaveForeign()` 之前。到时间被看门狗拉回来的不调：没有人在，照常进待机页。

**回前台不能多开实例（已核对、实测）**。`am start`、`BootReceiver`、`RecoveryReceiver`、看门狗用显式 Intent，启动的是应用类型；系统的 Home 是桌面类型。安卓按类型分开找任务（AOSP `ActivityStack.java:965` 的 “mismatch activity type”），所以显式 `startActivity` 可能多开一个 `MainActivity`（`tools/tests/panel_device.py` 的注释记过同样的现象）。设备现在就是应用类型（`mActivityType=APPLICATION_ACTIVITY_TYPE`，桌面栈里是 Trebuchet）。

所以回前台不用 `startActivity`：`onResume` 记下 `taskId`，接收者调 `ActivityManager.moveTaskToFront(taskId, 0)`，新增权限 `REORDER_TASKS`（normal 级）。进程被杀过（没有 `taskId`）或调用失败，才退回 `startActivity`。**未验证**。

### 3.7 安卓 App 里的手势：系统模块

**为什么钩这里（已核对 AOSP，实测这台 ROM 的 `services.vdex` 里有这些名字）**：

- `SystemGesturesPointerEventListener` 看得到所有触摸。起点在底部 `status_bar_height` 内（24dp，本机约 38px），500 ms 内上移超过同样的距离，就调 `mCallbacks.onSwipeFromBottom()`，一次手势一次，和导航栏在哪无关。
- 现有钩子的 `requestTransientBars` 不够：`onSwipeFromBottom` 只在导航栏位于底部时才调它。这台设备最短边 492dp，小于 600dp，横屏时导航栏在右侧（`mNavigationBarCanMove`），横屏就没有这个调用。
- 不用悬浮窗（要 `SYSTEM_ALERT_WINDOW`，还会挡住别的 App 的触摸），不读触摸设备（要 root 守护进程），不用无障碍服务（API 27 拿不到这种手势）。

**做法**：

1. 钩 `SystemGesturesPointerEventListener` 的构造函数，取 `mCallbacks` 的实际类（`PhoneWindowManager` 里的匿名类），再钩它的 `onSwipeFromBottom`。这是接口调用，不会被内联，匿名类的编号也不用写死。
2. 钩子里经 `this$0` 取 `PhoneWindowManager`，读 `mFocusedWindow.getOwningPackage()`。是 ThinkOS、是系统自己（`android`、`com.android.systemui`）或没有焦点：什么都不做。ThinkOS 自己的窗口由 §3.5 处理，不能发两次。
3. 否则经 `mHandler` post 一次 `sendBroadcastAsUser`，显式组件 `moe.kiwi.thinkos/.shell.EdgeReturnReceiver`，`FLAG_RECEIVER_FOREGROUND`，不带任何内容。
4. **不拦系统原来的行为**：模块读不到 ThinkOS 的设置，手势关掉时不能吃掉系统手势。全屏 App 里导航栏可能闪一下，然后 ThinkOS 回到前台。
5. 任何异常都当没发生，和现有钩子一样。日志写 `hooked onSwipeFromBottom`。

**接收者** `shell/EdgeReturnReceiver`，`exported=false`：

- system_server 是 uid 1000，能发给不导出的接收者；别的应用和 adb shell 发不了。模块只能说“有人上滑了”，决定权在 ThinkOS。
- `nav.up_enabled` 关着就返回。
- `KioskState.foreign || KioskState.shouldRecover()` 才回来。这是看门狗判断“ThinkOS 该不该在前台”的同一条规则，所以权限对话框和救援菜单打开的安卓设置不会被打断（`allowSystemSettings` 期间 `shouldRecover` 为假）。
- 回前台按 §3.6，路由不动，页面就是离开时的页面。

**构建**：`VERSION=1.1`，`--version-code 2`。模块现在只对 stubs 编译，主机测试也不依赖 `android.jar`：`build-immersive.sh` 的 classpath 加 `android.jar`，`test/` 里放 `Context`、`Intent`、`Handler` 的替身，stubs 加 `hookAllConstructors`。

**兼容**：新模块配旧 APK，广播没人收；旧模块配新 APK，安卓 App 里没有这个手势，用 Back 或 Home 回来；没有模块时，ThinkOS 里的手势照常，竖屏上系统栏会闪（`10-known-issues.md` §13）。

## 4. 任务

顺序：APP-5 → APP-7 → APP-6 → APP-8。除 APP-8 依赖 APP-6、APP-7 外互不依赖。每个任务都要过 `36` §0.4 的检查，性能和时钟测试先告诉所有者。这几个任务和 `36` 的其它任务会改同样的文件（`ThinkStage.kt`、`DeckRouter.kt`、`BuiltinComponents.kt`、`system.json`、11 种语言），同一时间只让一个 agent 改。

结果写在各任务下面，格式同 `36`。

### APP-5 “安卓应用”卡片和网格页

结果：2026-11-03，功能验收通过：网格真机48项、100项滚动/复用/索引3项、公共目录2项、直接open门禁4项；18个入口在竖屏4列、横屏7列均不需要滚动，三主题/三语言截图已保存。修复水平拖动误点和图标透明边缘黑底。JVM617/Python71/native/lint/assemble/Go契约通过；设置与计算器启用状态均恢复，音量0。性能按所有者要求留到功能完成后统一验收。证据 `artifacts/app/APP-5/report.md`。

| # | 内容 |
|---|---|
| 1 | `ui/GridComponent.kt`、工厂分支、`RenderGeometry.gridColumns`（§3.2） |
| 2 | `plugins/apps/views/main.json` 的 `hero` 换成 `grid`；`main.lua` 只留安卓项，删分组，加空状态 |
| 3 | `plugins/apps/i18n/*`：`name` 改“安卓应用”，加 `list.empty`，删 `group.*`。其它语言按同样的意思重译，优先短词 |
| 4 | `views/tile.json` 加底色 |
| 5 | 撤掉 `list` 行的 `image`、`group`：schema、`x-thinkos-api.listItemFields`、`ListComponent`、相关测试 |
| 6 | `tools/tests/apps_test.lua` 按新行为改；JVM 加列数测试；`tools/tests/fixtures/grid-probe`（100 项，普通图标，用来测滚动）；新设备脚本 `tools/tests/apps_device.py`；`ui_walkthrough.py` 加这一页 |
| 7 | 文档：`06-plugins.md`（词汇表、`apps` 插件）、`04-ui-spec.md`（网格尺寸）、`plugins/README.md`、`29` §4.2、`33` §6 |

验收（含 `36` APP-1 的四条）：

- [x] “应用”磁贴页上有“安卓应用”卡片，点开是网格。设备上 18 个，按名字排序，没有 ThinkOS 自己，也没有媒体、电视、投屏。
- [x] 第三方插件调 `tos.apps.*`、用 `app:` 图标都被拒绝。
- [x] 页面不进翻页序列，返回回到磁贴页。
- [x] 竖屏、横屏、深色、浅色、夜间，`zh-CN`、`en`、`ar` 的截图：图标和名字不出框，RTL 镜像。记录 18 个要不要滚。
- [x] 点图标打开对应 App；按 Back 回到网格。
- [x] `apps.hidden` 里的包不显示。关掉 `apps.android`：页面显示“没有安卓应用”，磁贴还在，打开请求被拒绝。
- [x] 用 `pm disable-user` 和 `pm enable` 切一个系统 App：网格自动刷新，不用重进页面。结束后恢复。
- [ ] `grid-probe` 滚动，janky < 5%（先告知）。
- [x] `test_ui_restraint.py` 通过，`docs/04` 和实际尺寸一致。

### APP-6 ThinkOS 里的底边上滑

结果：2026-11-03，功能代码已实现；竖横屏单轮51项断言通过，八文件逐字节恢复、临时插件/视频删除、音量0。JVM627/Python74/native/lint/assemble/Go契约通过。内容区专项另8项通过（列表/电视按钮/亮度）；音量操作遵守静音要求不跑；性能和时钟压力后置。证据 `artifacts/app/APP-6/report.md`。

| # | 内容 |
|---|---|
| 1 | **先验证**：最底下的触摸能不能到 ThinkOS。顶缘测过能到，底边只测过系统栏（`29` §3.8）。`input swipe 400 1277 400 900 200`，看 `ThinkStage` 收到的 DOWN 坐标。到不了就停下报告（§5） |
| 2 | 角色 `launcher`（§3.4） |
| 3 | `BackGesture`、`ThinkStage`、`PageEvents`、`RuntimeHost`（§3.5） |
| 4 | `DeckRouter.launcher()`、`Transition.RISE`、`renderDeck` |
| 5 | 设置和 11 种语言；`SystemSettingsVisibilityTest` |
| 6 | `tos.apps.open("launcher")`；`plugins/panel/main.lua`；`panel_device.py` 加检查 |
| 7 | 新设备脚本 `tools/tests/up_gesture_device.py`，竖屏横屏都跑 |
| 8 | 文档：`33` §2、§4.4、§7；`42-feature-rules.md` 的“应用与返回”；`06-plugins.md`；`09-roadmap.md` |

验收：

- [x] 从首页、时钟页、别的磁贴页上滑过阈值：到 `tiles.apps`，日志 `reason=launcher`。
- [x] 从 `cast.home` 上滑：到磁贴页，再按 Back 什么都不发生（栈已清）。
- [x] 视频页上滑：到磁贴页，片子暂停。设置页上滑：设置关掉，到磁贴页。
- [x] 滑一半松手：回弹，不换页。
- [x] 操作栏开着不触发；`tiles.apps` 栈空时条不生效；`nav.up_enabled` 关掉后全部不触发。
- [ ] 条外起手：切变体、翻栈、滚列表、视频亮度和音量都不变。
- [x] 条内轻点有效（Aero 任务栏、电视底部按钮）；条内长按 0.6 秒进设置；条内横滑照常翻页。
- [x] 左下角：向上是这个手势；向右在栈非空时是返回。
- [x] 第二根手指取消；双指长按左上角的救援手势不受影响。
- [x] 操作栏的“应用”按钮到磁贴页。
- [x] 非内置插件声明 `launcher` 被拒绝；API 8 的 manifest 声明它被拒绝。
- [x] 安全模式下不触发，不崩溃。阿拉伯语下手势不变。系统关闭动画时直接切换。
- [x] 手势期间不新建页面：拖动开始时 `pageHost` 的子视图数不变（脚本或 JVM 验证）。
- [ ] 在时钟页反复上滑和取消，`clock_accuracy_device.py` 判据不放宽（先告知）。
- [ ] 冷启动 < 3 秒，待机 janky < 5%，不回归（先告知）。


### APP-7 回来时保住页面

结果：2026-11-03，当前APK完整单轮17项断言通过。应用/HOME任务的Back与Home、Wi-Fi返回均真实等70秒并确认ASLEEP后操作，恢复原页或首页、正常亮度及Lock Task。自动返回设置60秒，69.2秒后保持ASLEEP进入clock.face，任务数不增；第一次Home新增HOME任务已记录。八文件逐字节恢复、音量0，早期失败原文保留。见 `artifacts/app/APP-7/report.md`、`device-final/results.json`；性能后置。

| # | 内容 |
|---|---|
| 1 | `MainActivity.onResume` 按 §3.6 调 `sensors.touch()` |
| 2 | 记下 `taskId`（APP-8 用） |
| 3 | `apps_device.py` 加检查 |

验收（把 `attention.to_asleep_s` 设成 60 秒来缩短等待，结束后恢复）：

- [x] 从安卓应用页打开计算器，等 70 秒，按 Back：回到安卓应用页，不是时钟或首页；屏幕亮度正常。
- [x] 同样等待后按 Home：回首页（ADR-040 不变）。应用类型（`am start -n` 启动）和桌面类型（按过一次 Home）两种状态各测一次，有问题就修，并记下结果。
- [x] 从操作栏打开 Wi-Fi 页，等 70 秒再 Back：回到原来的页面，Lock Task 恢复。
- [x] `apps.return_after_s` 到时间、没有人碰：ThinkOS 自己回来，按现有规则进待机页。
- [x] `dumpsys activity activities` 里 ThinkOS 的 Activity 数不因这些操作增加（第一次按 Home 造成的除外，记录现状）。

### APP-8 安卓 App 里的上滑

结果：2026-11-03，源码与构建完成；模块主机29项、应用JVM627/Python74、原cast系统主机18项通过。当前旧模块1.0配新APK，接收者的同UID真机19项及冷进程兜底3项通过；8份文件还原、音量0。未安装模块1.1、未改Vector登记、未重启，ROM真实回调与其余系统联动待人工，整项不标完成。证据 `artifacts/app/APP-8/report.md`。

| # | 内容 |
|---|---|
| 1 | `BarsHook.java` 或同目录的新类（§3.7），判定写成纯函数 |
| 2 | `shell/EdgeReturnReceiver.kt`、manifest、`REORDER_TASKS` |
| 3 | `build-immersive.sh`、`system/test/run-immersive.sh`、stubs、替身 |
| 4 | `tools/tests/immersive_device.py` 加检查，`up_gesture_device.py` 加 `--module` |
| 5 | **人工**：`system/install-immersive.sh`，然后重启设备。开发 Agent 只在所有者明确授权时做（ADR-037 的先例） |
| 6 | 文档：`system/README.md`、`10-known-issues.md` §13（滑得太慢、模块没更新）、`29` §3.8、ADR-037 加一句 |

验收：

- [x] 主机测试：焦点是 ThinkOS、`android`、`com.android.systemui` 或没有焦点时不发；别的包发且只发一次；字段不存在时不抛异常、不发。
- [ ] 重启后日志有 `hooked onSwipeFromBottom`，`immersive_device.py` 原有 6 项仍通过。
- [ ] 竖屏（导航栏在底部）和横屏（导航栏在右侧）各一次：在计算器里快速上滑，回到安卓应用页；Lock Task 恢复；ThinkOS 的 Activity 数不增加。
- [ ] 应用类型和桌面类型两种状态都测。
- [ ] 全屏 App（比如相机）里上滑：回到安卓应用页，系统栏最多闪一下。
- [ ] 慢滑（超过 0.5 秒）不触发，App 不受影响。
- [ ] 从导航栏 Home 键上起手上滑：到安卓应用页，不是首页。如果抬手时触发了 Home，就在接收者处理后的短时间内忽略 Home，并记录。
- [ ] 操作栏打开的 Wi-Fi 页里上滑：回到原来的页面。
- [ ] 救援菜单打开的安卓设置里、权限对话框上：上滑不把 ThinkOS 拉回来。
- [ ] `nav.up_enabled` 关掉：安卓 App 里不触发。
- [x] `adb shell am broadcast -n moe.kiwi.thinkos/.shell.EdgeReturnReceiver` 被拒绝。
- [ ] ThinkOS 进程被杀（`su -c kill`）后在安卓 App 里上滑：回到 ThinkOS，Activity 正常。
- [ ] 旧模块配新 APK、新模块配旧 APK（只测不崩）。

## 5. 风险与没验证的

| 风险 | 对策 |
|---|---|
| 最底下的触摸到不了 ThinkOS（**未验证**） | APP-6 第 1 步先查。到不了就让 ThinkOS 里也走模块，那样没有新模块就没有这个手势，先报告所有者 |
| 钩子在这台 ROM 上不生效（**未验证**，只查到名字存在） | 日志看得到。备选是钩 `detectSwipe(MotionEvent)` 的返回值（2 表示从底部）。钩不上只是没有这个手势，不影响别的 |
| `moveTaskToFront` 在这台 ROM 上的表现（**未验证**） | 失败退回 `startActivity`，代价是可能多一个 Activity 实例 |
| 全屏视频、投屏时误触退出 | 条只有 24dp，和 Home 同样的后果。可调可关。真机上不好用再给接管页加例外，先报告，不要自己加 |
| 安卓 App 里只认快滑（0.5 秒内） | ROM 写死的判定，写进文档。ThinkOS 里没有这个限制 |
| 系统模块改一次要重启一次 | 钩子只发一个不带内容的广播，判断都在 ThinkOS，以后基本不用改 |
| 安卓 App 用到 15 分钟被拉回 | `apps.return_after_s` 默认 900，有声音时不回。这是现有行为，现在用安卓 App 更方便了会更明显。设置里能改，默认值由所有者定 |
| Vector 2.2 在 8.1 上的守护进程问题 | 已有：`install-immersive.sh` 处理（ADR-037） |

另外没验证的：APP-1 现有代码的设备行为；§3.2 的列数和“不用滚”是估算。
