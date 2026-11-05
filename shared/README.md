# shared/

客户端与服务端的**唯一契约来源**。改协议先改这里，再生成两端代码。

```
shared/
├── protocol/                # 设备 ↔ Server 通讯（docs/05-protocol.md）
│   ├── VERSION              # 协议版本（整数）
│   └── schema/
│       ├── envelope.json    # 实时通道消息信封
│       ├── desired.json     # 期望状态文档
│       ├── report.json      # 回报（实际状态、能力、指标）
│       └── live.json        # 实时消息（nudge、command、server.* …）
├── schema/plugin/           # 插件格式（docs/06-plugins.md）
│   ├── manifest.json        # manifest
│   ├── view.json            # 视图：版式、模板、组件、绑定
│   ├── layouts.json         # 各版式/模板的槽位
│   ├── index.json           # 插件目录文件清单与摘要
│   └── catalog.json         # APK 内置插件根索引
├── schema/settings/         # 设置（ADR-017，docs/06-plugins.md §11）
│   ├── field.json           # 字段格式：bool / choice / number / text
│   └── system.json          # 系统级设置清单（docs/04-ui-spec.md §9）
├── schema/update/           # 独立schema 1：签名OTA目录、APK公钥与设备私有序号记录
└── gen/
    ├── gen_kotlin.py        # → android/…/plugin/PluginSchema.kt、update/UpdateModels.kt
    ├── gen_go.py            # → server/internal/protocol/plugin_generated.go、update_generated.go
    └── update_contracts.py  # 两个标准入口共同调用的更新契约生成器
```

插件格式和设置格式也放在这里：设备端用它解析插件、校验设置值，Server 从 Git 取回插件时用同一份 schema 校验，保证下发的文件集设备一定能解析。

## 规则

- schema 用 JSON Schema 描述，必填项明确
- 生成文件头部必须带 `GENERATED - DO NOT EDIT`
- 手写数据类视为 bug
- 协议版本变更必须同时更新 `protocol/VERSION` 与 `docs/05-protocol.md`；插件格式变更必须同时提升运行时 `api` 版本并更新 `docs/06-plugins.md`

## 生成

```bash
python3 shared/gen/gen_kotlin.py    # 产出 Kotlin 数据类
python3 shared/gen/gen_go.py        # 产出 Go struct
```

生成脚本本身用 Python 写（纯文本模板，无第三方依赖），因为它是**构建工具**，不参与运行时。
