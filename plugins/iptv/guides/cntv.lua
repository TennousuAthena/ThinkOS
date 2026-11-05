-- Guide provider "cntv": the broadcaster's own schedule of its channels (CCTV-1 … CCTV-17, 5+, 4K, 8K), one small JSON per channel and
-- day from api.cntv.cn:
--   https://api.cntv.cn/epg/epginfo?c=cctv1&serviceId=tvcctv&d=20261102
--   {"cctv1": {"channelName": "CCTV-1 综合", "program": [{"t": title, "st": start (epoch seconds), "et": end, ...}, ...]}}
-- The setting is the address of the service without a query: the service wants its own channel ids ("cctv1", "cctv5plus", "cctvjilu"),
-- which a channel list does not carry, so this provider builds the requests itself. A channel that is not one of CCTV's has no guide
-- here (and no request is made for it).
local guide = require("guide")
local P = {id = "cntv"}

local SERVICE = "https://api.cntv.cn/epg/epginfo"
local BEIJING = 8 * 3600 * 1000                 -- the service's days are Beijing days
local HOUR, DAY = 3600 * 1000, 24 * 3600 * 1000
-- Channel key (channels.key) -> the service's id, for the channels whose id is not "cctv" and the number.
local SPECIAL = {["cctv5+"] = "cctv5plus", cctv4k = "cctv4k", cctv8k = "cctv8k", cctv9 = "cctvjilu", cctv14 = "cctvchild"}

function P.accepts(spec) return spec:sub(1, #SERVICE) == SERVICE end

function P.reset() end

-- The service's id of a channel, from its key ("cctv1", "cctv5+", "cctv4k"), or nil for a channel that is not CCTV's.
function P.idOf(key)
  if SPECIAL[key] then return SPECIAL[key] end
  local number = key:match("^cctv(%d+)$")
  number = number and tonumber(number)
  if number and number >= 1 and number <= 17 then return "cctv" .. number end
  return nil
end

-- One day of one channel: {{s, e, t}, ...} (an answer without programmes, like the service's error object, is an empty list), or
-- nil and why when the document could not be had.
local function day(ctx, id, ymd)
  local body, err = ctx.download(SERVICE .. "?c=" .. id .. "&serviceId=tvcctv&d=" .. ymd)
  if not body then return nil, err end
  local ok, document = pcall(tos.json.decode, body)
  local entry = ok and type(document) == "table" and document[id] or nil
  local rows = type(entry) == "table" and entry.program or nil
  local list = {}
  if type(rows) ~= "table" then return list end
  for _, row in ipairs(rows) do
    if type(row) == "table" and type(row.t) == "string" and type(row.st) == "number" and row.st > 0 then
      local stop = type(row.et) == "number" and row.et > row.st and row.et or 0
      list[#list + 1] = {s = math.floor(row.st * 1000), e = math.floor(stop * 1000), t = guide.title(row.t)}
    end
  end
  return list
end

function P.fetch(ctx, channel)
  local id = P.idOf(channel.key)
  if not id then return nil, nil, "no programmes" end
  local list, err = day(ctx, id, guide.ymd(ctx.now, BEIJING))
  if not list then return nil, nil, err end
  -- Late in the day what comes next is in tomorrow's schedule (a day ends at 23:59).
  local last = list[#list]
  if not last or last.e == 0 or last.e < ctx.now + 2 * HOUR then
    local more = day(ctx, id, guide.ymd(ctx.now + DAY, BEIJING))
    for _, programme in ipairs(more or {}) do list[#list + 1] = programme end
  end
  local tidy = guide.tidy(list, ctx.now)
  if #tidy == 0 then return nil, nil, "no programmes" end
  return tidy, nil, nil
end

return P
