# 42 · 按功能的规则

> 2026-11-04，整理 `AGENTS.md` 时从它的第 89–95 行**原样**搬来（所有者同意），只加了标题，正文没改。动到下面任一功能前，先读对应一节和它引用的文档。
> 这些是对 ADR 的摘要：和 `08-decisions.md` 里的 ADR 冲突时以 ADR 为准，并把这里改对。要改已定结论，走新 ADR 并经所有者同意（`AGENTS.md` “遇到问题”）。
> `32-open-ui.md` 附录 A 拟议修改其中的 §2、§3、§4、§6，在所有者同意之前不生效。

## 1. 插件之间的交互

**插件之间只通过声明交互**（ADR-027）：`exports` / `uses` / `calls` 和公开 widget，见 `docs/06-plugins.md` §6.8；状态默认私有，不要给插件开读取其他插件状态的通道；来自受权限能力的数据要声明 `requires`

## 2. 磁贴动画

**磁贴动画只能按名称请求**（ADR-028）：`tos.tile.animate("pulse" | "flip" | "nudge")`，样式、时长、曲线归 OS；按下倾斜与页面入场自动播放，不要给插件开放参数，也不要绕过 `tiles.live` 与系统动画开关

## 3. 页面外观

**不为页面外观预留扩展点**（ADR-017；ADR-025 仅允许 `tile` 磁贴底色 `color`，前景色由 OS 决定）：不要引入样式语言、表盘包、部件皮肤；需要新的版式或组件，按 `docs/06-plugins.md` §4.1 的扩展流程加词汇

## 4. 媒体

**媒体只通过 `tos.media.*` 与 OS 的 `video` / `controls` / `seekbar` 组件**（ADR-022、ADR-030）：不给插件开私有播放通道；明文地址只允许私网（`media.public_http` 是唯一例外开关）；新增解码库必须含 `armeabi-v7a`，FFmpeg 扩展是 GPL-3.0，完整版 APK 按 GPLv3 发布（ADR-043）所以可以带上，想要不含它的构建时用 `-Pthinkos.ffmpeg=false`；设备测试脚本和排错见 `docs/27-streaming.md`

## 5. 投屏接收

**投屏接收只在 OS 的 `CastHost` 里做，播放一律走 `MediaEngine`**（ADR-031，`docs/28-casting.md`）：不为投屏引入第二个播放器；协议不进 Lua，插件只声明页面；每个协议各有开关（`cast.enabled` DLNA、`cast.airplay`、`cast.miracast`）且默认关，只服务私网来源，推来的地址只收 http / https，拉取仍受 `media.public_http` 约束；监听、解析发送端数据时一律有界、不信任；新增协议必须带畸形输入测试。参考项目 MiracastReceiver 只当零件库（固定提交 `d1aea7e`，MIT，见 `THIRD_PARTY_NOTICES.md`），其 `playfair`（GPLv3）已按原样纳入 `android/app/src/main/cpp/playfair/`（来源与许可证见同目录 `ORIGIN.md`），因为完整版 APK 按 GPLv3 发布（ADR-043）；AirPlay 镜像经 `AirPlayMirror` 重新封装成 TS 交给 `MirrorSource`，不为它引入第二个播放器。Miracast 需要的系统权限由 `system/thinkos-cast` 模块的 root 守护进程代为行使，**应用里不要加 `su`**；镜像画面只经引擎的 `MirrorSource`（只有 OS 能创建，插件不能构造）

## 6. 电视直播

**电视直播只通过 `iptv` 插件与 OS 的 `tv` 组件**（ADR-038，`docs/31-iptv.md`）：播放仍是 `MediaEngine`，不为电视引入第二个播放器；`tv` 的按钮与手势只发 `tap(名字, 动作)`，动作只有 `prev` `next` `list` `line` `favorite`，不为电视界面开外观参数；测一条线路用 `tos.media.probe`（走播放的网络规则），不要用 `tos.http` 去探媒体地址；列表源、节目单提供方各是一个可增加的 Lua 模块，不把某种格式写进 `main.lua`；不内置频道本身；默认只预置用户指定的一个列表地址（`manifest.json` 里 `source_1` 的默认值，用户可以清空或改掉，ADR-038）；默认节目单只有央视网自己的央视节目表（`epg_url` 的默认值，`guides/cntv.lua`，只有央视频道）；自动测试只用本地测试台（`tools/tests/iptv_device.py`），不依赖公网列表；判断一个外部来源设备连不连得上，要在设备上试（`adb shell 'nc -w 5 -z 主机 443'`），Mac 是经代理上网的，它能连不说明设备能连（`docs/31-iptv.md` §10）

## 7. 应用与返回

**应用与返回**（ADR-040、ADR-046，`docs/33-apps-and-back.md`、`docs/38-global-gestures.md`）：应用是 manifest `apps[]` 指向的页面，全屏、不进翻页序列、页面不能带角色，左右滑归应用自己，**一律用返回退出**（最左缘右滑、Back 键）；ADR-046 规定全系统物理左缘返回、底缘上滑回 `home` 角色首页并清空返回栈、顶缘下拉 ThinkOS 控制栏，同时屏蔽安卓通知/快捷设置与系统导航栏，取代 ADR-045 的 launcher/原页面返回；公开 launcher 目标仍是应用磁贴页。不要用横滑退出应用、接管页或设置页；导航状态只放在 `NavStack`，不要在 `DeckRouter` 里另加返回字段；ThinkOS 基础页最左缘只在有可返回内容时接管，翻页序列不能留死区；要自己处理返回的应用只用 `tos.back.intercept` 和 `back` 事件，不开新手势，也不能困住用户（2 秒兜底）；打开 / 关闭应用的“翻转并启动”动画归 OS（`AppTransition`），插件只能打开或关闭，不能要别的姿势，也不能绕过系统动画开关。全局系统钩子归 system/，应用和插件没有输入注入通道
