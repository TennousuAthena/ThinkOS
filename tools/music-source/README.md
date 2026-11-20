# ThinkOS 在线音乐源

播放器的「音乐源地址」提供在线精选、搜索和音频地址。ThinkOS 管理 Server 可以离线。LX Music Desktop 的官方自定义源接口是脚本 `request({source, action: "musicUrl", info: {type, musicInfo}})`，由客户端传入歌曲元数据和自定义源声明的音质，脚本返回播放 URL。常见音质为 `128k`、`320k`、`flac`、`flac24bit`。LX 的同步服务负责私人数据同步，开放 API 用于控制本机播放器。ThinkOS 将浏览、搜索和播放地址解析分开，通过 HTTP v1 接入。LX 脚本或私有解析协议需要在源侧适配。

1. 把有权播放的 MP3、AAC、WAV 等文件放在设备能访问的静态文件主机或对象存储上。填写 `catalog.json`，每项 ID 唯一。

   ```json
   [
     {"id":"song1","title":"My recording","artist":"My name","album":"My collection","durationMs":185000,"url":"http://192.168.1.20:8080/song1.mp3"}
   ]
   ```

2. 在接入同一网络的电脑上运行音源。`--bind` 填该电脑的实际私有 IPv4。仅供本机访问时使用默认 `127.0.0.1`。参考服务适用于可信内网。已有 HTTPS 入口时可反代到回环地址。对接有授权的上游目录时，在服务端保管上游凭据并返回设备可直接读取的音频 URL。时效链接要覆盖队列播放的预计时间。

   ```sh
   python3 tools/music-source/serve.py --bind 192.168.1.20 --port 8765 --catalog catalog.json
   curl -fsS 'http://192.168.1.20:8765/v1/browse'
   curl -fsS 'http://192.168.1.20:8765/v1/search?q=recording'
   curl -fsS 'http://192.168.1.20:8765/v1/resolve?ids=song1'
   ```

3. 在 ThinkOS 的播放器设置将“音乐源地址”设为 `http://192.168.1.20:8765`，打开「媒体 → 发现」即可查看目录前 12 首。也可用「搜索歌曲」找歌。点曲目后解析这一批结果并从所选曲目开始播放。点歌后留页。底栏可暂停或切歌，点歌名展开播放页，从「待播」查看队列。链接过期后重新选曲会重新解析。来源不可达、超时和解析错误会反馈到界面。

[Incompetech 示例目录](catalog.incompetech.json) 可直接作为 `--catalog` 参数。曲目是 Kevin MacLeod 在 [Incompetech](https://incompetech.com/music/royalty-free/) 提供的完整录音，按 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 授权。文件中的 URL 指向作者托管的原始音频，需要设备能访问 Incompetech。专辑名和封面取自对应发行记录，逐曲依据见 [封面来源](catalog-artwork-provenance.json)。时长由原 MP3 实测。

本地无声夹具需要已有的 ThinkOS 模拟器工具链和 Chromium。它启动两个回环服务，生成两段零 PCM 音频，从默认目录直接点播，经 Lua、音源和一次音频重定向读取浏览器解码进度，也验证搜索和睡眠门控。

```sh
python3 tools/music-source/gen_contract.py --check
fixture_dir=$(mktemp -d /tmp/thinkos-music-fixture.XXXXXX)
node tools/music-source/browser_verify.mjs "$fixture_dir"
THINKOS_TEST_MUSIC_FIXTURE="$fixture_dir/source-replies.json" bash tools/tests/player_native.sh
```

接口和数据格式见 `docs/05-protocol.md` 的在线音乐源一节。浏览器夹具、Android 实际播放和 MODE-0 实物验收分开记录。

### 在开发机聚合两个渠道

运行现有的曲库服务（serve.py），再启动 multi_source.py。下面的示例中，曲库服务监听 Mac 的 51864 端口，聚合服务监听 51865。曲库内的播放和封面地址必须可从设备访问。

    python3 tools/music-source/multi_source.py --port 51865 --device-port 53296 --channel http://127.0.0.1:51864 --featured HOYO-MiX
    ADB_MDNS=0 adb -s HUA0BWUT reverse tcp:53296 tcp:51865

播放器设置中的音乐源地址填 http://127.0.0.1:53296 。发现页交替显示指定艺人的网易云结果和原曲库精选，搜索会查询两个渠道，每次最多显示 12 条。渠道前缀用于把播放请求送回原渠道。网易云使用匿名网页接口与平台返回的媒体地址，遇到地区限制、无可用外链或接口变化时个别曲目可能无法播放。该配置仅监听开发机本地，使用期间保留 Mac 服务和 USB 转发。
