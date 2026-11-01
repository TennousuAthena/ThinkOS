-- Device regression probe for tos.data / tos.command (ADR-027); each line reports through logcat.
local function report(label, ...) tos.log.info("public-probe", label, ...) end
local function read(alias)
  local value, err = tos.data.get(alias)
  if value then return "photos=" .. tostring(value.photos and #value.photos) end
  return "err=" .. tostring(err)
end
tos.on("start", function()
  report("undeclared", read("nope"))
  report("wrong", read("wrong"))
  report("call-private-weather", tos.command.call("weather.nope"))
  report("call-undeclared", tos.command.call("gallery.other"))
  local ok = {pcall(tos.state.set, {uses = {}})}
  report("reserved", tostring(ok[1]))
  tos.state.set({report = "start"})
end)
tos.on("data", function(alias)
  local text = read(alias)
  if alias == "deep" or alias == "bomb" or alias == "secure" then report(alias, text); return end
  report("data", alias, text)
  tos.state.set({report = alias .. " " .. text})
end)
tos.on("tap", function() end)
tos.on("page.enter", function()
  report("call-args", tos.command.call("public-provider.ping", {n = 7}))
  report("call-provider-private", tos.command.call("public-provider.private"))
  report("call-public", tos.command.call("gallery.refresh", {reason = "probe"}))
  report("call-cooldown", tos.command.call("gallery.refresh"))
end)
