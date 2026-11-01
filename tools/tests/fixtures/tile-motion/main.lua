-- Device probe for tos.tile.animate (ADR-028): the command name is the animation to request.
tos.on("command", function(name)
  local ok, err = tos.tile.animate(name)
  tos.state.set({ lastResult = ok and "ok" or tostring(err) })
end)
