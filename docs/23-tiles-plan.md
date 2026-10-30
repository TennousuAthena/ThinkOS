# 23 · 磁贴规划：`tiles` 版式、`tile` 卡片模板与 `tiles` 插件

> 2026-10-31。**已于 2026-10-31 按用户授权进入实现。** 实际验收见 `24-tiles-validation.md`。
> 标记：**已核对** = 读过代码或在设备上查过；**提议** = 我的设计，可以推翻；**未核对** = 开工前先验证。
> 先读：`AGENTS.md` → `08-decisions.md` ADR-013 / ADR-017 → `06-plugins.md` §4 → `shared/schema/plugin/README.md` → 本文。

## 1. 目标与边界

用户要一个 Windows 磁贴风格的页面和卡片。

本期做：多尺寸网格页面、插件自定义磁贴底色、磁贴内容自动翻面（动态磁贴）、角标、补充图标，以及一个示例插件。

本期不做：拖动排列或编辑模式（ADR-017 已否决长按编辑，长按仍进设置）、插件自定义文字色、磁贴内放图片或轮播、磁贴页滚动。

沿用的原则：排版、字号、动画归 OS；词汇只随 APK 扩展（ADR-013）；设备离线可用（ADR-015）。**例外**：用户于 2026-10-31 决定磁贴底色由插件定义，这是对 ADR-017“外观不开放接口”的明确修订（§6）。

## 2. 现状核对（已核对）

| 项 | 现状 | 依据 |
|---|---|---|
| API 版本 | 当前为 3，已含 `image` / `carousel` | `layouts.json` 的 `x-thinkos-api` |
| API 4 与 ADR-025 | 已被 `22-player-plan.md` 提议占用，但播放器尚未批准 | `22-player-plan.md` §4.5、P0 |
| 版式 | `stacks` / `focus` / `form` / `split` / `stage`，无网格 | `layouts.json` |
| 卡片模板 | 只有 `glance`，且版本表没有模板这一列 | `x-thinkos-api` |
| 版本闸门 | Python、Kotlin、Go 三处各自实现，读同一张表 | `schema_tools.py`、`kotlin_validator.kt.template`、`gen_go.py` |
| 页面渲染 | `PageView.update` 按槽位角色分两支：`stack` 走数组，其它走单个对象 | `ui/PageView.kt` |
| 卡片引用 | `resolveStackItem` 可复用，能解析跨插件卡片和 `opens` | `ui/StackContent.kt` |
| 卡片外观 | `ComponentGroup` 写死 32dp 圆角、24dp 内边距和 `palette.surface` 底色；`display` 文字 72sp，小格放不下 | `ui/PageView.kt` |
| 颜色 | `Palette` 只有一个 `accent`；夜间由根层滤镜统一处理 | `ui/Palette.kt`、`ui/ThinkStage.kt` |
| 图标 | 15 个名称：天气 10 个加 `sun/moon/calendar/clock/unknown`；没有音乐、相册、通知等 | `ui/IconView.kt` |
| `relevance` | **运行时未实现**，只存在于 schema 和文档，`android/.../java` 中除生成的校验器外没有引用 | grep |
| 手势 | 点击先 `tapAt`，再 `openAt`；长按用 `ownerAt`；三者都只认 `stacks` 和 `groups` | `ui/PageView.kt`、`ui/ThinkStage.kt` |
| 屏幕 | 800×1280，density 260（×1.625），约 788×492dp | `adb shell wm` |

未核对：默认 deck 的页面顺序如何决定。`DeckRouter` 使用目录给的顺序，`06-plugins.md` §8 写的 `config.toml` 编排在无 Server 时的默认值未找到，T3 先确认新页面出现在哪里。

## 3. 插件视角的接口（提议，API 4）

### 3.1 页面版式 `tiles`

```json
{
  "layout": "tiles",
  "slots": {
    "tiles": [
      { "widget": "clock.tile",    "size": "wide" },
      { "widget": "weather.tile",  "size": "medium" },
      { "widget": "clock.date",    "size": "medium", "color": "#D24726" },
      { "widget": "gallery.tile",  "size": "small" }
    ]
  }
}
```

