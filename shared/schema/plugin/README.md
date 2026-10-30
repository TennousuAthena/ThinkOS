# 插件契约（API 1 / 2）

`manifest.json` 定义包元信息；`view.json` 引用 `layouts.json` 中已经实现的固定渲染词汇。API 1 接受 `stacks` / `focus` / `form` 页面、`glance` 卡片、`text` / `icon` / `clock` / `form` 组件。API 2 增加 `split` / `stage`、组件组和 `progress` / `chart` / `list` / `daylight` / `moon`。

用户指定本轮先做 UI 测试。`controls/video/image/carousel` 的候选定义保留作设计记录，尚未进入 `component.oneOf` 和可安装词汇；安装器必须拒绝这些未接线的组件，不能让其进入主线程渲染后崩溃。

`stacks.slots.left` / `right` 为卡片引用数组，例如 `[{"widget":"clock.now"}]`。这些引用解析到对应插件的 widget 和状态。`focus.slots.hero` 为主组件，`under` / `over` 为可选组件。API 2 槽位也可用 `{"children":[组件…]}`，不允许任意嵌套。`split` 使用固定 40/60 的 `lead/main`；`stage` 使用全出血 `stage` 与叠加的 `over/under`。组件不提供像素、颜色或动画参数；`weight` 是组内剩余高度的相对分配。

版本门槛来自 `layouts.json` 的 `x-thinkos-api`。Python `validate_view(view, api)`、Android `PluginSchema.validateView(view, api)` 和 Go 生成的 `ValidateRenderAPI` 读取相同版本表；打包器/安装器传入 manifest 中的 API。API 1 不能使用新增类型、组、版式或 `tap/weight`。

`progress` 支持条/环，`chart` 支持线/柱/面积并为缺值断段；`list` 的 `items` 绑定数组，`item` 内 `{{.title}}` 引用行元素。`daylight/moon` 的 `path` 只接收派生天文数据，不读取设备位置。组件点击统一发 `tap(name, index?)`，列表索引从 1 开始。完整字段见 schema 与 `docs/06-plugins.md` §4.1。

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
