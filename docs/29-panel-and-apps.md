# 29 · 操作栏与应用：策划

> 2026-11-03，阶段1已有设备证据；APP-1/5安卓应用网格、tos.apps接口、图标和直接打开门禁已通过真机功能验收。外部App的进一步行为、广播和叠层仍按§7先验证。阶段状态以docs/36任务清单为准。
> 标记：**已核对** = 读过代码或文档；**提议** = 我的设计，可以推翻；**未核对** = 开工前先上真机验证（写初稿时设备没有连上 adb；§7 第 1 项后来在真机上验证过，结果写在表后）。
> 先读：`AGENTS.md` → ADR-013 / 016 / 017 / 027 / 033 → `06-plugins.md` §4、§6 → 本文。

## 1. 用户要什么

1. 屏蔽安卓通知栏，**从屏幕顶部往下拉统一打开 ThinkOS 的操作栏**。
2. 操作栏能控制多媒体、亮度、音量、网络。
3. 操作栏**是一个 Lua 插件**，符合现有规范，系统只留接口。
4. 增加「应用」概念：把一些页面或卡片定义为应用，只做简单功能，如投屏、媒体、电视、广播。
5. ThinkOS 是系统 Launcher，也要能打开安卓的其他 App。

**不做**：通知中心（`tos.alert`还没实现，操作栏不等它）、安卓App商店、拖动排列（ADR-017已否决长按编辑）、节目单（EPG）、站点解析、蓝牙和热点开关。签名插件中心`hub`属于ADR-041及`34`号文档的范围，不是安卓App商店。

## 2. 现状（已核对）

| 事实 | 位置 | 对方案的影响 |
|---|---|---|
| 通知栏靠 Device Owner 屏蔽，本机是 Device Owner，但开关绑在 `kiosk.lock` 上 | `KioskPolicy.kt:50`、`27-streaming.md:307` | 要把“屏蔽通知栏”和“锁定任务”拆开 |
| 上下滑已用于切变体、翻栈，影院视图的竖拖调亮度和音量 | `ThinkStage.kt`、`VideoGestures.kt` | 只有**从顶部边缘起手**才算下拉，并且优先于这些手势 |
| 亮度和音量已有统一入口 `DeviceLevels`（亮度即 `display.brightness_max`） | `RuntimeHost.kt:101` | 滑块直接复用，不新建第二条路径 |
| 任何有 `media` 权限的插件都能控制当前会话，`controls`、`seekbar`、`{{media.*}}` 已有 | `06-plugins.md` §6.10 | 操作栏的媒体区几乎不用新接口 |
| 系统权限的先例 `system.settings`：只给 APK 内置版本，按摘要校验 | `PluginRuntime.kt:880` | 新系统权限照抄 |
| `settings` 角色页面不进翻页序列，`live` 页面用返回退出（ADR-040；本表写成时是左右滑） | `DeckRouter.kt` | “应用”页面用同一套规则 |
| 进入页面时旧页面收到 `page.leave`；`cast` 收到后会**暂停投屏、结束镜像** | `plugins/cast/main.lua` | 操作栏不能走页面切换，必须是叠层，否则一拉就断投屏 |
| Lock Task 的白名单只有 ThinkOS | `KioskPolicy.kt:49` | 现在打不开别的 App |
| API 27 没有 `setLockTaskFeatures`（API 28 才有），锁定中 Home、Recents 都不可用 | Android 文档 | 外部 App 不能留在 Lock Task 里，否则可能回不来 |
| 看门狗每 30 秒把不在前台的 ThinkOS 拉回来，只有 5 分钟维护窗口例外 | `KioskState.kt`、`KioskService.kt` | 必须给外部 App 一个明确的状态 |
| 亮度和常亮都是 ThinkOS 窗口的属性 | `MainActivity.kt:39`、`:148` | 到了外部 App 里两者都失效，要另外处理 |
| 视图不支持条件表达式 | `06-plugins.md` §4.2 | 媒体区“没播放就隐藏”由 OS 版式规则负责 |
| `tiles` 固定1–16项且不滚动 | `06-plugins.md` §4.1.0 | 安卓应用页用新组件 `grid`（DEC-5，ADR-045） |
| 文档没有记录 FM 硬件，无线模块是 QCA9379（Wi-Fi + 蓝牙） | `02-device.md:18` | “广播”按网络电台做 |
| 设备有 LatinIME，可以输入密码 | `02-device.md:119` | 连新 Wi-Fi 可以交给安卓设置页 |

