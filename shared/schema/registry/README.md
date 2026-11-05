# 插件仓库文件契约

来源是 `docs/34-ota-and-plugin-registry.md` §5。它们是签名分发文件与设备私有状态，不是 Server HTTP 业务接口。标准 `shared/gen/gen_kotlin.py` / `gen_go.py` 同时生成 RegistryModels 与 Go 契约；解析器复用更新文件的精确整数、UTF-8、重复键及大小/深度限制。

- `catalog.json`：最多 1 MiB，P-256/SHA256withECDSA 的 ASN.1 DER 签名附在同名 `.sig`；只信 APK 内仓库角色公钥。原始字节验签后才解析，不信任目录自带的钥匙。最多 256 个插件、每个三个版本，先选插件 API 可用的最新版本。每个权限必须给出非空本地化理由。
- `files.json`：与原有 PluginFiles 目录清单同义，最多 64 KiB、128 个文件、8 MiB 总量。设备重新计算清单 digest 和逐文件 SHA-256/大小；不解包、不取 Git、不读取作者仓库。只从已签名的 blob_bases 获取内容寻址文件。
- `ledger.json`：独立的设备序号 floor，缓存不能降低它；同 seq 只能接受相同原始目录摘要。同轮多个源给出同最高 seq 的不同内容时拒绝该轮结果。
- `cache.json`：保存原始目录及原始签名。每次进程重建重新验签并核对独立 floor，已认证内容离线可浏览；过期只是提示。缺少正式钥匙或来源时显示未配置，不冒充目录为空。
- `state.json`：`files/plugins/registry-state.json` 的实际私有格式。最多 16 条，记来源/提交、已确认权限、自动更新、当前及上一版摘要和试用期。写入与目录同步完成后才能激活候选。十分钟连续试用未结束时的进程重启、资源重启或冷却会退回前版，并阻止该摘要自动重装。卸载和恢复出厂插件保留设置数据。

所有下载、哈希、语义/缓存校验和仓库状态写入在 IO 执行；只有 Lua 创建/激活回到插件线程。仓库插件在实际首帧及内置目录发布后分批启动。正在显示的插件或拥有媒体会话的插件等待离开后再替换。

设备再次拒绝任何角色和 `system.*` 声明；仓库页面一律当作应用页，不进入左右翻页序列。普通作者不能占用 factory ID。由维护者受控来源清单签出的 `tier=official` 才能覆盖没有系统权限/角色的同 ID 内置插件；版本相同或内置版本更高时用内置。home/settings/panel/welcome/hub 等系统插件继续随 APK 更新。签名 `revoked` 只针对具体 id+digest，命中后停用并显示原因。

安装与新增权限由 OS 原生窗口确认，显示名称、repo@commit、档位、大小、权限和 why。只接受新鲜、未遮挡的屏幕触摸释放；键盘、可访问性调用或程序 performClick 不会产生批准。Lua 没有授权写入口，第三方也拿不到 `system.plugins`。

当前代码提供 debug 构建下仅原生 instrumentation 可用的 RAM 根/来源覆盖，以便真实本地测试；它不写钥匙、不进 Lua/Intent/IPC，release 直接拒绝。正式仓库钥匙、来源、网络安装、原生确认、跨进程试用回退、吊销和卸载仍须由 root agent 统一真机验收，不能仅凭主机策略测试宣称 REG-2 完成。
