-- One OS player; this plugin owns directory selection, cache and reachability.
local m3u,playlist=require("m3u"),require("playlist")
local rb,categories,health=require("sources.radiobrowser"),require("categories"),require("health")
local LIMIT,FAVORITES,CACHE_BYTES,HEALTH_BYTES,STORE_BYTES=200,50,65536,81920,256*1024
local stations,favorites,visible,caches,records={},{},{},{},{}
local stored,pendingFavorite={},nil
local tab,category,current,notice=2,nil,nil,nil
local generation,loading,again,stopped=0,false,false,false
local reconnect,testTimer,testing,attention=nil,nil,false,"AWAKE"
local wanted,playSequence,context=false,0,nil
local previousKey,hasMore,offsets="",false,nil
local directorySession={}
local refresh,render,startTester,play,advance
local function array() return tos.json.decode("[]") end
local function now() return tos.time.now() end
local function t(key) return tos.i18n.t(key) end
local function clean(value,n) return m3u.clip(m3u.clean(type(value)=="string" and value or ""):gsub("[%c]"," "),n or 120) end
local function cfg(key) return type(tos.config[key])=="string" and tos.config[key] or "" end
local function directory() return tos.config.directory==true end
local function country()
  if tos.config.country_auto~=false then
    local setup=tos.data.get("onboarding")
    return type(setup)=="table" and rb.country(setup.region) or ""
  end
  return rb.country(cfg("country"))
end
local function sourceKey()
  return directory() and ("rb\n"..cfg("directory_url").."\n"..country().."\n"..(category or ""))
    or ("list\n"..cfg("source").."\n"..cfg("stream_url").."\n"..cfg("station_name"))
end
local function favorite(url)
  for i,item in ipairs(favorites) do if item.url==url then return i end end
end
local function recordKey(item)
  if not item.h then item.h=health.hash(item.url) end
  return item.h
end
local function normalize(item)
  if type(item)~="table" or not playlist.valid(item.url) then return nil end
  local fromDirectory=item.directory==true or item.uuid~=nil
  if fromDirectory and (not rb.uuid(item.uuid) or not rb.public(item.url)) then return nil end
  return {url=item.url,title=clean(type(item.title)=="string" and item.title~="" and item.title or item.url),uuid=fromDirectory and item.uuid:lower() or nil,
    directory=fromDirectory or nil,public_only=fromDirectory or nil,tags=fromDirectory and clean(item.tags,2048) or nil,
    country=fromDirectory and rb.country(item.country) or nil,clicks=fromDirectory and tonumber(item.clicks) or nil,
    hls=item.hls==true or nil,group=not fromDirectory and clean(item.group,60) or nil,
    agent=type(item.agent)=="string" and clean(item.agent,256) or nil,
    referer=type(item.referer)=="string" and clean(item.referer,256) or nil}
end
local function restore(key)
  local raw=tos.store.get(key)
  if type(raw)~="string" or #raw>196608 then return nil end
  local ok,value=pcall(tos.json.decode,raw)
  return ok and type(value)=="table" and value or nil