## 3. 操作栏

### 3.1 分工（提议）

| 归 OS（APK） | 归插件 `panel`（Lua + 视图） |
|---|---|
| 顶部下拉手势、跟手、吸附、遮罩、关闭 | 操作栏放哪些区块、什么顺序 |
| `panel` 版式：横竖屏排版、颜色、字号、动画 | 快捷按钮有哪些、点了做什么 |
| `slider`、`toggle` 组件；滑块直接改设备，不经 Lua | 把设备状态整理成显示文案 |
| `tos.device.*`：真正去改 Wi-Fi、静音、打开安卓设置 | 打开应用、打开设置 |

和设置页同一个思路：`settings` 是插件，`form` 版式和控件是 OS 的。

插件出问题时（崩溃、被隔离、安全模式），下拉不响应。长按进设置和左上角双指长按进救援菜单不依赖它，照常可用（§11.3 “修插件的工具不能依赖插件”）。

### 3.2 手势（提议）

- **触发**：单指从当前方向的顶部 24dp 内起手，向下超过 slop，且竖向位移大于横向。第二根手指落下即取消，所以救援手势不受影响。
- **优先级**：在 `ThinkStage` 最早判断，优先于变体切换、翻栈、影院视图拖动和列表滚动。起手在 24dp 之外的手势全部维持原样。
- **例外**：设置页打开时不响应（延续“没有东西能把用户拉出设置页”）。
- **提交**：沿用翻页规则，下拉超过 22% 或快速甩动就打开，否则回弹。
- **关闭**：上滑、点遮罩、按 Home、无操作 15 秒。
- **旋转**：跟随屏幕方向，竖屏时“顶部”是竖屏的顶部。

操作栏是 `ThinkStage` 里的一层叠层，**不是页面切换**：当前页面不收到 `page.leave`，投屏和播放都不中断，夜间红色滤镜和防残影继续统一生效。打开期间保持 ENGAGED。

### 3.3 版面（提议）

```
横屏 788×492dp
┌──────────────────────────────────────────┐
│ 10:42  11月2日 周一                 ⚙ 设置 │ header
│ [Wi-Fi 家里] [静音] [投屏] [应用]            │ quick（toggle，最多 4–6 个）
│ ☀ ──────────●───── 85%                    │ levels（slider）
│ 🔊 ─────●────────── 40%                    │
│ 曲名 · 艺术家            ⏮  ⏯  ⏭            │ media（没有会话时 OS 自动收起）
│ ━━━━━━●───────── 01:23 / 04:56             │
│ 家里 · 192.168.1.7             网络设置 ›   │ footer
└──────────────────────────────────────────┘
          遮罩（下层页面仍在运行）
```

竖屏同样的元素，宽度改为 492dp−边距。样式归 OS，不开放任何外观参数（ADR-017）。第一版媒体区不放封面：封面可能是网络地址，操作栏不该为此申请 `network`。

### 3.4 插件长什么样（提议）

```jsonc
// plugins/panel/manifest.json
{
  "id": "panel", "name": "@name", "version": "0.1.0", "api": 8, "main": "main.lua",
  "permissions": ["media", "system.device", "system.apps"],
  "pages": [{ "id": "main", "view": "views/main.json", "roles": ["panel"] }]
}
```

```jsonc
// plugins/panel/views/main.json（节选）
{ "layout": "panel", "slots": {
  "quick":  { "children": [
    { "type": "toggle", "icon": "wifi", "label": "{{wifi.name}}", "on": "{{wifi.on}}", "tap": "wifi" },
    { "type": "toggle", "icon": "apps", "label": "@apps", "tap": "apps" } ] },
  "levels": { "children": [
    { "type": "slider", "level": "brightness" },
    { "type": "slider", "level": "volume" } ] },
  "media":  { "children": [
    { "type": "text", "value": "{{media.title}}", "style": "body" },
    { "type": "controls", "mode": "queue" }, { "type": "seekbar" } ] } } }
```

