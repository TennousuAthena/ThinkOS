local rows = {}
for i = 1, 100 do rows[i] = {title = string.format("Item %03d", i)} end
tos.on("start", function()
  tos.state.set({rows = rows, selected = "No selection", channel = {number = 1, name = "Local UI probe", line = 1, lines = 2}})
end)
tos.on("tap", function(name, value)
  if name == "row" then tos.state.set({selected = "Selected " .. tostring(value)})
  elseif name == "tv" then tos.log.info("EDGE_TV_ACTION=" .. tostring(value)) end
end)
