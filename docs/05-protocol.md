# 05 · 通讯协议（设备 ↔ Server）

> 设备是自主运行的 OS，Server 是下发与管理装置（ADR-015）。
> 本协议只承载**管理**（同步、回报）与**可选的实时推送**；设备的画面内容不经过网络。
> 机器可读版本以 `shared/protocol/schema/` 为准，两端数据类由脚本生成。

## 1. 总览

| 通道 | 传输 | 方向 | 用途 | 断开时 |
|---|---|---|---|---|
| **同步** | HTTP `GET` | 设备拉取 | 期望状态文档、制品（插件文件、照片、APK） | 设备按已有状态继续运行 |
| **回报** | HTTP `POST` | 设备推送 | 实际状态、指标、日志、崩溃 | 写入本地 outbox，恢复后补传 |
| **实时** | WebSocket | 双向 | nudge、通知、远程指令、服务调用、语音帧 | 推送暂停；设备功能不受影响 |

原则：

- **设备主动，Server 被动**：除实时通道上的推送外，所有交互都由设备发起
- **幂等**：同一期望状态重复应用结果不变；制品按 sha256 寻址，重复下载无副作用
- **单一事实源**：期望状态由 Server 定义，实际状态由设备回报；设备从不“等 Server 告诉它画什么”

基址：`http://<server>:8765`。所有 API 前缀 `/api/v1`，JSON 编码 UTF-8，时间为 Unix 毫秒。

## 2. 发现与配对

**发现**：Server 广播 mDNS `_thinkos._tcp`（TXT：`proto=1`、`name=<主机名>`）。设备用 `NsdManager` 发现；失败时使用配置里的固定地址。

**设备身份**：设备首次启动生成 `device_id`（UUID v4），存于本地，永不改变。

**配对（MVP）**：共享 token。

- Server 的 `config.toml` 里配置 token；设备在设置页填写系统设置 `server.token`（或开发期经 `adb` 写入 `settings/local.json`，见 `06-plugins.md` §11.2）
- 所有请求带 `Authorization: Bearer <token>` 与 `X-Device-Id: <device_id>`
- Server 首次见到某个 `device_id` 时自动登记，并使用默认的期望状态

**配对（V1）**：设备设置页显示 6 位配对码，用户在 Server 端确认后签发每设备独立 token。

## 3. 同步：期望状态

### 3.1 拉取

```
GET /api/v1/devices/{device_id}/desired
If-None-Match: "<rev>"
```

| 响应 | 含义 |
|---|---|
| `200` + 文档 + `ETag: "<rev>"` | 有新的期望状态 |
| `304` | 未变化 |
| `401` | token 无效 → 设备进入 `UNPAIRED`，继续本地运行 |

触发时机：开机后、每 5 分钟、收到实时通道 `nudge` 时、维护页手动“立即同步”。

### 3.2 期望状态文档

```json
{
  "proto": 1,
  "rev": 42,
  "config": {
    "deck": {
      "pages": ["home.main", "gallery.slideshow", "clock.face", "music.nowplaying"],
      "roles": { "home": "home.main", "ambient": "gallery.slideshow", "sleep": "clock.face" },
      "home": { "left": ["clock.now", "weather.now"], "right": ["music.mini", "gallery.preview"] }
    },
    "attention": { "awake_to_ambient_s": 120, "to_asleep_s": 180, "engaged_idle_s": 15 },
    "theme": { "mode": "auto", "dark_below_lux": 50, "light_above_lux": 120, "night_below_lux": 5, "night_above_lux": 15 },
    "display": { "orientation": "auto" },
    "units": { "system": "metric", "wind": "auto" },
    "plugins": {
      "weather": { "interval": "15" },
      "music": { "navidrome": "http://192.168.1.10:4533", "user": "kiwi" }
    }
  },
  "plugins": [
    { "id": "weather", "version": "1.2.0", "digest": "5be0…",
      "source": { "kind": "git", "url": "https://github.com/alice/thinkos-weather", "path": "", "ref": "v1.2.0", "commit": "9f2c1e7…", "track": false },
      "permissions": ["network"],
      "files": [
        { "path": "manifest.json",      "sha256": "a1c4…", "size": 812 },
        { "path": "main.lua",           "sha256": "77d0…", "size": 2304 },
        { "path": "views/today.json",   "sha256": "e932…", "size": 401 }
      ] },
    { "id": "clock", "version": "1.0.3", "digest": "41aa…",
      "source": { "kind": "local" },
      "permissions": [],
      "files": [ { "path": "manifest.json", "sha256": "0b5d…", "size": 604 } ] }
  ],
  "albums": [
    { "id": "family", "rev": 7, "items": [
      { "id": "IMG_0012", "portrait": "c0de…", "landscape": "be3f…", "tint": "#C9A27E", "taken": 1726000000000 }
    ] }
  ],
  "apk": { "version": "0.2.0", "code": 20, "sha256": "77e1…", "size": 21455872 }
}
```

