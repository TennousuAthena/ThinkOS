# 03 · 架构（详细版）

> 本文是 ThinkOS 的**总体架构**：设备端（Android）作为自主运行的 OS，Server（Go）作为下发与管理装置。
> 内容包括模块划分、状态模型、渲染与动效引擎、Lua 插件运行时、感知链路、同步与通讯、资源预算与失败处理。
> 视觉细节见 `04-ui-spec.md`，通讯协议见 `05-protocol.md`，插件开发见 `06-plugins.md`。
> 标注「**[提议]**」的条目尚未写入 `08-decisions.md`，需确认后再登记为 ADR。

---

## 0. 设计目标

产品是一个**电子摆件**，不是 AI 助手。架构上的每个取舍都服务于下面六条，按优先级排列：

| # | 目标 | 架构含义 |
|---|---|---|
| G1 | **一直活着** | 7×24 运行；崩溃自愈；不依赖设备时间正确 |
| G2 | **设备自主** | 设备是一个完整的 OS：插件在设备上运行，**没有 Server 也能完整使用**；Server 只负责下发和管理（ADR-015） |
| G3 | **动画是第一公民** | 渲染层独立成「舞台」，统一时钟驱动所有动画；动画按状态分档限帧，而不是一律 60fps |
| G4 | **先感知再反应** | 「有人走近 → 醒来」在设备本地闭环，延迟 < 500ms |
| G5 | **插件优先，加功能不重装** | 每个页面、每张卡片都由插件提供；插件是「声明式视图 + Lua 脚本」的目录，来自 Git 仓库，由 Server 取回并下发、设备热加载（ADR-013、ADR-016、ADR-018） |
| G6 | **重活交给 Server，但只是可选增强** | 照片裁切、语音识别等吃 CPU 的事放在 Server；Server 不在时功能降级而不是失效 |

不在目标内：聊天对话、本地大模型、WebView 界面、Home Assistant、多用户。（视频通话原在此列，2026-11-04 改为要做，见 ADR-047。）

---

## 1. 系统全景

```
┌─────────────────────── ThinkSmart View：ThinkOS（自主运行）────────────────────────┐
│                                                                                   │
│  插件（声明式视图 + Lua）：home  clock  weather  gallery  music  notify  第三方…      │
│        │ tos.* API                                                                │
│  ┌─────┴───────────────────────────── 运行时（APK）───────────────────────────────┐ │
│  │ plugin  ── Lua 5.4 运行时 · 沙箱 · 预算 · 视图绑定 · 目录安装与热加载          │ │
│  │ core    ── DeviceState · Store.update · DeckRouter · NavStack · WallClock          │ │
│  │ settings ── 设置存储（系统级 + 页面级）· 校验 · 叠加 · 回报                    │ │
│  │ tz/i18n ── 自带 IANA 规则 · 系统语言/日期格式 · 插件翻译                       │ │
│  │ astro/location ── 本地日月/季节计算 · 配置位置 · 可选地点搜索                   │ │
│  │ ui/anim ── Stage（四层舞台）· 页面版式 · 卡片模板 · MotionClock · 主题         │ │
│  │ media   ── ExoPlayer · 频谱 · 提示音                                         │ │
│  │ vision  ── 接近/环境光传感器 · Camera2 · 运动/人脸/手势                        │ │
│  │ sync    ── 期望状态同步 · 制品下载校验 · 状态回报                             │ │
│  │ net     ── 服务发现 · HTTP · 实时通道（可选）                                │ │
│  │ shell   ── HOME 接管 · 沉浸 · 开机自启 · 看门狗 · 崩溃自恢复                  │ │
│  └───────────────────────────────────────────────────────────────────────────────┘ │
│  本地数据：已安装插件 · 插件存储 · 照片集 · 配置 · 最后画面                          │
└───────────────┬──────────────────────────────────────────┬────────────────────────┘
                │ 同步（HTTP，设备拉取）                    │ 实时（WS，可选）
                │ 期望状态 / 制品 / 状态回报                │ 推送提醒、通知、远程指令、语音帧
┌───────────────┴──────────────────────────────────────────┴────────────────────────┐
│  Server：thinkos-server（Go，单二进制）—— 下发与管理装置                               │
│  devices  ── 设备登记 · 期望状态（配置、插件清单、照片集、APK 版本）· 版本号         │
│  registry ── 从 Git 仓库取插件 · 校验 · 锁定 commit · 文件按 sha256 存储            │
│  content  ── 照片集：扫描、按设备尺寸横竖各裁一份、HEIC 转码、取主色                 │
│  relay    ── 通知入口（手机快捷指令 / Webhook）→ 推给设备                           │
│  services ── 可选增强：语音识别、Server 上的音乐库流、Server 端服务插件                       │
│  observe  ── 设备状态、指标、日志、崩溃收集                                         │
└───────────────────────────────────────────────────────────────────────────────────┘
        外部：天气 API、Navidrome 等 —— 设备上的插件直接访问，不必经过 Server
```

**关键变化（相对旧版“Server 推画面”的模型）**

| 旧 | 新 |
|---|---|
| 插件跑在 Server，Server 推送每一帧页面内容 | 插件跑在设备，页面内容在设备本地产生 |
| 设备离线 = 降级到“兜底画面” | 设备离线 = **正常运行**，只是暂时收不到新下发和远程推送 |
| WebSocket 是唯一生命线 | 同步走 HTTP 拉取；WebSocket 只是可选的实时通道 |
| Server 决定显示什么 | 设备上的插件和 DeckRouter 决定显示什么；Server 决定**装什么插件、怎么配置** |

---

## 2. 职责切分

| 能力 | 运行位置 | 理由 |
|---|---|---|
| 渲染、动画、布局、横竖屏 | 设备 | 这是它最擅长的事 |
| 插件执行（数据获取、状态、逻辑） | **设备**（Lua） | 自主运行（G2）；插件逻辑都很轻 |
| 页面序列、角色、焦点仲裁 | 设备（DeckRouter） | 不能依赖网络 |
| 时钟走秒、授时 | 设备 | timesync 校时；Server 在线时额外校准 |
| 存在感知、手势、主题切换 | 设备 | 本地闭环；画面不出设备 |
| 音频播放 + 频谱 | 设备 | 声音从它的扬声器出来 |
| 天气、Navidrome 音乐等外部数据 | 设备上的插件直接访问 | 不需要 Server 中转 |
| 插件安装、升级、配置 | **Server 下发**，设备执行 | 管理面 |
| 照片集准备（选片、裁切、HEIC 转码） | Server 预处理并下发 | 设备只拿到刚好等于屏幕尺寸的 JPEG |
| 手机通知、远程指令的入口 | Server 中转 | 手机只知道 Server 的地址 |
| 语音识别（可选） | Server | 设备上不常驻语音模型 |
| 日志、指标、崩溃收集 | Server | 运维 |

一句话：**设备决定显示什么、怎么显示、何时亮起；Server 决定设备上装什么、怎么配，并在需要时帮忙做重活。**

### 2.1 插件优先（ADR-013）

ThinkOS 自身**不实现任何具体功能**。时钟、天气、相册、音乐、通知，乃至首页，都是插件；APK 只是“运行时”：

```
┌──────────────────────── 插件（功能都在这里） ─────────────────────────┐
│  home      clock      weather    gallery    music    notify    第三方…  │
│  每个插件 = manifest + 声明式视图（页面/卡片）+ 可选的 Lua 脚本 + 资源  │
└───────────────┬──────────────────────────────────────────────────────┘
                │ 视图声明 + 状态（只描述内容与语义，不描述像素）
┌───────────────┴──────────── 运行时（不含功能） ──────────────────────┐
│  APK：Lua 运行时 · 舞台与动效 · 页面版式 · 卡片模板 · 设置 · 播放器 · 感知 │
│  Server：从 Git 取插件并下发 · 配置管理 · 可选增强服务                   │
└─────────────────────────────────────────────────────────────────────┘
```

| 原则 | 含义 |
|---|---|
| **页面即插件** | 一个整屏页面 = 某个插件声明的 `page`；左右翻页就是在插件之间切换 |
| **插件出内容，设备出长相** | 插件选一个设备内置的**页面版式**并绑定状态；排版、横竖屏、动画由设备完成，别人写的插件也一样好看、会动 |
| **声明式为主，脚本为辅** | 视图是 JSON 声明，用 `{{path}}` 绑定状态；Lua 只负责取数据、改状态、响应事件。没有逻辑的插件可以完全不写 Lua（ADR-016） |
| **能力通过 `tos.*` 拿** | 网络、存储、播放器、通知、照片集都是运行时 API，按 manifest 声明的权限授予 |
| **词汇固定，内容无限** | 页面版式、卡片模板、组件的种类只随 APK 升级增加；插件不能自造类型（延续 ADR-008） |
| **自带与第三方同等** | 自带插件也走同一套 `tos.*` 接口，没有私有通道 |
| **外观不开放接口，设置开放** | OS 不为样式、表盘、部件皮肤预留扩展点；想要不一样的页面就写一个页面插件（可以让 AI 写）。OS 只提供系统级和页面级**设置**，设置界面本身也是插件（ADR-017，§3.18） |
| **用 Git 分发** | 插件是 Git 仓库里的一个目录，版本是 tag，Server 取回并下发；设备不碰 Git（ADR-018，§3.7.5） |

### 2.2 整机体验：参照 iPhone「待机显示」（StandBy）（ADR-014）