```lua
-- plugins/panel/main.lua（节选）
local function refresh()
  local d = tos.device.get()
  tos.state.set({ wifi = { on = d.wifi.enabled, name = d.wifi.ssid or tos.i18n.t("wifi.none") } })
end
tos.on("page.enter", refresh)
tos.on("device", refresh)
tos.on("tap", function(name)
  if name == "wifi" then tos.device.wifi(not tos.device.get().wifi.enabled)
  elseif name == "apps" then tos.apps.open("launcher"); tos.panel.close() end
end)
```

### 3.5 新增接口（API 8，提议）

| 接口 | 权限 | 说明 |
|---|---|---|
| 角色 `panel` | `system.device` | 不进翻页序列，只由下拉打开；只允许一个，取 APK 内置版本 |
| 版式 `panel` | — | 槽位 `header` / `quick` / `levels` / `media` / `footer` |
| 组件 `slider` | `system.device` | `level`：`brightness` / `volume`。由 OS 直接调 `DeviceLevels`，跟手不经 Lua |
| 组件 `toggle` | — | `icon`、`label`、`on`、`tap`；按下反馈归 OS，点击照常发 `tap` |
| 图标 | — | `wifi`、`wifi-off`、`volume`、`volume-off`、`brightness`、`tv`、`radio`、`cast`、`apps`（以及 `settings` 换成同一套的齿轮）。**取自 Iconfont 的 Lucide，不自己画**（`assets/README.md`）；矢量，按实际大小绘制 |
| `tos.device.get()` | `system.device` | `{brightness, volume, muted, wifi={enabled, ssid, level, ip}, online}` |
| `tos.device.wifi(bool)` / `mute(bool)` | `system.device` | 开关 Wi-Fi、静音 |
| `tos.device.openAndroid("wifi"\|"settings")` | `system.device` | 打开安卓的 Wi-Fi 页或设置页，走 §5 的外部 App 流程 |
| 事件 `device` | `system.device` | 亮度、音量、网络变化时通知 |
| `tos.panel.close()` | `system.device` | 关闭操作栏 |
| `tos.settings.open(scope)` | 无 | 打开某范围的设置页；只是打开，不能读写 |

`system.device` 和 `system.settings` 一样，只给 APK 内置版本。第三方插件要控制媒体仍然用 `media`，不开放设备控制。

### 3.6 网络做到什么程度（提议）

- **操作栏里**：Wi-Fi 开关、当前网络名、信号、IP。
- **连新网络**：点“网络设置 ›”进安卓的 Wi-Fi 页，用系统输入法填密码。自己做一套 Wi-Fi 列表和密码框工作量大，用户也看不出区别，所以第一版不做。
- 新增 `CHANGE_WIFI_STATE`；读 SSID 可能还要位置权限（见 §7），由 Device Owner 静默授予。

### 3.7 屏蔽通知栏（提议）

- 把 `KioskPolicy` 拆开：`setStatusBarDisabled` 跟随新设置 `panel.enabled`，不再跟随 `kiosk.lock`。这样关掉锁定去用别的 App 时，下拉仍然是 ThinkOS 的操作栏。
- 救援菜单的“打开系统设置”照旧放开通知栏，留一条完全回到安卓的路。
- 不是 Device Owner 时屏蔽不了，操作栏照常可用，但拉得够用力仍可能带出安卓的状态栏。

### 3.8 临时系统栏（已核对，2026-11-02）

通知栏屏蔽之后，从屏幕边缘滑动仍会唤出安卓的状态栏和导航栏（沉浸式粘性模式的“临时栏”）：两条一起出现，约 1.5 秒后自己收回。真机用 `input swipe` 测得，读的是 `dumpsys window policy` 里两条栏的 `mTransientBar`：

| 滑动起点（竖屏 800×1280） | 结果 |
|---|---|
| 离上沿 4px、30px，向下 | 状态栏和导航栏都出现 |
| 离上沿 45px、60px，向下 | 都不出现 |
| 离下沿 4px（y=1276），向上 | 都出现 |
| 离下沿 44px（y=1236），向上 | 都不出现 |

