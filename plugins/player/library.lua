-- Names, durations and the play history. Pure functions, no tos.* calls.
local M = {}

-- "Artist - Title [123456]" is how the files on this device are named: split at the first " - ", drop the id.
function M.split(name)
  name = name:gsub("%s*%[%d+%]%s*$", "")
  local artist, title = name:match("^(.-)%s+%-%s+(.+)$")
  if artist and artist ~= "" then return artist, title end
  return "", name
end

-- Tags win; the file name fills in what the tags do not say.
function M.describe(item)
  local title, artist = item.title or "", item.artist or ""
  if artist == "" then
    local a, t = M.split(title)
    if a ~= "" then artist, title = a, t end
  end
  return title, artist
end

function M.duration(ms)
  ms = tonumber(ms) or 0
  if ms <= 0 then return "" end
  local total = ms // 1000
  local h, m, s = total // 3600, total % 3600 // 60, total % 60
  if h > 0 then return string.format("%d:%02d:%02d", h, m, s) end
  return string.format("%d:%02d", m, s)
end

local function lower(s) return (s or ""):lower() end

local AUDIO = {mp3 = true, aac = true, m4a = true, flac = true, ogg = true, oga = true, opus = true, wav = true, amr = true,
  mka = true, ac3 = true, eac3 = true, dts = true}

-- A web address does not say what it carries: judge by its extension, and treat the rest as video.
function M.kindOf(url)
  local ext = lower(url:gsub("[?#].*$", "")):match("%.(%w+)$")
  return AUDIO[ext or ""] and "audio" or "video"
end

-- Where a title restarts: not for a few seconds in, not for the last stretch, not for a live stream.
function M.resumeAt(entry, minimum)
  minimum = minimum or 10000
  local position, duration = tonumber(entry.position) or 0, tonumber(entry.duration) or 0
  if position < minimum then return 0 end
  if duration > 0 and position > duration - 15000 then return 0 end
  return position
end

-- Most recent first, one entry per address, at most `limit` of them.
function M.touch(list, entry, limit)
  local result = {entry}
  for _, old in ipairs(list) do
    if old.url ~= entry.url and #result < limit then result[#result + 1] = old end
  end
  return result
end

function M.progress(entry)
  local duration = tonumber(entry.duration) or 0
  if duration <= 0 then return 0 end
  return math.max(0, math.min(100, math.floor((tonumber(entry.position) or 0) * 100 / duration)))
end

return M
