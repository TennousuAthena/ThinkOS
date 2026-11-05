-- Exercise the factory settings script through public descriptors, including related scopes.
assert(_VERSION == "Lua 5.4")
local handlers, state, intercepted, resets, writes, opened = {}, nil, false, {}, {}, {}
local setupData={pending=false,skipped={}}
local updateStatus={silent_install=true}
local sections = {
  {id="display",label="Display",icon="monitor"}, {id="location",label="Location",icon="map-pin"},
  {id="desktop",label="Desktop",icon="house"}, {id="media",label="Media",icon="cast"},
  {id="system",label="System",icon="sliders-horizontal"}, {id="plugins",label="Plugins",icon="puzzle"},
}
local defaults = {
  system = { ["display.delay"]=30, ["display.idle"]=5, ["location.mode"]="auto", ["location.name"]="",
             ["location.latitude"]=0, ["location.longitude"]=0, ["location.elevation"]=0, ["location.timezone"]="",
             ["units.system"]="metric", ["units.wind"]="auto", ["media.public_http"]=false },
  weather = { interval=60, endpoint="default", retry=1, about="old" },
}
local values = {system={}, weather={}}
for scope, fields in pairs(defaults) do for key,value in pairs(fields) do values[scope][key]=value end end
local function row(scope,key,kind,level,section,source)
  local overridden = values[scope][key] ~= defaults[scope][key]
  if source=="place" then
    for _,part in ipairs {"mode","name","latitude","longitude","elevation","timezone"} do
      if values.system["location."..part]~=defaults.system["location."..part] then overridden=true end
    end
  end
  return {scope=scope,key=key,type=kind,label=key,value=values[scope][key],default=defaults[scope][key],
          level=level or "normal",section=section,source=source,overridden=overridden}
end
local descriptorReads = 0
local function get(scope)
  if scope=="missing" then return nil end
  descriptorReads = descriptorReads + 1
  local fields={}
  if scope=="system" then
    fields={row("system","display.delay","number",nil,"display"),row("system","display.idle","number","advanced","display"),
            row("system","location.name","choice",nil,"location","place"),row("system","units.system","choice",nil,"location"),
            row("system","units.wind","choice",nil,"location"),row("system","media.public_http","bool",nil,"system")}
  elseif scope=="weather" then
    fields={row("system","location.name","choice",nil,"location","place"),row("system","units.system","choice",nil,"location"),
            row("system","units.wind","choice",nil,"location"),row("weather","interval","number"),
            row("weather","about","text"),row("weather","endpoint","text","advanced")}
  end
  return {scope=scope,name=scope,sections=sections,fields=fields}
