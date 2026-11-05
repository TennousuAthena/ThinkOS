-- Source kind "json": a JSON list of channels, either an array or an object holding one under "channels" (or "list", "items",
-- "data"). A channel names itself `name` / `title`, its stream `url` / `stream` / `src` / `link` (or several in `urls`), and may
-- bring `group` / `category`, `id` / `tvg_id`, `ua` / `user_agent` and `referer`. The object may announce its programme guide as `epg`.
local m3u = require("m3u")
local K = {id = "json"}

function K.sniff(text)
  local first = text:match("^%s*(.)")
  return first == "[" or first == "{"
end

local function text(value, limit)
  if type(value) ~= "string" or value == "" then return nil end
  return m3u.clip(m3u.clean(value), limit)
end

function K.parse(source, base, opts)
  local ok, document = pcall(tos.json.decode, source)
  if not ok or type(document) ~= "table" then return {}, {}, false end
  local rows = document
  local meta = {}
  if document[1] == nil then
    rows = document.channels or document.list or document.items or document.data
    local epg = text(document.epg or document["url-tvg"] or document.url_tvg, 1024)
    if epg and epg:match("^https?://") and m3u.valid(epg) then meta.epg = epg end
  end
  if type(rows) ~= "table" then return {}, meta, false end
  local items, count = {}, 0
  for _, row in ipairs(rows) do
    count = count + 1
    if opts.pause and count % 40 == 0 then opts.pause() end
    if type(row) == "table" then
      local name = text(row.name or row.title or row.channel, 120)
      local urls = row.urls or row.lines
      if type(urls) ~= "table" then urls = {row.url or row.stream or row.src or row.link} end
      for _, address in ipairs(urls) do
        if type(address) == "string" and m3u.valid(address) and #address <= 2048 then
          local url = m3u.resolve(base, address)
          if url and (url:match("^https?://") or url:match("^rtsp://")) then
            if #items >= opts.limit then return items, meta, true end
            items[#items + 1] = {name = name or url:gsub("[?#].*$", ""):match("([^/]+)/*$") or url, url = url,
              group = text(row.group or row.category or row["group-title"], 60), id = text(row.id or row.tvg_id or row["tvg-id"], 80),
              agent = text(row.ua or row.user_agent or row["user-agent"], 200), referer = text(row.referer or row.referrer, 500)}
          end
        end
      end
    end
  end
  return items, meta, false
end

return K
