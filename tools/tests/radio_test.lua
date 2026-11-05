-- The production Lua allocator, JSON codec and sandbox budget drive these tests.
local json = _json
local manifest, strings = json.decode(manifest_json), json.decode(strings_json)
local m3u = assert(load(m3u_source, "m3u", "t"))()
require = function(name) assert(name == "m3u"); return m3u end
local playlist = assert(load(library_source, "playlist", "t"))()
local browser = assert(load(radio_browser_source, "radiobrowser", "t"))()
local categories = assert(load(radio_categories_source, "categories", "t"))()
local health = assert(load(radio_health_source, "health", "t"))()
require = function(name) return assert(({m3u=m3u,playlist=playlist,["sources.radiobrowser"]=browser,categories=categories,health=health})[name]) end
local function test(name, fn) test_begin(); fn(); test_end(name) end

test("M3U handles BOM, names, relative addresses and rejects other protocols", function()
  local rows = playlist.parse('\239\187\191#EXTM3U\r\n#EXTINF:-1 group-title="News, World",City, News\r\n../live.mp3\r\nfile:///sdcard/a.mp3\r\nrtsp://cam/live\r\njavascript:alert(1)\r\nhttps://radio.test/two.mp3', "https://radio.test/list/a.m3u")
  assert(#rows == 2 and rows[1].title == "City, News" and rows[1].url == "https://radio.test/list/../live.mp3")
  assert(rows[2].url == "https://radio.test/two.mp3")
  assert(not playlist.valid("http://radio.test/a b") and not playlist.valid("http://radio.test/a\r\nInjected:yes"))
  assert(not playlist.valid("file:///a") and not playlist.valid({}) and not playlist.valid("http://h/" .. string.rep("a", 2048)))
  assert(not playlist.valid("https://user:password@radio.test/stream") and not playlist.valid("https://radio.test/a#fragment"))
  assert(#playlist.parse("<html>error</html>", "https://radio.test/a.m3u") == 0)
  local directoryM3u=playlist.parse("#EXTM3U\n#RADIOBROWSERUUID:00000000-0000-4000-8000-000000000001\n#EXTINF:1,Directory\nhttps://radio.test/live",nil)
  assert(#directoryM3u==1 and directoryM3u[1].title=="Directory","UUID comments must not become stations")
  local items, _, reason = playlist.parse("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6,\na.ts", "https://radio.test/a.m3u8")
  assert(#items == 0 and reason == "hls")
end)

test("PLS sorts sparse numbered fields, deduplicates and sanitizes UTF-8", function()
  local rows = playlist.parse('\239\187\191[Playlist]\r\nTitle3=第三台\r\nFile3=/three.mp3\r\nFile1=https://radio.test/one\r\nTitle1=One\r\nFile2=https://radio.test/one\r\nFile4=file:///bad\r\nFile999999999=https://radio.test/huge\r\nLength1=-1\r\nNumberOfEntries=999999999\r\nVersion=2', "https://radio.test/list.pls")
  assert(#rows == 2 and rows[1].title == "One" and rows[2].title == "第三台" and rows[2].url == "https://radio.test/three.mp3")
  local bad = playlist.parse("[playlist]\nFile1=https://radio.test/a\nTitle1=\200\201" .. string.rep("汉", 100), nil)
  assert(#bad == 1 and #bad[1].title <= 120 and utf8.len(bad[1].title))
  assert(#playlist.parse("[playlist]\nFile1=not a URL\nFile0=https://radio.test/zero\nTitle2=No file", nil) == 0)
end)

test("large lists yield slices and keep at most the declared bound", function()
  local lines = {"#EXTM3U"}
  for i = 1, 600 do lines[#lines + 1] = "#EXTINF:-1,Station " .. i; lines[#lines + 1] = "https://radio.test/" .. i end
  local pauses = 0
  local rows, cut = playlist.parse(table.concat(lines, "\n"), nil, 200, function() pauses = pauses + 1; test_slice() end)
  assert(#rows == 200 and cut and pauses > 0)
end)

local function new(options)
  options = options or {}
  local env = {handlers = {}, state = {}, store = options.store or {}, loads = {}, focuses = {}, timers = {}, serial = 0,
    media = {owner = "", status = "stopped"}, requests = {}, probes = {}, clock=1700000000000, intercepted=false,
    storeRefusals=0, maxStoreBytes=0, stops=0, resolutions={}}
  local cfg = {}
  for key, field in pairs(manifest.settings) do cfg[key] = field.default end
  for key, value in pairs(options.config or {}) do cfg[key] = value end
  env.config = cfg
  tos = {config = cfg, json = json, on = function(name, fn) env.handlers[name] = fn end,
    i18n = {t = function(key) return strings[key] or key end},
    time = {now=function() return env.clock end},
    data = {get=function(alias) assert(alias=="onboarding");return options.setup end},
    back = {intercept=function(value) env.intercepted=value end},
    state = {get = function(path) return env.media[path:match("^media%.(.*)$")] end,
      set = function(patch) for key, value in pairs(type(patch) == "string" and json.decode(patch) or patch) do env.state[key] = value end end},
    store = {get = function(key) return env.store[key] end, set = function(key, value)
      local proposed={};for name,raw in pairs(env.store) do proposed[name]=raw end;proposed[key]=value
      local bytes=#json.encode(proposed)
      if bytes>256*1024 then env.storeRefusals=env.storeRefusals+1;error("JSON document exceeds 256KB") end
      env.maxStoreBytes=math.max(env.maxStoreBytes,bytes);env.store[key]=value
    end},
    timer = {sleep = test_slice,
      after = function(seconds, fn) env.serial = env.serial + 1; env.timers[env.serial] = {seconds = seconds, fn = fn}; return env.serial end,
      every = function(seconds, fn) env.serial = env.serial + 1; env.timers[env.serial] = {seconds = seconds, fn = fn, repeating=true}; return env.serial end,
      cancel = function(id) env.timers[id] = nil end},
    http = {get = function(url, opts) env.requests[#env.requests + 1] = {url = url, opts = opts}; return (options.http or function() return nil end)(url, opts) end},
    net = {resolve=function(host)
      env.resolutions[#env.resolutions+1]=host
      assert(host=="all.api.radio-browser.info")
      if options.resolve then return options.resolve(host) end
      return {{address="192.0.2.1",name="fixture.api.radio-browser.info"}}
    end},
    media = {probe=function(url,opts)
      env.probes[#env.probes+1]={url=url,opts=opts}
      return (options.probe or function() return {ok=true,kind="stream"} end)(url,opts)
    end,
    stop=function() env.stops=env.stops+1;env.media.status="stopped" end,
    load = function(entries, opts)
      env.loads[#env.loads + 1] = {entries = entries, options = opts}
      if options.load_fail then return nil, options.load_error or "bad" end
      env.media.owner, env.media.status = "radio", "playing"
      if options.media_await then return coroutine.yield("media") end
      return env.media
    end},
    page = {focus = function(page, opts) env.focuses[#env.focuses + 1] = {page = page, options = opts} end}}
  env.emit = function(name, ...) assert(env.handlers[name], name)(...) end
  env.fire = function(id) local timer = assert(env.timers[id]); if not timer.repeating then env.timers[id] = nil end; timer.fn() end
  assert(load(main_source, "radio", "t"))()
  env.emit("start")
  return env
end

test("empty start makes no request and a direct station plays only after a tap", function()
  local env = new()
  assert(#env.loads == 0 and #env.requests == 0 and env.state.footer == strings["notice.empty"])
  env.config.stream_url, env.config.station_name = "https://radio.test/stream", "A station"
  env.emit("config"); assert(#env.state.items == 1 and #env.loads == 0)
  for _, index in ipairs({0, -1, 1.5, "1", 2}) do env.emit("tap", "play", index) end
  assert(#env.loads == 0)
  env.emit("tap", "play", 1)
  assert(#env.loads == 1 and env.loads[1].entries[1].kind == "audio" and env.loads[1].entries[1].title == "A station")
  assert(env.focuses[1].page == "radio.now" and env.focuses[1].options.live and env.state.station == "A station")
  assert(env.handlers["page.leave"] == nil, "Back must keep radio playing")
end)

test("favorites persist and restore through public store without autoplay", function()
  local env = new({config = {stream_url = "https://radio.test/live", station_name = "Saved"}})
  env.emit("page.enter", "stations"); env.emit("tap", "play", 1); env.emit("tap", "favorite")
  assert(#json.decode(env.store.favorites) == 1 and env.state.favoriteLabel == strings["action.unfavorite"])
  local restored = new({store = env.store}); restored.emit("tap", "tab", 1)
  assert(#restored.loads == 0 and #restored.state.items == 1 and restored.state.items[1].title == "Saved")
  restored.emit("tap", "play", 1); restored.emit("tap", "favorite")
  assert(#restored.state.items == 0)
  local corrupt = new({store = {favorites = "not JSON"}}); corrupt.emit("tap", "tab", 1)
  assert(#corrupt.state.items == 0)
end)

test("M3U category filters All without hiding a saved favorite from another group", function()
  local body = [[#EXTM3U
#EXTINF:-1 group-title="甲组",甲台
https://radio.test/a
#EXTINF:-1 group-title="乙组",乙台
https://radio.test/b
]]
  local env = new({config={source="https://radio.test/groups.m3u"},
    http=function() return {status=200,body=body} end})
  env.emit("page.enter","stations")
  assert(#env.state.items==2)
  env.emit("tap","play",2);env.emit("tap","favorite")
  local saved = env.store.favorites
  assert(#json.decode(saved)==1 and json.decode(saved)[1].group=="乙组")
  env.emit("tap","tab",3);env.emit("tap","play",1)
  assert(env.state.tab==2 and #env.state.items==1 and env.state.items[1].title=="甲台")
  env.emit("tap","tab",1)
  assert(env.state.tab==1 and #env.state.items==1 and env.state.items[1].title=="乙台" and env.state.tabs[2]=="甲组")
  assert(env.state.footer~=strings["notice.favorites"] and env.state.items[1].trailing=="✓")
  env.emit("tap","tab",2)
  assert(#env.state.items==2 and env.state.items[1].title=="甲台" and env.state.items[2].title=="乙台" and env.state.tabs[2]==strings["tab.all"])
  assert(env.store.favorites==saved and #env.loads==1 and #env.requests==1,
    "changing tabs must preserve favorites, active category and the existing playback")
end)

test("source reads are bounded, failed refresh keeps favorites and bad media shows a short notice", function()
  local env = new({config = {source = "https://radio.test/a.pls"}, http = function()
    return {status = 200, body = "[playlist]\nFile1=https://radio.test/live\nTitle1=PLS station"}
  end})
  env.emit("page.enter", "stations")
  assert(#env.state.items == 1 and env.requests[1].opts == nil)
  env.emit("tap", "play", 1); env.emit("tap", "favorite")
  env.emit("media.error", {category = "policy"}); assert(env.state.notice == strings["notice.policy"] and next(env.timers) == nil)
  env.emit("media.error", {category = "http", status = 403}); assert(env.state.notice == strings["notice.play_failed"])
  env.emit("tap", "tab", 2); assert(#env.state.items == 1)
  local huge = new({config = {source = "https://radio.test/large"}, http = function() return {status = 200, body = string.rep("x",262145)} end})
  huge.emit("page.enter", "stations"); assert(#huge.state.items == 0 and huge.state.footer == strings["notice.list_failed"])
end)

test("terminal disconnect retries in background, but pause or another owner prevents resumption", function()
  local env = new({config = {stream_url = "https://radio.test/live", reconnect_s = 7}})
  env.emit("page.enter", "stations"); env.emit("tap", "play", 1)
  env.media.status = "error"; env.emit("media.error", {category = "network"})
  local id, timer = next(env.timers); assert(timer.seconds == 7); env.fire(id)
  assert(#env.loads == 2 and #env.focuses == 1)
  env.media.status = "ended"; env.emit("queue.end"); id = next(env.timers)
  env.media.status = "paused"; env.fire(id); assert(#env.loads == 2)
  env.media.status = "error"; env.emit("media.error", {category = "http", status = 503}); id = next(env.timers)
  env.media.owner = "player"; env.emit("media.session", {reason = "replaced"}); assert(env.timers[id] == nil)
  env.emit("stop"); assert(next(env.timers) == nil)
end)

test("async configuration changes coalesce and cannot publish stale lists", function()
  local env = new({config = {source = "https://radio.test/old"}, http = function() return coroutine.yield("http") end})
  local old = coroutine.create(function() env.emit("page.enter", "stations") end)
  assert(coroutine.resume(old)); env.config.source = "https://radio.test/new"
  for i = 1, 20 do env.emit("config") end
  assert(#env.requests == 1)
  assert(coroutine.resume(old, {status = 200, body = "#EXTM3U\n#EXTINF:-1,Old\nhttps://radio.test/old.mp3"}))
  assert(coroutine.status(old) == "suspended" and #env.requests == 2 and #env.state.items == 0)
  assert(coroutine.resume(old, {status = 200, body = "#EXTM3U\n#EXTINF:-1,New\nhttps://radio.test/new.mp3"}))
  assert(coroutine.status(old) == "dead" and env.state.items[1].title == "New")
end)

test("late media replies cannot replace a newer station or reclaim another owner", function()
  local env = new({config = {source = "https://radio.test/list"}, media_await = true, http = function()
    return {status = 200, body = "#EXTM3U\n#EXTINF:-1,One\nhttps://radio.test/one\n#EXTINF:-1,Two\nhttps://radio.test/two"}
  end})
  env.emit("page.enter", "stations")
  local first = coroutine.create(function() env.emit("tap", "play", 1) end)
  local second = coroutine.create(function() env.emit("tap", "play", 2) end)
  assert(coroutine.resume(first)); assert(coroutine.resume(second))
  assert(coroutine.resume(second, env.media)); assert(env.state.station == "Two" and #env.focuses == 1)
  assert(coroutine.resume(first, env.media)); assert(env.state.station == "Two" and #env.focuses == 1)
  local late = coroutine.create(function() env.emit("tap", "play", 1) end)
  assert(coroutine.resume(late)); env.emit("media.session", {reason = "replaced"})
  assert(coroutine.resume(late, env.media)); assert(#env.focuses == 1)
end)

local function station(id,url,tags)
  return {stationuuid=string.format("00000000-0000-4000-8000-%012x",id),name="Station "..id,
    url=url or "https://stream.example.org/"..id,url_resolved=url or "https://stream.example.org/"..id,
    tags=tags or "news",countrycode="GB",lastcheckok=1,ssl_error=0,clickcount=1000-id}
end
local function response(rows) return {status=200,body=json.encode(rows)} end
local function poolOptions(extra)
  local options={resolve=function(host)
    assert(host=="all.api.radio-browser.info")
    return {{address="192.0.2.1",name="one.api.radio-browser.info"}}
  end,random=function(n) assert(n>0);return 1 end,session={}}
  for key,value in pairs(extra or {}) do options[key]=value end
  return options
end
local function directoryOptions(extra)
  local options={config={directory=true,test=false},http=function() return response({station(1)}) end}
  for key,value in pairs(extra or {}) do
    if key=="config" then for name,setting in pairs(value) do options.config[name]=setting end
    else options[key]=value end
  end
  return options
end

test("directory defaults stay off, clicks stay off and explicit setup region uses a declared export", function()
  assert(manifest.api==10 and manifest.settings.directory.default==false and manifest.settings.click.default==false)
  assert(manifest.settings.source.default=="" and manifest.settings.stream_url.default=="")
  assert(manifest.uses.onboarding.export=="welcome.setup" and manifest.uses.onboarding.shape=="onboarding/1")
  local env=new(directoryOptions({setup={region="GB"}}))
  env.emit("page.enter","stations")
  assert(env.requests[1].url:find("countrycode=GB",1,true))
  assert(env.requests[1].opts.headers["User-Agent"]:find("ThinkOS",1,true))
  assert(env.requests[1].url:find("limit=100",1,true) and not env.requests[1].url:find("name=",1,true))
  env.emit("tap","play",1)
  assert(#env.requests==1 and next(env.timers)==nil and env.loads[1].entries[1].public_only==true)
  local global=new(directoryOptions({config={country_auto=false,country=""},setup={region="GB"}}))
  global.emit("page.enter","stations");assert(not global.requests[1].url:find("countrycode",1,true))
end)

test("JSON parser rejects malformed shapes, oversize, broken stations and private or ambiguous addresses", function()
  for _,body in ipairs({"{\"items\":[]}","null","[",string.rep("x",262145)}) do
    assert(browser.parse(body,json.decode)==nil)
  end
  local many={};for i=1,101 do many[i]=station(i) end
  assert(browser.parse(json.encode(many),json.decode)==nil)
  for _,url in ipairs({"http://radio.example.org/live","https://127.0.0.1/live","https://10.0.0.1/a",
    "https://172.31.1.1/a","https://192.168.1.1/a","https://169.254.2.1/a","https://100.64.1.1/a",
    "https://0.0.0.0/a","https://224.0.0.1/a","https://198.18.1.1/a","https://198.51.100.1/a",
    "https://2130706433/a","https://0177.0.0.1/a","https://[::1]/a","https://[::ffff:127.0.0.1]/a",
    "https://localhost/a","https://localhost./a","https://radio.local/a","https://radio.internal/a",
    "https://singlelabel/a","https://user:secret@radio.example.org/a","https://radio.example.org/a#x",
    "https://radio.example.org:70000/a","https://-bad.example.org/a","https://a..example.org/a"}) do
    assert(not browser.public(url),url)
    assert(#assert(browser.parse(json.encode({station(1,url)}),json.decode))==0,url)
  end
  assert(browser.public("https://8.8.8.8/live") and browser.public("https://stream.example.org:8443/live?q=1"))
  local broken,ssl,badId=station(1),station(2),station(3)
  broken.lastcheckok=0;ssl.ssl_error=1;badId.stationuuid="not-a-uuid"
  assert(#assert(browser.parse(json.encode({broken,ssl,badId}),json.decode))==0)
  local dirty=station(4);dirty.name="A\r\nB"..string.rep("汉",100);dirty.favicon="https://tracker.example.org/image"
  local rows=assert(browser.parse(json.encode({dirty,dirty}),json.decode))
  assert(#rows==1 and #rows[1].title<=120 and utf8.len(rows[1].title) and not rows[1].title:find("\n",1,true))
  assert(rows[1].favicon==nil and rows[1].uuid==dirty.stationuuid)
end)

test("category mapping covers every fixed category and keeps aliases in only one category", function()
  local seen={}
  assert(#categories.items==12)
  for _,item in ipairs(categories.items) do
    assert(#item.tags>=1 and #item.tags<=3 and strings["category."..item.id])
    for _,tags in ipairs({item.tags,item.aliases}) do for _,tag in ipairs(tags) do
      local matched=categories.match(" "..tag.." ")
      assert(matched[item.id] and not seen[tag]);seen[tag]=item.id
      local count=0;for _ in pairs(matched) do count=count+1 end;assert(count==1)
    end end
  end
  assert(categories.match("música pop").pop and categories.match("noticias").news)
  assert(categories.match("actualités").news and categories.match("électronique").dance)
  assert(next(categories.match("méxico,estación,moi merino"))==nil)
end)

test("category fetch uses at most three OR requests, escapes tags, deduplicates UUIDs and never requests favicons", function()
  local requests={}
  local rows,err,more,nextOffsets=browser.fetch(function(url,opts)
    requests[#requests+1]={url=url,opts=opts};return response({station(1),station(#requests+1)})
  end,json.decode,"https://all.api.radio-browser.info","GB",categories.get("news").tags,nil,test_slice,nil,poolOptions())
  assert(rows and not err and #rows==4 and #requests==3 and not more and nextOffsets[1]==100)
  assert(requests[3].url:find("actualit%C3%A9s",1,true))
  for _,request in ipairs(requests) do
    assert(request.url:find("tagExact=true",1,true) and request.url:find("hidebroken=true",1,true))
    assert(not request.url:find("tagList",1,true) and not request.url:find("favicon",1,true))
  end
  local n=0
  assert(browser.fetch(function() n=n+1;return response({}) end,json.decode,"https://all.api.radio-browser.info","",{"a","b","c","d"})==nil)
  assert(n==0)
end)

test("directory pagination is explicit, bounded at 200 and advances raw offsets despite duplicates", function()
  local env=new(directoryOptions({http=function(url)
    local offset=tonumber(url:match("offset=(%d+)"));local rows={}
    for i=1,100 do rows[i]=station(offset==0 and i or offset+i-1) end
    return response(rows)
  end}))
  env.emit("page.enter","stations")
  assert(#env.requests==1 and #env.state.items==101 and env.state.items[101].title==strings["action.more"])
  env.emit("tap","play",101)
  assert(#env.requests==2 and env.requests[2].url:find("offset=100",1,true) and #env.state.items==200)
  env.emit("tap","play",200)
  assert(#env.requests==3 and env.requests[3].url:find("offset=200",1,true) and #env.state.items==200)
  assert(env.state.items[200].title~=strings["action.more"] and #env.loads==0)
end)

test("a fully filtered page still shows its empty state and More can reveal a matching later station", function()
  local env=new(directoryOptions({config={country_auto=false,country="FR"},http=function(url)
    assert(url:find("countrycode=FR",1,true))
    local rows={}
    if url:find("offset=0&",1,true) then
      for i=1,100 do rows[i]=station(i);rows[i].countrycode="CN" end
    else
      assert(url:find("offset=100&",1,true))
      rows[1]=station(101);rows[1].countrycode="FR"
    end
    return response(rows)
  end}))
  env.emit("page.enter","stations")
  local cached=json.decode(env.store.directory_cache).entries[1]
  assert(#cached.items==0 and cached.more==true and cached.offsets[1]==100)
  assert(#env.state.items==1 and env.state.items[1].title==strings["action.more"])
  assert(env.state.footer==strings["notice.list_empty"] and env.state.notice=="" and #env.loads==0)
  env.emit("tap","tab",1)
  assert(#env.state.items==0 and env.state.footer==strings["notice.favorites"])
  env.emit("tap","tab",3)
  assert(#env.state.items==12 and env.state.footer==strings["action.refresh"])
  env.emit("tap","tab",2)
  assert(#env.state.items==1 and env.state.footer==strings["notice.list_empty"] and #env.requests==1)
  env.emit("tap","play",1)
  cached=json.decode(env.store.directory_cache).entries[1]
  assert(#env.requests==2 and #cached.items==1 and cached.items[1].country=="FR" and cached.more==false)
  assert(#env.state.items==1 and env.state.items[1].title=="Station 101")
  assert(env.state.footer==strings["action.refresh"] and env.state.notice=="" and #env.loads==0)
end)

test("category is lazy, localized and Back closes it before leaving the app", function()
  local env=new(directoryOptions())
  env.emit("page.enter","stations");assert(#env.requests==1)
  env.emit("tap","tab",3)
  assert(#env.requests==1 and #env.state.items==12 and env.state.items[1].title==strings["category.news"])
  env.emit("tap","play",1)
  assert(#env.requests==4 and env.intercepted and env.state.tabs[2]==strings["category.news"])
  env.emit("back","stations")
  assert(not env.intercepted and env.state.tab==3 and #env.focuses==0)
end)

test("failed refresh retains the last list and UUID favorites work without the directory", function()
  local env=new(directoryOptions())
  env.emit("page.enter","stations");env.emit("tap","play",1);env.emit("tap","favorite")
  local saved=json.decode(env.store.favorites)
  assert(saved[1].uuid==station(1).stationuuid and saved[1].public_only and #env.store.directory_cache<=65536)
  local offline=new(directoryOptions({store=env.store,http=function() return nil,"offline" end}))
  assert(#offline.state.items==1 and #offline.requests==0)
  offline.emit("tap","refresh")
  assert(#offline.state.items==1 and offline.state.notice==strings["notice.list_failed"])
  offline.emit("tap","tab",1);offline.emit("tap","play",1)
  assert(#offline.loads==1 and offline.loads[1].entries[1].public_only and #offline.requests==1)
  offline.config.country_auto=false;offline.config.country="FR";offline.emit("config")
  offline.emit("tap","tab",2)
  assert(#offline.state.items==0,"a different country's failed query must not show the previous country")
end)

test("cooldown grows x3 to one day, policy is unknown and a successful recheck restores a station", function()
  local clock,record=1700000000000,nil
  for i=1,8 do
    record=health.record(record,{ok=false,error="http"},clock,10)
    assert(record.u-clock==math.min(600000*3^(i-1),86400000))
  end
  assert(not health.usable(record,clock) and health.due(record,record.u,43200000)==1)
  local failures=record.f
  record=health.record(record,{ok=false,error="policy"},clock,10)
  assert(record.f==failures and record.x)
  record=health.record(record,{ok=true,kind="hls"},clock,10)
  assert(health.usable(record,clock) and record.k=="hls" and not record.f)
  local records={};for i=1,950 do records[tostring(i)]={t=i} end
  health.trim(records);local count=0;for _ in pairs(records) do count=count+1 end
  assert(count==900 and records["1"]==nil and records["950"])
end)

test("preplay probes hide dead directory stations and pass the same public-only policy to the live player", function()
  local env=new(directoryOptions({config={test=true},http=function() return response({station(1),station(2)}) end,
    probe=function(url,opts) assert(opts.public_only);return {ok=not url:match("/1$"),error="http",status=404} end}))
  env.emit("page.enter","stations");env.emit("tap","play",1)
  assert(#env.probes==2 and #env.loads==1 and env.state.station=="Station 2" and #env.state.items==1)
  assert(env.loads[1].entries[1].public_only)
  local ledger=json.decode(env.store.health);assert(ledger[health.hash(station(1).url_resolved)].f==1)
  env.clock=env.clock+600000
  for id,timer in pairs(env.timers) do if timer.repeating then env.fire(id);break end end
  assert(#env.probes==3,"a paused station is eligible again when its real cooldown expires")
end)

test("automatic failover tries no more than five different stations and never reports clicks", function()
  local rows={};for i=1,7 do rows[i]=station(i) end
  local env=new(directoryOptions({http=function() return response(rows) end}))
  env.emit("page.enter","stations");env.emit("tap","play",7)
  for i=1,5 do env.media.status="error";env.emit("media.error",{category="network"}) end
  assert(#env.loads==5 and env.loads[1].entries[1].title=="Station 7" and env.loads[2].entries[1].title=="Station 1")
  assert(env.state.notice==strings["notice.all_failed"] and env.media.status=="stopped" and #env.requests==1)
end)

test("favorites reconnect the selected station rather than advancing to another one", function()
  local env=new(directoryOptions({http=function() return response({station(1),station(2)}) end}))
  env.emit("page.enter","stations");env.emit("tap","play",1);env.emit("tap","favorite")
  env.emit("tap","tab",1);env.emit("tap","play",1)
  env.media.status="error";env.emit("media.error",{category="network"})
  local id,timer=next(env.timers);assert(timer.seconds==env.config.reconnect_s);env.fire(id)
  assert(#env.loads==3 and env.loads[3].entries[1].title=="Station 1")
end)

test("background probing is single-flight, skips sleep and the playing station, and host cascading is opt-in", function()
  local calls=0
  local env=new(directoryOptions({config={test=true,host_cooldown=true},http=function() return response({station(1),station(2),station(3,"https://other.example.org/3")}) end,
    probe=function() calls=calls+1;return {ok=false,error="dns"} end}))
  env.emit("page.enter","stations")
  local id=next(env.timers)
  env.emit("attention","ASLEEP");env.fire(id);assert(calls==0)
  env.emit("attention","AWAKE");env.media.status="buffering";env.fire(id);assert(calls==0)
  env.media.status="stopped";env.fire(id)
  assert(calls==1 and #env.state.items==1 and env.state.items[1].title=="Station 3")
  local ledger=json.decode(env.store.health)
  assert(ledger[health.hash(station(1).url_resolved)] and ledger[health.hash(station(2).url_resolved)])
  assert(not health.hostFailure({ok=false,error="http"}) and not health.hostFailure({ok=false,error="format"}))
  env.emit("stop");assert(next(env.timers)==nil)
end)

test("explicit click reporting is best effort, starts after playback and never runs for automatic failover", function()
  local env=new(directoryOptions({config={click=true},http=function(url)
    if url:find("/json/url/",1,true) then return nil,"offline" end
    return response({station(1),station(2)})
  end}))
  env.emit("page.enter","stations");env.emit("tap","play",1)
  assert(#env.loads==1 and #env.requests==1)
  local id=next(env.timers);env.fire(id)
  assert(#env.requests==2 and env.requests[2].url:find("/json/url/"..station(1).stationuuid,1,true))
  env.media.status="error";env.emit("media.error",{category="network"})
  assert(#env.loads==2 and #env.requests==2 and next(env.timers)==nil)
end)

test("untrusted escaped tags cannot exceed cache bytes or change station names into code", function()
  local rows={}
  for i=1,100 do rows[i]=station(i);rows[i].tags=string.rep('"',1024) end
  local env=new(directoryOptions({http=function() return response(rows) end}))
  env.emit("page.enter","stations")
  assert(#env.state.items==101 and #env.store.directory_cache<=65536)
  assert(#json.decode(env.store.directory_cache).entries[1].items<100)
  assert(env.state.items[1].title=="Station 1" and #env.loads==0)
end)

test("public-only policy refusal does not mark a favorite dead or advertise a cleartext opt-out", function()
  local item=station(1);item.uuid=item.stationuuid;item.title=item.name;item.directory=true
  local env=new(directoryOptions({config={test=true},store={favorites=json.encode({item})},
    probe=function() return {ok=false,error="policy"} end,load_fail=true,load_error="policy"}))
  env.emit("tap","tab",1);env.emit("tap","play",1)
  local record=json.decode(env.store.health)[health.hash(item.url_resolved)]
  assert(record.x and not record.f and #env.state.items==1)
  assert(env.loads[1].entries[1].public_only==true)
  assert(env.state.notice==strings["notice.directory_policy"])
end)

test("a playing station is excluded even when its health record is old", function()
  local env=new(directoryOptions({config={test=true},http=function() return response({station(1),station(2)}) end}))
  env.emit("page.enter","stations");env.emit("tap","play",1)
  env.clock=env.clock+13*3600000
  local id=next(env.timers);env.fire(id)
  assert(#env.probes==2 and env.probes[2].url==station(2).url_resolved)
  env.fire(id);assert(#env.probes==2,"the expired currently playing station must not be probed")
end)

test("HTTP and format failures never pause another station on the same host", function()
  local env=new(directoryOptions({config={test=true,host_cooldown=true},http=function() return response({station(1),station(2)}) end,
    probe=function() return {ok=false,error="http",status=404} end}))
  env.emit("page.enter","stations")
  local id=next(env.timers);env.fire(id)
  local ledger=json.decode(env.store.health)
  assert(ledger[health.hash(station(1).url_resolved)] and not ledger[health.hash(station(2).url_resolved)])
  assert(#env.state.items==1 and env.state.items[1].title=="Station 2")
end)

test("a dishonest category or country response cannot leak unrelated stations into the selected view", function()
  local env=new(directoryOptions({http=function()
    return response({station(1,nil,"news"),station(2,nil,"música pop"),station(3,nil,"méxico")})
  end}))
  env.emit("page.enter","stations");env.emit("tap","tab",3);env.emit("tap","play",2)
  assert(#env.state.items==1 and env.state.items[1].title=="Station 2")
  local rows=assert(browser.fetch(function() return response({station(1)}) end,json.decode,
    "https://all.api.radio-browser.info","FR",nil,nil,test_slice,nil,poolOptions()))
  assert(#rows==0)
end)

test("unknown results after an expired dead cooldown obey their own retry interval", function()
  local clock=1700000000000
  for _,errorKey in ipairs({"policy","unsupported"}) do
    local record=health.record(nil,{ok=false,error="http"},clock,10)
    local oldPause,failures=record.u,record.f
    local observed=oldPause+1
    record=health.record(record,{ok=false,error=errorKey},observed,10)
    assert(record.f==failures and record.u==oldPause and record.x)
    assert(health.due(record,observed,1)==nil)
    assert(health.due(record,observed+record.r-1,1)==nil)
    assert(health.due(record,observed+record.r,1)==3)
  end
  local long=health.record(nil,{ok=false,error="network"},clock,720)
  long=health.record(long,{ok=false,error="policy"},clock+1,720)
  assert(health.due(long,clock+1+long.r,1)==nil,"unknown cannot shorten a live dead cooldown")
  assert(health.due(long,long.u+1,1)==3)
end)

test("three tag top K is sorted after all bounded candidates are merged", function()
  local calls=0
  local rows=assert(browser.fetch(function()
    calls=calls+1;local page={}
    for i=1,100 do
      local row=station(calls*100+i)
      row.clickcount=calls==3 and 100000+i or i
      page[i]=row
    end
    return response(page)
  end,json.decode,"https://all.api.radio-browser.info","GB",{"news","noticias","actualités"},nil,test_slice,nil,poolOptions()))
  assert(calls==3 and #rows==200 and rows[1].clicks==100100 and rows[100].clicks==100001)
  local seen={}
  for _,row in ipairs(rows) do assert(not seen[row.uuid]);seen[row.uuid]=true end
  for i=1,100 do assert(seen[station(300+i).stationuuid],"the third tag's popular station was discarded before sorting") end
end)

local function favoriteItem(id)
  local row=station(id)
  return {uuid=row.stationuuid,url=row.url,title=row.name,directory=true,public_only=true}
end
local function retryTimer(env)
  for id,timer in pairs(env.timers) do if not timer.repeating and timer.seconds==env.config.reconnect_s then return id,timer end end
end
local function pendingFixture(probe)
  local env=new(directoryOptions({config={test=true},store={favorites=json.encode({favoriteItem(1),favoriteItem(2)})},
    http=function() return response({station(1),station(2)}) end,probe=probe}))
  env.emit("page.enter","stations");env.emit("tap","play",1)
  env.emit("tap","tab",1);env.emit("tap","play",2)
  return env
end

test("pending favorite B retries while actual A keeps playing without being relabeled", function()
  local bWorks=false
  local env=pendingFixture(function(url)
    return url==station(2).url and not bWorks and {ok=false,error="network"} or {ok=true,kind="stream"}
  end)
  assert(#env.loads==1 and env.media.status=="playing" and env.state.station=="Station 1" and env.state.current==1)
  assert(#env.probes==2 and env.probes[2].url==station(2).url)
  env.emit("tap","tab",2);assert(#env.state.items==1 and env.state.items[1].title=="Station 1")
  local id=assert(retryTimer(env));env.clock=env.clock+env.config.reconnect_s*1000;env.fire(id)
  assert(#env.probes==3 and env.probes[3].url==station(2).url and #env.loads==1)
  assert(env.state.station=="Station 1" and retryTimer(env) and env.stops==0)
  local paused=json.decode(env.store.health)[health.hash(station(2).url)]
  assert(paused.u>env.clock,"the explicit retry must not shorten the stored dead cooldown")
  bWorks=true;id=assert(retryTimer(env));env.clock=env.clock+env.config.reconnect_s*1000;env.fire(id)
  assert(#env.probes==4 and #env.loads==2 and env.loads[2].entries[1].title=="Station 2")
  assert(env.state.station=="Station 2" and #env.focuses==1 and not retryTimer(env),"a delayed retry must not take the page back")
end)

test("pending favorite cannot resume across another owner, a newer tap, stop or an old queued timer", function()
  local function deadB(url) return {ok=url~=station(2).url,error="network",kind="stream"} end
  local env=pendingFixture(deadB)
  local id=assert(retryTimer(env));env.media.owner="player";env.fire(id)
  assert(#env.probes==2 and #env.loads==1 and env.stops==0 and not retryTimer(env))
  env=new(directoryOptions({config={test=true},store={favorites=json.encode({favoriteItem(1),favoriteItem(2)})},
    http=function() return response({station(1),station(2)}) end,probe=deadB}))
  env.emit("page.enter","stations");env.emit("tap","play",1);env.emit("tap","tab",1);env.emit("tap","play",2)
  local _,old=retryTimer(env)
  env.emit("tap","play",1);local probes,loads=#env.probes,#env.loads
  old.fn();assert(#env.probes==probes and #env.loads==loads and env.state.station=="Station 1")
  env.media.status="error";env.emit("media.error",{category="network"})
  assert(retryTimer(env),"an obsolete B timer must not cancel the newer A request")
  local _,stale=retryTimer(env);env.emit("stop");stale.fn()
  assert(#env.probes==probes and #env.loads==loads and next(env.timers)==nil and env.stops==0)
end)

test("asynchronous public-only policy errors use the directory notice for favorites and automatic candidates", function()
  local env=new(directoryOptions({store={favorites=json.encode({favoriteItem(1)})}}))
  env.emit("tap","tab",1);env.emit("tap","play",1)
  env.media.status="error";env.emit("media.error",{category="policy"})
  assert(env.state.notice==strings["notice.directory_policy"] and #env.loads==1 and not retryTimer(env))
  local rows={};for i=1,6 do rows[i]=station(i) end
  env=new(directoryOptions({http=function() return response(rows) end}))
  env.emit("page.enter","stations");env.emit("tap","play",1)
  for _=1,5 do env.media.status="error";env.emit("media.error",{category="policy"}) end
  assert(#env.loads==5 and env.state.notice==strings["notice.directory_policy"])
  for _,record in pairs(json.decode(env.store.health)) do assert(record.x and not record.f) end
end)

local function escapedFavorites(count)
  local rows={}
  for i=1,count do
    rows[i]={url="https://stream.example.org/"..i.."/"..string.rep("a",450),title=string.rep('"',120),
      agent=string.rep('\\"',100),referer="https://origin.example.org/"..string.rep('"',50)}
  end
  return json.encode(rows),rows
end
local function nearlyFullHealth()
  local records={}
  for i=1,900 do records[string.format("%016x",i)]={t=1700000000000,u=1699999999999,f=1,x=true,r=3600000} end
  local raw=json.encode(records)
  local i=900
  while #raw>81920 do
    records[string.format("%016x",i)]=nil;i=i-1;raw=json.encode(records);test_slice()
  end
  return raw
end

test("three escaped JSON strings share the real outer 256KiB store budget and preserve favorites", function()
  local savedFavorites=escapedFavorites(50)
  local savedHealth=nearlyFullHealth()
  local store={favorites=savedFavorites,health=savedHealth,legacy="keep-unknown"}
  assert(#savedFavorites>56000 and #savedFavorites<=65536 and #savedHealth>70000)
  assert(#json.encode(store)<256*1024)
  local page={};for i=1,100 do page[i]=station(i);page[i].tags=string.rep('"',1024) end
  local env=new(directoryOptions({store=store,http=function() return response(page) end}))
  env.emit("page.enter","stations")
  assert(#json.encode(env.store)<=256*1024 and env.maxStoreBytes<=256*1024)
  assert(env.store.favorites==savedFavorites and env.store.legacy=="keep-unknown")
  assert(#env.store.directory_cache<65536,"cache must yield to escaped favorites and health in the outer document")
end)

test("store pressure cuts known health before losing any valid favorite or unknown legacy key", function()
  local favoritesRaw,original=escapedFavorites(49)
  local healthRaw=nearlyFullHealth()
  local store={favorites=favoritesRaw,health=healthRaw,legacy=""}
  store.legacy=string.rep("z",256*1024-#json.encode(store)-512)
  assert(#json.encode(store)<=256*1024)
  local unknown=store.legacy
  local env=new({store=store,config={stream_url="https://stream.example.org/"..string.rep("n",970),station_name=string.rep('"',120),test=false}})
  env.emit("page.enter","stations");env.emit("tap","play",1);env.emit("tap","favorite")
  local saved=json.decode(env.store.favorites)
  assert(#saved==50 and env.store.legacy==unknown and #env.store.health<#healthRaw)
  local seen={};for _,item in ipairs(saved) do seen[item.url]=true end
  for _,item in ipairs(original) do assert(seen[item.url],"a valid existing favorite was evicted to pay for cache/health") end
  assert(#json.encode(env.store)<=256*1024 and env.maxStoreBytes<=256*1024)
end)

test("unknown full-store pressure safely refuses a new favorite and restores its visible state", function()
  local original=json.encode({{url="https://saved.example.org/live",title="Saved"}})
  local store={favorites=original,legacy=""}
  store.legacy=string.rep("z",256*1024-#json.encode(store)-256)
  local unknown=store.legacy
  local env=new({store=store,config={stream_url="https://stream.example.org/"..string.rep("n",970),station_name="New",test=false}})
  env.emit("page.enter","stations");env.emit("tap","play",1);env.emit("tap","favorite")
  assert(env.store.favorites==original and env.store.legacy==unknown and env.storeRefusals>0)
  assert(env.state.notice==strings["notice.favorite_failed"] and env.state.favoriteLabel==strings["action.favorite"])
  env.emit("tap","tab",1);assert(#env.state.items==1 and env.state.items[1].title=="Saved")
  assert(#json.encode(env.store)<=256*1024 and env.maxStoreBytes<=256*1024)
end)

test("a pending favorite probe is not duplicated by the background timer or revived after owner replacement", function()
  local hold=false
  local env=new(directoryOptions({config={test=true},store={favorites=json.encode({favoriteItem(1),favoriteItem(2)})},
    http=function() return response({station(1),station(2)}) end,
    probe=function(url)
      if url==station(2).url then
        if hold then return coroutine.yield("pending-B") end
        return {ok=false,error="network"}
      end
      return {ok=true,kind="stream"}
    end}))
  env.emit("page.enter","stations");env.emit("tap","play",1);env.emit("tap","tab",1);env.emit("tap","play",2)
  hold=true
  local id=assert(retryTimer(env))
  local worker=coroutine.create(function() env.fire(id) end)
  assert(coroutine.resume(worker) and coroutine.status(worker)=="suspended")
  local probes=#env.probes
  for timerId,timer in pairs(env.timers) do if timer.repeating then env.fire(timerId);break end end
  assert(#env.probes==probes,"background must not start a second probe for pending B")
  env.media.owner="player"
  assert(coroutine.resume(worker,{ok=true,kind="stream"}))
  assert(coroutine.status(worker)=="dead" and #env.loads==1 and env.stops==0 and not retryTimer(env))
end)

test("official discovery rejects malicious PTR names and never uses numeric addresses or all as an HTTPS origin", function()
  for _,name in ipairs({"all.api.radio-browser.info","ALL.API.RADIO-BROWSER.INFO.","api.radio-browser.info",
    "one.api.radio-browser.info.evil.org","evilradio-browser.info","one.api.radio-browser.info:443",
    "user@one.api.radio-browser.info","https://one.api.radio-browser.info","one.api.radio-browser.info/path",
    "one.api.radio-browser.info?x=1","one.api.radio-browser.info#x","one.api.radio-browser.info\n",
    "127.0.0.1","[::1]",".one.api.radio-browser.info","one..api.radio-browser.info",
    "-one.api.radio-browser.info","one_.api.radio-browser.info",string.rep("x",64)..".api.radio-browser.info"}) do
    assert(not browser.poolHost(name),name)
  end
  local dns,requests=0,{}
  local rows=assert(browser.fetch(function(url)
    requests[#requests+1]=url;return response({station(1)})
  end,json.decode,"https://all.api.radio-browser.info/","",nil,nil,test_slice,nil,poolOptions({resolve=function()
    dns=dns+1
    return {{address="127.0.0.1"},{address="192.0.2.1",name="all.api.radio-browser.info"},
      {address="192.0.2.2",name="ONE.API.RADIO-BROWSER.INFO."},{address="2001:db8::1",name="one.api.radio-browser.info"},
      {name="evil.api.radio-browser.info.evil.org"},{name="user@two.api.radio-browser.info"}}
  end})))
  assert(#rows==1 and dns==1 and #requests==1 and requests[1]:match("^https://one%.api%.radio%-browser%.info/json/"))
  local calls=0
  local options=poolOptions({resolve=function() return {{address="192.0.2.1"},{name="all.api.radio-browser.info"}} end})
  assert(not browser.fetch(function() calls=calls+1 end,json.decode,"https://all.api.radio-browser.info","",nil,nil,nil,nil,options))
  assert(calls==0,"no name is not permission to use an IP, all, HTTP, or a hardcoded server")
end)

test("official pool randomizes its start, deduplicates hosts and rotates request or JSON failures", function()
  local names={"one","two","three"}
  local dns,requests,failPreferred=0,{},false
  local options=poolOptions({random=function(n) assert(n==3);return 2 end,resolve=function()
    dns=dns+1;local entries={}
    for _,name in ipairs(names) do entries[#entries+1]={name=name..".api.radio-browser.info"} end
    entries[#entries+1]={name="two.api.radio-browser.info"};return entries
  end})
  local function get(url)
    local host=url:match("^https://([^.]+)");requests[#requests+1]=host
    if host=="two" then return failPreferred and response({station(2)}) or {status=503} end
    if host=="three" then return {status=200,body="["} end
    return not failPreferred and response({station(1)}) or nil,"offline"
  end
  assert(browser.fetch(get,json.decode,"https://all.api.radio-browser.info","",nil,nil,test_slice,nil,options))
  assert(table.concat(requests,",")=="two,three,one" and dns==1)
  requests={}
  assert(browser.fetch(get,json.decode,"https://all.api.radio-browser.info","",nil,nil,test_slice,nil,options))
  assert(table.concat(requests,",")=="one" and dns==2,"last successful host must lead a fresh discovery")
  failPreferred=true;requests={}
  assert(browser.fetch(get,json.decode,"https://all.api.radio-browser.info","",nil,nil,test_slice,nil,options))
  assert(table.concat(requests,",")=="one,two" and dns==3)
  assert(options.session.preferred=="https://two.api.radio-browser.info")
end)

test("category failover spends at most three calls, discards partial tags and reuses the confirmed host next refresh", function()
  local dns,requests=0,{}
  local options=poolOptions({resolve=function()
    dns=dns+1;return {{name="one.api.radio-browser.info"},{name="two.api.radio-browser.info"},{name="three.api.radio-browser.info"}}
  end})
  local function get(url)
    requests[#requests+1]=url
    local host=url:match("^https://([^.]+)")
    local tag=url:match("&tag=([^&]+)")
    if host=="one" then return {status=503} end
    local id=tag=="news" and 2 or tag=="noticias" and 3 or 4
    return response({station(1),station(id)})
  end
  local rows,err,more,offsets=browser.fetch(get,json.decode,"https://all.api.radio-browser.info","GB",
    categories.get("news").tags,nil,test_slice,nil,options)
  assert(not rows and err and more==nil and offsets==nil and dns==1 and #requests==3)
  assert(requests[1]:match("^https://one%.") and requests[2]:match("^https://two%."))
  assert(requests[3]:match("^https://two%.") and options.session.preferred=="https://two.api.radio-browser.info")
  assert(not requests[3]:find("actualit%C3%A9s",1,true),"a partial third tag must not exceed the category budget")
  requests={}
  rows,err,more,offsets=browser.fetch(get,json.decode,"https://all.api.radio-browser.info","GB",
    categories.get("news").tags,nil,test_slice,nil,options)
  assert(rows and not err and #rows==4 and not more and dns==2 and #requests==3)
  assert(offsets[1]==100 and offsets[2]==100 and offsets[3]==100)
  for _,url in ipairs(requests) do assert(url:match("^https://two%.")) end
  assert(requests[3]:find("actualit%C3%A9s",1,true))
  local seen={};for _,row in ipairs(rows) do assert(not seen[row.uuid]);seen[row.uuid]=true end
end)

test("an ordinary All-list refresh retains the eight-call failover budget", function()
  local dns,calls=0,0
  local options=poolOptions({resolve=function()
    dns=dns+1;local entries={}
    for i=1,8 do entries[i]={name="node"..i..".api.radio-browser.info"} end
    return entries
  end})
  local rows,err=browser.fetch(function()
    calls=calls+1
    return {status=503}
  end,json.decode,"https://all.api.radio-browser.info","",nil,nil,test_slice,nil,options)
  assert(not rows and err and calls==8 and dns==1)
  assert(browser.REQUESTS==8 and browser.POOL==8 and browser.CATEGORY_REQUESTS==3)
end)

test("all discovered servers failing preserves complete directory cache and UUID favorites", function()
  local offline=false
  local env=new(directoryOptions({resolve=function()
    return {{name="one.api.radio-browser.info"},{name="two.api.radio-browser.info"}}
  end,http=function() if offline then return nil,"offline" end;return response({station(1)}) end}))
  env.emit("page.enter","stations");env.emit("tap","play",1);env.emit("tap","favorite")
  local cache,favorites=env.store.directory_cache,env.store.favorites
  local key=json.decode(cache).entries[1].key
  assert(key:find("https://all.api.radio-browser.info",1,true) and not key:find("one.api",1,true))
  offline=true;env.emit("tap","refresh")
  assert(#env.resolutions==2 and #env.requests==3)
  assert(env.store.directory_cache==cache and env.store.favorites==favorites and #env.state.items==1)
  assert(env.state.notice==strings["notice.list_failed"])
  env.emit("tap","tab",1);env.emit("tap","play",1)
  assert(#env.loads==2 and #env.requests==3 and #env.resolutions==2 and next(env.timers)==nil)
end)

test("discovery failure and late cancelled resolution cannot start an HTTP request", function()
  local env=new(directoryOptions({resolve=function() return nil,"timeout" end}))
  env.emit("page.enter","stations")
  assert(#env.requests==0 and #env.resolutions==1 and env.state.notice==strings["notice.list_failed"])
  local cancelled=new(directoryOptions({resolve=function() return coroutine.yield("resolve") end}))
  local worker=coroutine.create(function() cancelled.emit("page.enter","stations") end)
  local ok,reason=coroutine.resume(worker);assert(ok and reason=="resolve")
  cancelled.emit("stop")
  assert(coroutine.resume(worker,{{name="one.api.radio-browser.info"}}))
  assert(coroutine.status(worker)=="dead" and #cancelled.requests==0)
end)

test("directory off and click false never add discovery or reporting traffic", function()
  local env=new({config={test=false},store={favorites=json.encode({favoriteItem(1)})}})
  env.emit("page.enter","stations");env.emit("tap","tab",1);env.emit("tap","play",1)
  assert(#env.loads==1 and #env.resolutions==0 and #env.requests==0 and next(env.timers)==nil)
  local on=new(directoryOptions())
  on.emit("page.enter","stations");on.emit("tap","play",1)
  assert(#on.resolutions==1 and #on.requests==1 and next(on.timers)==nil)
end)

test("explicit mirrors keep their origin and never invoke official discovery", function()
  for _,origin in ipairs({"https://mirror.example.org:8443/","http://127.0.0.1:8894/","http://localhost:8894"}) do
    local env=new(directoryOptions({config={directory_url=origin,click=true},resolve=function() error("custom origin must not resolve a pool") end}))
    env.emit("page.enter","stations");env.emit("tap","play",1)
    assert(#env.requests==1 and #env.resolutions==0)
    env.fire(assert(next(env.timers)))
    assert(#env.requests==2 and #env.resolutions==0)
    local base=origin:gsub("/+$","")
    for _,request in ipairs(env.requests) do assert(request.url:sub(1,#base+1)==base.."/") end
    env.emit("stop")
  end
end)

test("click opt in discovers only legal HTTPS origins and turning it off cancels a late resolution", function()
  local uuid=station(1).stationuuid
  local dns,calls=0,{}
  browser.click(function(url)
    calls[#calls+1]=url;return #calls==1 and {status=503} or {status=200}
  end,"https://all.api.radio-browser.info",uuid,nil,poolOptions({resolve=function()
    dns=dns+1;return {{name="all.api.radio-browser.info"},{name="one.api.radio-browser.info"},{name="two.api.radio-browser.info"}}
  end}))
  assert(dns==1 and #calls==2 and calls[1]=="https://one.api.radio-browser.info/json/url/"..uuid)
  assert(calls[2]=="https://two.api.radio-browser.info/json/url/"..uuid)
  local hold=false
  local env=new(directoryOptions({config={click=true},resolve=function()
    if hold then return coroutine.yield("click-dns") end
    return {{name="one.api.radio-browser.info"}}
  end}))
  env.emit("page.enter","stations");env.emit("tap","play",1)
  hold=true
  local worker=coroutine.create(function() env.fire(assert(next(env.timers))) end)
  local ok,reason=coroutine.resume(worker);assert(ok and reason=="click-dns")
  env.config.click=false
  assert(coroutine.resume(worker,{{name="one.api.radio-browser.info"}}))
  assert(coroutine.status(worker)=="dead" and #env.requests==1 and #env.resolutions==2)
end)