分界就是状态栏的高度（24dp，本机约 38px）。识别发生在系统框架里、触摸到达任何窗口之前（`PhoneWindowManager.requestTransientBars`），应用无权拒绝：要免于它需要平台签名的 `STATUS_BAR` 权限。Device Owner、Lock Task（API 27 没有 `setLockTaskFeatures`）、沉浸式标志、`policy_control` 都试过或推过，都做不到；把 `user_setup_complete` 改成 0 能让系统跳过它，但会连带动设置向导和 Device Owner 的前提，不用。

所以走系统层：Vector 模块 `system/thinkos-immersive`（ADR-037）钩住这一个方法，焦点窗口是 ThinkOS 时什么都不做；别的应用、包括操作栏打开的安卓设置页，系统栏和返回键照旧。**没装模块时**：从离边缘 26dp 以下起手的滑动不会唤出系统栏，但操作栏的触发区就在边缘，所以每次下拉都会闪一下；这是已知现象，见 `10-known-issues.md` §13。

## 4. 应用

> 应用用apps[]声明，以返回退出，页面内左右滑归应用；player.library、iptv.channels、cast.home已有设备证据。2026-11-03补齐plugins/apps与tos.apps.*的设备端代码，APP-5安卓网格已过真机功能验收，证据见artifacts/app/APP-5/report.md。规则以33号文档（ADR-040）为准。用户同日决定只列安卓App、使用图标网格，卡片叫“安卓应用”；操作栏按钮已改经launcher角色去磁贴页，APP-6手势验收进行中（ADR-045，37号文档）。

### 4.1 什么是应用（提议）

**应用不是新的运行形态，只是插件 manifest 里的一个入口**，指向自己的一个页面（ADR-013 “页面即插件”不变）：

```json
"apps": [{ "id": "tv", "page": "channels", "title": "@app.tv", "icon": "tv" }]
```

- 被 `apps` 指向的页面**退出左右翻页序列**，只能从应用列表、磁贴或操作栏进入。这样加再多应用，首页的左右翻页也不会变长。
- **返回**：返回（最左缘右滑、Back 键）回到进入前的页面，和 `live` 页面、设置页的规则一致；按 Home 回首页；下拉操作栏仍然可用。
- **卡片当应用**：写一个单行页面 `{"layout":"focus","slots":{"hero":{"widget":"weather.now"}}}`（API 6 已支持），再让入口指向它。不新增机制。
- 变暗、夜间、`ambient` 和 `sleep` 的规则全部照旧，应用没有特权；正在放视频时已有的 `media.keep_awake_video` 继续负责亮屏。

### 4.2 安卓应用网格：插件 `apps`（设备功能已验）

apps.main自身也是应用，不进左右翻页序列。由tiles.apps的绿色“安卓应用”磁贴进入，返回到进入前的页面。自身入口listed:false。公开list接口仍返回ThinkOS和安卓两类，页面过滤只显示安卓App；媒体、电视、投屏继续使用磁贴页上的独立入口。操作栏的“应用”现经tos.apps.open("launcher")到launcher角色磁贴页；相关底边手势见37号文档APP-6。

```
┌───────────────────────────────────┐
│ 应用                               │
│ [投屏] [媒体] [电视] [广播] [天气]    │  ThinkOS 应用
│ ───────────────────────────────── │
│ [相机] [文件] [浏览器] …             │  安卓 App（可关）
└───────────────────────────────────┘
```

接口（tos.apps为API9；grid的既有API8契约现已接入渲染器）：

| 接口 | 权限 | 说明 |
|---|---|---|
| 组件 `grid` | — | 原生GridView复用单元，item只有icon/image/title；56dp图标、14sp单行名称，每96dp一列且至少3列，RTL镜像；没有外观参数 |
| 图片地址 `app:<包名>` | `system.apps` | 只由 OS 取图标、解码、缓存，插件拿不到字节（沿用 ADR-024） |
| `tos.apps.list()` | `system.apps` | 异步返回[{id,kind="page"\|"android",title,icon,image}]；ThinkOS项按apps[]和listed筛选，安卓项只含桌面入口，去掉ThinkOS自己及apps.hidden里的包 |
| `tos.apps.open(id)` | `system.apps` | 打开应用；安卓项走 §5 |