| StandBy | ThinkOS 中的对应 |
|---|---|
| 三个视图：小组件 / 照片 / 时钟，**左右滑**切换 | deck 默认三页：`home.main`（双栈小组件）、`gallery.slideshow`、`clock.face`；第三方插件页面排在后面 |
| 同一视图内**上下滑**换样式 | 页面的**变体（variants）**：时钟换表盘、相册换相簿、小组件栈翻到下一张卡片 |
| 智能叠放（Smart Stack）+ 智能轮换 | `stacks` 版式：两个卡片栈；插件给卡片打**相关度**分，栈自动轮换到此刻最相关的卡片 |
| 实时活动（Live Activity）全屏接管 | 插件以 `live` 方式请求焦点（正在播放、计时器）→ 全屏接管；结束后回到原页面 |
| 通知以大图标全屏出现 | AMBIENT/ASLEEP 时居中大图标 + 标题，几秒后收起；AWAKE 时从顶部滑入卡片 |
| 夜间模式：暗光下整屏变红 | 环境光传感器（VCNL4200 ALS）低于阈值 → 全局红色单色滤镜 + 最低亮度（§3.4） |
| 动作唤醒 / 常亮 | 接近传感器 + 摄像头存在感知（§3.11）；屏幕从不熄灭，只调暗 |
| 横放进入 StandBy | 设备始终处于这种模式；**横屏是主形态**，竖屏是同一套体验的竖排（§3.5） |
| 黑底、超大数字、远看可读 | 超大字号；**白天浅色、夜里深色**，由环境光自动切换（`04-ui-spec.md` §1） |
| 轻点显示控件、长按编辑 | 轻点 → 显示该页控件 3 秒；**长按 → 该页面所属插件的设置页**（ADR-017，没有 StandBy 那样的编辑模式；换样式用上下滑，调整首页两栈由 Server 配置） |

---

## 3. 设备端架构

### 3.1 进程与组件（2026-11-03 按代码核对）

```
ThinkApplication
 ├─ RuntimeHost（进程内单例，Application 持有）
 │   ├─ Store                      DeviceState / StateFlow，主线程 update{}
 │   ├─ PluginRuntime              单一 Plugin 线程、每插件独立 Lua 沙箱、目录加载
 │   ├─ SettingsStore              声明校验、三层叠加、异步持久化
 │   ├─ DeckRouter / NavStack       页面序列、角色、应用与返回
 │   ├─ MediaEngine / CastHost      唯一播放器、DLNA / AirPlay / Miracast
 │   ├─ SensorController            AttentionPolicy / ThemePolicy、亮度
 │   ├─ TimeSync / LocationProvider 授时、位置、日月计算
 │   └─ ImageLoader / DeviceControl 图片缓存、亮度音量与安卓入口
 ├─ KioskService                    前台服务与恢复应用
 ├─ MainActivity（singleTask、HOME） ThinkStage 和窗口、权限、救援菜单
 └─ BootReceiver                    开机拉起 Service 和 Activity
```

- 单进程、单 Activity；Lua 不在主线程执行。运行时由 Application 持有，不由 Activity 持有；旋转不重建 Activity，声明见 `AndroidManifest.xml`。
- `AppGraph`、`PlayerController`、`PerceptionManager`、`CacheManager` 不是现有类名，职责由上表实际对象承担。
- `SyncAgent`、`LiveLink` 与摄像头感知仍是计划，Server 只有共享协议包，没有入口程序。

### 3.2 单向数据流（当前实现）

```
传感器 / 触摸 → SensorController 的 Policy → Store.update{} → StateFlow → ThinkStage
插件 page 请求 → RuntimeHost → DeckRouter / NavStack → Store.update{}
插件 state 更新 → PluginRuntime.Callbacks → RendererObserver → 页面局部绑定
播放器回调 → RuntimeHost → PluginRuntime 的 media 状态 / 事件
设置变更 → RuntimeHost → SystemValues / SensorController / 舞台 / 插件 config 事件
```

`PluginRuntime.host()` 只做 Back 应答记账和固定能力分派。61个宿主入口由9个私有能力对象处理：状态/存储/模块、设置、设备、导航、时间/本地化、天文、计时、媒体、网络；实例生命周期、权限检查实现、线程/挂起取消仍由原运行时持有，分支逻辑保留。能力对象每个运行时创建一次。

`RuntimeObservers.kt` 将进程内通知分为 Renderer、Display、Kiosk、Navigation、StoragePermission、Diagnostic 六类；订阅者按所需接口注册。`RuntimeHost.Observer` 是 Activity 使用的聚合接口；初始目录/状态、亮度、方向、Kiosk通知保持原顺序，每次通知仍先取订阅者快照。

```kotlin
data class DeviceState(
    val attention: Attention = Attention.AWAKE,
    val deck: DeckState = DeckState(),
    val overlay: OverlayState = OverlayState(),
    val link: Link = Link.UNPAIRED,
    val theme: Theme = Theme.DARK,
    val orientation: Orientation = Orientation.LANDSCAPE,
    val safeMode: Boolean = false,
)
```

- `Store` 用 `MutableStateFlow`；主线程调用 `update { old -> ... }`，没有独立的 `Reducer` 或 `Effects` 类。主题和注意力由纯 Kotlin Policy 决定，实际副作用由 `RuntimeHost`、观察者和各能力对象执行。
- 插件内容不放入 DeviceState；按状态路径更新绑定。`MediaEngine` 保存播放状态，`TimeSync` 保存授时状态；DeviceState 没有 `playback`、`clock`、`perf` 字段。
- `OverlayState` 目前只有可选 `message`；`Link` 保留协议状态，通知队列、实时连接、性能自动降档仍为计划。
- 磁盘和网络工作放到工作线程，状态更新与 UI 在主线程。

### 3.3 状态机：四条正交的轴

旧版把 IDLE/AWAKE/ACTIVE/ALERT 揉成一条状态机，会出现组合爆炸。拆成四条互不干扰的轴：

#### ① Attention（注意力）

```
          人脸/运动/接近出现                触摸 / 手势 / 语音
  ASLEEP ───────────────> AWAKE ─────────────────────────> ENGAGED
    ▲  ▲                   │  ▲                                │
    │  └── 无人 ≥ 3min ────┘  └────── 无交互 ≥ 15s ─────────────┘
    │                       │
    └── 无人 ≥ 3min ── AMBIENT <── 有人但 ≥ 2min 没看屏 ──┘
```

| 状态 | 含义 | 亮度 | 动画帧率档 | 相机 | 画面 |
|---|---|---|---|---|---|
| `ASLEEP` | 长时间无人 | 最低（≈3%） | Tier C（几乎静止） | L0 低频 | `sleep` 角色页面（时钟），缓慢漂移防残影 |
| `AMBIENT` | 有人在附近但没在看 | 中（≈40%） | Tier B（30fps） | L0 | 当前页，N 分钟后切 `ambient` 角色页面 |
| `AWAKE` | 刚有人走近 / 正看着屏 | 满（用户设定上限） | Tier A（60fps） | L0 + 准备 L1 | 信息展开、卡片浮现 |
| `ENGAGED` | 正在交互 | 满 | Tier A | L1（手势） | 响应交互 |

- **滞回**：人脸丢失后不会立刻降级，由计时器决定；阈值来自配置（默认值在 APK，Server 可下发覆盖）。
- **视觉失明时**（暗光、镜头被挡）：退化为只靠接近传感器和触摸唤醒。
- **不熄屏**：“暗下去”= 窗口亮度调到最低 + 画面变简，**从不**关闭屏幕（`10-known-issues.md` §4）。亮度用 `WindowManager.LayoutParams.screenBrightness`，无需任何权限。

#### ② Deck（页面）—— 设备本地决定

页面全部来自插件（`<plugin>.<page>`，如 `gallery.slideshow`），排成一个**页面序列（deck）**。deck 顺序与角色绑定来自配置：APK 自带默认配置，Server 下发的配置覆盖它。

| 角色 | 何时显示 | 默认绑定 |
|---|---|---|
| `home` | AWAKE / ENGAGED 时的默认页 | `home.main`（`stacks` 双栈小组件） |
| `ambient` | AMBIENT 持续 N 分钟后 | `gallery.slideshow`（照片 + 角落时间） |
| `sleep` | ASLEEP | `clock.face`（全屏时钟，夜间自动变红） |
| `settings` | 长按页面触发 | `settings.form`（设置页；**不在翻页序列里**，退出后回到原页面和变体） |

导航是二维的（同 StandBy）：**左右**在页面之间切换，**上下**在当前页面的变体之间切换。页面记住用户上次选的变体，存在本地，Server 在线时回报。

切页来源：

1. 用户本地导航：左右滑 / 左右挥手 → 翻页；上下滑 → 切换变体；点卡片 → 打开卡片所属插件的页面
2. 角色策略：Attention 变化 → 切到对应角色的页面
3. 插件的**焦点请求**（`tos.page.focus`，例如 music 开始播放 → 请求切到 `music.nowplaying`），由 DeckRouter 仲裁。带 `live` 标记的是“实时活动”：结束时自动回到被接管前的页面和变体
4. 远程指令（实时通道下发的 `command`，例如手机上点“打开相册”）→ 转成同样的焦点请求
5. **长按页面或首页栈里的卡片** → 打开 `settings` 角色页面，参数 `scope` 为被长按元素所属的插件（该插件没有设置项则为 `system`）。设置页打开期间暂停角色策略、保持 ENGAGED；点“完成”、30 秒无操作或按 Home 键退出。`settings` 插件不可用或处于安全模式时，改为打开原生救援菜单（§3.12）

冲突规则：**用户最近 30s 内的本地操作优先于任何焦点请求**；ASLEEP 时焦点请求不唤醒屏幕（只有通知可以）；多个插件同时请求时按 manifest `priority` + 配置排序。

