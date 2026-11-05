-- Player: the media library and the way into the OS media session. Playback, buffering, decoding, subtitles and
-- the controls belong to the OS (tos.media, the video / seekbar / controls components); this plugin only decides
-- what to play: local files, a stream address, the channels of a playlist, and what was played last.
local m3u = require("m3u")
local library = require("library")

local RECENT_LIMIT, SAVED_LIMIT, CHANNEL_LIMIT = 30, 30, 300
local LIBRARY_PAGE = "library"

local ORDER = {"music", "video", "recent", "streams"}
local variant = "music"
local lists = {music = {}, video = {}, recent = {}, streams = {}}
local recent, saved, channels = {}, {}, {}
local notices = {}
local queue = {}                 -- what was last handed to tos.media.load, by Lua index
local session, scanGeneration, listGeneration = 0, 0, 0
local scannedAt = nil
local saver, releaseTimer
local stopped = false
local previous = {}              -- last seen values of the settings that trigger work

local function empty() return tos.json.decode("[]") end
local function now() return tos.time.now() or 0 end

local function restore(key, fallback)
  local text = tos.store.get(key)
  if type(text) ~= "string" then return fallback end
  local ok, value = pcall(tos.json.decode, text)
  if ok and type(value) == "table" then return value end
  return fallback
end

local function persist(key, value) tos.store.set(key, tos.json.encode(value)) end

-- The host of an address, without any user:password in front of it: this ends up on screen and in the media state.
local function hostOf(url)
  local authority = url:match("^%a+://([^/?#]*)")
  if not authority then return url end
  return (authority:gsub("^.*@", ""))
end

-- Where an item comes from, in a word: the folder of a local file, the host of an address.
local function placeOf(url)
  if url:sub(1, 5) == "file:" then
    local dir = url:gsub("^file:/*", "/"):match("^(.*)/[^/]*$") or ""
    return (dir:match("([^/]+)$") or ""):gsub("%%(%x%x)", function(hex) return string.char(tonumber(hex, 16)) end)
  end
  return hostOf(url)
end

-- ---------------------------------------------------------------------------------------------------------------
-- what the library page shows

local function row(name, item)
  if name == "music" or name == "video" then
    local title, artist = library.describe(item)
    return {title = m3u.clip(m3u.clean(title), 120),
      subtitle = m3u.clip(m3u.clean(artist ~= "" and artist or (item.album or "")), 120), trailing = library.duration(item.duration)}
  elseif name == "recent" then
    local resume = library.resumeAt(item)
    local trailing = resume > 0 and (library.duration(resume) .. " / " .. library.duration(item.duration)) or library.duration(item.duration)
    return {icon = item.kind == "audio" and "music" or "play", title = m3u.clip(m3u.clean(item.title or ""), 120),
      subtitle = m3u.clip(m3u.clean(item.artist and item.artist ~= "" and item.artist or placeOf(item.url)), 120), trailing = trailing}
  end
  return {title = m3u.clip(m3u.clean(item.title or ""), 120), subtitle = m3u.clip(m3u.clean(item.group or hostOf(item.url)), 120), trailing = ""}
end

local function emptyNotice(name)
  if notices[name] then return tos.i18n.t("notice." .. notices[name], {dir = name == "music" and tos.config.music_dir or tos.config.video_dir}) end
  if #lists[name] > 0 then return "" end
  if name == "music" then return tos.i18n.t("empty.music", {dir = tos.config.music_dir}) end
  if name == "video" then return tos.i18n.t("empty.video", {dir = tos.config.video_dir}) end
  return tos.i18n.t("empty." .. name)
end

local function variantIndex()
  for i, name in ipairs(ORDER) do
    if name == variant then return i end
  end
  return 1
end

local function tabLabels()
  local labels = empty()
  for i, name in ipairs(ORDER) do labels[i] = tos.i18n.t("variant." .. name) end
  return labels
end

-- Which row of the list on screen is the track that is playing (0 when it is not in this list, or nothing plays).
local lastCurrent = 0
local function playingRow()
  local status = tos.state.get("media.status")
  if not (status == "playing" or status == "buffering" or status == "paused" or status == "reconnecting") then return 0 end
  local entry = queue[tos.state.get("media.index") or 0]
  if not entry then return 0 end
  for i, item in ipairs(lists[variant]) do
    if item.url == entry.url then return i end
  end
  return 0
end

