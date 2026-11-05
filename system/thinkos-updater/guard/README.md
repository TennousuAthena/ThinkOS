# 模块之外的恢复守卫

守卫的作用：模块更新把系统搞到起不来，或新 APK 一直不健康时，不靠被更新的东西自己，把旧版本恢复回来。所以它放在每个模块之外。开发 Agent 不把它写入设备，不重启，也不模拟真实系统引导失败；持久安装由所有者在备用机上做。

## 布局（2026-11-06 起，随 OTA）

```
/data/adb/thinkos/guard/
  current/updater-guard     正在用的守卫（root:root 0755）
  previous/updater-guard    上一份能用的
  policy                    健康时长和采样间隔（root:root 0600，父目录 0700）
  updater-guard             旧安装留下的单文件，最后才用
/data/adb/post-fs-data.d/thinkos-guard.sh    启动脚本：--guard-early
/data/adb/service.d/thinkos-guard.sh         启动脚本：--guard-watch
```

两个启动脚本很小而且稳定，只负责选用哪一份守卫：依次 `current`、`previous`、旧的单文件。某一份起不来或立刻崩溃（退出码 126 以上，10 秒内）就换下一份；守卫自己的退出码（小数字）原样返回，不换。脚本第二行是 `# thinkos-guard-launcher N`，N 是版本。

**谁来更新什么**

- 首次安装 `thinkos-updater` 模块（`system/install-updater.sh`，所有者在备用机上运行）时，模块安装器 `customize.sh` 做三件事：没有任何守卫时，把包里的 `updaterd` 当作第一份 `current`；`policy` 不存在时放入包里的（由 `system/gen_guard_policy.py` 按设置 schema 渲染，构建时可加 `--guard-health-s` / `--guard-poll-s`）；两个启动脚本不存在时放入，存在但版本更低（没有版本行的老脚本算 1）时替换，**绝不降级**。已有的 `policy`、`current`、`previous` 一律不碰。
- 之后每次更新 `thinkos-updater` 自己的 OTA，在新版本通过健康期（新开机、模块已生效，`HEALTHY` 的持久成功已经答复）之后，运行中的更新器把自己的文件拷成新的 `current`，旧的 `current` 变成 `previous`，更老的丢弃。失败（源是符号链接、超过 4 MiB、写不下）只会让旧守卫留着，不影响这次更新。别的模块的更新不碰守卫。
- 启动脚本只有在新版随更新器模块进来且版本更高时才被替换（同样在模块安装器里）。启动脚本本身的改动要极小心，每次改动都要在主机测试里覆盖。

守卫逻辑（和以前一样）：`--guard-early` 在挂载模块之前记录本次 boot_id；只有同一未完成事务的上一次启动确实没有达到 `sys.boot_completed=1`，才先同步恢复声明，再恢复备份的 Magisk 模块，删除待生效新版。Vector 先禁用钩子，APK 与数据库在系统完成启动后恢复。它不调用 setprop；若将来需要必须用 resetprop。`--guard-watch` 观察实际 `boot_completed`，默认 15 分钟健康等待；APK 替换但不重启时，从首次观察到该次 APK 安装后的就绪状态开始等待。超时请求固定 `GUARD_ROLLBACK <transaction_id>`：内核 peer UID 必须为 0，事务 id 必须仍相同，deadline、早期恢复状态和一次预算重新从私有持久记录验证。守卫和 daemon 共享文件锁并重载 journal；模块 daemon 损坏或不支持此内部命令时，稳定副本持同一锁执行相同检查和恢复流程，因此更新器自身故障不依赖新版 daemon。一次自动恢复声明、旧 APK/模块恢复、重启预算按顺序同步。每事务至多一次自动恢复和一次自动重启；失败后停止自动动作，等待人工。

策略来源是系统设置 schema 的隐藏 `update.module_health_s`（默认 900 秒）和 `update.module_guard_poll_s`（默认 5 秒）。守卫只读固定 root 私有文件，不读取应用数据；`policy` 缺失、损坏、权限错误或与进行中事务的冻结策略不一致时停止自动恢复，没有写死的静默兜底。

**测试：**`system/test/run-guard.sh`（启动脚本顺序、崩溃回退、版本只升不降、首次安装、策略放置，16 项）和 `system/test/run-updater.sh`（含自更新后的轮换，5 项）。主机测试使用私有目录、模拟 boot_id/单调时钟和固定假执行器，不代表真实坏模块、断电或重启验收。真实引导失败演练仍需要备用机和所有者在场。