#### ③ Overlay（叠层）—— 队列（计划）

`NONE` / `ALERT(queue)` / `TOAST` / `VOICE_HINT`。通知进入队列逐个展示，不打断页面本身。展示形态随 Attention 变化：AMBIENT/ASLEEP 时居中大图标 + 标题，提升到 AWAKE 亮度，数秒后收起；AWAKE/ENGAGED 时从顶部滑入卡片。夜间模式下通知同样走红色滤镜，只有 `level=urgent` 例外。

#### ④ Link（与 Server 的连接，通讯计划）

`UNPAIRED` / `OFFLINE` / `ONLINE`。**只影响**同步、远程推送和一个可选的状态点（维护设置里开启才显示），不影响其它三条轴，也不影响任何插件的运行。

### 3.4 渲染：舞台（Stage）

```
┌─ L3 SystemLayer   状态点、调试 HUD、维护入口热区             （几乎静止）
├─ L2 OverlayLayer  通知、Toast、语音聆听提示                   （按需）
├─ L1 PageLayer     当前页面：某个插件的页面，按其版式渲染      （内容）
└─ L0 AmbientLayer  背景呼吸场：缓慢的色块/光晕，自绘 Canvas     （常驻、低频）
```

- **L0 永不重建**：翻页时背景只改变“情绪参数”（颜色、呼吸幅度、速度），由 MotionClock 平滑插值过去，切页时不会“闪一下”。页面可以给出 `mood`（照片或封面的主色：下发的照片集已带主色，其它图片在解码时顺带算一次），设备按主题映射到安全色域再使用。
- **PageHost** 管理 L1：同一时间最多存在两个页面（离场中 + 入场中），`exit()` 与 `enter()` 重叠执行，元素错开入场，节奏统一由 Motion tokens 定义——不管页面来自哪个插件，转场节奏都一样。
- **视图绑定**：插件视图（§3.6）在插件加载时被编译成 View 树 + 绑定表；插件状态变化时只更新受影响的绑定：
  - 列表增删 → 入场/离场动画；位置变化 → FLIP 位移动画
  - 文本/数字变化 → 局部过渡（数字滚动、图片交叉淡入），**不整卡重绘**
- View 复用：卡片模板 View 有小型对象池，避免频繁 inflate。
- **主题**：所有 View 只引用颜色 token（`04-ui-spec.md` §1.1），切换时 token 在 2s 内插值，不重建 View。
- **夜间模式滤镜**：Stage 根 View 设 `setLayerType(LAYER_TYPE_HARDWARE, paint)`，`paint` 带一个 `ColorMatrixColorFilter`（亮度 → 红色单色）。整屏在 GPU 合成时一次性变红，插件和版式都不需要知道夜间模式的存在。进出时矩阵在 1.5s 内插值。阈值与滞回见 `04-ui-spec.md` §1。

### 3.5 横竖屏

- `screenOrientation="fullSensor"`（BMA255 加速度计）；方向锁定属于以后的用户设置。
- **横屏是主形态**（与 StandBy 一致）：所有版式先按横屏设计，竖屏是同一套元素的竖排。
- 旋转不重建 Activity；`onConfigurationChanged` → `LayoutSpec` 重新计算：

| | 横屏 788×492dp | 竖屏 492×788dp |
|---|---|---|
| `stacks` | 两栈左右并排 | 两栈上下排列 |
| `nowplaying` | 封面在左、信息+频谱在右 | 封面在上、频谱在下 |
| `slideshow` | 用照片集的横版图 | 用竖版图（Server 预处理时各裁一份） |
| `focus` | 时钟一行 | 时、分两行 |

- 系统完成窗口旋转后，元素用 FLIP 从旧坐标过渡到新坐标（320ms），不重放入场动画。
- 每个**页面版式**提供 `layoutFor(spec: LayoutSpec)`，横竖布局在代码里定义（不依赖 `layout-land` 的重建机制）。插件不需要关心方向。

### 3.6 渲染词汇：插件的“画笔”

插件只能从设备提供的固定词汇中选择，不能自造（ADR-008/013）。分三层：

**① 页面版式（page layout）**

| layout | 用途 | 设备负责的事 |
|---|---|---|
| `stacks` | 小组件视图（StandBy 的智能叠放） | 两个卡片栈；上下滑翻栈；按相关度自动轮换；翻栈动画与指示点 |
| `focus` | 全屏主角元素（时钟表盘、倒计时、单个指标） | 超大字号、防残影漂移、变体切换动画 |
| `slideshow` | 全幅图片轮播 | Ken Burns、交叉淡入、预取下两张 |
| `nowplaying` | 正在播放 | 绑定设备播放器：封面、进度自走、64 根频谱、控制按钮 |
| `list` | 列表型内容（日程、队列） | 分组、滚动、条目入场错开 |
| `form` | 设置表单（仅 `settings` 这类系统插件用） | 开关、单选、滑块 / 步进、文本行等原生控件；分组、滚动、触控尺寸、就地展开动画；控件操作以 `form.change` 事件交给插件（§3.18，`04-ui-spec.md` §10） |

**变体（variants）**：页面可以声明多个变体，用户上下滑切换，例如 `clock.face` 的 `digital` / `analog` / `solar` / `world`，`gallery.slideshow` 的不同相簿。表盘等复杂样式由设备内置为 `clock` 组件的 `style`，插件只选用。

**② 卡片模板（widget template）** —— 卡片在 `stacks` 栈里的样子：`glance` / `media` / `list` / `photo` / `stack`（兜底，按 children 竖排）。

**③ 组件（component）**

- 静态组件：`text` / `image` / `list` / `icon` / `progress` / `player`
- **活组件**：`clock`（`style`: `digital`/`analog`/`solar`/`world`/`float`，`format`、`tz`）——设备本地按 TimeSource 走动，插件不需要每秒更新

图标 `icon.name` 映射到设备内置图标集；API 3 的 `image.src` 接受插件 `asset:assets/...`、已授权本地文件或 HTTP(S) 图片（network 权限；公网 HTTPS、私网 HTTP）。Lua 提供清单，OS 管理解码、平移与轮播，见 ADR-024。

**扩展流程**：需要新的版式或组件 → 在 APK 中实现 → 登记到 `shared/schema/plugin/` 与 `06-plugins.md` → 提升运行时 `api` 版本 → 插件才能使用。插件声明了设备不支持的版式时，设备回退到 `stack` 渲染并记录警告。

### 3.7 插件运行时（plugin/）—— 声明式 + Lua（ADR-016）

#### 3.7.1 为什么是 Lua 5.4，以及怎么嵌入

| 方案 | 结论 |
|---|---|
| **官方 Lua 5.4（C），随 APK 用 NDK 编译为 armeabi-v7a，自写 JNI 桥** | **采用 [提议]**。解释器快、对象不在 ART 堆上、不加重 GC；每个插件一个 `lua_State`，空状态约 20–40KB；从源码编译，ABI 由我们自己保证；没有第三方 AAR |
| LuaJ（纯 Java 实现的 Lua 5.2） | 备选。没有 native 代码、集成最简单，但运行慢数倍，对象都在 ART 堆上 |
| LuaJIT | 不采用。5.1 语义；ARMv7 上 JIT 收益有限；维护不活跃 |

- 源码放在 `android/app/src/main/cpp/lua/`（Lua 5.4 官方发布包，约 30 个 C 文件，MIT 许可），CMake 构建，`abiFilters` 只有 `armeabi-v7a`。
- JNI 桥只暴露少量入口：创建/销毁状态、加载脚本、派发事件、恢复协程；`tos.*` 的实现在 Kotlin 侧，通过注册的 C 函数回调进来。
- 若 JNI 桥的实现成本或稳定性不达预期，切换到 LuaJ，`tos.*` 接口不变，插件无感。

#### 3.7.2 线程与调度

```
PluginThread（单线程 HandlerThread，所有 lua_State 都只在这里执行）
  ├─ 事件队列：start / stop / page.enter / page.leave / command / presence / theme / timer / http 结果 …
  ├─ 每个事件处理函数在一个协程里运行
  │    └─ 调用 tos.http.get 等异步 API → 协程 yield → IO 线程完成后把结果投回队列 → resume
  └─ 状态变化：同一插件 50ms 内的多次 tos.state.set 合并为一次，按变化路径投递给主线程
```

- Lua 永远不在主线程执行，插件再慢也不会卡动画。
- 写法是同步风格（`local res = tos.http.get(url)`），实际不阻塞线程。
- `_dispatch` / `_timer` 在引导后缓存为注册表引用，事件直接传入类型化参数，不编译调用源码。JNI 用 Map/List/标量互转；API 3 的大媒体库结果改为 IO 编 JSON 文本、C 侧解码为表，`tos.state.set` 也支持对象 JSON 文本以避免大清单逐字段 JNI 成本。JSON 编解码由 C 侧 yyjson 完成。Lua 5.4 使用分代 GC，不再逐事件全量回收。
- 挂起的根协程保留注册表引用，IO 完成后恢复同一个任务；每次恢复重新计预算。实例停止/替换时取消任务，迟到结果丢弃。异步 `start` 成功前保留旧版，失败则回滚。

#### 3.7.3 沙箱与预算

