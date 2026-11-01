-- Test harness only: the production module below still uses the same public tos.* contract.
local public, handlers = tos, {}
local cfg = {}; for k,v in pairs(public.config) do cfg[k]=v end
local root, route = "FIXTURE_ROOT", "/good"
local calls, writes, lastDelay, minute = 0, 0
local synced, placeOverride, timeOffset = true, nil, 0
local snapshot
local function log(text) public.log.info("WEATHER PROBE " .. text) end
local function expect(ok, text) assert(ok, text); log("PASS " .. text) end
local shim = {}
for k,v in pairs(public) do shim[k]=v end
shim.config = cfg
shim.on = function(name,fn) handlers[name]=fn end
shim.time = {format=public.time.format,now=function() local now=public.time.now();return synced and now and now+timeOffset or nil end}
shim.location = {get=function() return placeOverride or public.location.get() end}
local unitsOverride
shim.units = {
  get=function() return unitsOverride or public.units.get() end,
  temperature=function(c) return (unitsOverride or public.units.get()).temperature=="f" and c*1.8+32 or c end,
  wind=function(kmh) return (unitsOverride or public.units.get()).wind=="mph" and kmh*.621371192 or kmh end,
}
shim.state = {get=public.state.get,set=function(state) public.state.set(state) end}
shim.store = {get=public.store.get,set=function(key,value) writes=writes+1;public.store.set(key,value) end}
shim.http = {get=function(url,opts)
  calls=calls+1
  return public.http.get(root .. route .. url:sub(#cfg.endpoint+1),opts)
end}
shim.timer = {cancel=public.timer.cancel,after=function(delay,fn) lastDelay=delay;return public.timer.after(delay,fn) end,
  every=function(delay,fn,opts) minute=fn;return public.timer.every(delay,fn,opts) end}
tos = shim
require("production")
public.on("locale",function() handlers.locale() end)
public.on("attention",function(level) handlers.attention(level) end)
public.on("stop",function() handlers.stop() end)
local function state() return public.state.get("") end
public.on("start",function()
  handlers.start()
  expect(calls==0,"start never waits for HTTP")
  public.timer.after(1.5,function()
    local ok,err=pcall(function()
      for _=1,20 do if writes>0 then break end;public.timer.sleep(.2) end
      expect(writes==1 and state().temperature=="23°","fixture loads through actual PluginHttp and JNI")
      snapshot=public.store.get("forecast")
      local count=calls
      unitsOverride={system="imperial",temperature="f",wind="mph"};handlers.units();public.timer.sleep(.01);handlers.locale()
      expect(calls==count and state().temperature=="74°","unit and locale render locally without HTTP")
      for _,path in ipairs({"/503","/slow","/html","/missing"}) do
        route=path;handlers.command("refresh")
        expect(state().temperature=="74°" and state().stale and public.store.get("forecast")==snapshot,"preserves snapshot after "..path)
        route="/good";handlers.command("refresh");snapshot=public.store.get("forecast")
      end
      route="/503";handlers.command("refresh")
      local beforeRecovery=writes
      route="/good"
      -- The pending 10-second retry is real, not a forced manual refresh.
      for _=1,60 do if not state().stale then break end;public.timer.sleep(.2) end
      expect(not state().stale and writes==beforeRecovery+1,"scheduled retry automatically recovers")
      count=calls;handlers.attention("ASLEEP");public.timer.sleep(.1)
      handlers.attention("AWAKE");public.timer.sleep(.2)
      expect(calls==count,"wake keeps a still-fresh snapshot without duplicate HTTP")
      count=calls;handlers.attention("ASLEEP");handlers.command("refresh");public.timer.sleep(.3)
      expect(calls==count,"ASLEEP suppresses all new requests")
      timeOffset=tonumber(cfg.interval)*60000+1000
      handlers.attention("AWAKE");public.timer.sleep(.6)
      expect(calls>count,"wake resumes an overdue refresh")
      timeOffset=0
      synced=false;route="/503";handlers.command("refresh")
      expect(lastDelay==cfg.clock_retry and not state().status:find("分钟前",1,true),"untrusted clock uses short retry without a relative age")
      route="/good";synced=true;minute();public.timer.sleep(.6)
      expect(not state().stale,"time sync triggers refresh on the next minute tick")
      local original=public.location.get()
      placeOverride={name="Weather race test",latitude=31.2,longitude=121.5,timezone="Asia/Shanghai"}
      route="/delayed"
      public.timer.after(.1,function()
        placeOverride={name="Weather newest place",latitude=40.7,longitude=-74,timezone="America/New_York"}
        handlers.location()
      end)
      local before=writes
      handlers.command("refresh")
      expect(writes==before,"old-location response is discarded while HTTP is suspended")
      route="/good";public.timer.sleep(.8)
      local cached=public.json.decode(public.store.get("forecast"))
      expect(cached.place.latitude==40.7 and cached.place.name=="Weather newest place","newest coordinates win after the race")
      -- The response uses Oct 30's UTC-4 for all five daily boundaries, while IANA
      -- switches to UTC-5 on Nov 1. Formatting raw daily epochs in IANA repeats Sunday.
      route="/dst";timeOffset=1793376000000-public.time.now()
      placeOverride={name="Weather DST test",latitude=40.7,longitude=-74,timezone="America/New_York"}
      handlers.command("refresh")
      local days,unique=state().days,{}
      assert(#days==5)
      for i=1,5 do
        local expected=public.time.format((1793318400+(i-1)*86400)*1000,"@weekday","UTC")
        assert(days[i].title==expected and not unique[expected],"daily DST date shifted")
        unique[expected]=true
      end
      expect(true,"five daily labels stay distinct across the New York DST boundary")
      timeOffset=0;route="/good"
      placeOverride=nil;handlers.location();public.timer.sleep(.5)
      -- Validate the real public forecast endpoint on API 27, independently of the local fixture.
      local response,error=public.http.get(require("forecast").url("https://api.open-meteo.com/v1/forecast",original))
      assert(response and response.status==200,error or "real forecast HTTP status")
      local real=require("forecast").validate(public.json.decode(response.body))
      expect(#real.daily.time==5 and #real.hourly.time>=24,"real forecast TLS and WMO fields work on API 27")
      log("ALL PASSED calls="..calls.." writes="..writes)
    end)
    if not ok then public.log.error("WEATHER PROBE FAILED "..tostring(err)) end
  end)
end)
