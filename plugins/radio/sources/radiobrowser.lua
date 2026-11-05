-- Original client for the documented Radio Browser JSON API; no upstream code.
local m3u = require("m3u")
local M = {PAGE=100, BODY=256*1024, LIMIT=200, POOL=8, REQUESTS=8, CATEGORY_REQUESTS=3}
local OFFICIAL="https://all.api.radio-browser.info"
local DISCOVERY="all.api.radio-browser.info"
local function text(value, limit)
  return type(value)=="string" and m3u.clip(m3u.clean(value):gsub("[%c]", " "),limit) or ""
end
function M.uuid(value)
  return type(value)=="string" and #value==36 and value:match("^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$")~=nil
end
function M.country(value)
  if type(value)~="string" then return "" end
  value=value:upper()
  return value:match("^[A-Z][A-Z]$") and value or ""
end
function M.host(url)
  if type(url)~="string" then return nil end
  local authority=url:match("^https://([^/?#]+)")
  if not authority or authority:find("@",1,true) then return nil end
  local host, port=authority:match("^([%w.-]+):(%d+)$")
  host=host or authority
  if port and (tonumber(port)<1 or tonumber(port)>65535) then return nil end
  host=host:lower()
  if host:sub(-1)=="." then host=host:sub(1,-2) end
  return host
