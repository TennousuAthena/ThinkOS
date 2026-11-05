-- IPTV: live television from channel lists. The OS does the playing (tos.media, the tv component) and the testing of an address
-- (tos.media.probe); this plugin decides what to play: it reads the lists (sources.lua), puts the same channel from different
-- lists into one (channels.lua), keeps track of which lines work and pauses the ones that do not (health.lua), looks up what is
-- on (guide.lua, guides.lua) and answers the buttons of the tv component.
local sources = require("sources")
local channelsLib = require("channels")
local health = require("health")
local guide = require("guide")
local guides = require("guides")

local SOURCE_SLOTS = 4
local BODY_LIMIT = 900 * 1024            -- of a list's text, which tos.http caps at 1 MiB anyway
local SOURCE_LIMIT = 1500                -- entries read from one list
local MAX_CHANNELS, MAX_LINES = 600, 6
local ROW_LIMIT = 500                    -- rows of the channel page: the plugin's state holds 256 KiB
local RECENT_LIMIT = 30
local START_TIMEOUT = 20 * 1000          -- a line that has not started playing by then is given up
local PLAYED_AFTER = 5 * 1000            -- a line that has played this long is working
local NOTICE_MILLIS = 6 * 1000
local OFFLINE_GRACE = 15 * 60 * 1000     -- no answer from anywhere for this long: probably the network, not the lines
local POLICY_RETRY = 60 * 60 * 1000      -- a line the network rules refused is looked at again after this: the rule may have been lifted
local LIST_BYTES = 130 * 1024            -- of the store (256 KiB for everything) the saved channel list may take
local HEALTH_RECORDS = 900
local GUIDE_AHEAD = 2 * 3600 * 1000
local GUIDE_FRESH = 6 * 3600 * 1000
local GUIDE_RETRY = 10 * 60 * 1000
local LIST_REFRESH = 6 * 3600
local RETRY_STEPS = {30, 60, 120, 300, 600, 1800}   -- seconds until a list that could not be read is asked for again, later each time
local VIEWS = {"fav", "recent", "all", "groups"}

local list = {}                          -- the channels: {key, name, group, id, lines = {{url, agent, referer, src, h}}}
local indexOfKey = {}
local byGuideId, byNameKey = {}, {}
local meta = {}                          -- what the lists' headers said ({epg})
local prefs = {favorites = {}, recent = {}, view = "all"}
local records = {}                       -- health.lua records by hash of the line's address
local groupFilter = nil
-- A group that was opened is a level of its own: while one is, Back closes it (back to the list of groups) before it leaves the app (ADR-040).
local function holdBack() tos.back.intercept(groupFilter ~= nil) end
local shown = {}                         -- what the rows on the channel page are: a list index, or a group name
local cur = nil                          -- the channel on screen: {index, key, set, tried, line, since, played, ...}
local notices = {}                       -- sources, guide
local guideData, guideAt, guideBusy, guideNote = {}, {}, {}, nil
local listGeneration, renderGeneration, session = 0, 0, 0
local sourcesGeneration = 0
local retryTimer, retryStep = nil, 0
local previous = {}                      -- last seen values of the settings that trigger work
local attentionLevel = "AWAKE"
local testing, offlineUntil, reach = false, 0, 0
local stopped = false
local saveTimer, renderTimer, testTimer

local function now() return tos.time.now() or 0 end
local function tr(key, params) return tos.i18n.t(key, params) end
local function empty() return tos.json.decode("[]") end
local function pause() tos.timer.sleep(0.001) end
local function trim(s) return ((tostring(s or ""):gsub("^%s+", ""):gsub("%s+$", ""))) end

local function restore(key, fallback)
  local text = tos.store.get(key)
  if type(text) ~= "string" then return fallback end
  local ok, value = pcall(tos.json.decode, text)
  if ok and type(value) == "table" then return value end
  return fallback
end

local function persist(key, value) tos.store.set(key, tos.json.encode(value)) end

-- A short name for an address, so that records stay small (the store holds 256 KiB for everything).
local function hash(url)
  local h = 2166136261
  for i = 1, #url do h = ((h ~ url:byte(i)) * 16777619) & 0xffffffff end
  return string.format("%08x", h)
end

-- ---------------------------------------------------------------------------------------------------------------
-- lines: which work

local function recordOf(line) return records[line.h] end

local function usableLines(channel)
  local t, count = now(), 0
  for _, line in ipairs(channel.lines) do
    if health.usable(recordOf(line), t) then count = count + 1 end
  end
  return count
end

