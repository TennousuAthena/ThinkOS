# REG F030 运行期 Lua fault：一次真实五事件

此源码只补既有独立F030、真实healthy1.0.2前置下的运行期Lua故障。Root唯一构建/测试/运行者；本提案未执行任何测试或设备命令。它不授权安装/重启，也不测试原生崩溃、安全模式、自动恢复、启动故障、16数量或性能，完整REG-2b仍未完成。

继续用同root Material及已安装F030全SHA/固定APKroots，不新建密钥、重装、改配置或回写历史。原blocked 1.0.3及1.0.5必须存在且reason=trial_interrupted；1.0.4不可已blocked，状态必须已有healthy1.0.2、已授network/media/location、媒体公开停干净，无原/F pending。与既有拒绝actor同样要求原O仍HOME/DO/返回900/静音/中文及监测无人工输入。先经正常公开网格打开F，任何权限弹窗都由Root另审，本actor不点Allow/Deny。

```sh
ADB_MDNS=0 python3 -B tools/tests/registry_fixture_runtime_fault.py \
  --serial HUA0BWUT --material /private/tmp/existing-reg2b-material \
  --apk /private/tmp/pinned-F030/app-regFixture.apk \
  --hierarchy-jar /private/tmp/prebuilt/hierarchy.jar \
  --output /private/tmp/new-reg-runtime-fault
```

以上是占位路径，Root必须换成实际固定材料。复用现host发布level5但先完整重建/逐摘要验证它确为原有1.0.4固定public tap故障脚本；仅主机使用原一次性fixture钥匙签新增单调seq，没有HTTP控制或正式钥匙。真实公开Hub→更新（同已授权限，不应弹新授权）→打开1.0.4。每次只触屏一次“触发错误”，等待对应真实Lua报错；前1–4次须同PID、原trial journal逐字节相同、活跃非0VM、只有原来的单次REGISTRY_STARTED/loaded见证。公开diagnostic不暴露内部VM handle，因此不会宣称读取到pointer身份。

第5次经过真实PluginHealth.RESTART→RegistryRepository.failed→rollback；最终state必须为原healthy1.0.2完整记录/confirmed/auto加且仅加1.0.4 trial-failed blocked一条，原1.0.3/1.0.5及其他blocked逐项保留。保存第五条真实错误和其后实际1.0.2 startup；同PID，不kill/force-stop/Java/native crash。v3回退会正常重建它的暂停视频，只用现公开打开→停止媒体清精确own queue。固定O/F nine/native/recovery/deck历史逐SHA保护；O完整registry/update树前后比较；F认证cache/floor/download可自然增长，seq必须固定为本轮真实新增值，不还原。

总活跃预算复用540秒单次host alarm及BudgetGuard，不修改真实600秒策略；故障完成前超时/未知前台/物理输入/多出错误或启动见证立即失败保留，不能重发关键tap或清blocked重试。成功经正常Home返回原O，仅精确清自有helper/reverse并停自有origin；cleanup失败不得complete/返回0。

顺序：此actor应在最终签名吊销之前。它会有意增加1.0.4 blocked；之后四拒绝actor应从这个真实新baseline捕获，而不是要求回到旧状态。最终吊销才把v3停用，不能为测试再解除吊销。更高启动故障尚需一次受限材料追加（旧1.0.1低于当前healthy1.0.2会先not-newer），本提案不追加、不签发该材料。