ThinkOS的id是插件.页面，安卓id是android:包名。OS只启动当前目录中可用的应用，再次核对apps.android/apps.hidden；Lua不能构造Intent。权限只授予APK出厂副本，第三方声明无效。包枚举在IO线程，列表刷新单飞并合并重入。apps事件报告目录、安装包和开关变化，受相同权限约束。图标在OS的图片解码线程绘制，单图最大256px、内存缓存4MiB，以包更新时间使缓存失效；像素不进入Lua。

### 4.3 四个应用（提议）

| 应用 | 实现 | 要做什么 | 第一版不做 |
|---|---|---|---|
| **媒体** | 现有 `player` | manifest 加一行 `apps`，`library` 退出翻页序列 | — |
| **投屏** | 现有 `cast` | 等待页 `cast.home`（**已做**）：设备名、三种协议的状态、发送端操作说明；开关通过 `tos.settings.open("system")` 跳到设置（插件不能写设置）。只读的 `tos.cast.status()`（要 `media` 权限，**已做**） | 二维码、页内开关 |
| **电视** | 新插件 **`iptv`**（已实现，2026-11-02，ADR-038，见 `31-iptv.md`） | 设置里填最多四个频道列表地址（M3U 或 JSON，列表源解耦）；同一频道合并成一个、多条线路自动测试并暂时停用失效的；节目单；分组、收藏、最近；点频道进 `live` 页，**专属组件 `tv`** 带上一台 / 下一台 / 频道列表 / 线路 / 收藏。没有采用 `controls` 的 `events` 模式：电视的按钮和手势要和节目、线路放在一起设计，做成一个组件更直接 | 回看、录制、内置频道表；设备读本地文件做列表（插件不能读文件，只能用地址）；还没有“应用”入口（`apps` 插件见阶段 2） |
| **广播** | `radio`（APP-3已实现，2026-11-03） | 网络电台，M3U / PLS 列表、直接地址、收藏，focus + 原生nowplaying显示台名和ICY曲名；返回继续播放，操作栏控制，故障后重连；19项真机功能通过，301秒含190秒源站故障，见APP-3报告 | FM 收音、电台目录搜索、定时关闭；小时级长稳/性能待验 |

几个具体决定：

- **电视和广播是两个独立插件**：频道表可能占满 256KiB 状态预算，分开后互不影响，符合“一个插件一件事”。`m3u.lua` 各带一份，因为 `require` 只能加载包内文件（电视的是 `plugins/iptv/m3u.lua`，多读 `tvg-id` 和列表头里的节目单地址）。
- **换台一次只装一个频道**：队列里每个 HLS 项都会提前取清单，几百个频道不能整表排队（`06-plugins.md` §6.10 已写明）。
- **视频上的竖拖仍是亮度和音量**：用户已经习惯这个手势，换台不抢它，用按钮和列表。
- **不内置任何频道或电台**：地址会失效，也有版权风险，由用户自己提供。
- **很多公网电视源是明文 HTTP**，默认会被 `media.public_http` 拦下。`iptv` 和 `radio` 收到 `category=policy` 的错误时要给出明确提示（`iptv` 已做：这条线路不记为失效，换下一条，全部被拦时画面上写明原因；去设置的入口还没有）。

## 5. 打开安卓 App（APP-2功能已验，叠层待APP-4）

### 5.1 流程

1. `tos.apps.open` → OS 记录 `KioskState.foreign = 包名`，停止 Lock Task，**继续屏蔽通知栏**，然后启动目标 App。
2. 看门狗看到 `foreign` 就不拉回。
3. 用户按导航栏 Home（默认桌面是 ThinkOS）或从操作栏回来 → `onResume` 清除 `foreign`，按 `kiosk.lock` 重新锁定。
4. **防止无人时一直停在外部 App**：进入外部 App 达到 `apps.return_after_s` 后，且 `AudioManager.isMusicActive()` 为假时自动回 ThinkOS（默认 900 秒，0 关闭）。

2026-11-03功能单轮14项通过，见`artifacts/app/APP-2/report.md`。包括实际拒绝Back后的Home返回、启动权限拒绝的异步结果与提示、视频暂停/镜像结束、局域网投屏移回原任务、96秒静音音频豁免超时及停音后的自动返回与重锁。启动请求只在Android接受后才返回成功；task恢复与底边返回共用ForegroundTask。音量0，无thinkos-shell，无Server、持久安装或重启；性能后置。

