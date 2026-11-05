-- Where the programme guide comes from. A PROVIDER turns a guide address and a channel into that channel's programmes, and
-- nothing else in this plugin knows what a guide looks like (guide.lua holds what the providers share).
--
-- A provider is a module returning
--   id        its name
--   accepts   function(spec) -> boolean      whether it can use the guide address (from the settings, or the header of a list)
--   fetch     function(ctx, channel) -> programmes, all, err
--               programmes = {{s = start ms, e = end ms, t = title}, ...} sorted by start, for this channel (nil when unknown)
--               all        = the same for every channel it learned about (key -> programmes), or nil
--               err        = why there is none, for the log and the page
--               ctx        = {spec, now, download = function(url) -> text | nil, err, pause = function() or nil,
--                             dayStart = ms at the start of today in the guide's zone, offset = that zone's offset in ms,
--                             format = function(ms, pattern) in the guide's zone,
--                             resolve = function(guideId, names) -> channel key | nil}
--   reset     function()                     forget what it cached (the channel list changed)
-- To add a provider write guides/<id>.lua and add it to PROVIDERS. The first one that accepts the address is used, so one that knows
-- an address of its own goes before the general ones (guides/cntv.lua before guides/template.lua).
local M = {}

local PROVIDERS = {require("guides.cntv"), require("guides.template"), require("guides.xmltv")}

function M.provider(spec)
  for _, provider in ipairs(PROVIDERS) do
    if provider.accepts(spec) then return provider end
  end
  return nil
end

function M.reset() for _, provider in ipairs(PROVIDERS) do provider.reset() end end

return M