| 字段 | 说明 |
|---|---|
| `rev` | 单调递增；任何字段变化都会递增 |
| `config` | **设置文档**（ADR-017）：除 `deck` 之外的顶层键是**系统级设置**（清单与 schema 见 `04-ui-spec.md` §9、`shared/schema/settings/system.json`），`plugins.<id>` 是**页面级设置**（schema 在该插件的 manifest `settings` 里，生效值以 `tos.config` 交给插件）。覆盖 APK 内置默认值；缺省项沿用设备默认值。`deck` 是部署配置（页面顺序、角色、首页两栈），不是设置项 |
| `plugins` | 本Server管理的插件集合，不是设备全部来源的全集。缺项只撤销该Server管理的副本，不卸载用户从签名registry安装的副本；APK内置版本保留。每项含：`digest`（文件清单摘要，见 `06-plugins.md` §2）、`source`（来源：`git` 带 `url` / `path` / `ref` / 锁定的 `commit` / `track`，或 `local`；仅用于显示和排错，**设备不访问它**）、`permissions`（用户已在 Server 上确认的权限）、`files`（逐文件清单，每个文件都是一个 blob） |
| `albums` | 照片集；`portrait` / `landscape` 是已按设备尺寸裁好的 JPEG 的 sha256 |
| `apk` | 可选的管理端版本提示；实际更新由设备按`34` §3–4读取签名目录并校验，Device Owner下可依安静时段策略静默安装。此字段自身不是验签或安装授权 |

### 3.3 制品下载

```
GET /api/v1/blobs/{sha256}
```

- 内容寻址：URL 即校验依据，设备下载后必须校验 sha256，不一致即丢弃
- 支持 `Range`，大文件可断点续传
- 可长期缓存（`Cache-Control: immutable`）
- 插件的每个文件、照片集的每张图片都是一个 blob；没有“插件包”这种整体制品（ADR-018）

### 3.4 对齐（设备侧）

```
desired 与 actual 比较
  ├─ 插件：`digest` 不同 → 逐文件下载（本地已有同 sha256 的文件直接复用，不重下）→ 校验 sha256 与 size
  │        → 在临时目录拼出 → 校验文件数 / 大小限制、manifest 与视图 schema、`api`、权限
  │        → 原子切换 → 热加载；仅该Server管理且已缺项的副本 → 停止并撤销，不删除registry来源或APK内置版本
  ├─ 照片集：增量下载缺失的图片，删除不再需要的图片（受存储上限约束）
  ├─ 配置：校验后整体替换 Server 下发层，重新计算生效值（§3.5），受影响的插件收到 `config` 事件
  └─ APK：作为管理端版本提示；实际签名更新流程与静默安装门禁见34号文档
全部完成后 → 回报 actual
```

- 对齐失败的单项不影响其它项；失败项在下一轮同步时重试
- 大文件下载只在 Attention ≤ AMBIENT 时进行
- 期望状态里 `permissions` 超出 manifest 声明、或声明了系统权限（`system.settings`）的 Git 来源插件：设备拒绝加载并回报

**实现边界（OTA-4核对）**：本节Server期望状态对齐是协议设计；当前没有Server业务入口或来源隔离删除的端到端证据。设备已有的独立registry存储/按来源停用不能冒充本算法已实现。本轮不开发Server，也不据此勾选“不误删registry”的集成验收。

### 3.5 设置值的同步（ADR-017）

设置的叠加规则见 `06-plugins.md` §11.2。网络上只涉及两件事：

- **下发**：期望状态的 `config`（含 `plugins.<id>`）就是 Server 下发层。设备按 schema 校验：不合法的值忽略并写入回报的 `settings.rejected`，该项沿用旧值或默认值
- **回报**：设备把本地修改（overrides）连同被拒绝的下发值放在回报的 `settings` 里（§4）。`secret` 字段不回报
- **冲突**：设备上的修改优先；只有 Server 对同一个 key 下发了与上次不同的值，才清除该 key 在设备上的旧修改。Server 的其它无关更新不会冲掉设备上的修改
- 设备上的修改**不会自动写回 Server 的 `config.toml`**。Server 只记录并展示它们；写回的操作留给 M2 的 Server 管理界面

