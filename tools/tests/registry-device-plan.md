# REG-2 真机统一验收夹具

开发 Agent 只编写，root Agent 单一执行。`RegistryProbe.kt` 不安装任何插件、不自行批准权限，也不直接调用 Repository/activate；它仅给实际 debug 进程注入 RAM 内的仓库公钥，并在 loopback 提供签名目录和内容寻址文件。业务动作必须通过实际内置 hub 的公开 `tos.plugins.*` 入口与屏幕触摸。

在 `UpdateInstrumentation` 的 phase 分派中由 root 加：

```kotlin
"registry" -> RegistryProbe.run(this@UpdateInstrumentation, directory, token, result, ::test)
```

沿原有 `-PupdateProbe=true` 构建 instrumentation。运行前保存用户设置、导航、投屏身份及文件原始状态，保证音量流 1–5 全 0；已有其它仓库插件/仓库缓存时先保留，不能覆盖。首次夹具最好要求 `files/plugins/registry-state.json` 和 `files/registry` 尚不存在。

统一入口为 `registry_device.py --mode basic|malicious|limits|lifecycle --apk <已装主包> --test-apk <已装测试包> --output <新目录>`。它先核对两个已装APK的实际摘要和注册runner，再停止应用保存快照，不负责安装或构建。测试包必须用 `cd android && ./gradlew -PupdateProbe=true assembleDebugAndroidTest` 构建。`lifecycle` 已接独立驱动，使用两次原始600秒试用；尚未执行的用例不能凭入口已存在认定通过。

开始必须是正常 MainActivity 的空基础页，无媒体/队列、原生法律/更新窗口、APK 或模块 pending 事务。原 `page + variant`、全部进程内 holds、方向和 Android HOME preference 都记录并核对恢复，不要求从 Home 开始。600 秒证据以原生确认按钮的真实 input 提交前为最早起点，阅读/滚动权限说明不计入试用等待。音量流 1–5 全程只读核对，绝不恢复任何非零音量。

进程停止、instrumentation 结束与重启使用持久记录的受控窗口：只允许 ThinkOS 或运行前记录的 HOME 候选。空焦点最多只观察 5 秒，不进行 UI 操作；第三方前台、人体输入、失去触摸监视或音量非零立即停止，不启动 Main 抢回。`--restore` 只可从 ThinkOS 或这次受控停止暴露的 Launcher 继续；若已有外部输入记录，需先由所有者返回 ThinkOS，再通过最后检查点字节审计。所有删除、旧字节还原和自有 iptables 规则操作前都重新检查保护条件；成功路径也不能跳过最后检查点审计，不能在应用已运行后强写旧 store 掩盖未知新变化。

`am instrument -e phase registry -e run_id <32位hex>` 持续运行期间，夹具会将以下文件写在应用私有 `cache/update-device-<id>/registry/`：

- `ready.json`：真实 UID/PID、测试插件 id、源地址、五个版本的摘要；`samples` 给出恶意角色/权限/坏 blob 的固定 id、名称和摘要，`limits` 列出 17 个数量样本。先看到它，再操作 hub。
- `control.json`：root 以临时文件加原子改名写入，最多 4096 字节，generation 单调增加。
- `response.json`：固定控制请求的结果。`handled=true` 仅表示夹具控制执行了，绝不是安装或权限批准。

固定控制：

```json
{"generation":1,"action":"publish","level":2}
{"generation":2,"action":"accessibility_accept"}
{"generation":3,"action":"publish","level":3,"bad_signature":true}
{"generation":4,"action":"publish","level":3,"old_seq":true}
{"generation":5,"action":"publish","level":4,"revoked":true}
{"generation":6,"action":"checkpoint"}
{"generation":7,"action":"finish"}
```

额外固定样本（仍需在 hub 实际点击刷新，不会自行安装）：

```json
{"generation":8,"action":"publish","sample":"home"}
{"generation":9,"action":"publish","sample":"settings"}
{"generation":10,"action":"publish","sample":"system"}
{"generation":11,"action":"publish","sample":"tampered-blob"}
{"generation":12,"action":"publish","sample":"limits","limit_count":17}
```

`sample` 只接受 `normal`、`home`、`settings`、`system`、`tampered-blob`、`limits`。`limit_count` 只接受 1–17，不接收任意 manifest、URL、Lua、文件路径或执行命令。正常版本用 `sample:"normal",level:1..5`，第5版仅在实际点按“触发错误”后抛Lua错误。`tier` 只接受 `reviewed|community`，不允许夹具授予 `official`。所有恶意样本的目录签名都是正确的仓库角色签名，不能把坏签名的提前拒绝误当成设备语义防护通过。

每次发布之后从 hub 真实点击刷新。目录发布级别 1–4 对应 `1.0.0`–`1.0.3`：