end
local function restoreRows(rows,limit)
  local out,seen={},{}
  if type(rows)~="table" then return out end
  for index,row in ipairs(rows) do
    if #out>=limit then break end
    local item=normalize(row)
    if item and not seen[item.uuid or item.url] then
      if item.directory then item.h=health.hash(item.url) end
      seen[item.uuid or item.url]=true;out[#out+1]=item
    end
    if index%10==0 then tos.timer.sleep(.001) end
  end
  return out
end
local function cacheValue() return tos.json.encode({v=1,entries=#caches>0 and caches or array()}) end
local function outerBytes(values)
  -- The C encoder includes the second escaping layer without whole-string Lua
  -- pattern scans. This is only a planning estimate: Android quoting differences
  -- and unknown old keys are handled by the host's atomic limit check below.
  return #tos.json.encode(values)
end
local function writeStored(key,value)
  -- JsonDocument rejects an over-budget plan before changing any existing key.
  local ok,result,errorKey=pcall(tos.store.set,key,value)
  if ok and result~=false and errorKey==nil then stored[key]=value;return true end
  return false
end
local function persistKnown(key,value)
  local function current(other) return other==key and value or stored[other] end
  local function reduce()
    if #caches>1 then table.remove(caches);return "directory_cache",cacheValue() end
    if #caches==1 and #caches[1].items>0 then
      local list=caches[1].items
      for _=1,math.max(1,math.ceil(#list/4)) do table.remove(list) end
      caches[1].more=false
      return "directory_cache",cacheValue()
    end
    local empty=tos.json.encode({v=1,entries=array()})
    if current("directory_cache")~=nil and current("directory_cache")~=empty then
      caches={};return "directory_cache",empty
    end
    local keys={}
    for hash in pairs(records) do keys[#keys+1]=hash end
    if #keys>0 then
      table.sort(keys,function(a,b) return (records[a].t or 0)<(records[b].t or 0) end)
      for i=1,math.max(1,math.ceil(#keys/4)) do records[keys[i]]=nil end
      return "health",tos.json.encode(records)
    end
    if current("health")~=nil and current("health")~="{}" then return "health","{}" end
  end
  while true do
    local plan={}
    for name,raw in pairs(stored) do plan[name]=raw end
    plan[key]=value
    if outerBytes(plan)<=STORE_BYTES and writeStored(key,value) then return true end
    -- Unknown old keys cannot be enumerated through tos.store. A host refusal
    -- therefore trims only our known expendable data, never unknown keys or
    -- valid favorites. If no room remains, the old favorite value stays intact.
    local cut,reduced=reduce()
    if not cut then return false end
    if cut==key then value=reduced
    elseif stored[cut]~=nil and outerBytes({[cut]=reduced})<outerBytes({[cut]=stored[cut]}) then
      if not writeStored(cut,reduced) then return false end
    end
  end
end
local function saveFavorites()
  local saved=array()
  for _,item in ipairs(favorites) do
    local copy={}
    for key,value in pairs(item) do if key~="tags" and key~="clicks" and key~="country" and key~="h" then copy[key]=value end end
    saved[#saved+1]=copy
  end
  return persistKnown("favorites",tos.json.encode(saved))
end
local function cacheFor(key)
  for _,item in ipairs(caches) do if item.key==key then return item end end
end
local function saveCache(key,rows,more,nextOffsets)
  local old=cacheFor(key)
  if old then for i,item in ipairs(caches) do if item==old then table.remove(caches,i);break end end end
  -- Size rows individually before encoding the whole cache. A malicious tag
  -- full of JSON escapes must not make a transient >1 MiB encode or store write.
  local packed,budget={},CACHE_BYTES-#key-512
  for i,item in ipairs(restoreRows(rows,LIMIT)) do
    local size=#tos.json.encode(item)+1
    if size>budget then more=false;break end
    packed[#packed+1]=item;budget=budget-size
    if i%20==0 then tos.timer.sleep(.001) end
  end
  local saved={key=key,items=packed,more=more,offsets=nextOffsets}
  table.insert(caches,1,saved)
  while #caches>4 do table.remove(caches) end
  local encoded=tos.json.encode({v=1,entries=caches})
  while #encoded>CACHE_BYTES do
    if #caches>1 then table.remove(caches)
    elseif #saved.items>0 then
      local drop=math.max(1,math.ceil(#saved.items*(#encoded-CACHE_BYTES)/#encoded))
      for _=1,drop do table.remove(saved.items) end
      saved.more=false
    else break end
    encoded=tos.json.encode({v=1,entries=caches})
  end
  persistKnown("directory_cache",encoded)
end
local function saveHealth()
  health.trim(records)
  local encoded=tos.json.encode(records)
  if #encoded>HEALTH_BYTES then
    local keys={}
    for key in pairs(records) do keys[#keys+1]=key end
    table.sort(keys,function(a,b) return (records[a].t or 0)<(records[b].t or 0) end)
    local i=1
    while #encoded>HEALTH_BYTES and keys[i] do
      local drop=math.max(1,math.ceil((#keys-i+1)*(#encoded-HEALTH_BYTES)/#encoded))
      for _=1,drop do if keys[i] then records[keys[i]]=nil;i=i+1 end end
      encoded=tos.json.encode(records)
    end
  end
  persistKnown("health",encoded)
end
local function usable(item)
  local clock=now()
  return not item.directory or not clock or health.usable(records[recordKey(item)],clock)
end
local function holdBack() tos.back.intercept(category~=nil) end
local function groupRows()
  local out=array()
  if directory() then
    for _,item in ipairs(categories.items) do out[#out+1]={title=t("category."..item.id),category=item.id} end
  else
    local seen={}
    for _,item in ipairs(stations) do
      if item.group and item.group~="" and not seen[item.group] then
        seen[item.group]=true;out[#out+1]={title=item.group,category=item.group}
      end
    end
  end
  return out
end
render=function()
  visible={}
  local rows,selected,contentRows=array(),0,0
  if tab==3 then
    for _,item in ipairs(groupRows()) do rows[#rows+1]={title=item.title};visible[#visible+1]=item end
    contentRows=#rows
  else
    local source=tab==1 and favorites or stations
    for _,item in ipairs(source) do
      if (tab==1 or usable(item)) and (tab==1 or directory() or not category or item.group==category) then
        rows[#rows+1]={title=item.title,subtitle=clean(rb.host(item.url) or (item.url:match("^%a+://([^/?#]*)") or "")),trailing=favorite(item.url) and "✓" or ""}
        visible[#visible+1]=item
        if current and item.url==current.url and tos.state.get("media.owner")=="radio" then selected=#rows end
      end
    end
    contentRows=#rows -- The pagination action is not a station or category.
    if directory() and tab==2 and hasMore and #stations<LIMIT then
      rows[#rows+1]={title=t("action.more")};visible[#visible+1]={more=true}
    end
  end
  local message=notice and t("notice."..notice) or ""
  local footer=message
  if footer=="" and contentRows==0 then
    footer=t(tab==1 and "notice.favorites" or tab==3 and "notice.categories"
      or ((not directory() and cfg("source")=="" and cfg("stream_url")=="") and "notice.empty" or "notice.list_empty"))
  end
  if footer=="" then footer=t("action.refresh") end
  tos.state.set(tos.json.encode({items=rows,current=selected,tab=tab,
    tabs={t("tab.favorites"),category and (directory() and t("category."..category) or category) or t("tab.all"),t("tab.categories")},
    footer=footer,notice=message,station=current and current.title or "",
    favoriteLabel=current and t(favorite(current.url) and "action.unfavorite" or "action.favorite") or ""}))
end
local function cancelRetry()
  if reconnect then tos.timer.cancel(reconnect);reconnect=nil end
end
local function entry(item)
  local headers={}
  if item.agent then headers["User-Agent"]=item.agent end
  if item.referer then headers.Referer=item.referer end
  return {url=item.url,title=item.title,kind="audio",type=item.hls and "hls" or nil,
    headers=next(headers) and headers or nil,public_only=item.directory and true or nil}
end
local function probeOptions(item)
  local value=entry(item)
  return {headers=value.headers,public_only=item.directory and true or nil}
end
local function recordTest(item,result)
  local clock=now()
  if not clock then return result and result.ok and "ok" or "unknown" end
  local key=recordKey(item)
  local record,verdict=health.record(records[key],result,clock,tonumber(tos.config.cooldown) or 10)
  records[key]=record
  if verdict=="dead" and tos.config.host_cooldown==true and health.hostFailure(result) then
    local host=rb.host(item.url)
    if host then
      local seen={[item.url]=true}
      local lists={stations,favorites}
      for _,saved in ipairs(caches) do lists[#lists+1]=saved.items end
      for _,list in ipairs(lists) do for _,other in ipairs(list) do
        if other.directory and not seen[other.url] and rb.host(other.url)==host and
          not (current and current.url==other.url and tos.state.get("media.status")=="playing") then
          seen[other.url]=true
          records[recordKey(other)]={f=record.f,u=record.u,t=record.t}
        end
      end end
    end
  end
  saveHealth();render()
  return verdict
end
local function deadAhead(item,explicitFavorite)
  if not item.directory or tos.config.test==false or not tos.media.probe then return false end
  local clock,record=now(),records[recordKey(item)]
  if not explicitFavorite and clock and not health.due(record,clock,(tonumber(tos.config.recheck) or 12)*3600000) then
    return not health.usable(record,clock)
  end
  local result=tos.media.probe(item.url,probeOptions(item))
  return recordTest(item,result)=="dead"
end
local function reportClick(item)
  if not item.directory or tos.config.click~=true then return end
  local uuid,base=item.uuid,cfg("directory_url")
  -- Best effort in a separate managed timer; a slow directory cannot delay playback.
  tos.timer.after(.001,function()
    if not stopped and tos.config.click==true then
      rb.click(tos.http.get,base,uuid,"ThinkOS-Radio/0.1.0",{resolve=tos.net.resolve,session=directorySession,
        active=function() return not stopped and tos.config.click==true and cfg("directory_url")==base end})
    end
  end)
end
local function pendingOwned(pending)
  if not pending or stopped or not wanted or pendingFavorite~=pending or pending.sequence~=playSequence or current~=pending.previous then return false end
  local owner,status=tos.state.get("media.owner") or "",tos.state.get("media.status")
  return owner=="radio" and pending.owner=="radio" and status~="paused" and status~="stopped"
    or owner=="" and pending.owner=="" and status=="stopped"
end
local function retry()
  cancelRetry()
  if not wanted or stopped then return end
  if pendingFavorite then
    local pending=pendingFavorite
    if not pendingOwned(pending) then pendingFavorite=nil;wanted=false;return end
    notice=current and tos.state.get("media.status")=="playing" and "loading" or "network";render()
    local retryId
    retryId=tos.timer.after(tos.config.reconnect_s,function()
      if pendingFavorite~=pending or pending.sequence~=playSequence then return end
      if reconnect==retryId then reconnect=nil end
      if not pendingOwned(pending) then pendingFavorite=nil;wanted=false;return end
      pending.ctx.index,pending.ctx.attempts=1,0
      pending.ctx.pendingRetry=true
      -- A user-named pending favorite really retries, without changing its
      -- health cooldown or making that address eligible for automatic lists.
      advance(pending.ctx,pending.sequence)
    end)
    reconnect=retryId
    return
  end
  if not current then return end
  notice="network";render()
  local item=current
  reconnect=tos.timer.after(tos.config.reconnect_s,function()
    reconnect=nil
    local status=tos.state.get("media.status")
    local owner=tos.state.get("media.owner")
    if not stopped and wanted and current==item and owner=="radio" and (status=="error" or status=="ended") then
      play(item,false,true)
    end
  end)
end
local function giveUp()
  wanted=false;context=nil;pendingFavorite=nil;notice="all_failed";cancelRetry()
  if tos.state.get("media.owner")=="radio" and tos.media.stop then tos.media.stop() end
  render()
end
advance=function(ctx,mine)
  local limit=math.min(5,tonumber(tos.config.failover_limit) or 5)
  while not stopped and wanted and mine==playSequence and ctx.attempts<limit and ctx.index<=#ctx.items do
    local item=ctx.items[ctx.index]
    ctx.index=ctx.index+1
    if ctx.favorite or usable(item) then
      ctx.attempts=ctx.attempts+1
      local dead=deadAhead(item,ctx.favorite)
      if stopped or not wanted or mine~=playSequence then return end
      if ctx.pendingRetry and not pendingOwned(pendingFavorite) then pendingFavorite=nil;wanted=false;return end
      if not dead then
        local state,errorKey=tos.media.load({entry(item)},{autoplay=true})
        if stopped or not wanted or mine~=playSequence then return end
        if state then
          current,notice,context=item,nil,ctx
          pendingFavorite=nil
          render()
          if ctx.focus then ctx.focus=false;tos.page.focus("radio.now",{live=true}) end
          -- Only a deliberate user start reports a click; automatic failover/retries never do.
          if ctx.userClick then ctx.userClick=false;reportClick(item) end
          return
        end
        if errorKey=="policy" then ctx.policy=true;recordTest(item,{ok=false,error="policy"}) end
      end
      if ctx.favorite then
        -- Until OS load succeeds, current/context still name the real old
        -- session. A failed B probe must never label playing A as station B.
        if dead then ctx.focus=false;retry()
        else wanted=false;pendingFavorite=nil;notice=ctx.policy and "directory_policy" or "play_failed";render() end
        return
      end
    end
  end
  if mine==playSequence then
    giveUp()
    if ctx.policy then notice="directory_policy";render() end
  end
end
play=function(item,focus,retrying)
  cancelRetry();pendingFavorite=nil;playSequence=playSequence+1
  local mine=playSequence
  wanted=true
  local ownFavorite=retrying or tab==1 or not item.directory
  local choices={item}
  if not ownFavorite then
    local after=false
    for _,other in ipairs(stations) do
      if after and other.url~=item.url then choices[#choices+1]=other end
      if other.url==item.url then after=true end
    end
    for _,other in ipairs(stations) do
      if other.url==item.url then break end
      choices[#choices+1]=other
    end
  end
  local ctx={items=choices,index=1,attempts=0,favorite=ownFavorite,focus=focus,userClick=not retrying}
  if ownFavorite then
    pendingFavorite={item=item,ctx=ctx,sequence=mine,previous=current,owner=tos.state.get("media.owner") or ""}
  end
  advance(ctx,mine)
end
refresh=function(more)
  generation=generation+1
  if loading then again=true;return end
  loading=true
  repeat
    again=false
    local mine,key=generation,sourceKey()
    local list,errorKey,moreResult,nextOffsets=nil,nil,false,nil
    notice="loading";render()
    if directory() then
      local descriptor=category and categories.get(category)
      list,errorKey,moreResult,nextOffsets=rb.fetch(tos.http.get,tos.json.decode,cfg("directory_url"),country(),
        descriptor and descriptor.tags or nil,more and offsets or nil,function() tos.timer.sleep(.001) end,"ThinkOS-Radio/0.1.0",
        {resolve=tos.net.resolve,session=directorySession,active=function() return not stopped and mine==generation end})
      if list then
        local accepted={}
        for index,item in ipairs(list) do
          if not descriptor or categories.match(item.tags)[category] then
            item.directory=true;item.public_only=true;item.h=health.hash(item.url)
            accepted[#accepted+1]=item
          end
          if index%10==0 then tos.timer.sleep(.001) end
        end
        list=accepted
        if more then
          local joined,seen={},{}
          for _,set in ipairs({stations,list}) do for _,item in ipairs(set) do
            if #joined<LIMIT and not seen[item.uuid or item.url] then
              seen[item.uuid or item.url]=true;joined[#joined+1]=item
            end
          end end
          list=joined
        end
      end
    else
      list={}
      local source=cfg("source")
      if source~="" then
        local response=tos.http.get(source)
        if response and response.status==200 and type(response.body)=="string" and #response.body<=262144 then
          local rows=playlist.parse(response.body,source,LIMIT,function() tos.timer.sleep(.001) end)
          list=restoreRows(rows,LIMIT)
        else list=nil;errorKey="request" end
      end
      local stream,name=cfg("stream_url"),cfg("station_name")
      if playlist.valid(stream) then
        list=list or {}
        table.insert(list,1,{url=stream,title=clean(name~="" and name or (stream:match("^%a+://([^/?#]*)") or stream))})
        if #list>LIMIT then table.remove(list) end
      end
    end
    if not stopped and mine==generation then
      local cached=cacheFor(key)
      if list and not errorKey then
        stations,hasMore,offsets=list,moreResult,nextOffsets
        saveCache(key,list,hasMore,offsets);notice=nil
      else
        if list then stations=list
        elseif key~=previousKey then stations=cached and restoreRows(cached.items,LIMIT) or {} end
        hasMore,offsets=cached and cached.more==true or false,cached and cached.offsets or nil
        notice="list_failed"
      end
      previousKey=key
      holdBack();render();startTester()
    end
    more=false
  until not again or stopped
  loading=false
end
local function nextToTest()
  local clock=now()
  if not clock then return nil end
  local best,score,seen=nil,nil,{}
  for boost,list in ipairs({stations,favorites}) do
    for index,item in ipairs(list) do
      if item.directory and not seen[item.url] then
        seen[item.url]=true
        local rank=health.due(records[recordKey(item)],clock,(tonumber(tos.config.recheck) or 12)*3600000)
        if rank and not (current and current.url==item.url and tos.state.get("media.owner")=="radio") and
          not (pendingFavorite and pendingFavorite.item.url==item.url) then
          local candidate=rank*100000+boost*1000+index
          if not score or candidate<score then best,score=item,candidate end
        end
      end
    end
  end
  return best
end
local function testOne()
  if testing or stopped or attention=="ASLEEP" or tos.config.test==false or not tos.media.probe then return end
  local status=tos.state.get("media.status")
  if status=="buffering" or status=="reconnecting" then return end
  local item=nextToTest()
  if not item then return end
  testing=true
  local mine=generation
  local ok=pcall(function()
    local result=tos.media.probe(item.url,probeOptions(item))
    if not stopped and mine==generation then recordTest(item,result) end
  end)
  testing=false
  if not ok then return end
end
startTester=function()
  if testTimer then tos.timer.cancel(testTimer);testTimer=nil end
  if tos.config.test==false then return end
  local available=directory()
  for _,item in ipairs(favorites) do if item.directory then available=true;break end end
  if available then testTimer=tos.timer.every(tonumber(tos.config.test_gap) or 10,testOne) end
end
tos.on("start",function()
  for _,key in ipairs({"favorites","directory_cache","health"}) do stored[key]=tos.store.get(key) end
  favorites=restoreRows(restore("favorites"),FAVORITES)
  local saved=restore("directory_cache")
  if saved and saved.v==1 and type(saved.entries)=="table" then
    for i,item in ipairs(saved.entries) do
      if i>4 then break end
      if type(item)=="table" and type(item.key)=="string" and #item.key<=4096 then
        local validOffsets=type(item.offsets)=="table" and #item.offsets<=3
        if validOffsets then for _,value in ipairs(item.offsets) do
          if type(value)~="number" or value%1~=0 or value<0 or value>10000 then validOffsets=false;break end
        end end
        caches[#caches+1]={key=item.key,items=restoreRows(item.items,LIMIT),more=item.more==true and validOffsets,
          offsets=validOffsets and item.offsets or nil}
      end
    end
  end
  local ledger=restore("health")
  if ledger then
    for key,item in pairs(ledger) do
      if type(key)=="string" and key:match("^%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x%x$") and type(item)=="table" and
        type(item.t)=="number" and item.t>=0 and (item.u==nil or type(item.u)=="number") and
        (item.f==nil or type(item.f)=="number" and item.f>=0 and item.f<=20) and
        (item.r==nil or type(item.r)=="number" and item.r>=0 and item.r<=604800000) then
        records[key]={t=item.t,u=item.u,f=item.f,r=item.r,x=item.x==true or nil,k=item.k=="hls" and "hls" or nil}
      end
    end
    health.trim(records)
  end
  local cached=cacheFor(sourceKey())
  if cached then stations=restoreRows(cached.items,LIMIT);hasMore=cached.more;offsets=cached.offsets;previousKey=sourceKey() end
  render();startTester()
end)
tos.on("page.enter",function(page)
  if page~="stations" then return end
  holdBack()
  if #stations==0 or previousKey~=sourceKey() then refresh() else render() end
end)
tos.on("config",function()
  if not directory() and category and categories.get(category) then category=nil end
  if previousKey~=sourceKey() then refresh() else startTester();render() end
end)
tos.on("data",function(name) if name=="onboarding" and directory() and previousKey~=sourceKey() then refresh() end end)
tos.on("locale",render)
tos.on("attention",function(level) attention=level end)
tos.on("back",function()
  if category then category=nil;tab=3;holdBack();if directory() then refresh() else render() end end
end)
tos.on("tap",function(name,index)
  if name=="tab" and (index==1 or index==2 or index==3) then
    if index==2 and category then category=nil;tab=2;holdBack();if directory() then refresh() else render() end
    else tab=index;render() end
  elseif name=="refresh" then refresh()
  elseif name=="play" and type(index)=="number" and index%1==0 and visible[index] then
    local item=visible[index]
    if item.more then if not loading then refresh(true) end
    elseif item.category then category=item.category;tab=2;holdBack();if directory() then refresh() else render() end
    else play(item,true) end
  elseif name=="favorite" and current then
    local before={}
    for i,item in ipairs(favorites) do before[i]=item end
    local at=favorite(current.url)
    if at then table.remove(favorites,at)
    else table.insert(favorites,1,current);if #favorites>FAVORITES then table.remove(favorites) end end
    if not saveFavorites() then favorites=before;notice="favorite_failed" end
    render();startTester()
  end
end)
local function terminal(info)
  if not wanted or not current or tos.state.get("media.owner")~="radio" then return end
  if pendingFavorite then return end -- An old A error cannot replace the user's pending B request.
  if context and not context.favorite then
    local ctx,item,mine=context,current,playSequence
    local error=({http="http",network="network",policy="policy",codec="unsupported",format="format"})[info.category] or "format"
    recordTest(item,{ok=false,error=error})
    if mine~=playSequence or context~=ctx then return end
    if info.category=="policy" then ctx.policy=true end
    advance(ctx,mine)
  elseif info.category=="policy" then cancelRetry();notice=current.directory and "directory_policy" or "policy";render()
  elseif info.category=="network" or info.category=="ended" or (info.category=="http" and
    (info.status==408 or info.status==429 or (tonumber(info.status) or 0)>=500)) then retry()
  else cancelRetry();notice="play_failed";render() end
end
tos.on("queue.end",function() terminal({category="ended"}) end)
tos.on("media.error",terminal)
tos.on("media.session",function()
  wanted=false;playSequence=playSequence+1;context=nil;pendingFavorite=nil;cancelRetry();render()
end)
tos.on("stop",function()
  stopped=true;wanted=false;pendingFavorite=nil;generation=generation+1;playSequence=playSequence+1;cancelRetry()
  if testTimer then tos.timer.cancel(testTimer);testTimer=nil end
end)
