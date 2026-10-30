# 06 · 插件模型（声明式视图 + Lua）

> ThinkOS 自身不含任何具体功能：时钟、天气、相册、音乐、通知、首页**全部是插件**（ADR-013）。
> 插件**运行在设备上**（ADR-015），由「声明式视图 + 可选的 Lua 脚本」组成（ADR-016）；
> 插件以 **Git 仓库**分发（ADR-018），由 Server 取回、校验并下发，设备收到后热加载，Server 离线时插件照常运行。
> 插件可以声明**设置项**，由 OS 自动生成设置页（ADR-017）。页面长什么样完全由插件自己决定，OS 不为外观预留额外接口。
> 运行时实现见 `03-architecture.md` §3.6–3.7、§3.18。

> **实现状态**：M0 与 M1 前加固已实现视图、Lua 运行时与 `tos.*` 的大部分内容（下文 §6 标注了已实现范围）。
> **当前实现**：设备已支持设置体系（ADR-017）与目录 / 文件清单安装（ADR-018），APK 内置和 debug 热加载走同一套校验。Server 的 Git 取回与下发属于 M2，尚未实现。

## 1. 插件能贡献什么

| 贡献 | 是什么 | 例子 |
|---|---|---|
| **page（页面）** | 一个整屏页面，选用设备内置的页面版式 | `gallery.slideshow`、`music.nowplaying`、`clock.face` |
| **widget（卡片）** | 放进首页小组件栈的卡片 | `weather.today`、`music.mini` |
| **alert（通知）** | 叠层通知，可唤醒屏幕 | `notify` 插件转发的手机通知 |
| **command（指令）** | 可被点击、手势、语音、远程触发的动作 | `music.next`（“下一首”） |
| **settings（设置项）** | 在 manifest 里声明，OS 自动生成设置页 | `weather` 的城市、`clock` 的 24 小时制 |

**页面即插件**：设备上左右翻动的每一页都是某个插件的 page；首页 `home.main` 本身也是插件，它是一个 `stacks` 页面，两个栈里放的是其他插件的 widget。

**想要不一样的页面？** 写一个页面插件。把本文、`shared/schema/plugin/` 和一个现成插件（比如 `plugins/clock`）交给 AI，描述想要的页面，再用 §9、§12 的方式装到设备上即可。OS 只负责版式、动画、主题、设置这些共同的部分。

## 2. 插件目录结构

一个插件就是一个目录（通常是一个 Git 仓库的根目录，见 §12）：

```
<plugin-id>/
├── manifest.json               必需
├── views/                      声明式视图：每个 page / widget 一个 JSON
│   ├── today.json
│   └── detail.json
├── main.lua                    可选：入口脚本；可以 require 同目录下其它 .lua
└── assets/                     图标、图片、提示音
```

- **没有压缩包**：设备和 Server 之间传的是“文件清单 + 按 sha256 寻址的文件”（`05-protocol.md` §3）。仓库里的 README、LICENSE、`.git*` 等不在白名单内的文件会被忽略，不下发
- 白名单：`manifest.json`、`views/**/*.json`、`i18n/**/*.json`、`**/*.lua`、`assets/**` 下的图片与音频（`.png .jpg .jpeg .webp .ogg .wav`）
- 限制：最多 128 个文件；JSON / Lua 单文件 ≤ 256 KiB，其它单文件 ≤ 1 MiB，总计 ≤ 8 MiB；不接受符号链接；路径不得为绝对路径或含 `..`
- 插件的**摘要**（digest）= 按路径排序的“`path`、`sha256`”清单的 sha256，用来命名安装目录、做原生退出归因
- **纯声明插件**：没有 `main.lua`，靠 manifest 的 `sources` 或只靠配置驱动（例如 `home`）
- 自带插件放在本仓库 `plugins/` 下，APK 构建时随包内置；它们和第三方插件走完全相同的接口

每个设备安装输入目录含 `index.json`：`{id, version, digest, files:[{path, sha256, size}]}`，索引本身不计入文件清单（上限 64 KiB）。文件按路径的 UTF-8 字节顺序排列，`digest = sha256(UTF8(path + "\0" + sha256 + "\n") …)`，其中 `sha256` 为小写十六进制。拒绝重复路径、大小写冲突、控制字符、未列出文件以及哈希 / 大小不符的内容。

APK 根索引 `assets/plugins/index.json` 为 `{plugins:[上述完整索引…]}`；插件文件位于 `assets/plugins/<id>/…`。运行时先验证并拷贝到私有暂存目录，再做 manifest / view 语义校验，通过后发布到以完整 digest 命名的版本目录。失败候选不覆盖正在运行的版本；启动错误、预算终止与原生退出隔离延用相同回滚逻辑。

## 3. manifest.json

