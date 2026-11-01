-- Every tos.media function, driven from Lua against the real engine on the device. The files come from the test.
local SERVER = "http://127.0.0.1:8099"
local passed, failed = 0, 0
local function log(s) tos.log.info("MEDIA-PROBE " .. s) end
local function check(name, ok, detail)
  if ok then passed = passed + 1; log("PASS " .. name)
  else failed = failed + 1; log("FAIL " .. name .. " " .. tostring(detail)) end
end
local function get(key) return tos.state.get("media." .. key) end
local function until_(test, seconds)
  for _ = 1, math.floor(seconds * 5) do
    if test() then return true end
    tos.timer.sleep(0.2)
  end
  return test()
end

local events = {track = 0, ended = 0, errors = {}, warnings = 0, sessions = {}}
tos.on("media.track", function() events.track = events.track + 1 end)
tos.on("queue.end", function() events.ended = events.ended + 1 end)
tos.on("media.error", function(info) events.errors[#events.errors + 1] = info end)
tos.on("media.warning", function() events.warnings = events.warnings + 1 end)
tos.on("media.session", function(info) events.sessions[#events.sessions + 1] = info.reason end)

local function queue()
  return {
    {url = "/sdcard/Music/probe/a.mp3", title = "Alpha", artist = "Artist A", kind = "audio"},
    {url = "/sdcard/Music/probe/b.flac", title = "Beta", kind = "audio", mime = "audio/flac"},
    {url = SERVER .. "/files/audio-mp3-cbr192.mp3", title = "Gamma", kind = "audio",
      headers = {["User-Agent"] = "MediaProbe/1"}},
  }
end

local function run()
  -- load and read back
  local state, err = tos.media.load(queue(), {index = 2, autoplay = true})
  check("load returns the session state", state and state.count == 3 and state.index == 2 and state.owner == "media-probe", err or (state and state.index))
  check("playback starts", until_(function() return get("status") == "playing" end, 8), get("status"))
  check("the media state is readable from Lua", get("title") == "Beta" and get("index") == 2 and get("count") == 3 and get("kind") == "audio", get("title"))
  check("the codec and format are reported", get("audioCodec") == "flac" and get("sampleRate") > 0, get("audioCodec"))

  -- transport
  state = tos.media.pause()
  check("pause", state and until_(function() return get("status") == "paused" end, 3), get("status"))
  state = tos.media.play()
  check("play", until_(function() return get("status") == "playing" end, 3), get("status"))
  tos.media.toggle()
  check("toggle pauses", until_(function() return get("status") == "paused" end, 3), get("status"))
  tos.media.toggle()
  check("toggle resumes", until_(function() return get("status") == "playing" end, 3), get("status"))

  -- seek and volume, with bad arguments answered by errors rather than thrown
  tos.media.seek(5000)
  check("seek moves the playhead", until_(function() return get("position") >= 5000 end, 4), get("position"))
  local bad, why = tos.media.seek(-1)
  check("seek(-1) is refused", bad == nil and why ~= nil, why)
  bad, why = tos.media.seek("soon")
  check("seek with text is refused", bad == nil and why ~= nil, why)
  state = tos.media.volume(0.5)
  check("volume(0.5)", state and math.abs(get("volume") - 0.5) < 0.01, get("volume"))
  bad, why = tos.media.volume(2)
  check("volume(2) is refused", bad == nil and why ~= nil, why)

  -- the queue
  tos.media.next()
  check("next goes to item 3", until_(function() return get("index") == 3 end, 4), get("index"))
  check("a remote item with its own User-Agent plays", until_(function() return get("status") == "playing" and get("source") == "progressive" end, 8), get("status"))
  tos.media.prev()
  check("prev goes back to item 2", until_(function() return get("index") == 2 end, 4), get("index"))
  tos.media.jump(1)
  check("jump(1)", until_(function() return get("index") == 1 and get("title") == "Alpha" end, 4), get("index"))
  bad, why = tos.media.jump(9)
  check("jump(9) is refused", bad == nil and why ~= nil, why)
  bad, why = tos.media.jump(0)
  check("jump(0) is refused", bad == nil and why ~= nil, why)
  tos.media.shuffle(true)
  check("shuffle(true)", get("shuffle") == true, get("shuffle"))
  tos.media.shuffle(false)
  bad, why = tos.media.shuffle("yes")
  check("shuffle('yes') is refused", bad == nil and why ~= nil, why)
  tos.media["repeat"]("one")
  check("repeat('one')", get("repeat") == "one", get("repeat"))
  tos.media.repeatMode("all")
  check("repeatMode('all') is the same function", get("repeat") == "all", get("repeat"))
  bad, why = tos.media["repeat"]("forever")
  check("repeat('forever') is refused", bad == nil and why ~= nil, why)

  -- tracks and live on a stream that has neither
  state, why = tos.media.audioTrack(1)
  check("audioTrack(1) is accepted", state ~= nil, why)
  state, why = tos.media.textTrack(0)
  check("textTrack(0) is accepted", state ~= nil, why)
  state, why = tos.media.live()
  check("live() on a recording is harmless", state ~= nil, why)

  -- a rejected load leaves the session alone; the engine's own checks run before anything changes
  bad, why = tos.media.load({{url = "ftp://x.test/a.mp3"}}, {})
  check("load of an ftp address is refused", bad == nil and why ~= nil and get("count") == 3, why)
  bad, why = tos.media.load({{url = SERVER .. "/a.mp3", headers = {Host = "evil.test"}}}, {})
  check("load with a forbidden header is refused", bad == nil and why ~= nil and get("count") == 3, why)
  bad, why = tos.media.load({{url = "rtsp://u:p@192.168.1.9/x", kind = "video", headers = {Referer = "x"}}}, {})
  check("load of rtsp with headers is refused", bad == nil and why ~= nil, why)
  bad, why = tos.media.load({}, {})
  check("load of an empty queue is refused", bad == nil and why ~= nil, why)
  bad, why = tos.media.load(queue(), {index = 7})
  check("load with index past the end is refused", bad == nil and why ~= nil, why)
  bad, why = tos.media.load(queue(), {colour = "red"})
  check("load with an unknown option is refused", bad == nil and why ~= nil, why)
  check("the session survived all of that", get("count") == 3 and get("status") ~= "stopped", get("status"))

  -- reserved state
  local ok, problem = pcall(tos.state.set, {media = {status = "fake"}})
  -- The runtime does not explain a rejected host call to the plugin; that it is rejected is the point.
  check("the plugin cannot write the reserved media key", not ok, problem)

  -- events: the end of a queue, a missing file, an unsupported format
  local ended = events.ended
  tos.media.load({{url = "/sdcard/Music/probe/a.mp3", title = "Alpha", kind = "audio"}}, {start = 0})
  until_(function() return get("status") == "playing" end, 6)
  tos.media.seek(get("duration") - 1500)
  check("queue.end arrives once the last item finishes", until_(function() return events.ended > ended end, 8), events.ended)
  local before = #events.errors
  tos.media.load({{url = "/sdcard/Music/probe/missing.mp3", kind = "audio"}}, {})
  check("a missing file reports media.error as notfound", until_(function() return #events.errors > before end, 8) and events.errors[#events.errors].category == "notfound",
    events.errors[#events.errors] and events.errors[#events.errors].category)
  check("the error shows in the media state", get("status") == "error" and get("error").category == "notfound", get("status"))
  before = #events.errors
  tos.media.load({{url = SERVER .. "/files/lyrics-sample.lrc", kind = "audio"}}, {})
  check("a text file reports media.error as format", until_(function() return #events.errors > before end, 10) and events.errors[#events.errors].category == "format",
    events.errors[#events.errors] and events.errors[#events.errors].category)

  -- start offset, then stop and ownership
  tos.media.load({{url = SERVER .. "/files/audio-mp3-cbr192.mp3", kind = "audio"}}, {start = 6000})
  check("start= begins part-way in", until_(function() return get("status") == "playing" and get("position") >= 6000 end, 8), get("position"))
  local sessions = #events.sessions
  tos.media.stop()
  check("stop empties the session", until_(function() return get("status") == "stopped" and get("count") == 0 end, 3), get("status"))
  check("stop reports media.session to its owner", #events.sessions > sessions and events.sessions[#events.sessions] == "stopped", events.sessions[#events.sessions])

  -- library
  local audio, lerr = tos.library.list({kind = "audio", dir = "/sdcard/Music/probe"})
  check("library.list sees the pushed audio", audio and #audio.items >= 2, lerr)
  local nothing, nerr = tos.library.list({kind = "audio", dir = "/data/data"})
  check("library.list refuses a folder outside the media roots", nothing == nil and nerr ~= nil, nerr)
  log("DONE passed=" .. passed .. " failed=" .. failed .. " tracks=" .. events.track)
end

tos.on("start", function()
  tos.timer.after(1.5, function() local ok, problem = pcall(run); if not ok then failed = failed + 1; log("FAIL script error " .. tostring(problem)); log("DONE passed=" .. passed .. " failed=" .. failed) end end)
end)
