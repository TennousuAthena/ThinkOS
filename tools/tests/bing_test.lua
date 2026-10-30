local Bing=assert(loadfile(arg[1]))()
package.preload["sources.bing"]=function() return Bing end
local source=assert(arg[2])
local endpoint="https://www.bing.com/HPImageArchive.aspx"
local function photo(name,date,title,wp)
  return {urlbase="/th?id=OHR."..name.."_ZH-CN1234567890",fullstartdate=date or "202610291600",title=title or name,copyright="A place (© Author)",wp=wp}
end
assert(Bing.market({language="zh",region="CN"},"auto")=="zh-CN")
assert(Bing.market({language="zh",region="TW",script="Hant"},"auto")=="en-US")
assert(Bing.market({language="ur",region="PK"},"auto")=="en-US")
assert(Bing.market({language="en",region="GB"},"auto")=="en-GB")
assert(Bing.market({language="ar"},"fr-FR")=="fr-FR")
assert(Bing.url(endpoint,"zh-CN",7)==endpoint.."?format=js&idx=7&n=8&mkt=zh-CN")
for _,url in ipairs({"https://user@bing.com/api","https://bing.com/api?x=y","https://bing.com/api#x","file:///api"}) do assert(not pcall(Bing.endpoint,url)) end
local items=Bing.parse({images={photo("A",nil,"Info"),photo("B",nil,nil,false),{urlbase="https://evil.invalid/a"}}},"https://www.bing.com",true)
assert(#items==1 and items[1].id=="OHR.A" and items[1].title=="A place" and items[1].credit=="A place (© Author)")
assert(items[1].src=="https://www.bing.com/th?id=OHR.A_ZH-CN1234567890_1920x1080.jpg")
assert(#Bing.parse({images={photo("B",nil,nil,false)}},"https://www.bing.com",false)==1)
local replaced=Bing.parse({images={photo("A","202610301600","New title"),photo("C","202610281600")}},"https://www.bing.com",true)
local merged=Bing.merge(items,replaced,2); assert(#merged==2 and merged[1].title=="New title" and merged[2].id=="OHR.C")
assert(#Bing.restore(merged,"https://www.bing.com",30,true)==2)
assert(#Bing.restore(merged,"https://evil.invalid",30,true)==0)
local huge={}; for i=1,90 do huge[i]={id=tostring(i),src="https://bing.com/"..i,title=string.rep("汉",512),credit=string.rep("字",512),stamp=tostring(i)} end
assert(#Bing.merge({},huge,90)<90)
print("PASS Bing market mapping, URL validation, archive normalization, credits, deduplication and persistence budget")

local function clone(v) if type(v)~="table" then return v end; local r={}; for k,x in pairs(v) do r[k]=clone(x) end; return r end
local encoded,sequence={},0
local function encode(v) sequence=sequence+1; local k="json:"..sequence; encoded[k]=clone(v); return k end
local function decode(v) if v=="[]" then return {} end; return clone(assert(encoded[v],"Invalid JSON")) end
local function fixture(saved,overrides)
  local f={handlers={},timers={},calls={},saved=saved or {},states={},now=1793347200000,locale={language="zh",region="CN"}}
  local config={source="bing",market="auto",endpoint=endpoint,history=30,wallpaper_only=true,refresh_hours="6",retry_initial=60,retry_max=900,clock_retry=30,local_dir="",scan_seconds=300}
  for k,v in pairs(overrides or {}) do config[k]=v end
  tos={config=config,json={encode=encode,decode=decode},locale=function() return f.locale end,time={now=function() return f.now end},
    on=function(n,fn) f.handlers[n]=fn end,i18n={t=function(k) return k end},
    state={set=function(v) f.state=decode(v); f.states[#f.states+1]=f.state end},
    store={get=function(k) return f.saved[k] end,set=function(k,v) f.saved[k]=v end},
    log={info=function() end,warn=function() end},
    timer={after=function(s,fn) local n=#f.timers+1; f.timers[n]={seconds=s,fn=fn}; f.delay=s; return n end,cancel=function(n) f.timers[n]=false end},
    http={get=function(url)
      f.calls[#f.calls+1]=url
      local name,title=f.name or "A",f.title
      if f.suspend then coroutine.yield() end
      if f.fail or f.failSecond and url:find("idx=7",1,true) then return nil,"offline" end
      return {status=200,body=encode({images={photo(name,nil,title)}})}
    end},library={list=function() if f.suspendLocal then coroutine.yield() end; return {items={{src="file:///local.jpg",title="Local"}},directory="/private/Pictures"} end}}
  assert(loadfile(source))()
  function f.event(n,...) return f.handlers[n](...) end
  function f.fire() for n,v in ipairs(f.timers) do if v then f.timers[n]=false; v.fn(); return end end; error("No timer") end
  function f.config(values) for k,v in pairs(values) do config[k]=v end; f.event("config") end
  f.event("start"); return f
end
local f=fixture(); assert(#f.calls==0 and f.state.notice=="loading")
f.fire(); assert(#f.calls==2 and #f.state.photos==1 and f.state.coverCredit=="A place (© Author)")
assert(f.calls[1]:find("mkt=zh-CN",1,true) and f.calls[2]:find("idx=7",1,true))
f.fire(); assert(#f.calls==2, "fresh metadata must respect refresh interval")
f.fail=true; f.event("command","refresh"); assert(f.state.notice=="offline" and #f.state.photos==1 and f.delay==60)
f.fire(); assert(#f.calls==4 and f.delay==120,"forced refresh failure must retry even inside fresh TTL")
f.fail=false; f.fire(); assert(f.state.notice=="" and f.delay==21600)
f.event("attention","ASLEEP"); local calls=#f.calls; f.event("command","refresh"); assert(#f.calls==calls)
f.event("attention","AWAKE"); f.event("stop"); f.event("command","refresh"); assert(#f.calls==calls)
local saved=f.saved
f=fixture(saved); assert(#f.state.photos==1 and #f.calls==0,"cached metadata must render before network")
f.config({source="all"}); f.fire(); assert(#f.state.photos==2)
f=fixture(); f.failSecond=true; f.fire(); assert(f.state.notice=="offline" and f.saved.bing,"first batch must persist independently")
local restored=fixture(f.saved); assert(#restored.state.photos==1)
f=fixture(); f.now=nil; f.fire(); assert(f.state.notice=="waiting_clock" and #f.calls==0 and f.delay==30)
f=fixture(); f.suspend=true; local job=coroutine.create(function() f.fire() end); assert(coroutine.resume(job))
f.config({market="en-US"}); f.name="Fresh"; f.suspend=false; assert(coroutine.resume(job))
assert(f.state.coverTitle=="Fresh" and f.calls[#f.calls]:find("mkt=en-US",1,true))
f.locale={language="ar"}; f.config({market="auto"}); f.fire(); assert(f.calls[#f.calls]:find("mkt=en-US",1,true))
print("PASS Bing startup, cache restore, TTL, forced retry, partial archive persistence, sleep, clock and stale response isolation")

f=fixture(); f.name="Old"; f.suspend=true
local old=coroutine.create(function() f.fire() end); assert(coroutine.resume(old))
f.suspendLocal=true
local change=coroutine.create(function() f.config({source="all",market="en-US"}) end); assert(coroutine.resume(change))
f.name="Fresh"; f.suspend=false; assert(coroutine.resume(old))
for _,state in ipairs(f.states) do for _,item in ipairs(state.photos) do assert(item.id~="OHR.Old", "old network generation escaped during a local scan") end end
f.suspendLocal=false; assert(coroutine.resume(change)); assert(#f.state.photos==2)
print("PASS simultaneous source and market changes revoke HTTP responses before local IO yields")
