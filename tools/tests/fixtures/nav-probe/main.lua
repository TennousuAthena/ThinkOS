-- Back probe (ADR-040). `plain` is an app that asks for nothing; `levels` can open it, which stacks one app on another.
-- `levels` has its own depth: a tap goes one level deeper and takes Back for itself until the top is
-- reached again. `silent` declares that it wants Back and then does nothing about it, which must not trap the user.
local depth = 1

local function publish() tos.state.set({text = "depth " .. depth}) end

tos.on("page.enter", function(page)
  if page == "levels" then
    depth = 1
    publish()
  elseif page == "silent" then
    tos.back.intercept(true)
    tos.state.set({text = "silent"})
  end
end)

tos.on("tap", function(name)
  if name == "plain" then tos.page.focus("nav-probe.plain"); return end
  if name ~= "deeper" then return end
  depth = depth + 1
  tos.back.intercept(true)
  publish()
  tos.log.info("probe depth=" .. depth)
end)

tos.on("back", function(page)
  if page ~= "levels" then return end   -- the silent page has no reaction at all
  depth = depth - 1
  if depth <= 1 then depth = 1; tos.back.intercept(false) end
  publish()
  tos.log.info("probe back depth=" .. depth)
end)
