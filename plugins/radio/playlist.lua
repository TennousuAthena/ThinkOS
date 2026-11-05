-- Radio keeps its own parser modules. Playback and HTTP policy remain OS capabilities.
local m3u = require("m3u")
local M = {}

function M.valid(url)
  if type(url) ~= "string" or #url > 2048 then return false end
  local authority = url:match("^https?://([^/?#]+)")
  return authority ~= nil and not authority:find("@", 1, true) and not url:find("#", 1, true)
    and not url:find("[^%w%-%._~:/%?#%[%]@!%$&'%(%)%*%+,;=%%\128-\255]")
end

function M.parse(text, base, limit, pause)
  limit = limit or 200
  text = text:gsub("^\239\187\191", "")
  if m3u.isHls(text) then return {}, false, "hls" end
  if not text:lower():match("^%s*%[playlist%]") then
    local parsed, cut = m3u.parse(text, base, limit, pause, 40)
    local rows = {}
    for _, item in ipairs(parsed) do if M.valid(item.url) then rows[#rows + 1] = item end end
    return rows, cut
  end
  local values, lines = {}, 0
  for line in (text .. "\n"):gmatch("([^\r\n]*)\r?\n") do
    lines = lines + 1
    if pause and lines % 40 == 0 then pause() end
    local key, index, value = line:match("^%s*([%a]+)(%d+)%s*=%s*(.-)%s*$")
    index = tonumber(index)
    if index and index >= 1 and index <= limit then
      key = key:lower()
      if key == "file" or key == "title" then
        values[index] = values[index] or {}
        values[index][key] = value
      end
    end
  end
  local items, seen = {}, {}
  for index = 1, limit do
    local item = values[index]
    local url = item and item.file and m3u.resolve(base, item.file)
    if url and M.valid(url) and not seen[url] then
      seen[url] = true
      local title = item.title and item.title ~= "" and item.title or url:gsub("[?#].*$", ""):match("([^/]+)$") or ""
      items[#items + 1] = {url = url, title = m3u.clip(m3u.clean(title), 120)}
    end
  end
  return items, false
end

return M
