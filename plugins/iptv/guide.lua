-- What every programme guide provider needs (guides.lua says what a provider is): times, XML text, lookups of the programme on
-- air. Pure functions, no tos.* calls.
local M = {}

M.WINDOW_BEFORE = 30 * 60 * 1000          -- a programme that ended less than this long ago is still worth keeping
M.WINDOW_AFTER = 12 * 3600 * 1000
M.PER_CHANNEL = 24
M.TITLE_BYTES = 80

-- ---------------------------------------------------------------------------------------------------------------
-- time

local function daysFromCivil(year, month, day)
  if month <= 2 then year = year - 1 end
  local era = (year >= 0 and year or year - 399) // 400
  local yoe = year - era * 400
  local doy = (153 * (month + (month > 2 and -3 or 9)) + 2) // 5 + day - 1
  local doe = yoe * 365 + yoe // 4 - yoe // 100 + doy
  return era * 146097 + doe - 719468
end
M.daysFromCivil = daysFromCivil

-- The calendar date of an instant in a zone `offset` ms east of UTC, as "20261102" (the inverse of daysFromCivil).
function M.ymd(ms, offset)
  local days = (ms + (offset or 0)) // 86400000 + 719468
  local era = days // 146097
  local doe = days - era * 146097
  local yoe = (doe - doe // 1460 + doe // 36524 - doe // 146096) // 365
  local doy = doe - (365 * yoe + yoe // 4 - yoe // 100)
  local mp = (5 * doy + 2) // 153
  local day = doy - (153 * mp + 2) // 5 + 1
  local month = mp < 10 and mp + 3 or mp - 9
  return string.format("%04d%02d%02d", yoe + era * 400 + (month <= 2 and 1 or 0), month, day)
end

-- "20261102093000 +0800" (XMLTV) -> epoch milliseconds. The offset defaults to UTC; seconds may be missing.
function M.xmltvTime(text)
  local y, mo, d, h, mi, s, rest = text:match("^%s*(%d%d%d%d)(%d%d)(%d%d)(%d%d)(%d%d)(%d?%d?)%s*(.-)%s*$")
  if not y then return nil end
  local seconds = ((daysFromCivil(tonumber(y), tonumber(mo), tonumber(d)) * 24 + tonumber(h)) * 60 + tonumber(mi)) * 60 + (tonumber(s) or 0)
  local sign, oh, om = rest:match("^([%+%-])(%d%d):?(%d%d)")
  if sign then
    local offset = (tonumber(oh) * 60 + tonumber(om)) * 60
    seconds = seconds - (sign == "-" and -offset or offset)
  end
  return seconds * 1000
end

-- "2026-11-02T09:30:00+08:00", "2026-11-02 09:30:00" -> epoch ms; a text with no offset is read in `offset` (ms east of UTC).
function M.isoTime(text, offset)
  local y, mo, d, h, mi, s, rest = text:match("^%s*(%d%d%d%d)%-(%d%d)%-(%d%d)[T ](%d%d):(%d%d):?(%d?%d?)[%.%d]*%s*(.-)%s*$")
  if not y then return nil end
  local seconds = ((daysFromCivil(tonumber(y), tonumber(mo), tonumber(d)) * 24 + tonumber(h)) * 60 + tonumber(mi)) * 60 + (tonumber(s) or 0)
  local zone = 0
  if rest == "Z" or rest == "z" then zone = 0
  else
    local sign, oh, om = rest:match("^([%+%-])(%d%d):?(%d%d)")
    if sign then zone = (tonumber(oh) * 60 + tonumber(om)) * 60 * (sign == "-" and -1 or 1)
    else zone = math.floor((offset or 0) / 1000) end
  end
  return (seconds - zone) * 1000
end

-- "06:30" -> minutes into the day
function M.clock(text)
  local h, m = text:match("^%s*(%d%d?):(%d%d)")
  if not h then return nil end
  h, m = tonumber(h), tonumber(m)
  if h > 24 or m > 59 then return nil end
  return h * 60 + m
end

-- ---------------------------------------------------------------------------------------------------------------
-- text

local ENTITIES = {amp = "&", lt = "<", gt = ">", quot = '"', apos = "'"}

function M.unescape(s)
  s = s:gsub("<!%[CDATA%[(.-)%]%]>", "%1")
  return (s:gsub("&(#?%w+);", function(name)
    if ENTITIES[name] then return ENTITIES[name] end
    local code = name:match("^#(%d+)$") or (name:match("^#[xX](%x+)$") and tonumber(name:sub(3), 16))
    code = tonumber(code)
    if code and code > 0 and code < 0x110000 and not (code >= 0xD800 and code < 0xE000) then return utf8.char(code) end
    return ""
  end))
end

-- A title that cannot be stored as UTF-8 would make the whole state update fail.
function M.title(s)
  s = M.unescape(s):gsub("%s+", " "):gsub("^ ", ""):gsub(" $", "")
  if not utf8.len(s) then s = s:gsub("[\128-\255]", "?") end
  if #s > M.TITLE_BYTES then
    local cut = M.TITLE_BYTES
    while cut > 0 do
      local b = s:byte(cut + 1)
      if b and b >= 0x80 and b < 0xC0 then cut = cut - 1 else break end
    end
    s = s:sub(1, cut)
  end
  return s
end

function M.encode(s)
  return (s:gsub("[^%w%-%._~]", function(c) return string.format("%%%02X", c:byte()) end))
end

-- ---------------------------------------------------------------------------------------------------------------
-- programmes

-- Keeps what is near `now`, in order, with the end of each filled in from the start of the next when the guide gives none.
function M.tidy(list, now)
  table.sort(list, function(a, b) return a.s < b.s end)
  local out = {}
  for i, p in ipairs(list) do
    local nextStart = list[i + 1] and list[i + 1].s
    local finish = p.e
    -- No end given: the programme lasts until the next one starts, and the last one is taken to last two hours.
    if finish == nil or finish <= p.s then finish = nextStart or (p.s + 2 * 3600 * 1000) end
    if finish > now - M.WINDOW_BEFORE then
      if p.s < now + M.WINDOW_AFTER and #out < M.PER_CHANNEL and (#out == 0 or out[#out].s ~= p.s) then
        out[#out + 1] = {s = p.s, e = finish, t = p.t}
      end
    end
  end
  return out
end

-- The programme on air at `now` and the one after it.
function M.at(list, now)
  if not list then return nil, nil end
  for i, p in ipairs(list) do
    if p.s <= now and now < p.e then return p, list[i + 1] end
    if p.s > now then return nil, p end
  end
  return nil, nil
end

-- True when the guide knows what is on at `now` and for a while after it: nothing more to fetch for a few hours.
function M.covers(list, now, horizon)
  local last = list and list[#list]
  return last ~= nil and last.e >= now + horizon
end

-- JSON guides, one channel per document, in the shapes the common guide services use:
--   {"epg_data": [{"start": "06:30", "end": "07:00", "title": "News"}]}        (times of the day, read on `dayStart`)
--   {"programs": [{"start": 1730507400, "end": 1730509200, "title": "News"}]}   (epoch seconds or milliseconds)
--   [{"start": "2026-11-02T06:30:00+08:00", "title": "News"}]                    (ISO times)
-- `dayStart` is the epoch ms of the start of the guide's day, `offset` the guide zone's offset in ms east of UTC.
function M.fromJson(document, dayStart, offset, now)
  if type(document) ~= "table" then return nil end
  local rows = document
  if document[1] == nil then rows = document.epg_data or document.programs or document.programmes or document.data or document.list end
  if type(rows) ~= "table" then return nil end
  local list, previous, day = {}, nil, 0
  for _, row in ipairs(rows) do
    if type(row) == "table" then
      local title = row.title or row.name or row.programme or row.program
      local start = M.when(row.start or row.starttime or row.begin, dayStart, offset)
      local finish = M.when(row["end"] or row.endtime or row.stop, dayStart, offset)
      if type(title) == "string" and start then
        -- Times of the day wrap past midnight: a start earlier than the one before it is the next morning.
        if type(row.start) == "string" and M.clock(row.start) and not row.start:find("[%-T]") then
          if previous and start + day < previous then day = day + 86400000 end
          start = start + day
          if finish then finish = finish + day; if finish <= start then finish = finish + 86400000 end end
          previous = start
        end
        list[#list + 1] = {s = start, e = finish or 0, t = M.title(title)}
      end
    end
  end
  if #list == 0 then return nil end
  -- A guide service that knows nothing of a channel often answers with the same filler programme all day ("Good programmes", twelve
  -- times). That is no guide.
  if #list >= 3 then
    local same = true
    for i = 2, #list do
      if list[i].t ~= list[1].t then same = false; break end
    end
    if same then return nil end
  end
  return M.tidy(list, now)
end

function M.when(value, dayStart, offset)
  if type(value) == "number" then
    if value ~= value or value <= 0 then return nil end
    return math.floor(value < 100000000000 and value * 1000 or value)
  end
  if type(value) ~= "string" then return nil end
  local minutes = M.clock(value)
  if minutes and not value:find("[%-T]") then return dayStart + minutes * 60000 end
  return M.isoTime(value, offset) or M.xmltvTime(value)
end

return M