```json
{
  "id": "weather",
  "name": "天气",
  "version": "1.2.0",
  "api": 1,
  "icon": "assets/icon.png",
  "main": "main.lua",
  "permissions": ["network"],
  "settings": {
    "city": { "type": "text",   "label": "城市", "default": "Hong Kong" },
    "lat":  { "type": "number", "label": "纬度", "default": 22.3,  "min": -90,  "max": 90,  "step": 0.1 },
    "lon":  { "type": "number", "label": "经度", "default": 114.2, "min": -180, "max": 180, "step": 0.1 },
    "unit": { "type": "choice", "label": "温度单位", "default": "c",
              "choices": [ { "value": "c", "label": "摄氏" }, { "value": "f", "label": "华氏" } ] }
  },
  "pages": [
    { "id": "detail", "view": "views/detail.json", "title": "天气" }
  ],
  "widgets": [
    { "id": "today", "view": "views/today.json", "opens": "detail" }
  ],
  "commands": [
    { "id": "refresh", "phrases": ["刷新天气"] }
  ]
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 全局唯一，小写字母数字与 `-`；也是页面/卡片/指令的命名空间 |
| `name` / `version` | 是 | 显示名 / 语义化版本。用 Git 分发时，`version` 必须等于 git tag 去掉前缀 `v` 后的值（§12.2） |
| `api` | 是 | 需要的运行时 API 版本（当前为 `1`）；高于设备支持的版本时不会被安装 |
| `icon` | 否 | 包内相对路径 |
| `main` | 否 | 入口 Lua 文件；省略即纯声明插件 |
| `permissions` | 否 | 见 §6.3；未声明即拒绝 |
| `settings` | 否 | 本插件的设置项（页面级设置），格式见 §11.1；取代 M0 的 `config` |
| `pages[]` | 否 | `id`、`view`、`title`、建议的 `roles`（`home`/`ambient`/`sleep`；`settings` 仅限持有 `system.settings` 权限的插件）、焦点 `priority`、`variants`、`live`（可作为实时活动全屏接管） |
| `widgets[]` | 否 | `id`、`view`、`opens`（点击打开本插件的哪个页面） |
| `commands[]` | 否 | `id`；可选 `phrases`（语音固定短语）、`gesture`（`wave`/`left`/`right`/`fist`，冲突以配置为准） |
| `sources` | 否 | 纯声明插件的数据来源，见 §5 |

页面、卡片、指令的全名都是 `<plugin>.<id>`，例如 `weather.today`、`music.next`。

## 4. 声明式视图

视图只描述**内容与语义**，不描述像素、颜色、动画。排版、横竖屏、主题、夜间模式、动画都由设备完成。

### 4.1 渲染词汇

| 层 | 可选值 |
|---|---|
| 页面版式 `layout` | API 1：`stacks` / `focus` / 设置专用 `form`（§11.3）；API 2：`split` / `stage` |
| 卡片模板 `template` | 已实现 `glance`；M1 媒体与列表模板待实现 |
| 组件 `type` | API 1：`text` / `icon` / `clock`，`form` 只用于设置版式；API 2：`progress` / `chart` / `list` / `daylight` / `moon`，媒体词汇见下 |
| `clock.style` | `digital` / `analog` / `solar` / `world` / `float` |
| `text.style` | `display` / `title` / `body` / `caption` |

各版式、模板有哪些槽位以 `shared/schema/plugin/` 为准。需要新的版式或组件属于「设备端功能迭代」：先在 APK 实现、提升 `api` 版本，插件才能使用。

设备同时接受 API 1 和 2。使用新增词汇、组件组、`tap` 或 `weight` 的插件必须声明 `"api": 2`；安装器与打包器都检查，不会让 API 1 插件静默使用新版词汇。版本表来自 `layouts.json` 的 `x-thinkos-api`，并生成到 Kotlin / Go。

API 2 的固定组件与内容字段：

| 组件 | 内容字段 |
|---|---|
| `progress` | `value` / `max`（默认 1，可绑定）；`style` 为 `bar` / `ring`，非法范围显示空轨道 |
| `chart` | `values` 为数值数组或 `"{{路径}}"`，最多 168 点；`style` 为 `line` / `bar` / `area`；`min` / `max` / `highlight` 可绑定，高亮索引从 1 开始；缺值保留间隔，不连线跨过缺值 |
| `list` | `items: "{{数组路径}}"`，`item` 行模板可含 `icon/title/subtitle/trailing/progress/max`；使用原生列表复用行视图，支持滚动 |
| `daylight` | `path` 指向 `tos.astro.sun()` 的派生状态；复用日光表盘，不自行读取位置 |
| `moon` | `path` 指向 `tos.astro.moon()` 或其 `phase`；显示月相及照亮比例；`hemisphere` 为 `north/south` 或状态绑定，南半球水平镜像 |

用户于2026-10-30指定先测试UI。`controls/video/image/carousel` 的候选定义暂存在 schema 中，但尚未完成宿主接线，因此不在当前可安装的组件词汇中。设备在安装时拒绝这些声明，避免页面进入后因缺渲染器崩溃；媒体与图片的完整功能留待后续。

所有组件可带固定事件名 `tap` 与数值 `weight`（0…100）。点击发送 `tos.on("tap", function(name, index) ... end)`，其中 `name` 是声明值；列表行还给出从 1 开始的 `index`。OS 保留横向翻页手势，列表区域的纵向拖动用于滚动。`daylight/moon` 只消费派生数据，绘制本身不要求 `location` 权限。

`weight` 只分配 OS 组件组剩余高度：文本、图标、进度默认按内容高度；时钟、表单、图表、列表、天文组件默认共享剩余高度。显式 0 使用内容高度。`focus` 的槽位以及新版式槽位可放单个组件或 `{"children":[...]}` 组件组，不支持任意嵌套。版式固定：`split` 的 `lead/main` 横屏左右约 40/60、竖屏上下约 40/60；`stage` 的 `stage` 全出血，`over/under` 覆盖其上。插件不能设置像素、颜色、CSS 或自定义动画。

当前 `icon.name` 精确支持 `sun`、`moon`、`clock`、`calendar`；未知名称显示明确的占位符，不按子串猜测。布局槽位及 stack/header/content/footer 角色从同一份 schema 生成。

`clock.sun` 为状态路径，例如 `"sun": "sun"`，读取插件通过 `tos.astro.sun()` 取得的派生数据。solar 表盘以真实太阳中天为圆环顶部，用真实日出日落和三层曙暮光标出白昼区间；无有效天文数据时只显示空环与时间，不推测日出日落。OS 绘图只接收派生事件，不把位置反向暴露给插件。

### 4.2 绑定语法

在任何字符串字段里用 `{{路径 | 格式化}}` 引用插件状态（`tos.state`）：

| 写法 | 含义 |
|---|---|
| `{{temp}}` | 取状态 `temp` |
| `{{now.title}}` | 取嵌套字段 |
| `{{temp \| round}}°` | 格式化后拼接 |
| `{{updated \| time "HH:mm"}}` | 时间格式化 |
| `{{desc \| default "—"}}` | 缺省值 |
| `{{config.city}}` | 引用本插件的设置值（M0.5 起；可绑定的字段随 schema 逐步放开） |

内置格式化：`round`、`fixed n`、`time "fmt"`、`date "fmt"`、`upper`、`lower`、`default "x"`。**不支持表达式和条件**——需要逻辑就在 Lua 里算好再放进状态。

列表用 `"items": "{{queue}}"` 绑定整个数组，数组元素的字段在 `item` 模板里用 `{{.title}}` 引用。行内也可读取 `{{config.unit}}` 等插件全局状态；未带点前缀的路径先查行元素再查全局。数组绑定只读取完整数组路径，不执行表达式。

图片地址可以是：`asset:icon.png`（包内资源）、`album:family`（照片集）、`http(s)://…`。

