# 16 · 插件目录化验证

> 2026-10-30。ADR-018 的设备端安装已由压缩包切换为目录和文件清单；Server Git 取回与下发仍属于 M2。本记录区分已执行检查和待真机验证，未完成项不代表验收通过。

## 交付与不变量

- APK 中 `assets/plugins/<id>/` 含运行文件和独立 `index.json`；根 `assets/plugins/index.json` 收录各插件完整索引。
- 摘要为按 UTF-8 路径排序的 `path + NUL + sha256 + LF` 字节串的 SHA-256。每个文件的大小和哈希在拷贝时验证，之后再校验 manifest / view。完整摘要用于私有版本目录命名和原生退出归因。
- 不解压 zip。路径、白名单、重复项、大小写冲突、符号链接、文件数量和大小限制适用于所有安装输入。
- debug 写入隐藏暂存目录，全部到齐后发布；旧目录作为隐藏 `.previous-<id>` 暂存。包括之前排队的扫描在内，扫描器都能在替换间隙继续看到旧版，不因暂时缺失卸载插件。启动恢复中断替换。
- `activate` / pending / 启动提交、预算、回滚、隔离和迟到回调取消机制继续使用原实现；已安装版本不会直接引用可修改的开发目录。

## 已执行

- `python3 -I -m unittest discover -s tools/tests`：23 项通过，覆盖目录产物可复现、独立文件摘要核对、非运行文件忽略、大小写冲突、限制、旧产物清除、索引 / 根目录 schema 边界和 shell 路径 / 内容注入防护及原 schema 验证。
- `python3 -I tools/package_plugins.py`：生成 home / clock / settings 三个目录及根索引；输出目录不再包含旧压缩包。
- Python 发布器和原设备回归脚本语法检查通过。原设备回归脚本已改用同一个目录发布器。
- 新增 `PluginFilesTest`：跨语言固定摘要向量、损坏 / 增长文件清理、禁止覆盖已有目标、路径和索引攻击、缺失 / 未列出文件、符号链接、目录替换间隙。首个候选 APK 的统一 JVM / lint / assemble 检查通过（全项目 123 项）；随后新增共享索引 schema 的边界测试，需最终统一构建复验。

## 已完成真机回归

设备为 `HUA0BWUT`，安装首个统一构建候选 APK 后执行。全部是应用层操作，没有修改设备系统层。

- `python3 -I tools/tests/pre_m1_device.py --output artifacts/directory-validation/device-runtime`：7 项通过。验证 HTTP / JSON 与预算、store 合并写入、热替换取消 HTTP / sleep、异步启动失败保留旧版、权限、删除挂起候选、AtomicFile 备份恢复、3 次原生退出标记仅隔离对应 digest。证据：[results.json](../artifacts/directory-validation/device-runtime/results.json)、[logcat](../artifacts/directory-validation/device-runtime/logcat.txt)。
- `python3 -I tools/tests/directory_device.py`：7 项通过。首次发布与合法热替换 PID 均为 `12996`；坏哈希 / 大小与死循环候选保留旧版及其 timer；故意把正常目录移到 `.previous-…`，再发布另一插件强制触发扫描，原 Lua 实例没有卸载或重新启动；强制停止后重启，`.previous-…` 正确恢复为正常目录（新 PID `13228`）；应用存储无旧压缩包。证据：[results.json](../artifacts/directory-validation/installer/results.json)、[logcat](../artifacts/directory-validation/installer/logcat.txt)。
- 所有测试覆盖已移除、测试 HTTP 服务已停止，HOME 已恢复为 ThinkOS。

测试中发现并修正了两处工具问题：逐文件多次 adb 的传输延迟使旧异步请求在新版本发布前就已完成，现改为一次 `run-as sh` 会话用 base64 逐文件写入暂存目录（没有压缩包，也不把插件字节作为脚本执行）；原回归的固定 0.6 秒冷启动等待改为等待实际加载 / 隔离诊断，避免把尚未完成的启动误判为隔离失败。

同时记录到新时钟天文计算在首候选的冷启动触发宿主 CPU 信用预算，主任务已据此改为异步天文执行；该问题的修复与最终冷启动复验由本轮主验证记录跟踪。

## 最终复验待记录

1. 新增 `shared/schema/plugin/index.json` / `catalog.json` 后再统一运行 JVM / lint / assemble，安装最终 APK；检查仅 armv7 原生库、目录插件和共享索引校验。
2. 最终 APK 上按 M0.5 原要求测断网无 Server 冷启动 <3 s、AWAKE / AMBIENT CPU；超基线 20% 需调查。目录安全与生命周期的以上真机证据来自首候选，不代替最终性能验证。

尚未完成的验收不提前勾选 `09-roadmap.md` 中的 M0.5 验收框。
