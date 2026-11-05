local visible, generation, rows = false, 0, {}
local loading, dirty = false, false
local function t(key) return tos.i18n.t(key) end

local function refresh()
  generation = generation + 1
  dirty = true
  if loading then return end
  loading = true
  repeat
    dirty = false
    local request = generation
    local entries, err = tos.apps.list()
    if visible and request == generation then
      rows = {}
      local pages = 0
      if entries then
        for _, entry in ipairs(entries) do
          if entry.kind == "android" then
            rows[#rows + 1] = { id = entry.id, title = entry.title, icon = entry.icon or "apps", image = entry.image or "" }
          elseif entry.kind == "page" then
            pages = pages + 1
          end
        end
        tos.log.info("apps catalog pages=" .. pages .. " android=" .. #rows)
      end
      tos.state.set { apps = rows, notice = err and t("list.failed") or #rows == 0 and t("list.empty") or "" }
    end
  until not dirty or not visible
  loading = false
end

tos.on("page.enter", function() visible = true; refresh() end)
tos.on("page.leave", function() visible = false; dirty = false; generation = generation + 1 end)
tos.on("apps", function() if visible then refresh() end end)
tos.on("locale", function() if visible then refresh() end end)
tos.on("tap", function(name, index)
  if name ~= "open" or type(index) ~= "number" or index % 1 ~= 0 then return end
  local entry = rows[index]
  if not entry then return end
  local ok = tos.apps.open(entry.id)
  if visible and not ok then tos.state.set { notice = t("open.failed") } end
end)
