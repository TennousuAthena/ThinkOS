-- Factory panel: state comes from public OS mode, including failed and asynchronous changes.
assert(_VERSION == "Lua 5.4")
local handlers, state, timers, calls = {}, nil, {}, {}
local mode, failure, delayed = "day", false, false
tos = {
  on = function(name, fn) handlers[name] = fn end,
  i18n = { t = function(key) return key end },
  time = { now = function() return 1 end, format = function(_, pattern) return pattern end },
  state = { set = function(value) state = value end },
  timer = { every = function(_, fn) timers[#timers + 1] = fn; return #timers end,
    cancel = function(id) timers[id] = false end },
  device = {
    get = function() return { wifi = { enabled = true, connected = true, ssid = "Home" }, muted = true } end,
    mode = function() return mode end,
    setMode = function(value)
      calls[#calls + 1] = value
      if delayed then handlers["page.leave"]() end
      if failure then return nil, "device mode needs a recent touch" end
      mode = value; return true
    end,
    wifi = function() end, mute = function() end, openAndroid = function() end,
  },
  update = { status = function() return {} end, open = function() end },
  settings = { open = function() end }, apps = { open = function() end },
}
assert(arg[1], "pass plugins/panel/main.lua")
assert(loadfile(arg[1]))()
local function emit(name, ...) assert(handlers[name], name)(...) end
emit("page.enter")
assert(not state.redshift.on and not state.sleep.on and #calls == 0)
emit("tap", "redshift")
assert(mode == "redshift" and state.redshift.on and not state.sleep.on and calls[1] == "redshift")
emit("tap", "redshift")
assert(mode == "day" and not state.redshift.on and calls[2] == "day")
print("PASS panel redshift toggle requests a manual override and follows actual OS state")
emit("tap", "sleep")
assert(mode == "sleep" and state.sleep.on and not state.redshift.on)
emit("tap", "sleep")
assert(mode == "day" and not state.sleep.on)
failure = true
emit("tap", "sleep")
assert(mode == "day" and not state.sleep.on and state.update == "mode.failed")
mode = "sleep"; emit("mode", "sleep")
assert(state.sleep.on and state.update == "")
print("PASS failed mutation cannot fake a mode; mode events refresh an open sheet")
emit("page.leave")
local previous = state
mode = "day"; emit("mode", "day")
assert(state == previous and timers[1] == false)
emit("page.enter")
assert(not state.sleep.on)
failure = false; delayed = true
previous = state
emit("tap", "sleep")
assert(mode == "sleep" and state == previous)
print("PASS a closed panel ignores late mode results and releases its clock timer")