- `size` 必填：`small` 1×1、`medium` 2×2、`wide` 4×2、`large` 4×4（列×行）。
- `color` 可选：页面对这张磁贴的颜色覆盖，见 §3.2。
- `tiles` 数组 1–16 项。尺寸放在页面的引用上，因为排版是页面的事，同一张卡片可以在不同页面用不同尺寸。

### 3.2 卡片模板 `tile`

```json
{
  "template": "tile",
  "color": "{{tile_color | default \"#2D89EF\"}}",
  "faces": [
    { "icon": "{{icon}}", "value": "{{temp | round}}°", "label": "{{city}}", "detail": "{{desc}}" },
    { "icon": "calendar", "value": "{{high | round}}°", "label": "@today_high" }
  ],
  "badge": "{{alerts}}",
  "content": { "type": "chart", "values": "{{hourly}}", "style": "area" }
}
```

- `color` 可选：`#RRGGBB` 或一个 `{{绑定}}`，所以既能写死，也能由 Lua 动态改（比如按天气变色），或绑定 `{{config.color}}` 交给用户设置。只接受不透明色。
- 颜色优先级：页面引用的 `color` > 卡片的 `color` > 系统 `palette.accent`。绑定结果不是合法色值时，退到下一级并记 `warn` 日志，不报错。
- 文字、图标和 `content` 的前景色由 OS 按底色自动选黑或白，插件不能指定，所以任何底色都能读。
- `faces` 1–4 项，每面 `label` 必填，`icon` / `value` / `detail` 可选。
- 绑定不支持条件，所以约定 `value` 和 `detail` 渲染为空字符串的面运行时跳过，Lua 把值置空即可隐藏一面。
- `badge` 可选，渲染为空时不显示。
- `content` 可选，只接受 `clock` / `progress` / `chart` / `daylight` / `moon`，只在 `wide` 和 `large` 显示。不开放 `list`、`image`、`carousel`，它们有滚动手势或大图预算问题。

各尺寸显示什么由 OS 固定：

| 尺寸 | 内容 |
|---|---|
| `small` | `icon`，无图标时显示 `value`；角标 |
| `medium` | `icon`、`value`、`label`；角标 |
| `wide` | 左侧 `icon`、`value`、`label`；右侧 `content`，没有则显示 `detail` |
| `large` | 上部 `content`，下部 `icon`、`value`、`label`、`detail` |

点击打开 manifest 的 `opens`，长按进入所属插件设置，和 `stacks` 里的卡片一致。

兼容规则：`tile` 模板放进 `stacks` 时按 `large` 渲染；`glance` 放进 `tiles` 时显示占位并记日志，不尝试缩放，否则 72sp 文字在小格里必然溢出。缺失的引用显示为空磁贴，沿用 `resolveStackItem` 的做法。

### 3.3 图标

新增 `music`、`photo`、`bell`、`settings`、`home`、`folder`、`list`、`star`、`heart`、`message`、`play`、`battery`。`icon.name` 是普通字符串，不用改 schema，但旧 API 的插件用到新名称时，在旧 APK 上显示占位符。

### 3.4 系统设置

计时和开关按 ADR-017 走 `shared/schema/settings/system.json`：

| key | 类型 | 默认 |
|---|---|---|
| `tiles.live` | `bool` | `true` |
| `tiles.interval` | `number`，5–60 秒，步长 1 | 8 |

## 4. OS 实现（提议）

**网格**：`RenderGeometry.tiles(sizes, width, height, density, rtl)` 是纯函数，放 JVM 测试。横屏 6×4，竖屏 4×6，外边距 20dp，间距 8dp。每个基础单元取两轴可用尺寸的较小值，保持正方形，在本机约 107×107dp，都大于 48dp 的最小点击区。

**排布**：按声明顺序做首次适配，从左上起逐行找第一个放得下的位置；RTL 水平镜像。放不下的磁贴不显示并记 `warn` 日志，不缩小也不滚动。比如横屏下 `large` 加 `wide` 就放不下（6 列放不下 4+4），文档要写清这一点，并在 `package_plugins.py` 里对两个方向都放不下的情况给警告。

