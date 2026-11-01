package moe.kiwi.thinkos.plugin

/** Small trusted adapter. Values cross JNI as typed objects; JSON runs in budgeted C. */
internal object LuaBootstrap {
    val code = """
local host, await, json, originalSetmetatable, protected = _host, _await, _json, setmetatable, pcall
_host, _await, _json = nil, nil, nil
local readonly = originalSetmetatable({}, {__mode="k"})
local originalRawset = rawset
rawset = function(t,k,v)
  if readonly[t] then error("read-only configuration") end
  return originalRawset(t,k,v)
end
setmetatable = function(t,m)
  if readonly[t] then error("read-only configuration") end
  if m == nil then return originalSetmetatable(t,nil) end
  local safe = {}
  for k,v in pairs(m) do
    if k == "__gc" or k == "__close" then error("finalizers are unavailable") end
    safe[k]=v
  end
  safe.__metatable = false
  return originalSetmetatable(t,safe)
end
getmetatable = nil
local handlers,timers={},{}
local function immutable(t)
  if type(t)~="table" then return t end
  local values={}; for k,v in pairs(t) do values[k]=immutable(v) end
  local proxy={}; readonly[proxy]=true
  return originalSetmetatable(proxy,{__index=values,__newindex=function() error("read-only configuration") end,__metatable=false,__pairs=function() local k; return function() local v; k,v=next(values,k); return k,v end,nil,nil end})
end
tos={config=immutable(host("config")),json=json}
tos.on=function(name,fn) assert(type(name)=="string" and type(fn)=="function"); handlers[name]=fn end
tos.state={set=function(t) assert(type(t)=="table" or type(t)=="string"); host("state.set",t) end,get=function(path) return host("state.get",path or "") end}
tos.store={get=function(k) return host("store.get",k) end,set=function(k,v) return host("store.set",k,v) end}
tos.time={now=function() return host("time.now") end,format=function(ms,fmt,tz) if ms==nil then return "—" end; return host("time.format",ms,fmt or "@time",tz) end}
tos.tz={home=function() return host("tz.home") end,info=function(id) return host("tz.info",id) end}
tos.location={get=function() return host("location.get") end}
local function astro(method,ms,place) local value,err=await(method,ms,place); if err then error(err) end; return value end
tos.astro={sun=function(ms,place) return astro("astro.sun",ms,place) end,moon=function(ms,place) return astro("astro.moon",ms,place) end,seasons=function(year) return host("astro.seasons",year) end}
tos.locale=function() return host("locale") end
tos.units={get=function() return host("units.get") end,temperature=function(celsius) return host("units.temperature",celsius) end,wind=function(kmh) return host("units.wind",kmh) end}
local nextTimer=0
local function timer(seconds,fn,repeating,opts)
  assert(type(fn)=="function"); nextTimer=nextTimer+1; timers[nextTimer]=fn
  host("timer.add",nextTimer,seconds,repeating,opts and opts.align==true)
  return nextTimer
end
tos.timer={after=function(s,fn) return timer(s,fn,false) end,every=function(s,fn,opts) return timer(s,fn,true,opts) end,cancel=function(id) timers[id]=nil; host("timer.cancel",id) end,sleep=function(s) return await("sleep",s) end}
tos.http={get=function(url,opts) return await("http.get",url,opts) end,post=function(url,body,opts) return await("http.post",url,body,opts) end}
tos.media={load=function(queue,opts) return await("media.load",queue,opts) end}
for _,method in ipairs({'play','pause','toggle','next','prev','seek','volume','stop','shuffle','repeat','jump','live','audioTrack','textTrack'}) do
  tos.media[method]=function(...) return await("media."..method,...) end
end
-- `repeat` is a Lua keyword; both media["repeat"](mode) and media.repeatMode(mode) are available.
tos.media.repeatMode=tos.media["repeat"]
tos.library={list=function(opts) local text,err=await("library.list",opts); if err then return nil,err end; return json.decode(text) end}
tos.settings={
  list=function() return host("settings.list") end,
  get=function(scope) return host("settings.get",scope) end,
  set=function(scope,key,value) local r=host("settings.set",scope,key,value); if r.error then return nil,r.error end; return r.value end,
  setMany=function(scope,values) local r=host("settings.setMany",scope,values); if r.error then return nil,r.error end; return r.value end,
  reset=function(scope,key) return host("settings.reset",scope,key) end,
}
tos.data={get=function(alias) assert(type(alias)=="string"); local r=host("data.get",alias); if r.error then return nil,r.error end; local ok,value=protected(json.decode,r.text); if not ok then return nil,"invalid" end; return value end}
tos.command={call=function(name,args) assert(type(name)=="string"); local r=host("command.call",name,args); if r.error then return nil,r.error end; return true end}
tos.tile={animate=function(name) assert(type(name)=="string"); local r=host("tile.animate",name); if r.error then return nil,r.error end; return true end}
tos.i18n={t=function(key,params) return host("i18n.t",key,params) end}
tos.page={focus=function(page,opts) host("page.focus",page,opts and opts.live==true) end,release=function(page) host("page.release",page) end}
tos.log={}
for _,level in ipairs({'info','warn','error'}) do
  tos.log[level]=function(...) local a={}; for i=1,select('#',...) do a[i]=tostring(select(i,...)) end; host("log",level,table.concat(a,' ')) end
end
local modules={}
require=function(name)
  assert(type(name)=="string" and #name<=128 and name:match('^[%w_%-%.]+$') and not name:find('..',1,true),"invalid module name")
  if modules[name]~=nil then return modules[name] end
  local source=host("module",name)
  local fn,err=load(source,'@'..name,'t'); if not fn then error(err) end
  modules[name]=true
  local result=fn(); if result~=nil then modules[name]=result end
  return modules[name]
end
_dispatch=function(name,...)
  local fn=handlers[name]
  if name=="config" then local c=immutable((...)); tos.config=c; if fn then return fn(c) end; return end
  if fn then return fn(...) end
end
_timer=function(id,repeating) local fn=timers[id]; if not repeating then timers[id]=nil end; if fn then return fn() end end
""".trimIndent()
}