## 4. 回报：实际状态

```
POST /api/v1/devices/{device_id}/report
```

```json
{
  "proto": 1,
  "applied_rev": 42,
  "apk": { "version": "0.1.7", "code": 17, "api": 1 },
  "caps": {
    "screen": { "w": 800, "h": 1280, "dpi": 260 },
    "orientation": "landscape",
    "layouts": ["stacks", "focus", "slideshow", "nowplaying", "list", "form"],
    "templates": ["glance", "media", "list", "photo", "stack"],
    "components": ["text", "image", "list", "icon", "progress", "player", "clock"],
    "sensors": ["proximity", "light", "accelerometer"],
    "voice": false
  },
  "plugins": [
    { "id": "weather", "version": "1.2.0", "digest": "5be0…", "commit": "9f2c1e7…", "status": "running" },
    { "id": "stocks",  "version": "0.1.0", "digest": "c7d1…", "status": "disabled", "error": "budget: cpu > 50ms x5/h" }
  ],
  "settings": {
    "overrides": {
      "system": { "theme.mode": "dark" },
      "plugins": { "weather": { "interval": "30" } }
    },
    "rejected": [ { "scope": "weather", "key": "temp_unit", "reason": "unknown option" } ]
  },
  "errors": [ { "item": "album:family/IMG_0099", "reason": "sha256 mismatch" } ],
  "state": { "attention": "AMBIENT", "page": "gallery.slideshow", "theme": "DARK" },
  "free_storage_mb": 2210
}
```

- 每次对齐完成后、以及每 15 分钟发送一次
- Server 用 `caps.screen` 为该设备裁剪照片集；用 `apk.api` 判断插件是否兼容；用 `settings.overrides` 展示设备上有哪些本地修改

其它回报端点：

| 端点 | 内容 |
|---|---|
| `POST /api/v1/devices/{id}/metrics` | 批量指标（帧统计、PSS、Lua 内存、CPU、档位），每条带时间戳 |
| `POST /api/v1/devices/{id}/logs` | 批量日志（等级 ≥ warn，或维护页开启调试时全部） |
| `POST /api/v1/devices/{id}/crash` | 崩溃日志 |

均可批量补传；Server 按设备 + 时间戳去重。

## 5. 实时通道（可选）

```
ws://<server>:8765/ws
```

### 5.1 连接

- 设备连接后先发 `hello`，Server 回 `welcome`；认证失败即关闭连接
- 心跳：设备每 20s 发 `ping`，Server 回 `pong`；40s 未收到 `pong` 视为断线
- 重连：指数退避 1s → 2s → 4s → … → 最大 30s，无限重试
- **断线不影响设备上的任何功能**，只是收不到推送

### 5.2 消息信封

```json
{ "type": "…", "id": "可选，请求/应答配对用", "ts": 1730000000000, "payload": { } }
```

### 5.3 设备 → Server

| type | payload | 说明 |
|---|---|---|
| `hello` | `{"proto":1,"device_id":"…","token":"…","applied_rev":42,"apk":"0.1.7"}` | 握手 |
| `ping` | — | 心跳 |
| `event` | `{"name":"presence","value":"present"}` | 仅发送 Server 在 `welcome.subscribe` 中订阅了的设备事件 |
| `server.call` | `{"plugin":"music","name":"media.resolve","args":{…}}` | 插件调用 Server 端服务，带 `id`，等待 `server.result` |
| `server.subscribe` | `{"plugin":"notify","topic":"notify"}` | 插件订阅 Server 端主题 |
| 二进制帧 | 首字节帧类型（`0x01` = 语音 PCM16 16kHz 20ms） | 可选语音上行 |

### 5.4 Server → 设备