为什么不用 Lock Task 白名单：API 27 上 Home 在锁定时不可用，遇到不响应返回键的 App，用户就被困住了，这台设备没有实体 Home 键可以兜底。

### 5.2 伴随问题

| 问题 | 处理 |
|---|---|
| 离开 ThinkOS 后屏幕常亮失效 | Device Owner 设 `STAY_ON_WHILE_PLUGGED_IN`（`setGlobalSetting`） |
| 亮度是窗口属性，外部 App 里不生效 | 操作栏的叠层窗口带上 `screenBrightness`（见 §7 验证）；不行就在外部 App 里禁用亮度滑块 |
| 两边同时出声、ThinkOS 视频占解码器 | 启动外部 App 时暂停自己的视频、结束镜像；音乐交给现有的音频焦点机制（§7 验证） |
| 外部 App 运行时有人投屏过来 | 把 ThinkOS 拉回前台：这是用户在手机上的明确操作 |
| 很多 App 需要 GMS 或只有 arm64 | 打不开时回到 ThinkOS 并提示，不做预判过滤 |

### 5.3 在外部 App 里也能下拉（第 4 阶段）

“统一”要求外部 App 里也能拉，这是全案里风险最高的一步：

- 用 `TYPE_APPLICATION_OVERLAY` 放一条顶部窄条接手势，操作栏用同一个 `PanelHost` 放进叠层窗口。外部 App 不暂停，视频也继续播。
- 需要 `SYSTEM_ALERT_WINDOW`。这是 appop，Device Owner 不能静默授予。
- 叠层在状态栏之下：外部 App 显示状态栏时，最顶上的 24dp 碰不到。解法是全局隐藏状态栏（`policy_control immersive.status=*`，§7 验证）。
- 外部 App 里的栏不属于 `thinkos-immersive`（§3.8）：那里的返回键是离开它的路。这两件事都放进新的 Magisk 模块 `system/thinkos-shell`（ADR-033）：`service.sh` 开机执行，**由人工安装**；应用里不加 `su`。
- 没装模块时：外部 App 里不能下拉，用 Home 键回来，其它功能不受影响（ADR-015）。

如果窄条方案验证不通过，备选是 root 守护进程读触摸事件并通知应用。这样改动更深，只在必要时才做。

## 6. 系统设置新增（提议，走 `system.json`，ADR-017）

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `panel.enabled` | bool | 开 | 下拉打开操作栏；同时决定是否屏蔽安卓通知栏 |
| `panel.edge_dp` | number，16–48 | 24 | 顶部触发区高度，隐藏项 |
| `panel.idle_s` | number，5–60 | 15 | 无操作自动关闭 |
| `apps.android` | bool | 开 | 应用列表显示安卓App；APP-1接入后在桌面分类和应用插件相关设置可见 |
| `apps.hidden` | text | 空 | 不显示的包名，逗号分隔，隐藏项 |
| `apps.return_after_s` | number，0–7200 秒 | 900 | §5.1 第 4 步，0 关闭 |

## 7. 开工前要上真机验证（未核对）

