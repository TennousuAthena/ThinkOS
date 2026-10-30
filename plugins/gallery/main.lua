-- Sources provide metadata through tos.*. OS image/carousel owns HTTP image bytes and pixels.
local localPhotos,dailyPhotos={},{}
local photos=tos.json.decode("[]")
local localNotice,dailyNotice,directory="loading","loading",""
local stopped,sleeping=false,false
local scanBusy,scanAgain,scanGeneration=false,false,0
local dailyBusy,dailyAgain,dailyGeneration=false,false,0
local scanTimer,dailyTimer
local activeDir=tos.config.local_dir
local fetchedAt,retry=0,nil
local Bing
local function source() return tos.config.source or "local" end
local function localEnabled() return source()=="local" or source()=="all" end
local function dailyEnabled() return source()=="bing" or source()=="all" end
local function bing() if not Bing then Bing=require("sources.bing") end; return Bing end
local function identity()
  return bing().market(tos.locale(),tos.config.market),tos.config.endpoint
end
local function render()
  local list=tos.json.decode("[]")
  local bytes,seen=0,{}
  local function append(item)
    if not item or type(item.src)~="string" or #item.src>2048 or #list>=300 then return end
    local title=type(item.title)=="string" and item.title or ""
    local credit=type(item.credit)=="string" and item.credit or ""
    local id=type(item.id)=="string" and item.id or item.src
    local cost=#item.src+#id+(#title+#credit)*6+128
    if not seen[id] and bytes+cost<=180*1024 then
      bytes=bytes+cost; seen[id]=true
      list[#list+1]={id=id,src=item.src,title=title,credit=credit}
    end
  end
  for i=1,math.max(localEnabled() and #localPhotos or 0,dailyEnabled() and #dailyPhotos or 0) do
    if dailyEnabled() then append(dailyPhotos[i]) end
    if localEnabled() then append(localPhotos[i]) end
  end
  photos=list
  local notice
  if #photos==0 then notice=dailyEnabled() and dailyNotice or localNotice
  elseif dailyEnabled() and dailyNotice=="offline" then notice="offline"
  elseif not dailyEnabled() and localNotice=="unavailable" then notice="unavailable" end
  tos.state.set(tos.json.encode({photos=photos,cover=photos[1] and photos[1].src or "",
    coverTitle=photos[1] and photos[1].title or tos.i18n.t(notice or "name"),
    coverCredit=photos[1] and photos[1].credit or "",notice=notice and tos.i18n.t(notice) or "",directory=directory}))
end
local function cancelScan() if scanTimer then tos.timer.cancel(scanTimer); scanTimer=nil end end
local function cancelDaily() if dailyTimer then tos.timer.cancel(dailyTimer); dailyTimer=nil end end
local scan,refresh
local function scheduleScan()
  cancelScan()
  if not stopped and not sleeping and localEnabled() then
    scanTimer=tos.timer.after(tos.config.scan_seconds,function() scanTimer=nil; scan() end)
  end
end
local function scheduleDaily(seconds,force)
  cancelDaily()
  if not stopped and not sleeping and dailyEnabled() then
    dailyTimer=tos.timer.after(math.max(.1,seconds),function() dailyTimer=nil; refresh(force) end)
  end
end
scan=function()
  if stopped or sleeping or not localEnabled() then return end
  if scanBusy then scanAgain=true; return end
  cancelScan(); scanBusy=true; scanAgain=false
  local version=scanGeneration
  local dir=tos.config.local_dir:match("^%s*(.-)%s*$")
  local ok,result,err=pcall(tos.library.list,{kind="image",dir=dir~="" and dir or nil,recursive=true})
  scanBusy=false
  if stopped then return end
  if version==scanGeneration then
    if ok and result then
      local list={}; local bytes,limited=0,false
      for _,item in ipairs(result.items or {}) do
        if #list==300 then limited=true; break end
        if type(item.src)=="string" and #item.src<=2048 then
          local title=type(item.title)=="string" and item.title or ""
          local cost=#item.src*2+#title*6+128
          if bytes+cost>180*1024 then limited=true; break end
          bytes=bytes+cost; list[#list+1]={id=item.src,src=item.src,title=title}
        end
      end
      table.sort(list,function(a,b) if a.title==b.title then return a.src<b.src end; return a.title<b.title end)
      localPhotos=list; directory=result.directory or ""; localNotice=#list==0 and "empty" or nil
      tos.log.info("Local gallery scanned",#list,"photos")
      if result.truncated or limited then tos.log.warn("Local gallery reached its item or state-size budget") end
    else localNotice="unavailable"; tos.log.warn("Local gallery scan failed",err or result) end
    render()
  end
  if scanAgain or version~=scanGeneration then return scan() end
  scheduleScan()
end
local function loadCache()
  if not dailyEnabled() then return end
  local market,endpoint=identity()
  local ok,cache=pcall(function()
    local value=tos.store.get("bing")
    return type(value)=="string" and tos.json.decode(value) or nil
  end)
  if ok and type(cache)=="table" and cache.market==market and cache.endpoint==endpoint then
    dailyPhotos=bing().restore(cache.items,bing().endpoint(endpoint),tos.config.history,tos.config.wallpaper_only)
    fetchedAt=type(cache.fetchedAt)=="number" and cache.fetchedAt or 0
    if #dailyPhotos>0 then dailyNotice=nil end
  end
end
local function saveCache(market,endpoint)
  tos.store.set("bing",tos.json.encode({market=market,endpoint=endpoint,items=dailyPhotos,fetchedAt=fetchedAt}))
end
local function nextDailyDelay()
  local now=tos.time.now()
  local period=tonumber(tos.config.refresh_hours)*3600
  if now and fetchedAt>0 and now>=fetchedAt then return math.max(1,period-(now-fetchedAt)/1000) end
  return 1
end
refresh=function(force)
  if stopped or sleeping or not dailyEnabled() then return end
  if dailyBusy then dailyAgain=true; return end
  cancelDaily()
  local now=tos.time.now()
  if not now then dailyNotice=#dailyPhotos>0 and "offline" or "waiting_clock"; render(); scheduleDaily(tos.config.clock_retry); return end
  if not force and fetchedAt>0 and now>=fetchedAt and now-fetchedAt<tonumber(tos.config.refresh_hours)*3600000 then
    scheduleDaily(nextDailyDelay()); return
  end
  dailyBusy=true; dailyAgain=false
  local version=dailyGeneration
  local market,endpoint=identity()
  local ok,err=pcall(function()
    local indices=(#dailyPhotos==0 or force) and {0,7} or {0}
    for _,index in ipairs(indices) do
      local response,error=tos.http.get(bing().url(endpoint,market,index),{headers={Accept="application/json"}})
      if stopped or version~=dailyGeneration then return end
      assert(response and response.status==200,error or ("Bing HTTP "..tostring(response and response.status)))
      local items=bing().parse(tos.json.decode(response.body),bing().endpoint(endpoint),tos.config.wallpaper_only)
      assert(#items>0,"Bing response has no usable daily images")
      dailyPhotos=bing().merge(dailyPhotos,items,tos.config.history); dailyNotice=nil; render()
      saveCache(market,endpoint) -- Preserve the first batch even if the older archive fails.
    end
    fetchedAt=now; retry=tos.config.retry_initial
    saveCache(market,endpoint)
    tos.log.info("Bing gallery refreshed",market,#dailyPhotos,"photos")
  end)
  dailyBusy=false
  if stopped then return end
  if version~=dailyGeneration or dailyAgain then return refresh(true) end
  if ok then scheduleDaily(tonumber(tos.config.refresh_hours)*3600)
  else
    dailyNotice=#dailyPhotos>0 and "offline" or "bing_unavailable"
    tos.log.warn("Bing gallery refresh failed",err); render()
    retry=retry or tos.config.retry_initial; scheduleDaily(retry,true); retry=math.min(retry*2,tos.config.retry_max)
  end
end
local previousSource=source()
local previousData
local function dataIdentity()
  if not dailyEnabled() then return "" end
  local market,endpoint=identity()
  return market.."|"..endpoint.."|"..tostring(tos.config.wallpaper_only).."|"..tos.config.history
end
tos.on("start",function()
  if dailyEnabled() then loadCache(); previousData=dataIdentity(); scheduleDaily(nextDailyDelay()) end
  render()
  if localEnabled() then scanTimer=tos.timer.after(.1,function() scanTimer=nil; scan() end) end
end)
tos.on("stop",function() stopped=true; scanGeneration=scanGeneration+1; dailyGeneration=dailyGeneration+1; cancelScan(); cancelDaily() end)
tos.on("config",function()
  local changedSource=previousSource~=source(); previousSource=source()
  local changedLocal=changedSource or tos.config.local_dir~=activeDir
  if changedLocal then
    activeDir=tos.config.local_dir; scanGeneration=scanGeneration+1; localPhotos={}; localNotice="loading"
  end
  local current=dataIdentity()
  if changedSource or current~=previousData then
    previousData=current; dailyGeneration=dailyGeneration+1; dailyPhotos={}; fetchedAt=0; dailyNotice="loading"; retry=nil
    if dailyEnabled() then loadCache(); scheduleDaily(.1) else cancelDaily() end
  elseif dailyEnabled() then scheduleDaily(nextDailyDelay()) end
  render()
  -- Invalidate both generations before the local enumeration can yield to an old HTTP result.
  if changedLocal then
    if localEnabled() then scan() else cancelScan() end
  else scheduleScan() end
end)
tos.on("locale",function()
  if dailyEnabled() and dataIdentity()~=previousData then
    previousData=dataIdentity(); dailyGeneration=dailyGeneration+1; dailyPhotos={}; fetchedAt=0; dailyNotice="loading"
    loadCache(); scheduleDaily(.1)
  end
  render()
end)
tos.on("command",function(name) if name=="refresh" then scan(); refresh(true) end end)
tos.on("attention",function(level)
  local wasSleeping=sleeping; sleeping=level=="ASLEEP"
  if sleeping then cancelScan(); cancelDaily()
  elseif wasSleeping then scan(); if dailyEnabled() then scheduleDaily(nextDailyDelay()) end
  else
    if localEnabled() and not scanTimer and not scanBusy then scheduleScan() end
    if dailyEnabled() and not dailyTimer and not dailyBusy then scheduleDaily(nextDailyDelay()) end
  end
end)