1. v1：只声明 network，正常启动，日志 `REGISTRY_DENIED 1.0.0 7`、`REGISTRY_STARTED 1.0.0`。通过 hub 安装，原生确认里核对 repo@commit、档位、权限理由、大小。对确认框执行 accessibility_accept 必须不写权限记录且窗口仍在；随后用真实 adb touchscreen 点击批准。
2. v2：启动 handler 故意报错。先在 v1 试用未完成时发布 v2，从 hub 请求同插件更新必须拒绝；核对整个该插件记录（尤其 trial、previous、digest、confirmed）没有变化，不下载或激活 v2，不清除或重计 v1 的健康期。等待 v1 原来的真实试用期完成后再次用 hub 更新，旧 v1 保持运行，v2 摘要进入阻止自动安装列表；不能以夹具发布成功当作回退成功。
3. v3：增加 media、location，建立 autoplay=false 的静音视频会话。新增权限确认前 v1 仍有效；取消应保留 v1。再次点击更新并触屏批准才加载 v3。
4. v4：权限与 v3 相同但不持有媒体。先等待 v3 原来的完整试用期通过，再请求 v4，避免“试用中拒绝叠加”冒充媒体门禁。hub 更新时实际 v3 的暂停会话应阻止替换；回 v3 点击“停止媒体”，页面仍可阻止替换；底缘回首页后才激活 v4。记录实际进程、页面、MediaEngine owner 与 PluginRuntime 日志。
5. v4 试用中停止进程、重开 MainActivity（夹具 RAM 公钥和服务器也会消失）：设备仍应从本地恢复 v3，并阻止 v4 自动重装。确认插件目录与设置无需 Server/网络。初次恢复时 v3 的媒体 URL 已离线，测试应区分“媒体来源不可达”和“插件不能离线加载”。
6. 独立一轮发布 revoked=true 的已安装版本，刷新目录后必须停用该具体摘要并显示原因。再过无特权插件 API/事件检查，不能出现 `REGISTRY_EVENT_LEAK`。
7. 使用真实救援菜单恢复出厂插件，仓库记录清空、VM 消失，插件设置保留；真实安全模式也不能启动仓库源。

第 7 项会调用 `NativeCrashTracker.reset()`，清空全部插件的 `files/native-failures.json`。现有恢复日志没有可靠方式区分并安全合并后来发生的原生崩溃历史，因此该阶段在主文件或备份含非空/未知历史时必须跳过，不写回旧历史，也不清历史换取成功。驱动改用公开卸载清理本次插件并继续其余 lifecycle；结果明确 `partial=true`、`factory_restore.executed=false`，将此项保留在 `pending`。只有历史为空或不存在时才允许真实点按菜单项，并在点按前再次检查。该条件跳过不代表恢复出厂插件验收通过。

恶意内容和数量边界必须再执行以下真实路径，夹具不会替 root 做断言：

1. `home`：目录与清单签名/摘要正确，下载后的 manifest 页面声明 home，且没有用 manifest.apps 的角色互斥去提前拒绝。真正从 hub 点安装，确认框应显示该样本自己的来源/权限/大小；用屏幕触摸批准后，由设备语义校验拒绝。核对该 id 没有安装记录、没有 VM/页面/角色，也没有 `REGISTRY_STARTED` 日志，原 home 不变。
2. `settings`：同样走真实 hub 安装与原生触摸确认；manifest 声明 settings，但没有 system.settings，设备必须拒绝。核对 factory settings 仍持有唯一设置入口，不出现新 role/权限记录，原用户设置不变。
3. `system`：已签名目录只声明普通 network 并给出 why，真实 payload manifest 额外夹带 system.settings。真实 hub 与确认框批准普通权限后，设备必须因声明/确认不一致及禁用系统权限的规则拒绝整个候选，不能部分安装或赋予权限。不能仅检查 UI 的错误字样；要看记录、VM、catalog 和 API/事件实际结果。
4. `tampered-blob`：正确签名目录、清单、digest 保持不变，服务器把该样本 main.lua 的响应翻转一个字节，长度不变。新 id 确保它没有已认证的旧同 hash blob 可复用。真实 hub 与原生触摸确认后，实际 GET 到 `ready.tampered_blob_sha256` 的路径，设备必须在文件 hash 校验处拒绝，没有记录/VM/已批准权限。捕获请求与失败日志，验证它不是签名或长度错误，也不执行被篡改 Lua。
5. `limits`：先通过公开 hub 卸载本轮其它已安装测试样本，确认公开 installed 数量为 0，保留设置不影响数量。目录有 17 个相同权限策略、不同 id 的独立插件。前 16 个都用真实 hub 触摸安装，并逐个在原生确认框触屏批准；核对 16 个实际记录和运行 VM，API/事件隔离仍成立。第 17 个只通过 hub 真实点击安装，设备应在已知数量上限处立即拒绝，**此时不应再弹确认框**、不下载它、不产生第 17 条记录/VM，也不改变其它 16 个摘要/权限。不能以没有点第 17 个、把隐藏上限改小，或元数据声称安装成功作为证据。此处只做功能验证，性能依所有者要求后置。

这些样本各自占固定子 id，失败后缓存目录或未激活的已验证文件可能存在；那不等于安装成功。用 checkpoint 收集真实状态与请求，根检查点要区分已下载文件、持久安装记录、已激活实例、导航 catalog 和实际角色。

同一轮还应验证：坏仓库角色签名和旧 seq 不替换已认证缓存；请求头无 Cookie、Accept-Language、设备标识；中文横竖屏确认按钮和详情不裁切。所有样本只存在于 androidTest，公钥和签名源都在 RAM，未增加生产安装入口或自动批准通道。

试用要求默认 600 秒，不能把缩短阈值的测试当作真实十分钟证据。注意候选可在等待替换时已写 trial 记录，但它没有执行；“已下载/已暂存”也不能冒充激活。

结束时先通过公开 UI 停媒体并卸载测试插件，再发送 finish。只清理这次创建的测试 id 目录、对应 store 和本次新建的 registry ledger/cache/state，保留 APK 中的 factory 目录、真实 OTA install-history 及任何后来的用户操作。出现外部输入时先审计变化，禁止把旧快照覆盖回来。夹具 report 只证明准备与负向权限测试；整个 REG-2 完成必须由 root 的实际 UI、文件和日志断言共同证明。

恢复时原字节证明在本次应用UID网络阻断下完成，之后只删除本次唯一comment规则。直播列表和线路健康缓存可能自然刷新：仅当用户偏好语义及其他字段完全不变时保留新缓存，报告单独列出，不能把它计入逐字节还原数量。检测到偏好变化、其他私有文件变化或真实触摸时保留日志等待审计。
