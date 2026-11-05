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
| **widget（卡片）** | 放进首页小组件栈的卡片 | `weather.now`、`music.mini` |
| **alert（通知）** | 叠层通知，可唤醒屏幕 | `notify` 插件转发的手机通知 |
| **command（指令）** | 可被点击、手势、语音、远程触发的动作 | `music.next`（“下一首”） |
| **settings（设置项）** | 在 manifest 里声明，OS 自动生成设置页 | `weather` 的温度单位、`clock` 的秒数显示 |
| **export（公开数据）** | 显式公开的一段状态，其他插件声明后可读（§6.8） | `gallery.daily`（Bing 每日图）、`weather.now` |

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

APK 根索引 `assets/plugins/index.json` 为 `{plugins:[上述完整索引…]}`；插件文件位于 `assets/plugins/<id>/…`。运行时先验证并拷贝到私有暂存目录，再做 manifest / view 语义校验，通过后发布到以完整 digest 命名的版本目录。失败候选不覆盖正在运行的版本；启动错误、预算终止与原生退出隔离延用相同回滚逻辑。版本目录名含完整 digest，且只能由已校验的暂存目录原子改名产生，所以之后每次冷启动遇到已发布的同 digest 目录（`index.json` 与 APK 里的索引一致）时不再重复拷贝和哈希（APK 内的原件由 Android 验签），只重新做 manifest / view 语义校验；目录缺失或索引不符才走上述完整安装。这一步原本占内置插件冷启动约 1.2 秒（8 个插件，逐文件写入闪存）。

## 3. manifest.json