### 4.3 示例

**卡片** `views/today.json`：

```json
{
  "template": "glance",
  "children": [
    { "type": "text", "value": "{{temp | round}}°", "style": "display" },
    { "type": "text", "value": "{{desc | default \"—\"}} · 湿度 {{humidity}}%", "style": "body" },
    { "type": "icon", "name": "{{icon}}" }
  ],
  "relevance": "{{relevance}}"
}
```

**页面（带变体）**——时钟插件的 `views/face.json`：

```json
{
  "layout": "focus",
  "variants": [
    { "id": "digital", "title": "数字" },
    { "id": "analog",  "title": "指针" },
    { "id": "solar",   "title": "日光" },
    { "id": "world",   "title": "世界" }
  ],
  "slots": {
    "hero":  { "type": "clock", "style": "{{variant}}", "format": "HH:mm" },
    "under": { "type": "text", "value": "{{date}}", "style": "body" }
  }
}
```

`variant` 是运行时自动提供的状态字段，表示用户当前选择的变体。

### 4.4 相关度与情绪色

- **相关度**：卡片可绑定 `relevance`（0–1）。用户 5 分钟内没有手动翻栈时，栈自动转到相关度最高的卡片（例如即将开始的日程、正在播放的音乐）。运行时会对相关度频繁跳动的插件降权。
- **情绪色**：页面可绑定 `mood`（`"#RRGGBB"`，通常取照片或封面的主色），设备按当前主题映射到安全色域，用来染色背景呼吸场。

## 5. 纯声明插件与 `sources`

没有逻辑的插件可以不写 Lua，用 `sources` 定时取数据填进状态：

```json
"sources": {
  "now": {
    "http": "https://api.open-meteo.com/v1/forecast?latitude={{config.lat}}&longitude={{config.lon}}&current=temperature_2m",
    "every": 600,
    "into": "raw"
  }
}
```

拉到的 JSON 放进状态 `raw`，视图里用 `{{raw.current.temperature_2m | round}}` 绑定。`sources` 支持 `http`（需 `network` 权限）和 `album`（需 `photos` 权限）。需要换算、判断或组合数据时，改用 Lua。

## 6. Lua API（`tos.*`）

运行环境：Lua 5.4，沙箱内只有 `string`、`table`、`math`、`utf8`、`coroutine` 和裁剪过的 `base`；没有 `io`、`os`、`package`、`debug`。`require` 只能加载包内文件。

字符串模式匹配已开放：`string.find/match/gmatch/gsub`（含冒号写法）都在 C 匹配器内部执行预算检查，病态回溯同样会触发资源错误。仅 `lstrlib.c` 有这一处上游补丁，详见 `android/app/src/main/cpp/PATCHES.md`。

每个事件处理函数都在协程里执行：标为「挂起」的 API 写起来是同步的，实际不阻塞任何线程。

`tos.http` 与 `tos.timer.sleep` 只能在运行时管理的事件/定时器根协程里调用，不能在自行 `coroutine.create` 的子协程或模块顶层调用。挂起保留局部变量；IO 完成恢复同一协程，同时其它插件/定时器继续执行。热替换或停止后，旧协程和迟到结果会取消。`start` 可以挂起，新版启动完成后才替换旧版；`stop` 中应只做同步清理，不启动新 IO。

当前实现范围：下表的 state/config/store/timer/http/json/time/page/log 已实现；alert/audio/chime/album/server 与声明式 sources 仍按 M1/M2 推进；`tos.settings` 属 M0.5。

### 6.1 生命周期与事件

