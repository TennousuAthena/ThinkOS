-- One channel, however many lists carry it. Lists name the same programme in many ways ("CCTV-1 综合 HD", "CCTV1", "cctv-1 [1080p]"):
-- key() reduces a name to what is left when the tags, brackets, spacing and case are gone, and merge() puts every item whose
-- guide id or key is the same into one channel with several LINES (alternative streams of it). Pure functions, no tos.* calls.
local M = {}

-- Words that describe the stream and not the programme. Latin ones are removed as whole words, the others wherever they stand.
local TAGS = {hd = true, fhd = true, uhd = true, sd = true, ["4k"] = true, ["8k"] = true, ["1080p"] = true, ["1080i"] = true,
  ["720p"] = true, ["576p"] = true, ["480p"] = true, ["50fps"] = true, ["60fps"] = true, hevc = true, h264 = true, h265 = true,
  backup = true, test = true, hls = true, ipv6 = true}
local WORDS = {"超高清", "高清", "标清", "標清", "超清", "蓝光", "藍光", "高畫質", "备用", "備用", "测试", "測試"}
local PAIRS = {{"(", ")"}, {"[", "]"}, {"<", ">"}, {"（", "）"}, {"【", "】"}, {"「", "」"}, {"［", "］"}}

local function dropBrackets(s)
  for _, pair in ipairs(PAIRS) do
    local from = 1
    while true do
      local open = s:find(pair[1], from, true)
      if not open then break end
      local close = s:find(pair[2], open + #pair[1], true)
      if not close then break end
      s = s:sub(1, open - 1) .. " " .. s:sub(close + #pair[2])
      from = open
    end
  end
  return s
end

-- How good the stream says it is, 0 when it does not say: 4 = 4K, 3 = full HD, 2 = HD, 1 = SD.
function M.quality(name)
  local s = name:lower()
  if s:find("4k", 1, true) or s:find("uhd", 1, true) or s:find("超高清", 1, true) then return 4 end
  if s:find("fhd", 1, true) or s:find("1080", 1, true) then return 3 end
  if s:find("%f[%w]hd%f[%W]") or s:find("高清", 1, true) then return 2 end
  if s:find("%f[%w]sd%f[%W]") or s:find("标清", 1, true) or s:find("標清", 1, true) then return 1 end
  return 0
end

-- The key of a name. CCTV channels are numbered, and what follows the number is a nickname ("CCTV-1综合"), so the number is the key.
function M.key(name)
  local lower = dropBrackets(name:lower())
  local number, plus, tail = lower:match("^%s*cctv[%s%-_]*(%d+)(%+?)(.*)$")
  if number then
    if (number == "4" or number == "8") and tail:find("^k%f[%W]") then return "cctv" .. number .. "k" end
    return "cctv" .. number .. plus
  end
  local plain = lower
  for _, word in ipairs(WORDS) do plain = plain:gsub(word, "") end
  plain = plain:gsub("%f[%w](%w+)%f[%W]", function(token) if TAGS[token] then return "" end end)
  plain = plain:gsub("[^%w\128-\255+]", "")
  if plain == "" then plain = lower:gsub("[^%w\128-\255+]", "") end
  return plain
end

local function better(a, b)
  if a.src ~= b.src then return a.src < b.src end
  if a.q ~= b.q then return a.q > b.q end
  return a.seq < b.seq
end

-- Lists decorate their group names with emoji ("📺央视频道"). The device's font does not know the newer ones (it draws an empty box),
-- and the OS draws flat single-colour icons: the decoration goes, the words stay. Returns nil when nothing else is left.
function M.label(text)
  if not text then return nil end
  text = text:gsub("[\240-\247][\128-\191][\128-\191][\128-\191]", "")   -- outside the Basic Multilingual Plane: nearly all emoji
  text = text:gsub("\226[\152-\158][\128-\191]", "")                         -- U+2600..U+27BF: symbols and dingbats (the clover)
  text = text:gsub("\239\184[\128-\143]", "")                                 -- U+FE00..U+FE0F: variation selectors
  text = text:gsub("\226\128\141", "")                                         -- U+200D: zero width joiner
  text = text:gsub("^%s+", ""):gsub("%s+$", "")
  return text ~= "" and text or nil
end

-- Some lists carry an entry that is no channel but a stamp of when the list was made, named "2026-10-12 11:25:50" and wearing the
-- guide id of the first real channel: it would take that channel's lines and give it the date as its name.
function M.isChannel(name) return not name:find("^%s*%d%d%d%d[%-/%.]%d%d?[%-/%.]%d%d?") end

-- items: {{name, url, group?, id?, agent?, referer?, src}, ...} in the order of preference (lists as the user ordered them, then
-- the order inside each list). Returns the channels in the order they first appeared:
--   {key, name, group, id, lines = {{url, agent, referer, src, q}, ...}}
-- with each channel's lines ordered by list, then by quality, and at most opts.maxLines of them; opts.maxChannels bounds the rest.
function M.merge(items, opts)
  opts = opts or {}
  local maxChannels, maxLines = opts.maxChannels or 600, opts.maxLines or 6
  local out, byId, byKey, seen, labels = {}, {}, {}, {}, {}
  for n, item in ipairs(items) do
    if opts.pause and n % 60 == 0 then opts.pause() end
    local key = M.isChannel(item.name) and M.key(item.name)
    local group = item.group
    if group then
      if labels[group] == nil then labels[group] = M.label(group) or false end
      group = labels[group] or nil
    end
    local id = item.id and item.id:lower() or nil
    local channel = key and ((id and byId[id]) or byKey[key])
    if key and not channel and #out < maxChannels then
      channel = {key = key, name = item.name, group = group, id = item.id, lines = {}}
      out[#out + 1] = channel
    end
    if channel then
      byKey[key] = byKey[key] or channel
      if id then byId[id] = byId[id] or channel end
      channel.group = channel.group or group
      channel.id = channel.id or item.id
      if not seen[item.url] then
        seen[item.url] = true
        channel.lines[#channel.lines + 1] = {url = item.url, agent = item.agent, referer = item.referer, src = item.src or 1,
          q = M.quality(item.name), seq = n}
      end
    end
  end
  for _, channel in ipairs(out) do
    table.sort(channel.lines, better)
    while #channel.lines > maxLines do channel.lines[#channel.lines] = nil end
    for _, line in ipairs(channel.lines) do line.seq = nil end
  end
  return out
end

return M
