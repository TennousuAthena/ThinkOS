local rows, icons = {}, {"apps", "music", "tv", "cast", "settings"}
for index = 1, 100 do
  rows[index] = {title = string.format("Item %03d", index), icon = icons[(index - 1) % #icons + 1]}
end
tos.on("start", function() tos.state.set { rows = rows, selected = "" } end)
tos.on("tap", function(name, index)
  if name == "choose" and type(index) == "number" and rows[index] then
    tos.state.set { selected = "Selected " .. index }
  end
end)