```lua
tos.on("start", function() ... end)             -- 插件启动（含热加载后）
tos.on("stop", function() ... end)              -- 插件停止前，适合 tos.store 保存
tos.on("config", function(cfg) ... end)         -- 设置值变化（含 Server 下发、设备上修改）；cfg 为新的只读表
tos.on("page.enter", function(page, variant, params) ... end)  -- params：打开页面时附带的参数（设置页用 {scope=…}）
tos.on("page.leave", function(page) ... end)
tos.on("variant", function(page, variant) ... end)
tos.on("command", function(name, args, source) ... end)   -- source: tap / gesture / voice / remote
tos.on("form.change", function(key, value) ... end)       -- 仅 form 版式页面：用户操作了某个控件（§11.3）
tos.on("attention", function(level) ... end)    -- ASLEEP / AMBIENT / AWAKE / ENGAGED
tos.on("theme", function(theme) ... end)        -- LIGHT / DARK / NIGHT（一般不需要）
tos.on("presence", function(present) ... end)   -- 需 presence 权限
tos.on("gesture", function(name) ... end)       -- 需 presence 权限
tos.on("playback", function(st) ... end)        -- 仅播放器持有者收到
```

### 6.2 API 一览

| API | 权限 | 挂起 | 说明 |
|---|---|---|---|
| `tos.state.set(tbl)` | — | 否 | 合并写入状态；变化的路径会更新视图（50ms 内合并） |
| `tos.state.get(path)` | — | 否 | 读状态 |
| `tos.config` | — | — | 只读表：本插件所有设置项的当前生效值（manifest 默认值 ← Server 下发 ← 设备修改，见 §11.2）。插件不能写，值变化时收到 `config` 事件 |
| `tos.settings.list()` | `system.settings` | 否 | 列出所有有设置项的范围：`[{scope, name}]`，`scope` 为 `"system"` 或插件 id |
| `tos.settings.get(scope)` | `system.settings` | 否 | 取某范围的字段描述与当前值：`{name, fields=[{key,type,label,…,value,default,overridden}]}` |
| `tos.settings.set(scope, key, value)` | `system.settings` | 否 | 校验并写入设备上的修改；成功立即生效，失败返回 `nil, err` |
| `tos.settings.setMany(scope, values)` | `system.settings` | 否 | 同一范围内原子校验并提交多个键；任一字段或跨字段约束失败则全不写，用于地点选择等复合输入 |
| `tos.settings.reset(scope, key?)` | `system.settings` | 否 | 清除设备上的修改，回到 Server 下发值或默认值；省略 `key` 即整个范围 |
| `tos.store.get(key)` / `set(key, v)` | — | 否 | 持久化 KV，每插件 256KiB；立即更新内存，最后一次修改 2s 后合并原子落盘，停止时提交。进程突然退出可能丢失尚未提交的修改 |
| `tos.timer.after(s, fn)` / `every(s, fn)` / `cancel(id)` | — | 否 | 定时器；`every(60, fn, {align=true})` 对齐到整分钟 |
| `tos.timer.sleep(s)` | — | 是 | 等待 0.001–3600 秒，不阻塞共享插件线程 |
| `tos.http.get(url, opts)` / `post(url, body, opts)` | `network` | 是 | 返回 `res`（`status`、`headers`、`body`）或 `nil, err`；连接/读超时各 3s，请求/响应 ≤1MiB；`opts.headers` 设置请求头 |
| `tos.json.decode(s)` / `encode(t)` | — | 否 | C 侧 yyjson；深度 ≤24、文本 ≤1MiB；JSON null 为 `tos.json.null`，保留解码所得空数组/空对象类型及 int64；循环表与非 JSON 值报错 |
| `tos.time.now()` | — | 否 | 可信时间（毫秒）；时间未校准时返回 `nil` |
| `tos.time.format(ms, fmt, tz?)` | — | 否 | 命名格式遵从系统语言与 12/24 小时制；可选 IANA 时区，省略取系统时区；`ms=nil` 显示占位 |
| `tos.tz.home()` / `tos.tz.info(id)` | — | 否 | 自带 IANA 规则的时区 ID、偏移、DST、代表城市与坐标 |
| `tos.locale()` / `tos.i18n.t(key, params?)` | — | 否 | 系统 locale / 脚本 / RTL / 12或24小时信息；插件字符串与占位符替换 |
| `tos.location.get()` | `location` | 否 | 系统配置位置 `{name,latitude,longitude,elevation,timezone,source}`；来源明确区分时区推定和手动输入，见 ADR-021 |
| `tos.astro.sun(ms, place?)` / `tos.astro.moon(ms, place?)` | `location` | 是 | 独立计算线程返回本地天文数据，见 §6.5；省略地点取系统位置。取消/移除插件时不投递迟到结果 |
| `tos.astro.seasons(year)` | — | 否 | 当年二分二至的 UTC 毫秒时间戳，支持 1950–2050 |
| `tos.page.focus(page, {live=bool})` / `release(page)` | — | 否 | 请求切到本插件页面，由 DeckRouter 仲裁；`live` 需页面声明 `live` |
| `tos.alert{…}`（待实现） | `alert` | — | 本轮仅测试 debug 模拟通知界面，未接插件 API |
| `tos.media.*` / `tos.library.list(opts)`（待接线） | `media` / `library` | — | 队列与媒体库核心已有代码；宿主接线和实际播放按用户要求暂缓 |
| `tos.chime(name)`（计划） | `alert` | — | 内置提示音，尚未实现 |
| `tos.server.call(name, args)`（M2） | `server` | 是 | 可选 Server 服务，尚未实现 |
| `tos.server.subscribe(topic, fn)`（M2） | `server` | — | 可选 Server 推送，尚未实现 |
| `tos.log.info/warn/error(...)` | — | 否 | 日志，带插件 id 前缀 |

### 6.3 权限

