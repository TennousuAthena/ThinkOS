# 移除设备管理：仅取消验收

由 root 单一执行 `management_exit_cancel_device.py`。开发阶段未执行。只适用于已安装的固定 0.1.9：主包 `74cc263027c07d85a7b41ad0bddba23a5899e324b7d91576bcf1c761af619f41`，测试包 `9dc76dfbec4666f737ef951cb81f6437c3d1d6346fa29301214eb96bf2f4583d`。脚本不构建、安装或重启；只使用已有缓存 hierarchy jar，主机裁图使用现有 Pillow。

静态检查：`MainActivity.confirmManagementExit()` 的打开路径只创建原生确认框并绑定 `NativeTouchConfirmation`；`kiosk.surrender()` 位于其 accepted callback 内。打开动作本身没有写管理退出记录、清除 owner 或停用 self-admin 的调用。这是源码判断，真机仍须核对。

实际流程为导航型 `maintenance` → 真实点按“移除设备管理”菜单项 → 核对标题、正文“退出锁定与自动恢复，设置和插件保留”、正向“移除管理”和负向“取消”。竖屏只触屏取消；横屏只发 Back。正向按钮只读取文字/边界，禁止正向 ACTION_CLICK、触摸或 owner API；不以此脚本验证实际移除、卸载或重新设 owner。

开始要求正常 MainActivity 的空基础页、无媒体/投屏或队列、无更新事务与法律/更新窗口、中文、音量流 1–5 为零。每次打开与取消后读取真实 `dumpsys device_policy` 的 owner/self-admin、`dumpsys activity activities` 的 LockTask 模式和白名单，以及当前 HOME preference，必须与原值一致。五份原始文件只比对，不写回旧数据。`clock.face/solar` 等原 page/variant、原 holds 与方向需要准确恢复；维护菜单底层回 Home 不算外部应用切换，也不作为最终恢复目标。

原始 UI、策略、未裁截图与操作恢复记录保留在 0700 私有目录，文件 0600；公开截图只有原生确认框。人体输入、音量非零、进程变化或失焦时停止，不抢回前台。失败保留日志；明确 `--restore JOURNAL` 仍须通过原 APK、原进程、五文件和管理状态核对，且只关闭已识别的本次原生窗口。未知新状态不覆盖，管理状态改变时不尝试恢复/重设 owner。