| type | payload | 说明 |
|---|---|---|
| `welcome` | `{"proto":1,"time":1730000000000,"subscribe":["presence"]}` | 握手应答；`time` 作为授时补充 |
| `pong` | — | 心跳 |
| `nudge` | `{"rev":43}` | 期望状态已变化，设备应立即同步 |
| `command` | `{"target":"music.next","args":{},"source":"phone"}` | 远程指令；设备路由给对应插件，等同本地触发 |
| `alert` | `{"title":"…","body":"…","icon":"bell","level":"info","sound":true}` | 系统级通知（Server 自身的提示，例如“插件 stocks 已被禁用”）；普通通知走 `notify` 插件 |
| `server.message` | `{"topic":"notify","data":{…}}` | 推给订阅了该主题的插件 |
| `server.result` | `{"ok":true,"data":{…}}` 或 `{"ok":false,"error":"…"}` | `server.call` 的应答，`id` 与请求相同 |
| `voice.result` | `{"text":"下一首","command":"music.next"}` | 可选，语音识别结果（随后会附带对应 `command`） |

## 6. 通知与远程指令入口（Server 对外）

供手机快捷指令、脚本等调用，不属于设备协议，但决定了推送的来源：

| 端点 | 说明 |
|---|---|
| `POST /notify` | `{"title","body","icon?","level?","source?"}` → 以 `server.message{topic:"notify"}` 推给设备 |
| `POST /api/v1/devices/{id}/command` | `{"target":"music.next","args":{}}` → 实时通道 `command` |

设备离线时，Server 将这两类消息排队，TTL 1 小时，设备重连后按顺序投递；过期丢弃。

## 7. 插件文件与视图（不单独在网络上传输的结构）

插件是一个目录（`06-plugins.md` §2）：manifest、视图声明、状态绑定语法定义在 `shared/schema/plugin/`；设置项的声明格式定义在 `shared/schema/settings/`。
它们只在**设备本地**被解析；Server 从 Git 仓库取回插件（`06-plugins.md` §12）时用同一份 schema 校验，保证下发的文件集设备一定能解析。

> 现状（M0）：插件以 `.tpk`（确定性 zip）形态打包，同步协议的这一部分尚未实现。M0.5 起设备端改为目录与文件清单，M2 实现 Server 与同步。

## 8. 版本与兼容

| 版本 | 来源 | 规则 |
|---|---|---|
| 协议 `proto` | `shared/protocol/VERSION` | 不一致时设备停止同步、继续本地运行，维护页提示“需要更新” |
| 运行时 `api` | APK 内常量，随回报上传 | 插件 manifest 的 `api` 大于设备 `api` 时，Server 不下发、设备拒绝安装 |
| 期望状态 `rev` | Server | 单调递增 |

## 9. 错误与降级

| 情况 | 行为 |
|---|---|
| 网络不可达 / Server 停机 | 静默重试；设备照常运行 |
| JSON 解析失败 | 丢弃该消息并记录日志 |
| 制品 sha256 不一致 | 丢弃，下一轮重试；连续 3 次失败写入回报 `errors` |
| 插件文件集校验失败（schema、数量 / 大小限制、权限、`api`） | 拒绝安装，保留旧版本，写入回报 |
| 未知 `command.target` | 忽略并写入回报 |
| 未知消息类型 | 忽略（向前兼容） |

## 10. 时间

- 设备时间来自系统（timesync 校时）；Server 在线时 `welcome.time` 作为补充
- 设备开机初期时间可能不准（无 RTC），Server **不要依赖设备上报的时间戳做业务判断**；回报中的时间戳仅用于排序

## 11. 安全

- 仅限局域网；Server 不应暴露到公网。若需要公网访问，必须 `https/wss` + 每设备 token。该限制针对Server管理协议；独立OTA/registry公开镜像无需设备token，公网仅HTTPS，私网镜像可用HTTP，仍必须验签。
- Server下发路径以已认证期望状态的sha256校验文件；可选Git tag校验仍只在取回端执行。ADR-041的独立OTA/registry路径先用APK内置的独立角色公钥验证目录原始字节，再核对清单与文件摘要；Server提示不能替代签名目录或模块的独立系统根。
- 设备不运行Git、不访问作者仓库；可从公开镜像或可选Server镜像读取签名registry目录和内容寻址文件。`source`中的作者仓库地址仍只用于显示/排错，不会变成设备的Git或下载入口。
- 插件的权限由设备按 manifest 强制执行，Server 下发不能绕过

## 12. 本地网页模拟器开发通道

`python3 -m simulator serve --plugin <目录>` 启动独立的本地主机工具；它不参与设备与 Server 的同步，不安装 APK，也不修改 Android 源码或设备。开发协议版本独立为 `1`，不改变上文设备协议的 `proto=1`。唯一机器可读来源为 `shared/protocol/schema/simulator/development.json`；`python3 simulator/scripts/gen_protocol.py` 生成 `simulator/generated/protocol.py` 与 `protocol.ts`，`--check` 检查漂移。模拟器配置统一来自 `simulator/settings.schema.json`。