| 权限 | 授予的能力 |
|---|---|
| `network` | `tos.http`、`sources.http` |
| `alert` | 预留通知与提示音权限，当前宿主 API 尚未接入 |
| `media` | OS 音视频播放及媒体会话控制（原 `audio` 权限名已移除） |
| `library` | 本地媒体目录与文件元数据枚举（原 `photos` 权限名已移除） |
| `location` | `tos.location.get`、`tos.astro.sun/moon`、位置设置变化时的 `location` 事件 |
| `presence` | `presence`、`gesture` 事件 |
| `server` | `tos.server.*` |
| `system.settings` | `tos.settings.*`；可声明 `settings` 角色页面。**系统权限**：只授予 APK 内置的出厂版本，Git 来源的包声明它会被 Server 和设备双方拒绝（§12.6） |

### 6.4 预算

每次事件执行或异步恢复 ≤50ms 线程 CPU、≤200ms 墙钟、≤500 万条 Lua 指令；异步等待不占执行预算，下次恢复重新计时。每插件内存 ≤4MiB、总计 ≤24MiB，包含 Lua 与 JSON 临时分配。资源错误不能通过 `pcall/xpcall` 吞掉。

普通事件脚本错误只记录日志/诊断，保留实例与状态。一小时累计 5 次普通错误，或一次 CPU/墙钟/内存超限，才重启插件；按 2/4/8/16s 退避。一小时内触发 5 次重启则冷却一小时，自动再试。Lua 状态表与 KV 保留，Lua 局部变量会在重启时重建。新版本启动失败时继续运行旧版本。原生执行发生不洁退出会按插件包摘要累计，三次后隔离一小时，避免反复加载同一故障包。

每插件最多 64 个定时器、8 个挂起任务；HTTP 全局最多 32 个挂起任务。HTTP 明文仅允许私网、链路本地及回环地址，校验所有 DNS 结果并固定连接 IP；每次重定向重新检查，最多 3 跳，禁止 HTTPS 降级。HTTPS 使用系统证书校验；未校时可能失败。Android 网络安全 XML 不能表达 CIDR，私网限制由公共 HTTP 客户端执行，后续音频/同步客户端也必须复用该策略。

### 6.5 天文数据（ADR-021）

`place` 可包含 `latitude`、`longitude`、`elevation`（米，可省略为0）、`timezone`（IANA ID，可省略为系统时区）。数值必须有限；纬度 −90..90、经度 −180..180、海拔 −500..10000。显式非法时区报错。所有事件时间均为 UTC epoch 毫秒；事件不存在时为 `nil`，不要用午夜代替。日期早于 2020 时返回 `nil`，避免未授时设备展示错误天文；晚于 2050 报错。

- `sun`：`start/end` 当地日边界、`noon`、`sunrise/sunset`、`dayLength/dayLengthChange`（毫秒）、`maximumAltitude`（度）、`polar`（`normal/midnight_sun/polar_night/transition`）、`civil/nautical/astronomical={dawn,dusk}`、`golden/blue=[{start,end}]`、当前 `azimuth/altitude` 与 `timezone`。日出日落属于中天前后各12小时的太阳窗口，可能跨民用午夜；日长不随 DST 人为增减。黄金时段 −4°..+6°，蓝调 −8°..−4°，区间在太阳窗口边界裁切。
- `moon`：当地日 `start/end`、首个 `rise/set/transit` 及完整 `rises/sets/transits` 列表、`alwaysUp/alwaysDown`、当前站心 `azimuth/altitude`、`timezone` 和 `phase`。`phase` 有 `name`（8个语言无关 ID）、`angle`、`illumination`（0..1）、`waxing`、`ageDays`、`previousNewMoon/nextNewMoon/nextFirstQuarter/nextFullMoon/nextLastQuarter`。
- `seasons`：`{year,marchEquinox,juneSolstice,septemberEquinox,decemberSolstice}`，名称不预设南北半球季节。

太阳使用截断 VSOP87D，月球使用 Schlyter 摄动模型，季节使用 Meeus 第27章；ΔT固定69.2秒，适用于2020年代的分钟级展示。位置搜索服务仅辅助配置；断网或无 Server 时全部天文计算正常。精度和实测见后续验证记录。

## 7. 完整示例：天气插件

`main.lua`：

```lua
local cfg = tos.config

local ICONS = { [0] = "sun", [1] = "sun-cloud", [2] = "cloud", [3] = "cloud",
                [61] = "rain", [63] = "rain", [71] = "snow" }

local function refresh()
  local url = string.format(
    "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
    .. "&current=temperature_2m,relative_humidity_2m,weather_code", cfg.lat, cfg.lon)
  local res, err = tos.http.get(url)           -- 挂起，不阻塞
  if not res or res.status ~= 200 then
    tos.log.warn("weather fetch failed", err or res.status)
    return                                     -- 保留上次的数据
  end
  local cur = tos.json.decode(res.body).current
  local temp = cur.temperature_2m
  if cfg.unit == "f" then temp = temp * 9 / 5 + 32 end
  tos.state.set{
    temp      = temp,
    humidity  = cur.relative_humidity_2m,
    icon      = ICONS[cur.weather_code] or "cloud",
    desc      = cfg.city,
    updated   = tos.time.now(),
    relevance = 0.3,
  }
  tos.store.set("last", tos.state.get(""))     -- 离线重启后先显示上次的数据
end

tos.on("start", function()
  local last = tos.store.get("last")
  if last then tos.state.set(last) end
  refresh()
  tos.timer.every(600, refresh)
end)

-- 用户在设置页改了城市 / 经纬度 / 单位：立即用新值刷新
tos.on("config", function(c)
  cfg = c
  refresh()
end)

tos.on("command", function(name)
  if name == "refresh" then refresh() end
end)
```

