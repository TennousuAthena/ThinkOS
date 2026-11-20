# ThinkOS 网页模拟器

独立运行现有插件目录，用于查看声明式界面、调试 Lua、检查 `tos.*` 调用及快速热更新。不需要连接设备或启动 Server，不改 `android/`。目前支持全部 15 个内置插件的 24 个页面，以及 8 种布局、20 种组件和 glance/tile 模板。

**当前是可运行、可做契约对照的开发模拟器；尚未完成“与实体设备完全等价”的验收。** 画布一直标记「视觉待校准」。本轮所有者明确将真机截图、交互和性能验收留待设备可连接后完成。

## 启动

在仓库根目录运行：

```sh
python3 -m simulator serve --profile default
```

手动打开 <http://127.0.0.1:8766>。本轮工作区已构建好可运行产物。一个 profile 同时使用一个开发预览；另开独立实例时使用不同 `--profile` 和 `--port`。

首次从干净检出构建：

```sh
cd simulator
npm ci --ignore-scripts --no-audit --no-fund --cache .local/npm-cache
npm run build:wasm
npm run build
npm run check
npm test
cd ..
python3 -m simulator serve
```

需要 Node.js 24+、Python 3.10+、JDK 17，以及固定 Emscripten 6.0.13 和 Kotlin 2.2.0。依赖准备与来源校验分别见 [WASM 说明](native/README.md)、[Kotlin 引用构建](reference/README.md)。构建脚本不自行安装编译器；缺少依赖会明确停止。npm 安装脚本保持禁用。`npm run build` 校验原生源码锁、准备同版时区数据、汇集许可证，并从原始 Kotlin 文件编译网页几何逻辑。

原生源码漂移必须审阅后显式更新锁，再运行一致性测试。普通构建**不会重建工厂权限基线**；`scripts/build_assets.py --update-factory` 仅用于审阅后的内置发布基线更新，不能当作让编辑后的系统插件恢复特权的快捷方式。

## 插件开发

```sh
python3 -m simulator serve --plugin /absolute/path/to/my-plugin --profile my-plugin
```

继续用本地编辑器修改 `manifest.json`、Lua、视图和资源。网关监测文件变化，复用仓库打包验证器捕获不可变文件快照；浏览器再次核对清单与 SHA-256。新 Lua 实例成功启动后才提交新视图和权限，失败时保留上一版状态与网络资源授权。旧代的定时器、异步回复和媒体所有权会释放。错误见日志面板。

默认加载内置插件。`--no-builtins` 可仅加载指定目录。内置 ID 冲突会报错；编辑仓库内的同一目录即可预览内置插件改动。

`system.*` 权限默认只授予固定工厂摘要。编辑后的内置系统插件需显式使用 `--simulate-system settings`（或工作台的系统插件模拟授权）；只接受固定工厂 ID。普通第三方插件即使声明系统权限也不会获得它。模拟授权仅影响这个 profile。

左侧选择页面、方向、主题、字号、语言和网络模式；右侧查看状态、日志、API 调用。画布提供点击、拖动、滑动、长按与返回。截图由 CanvasKit 自身编码；场景 JSON 可导入/导出。导入会替换整份虚拟环境，未提供的字段恢复 schema 默认值，清除旧场景的虚拟时间等 fixture；设置覆盖和插件 store 保留。场景环境以 [协议 schema](../shared/protocol/schema/simulator/development.json) 为准，可设置时间、地点、注意力、存在状态、模式、HTTP/媒体/更新和应用 fixtures。操作结果通过 `operationResults` 显式配置；不会安装系统模块、更新设备或启动 Mac 应用。

设置及插件 store 只写 `simulator/.local/profiles/`，不同 profile 隔离，写入有上限且原子替换。设置使用原 system/plugin schema，并处理依赖、动态选项、复数、地点、时区、复合约束与重置；不能另建插件私有能力通道。

地区、时区和地点使用专用选择器：地区代码与同版时区库存一致，时区可按洲或名称搜索；地点支持在线搜索、离线城市、跟随设备和手动坐标。OS 地点搜索经网关，只取系统设置 `location.search_url`；固定场景模式直接用离线列表，真实联网失败时也回退离线。IANA 城市名仍采用名称 fallback，与 Android ICU 的本地化名称可能不同。

## 网络、媒体与字体

HTTP/DNS、图片和媒体经同源 Python 网关，检查会话、来源、插件摘要、权限、地址和响应上限。媒体跨源时剥除凭据，并使用不包含会话密钥的资源票据；`tos.http` 的重定向头处理保持原 Android `PluginHttp` 语义。网页不直接绕开网关访问插件资源。真实联网模式会产生插件原本声明的网络请求，固定场景模式只返回显式 HTTP fixture。