**渲染**：

- `LayoutVocabulary` 增加槽位角色 `tiles`；`PageView.update` 增加第三条分支，`openAt` / `ownerAt` / `interactiveAt` / `tick` / `bind` / `colors` 都加上磁贴集合。
- 新文件 `ui/TileView.kt`：不复用 `ComponentGroup` 的卡片外观（圆角、内边距、`display` 字号都不适用），只用它承载 `content`（`card=false, compact=true`）。文本走 `Binding.compile`，与现有组件一致。
- 新增 UI token（`04-ui-spec.md`）：磁贴直角 0dp、内边距 12dp、网格间距 8dp。

**颜色**：`TileColor` 是纯函数，放 JVM 测试：`parse("#RRGGBB")` 解析和拒绝非法值，`foreground(color)` 取黑白中对比度较高者。这样任意底色的对比度都 ≥ 4.5:1（最差情况约 4.58:1）。`TileView` 由底色和前景色派生一份 `Palette`（`surface` = 底色，`primary` / `secondary` / `accent` = 前景色），传给 `IconView` 和 `content`，这些组件不用改。底色不随浅色/深色主题变化；未设置颜色的磁贴跟随主题的 `accent`，切换时只插值不重建 View。绑定值变化时底色用 `Motion.NUMBER` 过渡。夜间由根层滤镜统一处理，不单独适配。

**翻面**：页面只有一个调度器，挂在现有 `tick` 上，同一时刻最多一张磁贴在动。只在页面 active 且 `AWAKE` / `ENGAGED` 时运行；减少动画时直接切换不做动画。动画只改 `translationY` 和 `alpha`，动画期间用硬件层，结束后撤掉。时长常量放 `anim/Motion.kt`。

**性能**：不给每张磁贴常开硬件层；`IconView` 目前自带硬件层，单个约 78×78px，16 张可接受；但天气图标在 AWAKE 时逐帧 invalidate，多张同屏要实测（T5）。

## 5. 契约改动

| 文件 | 改动 |
|---|---|
| `shared/schema/plugin/layouts.json` | 新增 `tiles`、`tileReference`、`tile`、`tileFace`、`tileContent`，`color` 用 pattern `^(#[0-9A-Fa-f]{6}|\{\{[^{}]+\}\})$` 约束；`x-thinkos-api.current` → 4，`layouts.tiles: 4`，**新增 `templates` 表**：`glance: 1`、`tile: 4` |
| `shared/schema/plugin/view.json` | `oneOf` 加 `tiles`、`tile` |
| `shared/schema/plugin/manifest.json` | `api` 枚举加 4 |
| `shared/gen/schema_tools.py`、`kotlin_validator.kt.template`、`gen_go.py` | 三处闸门都检查 `template`；Go 生成 `renderTemplates` |
| 生成物 | 跑 `gen_kotlin.py`、`gen_go.py`，不手改 `PluginSchema.kt` / `plugin_generated.go` |
| `shared/schema/settings/system.json`、`shared/i18n/system/*.json` | §3.4 的两项，11 种语言 |
| `docs/08-decisions.md` | 新 ADR（§6） |
| `docs/06-plugins.md`、`04-ui-spec.md`、`shared/schema/plugin/README.md`、`09-roadmap.md` | 词汇、token、里程碑；删改“插件不能设置颜色”的表述，注明磁贴底色例外 |
| `AGENTS.md` | “不为页面外观预留扩展点”一条注明磁贴底色例外，指向新 ADR |

## 6. 决策记录（写入 ADR）

**编号**：磁贴先做，用 API 4 和 ADR-025，同时把 `22-player-plan.md` 里的提议顺延为 API 5 和 ADR-026。两者都还没落地，顺延只改文档。ADR-023 继续留给 HTTP 通知提案。

**ADR-025 要点**：

