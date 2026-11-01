-- Run with the native test CLI. No network, files, or Android-specific plugin shortcuts.
local source=assert(arg[1])
local function fixture()
  local f={handlers={},timers={},scans=0,logs={},states={}}
  local config={local_dir="",scan_seconds=300}
  f.animations={}
  tos={config=config,tile={animate=function(name) f.animations[#f.animations+1]=name; return true end},json={decode=function(text) assert(text=="[]"); return {} end,encode=function(value) return value end},state={set=function(value) f.state=value; f.states[#f.states+1]=value end},
    i18n={t=function(key) return key end},on=function(name,fn) f.handlers[name]=fn end,
    log={warn=function(...) f.logs[#f.logs+1]={...} end,info=function() end},
    timer={after=function(seconds,fn) local n=#f.timers+1; f.timers[n]=fn; f.delay=seconds; return n end,cancel=function(n) f.timers[n]=false end},
    library={list=function(options)
      f.scans=f.scans+1; f.options=options
      if f.suspend then coroutine.yield() end
      return f.result,f.error
    end}}
  f.result={items={{src="file:///b.jpg",title="B"},{src="file:///a.jpg",title="A"}},directory="/private/Pictures"}
  assert(loadfile(source))()
  function f.event(name,...) return f.handlers[name](...) end
  function f.fire() for n,fn in ipairs(f.timers) do if fn then f.timers[n]=false; fn(); return end end; error("No pending timer") end
  function f.config(values) for k,v in pairs(values) do config[k]=v end; f.event("config") end
  f.event("start"); return f
end
local f=fixture(); assert(f.scans==0 and f.state.notice=="loading")
f.event("attention","AWAKE"); assert(f.delay==.1, "initial attention must not postpone the first scan")
f.fire(); assert(f.scans==1 and f.options.kind=="image" and f.options.dir==nil and f.options.recursive)
assert(#f.state.photos==2 and f.state.cover=="file:///a.jpg" and f.state.notice=="")
assert(#f.state.public.daily.photos==0 and f.state.public.daily.cover=="") -- Local paths are never exported.
f.result.items[1].title=string.rep("汉",256)
f.event("command","refresh"); assert(f.state.photos[2].title==string.rep("汉",256))
f.result.items[1].title="B"
local scanned=f.scans
f.config({interval=20}); assert(f.scans==scanned and #f.state.photos==2)
f.event("locale"); assert(f.state.coverTitle=="A")
f.result=nil; f.error="Denied"; f.event("command","refresh")
assert(#f.state.photos==2 and f.state.notice=="unavailable")
f.config({local_dir="/new"}); assert(#f.state.photos==0 and f.state.notice=="unavailable")
f.result={items={},directory="/new"}; f.event("command","refresh"); assert(f.state.notice=="empty")
f.result={items={},directory="/new",truncated=true}
for i=1,512 do f.result.items[i]={src="file:///"..i..".jpg",title=tostring(i)} end
f.event("command","refresh"); assert(#f.state.photos==300 and #f.logs>0)
for i=1,512 do f.result.items[i]={src="file:///"..string.rep("a",1900)..i..".jpg",title=string.rep("汉",256)} end
f.event("command","refresh"); assert(#f.state.photos>0 and #f.state.photos<300)
print("PASS gallery preserves UTF-8 titles and bounds state for long photo paths")
f.event("attention","ASLEEP"); local n=f.scans; f.event("command","refresh"); assert(f.scans==n)
f.event("attention","AWAKE"); assert(f.scans==n+1)
f.event("stop"); n=f.scans; f.event("command","refresh"); assert(f.scans==n)
print("PASS gallery local scan, sorted list, display settings, error retention, directory isolation, cap, sleep and stop")
-- A directory change while awaiting IO must discard the old generation's result.
f=fixture(); f.suspend=true
local job=coroutine.create(function() f.fire() end); assert(coroutine.resume(job))
f.config({local_dir="/changed"}); f.result={items={{src="file:///stale.jpg",title="Stale"}}}
f.suspend=false; assert(coroutine.resume(job))
assert(f.scans==2)
for i=1,#f.states-1 do assert(f.states[i].cover~="file:///stale.jpg") end
print("PASS gallery in-flight directory change discards stale response and rescans")
