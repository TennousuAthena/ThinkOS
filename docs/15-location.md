# 15 · 位置设置与地点搜索

设备没有 GPS。`location.mode = auto` 只读取设备时区在自带 IANA `zones.tsv` 中的代表城市及坐标，因此界面明确标为“近似”。不请求定位权限，不联系 Server；时区没有代表坐标时使用已保存的手填坐标，并返回 `source = manual-fallback`。

## 配置与离线使用

系统设置中的“位置”组提供以下项目；默认值与范围来自 `shared/schema/settings/system.json`。

| key | 用途 |
|---|---|
| `location.mode` | `auto` 时区代表城市 / `manual` 自选地点 |
| `location.name` | 点击进入城市、区县搜索器；也可手填名称与坐标 |
| `location.latitude` / `location.longitude` | 纬度 −90…90 / 经度 −180…180，东经为正 |
| `location.elevation` | 海拔 −500…10000 米 |
| `location.timezone` | 自选地点的 IANA 时区，留空使用设备时区 |
| `location.search_url` | Open-Meteo 兼容的地名搜索接口，可设为自建服务 |

选中搜索结果会一次提交名称、坐标、海拔、时区及 `mode = manual`。`SettingsStore.setMany` 先校验全部字段及跨字段约束，再原子修改，监听者只收到一次完整变更。任何字段错误都不会留下半个地点。仍沿用设置默认值、Server 下发层、设备修改层及现有持久化。

搜索器底部的“手填坐标”始终可用；查询失败不会阻止返回、手填、显示本地内容或计算天文。手填支持负数、小数和地点时区。地点选择不修改 Android 系统时区。

手填表单保留手动位置配置的空/显式时区；时区代表城市来源的编辑初始值留空，表示使用设备时区。重开后原样保存不会把已解析的当前时区固定下来。`tos.location.get()` 等数据接口继续返回可用于计算的解析后时区。

## 搜索服务

默认使用 [Open-Meteo Geocoding API](https://open-meteo.com/en/docs/geocoding-api)。只有点击搜索或输入法“搜索”时才发起请求，在协程 IO 线程执行；同一选择器一次只运行一个查询。请求包含 `name`、`count=20`、`language`、`format=json`。结果显示 GeoNames 的省、市、区县层级（`admin1`–`admin3`），空字段和重复层级会省略；不同地区的覆盖程度取决于 GeoNames。无匹配结果可直接手填坐标。

HTTP 使用既有 `PluginHttp`，与插件相同：响应上限 1 MiB、连接和读取超时、有限重定向、拒绝 HTTPS 降级，明文 HTTP 仅能访问私网。无效坐标、缺少名称或时区的结果会被丢弃；结果时区必须存在于设备自带的时区数据库才能选择。关闭选择器后取消其协程，不投递迟到结果。

截至 2026-10-30，[Open-Meteo 免费服务条款](https://open-meteo.com/en/terms)仅供非商业使用，限额为每分钟 600、每小时 5000、每天少于 10000 次调用；商业使用需使用适用的付费方案。搜索器展示 Open-Meteo / GeoNames 及 CC BY 4.0 署名。无需 Key 的默认接口适用于本项目的个人设备用途；服务条款及限额以服务商发布版本为准。

## 插件接口与表单契约

`tos.location.get()` 需要 `location` 权限，返回：

```lua
{ name = "…", latitude = 39.9, longitude = 116.4, elevation = 0,
  timezone = "Asia/Shanghai", source = "timezone" } -- 或 manual / manual-fallback
```

位置由 `LocationProvider(settings).get().toMap()` 生产，只依赖本地设置和 tzdata。`tos.astro.sun(ms[, place])`、`tos.astro.moon(ms[, place])` 的完整返回值以 `06-plugins.md` 为准；地点包含自己的时区，避免远方地点按设备日期计算。

动态设置源 `source: "place"` 使用原有 `choice` 类型。`form.change(key, value)` 的复合值含 `mode/name/latitude/longitude/elevation/timezone`；内置设置插件将其映射为同组键，调用 `tos.settings.setMany(scope, patch)`。写入权限与 `tos.settings.set` 一致，只授予 APK 内置的设置管理插件，普通插件即使声明 `system.settings` 也不能修改系统设置。

## 验证

- `LocationProviderTest`：时区代表坐标、手填位置及独立时区持久化、缺少时区元数据的离线回退。
- `PlaceSearchTest`：区县层级、无效数据丢弃、结果数量上限。
- `SettingsStoreTest`：复合变更完整发布、任何字段错误时不写入。
- `SettingsDescriberTest`：手填表单保留“跟随设备”的空时区，重开后直接保存不固定解析后的时区；显式地点时区与公开位置数据保持正确。
- `python3 -I tools/tests/location_device.py --output <目录>`：目录插件热更新、公有 Lua 接口、权限拒绝、API-27 地名服务 TLS、Sun/Moon/Seasons 数据及耗时；测试不修改用户设置，最后移除自己的临时插件。真机结论以执行后的 `results.json` 为准。
- `python3 -I tools/tests/place_ui.py --output <目录>`：关闭 Wi-Fi，通过真实系统设置页验证搜索输入、离线手填、范围与时区拒绝、负数坐标保存、取消/Home 丢弃草稿，以及浅深主题与横竖屏。原始设置保存在本机私有临时目录；结束后逐字节恢复设备修改与 Server 层、恢复 Wi-Fi，核对成功后删除备份。当前 UI 证据见 [17-ui-validation.md](17-ui-validation.md)。
- `python3 -I tools/tests/place_ui.py --timezone-retention --output <目录>`：定向检查空值、显式时区与自动来源在重开及直接保存后的语义，使用同样的备份与恢复流程。
