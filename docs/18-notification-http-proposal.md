# 18 · 设备 HTTP 通知接收方案（待确认）

这是交接 §4-F 的候选方案。用户于2026-10-30决定本轮暂不实现完整功能，仅测试UI；当前不创建监听端口，不登记已确认 ADR。

本轮只通过 debug 构建的 `NotificationPreviewActivity` 使用模拟文字检查横幅、全屏、主题和长文案。预览不接收HTTP，不启用通知服务，不申请新的运行时权限，也不改变插件的数据接口。

## 使用方式

`notify` 插件提供两种可选来源：直接接收局域网 HTTP 推送，或从 ntfy 兼容地址轮询。两者都不需要 ThinkOS Server。默认关闭网络监听；用户在插件设置中启用并填写 Bearer token 后才监听。轮询也默认关闭，由用户配置地址和刷新间隔。

设备端配置全部声明在插件设置 schema：`listen_enabled`（默认 false）、`listen_port`（默认 8768）、`listen_token`（secret，默认空，至少32字符后才能启用）、`poll_enabled`、`poll_url`、`poll_interval`、通知展示时长和免打扰。端口冲突或授权字段未填写时在设置/插件诊断显示原因，不退出应用。

用户从同一局域网向 `http://设备IP:8768/notify` 发送 POST，使用 `Authorization: Bearer <token>`。消息为 JSON，包含 `title`、`text`，以及可选 `icon`、`level`（normal/urgent）、`duration`。字段、长度和类型在实现前加入 `shared/` 的 schema；推送示例和校验器从同一契约生成。请求被接受后返回 202；格式不符返回 400，认证失败返回 401，资源超限返回 413/429。响应不回显 token 或请求体。

## OS 与插件边界

OS 提供带 `listen` 权限的 `tos.http.listen{port,token}` 和停止监听能力；生命周期跟随插件，替换/移除/失败时关闭旧实例监听。请求按 `http.request` 事件送入 Lua；`notify` 插件只做解析、去重、免打扰与 `tos.alert` 调用。OS 负责 HTTP 解析、认证、限制和屏幕通知层；不增加自带插件私有通道。

接收端只处理来自私网或回环地址的客户端，拒绝公网对端；Bearer token 在读完整消息前检查，比较不随匹配长度提前返回。只支持有限的 HTTP 请求格式，不支持 chunked/压缩/连接复用；请求体上限16KiB、并发上限4、读取超时5秒、请求头有明确大小上限。工作在专用线程，不占 UI 或 Lua 线程。关闭监听时关闭现有 socket 并取消迟到任务。

通知显示沿用 StandBy 交互：AWAKE/ENGAGED 顶部横幅；AMBIENT/ASLEEP 大图标展示；urgent 可唤醒。此显示层和 ntfy 轮询可先独立实现。

## 验证与确认范围

验证覆盖开关关闭时没有端口、认证/私网/体积/慢请求限制、重复通知、插件替换与端口复用、离线本地推送、睡眠与唤醒展示。不会调整设备系统分区或防火墙。

需要确认的是是否实现上述**默认关闭、显式启用并带 token 的设备局域网监听能力**。选择只做轮询时，OS 不提供设备监听端口，`notify` 仍可独立轮询外部 HTTP 服务。
