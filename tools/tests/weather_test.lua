local json = _json
local sample, manifest, strings = json.decode(sample_json), json.decode(manifest_json), json.decode(strings_json)
local model = assert(load(forecast_source, "forecast", "t"))()
local codes = assert(load(codes_source, "codes", "t"))()
local function clone(value) return json.decode(json.encode(value)) end
local function test(name, fn) test_begin(); fn(); test_end(name) end
local function new(opts)
  opts = opts or {}
  local env = { animations = {}, handlers = {}, timers = {}, state = {}, requests = {}, writes = {}, serial = 0,
    now = sample.current.time * 1000,
    place = {name="Hong Kong",latitude=22.3,longitude=114.2,timezone="Asia/Hong_Kong"}, clock = 0,
    units = {system="metric",temperature="c",wind="kmh"} }
  local cfg = {}; for key, field in pairs(manifest.settings) do cfg[key] = field.default end
  if opts.noTime then env.now = false end
  local function translate(key, args)
    local value = strings[key] or key
    return (value:gsub("{([%w_]+)}", function(k) return tostring(args[k]) end))
  end
  env.response = function() return {status=200,body=sample_json} end
  tos = {
    config = cfg, json = json,
    on = function(name, fn) env.handlers[name] = fn end,
    state = {set=function(value) for k,v in pairs(value) do env.state[k]=v end end},
    tile = {animate=function(name) env.animations[#env.animations+1]=name; return true end},
    store = {get=function() return opts.cache end,set=function(key,value) env.writes[#env.writes+1]=clone(value) end},
    location = {get=function() return clone(env.place) end},
    units = {
      get=function() return {system=env.units.system,temperature=env.units.temperature,wind=env.units.wind} end,
      temperature=function(c) return env.units.temperature=="f" and c*1.8+32 or c end,
      wind=function(kmh) return ({kmh=kmh,ms=kmh/3.6,mph=kmh*.621371192,kn=kmh*.539956803})[env.units.wind] end,
    },
    time = {now=function() return env.now or nil end,format=function(ms,fmt,tz)
      assert(tz == "Asia/Hong_Kong" or tz == "Asia/Kathmandu" or tz == "America/New_York" or tz == "UTC")
      assert(fmt == "@time" or fmt == "@weekday")
      return fmt .. ":" .. tostring(ms)
    end},
    i18n = {t=function(key,args) return translate(key,args or {}) end},
    http = {get=function(url)
      env.requests[#env.requests+1]=url
      if env.pause then coroutine.yield("http") end
      return env.response()
    end},
    log = {info=function()end,warn=function()end},
    timer = {
      after=function(delay,fn) env.serial=env.serial+1;env.timers[env.serial]={at=env.clock+delay,fn=fn};return env.serial end,
      every=function(delay,fn,options) assert(delay==60 and options.align);env.minute=fn;return -1 end,
      cancel=function(id) env.timers[id]=nil end,
    },
  }
  require = function(name) return name == "forecast" and model or codes end
  function env.emit(name,...)
    return assert(env.handlers[name],name)(...)
  end
  function env.next()
    local first,id
    for key,timer in pairs(env.timers) do if not first or timer.at<first.at then first,id=timer,key end end
    assert(first,"no scheduled retry");env.timers[id]=nil;env.clock=first.at;return first.fn
  end
  function env.delay()
    local nextTime
    for _,timer in pairs(env.timers) do nextTime=math.min(nextTime or math.huge,timer.at) end
    return nextTime and nextTime-env.clock
  end
  assert(load(main_source,"weather.main","t"))()
  env.emit("start")
  return env
end
test("start publishes immediately; network deferred; bounded full forecast", function()
  local e=new();assert(#e.requests==0 and e.state.temperature=="—" and e.delay()==1)
  e.next()();assert(#e.requests==1 and e.state.temperature=="23°" and #e.state.hourly==24 and #e.state.days==5)
  assert(e.state.highlight==1 and e.delay()==900 and #e.writes==1 and not e.state.stale)
  assert(e.requests[1]:find("temperature_unit=celsius",1,true) and e.requests[1]:find("timeformat=unixtime",1,true))
end)
test("unit, language and minute changes only render; relevance remains stable", function()
  local e=new();e.next()();local count=#e.requests
  e.units={system="imperial",temperature="f",wind="mph"};e.emit("units")
  assert(e.state.temperature=="74°" and e.state.metrics:find("8 mph",1,true))
  e.units={system="metric",temperature="c",wind="kn"};e.emit("units")
  assert(e.state.temperature=="23°" and e.state.metrics:find("kn",1,true))
  e.units={system="imperial",temperature="f",wind="mph"};e.emit("units")
  local previous=e.state.relevance;e.state.relevance=nil;e.minute();e.emit("locale")
  assert(#e.requests==count and e.state.relevance==nil and previous==.3)
end)
test("fresh weather has no status or minute-old text; only meaningful precipitation appears", function()
  local e=new();e.next()()
  assert(e.state.status == "" and e.state.cardInfo == e.place.name)
  local metrics, card, summary = e.state.metrics, e.state.cardInfo, e.state.summary
  e.now=e.now+60000;e.minute()
  assert(e.state.status == "" and e.state.cardInfo == card and e.state.metrics == metrics and e.state.summary == summary)
  assert(not e.state.metrics:find("\n",1,true))
  assert(e.state.hourlyRange == nil)
end)
test("cache starts offline immediately; timestamp and raw Celsius survive", function()
  local e=new();e.next()();local cache=e.writes[1]
  e=new({cache=cache});assert(#e.requests==0 and e.state.temperature=="23°")
  e.response=function()return nil,"offline"end;e.next()()
  assert(e.state.temperature=="23°" and e.state.stale and e.state.status:find("Offline",1,true) and #e.writes==0)
  assert(json.decode(cache).weather.current.temperature_2m==23.4)
end)
test("HTTP, timeout, non JSON and missing fields preserve complete cache", function()
  local e=new();e.next()();local cache=e.writes[1]
  local missing=clone(sample);missing.daily.temperature_2m_min[2]=json.null
  for _,response in ipairs({function()return {status=503,body="busy"}end,function()return nil,"timeout"end,
    function()return {status=200,body="<html>"}end,function()return {status=200,body=json.encode(missing)}end}) do
    e=new({cache=cache});e.response=response;e.next()()
    assert(e.state.temperature=="23°" and e.state.stale and #e.writes==0 and e.delay()==60)
  end
end)
test("backoff 1, 2, 5, 15 minutes; success resets retry cycle", function()
  local e=new();e.response=function()return {status=500}end
  for _,delay in ipairs({60,120,300,900,900}) do e.next()();assert(e.delay()==delay)end
  e.response=function()return {status=200,body=sample_json}end;e.next()();assert(e.delay()==900 and not e.state.stale)
  e.response=function()return nil,"offline"end;e.next()();assert(e.delay()==60)
end)
test("sleep stops refresh; wake and changed interval resume", function()
  local e=new();e.next()();e.emit("attention","ASLEEP");assert(e.delay()==nil)
  e.emit("attention","AWAKE");assert(#e.requests==1 and e.delay()==900)
  e.emit("attention","ASLEEP")
  e.emit("command","refresh");assert(#e.requests==1)
  e.emit("attention","AWAKE");e.next()();assert(#e.requests==2)
  e.emit("attention","ASLEEP");e.now=e.now+901000;e.emit("attention","AWAKE");e.next()();assert(#e.requests==3)
  local cfg=clone(tos.config);cfg.interval="10";tos.config=cfg;e.emit("config",cfg);assert(e.delay()==600)
end)
test("untrusted clock retries at 30s; sync pulls next minute without relative age", function()
  local e=new({noTime=true});e.response=function()return nil,"certificate date"end;e.next()()
  assert(e.delay()==30 and not e.state.status:find("ago",1,true))
  e.now=sample.current.time*1000;e.minute();assert(e.delay()>.049 and e.delay()<.051)
  e.response=function()return {status=200,body=sample_json}end;e.next()();assert(not e.state.stale)
end)
test("an inflight success during sleep stays fresh on wake", function()
  local e=new();e.pause=true;local co=coroutine.create(e.next());assert(coroutine.resume(co))
  e.emit("attention","ASLEEP");assert(coroutine.resume(co));assert(#e.writes==1 and e.delay()==nil)
  e.emit("attention","AWAKE");assert(#e.requests==1 and e.delay()==900)
end)
test("expired data and clock moving backwards never appear freshly updated", function()
  local e=new();e.next()();local original=e.state.temperature
  e.now=e.now+181*60000;e.minute();assert(e.state.stale and e.state.status:find("Data expired",1,true) and e.state.temperature==original)
  e.now=sample.current.time*1000-60000;e.minute();assert(not e.state.status:find("ago",1,true))
end)
test("location race discards late result and fetches newest coordinates", function()
  local e=new();e.pause=true;local co=coroutine.create(e.next());assert(coroutine.resume(co))
  e.place.latitude=31.2;e.place.name="Shanghai";e.emit("location");assert(coroutine.resume(co))
  assert(#e.writes==0);e.pause=false;e.next()()
  assert(#e.writes==1 and e.requests[2]:find("latitude=31.200000",1,true) and e.state.location=="Shanghai")
end)
test("inflight overlap coalesces; stop suppresses late writes", function()
  local e=new();e.pause=true;local co=coroutine.create(e.next());assert(coroutine.resume(co))
  e.emit("command","refresh");assert(#e.requests==1)
  e.emit("stop");assert(coroutine.resume(co));assert(#e.writes==0 and e.delay()==nil)
end)
test("invalid cache and malformed full arrays are rejected atomically", function()
  local e=new({cache={version=0}});assert(e.state.temperature=="—")
  for _,mutate in ipairs({function(b)b.hourly.time[2]=b.hourly.time[1]end,function(b)b.current.temperature_2m="23"end,
    function(b)b.current.is_day=.5 end,function(b)b.current.weather_code=1.5 end,function(b)b.hourly.weather_code[2]=nil end,
    function(b)b.daily.temperature_2m_min[1]=40 end,function(b)b.current.wind_speed_10m=0/0 end})do
    local b=clone(sample);mutate(b);assert(not pcall(model.validate,b))
  end
end)
test("all WMO groups, night icons, hazards and unknown codes", function()
  for _,code in ipairs({0,1,2,3,45,48,51,53,55,56,57,61,63,65,66,67,71,73,75,77,80,81,82,85,86,95,96,97,99})do
    local icon,key=codes(code,true);assert(icon~="unknown" and strings[key])
  end
  assert(codes(0,false)=="clear-night" and codes(2,false)=="partly-night" and codes(999,true)=="unknown")
  local e=new();local b=clone(sample);b.current.weather_code=95;e.response=function()return {status=200,body=json.encode(b)}end;e.next()()
  assert(e.state.relevance==.85 and e.state.icon=="thunder")
end)
test("fractional time zones select local current hour and nominal local date", function()
  local e=new();local b=clone(sample);b.timezone="Asia/Kathmandu";b.utc_offset_seconds=20700
  local delta=28800-20700
  for _,kind in ipairs({"hourly","daily"})do for i,time in ipairs(b[kind].time)do b[kind].time[i]=time+delta end end
  b.current.time=b.current.time+delta;e.now=b.current.time*1000
  e.response=function()return {status=200,body=json.encode(b)}end;e.next()()
  assert(#e.state.hourly==24 and e.state.highlight==1)
  assert(e.state.days[1].title=="@weekday:"..tostring((b.daily.time[1]+b.utc_offset_seconds)*1000))
  assert(e.state.hourlyRange == nil) -- no redundant time range below the chart
end)
test("daily date labels retain fixed API boundaries across a DST transition", function()
  local e=new();local b=clone(sample);b.timezone="America/New_York";b.utc_offset_seconds=-14400
  for i=1,#b.daily.time do b.daily.time[i]=1793332800+(i-1)*86400 end
  for i=1,#b.hourly.time do b.hourly.time[i]=1793332800+(i-1)*3600 end
  b.current.time=1793376000;e.now=b.current.time*1000
  e.response=function()return {status=200,body=json.encode(b)}end;e.next()()
  assert(#e.state.days==5)
  for i=1,5 do assert(e.state.days[i].title=="@weekday:"..tostring((1793318400+(i-1)*86400)*1000)) end
end)