配合 §3 的 manifest 和 §4.3 的 `views/today.json` 即为一个完整插件。Server 关机后它继续在设备上每 10 分钟直接请求天气 API；长按天气页或天气卡片就能改城市和单位，**这个设置页插件作者一行 UI 代码都没写**。

## 8. 页面编排配置

页面顺序、角色、首页两个栈放什么，写在 Server 的 `config.toml`，随期望状态下发（APK 内置一份默认值，Server 不在时使用）。这部分是**部署配置**而不是设备设置项：设备上的设置页不编辑它。

```toml
[device.default.deck]
pages = ["home.main", "gallery.slideshow", "clock.face", "music.nowplaying", "weather.detail"]

[device.default.deck.roles]
home    = "home.main"
ambient = "gallery.slideshow"
sleep   = "clock.face"

[device.default.deck.home]       # home.main 的两个卡片栈（横屏左/右，竖屏上/下）
left  = ["clock.now", "weather.today"]
right = ["music.mini", "gallery.preview"]

[device.default.plugins.weather] # 页面级设置的下发值（§11.2 的“Server 下发”层）
city = "Hong Kong"
lat  = 22.3
lon  = 114.2
```

新插件安装后若未出现在配置中，其页面追加到 deck 末尾、卡片追加到右栈末尾。需要安装哪些插件、从哪个仓库装，另在 `config.toml` 的 `[[plugins]]` 里声明，见 §12.3。

## 9. 开发与调试

- **最快的路径（无需 Server）**：`tools/plugin-push.sh plugins/<id>` 把目录推到 debug 设备的开发目录，设备热加载；设备本身就是预览器。（目录形态，命令不变。）
- **走 Git**：把插件放进任意 Git 仓库，打 tag（或手动导入时跟踪分支），在 Server 上 `plugin add`（§12.3），设备自动更新
- Server 取回时检查：manifest 与视图 schema、文件白名单与大小限制、权限声明、`api` 版本、id 冲突；Lua 语法由设备加载时检查
- 设备日志：`adb logcat -s ThinkOS:V` 里过滤 `[plugin:<id>]`
- 维护页（原生救援菜单 / 设置页的诊断）可以查看每个插件的状态、内存、最近错误，并手动重启或禁用
- 给 AI 的上下文：本文、`shared/schema/plugin/*.json`、一个同类自带插件；让它按 manifest → views → main.lua 的顺序写，并先跑 `python3 tools/package_plugins.py --validate-only`

## 10. 自带插件

| 插件 | 页面 | 卡片 | 脚本 | 主要能力 |
|---|---|---|---|---|
| `home` | `main`（`stacks`，角色 home） | — | 无（纯声明） | — |
| `clock` | `face`（`focus` + 活组件 clock，变体 数字/指针/日光/世界，角色 sleep） | `now` / `calendar` | 有（日期、时区、日照数据） | `location`；设置为显示秒与世界城市，12/24小时跟随系统 |
| `settings` | `form`（`form`，角色 settings，不在翻页序列里） | — | 有 | `system.settings`（系统插件，固定安装，见 §11.3） |
| `weather`（待实现） | 目标：详情页 | 目标：天气卡片 | 待实现 | `network` / `location` |
| `gallery`（待实现） | 目标：相册轮播 | 目标：预览卡片 | 待实现 | `library` |
| `music`（待实现） | 目标：正在播放 | 目标：迷你卡片 | 待实现 | `media` / `library` / `network` |
| `notify`（仅 UI 预览） | debug 模拟预览 | — | 未接收或轮询消息 | HTTP 监听按用户要求暂缓 |

自带插件和第三方插件走**完全相同**的接口（`settings` 只是多了一个系统权限）。如果某个自带功能用这套接口做不出来，说明运行时缺能力，应该补运行时，而不是给它开后门。

## 11. 设置（ADR-017）

> 状态：设计已定，M0.5 实现。

设置分两级，**用同一种声明格式**：

| 级别 | 谁声明 | `scope` | 例子 |
|---|---|---|---|
| 系统级 | OS 自己：`shared/schema/settings/system.json`，清单见 `04-ui-spec.md` §9 | `system` | 主题模式、夜间模式、方向锁定、亮度上限、变暗时间、Server 地址 |
| 页面级 | 插件 manifest 的 `settings` | 插件 id | 天气城市、时钟 24 小时制、相册的轮播间隔 |

页面级设置**以插件为单位**：长按某个页面，打开的是它所属插件的设置；长按首页栈里的某张卡片，打开的是该卡片所属插件的设置——所谓“组件级设置”就是所属插件的设置。首页两个栈里放什么、页面的先后顺序不是设置项，属于部署配置（§8）。页面的**变体**（上下滑切换的表盘、相簿等）也不是设置项，保持现有的 DeckRouter 记录方式。

### 11.1 声明格式

```json
"settings": {
  "hour24":   { "type": "bool",   "label": "24 小时制", "default": true },
  "interval": { "type": "number", "label": "切换间隔", "default": 20, "min": 5, "max": 120, "step": 5, "unit": "秒" },
  "effect":   { "type": "choice", "label": "切换效果", "default": "fade",
                "choices": [ { "value": "fade", "label": "淡入淡出" }, { "value": "slide", "label": "滑动" } ] },
  "city":     { "type": "text",   "label": "城市", "default": "Hong Kong" }
}
```

| `type` | 专有字段 | 设备上的控件 |
|---|---|---|
| `bool` | — | 开关 |
| `choice` | `choices`：`[{value,label}]`（用数组而不是对象，因为 JSON 对象没有顺序） | 单选列表 |
| `number` | `min`、`max`、`step`、`unit?` | 滑块 + 步进按钮 |
| `text` | `maxLength?`（≤ 1024）、`secret?`（设备上显示为 ••••，不进日志、不随回报上传） | 显示当前值；点击编辑（见 §11.3） |

