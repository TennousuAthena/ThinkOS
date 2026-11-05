-- Where channels come from. A source KIND turns the text of one address into channels, and nothing else in this plugin knows
-- what a list looks like: main.lua downloads, this file picks the kind, channels.lua merges what the kinds returned.
--
-- A kind is a module returning
--   id      the name used in front of an address ("json:https://host/list") to force it
--   sniff   function(text) -> boolean   whether the text looks like this kind (used when the address names no kind)
--   parse   function(text, base, opts) -> items, meta, truncated
--             opts = {limit = most items to return, pause = function to give the CPU back, or nil}
--             items = {{name, url, group?, id?, agent?, referer?}, ...}; meta = {epg = programme guide address, or nil}
-- To add a kind (an Xtream API, a Stalker portal ...) write sources/<id>.lua and add it to KINDS.
local M = {}

local KINDS = {require("sources.m3u"), require("sources.json")}
local byId = {}
for _, kind in ipairs(KINDS) do byId[kind.id] = kind end

-- "json:https://host/list" names the kind; a bare address leaves it to the text.
function M.split(spec)
  local id, address = spec:match("^(%a[%w_]*):(https?://.*)$")
  if id and byId[id] then return id, address end
  return nil, spec
end

function M.kinds() return KINDS end

-- Returns items, meta, truncated, and the id of the kind that read the text (nil when none could).
function M.parse(id, text, base, opts)
  local kind = id and byId[id]
  if not kind then
    for _, candidate in ipairs(KINDS) do
      if candidate.sniff(text) then kind = candidate; break end
    end
  end
  if not kind then return {}, {}, false, nil end
  local items, meta, truncated = kind.parse(text, base, opts)
  return items, meta or {}, truncated == true, kind.id
end

return M