```json
{
  "id": "weather", "name": "@name", "version": "0.1.0", "api": 2,
  "main": "main.lua", "permissions": ["network", "location"],
  "settings": {
    "interval": { "type": "choice", "label": "@setting.interval", "default": "15",
      "choices": [{ "value": "10", "label": "10 min" }, { "value": "15", "label": "15 min" },
                  { "value": "30", "label": "30 min" }, { "value": "60", "label": "60 min" }] }
  },
  "pages": [{ "id": "detail", "view": "views/detail.json", "title": "@title" }],
  "widgets": [{ "id": "now", "view": "views/now.json", "opens": "detail" }],
  "commands": [{ "id": "refresh" }]
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 全局唯一，小写字母数字与 `-`；也是页面/卡片/指令的命名空间 |
| `name` / `version` | 是 | 显示名 / 语义化版本。用 Git 分发时，`version` 必须等于 git tag 去掉前缀 `v` 后的值（§12.2） |
| `api` | 是 | 需要的运行时 API 版本（当前支持 `1`–`9`）；高于设备支持的版本时不会被安装 |
| `icon` | 否 | 包内相对路径 |
| `main` | 否 | 入口 Lua 文件；省略即纯声明插件 |
| `permissions` | 否 | 见 §6.3；未声明即拒绝 |
| `settings` | 否 | 本插件的设置项（页面级设置），格式见 §11.1；取代 M0 的 `config` |
| `pages[]` | 否 | `id`、`view`、`title`、建议的 `roles`（`home`/`ambient`/`sleep`；`settings` 仅限持有 `system.settings` 权限的插件）、API9 `launcher` 要实际授予的 `system.apps`（仅出厂副本）；`panel` 要 `system.device`，焦点 `priority`、`variants`、`live`（可作为实时活动全屏接管） |
| `widgets[]` | 否 | `id`、`view`、`opens`（点击打开本插件的哪个页面）；API 6 可选 `alternates`（按模板提供其他呈现，§6.8.5） |
| `apps[]` | 否 | API 8：应用（ADR-040，§6.11）。`id`、`page`（本插件的一个页面，**不能带 `roles`**）、`title`、可选 `icon`、`listed`（默认 true；false 表示它是应用但不进应用列表）。最多 8 个 |
| `commands[]` | 否 | `id`；可选 `phrases`（语音固定短语）、`gesture`（`wave`/`left`/`right`/`fist`，冲突以配置为准）；API 6 可选 `public`、`cooldown`（§6.8.4） |
| `exports` / `uses` / `calls` | 否 | API 6：公开的数据、读取的公开数据、调用的公开指令，见 §6.8 |
| `sources` | 否 | 纯声明插件的数据来源，见 §5 |

页面、卡片、指令的全名都是 `<plugin>.<id>`，例如 `weather.now`、`music.next`。

## 4. 声明式视图

视图只描述**内容与语义**，不描述像素或自定义动画；ADR-025 仅开放 tile 磁贴底色 color，其它颜色仍由 OS 决定。排版、横竖屏、主题、红移、动画都由设备完成。

### 4.1 渲染词汇

| 层 | 可选值 |
|---|---|
| 页面版式 `layout` | API 1：`stacks` / `focus` / 设置专用 `form`（§11.3）；API 2：`split` / `stage`；API 4：`tiles`；API 5：`aero`；API 6：`focus` / `split` / `stage` 的槽位可放 `{"widget":"<plugin>.<id>"}` |
| 卡片模板 `template` | API 1：`glance`；API 4：`tile`；M1 媒体与列表模板待实现 |
| 组件 `type` | API 1：`text` / `icon` / `clock`，`form` 只用于设置版式；API 2：`progress` / `chart` / `list` / `daylight` / `moon`；API 3：`image` / `carousel`；API 7：`video` / `controls` / `seekbar`（需 `media` 权限，§6.10）；API 8：`nowplaying`（需 `media` 权限）/ `tabs`，`list` 可带 `current`（§4.1.2） |
| `clock.style` | `digital` / `analog` / `solar` / `world` / `float` |
| `text.style` | `display` / `title` / `body` / `caption` |

各版式、模板有哪些槽位以 `shared/schema/plugin/` 为准。需要新的版式或组件属于「设备端功能迭代」：先在 APK 实现、提升 `api` 版本，插件才能使用。

设备同时接受 API 1–10。API 10 增加 `system.update` 与更新状态/操作/原生恢复页入口，不增加渲染组件。API 9 增加设置的 `warn` / `placeholder` 和表单 link 的确认文本；旧 `help` 保持兼容。使用 API 2 词汇、组件组、`tap` 或 `weight` 的插件至少声明 `"api": 2`；图片组件至少声明 `"api": 3`；安装器与打包器都检查，不会让 API 1 插件静默使用新版词汇。版本表来自 `layouts.json` 的 `x-thinkos-api`，并生成到 Kotlin / Go。

API 2 的固定组件与内容字段：

| 组件 | 内容字段 |
|---|---|
| `progress` | `value` / `max`（默认 1，可绑定）；`style` 为 `bar` / `ring`，非法范围显示空轨道 |
| `chart` | `values` 为数值数组或 `"{{路径}}"`，最多 168 点；`style` 为 `line` / `bar` / `area`；`min` / `max` / `highlight` 可绑定，高亮索引从 1 开始；缺值保留间隔，不连线跨过缺值 |
| `list` | `items: "{{数组路径}}"`，`item`含icon/title/subtitle/trailing/progress/max；原生列表复用行并滚动 |
| `daylight` | `path` 指向 `tos.astro.sun()` 的派生状态；复用日光表盘，不自行读取位置 |
| `moon` | `path` 指向 `tos.astro.moon()` 或其 `phase`；显示月相及照亮比例；`hemisphere` 为 `north/south` 或状态绑定，南半球水平镜像 |

API8的`grid`契约现有原生渲染器：`items`指向状态数组，`item`只有`icon`、`image`、`title`，`tap`给出事件名和从1开始的单元序号。OS按可用宽度每96dp分一列，至少3列；图标56dp，单行名称14sp、省略、RTL镜像，单元复用并滚动。图片通过所属插件的正常图片权限加载，`app:<包名>`需要实际授予的`system.apps`。不开放列数、字号、间距等外观字段。尚未发布的API9 list行`image/group`已撤回，所有API均拒绝这两个list字段（ADR-045）。

用户后续已授权相册与通用图片接口，并明确要求微软每日一图。API 3 开放 `image` / `carousel` 与 `tos.library.list`，支持本地、包内和 HTTP(S) 图片；gallery 默认设备直连 Bing，不依赖 Server。`video` / `controls` / `seekbar` 与 `tos.media.*` 在 API 7 开放（§6.10，ADR-030）；`live` 页面（实时活动）不在横向翻页序列里，只能由插件接管进入，左右滑动回到接管前的页面。

除点击由 OS 保留的 `carousel` 外，组件可带固定事件名 `tap`；所有组件可带数值 `weight`（0…100）。点击发送 `tos.on("tap", function(name, index) ... end)`，其中 `name` 是声明值；列表行还给出从 1 开始的 `index`。OS 保留横向翻页手势，列表区域的纵向拖动用于滚动。`daylight/moon` 只消费派生数据，绘制本身不要求 `location` 权限。

`weight` 只分配 OS 组件组剩余高度：文本、图标、进度默认按内容高度；时钟、表单、图表、列表、天文组件默认共享剩余高度。显式 0 使用内容高度。`focus` 的槽位以及新版式槽位可放单个组件或 `{"children":[...]}` 组件组，不支持任意嵌套。版式固定：`split` 的 `lead/main` 横屏左右约 40/60、竖屏上下约 40/60；`stage` 的 `stage` 全出血，`over/under` 覆盖其上。插件不能设置像素、CSS 或自定义动画；ADR-025 仅允许 tile 底色 color。

当前 `icon.name` 精确支持 `sun`、`moon`、`clock`、`calendar`、`unknown`，以及天气词汇 `clear-day` / `clear-night` / `partly-day` / `partly-night` / `cloud` / `fog` / `rain` / `shower` / `snow` / `thunder`；未知名称显示明确的占位符，不按子串猜测。布局槽位及 stack/header/content/footer 角色从同一份 schema 生成。

`clock.sun` 为状态路径，例如 `"sun": "sun"`，读取插件通过 `tos.astro.sun()` 取得的派生数据。solar 表盘以真实太阳中天为圆环顶部，用真实日出日落和三层曙暮光标出白昼区间；无有效天文数据时只显示空环与时间，不推测日出日落。OS 绘图只接收派生事件，不把位置反向暴露给插件。

### 4.1.0a API 5：Aero 桌面

`{"layout":"aero","slots":{"windows":[{"widget":"clock.desktop"},{"widget":"weather.now"},{"widget":"gallery.preview"}]}}`。1–3 扇窗口，共用普通跨插件卡片引用；窗口标题取所属插件的 name 文案。横屏并排错落，竖屏上方主窗、下方副窗；开始菜单与任务栏打开卡片的 opens 和系统设置。所有外观归 OS，无 CSS/颜色/像素/模糊接口。Aero 作为普通页面加入 deck，不替换现有角色。API 1–4 不得使用此版式。见 ADR-026 与 `25-windows-design.md`。

### 4.1.0 API 4：磁贴

`{"layout":"tiles","slots":{"tiles":[{"widget":"clock.tile","size":"wide","color":"#D24726"}]}}`。1–16 项，size 必填：small 1×1、medium 2×2、wide 4×2、large 4×4。横屏 8×6、竖屏 6×8（ADR-028），按声明顺序首次适配，RTL 镜像，不滚动；放不下丢弃并 warn（例如两张 large 横屏并排，竖屏上下叠放，第三张 large 在两个方向都放不下；`large, large, wide, wide` 横屏全部放得下，竖屏两张 wide 放不下）。打包器对两个方向都放不下的引用给警告。网格贴长边远端，余下一栏由 OS 画页面标题（取页面 manifest 的 `title`，可用 `@key`），背景由系统设置 `tiles.background` 决定，插件都不能改。内置的 `tiles.main`（标题“开始”）在两个方向都正好填满 48 格。

`{"template":"tile","color":"{{config.color}}","faces":[{"icon":"clock","value":"{{time}}","label":"@name","detail":"{{date}}"}],"badge":"{{count}}"}`。faces 1–4 面，label 必填；value/detail 都空的面跳过，全空时显示第一面的 label。color 可为 #RRGGBB 或 {{绑定}}，引用颜色在页面状态中求值，卡片颜色在所属插件状态中求值。引用 > 卡片 > 系统 accent；非法运行时值回退并 warn。OS 自动取黑白前景色，插件不能指定。可绑定配置项给用户选颜色（ADR-025）。

API 6 起，face 可带自己的 `content`（同样只接受 clock/progress/chart/daylight/moon，仅 wide/large 显示）；没有时沿用磁贴级 `content`。带 content 的面即使没有 value/detail 也参与翻面。例如时钟磁贴第一面是时间和日期，第二面是白色表盘：`{"faces":[{"value":"{{tileTime}}","label":"@name","detail":"{{date}}"},{"label":"@name","content":{"type":"clock","style":"analog"}}]}`。API 6 之前使用 `faces[].content` 会被拒绝。

API 6 可选 `image`（完整的 `image` 组件声明）：`src` 非空时图片铺满磁贴，叠底部暗角，隐藏 icon 和 content，文字固定为白色；`value` 显示为两行标题，`detail` 最多两行（用于署名），都在名称上方。`src` 为空时回到普通磁贴。所以 `gallery.tile` 在没有每日图时显示图标。

small 只显示 icon（无 icon 时 value）与角标；medium 显示 icon/value/label；wide 左侧 icon/value/label，右侧 content 或 detail；large 上部 content，下部 icon/value/label/detail。content 仅接受 clock/progress/chart/daylight/moon（仅 wide/large 显示）。tile 在 stacks 中按 large 显示；glance 在 tiles 中为空占位。点击 opens，长按进入卡片所属插件设置。页面 active 且 AWAKE/ENGAGED 时逐张翻面，同一时间最多一张，受系统 tiles.live/tiles.interval 控制；减少动画直接换面。离线沿用本地状态。所有 value/detail 均为空时不翻面，首面的图标仍可作为静态入口；没有图标则回退 label。

新增图标 music/photo/bell/settings/home/folder/list/star/heart/message/play/battery。

API 8 再加 `wifi` / `wifi-off` / `volume` / `volume-off` / `brightness` / `tv` / `radio` / `cast` / `apps`，同时 `settings` 换成同一套里的齿轮。这些图标不是自己画的，取自 Iconfont 上的 Lucide（描边 2px、圆头端点，和 `04-ui-spec.md` 的图标规范一致），是矢量图，按显示的实际大小直接画；来源、许可证和增加图标的办法见 `assets/README.md`、`THIRD_PARTY_NOTICES.md`。天气图标（`clear-day` … `thunder`、`sun`、`moon`、`unknown`）同样换成这一套，名字不变；太阳摆动、云飘的动画保留，雨雪粒子的下落没有了（`20-weather-handoff.md`）。仍由代码绘制的只剩 `calendar` / `clock` / `music` / `photo` / `bell` / `home` / `folder` / `list` / `star` / `heart` / `message` / `play` / `battery` 和月相 `moon-phase-*`。

### 4.1.1 API 3：图片与轮播

这两个是通用 OS 组件；第三方插件和 `gallery` 使用同一接口。插件给出地址和文字，不接触图片字节、Bitmap、原生 View 或帧循环。

| 组件 | 字段 |
|---|---|
| `image` | `src`（可绑定字符串）；`fit: "pan" / "contain"`，默认 `pan`；`pan`（默认 true）；`interval`（单图或单程最短停留秒数，默认 30）；`pan_speed`（1–120 dp/s，默认 45）；`pan_seconds`（单程动画最短秒数，默认 12，0.1–120）；`pan_direction: random/forward/reverse`（可绑定，默认 random）；`tap` / `weight` |
| `carousel` | `items` 为状态数组路径，接受 `"photos"` 或 `"{{photos}}"`；必填 `interval`（秒）和 `duration`（转场毫秒）；同上 `fit/pan/pan_speed/pan_seconds/pan_direction`；`transition: fade/slide/zoom/random`，默认 fade；`shuffle`；`caption: auto/tap/off`，默认 auto；`caption_hold`（1–60 秒，默认 6）；`weight` |

`carousel` 数组最多 300 项，每项 `{src, id?, title?, credit?}`。`id` 缺省取 `src`，重复 id 只取第一项；`title/credit` 各最多 512 字符。更新或重排数组保留当前 id；删除当前项时切到首张。离页再进入在当前进程内记住位置。`credit` 是独立常驻的署名，`caption=off` 或标题淡出都不会隐藏它；首页预览也应单独声明署名文字。

照片叠层使用固定 OS 版式：`clock.style=float` 在 stage 的 over/header 中固定右上角，24dp 边距、数字右对齐，保留白字与窄黑边；不再在容器中间摆放或独立漂移。carousel 标题和完整署名合为底部文字组，边距 24dp、横屏最多 560dp，标题与署名相隔 8dp；独立的 under 状态提示按实际文字高度避让并对齐。没有新增插件对齐/像素样式选项。

`pan` **不永久裁掉任何画面**：保持比例、按一条边贴合窗口，从溢出轴一端移到另一端；水平平移按 RTL 反向。OS 根据速度上限延长停留，完整扫过后才自动换图。平移由 Android 原生属性动画连续插值，使用亚像素 translation 与正弦缓入缓出，跟随显示刷新；不再以待机 5fps 手动推进图片坐标。每张图默认随机选择正/反方向，forward 按阅读方向，reverse 交换两端。单程默认至少 12 秒，速度上限 45dp/s；若提前走完便停留到 interval，过宽的图延长动画和停留，确保完整扫过。单张图片在停留结束后平滑反向。动画自身不要求提高 OS 其它层的场景节拍；离页即取消。`fit=contain`、`pan=false` 或系统减少动画时，整张缩放居中，纯色留边；没有 Ken Burns 放大。`cover/smart` 不是当前 API 3 词汇。

上滑轮播查看清单下一项（Bing 清单按时间倒序，因此是更早的图片），下滑返回上一项；手动浏览在两端回弹，不跨端循环。图片随手指上下移动，时间与其它 stage 叠层固定。短拖动取消；按距离/速度提交后原生缓动归位。缺图先显示加载预览，松手时仍未下载完则保持当前跟手位置，解码完成后沿同一方向继续滑入；反向拖动/离页撤销旧请求，不接受迟到结果。手动浏览重置自动轮播停留时间；自动轮播仍循环。短促手势需达到系统 paging touch slop，速度不能单独把极小位移判成翻页；快速反向甩动可撤销尚未落定的切换。归位/回弹通过可中断的阻尼运动驱动，新触摸从当前实际位置接管；预览请求与清理回调按代际撤销，不强制跳到动画终点。等待图片解码时保持当前拖动位置，解码后继续同一方向；抬手用最终坐标，避免缺少 MOVE 的快滑误判成点击。过渡中的轻触只显示说明，不重复启动新的切换。点击轮播 = 下一张并显示字幕；`carousel` 不接受 `tap` 字段（该组件保留自身点击）。长按设置、左右翻页仍由 OS 接管。当前可见页面才启动图片任务；邻页预览不加载/计时，离页、ASLEEP 或 Activity 停止即取消任务、停止属性动画并清空位图引用。减少动画时不平移或缩放转场。

解码在线程 `ThinkOS-ImageDecode` 顺序进行：单文件 ≤8MiB，长边 ≤8192、≤32MP；先读边界后采样，ARGB_8888，完整处理 EXIF 的旋转/镜像。交付位图 ≤窗口像素的 1.5 倍、≤2MP、长边 ≤4096；只保留当前/转场中的两张，不另建全尺寸内存缓存。无效/未授权图片跳过；全部失败停在背景，可点击重试。

HTTP(S) 图片需要所属插件声明 `network` 权限。OS 使用通用 `MediaHttpClient`，2 个 ImageIO 线程负责下载；本地/缓存读取与解码不排在网络请求之后。响应必须成功、Content-Type 为 image、实际字节≤8MiB，并在提交缓存前通过图片边界检查。每次重定向校验地址，禁止 HTTPS 降级，公网只允许 HTTPS，明文 HTTP 仅私网。离页/停用插件时取消网络和解码，旧代际结果丢弃。

缓存位于应用内部 `cacheDir/images`，URL 的 SHA256 作键；最多 50MiB / 512 文件。下载写临时文件，校验后原子提交；同 URL 并发合并。按访问时间淘汰，显示或解码中的文件持有 lease，不会被清理；不缓存失败或过大的响应。缓存先读，命中后不联网；URL 应标识不可变内容，更新图片须提供新 URL；Android 仍可回收 cacheDir，所以离线只保证已下载且尚未被回收的照片。

```json
{"layout":"stage","slots":{"stage":{
  "type":"carousel","items":"photos","interval":"{{config.interval}}",
  "duration":"{{config.duration}}","fit":"pan","pan":"{{config.pan}}",
  "pan_speed":"{{config.pan_speed}}","pan_seconds":"{{config.pan_seconds}}",
  "pan_direction":"{{config.pan_direction}}","transition":"{{config.transition}}",
  "caption":"{{config.caption}}"
}}}
```

### 4.1.2 API 8：音乐播放器视图

OS 画的音乐界面，插件只提供媒体（`tos.media.*`）和几段文字（ADR-036，设计与验证见 `30-player-ui.md`）。

| 组件 | 字段 |
|---|---|
| `nowplaying` | `style`：`full`（整页，默认）/ `bar`（一行迷你播放器）/ `card`（首页卡片）；`idle`（没有会话时显示的文字，可绑定）；`notice`（标题下的一行提示，例如播放错误，可绑定）；`weight`。**需要 `media` 权限** |
| `tabs` | `items`：字符串数组的状态路径（最多 6 项）；`current`：1 起的选中项（可绑定）；`tap`：点击时以 `(name, index)` 发给插件，`index` 从 1 开始 |
| `list.current` | 1 起的行号（可绑定），高亮该行（例如正在播放的曲目），变化时滚动到可见 |

`nowplaying` 的封面（有图用图，没有就按曲名画一张）、取色、格式行、位置条、随机 / 上一首 / 播放 / 下一首 / 循环按钮、系统音量条和队列都由 OS 绘制，按钮直接操作播放器，不经过 Lua。页面可以声明变体 `cover`（默认）和 `queue`，上下滑切换；轻点封面暂停或继续；封面以外的空白仍然由 OS 用来翻页和切变体。没有 `media` 权限的插件使用它会被安装器和打包器拒绝。

### 4.1.3 API 8：电视视图

OS 画的电视界面，插件只提供媒体（`tos.media.*`）和当前频道的描述（ADR-038，设计与验证见 `31-iptv.md`）。

| 组件 | 字段 |
|---|---|
| `tv` | `channel`：状态路径，指向频道对象（见下，可绑定，例如 `{{tv}}`）；`tap`：按钮与手势发给插件的事件名；`fit`：`contain` / `cover`；`weight`。**需要 `media` 权限** |

频道对象的字段都可省略，类型不对的当作没有：`number`（台号）、`name`、`group`、`line` / `lines`（现在第几条线路、共几条；多于一条时出现线路按钮）、`favorite`、`notice`（画面上的一行提示，例如“这个频道的线路现在都不能用”）、`program`（`{title, start, end}`，正在播的节目，时间为 epoch 毫秒）、`next`（`{title, start}`，下一档）。

`tv` 是影院视图 `video`（画面、字幕、缓冲与错误提示、竖拖调亮度与音量）加一层直播界面：顶部是台号、频道名与分组，底部是正在播的节目与进度条、下一档，一排按钮——上一台、下一台、频道列表、线路、收藏。进度条由 OS 按墙钟走，插件不需要每秒刷新。没有节目单、没有多条线路时，对应部分不出现。**没有进度条、没有快进快退**：直播没有可拖动的位置。

按钮和手势只发一个事件：`tos.on("tap", function(name, action) ... end)`，`name` 是 `tap` 字段的值，`action` 是 `"prev"`、`"next"`、`"list"`、`"line"`、`"favorite"` 之一。画面左、右四分之一处双击等同 `prev` / `next`（中间双击仍是暂停与继续的手势，在直播里没有作用）。换台后插件重新 `tos.media.load`；OS 不替插件决定下一台是谁。没有媒体会话时轻点画面也能唤出控件，所以频道都不能播时仍能打开频道页。

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
| `{{uses.daily.cover}}` | 引用 manifest 声明的公开数据（API 6，§6.8）；不可用时为空，可配合 `default` |

内置格式化：`round`、`fixed n`、`time "fmt"`、`date "fmt"`、`upper`、`lower`、`default "x"`。**不支持表达式和条件**——需要逻辑就在 Lua 里算好再放进状态。

列表用 `"items": "{{queue}}"` 绑定整个数组，数组元素的字段在 `item` 模板里用 `{{.title}}` 引用。行内也可读取 `{{config.unit}}` 等插件全局状态；未带点前缀的路径先查行元素再查全局。数组绑定只读取完整数组路径，不执行表达式。

图片地址接受 `asset:assets/photo.png`（所属插件包内资源）、绝对本地路径或 `file:///…`（需要 `library` 权限并通过 OS 目录授权）、`https://…` 或私网 `http://…`（需要 `network` 权限）。URL 不接受用户凭据或 fragment；不接受 `album:`、`content:`。图片字节始终由 OS 处理，`tos.http` 仅用于来源元数据。

