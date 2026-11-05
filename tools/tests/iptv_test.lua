-- The iptv plugin's logic in the production sandbox (native harness): the list readers, the channel merge, the line health and the
-- programme guide as pure functions, and main.lua driven by a faithful stand-in for tos.*. No network, files or Android.
local json = _json
local manifest, strings = json.decode(manifest_json), json.decode(strings_json)
local function test(name, fn) test_begin(); fn(); test_end(name) end
local function clone(value) return json.decode(json.encode(value)) end

local MODULES = {
  m3u = src_m3u, sources = src_sources, ["sources.m3u"] = src_sources_m3u, ["sources.json"] = src_sources_json,
  channels = src_channels, health = src_health, guide = src_guide, guides = src_guides,
  ["guides.template"] = src_guides_template, ["guides.xmltv"] = src_guides_xmltv, ["guides.cntv"] = src_guides_cntv,
}
local loaded = {}
local function use(name)
  if loaded[name] ~= nil then return loaded[name] end
  local fn = assert(load(assert(MODULES[name], name), name, "t"))
  loaded[name] = true                  -- a module that requires itself (through another) sees `true`, as in the app
  local result = fn()
  if result ~= nil then loaded[name] = result end
  return loaded[name]
end
require = use
tos = {json = json}

local NOW = 1793583000000              -- 2026-11-02 09:30 in UTC+8
local MINUTE, HOUR = 60000, 3600000
local m3u, sources, channels, health, guide = use("m3u"), use("sources"), use("channels"), use("health"), use("guide")
local guides = use("guides")

