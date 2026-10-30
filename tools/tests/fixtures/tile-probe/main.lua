local count = 0
tos.on("start", function()
  tos.state.set { color = "#2D89EF", override = "#D24726" }
  tos.timer.every(4, function()
    count = count + 1
    tos.state.set { color = count % 2 == 0 and "#2D89EF" or "#107C10", override = count % 3 == 0 and "bad" or "#D24726" }
  end)
end)