| 项 | 规则 |
|---|---|
| 标准库 | 只开放 `base`（去掉 `dofile`/`loadfile`，`load` 只接受文本）、`string`、`table`、`math`、`utf8`、`coroutine`；**不开放** `io`、`os`、`package`、`debug` |
| 模块 | `require` 只能加载插件包内的 `.lua` 文件 |
| CPU / 墙钟 | 每次执行或恢复 ≤50ms 线程 CPU、≤200ms 墙钟、≤500 万条指令；每千指令检查计数，每万指令读时钟。资源错误有不可被 `pcall` 吞掉的锁存；与正常 API yield 区分 |
| C 字符串匹配 | `lstrlib.c` 加入预算检查；开放 `find/match/gmatch/gsub`，回溯、平衡扫描和字面搜索同样受限 |
| 内存 | Lua 与 JSON 分配共用计数：每插件 4MiB、合计 24MiB；超限记为资源错误 |
| 网络 | 需 `network` 权限；连接/读取超时各 3s，体积 ≤1MiB，最多 3 次重定向；明文只连经校验并固定 IP 的私网/本地地址，HTTPS 使用系统证书信任 |
| 存储 | 状态、`tos.store` 各 ≤256KiB；KV 在内存立即可读，最后修改后 2s 合并快照，由专用 IO 线程原子落盘，停止时也提交 |
| 失败处理 | 普通事件错误保留实例和状态；一小时内累计 5 次普通错误或单次资源错误触发重启，退避 2/4/8/16s；一小时内 5 次重启后冷却 1h，再自动试运行。保留最后画面，日志/诊断记录错误；Server 回报留到 M2 |
| 原生退出 | 每次进入插件原生执行前写共享 mmap 标记，正常返回或 API 挂起后清除；下次启动归因未清标记。同一包摘要一小时内 3 次不洁退出隔离 1h，更新包不继承旧摘要隔离 |

#### 3.7.4 视图与状态绑定

- 插件的声明式视图（`views/*.json`）在加载时编译：版式/模板 → View 树；`{{path | 格式化}}` → 绑定表（路径 → 若干 View 属性）。
- 插件状态是一棵表（`tos.state`）。脚本修改状态 → 运行时计算变化的路径 → 主线程只更新这些路径对应的 View，并按组件类型选择过渡动画。
- 绑定只支持路径取值和少量内置格式化（`round`、`time`、`date`、`upper`、`default`），**不支持表达式**——逻辑写在 Lua 里。
- 没有 Lua 的插件当前只能提供静态声明（如 home）；`sources` 调度器尚未实现。数据插件使用 `tos.http` / `tos.library.list` 和 `tos.state`，见 `06-plugins.md`。

插件之间只通过声明的公开数据交互（API 6，ADR-027，接口见 `06-plugins.md` §6.8）：提供方发布状态时，运行时取出 `exports` 声明的路径，值变了才重新发布相关使用方的快照（`uses` 与 `config` 同为保留键）。未变化的值保持同一对象，UI 差量比较可直接跳过。图片按提供方授权加载的规则也在运行时解析。实现见 `plugin/PublicData.kt`。

#### 3.7.5 插件的安装（目录 + 文件清单，ADR-018）

| 项 | 设计 |
|---|---|
| 形态 | **一个目录 + 一份文件清单**，没有压缩包。清单每项 `{path, sha256, size}`；**摘要** `digest = sha256(按 path 排序的 "path\0sha256\n" 拼接)`，用来命名安装目录、做原生退出归因（`13-pre-m1-hardening.md`） |
| 来源 | ① APK 内置：`assets/plugins/<id>/…` 加一份同样格式的 `assets/plugins/index.json`，首次启动安装，作为出厂版本 ② Server 下发：期望状态里的文件清单，文件按 sha256 从 blob 接口下载 ③ debug 构建的开发目录：`files/dev-plugins/<id>/` |
| 安装目录 | `filesDir/plugins/<id>/<version>-<digest前12位>/`；当前版本由 `filesDir/plugins/installed.json` 指向 |
| 安装流程 | 逐文件下载（本地已有同 sha256 的文件复制或硬链接，不重下）→ 校验 sha256 与 size → 在 `.stage-<digest>` 临时目录拼出 → 校验文件数 / 大小限制（≤128 个文件，JSON / Lua ≤256KiB，其它 ≤1MiB，总计 ≤8MiB，无符号链接、无 `..`）、manifest 与视图 schema、`api`、权限 → `rename` 原子切换 → 停旧插件、启新插件（热加载）→ 回报结果 |
| 回滚 | 新版本启动失败 → 自动回到上一版本并回报；维护入口可“恢复出厂插件” |
| 权限 | 期望状态里的 `permissions` 是用户在 Server 上确认过的集合；manifest 申请的权限超出它则不授予。系统权限（`system.settings`）只授予 APK 内置版本 |
| API 版本 | manifest `api` 大于运行时支持的版本 → 拒绝安装并回报 |

设备端**不依赖 Git**：Git 取回发生在 Server 上（`06-plugins.md` §12），设备只看到“文件清单 + blob”。这一做法同时去掉了设备端的 zip 解包与防路径穿越代码。

> 现状（M0）：安装单位是确定性 zip（`.tpk`），摘要为 zip 的 sha256。M0.5 改为目录形态，摘要的定义随之变化；归因和隔离逻辑按“摘要”工作，不受影响。

### 3.8 动效引擎（anim/）

#### 3.8.1 统一时钟 MotionClock

- 自绘场景使用**一个** `Choreographer.FrameCallback`，按需推进主题、唤醒波纹与 HUD；没有活动自绘动画时 AWAKE 退到 1Hz、AMBIENT 3Hz、ASLEEP 1Hz。
- 可以集中限帧、集中降级，也能保证多个循环动画相位一致。
- View 属性动画（位移、透明度）照常用 `ViewPropertyAnimator`，时长和缓动一律取自 `Motion` tokens。

#### 3.8.2 帧率档位 FrameGovernor

| 档位 | 自绘动画帧率 | 何时使用 |
|---|---|---|
| Tier A | 60fps | AWAKE / ENGAGED、翻页、通知入场 |
| Tier B | 30fps | AMBIENT（4s 周期的呼吸在 30fps 下肉眼无差别，CPU 约减半） |
| Tier C | ≤ 10fps，时钟按分钟更新 | ASLEEP |

限帧方式：MotionClock 在 vsync 回调里按档位跳帧，**不**去改系统刷新率。

M0 实现说明：档位表示调度上限，静态内容按变化更新。真机优化后，AMBIENT 慢背景约 3Hz 更新；分钟数字仅内容变化时重绘，防残影每秒以 1/8px 精度更新。主题和交互过渡仍使用相应高帧率上限，避免在几乎无像素变化时持续提交整页合成。

M1 前加固：背景圆只画入硬件层一次，呼吸改为 scale/alpha；数字时钟呼吸改为 View alpha；模拟表盘缓存刻度，秒级只画指针。AWAKE 的属性动画由系统合成调度，不能把 MotionClock 的低频唤醒误认为屏幕只跑 1fps。页面拖动同时展示相邻页并按手指全量移动，松手继承速度做弹簧吸附；栈内卡片采用相同机制。同页插件热更新原地差量更新；走近/离开使用波纹与卡片交错浮现/沉下。

#### 3.8.3 掉帧自适应

- 用 `Window.addOnFrameMetricsAvailableListener`（API 24+）采集每帧耗时。
- 连续 3 秒超过 16.6ms 的帧占比 > 25% → `perf` 降一级：频谱 64→32 → 关闭背景光晕 → Lottie 降为静态帧。持续 60 秒健康 → 升回一级。
- 帧统计（帧数、janky%、P90 耗时）每 60 秒记入本地指标，Server 在线时随回报上传。

#### 3.8.4 动画分工

| 类型 | 实现 | 例子 |
|---|---|---|
| 参数化、连续、跟数据走 | Canvas 自绘 | 呼吸场、频谱、时钟数字、进度条、Ken Burns |
| 设计师做的一次性/循环小动画 | Lottie（`RenderMode.HARDWARE`） | 天气图标、唤醒波纹、通知图标 |
| 布局与转场 | ViewPropertyAnimator + FLIP | 卡片入场、翻页、旋转重排 |

#### 3.8.5 动作词汇表（事件 → 动画）

| 事件 | 反应 |
|---|---|
| 有人走近（→ AWAKE） | 背景呼吸加快一拍 → 中心波纹扩散 → 卡片依次浮现 |
| 人离开（→ AMBIENT/ASLEEP） | 卡片依次沉下淡出 → 呼吸放慢 → 亮度在 2s 内缓降 |
| 通知到达 | 背景向强调色偏移一次 → 通知出现（形态随 Attention）→ 提示音 |
| 手势被识别 | 屏幕边缘对应方向一道轻光，作为“我看到了”的确认 |
| 语音被识别 | 底部聆听条出现 → 识别成功后打勾消失 |
| 音乐节拍 | 背景呼吸幅度跟随低频能量（轻微） |
| 主题切换 / 进出夜间 | 颜色 token / 颜色矩阵平滑插值 |
| 插件新装 / 升级完成 | 新卡片带入场动画出现，不打断当前页面 |

完整时长与缓动见 `04-ui-spec.md` §5。

#### 3.8.6 防残影

Stage 整体每 3 分钟以亚像素速度漂移 ±8px（横向最多 8 px、纵向最多 6 px，公式在 `ui/BurnIn.kt`）；ASLEEP 时时钟每分钟换一次位置（缓动过去，不跳）。**盖满屏幕的页面不随它漂移**，否则两条边会露出页面底色（浅色主题下是白边）：磁贴、Aero、照片舞台与视频舞台都把底板钉在屏幕边缘，只有前景和小的叠层漂移（视频舞台没有前景漂移，控件浮层只出现几秒）。

### 3.9 同步与网络（sync/、net/）

设备与 Server 的关系是“**被管理**”而不是“被驱动”。详细协议见 `05-protocol.md`。

