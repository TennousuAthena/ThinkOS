# 插件契约（API 1–10）

`manifest.json` 定义包元信息；`view.json` 引用 `layouts.json` 中已经实现的固定渲染词汇。API 1 接受 `stacks` / `focus` / `form` 页面、`glance` 卡片、`text` / `icon` / `clock` / `form` 组件。API 2 增加 `split` / `stage`、组件组和 `progress` / `chart` / `list` / `daylight` / `moon`。API 3 增加本地/包内/HTTP(S) `image` / `carousel` 与 `tos.library.list`；`tos.state.set` 同时接受对象 JSON 文本以承载大清单。

天气已接入网络与缓存；相册默认直连 Bing，支持本地/混合；通用 OS 图片管线负责下载和有界磁盘缓存，Server 下发/管理暂不实现。`image/carousel` 仅允许 API 3，完整平移或 contain，无 cover 裁切。`video` / `controls` / `seekbar` 在 API 7 开放（需 manifest 声明 `media` 权限，安装器与打包器检查），见 `docs/06-plugins.md` §6.10。字段、权限、生命周期和示例见 `docs/06-plugins.md` §4.1.1、§6.6。

`stacks.slots.left` / `right` 为卡片引用数组，例如 `[{"widget":"clock.now"}]`。这些引用解析到对应插件的 widget 和状态。`focus.slots.hero` 为主组件，`under` / `over` 为可选组件。API 2 槽位也可用 `{"children":[组件…]}`，不允许任意嵌套。`split` 使用固定 40/60 的 `lead/main`；`stage` 使用全出血 `stage` 与叠加的 `over/under`。组件不提供像素、颜色或自定义动画；API 3 只开放固定转场名称、时长和完整画面的平移速度；`weight` 是组内剩余高度的相对分配。

版本门槛来自 `layouts.json` 的 `x-thinkos-api`。Python `validate_view(view, api)`、Android `PluginSchema.validateView(view, api)` 和 Go 生成的 `ValidateRenderAPI` 读取相同版本表；打包器/安装器传入 manifest 中的 API。API 1 不能使用新增类型、组、版式或 `tap/weight`。

`progress` 支持条/环，`chart` 支持线/柱/面积并为缺值断段；`list` 的 `items` 绑定数组，`item` 内 `{{.title}}` 引用行元素。`daylight/moon` 的 `path` 只接收派生天文数据，不读取设备位置。组件点击统一发 `tap(name, index?)`，列表索引从 1 开始。完整字段见 schema 与 `docs/06-plugins.md` §4.1。

API 6 还为磁贴增加全幅 `image`、`faces[].content`（ADR-028，版本门槛同样来自 `x-thinkos-api` 的 `tileFields` / `tileFaceFields`），插件可调用 `tos.tile.animate`。tiles 网格为横屏 8×6 / 竖屏 6×8。

API 6（ADR-027）增加 manifest 的 `exports` / `uses` / `calls`、`commands[].public` / `cooldown`、`widgets[].alternates`，以及 `focus` / `split` / `stage` 槽位里的 widget 引用。schema 表达不了的规则（版本门槛、alias 命名、不能引用自己、`cooldown` 只用于公开指令）在 `validate_public_contract` 和 Android `validatePublicContract` 中检查；门槛表来自 `layouts.json` 的 `x-thinkos-api`。见 `docs/06-plugins.md` §6.8。

新增磁贴图标：music/photo/bell/settings/home/folder/list/star/heart/message/play/battery。

`icon.name` 为可绑定的字符串，不是枚举，无需为新图标修改生成器。原生精确词汇含 `calendar/moon/clock/sun/unknown`，天气增加 `clear-day/clear-night/partly-day/partly-night/cloud/fog/rain/shower/snow/thunder`；未知名称显示占位。

`clock.zones` 是世界表盘的城市列表，每项含 `label` 和 IANA `timezone`。`clock.style` 可为 `digital`、`analog`、`solar`、`world`、`float` 或 `{{variant}}`。

`layouts.json` 的 `x-thinkos-slot-roles` 标记槽位角色；生成脚本同时输出 `LayoutVocabulary.slots/roles`，Android 渲染器读取生成词汇，不另写槽位名称。新增版式须同时实现其渲染语义。

```bash
python3 shared/gen/gen_kotlin.py
python3 shared/gen/gen_go.py
python3 tools/package_plugins.py --validate-only
python3 -m unittest discover -s tools/tests -v
```

生成结果包含 DTO 和 Android schema 校验器；不要手改 `PluginSchema.kt` / `plugin_generated.go`。Python 打包器与 Android 校验器执行同一组 schema，额外检查重复 ID、配置默认值类型、widget 的 `opens` 指向。生成脚本仅支持此目录使用的 JSON Schema 子集，新增未实现的断言关键字会直接失败。

文件集限制：JSON / Lua 单文件 256 KiB、其它单文件 1 MiB、总计 8 MiB、最多 128 个文件；禁止符号链接、绝对路径和 `..` 路径。文件按路径排序，同一输入产生相同的包摘要（文件清单的 sha256，`docs/06-plugins.md` §2）。

