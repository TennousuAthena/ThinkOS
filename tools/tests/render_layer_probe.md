# RenderThread 单图片功能探测

仅开发脚本，不改 APK 或正式插件，不安装模块、不重启设备。主 agent 统一执行：

```sh
python3 -I tools/tests/render_layer_device.py --output artifacts/render-layer/pan-pair-01
```

`--output` 必须是不存在的目录。默认两轮，每轮先 `pan=true` 后 `pan=false`，每项实际停止并重新启动 ThinkOS，验证新 PID，固定 DARK、AWAKE、横屏，再从 home 进入唯一的 stage 页面。每次进入观察 6 秒；可用 `--rounds 1..5`、`--observe-seconds 2..20` 缩小范围。观察进程丢失、同 PID 的 native signal/RenderThread 和 Java 崩溃，不读 gfxinfo、不测帧率或时钟性能。一个崩溃后的 false 对照完成就停止，避免持续触发崩溃恢复。

图片默认来自现有 `files/store/gallery.json` 的 Bing 元数据，原 URL 必须已有 `cache/images/SHA256(URL)`。可用 `--cached-url 'https://…'` 指定已缓存的原 URL，或同时给 `--cache-key HEX64` 核对。两个临时开发插件通过公开 `exports/uses` 借用一张图片，提供者没有页面、消费者仅一个 stage；两项声明仅 pan 字面布尔值不同。宿主脚本临时加带本次唯一 comment 的应用 UID IPv4/IPv6 阻断规则，缺 root/规则支持就失败关闭，绝不从公网下载图片。

找不到原 URL 元数据时，仅从现有缓存复制不超过 1 MiB 的 JPEG/PNG 作为 fixture asset；也可用单独的 `--cache-key HEX64` 选择。结果会明确写 `source_mode=copied-cache-asset`、`probe_only=true`。没有合适缓存就拒绝，不生成替代图。原 URL 模式保留缓存读取和公开借用路径，但两种模式都无法恢复历史 GPU/context、图片内存驻留和旋转时序，未复现不表示问题已修复。该脚本不把崩溃归因于 CA。

开始前要求 ThinkOS MainActivity 已在前台、当前是空返回栈的基础页，没有面板、practice、可见 welcome、安全模式或任何媒体会话（暂停/已结束队列也拒绝）。只拒绝本脚本两个同名 dev 目录及其 previous/stage，不删除其他开发插件。先保存原设置、deck/recovery 与全部已有插件 store（含 welcome），私有恢复目录位于系统临时目录、权限 0700，快照文件 0600；不会把原始数据写入 artifacts。对照结束逐字节恢复原文件，恢复页面、variant、语言、屏幕方向和进程内调试 holds，核对媒体仍空闲；音量 1–5 始终设为/保持 0，不恢复任何非零音量。

恢复时保留本次应用 UID 的网络阻断，等待启动时的缓存写入结束，逐字节核对快照后再删除阻断规则；联网后的正常缓存刷新不纳入原字节证明。脚本只清理本次拥有且 digest 匹配的两个 dev 目录及自己的带 comment 防火墙规则。恢复失败会非零退出，保留私有 journal 及 `results.json` 中的路径；修复连接后使用相同脚本恢复：

```sh
python3 -I tools/tests/render_layer_device.py --restore /private/tmp/thinkos-render-layer-recovery-EXAMPLE
```

未恢复成功不要删除 journal。SIGINT/SIGTERM 会走恢复；SIGKILL、主机断电、ADB/设备断线无法保证即时恢复，因此执行期间不要使用设备。检测到真实触摸会中止并恢复。本脚本没有操作触摸输入或音量调高的路径。

`results.json` 的 `complete` 只表示这次有界对照与恢复成功；`reproduced` 表示观察到本进程 native 崩溃。实际截图、图层生命周期或根因结论需结合 `logcat.txt`，不要以一次无崩溃推断驱动安全。