end
function M.public(url)
  if type(url)~="string" or #url>2048 or not url:match("^https://") or url:find("#",1,true)
    or url:find("[^%w%-%._~:/%?%[%]@!%$&'%(%)%*%+,;=%%\128-\255]") then return false end
  local host=M.host(url)
  if not host or #host>253 or host:find("[^a-z0-9.-]") or host:find("..",1,true) then return false end
  -- Reject ambiguous numeric addresses, IPv6 literals, single-label and local names.
  -- DNS answers/redirect addresses for media remain governed by the OS.
  if host:match("^%d+%.%d+%.%d+%.%d+$") then
    local octets={}
    for part in host:gmatch("[^.]+") do
      if #part>1 and part:sub(1,1)=="0" then return false end
      local n=tonumber(part); if not n or n>255 then return false end
      octets[#octets+1]=n
    end
    local a,b,c=table.unpack(octets)
    return not (a==0 or a==10 or a==127 or a>=224 or (a==100 and b>=64 and b<=127)
      or (a==169 and b==254) or (a==172 and b>=16 and b<=31)
      or (a==192 and (b==168 or b==0 or (b==88 and c==99)))
      or (a==198 and (b==18 or b==19 or (b==51 and c==100))) or (a==203 and b==0 and c==113))
  end
  if not host:match("%.[a-z][a-z]+$") then return false end
  for _, suffix in ipairs({"localhost", "local", "localdomain", "internal", "home", "lan", "invalid", "test", "example", "onion"}) do
    if host==suffix or host:sub(-#suffix-1)=="."..suffix then return false end
  end
  for label in host:gmatch("[^.]+") do
    if #label>63 or not label:match("^[a-z0-9]") or not label:match("[a-z0-9]$") then return false end
  end
  return true
end
local function quote(value)
  return (value:gsub("[^A-Za-z0-9_.~-]", function(c) return string.format("%%%02X",c:byte()) end))
end
function M.endpoint(value)
  if type(value)~="string" or #value>1024 or value:find("[?#%s@]") then return nil end
  value=value:gsub("/+$","")
  -- The public endpoint is configurable for an explicitly selected local mirror/test origin.
  local port=value:match(":(%d+)$")
  if port and (tonumber(port)<1 or tonumber(port)>65535) then return nil end
  if value:match("^https://[%w.-]+$") or value:match("^https://[%w.-]+:%d+$") then return value end
  if value:match("^http://127%.0%.0%.1:%d+$") or value:match("^http://localhost:%d+$") then return value end
  return nil
end
function M.poolHost(value)
  if type(value)~="string" or #value>253 then return nil end
  local host=value:lower():gsub("%.$","")
  local suffix=".api.radio-browser.info"
  if host==DISCOVERY or host:sub(-#suffix)~=suffix or #host<=#suffix
    or host:find("[^a-z0-9.-]") or host:find("..",1,true) then return nil end
  for label in host:gmatch("[^.]+") do
    if #label>63 or not label:match("^[a-z0-9]") or not label:match("[a-z0-9]$") then return nil end
  end
  if host:sub(1,1)=="." then return nil end
  return host
end
local function active(options) return not options or not options.active or options.active() end
local function routes(base,options)
  base=M.endpoint(base)
  if not base then return nil,"endpoint" end
  if not active(options) then return nil,"cancelled" end
  local origins={}
  if base==OFFICIAL then
    if not options or type(options.resolve)~="function" then return nil,"discovery" end
    -- One managed DNS await per refresh, shared by all tag requests. A PTR is
    -- only a candidate name; the HTTPS client still verifies its DNS and TLS.
    local entries=options.resolve(DISCOVERY)
    if not active(options) then return nil,"cancelled" end
    local seen={}
    if type(entries)=="table" then for i=1,math.min(#entries,M.POOL) do
      local host=type(entries[i])=="table" and M.poolHost(entries[i].name)
      if host and not seen[host] then seen[host]=true;origins[#origins+1]="https://"..host end
    end end
    if #origins==0 then return nil,"discovery" end
    local start=(options.random or math.random)(#origins)
    local ordered={}
    for i=1,#origins do ordered[i]=origins[(start+i-2)%#origins+1] end
    origins=ordered
    local preferred=options.session and options.session.preferred
    for i,origin in ipairs(origins) do if origin==preferred then table.remove(origins,i);table.insert(origins,1,origin);break end end
  else origins[1]=base end -- Explicit mirrors never trigger discovery or rewriting.
  return {origins=origins,failed={},used=0,limit=M.REQUESTS,options=options,official=base==OFFICIAL}
end
local function request(pool,get,path,agent,parse)
  for _,origin in ipairs(pool.origins) do
    if not active(pool.options) then return nil,"cancelled" end
    if pool.used>=pool.limit then return nil,"request" end
    if not pool.failed[origin] then
      pool.used=pool.used+1
      local response=get(origin..path,{headers={["User-Agent"]=agent or "ThinkOS-Radio/0.1.0",Accept="application/json"}})
      if not active(pool.options) then return nil,"cancelled" end
      local rows,err,more
      if response and response.status==200 then
        if parse then rows,err,more=parse(response.body) else rows=true end
      end
      if rows then
        -- Keep the last working origin first for the remaining tags and the
        -- next discovery in this process. Nothing is pinned or persisted.
        for i,value in ipairs(pool.origins) do if value==origin then table.remove(pool.origins,i);table.insert(pool.origins,1,origin);break end end
        if pool.official and pool.options.session then pool.options.session.preferred=origin end
        return rows,nil,more
      end
      pool.failed[origin]=true
      if pool.official and pool.options.session and pool.options.session.preferred==origin then pool.options.session.preferred=nil end
    end
  end
  return nil,"request"
end
function M.query(base, country, tag, offset)
  base=M.endpoint(base)
  offset=tonumber(offset) or 0
  if not base or offset<0 or offset%1~=0 or offset>10000 then return nil end
  local url=base.."/json/stations/search?hidebroken=true&is_https=true&order=clickcount&reverse=true&limit=100&offset="..offset
  country=M.country(country)
  if country~="" then url=url.."&countrycode="..country end
  if tag then
    if type(tag)~="string" or #tag>80 then return nil end
    url=url.."&tag="..quote(tag).."&tagExact=true"
  end
  return url
end
local function boundedArray(body,pause)
  local depth,quoted,escaped,count=0,false,false,0
  for i=1,#body do
    local c=body:byte(i)
    if quoted then
      if escaped then escaped=false elseif c==92 then escaped=true elseif c==34 then quoted=false end
    elseif c==34 then
      if depth==1 then return false end
      quoted=true
    elseif c==123 or c==91 then
      if depth==1 then
        if c~=123 then return false end
        count=count+1;if count>M.PAGE then return false end
      end
      depth=depth+1;if depth>24 then return false end
    elseif c==125 or c==93 then depth=depth-1
    elseif depth==1 and c~=32 and c~=9 and c~=10 and c~=13 and c~=44 then return false end
    if pause and i%4096==0 then pause() end
  end
  return not quoted and depth==0
end
function M.parse(body, decode, pause)
  if type(body)~="string" or #body>M.BODY or not body:match("^%s*%[") then return nil,"body" end
  -- Bound row count/depth before materializing untrusted JSON into Lua tables.
  if not boundedArray(body,pause) then return nil,"shape" end
  local ok, rows=pcall(decode,body)
  if not ok or type(rows)~="table" or #rows>M.PAGE then return nil,"json" end
  local out, seen={},{}
  for i,row in ipairs(rows) do
    if pause and i%10==0 then pause() end
    if type(row)=="table" and M.uuid(row.stationuuid) and tonumber(row.lastcheckok)==1 and tonumber(row.ssl_error or 0)==0 then
      local url=type(row.url_resolved)=="string" and row.url_resolved~="" and row.url_resolved or row.url
      local uuid=row.stationuuid:lower()
      if M.public(url) and not seen[uuid] then
        seen[uuid]=true
        out[#out+1]={uuid=uuid,url=url,title=text(row.name,120),tags=text(row.tags,2048),
          country=M.country(row.countrycode),clicks=math.max(0,math.min(1000000000,tonumber(row.clickcount) or 0)),hls=tonumber(row.hls)==1 or nil}
        if out[#out].title=="" then out[#out].title=M.host(url) end
      end
    end
  end
  return out,nil,#rows==M.PAGE
end
function M.fetch(get, decode, base, country, tags, offsets, pause, agent, options)
  local out, seen, nextOffsets, more={}, {}, {}, false
  local selectedCountry=M.country(country)
  local categoryRequest=tags~=nil
  tags=tags or {false}
  if #tags>3 then return nil,"tags" end
  -- Validate every query before DNS/HTTP. A failed tag discards this entire
  -- refresh, so the caller can keep its previous complete cache.
  local paths={}
  for i,tag in ipairs(tags) do
    local url=M.query(base,country,tag or nil,(offsets and offsets[i]) or 0)
    if not url then return nil,"endpoint" end
    paths[i]=url:sub(#M.endpoint(base)+1)
  end
  local pool,poolError=routes(base,options)
  if not pool then return nil,poolError end
  -- APP-9 caps the entire category refresh at three managed HTTP calls,
  -- including failed origins. All-list discovery and click keep their bound.
  pool.limit=categoryRequest and M.CATEGORY_REQUESTS or M.REQUESTS
  for i,tag in ipairs(tags) do
    local offset=(offsets and offsets[i]) or 0
    local rows,err,hasMore=request(pool,get,paths[i],agent,function(body) return M.parse(body,decode,pause) end)
    if not rows then return nil,err end
    nextOffsets[i]=offset+M.PAGE; more=more or hasMore
    for _,row in ipairs(rows) do
      if (selectedCountry=="" or row.country==selectedCountry) and not seen[row.uuid] then
        seen[row.uuid]=true;out[#out+1]=row
      end
    end
  end
  -- At most three independently bounded 100-row replies reach this merge.
  -- Trim only after ranking their union; otherwise a full first/second tag
  -- silently excludes every higher-click station in the third tag.
  table.sort(out,function(a,b) return a.clicks==b.clicks and a.uuid<b.uuid or a.clicks>b.clicks end)
  while #out>M.LIMIT do table.remove(out) end
  return out,nil,more,nextOffsets
end
function M.click(get,base,uuid,agent,options)
  if not M.uuid(uuid) then return end
  local pool=routes(base,options)
  if not pool then return end
  return request(pool,get,"/json/url/"..uuid:lower(),agent)
end
return M
