# OTA-5正常来源公开UI演练

本增量是可执行的**正常APK-only** runner：原有host plan/只读preflight保持；新增公开设置→原生CHECK→原生DOWNLOAD→真实READY，以及显式原生INSTALL→普通安装新MAIN进程→真实600秒健康。源码和10项主机测试源码及1项真实SettingsStore JVM回归源码尚未由本agent运行，不代表真机通过。

全部沿用`tools/ota_lan.py`的已执行正常release材料及原`ota5_device.py`证书/公钥/production_roots绑定：真实owner APK单签名、相同六个生产公钥、min/target27、armv7、baseline精确SHA、向前target。UI执行要求baseline/target两份真实APK的5个更新default一致；改变default的迁移另测，不能用旧default错判安装后的effective值。`ota_negative.py`实验kind仍被普通Origin拒绝；本runner不接收负例、debug-crash、自填capability bool或任意URL/HTTP目录。

前置材料：仓库外owner正式公钥导出、已签baseline与N+1（N+1非debuggable且无额外debug roots）、已执行正常APK-only LAN目录和result、该baseline真实`production_roots`只读报告、维护者审过的预编译ThinkHierarchy jar。发布地址必须是主机实际能绑定、设备可达的数值RFC1918 LAN地址；此流程不用ADB reverse。签名/正式证书迁移仍依既有人工边界，runner不读私钥、不构建/安装baseline或test APK、不创建证书。

先准备同一组公共参数（下列绝对路径/序列号均为占位）：

```sh
python3 -B tools/tests/ota5_device.py \
  --serial SPARE-ENDPOINT --physical-serial SPARE-PHYSICAL \
  --lan-dir /private/tmp/reviewed-normal-lan \
  --lan-result /private/tmp/reviewed-normal-result.json \
  --public-dir /private/owner/public \
  --baseline-apk /private/tmp/owner-baseline.apk --baseline-version 0.2.0 \
  --roots-observation /private/tmp/actual-baseline-production-roots.json \
  --hierarchy-jar /private/tmp/reviewed-hierarchy.jar
```

默认没有ADB/网络监听/输出目录/签名。保存`ui_plan.ui_input_sha256`；它绑定实际签名材料、原始result/roots报告、固定spare身份及jar字节。READY-only执行为相同参数加`--execute-ui --authorize-spare SPARE-PHYSICAL --reviewed-sha256 <该值> --output /private/tmp/new-ota5-ready-report`。

**正常安装计划**在相同默认参数加`--plan-install`，仍无ADB。确认该计划后以相同参数改用`--execute-install --authorize-spare SPARE-PHYSICAL --reviewed-sha256 <安装计划值> --output /private/tmp/new-ota5-normal-report`。READY-only与install的fingerprint不同，不能互用；没有reviewed SHA/明确spare标识或缺实际APK设置schema时在任何ADB前拒绝。

执行限制与行为：

- 严拒HUA0BWUT、其实际物理别名、unknown、serial不符；实际API27/armv7、精确已装baseline SHA、已有ThinkOS DO/HOME、五音量0、中文公开页面、正常运行PID必须成立。DO不存在的系统确认路径不在此runner内；不设置/清除DO、权限或模块，也不调用adb reboot/root/remount。
- 正式release没有DebugReceiver。host仅以已存在只读root查询固定APP私有名，逐层拒symlink/超限/非regular并核读取前后身份；不写任何私有业务文件。无需运行target instrumentation或临时覆盖roots。唯一设备临时写是已review jar/UIAutomator自己的owned TMP；不执行外部下载代码。
- 原来`update.auto_install`必须false、update.enabled true、隐藏update.sources为空；没有真实pending/module事务或正在下载/安装，没有已有safe-mode/crash事件可被本次健康更新清掉。现seq严格小于材料seq。条件不符保留数据并拒绝，不自动调整阈值、计时、开关或history。
- 首先只读获取完整native所有window hierarchy，确认唯一普通Main、没有确认/编辑/更新窗、可证不覆盖任何交互控件或外部window的空白corner，才一次触屏作wake准备；随后重新读真实页面。600秒后或下载窗退出后的来源恢复前也重复这项独立wake准备。它不读取debug attention override，不把Clock/Solar自动attentionRole变化当外部用户；关键CHECK/DOWNLOAD/INSTALL永不重复。
- 经已有公开系统设置入口（可由应用内下拉控制面板“设置”进入），选择“系统/高级/更新通道/更新镜像”；EditText按实际全text有界删除、输入经过审阅的LAN URL，并真实点“确定”。只改本次channel/mirror。回普通系统页，软件更新row可为“软件更新”或精确的既有更新结果消息。
- 只发真实触屏CHECK/DOWNLOAD；核原生确切目标版本与“更新已下载/立即安装”。同时读取生产READY、独立seq/digest floor、缓存原始body+signature与source、实际下载APK完整SHA，前后重读一致；state READY本身不被当独立验签证据。
- `--execute-ui`绝不点安装。`--execute-install`重新核上述完整READY、auto仍false后只发一次真实“立即安装”触屏；观察已有PackageInstaller app UID会话，actualpending必须完整绑定from/to、catalog、seq、manual、health_ms600000。只读接受180秒已有安装替换窗口；未知前台/物理输入立即停止，不amstart/force-stop/抢回。
- 普通新MAIN PID必须是精确target SHA、首次attempt1、同transaction/trial_id。立即保存该PID真实首帧/catalog health日志，随后真实主机连续观察600秒（不虚拟推进、不instrument、不重启/清日志）。任何早于原生600秒门槛的pending清理、进程变化、回滚或事务变化判失败。完成必须自然清pending/previous、健康result/时间/版本与平台session成功一致、没有手动系统许可批准。
- 现有failure/blocked记录不清；正常healthy_update新增transaction确认属于生产语义，单独验证，其余native/deck/registry历史逐字节比较。不把允许的OTA result/floor/cache/下载增长称为全目录unchanged。原local/server除channel/mirror外须保持；固定HISTORY白名单不代表全部自定义userstore已逐文件冻结。首触前在私有receipt保存原channel/mirror local presence及raw值、Server存在性和原字节SHA；Server原文只在RAM核对，不落无关明文。SettingsStore公开set写回等于server/default时本来就删除local override，因此原local absent无需虚构不存在的字段长按reset。用同一公开表单恢复后，presence/value与Server字节均须相等；原local冗余或非法值无法通过正常set精确重建，在任何UI写入前拒绝。来源和Server使用公开set前后的只读观察核对；观察到不一致就失败并保留现场。这不是与SettingsStore同锁的原子CAS，读与公开set之间仍有竞态，不能保证原子阻止并发Server修改；只能在最终校验失败时记录事实，不以旧值raw restore掩盖并发。不raw restore，也不回退seq/cache/READY或已安装APK。
- 成功保存私有result、READY PNG、脱敏origin事件及真实pending/session/600秒样本，检查请求头无标识，再精确清自有jar/oat和停止自有Origin。失败/人工输入只保存现场，不恢复旧事务、不重复触屏或重发安装；cleanup失败complete=false。新签名seq已被消费时不能清floor重跑旧材料，需按实际最新状态审阅后续更高seq。

本normal case的“重启”仅指PackageInstaller替换后的普通新MAIN进程；没有物理系统重启或安装后额外强杀冷启动。完整OTA-5仍缺：安静时段自动安装、全部认证/语义拒绝矩阵、已证明v2能力的owner-debug crash材料及真实三次崩溃/回滚、无DO备用机系统确认，以及必要的人工迁移。不可因为源码可执行或主机fake UI/虚拟等待测试而勾这些真机项。
