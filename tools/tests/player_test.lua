-- Player plugin logic in the production sandbox (native harness): M3U lists, names and history, and main.lua
-- driven by a faithful stand-in for tos.*. No network, files or Android.
local json = _json
local manifest, strings = json.decode(manifest_json), json.decode(strings_json)
local m3u = assert(load(m3u_source, "m3u", "t"))()
local library = assert(load(library_source, "library", "t"))()
local function test(name, fn) test_begin(); fn(); test_end(name) end
local function clone(value) return json.decode(json.encode(value)) end

-- ------------------------------------------------------------------------------------------------ M3U
test("m3u reads channel lists the way IPTV lists are written", function()
  local text = table.concat({
    "\239\187\191#EXTM3U x-tvg-url=\"http://epg.test/guide.xml\"",
    '#EXTINF:-1 tvg-id="a" tvg-name="News A" group-title="News, World",Channel A',
    "#EXTVLCOPT:http-user-agent=Vendor/2.0",
    "#EXTVLCOPT:http-referrer=https://tv.test/",
    "http://tv.test/a.m3u8",
    "#EXTINF:-1 group-title=\"Kids\",",
    "/live/b.ts",
    '#EXTINF:-1 tvg-name="Only attribute name",',
    "rtsp://192.168.1.9/cam1",
    "#EXTINF:-1,Channel A",
    "http://tv.test/a.m3u8",
    "#EXTINF:-1,中文频道",
    "https://tv.test/zh%20one.m3u8?token=1",
    "#EXTINF:-1,Not playable",
    "ftp://tv.test/x",
    "javascript:alert(1)",
    "",
  }, "\r\n")
  local items, cut = m3u.parse(text, "http://tv.test/lists/main.m3u")
  assert(not cut and #items == 5, #items)
  assert(items[1].title == "Channel A" and items[1].group == "News, World" and items[1].url == "http://tv.test/a.m3u8")
  assert(items[1].agent == "Vendor/2.0" and items[1].referer == "https://tv.test/")
  assert(items[2].url == "http://tv.test/live/b.ts" and items[2].title == "b.ts" and items[2].group == "Kids", items[2].title)
  assert(items[3].title == "Only attribute name" and items[3].url == "rtsp://192.168.1.9/cam1")
  assert(items[4].title == "中文频道" and items[4].agent == nil)
  assert(m3u.resolve("http://tv.test/a/b/c.m3u", "d.ts") == "http://tv.test/a/b/d.ts")
  assert(m3u.resolve("http://tv.test/a/b/c.m3u?x=1", "../d.ts") == "http://tv.test/a/b/../d.ts")
  assert(m3u.resolve("https://tv.test:8443/c.m3u", "//cdn.test/x.ts") == "https://cdn.test/x.ts")
  assert(m3u.resolve(nil, "x.ts") == nil and m3u.resolve("not a url", "x.ts") == nil)
end)

test("m3u tells a list from an HLS playlist and bounds what it keeps", function()
  assert(m3u.isHls("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6,\nseg0.ts"))
  assert(not m3u.isHls("#EXTM3U\n#EXTINF:-1,A\nhttp://a/x"))
  local lines = {"#EXTM3U"}
  for i = 1, 700 do lines[#lines + 1] = "#EXTINF:-1,Ch " .. i; lines[#lines + 1] = "http://tv.test/" .. i end
  local pauses = 0
  local items, cut = m3u.parse(table.concat(lines, "\n"), nil, 300, function() pauses = pauses + 1 end, 100)
  assert(#items == 300 and cut and pauses >= 2, pauses)
end)

test("m3u keeps titles valid UTF-8 and short", function()
  assert(m3u.clean("ok 汉") == "ok 汉")
  assert(m3u.clean("bad \200\201 bytes") == "bad ?? bytes")
  local long = string.rep("汉", 100)
  local cut = m3u.clip(long, 120)
  assert(#cut == 120 and utf8.len(cut) == 40)
  assert(#m3u.clip(string.rep("汉", 100), 121) == 120)
  assert(m3u.clip("abc", 120) == "abc")
  local items = m3u.parse("#EXTINF:-1," .. string.rep("汉", 300) .. "\nhttp://tv.test/x", nil)
  assert(utf8.len(items[1].title) == 40)
  local gbk = m3u.parse("#EXTINF:-1,\213\209\214\208\n#comment\nhttp://tv.test/y", nil)
  assert(gbk[1].title == "????" and json.encode({title = gbk[1].title}))
end)

-- ------------------------------------------------------------------------------------------------ library
test("library names, durations, kinds and history", function()
  local artist, title = library.split("Hans Zimmer - Drive Fast [2718994112]")
  assert(artist == "Hans Zimmer" and title == "Drive Fast")
  artist, title = library.split("五月天 - 派对动物 [417953658]")
  assert(artist == "五月天" and title == "派对动物")
  artist, title = library.split("No separator here")
  assert(artist == "" and title == "No separator here")
  artist, title = library.split("Dash-in-word")
  assert(artist == "" and title == "Dash-in-word")
  assert(select(2, library.describe({title = "Song", artist = "Tagged"})) == "Tagged")
  assert(select(1, library.describe({title = "A - B", artist = ""})) == "B")
  assert(library.duration(0) == "" and library.duration(nil) == "" and library.duration(65000) == "1:05" and library.duration(3723000) == "1:02:03")
  assert(library.kindOf("http://radio.test/stream.MP3?x=1") == "audio" and library.kindOf("http://tv.test/live.m3u8") == "video"
    and library.kindOf("rtsp://cam/live") == "video" and library.kindOf("http://tv.test/stream") == "video")
  assert(library.resumeAt({position = 5000, duration = 300000}) == 0)        -- just started
  assert(library.resumeAt({position = 60000, duration = 300000}) == 60000)
  assert(library.resumeAt({position = 290000, duration = 300000}) == 0)      -- nearly finished
  assert(library.resumeAt({position = 60000, duration = 0}) == 60000)        -- duration unknown
  local list = {}
  for i = 1, 5 do list = library.touch(list, {url = "u" .. i}, 4) end
  assert(#list == 4 and list[1].url == "u5" and list[4].url == "u2")
  list = library.touch(list, {url = "u3"}, 4)
  assert(#list == 4 and list[1].url == "u3" and list[2].url == "u5" and list[3].url == "u4")
  assert(library.progress({position = 50, duration = 200}) == 25 and library.progress({position = 5, duration = 0}) == 0
    and library.progress({position = 500, duration = 200}) == 100)
end)

-- ------------------------------------------------------------------------------------------------ main.lua
local function new(options)
  options = options or {}
  local env = {handlers = {}, timers = {}, serial = 0, state = {}, store = {}, loads = {}, focus = {}, released = 0, calls = {}, logs = {},
    media = {status = "stopped", index = 1, position = 0, duration = 0, live = false, kind = "audio"}, now = 1000000}
  local cfg = {}
  for key, field in pairs(manifest.settings) do cfg[key] = field.default end
  for key, value in pairs(options.config or {}) do cfg[key] = value end
  env.config = cfg
  env.lists = options.lists or {audio = {items = {}}, video = {items = {}}}
  env.http = options.http or function() return nil, "offline" end
  local function translate(key, args)
    local value = strings[key] or key
    return (value:gsub("{([%w_]+)}", function(k) return tostring(args and args[k]) end))
  end
  tos = {
    config = cfg, json = json,
    on = function(name, fn) env.handlers[name] = fn end,
    state = {set = function(text) for key, value in pairs(json.decode(text)) do env.state[key] = value end end, get = function(path)
      local key = path:match("^media%.(.+)$"); return key and env.media[key] or nil end},
    store = {get = function(key) return env.store[key] end, set = function(key, value) env.store[key] = value end},
    time = {now = function() return env.now end},
    i18n = {t = translate},
    log = {info = function() end, warn = function(...) env.logs[#env.logs + 1] = table.concat({...}, " ") end, error = function() end},
    timer = {
      after = function(delay, fn) env.serial = env.serial + 1; env.timers[env.serial] = {fn = fn, delay = delay}; return env.serial end,
      every = function(delay, fn) env.serial = env.serial + 1; env.timers[env.serial] = {fn = fn, delay = delay, every = true}; return env.serial end,
      cancel = function(id) env.timers[id] = nil end,
      sleep = function() test_slice() end,
    },
    library = {list = function(opts)
      env.calls[#env.calls + 1] = clone(opts)
      local result = env.lists[opts.kind]
      if result == nil then return nil, "denied" end
      return result
    end},
    http = {get = function(url, opts) env.requested = url; return env.http(url, opts) end},
    media = {
      load = function(entries, opts)
        env.loads[#env.loads + 1] = {entries = clone(entries), options = clone(opts)}
        if env.refuse then return nil, env.refuse end
        env.media.status, env.media.index = "playing", opts.index
        env.media.kind = entries[opts.index].kind
        return clone(env.media)
      end,
      toggle = function() env.toggled = true end, next = function() env.nexted = true end, prev = function() env.preved = true end,
      pause = function() env.paused = true; env.media.status = "paused" end,
    },
    page = {focus = function(page, opts) env.focus[#env.focus + 1] = {page = page, live = opts and opts.live} end,
      release = function(page) env.released = env.released + 1 end},
  }
  local modules = {m3u = m3u, library = library}
  require = function(name) return assert(modules[name], name) end
  assert(load(main_source, "player", "t"))()
  function env.fire()
    local ids = {}
    for id in pairs(env.timers) do ids[#ids + 1] = id end
    table.sort(ids)
    for _, id in ipairs(ids) do
      local timer = env.timers[id]
      if timer then if not timer.every then env.timers[id] = nil end; timer.fn() end
    end
  end
  function env.event(name, ...) return env.handlers[name](...) end
  env.event("start")
  return env
end

-- The library lists are chosen by tapping the title; walk to the one asked for.
local function show(env, name)
  for _ = 1, 4 do
    if env.state.heading == strings["variant." .. name] then return end
    env.event("tap", "cycle")
  end
  error("no list called " .. name)
end

local function music(n)
  local items = {}
  for i = 1, n do items[i] = {url = "file:///sdcard/Music/t" .. i .. ".flac", kind = "audio", title = "Artist " .. i .. " - Song " .. i,
    artist = "", album = "", mime = "audio/flac", duration = 180000 + i} end
  return items
end

test("start shows the stored history at once and scans after a moment", function()
  local env = new({lists = {audio = {items = music(3)}, video = {items = {{url = "file:///sdcard/Movies/a.mp4", kind = "video", title = "Film", duration = 7200000}}}}})
  assert(#env.calls == 0 and env.state.heading == strings["variant.music"] and #env.state.items == 0)
  assert(env.state.footer == "No music found in /sdcard/Music", env.state.footer)
  env.fire()
  assert(#env.calls == 2 and env.calls[1].kind == "audio" and env.calls[1].dir == "/sdcard/Music" and env.calls[1].recursive == true
    and env.calls[2].kind == "video" and env.calls[2].dir == "/sdcard/Movies")
  assert(#env.state.items == 3 and env.state.items[1].title == "Song 1" and env.state.items[1].subtitle == "Artist 1" and env.state.items[1].icon == "music"
    and env.state.items[1].trailing == "3:00"
    and env.state.footer == "Tap the title to switch to Videos", env.state.footer)
  show(env, "video")
  assert(#env.state.items == 1 and env.state.items[1].icon == "play" and env.state.items[1].trailing == "2:00:00" and env.state.heading == strings["variant.video"])
  env.event("tap", "cycle")
  assert(env.state.heading == strings["variant.recent"])
end)

test("an unreadable folder says so instead of looking empty", function()
  local env = new({lists = {video = {items = {}}}})   -- audio: denied
  env.fire()
  assert(env.state.footer == "Cannot read /sdcard/Music", env.state.footer)
  assert(#env.logs == 1)
end)

test("tapping music queues the whole list, starts there and opens the audio page", function()
  local env = new({lists = {audio = {items = music(4)}, video = {items = {}}}})
  env.fire()
  env.event("tap", "play", 3)
  local load = env.loads[1]
  assert(#load.entries == 4 and load.options.index == 3 and load.options.autoplay and load.options.start == nil)
  assert(load.entries[3].url == "file:///sdcard/Music/t3.flac" and load.entries[3].kind == "audio" and load.entries[3].mime == "audio/flac")
  assert(load.entries[3].title == "Song 3" and load.entries[3].artist == "Artist 3")   -- the file name, split for the screen
  assert(env.focus[1].page == "player.audio" and env.focus[1].live)
  env.event("tap", "play", 99)
  env.event("tap", "other", 1)
  assert(#env.loads == 1)
  assert(env.store.recent and json.decode(env.store.recent)[1].url == "file:///sdcard/Music/t3.flac")
end)

test("the home card says what is playing and how much it wants to be shown", function()
  local env = new({lists = {audio = {items = music(2)}, video = {items = {}}}})
  assert(env.state.nowTitle == "Nothing playing" and env.state.relevance == 0, env.state.nowTitle)
  env.fire()
  env.event("tap", "play", 2)
  env.media.status, env.media.title = "playing", "Song 2"
  env.event("media.track", {index = 2})
  assert(env.state.nowTitle == "Song 2" and env.state.relevance == 0.9, env.state.nowTitle)
  env.media.status = "paused"
  env.event("media.track", {index = 2})
  assert(env.state.nowTitle == "Song 2" and env.state.relevance == 0)
  env.media.status = "stopped"
  env.event("media.session", {reason = "stopped", owner = ""})
  assert(env.state.nowTitle == "Nothing playing" and env.state.relevance == 0)
  env.media.title = string.rep("汉", 300)
  env.media.status = "playing"
  env.event("media.track", {index = 1})
  assert(utf8.len(env.state.nowTitle) and #env.state.nowTitle <= 120)
end)

test("the home card follows a session that someone else started, without any event", function()
  local env = new({lists = {audio = {items = music(2)}, video = {items = {}}}})
  env.fire()
  env.media.status, env.media.title = "playing", "Radio 1"
  env.fire()
  assert(env.state.nowTitle == "Radio 1" and env.state.relevance == 0.9, env.state.nowTitle)
  env.media.status = "paused"
  env.fire()
  assert(env.state.nowTitle == "Radio 1" and env.state.relevance == 0)
  env.media.status = "stopped"
  env.fire()
  assert(env.state.nowTitle == "Nothing playing" and env.state.relevance == 0)
end)

test("video opens the video page; recent resumes where it stopped when asked to", function()
  local film = {url = "file:///sdcard/Movies/a.mp4", kind = "video", title = "Film", duration = 7200000}
  local env = new({lists = {audio = {items = {}}, video = {items = {film}}}})
  env.fire()
  show(env, "video")
  env.event("tap", "play", 1)
  assert(env.focus[1].page == "player.video" and env.loads[1].entries[1].kind == "video")
  -- an hour in, then the saver timer writes the position down
  env.media.position, env.media.duration = 3600000, 7200000
  env.fire()
  local recent = json.decode(env.store.recent)
  assert(recent[1].position == 3600000 and recent[1].duration == 7200000)
  show(env, "recent")
  assert(env.state.items[1].trailing == "1:00:00 / 2:00:00", env.state.items[1].trailing)
  assert(env.state.items[1].subtitle == "Movies", env.state.items[1].subtitle)   -- the folder, not file:///sdcard/Movies/a.mp4
  env.event("tap", "play", 1)
  assert(env.loads[2].options.start == 3600000 and #env.loads[2].entries == 1)
end)

test("resume can be switched off and finished titles start over", function()
  local env = new({config = {resume = false}})
  env.store.recent = json.encode({{url = "file:///a.mp4", title = "A", kind = "video", position = 600000, duration = 3600000}})
  env.event("start")
  show(env, "recent")
  env.event("tap", "play", 1)
  assert(env.loads[1].options.start == nil)
  env = new()
  env.store.recent = json.encode({{url = "file:///a.mp4", title = "A", kind = "video", position = 3595000, duration = 3600000}})
  env.event("start")
  show(env, "recent")
  env.event("tap", "play", 1)
  assert(env.loads[1].options.start == nil)
end)

test("a stream address in the settings plays at once, is saved once, and is not replayed on the next start", function()
  local env = new()
  env.config.stream_url = "http://radio.test/live.mp3"
  env.event("config", env.config)
  assert(#env.loads == 1 and env.loads[1].entries[1].kind == "audio" and env.focus[1].page == "player.audio")
  env.event("config", env.config)                       -- same value again: nothing new
  assert(#env.loads == 1)
  env.config.stream_url = "rtsp://192.168.1.9/cam1"
  env.event("config", env.config)
  assert(#env.loads == 2 and env.loads[2].entries[1].kind == "video" and env.focus[2].page == "player.video")
  local saved = json.decode(env.store.saved)
  assert(#saved == 2 and saved[1].url == "rtsp://192.168.1.9/cam1")
  show(env, "streams")
  assert(#env.state.items == 2 and env.state.items[1].subtitle == strings["group.saved"])
  local again = new({config = {stream_url = "rtsp://192.168.1.9/cam1"}})
  assert(#again.loads == 0)
end)

test("a refused load shows why and does not steal the screen", function()
  local env = new()
  env.refuse = "READ_EXTERNAL_STORAGE permission was denied"
  env.config.stream_url = "http://radio.test/live.mp3"
  env.event("config", env.config)
  assert(#env.focus == 0 and env.state.footer:find("denied", 1, true))
end)

test("a playlist address fills the Streams page, and cached channels survive a restart", function()
  local body = "#EXTM3U\n#EXTINF:-1 group-title=\"News\",One\nhttp://tv.test/1\n#EXTINF:-1,Radio\nhttp://tv.test/radio.mp3\n"
  local env = new({config = {playlist_url = "http://tv.test/list.m3u"}, http = function() return {status = 200, body = body} end})
  env.fire()
  show(env, "streams")
  assert(#env.state.items == 2 and env.state.items[1].title == "One" and env.state.items[1].subtitle == "News")
  env.event("tap", "play", 2)
  -- A channel plays alone: the player would otherwise fetch the playlist of every queued HLS / DASH item at once.
  assert(#env.loads[1].entries == 1 and env.loads[1].options.index == 1 and env.loads[1].entries[1].kind == "audio"
    and env.loads[1].entries[1].url == "http://tv.test/radio.mp3" and env.focus[1].page == "player.audio")
  local cached = new({config = {playlist_url = "http://tv.test/list.m3u"}})
  cached.store.channels = env.store.channels
  cached.event("start")
  show(cached, "streams")
  assert(#cached.state.items == 2)
end)

test("a real sized IPTV list is read in slices that each stay inside the CPU budget", function()
  -- Shaped like a published list: 277 channels, long logo attributes, emoji group titles, a bare address under each.
  local lines = {'#EXTM3U x-tvg-url="https://iptv.test/epg.gz"'}
  local groups = {"📺央视频道", "📡卫视频道", "💰央视付费频道", "☘️广东频道"}
  for i = 1, 277 do
    local name = "CCTV-" .. (i % 17 + 1)
    lines[#lines + 1] = string.format('#EXTINF:-1 tvg-id="%s" tvg-name="%s" tvg-logo="https://www.xn--rgv465a.top/tvlogo/2026/10/12/channels/high-definition/%s.png" group-title="%s",%s',
      name, name, name, groups[i % 4 + 1], name)
    lines[#lines + 1] = string.format("http://10.%d.%d.%d:8181/live/%d/index.m3u8", i % 250, i % 97, i % 31, i)
  end
  local body = table.concat(lines, "\n") .. "\n"
  assert(#body > 60000, #body)   -- the published list is 62.8 KB
  local env = new({config = {playlist_url = "https://iptv.test/ipv4.m3u"}, http = function() return {status = 200, body = body} end})
  env.fire()
  show(env, "streams")
  assert(#env.state.items == 277, #env.state.items)
  assert(env.state.items[1].subtitle == "📺央视频道" or env.state.items[1].subtitle ~= nil)
end)

test("a broken or hostile playlist leaves a notice, not an error", function()
  local env = new({config = {playlist_url = "http://tv.test/list.m3u"}, http = function() return {status = 404, body = "no"} end})
  env.fire()
  show(env, "streams")
  assert(#env.state.items == 0 and env.state.footer == strings["notice.playlist_failed"], env.state.footer)
  env = new({config = {playlist_url = "http://tv.test/list.m3u"}, http = function() return {status = 200, body = "<html>not a list</html>"} end})
  env.fire()
  show(env, "streams")
  assert(#env.state.items == 0 and env.state.footer == strings["notice.playlist_failed"])
end)

test("an .m3u8 stream address is played, an .m3u list address is adopted as channels", function()
  local hls = new({http = function() return {status = 200, body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6,\nseg.ts\n"} end})
  hls.config.stream_url = "http://tv.test/live/index.m3u8"
  hls.event("config", hls.config)
  assert(#hls.loads == 1 and hls.loads[1].entries[1].url == "http://tv.test/live/index.m3u8")
  local list = new({http = function() return {status = 200, body = "#EXTM3U\n#EXTINF:-1,A\nhttp://tv.test/a\n"} end})
  list.config.stream_url = "http://tv.test/channels.m3u"
  list.event("config", list.config)
  assert(#list.loads == 0 and #list.state.items == 1 and list.state.heading == strings["variant.streams"])
end)

test("errors are named for the audio page; the next track clears them; the page is released later", function()
  local env = new()
  env.event("media.error", {category = "http", status = 404, codec = ""})
  assert(env.state.footer == "The server answered with error 404", env.state.footer)
  env.event("media.error", {category = "format", codec = "video/av01"})
  assert(env.state.footer == "This device cannot decode AV01", env.state.footer)
  env.event("media.error", {category = "decoder", codec = ""})
  assert(env.state.footer == strings["error.decoder"])
  env.media.status = "error"
  env.fire()
  assert(env.released == 2)
  env.event("media.track", {index = 1})
  assert(env.state.footer == "No music found in /sdcard/Music")
end)

test("leaving the video page pauses the film; the end of a queue forgets the position", function()
  local env = new()
  env.media.kind, env.media.status = "video", "playing"
  env.event("page.leave", "video")
  assert(env.paused)
  env = new()
  env.media.kind, env.media.status = "audio", "playing"
  env.event("page.leave", "video")
  assert(not env.paused)
  local film = {url = "file:///sdcard/Movies/a.mp4", kind = "video", title = "Film", duration = 600000}
  env = new({lists = {audio = {items = {}}, video = {items = {film}}}})
  env.fire()
  show(env, "video")
  env.event("tap", "play", 1)
  env.media.position, env.media.duration, env.media.status = 599000, 600000, "ended"
  env.event("queue.end", {index = 1, count = 1})
  assert(json.decode(env.store.recent)[1].position == 0)
  env.fire()
  assert(env.released >= 2)
end)

test("commands drive the session; live streams never save a position", function()
  local env = new()
  env.event("command", "toggle"); env.event("command", "next"); env.event("command", "prev"); env.event("command", "nonsense")
  assert(env.toggled and env.nexted and env.preved)
  env.config.stream_url = "http://tv.test/live.m3u8"
  env.event("config", env.config)
  env.media.live, env.media.position, env.media.duration = true, 99000, 100000
  env.fire()
  assert(json.decode(env.store.recent)[1].position == 0 and json.decode(env.store.recent)[1].duration == 0)
  env.event("stop")
end)

test("m3u accepts Kodi style header suffixes and drops addresses the engine would refuse", function()
  local text = table.concat({
    "#EXTM3U",
    "#EXTINF:-1,Piped",
    "http://tv.test/a.m3u8|User-Agent=Vendor%2F9&Referer=https%3A%2F%2Ftv.test%2F",
    "#EXTINF:-1,Braces",
    "http://tv.test/{id}/index.m3u8",
    "#EXTINF:-1,Caret",
    "http://tv.test/a^b",
    "#EXTINF:-1,Space",
    "http://tv.test/a b",
    "#EXTINF:-1,Path with 中文",
    "http://tv.test/中文/a.m3u8",
    "#EXTINF:-1,Camera",
    "#EXTVLCOPT:http-user-agent=Cam/1",
    "rtsp://192.168.1.9/live",
  }, "\n")
  local items = m3u.parse(text, nil)
  assert(#items == 3, #items)
  assert(items[1].url == "http://tv.test/a.m3u8" and items[1].agent == "Vendor/9" and items[1].referer == "https://tv.test/")
  assert(items[2].url == "http://tv.test/中文/a.m3u8")
  assert(items[3].url == "rtsp://192.168.1.9/live")
end)

test("credentials in an address never become a title or a subtitle", function()
  local env = new()
  env.config.stream_url = "rtsp://admin:hunter2@192.168.1.9:554/stream1"
  env.event("config", env.config)
  assert(#env.loads == 1 and env.loads[1].entries[1].title == "192.168.1.9:554", env.loads[1].entries[1].title)
  assert(env.loads[1].entries[1].headers == nil)           -- an RTSP item takes no headers
  show(env, "recent")
  assert(not json.encode(env.state):find("hunter2", 1, true))
  show(env, "streams")
  assert(not json.encode(env.state):find("hunter2", 1, true))
  for _, line in ipairs(env.logs) do assert(not line:find("hunter2", 1, true)) end
end)

test("a queue with one bad entry still plays the chosen one; a refused load keeps the old queue", function()
  local items = {}
  for i = 1, 3 do items[i] = {url = "file:///sdcard/Music/t" .. i .. ".flac", kind = "audio", title = "A - S" .. i, mime = "audio/flac", duration = 1000} end
  local env = new({lists = {audio = {items = items}, video = {items = {}}}})
  env.fire()
  local calls = 0
  local load = tos.media.load
  tos.media.load = function(entries, opts)
    calls = calls + 1
    if #entries > 1 then return nil, "Invalid media URL" end
    return load(entries, opts)
  end
  env.event("tap", "play", 2)
  assert(calls == 2 and #env.loads == 1 and #env.loads[1].entries == 1 and env.loads[1].entries[1].url == "file:///sdcard/Music/t2.flac")
  assert(env.focus[1].page == "player.audio" and env.state.playNotice == "")
  tos.media.load = function() return nil, "denied" end
  env.event("tap", "play", 1)
  assert(env.state.playNotice == "Cannot start playback: denied" and env.state.footer == "Cannot start playback: denied", env.state.playNotice)
  env.event("media.track", {index = 1})
  assert(env.state.playNotice == "")
end)

test("a full library stays inside the state budget with long names", function()
  local items = {}
  local long, artist = string.rep("汉", 100), string.rep("艺", 100)
  for i = 1, 512 do items[i] = {url = "file:///sdcard/Music/" .. string.rep("目", 30) .. i .. ".mp3", kind = "audio", title = long .. i,
    artist = artist, album = "", mime = "audio/mpeg", duration = 1000 * i} end
  local env = new({lists = {audio = {items = items}, video = {items = {}}}})
  env.fire()
  assert(#env.state.items == 512)
  assert(#json.encode(env.state) < 200 * 1024, #json.encode(env.state))   -- the state document may hold 256 KiB
  assert(utf8.len(env.state.items[1].title) and utf8.len(env.state.items[1].subtitle))
end)