- 用户决定（2026-10-31）：磁贴底色由插件定义，修订 ADR-017 第 1 条。
- 范围只限 `tile` 的底色。文字色、字号、圆角、像素和动画仍归 OS，其它版式和组件也不开放颜色。
- 前景色由 OS 自动选，保证可读性。
- 这不引入样式语言或皮肤包：没有主题文件，也没有继承规则，只有一个颜色字段。

## 7. 执行计划

| 步骤 | 内容 | 主要文件 | 完成标准 |
|---|---|---|---|
| **T0 落盘** | 现有 94 项未提交改动（含 weather、gallery 全部代码）先提交，再开分支 `feat/tiles` | git | `git status` 干净 |
| **T1 契约** | 改 schema 和版本表，三处闸门检查 `template`，重新生成两端代码；写 ADR-025，顺延播放器计划，修订 `AGENTS.md` | §5 所列 | A1 |
| **T2 纯逻辑** | `RenderGeometry.tiles`、`TileColor`，带单元测试 | `ui/RenderGeometry.kt`、新 `ui/TileColor.kt` | A2 |
| **T3 渲染** | `TileView`、`PageView` 第三条分支、手势、`tile` 进 `stacks`、`glance` 占位、12 个图标；确认新页面在 deck 中的位置；写 `tile-probe` 和 `tile_ui.py` | `ui/TileView.kt`、`ui/PageView.kt`、`ui/IconView.kt`、`tools/tests/` | A3、A4、A5 |
| **T4 翻面与设置** | 单一调度器、`tiles.live`、`tiles.interval`、空面跳过、11 种语言词条 | `ui/PageView.kt`、`anim/Motion.kt`、`shared/schema/settings/system.json`、`shared/i18n/system/` | A6 |
| **T5 插件** | 新建 `plugins/tiles`（`api: 4`，纯声明）；给 `clock`、`weather`、`gallery` 各加 `tile` 卡片和颜色并升版本。`music` 未实现，不做 | `plugins/` | A7 |
| **T6 整机验收** | 跑全部验收，结果记入 `docs/24-tiles-validation.md`，同步 `README.md` 和 `09-roadmap.md` | `docs/` | A8、A9 |

依赖关系：T1 → T2 → T3 → T4 → T5 → T6，每步通过后再进下一步。规模：T3 最大，T4、T5 中等，T1、T2 较小。

## 8. 验收方案

所有设备操作都不使用 `adb root`、`disable-verity`、`remount`，也不写 `/system`。

### A1 契约

命令：`python3 shared/gen/gen_kotlin.py && python3 shared/gen/gen_go.py`、`python3 tools/package_plugins.py --validate-only`、`python3 -m unittest discover -s tools/tests -v`、`cd server && go test ./internal/protocol/`、`cd android && ./gradlew testDebugUnitTest`。

- 全部通过，重复运行生成脚本后生成物无差异。
- API 4 接受 `tiles`、`tile`；API 3 使用它们时，Python、Kotlin、Go 三处都拒绝。
- `color` 接受 `#2D89EF` 和 `{{c}}`；拒绝 `red`、`#FFF`、`#2D89EF80`。
- `content` 放 `list` / `image` / `carousel`、`size` 用未知值，都被拒绝。

### A2 纯逻辑（JVM）

- 排布：四种尺寸、横竖屏、RTL 镜像、宽高为 0；同样输入结果相同；磁贴互不重叠且不出界。
- 横屏 `large` + `wide` 时 `wide` 被丢弃，竖屏同样输入两者都放得下。
- 颜色：非法输入返回空；优先级为页面 > 卡片 > 系统；纯黑、纯白、中灰和全部 4096 个采样色的前景对比度都 ≥ 4.5:1。

### A3 视觉（真机截图）

`tools/tests/tile_ui.py` 输出到 `artifacts/ui-validation/tiles/`，人工看图。矩阵：横屏/竖屏 × 浅色/深色/夜间，共 6 组。

