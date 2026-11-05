# BT-0：API27 Bluetooth Sink 资源实验

2026-11-05：根agent已完成7项主机测试与模块构建；常规`system/test/run.sh`也通过7项BT及原18项wfdd检查。没有安装模块、重启、开启蓝牙或配对/播放。BT-0整体没有通过，BT-1至BT-3仍须等真实可行性实验。代码独立实现，无复制AOSP实现、无新增库或原生二进制；项目MIT许可。

## 已确认的输入

根agent的[只读采样](../../artifacts/bt/BT-0/resource-preflight-1/results.json)确认目标包`com.android.bluetooth`位于`/system/app/Bluetooth/Bluetooth.apk`，SHA-256为`7568679597d73871be7f5fb526938080ab0e67afcccffd02baf13c7459d36767`，`profile_supported_a2dp_sink`与`profile_supported_avrcp_controller`默认均为false。蓝牙保持0。`/system/vendor -> /vendor`，已有static overlay的实际路径为`/vendor/overlay/framework-res__auto_generated_rro.apk`。原APK只保留在仓库外私有目录，不随模块分发。

## 为什么使用静态vendor overlay

[AOSP Android8.1 PackageManagerService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.1.0_r81/services/core/java/com/android/server/pm/PackageManagerService.java)的vendor overlay启动扫描带可信标记；[OverlayManagerServiceImpl](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.1.0_r81/services/core/java/com/android/server/om/OverlayManagerServiceImpl.java)为新static overlay默认启用，并拒绝用动态setEnabled修改static。模块的资源APK放`system/vendor/overlay/ThinkOSBluetoothSink.apk`，由[Magisk目录映射](https://topjohnwu.github.io/Magisk/guides.html#the-system-folder)提供给`/vendor/overlay`；该映射不直接写系统分区。人工安装、重启后才能验证实际idmap和两个资源是否生效，不能从源码推断这台ROM已经接受。

模块只有两条bool=true、无代码APK、安装检查和module.prop。min/target均27；人工安装检查ARCH=arm、API=27、Magisk app/CLI模式、原Bluetooth.apk精确SHA和普通overlay文件。没有service.sh、post-fs-data、system.prop、sepolicy、`.replace`、开启蓝牙/配对的命令或后台进程。不安装动态overlay，不启用Vector备选路径。

## 主机入口（仅供根agent统一执行）

```bash
bash system/test/run-btsink.sh
bash system/build-btsink.sh --bluetooth-apk /仓库外私有目录/Bluetooth.apk
```

构建读取`android/local.properties` SDK和当前compileSdk的android.jar，默认需要已存在的`~/.android/debug.keystore`，不生成钥匙。可显式传`--sdk`、`--debug-keystore`；该参数仅适用于本地debug证书，固定debug alias/口令，不是正式签名迁移。构建前验证原APK SHA、包名与两个实际默认资源；输出`system/build/thinkos-btsink-v1.0.zip`。APK只允许manifest/resources/signature条目，不带dex或任何ABI的so；目标限定仍由安装门禁执行。

七项纯主机测试已通过：真实SHA门禁/非普通文件、资源默认值漂移、无代码/权限声明、有界APK条目、固定ZIP路径与权限、错误ABI/API/ROM/recovery的拒绝、合法安装只做权限。资源夹具不冒充真实overlay加载。

实际产物见[builds.json](../../artifacts/bt/BT-0/builds.json)：ZIP为5108字节、SHA-256 `ff77ddc5ede6915f8776853c182e38cf4b896c02d02e71109ba2a1cc1f129b98`；内含资源APK为8538字节、SHA-256 `4cd3b5d7cb9991f33d0e26ed928dd479c5e80821ff13c02b2f0af1ec41d83ba3`。原始[host-test.log](../../artifacts/bt/BT-0/host-test.log)、[build.log](../../artifacts/bt/BT-0/build.log)、[system-host.log](../../artifacts/bt/BT-0/system-host.log)以及[manifest](../../artifacts/bt/BT-0/xmltree.txt)/[resources](../../artifacts/bt/BT-0/resources.txt)核对均已留存；资源only/API27，不含dex或so。`builds.json.complete=false`保留，安装前必须重新核对上述固定ZIP SHA。

人工安装、预期输出、配对/声音边界与回滚见[BT-0人工步骤](../../artifacts/bt/BT-0/human-steps.md)。仍未验证：该ROM加载/idmap、开蓝牙后的A2DP sink、LockTask配对、系统确认、真实扬声器/AVRCP/编码/蓝牙类别。后四项要所有者在场，不能在无人值守静音期间完成或假报成功。
