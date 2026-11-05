-- M3U / M3U8 channel lists (IPTV style) and the test that tells a list from an HLS playlist.
-- Pure functions: no tos.* calls, so they run unchanged in the native test harness.
-- A copy of the player plugin's parser (a plugin brings its own files) that also keeps the guide id of a channel (tvg-id)
-- and the programme guide a list announces in its header (url-tvg / x-tvg-url).
local M = {}

local function trim(s)
  s = s:gsub("^%s+", "")
  s = s:gsub("%s+$", "")
  return s
end

-- A title that cannot be stored as UTF-8 would make the whole state update fail: keep only what is valid.
function M.clean(s)
  if utf8.len(s) then return s end
  return (s:gsub("[\128-\255]", "?"))
end

-- Cut to at most n bytes without splitting a multi-byte character.
function M.clip(s, n)
  if #s <= n then return s end
  local cut = n
  while cut > 0 do
    local b = s:byte(cut + 1)
    if b and b >= 0x80 and b < 0xC0 then cut = cut - 1 else break end
  end
  return s:sub(1, cut)
end

-- A media playlist of an HLS stream carries #EXT-X-* tags; a channel list never does.
function M.isHls(text)
  return text:find("#EXT-X-", 1, true) ~= nil
end

function M.resolve(base, ref)
  if ref:match("^%a[%w+.-]*://") then return ref end
  if not base then return nil end
  local scheme, origin, path = base:match("^(%a[%w+.-]*:)//([^/?#]*)(.*)$")
  if not scheme then return nil end
  if ref:sub(1, 2) == "//" then return scheme .. ref end
  if ref:sub(1, 1) == "/" then return scheme .. "//" .. origin .. ref end
  path = path:gsub("[?#].*$", "")
  local dir = path:match("^(.*/)") or "/"
  return scheme .. "//" .. origin .. dir .. ref
end

local function attributes(text)
  local found = {}
  for key, value in text:gmatch('([%w_%-]+)="([^"]*)"') do found[key:lower()] = value end
  return found
end

-- "#EXTINF:-1 tvg-id="a" group-title="News, World",Channel name": the name follows the first comma outside quotes.
local function extinf(line)
  local rest = line:sub(9)
  -- Jump from quote to quote and comma to comma: a character at a time cost 170 string operations per line, which on
  -- the device was most of a 50 ms slice for a few hundred channels.
  local quoted, comma, at = false, nil, 1
  while true do
    local i = rest:find('[",]', at)
    if not i then break end
    if rest:byte(i) == 34 then quoted = not quoted
    elseif not quoted then comma = i; break end
    at = i + 1
  end
  local head = comma and rest:sub(1, comma - 1) or rest
  local name = comma and trim(rest:sub(comma + 1)) or ""
  return name, attributes(head)
end

-- An address must survive java.net.URI on the other side: RFC 3986 characters, percent signs and non-ASCII text, no
-- spaces, braces or markup. One bad address would otherwise make the engine refuse a whole queue.
local function valid(url)
  return not url:find("[^%w%-%._~:/%?#%[%]@!%$&'%(%)%*%+,;=%%\128-\255]")
end
M.valid = valid

-- The programme guide a list announces: the first http(s) address of url-tvg="a,b".
local function guideOf(attrs)
  local value = attrs["url-tvg"] or attrs["x-tvg-url"] or attrs["tvg-url"]
  if not value then return nil end
  for address in value:gmatch("[^,%s]+") do
    if address:match("^https?://") and valid(address) and #address <= 1024 then return address end
  end
  return nil
end

-- Kodi / IPTV lists append request headers after a bar: "http://host/a.m3u8|User-Agent=Foo&Referer=https%3A%2F%2Fx".
local function withHeaders(line)
  local address, extra = line:match("^([^|]+)|(.+)$")
  if not address then return line, nil, nil end
  local agent, referer
  for pair in extra:gmatch("[^&]+") do
    local key, value = pair:match("^([^=]+)=(.*)$")
    if key then
      value = value:gsub("%%(%x%x)", function(hex) return string.char(tonumber(hex, 16)) end)
      key = key:lower()
      if key == "user-agent" then agent = value elseif key == "referer" or key == "referrer" then referer = value end
    end
  end
  return address, agent, referer
end

local function nameOf(url)
  local last = url:gsub("[?#].*$", ""):match("([^/]+)/*$") or url
  last = last:gsub("%%(%x%x)", function(hex) return string.char(tonumber(hex, 16)) end)
  return last
end

-- Returns the list of {title, url, group, id, agent, referer}, whether it was cut at `limit` entries, and what the header
-- of the list says ({epg = address of the programme guide}).
-- A plugin slice may use 50 ms of CPU and the device is more than ten times slower than a laptop: `pause` is called every
-- `step` lines so the caller can give the CPU back (a published list of 277 channels needed under 40 lines per slice).
function M.parse(text, base, limit, pause, step)
  limit = limit or 300
  step = step or 40
  text = text:gsub("^\239\187\191", "")
  local items, seen, pending, count, meta = {}, {}, {}, 0, {}
  for line in (text .. "\n"):gmatch("([^\r\n]*)\r?\n") do
    count = count + 1
    if pause and count % step == 0 then pause() end
    line = trim(line)
    if line ~= "" then
      local upper = line:sub(1, 11):upper()
      if upper:sub(1, 7) == "#EXTM3U" then
        meta.epg = meta.epg or guideOf(attributes(line))
      elseif upper:sub(1, 8) == "#EXTINF:" then
        local name, attrs = extinf(line)
        pending = {title = name ~= "" and name or attrs["tvg-name"], group = attrs["group-title"], id = attrs["tvg-id"]}
      elseif upper == "#EXTVLCOPT:" then
        local key, value = line:sub(12):match("^%s*([%w%-]+)%s*=%s*(.-)%s*$")
        key = key and key:lower()
        if key == "http-user-agent" then pending.agent = value
        elseif key == "http-referrer" or key == "http-referer" then pending.referer = value end
      elseif upper:sub(1, 8) == "#EXTGRP:" then
        pending.group = pending.group or trim(line:sub(9))
      elseif line:sub(1, 1) ~= "#" then
        local address, agent, referer = withHeaders(line)
        -- An HTML error page must not turn into "channels": only well-formed addresses count.
        local url = valid(address) and M.resolve(base, address) or nil
        if url and (url:match("^https?://") or url:match("^rtsp://")) and #url <= 2048 then
          local title = M.clip(M.clean(pending.title or nameOf(url)), 120)
          local key = title .. "\n" .. url
          if not seen[key] then
            if #items >= limit then return items, true, meta end
            seen[key] = true
            items[#items + 1] = {title = title, url = url, group = pending.group and M.clip(M.clean(pending.group), 60) or nil,
              id = pending.id and pending.id ~= "" and M.clip(M.clean(pending.id), 80) or nil,
              agent = (agent or pending.agent) and M.clip(agent or pending.agent, 200) or nil,
              referer = (referer or pending.referer) and M.clip(referer or pending.referer, 500) or nil}
          end
        end
        pending = {}
      end
    end
  end
  return items, false, meta
end

return M
