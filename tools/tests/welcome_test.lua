-- Production setup plugin, deterministic public-API fixture. No Android/device control.
assert(_VERSION == "Lua 5.4")
local source=assert(arg[1], "pass plugins/welcome/main.lua")
local function copy(value)
  if type(value) ~= "table" then return value end
  local result={}; for key, item in pairs(value) do result[key]=copy(item) end; return result
end
local handlers, state, persisted, values, opened = {}, nil, {}, {}, {}
local page, focus, practice, intercepted, online, canLocale = nil, nil, nil, false, false, false
local locale={tag="zh-CN",language="zh",script="Hans",region="CN"}
local timerId=0
local readsSincePublication, descriptorReads=0, 0
local function field(key, kind)
  return {key=key,type=kind or "choice",label=key,value=values[key] or "",searchUrl="https://example.invalid/search",source=key == "location.name" and "place" or nil}
end
local function resetVm()
  handlers={}; state=nil; focus=nil; page=nil; practice=nil
  tos={
    on=function(name, fn) handlers[name]=fn end,
    i18n={t=function(key) return key end},
    state={set=function(value)
      assert(readsSincePublication<=1,"one render must read the system description at most once")
      readsSincePublication=0;state=copy(value)
    end},
    store={get=function(key) return copy(persisted[key]) end,set=function(key,value) persisted[key]=copy(value) end},
    back={intercept=function(on) intercepted=on end},
    page={focus=function(next) focus=next end,release=function(closed) opened[#opened+1]="release:"..closed end},
    onboarding={status=function() return {version=1,greeting_seconds=2.4} end,
      practice=function(name,text) practice=name; assert(type(text)=="string") end,stop=function() practice=nil end},
    timer={every=function(delay,fn) assert(delay>0);timerId=timerId+1;return timerId end,cancel=function() end},
    locale=function() return copy(locale) end,
    time={now=function() return 1 end,format=function() return "12:34" end},
    tz={home=function() return "Asia/Shanghai" end,zones=function(region)
      return region=="US" and {{id="America/New_York",city="New York",time="12:34"},{id="America/Chicago",city="Chicago",time="11:34"}}
        or {{id="Asia/Shanghai",city="Shanghai",time="12:34"}}
    end},
    settings={get=function()
      readsSincePublication=readsSincePublication+1;descriptorReads=descriptorReads+1
      return {fields={field("location.name"),field("theme.mode"),field("redshift.trigger","choice"),field("display.orientation")}}
    end,
      set=function(scope,key,value) assert(scope=="system");values[key]=value;return value end,
      setMany=function(scope,patch) assert(scope=="system");for key,value in pairs(patch) do values[key]=value end;return patch end,
      about=function() opened[#opened+1]="licenses" end},
    device={get=function() return {online=online,wifi={connected=online}} end,
      canSetLocale=function() return canLocale end,
      setLocale=function(tag)
        if not canLocale then return false end
        locale.tag=tag;locale.language=tag:match("^[a-z]+");return true
      end,
      canSetTimeZone=function() return false end,setTimeZone=function() error("no updater must never pretend to set a zone") end,
      openAndroid=function(target) opened[#opened+1]=target end},
  }
  assert(loadfile(source))()
end
local function emit(name, ...)
  assert(handlers[name],name)(...)
  local transitions=0
  while focus do
    transitions=transitions+1;assert(transitions<5,"focus loop")
    local next=focus;focus=nil
    if page then handlers["page.leave"](page) end
    page=next;handlers["page.enter"](page,"main")
  end
end
local function enter(mode)
  page="hello";emit("page.enter",page,"main",{mode=mode})
end
local function tap(action) emit("tap","setup",action) end
local function pass(label) print("PASS "..label) end

values["onboarding.done"]=1
values["onboarding.attempts"]=3
resetVm()
assert(not state.public.setup.pending,"a migrated user who has not entered setup must not gain an unfinished session")
enter("restart")
assert(values["onboarding.done"]==0,"manual restart must clear an old completion before starting")
assert(values["onboarding.attempts"]==0,"manual restart must renew an exhausted automatic prompt budget")
assert(state.step=="hello" and #opened==0 and intercepted)
assert(state.public.setup.region==nil,"an inferred locale must not be published as a chosen region")
emit("tap","hello.next");assert(state.step=="language" and page=="setup")
for _,name in ipairs({"hello.next","hello.skip","hello.accessibility"}) do emit("tap",name) end
assert(state.step=="language" and values["onboarding.done"]==0 and #opened==0 and #state.public.setup.skipped==0,
  "late welcome-page taps must not advance, skip or leave the current screen")
emit("form.change","language:zh-CN",true)
assert(state.step=="region" and #opened==0)
assert(state.fields[1].key=="region" and state.fields[1].source=="region")
pass("hello enters the real language list; choosing the current language advances without leaving ThinkOS")

emit("form.change","region","US")
assert(state.public.setup.region=="US","an explicit region is shared through the declared onboarding export")
assert(values["units.system"]=="imperial" and state.step=="wifi")
tap("primary");assert(opened[#opened]=="wifi" and state.step=="wifi")
tap("secondary");assert(state.step=="privacy" and state.public.setup.pending)
tap("secondary");assert(opened[#opened]=="licenses" and state.step=="privacy")
tap("primary");assert(state.step=="updates")
tap("secondary");assert(values["update.auto_install"]==false and state.step=="location")
pass("offline Wi-Fi is optional; privacy licenses stay on the same step; download-only writes false")

assert(state.hero=="12:34")
emit("form.change","location.name",{mode="manual",name="City",latitude=1,longitude=2,elevation=3,timezone="Asia/Shanghai"})
assert(values["location.name"]=="City" and values["location.longitude"]==2)
emit("form.change","action:timezone",true);assert(opened[#opened]=="date" and state.step=="location")
tap("primary");assert(state.step=="appearance" and #state.fields==3)
emit("form.change","theme.mode","dark");assert(values["theme.mode"]=="dark")
assert(state.fields[1].value=="dark","the next render must not reuse old settings")
emit("form.change","redshift.trigger","off");assert(values["redshift.trigger"]=="off" and values["theme.night"]==nil,
  "appearance uses the public redshift setting rather than the consumed legacy switch")
local beforeLocale=descriptorReads
emit("locale")
assert(descriptorReads==beforeLocale+1,"a locale change must fetch a fresh description once")
emit("form.change","display.orientation","landscape");assert(values["display.orientation"]=="landscape")
tap("primary");assert(state.step=="gestures" and page=="gestures" and practice=="swipe")
pass("place edits are compound; timezone fallback is real Android entry; appearance writes apply before continuing")

emit("practice","wrong");assert(practice=="swipe" and persisted.progress.gesture==1)
emit("tap","gesture.next");assert(persisted.progress.gesture==1)
emit("practice","swipe");assert(practice=="variant" and persisted.progress.gesture==2)
emit("back");assert(practice=="swipe" and persisted.progress.gesture==1)
for _, name in ipairs {"swipe","variant","panel","back"} do emit("practice",name) end
assert(practice=="home" and persisted.progress.gesture==5)
emit("practice","home");assert(persisted.progress.gesture==6 and focus==nil)
emit("page.leave","gestures");assert(practice==nil and values["onboarding.done"]==0)
resetVm();enter("continue")
assert(page=="gestures" and practice=="long-press" and persisted.progress.gesture==6)
emit("practice","long-press");assert(practice==nil and state.instruction=="gesture.7")
emit("tap","gesture.next");assert(state.step=="welcome")
emit("tap","gesture.next");emit("tap","hello.skip")
assert(state.step=="welcome" and values["onboarding.done"]==0,"a stale lesson or greeting tap cannot press Start on the final page")
tap("primary");assert(values["onboarding.done"]==1 and opened[#opened]=="release:setup")
pass("wrong gestures and taps cannot pass practice; Home persists and resumes the next lesson; real completion releases the page")

online=true;resetVm();enter("restart")
emit("tap","hello.skip")
assert(values["onboarding.done"]==1 and state.public.setup.pending)
assert(persisted.progress.skipped.wifi==nil and persisted.progress.skipped.timezone==nil)
pass("skip-all completes setup and publishes only applicable skipped screens for later continuation")

persisted.progress={schema=1,step="done",gesture=1,skipped={location=true}}
resetVm();enter("resume")
assert(state.step=="location" and #state.public.setup.skipped==1)
emit("page.leave","setup")
resetVm();enter("continue")
assert(state.step=="location" and persisted.progress.resumeOnly==true)
tap("primary");assert(state.step=="welcome")
tap("primary");assert(not state.public.setup.pending and #state.public.setup.skipped==0)
pass("Finish setup runs only the skipped screen and clears the public continuation row")

persisted={};opened={};resetVm();enter("restart")
emit("tap","hello.next");emit("form.change","language:en",true)
assert(state.step=="language" and opened[#opened]=="locale")
canLocale=true;emit("form.change","language:en",true)
assert(state.step=="region" and locale.language=="en")
emit("form.change","region","CN")
assert(values["units.system"]=="imperial" and state.step=="privacy",
  "selecting another region must retain the existing unit preference")
tap("primary");tap("primary")
assert(values["update.auto_install"]==true and state.step=="location")
pass("another language uses Android only when permission is absent; connected Wi-Fi skips; automatic-install choice writes true")

resetVm()
assert(state.public.setup.region=="CN","the public explicit region survives plugin restart")
for _,invalid in ipairs({"cn","C","CHN",7}) do
  persisted.progress.region=invalid;resetVm()
  assert(state.public.setup.region==nil,"invalid private progress must not become a public region")
end
persisted.progress.region=nil;resetVm()
assert(state.public.setup.region==nil and type(state.public.setup.pending)=="boolean" and type(state.public.setup.skipped)=="table")
pass("the compatible setup export publishes only an explicit uppercase two-letter region")

-- A complete normal path without a single skipped screen must still expose
-- Settings > Finish setup after the mandatory Home lesson really leaves it.
persisted={};opened={};online=true;canLocale=false
locale={tag="zh-CN",language="zh",script="Hans",region="CN"}
resetVm();enter("restart")
emit("tap","hello.next");emit("form.change","language:zh-CN",true)
emit("form.change","region","CN");assert(state.step=="privacy")
tap("primary");tap("primary");tap("primary");tap("primary")
assert(state.step=="gestures" and practice=="swipe" and #state.public.setup.skipped==0)
for _,name in ipairs({"swipe","variant","panel","back","home"}) do emit("practice",name) end
assert(persisted.progress.gesture==6 and values["onboarding.done"]==0)
emit("page.leave","gestures")
resetVm()
assert(state.public.setup.pending and #state.public.setup.skipped==0,"unfinished Home exit needs the public settings continuation row")
enter("resume")
assert(page=="gestures" and practice=="long-press" and persisted.progress.gesture==6 and #opened==0,
  "the existing settings resume action must continue lesson six rather than finish or restart")
emit("practice","long-press");emit("tap","gesture.next")
assert(state.step=="welcome" and values["onboarding.done"]==0)
tap("primary")
assert(values["onboarding.done"]==1 and persisted.progress.started==false and not state.public.setup.pending)
pass("Home without skipped screens exposes Finish setup and resumes the next real lesson through the existing settings action")

-- Completing with omissions retains the old meaning of resume: only omissions.
persisted.progress={schema=1,step="done",gesture=7,started=false,skipped={location=true}}
resetVm();enter("resume")
assert(state.step=="location" and page=="setup" and persisted.progress.resumeOnly==true)
tap("primary");assert(state.step=="welcome")
tap("primary");assert(not state.public.setup.pending and persisted.progress.started==false)
pass("a truly completed setup still resumes only its omitted screen and clears continuation after finishing")

-- A stale legacy step is not evidence that a migrated old user just started.
persisted.progress={schema=1,step="appearance",gesture=1,skipped={}}
resetVm();assert(not state.public.setup.pending)
values["onboarding.done"]=0
enter("continue");assert(state.step=="appearance" and state.public.setup.pending)
emit("page.leave","setup")
emit("tap","setup","primary");emit("tap","hello.skip");emit("back")
assert(persisted.progress.step=="appearance" and values["onboarding.done"]==0,
  "events after leaving cannot mutate or complete the stored session")
pass("legacy progress becomes pending only after actual entry, and hidden-page actions leave it unchanged")

-- Real Locale.toLanguageTag may retain a Chinese script and a different region.
-- LocaleChain.script is the public distinction; language==zh alone is insufficient.
for _, case in ipairs {
  {tag="zh-Hans-CN",script="Hans",region="CN",selected="zh-CN",other="zh-TW"},
  {tag="zh-Hant-TW",script="Hant",region="TW",selected="zh-TW",other="zh-CN"},
  {tag="zh-Hant-HK",script="Hant",region="HK",selected="zh-TW",other="zh-CN"},
} do
  persisted={};opened={};canLocale=false
  locale={tag=case.tag,language="zh",script=case.script,region=case.region}
  resetVm();enter("restart");emit("tap","hello.next")
  local attempts={}
  tos.device.setLocale=function(tag) attempts[#attempts+1]=tag;return false end
  assert(state.fields[1].key=="language:"..case.selected,"the active Chinese script must be first")
  emit("form.change","language:"..case.selected,true)
  assert(state.step=="region" and #attempts==0 and #opened==0 and locale.tag==case.tag,
    "choosing the active Chinese script must not request permission or leave ThinkOS")
  assert(state.fields[1].value==case.region,"a language-only choice must preserve the existing region")
  tap("back");assert(state.step=="language")
  emit("form.change","language:"..case.other,true)
  assert(state.step=="language" and #attempts==1 and attempts[1]==case.other and opened[#opened]=="locale" and locale.tag==case.tag,
    "a real Hans/Hant change must still use the existing permission fallback")
end
pass("script-qualified Chinese locales stay inside setup for the active language; real script changes retain Android fallback")

-- §4.6: US/LR/MM supply an imperial default; every other region leaves
-- the owner's existing unit preference and absent override untouched.
for _, case in ipairs {
  {region="US", initial="metric", expected="imperial", writes=1},
  {region="LR", initial="metric", expected="imperial", writes=1},
  {region="MM", initial="metric", expected="imperial", writes=1},
  {region="CN", initial="imperial", expected="imperial", writes=0},
  {region="FR", initial="metric", expected="metric", writes=0},
  {region="CN", writes=0},
} do
  persisted={};opened={};values={["units.system"]=case.initial};online=false;canLocale=false
  locale={tag="zh-CN",language="zh",script="Hans",region="CN"}
  resetVm();enter("restart");emit("tap","hello.next");emit("form.change","language:zh-CN",true)
  local write, count=tos.settings.set, 0
  tos.settings.set=function(scope,key,value)
    if key=="units.system" then count=count+1 end
    return write(scope,key,value)
  end
  emit("form.change","region",case.region)
  assert(values["units.system"]==case.expected and count==case.writes,
    "region "..case.region.." must not silently reset the unit override")
  assert(state.step=="wifi" and values["onboarding.done"]==0 and #opened==0,
    "the ordinary offline guide continues without changing device capabilities")
end
pass("regional imperial defaults never overwrite other regions' existing unit preference or create an override")

-- A native setup-capability answer may precede an updater failure/unknown ROM
-- zone. Both real UI paths must hand the actual change to Android, then leave
-- the next setup step ready when the user returns, without inventing a zone.
for _, region in ipairs {"CN", "US"} do
  for _, outcome in ipairs {"success", "false", "nil"} do
    persisted={};opened={};values={};online=false;canLocale=false
    locale={tag="zh-CN",language="zh",script="Hans",region="CN"}
    resetVm()
    local requested={}
    tos.device.canSetTimeZone=function() return true end
    tos.device.setTimeZone=function(id)
      requested[#requested+1]=id
      if outcome=="success" then return true end
      if outcome=="false" then return false end
      return nil,"unavailable"
    end
    enter("restart");emit("tap","hello.next");emit("form.change","language:zh-CN",true)
    emit("form.change","region",region)
    local expected=region=="CN" and "Asia/Shanghai" or "America/New_York"
    if region=="US" then
      assert(state.step=="timezone" and #requested==0,"a multi-zone region must wait for the actual city row")
      emit("form.change","timezone:"..expected,true)
    end
    assert(#requested==1 and requested[1]==expected,"the native setter must receive only the chosen/sole zone")
    assert(#opened==(outcome=="success" and 0 or 1) and (outcome=="success" or opened[1]=="date"),
      "failed native timezone changes must open the existing Android date page exactly once")
    assert(state.step=="wifi" and persisted.progress.step=="wifi" and values["onboarding.done"]==0,
      "returning from Android must continue the real guide instead of repeating the failed setter or completing setup")
    assert(values["system.timezone"]==nil and values["timezone"]==nil,
      "fallback must not invent a ThinkOS system-timezone override")
  end
end
pass("single/multi-zone native failures open Android date settings and preserve the next ordinary guide step")

-- Suspend the actual public setter using a real Lua coroutine. The main test
-- coroutine can deliver the OS page.leave caused by Home, or a new visit/Back,
-- while the original form.change handler is still awaiting its native result.
for _, case in ipairs {
  {region="CN", interruption="Home", result=false},
  {region="US", interruption="Home-return", result=false},
  {region="US", interruption="Back", result=true},
} do
  persisted={};opened={};values={};online=false;canLocale=false
  locale={tag="zh-CN",language="zh",script="Hans",region="CN"}
  resetVm()
  tos.device.canSetTimeZone=function() return true end
  enter("restart");emit("tap","hello.next");emit("form.change","language:zh-CN",true)
  if case.region=="US" then emit("form.change","region","US");assert(state.step=="timezone") end
  local calls=0
  tos.device.setTimeZone=function(id)
    calls=calls+1
    return coroutine.yield("await-timezone",id)
  end
  local old=coroutine.create(function()
    if case.region=="CN" then emit("form.change","region","CN")
    else emit("form.change","timezone:America/New_York",true) end
  end)
  local ok, waiting=coroutine.resume(old)
  assert(ok and waiting=="await-timezone" and coroutine.status(old)=="suspended" and calls==1)
  if case.interruption=="Back" then
    tap("back");assert(state.step=="region")
  else
    emit("page.leave","setup") -- Native Home exits the onboarding role by dispatching page.leave.
    if case.interruption=="Home-return" then emit("page.enter","setup","main") end
  end
  local step, savedStep, openedCount=state.step,persisted.progress.step,#opened
  local resumed, err=coroutine.resume(old,case.result)
  assert(resumed,err)
  assert(coroutine.status(old)=="dead" and #opened==openedCount and calls==1,
    "an old native result must not reopen Android after "..case.interruption)
  assert(state.step==step and persisted.progress.step==savedStep and values["onboarding.done"]==0,
    "an old native result must not advance or complete the paused/revisited guide")
end
pass("real suspended timezone continuations cannot reopen or advance setup after Home, reentry or Back")
