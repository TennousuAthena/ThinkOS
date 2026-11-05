-- Guide provider "xmltv": one XMLTV document for all channels, from the settings or from the header of a list (url-tvg).
-- It is read once and kept for a few hours. The plugin gets the document through tos.http, which stops at 1 MiB and does not
-- unpack .gz files: a bigger or compressed guide is reported as such and the plugin goes on without one.
local guide = require("guide")
local P = {id = "xmltv"}

local REFRESH = 6 * 3600 * 1000
local RETRY = 20 * 60 * 1000
local cache = {}                        -- address -> {at, byKey}

function P.accepts(spec) return spec:match("^https?://") ~= nil and spec:find("{", 1, true) == nil end

function P.reset() cache = {} end

-- Reads a document. `resolve(id, names)` says which channel of ours an XMLTV channel is (its key), or nil.
function P.parse(text, resolve, now, pause)
  local keyOf = {}
  -- The channels come before the programmes in an XMLTV file. Looking for the last </channel> through the programmes that
  -- follow it would be one pattern match over most of the document, with no chance to give the CPU back.
  local first = text:find("<programme", 1, true)
  for attributes, body in (first and text:sub(1, first - 1) or text):gmatch("<channel%s+([^>]-)>(.-)</channel>") do
    local id = attributes:match('id%s*=%s*"([^"]*)"') or attributes:match("id%s*=%s*'([^']*)'")
    if id then
      local names = {}
      for name in body:gmatch("<display%-name[^>]*>(.-)</display%-name>") do names[#names + 1] = guide.title(name) end
      keyOf[id] = resolve(id, names)
    end
  end
  local lists, count = {}, 0
  for attributes, body in text:gmatch("<programme%s+([^>]-)>(.-)</programme>", first or 1) do
    count = count + 1
    if pause and count % 40 == 0 then pause() end
    local id = attributes:match('channel%s*=%s*"([^"]*)"') or attributes:match("channel%s*=%s*'([^']*)'")
    local key = id and keyOf[id]
    if key then
      local start = guide.xmltvTime(attributes:match('start%s*=%s*"([^"]*)"') or "")
      local stop = guide.xmltvTime(attributes:match('stop%s*=%s*"([^"]*)"') or "")
      local title = body:match("<title[^>]*>(.-)</title>")
      if start and title and start < now + guide.WINDOW_AFTER and (not stop or stop > now - guide.WINDOW_BEFORE) then
        local list = lists[key]
        if not list then list = {}; lists[key] = list end
        if #list < 4 * guide.PER_CHANNEL then list[#list + 1] = {s = start, e = stop or 0, t = guide.title(title)} end
      end
    end
  end
  for key, list in pairs(lists) do lists[key] = guide.tidy(list, now) end
  return lists
end

function P.fetch(ctx, channel)
  local entry = cache[ctx.spec]
  if not entry or ctx.now - entry.at >= REFRESH then
    local body, err = ctx.download(ctx.spec)
    if not body then
      -- Try again in a while, not at every channel change.
      cache[ctx.spec] = {at = ctx.now - REFRESH + RETRY, byKey = entry and entry.byKey or {}}
      return nil, nil, err
    end
    if body:sub(1, 2) == "\31\139" then
      cache[ctx.spec] = {at = ctx.now, byKey = {}}
      return nil, nil, "compressed"
    end
    local lists = P.parse(body, ctx.resolve, ctx.now, ctx.pause)
    body = nil
    entry = {at = ctx.now, byKey = lists}
    cache[ctx.spec] = entry
  end
  return entry.byKey[channel.key], entry.byKey, nil
end

return P