`/sdcard/Music`、`/sdcard/Movies`、`/sdcard/Pictures` 及应用私有外部目录，映射到当前 profile 的 `library/Music`、`Movies`、`Pictures`。把自有测试文件放入这些本地目录即可。额外挂载须通过 `--config` 的 `library_mounts` 显式声明；不会把任意 Mac 路径开放给插件，不跟随符号链接。目录扫描不执行文件。

HTMLMediaElement + Shaka 支持当前浏览器可解码的本地/HTTP 音视频、HLS/DASH、队列、字幕轨和音轨。浏览器要求用户手势才能出声，点击「启用声音」。逻辑睡眠暂停媒体，拒绝新播放，唤醒不自动续播；这不证明设备电源或音量恢复行为。RTSP、Android content URI、硬件投屏和解码器差异用 fixtures 检查对应插件状态。媒体库枚举的可选 duration/width/height/rotation 元数据尚未与 Android 媒体扫描器对齐，实播元数据来自浏览器。

字体从所有者已有的本地 system.img 只读提取，不下载 ROM：

```sh
python3 simulator/scripts/extract_fonts.py --image /path/to/local/system.img
```

此可选工具使用已有 FontTools，将 Android fonts.xml 指定的简繁中文 TTC 索引分别提取，并保留阿拉伯语、天城文、孟加拉文及 Roboto 字体。字体与产物不入库，清单记录摘要；换字体后重启网关。缺字体时界面诊断会明确提示。当前本地 ROM 字体尚未与本轮在线设备核对。

## 验证与剩余边界

```sh
python3 -I -m unittest discover -s simulator/tests -p 'test_*.py'
node simulator/native/wasm-conformance.mjs
node simulator/tests/runtime-conformance.mjs
node simulator/tests/media-browser.mjs
node simulator/tests/media-transactions-browser.mjs
node simulator/tests/hotreload-browser.mjs
node simulator/tests/render-interactions.mjs
node simulator/tests/render-pickers-browser.mjs
node simulator/tests/render-pickers-workbench.mjs
node simulator/tests/navigation-browser.mjs
node simulator/tests/scenario-browser.mjs
node simulator/scripts/browser_check.mjs
python3 simulator/scripts/gen_protocol.py --check
python3 -I -m unittest discover -s tools/tests
```

浏览器测试只启动现有 Chromium 的无界面进程，不操作 Mac 窗口。可用 `THINKOS_TEST_CHROME` 指向已安装浏览器；页面测试用 `THINKOS_SIM_URL` 指向本地服务。报告和截图保存在 `.local/`，测试媒体自己生成，不从外网下载。

| 范围 | 当前证据与边界 |
|---|---|
| Lua、JSON、沙箱及配额 | 原 Lua/yyjson/桥接 C 直接编译 WASM；测试覆盖 int64、Double 子类型、负零、Unicode、null/空容器、禁止库、模式/指令/内存预算、异步恢复与释放。浏览器没有线程 CPU 计时，明确标为不可用。 |
| 布局与导航 | 4 个原 Kotlin 文件编译 JVM/JS，63 组独立对照向量；浏览器遍历全部页面并检查绘制错误。导航保护设置/引导，按目标释放页面，并处理后台请求、接管优先级和原生返回栈。编辑器使用浏览器 DOM，控制面板仍是模拟页面层；完整 Android View 排版、原生叠层、动画与命中区仍须真机对照。 |
| 天文与时区 | 原独立太阳/月亮/节气向量通过；使用相同 IANA 2026e TZif，并与 Python ZoneInfo 交叉验证。 |
| 语言与字体 | 11 种插件语言、原生复数规则与同源字体；浏览器 CLDR 命名格式、ICU 城市名 fallback、Skia 栅格与字体抗锯齿仍有差异。 |
| 系统能力 | 更新、registry、Android 应用、Wi-Fi、电源、投屏、引导使用受限虚拟状态/明确操作结果；不会产生真实设备副作用。原生系统弹窗仅提供模拟控件。 |
| 浏览器和真机 | 当前已验证现装 Chromium；Google Chrome/Edge 品牌版本、实体设备安装/截图/离线冷启动/连续待机性能尚未验收。 |

这些对照用例能够发现实现漂移，不能替代实机性能与逐像素验收。不得把未校准的浏览器截图登记为设备黄金图。

本轮本地验收记录在 [qualification.json](../artifacts/simulator/web-preview/qualification.json)，真机步骤见 [验收待办](../artifacts/simulator/web-preview/device-acceptance.md)。记录与截图属于本机验收产物，不是设备黄金图。