| # | 问题 | 不成立时怎么办 |
|---|---|---|
| 1 | 屏蔽通知栏时，从最顶边下拉的触摸事件能否完整到达 ThinkOS；安卓是否仍闪出半透明状态栏 | 能到达但闪状态栏：先接受，再考虑模块隐藏。到达不了：触发区下移，或提前做叠层窄条（**已验证**：触摸能到达，状态栏和导航栏都会闪，见 §3.8） |
| 2 | 屏幕最边缘 24dp 的触摸灵敏度够不够 | 调大 `panel.edge_dp` |
| 3 | API 27 读当前 SSID 是否需要位置权限和位置服务 | 需要就静默授予；位置服务打不开时只显示“已连接”和 IP |
| 4 | 停止 Lock Task 后启动外部 App，导航栏 Home 能否回到 ThinkOS；原厂桌面重拉是否干扰 | 2026-11-03 APP-2前置已验：竖横屏实际点导航栏Home，在测试应用故意忽略Back时仍返回ThinkOS并恢复Lock Task；等待期间未被原厂桌面覆盖 |
| 5 | `setStatusBarDisabled` 是否连带影响外部 App 的 Home 键 | 前置已验：保持现有禁通知栏策略，竖横屏实体导航栏Home均可点；没有改Device Owner或安装模块 |
| 6 | 叠层窗口的 `screenBrightness` 对外部 App 是否生效；`policy_control` 是否有效 | 前置已验：叠层与前台应用是不同UID，NOT_FOCUSABLE叠层没有让应用暂停；0.1/0.6对应实际背光25/153。默认顶边手势不到叠层，临时`immersive.status=*`后一次DOWN/UP完整到达。原policy_control、亮度/常亮值及十文件已还原，两个测试APK已卸载。产品叠层及持久模块仍属APP-4 |
| 7 | 外部 App 抢音频焦点时 `MediaEngine` 会不会暂停 | 前置已验：外部应用取得真实AUDIOFOCUS_GAIN后，静音本地MP3在MediaEngine里变为paused；放弃焦点后仍paused，不强制自动恢复。音量始终0 |
| 8 | 网络电台的 ICY 曲目信息能否进入 `media.title` | 2026-11-03已验：本地静音MP3/ICY真实播放、Track One→Track Two及换台清除4项通过（APP-3/icy-fixed）。修复MediaItem台名覆盖曲名；解析仍由既有Media3处理，没有第二播放器 |

## 8. 分阶段

| 阶段 | 内容 | 验收（另加 `AGENTS.md` 的五条） |
|---|---|---|
| **0 验证** | §7 的 1–5；一个不入库的小原型 | 结论写回本文 |
| **1 操作栏** | API 8 schema 和代码生成；手势、叠层、`panel` 版式、`slider`、`toggle`、`tos.device.*`；`plugins/panel`；拆开通知栏开关 | 所有页面下拉都打开操作栏，投屏和播放不中断；原有手势不回归；断网可用；插件崩溃时救援路径仍在；**时钟页下拉、释放时秒的跳变不迟到不跳秒** |
| **2 应用**（APP-1/5功能已验） | apps[]、返回栈、原生grid安卓应用页、tos.apps接口与app图标（DEC-5，37号文档） | 48项基础、3项百项网格、2项目录、4项直接打开权限通过；应用不进翻页序列，返回原页；第三方不能列举或启动安卓应用。性能后置，见APP-5报告 |
| **3 电视、广播** | `plugins/iptv`（已完成）、`plugins/radio`；`tv` 组件取代原计划的 `controls` `events` 模式 | 上千行 M3U 不超预算；明文被拦时有提示；换台无残留解码器；广播长时间运行自动重连 |
| **4 安卓 App** | `tos.apps.open` 的外部 App 流程、看门狗、自动返回；然后是叠层下拉和 `system/thinkos-shell` | 打开任意 App 都能回来；不响应返回键的 App 也不会困住用户；未装模块时功能正常降级 |

阶段 1、2 必须按顺序，3、4 之间互不依赖。性能实测前按惯例先告知用户。

§7第4–7条的原始证据：`artifacts/app/APP-2/preflight-first/results.json`及driver.log，13项检查、独立叠层/背光/顶边观测，APK a1b07b86…；不替代APP-2产品代码的后续设备验收。

每阶段都要同步更新 `04-ui-spec.md`、`06-plugins.md`（词汇表、权限表、自带插件表）和 `shared/schema/`，数据类由生成脚本产出，不手写。

## 9. 风险

- **手势抢占**：顶部 24dp 内原本的滑动都会被操作栏接管，例如顶部的变体切换和影院视频的竖拖起手。触发区很窄，竖拖从别处起手即可，可以接受。
- **时钟优先**：操作栏只做位移和透明度动画，不用模糊和大面积阴影（`04-ui-spec.md` §6）；视图第一次下拉时才创建，不增加冷启动时间。如果第一次下拉有卡顿，改为手指按进触发区时预建，而不是冷启动时建。
- **外部 App 期间 ThinkOS 的控制力下降**：亮度、常亮、注意力都不再完全由它掌握，属于这个功能本身的代价，§5.2 已尽量补上。
- **内存**：2GB 设备上 ThinkOS 运行时和外部 App 同时存活，被系统回收后由看门狗恢复；大型 App 可能本来就跑不动。