end
local many=0
tos={
  on=function(name,fn) handlers[name]=fn end,
  data={get=function(alias) assert(alias=="onboarding");return setupData end},
  onboarding={open=function(mode) opened[#opened+1]="onboarding:"..mode end},
  apps={open=function(id) opened[#opened+1]="app:"..id;return true end},
  back={intercept=function(on) intercepted=on end},
  state={set=function(next) state=next end},
  i18n={t=function(key,params) return key=="components.installed" and key..":"..params.count or key end},
  timer={after=function() return 1 end,cancel=function() end},
  device={openAndroid=function(target) opened[#opened+1]=target end},
  update={status=function() return updateStatus end, open=function() opened[#opened+1]="updates"; return true end},
  settings={
    get=get,
    list=function() return {{scope="system",name="System"},{scope="weather",name="Weather"},{scope="tiles",name="Tiles"}} end,
    set=function(scope,key,value)
      assert(defaults[scope] and defaults[scope][key]~=nil,"edit must use the setting's real scope")
      values[scope][key]=value;writes[#writes+1]={scope,key,value};return value
    end,
    setMany=function(scope,patch)
      assert(scope=="system");many=many+1
      for key,value in pairs(patch) do assert(defaults.system[key]~=nil);values.system[key]=value end
      return patch
    end,
    reset=function(scope,key)
      assert(key,"a page must not reset unseen settings in its whole scope")
      assert(defaults[scope][key]~=nil);values[scope][key]=defaults[scope][key];resets[#resets+1]={scope,key}
    end,
    about=function() opened[#opened+1]="about" end,
  },
}
local function emit(name,...)
  descriptorReads = 0
  assert(handlers[name],name)(...)
  assert(descriptorReads <= 1, "an event must not rebuild and transport the full descriptor twice")
end
local function has(key) for _,field in ipairs(state.fields) do if field.key==key then return field end end end
local function pass(message) print("PASS "..message) end
assert(loadfile(assert(arg[1],"pass plugins/settings/main.lua")))()
emit("page.enter","form","",{scope="system"})
assert(#state.fields==6 and not intercepted and state.title=="title")
for i,section in ipairs(sections) do assert(state.fields[i].key=="section:"..section.id and state.fields[i].icon==section.icon) end
local locationIcon=sections[2].icon
sections[2].icon=nil;emit("locale")
for _,field in ipairs(state.fields) do assert(field.icon==nil,"partial category icons must not leave placeholders") end
sections[2].icon=locationIcon;emit("locale")
assert(state.nav.items[2].icon==locationIcon and state.fields[2].icon==locationIcon)
pass("root links and the sidebar carry the same complete vector set; missing icons degrade as one group")
emit("form.change","section:display",true)
assert(intercepted and has("display.delay") and not has("display.idle") and has("level:advanced"))
emit("form.change","level:advanced",true)
assert(has("display.idle") and not has("display.delay") and state.title=="Display  advanced")
emit("back");assert(has("display.delay") and state.title=="Display")
emit("back");assert(not intercepted and #state.fields==6)
pass("system category and advanced pages return one level at a time")

emit("form.change","section:plugins",true)
assert(has("scope:weather") and has("scope:tiles") and not has("scope:system"))
emit("form.change","scope:weather",true)
assert(state.fields[1].key=="location.name" and state.fields[2].key=="units.system" and has("interval"))
for _,field in ipairs(state.fields) do assert(field.icon==nil,"plugin settings and action links stay undecorated") end
emit("form.change","units.system","imperial")
assert(values.system["units.system"]=="imperial" and values.weather["units.system"]==nil)
emit("form.change","interval",120);assert(values.weather.interval==120)
emit("form.change","about","custom");assert(values.weather.about=="custom" and #opened==0)
pass("related keys edit system values; legacy plain keys remain plugin values")

emit("form.change","level:advanced",true)
assert(has("endpoint") and not has("interval") and not has("units.system"))
emit("form.change","endpoint","custom")
emit("form.change","reset:page",true)
assert(values.weather.endpoint=="default" and values.weather.interval==120 and values.system["units.system"]=="imperial")
assert(not has("reset:page"))
pass("advanced reset preserves values on other pages and in related scopes")

emit("form.change","endpoint","keep-advanced")
emit("back")
emit("form.change","location.name",{mode="manual",name="Place",latitude=1,longitude=2,elevation=3,timezone=""})
assert(many==1 and values.system["location.name"]=="Place" and values.system["location.latitude"]==1)
values.system["display.delay"]=55;values.weather.retry=99
emit("form.change","reset:page",true)
for _,part in ipairs {"mode","name","latitude","longitude","elevation","timezone"} do
  assert(values.system["location."..part]==defaults.system["location."..part])
end
assert(values.weather.interval==60 and values.weather.endpoint=="keep-advanced" and values.weather.retry==99)
assert(values.system["display.delay"]==55 and values.system["units.system"]=="metric")
pass("place writes are compound; page reset clears all six place keys and retains unseen fields")

emit("form.change","scope:system",true);assert(not intercepted and #state.fields==6)
emit("form.change","section:system",true)
emit("form.change","action:about",true);emit("form.change","action:android",true)
assert(opened[1]=="about" and opened[2]=="settings")
pass("system actions use public about and Android capabilities")
emit("form.change","action:updates",true);assert(opened[3]=="updates")
pass("system update entry uses the public update capability")
assert(has("action:components").display=="components.unavailable")
local beforeComponentWrites=#writes
emit("form.change","action:components",true);assert(opened[4]=="updates" and #writes==beforeComponentWrites)
updateStatus.inventory={updater="read_only",modules={{id="timesync",installed_code=1},{id="thinkos-cast",installed_code=0}}}
emit("update");assert(has("action:components").display=="components.installed:1")
updateStatus.inventory.modules[2].installed_code=-1
emit("update");assert(has("action:components").display=="components.unknown")
updateStatus.inventory={updater="unavailable",modules={{id="timesync",installed_code=999}}}
emit("update");assert(has("action:components").display=="components.unavailable")
updateStatus.inventory=true
emit("update");assert(has("action:components").display=="components.unavailable")
updateStatus.inventory=nil
pass("system inventory entry survives missing sources, distinguishes unknown builds and opens only the public native update UI")
emit("form.change","action:hub",true);assert(opened[5]=="app:hub.main")
pass("the hub action uses the public application page key")
emit("page.enter","form","",{scope="missing"})
assert(not intercepted and #state.fields==6)
pass("a removed plugin scope falls back to system categories")
emit("form.change","section:missing",true)
assert(not intercepted and #state.fields==6)
pass("an invalid category reuses its existing description and returns to system categories")

emit("form.layout",true)
assert(state.nav.current=="display" and has("display.delay") and not intercepted)
emit("form.navigate","desktop")
emit("form.navigate","display")
assert(not intercepted and has("display.delay"))
emit("form.change","level:advanced",true)
assert(intercepted and has("display.idle"))
emit("back");assert(not intercepted and has("display.delay"))
pass("wide sidebar replaces categories while advanced pages retain Back")

emit("form.navigate","plugins")
emit("form.change","scope:weather",true)
assert(intercepted and state.nav.current=="plugins" and has("interval"))
emit("form.layout",false)
assert(intercepted and has("interval"))
emit("back");assert(has("scope:weather") and state.nav.current=="plugins")
emit("back");assert(not intercepted and #state.fields==6)
pass("wide-to-narrow rotation preserves the plugin and restores portrait category Back")

emit("form.change","section:display",true)
emit("form.change","level:advanced",true)
emit("form.layout",true)
assert(intercepted and has("display.idle") and state.nav.current=="display")
emit("back");assert(not intercepted and has("display.delay"))
emit("form.layout",false)
assert(intercepted and has("display.delay"))
emit("back");assert(not intercepted and #state.fields==6)
pass("rotation in an advanced category keeps its fields without duplicate return entries")

emit("form.layout",true)
emit("page.enter","form","",{scope="weather"})
assert(not intercepted and has("interval") and state.nav.current=="plugins")
emit("form.change","level:advanced",true)
emit("back");assert(not intercepted and has("interval"))
emit("form.navigate","missing")
assert(has("interval"))
emit("form.layout",false)
assert(not intercepted and has("interval"))
pass("direct plugin entry and invalid sidebar targets preserve the original exit boundary")

emit("page.enter","form","",{scope="system"})
setupData={pending=true,skipped={"location"}}
emit("data","onboarding")
assert(state.fields[1].key=="action:completeSetup")
emit("form.change","action:completeSetup",true)
assert(opened[#opened]=="onboarding:resume")
setupData={pending=false,skipped={}}
emit("data","onboarding")
assert(not has("action:completeSetup"))
emit("form.change","section:system",true)
assert(has("action:onboarding"))
emit("form.change","action:onboarding",true)
assert(opened[#opened]=="onboarding:restart")
pass("public skipped-step data controls Finish setup; system restart uses the public onboarding capability")