```
SyncAgent
 ├─ 发现：NsdManager 发现 _thinkos._tcp；失败时用配置里的固定地址
 ├─ 拉取期望状态：开机、每 5 分钟、收到实时通道的 nudge 时；带 ETag，未变化即 304
 ├─ 对齐：比较期望与实际 → 下载缺失制品（按 sha256 寻址，插件按文件）→ 校验 → 安装/卸载插件、应用设置、同步照片集
 └─ 回报：实际状态（已应用版本、插件健康、错误）+ 指标 + 崩溃日志

LiveLink（可选）
 └─ WebSocket：hello 认证；心跳 20s；指数退避 1→30s；断开不影响任何本地功能
     收：nudge（期望状态变了）、alert（通知）、command（远程指令）、server.message（服务推送）
     发：event（Server 订阅了的设备事件）、server.call（插件调用 Server 服务）、语音帧
```

- 所有 HTTP 请求连接超时 3s；下载可断点续传；大文件只在 Attention ≤ AMBIENT 时下载，避免交互时抢带宽。
- **离线是常态之一**：同步失败只记日志和重试，不弹提示，不改变画面。
- 插件访问外网（天气 API、Navidrome）走同一个 `OkHttpClient`，直接出网，不经过 Server。

### 3.10 媒体（media/）

- **播放**（已实现，ADR-030，设计与验收见 `27-streaming.md`）：Media3 ExoPlayer 1.9.2，一个进程级会话 `MediaEngine`；每个队列项按自己的类型建 `MediaSource`（渐进式 HTTP、HLS、DASH、RTSP、本地文件可混排），音源由插件给出，设备不关心来源。`LoadControl` 缓冲 ≤30 秒、≤48 MB（系统设置，ExoPlayer 默认给远程视频 125 MiB，本机放不下）。AC-3 / E-AC-3 / DTS / ALAC 由 FFmpeg 扩展解码（GPL-3.0，可用 `-Pthinkos.ffmpeg=false` 关掉）。网络故障自动退避重连，直播被断开会重连，缓冲停滞 30 秒视为卡死。
- **影院视图与控件**：`video` / `controls` / `seekbar` 是 OS 绘制的组件（`ui/MediaComponents.kt`），按钮直接调引擎、不经过 Lua；字幕、缓冲和错误提示、音轨与字幕切换都在 `video` 里。进度条自己读播放头，横向拖动不会被翻页手势抢走。
- **频谱** **[提议]**：在 ExoPlayer 音频链路里插入 `TeeAudioProcessor` 拿到解码后的 PCM：
  - 不需要 `RECORD_AUDIO` 权限（`Visualizer` 需要）；与声音对齐要按带时间戳的帧取最接近播放位置的一帧，Tee 点在 `AudioTrack` 之前，直接画会比声音早约 250–750 ms，见 `22-player-plan.md` §8.2。**未实现**
  - PCM → 环形缓冲 → 工作线程每 33ms 做一次 1024 点 FFT → 64 个对数频段 → 平滑 → 主线程绘制
- **提示音**：`SoundPool` 预加载，音量比当前媒体音量低约 6dB；播放音乐时先压低音乐再响提示音。
- **播放器所有权**：同一时间只有一个插件持有播放器（`tos.media`）。每个插件实例有自己的会话令牌，插件重启或被替换时旧会话被释放，迟到的回调进不了新的 Lua 虚拟机；状态以保留键 `media` 发布，事件（`media.session` / `media.track` / `media.error` / `media.warning` / `queue.end`）只发给持有者，见 `06-plugins.md` §6.10。
- **音量 / 静音**：`AudioManager` 的 `STREAM_MUSIC`。

### 3.11 感知（vision/ 与 voice/）

#### 3.11.1 传感器与视觉流水线（ADR-004 的具体实现）

```
传感器（几乎零开销，常开）
  ├─ 接近 VCNL4200 PRX ──“近”──> 立即把人脸检测提到 3fps
  └─ 环境光 VCNL4200 ALS ──> 主题 / 夜间模式 / 亮度上限 / 视觉是否 blind

Camera2（HandlerThread）
  └─ 持续预览到 ImageReader（YUV，320×240，AE 帧率区间取最低档）
       └─ 按当前档位抽帧，丢弃其余帧
            ├─ L0a 运动检测：Y 平面下采样到 40×30 做帧差 → 2fps
            ├─ L0b 人脸检测：MediaPipe FaceDetector（short-range）→ 1fps；运动/接近触发时临时 3fps
            └─ L1  手势：MediaPipe 手部关键点 / GestureRecognizer → 10fps，
                         仅在 AWAKE/ENGAGED 且 15s 内有过交互时开启，单次连续 ≤ 10s
```

- **接近传感器**的有效距离需实测；若只有几厘米，则仅用于“手靠近屏幕 → 显示控件”。
- **“走近”的判断**：人脸框面积在 1–2 秒内持续变大，或从无到有且面积超过阈值 → AWAKE。只有运动没有人脸 → 只把呼吸调快一点，不亮屏。
- **手势**：握拳、张掌取 GestureRecognizer 内置类别；“左右挥动”根据手掌中心点 x 坐标在 0.5s 内的位移方向自行判断。
- **相机一直开着，只调抽帧率**：Camera2 开关本身很贵，频繁开关容易出现资源无法释放。
- **暗光**：环境光低于阈值 → 标记 `blind`，暂停检测，每 30s 再试一次。
- **隐私**：画面不离开设备、不落盘。
- 检测结果作为 Intent 进入 Store；插件可通过 `tos.on("presence")` 等订阅（需 `presence` 权限）。

#### 3.11.2 语音（可选，默认关闭）**[提议：识别放到 Server]**

```
设备：AudioRecord 16kHz 单声道
      └─ 仅在「语音已开启」「Server 在线」且 Attention ≥ AWAKE 时采集
      └─ 20ms 一帧 PCM16，作为实时通道的二进制帧推给 Server        ≈ 32KB/s，设备 CPU ≈ 1–2%
Server： Voice Sidecar（独立进程）
      └─ VAD → 关键词识别（关键词表由已安装插件 manifest 的 phrases 汇编）
      └─ 命中 → 下发 command（与点按钮走同一条路径）+ VOICE_HINT
```

- 设备上不常驻语音模型，省出约 50–100MB；不用 Porcupine（需 AccessKey 联网校验）。
- 语音是可选增强：Server 不在线时语音不可用，其它一切照常（G6）。

### 3.12 Kiosk 外壳与自愈（shell/、service/）

| 机制 | 作用 |
|---|---|
| HOME + DEFAULT intent，`set-home-activity` | 按 Home 永远回到 ThinkOS；没有“桌面”概念 |
| 沉浸式 `IMMERSIVE_STICKY` + `onWindowFocusChanged` 里重设 | 状态栏/导航栏不出现 |
| Lock Task + `setStatusBarDisabled`（`shell/KioskPolicy`，设置项 `kiosk.lock`） | 需 Device Owner。屏蔽状态栏下拉、Home、Recents、底部手势；`onResume` 与设置变化时应用，维护菜单“打开系统设置”前先释放，回到 ThinkOS 后重新锁定。非 Device Owner 时为空操作 |
| `BootReceiver` | 开机拉起 Service 和 Activity |
| `KioskService` 前台服务 | 保活；持有插件运行时、播放器、相机、同步 |
| 看门狗（30s） | Activity 不在前台 → 用 `FLAG_ACTIVITY_NEW_TASK` 拉回 |
| 崩溃自恢复 | `UncaughtExceptionHandler`：写崩溃日志 → `AlarmManager` 1s 后重启 → 杀进程；1 小时内连续崩溃 ≥ 5 次 → **安全模式**（只运行内置 `clock` 插件，关闭相机和其它插件） |
| 插件原生退出归因 | 共享 mmap 标记跨进程保留当前插件及包摘要；下次启动累计并隔离故障摘要，避免原生崩溃循环。它是插件级恢复，与 Java 全局安全模式互补；不是任意系统崩溃的精确诊断器 |
| 维护入口（原生救援菜单） | 隐藏手势（左上角双指长按 5s）→ 原生菜单：退出安全模式、恢复出厂插件、重置设置、打开系统设置、查看诊断。它是**唯一保留的原生设置界面**：`settings` 插件被禁用、崩溃或处于安全模式时，长按页面也会改为打开它。日常设置（含 Server 地址与配对）在 `settings` 插件的设置页里（§3.18）。文档中其它位置的“维护页 / 维护入口”即指这个救援菜单 |

Device Owner 属于系统级操作，按 `07-build-deploy.md` §4.3 交由人工执行；应用侧代码（`admin/ThinkOSAdminReceiver`、`shell/KioskPolicy`）已就绪，未设置 Device Owner 时不生效。

### 3.13 时间与本地数据

**TimeSource**

```
可信时间 = 系统时间（年份 ≥ 2024 视为已被 timesync 校准）
        ?: Server 在线时 hello 应答里的时间 + 单调时钟推算
        ?: 不可信 → 时钟显示 "--:--" 占位（呼吸动画照常）
```

时钟走秒只依赖单调时钟；系统时间被校准时平滑过渡而不是跳变。

**App 自己校时（`time/TimeSync`）**：API 27 上应用不能写系统时间，所以 `WallClock.now()` = 系统时间 + 偏移，界面、插件 `tos.time` 和日出日落都走它。启动即校，失败按 30 秒…30 分钟退避重试，成功后每天 `time.sync_hour` 点再校；系统时钟被 ROM 的 NTP 改动（`ACTION_TIME_CHANGED`）时偏移清零重测。不依赖 Server。

**本地数据（设备自主运行的基础）**

