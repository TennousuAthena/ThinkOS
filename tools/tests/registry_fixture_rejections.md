# REG固定夹具：四拒绝，最后吊销

只用于既有同根、独立UID的`moe.kiwi.thinkos.regfixture` F030（0.1.30/1030）。不装APK、不instrument、不写设置、不force-stop、不清数据或floor/blocked/history、不改变600秒。与正式UID/DO/全局手势、原生不洁、自动恢复、16插件和性能无关。

所有者在本轮已授权的外层准备仍由root统一执行：确认五音量0、没有人工输入；保持正式O现有`apps.return_after_s=900`，不写该偏好；通过公开安卓应用网格打开F。首次安卓文件权限窗口由root审查处理，actor不自动Allow/Deny。F必须原已healthy1.0.2且权限network/media/location，媒体由公开停止按钮清空，mirror与本次同根material一致、auto/cast关闭、无pending。不要为满足起点清理或重装F；不满足就保留并停。

CLI显式接收当前F030真实APK（不是旧current.json猜测），核安装APK全SHA、APK固定roots和实际独立UID/API27/armv7、O HOME/DO、O/F无事务、中文和设置、O返回时间仍900；host当前认证catalog不可落后/分叉实际F floor。外层先确认fixture原域允许的本轮输入，所有输出是新0700私有TMP。CLI完整活跃流程由主机540秒单次alarm及每次Guard绝对deadline限制（不修改设备任何timer/权限/设置）；超时立即停止、不抢回前台，不因流程未完再续预算。既有host alarm存在则拒绝，不覆盖外层alarm。

```sh
ADB_MDNS=0 python3 -B tools/tests/registry_fixture_rejections.py \
  --serial HUA0BWUT --material /private/tmp/existing-reg2b-material \
  --apk /private/tmp/pinned-F030/app-regFixture.apk \
  --hierarchy-jar /private/tmp/prebuilt/hierarchy.jar \
  --output /private/tmp/new-reg-fixed-rejections
```

路径为占位。入口自有Origin和loopback reverse必须未被占用；不会接管已有port/reverse。若root已有同一Material/Origin/Guard/Hierarchy外actor，可只构造`RejectionDriver(device,guard,hierarchy,material,origin,output)`并调用`run()`，由该已认证外层完成同样540秒host绝对预算、BudgetGuard、前置/原树基线和统一收尾，不重复启动origin或改变公开交互；不得只用无deadline的旧Guard执行。普通CLI只通过固定GET Origin提供数据，没有网络签名/控制接口；签名只由主机Material使用同一次一次性fixture私钥，绝不是正式钥匙。

顺序固定home→settings→system→tampered-blob，每次只追加更高真实registry seq，不回放catalog。公开Hub详情→原生对话框检查精确name/repo@commit/tier/权限原因→真实触屏安装；确认的只有签名目录声明的network，不授manifest里隐藏的system.settings。随后必须实际请求精确files index、公开“操作失败”、该样本VM不存在、原健康v3仍活跃、整个registry-state原字节不变；tampered还要求实际坏blob请求。cache/ledger/已下载验证材料可自然增长，不宣称所有F目录不变。

最后发布正常level3并`revoked=True`：真实刷新将v3 enabled改false/error=revoked，VM消失，公开详情显示“已停用”“测试台撤销此版本”。除此以外registry-state字段及blocked必须保持；**这是有意、耐久的F状态变化，不能还原成healthy或声称F unchanged**。后续测试必须按这次更高seq和真实吊销事实规划；本actor不实现解除吊销/恢复出厂。

成功才经KEYCODE_HOME和已知两包观察窗返回O的home.main；全程陌生前台/物理输入立即停，不抢屏。保留result.json、state前像（私有）、各失败/吊销PNG、每个新seq/签名摘要和O registry/update完整哈希前后。另在每个负例、吊销及返回O后核固定名耐久SHA：O原9文件、O registry-state、O/F native-failures及bak、recovery/deck及bak（F原9也稳定）；不读出文件内容、不回写。单文件≤2MiB、拒symlink/非regular/多硬链接/非本UID，stat前后稳定才接受hash。F registry-state由四拒绝字节相等和末吊销精确差异独立验证，F cache/ledger保持真实单调增长，不冻结/恢复它们。native-flight是活动归因标记，不纳入耐久相等声称。最后停止自有origin、精确删除自有hierarchy jar/oat与自有reverse；清理失败complete=false/非0并保留现场，原异常不被替换。外层只读确认O仍900及原界面/管理/数据/静音，不需恢复return_after。

本提案仅源码与8项focused主机边界测试源码，尚未执行。root统一静态审查、主机测试和真实设备验收，不能提前勾REG-2b或宣称四拒绝/吊销通过。