local function render()
  local rows = empty()
  for i, item in ipairs(lists[variant]) do rows[i] = row(variant, item) end
  -- The footer is where the player's own words go: why a list is empty, why playback failed. The list itself is chosen by the tabs.
  lastCurrent = playingRow()
  tos.state.set(tos.json.encode({items = rows, playNotice = notices.play or "", heading = tos.i18n.t("variant." .. variant),
    footer = notices.play or emptyNotice(variant), count = tostring(#rows), tabs = tabLabels(), tab = variantIndex(), current = lastCurrent}))
end

-- The home card: what is playing, and how much the stack should want to show it (published for the OS; see 20-weather-handoff).
-- The home card shows whatever the device is playing, whoever started it, and events only reach the session owner:
-- so it follows the `media` state, on a slow beat and right after this plugin's own changes.
local shown = {}

local function renderNow()
  local status = tos.state.get("media.status")
  local active = status == "playing" or status == "buffering" or status == "paused" or status == "reconnecting"
  local title = active and tos.state.get("media.title") or ""
  local text = title ~= "" and m3u.clip(m3u.clean(title), 120) or tos.i18n.t("now.idle")
  local relevance = (status == "playing" or status == "buffering") and 0.9 or 0
  if text == shown.text and relevance == shown.relevance then return end
  shown.text, shown.relevance = text, relevance
  tos.state.set(tos.json.encode({nowTitle = text, relevance = relevance}))
end

-- Playback moves on without this plugin (the next track, a stop, another plugin's session): keep the marked row right.
local function refreshCurrent()
  local row = playingRow()
  if row ~= lastCurrent then
    lastCurrent = row
    tos.state.set(tos.json.encode({current = row}))
  end
end

local function refreshLists()
  lists.recent = recent
  local streams = {}
  for _, item in ipairs(saved) do streams[#streams + 1] = {title = item.title, url = item.url, group = tos.i18n.t("group.saved"), type = item.type} end
  for _, item in ipairs(channels) do
    if #streams < 512 then streams[#streams + 1] = item end
  end
  lists.streams = streams
end

-- ---------------------------------------------------------------------------------------------------------------
-- playback

local function entryOf(item, kind)
  local title, artist = item.title or "", item.artist or ""
  -- Files from the library carry their file name as the title when they have no tags: show "Song" by "Artist".
  if item.mime then title, artist = library.describe(item) end
  local entry = {url = item.url, title = title, artist = artist, album = item.album or "", kind = kind or item.kind or "video"}
  if item.mime and item.mime ~= "" then entry.mime = item.mime end
  if item.type then entry.type = item.type end
  if (item.agent or item.referer) and item.url:match("^https?://") then entry.headers = {["User-Agent"] = item.agent, Referer = item.referer} end
  return entry
end

local function remember(entry)
  if not entry then return end
  local kept = {url = entry.url, title = entry.title, artist = entry.artist, kind = entry.kind, type = entry.type,
    agent = entry.headers and entry.headers["User-Agent"], referer = entry.headers and entry.headers.Referer, position = 0, duration = 0, at = now()}
  for _, old in ipairs(recent) do
    if old.url == entry.url then kept.position, kept.duration = old.position or 0, old.duration or 0 end
  end
  recent = library.touch(recent, kept, RECENT_LIMIT)
  persist("recent", recent)
  refreshLists()
end

-- Where the title is now, kept for "continue where you left off". Live streams and finished titles restart.
local function savePosition(finished)
  local entry = queue[tos.state.get("media.index") or 0]
  if not entry then return end
  for _, old in ipairs(recent) do
    if old.url == entry.url then
      local live = tos.state.get("media.live")
      old.duration = live and 0 or (tos.state.get("media.duration") or 0)
      old.position = (live or finished) and 0 or (tos.state.get("media.position") or 0)
      persist("recent", recent)
      return
    end
  end
end

local function stopSaver()
  if saver then tos.timer.cancel(saver); saver = nil end
end

local function releasePages()
  tos.page.release("player.video")
  tos.page.release("player.audio")
end

local function releaseSoon(seconds)
  if releaseTimer then tos.timer.cancel(releaseTimer) end
  releaseTimer = tos.timer.after(seconds, function()
    releaseTimer = nil
    local status = tos.state.get("media.status")
    if status ~= "playing" and status ~= "buffering" and status ~= "paused" and status ~= "reconnecting" then releasePages() end
  end)
end

local function play(entries, index, kind, start)
  session = session + 1
  local mine = session
  if releaseTimer then tos.timer.cancel(releaseTimer); releaseTimer = nil end
  local previous = queue
  queue = entries
  local options = {index = index, autoplay = true}
  if start and start > 0 then options.start = start end
  local state, err = tos.media.load(entries, options)
  if not state and #entries > 1 then
    -- The engine refuses a queue as a whole if one item is malformed; the item that was asked for must still play.
    queue = {entries[index]}
    options.index = 1
    state, err = tos.media.load(queue, options)
    if state then entries, index = queue, 1 end
  end
  if stopped or mine ~= session then return end
  if not state then
    queue = previous
    notices.play = tos.i18n.t("notice.play_failed", {reason = tostring(err or "?")})
    render()
    return
  end
  notices.play = nil
  remember(entries[index])
  renderNow()
  tos.page.focus(kind == "video" and "player.video" or "player.audio", {live = true})
  stopSaver()
  saver = tos.timer.every(30, function() savePosition(false) end)
  render()
end

local function entriesOf(list, kind)
  local entries = {}
  for i, item in ipairs(list) do entries[i] = entryOf(item, kind or item.kind or library.kindOf(item.url)) end
  return entries
end

-- ---------------------------------------------------------------------------------------------------------------
-- library scan and playlists

local function scan()
  scanGeneration = scanGeneration + 1
  local mine = scanGeneration
  for _, kind in ipairs({"audio", "video"}) do
    local name = kind == "audio" and "music" or "video"
    local dir = kind == "audio" and tos.config.music_dir or tos.config.video_dir
    local result, err = tos.library.list({kind = kind, dir = dir ~= "" and dir or nil, recursive = true})
    if stopped or mine ~= scanGeneration then return end
    if result then
      lists[name] = result.items or {}
      notices[name] = nil
      if result.truncated then tos.log.warn("player: " .. name .. " library truncated at " .. #lists[name] .. " items") end
    else
      notices[name] = "unreadable"
      tos.log.warn("player: cannot read " .. name .. " folder: " .. tostring(err))
    end
  end
  scannedAt = now()
  render()
end

local function pause() tos.timer.sleep(0.001) end

-- Reads a playlist address. Returns the text, or nil when it cannot be had.
local function download(url)
  local response, err = tos.http.get(url, {headers = {Accept = "*/*"}})
  if not response or response.status ~= 200 or type(response.body) ~= "string" then
    tos.log.warn("player: " .. hostOf(url) .. " failed: " .. tostring(err or (response and response.status)))
    return nil
  end
  return response.body:sub(1, 400 * 1024)
end

local function adopt(url, body)
  local items
  if m3u.isHls(body) then
    -- The address is a stream, not a list of channels: offer it as the one channel it is.
    items = {{title = hostOf(url), url = url, type = "hls"}}
  else
    items = m3u.parse(body, url, CHANNEL_LIMIT, pause)
  end
  return items
end

local function fetchPlaylist(url)
  listGeneration = listGeneration + 1
  local mine = listGeneration
  if url == "" then
    channels = {}
    notices.streams = nil
    persist("channels", {url = "", items = channels})
    refreshLists(); render()
    return
  end
  local body = download(url)
  if stopped or mine ~= listGeneration then return end
  local items = body and adopt(url, body) or {}
  if stopped or mine ~= listGeneration then return end
  channels = items
  notices.streams = #items == 0 and "playlist_failed" or nil
  if #items > 0 then persist("channels", {url = url, items = channels, at = now()}) end
  refreshLists(); render()
end

local function playAddress(url)
  -- A .m3u / .m3u8 address may be a stream (HLS) or a list of channels; only the text can tell.
  local path = url:gsub("[?#].*$", ""):lower()
  if url:match("^https?://") and (path:match("%.m3u8?$")) then
    local body = download(url)
    if body and not m3u.isHls(body) then
      listGeneration = listGeneration + 1
      channels = m3u.parse(body, url, CHANNEL_LIMIT, pause)
      persist("channels", {url = url, items = channels, at = now()})
      variant = "streams"
      notices.play = nil
      notices.streams = #channels == 0 and "playlist_failed" or nil
      refreshLists(); render()
      return
    end
  end
  local kind = library.kindOf(url)
  local item = {url = url, title = hostOf(url), kind = kind}
  saved = library.touch(saved, item, SAVED_LIMIT)
  persist("saved", saved)
  refreshLists()
  play({entryOf(item, kind)}, 1, kind)
end

-- ---------------------------------------------------------------------------------------------------------------
-- events

tos.on("start", function()
  recent = restore("recent", {})
  saved = restore("saved", {})
  local cached = restore("channels", {})
  if cached.url == tos.config.playlist_url and type(cached.items) == "table" then channels = cached.items end
  previous = {music_dir = tos.config.music_dir, video_dir = tos.config.video_dir, playlist_url = tos.config.playlist_url,
    stream_url = tos.config.stream_url}
  local last = tos.store.get("variant")
  if type(last) == "string" and lists[last] then variant = last end
  refreshLists()
  render()
  renderNow()
  tos.timer.every(2, function() renderNow(); refreshCurrent() end)
  -- Nothing heavy while the OS is still bringing the clock up: the scan and the playlist wait a moment.
  tos.timer.after(1, scan)
  if tos.config.playlist_url ~= "" then tos.timer.after(3, function() fetchPlaylist(tos.config.playlist_url) end) end
end)

tos.on("config", function(c)
  local changed = {}
  for key, value in pairs({music_dir = c.music_dir, video_dir = c.video_dir, playlist_url = c.playlist_url, stream_url = c.stream_url}) do
    changed[key] = previous[key] ~= value
    previous[key] = value
  end
  if changed.music_dir or changed.video_dir then scan() end
  if changed.playlist_url then fetchPlaylist(c.playlist_url) end
  if changed.stream_url and c.stream_url ~= "" then playAddress(c.stream_url) end
end)

tos.on("page.enter", function(page)
  if page ~= LIBRARY_PAGE then return end
  render()
  -- New files may have arrived since the last look.
  if not scannedAt or now() - scannedAt > 15000 then scan() end
end)

tos.on("page.leave", function(page)
  -- A film nobody is looking at keeps no decoder busy.
  if page == "video" and tos.state.get("media.kind") == "video" then
    local status = tos.state.get("media.status")
    if status == "playing" or status == "buffering" then tos.media.pause() end
    savePosition(false)
  end
end)

tos.on("tap", function(name, index)
  if name == "tab" then
    local target = type(index) == "number" and ORDER[index]
    if target and target ~= variant then
      variant = target
      tos.store.set("variant", variant)
      render()
    end
    return
  end
  if name == "cycle" then
    variant = ORDER[variantIndex() % #ORDER + 1]
    tos.store.set("variant", variant)
    render()
    return
  end
  if name ~= "play" or type(index) ~= "number" then return end
  local list = lists[variant]
  local item = list[index]
  if not item then return end
  if variant == "music" then play(entriesOf(list, "audio"), index, "audio")
  elseif variant == "video" then play(entriesOf(list, "video"), index, "video")
  elseif variant == "recent" then
    local start = tos.config.resume and library.resumeAt(item) or 0
    play({entryOf(item)}, 1, item.kind, start)
  else
    -- Channels are played one at a time: the player prepares every queued HLS / DASH item at once.
    local kind = item.kind or library.kindOf(item.url)
    play({entryOf(item, kind)}, 1, kind)
  end
end)

tos.on("media.track", function(info)
  notices.play = nil
  remember(queue[info.index])
  render()
  renderNow()
end)

tos.on("queue.end", function()
  savePosition(true)
  stopSaver()
  releaseSoon(10)
  renderNow()
end)

tos.on("media.error", function(info)
  local codec = tostring(info.codec or "")
  -- Name the codec when the engine knows it: "cannot decode av01" says more than "format not supported".
  local key = ((info.category == "format" or info.category == "decoder") and codec ~= "") and "error.codec" or "error." .. tostring(info.category)
  local message = tos.i18n.t(key, {status = tostring(info.status or ""), codec = codec:gsub("^.*/", ""):gsub("^x%-", ""):upper()})
  notices.play = message
  render()
  releaseSoon(30)
end)

tos.on("media.session", function(info)
  if info.reason == "stopped" or info.reason == "replaced" then
    savePosition(false)
    stopSaver()
    renderNow()
  end
end)

tos.on("command", function(name)
  if name == "toggle" then tos.media.toggle()
  elseif name == "next" then tos.media.next()
  elseif name == "prev" then tos.media.prev() end
end)

tos.on("stop", function()
  stopped = true
  savePosition(false)
  stopSaver()
end)
