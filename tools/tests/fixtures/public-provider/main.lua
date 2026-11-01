-- Hostile provider for the device regression: its values must not hurt a consumer.
tos.on("start", function()
  local deep = string.rep('{"a":', 30) .. "1" .. string.rep("}", 30)
  local bomb = "[" .. string.rep("[],", 8000) .. "[]]"
  tos.state.set('{"public":{"deep":' .. deep .. ',"bomb":' .. bomb .. ',"secure":{"place":"home"}}}')
end)
tos.on("command", function(name, args, source, caller)
  tos.log.info("public-probe", "received", name, args and args.n, source, caller)
end)