-- ------------------------------------------------------------------------------------------------ list readers
test("m3u keeps the guide id of a channel and the guide a list announces", function()
  local text = table.concat({
    '#EXTM3U url-tvg="ftp://nope/g.xml, https://epg.test/g.xml.gz,https://epg.test/other.xml"',
    '#EXTINF:-1 tvg-id="c1.cn" tvg-name="One" group-title="News",Channel One',
    "http://tv.test/one.m3u8",
    '#EXTINF:-1 tvg-id="",No id',
    "http://tv.test/two.m3u8",
  }, "\n")
  local items, cut, meta = m3u.parse(text, nil)
  assert(not cut and #items == 2 and meta.epg == "https://epg.test/g.xml.gz", meta.epg)
  assert(items[1].id == "c1.cn" and items[1].group == "News" and items[1].title == "Channel One")
  assert(items[2].id == nil and items[2].title == "No id")
  local _, _, other = m3u.parse('#EXTM3U x-tvg-url="http://e.test/x.xml"\n#EXTINF:-1,A\nhttp://a.test/x', nil)
  assert(other.epg == "http://e.test/x.xml")
  local _, _, none = m3u.parse("#EXTM3U\n#EXTINF:-1,A\nhttp://a.test/x", nil)
  assert(none.epg == nil)
  local _, _, bad = m3u.parse('#EXTM3U url-tvg="javascript:alert(1)"\n#EXTINF:-1,A\nhttp://a.test/x', nil)
  assert(bad.epg == nil)
end)

test("a list is read by the kind its address names or its text looks like", function()
  local id, address = sources.split("json:https://h.test/x")
  assert(id == "json" and address == "https://h.test/x")
  id, address = sources.split("https://h.test/x.m3u")
  assert(id == nil and address == "https://h.test/x.m3u")
  id, address = sources.split("xtream:https://h.test/x")
  assert(id == nil and address == "xtream:https://h.test/x")

  local items, meta, cut, kind = sources.parse(nil, '#EXTM3U x-tvg-url="https://e.test/g.xml"\n#EXTINF:-1 tvg-id="a" group-title="G",Alpha\nhttps://l.test/a.m3u8\n',
    "https://l.test/list.m3u", {limit = 10})
  assert(kind == "m3u" and #items == 1 and not cut)
  assert(items[1].name == "Alpha" and items[1].group == "G" and items[1].id == "a" and items[1].url == "https://l.test/a.m3u8" and meta.epg == "https://e.test/g.xml")

  local document = json.encode({epg = "https://epg.test/g.xml", channels = {
    {name = "Alpha", url = "https://j.test/a.m3u8", group = "G", id = "alpha.id", ua = "UA/1", referer = "https://j.test/"},
    {title = "Beta", urls = {"https://j.test/b1.m3u8", "/relative/b2.m3u8", "ftp://j.test/bad"}},
    {name = "Broken"},
    "junk",
  }})
  items, meta, cut, kind = sources.parse(nil, document, "https://j.test/list.json", {limit = 10})
  assert(kind == "json" and not cut and #items == 3, #items)
  assert(items[1].name == "Alpha" and items[1].group == "G" and items[1].id == "alpha.id" and items[1].agent == "UA/1" and items[1].referer == "https://j.test/")
  assert(items[2].name == "Beta" and items[2].url == "https://j.test/b1.m3u8" and items[3].url == "https://j.test/relative/b2.m3u8")
  assert(meta.epg == "https://epg.test/g.xml")
  items, _, cut = sources.parse(nil, document, "https://j.test/list.json", {limit = 2})
  assert(#items == 2 and cut)
  items, _, _, kind = sources.parse(nil, json.encode({{name = "Bare", url = "https://j.test/x"}}), nil, {limit = 5})
  assert(kind == "json" and #items == 1 and items[1].name == "Bare")

  items, meta, cut, kind = sources.parse(nil, "<html>not a list</html>", nil, {limit = 5})
  assert(#items == 0 and kind == nil and next(meta) == nil)
  items, _, _, kind = sources.parse("json", "not json at all", nil, {limit = 5})
  assert(#items == 0 and kind == "json")
  items, _, _, kind = sources.parse("m3u", "[]", nil, {limit = 5})
  assert(#items == 0 and kind == "m3u")
end)

test("an address that is itself a stream is one channel named for its host, without the password", function()
  local items, _, _, kind = sources.parse(nil, "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6,\nseg0.ts\n", "https://user:secret@cdn.test/live.m3u8", {limit = 5})
  assert(kind == "m3u" and #items == 1 and items[1].name == "cdn.test" and items[1].url == "https://user:secret@cdn.test/live.m3u8")
end)

-- ------------------------------------------------------------------------------------------------ one channel, many lines
test("names that mean the same channel give the same key", function()
  local key = channels.key
  assert(key("CCTV-1 综合 HD") == "cctv1" and key("CCTV1") == "cctv1" and key("cctv-1 [1080p]") == "cctv1" and key("CCTV 1") == "cctv1")
  assert(key("CCTV-5+") == "cctv5+" and key("CCTV5") == "cctv5" and key("CCTV-5+ 体育赛事") == "cctv5+")
  assert(key("CCTV-4K") == "cctv4k" and key("CCTV-4") == "cctv4" and key("CCTV-16") == "cctv16" and key("CCTV-8K 超高清") == "cctv8k")
  assert(key("东方卫视 高清") == key("东方卫视") and key("东方卫视") == "东方卫视")
  assert(key("News 24 HD") == "news24" and key("news-24") == "news24" and key("News  24 (Backup)") == "news24")
  assert(key("【测试】Some Show") == "someshow" and key("Some Show [HEVC]") == "someshow" and key("Some Show 4K") == "someshow")
  assert(key("HD") == "hd" and key("") == "")                       -- a name with nothing else keeps what it has
  assert(key("BBC One") ~= key("BBC Two") and key("CCTV-1") ~= key("CCTV-11"))
end)

test("the quality a name claims", function()
  local q = channels.quality
  assert(q("CCTV-1 4K") == 4 and q("Some UHD") == 4 and q("x FHD") == 3 and q("cctv-1 [1080p]") == 3)
  assert(q("CCTV-1 综合 HD") == 2 and q("频道 高清") == 2 and q("Y SD") == 1 and q("频道 标清") == 1 and q("Plain") == 0)
  assert(q("Shdow") == 0 and q("HDTV") == 0)                       -- only the word, not the letters inside another
end)

test("items with the same guide id or key become one channel with ordered lines", function()
  local merged = channels.merge({
    {name = "CCTV-1 综合 HD", url = "u1", id = "c1", group = "Central", src = 1},
    {name = "CCTV1", url = "u2", src = 2},
    {name = "cctv-1 1080p", url = "u3", src = 2},
    {name = "CCTV1", url = "u2", src = 3},                          -- the same address again: not a second line
    {name = "Other", url = "u4", id = "C1", src = 2},               -- another name, the same guide id
    {name = "Solo", url = "u5", src = 1},
  }, {maxLines = 3})
  assert(#merged == 2, #merged)
  local one = merged[1]
  assert(one.name == "CCTV-1 综合 HD" and one.key == "cctv1" and one.group == "Central" and one.id == "c1")
  assert(#one.lines == 3 and one.lines[1].url == "u1" and one.lines[2].url == "u3" and one.lines[3].url == "u2", one.lines[2].url)
  assert(one.lines[2].q == 3 and one.lines[3].q == 0 and one.lines[1].src == 1 and one.lines[1].seq == nil)
  assert(merged[2].name == "Solo" and #merged[2].lines == 1)
end)

test("group names keep their words and lose their emoji", function()
  local label = channels.label
  assert(label("📺央视频道") == "央视频道" and label("🪁动画频道") == "动画频道" and label("🌊港·澳·台") == "港·澳·台")
  assert(label("☘️江苏频道") == "江苏频道" and label("🕘️更新时间") == "更新时间" and label("👨‍👩‍👧 Family") == "Family")
  assert(label("News") == "News" and label("Sports – Live → 4K") == "Sports – Live → 4K" and label("  🏀 体育  ") == "体育")
  assert(label("🎵") == nil and label("") == nil and label(nil) == nil)
  local merged = channels.merge({{name = "A", url = "1", group = "🎵"}, {name = "B", url = "2", group = "🎵"}, {name = "A", url = "3", group = "📺 Music"}})
  assert(merged[1].group == "Music" and merged[2].group == nil)                       -- a name of only emoji is no group
end)

test("an entry that only stamps the time of the list is not a channel", function()
  assert(not channels.isChannel("2026-10-12 11:25:50") and not channels.isChannel("2026/10/12") and not channels.isChannel(" 2026.1.2 x"))
  assert(channels.isChannel("CCTV-1") and channels.isChannel("1905 电影") and channels.isChannel("2026 World Cup") and channels.isChannel("12-34 Show"))
  local merged = channels.merge({
    {name = "2026-10-12 11:25:50", url = "u0", id = "CCTV-1", group = "Updated"},      -- wears the guide id of the first real channel
    {name = "CCTV-1", url = "u1", id = "CCTV-1", group = "Central"},
    {name = "CCTV-1", url = "u2", id = "CCTV-1", group = "Central"},
  })
  assert(#merged == 1 and merged[1].name == "CCTV-1" and merged[1].group == "Central", merged[1].name)
  assert(#merged[1].lines == 2 and merged[1].lines[1].url == "u1")
end)

test("the first source decides the name and the group, the later ones fill what is missing", function()
  local merged = channels.merge({
    {name = "News 24", url = "a", src = 1},
    {name = "News 24 HD", url = "b", group = "News", id = "n24", src = 2},
  })
  assert(#merged == 1 and merged[1].name == "News 24" and merged[1].group == "News" and merged[1].id == "n24")
end)

test("a big merge is bounded and gives the CPU back", function()
  local items = {}
  for i = 1, 1200 do items[i] = {name = "Channel " .. (i % 700), url = "http://tv.test/" .. i, src = 1 + i % 2} end
  local pauses = 0
  local merged = channels.merge(items, {maxChannels = 500, maxLines = 2, pause = function() pauses = pauses + 1; test_slice() end})
  assert(#merged == 500 and pauses == 20, pauses)
  for _, channel in ipairs(merged) do assert(#channel.lines <= 2) end
  -- A channel past the cap is dropped, but more lines of the ones kept are still taken.
  local capped = channels.merge({{name = "A", url = "1"}, {name = "B", url = "2"}, {name = "C", url = "3"}, {name = "A", url = "4"}}, {maxChannels = 2})
  assert(#capped == 2 and #capped[1].lines == 2 and capped[2].name == "B")
end)

-- ------------------------------------------------------------------------------------------------ health
test("a failing line is paused longer every time, a working one is not", function()
  assert(health.pause(1, 10) == 10 * MINUTE and health.pause(2, 10) == 30 * MINUTE and health.pause(3, 10) == 90 * MINUTE)
  assert(health.pause(40, 10) == health.MAX_PAUSE and health.pause(1, 100000) == health.MAX_PAUSE)
  local record = health.fail(nil, 1000, 10)
  assert(record.f == 1 and record.u == 1000 + 10 * MINUTE and record.t == 1000)
  assert(not health.usable(record, 1000) and not health.usable(record, record.u - 1) and health.usable(record, record.u))
  assert(health.usable(nil, 5))
  record = health.fail(record, record.u, 10)
  assert(record.f == 2 and record.u == 10 * MINUTE + 1000 + 30 * MINUTE)
  record = health.ok(record, 99999)
  assert(record.f == nil and record.u == nil and record.o == 99999 and record.t == 99999 and health.usable(record, 0))
  record = health.unknown(health.ok(nil, 5), 9)
  assert(record.x == true and record.t == 9 and record.f == nil and health.usable(record, 0))
end)

test("which lines are due for a test, and in what order", function()
  local recheck = 12 * HOUR
  assert(health.due(nil, 1, recheck) == 2 and health.due({}, 1, recheck) == 2)
  local worked = {t = 0, o = 0}
  assert(health.due(worked, recheck - 1, recheck) == nil and health.due(worked, recheck, recheck) == 3)
  assert(health.due({f = 1, u = 500, t = 100}, 499, recheck) == nil and health.due({f = 1, u = 500, t = 100}, 500, recheck) == 1)
  local untestable = {t = 0, x = true}
  assert(health.due(untestable, 6 * 24 * HOUR, recheck) == nil and health.due(untestable, 7 * 24 * HOUR, recheck) == 3)
  local refused = health.unknown(nil, 0, HOUR)                     -- refused by the network rules: they may be lifted soon
  assert(refused.x and refused.r == HOUR and health.due(refused, HOUR - 1, recheck) == nil and health.due(refused, HOUR, recheck) == 3)
  assert(health.ok(health.unknown(nil, 0, HOUR), 5).r == nil and health.fail(health.unknown(nil, 0, HOUR), 5, 10).r == nil)
end)

-- ------------------------------------------------------------------------------------------------ guide
test("times of the guide formats", function()
  assert(guide.xmltvTime("20261102093000 +0800") == NOW and guide.xmltvTime("20261102013000") == NOW)
  assert(guide.xmltvTime("202611020130 -0000") == NOW and guide.xmltvTime("20261101203000 -0500") == NOW and guide.xmltvTime("junk") == nil)
  assert(guide.isoTime("2026-11-02T09:30:00+08:00") == NOW and guide.isoTime("2026-11-02 01:30:00") == NOW)
  assert(guide.isoTime("2026-11-02 09:30:00", 8 * HOUR) == NOW and guide.isoTime("2026-11-02T01:30:00.250Z") == NOW and guide.isoTime("soon") == nil)
  assert(guide.clock("06:30") == 390 and guide.clock("25:00") == nil and guide.clock("6:5") == nil and guide.clock("x") == nil)
  assert(guide.daysFromCivil(1970, 1, 1) == 0 and guide.daysFromCivil(2000, 3, 1) == 11017 and guide.daysFromCivil(2026, 11, 2) * 86400000 == NOW - 9 * HOUR - 30 * MINUTE + 8 * HOUR + 0)
end)

test("the calendar date of an instant in a zone", function()
  assert(guide.ymd(0, 0) == "19700101" and guide.ymd(NOW, 8 * HOUR) == "20261102" and guide.ymd(NOW, 0) == "20261102")
  assert(guide.ymd(NOW + 14 * HOUR + 20 * MINUTE, 8 * HOUR) == "20261102" and guide.ymd(NOW + 14 * HOUR + 30 * MINUTE, 8 * HOUR) == "20261103")
  assert(guide.ymd(guide.daysFromCivil(2024, 2, 29) * 86400000 + 12 * HOUR, 0) == "20240229")
  local newYear = guide.daysFromCivil(2026, 12, 31) * 86400000 + 23 * HOUR + 59 * MINUTE
  assert(guide.ymd(newYear, 0) == "20261231" and guide.ymd(newYear, 8 * HOUR) == "20270101" and guide.ymd(newYear, -24 * HOUR) == "20261230")
end)

test("CCTV's schedule has its own ids for the channels, and is chosen by its address", function()
  local cntv = use("guides.cntv")
  for key, id in pairs({cctv1 = "cctv1", cctv2 = "cctv2", cctv13 = "cctv13", cctv17 = "cctv17", ["cctv5+"] = "cctv5plus", cctv4k = "cctv4k",
    cctv8k = "cctv8k", cctv9 = "cctvjilu", cctv14 = "cctvchild"}) do
    assert(cntv.idOf(key) == id, key)
  end
  for _, key in ipairs({"cctv0", "cctv18", "cctv99", "cctv", "hunan", "news24", "cctv1hd", "cctv5++", ""}) do assert(cntv.idOf(key) == nil, key) end
  assert(cntv.idOf(channels.key("CCTV-1 综合 HD")) == "cctv1" and cntv.idOf(channels.key("CCTV-5+ 体育赛事")) == "cctv5plus")
  assert(cntv.idOf(channels.key("CCTV-4K")) == "cctv4k" and cntv.idOf(channels.key("CCTV-16奥林匹克")) == "cctv16" and cntv.idOf(channels.key("湖南卫视")) == nil)
  assert(cntv.accepts("https://api.cntv.cn/epg/epginfo") and cntv.accepts("https://api.cntv.cn/epg/epginfo?c=x"))
  assert(not cntv.accepts("https://api.cntv.cn/other") and not cntv.accepts("http://api.cntv.cn/epg/epginfo"))
  assert(guides.provider("https://api.cntv.cn/epg/epginfo").id == "cntv" and guides.provider("https://x.test/?ch={name}").id == "template")
  assert(guides.provider("https://x.test/guide.xml").id == "xmltv" and guides.provider("ftp://x.test/guide.xml") == nil)
end)

test("titles are plain, valid and short", function()
  assert(guide.title("  Tom &amp; Jerry <![CDATA[& more]]> &#x41;&bogus; ") == "Tom & Jerry & more A")
  assert(guide.title("a\n\t b") == "a b" and guide.title("bad \200 byte") == "bad ? byte")
  local long = guide.title(string.rep("汉", 100))
  assert(#long == 78 and utf8.len(long) == 26)
  assert(guide.encode("CCTV-1 综合") == "CCTV-1%20%E7%BB%BC%E5%90%88" and guide.encode("a/b?c=d&e") == "a%2Fb%3Fc%3Dd%26e")
end)

test("a guide is tidied to what is near now, and the programme on air is found", function()
  local list = guide.tidy({{s = 3000, e = 0, t = "B"}, {s = 1000, e = 2000, t = "A"}, {s = 5000, e = 0, t = "C"}}, 1500)
  assert(#list == 3 and list[1].t == "A" and list[2].e == 5000 and list[3].e == 5000 + 2 * HOUR)
  local current, following = guide.at(list, 1500)
  assert(current.t == "A" and following.t == "B")
  current, following = guide.at(list, 2500)
  assert(current == nil and following.t == "B")
  current, following = guide.at(list, 4000)
  assert(current.t == "B" and following.t == "C")
  current, following = guide.at(list, 100 * HOUR)
  assert(current == nil and following == nil)
  assert(select(1, guide.at(nil, 1)) == nil)
  assert(guide.covers(list, 1500, 2 * HOUR - 100) and not guide.covers(list, 1500, 3 * HOUR) and not guide.covers(nil, 1, 1))
  assert(#guide.tidy({{s = 1000, e = 2000, t = "A"}, {s = 1000, e = 3000, t = "A2"}}, 1500) == 1)
  assert(#guide.tidy({{s = 1, e = 2, t = "old"}}, 10 * HOUR) == 0)
  assert(#guide.tidy({{s = 20 * HOUR, e = 21 * HOUR, t = "far"}}, 0) == 0)
  local many = {}
  for i = 1, 100 do many[i] = {s = i * MINUTE, e = (i + 1) * MINUTE, t = "p" .. i} end
  assert(#guide.tidy(many, 0) == guide.PER_CHANNEL)
end)

test("JSON guides in the shapes the services use", function()
  local day = guide.daysFromCivil(2026, 11, 2) * 86400000
  local list = guide.fromJson({epg_data = {{start = "09:00", ["end"] = "10:00", title = "A"}, {start = "23:30", ["end"] = "00:30", title = "Late"},
    {start = "00:30", ["end"] = "01:00", title = "After midnight"}}}, day, 0, day + 20 * HOUR)
  assert(#list == 2 and list[1].t == "Late" and list[1].s == day + 23 * HOUR + 30 * MINUTE and list[1].e == day + 24 * HOUR + 30 * MINUTE)
  assert(list[2].t == "After midnight" and list[2].s == day + 24 * HOUR + 30 * MINUTE and list[2].e == day + 25 * HOUR)

  list = guide.fromJson({programs = {{start = 1793583000, ["end"] = 1793586600, title = "News"}}}, day, 0, NOW + 1000)
  assert(#list == 1 and list[1].s == NOW and list[1].e == NOW + HOUR and list[1].t == "News")
  list = guide.fromJson({{start = "2026-11-02T09:30:00+08:00", name = "Iso"}}, day, 0, NOW + 1000)
  assert(#list == 1 and list[1].s == NOW and list[1].e == NOW + 2 * HOUR and list[1].t == "Iso")
  list = guide.fromJson({data = {{starttime = 1793583000000, endtime = 1793586600000, title = "Millis &amp; more"}}}, day, 0, NOW + 1000)
  assert(list[1].s == NOW and list[1].t == "Millis & more")
  assert(guide.fromJson("x", day, 0, NOW) == nil and guide.fromJson({}, day, 0, NOW) == nil and guide.fromJson({data = {}}, day, 0, NOW) == nil)
  assert(guide.fromJson({{start = "soon", title = "Never"}, {title = "No start"}, {start = 5}}, day, 0, NOW) == nil)
  local filler = {}
  for h = 0, 22, 2 do filler[#filler + 1] = {title = "Good programmes", start = string.format("%02d:00", h), ["end"] = string.format("%02d:59", h + 1)} end
  assert(guide.fromJson({epg_data = filler}, day, 0, NOW) == nil)                   -- twelve times the same title is no guide
  filler[2].title = "News"
  assert(guide.fromJson({epg_data = filler}, day, 0, NOW) ~= nil)
end)

test("an XMLTV guide gives each of our channels its programmes", function()
  local xmltv = use("guides.xmltv")
  local text = table.concat({
    '<?xml version="1.0" encoding="UTF-8"?><tv>',
    '<channel id="c1"><display-name lang="en">CCTV-1</display-name><display-name>综合</display-name></channel>',
    "<channel id='x9'><display-name>Elsewhere</display-name></channel>",
    '<programme start="20261102090000 +0800" stop="20261102100000 +0800" channel="c1"><title lang="zh">Morning &amp; News</title><desc>x</desc></programme>',
    '<programme start="20261102100000 +0800" stop="20261102110000 +0800" channel="c1"><title><![CDATA[Doc <b>]]></title></programme>',
    '<programme start="20261101090000 +0800" stop="20261101100000 +0800" channel="c1"><title>Yesterday</title></programme>',
    '<programme start="20261102090000 +0800" stop="20261102100000 +0800" channel="x9"><title>Not ours</title></programme>',
    '<programme start="junk" stop="20261102100000 +0800" channel="c1"><title>No start</title></programme>',
    "</tv>"}, "\n")
  local asked = {}
  local lists = xmltv.parse(text, function(id, names) asked[#asked + 1] = id .. ":" .. table.concat(names, "|"); if id == "c1" then return "cctv1" end end, NOW)
  assert(asked[1] == "c1:CCTV-1|综合" and asked[2] == "x9:Elsewhere")
  assert(lists.cctv1 and #lists.cctv1 == 2 and lists.x9 == nil and next(lists, next(lists)) == nil)
  assert(lists.cctv1[1].t == "Morning & News" and lists.cctv1[1].s == NOW - 30 * MINUTE and lists.cctv1[1].e == NOW + 30 * MINUTE)
  assert(lists.cctv1[2].t == "Doc <b>")
end)

-- ------------------------------------------------------------------------------------------------ main.lua
local function hash(url)
  local h = 2166136261
  for i = 1, #url do h = ((h ~ url:byte(i)) * 16777619) & 0xffffffff end
  return string.format("%08x", h)
end

-- The calendar date of an instant in a zone `offset` ms east of UTC.
local function civil(ms, offset)
  local days = (ms + offset) // 86400000 + 719468
  local era = days // 146097
  local doe = days - era * 146097
  local yoe = (doe - doe // 1460 + doe // 36524 - doe // 146096) // 365
  local doy = doe - (365 * yoe + yoe // 4 - yoe // 100)
  local mp = (5 * doy + 2) // 153
  local d = doy - (153 * mp + 2) // 5 + 1
  local m = mp < 10 and mp + 3 or mp - 9
  return yoe + era * 400 + (m <= 2 and 1 or 0), m, d
end

local LIST_A = table.concat({
  '#EXTM3U x-tvg-url="https://epg.test/guide.xml"',
  '#EXTINF:-1 tvg-id="cctv1.cn" group-title="Central",CCTV-1 综合 HD',
  "https://a.test/cctv1.m3u8",
  '#EXTINF:-1 group-title="News",News 24',
  "https://a.test/news24.m3u8",
  '#EXTINF:-1 group-title="Kids",Kids TV',
  "https://a.test/kids.m3u8",
}, "\n")
local LIST_B = table.concat({
  "#EXTM3U",
  '#EXTINF:-1 tvg-id="cctv1.cn",CCTV1',
  "https://b.test/cctv1-1080.m3u8",
  "#EXTINF:-1,cctv-1 [1080p]",
  "https://b.test/cctv1-alt.m3u8",
  "#EXTINF:-1,News 24 HD",
  "https://b.test/news24.m3u8",
  "#EXTINF:-1,Weather",
  "#EXTVLCOPT:http-user-agent=Vendor/2.0",
  "https://b.test/weather.m3u8",
}, "\n")
local GUIDE = table.concat({
  "<tv>",
  '<channel id="cctv1.cn"><display-name>CCTV-1</display-name></channel>',
  '<channel id="n24"><display-name>News 24</display-name></channel>',
  '<programme start="20261102090000 +0800" stop="20261102100000 +0800" channel="cctv1.cn"><title>Morning &amp; News</title></programme>',
  '<programme start="20261102100000 +0800" stop="20261102110000 +0800" channel="cctv1.cn"><title>Documentary</title></programme>',
  '<programme start="20261102090000 +0800" stop="20261102094500 +0800" channel="n24"><title>Headlines</title></programme>',
  "</tv>"}, "\n")

-- The plugin in a stand-in for the OS: options.config (settings), .pages (address -> {body | status | error}), .probes (address ->
-- result of tos.media.probe, or a function), .store (what a previous run saved), .offline.
local function new(options)
  options = options or {}
  loaded = {}
  local env = {handlers = {}, timers = {}, serial = 0, state = {}, store = options.store and clone(options.store) or {}, loads = {}, focus = {},
    probes = {}, probeOptions = {}, stops = 0, logs = {}, requests = {}, now = options.now or NOW, probeResults = options.probes or {},
    media = {status = "stopped", owner = "", index = 0, count = 0}, pages = {}}
  if not options.offline then
    env.pages["https://tv.test/a.m3u"], env.pages["https://cdn.test/b.m3u"] = {body = LIST_A}, {body = LIST_B}
  end
  for url, page in pairs(options.pages or {}) do env.pages[url] = page end
  local cfg = {}
  for key, field in pairs(manifest.settings) do cfg[key] = field.default end
  cfg.source_1, cfg.source_2 = "https://tv.test/a.m3u", "https://cdn.test/b.m3u"
  cfg.test = false                     -- the tests of lines are the subject of the tests that switch them on
  cfg.epg_url, cfg.epg_zone = "", ""   -- no guide unless a test asks for one (the plugin's own default is CCTV's schedule: see the defaults test)
  for key, value in pairs(options.config or {}) do cfg[key] = value end
  env.config = cfg
  local function translate(key, args)
    local value = strings[key] or key
    return (value:gsub("{([%w_]+)}", function(k) return tostring(args and args[k]) end))
  end
  tos = {
    config = cfg, json = json,
    on = function(name, fn) env.handlers[name] = fn end,
    state = {
      set = function(text) for key, value in pairs(json.decode(text)) do env.state[key] = value end end,
      get = function(path) local key = path:match("^media%.(.+)$"); if key then return env.media[key] end end,
    },
    store = {get = function(key) return env.store[key] end, set = function(key, value) env.store[key] = value end},
    time = {
      now = function() return env.now end,
      format = function(ms, pattern, zone)
        local offset = zone == "UTC" and 0 or 8 * 3600000
        if pattern == "Z" then return offset == 0 and "+0000" or "+0800" end
        local y, m, d = civil(ms, offset)
        if pattern == "yyyy-MM-dd" then return string.format("%04d-%02d-%02d", y, m, d) end
        if pattern == "yyyyMMdd" then return string.format("%04d%02d%02d", y, m, d) end
        return ""
      end,
    },
    i18n = {t = translate},
    log = {info = function() end, warn = function(...) env.logs[#env.logs + 1] = table.concat({...}, " ") end, error = function() end},
    timer = {
      after = function(delay, fn) env.serial = env.serial + 1; env.timers[env.serial] = {fn = fn, delay = delay}; return env.serial end,
      every = function(delay, fn) env.serial = env.serial + 1; env.timers[env.serial] = {fn = fn, delay = delay, every = true}; return env.serial end,
      cancel = function(id) env.timers[id] = nil end,
      sleep = function() test_slice() end,
    },
    http = {get = function(url, opts)
      env.requests[#env.requests + 1] = url
      local page = env.pages[url]
      if type(page) == "function" then page = page(url) end
      if not page then return nil, "offline" end
      if page.error then return nil, page.error end
      return {status = page.status or 200, headers = {}, body = page.body}
    end},
    media = {
      load = function(entries, opts)
        env.loads[#env.loads + 1] = {entries = clone(entries), options = clone(opts)}
        if env.refuse then return nil, "refused" end
        env.media.status, env.media.owner, env.media.index, env.media.count = "buffering", "iptv", opts.index, #entries
        return clone(env.media)
      end,
      stop = function() env.stops = env.stops + 1; env.media.status, env.media.owner = "stopped", ""; return clone(env.media) end,
      probe = function(url, opts)
        env.probes[#env.probes + 1] = url
        env.probeOptions[#env.probeOptions + 1] = opts and clone(opts) or false
        local result
        if type(env.probeResults) == "function" then result = env.probeResults(url) else result = env.probeResults[url] end
        if result == nil then return {ok = true, status = 200, kind = "stream", millis = 5} end
        if result == false then return nil, "unavailable" end
        return clone(result)
      end,
    },
    page = {focus = function(page, opts) env.focus[#env.focus + 1] = {page = page, live = opts and opts.live} end, release = function() end},
    back = {intercept = function(on) env.intercepting = on == true end},
  }
  assert(load(src_main, "iptv", "t"))()
  test_slice() -- Main/imports finish before production dispatches the start event.
  -- Every event and timer is a fresh 50 ms slice in the app; here each one ends the slice it ran in.
  function env.fire(delay)
    local ids = {}
    for id, timer in pairs(env.timers) do if delay == nil or timer.delay == delay then ids[#ids + 1] = id end end
    table.sort(ids)
    for _, id in ipairs(ids) do
      local timer = env.timers[id]
      if timer then
        if not timer.every then env.timers[id] = nil end
        timer.fn()
        test_slice()
      end
    end
  end
  function env.event(name, ...)
    local result = assert(env.handlers[name], name)(...)
    test_slice()
    return result
  end
  function env.timersWith(delay)
    local count = 0
    for _, timer in pairs(env.timers) do if timer.delay == delay then count = count + 1 end end
    return count
  end
  function env.asked(url)
    local count = 0
    for _, asked in ipairs(env.requests) do if asked == url then count = count + 1 end end
    return count
  end
  return env
end

local function boot(options)
  local env = new(options)
  env.event("start")
  env.fire(3)
  return env
end

local function absent(value) return value == nil or value == json.null end

test("two lists become one list of channels, the same channel once with all its lines", function()
  local env = boot({config = {test = true}})
  local rows = env.state.items
  assert(#rows == 4, #rows)
  assert(rows[1].title == "1  CCTV-1 综合 HD" and rows[1].subtitle == "Central" and rows[1].trailing == "" and rows[1].icon == nil)
  assert(rows[2].title == "2  News 24" and rows[2].trailing == "" and rows[3].title == "3  Kids TV" and rows[3].trailing == "")
  assert(rows[4].title == "4  Weather" and rows[4].subtitle == "")
  assert(env.state.tab == 3 and #env.state.tabs == 4 and env.state.tabs[3] == strings["tab.all"] and env.state.current == 0)
  assert(env.state.footer == "", env.state.footer) -- background progress is not UI copy
  assert(env.requests[1] == "https://tv.test/a.m3u" and env.requests[2] == "https://cdn.test/b.m3u")
  local saved = json.decode(env.store.list)
  assert(#saved.items == 4 and saved.epg == "https://epg.test/guide.xml" and #saved.items[1].l == 3)
  assert(env.state.tv.number == 0 and env.state.tv.name == "" and #env.focus == 0)
end)

test("choosing a channel plays its first line on the live page and shows it", function()
  local env = boot()
  env.event("tap", "play", 1)
  assert(#env.loads == 1 and env.loads[1].options.index == 1 and #env.loads[1].entries == 1)
  local entry = env.loads[1].entries[1]
  assert(entry.url == "https://a.test/cctv1.m3u8" and entry.title == "CCTV-1 综合 HD" and entry.artist == "Central" and entry.kind == "video" and entry.headers == nil)
  assert(env.focus[#env.focus].page == "iptv.live" and env.focus[#env.focus].live == true)
  local tv = env.state.tv
  assert(tv.number == 1 and tv.name == "CCTV-1 综合 HD" and tv.group == "Central" and tv.line == 1 and tv.lines == 3 and tv.favorite == false and tv.notice == "")
  assert(absent(tv.program) and absent(tv["next"]))
  assert(env.state.current == 1)
  assert(json.decode(env.store.prefs).recent[1] == "cctv1")
  env.event("tap", "play", 4)
  assert(env.loads[2].entries[1].url == "https://b.test/weather.m3u8" and env.loads[2].entries[1].headers["User-Agent"] == "Vendor/2.0")
  env.event("tap", "play", 99); env.event("tap", "play", "x"); env.event("tap", "play")
  assert(#env.loads == 2)
end)

test("a line that fails is given up and the next one takes over, with a note", function()
  local env = boot()
  env.event("tap", "play", 1)
  env.event("media.error", {category = "http", status = 404})
  assert(#env.loads == 2 and env.loads[2].entries[1].url == "https://b.test/cctv1-alt.m3u8")
  assert(env.state.tv.line == 2 and env.state.tv.notice == "Source 2/3", env.state.tv.notice)
  env.fire(1)
  assert(env.state.items[1].trailing == "2/3")
  env.fire(20)
  local count, only = 0, nil
  for h, record in pairs(json.decode(env.store.health)) do
    count = count + 1; only = record
    assert(h == hash("https://a.test/cctv1.m3u8"))
  end
  assert(count == 1 and only.f == 1 and only.u == NOW + 10 * MINUTE)
  env.event("media.error", {category = "network"})
  assert(#env.loads == 3 and env.loads[3].entries[1].url == "https://b.test/cctv1-1080.m3u8" and env.state.tv.line == 3)
  env.event("media.error", {category = "format", codec = "video/avc"})
  assert(#env.loads == 3 and env.stops == 1)
  assert(env.state.tv.notice == strings["notice.all_failed"] and env.state.tv.line == 0)
end)

test("a line that has played for a while is remembered and starts first next time", function()
  local env = boot()
  env.event("tap", "play", 1)
  env.media.status = "playing"
  env.fire(2)                                   -- the heartbeat sees it playing
  env.now = env.now + 6000; env.fire(2)         -- ... and still playing a while later
  env.event("tap", "tv", "line")
  assert(env.state.tv.line == 2 and #env.loads == 2 and env.loads[2].entries[1].url == "https://b.test/cctv1-alt.m3u8")
  env.media.status = "playing"
  env.now = env.now + 1000; env.fire(2)
  env.now = env.now + 6000; env.fire(2)
  env.event("tap", "play", 1)
  assert(#env.loads == 3 and env.loads[3].entries[1].url == "https://b.test/cctv1-alt.m3u8")
  env.fire(20)
  local first = json.decode(env.store.health)[hash("https://a.test/cctv1.m3u8")]
  assert(first.o and first.p and first.f == nil)
end)

test("a line that does not start in time is given up", function()
  local env = boot()
  env.event("tap", "play", 1)
  env.now = env.now + 19000; env.fire(2)
  assert(#env.loads == 1)
  env.now = env.now + 2000; env.fire(2)
  assert(#env.loads == 2 and env.loads[2].entries[1].url == "https://b.test/cctv1-alt.m3u8" and env.state.tv.line == 2)
end)

test("an engine that refuses every line leaves a note and no playback", function()
  local env = boot()
  env.refuse = true
  env.event("tap", "play", 1)
  assert(#env.loads == 3 and env.stops == 0)
  assert(env.state.tv.notice == strings["notice.all_failed"] and env.state.tv.line == 0)
  env.fire(20)
  local count = 0
  for _ in pairs(json.decode(env.store.health)) do count = count + 1 end
  assert(count == 3)
end)

test("a refusal by the network rules is not held against the lines", function()
  local env = boot()
  env.event("tap", "play", 1)
  for _ = 1, 3 do env.event("media.error", {category = "policy"}) end
  assert(#env.loads == 3 and env.state.tv.notice == strings["notice.policy"], env.state.tv.notice)
  assert(env.store.health == nil and env.timersWith(20) == 0)
end)

test("channel up and down skip channels whose lines are all paused", function()
  local env = boot()
  env.event("tap", "play", 2)
  env.event("media.error", {category = "network"})
  env.event("media.error", {category = "network"})
  assert(env.state.tv.notice == strings["notice.all_failed"])
  env.event("tap", "play", 1)
  local loads = #env.loads
  env.event("tap", "tv", "next")
  assert(#env.loads == loads + 1 and env.loads[#env.loads].entries[1].url == "https://a.test/kids.m3u8" and env.state.tv.number == 3)
  env.event("tap", "tv", "prev")
  assert(env.loads[#env.loads].entries[1].url == "https://a.test/cctv1.m3u8" and env.state.tv.number == 1)
  env.event("command", "prev")                    -- channel down from the first wraps to the last
  assert(env.loads[#env.loads].entries[1].url == "https://b.test/weather.m3u8" and env.state.tv.number == 4)
end)

test("favorites, recents and groups are views of the same list", function()
  local env = boot()
  env.event("tap", "play", 1)
  env.event("tap", "tv", "favorite")
  assert(env.state.tv.favorite == true and json.decode(env.store.prefs).favorites.cctv1 == true)
  env.event("tap", "tab", 1)
  assert(env.state.tab == 1 and #env.state.items == 1 and env.state.items[1].title == "1  CCTV-1 综合 HD")
  env.event("tap", "tv", "favorite")
  assert(env.state.tv.favorite == false and #env.state.items == 0 and env.state.footer == strings["footer.empty_favorites"])
  env.event("tap", "tab", 2)
  assert(env.state.tab == 2 and #env.state.items == 1)
  env.event("tap", "tab", 4)
  local rows = env.state.items
  assert(env.state.tab == 4 and #rows == 4 and rows[1].title == "Central" and rows[1].subtitle == "1 channels" and rows[4].title == "Other")
  env.event("tap", "play", 2)                     -- a group row filters the list
  assert(env.state.tab == 3 and #env.state.items == 1 and env.state.items[1].title == "2  News 24" and env.state.tabs[3] == "News")
  env.event("tap", "tab", 3)                      -- and the same tab again lifts the filter
  assert(#env.state.items == 4 and env.state.tabs[3] == strings["tab.all"])
  env.event("tap", "tab", 9); env.event("tap", "tab", 0); env.event("tap", "tab")
  assert(env.state.tab == 3)
end)

test("an open group takes Back for itself, and Back closes it before the app does", function()
  local env = boot()
  env.event("page.enter", "channels")
  assert(env.intercepting == false)               -- nothing is open: Back leaves the app
  env.event("tap", "tab", 4)
  assert(env.intercepting == false)               -- the list of groups is the top level
  env.event("tap", "play", 2)                     -- a group is opened
  assert(env.state.tab == 3 and env.state.tabs[3] == "News" and env.intercepting == true)
  env.event("back", "channels")                   -- Back: up to the groups, and Back is given back
  assert(env.state.tab == 4 and #env.state.items == 4 and env.intercepting == false)
  env.event("tap", "play", 2); env.event("tap", "tab", 3)   -- the same tab again lifts the filter by itself
  assert(env.intercepting == false)
  env.event("back", "live")                       -- another page's Back is not this page's business
  assert(env.state.tab == 3)
end)

test("lines are tested in the background and a failing one is paused", function()
  local env = boot({config = {test = true}, probes = {
    ["https://a.test/cctv1.m3u8"] = {ok = true, status = 200, kind = "hls", millis = 12},
    ["https://b.test/cctv1-alt.m3u8"] = {ok = false, status = 404, kind = "", error = "http", millis = 9},
  }})
  env.fire(10); env.fire(10)
  assert(#env.probes == 2 and env.probes[1] == "https://a.test/cctv1.m3u8" and env.probes[2] == "https://b.test/cctv1-alt.m3u8")
  env.fire(20)
  local records = json.decode(env.store.health)
  local alive, dead = records[hash("https://a.test/cctv1.m3u8")], records[hash("https://b.test/cctv1-alt.m3u8")]
  assert(alive.k == "hls" and alive.f == nil and dead.f == 1 and dead.u == NOW + 10 * MINUTE)
  env.fire(1)
  assert(env.state.items[1].trailing == "2/3")
  env.event("tap", "play", 1)
  assert(env.loads[1].entries[1].url == "https://a.test/cctv1.m3u8" and env.loads[1].entries[1].type == "hls")
end)

test("favorites and what was watched lately are tested before the rest", function()
  local env = boot()
  env.event("tap", "play", 4)
  env.event("tap", "tv", "favorite")
  env.event("page.leave", "live")
  assert(env.stops == 1 and env.state.tv.number == 0)
  env.event("tap", "play", 3); env.event("page.leave", "live")
  env.config.test = true; env.event("config", env.config)           -- nothing has been tested yet: the order is all there is
  env.fire(10)
  assert(env.probes[1] == "https://b.test/weather.m3u8")
  env.fire(10)
  assert(env.probes[2] == "https://a.test/kids.m3u8")
end)

test("the tester waits while a channel starts, while the screen sleeps and when it is switched off", function()
  local env = boot({config = {test = true}})
  env.event("tap", "play", 1)
  local asked = #env.probes                       -- the line was looked at before the player got it, once
  assert(asked == 1 and env.probes[1] == "https://a.test/cctv1.m3u8")
  env.fire(10)
  assert(#env.probes == asked)                    -- the channel is still starting: the tester leaves the network alone
  env.media.status = "playing"; env.fire(2); env.fire(10)
  assert(#env.probes == asked + 1 and env.probes[2] ~= "https://a.test/cctv1.m3u8")      -- and then not the line that is playing
  env.event("attention", "asleep"); env.fire(10)
  assert(#env.probes == asked + 1)
  env.event("attention", "AWAKE")
  env.config.test = false; env.event("config", env.config); env.fire(10)
  assert(#env.probes == asked + 1)
  env.config.test = true; env.event("config", env.config); env.fire(10)
  assert(#env.probes == asked + 2)
end)

local TIMEOUT = {ok = false, status = 0, kind = "", error = "timeout"}

test("when nothing answers anywhere the lines are not blamed, and the tester tries again later", function()
  local first = boot()                                              -- a list saved by an earlier run
  first.event("stop")
  local env = new({store = first.store, offline = true, config = {test = true}, probes = function() return TIMEOUT end})
  env.event("start"); env.fire(3)                                   -- the lists cannot be fetched: nothing has answered yet
  env.fire(10); env.fire(10)
  assert(#env.probes == 2 and env.probes[2] == "https://tv.test/a.m3u", #env.probes)       -- a line, and then the first list's own address
  assert(env.timersWith(20) == 0 and next(json.decode(env.store.health or "[]")) == nil)
  env.now = env.now + 6 * MINUTE; env.fire(10)
  assert(#env.probes == 4)
end)

test("a silent line is held against it while the list's own address answers, long after the last good answer", function()
  local env = boot({config = {test = true}, probes = function(url)
    if url == "https://tv.test/a.m3u" then return {ok = false, status = 200, kind = "list", error = "format", millis = 9} end
    return TIMEOUT
  end})
  env.now = env.now + 20 * MINUTE                                   -- the answers of the start are old by now
  env.fire(10); env.fire(20)
  assert(env.probes[1] == "https://a.test/cctv1.m3u8" and env.probes[2] == "https://tv.test/a.m3u")
  assert(json.decode(env.store.health)[hash("https://a.test/cctv1.m3u8")].f == 1)
end)

test("a timeout counts against a line once the network has answered", function()
  local env = boot({config = {test = true}, probes = {["https://b.test/cctv1-alt.m3u8"] = {ok = false, status = 0, kind = "", error = "timeout"}}})
  env.fire(10); env.fire(10)
  env.fire(20)
  local record = json.decode(env.store.health)[hash("https://b.test/cctv1-alt.m3u8")]
  assert(record and record.f == 1)
end)

test("a test that cannot judge a line does not pause it", function()
  local env = boot({config = {test = true}, probes = {["https://a.test/cctv1.m3u8"] = {ok = false, status = 0, kind = "", error = "policy"},
    ["https://b.test/cctv1-alt.m3u8"] = false}})
  env.fire(10); env.fire(10); env.fire(20)
  local records = json.decode(env.store.health)
  local first, second = records[hash("https://a.test/cctv1.m3u8")], records[hash("https://b.test/cctv1-alt.m3u8")]
  assert(first.x == true and first.f == nil and first.u == nil and second.x == true and second.f == nil)
  env.fire(10)
  assert(env.probes[3] == "https://b.test/cctv1-1080.m3u8")                     -- untestable ones are not asked again soon
end)

test("a line with its own request headers is tested with them", function()
  local env = boot({config = {test = true}})
  for _ = 1, 8 do env.fire(10) end
  local seen
  for i, url in ipairs(env.probes) do if url == "https://b.test/weather.m3u8" then seen = env.probeOptions[i] end end
  assert(seen and seen.headers["User-Agent"] == "Vendor/2.0", seen)
  assert(env.probeOptions[1] == false)
end)

local DEAD = {ok = false, status = 404, kind = "", error = "http", millis = 8}
local PAGE = {ok = false, status = 200, kind = "html", error = "format", millis = 8}

test("a line never tested is looked at before the player gets it, so dead ones cost no waiting", function()
  local env = boot({config = {test = true}, probes = {["https://a.test/cctv1.m3u8"] = DEAD, ["https://b.test/cctv1-alt.m3u8"] = PAGE}})
  env.event("tap", "play", 1)
  assert(#env.probes == 3 and env.probes[1] == "https://a.test/cctv1.m3u8" and env.probes[3] == "https://b.test/cctv1-1080.m3u8")
  assert(#env.loads == 1 and env.loads[1].entries[1].url == "https://b.test/cctv1-1080.m3u8")      -- the player never saw the other two
  assert(env.state.tv.line == 3 and env.state.tv.notice == "Source 3/3", env.state.tv.notice)
  env.fire(20)
  local records = json.decode(env.store.health)
  assert(records[hash("https://a.test/cctv1.m3u8")].f == 1 and records[hash("https://b.test/cctv1-alt.m3u8")].f == 1)
  assert(records[hash("https://b.test/cctv1-1080.m3u8")].f == nil and records[hash("https://b.test/cctv1-1080.m3u8")].o)
  env.event("tap", "play", 1)                                                                      -- tested now: no second look
  assert(#env.probes == 3 and #env.loads == 2 and env.loads[2].entries[1].url == "https://b.test/cctv1-1080.m3u8")
end)

test("a channel whose every line is dead is told so without the player", function()
  local env = boot({config = {test = true}, probes = function() return DEAD end})
  env.event("tap", "play", 1)
  assert(#env.loads == 0 and env.stops == 0 and #env.probes == 3)
  assert(env.state.tv.notice == strings["notice.all_failed"] and env.state.tv.line == 0)
end)

test("a look that cannot tell, or is not wanted, leaves the line to the player", function()
  for _, answer in ipairs({false, {ok = false, status = 0, kind = "", error = "policy"}}) do
    local env = boot({config = {test = true}, probes = function() return answer end})
    env.event("tap", "play", 1)
    assert(#env.loads == 1 and env.loads[1].entries[1].url == "https://a.test/cctv1.m3u8" and #env.probes == 1)
  end
  local off = boot()                                                                               -- testing switched off
  off.event("tap", "play", 1)
  assert(#off.probes == 0 and #off.loads == 1)
  local rtsp = boot({config = {test = true}, pages = {["https://tv.test/a.m3u"] = {body = "#EXTM3U\n#EXTINF:-1,Cam\nrtsp://192.168.1.9/cam1\n"}, ["https://cdn.test/b.m3u"] = {body = ""}}})
  rtsp.event("tap", "play", 1)
  assert(#rtsp.probes == 0 and #rtsp.loads == 1)                                                   -- not an address a probe can speak
end)

test("a line that does not answer is given up before the player has to wait for it, when the network answers", function()
  local env = boot({config = {test = true}, probes = {["https://a.test/cctv1.m3u8"] = TIMEOUT}})
  env.event("tap", "play", 1)
  assert(#env.probes == 2 and #env.loads == 1 and env.loads[1].entries[1].url == "https://b.test/cctv1-alt.m3u8")
  env.fire(20)
  assert(json.decode(env.store.health)[hash("https://a.test/cctv1.m3u8")].f == 1)
end)

test("with no network at all a silent line is left to the player", function()
  local first = boot()
  first.event("stop")
  local env = new({store = first.store, offline = true, config = {test = true}, probes = function() return TIMEOUT end})
  env.event("start"); env.fire(3)
  env.event("tap", "play", 1)
  assert(#env.probes == 2 and #env.loads == 1 and env.loads[1].entries[1].url == "https://a.test/cctv1.m3u8")
  assert(env.timersWith(20) == 0)                                   -- nobody was blamed
end)

test("a line the network rules refused is looked at again when it is played", function()
  local line = "https://a.test/cctv1.m3u8"
  local answer = {ok = false, status = 0, kind = "", error = "policy"}
  local env = boot({config = {test = true}, probes = function(url) if url == line then return answer end end})
  env.fire(10); env.fire(20)
  local record = json.decode(env.store.health)[hash(line)]
  assert(record.x == true and record.r == 60 * MINUTE and record.f == nil and record.u == nil)
  answer = {ok = true, status = 200, kind = "hls", millis = 7}                   -- the switch was turned on in the meantime
  env.event("tap", "play", 1)
  local asked = 0
  for _, url in ipairs(env.probes) do if url == line then asked = asked + 1 end end
  assert(asked == 2 and env.loads[1].entries[1].url == line)
  env.fire(20)
  record = json.decode(env.store.health)[hash(line)]
  assert(record.x == nil and record.r == nil and record.k == "hls" and record.o)
end)

test("a channel chosen again while it is being looked at is not started twice", function()
  local env = boot({config = {test = true}})
  local looked = 0
  tos.media.probe = function(url)
    looked = looked + 1
    if looked == 1 then env.event("tap", "play", 3) end                                            -- the viewer chooses another channel meanwhile
    return {ok = true, status = 200, kind = "stream", millis = 5}
  end
  env.event("tap", "play", 1)
  assert(#env.loads == 1 and env.loads[1].entries[1].url == "https://a.test/kids.m3u8" and env.state.tv.number == 3)
end)

test("the guide named in a list header gives what is on and what is next", function()
  local env = boot({pages = {["https://epg.test/guide.xml"] = {body = GUIDE}}})
  env.event("tap", "play", 1)
  local tv = env.state.tv
  assert(tv.program.title == "Morning & News" and tv.program.start == NOW - 30 * MINUTE and tv.program["end"] == NOW + 30 * MINUTE)
  assert(tv["next"].title == "Documentary" and tv["next"].start == NOW + 30 * MINUTE)
  env.fire(1)
  assert(env.state.items[1].subtitle == "Morning & News" and env.state.items[2].subtitle == "Headlines" and env.state.items[3].subtitle == "Kids")
  env.fire(2)
  assert(env.asked("https://epg.test/guide.xml") == 1)                          -- one document for all channels, not one per tick
  env.event("tap", "play", 2)
  assert(env.state.tv.program.title == "Headlines" and env.asked("https://epg.test/guide.xml") == 1)
  env.event("tap", "play", 1)
  env.media.status = "playing"
  env.now = NOW + 35 * MINUTE; env.fire(2)                                       -- 10:05: the next programme is on
  assert(env.state.tv.program.title == "Documentary" and absent(env.state.tv["next"]) == true)
  assert(env.asked("https://epg.test/guide.xml") == 1)                          -- the document is kept for six hours
  env.now = NOW + 7 * HOUR; env.fire(2)
  assert(env.asked("https://epg.test/guide.xml") == 2 and absent(env.state.tv.program))      -- and read again after that
end)

test("a list in the shape of the preloaded one reads as channels and lines, and its compressed guide is not asked for", function()
  local text = table.concat({
    '#EXTM3U x-tvg-url="https://lists.test/epg.gz"',
    '#EXTINF:-1 tvg-id="CCTV-1" tvg-name="CCTV-1" tvg-logo="https://logos.test/CCTV-1.png" group-title="🕘️更新时间",2026-10-12 11:25:50',
    "http://192.0.2.10:82/live/cctv1hd.m3u8",
    '#EXTINF:-1 tvg-id="CCTV-1" tvg-name="CCTV-1" tvg-logo="https://logos.test/CCTV-1.png" group-title="📺央视频道",CCTV-1',
    "http://192.0.2.10:82/live/cctv1hd.m3u8",
    '#EXTINF:-1 tvg-id="CCTV-1" tvg-name="CCTV-1" tvg-logo="https://logos.test/CCTV-1.png" group-title="📺央视频道",CCTV-1',
    "http://[2001:db8::1]:8080/cctv1.m3u8",
    '#EXTINF:-1 tvg-id="CCTV-1" tvg-name="CCTV-1" tvg-logo="https://logos.test/CCTV-1.png" group-title="📺央视频道",CCTV-1',
    "#EXTVLCOPT:http-user-agent=iPhone",
    "https://cdn.test/cctv1.m3u8?auth=x",
    '#EXTINF:-1 tvg-id="CCTV-2" tvg-name="CCTV-2" tvg-logo="https://logos.test/CCTV-2.png" group-title="📺央视频道",CCTV-2',
    "rtmp://192.0.2.20/live/cctv2",
  }, "\n")
  local items, meta = sources.parse(nil, text, "https://lists.test/list.m3u", {limit = 100})
  assert(#items == 4 and meta.epg == "https://lists.test/epg.gz")                  -- the stamp is still an entry here, and rtmp is not
  local merged = channels.merge(items)
  assert(#merged == 1 and merged[1].name == "CCTV-1" and merged[1].group == "央视频道" and merged[1].id == "CCTV-1")   -- without the emoji
  assert(#merged[1].lines == 3 and merged[1].lines[2].url == "http://[2001:db8::1]:8080/cctv1.m3u8" and merged[1].lines[3].agent == "iPhone")
  local env = boot({config = {source_1 = "https://lists.test/list.m3u", source_2 = ""}, pages = {["https://lists.test/list.m3u"] = {body = text}}})
  assert(#env.state.items == 1 and env.state.items[1].title == "1  CCTV-1" and env.state.items[1].trailing == "")
  env.event("tap", "play", 1)
  env.fire(1)
  assert(env.asked("https://lists.test/epg.gz") == 0)                              -- tos.http cannot unpack it: no request, no error line
  for _, note in ipairs({"guide_gz", "guide_big", "guide_failed"}) do assert(env.state.footer ~= strings["notice." .. note]) end
end)

test("a guide service that has nothing for a channel is not a failed guide", function()
  local filler = {}
  for h = 0, 22, 2 do filler[#filler + 1] = {title = "Good programmes", start = string.format("%02d:00", h), ["end"] = string.format("%02d:59", h + 1)} end
  local want = "https://guide.test/api?ch=CCTV-1%20%E7%BB%BC%E5%90%88%20HD&date=2026-11-02"
  local env = boot({config = {epg_url = "https://guide.test/api?ch={name}&date={date}"}, pages = {[want] = {body = json.encode({epg_data = filler})}}})
  env.event("tap", "play", 1)
  env.fire(1)
  assert(env.asked(want) == 1 and absent(env.state.tv.program))
  for _, note in ipairs({"guide_gz", "guide_big", "guide_failed"}) do assert(env.state.footer ~= strings["notice." .. note]) end
  env.fire(2)
  assert(env.asked(want) == 1)                                                     -- and it is not asked again at every beat
end)

test("the plugin comes with a channel list and CCTV's own schedule already set", function()
  local settings = manifest.settings
  assert(settings.source_1.default == "https://guovin.github.io/iptv-api/result.m3u")
  for slot = 1, 4 do                                                                -- tos.http reads plain http from private networks only
    local default = settings["source_" .. slot].default
    assert(default == "" or default:match("^https://"), default)
  end
  assert(settings.source_2.default == "" and settings.source_3.default == "" and settings.source_4.default == "")
  -- The guide: CCTV's own schedule, which answers on the device's own network (the services that serve the other channels of this
  -- list, epg.112114.xyz, epg.pw, fanmingming, do not: a default that cannot be reached would only put an error in the footer). The
  -- guide the list itself names (a .gz) is skipped silently, see the test above.
  assert(settings.epg_url.default == "https://api.cntv.cn/epg/epginfo" and guides.provider(settings.epg_url.default).id == "cntv")
  assert(settings.epg_zone.default == "")
end)

local CNTV = "https://api.cntv.cn/epg/epginfo"

-- A day of CCTV's schedule as the service answers it; rows are {title, start ms, end ms}.
local function cntvDay(id, rows)
  local program = {}
  for i, row in ipairs(rows) do program[i] = {t = row[1], st = row[2] // 1000, et = row[3] // 1000, showTime = "00:00", duration = 1} end
  return {body = json.encode({[id] = {channelName = id, program = program}})}
end

test("CCTV's own schedule gives what is on and what is next for the CCTV channels", function()
  local today, tomorrow = CNTV .. "?c=cctv1&serviceId=tvcctv&d=20261102", CNTV .. "?c=cctv1&serviceId=tvcctv&d=20261103"
  local env = boot({config = {epg_url = CNTV}, pages = {
    [today] = cntvDay("cctv1", {{"朝闻天下", NOW - 30 * MINUTE, NOW + 30 * MINUTE}, {"Documentary &amp; more", NOW + 30 * MINUTE, NOW + 90 * MINUTE}}),
    [tomorrow] = cntvDay("cctv1", {})}})
  env.event("tap", "play", 1)
  local tv = env.state.tv
  assert(tv.program.title == "朝闻天下" and tv.program.start == NOW - 30 * MINUTE and tv.program["end"] == NOW + 30 * MINUTE)
  assert(tv["next"].title == "Documentary & more" and tv["next"].start == NOW + 30 * MINUTE)
  assert(env.asked(today) == 1 and env.asked(tomorrow) == 1)                       -- the day ends within two hours: tomorrow's too
  env.fire(1)
  assert(env.state.items[1].subtitle == "朝闻天下")
  env.fire(2)
  assert(env.asked(today) == 1)                                                    -- kept, not asked again at every beat
end)

test("late in the evening what comes next is in tomorrow's schedule", function()
  local late = NOW + 14 * HOUR + 20 * MINUTE                                       -- 23:50 in Beijing
  local today, tomorrow = CNTV .. "?c=cctv1&serviceId=tvcctv&d=20261102", CNTV .. "?c=cctv1&serviceId=tvcctv&d=20261103"
  local env = boot({now = late, config = {epg_url = CNTV}, pages = {
    [today] = cntvDay("cctv1", {{"Late Film", late - 50 * MINUTE, late + 9 * MINUTE}}),
    [tomorrow] = cntvDay("cctv1", {{"Midnight News", late + 10 * MINUTE, late + 70 * MINUTE}})}})
  env.event("tap", "play", 1)
  assert(env.state.tv.program.title == "Late Film" and env.state.tv["next"].title == "Midnight News")
end)

test("the service's days are Beijing days, whatever zone the device is in", function()
  local early = NOW + 15 * HOUR                                                    -- 00:30 on the 3rd in Beijing, still the 2nd in UTC
  local today = CNTV .. "?c=cctv1&serviceId=tvcctv&d=20261103"
  local env = boot({now = early, config = {epg_url = CNTV}, pages = {[today] = cntvDay("cctv1", {{"Small hours", early - 30 * MINUTE, early + 30 * MINUTE}})}})
  env.event("tap", "play", 1)
  assert(env.state.tv.program.title == "Small hours" and env.asked(today) == 1)
end)

test("other channels get no request to CCTV's schedule, an empty answer is no failure, an unreachable service is said so", function()
  local today = CNTV .. "?c=cctv1&serviceId=tvcctv&d=20261102"
  local env = boot({config = {epg_url = CNTV}})
  env.event("tap", "play", 3); env.event("tap", "play", 4)                         -- Kids TV, Weather
  env.fire(1)
  for _, url in ipairs(env.requests) do assert(not url:find("api.cntv.cn", 1, true), url) end
  assert(absent(env.state.tv.program))
  local quiet = boot({config = {epg_url = CNTV}, pages = {[today] = {body = json.encode({errcode = "1001", msg = "params error"})}}})
  quiet.event("tap", "play", 1); quiet.fire(1)
  assert(absent(quiet.state.tv.program) and #quiet.loads == 1)
  for _, note in ipairs({"guide_gz", "guide_big", "guide_failed"}) do assert(quiet.state.footer ~= strings["notice." .. note]) end
  local down = boot({config = {epg_url = CNTV}, pages = {[today] = {error = "timeout"}}})
  down.event("tap", "play", 1); down.fire(1)
  assert(down.state.footer == strings["notice.guide_failed"] and #down.loads == 1)
end)

test("a guide address with placeholders asks for one document per channel", function()
  local want = "https://guide.test/api?ch=CCTV-1%20%E7%BB%BC%E5%90%88%20HD&date=2026-11-02"
  local body = json.encode({epg_data = {{start = "09:00", ["end"] = "10:00", title = "Morning"}, {start = "10:00", ["end"] = "11:00", title = "Late"}}})
  local env = boot({config = {epg_url = "https://guide.test/api?ch={name}&date={date}", epg_zone = "Asia/Shanghai"}, pages = {[want] = {body = body}}})
  env.event("tap", "play", 1)
  assert(env.requests[#env.requests] == want, env.requests[#env.requests])
  local tv = env.state.tv
  assert(tv.program.title == "Morning" and tv["next"].title == "Late" and tv.program.start == NOW - 30 * MINUTE)
end)

test("a guide that cannot be had is said so in the footer, and the channel plays on", function()
  local cases = {
    {page = {body = "\31\139\8\0binary"}, note = "notice.guide_gz"},
    {page = {error = "HTTP response exceeds 1MB"}, note = "notice.guide_big"},
    {page = {status = 404, body = "no"}, note = "notice.guide_failed"},
  }
  for _, case in ipairs(cases) do
    local env = boot({config = {epg_url = "https://epg.test/x.xml"}, pages = {["https://epg.test/x.xml"] = case.page}})
    env.event("tap", "play", 1)
    env.fire(1)
    assert(env.state.footer == strings[case.note], env.state.footer)
    assert(#env.loads == 1 and absent(env.state.tv.program))
  end
end)

test("the saved list and the favorites come back at start, before any network", function()
  local first = boot()
  first.event("tap", "play", 1); first.event("tap", "tv", "favorite")
  first.event("stop")
  local second = new({store = first.store, offline = true})
  second.event("start")
  assert(#second.state.items == 4 and second.state.items[1].title == "1  CCTV-1 综合 HD" and second.state.items[1].trailing == "")
  assert(#second.requests == 0)
  second.fire(3)
  assert(second.state.footer == strings["notice.sources_failed"] and #second.state.items == 4)
  second.event("tap", "tab", 1)
  assert(#second.state.items == 1)
  second.event("tap", "play", 1)
  assert(#second.loads == 1 and second.loads[1].entries[1].url == "https://a.test/cctv1.m3u8" and second.state.tv.favorite == true)
end)

test("a list that cannot be read is asked for again, later each time, until it can be", function()
  local env = new({offline = true})
  env.event("start"); env.fire(3)
  assert(#env.state.items == 0 and env.state.footer == strings["notice.sources_none"])
  assert(env.timersWith(30) == 1)
  env.fire(30); assert(env.timersWith(60) == 1 and env.timersWith(30) == 0)
  env.fire(60); assert(env.timersWith(120) == 1)
  env.fire(120); env.fire(300); env.fire(600)
  assert(env.timersWith(1800) == 1)
  env.fire(1800); assert(env.timersWith(1800) == 1)                                -- and then every half hour
  env.pages["https://tv.test/a.m3u"], env.pages["https://cdn.test/b.m3u"] = {body = LIST_A}, {body = LIST_B}
  env.fire(1800)
  assert(#env.state.items == 4 and env.state.footer ~= strings["notice.sources_none"])
  assert(env.timersWith(1800) == 0 and env.timersWith(30) == 0)                    -- read: nothing more to ask
  env.config.source_2 = ""; env.event("config", env.config)                        -- and a new failure starts over from the short wait
  env.pages["https://tv.test/a.m3u"] = nil; env.config.source_1 = "https://tv.test/other.m3u"; env.event("config", env.config)
  assert(env.timersWith(30) == 1)
end)

test("when only one of the lists could be read the others are used, and the missing one is asked for again", function()
  local env = new({pages = {["https://cdn.test/b.m3u"] = {error = "timeout"}}})
  env.event("start"); env.fire(3)
  assert(#env.state.items == 3 and env.timersWith(30) == 1)                        -- list a alone: CCTV-1 has its first line only
  env.pages["https://cdn.test/b.m3u"] = {body = LIST_B}
  env.fire(30)
  assert(#env.state.items == 4 and env.state.items[1].trailing == "" and env.timersWith(30) == 0 and env.timersWith(60) == 0)
end)

test("changing the lists in the settings reloads them, and removing them empties the page", function()
  local env = boot()
  env.config.source_2 = ""
  env.event("config", env.config)
  assert(#env.state.items == 3 and env.state.items[1].trailing == "" and env.state.items[2].title == "2  News 24")
  env.config.source_1 = ""
  env.event("config", env.config)
  assert(#env.state.items == 0 and env.state.footer == strings["footer.empty"] and env.store.list == "")
  env.config.source_1 = "https://tv.test/a.m3u"
  env.event("config", env.config)
  assert(#env.state.items == 3)
end)

test("lines that left the lists lose their health records for good", function()
  local env = boot()
  env.event("tap", "play", 1)
  env.event("media.error", {category = "network"})
  env.fire(20)
  assert(next(json.decode(env.store.health)))
  env.config.source_1, env.config.source_2 = "", ""
  env.event("config", env.config)
  env.fire(20)
  assert(next(json.decode(env.store.health)) == nil)
end)

test("a JSON list is named by its address and merged like the others", function()
  local env = boot({config = {source_1 = "json:https://j.test/list", source_2 = ""}, pages = {["https://j.test/list"] = {body = json.encode({channels = {
    {name = "Alpha HD", urls = {"https://j.test/a1.m3u8", "https://j.test/a2.m3u8"}}, {name = "Beta", url = "https://j.test/b.m3u8"}}})}}})
  assert(#env.state.items == 2 and env.state.items[1].title == "1  Alpha HD" and env.state.items[1].trailing == "")
end)

test("leaving the live page stops the channel, and another player taking over ends it too", function()
  local env = boot()
  env.event("page.enter", "channels")
  env.event("tap", "play", 1)
  env.event("page.leave", "channels")
  assert(env.stops == 0 and env.state.tv.number == 1)
  env.event("page.leave", "live")
  assert(env.stops == 1 and env.state.tv.number == 0 and env.state.current == 0)
  env.event("tap", "play", 1)
  env.event("media.session", {reason = "replaced"})
  assert(env.stops == 1 and env.state.tv.number == 0)
  env.event("media.error", {category = "network"})
  assert(#env.loads == 2)
end)

test("changing the time between tests restarts the tester", function()
  local env = boot()
  assert(env.timersWith(10) == 1)
  env.config.test_gap = 30; env.event("config", env.config)
  assert(env.timersWith(10) == 0 and env.timersWith(30) == 1)
end)

test("a long list is bounded: the page shows 500 rows and the saved list fits the store", function()
  local lines = {"#EXTM3U"}
  for i = 1, 700 do
    lines[#lines + 1] = '#EXTINF:-1 tvg-id="id' .. i .. '" group-title="Group ' .. (i % 9) .. '",Channel ' .. i .. " HD"
    lines[#lines + 1] = "https://big.test/live/" .. i .. "/index.m3u8?token=0123456789abcdef"
  end
  local env = boot({config = {source_2 = ""}, pages = {["https://tv.test/a.m3u"] = {body = table.concat(lines, "\n")}}})
  assert(#env.state.items == 500, #env.state.items)
  assert(#json.decode(env.store.list).items == 600 and #env.store.list < 130 * 1024, #env.store.list)
  env.event("tap", "tab", 4)
  assert(#env.state.items == 9)
  env.event("tap", "tab", 3)
  assert(#env.state.items == 500)
  local restarted = new({store = env.store, offline = true})
  restarted.event("start")
  assert(#restarted.requests == 0 and #restarted.state.items == 500)
  assert(restarted.state.items[1].title == env.state.items[1].title)
end)

test("a big XMLTV guide is read in slices", function()
  local xmltv = use("guides.xmltv")
  local parts, filler = {"<tv>"}, string.rep("d", 120)
  for c = 1, 120 do parts[#parts + 1] = '<channel id="c' .. c .. '"><display-name>Channel ' .. c .. "</display-name></channel>" end
  for c = 1, 120 do
    for p = 0, 11 do
      local hour = 8 + p // 2
      parts[#parts + 1] = string.format('<programme start="202611020%d%02d00 +0800" stop="202611020%d%02d00 +0800" channel="c%d"><title lang="en">Programme %d of channel %d</title><desc>%s</desc></programme>',
        hour % 10, (p % 2) * 30, (hour + (p % 2)) % 10, ((p + 1) % 2) * 30, c, p, c, filler)
    end
  end
  parts[#parts + 1] = "</tv>"
  local text = table.concat(parts, "\n")
  parts = nil
  test_slice()                                    -- building the document is the test's work; what follows is the plugin's
  local pauses = 0
  local lists = xmltv.parse(text, function(id) return id end, NOW, function() pauses = pauses + 1; test_slice() end)
  local count = 0
  for _ in pairs(lists) do count = count + 1 end
  assert(count == 120 and pauses >= 30, pauses)
end)

test("every text the plugin shows exists in its strings and in the manifest", function()
  -- A key cut off by "..", like tr("notice." .. name), is one of the lists below.
  for key, after in src_main:gmatch('tr%("([%w_.]+)"()') do
    if not src_main:sub(after, after + 4):find("^%s*%.%.") then assert(strings[key], key) end
  end
  for _, name in ipairs({"sources_failed", "sources_none", "guide_big", "guide_gz", "guide_failed", "all_failed", "switched", "policy"}) do
    assert(strings["notice." .. name], name)
  end
  for _, name in ipairs({"empty_favorites", "empty_recent", "empty"}) do assert(strings["footer." .. name], name) end
  local function walk(value)
    if type(value) == "string" then
      if value:sub(1, 1) == "@" then assert(strings[value:sub(2)], value) end
    elseif type(value) == "table" then
      for _, inner in pairs(value) do walk(inner) end
    end
  end
  walk(manifest)
  for _, field in pairs(manifest.settings) do assert(field.default ~= nil) end
end)