| 数据 | 位置 | 上限 | 说明 |
|---|---|---|---|
| 已安装插件 | `filesDir/plugins/` | — | 当前版本 + 上一版本（回滚用） |
| 插件存储 | `filesDir/store/<id>.json` | 每插件 256KB | `tos.store` |
| 设置 | `filesDir/settings/server.json`（Server 下发层）、`filesDir/settings/local.json`（设备上的修改） | 每插件 ≤64KiB | APK / schema 默认值 ← Server 下发 ← 设备上的修改（§3.18） |
| 照片集 | `getExternalFilesDirs()[0]/Pictures/` | 本地文件，不另建副本 | 应用私有外部目录，免 READ_EXTERNAL_STORAGE；M2 下发亦使用此根；OS 保留完整画面 |
| 最后画面快照 | `filesDir/snapshot/` | 每页 1 份 | 冷启动先画上次的画面，插件启动后再更新 |
| 网络图片 | `cacheDir/images` | 50MiB / 512 项 | URL 哈希缓存；单张≤8MiB；缓存先读，原子提交和访问时间淘汰，显示中 pin；不依赖 Server |
| 图片内存 | 当前 + 转场两张 | 每张 ≤2MP / 窗口像素×1.5 | 无全尺寸 LruCache，离页清空引用 |
| 待回报的指标/日志 | `filesDir/outbox/` | 5MB，丢弃最旧 | Server 恢复后补传 |

冷启动顺序：显示快照（< 1s 出画面）→ 恢复呼吸 → 启动插件运行时（内置插件优先）→ 插件状态到达后差量过渡 → 后台尝试同步。冷启动 < 3s 不依赖网络。

### 3.14 线程模型

| 线程 | 用途 | 规则 |
|---|---|---|
| Main | Reducer、View、MotionClock 绘制、绑定更新 | 单帧预算 12ms；图片平移为独立的原生连续属性动画；不做解码、不做 IO、不跑 Lua |
| Plugin（单线程） | 所有 Lua 状态 | 每次执行/恢复 ≤50ms CPU +200ms 墙钟；IO 等待必须 yield |
| `Dispatchers.IO` | 网络、磁盘、同步、插件的异步 API | — |
| `ThinkOS-ImageIO`（2 线程） | 通用网络图片下载与缓存 | 同 URL 合并，取消请求，沿用媒体网络策略；慢网络不堵塞本地/缓存读取 |
| `ThinkOS-ImageDecode`（单线程） | 本地/包内/缓存图片解码 | 边界校验、采样、EXIF 旋转/镜像；不在主线程解码；当前未用 inBitmap 复用 |
| Camera（HandlerThread） | Camera2 回调 | 只拷贝 Y 平面，立即归还 Image |
| Vision（单线程） | 运动/人脸/手势推理 | 忙时直接丢帧，绝不排队 |
| Audio-FFT（单线程） | 频谱计算 | 33ms 一次 |
| ExoPlayer 内部线程 | 播放 | — |
| Voice（单线程，可选） | AudioRecord 读取 + 发送 | — |

### 3.15 资源预算

**内存（目标峰值 < 350MB，PSS）**

| 部分 | 预算 |
|---|---|
| ART 运行时 + 代码 + View 树 | ~70MB |
| Lua 运行时（所有插件合计上限） | 24MB |
| 照片 bitmap（2 × 800×1280×4B） | ~8MB |
| 图片内存缓存 | 32MB |
| Lottie（≤ 3 个） | ~15MB |
| ExoPlayer 缓冲 | ~10MB |
| MediaPipe 模型 + 张量 | ~40MB |
| 相机缓冲 | < 1MB |
| 余量 | ~150MB |

**CPU（8 核总占比）**

| 场景 | 目标 | 主要开销 |
|---|---|---|
| ASLEEP | < 4% | L0 运动检测 + 极低帧率绘制 |
| AMBIENT 待机 | < 8% | 30fps 呼吸 + 1fps 人脸 + 插件定时任务 |
| 音乐 + 频谱 | < 25% | 解码 + FFT + 60fps 频谱 |
| 手势模式 | < 45% | 10fps 手部模型（限时 ≤ 10s） |

插件的定时任务由运行时对齐到同一时刻批量唤醒（例如每分钟整点），避免 CPU 被零散唤醒。

### 3.16 包结构

```
android/app/src/main/
├── cpp/
│   ├── lua/                    Lua 5.4 官方源码（不修改）
│   ├── tos_bridge.c            JNI 桥：状态、加载、事件、协程、分配器、指令钩子
│   └── CMakeLists.txt
├── assets/plugins/             构建时复制的内置插件目录 + index.json（文件清单，不提交）
└── java/moe/kiwi/thinkos/
    ├── ThinkOSApp.kt  AppGraph.kt  MainActivity.kt
    ├── shell/                  BootReceiver、KioskPolicy、CrashRecovery、MaintenanceGesture
    ├── service/                KioskService
    ├── admin/                  ThinkOSAdminReceiver
    ├── core/                   Store、DeviceState、Intents、Reducer、Effects、
    │                           AttentionPolicy、DeckRouter、ThemePolicy、TimeSource
    ├── settings/               SettingsStore、SettingsSchema（生成）、SettingsApplier（把设置应用到各策略）
    ├── plugin/
    │   ├── PluginRuntime.kt        Plugin 线程、事件队列、调度
    │   ├── LuaBridge.kt            JNI 声明
    │   ├── api/                    tos.* 各模块（State、Timer、Http、Store、Page、Alert、Audio、Album、Server、Log）
    │   ├── view/                   视图 JSON 编译、绑定表、格式化器
    │   └── pkg/                    目录安装、文件清单校验、版本切换、回滚
    ├── ui/
    │   ├── stage/                  Stage、四个 Layer、PageHost
    │   ├── page/                   页面版式：Stacks、Focus、Slideshow、NowPlaying、List、Form
    │   ├── card/                   卡片模板：Glance、Media、List、Photo、Stack
    │   ├── component/              Text、Image、List、Icon、Progress、Player、Clock
    │   ├── overlay/                AlertView、ToastView、VoiceHintView
    │   ├── theme/                  颜色 token、插值、夜间滤镜
    │   └── layout/                 LayoutSpec
    ├── anim/                       Motion、MotionClock、FrameGovernor、FrameMonitor、AmbientField、
    │                               SpectrumView、KenBurnsView、LottiePool
    ├── media/                      PlayerController、SpectrumTap、Fft、Chime
    ├── vision/                     SensorSource、CameraSource、MotionDetector、PresenceDetector、
    │                               GestureDetector、PerceptionManager
    ├── sync/                       SyncAgent、DesiredState、Reconciler、Outbox
    ├── net/                        Discovery、Http、LiveLink、Protocol.kt（GENERATED）
    └── voice/                      VoiceUplink [可选]
```

### 3.17 依赖（按 `android/app/build.gradle.kts`）

| 用途 | 当前依赖 | 说明 |
|---|---|---|
| 协程 | kotlinx-coroutines-android 1.10.2 | 纯 JVM/Android |
| JSON | `org.json` | Android 自带；JVM 测试用 20240303 |
| 网络 | OkHttp | Media3 的 okhttp 数据源传递引入；插件 HTTP 和媒体各自执行策略 |
| 播放 | Media3 ExoPlayer 1.9.2 | HLS、DASH、RTSP、OkHttp 数据源，纯 Java |
| 音频解码扩展 | Jellyfin media3-ffmpeg-decoder 1.9.0+1 | 含 armv7，GPLv3，可用 `-Pthinkos.ffmpeg=false` 关闭 |
| 脚本 | Lua 5.4 源码 | NDK 编译为 armv7 `libtos.so` |
| AirPlay 镜像 | playfair 源码 | NDK 编译为 armv7，GPLv3，见 ADR-043 |
| 图片 | 自有 ImageLoader / BitmapFactory / LruCache | 没有 Coil / Glide |
| 动画 | Canvas / View 属性动画 / Motion | 没有 Lottie 依赖，`assets/lottie/` 为空 |
| 测试 | JUnit 4.13.2、org.json | 不打包到 APK |

计划中的 kotlinx-serialization、Lottie、MediaPipe Tasks Vision 尚未引入；频谱、相机感知也未实现。没有 DI 框架、RxJava、Compose 或 WebView。当前 compileSdk 35、minSdk = targetSdk = 27；所有打包原生库只含 `armeabi-v7a`。新增预编译依赖须先检查 armv7 与许可证。

### 3.18 设置（settings/，ADR-017）

OS 只提供设置，不提供外观扩展点。设置分系统级和页面级，格式相同（`06-plugins.md` §11.1），界面由内置的 `settings` 插件自动生成。

```
 写入方：SyncAgent（Server 下发层） │ tos.settings.set（settings 插件） │ 救援菜单“重置设置”
                          │
                          ▼
 SettingsStore（主线程，设备上唯一的设置持有者）
   schema：系统级（system.json 生成）+ 各插件 manifest.settings（安装 / 卸载 / 升级时注册 / 注销）
   三层值：默认值 ← server.json（Server 下发）← local.json（设备修改）
   校验（类型 / 范围 / choices / 跨字段）→ 不可变的生效值快照 → 合并 0.5s → 原子写盘（IO 线程）
                          │
          ┌───────────────┼────────────────────┐
          ▼               ▼                    ▼
  SettingsApplier     插件线程              SyncAgent
  （ThemePolicy、      tos.config 刷新       回报 overrides
   AttentionPolicy、   + `config` 事件
   DeckRouter、方向、亮度）
```