目录安装契约由本目录的 `index.json` 与 `catalog.json` 定义，生成两端 DTO 和 Android 结构校验器。打包器与设备端都执行共享 schema；哈希、UTF-8 排序、大小写冲突、总大小等额外安全语义由安装器校验。

目录安装输入含 `index.json`（`id` / `version` / `digest` / `files`），清单不包含索引自身。文件按 UTF-8 路径顺序排序，摘要计算为每个文件的 `path + NUL + sha256 + LF` 串联后的 SHA-256；具体约束见 `docs/06-plugins.md` §2。APK 根索引为 `{plugins:[完整索引…]}`。打包器忽略 README / LICENSE 等非运行时内容，拒绝符号链接和大小写冲突；设备校验清单完整性后再原子安装，不再解 zip。

Lua 语法和运行安全由设备的 Lua 5.4 文本加载器与预算检查；Python 打包器不执行 Lua，也不调用主机上可能版本不同的 `lua` / `luac`。

API 4 新增 `tiles.slots.tiles` 引用数组（1–16 项；widget、size 必填），size 为 small=1×1、medium=2×2、wide=4×2、large=4×4。`tile` 模板含 faces（1–4 面，每面 label 必填，icon/value/detail 可选）、可选 badge/content/color；content 仅 clock/progress/chart/daylight/moon。颜色只能是不透明 #RRGGBB 或 {{绑定}}，页面引用 > 卡片 > 系统 accent；非法绑定逐级回退并记 warn，前景由 OS 选黑/白（ADR-025）。glance 放入 tiles 显示空占位，tile 放入 stacks 按 large 显示。横屏 6×4、竖屏 4×6 首次适配网格，无滚动，放不下丢弃。版本闸门同时检查 layout、template、component。

API 5 新增固定原生桌面 `aero.slots.windows`（1–3 个普通 widgetReference）。OS 绘制窗口、玻璃光泽、任务栏、开始菜单和原创背景；不接受颜色、尺寸或样式字段。横竖屏自动重排，点击 opens、长按卡片所属插件设置。示例见 `plugins/aero`，设计见 `docs/25-windows-design.md`。

API 8（ADR-036）增加 `nowplaying`（`style` 为 `full` / `bar` / `card`，可带 `idle` 与 `notice`，需 manifest 声明 `media` 权限）、`tabs`（`items` / `current` / `tap`）和 `list.current`。音乐播放器的封面、取色、控件、音量条与队列全部由 OS 绘制；设计与验证见 `docs/30-player-ui.md`。

API 8（ADR-038）还增加 `tv`（`channel` 指向频道对象的状态路径，`tap` 事件名，`fit`，需 manifest 声明 `media` 权限）：影院视图加直播界面，台号、节目与进度、上一台 / 下一台 / 频道列表 / 线路 / 收藏全部由 OS 绘制，按钮以 `(tap, action)` 发给插件。设计与验证见 `docs/31-iptv.md`。

API 9（ADR-044）：设置的 `warn` 仅用于 bool，启用时显示；`placeholder` 仅用于 text，编辑框中显示。版本门槛来自 `settingFields`，第三方旧 `help` 保持兼容。状态中的 `formLink` 描述可带 `confirm`，确认后才发 `form.change(key, true)`；它不赋予任何设置写入权限。

API 10：`settingTypes.time` / `formFields.time` 登记 `time` 控件。值必须是ASCII `HH:mm`、00:00至23:55且分钟是5的倍数；不接收秒、时区、AM/PM、外观或自定步长。时刻采用本地草稿，取消不发事件，确定一次 `form.change(key, value)`。`device.mode` 只读与无权限的 `mode` 事件使用 `day` / `redshift` / `sleep`；`device.setMode` 仍须真实 `system.device` 授权及OS点按信用。

原生数字描述可带 OS 计算的 `formPreview` 值 `brightness`（API 9），拖动时经 DeviceLevels 临时预览，取消恢复、松手才提交；它不是 manifest 属性。OS 重新核对注册字段和设置管理员权限，状态文字不能授予设备能力。

`form.nav`（API9）引用状态里的`formNav`，仅提供分类`items:[{id,label}]`和`current`，不含尺寸或颜色。原生实现按700dp断点启用两栏，发送`form.layout(wide)`和`form.navigate(id)`；旧表单不加此属性继续单列。Kotlin/Python/生成Go的版本门槛来自同一`componentProperties.nav`表。

API 10 增加 `system.update`、`tos.update.status/open/check/download` 与 `update` 事件，不改变渲染词汇。权限有安装时 API 门槛，运行时只授予摘要匹配的 APK 出厂副本；开发/Git 副本即使声明也没有权限。操作要求来自 OS 的真实界面点按，最多5秒；`status` 与事件不需要点按。安装器尚未接入，所以 `install()` 未开放、`can_install` 恒 false。状态契约见 `../update/status.json`。
