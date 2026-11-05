# REG-2b 独立应用生命周期专项

仅验收同源码独立 `moe.kiwi.thinkos.regfixture` 的真实 Runtime、Lua、Repository、签名目录及普通冷 Main；不等同正式 UID、Device Owner、全局手势、Alarm 自主恢复、原生不洁恢复或 REG-2b 全部完成。16 插件性能、恶意输入、吊销和恢复出厂不在本轮范围。

设备离线时可以准备本脚本；脚本不构建、不安装 APK、不安装系统模块、不重启、不清数据，也不运行 instrumentation。初始与冷启动都使用夹具 APK 中固定的四把临时公钥，不支持 RAM 覆盖根。私钥只由已有 `registry_fixture_origin` 在主机私有目录持有；设备侧没有 Server 依赖。

前置由统一验收 Agent 完成：

1. 按 [REGISTRY-FIXTURE.md](REGISTRY-FIXTURE.md) 构建并安装独立夹具包，保留同一次 run 的材料、APK 和 SHA；不要卸载或覆盖正式 ThinkOS。
2. 在正式 ThinkOS 的公开设置把“返回时间”设为 0，记录原语义值；从公开“应用”网格打开 **ThinkOS REG 验收**。正式 ThinkOS 继续是 HOME 与原 Device Owner；夹具不是 HOME 或 Device Owner。
3. 设备中文，音频 1–5 全为 0，无其他人操作。准备已经编译的 ThinkHierarchy jar。本脚本不编译或下载辅助程序。
4. 正式包四个 APK/module pending 与 .bak 必须均不存在；脚本保守拒绝所有既有 pending（包括 terminal），不改写或自行恢复。
5. 使用全新夹具 registry 数据域；若已有 installed 记录，脚本停止，保留证据。不要删除 ledger/floor/blocked 或使用旧 snapshot/rawrestore 来重跑。

首次从公开网格打开夹具若出现安卓平台权限窗口，先由统一验收 Agent 审核处理，再确认夹具 Main 已在前台。driver 不自动授予或拒绝平台权限，不把这个窗口当作插件的原生权限确认；遇到此窗口或其它未知前台只停止。新建私有输出目录后，材料/APK/schema/前置检查和监测器创建阶段的失败也记录到`failure.txt`，不抢前台、不对未初始化对象做设备清理；目录验证或创建之前的错误不会向目标路径写入文件。诊断文件写入失败时保留原异常。

```sh
export ADB_MDNS=0
python3 -B tools/tests/registry_fixture_device.py \
  --serial HUA0BWUT \
  --material /private/tmp/reg2b-<32hex> \
  --apk /private/tmp/<pinned>/app-regFixture.apk \
  --hierarchy-jar /private/tmp/<existing>/hierarchy.jar \
  --output /private/tmp/thinkos-regfixture-lifecycle-<unique>
```

`--material` 是已有 `Material.open()` 目录，端点应为固定 loopback 8877 路径。脚本按该端口建立只属于本 run 的 adb reverse；若已被占用就停，不覆盖既有映射。整个 Origin 进程独立于目标 App PID；冷启动前关闭 Origin，不添加防火墙或关闭整机网络。

执行范围：v1 真实触屏确认并连续健康 600 秒；v3 新增媒体/位置权限取消后再真实确认、校验一个真实暂停视频队列并在插件页面“停止媒体”、再健康 600 秒；v4 相同权限更新，保持真实 trial 中；真实 HOME 回正式桌面后只 force-stop **夹具**，从正式公开应用网格启动普通 Main 新 PID。验证固定 APK 根与认证缓存恢复健康 v3、保留 blocked v4、cache/ledger 字节未变；再通过 v3 页面停止冷恢复新队列，最后真实 HOME 返回正式 ThinkOS 的 home.main；如有公开层残留，只用返回键和普通横向翻页收回首页。

每 25 秒真实触摸 Hub 当前“已安装”页签维持通常交互状态；不设置 attention override、不改 trial_s、不缩短计时。日志使用当前夹具 PID 查询并再次解析 PID；不会把同 TAG 的正式 ThinkOS 日志当夹具启动证据。首次v1/v3实际激活后立即捕获精确版本的启动行，并同当前PID、已认证digest、启用记录与实时VM绑定，最多保留8份有界私有host证据；健康期末使用这些已捕获事实并另查当前record/active。冷启动新PID必须重新捕获，健康期末不会因环形缓存滚失而丢掉初期已捕获的启动依据；没有捕获就失败，不从store或其它日志伪造启动行。单条媒体队列的生产snapshot是一基`count=1/index=1`。

原包 registry/update 目录仅做前后哈希读取；夹具私有文件也仅只读。初始 done/initialized、镜像、关闭自动更新与投屏经夹具 DebugReceiver 的正常 SettingsStore 设置并读回，安装/更新/权限/媒体停止全部经公开 Hub 或签名插件 UI。没有 private-file restore、回写、删除历史或 force-stop 正式包。

硬件触摸、陌生前台、失去监测或音量不为 0 均立即停止，不抢前台、不自动恢复旧数据。仅原包与夹具之间明确的有限过渡可观察空焦点，空焦点或错误包上绝不注入输入。失败后原目录、夹具 floor/blocked/试用历史及私有 TMP 报告保留；不要用删除历史的方法使下一轮变绿。成功/失败的 `result.json` 与 `failure.txt` 只在新建的 0700 主机 TMP 下，不能直接作为公开 artifacts 发布。

成功仅在所有本轮检查与原包树哈希比较通过后标 complete。原包正常运行引起差异时同样保留差异，不回写字节；由统一验收 Agent 判定是否为原包自己活动，并据实记录限制。成功后通过公开设置把正式 `apps.return_after_s` 恢复到先前记录的语义值；本脚本不猜测这个值，不写正式设置。

主机边界用例（仅根 Agent 统一执行）：

```sh
python3 -I -m unittest discover -s tools/tests -p test_registry_fixture_device.py
```

这些 fake transport/CLI 测试不声称设备生命周期、真实 600 秒或全 REG-2b 通过。
