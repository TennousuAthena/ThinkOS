# APP-9 官方目录元数据最小验证

新增 `radio_public_metadata_device.py`，继承本地目录驱动的输入守卫、真实设置输入、原字节快照与恢复。只写源码，尚未运行；不修改原驱动或生产。两个候选 APK 的 SHA-256 都由操作者显式传入，不在脚本固定某次构建值。

```sh
python3 -B tools/tests/radio_public_metadata_device.py \
  --apk-sha256 '<已安装主APK完整SHA256>' \
  --test-apk-sha256 '<已安装测试APK完整SHA256>' \
  --apk /owner/path/app-debug.apk \
  --test-apk /owner/path/app-debug-androidTest.apk \
  --output /tmp/thinkos-radio-official-metadata
```

唯一公网目标固定为 `https://all.api.radio-browser.info`，国家DE，空分类。通过现有实际 SettingsStore handler 临时改六项：`test=false`、`click=false`、`country_auto=false`、`country=DE`、`directory_url=固定官方端点`、`directory=true`。原目录必须关闭，旧 source/stream 都为空。实际横滑到应用磁贴页并触屏开“广播”；不点台、不调用 media.load/probe、不发送点击上报、不改 CA/TLS/默认网络超时，不开主机源站、不新增/删除 adb reverse。继承基类只读核对现有 reverse 清单。

成功需要同时看见真实站名、正常“刷新”页脚、没有失败/加载提示，并有生产 Lua 实际写盘的非空DE缓存（≤100台、≤64KiB、有界字段、UUID去重、HTTPS、public_only）。仅有旧缓存不合格：失败回退仍保留失败提示。等待合并落盘后再次做白名单/原数据核对。公开结果仅给数量、UUID及最多3个不含URL/查询分隔符的公开台名，绝不复制流URL、原缓存、请求令牌或原设置；原始缓存/日志只在0700私有journal，文件0600。

证据分层：没有独立的原生 HTTP 状态/TLS遥测。成功时只能写“生产 `M.fetch` 仅接受HTTP200，结合成功页脚与官方DE缓存，推断此次有HTTP200响应”。不能把这个推断写成原生网络测量；不声称测到TLS根、TLS版本或TCP。端到端耗时含设置、Lua、页面观察及落盘，不是HTTP请求延迟。未改超时意味着沿用了现有默认值，不证明所有网络条件都能在默认时间内完成。

官方失败仍记失败、`complete=false`，不通过缓存或重试假报成功；在Guard允许、资料仍全部可归属本轮时恢复四个原设置/radio文件。白名单只放本次固定端点+DE+空分类，以及逐项不变的原缓存/原空手动源缓存；原收藏、健康、未知radio键与其它scope必须保持。首次capture时还没设置官方endpoint，完全沿用基类严格基线逻辑，不放宽为任意来源。

原page/variant/holds/方向、DO/admin/HOME/LockTask、五静音与基类相同。deck/recovery仅做语义审计不回滚；其余immutable字节不变；只恢复四个固定settings/radio路径。人触、意外前台、非静音或新媒体立即停，不重获屏幕，保留journal。恢复使用同一双hash、新输出目录和该脚本的 `--restore 私有journal`。

此项不等于APP-9整体完成，也不验证MP3/AAC/HLS解码、流探测、健康/重连、点击关闭时的真实播放请求或全部分类。官方数据许可已由主agent登记，本计划不重新声称许可未明。
