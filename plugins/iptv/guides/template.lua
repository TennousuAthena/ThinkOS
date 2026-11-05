-- Guide provider "template": one small JSON document per channel and day from an address with placeholders, the way the
-- guide services behind many IPTV lists answer:  https://guide.example/api/?ch={name}&date={date}
--   {name}  the channel's name, percent-encoded      {id}   its guide id from the list (tvg-id), percent-encoded
--   {date}  2026-11-02                                {ymd}  20261102        (the day in the guide's time zone)
local guide = require("guide")
local P = {id = "template"}

function P.accepts(spec) return spec:find("{", 1, true) ~= nil and spec:match("^https?://") ~= nil end

function P.reset() end

function P.fetch(ctx, channel)
  local address = ctx.spec:gsub("{(%a+)}", function(name)
    if name == "name" then return guide.encode(channel.name)
    elseif name == "id" then return guide.encode(channel.id or "")
    elseif name == "date" then return ctx.format(ctx.now, "yyyy-MM-dd")
    elseif name == "ymd" then return ctx.format(ctx.now, "yyyyMMdd") end
  end)
  if #address > 2048 then return nil, nil, "address too long" end
  local body, err = ctx.download(address)
  if not body then return nil, nil, err end
  local ok, document = pcall(tos.json.decode, body)
  if not ok then return nil, nil, "not JSON" end
  local list = guide.fromJson(document, ctx.dayStart, ctx.offset, ctx.now)
  if not list then return nil, nil, "no programmes" end
  return list, nil, nil
end

return P