公共字段：`label`（必填，先用单一语种字符串）、`default`（必填，类型必须与 `type` 匹配）、`help?`（一行说明）、`group?`（分组标题）。

- key：`[a-z][a-z0-9_]*`；每个插件最多 32 项。系统级 key 用点号表示分组，对应 `config` 里的嵌套对象（如 `theme.mode` ↔ `{"theme":{"mode":…}}`，与 `05-protocol.md` §3.2 一致）
- 值不合法（超出范围、不在 `choices` 里、类型不符）一律拒绝写入并给出原因；系统级允许跨字段约束（如 `theme.night_below_lux < theme.night_above_lux`）
- 插件升级后：新增的 key 取默认值；已删除的 key 的值保留不用；校验不再通过的值回落到默认值。卸载插件时设置值保留，重装后恢复，只有“重置设置”才清除
- 视图里可用 `{{config.<key>}}` 绑定；脚本通过 `tos.config` 读取

### 11.2 取值与叠加

```
生效值 = 设备上的修改（overrides） ?? Server 下发值 ?? manifest / schema 默认值
```

- **设置值归 OS 所有，不归插件**：由设置存储（`SettingsStore`，`03-architecture.md` §3.18）统一校验、合并写盘。插件**只读**，值变化时收到 `config` 事件
- 设备上的修改保存在本地并回报给 Server（`05-protocol.md` §3.5）。冲突规则：**设备上的修改优先；只有 Server 对同一个 key 下发了与上次不同的值，才清除该 key 在设备上的旧修改**——也就是说 Server 一侧的显式改动可以覆盖设备上的旧修改，而 Server 的无关更新不会冲掉设备上的修改
- 设备上的修改可以在设置页逐项“重置”，也可以由救援菜单整体“重置设置”
- 把设备上的修改写回 Server 配置的操作，留到 M2 的 Server 管理界面再设计；M0.5 只做到“回报并可见”
- `secret` 字段不进回报；`server.token` 是唯一必须在设备本地写入的系统设置（开发期经 adb 写入 `settings/local.json`；V1 改为配对码，见 `05-protocol.md` §2）

### 11.3 设置界面是插件

内置系统插件 `settings`（与其它插件同一套接口，多一个系统权限 `system.settings`）：

- 页面 `form`：版式 `form`，角色 `settings`，**不在左右翻页序列里**。`form` 版式由 OS 提供原生控件（开关、单选列表、滑块/步进、文本行），负责分组、滚动、触控尺寸和动画——表单**根据 schema 自动生成**，新插件声明的设置项会自动出现，设置插件不需要认识每一个选项
- 流程：进入页面时 `page.enter` 带 `params={scope=…}` → 插件用 `tos.settings.get(scope)` 取字段描述与当前值，放进状态里的 `fields` → 用户操作控件，设备触发 `form.change(key, value)` → 插件调用 `tos.settings.set(scope, key, value)`，成功后刷新 `fields`，失败则把错误放进状态让控件回弹并提示。开关和单选立即提交，滑块在松手时提交
- 视图草图：

```json
{
  "layout": "form",
  "slots": {
    "title":  { "type": "text", "value": "{{title}}", "style": "title" },
    "fields": { "type": "form", "items": "{{fields}}" }
  }
}
```

槽位名以 `layouts.json` 为准，实现时登记。`form` 属于新增版式，须在 api 1 发布前合入词汇表；之后新增词汇必须提升 `api`。

- **入口**：长按任意页面（单指按住约 0.6 秒、移动小于拖动阈值）→ DeckRouter 打开 `settings` 角色页面，`params.scope` 为被长按元素所属插件的 id；该插件没有设置项时直接打开系统设置。设置页底部有“系统设置 / 其它插件”入口
- **退出**：点“完成”、30 秒无操作、或按 Home 键 → 回到打开前的页面和变体。设置页打开期间 DeckRouter 暂停按注意力档位切换角色页面
- **文本输入**：设备上不方便打字。`text` 项在设备上先作为只读行显示，点击后是否能直接输入取决于设备有没有可用的输入法（`02-device.md` 未记录，待实测），设计上不依赖它；长文本（Navidrome 地址、账号）建议在 Server 上填，由 Server 用同一份 schema 生成表单并下发
- **回退**：`settings` 插件被禁用、崩溃或处于安全模式时，长按自动改为打开**原生救援菜单**。救援菜单即现有 `MainActivity` 维护对话框，也是唯一保留的原生设置界面，条目：退出安全模式、恢复出厂插件、重置设置、打开系统设置、查看诊断；隐藏的“左上区域双指长按 5 秒”入口保持不变。修插件的工具不能依赖插件
- **系统插件**：`settings` 固定安装、不能卸载；因为它持有系统权限，现阶段只接受 APK 内置版本，更新随 APK 走

## 12. 通过 Git 仓库安装（ADR-018）

> 状态：设计已定，M2 实现（`registry` 模块与 `plugin` 命令）。设备端目录化安装器在 M0.5 提前完成。

### 12.1 一个插件 = 仓库里的一个目录

- 默认在仓库根目录放 `manifest.json`。一个仓库里也可以放多个插件，安装时指定子目录 `path`；导入时 Server 会扫描仓库树（深度 ≤ 3）找出所有 `manifest.json` 供选择。本仓库的 `plugins/clock` 就是这种例子
- 仓库里 §2 白名单之外的文件被忽略，不下发，不算错误
- 设备上不装 Git，也不访问 Git；设备只从 Server 拿文件