local function trimHealth()
  local healthy, total = {}, 0
  for h, record in pairs(records) do
    total = total + 1
    if (record.u or 0) == 0 then healthy[#healthy + 1] = h end
  end
  if total <= HEALTH_RECORDS then return end
  -- Forget the oldest results of lines that work; a line without a record is simply tested again.
  table.sort(healthy, function(a, b) return (records[a].t or 0) < (records[b].t or 0) end)
  for i = 1, math.min(#healthy, total - HEALTH_RECORDS) do records[healthy[i]] = nil end
end

local function saveHealth()
  trimHealth()
  persist("health", records)
end

local function scheduleSave()
  if saveTimer then return end
  saveTimer = tos.timer.after(20, function() saveTimer = nil; saveHealth() end)
end

local function savePrefs() persist("prefs", prefs) end

-- ---------------------------------------------------------------------------------------------------------------
-- the programme guide

local function guideSpec()
  local own = trim(tos.config.epg_url)
  if own ~= "" then return own end
  local named = meta.epg or ""
  -- A list often names a compressed guide (.gz), which tos.http does not unpack: asking for it could only end in an error line.
  if (named:gsub("[?#].*$", "")):lower():match("%.gz$") then return "" end
  return named
end

-- The guide's own time zone: the offset it has now and the start of its day, in epoch milliseconds.
local function guideZone(t)
  local zone = trim(tos.config.epg_zone)
  local ok, text = pcall(tos.time.format, t, "Z", zone ~= "" and zone or nil)
  local sign, h, m
  if ok and type(text) == "string" then sign, h, m = text:match("^([%+%-])(%d%d):?(%d%d)$") end
  local offset = sign and (tonumber(h) * 60 + tonumber(m)) * 60000 * (sign == "-" and -1 or 1) or 0
  local function format(ms, pattern)
    local good, out = pcall(tos.time.format, ms, pattern, zone ~= "" and zone or nil)
    return good and out or ""
  end
  return offset, math.floor((t + offset) / 86400000) * 86400000 - offset, format
end

local function download(url)
  local response, err = tos.http.get(url, {headers = {Accept = "*/*"}})
  if not response then return nil, tostring(err) end
  if response.status ~= 200 or type(response.body) ~= "string" then return nil, "HTTP " .. tostring(response.status) end
  return response.body
end

local function resolveGuideChannel(id, names)
  local key = byGuideId[id:lower()]
  if key then return key end
  for _, name in ipairs(names) do
    key = byNameKey[channelsLib.key(name)]
    if key then return key end
  end
  return nil
end

local function programmeOf(channel, t)
  local current, following = guide.at(guideData[channel.key], t)
  return current, following
end

local publish, scheduleRender

local function loadGuide(channel)
  local spec = guideSpec()
  local t = now()
  if spec == "" or t == 0 or guideBusy[channel.key] then return end
  local at, have = guideAt[channel.key], guideData[channel.key]
  if at and ((t - at < GUIDE_FRESH and guide.covers(have, t, GUIDE_AHEAD)) or t - at < GUIDE_RETRY) then return end
  local provider = guides.provider(spec)
  if not provider then return end
  guideBusy[channel.key] = true
  local generation = listGeneration
  local offset, dayStart, format = guideZone(t)
  local ctx = {spec = spec, now = t, download = download, pause = pause, dayStart = dayStart, offset = offset, format = format,
    resolve = resolveGuideChannel}
  local ok, programmes, all, err = pcall(provider.fetch, ctx, channel)
  if not ok then programmes, all, err = nil, nil, tostring(programmes) end
  guideBusy[channel.key] = nil
  if stopped or generation ~= listGeneration then return end
  guideAt[channel.key] = t
  if all then
    for key, programmesOfKey in pairs(all) do guideData[key] = programmesOfKey; guideAt[key] = t end
  end
  if programmes then guideData[channel.key] = programmes end
  if err == "no programmes" and not programmes then
    guideNote = nil                                  -- the guide answered: it just knows nothing of this channel
  elseif err and not programmes then
    guideNote = err:find("1MB", 1, true) and "guide_big" or (err == "compressed" and "guide_gz" or "guide_failed")
    tos.log.warn("iptv: no guide for " .. channel.name .. ": " .. tostring(err))
  elseif programmes then
    guideNote = nil
  end
  publish()
  scheduleRender()
end

-- ---------------------------------------------------------------------------------------------------------------
-- what the tv component and the channel page show

local EMPTY_TV = {number = 0, name = "", group = "", line = 0, lines = 0, favorite = false, notice = "",
  program = tos.json.null, next = tos.json.null}

function publish()
  local tv = EMPTY_TV
  if cur and list[cur.index] then
    local channel = list[cur.index]
    local current, following = programmeOf(channel, now())
    local line = 0
    for i, candidate in ipairs(channel.lines) do if candidate == cur.line then line = i end end
    tv = {number = cur.index, name = channel.name, group = channel.group or "", line = line, lines = #channel.lines,
      favorite = prefs.favorites[channel.key] == true, notice = cur.notice or "",
      program = current and {title = current.t, start = current.s, ["end"] = current.e} or tos.json.null,
      next = following and {title = following.t, start = following.s} or tos.json.null}
  end
  tos.state.set(tos.json.encode({tv = tv}))
end

local function summary()
  local t, total, tested, paused = now(), 0, 0, 0
  for _, channel in ipairs(list) do
    for _, line in ipairs(channel.lines) do
      total = total + 1
      local record = recordOf(line)
      if record and record.t then tested = tested + 1 end
      if record and (record.u or 0) > t then paused = paused + 1 end
    end
  end
  return total, tested, paused
end

local function trailing(channel)
  local usable, total = usableLines(channel), #channel.lines
  if usable == 0 then return "—" end
  if usable == total then return "" end
  return usable .. "/" .. total
end

local function rowOf(index)
  local channel = list[index]
  local current = programmeOf(channel, now())
  return {title = index .. "  " .. channel.name, subtitle = current and current.t or (channel.group or ""),
    trailing = trailing(channel)}
end

local function footer(rows)
  if notices.sources then return tr("notice." .. notices.sources) end
  if #list == 0 then return tr("footer.empty") end
  if #rows == 0 then return tr("footer.empty_" .. (prefs.view == "fav" and "favorites" or "recent")) end
  if guideNote and guideSpec() ~= "" then return tr("notice." .. guideNote) end
  return ""
end

local function viewIndex()
  for i, name in ipairs(VIEWS) do if name == prefs.view then return i end end
  return 3
end

local function tabLabels()
  local labels = empty()
  labels[1] = tr("tab.favorites")
  labels[2] = tr("tab.recent")
  labels[3] = groupFilter or tr("tab.all")
  labels[4] = tr("tab.groups")
  return labels
end

-- Which list indexes the current view shows, in order.
local function indexesOfView()
  local out = {}
  if prefs.view == "fav" then
    for i, channel in ipairs(list) do if prefs.favorites[channel.key] then out[#out + 1] = i end end
  elseif prefs.view == "recent" then
    for _, key in ipairs(prefs.recent) do
      local i = indexOfKey[key]
      if i then out[#out + 1] = i end
    end
  else
    for i, channel in ipairs(list) do
      if not groupFilter or (channel.group or tr("group.other")) == groupFilter then out[#out + 1] = i end
    end
  end
  return out
end

local function currentRow()
  if not cur then return 0 end
  for row, value in ipairs(shown) do
    if value == cur.index then return row end
  end
  return 0
end

local function render()
  renderGeneration = renderGeneration + 1
  local mine = renderGeneration
  local rows, map = empty(), {}
  if prefs.view == "groups" then
    local order, count = {}, {}
    for i, channel in ipairs(list) do
      local group = channel.group or tr("group.other")
      if not count[group] then order[#order + 1] = group; count[group] = 0 end
      count[group] = count[group] + 1
    end
    for i, group in ipairs(order) do
      if i > ROW_LIMIT then break end
      rows[i] = {title = group, subtitle = tr("row.count", {count = tostring(count[group])}), trailing = ""}
      map[i] = group
    end
  else
    local indexes = indexesOfView()
    for row, index in ipairs(indexes) do
      if row > ROW_LIMIT then break end
      if row % 80 == 0 then
        pause()
        if stopped or mine ~= renderGeneration then return end
      end
      rows[row] = rowOf(index)
      map[row] = index
    end
  end
  if stopped or mine ~= renderGeneration then return end
  shown = map
  tos.state.set(tos.json.encode({items = rows, tabs = tabLabels(), tab = viewIndex(), current = currentRow(), footer = footer(rows)}))
end

-- Background changes (a result of a test, a guide that arrived) wait a moment and then show up together.
function scheduleRender()
  if renderTimer then return end
  renderTimer = tos.timer.after(1, function() renderTimer = nil; render() end)
end

-- ---------------------------------------------------------------------------------------------------------------
-- the channel list: sources -> merge -> saved

local function prepare(channels)
  local count = 0
  for _, channel in ipairs(channels) do
    for _, line in ipairs(channel.lines) do
      count = count + 1
      if count % 100 == 0 then pause() end
      line.h = hash(line.url)
    end
  end
  return channels
end

-- Takes a list of channels (hashes already set) as the channel list.
local function adopt(channels, headers)
  listGeneration = listGeneration + 1
  list, meta = channels, headers or {}
  indexOfKey, byGuideId, byNameKey = {}, {}, {}
  local live = {}
  for index, channel in ipairs(list) do
    indexOfKey[channel.key] = index
    byNameKey[channel.key] = channel.key
    if channel.id then byGuideId[channel.id:lower()] = channel.key end
    for _, line in ipairs(channel.lines) do live[line.h] = true end
  end
  local forgot = false
  for h in pairs(records) do
    if not live[h] then records[h] = nil; forgot = true end
  end
  if forgot then scheduleSave() end
  local recent = empty()
  for _, key in ipairs(prefs.recent) do if indexOfKey[key] then recent[#recent + 1] = key end end
  prefs.recent = recent
  guides.reset()
  guideData, guideAt, guideNote = {}, {}, nil
  if cur then
    local index = indexOfKey[cur.key]
    if index then cur.index = index else cur = nil end
  end
end

local function saveList(sourcesKey)
  local items = {}
  for i, channel in ipairs(list) do
    local lines = {}
    for j, line in ipairs(channel.lines) do lines[j] = {u = line.url, a = line.agent, r = line.referer, s = line.src} end
    items[i] = {k = channel.key, n = channel.name, g = channel.group, i = channel.id, l = lines}
  end
  local text = tos.json.encode({v = 1, at = now(), sig = sourcesKey, epg = meta.epg, items = items})
  -- The saved list is what the device starts with when it is offline: keep as much of it as the store can hold.
  while #text > LIST_BYTES and #items > 10 do
    for _ = 1, math.max(1, #items // 10) do items[#items] = nil end
    text = tos.json.encode({v = 1, at = now(), sig = sourcesKey, epg = meta.epg, items = items})
  end
  tos.store.set("list", text)
end

local function restoreList(saved)
  local channels = {}
  for i, item in ipairs(saved.items or {}) do
    if i % 80 == 0 then pause() end
    if type(item) == "table" and type(item.k) == "string" and type(item.n) == "string" and type(item.l) == "table" then
      local lines = {}
      for _, line in ipairs(item.l) do
        if type(line) == "table" and type(line.u) == "string" then
          lines[#lines + 1] = {url = line.u, agent = line.a, referer = line.r, src = tonumber(line.s) or 1}
        end
      end
      if #lines > 0 then channels[#channels + 1] = {key = item.k, name = item.n, group = item.g, id = item.i, lines = lines} end
    end
  end
  return channels
end

local function sourceSpecs()
  local specs = {}
  for slot = 1, SOURCE_SLOTS do
    local spec = trim(tos.config["source_" .. slot])
    if spec ~= "" then specs[#specs + 1] = {slot = slot, spec = spec} end
  end
  return specs
end

local function sourcesSignature(specs)
  local parts = {}
  for _, entry in ipairs(specs) do parts[#parts + 1] = entry.slot .. "=" .. entry.spec end
  return table.concat(parts, "\n")
end

local function refreshSources()
  sourcesGeneration = sourcesGeneration + 1
  local mine = sourcesGeneration
  if retryTimer then tos.timer.cancel(retryTimer); retryTimer = nil end
  -- A list that cannot be read now (the network is slow to come up, the host does not answer within the limit) is asked for again
  -- soon, and later each time, instead of at the next six-hourly refresh: a device that starts with the list that comes with the
  -- plugin must not sit without channels for hours because the first request was unlucky.
  local function again()
    retryStep = math.min(retryStep + 1, #RETRY_STEPS)
    retryTimer = tos.timer.after(RETRY_STEPS[retryStep], function() retryTimer = nil; refreshSources() end)
  end
  local specs = sourceSpecs()
  if #specs == 0 then
    retryStep = 0
    if #list > 0 then adopt({}, {}); tos.store.set("list", ""); end
    notices.sources = nil
    render(); publish()
    return
  end
  local items, headers, loaded = {}, {}, 0
  for _, entry in ipairs(specs) do
    local kindId, address = sources.split(entry.spec)
    local text, err = download(address)
    if stopped or mine ~= sourcesGeneration then return end
    if text then
      reach = now()                                  -- something answered: the network is up
      local parsed, found, _, kind = sources.parse(kindId, text:sub(1, BODY_LIMIT), address, {limit = SOURCE_LIMIT, pause = pause})
      text = nil
      if stopped or mine ~= sourcesGeneration then return end
      if kind and #parsed > 0 then
        loaded = loaded + 1
        headers.epg = headers.epg or found.epg
        for _, item in ipairs(parsed) do item.src = entry.slot; items[#items + 1] = item end
      else
        tos.log.warn("iptv: list " .. entry.slot .. " has no channels")
      end
    else
      tos.log.warn("iptv: list " .. entry.slot .. " failed: " .. tostring(err))
    end
  end
  if loaded == 0 then
    notices.sources = #list > 0 and "sources_failed" or "sources_none"
    again()
    render()
    return
  end
  local merged = channelsLib.merge(items, {maxChannels = MAX_CHANNELS, maxLines = MAX_LINES, pause = pause})
  items = nil
  prepare(merged)
  if stopped or mine ~= sourcesGeneration then return end
  notices.sources = nil
  if loaded < #specs then again() else retryStep = 0 end
  adopt(merged, headers)
  saveList(sourcesSignature(specs))
  savePrefs()
  render()
  publish()
end

-- ---------------------------------------------------------------------------------------------------------------
-- tuning: start a channel, keep to a line that works

local function ownsMedia() return tos.state.get("media.owner") == "iptv" end

local function remember(channel)
  local recent = empty()
  recent[1] = channel.key
  for _, key in ipairs(prefs.recent) do
    if key ~= channel.key and #recent < RECENT_LIMIT then recent[#recent + 1] = key end
  end
  prefs.recent = recent
  savePrefs()
end

local function lineNumber(channel, line)
  for i, candidate in ipairs(channel.lines) do if candidate == line then return i end end
  return 0
end

-- The line to start with: of those not tried yet and not paused, the one that last played for somebody, else the first. When all
-- are paused, the one whose pause ends first (the viewer asked for this channel, so something is tried).
local function pickLine(channel, tried)
  local t = now()
  local best, soonest
  for _, line in ipairs(channel.lines) do
    if not tried[line.url] then
      local record = recordOf(line)
      if health.usable(record, t) then
        if not best or (record and record.p or 0) > (recordOf(best) and recordOf(best).p or 0) then best = line end
      elseif not soonest or (record.u or 0) < (recordOf(soonest).u or 0) then
        soonest = line
      end
    end
  end
  return best or soonest
end

local function entryOf(channel, line)
  local entry = {url = line.url, title = channel.name, artist = channel.group or "", kind = "video"}
  if (line.agent or line.referer) and line.url:match("^https?://") then
    entry.headers = {["User-Agent"] = line.agent, Referer = line.referer}
  end
  local record = recordOf(line)
  if record and record.k == "hls" then entry.type = "hls" end
  return entry
end

local function markFail(line)
  records[line.h] = health.fail(recordOf(line), now(), tonumber(tos.config.cooldown) or 10)
  scheduleSave()
  scheduleRender()
end

local function markPlayed(line)
  local t = now()
  local record = health.ok(recordOf(line), t)
  record.p = t
  records[line.h] = record
  reach = t
  scheduleSave()
  scheduleRender()
end

local function stopMedia()
  if ownsMedia() then tos.media.stop() end
end

local function probeOptions(line)
  if not (line.agent or line.referer) or not line.url:match("^https?://") then return nil end
  return {headers = {["User-Agent"] = line.agent, Referer = line.referer}}
end

-- An answer that tells about the line (the server said no, or sent a page instead of a stream) against one that tells about the
-- network (nothing answered).
local function answered(result)
  return result.ok or result.error == "http" or result.error == "format"
end

-- Whether anything answers at all. Asked when a test got no answer (a timeout, an address that does not resolve) to tell a dead line
-- from a dead network: a recent answer from anywhere settles it, otherwise the address of the first list, which was fetched not long
-- ago, is asked. If that answers the network is up and the silent line is the one at fault. (A list full of dead addresses would
-- otherwise look like a dead network, and nothing in it would ever be paused.)
local function networkUp(t)
  if reach > 0 and t - reach < OFFLINE_GRACE then return true end
  if not tos.media.probe then return false end
  for slot = 1, SOURCE_SLOTS do
    local spec = trim(tos.config["source_" .. slot])
    if spec ~= "" then
      local _, address = sources.split(spec)
      if not address:match("^https?://") then return false end
      local result = tos.media.probe(address)
      if result and answered(result) then reach = t; return true end
      return false
    end
  end
  return false
end

-- What an answer to a probe says about the line: "ok"; "dead" (the line is at fault); "unknown" (the test could not judge: the
-- network rules refuse the address, or it is no kind a probe speaks); "offline" (nothing answers anywhere: nobody's fault).
local function judge(result, t)
  if result.ok then reach = t; return "ok" end
  if result.error == "policy" or result.error == "unsupported" then return "unknown" end
  if answered(result) then reach = t; return "dead" end
  return networkUp(t) and "dead" or "offline"
end

-- A line that has never been tested is asked once before the player gets it: an address that is gone answers a probe at once,
-- while the player tries a playlist that says 404 a few times first, or an address that does not answer for as long as twenty
-- seconds: black screen for every dead line in front of the one that works. A line the last test could not judge is asked again.
-- Only with the automatic testing on: an address that works once only (a token) should not be used up here.
-- Returns true when the line is known to be dead (and has been paused).
local function deadAhead(line)
  local record = recordOf(line)
  if not tos.config.test or not tos.media.probe or not line.url:match("^https?://") or (record and record.t and not record.x) then return false end
  local result = tos.media.probe(line.url, probeOptions(line))
  if not result then return false end
  local t = now()
  local verdict = judge(result, t)
  if verdict == "ok" then
    local kept = health.ok(record, t)
    if result.kind == "hls" then kept.k = "hls" end
    records[line.h] = kept
    scheduleSave()
    return false
  end
  if verdict ~= "dead" then return false end         -- the player will say, then
  records[line.h] = health.fail(record, t, tonumber(tos.config.cooldown) or 10)
  scheduleSave()
  scheduleRender()
  return true
end

local startLine

local function giveUp()
  cur.line = nil
  cur.notice = cur.blocked and tr("notice.policy") or tr("notice.all_failed")
  cur.noticeUntil = nil
  stopMedia()
  publish()
end

-- Moves on to the next line of the channel; `failed` says whether the one that is left counts as broken.
local function advance(failed)
  if not cur then return end
  local channel = list[cur.index]
  if failed and cur.line then markFail(cur.line) end
  local line = pickLine(channel, cur.tried)
  if line then startLine(line, true) else giveUp() end
end

function startLine(line, switched)
  local channel = list[cur.index]
  local mine = session
  cur.line, cur.since, cur.played, cur.playedFor, cur.recorded = line, now(), false, nil, false
  cur.tried[line.url] = true
  cur.notice, cur.noticeUntil = nil, nil
  local dead = deadAhead(line)
  if stopped or mine ~= session or not cur then return end
  if dead then
    advance(false)                                  -- paused above: it is not counted a second time
    return
  end
  local state, err = tos.media.load({entryOf(channel, line)}, {index = 1, autoplay = true})
  if stopped or mine ~= session or not cur then return end
  if not state then
    tos.log.warn("iptv: cannot start " .. channel.name .. ": " .. tostring(err))
    advance(true)
    return
  end
  if switched then
    cur.notice = tr("notice.switched", {line = tostring(lineNumber(channel, line)), lines = tostring(#channel.lines)})
    cur.noticeUntil = now() + NOTICE_MILLIS
  end
  publish()
end

local function tune(index, set)
  local channel = list[index]
  if not channel then return end
  session = session + 1
  cur = {index = index, key = channel.key, set = set, tried = {}, blocked = false}
  remember(channel)
  tos.page.focus("iptv.live", {live = true})
  local line = pickLine(channel, cur.tried)
  publish()
  if line then startLine(line, false) else giveUp() end
  render()
  loadGuide(channel)
end

local function neighbour(step)
  local order = cur and cur.set
  if not order then
    order = {}
    for i = 1, #list do order[i] = i end
  end
  if #order == 0 then return nil end
  local position = 0
  for i, index in ipairs(order) do if cur and index == cur.index then position = i end end
  for n = 1, #order do
    local candidate = order[((position - 1 + step * n) % #order) + 1]
    if candidate ~= (cur and cur.index) and usableLines(list[candidate]) > 0 then return candidate end
  end
  return order[((position - 1 + step) % #order) + 1]
end

local function zap(step)
  if not cur then return end
  local index = neighbour(step)
  if index and index ~= cur.index then tune(index, cur.set) end
end

local function nextLine()
  if not cur then return end
  local channel = list[cur.index]
  if #channel.lines < 2 then return end
  local at = lineNumber(channel, cur.line)
  local line = channel.lines[(at % #channel.lines) + 1]
  session = session + 1
  cur.tried = {}
  startLine(line, true)
end

local function toggleFavorite()
  if not cur then return end
  local key = list[cur.index].key
  prefs.favorites[key] = (not prefs.favorites[key]) or nil
  savePrefs()
  publish()
  render()
end

local function leave()
  if not cur then return end
  session = session + 1
  cur = nil
  stopMedia()
  publish()
  render()
end

-- ---------------------------------------------------------------------------------------------------------------
-- testing lines in the background

-- Which line to test next: paused lines whose pause is over, then lines never tested, then the oldest results; within a rank,
-- favourites first, then what was watched lately, then the order of the list.
local function nextToTest(t)
  local recheck = (tonumber(tos.config.recheck) or 12) * 3600 * 1000
  local recent = {}
  for rank, key in ipairs(prefs.recent) do recent[key] = rank end
  local best, bestChannel, bestScore
  for index, channel in ipairs(list) do
    local boost = prefs.favorites[channel.key] and 0 or (recent[channel.key] and 1 or 2)
    for _, line in ipairs(channel.lines) do
      local rank = line.url:match("^rtsp:") and nil or health.due(recordOf(line), t, recheck)
      if rank and not (cur and cur.line == line) then
        local score = rank * 1000000 + boost * 100000 + index
        if not bestScore or score < bestScore then best, bestChannel, bestScore = line, channel, score end
      end
    end
  end
  return best, bestChannel
end

local function recordTest(line, result, err)
  local t = now()
  local verdict = result and judge(result, t)
  if not result then
    tos.log.warn("iptv: test of a line failed to start: " .. tostring(err))
    records[line.h] = health.unknown(recordOf(line), t)
  elseif verdict == "ok" then
    local record = health.ok(recordOf(line), t)
    if result.kind == "hls" then record.k = "hls" end
    records[line.h] = record
  elseif verdict == "unknown" then
    -- Refused by the network rules (plain http from the internet while that is off): the switch may be turned on by and by.
    records[line.h] = health.unknown(recordOf(line), t, result.error == "policy" and POLICY_RETRY or nil)
  elseif verdict == "dead" then
    records[line.h] = health.fail(recordOf(line), t, tonumber(tos.config.cooldown) or 10)
  else
    -- Nothing answers anywhere: the network is probably down, and that is no fault of the line.
    offlineUntil = t + 5 * 60 * 1000
    return
  end
  scheduleSave()
  scheduleRender()
end

local function testOne()
  if testing or stopped or not tos.config.test or attentionLevel == "ASLEEP" or not tos.media.probe then return end
  local t = now()
  if t == 0 or t < offlineUntil then return end
  -- A channel that is starting up, or struggling, has the network to itself.
  if cur and not cur.played then return end
  local status = tos.state.get("media.status")
  if status == "buffering" or status == "reconnecting" then return end
  local line = nextToTest(t)
  if not line then return end
  testing = true                                     -- until the verdict is in: judging may ask the network once more
  local generation = listGeneration
  local ok, problem = pcall(function()
    local result, err = tos.media.probe(line.url, probeOptions(line))
    if stopped or generation ~= listGeneration then return end
    recordTest(line, result, err)
  end)
  testing = false
  if not ok then tos.log.warn("iptv: a test of a line failed: " .. tostring(problem)) end
end

local function startTester()
  if testTimer then tos.timer.cancel(testTimer); testTimer = nil end
  local gap = tonumber(tos.config.test_gap) or 10
  testTimer = tos.timer.every(math.max(3, gap), testOne)
end

-- ---------------------------------------------------------------------------------------------------------------
-- the heartbeat: is the channel playing, is the programme still the same

local function tick()
  local t = now()
  if cur and ownsMedia() then
    local status = tos.state.get("media.status")
    if status == "playing" then
      cur.played, reach = true, t
      cur.playedFor = cur.playedFor or t
      if cur.line and not cur.recorded and t - cur.playedFor >= PLAYED_AFTER then
        cur.recorded = true
        markPlayed(cur.line)
      end
    else
      cur.playedFor = nil
      if not cur.played and (status == "buffering" or status == "reconnecting") and t - cur.since > START_TIMEOUT then
        tos.log.warn("iptv: " .. list[cur.index].name .. " did not start in time")
        advance(true)
        return
      end
    end
    if cur.noticeUntil and t > cur.noticeUntil then
      cur.notice, cur.noticeUntil = nil, nil
      publish()
    end
  end
  if cur then
    local channel = list[cur.index]
    local current = programmeOf(channel, t)
    local title = current and current.t
    if title ~= cur.programme then
      cur.programme = title
      publish()
    end
    loadGuide(channel)
  end
end

-- ---------------------------------------------------------------------------------------------------------------
-- events

local function settingsSnapshot(c)
  local out = {}
  for slot = 1, SOURCE_SLOTS do out["source_" .. slot] = trim(c["source_" .. slot]) end
  out.epg_url, out.epg_zone, out.test_gap = trim(c.epg_url), trim(c.epg_zone), tostring(c.test_gap)
  return out
end

tos.on("start", function()
  prefs = restore("prefs", prefs)
  prefs.favorites = type(prefs.favorites) == "table" and prefs.favorites or {}
  prefs.recent = type(prefs.recent) == "table" and prefs.recent or {}
  if not (prefs.view == "fav" or prefs.view == "recent" or prefs.view == "all" or prefs.view == "groups") then prefs.view = "all" end
  pause()
  records = restore("health", {})
  pause()
  local saved = restore("list", nil)
  pause()
  if saved and type(saved.items) == "table" then
    local channels = prepare(restoreList(saved))
    adopt(channels, {epg = type(saved.epg) == "string" and saved.epg or nil})
  end
  previous = settingsSnapshot(tos.config)
  render()
  publish()
  tos.timer.every(2, tick)
  startTester()
  tos.timer.every(LIST_REFRESH, refreshSources)
  -- Nothing heavy while the OS is still bringing the clock up.
  tos.timer.after(3, refreshSources)
end)

tos.on("config", function(c)
  local current = settingsSnapshot(c)
  local sourcesChanged, guideChanged = false, false
  for slot = 1, SOURCE_SLOTS do
    if current["source_" .. slot] ~= previous["source_" .. slot] then sourcesChanged = true end
  end
  if current.epg_url ~= previous.epg_url or current.epg_zone ~= previous.epg_zone then guideChanged = true end
  local gapChanged = current.test_gap ~= previous.test_gap
  previous = current
  if sourcesChanged then refreshSources() end
  if guideChanged then
    guides.reset()
    guideData, guideAt, guideNote = {}, {}, nil
    if cur then loadGuide(list[cur.index]) end
    render()
  end
  if gapChanged then startTester() end
end)

tos.on("attention", function(level) attentionLevel = tostring(level):upper() end)

tos.on("back", function(page)
  if page ~= "channels" then return end
  if groupFilter then
    groupFilter = nil
    prefs.view = "groups"
    savePrefs()
    render()
  end
  holdBack()
end)

tos.on("page.enter", function(page)
  if page == "channels" then
    holdBack()
    render()
    for _, index in ipairs(shown) do
      if type(index) == "number" then loadGuide(list[index]); break end
    end
  end
end)

tos.on("page.leave", function(page)
  -- A channel nobody is looking at keeps no decoder and no connection busy.
  if page == "live" then leave() end
end)

local function onTv(action)
  if action == "prev" then zap(-1)
  elseif action == "next" then zap(1)
  elseif action == "line" then nextLine()
  elseif action == "favorite" then toggleFavorite()
  elseif action == "list" then
    tos.page.focus("iptv.channels", {})
  end
end

local function onTab(index)
  local name = type(index) == "number" and VIEWS[index]
  if not name then return end
  if name == "all" and prefs.view == "all" and groupFilter then groupFilter = nil
  else prefs.view = name end
  savePrefs()
  render()
  holdBack()
end

local function onRow(row)
  local target = type(row) == "number" and shown[row]
  if not target then return end
  if type(target) == "string" then
    groupFilter = target
    prefs.view = "all"
    savePrefs()
    render()
    holdBack()
    return
  end
  local set = {}
  for i, index in ipairs(shown) do set[i] = index end
  tune(target, set)
end

tos.on("tap", function(name, argument)
  if name == "tv" then onTv(argument)
  elseif name == "tab" then onTab(argument)
  elseif name == "play" then onRow(argument) end
end)

tos.on("media.error", function(info)
  if not cur or not cur.line then return end
  local category = tostring(info.category)
  tos.log.warn("iptv: " .. list[cur.index].name .. " line failed: " .. category)
  if category == "policy" or category == "permission" then
    -- The line is not at fault: the network rules refuse it. Other lines may be allowed.
    cur.blocked = true
    advance(false)
  else
    advance(true)
  end
end)

tos.on("media.session", function(info)
  if info.reason == "replaced" and cur then
    -- Another plugin took the screen's player.
    session = session + 1
    cur = nil
    publish()
    render()
  end
end)

tos.on("command", function(name)
  if name == "next" then zap(1)
  elseif name == "prev" then zap(-1) end
end)

tos.on("stop", function()
  stopped = true
  saveHealth()
  savePrefs()
end)
