# 26 · 插件公开数据：设计取舍与真机验收

接口以 [`06-plugins.md` §6.8](06-plugins.md) 为准，决策见 ADR-027。本文记录为什么这样做，以及验证了什么。

设备：Lenovo ThinkSmart View，Android 8.1 / API 27 / armeabi-v7a，无线 ADB。开发分支 `feat/tiles`，2026-10-31。

## 对应 Home Assistant 的概念

| Home Assistant | ThinkOS |
|---|---|
| Integration | 插件 |
| Entity | `exports` 导出的状态路径 |
| Service | `public` 指令 |
| Lovelace 卡片 | widget，配合 `alternates` |

实体注册表、区域、全局事件总线和自动化引擎都没有引入（ADR-006）。

## 关键取舍

- **`uses` 放在 manifest，不放在视图**：视图和 Lua 共用一份声明，依赖关系可以统一检查和诊断。运行时像注入 `config` 一样注入 `uses`，绑定引擎和 UI 差量更新不需要改。
- **默认私有，不开放整份状态**：状态快照含设置值（包括 `secret`），也可能含来自 `location` 等权限的数据。`exports` 是白名单，路径不能进入 `config/uses/variant`，也不能互相重叠，否则内层的 `requires` 会被绕过。
- **`requires` 防止权限转授**：只比较 manifest 声明；`system.settings` 不接受作为 `requires`，因为只声明它并不等于被授予。
- **图片按提供方授权**：使用方否则要申请 `library`/`network`，而 `asset:` 还会解析到它自己的目录。插件碰不到图片字节，所以这样不会多暴露内容。只有整个值都是绑定时才适用，拼接地址（如 `https://me/{{uses.x}}`）按使用方权限检查。
- **`shape` 只比较字符串**：OS 不校验结构，以免变成 schema 系统。不兼容的改动升主版本，使用方断开而不是读错。
- **比较内容而不是文本**：Lua 表的键序在不同实例间可能不同，所以先比文本，再做键序无关的结构比较。

## 防护

| 风险 | 处理 |
|---|---|
| 提供方的值让使用方抛错 | `tos.data.get` 把解码失败转成 `nil, "invalid"` |
| 提供方的值撑爆使用方 Lua 内存 | 读取 ≤64KiB 且 ≤4096 个数组/对象，超出返回 `too_large` |
| 高频更新拖累主线程 | 每个使用方最多约每 250ms 重发一次快照，`data` 事件每个 alias 每秒最多一次 |
| 互相订阅形成循环 | 值不变不通知，加上上面的限速 |
| 指令被滥用 | `cooldown` 按指令计，不分调用方；`gallery.refresh`、`weather.refresh` 为 300 秒 |
| 提供方重启或升级 | 保留已发布的值和冷却状态；图片组件跟随授权变化，不依赖状态变化 |

## 已知限制

- 声明式视图不能直接调用指令，点击要先经过自己的 Lua。
- `gallery.daily` 跟随 `source` 设置：`source=local` 时不拉取 Bing，内容为空。
- 使用方升级并把 alias 改指向另一个提供方时，约 50ms 内旧值可能按新提供方的授权加载。结果只显示在屏幕上，Lua 拿不到字节，仅在插件升级瞬间出现，因此未处理。
- OS 不校验 `shape` 的实际结构，提供方不守约时使用方只会读到空值或错值。

## 验证

```bash
python3 -m unittest discover -s tools/tests
(cd server && go test ./...)
(cd android && ./gradlew testDebugUnitTest assembleDebug)
lua tools/tests/gallery_test.lua plugins/gallery/main.lua
python3 -I tools/tests/public_data_device.py --serial <设备>
```

`public_data_device.py` 使用三个夹具：`public-reader`（无权限、无 Lua 的页面）、`public-script`（Lua 使用方）、`public-provider`（恶意提供方）。结束后移除夹具并恢复设备状态。

## 结果（最终 APK）

| 项 | 结果 |
|---|---|
| Android 单元测试 | 通过 |
| Python 包校验测试 / Go 协议测试 | 通过 |
| 相册 Lua 测试（宿主）、天气原生 Lua 测试（真机，修复前的版本） | 通过 |
| `public_data_device.py` | 13 项全部通过，记录在 `artifacts/public-data/device/` |
| 现有回归 `tile_ui.py`（基础、`--plugins`、`--live`、`--aero` 分别运行）、`tile_regression.py` | 通过 |
| 断网冷启动 | 首帧 846ms；首页内容就绪 2793ms；含 adb 开销的外部观测 3328ms |
| 相册页 600 秒（AWAKE / DARK） | 14199 帧，janky 95（0.67%），最长 UI 绘制 7.7ms，完整帧最大 32.2ms，PSS 峰值 117.6MiB，跳帧告警 0 |
| APK ABI | 只有 `armeabi-v7a`，无新增依赖 |

口径与 `24-tiles-validation.md` 相同：janky <5%，不是零 janky。

**未覆盖**：

- 性能只采样了 `gallery.slideshow`，未单独采样使用方页面；使用方用的是同一个 `carousel` 组件。
- 高频提供方下的主线程开销未实测，250ms 限速的效果是估算。
- 性能采样有两轮因屏幕上出现外部触摸（`touches` 非 0，脚本未注入输入）而中止，已舍弃；上表是一轮完整有效的结果（`touches=0`、进程未变）。

## 代码审查发现的问题

实现后做了一次独立审查，以下问题已修复，并加了测试：

- 提供方重启后使用方图片卡住（`Picture` 现在在 tick 时跟随授权变化，插件自己的图片同样受益）
- 深层嵌套或超大的导出值让使用方抛错或内存超限
- `uses` 保留键影响了 API 1–5 插件；未声明的 alias 现在按插件自身状态处理
- 升级时丢失已发布的值和指令冷却
- 启动中的候选版本把未完成的状态发布给使用方
- 重叠的导出路径绕过 `requires`；`system.settings` 可被用作 `requires`
- 被隔离的使用方仍能借用提供方授权；启动失败的提供方在诊断里显示正常
- 槽位点击穿透叠层、页面自身 widget 的 `opens` 失效

## 补记：图片回到页面后变黑（2026-10-31）

`tile_ui.py` 的 `--plugins --aero` 只执行 Aero 场景，所以上面最初写的“磁贴回归通过”并不准确；分开运行后才发现 `--plugins` 因 `publishState` 改签名而失败，已修复。

用户随后发现相册磁贴全黑。根因：海报缓存在 `onSizeChanged`（布局过程中）同步添加图片层，新层未经测量就被按 0×0 摆放，且不会再次布局，所以首次进入正常，离开再回来就黑。磁贴、相册页和 Aero 都受影响；修复在 `ImageComponents.addLayer`。

`tools/tests/tile_photo_device.py` 检查切页返回、AMBIENT、睡眠唤醒、横竖屏和主题切换后，每个图片层都铺满所属图片组件；修复前失败，修复后 16 项通过。

磁贴全幅图片尚未做性能采样。