- **所有可配置项都走 SettingsStore**：阈值、计时、开关、地址不写死在代码里，也不另建 SharedPreferences。页面的**变体**（上下滑选择）保持现状，由 DeckRouter 记录，是页面状态而不是设置项；设置页里不重复出现
- **系统设置的消费者**用类型化访问器（由 schema 生成）读取，不碰 JSON；策略对象在下一次采样 / 计时时读取新值，方向与亮度立即应用，主题切换仍走 2 秒颜色插值
- **页面级设置的消费者**是插件：`tos.config` 是生效值的只读表，值变化时收到 `config` 事件；视图里用 `{{config.<key>}}` 绑定。插件**不能写**设置，修改只能经 `tos.settings.set`，而这个 API 只授予持有 `system.settings` 的 `settings` 插件
- **线程**：SettingsStore 的写入在主线程完成（和 Reducer 一致），生效值是不可变快照，投递给 Plugin 线程和各策略；落盘在 IO 线程、合并 0.5 秒、原子替换，进程突然死亡最多丢最近半秒的修改
- **容量与健壮**：每插件 ≤32 项、≤64KiB；插件升级后新增 key 取默认值、删除的 key 保留不用、不再合法的值回落默认值；卸载插件保留其设置值，重装后恢复
- **回报**：`local.json` 的内容（除 `secret` 字段）随回报上传（`05-protocol.md` §3.5、§4）。冲突规则：设备修改优先，Server 对同一 key 下发了与上次不同的值才清除该 key 的设备修改
- **`settings` 插件**：内置、固定安装、持有 `system.settings`，通过 `form` 版式渲染表单；长按入口、退出方式、救援菜单回退见 §3.3、§3.12 与 `06-plugins.md` §11.3
- **不属于设置**：页面顺序、角色、首页两栈内容、安装哪些插件、照片集——它们是 Server 管理的部署配置（`06-plugins.md` §8）

---

## 4. Server：下发与管理装置（server/）

### 4.1 定位

Server 是设备的**管理者和后勤**，不是设备的大脑：

- **管理**：设备登记与配对、期望状态（装哪些插件、什么配置、哪些照片集、APK 版本）、查看设备状态
- **下发**：插件包、照片集、配置、APK 更新
- **中转**：手机通知、远程指令（手机 → Server → 设备）
- **增强**：语音识别、Server 本机的音乐库、Server 端服务（插件通过 `tos.server` 调用）
- **观测**：指标、日志、崩溃收集

Server 停机时，设备上的一切照常运行；恢复后设备自动对齐期望状态并补传日志。

### 4.2 模块

```
server/
├── cmd/thinkos-server/main.go     装配：读配置 → 起各模块 → 优雅退出
└── internal/
    ├── config/      TOML 配置（服务端口、`[[plugins]]` 来源、各设备的期望配置）；热重载
    ├── protocol/    GENERATED - DO NOT EDIT（来自 shared/）
    ├── gateway/     HTTP 路由：/api/v1/…（同步、回报、制品）、/ws、/notify、/healthz
    ├── discovery/   mDNS 广播 _thinkos._tcp
    ├── devices/     设备登记、配对 token、期望状态文档与版本号、实际状态镜像
    ├── registry/    插件来源管理：从 Git 仓库取回（ls-remote / 浅克隆 / ls-tree / cat-file）、topic 搜索、schema 与限制校验、锁定 commit、更新检查、文件按 sha256 存储；也支持本地目录来源
    ├── content/     照片集：扫描目录、EXIF 方向、按设备像素横竖各裁一份、HEIC 经 sips 转码、取主色
    ├── live/        实时通道：连接管理、nudge、alert、command、server.message 推送、离线队列
    ├── relay/       通知入口 POST /notify、远程指令入口 → live（设备离线时排队，TTL 1h）
    ├── services/    Server 端服务注册表（tos.server.call 的目标）：内置 media（Server 上的音乐库流）与服务插件
    ├── voice/       [可选] Voice Sidecar 管理、关键词表汇编
    ├── observe/     回报、指标、日志、崩溃存储与查询
    └── version/
```

### 4.3 期望状态（desired state）

每台设备一份期望状态文档，由 `config.toml`、插件锁文件（`server/data/plugins.lock.json`）与本地插件目录计算得出，内容变化时版本号 `rev` 递增：

```
desired(device) = {
  rev, config（设置文档：系统级设置 + plugins.<id> 页面级设置；deck 部署配置），
  plugins: [{id, version, digest, source{git url/path/ref/commit | local}, permissions, files[{path, sha256, size}]}],
  albums:  [{id, rev, items: [{sha256, portrait_url, landscape_url, tint}]}],
  apk?:    {version, sha256, url}
}
```

- 用户改 `config.toml`、执行 `plugin add / update / remove`，或本地目录来源的文件变化 → registry 重新取回并校验 → `rev` 递增 → live 推 `nudge` → 设备拉取并对齐。
- **加功能的路径**：`thinkos-server plugin add <git 仓库地址>`（或把目录放进本地来源）→ 设备自动收到并热加载。不重装 APK、不重启任何东西（`06-plugins.md` §12）。
- APK更新按`34-ota-and-plugin-registry.md` §4执行：Device Owner或已配置且实际具备静默资格的受限更新器支持静默安装，`update.auto_install`默认开启，满足安静时段及媒体/前台等门禁后才自动安装；关闭后等待用户点“立即安装”。两者均无时走Android确认。Server只是可选镜像/管理者，不能绕过目录验签与安装门禁。此处不宣称正式钥匙/来源端到端、安静时段全部分支已验收，实际范围见`36-task-list.md` OTA组。

### 4.4 Server 端服务（可选增强）

设备上的插件可以调用 Server 端服务（`tos.server.call(name, args)`、`tos.server.subscribe(topic)`），用于设备做不了或不划算的事：

| 服务 | 内容 |
|---|---|
| `media` | 把 Server 本机音乐目录发布为可 Range 的流地址、读取标签与封面 |
| `notify` | 中转的手机通知，以 `server.message` 推给订阅了 `notify` 主题的插件 |
| 服务插件 | 跑在 Server 上的独立进程（stdio JSON-RPC），只提供数据，不提供界面 |

Server 不在线时，`tos.server.*` 返回 `nil, "offline"`，插件应自行降级（例如 music 插件改用 Navidrome 直连，或显示“音乐库不可用”）。

### 4.5 并发模型

- HTTP 请求各自独立；期望状态计算在 devices 模块的单协程里串行执行，不需要加锁。
- 每个实时连接：一个读协程 + 一个写协程 + 有界发送队列。
- registry 的 git 取回与校验、content 的裁图在 worker 池里执行，结果按 sha256 落盘，天然幂等；每次 git 调用带超时和输出上限。
- 全程使用 `context` 贯穿，Ctrl-C 时 2s 内优雅退出。

### 4.6 依赖（Go）

| 用途 | 库 |
|---|---|
| WebSocket | `github.com/coder/websocket` |
| TOML | `github.com/BurntSushi/toml` |
| mDNS | `github.com/hashicorp/mdns` 或 `github.com/grandcat/zeroconf` |
| 文件监听 | `github.com/fsnotify/fsnotify`（本地目录来源） |
| Git | 调用系统 `git` 可执行文件（不引入 Go 的 git 库）：继承 Mac 上的凭据、代理与 ssh 配置 |
| JSON Schema 校验 | `github.com/santhosh-tekuri/jsonschema` |
| Lua 语法检查 | 调用 `luac -p`（若已安装），否则用 `github.com/yuin/gopher-lua` 只做解析 |
| 图像 | 标准库 `image/jpeg` + `golang.org/x/image/draw`；HEIC 调用 `sips` |
| 日志 | 标准库 `log/slog` |

---

## 5. 通讯概览

完整定义见 `05-protocol.md`，要点：

| 通道 | 方向 | 内容 | 必需吗 |
|---|---|---|---|
| 同步（HTTP） | 设备向 Server 拉取 | 期望状态（ETag）、制品（按 sha256） | 只对“被管理”必需 |
| 回报（HTTP） | 设备向 Server 推送 | 实际状态、指标、日志、崩溃 | 否（离线时本地排队） |
| 实时（WS） | 双向 | nudge、alert、command、server.message / server.call、语音帧 | 否 |

设备内部的页面/卡片内容（版式、绑定、状态）**不再出现在网络上**；它们的结构由 `shared/schema/plugin/` 定义，用于设备端解析和 Server 端打包校验。

---

## 6. 关键时序

### 6.1 开机（不依赖 Server）

```
设备上电 ─(60–90s 系统启动)─> BootReceiver ─> KioskService ─> MainActivity
MainActivity：读快照 → 立刻画出上次的画面 + 呼吸（时间不可信时显示 --:--）
PluginRuntime：首次启动则安装 APK 内置插件 → 启动已安装插件 → 插件状态到达 → 差量过渡
SyncAgent（后台）：mDNS 发现 → 拉期望状态 → 有变化则下载、安装、热加载 → 回报
```

### 6.2 有人走近（完全本地）

```
接近传感器“近” 或 帧差超过阈值 ─> 人脸检测提到 3fps ─> 检测到人脸且面积在增大
  ─> Intent.PresenceApproach ─> Reducer：ASLEEP/AMBIENT → AWAKE
  ─> Effects：亮度 2s 内缓升；FrameGovernor 切到 Tier A；手势模块待命；DeckRouter 切到 home 角色页
  ─> Stage：呼吸加快 → 波纹 → 卡片浮现
  ─> 订阅了 presence 的插件收到事件
```

### 6.3 天气更新（插件自主，不经过 Server）

