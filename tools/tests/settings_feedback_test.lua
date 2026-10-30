-- Exercise the production settings plugin with a deterministic public-API fixture.
assert(_VERSION == "Lua 5.4", "Use Lua 5.4")
local handlers, timers, values = {}, {}, { system = 2, other = 1 }
local now, timerId, published = 0, 0, 0
local state
tos = {
  on = function(name, fn) handlers[name] = fn end,
  state = { set = function(value) state = value; published = published + 1 end },
  i18n = { t = function(key, args)
    if key == "error.max" then return "不能大于 " .. args.max end
    return key
  end },
  settings = {
    get = function(scope)
      return { name = scope, fields = {
        { key = "level", type = "number", label = "Level", help = "A readable explanation", value = values[scope], default = 2 }
      } }
    end,
    list = function() return {{ scope = "system", name = "System" }, { scope = "other", name = "Other" }} end,
    set = function(scope, key, value)
      assert(key == "level")
      if value > 4 then return nil, { code = "max", args = { max = 4 } } end
      values[scope] = value
      return value
    end,
    reset = function(scope) values[scope] = 2 end,
  },
  timer = {
    after = function(delay, fn)
      assert(delay == 3, "error feedback lasts three seconds")
      timerId = timerId + 1; timers[timerId] = { at = now + delay, fn = fn }; return timerId
    end,
    cancel = function(id) timers[id] = nil end,
  },
}

local function emit(name, ...) assert(handlers[name], name)(...) end
local function errorText() return state.fields[1].error end
local function advance(seconds)
  now = now + seconds
  local due = {}
  for id, item in pairs(timers) do if item.at <= now then due[#due+1] = id end end
  table.sort(due)
  for _, id in ipairs(due) do
    local item = timers[id]
    if item then timers[id] = nil; item.fn() end
  end
end
local function pass(message) print("PASS " .. message) end

assert(loadfile(assert(arg[1], "pass plugins/settings/main.lua")))()
emit("page.enter", "form", "digital", { scope = "system" })
assert(state.title == "system" and state.fields[1].help == "A readable explanation")
pass("generated descriptors retain their help")

emit("form.change", "level", 9)
assert(errorText() == "不能大于 4" and state.fields[1].value == 2)
advance(2.99); assert(errorText())
advance(.02); assert(not errorText() and next(timers) == nil)
pass("refused edits roll back and clear after three seconds")

emit("form.change", "level", 9); advance(2)
emit("form.change", "level", 10); advance(1.01); assert(errorText())
advance(2); assert(not errorText() and next(timers) == nil)
pass("a repeated refusal gets its own full display interval")

emit("form.change", "level", 9)
emit("form.change", "level", 3)
assert(not errorText() and state.fields[1].value == 3 and next(timers) == nil)
local before = published; advance(4); assert(published == before)
pass("a successful edit cancels old feedback")

emit("form.change", "level", 9)
emit("form.change", "scope:other", true)
assert(state.title == "other" and not errorText() and next(timers) == nil)
before = published; advance(4); assert(published == before)
pass("scope changes prevent stale error callbacks")

emit("form.change", "level", 9); emit("form.reset", "level")
assert(state.fields[1].value == 2 and not errorText() and next(timers) == nil)
emit("form.change", "level", 9); emit("locale")
assert(not errorText() and next(timers) == nil)
pass("reset and locale changes cancel feedback")

emit("form.change", "level", 9); emit("page.leave", "form")
assert(next(timers) == nil)
before = published; advance(4); assert(published == before)
emit("page.enter", "form", "digital", { scope = "system" }); assert(not errorText())
pass("leaving and reopening starts with clean feedback")

emit("form.change", "level", 9); emit("stop"); assert(next(timers) == nil)
pass("stopping the plugin cancels pending feedback")
