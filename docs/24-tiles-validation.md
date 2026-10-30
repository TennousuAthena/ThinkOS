# 24 · 磁贴实现与真机验收

设备：Lenovo ThinkSmart View，Android 8.1 / API 27 / armeabi-v7a。2026-10-31 开始验收；按用户要求使用无线 ADB `192.168.3.167:5555`。基线 `31ae3dc`，开发分支 `feat/tiles`。

## 已确认的设计修订

- Windows Phone / Windows 8.1 Metro 风格：直角、平面纯色、基础单元正方形、底部标签、大号轻字重数字；不分发额外字体。
- API 4 与 ADR-025 用于磁贴；播放器仍是未实现的 API 5 / ADR-026 提案。
- 夜间红色矩阵会使中灰底原有黑白前景不够清楚。现在按合成后的颜色选前景；必要时仅夜间补偿底色到最近的可读亮/暗端。白天浅/深主题保留插件原色。JVM 对 4096 色 × 5 个夜间过渡阶段检查 ≥4.5:1。
- 无线验收不能关闭 Wi-Fi；离线场景需保留 ADB 局域网、阻断应用外网、无 Server。

## 执行状态

| 验收 | 状态 | 证据 |
|---|---|---|
| T0 | 通过 | 既有改动提交 `31ae3dc`，然后创建 feat/tiles；当时 git status 干净 |
| A1 契约 | 通过 | `artifacts/tiles/a1-*`；生成器重复输出一致；Python/Go/JVM 拒绝 API 1–3 使用 tile/tiles；颜色、content、size 的边界测试 |
| A2 纯逻辑 | 通过 | `artifacts/tiles/a2-jvm.log`；随机排布、镜像、不重叠、不越界、溢出丢弃、4096 色对比度；夜间补偿测试见 t3-jvm |
| A3–A5 | 通过 | `tools/tests/tile_ui.py`、`artifacts/ui-validation/tiles/` |
| A6 翻面与设置 | 通过 | `artifacts/ui-validation/tiles-live/report.json`，真机单调度器、间隔、关闭即取消、AMBIENT/离页、恢复、空面与减少动画；223 个 JVM 测试 |
| A7 插件 | 通过 | `artifacts/ui-validation/tiles-plugins/`，目录热加载、跨插件设置、移除与恢复 |
| A8 性能 | 待执行 | |
| A9 回归 | 待执行 | |

Go 目录此前只有生成物和测试，没有 go.mod。已补与仓库约定一致的 `module thinkos-server`，无第三方依赖，原计划命令现在可直接运行。

默认 deck 顺序来自 `PluginRuntime.publishCatalog`：home 在前、sleep 次之、其它页面按 key 排序，settings 不参与横向导航。磁贴页作为普通插件加入，不改变 home 角色。

A7 的天气是 APK 自带插件：删除开发覆盖目录会按既有逻辑回退工厂版本，不会卸载自带插件。因此测试在运行时工作线程模拟 catalog 移除/恢复 weather，走真实的 publishCatalog → PageView.update 路径；另用目录推送验证 tiles 热加载。该测试没有新增插件私有 API，也没有为 Lua 开后门。
