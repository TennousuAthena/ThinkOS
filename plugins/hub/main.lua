-- The OS owns the signed catalog, downloads and native permission confirmation.
local visible, tab, offset, selected = false, 1, 0, nil
local generation, loading, dirty = 0, false, false
local forceRequested=false
local catalog, installed, byId, candidates = {}, {}, {}, {}
local configured, refreshing, expired, total = false, false, false, 0
local notice, busy, rows = nil, false, {}
local PAGE_SIZE=20 -- Bound Lua metadata/state well below the catalog API's 50-item maximum.
local render, refresh
local function t(key) return tos.i18n.t(key) end
local function array() return tos.json.decode("[]") end
local function text(value, limit)
  if type(value) ~= "string" then return "" end
  value=value:gsub("%c", " ")
  local edge=utf8.offset(value,(limit or 120)+1)
  return edge and value:sub(1,edge-1).."…" or value
end
local function row(items,title,subtitle,action,id,trailing)
  items[#items+1]={title=text(title),subtitle=text(subtitle),trailing=text(trailing,40)}
  rows[#rows+1]={action=action,id=id}
end
local function entry(id) return candidates[id] or byId[id] end
local function reason(value)
  if type(value)=="string" then return value end
  if type(value)~="table" then return "" end
  local locale=tos.locale()
  local tag=locale.language=="zh" and ((locale.script=="Hant" or locale.region=="TW" or locale.region=="HK") and "zh-Hant" or "zh-Hans") or locale.language
  return value[tag] or value.en or value["zh-Hans"] or ""
end
local function size(bytes)
  if type(bytes)~="number" or bytes<0 then return "" end
  return string.format(bytes>=1048576 and "%.1f MiB" or "%.0f KiB",bytes/(bytes>=1048576 and 1048576 or 1024))
end
local function message(err)
  if err=="cancelled" or err=="canceled" then return nil end
  return t("action.failed")
end
local function detail(items,id)
  local value=entry(id)
  if not value then selected=nil;return false end
  local own=byId[id]
  row(items,value.name or id,value.summary,nil,nil,t("tier."..(value.tier or own and own.tier or "reviewed")))
  if value.version then row(items,t("version"),value.version) end
  if own and value.version~=own.version then row(items,t("installedVersion"),own.version) end
  local repo=value.repo or own and own.repo or ""
  local commit=value.commit or own and own.commit
  row(items,t("source"),repo..(commit and "@"..commit or ""))
  if value.license then row(items,t("license"),value.license) end
  if value.size then row(items,t("size"),size(value.size)) end
  if own and own.enabled==false and own.error and not own.revoked then row(items,t("action.failed"),own.error) end
  for _, permission in ipairs(value.permissions or {}) do row(items,t("permission."..permission),reason((value.why or {})[permission])) end
  if value.revoked or own and own.revoked then row(items,t("revoked"),value.error or own and own.error)
  elseif not own then row(items,t("install"),"","install",id)
  else
    if own.enabled~=false then row(items,t("open"),"","open",id) end
    if value.update or own.update then row(items,t("update"),own.available_version or value.version,"update",id) end
  end
  if own then
    row(items,t("auto"),t(own.auto and "on" or "off"),"auto",id)
    row(items,t("uninstall"),"","uninstall",id)
  end
  row(items,t("back"),"","back")
  return true
end
render=function()
  local items=array();rows={}
  local showing=selected and detail(items,selected)
  if not showing then
    if tab==1 then
      for _, value in ipairs(catalog) do
        local own=byId[value.id]
        row(items,value.name or value.id,value.summary,"detail",value.id,
          value.revoked and t("revoked") or own and t("installed") or t("tier."..(value.tier or "reviewed")))
      end
      if offset>0 then row(items,t("previousPage"),"","previous") end
      if offset+#catalog<total then row(items,t("nextPage"),"","next") end
      if not configured then row(items,t("catalog.settings"),"","settings") end
    elseif tab==2 then
      for _, value in ipairs(installed) do row(items,value.name or value.id,value.version,"detail",value.id,value.revoked and t("revoked") or value.auto and t("auto") or "") end
    else
      local status=tos.update.status()
      row(items,t("systemUpdate"),type(status.result)=="table" and status.result.message or status.version or status.current_version,"system")
      for _, value in ipairs(installed) do
        if value.update or value.revoked then row(items,value.name or value.id,value.available_version or value.version,"detail",value.id,value.revoked and t("revoked") or t("update")) end
      end
    end
  end
  local footer=notice or (busy and t("working") or refreshing and t("refreshing") or expired and t("catalog.expired") or "")
  if footer=="" and not showing then
    if tab==1 and not configured then footer=t("catalog.unconfigured")
    elseif tab==1 and #catalog==0 then footer=t("catalog.empty")
    elseif tab==2 and #installed==0 then footer=t("installed.empty")
    else footer=t("refresh") end
  end
  tos.back.intercept(showing==true)
  tos.state.set{tabs={t("tab.discover"),t("tab.installed"),t("tab.updates")},tab=tab,items=items,footer=footer}
end
refresh=function(force)
  if force then forceRequested=true end
  generation=generation+1;dirty=true
  if loading then return end
  loading=true
  repeat
    dirty=false
    local mine=generation
    local forced=forceRequested;forceRequested=false
    local result=tos.plugins.installed() or {}
    if visible and mine==generation then
      installed=result;byId={}
      for _, value in ipairs(installed) do byId[value.id]=value end
    end
    if tab==1 or selected or forced then
      local result,err=tos.plugins.catalog{offset=selected and 0 or offset,limit=PAGE_SIZE,query=selected or "",refresh=forced}
      if visible and mine==generation then
        if result then
          configured=result.configured==true;refreshing=result.refreshing==true;expired=result.expired==true
          if not selected then catalog=result.items or {};total=result.total or #catalog;candidates={} end
          for _, value in ipairs(result.items or {}) do
            if not selected or value.id==selected then candidates[value.id]=value end
          end
          if not configured then notice=t("catalog.unconfigured")
          else notice=type(result.error)=="string" and result.error~="" and t("catalog.failed") or nil end
        else notice=err and t("catalog.failed") or nil end
      end
    end
    if visible and mine==generation then render() end
  until not dirty or not visible
  loading=false
end
local function changeTab(index)
  if type(index)~="number" or index%1~=0 or index<1 or index>3 then return end
  tab=index;selected=nil;notice=nil;offset=0;refresh()
end
local function mutate(action,id)
  if busy then return end
  busy=true;notice=nil;render()
  local ok,err
  if action=="auto" then ok,err=tos.plugins.setAuto(id,not (byId[id] or {}).auto)
  elseif action=="open" then ok,err=tos.plugins.open(id)
  elseif action=="install" then ok,err=tos.plugins.install(id)
  elseif action=="update" then ok,err=tos.plugins.update(id)
  elseif action=="uninstall" then ok,err=tos.plugins.uninstall(id) end
  busy=false
  if not visible then return end
  if ok and action=="uninstall" then selected=nil end
  refresh()
  if not ok then notice=message(err);render() end
end
tos.on("page.enter",function() visible=true;notice=nil;refresh() end)
tos.on("page.leave",function() visible=false;dirty=false;generation=generation+1 end)
tos.on("plugins",function() if visible then refresh() end end)
tos.on("update",function() if visible and tab==3 then render() end end)
tos.on("locale",function() if visible then refresh() end end)
tos.on("back",function() if selected then selected=nil;notice=nil;render() end end)
tos.on("tap",function(name,index)
  if name=="tab" then changeTab(index);return end
  if name=="footer" then if not selected then refresh(true) end;return end
  if name~="row" or type(index)~="number" or index%1~=0 then return end
  local value=rows[index]
  if not value then return end
  local action=value.action
  if action=="detail" then selected=value.id;notice=nil;render();refresh()
  elseif action=="back" then selected=nil;notice=nil;render()
  elseif action=="next" then offset=offset+PAGE_SIZE;refresh()
  elseif action=="previous" then offset=math.max(0,offset-PAGE_SIZE);refresh()
  elseif action=="settings" then tos.settings.open("hub")
  elseif action=="system" then local ok,err=tos.update.open();if not ok and visible then notice=message(err);render() end
  elseif action then mutate(action,value.id) end
end)
tos.on("stop",function() visible=false;dirty=false;generation=generation+1 end)