```
weather 插件 tos.timer.every(600) ─> tos.http.get(天气 API)（协程挂起，IO 线程请求）
  ─> 结果回到 Plugin 线程 ─> tos.state.set{temp=26, desc="多云", …}
  ─> 绑定更新：温度数字滚动、图标交叉淡入
```

### 6.4 手机通知

```
手机快捷指令 POST Server /notify ─> relay ─> live：server.message{topic:"notify", …}
  （设备离线时排队，TTL 1h）
  ─> 设备 notify 插件（tos.server.subscribe("notify")）收到 ─> 去重 / 免打扰判断 ─> tos.alert{…}
  ─> Overlay 入队；若 ASLEEP 先升到 AWAKE ─> 通知出现 → 提示音
```

### 6.5 放音乐 / 下一首

```
点击 music.mini 卡片 ─> 本地立即切到 music.nowplaying ─> 插件收到 command("play")
  ─> 插件取音源（Navidrome 直连，或 tos.server.call("media.resolve") 取 Server 上的音乐库流）
  ─> tos.audio.play(url, {title, artist, cover}) ─> ExoPlayer 拉流
  ─> playback 事件 ─> 插件 tos.state.set ─> 页面局部过渡；tos.page.focus(..., {live=true})
“下一首” 可以来自：点击 / 手势 / 语音 / 手机远程指令，最终都是插件收到 command("next")
```

### 6.6 新增一个插件

```
Server： thinkos-server plugin add https://github.com/alice/thinkos-stocks --tag v1.0.0
  ─> git ls-remote 解析 tag → commit ─> 浅克隆（bare，不检出）─> ls-tree / cat-file 读文件
  ─> 校验：白名单 / 大小 / 符号链接 / schema / api / id 冲突 ─> 显示申请的权限，用户确认
  ─> 写 config.toml 与 plugins.lock.json，文件按 sha256 入 blob 库 ─> 期望状态 rev+1 ─> nudge
设备：拉取期望状态 ─> 逐文件下载并校验 sha256 ─> 在临时目录拼出 ─> 校验 schema 与限制 ─> 原子安装 ─> 启动插件
  ─> DeckRouter 按配置把 stocks.board 加入 deck，stocks.ticker 卡片放进首页栈
  ─> 新卡片带入场动画出现；回报安装结果（含 commit 与摘要）
全程不重装 APK、不重启服务；之后 Server 关机或仓库删库，stocks 插件照常在设备上运行
```

### 6.7 修改一项设置

```
长按天气卡片 ─> DeckRouter 打开 settings.form，params.scope="weather"（页面按下反馈 → 缩小淡出 → 设置页淡入）
  ─> settings 插件 tos.settings.get("weather") ─> 状态里放 fields ─> form 渲染控件
用户改“温度单位”为华氏 ─> 设备触发 form.change("unit","f") ─> 插件 tos.settings.set("weather","unit","f")
  ─> SettingsStore 校验通过 ─> 生效值快照 ─> weather 插件收到 config 事件 ─> 立即刷新温度
  ─> 0.5s 后原子写盘 local.json ─> 下次回报时上传 overrides（Server 在线时）
```

---

## 7. 失败模式

| 故障 | 设备表现 | 恢复 |
|---|---|---|
| Server 停机 / 睡眠 | **无变化**，所有插件照常运行；收不到新下发、通知中转和语音 | 指数退避重连；恢复后对齐期望状态、补传日志 |
| Server 换了 IP | 同上 | mDNS 重新发现 |
| 外网断开 | 依赖外网的插件显示最后数据（由插件决定如何提示），其余正常 | 插件自行重试 |
| 某个插件脚本报错 / 超时 / 超内存 | 页面/卡片保留最后画面；普通错误保留实例，其它插件继续执行 | 频繁错误/资源超限后退避重启；多次重启冷却 1h 自动重试；原生不洁退出按摘要隔离 |
| 新版本插件启动失败 | 无感 | 自动回滚到上一版本并回报 |
| 下载的制品校验失败 | 无感 | 丢弃并重试，连续失败回报 |
| 设备时间错误（刚开机） | 时钟显示 `--:--` | timesync 完成后恢复 |
| 主线程异常 | 闪一下后回到画面 | CrashRecovery 1s 后重启；连续崩溃进入安全模式 |
| Activity 被系统杀掉 | 音乐不断、插件不重启 | 看门狗 30s 内拉回 |
| 相机被占用 / 打开失败 | 退化为接近传感器 + 触摸唤醒 | 每 60s 重试 |
| 暗光 | 不做视觉检测 | 每 30s 重试 |
| 掉帧 | 动画逐级简化 | 恢复健康 60s 后升回 |
| 协议版本不一致 | 设备照常运行，只是不同步；维护页显示“需要更新” | 人工升级 APK 或 Server |

---

## 8. 可观测性

- 设备端日志统一 tag `ThinkOS`，按模块前缀（`[plugin]`、`[sync]`、`[vision]`…）；插件 `tos.log` 带插件 id 前缀。
- 本地指标每 60s 一条：帧统计、PSS、Lua 内存（按插件）、各线程 CPU、当前档位、相机档位；写入 outbox，Server 在线时回报。
- 崩溃日志写入 `filesDir/crash/`，随回报上传。
- 服务端 `log/slog` 输出 JSON 到 `server/logs/`；`/healthz` 返回设备在线情况、期望/实际 rev 是否一致、插件健康。
- 调试 HUD（维护入口可开关）：FPS、Attention、主题、相机档位、Link、各插件状态。

---

## 9. 待确认的新增设计（确认后登记到 08-decisions.md）

| # | 提议 | 相关章节 |
|---|---|---|
| P1 | 设备状态拆成 Attention / Deck / Overlay / Link 四条正交的轴；删除对话类状态 | §3.3 |
| P2 | 页面版式 + 卡片模板 + 活组件三层渲染词汇（插件优先的落地方式） | §3.6 |
| P3 | 语音识别放到 Server（设备只在有人且 Server 在线时采集推流），不在设备上用 Porcupine/Vosk | §3.11.2 |
| P4 | 频谱用 ExoPlayer `TeeAudioProcessor` 抽取 PCM，不用 `Visualizer` | §3.10 |
| P5 | mDNS 服务发现与插件热加载在 MVP 内完成 | §3.7.5、§3.9 |
| P6 | 不引入 Coil/Glide，照片由 Server 预裁尺寸、设备自写轻量加载器 | §3.17、§4.2 |
| P7 | 帧率三档（60/30/≤10）随 Attention 切换 | §3.8.2 |
| P8 | 接近传感器做存在感知的预触发、环境光驱动主题/夜间模式与亮度 | §3.4、§3.11.1 |
| P9 | Lua 采用官方 5.4 源码 + NDK 编译 + 自写 JNI 桥（备选 LuaJ） | §3.7.1 |
| P10 | 插件沙箱与预算：单次 50ms、每插件 4MB / 合计 24MB、禁用 io/os/package/debug | §3.7.3 |
| P11 | 设置体系：系统级 + 页面级共用一种声明格式，`SettingsStore` 统一保管，设置界面是 `settings` 插件（**已定，ADR-017**） | §3.18 |
| P12 | 插件以 Git 仓库分发，设备安装单位为目录 + 文件清单（**已定，ADR-018**） | §3.7.5、`06-plugins.md` §12 |

### API 4 磁贴补充

`tiles` 是原生固定网格，`tile` 是原生卡片模板；页面引用决定尺寸，Lua 只通过 tos.* 提供数据。底色是 ADR-025 对 ADR-017 的唯一外观例外，前景色与所有排版仍由 OS 决定。PageView 维护单个翻面调度器，失活或 AMBIENT 停止。系统设置 tiles.live/tiles.interval 仍经过共享 schema 和 SettingsStore；不引入新偏好文件。契约、代码和真机结果见 `24-tiles-validation.md`。


### API 5 Aero 与磁贴缓存

`aero` 由 `AeroHomeView` 承载固定原生桌面，复用普通 widget 引用、ComponentGroup、Binding 与图片生命周期。所有玻璃/背景仅为静态 Canvas 渐变，任务栏/开始菜单属于 OS，不给插件增加私有能力。API 与 ADR 见 `25-windows-design.md`。

磁贴天气使用工作线程构建的完整透明帧图集；相同名称、尺寸与相位共享 ALPHA_8 像素，每个循环 View 用缓存的 HARDWARE sheet 与裁剪/平移显示帧，避免每帧重绘 mask 或刷新硬件层。图集缓存上限 10 项 / 12MiB；超大图标保留缓存 mask 的属性合成，避免过大纹理。旧 Bitmap 仅释放引用，不改写或 recycle 渲染线程仍可能使用的像素。纯磁贴文字复用 2MiB / 128 项 alpha 字形缓存，保留 TextView 的原生 shaping、自动字号与无障碍。

整页遮挡 Ambient 时仅暂停 disk 呼吸及 fallback tick，保留静态底色供防烧屏位移边缘使用；翻页预览时恢复。磁贴 AMBIENT 保持 alpha=1，避免破坏前景对比度。所有缓存和合成参数是固定渲染实现，不构成插件样式接口。


Aero 的静态窗口和文字由单一后台 raster worker 绘制。UI 只传不可变几何/颜色/字符串和 TextPaint 副本，每窗仅一个待处理任务；新结果按代际/尺寸校验后发布，旧 Bitmap 等渲染引用释放。Image/Clock/Icon/其它 RenderComponent 保留原生动态节点。模拟钟的表盘和窄指针为独立硬件缓存，实际扫秒由 RenderThread 属性动画执行；UI 不为扫秒升至 60Hz。普通 ClockView 与 TileView 不改变，缓存不会冻结动态内容。
