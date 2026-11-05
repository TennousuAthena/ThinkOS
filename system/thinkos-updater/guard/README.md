# 模块之外的恢复守卫

这些是待所有者人工安装的源码/产物模板；开发 Agent 不把它们写入设备，不重启，也不模拟真实系统引导失败。

构建输入在现有 updaterd、密码库之外增加 `src/guard_policy.c`。同一 armv7 可执行文件另留一份稳定副本，人工放在 `/data/adb/thinkos/guard/updater-guard`（root:root、0755），不从被更新的模块目录执行。两个脚本分别人工放在 `/data/adb/post-fs-data.d/thinkos-guard.sh` 和 `/data/adb/service.d/thinkos-guard.sh`。模块 OTA 不覆盖它们。

策略来源是系统设置 schema 的隐藏 `update.module_health_s`（默认900秒）和 `update.module_guard_poll_s`（默认5秒），用下面命令生成本地产物：

```sh
python3 system/gen_guard_policy.py --output system/build/guard-policy
```

所有者将该产物人工放在 `/data/adb/thinkos/guard/policy`（root:root、0600，父目录0700）。生成工具可给同名设置传入合法范围的 `--health-s`/`--poll-s`。守卫只读固定 root 私有文件，不读取应用数据；缺失、损坏、权限错误或与进行中事务的冻结策略不一致时停止自动恢复，没有写死的静默兜底。未增加其它偏好存储。

`--guard-early` 在挂载模块之前记录本次 boot_id；只有同一未完成事务的上一次启动确实没有达到 `sys.boot_completed=1`，才先同步恢复声明，再恢复备份的 Magisk 模块，删除待生效新版。Vector 先禁用钩子，APK与数据库在系统完成启动后恢复。它不调用 setprop；目前没有写系统属性的需求，若将来需要必须用 resetprop。记录恢复声明失败就不动模块；中途失败不自动反复尝试。

`--guard-watch` 观察实际 `boot_completed`，从这一刻开始默认15分钟健康等待。APK替换但不重启时，从首次观察到该次APK安装后的就绪状态开始等待。超时请求固定 `GUARD_ROLLBACK <transaction_id>`：内核 peer UID 必须为0，事务id必须仍相同，deadline、早期恢复状态和一次预算重新从私有持久记录验证。守卫和 daemon 共享文件锁并重载 journal，避免 HEALTHY/新BEGIN/旧超时之间的竞态。模块 daemon 损坏或不支持此内部命令时，稳定副本持同一锁执行相同检查和恢复流程，因此更新器自身故障不依赖新版 daemon。

一次自动恢复声明、旧APK/模块恢复、重启预算按顺序同步。每事务至多一次自动恢复和一次自动重启；失败后停止自动动作，等待人工。崩溃可能发生在声明与动作之间，因此不能承诺每次已声明动作一定执行，但不会为补偿回执丢失反复恢复或重启。没有正式钥匙的分发事务仍拒绝；root守卫只恢复已由旧事务保存的私有备份，不接受新包、路径或钥匙。

新增主机测试使用私有目录、模拟boot_id/单调时钟和固定假执行器，不代表真实坏模块、断电或重启验收。真实引导失败演练仍需要OWN-4与所有者在场。
