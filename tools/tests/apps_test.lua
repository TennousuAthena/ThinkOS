assert(_VERSION == "Lua 5.4")
local handlers, state, entries, failure, opened, messages = {}, {}, {}, nil, {}, {}
local hold, language, accepted = false, "en", true
tos = {
  on = function(name, fn) handlers[name] = fn end,
  i18n = {t = function(key) return language .. ":" .. key end},
  -- Only the public log levels exist; unknown methods must remain nil.
  log = {info = function(message) messages[#messages+1] = message end, warn = function() end, error = function() end},
  state = {set = function(patch) for key,value in pairs(patch) do state[key] = value end end},
  apps = {
    list = function() if hold then return coroutine.yield("list") end; return entries, failure end,
    open = function(id) opened[#opened+1] = id; return accepted end,
  },
}
local function emit(name, ...) assert(handlers[name], name)(...) end
local function pass(text) print("PASS " .. text) end
assert(loadfile(assert(arg[1])))()
entries = {{id="player.library",kind="page",title="Music",icon="music"},
           {id="android:com.example.reader",kind="android",title="Reader",image="app:com.example.reader"}}
emit("page.enter")
assert(messages[#messages]=="apps catalog pages=1 android=1")
assert(#state.apps==1 and state.apps[1].id=="android:com.example.reader" and state.apps[1].group==nil)
assert(state.apps[1].image=="app:com.example.reader" and state.notice=="")
emit("tap","open",0);emit("tap","open","1");emit("tap","open",1.5);assert(#opened==0)
emit("tap","open",2);assert(#opened==0)
emit("tap","open",1);assert(opened[1]=="android:com.example.reader")
accepted=false;emit("tap","open",1);assert(state.notice=="en:open.failed")
pass("the grid keeps only Android apps and public icon sources; invalid taps cannot launch")

entries={entries[1]};emit("apps");assert(#state.apps==0 and state.notice=="en:list.empty")
language="fr";emit("locale");assert(#state.apps==0 and state.notice=="fr:list.empty")
entries=nil;failure="offline";emit("apps");assert(#state.apps==0 and state.notice=="fr:list.failed")
pass("catalog settings, locale and discovery failures update the visible list")

hold=true
local old=coroutine.create(function() emit("apps") end)
assert(coroutine.resume(old));assert(coroutine.status(old)=="suspended")
local latest=coroutine.create(function() emit("apps") end)
assert(coroutine.resume(latest))
assert(coroutine.status(latest)=="dead")
for i=1,50 do emit("apps") end
assert(coroutine.resume(old, {{id="android:com.example.old",kind="android",title="Old"}}))
assert(coroutine.status(old)=="suspended") -- one coalesced follow-up, never 50 outstanding IO jobs
assert(coroutine.resume(old, {{id="android:com.example.new",kind="android",title="New"}}))
assert(state.apps[1].id=="android:com.example.new")
local leaving=coroutine.create(function() emit("apps") end)
assert(coroutine.resume(leaving));emit("page.leave")
assert(coroutine.resume(leaving, {{id="player.library",kind="page",title="Late"}}))
assert(state.apps[1].id=="android:com.example.new")
pass("bursts coalesce into one request; stale results and page leave cannot overwrite the list")
