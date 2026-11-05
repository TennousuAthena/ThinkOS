-- Source kind "m3u": an M3U / M3U8 channel list, the form most IPTV lists come in.
local m3u = require("m3u")
local K = {id = "m3u"}

-- The host of an address without any user:password in front of it (this becomes a channel's name on screen).
local function hostOf(url)
  local authority = url:match("^%a+://([^/?#]*)") or url
  return (authority:gsub("^.*@", ""))
end

function K.sniff(text)
  local head = text:sub(1, 4096)
  return head:find("#EXTM3U", 1, true) ~= nil or head:find("#EXTINF", 1, true) ~= nil
end

function K.parse(text, base, opts)
  -- An address that is itself a stream (an HLS playlist) is one channel.
  if m3u.isHls(text) and base then return {{name = hostOf(base), url = base}}, {}, false end
  local parsed, truncated, meta = m3u.parse(text, base, opts.limit, opts.pause)
  local items = {}
  for i, item in ipairs(parsed) do
    items[i] = {name = item.title, url = item.url, group = item.group, id = item.id, agent = item.agent, referer = item.referer}
  end
  return items, meta, truncated
end

return K