服务只监听 `127.0.0.1`。先从同源 `GET /api/session` 取得随机会话 token，后续 JSON API 与 SSE 请求携带 `X-ThinkOS-Session`；服务检查 Host、Origin 和 Sec-Fetch-Site，不启用跨源访问。`GET /api/events` 使用带 header 的 fetch 读取 SSE，事件 `revision` 的数据为 `RevisionEvent`。静态前端来自 `simulator/dist`，未构建时明确报出构建命令，不打开或操作主机窗口。

| 入口 | 请求/响应契约 | 用途 |
|---|---|---|
| `GET /api/session` | `Session` | 当前 revision、已验证插件快照、设置 schema、Lua bootstrap、虚拟 profile、字体及图标 |
| `GET /api/snapshot` | `SnapshotResponse` | 需会话 header；热更仅取得 revision、插件快照和诊断，不重复传输字体与环境配置 |
| `POST /api/http` | `HttpRequest` → `HttpResponse` | 有 network 权限的 GET/POST；权限从当前 pluginId/digest 对应清单读取 |
| `POST /api/dns` | `DnsRequest` → `DnsResponse` | API10、有 network 权限的有界 DNS 发现 |
| `POST /api/storage` | `StorageRequest` → `OkResponse` | 保存当前 profile 的单插件虚拟 store，不访问设备数据 |
| `POST /api/profile` | `ProfileRequest` → `ProfileResponse` | 保存模拟场景及 `{system,plugins}` 设置覆盖；字段仍按系统/插件设置 schema 校验，返回完整 settings/environment |
| `POST /api/place-search` | `PlaceSearchRequest` → `PlaceSearchResponse` | OS 地点选择器搜索；只使用 system location.search_url，返回最多 20 项，不接受插件或请求自选地址 |
| `POST /api/runtime` | `RuntimeRequest` → `OkResponse` | 新 VM 初始化成功后的 commit，或释放匹配 digest 的旧运行快照 |
| `POST /api/library` | `LibraryRequest` → `LibraryResponse` | 只读枚举当前 profile 的虚拟媒体库；需 library 权限 |
| `POST /api/resource` | `ResourceRequest` → 二进制 | 受权限和网络策略控制的图片/媒体流；媒体另需 media 权限 |
| `POST /api/resource-ticket` | `ResourceRequest` → `ResourceTicketResponse` | 为不能设置请求 header 的 audio/video 创建有期限的 opaque URL |
| `GET /api/resource/<ticket>` | 固定资源映射 → 二进制 | 不接受任意 URL；再次验证原 pluginId/digest/权限，支持 Range；不携带主会话 token |

快照只读源目录，经安全文件读取、现有插件校验和最终字节校验后按 digest 发布；文件内容在 `Session.plugins[].files` 中以 base64 表达。候选校验失败保留上一版并发送诊断；页面以新 revision 重新取得快照。生产权限仅在 digest 匹配构建时固定的 `simulator/assets/factory-index.json` 时授予。显式内置插件开发档只能选该固定清单的 ID，其系统能力仅改变独立模拟状态。更改插件源码不会自动成为出厂副本，声明 `system.*` 也不构成授权。

每个插件只保留最新候选和已 commit 的运行快照，不积累历史链。新候选启动失败时旧运行代仍可使用原权限的 HTTP、资源和 store；浏览器初始化候选成功后先 commit 再替换旧 VM。commit 必须匹配最新候选或已运行代，并撤销更旧的资源票据；迟到的旧代 release 不影响新代。同一 profile 的浏览器窗口共用运行代，因此一个 profile 同时只开一个开发预览；要独立预览使用不同 profile 和端口。

HTTP 与 DNS 沿用插件 API 的限额和地址策略，媒体使用独立流量上限；`originUrl` 表示媒体入口来源，跨源子资源与重定向去除非可携带请求头。真实请求使用开发机 DNS、网络和系统信任库，不能用它证明设备连通性或设备 TLS 行为；确定性检查使用有界 fixture。profile 默认只写入 `simulator/.local/profiles`，不保存至设备，不读取任意主机媒体路径。接口失败统一返回 `ErrorResponse`，不记录会话 token、资源 ticket、外部凭据或原始网络异常。

