# 只读 Kotlin 引用与对照向量

网页实际执行由以下四个 Android 源文件编译的 Kotlin/JS 模块；没有复制、移动或修改设备端文件，也没有修改 Android Gradle 配置。

| 原文件（相对 `android/app/src/main/java/moe/kiwi/thinkos/`） | 网页用途 |
| --- | --- |
| `ui/RenderGeometry.kt` | focus/split/stage 槽位、磁贴装箱、纵向高度分配 |
| `ui/PanelGeometry.kt` | 控制面板横竖屏槽位 |
| `ui/TileColor.kt` | 磁贴前景对比色与夜间配色 |
| `core/NavStack.kt` | 返回栈及 variant 保存，导出 `NativeNav` |

`Facade.kt` 仅转换输入输出。`Exports.kt` 是 JS 导出壳；`ReferenceMain.kt` 是独立 JVM 对照入口。两者编译同一组原文件。构建前后校验原文件摘要，防止在构建过程中遇到版本变化。

## 构建

要求 Python 3、JDK 17、Kotlin 2.2.0 编译器。脚本只读现有 Gradle 缓存，不运行 Android 构建、不下载依赖、不修改该缓存。所需缓存坐标：

- `org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.0`
- `org.jetbrains.kotlin:kotlin-stdlib:2.2.0`
- `org.jetbrains.kotlin:kotlin-script-runtime:2.2.0`
- `org.jetbrains.kotlin:kotlin-reflect:1.6.10`
- `org.jetbrains.intellij.deps:trove4j:1.0.20200330`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2`
- `org.jetbrains:annotations:23.0.0`

另将官方 [Kotlin/JS 标准库 2.2.0](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-js/2.2.0/kotlin-stdlib-js-2.2.0.klib) 放在 `reference/.cache/kotlin-stdlib-js-2.2.0.klib`。构建强制校验 SHA-256：

```
d68bcaa6ca7d6b69ed3a263323811eadb46ecebc4d6b3cd89eb3a0ad971686b9
```

从 `simulator/` 执行：

```sh
python3 reference/build.py
npm run check
npm run build
node --experimental-strip-types --test tests/render-conformance.test.ts
```

构建生成 `reference/generated/thinkos-reference.mjs`、TypeScript 声明和 JVM runner；生成目录与依赖缓存不入库。首次构建缺依赖时会明确报错，不能直接跳过引用构建。Kotlin/JS 标准库随生成模块进入浏览器，按 Apache-2.0 许可；编译器及其 JVM 依赖只用于开发构建。

## 对照与边界

`vectors.json` 是独立 JVM runner 的 63 组输出，覆盖不同尺寸/密度/RTL 的槽位和磁贴、溢出、负空间、权重、面板可选区块及颜色。Node 测试执行浏览器相同的 Kotlin/JS 导出，逐项与 JVM 输出比较，同时校验四个原文件的 SHA-256。

修改原逻辑后，先审阅变化，再明确重建向量：

```sh
python3 reference/build.py --jvm-only
python3 reference/generate_vectors.py
```

向量证明这些纯逻辑结果在 JVM/JS 间一致，不证明整机视觉或行为等价。文字整形、字重选择、CanvasKit 与 Android Skia 版本、动画/投影、Aero 窗口、表单编辑器、主题合成与媒体输出尚未完成真机校准。当前图形实现保留 `uncalibrated` 状态；不能用浏览器帧率替代设备性能验收，也不能冻结硬件时钟或从浏览器复现设备卡死。

`tests/render-browser.mjs` 在独立无窗口 Chromium 中遍历实际页面，检查绘制异常并保存代表页截图。该检查需要已启动的模拟器，默认 `http://127.0.0.1:8766`，可用 `THINKOS_SIM_URL` 指定。截图仍需人工逐张查看；非空像素不能证明排版正确，更不能替代连接实体设备后的成对截屏与行为验收。

地区、时区、地点编辑器的交互规则独立移植自原生 `ui/form` 和 `location/PlaceSearch`，未编译 Android View。地区库存、时区偏移/DST 与离线城市经纬度使用同版 `shared/tzdata`；地区名采用浏览器 `Intl.DisplayNames`，城市名仍使用 IANA ID 末段回退，因此本地化城市检索与 Android ICU 存在已知差异。编辑器为浏览器 DOM 弹窗，尚未做真机视觉校准。

地点搜索仅通过宿主提供的 OS `searchPlaces` 回调进入 `/api/place-search`，地址由系统 `location.search_url` 决定；插件自身仍没有私有网络通道。搜索失败或固定场景模式回退到最多 20 个内置时区城市，成功但空结果保持空列表。手动坐标必须显式有效，选择设备时区仅提交 `{mode:"auto"}`；关闭、切页后晚到的搜索响应不会提交或重开弹窗。网关请求会受网关时限约束，浏览器关闭弹窗不保证已经发出的 HTTP 请求立即终止。

`tests/render-pickers.test.ts` 验证数据边界；`tests/render-pickers-browser.mjs` 在测试页上验证真实 CanvasKit 命中、选择/搜索、错误回退、草稿校验、导航清理与 RTL；`tests/render-pickers-workbench.mjs` 使用独立临时 profile 验证完整工作台、原插件 Lua、OS 网关和设置持久化。两项浏览器测试只启动无窗口 Chromium，不操作桌面窗口，截图与报告保存于 `simulator/.local/`。

导航层继续只持有一个原生 `NativeNav` 实例；JS 外壳导出 `has/remove/removeKind/unwindTo/replaceTop/prune` 等原栈操作。浏览器 `navigation.ts` 对照 `DeckRouter.requestFocus` 实现设置/引导保护、休眠与本地操作 30 秒门槛、live 优先级、替换接管层和按目标释放；应用与系统引导入口单独处理。`tests/navigation.test.ts` 验证门槛和原栈结果，`tests/navigation-browser.mjs` 将 Runtime 格式的消息注入实际工作台接收器，验证接线。它们不证明 Android 转场、系统 Back 响应时序或控制面板原生覆盖层像素一致；这些仍需真机验收。
