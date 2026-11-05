# OTA-5 固定签名语义负例

`tools/ota_negative.py`只准备两种实验材料：目录通道不符、APK目录摘要不符。它沿用现有catalog schema；不改正式`release.prepare`、`ota_lan`的非debuggable规则，不让正常`Ota5Origin`接收实验类型，也不启动服务/设备/构建/密钥生成。本工具不提供owner-debug-crash材料；现有源码是v2不等于实际签名APK已证实支持v2，缺少该事实证明时这项继续待办。

输入必须是已执行的**APK-only** `ota_lan`输出目录及保存的result JSON、owner公钥导出。工具重新验channel角色签名、唯一owner APK证书、真实min/target27、ARM32/armv7、APK精确hash-size/version和两份公钥资产，不接受额外debug roots。只核调用者提供hash表不足以通过这些检查。实验路径须为独立数字私网HTTP根。

默认plan只读公开输入和SDK元数据，私有TMP快照自动结束；不创建指定output、不打开私钥或口令FD、不签名。保留plan的input_sha256；显式execute必须输入该值并重新核输入字节，最后才读取既有加密owner **channel** key。私钥和口令仍在仓库/CI/日志外。工具不生成key、不发布，也不连接任何网络。

```sh
python3 -B tools/ota_negative.py \
  --lan-dir /private/tmp/verified-normal-lan \
  --lan-result /private/tmp/verified-normal-result.json \
  --public-dir /private/owner/public \
  --case apk-digest-mismatch \
  --lan-root http://192.168.1.20:8765/ota/negative-digest \
  --seq 103 --initial \
  --output /private/tmp/new-negative-material \
  --private-key /private/owner/channel-active.key.pem
```

以上路径为占位，必须替换为已核材料。执行相同命令时再加`--execute --reviewed-sha256 <plan值>`及私有`--password-fd`，或在owner交互终端输入口令。`--initial`表示没有额外签名序号前驱；已有历史用`--previous-catalog`及相邻`.sig`，工具仅以认证文档保留更高seq，不把负例当正常前任APK，也不读取设备floor。操作者仍须把设备已见最高seq纳入规划。

- `wrong-channel`：输出文件仍用原请求通道名，签名文档的channel翻成另一合法值；应在通道校验拒绝。未实测设备前不声称floor一定没有推进。
- `apk-digest-mismatch`：真实已签APK原字节保留，只把新目录的APK SHA改为另一个合法64hex；它仍可通过目录验签并消费新seq，随后应在包摘要校验拒绝。**后续正常修复目录seq必须更高，禁止清floor/history回放旧seq。** 正常工具仍只接合法release输入，不能把本实验目录伪标成release。

除固定语义变化外，仅调整seq和显式实验LAN URL，其它源目录字段保持。输出只含原APK、新目录及channel签名，catalog最后提交；实验类型和拒绝阶段在CLI result中明确标记。保存result到输出目录外；正常Origin继续拒该类型，未来完整负例runner需单独受控接线，不能靠任意HTTP目录冒充已验来源。

仅主机源码/一次性真实PKCS12/P256/APK签名测试，不等于正式钥匙、备用机迁移、来源UI拒绝、自动更新、崩溃/回滚或完整OTA-5通过。实际验收仍依OWN-6/OWN-4和已有人工边界。