默认虚拟目录 `/sdcard/Android/data/moe.kiwi.thinkos/files/{Pictures,Music,Movies}` 对应 `simulator/.local/profiles/<profile>/library` 的同名子目录；`/sdcard/{Pictures,Music,Movies}` 是同一 profile 目录的别名，兼容现有插件默认配置，缺省目录未创建时列表为空。额外目录只能由主机设置 schema 的 `library_mounts` 显式映射；扫描和最终读取都以目录文件描述符逐层打开，不跟随符号链接，绝不把任意 Android 绝对路径解释为 Mac 路径。媒体票据支持虚拟 file URI 和 Range；`content:` 需要显式场景 fixture，不访问真实 provider。当前本地枚举的容器标签、时长、EXIF 等可选元数据为空，不能以此冒充 Android 媒体解析结果。设置修改验证实际值与跨字段约束，数值 step 只作为 UI 步进；时间要求五分钟步进，文字长度按原生 UTF-16 单元检查。

`ProfileRequest.reset` 是最多 128 项的 `{scope,key?}` 数组，scope 为 `system` 或当前插件 ID；省略 key 清该 scope 全部覆盖值。服务先删除覆盖值，再应用 settings 补丁，对最终有效设置校验后原子写入。重置不写入默认值，后续默认值变化仍可自然生效。

普通 environment 请求是补丁。导入整份场景时使用 `replaceEnvironment:true` 并提供 environment；服务先恢复环境默认值再应用导入内容，清除旧场景未提供的 now/http 等 fixture，返回完整规范化环境。此替换不清空插件 store 或设置覆盖；浏览器和 Worker 也必须使用替换语义，不能继续合并旧场景字段。

`Session.developmentSettings` 只暴露工作台的 `log_entries`、`worker_watchdog_ms`、`watch_retry_ms`、`worker_heartbeat_ms`，其默认值与边界通过主机设置 schema 的引用统一验证；它不授予插件改设置的能力。环境 fixture 显式定义系统音量/亮度、24 小时时制、引导间隔、catalog 分页状态与完整 installed 记录，未知字段拒绝；这些记录不触发真实安装或修改设备 registry。

地点搜索属于 OS 选择器服务，不向 Lua 增加私有能力。`/api/place-search` 需要会话验证，只接受 query/locale；真实模式使用 system `location.search_url`、现有 DNS/HTTP 地址与体积/超时/并发限制，按原生规则处理中文市县区变体和结果清洗。fixture 模式返回 `{items:[],offline:true}` 供离线城市列表接手；真实搜索成功但为空时不设置 offline。所有 HTTP/资源入口拒绝模拟器控制服务响应，防止插件利用局域网权限读取另一 profile 的会话。不同目录使用相同插件 ID 会拒绝初始启动；后来出现的冲突保留原拥有者并发布诊断。

## 在线音乐源 HTTP v1

音乐源独立于设备管理 Server。音源基址来自播放器的 `music_source_url`。返回 UTF-8 JSON。契约见 `shared/protocol/schema/music/v1.json`。执行 `python3 tools/music-source/gen_contract.py --check` 可验证生成的限制。

| 方法与路径 | 参数 | 成功响应 |
|---|---|---|
| GET `/v1/browse` | 无 | `{tracks:[{id,title,artist,album?,art?,durationMs?}]}`，按源端顺序返回最多 12 首 |
| GET `/v1/search` | `q` 是经百分号编码的歌曲或艺人，UTF-8 1–160 字节 | 和浏览使用同一曲目结构，最多 12 首 |
| GET `/v1/resolve` | `ids` 为逗号分隔的 1–12 个不重复 ID | `{links:[{id,url}]}`，仅返回可解析项。选中 ID 缺失时客户端报错 |

ID 最多 64 个 ASCII 字母、数字、下划线或连字符。标题、艺人、专辑各不超过 240 个 UTF-8 字节。封面是 HTTP(S) URL，最多 1024 字节。时长是 1–86400000 的整数毫秒。源缺少封面时省略该字段。旧音源没有浏览端点时，搜索仍可用。解析 URL 必须是绝对 HTTP(S)，不超过 2048 字节且不含用户信息或片段。解析 URL 仅在播放会话中使用。HTTP 请求总等待为 5 秒，响应体上限 32 KiB，仅跟随同源重定向。音频 URL 和后续重定向沿用原生媒体网络策略。部署与本地复现见 `tools/music-source/README.md`。