### 12.2 版本 = git tag

- tag 用 SemVer：`v1.2.0` 或 `1.2.0`。manifest 的 `version` 必须等于 tag 去掉前缀 `v` 后的值，不一致则拒绝（避免把错版本装上去；跟踪分支时不做此检查，设备按 commit / 摘要区分版本）。含预发布后缀（`-beta.1`）的 tag 默认不选
- 安装时 Server 把 tag 解析为 **commit SHA** 并写入锁文件。tag 可以被强制推送改掉，commit 不会，所以同一个版本永远是同一份代码。之后检查更新时若发现 tag 指向了别的 commit，**不自动更新**，只提示，需要用户确认
- 不指定版本时取满足 `api` 兼容的最新稳定 tag

### 12.3 在 Server 上安装与管理

命令形式暂定，Server 管理界面（V1）提供同样的操作：

```bash
thinkos-server plugin search weather                  # 按 topic 搜索
thinkos-server plugin add https://github.com/alice/thinkos-weather              # 最新稳定 tag
thinkos-server plugin add <url> --tag v1.2.0 [--path plugins/weather]
thinkos-server plugin add <url> --branch main --track # 跟踪分支，仅手动导入
thinkos-server plugin list | info <id> | update [<id>] | remove <id>
```

用户意图写在 `config.toml`，取回的结果（commit、摘要、已确认的权限）写在 `server/data/plugins.lock.json`：

```toml
[[plugins]]
id  = "weather"
url = "https://github.com/alice/thinkos-weather"
tag = "v1.2.0"
# path = "plugins/weather"       # 仓库内子目录，默认根
# auto_update = true             # 仅当新版本不增加权限时自动升级

[[plugins]]
id     = "my-page"
url    = "https://git.example.com/me/my-page"
branch = "main"                  # 跟踪最新提交，界面上标为“开发版”
track  = true

[[plugins]]
id  = "clock"
dir = "plugins/clock"            # 本地目录，保留原来的“放进目录即下发”
```

`url` 与 `dir` 二选一；同一个 `id` 出现在两个来源里则拒绝安装。移除条目 → 期望状态不再列出它 → 设备卸载（APK 内置插件回退到出厂版本）。

### 12.4 取回与校验（Server 侧）

1. `git ls-remote` 解析 tag / 分支到 commit
2. 浅克隆到 `server/data/git/`（bare、`--depth 1`、不检出、不递归子模块、禁用 LFS smudge），设超时与总大小上限
3. 用 `git ls-tree -r -z -l <commit> -- <path>` 读目录树，再用 `git cat-file --batch` 读文件内容。**不检出工作区**：这样不会触发过滤器、换行转换，也不会受 macOS 大小写不敏感文件系统影响
4. 拒绝：符号链接（`120000`）、子模块（`160000`）、非法路径（绝对路径、`..`、`.git`）、仅大小写不同的路径、超出 §2 限制的文件
5. 文件按 sha256 存入 blob 库（`/api/v1/blobs/{sha256}`），用 `shared/schema/plugin/` 校验 manifest 与视图（与设备同一份 schema），检查 `api`、id 冲突和权限
6. 环境加固：`GIT_TERMINAL_PROMPT=0`、`GIT_ALLOW_PROTOCOL=https:http:ssh`（禁止 `file`、`ext` 等）、`core.hooksPath` 指向空目录

私有仓库沿用 Mac 上已配置的 git 凭据（钥匙串 / ssh-agent）和代理；Server 只调用系统 `git`，不保存任何仓库凭据。

### 12.5 发现：topic `thinkos-plugin`

- 约定仓库 topic 为 `thinkos-plugin`（GitHub 的 Topics，不是 git tag）。Server 的 `plugin search` 按 topic 调用各平台的仓库搜索接口：GitHub `topic:thinkos-plugin`、GitLab `topic=` 参数、Gitea / Forgejo（含 Codeberg）的 `topic` 搜索。具体接口以各平台当前文档为准，GitHub 的搜索令牌（可选，用来放宽速率限制）放在 `config.toml` 的 `[registry]`
- 结果显示名称、简介、星数和最新 tag，仅作线索；能不能装以 §12.4 的校验为准
- 其它平台或私有仓库：直接粘贴 URL 手动导入。手动导入才可以选择跟踪分支

### 12.6 权限确认与更新

- 安装前列出插件申请的权限（`network`、`audio` 等），用户确认后才写入锁文件并下发；更新时如果权限比已确认的多，需要再确认一次，未确认前不下发新版本
- Server 每天用 `git ls-remote --tags` 检查新 tag，有新版就提示；`auto_update = true` 且权限未增加的插件自动升级。跟踪分支的插件每 5 分钟检查一次分支头，push 之后几分钟内设备就会更新——权限变化仍需确认，但代码变化不需要，**只跟踪你自己信任的仓库**
- **系统权限**（`system.settings`）永远不下发给 Git 来源的插件；声明了它的包 Server 拒绝，设备也拒绝加载。同理，Git 来源的包不能顶替 `settings` 这类系统插件的 id

### 12.7 安全边界

- 从网上装的是任意代码，但它只能在设备的 Lua 沙箱里运行，只能使用 manifest 声明且用户确认过的权限（`06-plugins.md` §6）
- Server 只取回和校验，**不执行**仓库里的任何东西
- 版本固定在 commit 上；下发的文件由期望状态里的 sha256 保证完整性
- 后续可选：用 `git verify-tag` 校验签名 tag（V1），取代原先计划的插件包签名