- 网格对齐，四种尺寸的内容符合 §3.2 的表，文字不溢出。
- 深色底配白字、浅色底配黑字；`#FFFFFF`、`#000000`、`#808080` 都能读。
- `zh-Hans`、`en`、`ar`（RTL）、`hi` 各截一组，文字不被裁切。
- 夜间模式下彩色磁贴仍可读。根层滤镜对彩色底的效果未实测，不达标时单独处理。

### A4 颜色行为（真机）

- 写死、页面覆盖、绑定三种来源都生效。
- Lua 改绑定后，底色平滑过渡且不重建 View。
- 绑定非法值时退到下一级，日志里有 `warn`，插件不崩。
- `{{config.color}}` 在设置页修改后立即生效。

### A5 手势（真机）

- 点击进入 `opens` 页面，无 `opens` 时无反应。
- 长按进入磁贴所属插件的设置，不是 `tiles` 插件的设置。
- 横向滑动翻页不受影响；有变体时纵向滑动能切换。

### A6 翻面（真机）

- 同一时刻最多一张在动，间隔符合 `tiles.interval`。
- 离页或进入 `AMBIENT` 后停止，回来后恢复。
- `tiles.live` 关闭立即生效；减少动画时直接切换。
- 空面被跳过，全空时显示 `label`。

### A7 插件

- `tools/plugin-push.sh plugins/tiles` 热加载成功，日志无错误。
- 卸载 `weather` 后其磁贴显示为空磁贴，页面不崩，重装后恢复。
- `glance` 放进 `tiles` 显示占位。

### A8 性能与稳定（真机，翻面开启）

| 指标 | 通过标准 | 方法 |
|---|---|---|
| 冷启动 | < 3s，断网、无 Server | `tools/tests/pre_m1_cold_start.py` |
| 掉帧 | 连续 10 分钟无掉帧告警 | `dumpsys gfxinfo`、`tools/bench.sh` |
| 单帧绘制 | ≤ 12ms | `gfxinfo` |
| 内存峰值 | < 350MB | `dumpsys meminfo` |
| Lua 内存 | 合计 < 24MiB | 维护页诊断 |

其中 16 张磁贴同屏、多个天气图标动画的场景单独跑一轮。

### A9 回归

- `./gradlew assembleDebug` 通过，`adb install -r` 成功，APK 只含 `armeabi-v7a` 的 so。
- `home`、`clock`、`weather`、`gallery`、`settings` 页面无变化：跑 `component_ui.py`、`weather_ui.py`、`settings_ui.py`。
- API 1–3 插件照常安装运行。
- 断网后磁贴保留最后数据（`weather` 已有缓存，其它插件在 T5 确认）。

## 9. 风险

- **小格文字**：ar、hi、bn 等语言字形较高，`medium` 可能挤。必要时固定单行省略（A3 检查）。
- **放不下时丢磁贴**：运行时只有日志，靠打包器警告兜底，第三方页面仍可能踩到。
- **颜色不随主题变**：插件写死亮色时，深色环境下会比较刺眼。这是用户选择插件定色的代价，OS 不做修正；夜间仍有根层滤镜。
- **风格统一**：磁贴的直角和饱和色与现有 32dp 卡片差异明显，所以只出现在 `tiles` 页面，不改首页。
- **`relevance`**：`tile` 模板不带这个字段。它在现有卡片上也未实现，本计划不补。

## 10. 待用户确认

1. **T0 已执行**：用户授权落盘，基线提交 `31ae3dc`，分支 `feat/tiles`。
2. **优先级已执行**：磁贴 API 4 / ADR-025，播放器顺延 API 5 / ADR-026。

## 11. 开发中确认的修订

- 用户明确要求 Windows Phone / Windows 8.1 磁贴语言：直角 0dp，不使用 8dp 圆角；基础网格正方形、底部标签、大号轻字重数字、平面色块。
- 无线调试设备为 `192.168.3.167:5555`；离线测试保留 ADB 局域网，阻断应用外网且无 Server，不禁用 Wi-Fi。
- 真机验证夜间中灰底无法在原红色矩阵下保持可读，已增加仅夜间生效的最小亮/暗端对比度补偿，并在 JVM 检查合成后 ≥4.5:1。