### 4.3 示例

**卡片** `views/now.json`：

```json
{
  "template": "glance",
  "children": [
    { "type": "text", "value": "{{temperature}}", "style": "display" },
    { "type": "text", "value": "{{summary}}", "style": "body" },
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

- **相关度（调度待实现）**：卡片可绑定 `relevance`（0–1）。用户 5 分钟内没有手动翻栈时，栈自动转到相关度最高的卡片（例如即将开始的日程、正在播放的音乐）。运行时会对相关度频繁跳动的插件降权。
- **情绪色（映射待实现）**：页面可绑定 `mood`（`"#RRGGBB"`，通常取照片或封面的主色），设备按当前主题映射到安全色域，用来染色背景呼吸场。

## 5. 纯声明插件与 `sources`

没有逻辑的插件可以不写 Lua（如 home），直接使用静态声明与其它插件的 widget 引用。

**当前没有 `sources` 调度器**，manifest schema 也不接受该字段。原先的 `sources.http/sources.album` 属于候选设计，不是现有接口；`photos` 权限名已移除。数据插件现在用 `tos.http` 或 `tos.library.list` 获取清单，通过 `tos.state` 提交给固定组件；本地相册不需要 Server。

## 6. Lua API（`tos.*`）

运行环境：Lua 5.4，沙箱内只有 `string`、`table`、`math`、`utf8`、`coroutine` 和裁剪过的 `base`；没有 `io`、`os`、`package`、`debug`。`require` 只能加载包内文件。

字符串模式匹配已开放：`string.find/match/gmatch/gsub`（含冒号写法）都在 C 匹配器内部执行预算检查，病态回溯同样会触发资源错误。仅 `lstrlib.c` 有这一处上游补丁，详见 `android/app/src/main/cpp/PATCHES.md`。

每个事件处理函数都在协程里执行：标为「挂起」的 API 写起来是同步的，实际不阻塞任何线程。

`tos.http`、`tos.net.resolve` 与 `tos.timer.sleep` 只能在运行时管理的事件/定时器根协程里调用，不能在自行 `coroutine.create` 的子协程或模块顶层调用。挂起保留局部变量；IO 完成恢复同一协程，同时其它插件/定时器继续执行。热替换或停止后，旧协程和迟到结果会取消。`start` 可以挂起，新版启动完成后才替换旧版；`stop` 中应只做同步清理，不启动新 IO。

当前实现范围：下表的 state/config/store/timer/http/net/json/time/page/log 已实现；alert/audio/chime/album/server 与声明式 sources 仍按 M1/M2 推进；`tos.settings` 属 M0.5。

### 6.1 生命周期与事件

```lua
tos.on("start", function() ... end)             -- 插件启动（含热加载后）
tos.on("stop", function() ... end)              -- 插件停止前，适合 tos.store 保存
tos.on("config", function(cfg) ... end)         -- 设置值变化（含 Server 下发、设备上修改）；cfg 为新的只读表
tos.on("units", function() ... end)             -- 系统单位设置（units.*）变化；重新取 tos.units.get() 并重绘，无需权限
tos.on("page.enter", function(page, variant, params) ... end)  -- params：打开页面时附带的参数（设置页用 {scope=…}）
tos.on("page.leave", function(page) ... end)
tos.on("back", function(page) ... end)          -- API 8：用户按了返回，而本插件用 tos.back.intercept(true) 声明过要先听到（§6.11）
tos.on("variant", function(page, variant) ... end)
tos.on("command", function(name, args, source, caller) ... end)   -- source: tap / gesture / voice / remote / plugin；caller 仅 plugin 时有值
tos.on("form.change", function(key, value) ... end)       -- 仅 form 版式页面：用户操作了某个控件（§11.3）
tos.on("attention", function(level) ... end)    -- ASLEEP / AMBIENT / AWAKE / ENGAGED
tos.on("theme", function(theme) ... end)        -- LIGHT / DARK / NIGHT（一般不需要）
tos.on("mode", function(mode) ... end)          -- API 10，无权限要求：day / redshift / sleep
tos.on("update", function() ... end)           -- API 10，需实际授予 system.update：重新读 tos.update.status()
tos.on("cast", function() ... end)             -- API 9，需 media：接收开关或局域网地址变化，重新取 tos.cast.status()
tos.on("presence", function(present) ... end)   -- 需 presence 权限
tos.on("gesture", function(name) ... end)       -- 需 presence 权限
tos.on("media.session", function(info) ... end)  -- 仅媒体会话所有者收到（API 7，§6.10）：{reason="replaced"|"stopped", owner}
tos.on("media.track", function(info) ... end)    -- 换到队列里的另一项：{index, reason}
tos.on("media.error", function(info) ... end)    -- {code, name, category, message, codec, status?, index}
tos.on("media.warning", function(info) ... end)  -- 非致命：{code, name, codec}，如某条音轨无法解码而画面照播
tos.on("queue.end", function(info) ... end)      -- 队列播完：{index, count}
tos.on("data", function(alias) ... end)         -- API 6：声明的公开数据或其可用性变了（§6.8.3）
```

### 6.2 API 一览

| API | 权限 | 挂起 | 说明 |
|---|---|---|---|
| `tos.state.set(t)` | — | 否 | 合并状态并触发视图更新；API 3 也可传对象 JSON 文本，适合大清单；仍禁止写 `config`，状态总量 ≤256KiB |
| `tos.state.get(path)` | — | 否 | 读状态 |
| `tos.config` | — | — | 只读表：本插件所有设置项的当前生效值（manifest 默认值 ← Server 下发 ← 设备修改，见 §11.2）。插件不能写，值变化时收到 `config` 事件 |
| `tos.settings.list()` | `system.settings` | 否 | 列出所有有设置项的范围：`[{scope, name}]`，`scope` 为 `"system"` 或插件 id |
| `tos.settings.get(scope)` | `system.settings` | 否 | 取某范围的字段描述与当前值：`{name, fields=[{key,type,label,…,value,default,overridden}]}` |
| `tos.settings.set(scope, key, value)` | `system.settings` | 否 | 校验并写入设备上的修改；成功立即生效，失败返回 `nil, err` |
| `tos.settings.setMany(scope, values)` | `system.settings` | 否 | 同一范围内原子校验并提交多个键；任一字段或跨字段约束失败则全不写，用于地点选择等复合输入 |
| `tos.settings.reset(scope, key?)` | `system.settings` | 否 | 清除设备上的修改，回到 Server 下发值或默认值；省略 `key` 即整个范围；成功无返回值，失败返回 `nil, err` |
| `tos.settings.about()` | `system.settings` | 否 | API 9：打开原生关于与许可证页；只授予内置的设置管理员，法律文本随 APK 离线提供 |
| `tos.device.mode()` | — | 否 | API 10：只读当前模式 `day` / `redshift` / `sleep`；变化时收到 `mode(value)` |
| `tos.device.setMode(value)` | `system.device` | 是 | API 10：仅实际获权的出厂设备控制插件可在5秒内真实原生点按后手动切换；值同上，返回 `true` 或 `nil, err`。保持策略读系统 `mode.manual_hold`（`next` / `forever`），不接收持续时间或自动参数 |
| `tos.update.status()` | `system.update` | 否 | API 10：有界只读状态，契约 `shared/schema/update/status.json`；不含路径、下载地址、钥匙或签名 |
| `tos.update.open()` | `system.update` | 是 | API 10：打开原生更新恢复页；真实界面点按后最多5秒，前台 Activity 才能打开 |
| `tos.update.check()` / `download()` / `install()` | `system.update` | 是 | API 10：点按后最多5秒；返回受理成功 `true` 或 `nil, err`，完成看 `update` 事件与状态，不阻塞 Lua 线程。安装只接收本机已验签并就绪的 APK，不接收路径或地址；媒体/投屏/外部前台会阻止安装 |
| `tos.store.get(key)` / `set(key, v)` | — | 否 | 持久化 KV，每插件 256KiB；立即更新内存，最后一次修改 2s 后合并原子落盘，停止时提交。进程突然退出可能丢失尚未提交的修改 |
| `tos.timer.after(s, fn)` / `every(s, fn)` / `cancel(id)` | — | 否 | 定时器；`every(60, fn, {align=true})` 对齐到整分钟 |
| `tos.timer.sleep(s)` | — | 是 | 等待 0.001–3600 秒，不阻塞共享插件线程 |
| `tos.http.get(url, opts)` / `post(url, body, opts)` | `network` | 是 | 返回 `res`（`status`、`headers`、`body`）或 `nil, err`；连接/读超时各 3s，请求/响应 ≤1MiB；`opts.headers` 设置请求头 |
| `tos.net.resolve(host)` | `network` | 是 | API 10：系统 DNS 发现，返回最多8项 `{address, name?}` 或 `nil, err`；IPv4/IPv6数值地址去重。`name` 为小写、无尾点的合法DNS名，canonical PTR须再经系统正向解析确认包含原地址，否则省略。只收≤253字节ASCII DNS主机名，拒URL、端口、控制字符和数值/歧义IP字面量。排队、A/AAAA、PTR及正向确认整体≤3s，失败为脱敏的 `dns invalid host` / `dns unavailable` / `dns timeout` / `dns busy`。不返回URL、不发HTTP，DNS结果不作为连接授权，HTTP(S)仍须按原网络规则和默认证书/主机名校验 |
| `tos.json.decode(s)` / `encode(t)` | — | 否 | C 侧 yyjson；深度 ≤24、文本 ≤1MiB；JSON null 为 `tos.json.null`，保留解码所得空数组/空对象类型及 int64；循环表与非 JSON 值报错 |
| `tos.time.now()` | — | 否 | 可信时间（毫秒）；时间未校准时返回 `nil` |
| `tos.time.format(ms, fmt, tz?)` | — | 否 | 命名格式遵从系统语言与 12/24 小时制；可选 IANA 时区，省略取系统时区；`ms=nil` 显示占位 |
| `tos.tz.home()` / `tos.tz.info(id)` | — | 否 | 自带 IANA 规则的时区 ID、偏移、DST、代表城市与坐标 |
| `tos.locale()` / `tos.i18n.t(key, params?)` | — | 否 | 系统 locale / 脚本 / RTL / 12或24小时信息；插件字符串与占位符替换 |
| `tos.units.get()` | — | 否 | 只读：用户选定的单位 `{system="metric"\|"imperial", temperature="c"\|"f", wind="kmh"\|"ms"\|"mph"\|"kn"}`（系统设置 `units.*`，ADR-032） |
| `tos.units.temperature(celsius)` / `tos.units.wind(kmh)` | — | 否 | 把摄氏度 / km/h 换算为用户选定的单位（数值，不取整、不带符号）；插件内部一律以摄氏、km/h 存数据 |
| `tos.location.get()` | `location` | 否 | 系统配置位置 `{name,latitude,longitude,elevation,timezone,source}`；来源明确区分时区推定和手动输入，见 ADR-021 |
| `tos.astro.sun(ms, place?)` / `tos.astro.moon(ms, place?)` | `location` | 是 | 独立计算线程返回本地天文数据，见 §6.5；省略地点取系统位置。取消/移除插件时不投递迟到结果 |
| `tos.astro.seasons(year)` | — | 否 | 当年二分二至的 UTC 毫秒时间戳，支持 1950–2050 |
| `tos.page.focus(page, {live=bool})` / `release(page)` | — | 否 | 请求切到本插件页面，由 DeckRouter 仲裁；`live` 需页面声明 `live`。API 8：`live` 压一个接管层，应用页压一个应用层，普通页是翻页序列里的一步；`release` 关掉本插件自己的接管层或应用层（§6.11） |
| `tos.back.intercept(on)` | — | 否 | API 8：声明“返回先给我”；`page.leave` 时自动作废；2 秒不理会 `back` 就被越过（§6.11） |
| `tos.cast.status()` | `media` | 否 | API 8：只读 `{name, dlna, airplay, mirror, miracast, miracastModule?, ip?}`：设备名、各接收开关、Miracast 系统模块是否应答、当前的局域网地址；`cast` 插件的等待页用 |
| `tos.alert{…}`（待实现） | `alert` | — | 本轮仅测试 debug 模拟通知界面，未接插件 API |
| `tos.library.list(opts)` | `library` | 是 | 枚举本地媒体，返回 `{items,truncated,scanned,directory}` 或 `nil,err`；见 §6.6 |
| `tos.media.load(items, opts)` 及 `play` / `pause` / `toggle` / `next` / `prev` / `seek` / `volume` / `stop` / `shuffle` / `repeat` / `jump` / `live` / `audioTrack` / `textTrack` | `media`（远程另需 `network`，本地另需 `library`） | 是 | 音视频会话与流媒体（HLS / DASH / RTSP / 渐进式 HTTP / 本地），返回 `state, err`，见 §6.10 |
| `tos.media.probe(url, opts?)` | `media` + `network` | 是 | 测一个地址像不像流（不碰当前会话），返回 `{ok, status, kind, millis, error?}` 或 `nil, err`，见 §6.10 |
| `tos.chime(name)`（计划） | `alert` | — | 内置提示音，尚未实现 |
| `tos.server.call(name, args)`（M2） | `server` | 是 | 可选 Server 服务，尚未实现 |
| `tos.server.subscribe(topic, fn)`（M2） | `server` | — | 可选 Server 推送，尚未实现 |
| `tos.tile.animate(name)` | — | 否 | 请求自己当前可见的磁贴播放 `pulse` / `flip` / `nudge`，返回 `true` 或 `nil, err`（`unknown` / `cooldown`），见 §6.9 |
| `tos.data.get(alias)` | manifest `uses` | 否 | 读声明的公开数据，返回值或 `nil, err`；≤64KiB（§6.8.3） |
| `tos.command.call("<plugin>.<id>", args?)` | manifest `calls` | 否 | 投递公开指令，返回 `true` 或 `nil, err`（§6.8.4） |
| `tos.log.info/warn/error(...)` | — | 否 | 日志，带插件 id 前缀 |

### 6.3 权限

| 权限 | 授予的能力 |
|---|---|
| `network` | `tos.http`、`tos.net.resolve`、`sources.http` |
| `alert` | 预留通知与提示音权限，当前宿主 API 尚未接入 |
| `media` | OS 音视频播放及媒体会话控制（原 `audio` 权限名已移除） |
| `library` | 本地媒体目录与文件元数据枚举（原 `photos` 权限名已移除） |
| `location` | `tos.location.get`、`tos.astro.sun/moon`、位置设置变化时的 `location` 事件 |
| `presence` | `presence`、`gesture` 事件 |
| `server` | `tos.server.*` |
| `system.settings` | `tos.settings.*`；可声明 `settings` 角色页面。**系统权限**：只授予 APK 内置的出厂版本，Git 来源的包声明它会被 Server 和设备双方拒绝（§12.6） |
| `system.device` | 出厂设备控制能力；API10手动 `tos.device.setMode` 还需同VM在5秒内的实际OS点按或原生布尔 `form.change`，执行前复核。`tos.device.mode()` 与 `mode` 事件不需要此权限 |
| `system.update` | API10的更新只读状态、事件、原生恢复页及check/download/install操作；仅APK摘要匹配的出厂副本，操作还需5秒内真实点按，安装受实际候选/事务/媒体与前台门禁约束 |
| `system.plugins` | API10的catalog/installed、open/install/update/uninstall/setAuto及plugins事件；仅APK摘要匹配的出厂副本。catalog/installed为读取，其余操作需5秒内真实点按；安装及新增权限由OS原生确认 |

`system.update` 只授予 APK 中摘要匹配的出厂副本，声明权限不等于实际授予。更新事件同样按 API 和实际权限过滤。操作信用来自 OS 的 `tap` 或原生 link/toggle 的布尔 `form.change`；计时器、指令、页面/布局事件、异步表单结果不能产生点按时间，Lua 也不能传入时间。信用跟随VM实例并在终止时清除；主线程排队后重新核对，检查/下载/安装控制器在实际执行前再次核对。`install()`已接入受控安装器，`can_install`依据已验签候选、未决事务与运行期门禁计算，不接受Lua传入路径/地址。没有正式公钥、可用来源或就绪候选时仍不可安装；接口存在不代表正式OTA已验收。

`system.plugins`同样只按实际出厂摘要授予，开发/下载副本只声明该权限也不能获得任何管理接口或事件。目录下载、验签与安装归原生registry，Lua只调用`tos.plugins.*`，不通过`tos.http`取得安装目录或包；首次安装及权限增加必须经过OS确认。实际基础安装和越权拒绝见`artifacts/registry/REG-2/0.1.8/`，完整生命周期/恶意样本仍按REG-2b验收。

模式接口沿用API10，不提升API号。`device.mode` 和 `mode(value)` 的值固定为小写 `day/redshift/sleep`，普通插件只有读取与订阅能力；`device.setMode` 不接受大写、时间戳或时长。控制面板通过此公开接口显示Wi-Fi、静音、红移、睡眠、设置、应用六个quick，OS排成3列×2行；红移图标 `sunset`、睡眠图标 `moon`，关闭回 `day`，布局没有插件参数。

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

### 6.6 本地媒体库（API 3）

```lua
-- manifest: api=3, permissions=["library"]
local result, err = tos.library.list{kind="image", recursive=true}
if result then
  local photos = tos.json.decode("[]")
  for i, item in ipairs(result.items) do
    if i > 300 then break end
    photos[#photos+1] = {id=item.src, src=item.src, title=item.title}
  end
  tos.state.set(tos.json.encode{photos=photos})
else
  tos.log.warn(err)
end
```

`opts` 只接受 `kind: image/audio/video`、可选绝对路径 `dir` 和布尔 `recursive`（默认 false）。省略目录时取应用外部私有 `Pictures/Music/Movies`；空私有目录返回空数组，不扫描共享盘兜底。image 项含 `src/url/title/mime` 与可读取到的 `width/height/rotation`；宽高已按 EXIF 方向修正；不可读取的可选值可能是 `tos.json.null`，与 `tos.json.decode` 的 null 语义相同。格式 jpg/jpeg/png/webp/gif（首帧）/bmp；API 27 不支持 HEIC。

扫描在 PluginIO 执行，结果由 OS 编成 JSON 文本跨 JNI，C 侧解码后仍返回相同的 Lua 表。大清单可用 `tos.state.set(tos.json.encode{photos=photos})` 提交，避免数千次逐字段 JNI 往返；编码文本与表补丁采用相同的 config 隔离、原子合并和 256KiB 上限。Lua 挂起后回到原插件线程恢复；停止/替换插件时取消，迟到结果丢弃。硬预算：≤512 项、≤8192 个目录条目、递归深度≤16、文本≤1MiB；命中预算 `truncated=true`。`scanned` 包括被跳过的条目；`directory` 是最终授权的绝对目录。

OS 在枚举和最终打开文件时都重新授权；不允许应用内部配置目录、其它 App 私有目录或逃逸符号链接。应用私有外部目录不需要共享读取授权；共享目录需要 manifest 中的 `READ_EXTERNAL_STORAGE`。依 ADR-030，Device Owner 启动时可静默授予，否则首次访问弹 Android 权限窗；同一运行期明确拒绝后，后续后台扫描返回 `nil, err`，不反复弹窗，Android 设置里实际授予后可继续读取。不申请写权限、不修改 Device Owner；`library` 插件权限本身不等于 Android 的存储读取授权。

开发送图（不需要 Server）：

```bash
adb shell mkdir -p /sdcard/Android/data/moe.kiwi.thinkos/files/Pictures
adb push photo.jpg /sdcard/Android/data/moe.kiwi.thinkos/files/Pictures/
```

gallery 最多 300 项，另以保守的 180KiB 清单预算给设置/预览留出状态空间；超长路径会进一步减少项数。默认每 300 秒重扫，也可发插件 `refresh` 命令；显示选项全在 manifest 设置 schema 中。选本地来源时只扫描本地目录，空目录显示提示。

### 6.7 相册来源：微软 Bing 每日一图

`plugins/gallery/sources/bing.lua` 只处理清单；main.lua 经公开 `tos.http.get / tos.locale / tos.store / tos.timer / tos.state` 调度，不包含图片下载私有接口。默认 `source=bing`，可改 `local` 或 `all`（每日图与本地交错）。设置都在 manifest schema：市场自动/手选、刷新默认 6 小时、历史默认 30（1–90）、重试、显示和本地扫描选项。

- 无缓存时延期首次请求，先取 `idx=0`，再取 `idx=7`，每次最多 8 项；有缓存时立即渲染，未过刷新间隔不联网。手动 refresh 重新取两批。每批独立保存，后一批失败不丢首批。
- `tos.locale()` 的 `language/region/script` 映射市场；简体中文地区用 zh-CN，繁体及没有本地化的语言用 en-US；支持 en-GB、ja-JP、de-DE、fr-FR、es-ES、pt-BR 手选。
- 使用受限的 `/th?id=OHR.…` urlbase 构造 `_1920x1080.jpg`；横竖屏共用同张原图，交给 OS 平移保留完整内容。按图名去重、fullstartdate 排序；title 为空/Info 时从 copyright 提取标题，完整 copyright 另作常驻 credit。默认跳过显式 wp=false，此标志不作为许可证明。
- 元数据以 JSON 文本保存至 tos.store，历史数和 180KiB 保守预算共同约束；跨市场/endpoint 不混用缓存。图片缓存归 OS。失败保留旧清单，按设置指数退避；校时前等待，ASLEEP 不发起新刷新；来源/市场变化先撤销旧代际再进行异步 IO。
- 首次离线且没有缓存时显示可重试的提示；已有数据时保留可用照片。Server 下发/管理、自定义 JSON 源、其它公共图源继续暂缓。

### 6.8 公开数据与指令（API 6，ADR-027）

插件状态默认私有。插件显式公开一段状态后，其他插件声明即可读取，不用互相认识实现，也不需要 Server。模型借鉴 Home Assistant 的 Entity / Service / 卡片，但没有实体注册表和全局总线，也不依赖 HA（ADR-006）。

#### 6.8.1 提供方

```json
"exports": {
  "daily": { "path": "public.daily", "shape": "photos/1" },
  "place": { "path": "public.place", "shape": "place/1", "requires": ["location"] }
}
```

| 字段 | 说明 |
|---|---|
| 键 | 导出名，`[a-z][a-z0-9-]*`，全名 `<plugin>.<name>`；每插件 ≤16 个 |
| `path` | 状态里的路径，用普通 `tos.state.set` 写入，共用 256KiB 状态预算。不能以 `config`、`uses`、`variant` 开头，所以设置值和 `secret` 字段不可能被导出；同一插件的导出路径不能重叠 |
| `shape` | 内容约定 `<名称>/<主版本>`。不兼容地修改内容就升主版本，使用方读不到而不是读错。OS 只比较字符串，不校验结构 |
| `requires` | 可选。读取方必须在 manifest 声明这些权限（不含 `system.settings`）。数据来自需要权限的能力（如位置）时必须声明，否则等于把权限转授给所有插件 |

没写在 `exports` 里的状态，其他插件读不到。

#### 6.8.2 视图读取（不需要 Lua，也不需要额外权限）

```json
"uses": { "daily": { "export": "gallery.daily", "shape": "photos/1" } }
```

```json
{ "layout": "stage", "slots": { "stage": {
  "type": "carousel", "items": "{{uses.daily.photos}}", "interval": 30, "duration": 800 } } }
```

`uses` 是保留键，和 `config` 一样由 OS 注入状态快照；API 6 起插件写它会被拒绝，API 1–5 的同名状态不受影响。提供方更新时，只有受影响的绑定会刷新，每个使用方最多约每 250ms 一次。

**不可用时为空，不报错**：提供方未安装、被隔离、未导出、`shape` 不符或缺 `requires` 权限时，`uses.<alias>` 不存在，绑定为空，可以写 `{{uses.daily.title | default "—"}}`。原因写入 logcat 和诊断（`unresolved=[daily:shape_mismatch]`）；提供方恢复后自动重连。打包器对视图里未声明的 alias 报错，对打包集合内不匹配的链接警告；提供方可能之后才安装，所以设备上不拒绝。

**图片授权**：`image.src` 或 `carousel.items` 的**整个值**是一个 `{{uses.<alias>…}}` 绑定时，图片按**提供方**的权限和包目录加载，使用方不用声明 `network`/`library`，也拿不到字节。拼接或带格式化的值（`https://me/{{uses.x}}`、`{{uses.x | default "…"}}`）算使用方自己的请求，按使用方权限检查。

#### 6.8.3 Lua 读取

```lua
tos.on("data", function(alias)
  local daily, err = tos.data.get(alias)   -- err: undeclared / missing / shape_mismatch / permission_required / empty / too_large / invalid
  if daily then tos.state.set({ count = #daily.photos }) end
end)
```

`data` 事件只告诉你哪个 alias 变了，同一 alias 最多每秒一次，所以两个插件互相订阅也不会空转。读取 ≤64KiB 且 ≤4096 个数组/对象，超出返回 `too_large`，大清单请绑定视图；嵌套超过 `tos.json` 深度返回 `invalid`，所以提供方的值不会让使用方抛错或内存超限。`start` 时提供方可能还没启动，读不到时等 `data` 事件。

**读取不会触发提供方联网，也不会唤醒它**：提供方按自己的节奏更新（如 ASLEEP 时暂停），使用方看到的是它最后发布的值。

#### 6.8.4 公开指令

```jsonc
"commands": [{ "id": "refresh", "public": true, "cooldown": 300 }]   // 提供方
"calls": ["gallery.refresh"]                                          // 调用方
```

```lua
local ok, err = tos.command.call("gallery.refresh", { reason = "tap" })  -- err: undeclared / unavailable / not_public / too_large / cooldown
```

只投递，不等待结果；提供方收到 `command` 事件，`source="plugin"`，`caller` 为调用方 id。`args` ≤4KiB。`cooldown`（秒，默认 1，0.1–86400）按指令计，不分调用方，用来保护提供方；会联网的指令应设得更长。结果需要回传时，由提供方更新公开数据。声明式视图不能直接调用指令，点击先发 `tap` 给自己的 Lua。

#### 6.8.5 复用排版

- 任何页面都能引用任何插件的 widget。卡片按所属插件的状态、权限和设置渲染，点击走它的 `opens`，长按打开它的设置。使用方不能改外观（ADR-017）
- API 6 起 `focus` / `split` / `stage` 的槽位也可放 `{"widget":"gallery.preview"}`，此前只有 `stacks`、`tiles`、`aero` 支持
- `widgets[].alternates` 让一个 widget 按模板提供多种呈现，容器自动选用：`tiles` 取 `tile`，其他容器取 `glance`，没有对应呈现时用 `view`

```json
{ "id": "preview", "view": "views/preview.json", "opens": "slideshow", "alternates": { "tile": "views/tile.json" } }
```

#### 6.8.6 自带插件的公开内容

| 导出 | `shape` | 内容 | 说明 |
|---|---|---|---|
| `gallery.daily` | `photos/1` | `photos[{id,src,title,credit}]`、`cover`、`coverTitle`、`coverCredit` | 只含 Bing 每日图，不含本地路径；≤48KiB。`source=local` 时不拉取 Bing，内容为空。显示时保留 `credit` |
| `weather.now` | `weather-now/1` | `temperature`、`icon`、`condition`、`stale` | 不含地点，所以不要求 `location` |

公开指令：`gallery.refresh`、`weather.refresh`，`cooldown` 均为 300 秒。

### 6.9 磁贴动画（API 6，ADR-028）

按下倾斜和页面入场由 OS 自动播放，插件不用也不能参与。插件能做的只有按名称请求下面三种，样式、时长和曲线都归 OS：

| 名称 | 效果 | 适合 |
|---|---|---|
| `pulse` | 放大到 108% 再回弹 | 有了新数据 |
| `flip` | 立即翻到下一面（只有一面时无效果） | 想让用户看到另一面 |
| `nudge` | 左右抖动几下 | 失败、被拒绝 |

```lua
-- 刷新成功且温度变了才提示；第一次加载不提示
if shown and shown ~= last then tos.tile.animate("pulse") end
```

只作用于调用者自己、且在当前页面可见的磁贴。同一插件同一名称 1 秒冷却，之后返回 `nil, "cooldown"`；名称不在表里返回 `nil, "unknown"`。用户关闭“自动切换磁贴内容”（`tiles.live`），或系统关闭动画时，请求被静默忽略。调用本身不联网、不需要权限。`gallery` 在每日图换新时、`weather` 在温度变化时使用它。

### 6.10 媒体会话与流媒体（API 7，ADR-030）

OS 持有一个音视频会话和一个队列（≤512 项）；同一时间只有一个所有者。插件只决定**播什么**，缓冲、解码、字幕、控件、进度条都归 OS（ADR-017）。需要 `media` 权限；远程地址另需 `network`；本地文件与旁路字幕文件另需 `library`。设计、验收和已知限制见 `27-streaming.md`。

**`tos.media.load(items, opts)`** → `state, err`。`items[i]` 的字段：

| 字段 | 说明 |
|---|---|
| `url` | `http` / `https` / `rtsp`、`file:` / `content:` 或绝对路径，≤8192 字符。HTTP(S) 里不能带 `user:pass@`；`rtsp://user:pass@host` 是唯一允许带凭据的形式，凭据不进入 `media` 状态和日志 |
| `title` / `artist` / `album` / `art` | 元数据，只用于显示（≤2048 / 8192 字符） |
| `kind` | `audio`（默认）或 `video`。只决定声道用途和页面，不决定能不能播 |
| `type` | `auto`（默认，按 URL 后缀、`mime` 判断，其余让播放器探测容器）、`progressive`、`hls`、`dash`、`rtsp`。`progressive` 不能带 `subtitles` |
| `mime` | 可选提示：`audio/*`、`video/*`，或 `application/x-mpegURL`、`application/vnd.apple.mpegurl`、`application/dash+xml` |
| `headers` | 至多 8 个请求头，仅 HTTP(S)。除 `User-Agent`、`Referer`、`Accept`、`Accept-Language`、`Origin` 以外的头（包括 `Authorization`、`Cookie`，以及 `X-Api-Key` 之类自定义令牌）只发给该项 URL 的同源地址：分片、密钥、字幕在别的主机上也不会带过去，跨源重定向也会丢掉；`User-Agent` 会替换默认的 `ThinkOS/1`。`Host`、`Range`、`Content-Length`、`Connection`、`Transfer-Encoding`、`Accept-Encoding`、`If-*`、`Content-Type` 等由 HTTP 栈自己管，不可设置 |
| `subtitles` | 旁路字幕 `{url, mime?, lang?, label?}`，至多 16 条，SubRip / WebVTT / TTML / SSA（按后缀推断 MIME） |
| `public_only` | API 10，可选 bool，默认 `false`。`true` 只允许公网 HTTPS 媒体；所有 DNS 结果与实际连接地址都须是公网单播，混合公私 DNS 一律拒绝。重定向、HLS / DASH 子清单、分片、密钥和字幕沿用同一限制，拒绝本地文件、content、RTSP 与 HTTP；`media.public_http` 也不能放宽。使用独立连接池，不复用普通媒体的连接或源工厂 |

一个队列最多 512 项，其中 HLS / DASH 项最多 16 个：播放器会同时准备队列里所有的项，每个 HLS / DASH 项在准备时就去取自己的清单，所以几百个频道的列表要**逐个点播**，不要整张列表排进队列。本地文件和渐进式 HTTP 是按需打开的，可以排满。

`opts`：`index`（从 1 开始）、`shuffle`、`repeat`（`off` / `one` / `all`）、`autoplay`（默认 `true`）、`volume`（0…1，播放器增益）、`start`（毫秒；0 表示默认位置，直播流为直播点）。成功返回 `media` 状态表；失败返回 `nil, err`，已有队列不受影响。

**控制**（都返回 `state, err`；任何有 `media` 权限的插件都能控制当前会话，不论它是不是所有者——例如语音指令插件要能暂停别人在放的音乐。只有 `load` 会换掉所有者，并通知旧所有者 `media.session`）：`play`、`pause`、`toggle`、`next`、`prev`、`seek(ms)`、`volume(v)`、`stop`、`shuffle(bool)`、`repeat(mode)`（`repeat` 是 Lua 关键字，也可用 `repeatMode`）、`jump(index)`、`live()`（回到直播点）、`audioTrack(index)`、`textTrack(index)`（0 为关闭字幕；序号对应状态里 `audioTracks` / `textTracks`）。屏幕上的控件由 OS 直接调用播放器，不经过 Lua。

逻辑睡眠会暂停现有播放并拒绝新的播放/投屏请求，退出后不自动续播。系统音量1–5的归零与恢复由OS及私有恢复记录处理，插件不能读取记录或绕过门禁。当前睡眠画面仍为 `NIGHT`、亮度不低于3%；黑屏与偷看须等MODE0人工验证，`device.mode() == "sleep"` 只证明逻辑模式，不能证明屏幕全黑。当前能力与待验范围见 `45-modes.md` §6、§12。

**状态 `media`**（保留键，只读，需 `media` 权限；视图可绑定 `{{media.title}}`，Lua 可用 `tos.state.get("media.position")`；`tos.state.set` 写 `media` 会被拒绝）。位置约每秒更新一次，进度条自己读播放头，不依赖它。

| 字段 | 说明 |
|---|---|
| `status` | `stopped` / `playing` / `paused` / `buffering` / `reconnecting` / `ended` / `error` |
| `owner` `kind` `index` `count` | 所有者插件 id、当前项的 `kind`、当前序号（从 1 开始，空队列为 0）、队列长度 |
| `title` `artist` `album` `art` | 优先取容器里的标签，没有就用 `load` 给的值 |
| `position` `duration` `buffered` `progress` | 毫秒；`progress` 为 0…1；直播流的 `duration` 是可回看窗口 |
| `live` `seekable` `liveOffset` `mirror` | 是否直播、能否拖动、落后直播点多少毫秒；`mirror` 为真表示这是别的设备共享上来的屏幕（Miracast，只有 OS 能创建，不能暂停与跳转，ADR-031） |
| `source` | `progressive` / `hls` / `dash` / `rtsp` |
| `videoWidth` `videoHeight` `pixelRatio` `frameRate` | 当前画面（已按旋转修正） |
| `videoCodec` `audioCodec` `bitrate` `sampleRate` `channels` `bitDepth` | 实际在解的格式；`bitDepth` 未知为 0 |
| `videoDecoder` `audioDecoder` `droppedFrames` | 用到的解码器名（`OMX.qcom…` 是硬解，`ffmpegLavc…` 是 FFmpeg 扩展）与本次丢帧数 |
| `audioTracks` `textTracks` | `[{index, language, label, codec, channels, selected}]`，嵌入和旁路字幕统一编号 |
| `volume` `shuffle` `repeat` | 播放器增益、随机、循环 |
| `retry` `warning` `error` | 当前重试次数；非致命警告（如 `audio-unsupported:audio/ac3`，画面照常播放）；错误对象，见下 |

**事件**（只发给会话所有者）：`media.session {reason = "replaced" / "stopped", owner}`、`media.track {index, reason}`、`media.error {code, name, category, message, codec, status?, index}`、`media.warning {code, name, codec}`、`queue.end {index, count}`。`media.error.category`：`network`（含超时、DNS、重定向循环）、`http`（`status` 是 HTTP 或 RTSP 的应答码）、`notfound`、`permission`、`policy`（明文 HTTP 被拒）、`format`（容器/编码不支持，`codec` 给出 MIME）、`decoder`、`audio`、`other`。

**`tos.media.probe(url, opts?)`**（API 8，ADR-038）→ `result, err`：问一个地址像不像一路流，不占用也不改动当前会话。走与播放同一套网络规则（私网明文、`media.public_http`、三次重定向、不降级，所以 `tos.http` 拒绝的公网明文地址它能测），只读开头的 16 KiB，整个测试 ≤12 秒；同时最多两个测试在做。`opts.headers` 同 `load` 的 `headers`。HLS 列表会顺着第一个子列表和第一个分片再各问一次（死频道常常还能拿到列表）。`result`：`ok`（地址像流）、`status`（HTTP 状态，没有应答为 0）、`kind`（`hls` / `stream` / `html` 状态 200 的网页 / `list` 频道列表而不是流 / `empty`）、`millis`、失败时的 `error`：`http`（状态不是 2xx）、`format`（200 但不是流）、`timeout`、`dns`、`tls`、`network`、`policy`（网络规则不让连）、`unsupported`（rtsp 等不能测的地址）。`policy` 和 `unsupported` 说的是“测不了”，不是“线路坏了”。

**网络规则**（沿用 ADR-022，并补充）：明文 HTTP 与 RTSP 只允许私网、环回、链路本地地址，DNS 与实际连接地址都校验；公网明文要用户在系统设置打开 `media.public_http`（默认关，只作用于媒体通道，不影响 `tos.http`）；最多 3 次重定向，HTTPS→HTTP 一律拒绝，跨源重定向丢弃 `Authorization` / `Cookie`。RTSP 固定走 TCP 交织（RTP 与控制共用一条经过校验的连接），不支持只提供 UDP 的服务端。

API 10 的 `tos.media.probe(url, {headers=..., public_only=true})` 使用与队列条目相同的公网限制；`public_only` 必须是 bool，显式 `false` 保持普通媒体规则。策略阻断返回 `ok=false, error="policy"`，包括初始地址、重定向及 HLS 子资源；它表示不能测，不应当作坏台暂停。`load` 的初始策略阻断返回 `nil, "policy"`，播放时子资源的策略阻断发 `media.error.category="policy"`，不自动重试。公网地址门禁保守排除特殊用途、文档、转换与保留网段，不把“非 RFC1918”当成公网。

`public_only` 的动态 DASH 使用 ThinkOS 已校时的 OS 时钟作为 direct UTC timing，不启动播放器自己的 SNTP/UDP 授时，也不另开清单指定的授时连接。静态 DASH 和未声明该字段的普通媒体保持原行为。此字段约束媒体及字幕请求；`art` 是独立图片能力，第三方目录不得把未验证的 favicon/封面地址交给它。

**自动恢复**：超时、连接失败、HTTP 408 / 429 / 5xx、RTSP 5xx 视为临时故障，按 1 / 2 / 4 / 8 / 15 / 15 秒退避重试六次，期间 `status = "reconnecting"`，网络恢复时立即重试；之后给出 `error`。401、403、404、策略拒绝、格式或解码错误不重试。直播被对端断开会重连；掉出直播窗口会自动回到直播点；缓冲 30 秒没有任何增长视为卡死，重连后仍不行则报错。

**组件**（API 7，需 `media` 权限，安装时检查；API 8 的 `nowplaying`、`tv` 同理，见 §4.1.2、§4.1.3）：`video`（`fit`：`contain` / `cover`）是 OS 画的影院视图：画面、字幕、缓冲与错误提示，触摸时浮出标题、进度条、上一首 / ±10 秒 / 播放暂停 / 下一首、音轨与字幕按钮，无操作一段时间后淡出（跟随 `attention.engaged_idle_s`，默认 15 秒）；`controls`（`mode`：`queue` 上一首·播放·下一首，`seek` ±10 秒，`play` 仅播放暂停）；`seekbar` 可点按、拖动，拖动不会被翻页手势抢走。视频播放时显示屏至少保持 AWAKE（系统设置 `media.keep_awake_video`，默认开）。

系统设置（ADR-017）：`media.public_http`、`media.keep_awake_video`，以及隐藏项 `media.buffer_s`（默认 30，10…120）、`media.buffer_mb`（默认 48，8…96），后两项只对下一次 `load` 起作用。

### 6.11 应用与返回（API 8，ADR-040）

**应用**是 `apps[]` 里的一项，指向本插件自己的一个页面。它全屏，不在左右翻页序列里，页面上没有翻页指示点；OS 不用左右滑去翻页，左右滑属于页面自己的内容。进入：自己的磁贴或卡片 `opens`、`tos.page.focus`；退出：**返回**（屏幕最左缘向右滑，或 Back 键）、Home 键，或 `tos.page.release`。应用页退出的是栈，不是页面：从应用里再打开别的应用、被接管、进设置，都是再压一层，返回一层一层退（`33-apps-and-back.md` §3）。

- **启动动画**：从磁贴打开应用是“翻转并启动”（所有磁贴绕左边缘翻走，应用页翻进来），返回时倒着放。样式、时长、顺序归 OS，插件只能打开或关闭应用（ADR-028）。
- **`tos.back.intercept(true)`**：应用有自己的层级（频道页里打开了一个分组）时，声明返回先给它。用户返回时它收到 `back` 事件（参数是页面 `id`），这时它自己决定：上一层、关一个弹窗，回到根层就 `tos.back.intercept(false)` 把返回交还系统。拦截是声明式的，OS 当场决定，不等 Lua 回答；`page.leave`（包括被别的层盖住）会让它作废，回到前台的 `page.enter` 里要重新声明。不需要权限，只对栈顶那一层所属的插件有效。
- **不会被困住**：收到 `back` 后 2 秒内插件没有做任何 `tos.*` 动作（只读取不算），下一次返回直接退出并收回拦截。
- **页面角色**：应用页不能带 `roles`（`home` / `ambient` / `sleep` / `settings` / `panel` / `launcher`），安装与打包时检查。
- **返回的顺序**：操作栏 → 页面上的原生弹层 → 声明了拦截的应用 → 弹栈。翻页序列里 Back 键不起作用，最左缘右滑仍是翻页。
- 系统设置：`nav.back_enabled`（默认开）、`nav.back_edge_dp`（隐藏项，16–48，默认 24）。

API9的tos.apps.list()和tos.apps.open(id)需要system.apps，只授予APK出厂副本。list异步返回[{id,kind,title,icon,image}]，ThinkOS id为插件.页面、kind为page；安卓id为android:包名、kind为android，只取有桌面入口的应用，去掉ThinkOS自身及apps.hidden，受apps.android控制。listed:false不进列表，仍可按声明id打开。open异步返回成功值或错误，不接收Intent。保留目标 `tos.apps.open("launcher")` 按 `launcher` 角色打开应用磁贴页并清空返回栈，可关闭设置页；它不出现在list结果中，也不受apps.android开关影响。`launcher` 角色在API9登记，必须声明system.apps，目录发布还会校验出厂副本的实际授权。apps事件报告目录/包/开关变化，受相同权限和API门槛约束。

ADR-046把物理底缘手势改为home首页；公开launcher目标和操作栏应用按钮仍打开应用磁贴页。外部Back由系统模块的固定系统回调实现，不给插件增加按键/触摸注入能力；所有导航仍由OS和NavStack管理。

image源app:包名只在OS取图标、解码、缓存，需要实际授予的system.apps。第三方声明此权限也不会取得图标；Lua拿不到像素或文件字节。list行图片使用页面所属插件的图片权限，不能通过一段状态文字取得其他插件权限。

### 6.12 新手引导（API 10，ADR-042）

内置 `welcome` 用 Lua 决定步骤，用 `tos.store` 保存进度。`onboarding` 页面角色需要真实获授的 `system.settings`，开发副本仅声明权限不能取得角色。引导在独立导航层中运行，Home和物理底缘上滑仍会退出，保留已做的设置与进度。

- `tos.onboarding.status()` 返回流程 `version`、`active`、`updater` 与问候轮换秒数。当前没有更新器，`updater=false`。
- `tos.onboarding.open("restart" | "resume")` 需要内置设置管理员及真实点按信用。前者重新开始，后者只补跳过的步骤；首次启动的自动续跑由 OS 负责。
- `tos.onboarding.practice(name, text)` 和 `stop()` 只对当前可见的内置引导角色页有效。名称固定为 `swipe`、`variant`、`panel`、`back`、`home`、`long-press`；完成后发 `practice(name)`。错误动作不推进；Home完成报告后仍退出，不会吞掉全局出口。维护手势始终保留。
- `tos.tz.zones(region)` 只读返回地区的 `{id, city, country, time}` 数组。`tos.device.canSetLocale()` / `setLocale(tag)` 仅使用已持有的系统权限，不自行授权。无权限时由插件打开安卓语言页。当前 `canSetTimeZone()` / `setTimeZone(id)` 返回不可用，时区使用安卓日期页，不能把应用显示的时区冒充系统修改。
- `tos.device.openAndroid` 目标扩为 `wifi`、`settings`、`locale`、`date`、`accessibility`，仍不接收任意 Intent。

`form` 的可选字符串 `subtitle`、`primary`、`secondary`、`back` 启用单列引导布局，`tap` 指定操作名称；主次按钮和返回发 `tap(name, "primary" | "secondary" | "back")`。无这些字段的设置表单保持原样。可选 `hero` 槽用于不超过72sp的时间等内容，空内容自动收起；`source: "region"` 是离线可搜索地区选择器。设置通过声明的 `uses` 读取 `welcome.setup` 公开进度，显示“完成设置”，不读取私有存储。

## 7. 完整实现：天气插件

源码见 [`plugins/weather/`](../plugins/weather/manifest.json)。卡片 `weather.now` 使用 `glance`，点击进入 `weather.detail` 的原生 `split` 页面：当前温度、体感/湿度/风、24 点温度面积图和可滚动的 5 日预报。所有图标、主题、布局和动画由 OS 绘制。设置、状态和事件只走公开 `tos.*`，无需 Server。

- 地点只来自 `tos.location.get()`，在系统设置中编辑；天气设置不重复保存坐标。温度始终按摄氏、风速按 km/h 请求，按系统单位设置（`tos.units`，ADR-032）在本地换算温度与风速；天气不再有自己的单位开关。更改单位和语言不会联网。
- 默认 [Open-Meteo Forecast API](https://open-meteo.com/en/docs)，`forecast_days=5`、`timezone=auto`、`timeformat=unixtime`。小时与更新时间使用原始 UTC 秒转毫秒、响应时区及命名格式；日预报边界跨 DST 仍使用响应固定偏移，因此加该偏移还原名义日期，在 UTC 以 `@weekday` 显示，避免星期重复。
- `forecast.lua` 校验全部必需字段、数组长度/时间步长、有限数值与温度范围，整份通过才替换旧快照。`codes.lua` 映射 WMO 类别、日夜图标和情绪色，未知码明确显示未知天气。11 种语言都有完整词条。
- `start` 同步读缓存、立即渲染，再安排 1 秒后的首个请求。缓存保留原始摄氏数据，作为 JSON 文本写入 `tos.store`；C 编解码避免大数组逐字段跨 JNI 传输。支持早期对象形态缓存，损坏缓存丢弃。
- 请求挂起不阻塞插件线程；单飞并合并重入；位置/接口变更使旧请求失效。默认 15 分钟刷新，失败按 1→2→5→15 分钟退避，保留并标注上次数据；ASLEEP 停止刷新，唤醒后按剩余有效期恢复。未校时不显示相对时间，默认 30 秒重试，可信时间恢复后下一分钟补拉。
- `stale_after` 默认 180 分钟；断网、过期与换地点都有明确文案。重试时长、接口地址和关注阈值均来自 manifest 设置 schema。点击详情的更新时间或发送 `refresh` 指令可手动刷新。
- 卡片显示 Open-Meteo.com，详情附 CC BY 4.0；[使用条款](https://open-meteo.com/en/terms)与[署名许可](https://open-meteo.com/en/licence)适用。免费接口供本项目的个人非商业用途。

相关度与 mood 已发布为状态。自动相关度轮换及 mood 背景映射仍是 OS 后续能力，当前没有消费者；不把状态绑定声明当作已实现效果。第二页 `weather.sky` 和降雨提醒未实现，后者依赖 `tos.alert`。

验证与复现见 [`20-weather-handoff.md`](20-weather-handoff.md) 的续作记录。

## 8. 页面编排配置

**当前设备实现**：角色来自 manifest 的 `pages[].roles`；目录按 home、sleep、其它 page key 排序，settings 不进入左右翻页。AMBIENT 延时从系统设置读取，切首个 ambient 页；NIGHT 排除 ambient 页并退回 sleep。home 的栈内容来自 `plugins/home/views/main.json` 中的 widget 引用，安装新的 widget 不会自动添加它。

下面保留的是 **M2 候选部署草图，当前没有 Server 下发接线**；`deck.roles` 不是正在生效的设备接口。未来实现需先统一共享 schema，不能绕过 manifest 角色与设置契约。

```toml
[device.default.deck]
pages = ["home.main", "gallery.slideshow", "clock.face", "music.nowplaying", "weather.detail"]

[device.default.deck.roles]
home    = "home.main"
ambient = "gallery.slideshow"
sleep   = "clock.face"

[device.default.deck.home]       # home.main 的两个卡片栈（横屏左/右，竖屏上/下）
left  = ["clock.now", "weather.now"]
right = ["music.mini", "gallery.preview"]

[device.default.plugins.weather] # 页面级设置的下发值（§11.2 的“Server 下发”层）
interval = "15"
```

当前设备新增页面会按上述规则重新排序；卡片必须在 home 视图中明确引用。M2 的安装来源候选配置见 §12.3。

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
| `weather` | `detail`（24 小时 + 5 日） | `now` | `refresh` | `network` / `location` |
| `gallery` | 相册轮播 | 预览卡片 | 默认直连 Bing 每日一图，可选本地或合并；离线缓存 | `library` / `network` |
| `player` | `library`（音乐 / 视频 / 最近 / 网络流，点标题切换；**应用 `media`**）、`audio`（正在播放）、`video`（影院）；后两页是 `live` 页面，由播放接管 | `now`、`tile`（应用磁贴） | `toggle` / `next` / `prev` | `media` / `library` / `network`；设置：音乐与视频目录、串流地址、播放列表地址、续播 |
| `iptv` | `channels`（频道列表，分收藏 / 最近 / 全部 / 分组；**应用 `tv`**，打开分组时返回先回分组列表）、`live`（`tv` 组件，`live` 页面，由播放接管） | `tile`（应用磁贴） | 有（列表源、合并、线路测试、节目单） | `media` / `network`；设置：四个频道列表地址、节目单地址与时区、自动测试开关、测试间隔、暂停时长、复测间隔；设计与验收见 `31-iptv.md` |
| `cast` | `home`（等待页，**应用 `cast`**：设备名、三种接收的开关状态和怎么投）、`video` / `audio`（`live` 页面，由投屏接管） | `tile`（应用磁贴） | 有（等待页数据；离开接管页时暂停或结束投屏） | `media`；用 `tos.cast.status()` 读状态，开关在系统设置 |
| `tiles` | main（开始屏）、apps（播放器/电视/投屏/完整应用列表磁贴；开始屏48格已满，另起一页） | — | 无（纯声明） | — |
| `apps` | main（安卓应用网格，自身是应用，不进翻页序列） | tile | 有 | system.apps；页面只列安卓App，关联apps.android；公开list接口仍返回两类应用 |
| `radio` | `stations`（电台/收藏，应用）、`now`（focus + 原生nowplaying，live） | tile | 有（M3U/PLS、收藏、断线重试） | media / network；默认无电台；列表/直接地址/名称/重试间隔走插件设置，播放继续使用唯一MediaEngine，ICY曲名进入公共media.title |
| `notify`（仅 UI 预览） | debug 模拟预览 | — | 未接收或轮询消息 | HTTP 监听按用户要求暂缓 |

自带插件和第三方插件走相同的公开tos接口，能力由实际授予的权限控制；现阶段system.*只授予对应APK出厂副本，不开私有通道。新能力按契约扩展，不给自带插件绕过运行时的入口。

## 11. 设置（ADR-017）

> 状态：设计已定，M0.5 实现。

设置分两级，**用同一种声明格式**：

| 级别 | 谁声明 | `scope` | 例子 |
|---|---|---|---|
| 系统级 | OS 自己：`shared/schema/settings/system.json`，清单见 `04-ui-spec.md` §9 | `system` | 主题、红移、睡眠、方向锁定、亮度上限、变暗时间、Server 地址 |
| 页面级 | 插件 manifest 的 `settings` | 插件 id | 天气温度单位、时钟秒数、相册的轮播间隔 |

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
| `time` | — | API10：`HH:mm` 字符串，24小时制、5分钟一档；原生时刻编辑，取消不提交，确定只提交一次 |
| `choice` | `choices`：`[{value,label}]`（用数组而不是对象，因为 JSON 对象没有顺序） | 单选列表 |
| `number` | `min`、`max`、`step`、`unit?`、`format?`、`zero?`、`presets?` | 点击展开；有档位时内联单选，其余为滑块 + 步进按钮；与单选共用一个展开项 |
| `text` | `maxLength?`（≤ 1024）、`secret?`（设备上显示为 ••••，不进日志、不随回报上传） | 显示当前值；点击编辑（见 §11.3） |

公共字段：`label`（必填，先用单一语种字符串）、`default`（必填，类型必须与 `type` 匹配）、`group?`（分组标题）。

API 10 的 `choice` 可用 `source: "region"` 复用离线地区选择器，值仍是ISO两字母字符串（写入规范为大写）。`allowEmpty: true` 增加“所有地区”，提交空串；省略时不允许空选项。普通设置与引导共用原生搜索/返回行为，不开放外观参数、网络请求或插件私有通道。已有合法地区字符串和本地/下发覆盖层继续使用，不做存储迁移或删除；旧原始值不会因显示而回写。`timezone`、`place` 保持原API兼容。

API 9 的显示声明：

- `level` 为 `normal`（默认）、`advanced` 或 `hidden`。旧布尔 `hidden` 继续接受；`true` 等于 `level: "hidden"`，`false` 等于默认层级，不能和 `level` 同时声明。隐藏只影响界面，原值仍校验、持久保存并可下发
- `depends` 为 `{ "key": "enabled", "value": true }` 或 `{ "key": "source", "in": ["bing", "all"] }`；只能引用同一范围的字段，值须符合目标字段的声明。也可写 `{ "key": "source_1", "not": "" }` 表示不等于空串；三种运算互斥，数字按数值相等，布尔与数字不相等。不存在的目标、无效条件值、自环或循环在安装时拒绝。`SettingsDescriber` 按当前生效值判断整条依赖链；祖先条件不成立时后续行一起收起，不删除值；隐藏层级本身不改变条件结果，Lua 不解释条件
- `number.format: "duration"` 的值和上下限都是非负秒数。整小时、整分钟分别显示本地化的小时、分钟，其余显示秒；`zero` 可用本地化引用指定 0 的文字，要求范围包含 0。`presets` 是 2–64 个不重复、在范围内的数值，决定内联选项；它不是值的白名单，不在档位中的合法下发值仍显示和保存
- manifest 顶层 `related` 是最多 32 个不重复的系统键，按声明顺序显示在插件设置最前面。键必须存在、非隐藏、非 `secret`；高级字段也可关联。此声明不授予插件读写系统设置的能力，不进入该插件的 `tos.config`
- 只有系统声明接受 `section`，它必须引用 `system.json` 顶层 `sections: [{id,label,icon?}]` 中唯一的分类 id；数组决定分类顺序。API10的可选 `icon` 只引用 `layouts.json` 中 `formIcon` 的已登记矢量名。`section` 和 `sections` 不属于插件外观扩展

`tos.settings.get` 的每个范围描述都包含全局系统分类 `sections` 和本范围已本地化的 `fields`，设置页不必为侧栏再读取一次完整系统描述。分类标签在system范围解析；它不附带未关联的系统值。每行带 `key`、实际 `scope`、`level`，系统行可带 `section`；相关行的 `scope` 是 `system`。数字档位返回 `presets: [{value,label}]`，其中 `value` 保持数值，`label` 按 `format` 生成。隐私值继续脱敏，依赖不成立的行不返回。读取描述、写入和重置仍要求出厂 `settings` 管理员权限。

OS 可为数字描述加入 `preview: "brightness"`（API 9），只用于注册的系统亮度字段。原生表单按该标记通过 DeviceLevels 临时预览，松手仍经 form.change 提交，取消或离页恢复保存值；预览不会写设置或经过 Lua。manifest 不能声明 preview，伪造状态标记也不会取得设备权限。

`form`组件可选`nav: "nav"`（API9），值是状态路径，指向`{items:[{id,label,icon?}],current:id}`，最多32类，无样式参数。API10的 `icon` 只引用固定矢量词汇；分类图标全有或全无，以24dp和16dp文字间距显示，选中时用强调文字色，不改变行高。OS在可用宽度≥700dp时显示两栏，以`form.layout(wide)`报告断点、`form.navigate(id)`报告分类选择；不提供nav的旧表单保持单列。设置插件用选择替换当前分类，高级/插件页仍入栈；旋转保持当前分类及层级，RTL由OS镜像。

- `warn?` 只用于 bool：有风险的开关打开后才显示一句。`placeholder?` 只用于 text：仅在编辑框 hint 显示，设置行不显示。内置插件和系统字段不使用 `help`；旧第三方的 `help?` 继续解析显示，兼容 API 1–8（ADR-044）
- key：`[a-z][a-z0-9_]*`；每个插件最多 32 项。系统级 key 用点号表示分组，对应 `config` 里的嵌套对象（如 `theme.mode` ↔ `{"theme":{"mode":…}}`，与 `05-protocol.md` §3.2 一致）
- 值不合法（超出范围、不在 `choices` 里、类型不符）一律拒绝写入并给出原因；系统级允许跨字段约束（如 `theme.night_below_lux < theme.night_above_lux`）
- 系统 `constraints` 中两个 `time` 字段表示开始与结束不得相同，允许同日或跨午夜的两种顺序；两个 `number` 仍表示前者必须小于后者。模式旧键 `theme.night` 保留为隐藏兼容读写，按 `local` / `server` 各层消费旧布尔值为 `redshift.trigger`（关→`off`，开→`sun`，同层合法新值优先）；兼容读取由新键派生，不再存第二份模式值
- 插件升级后：新增的 key 取默认值；已删除的 key 的值保留不用；校验不再通过的值回落到默认值。卸载插件时设置值保留，重装后恢复，只有“重置设置”才清除
- 视图里可用 `{{config.<key>}}` 绑定；脚本通过 `tos.config` 读取

`form.path` 指向的字段描述数组也可包含 `link` 行（契约见 `layouts.json` 的 `formLink`）：`{key, type: "link", label, display?, group?, confirm?, icon?}`。API10的可选 `icon` 与侧栏使用同一固定矢量词汇和尺寸，不接受路径、颜色、字体或样式。它显示换页箭头，激活后发 `form.change(key, true)`；有 `confirm` 时先显示原生确认框，取消不发事件。设置插件只给根分类链接图标，不给插件自有设置页加图标。设置值仍由 OS 描述，不由内容插件伪造写入权限。

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

- **入口**：长按页面或卡片→设置角色页，`params.scope`为所属插件。自有字段或`related`任一非空就有设置页，磁贴/投屏可直达各自关联项；没有设置页时进系统分类。系统根页按`sections`列分类，插件分类动态列出插件；分类/插件页按`level`分常用与高级，Back逐层返回。插件页关联系统键按点号路由到`system`，其它键写当前插件。系统分类的关于/安卓入口分别调用公共`tos.settings.about()` / `tos.device.openAndroid("settings")`，权限仍只授予出厂版本
- **退出**：完成/Home/30秒无操作退出设置会话；Back先关闭原生选择器或展开项，再退分类/高级/插件的内部栈，根页Back退出。内部栈由NAV-02的`tos.back.intercept`和`back`事件维护。页面恢复默认只重置当前显示字段，关联字段按实际范围、地点按六键处理；其它分类/层级与因依赖收起的值保留
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


### 12.8 社区自动发现约定（REG-3，API不变）

将公开GitHub仓库加topic `thinkos-plugin` 后，CI可自动裸读并校验，无需逐项目
人工注册才能进入community未签名目录。默认仅支持仓库根的 `manifest.json`、
`store.json`、原始许可证和 `v<manifest.version>` tag，id跨版本稳定；不猜子目录。
已登记monorepo的path/tag_prefix沿原登记流程。不得占用内置或其它来源id；同批
同id歧义全部拒绝。公开fork、归档项目同样走校验，不额外按这些标志排除。topic不授予官方档位，community设备默认隐藏。

所有者仅对最后批次本机签名和发布；移动tag、权限增加、角色/system权限限制和
设备原生安装确认沿现规则。发现缺页/不完整/限流时不会拿部分结果生成新目录，
也不因搜索遗漏删除旧插件。作者约定与工具命令见 [registry/README.md](../registry/README.md)。
