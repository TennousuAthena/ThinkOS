-- The OS describes values, dependencies and labels. This plugin owns only the settings navigation.
local path = { { scope = "system" } }
local shown = {}
local failure, failureTimer = nil, nil
local PLACE_PARTS = { "mode", "name", "latitude", "longitude", "elevation", "timezone" }
local render
local wide, lastSection = false, nil
local sections = {}

local function t(key, params) return tos.i18n.t(key, params) end
local function current() return path[#path] end
local function fieldScope(key) return key:find(".", 1, true) and "system" or current().scope end

local function clearFailure()
  if failureTimer then tos.timer.cancel(failureTimer) end
  failureTimer, failure = nil, nil
end

local function explain(err)
  local code = err.code
  if code == "order" then code = err.args.below and "order_below" or "order_above" end
  local text = t("error." .. code, err.args)
  return text == "error." .. code and err.message or text
end

local function identity(node)
  return node.scope .. "/" .. (node.section or "") .. "/" .. (node.advanced and "advanced" or "normal")
end

local function wideRoot(node)
  if wide and node.scope == "system" and not node.section then
    local selected = lastSection or (sections[1] and sections[1].id)
    if selected then return { scope = "system", section = selected, returnToRoot = true } end
  end
  return node
end

local function normalizePath()
  local i = 1
  while i <= #path do
    local node, following = path[i], path[i + 1]
    if wide and node.scope == "system" and not node.section then
      if following and following.scope == "system" and following.section then
        following.returnToRoot = true
        table.remove(path, i)
      else
        path[i] = wideRoot(node)
        i = i + 1
      end
    elseif not wide and node.returnToRoot then
      node.returnToRoot = nil
      table.insert(path, i, { scope = "system" })
      i = i + 2
    else
      i = i + 1
    end
  end
end

local function go(node)
  clearFailure()
  node = wideRoot(node)
  if node.scope == "system" and node.section then lastSection = node.section end
  local found
  for i, item in ipairs(path) do if identity(item) == identity(node) then found = i; break end end
  if found then
    while #path > found do table.remove(path) end
  else
    path[#path + 1] = node
  end
  tos.back.intercept(#path > 1)
  render()
end

local function link(fields, key, label, icon)
  fields[#fields + 1] = { key = key, type = "link", label = label, display = "", icon = icon }
end

local function excerpt(text)
  local flat = (type(text) == "string" and text or ""):gsub("%s+", " ")
  local edge = utf8.offset(flat, 81)
  return edge and flat:sub(1, edge - 1) .. "…" or flat
end

local function componentSummary(inventory)
  if type(inventory) ~= "table" or (inventory.updater ~= "ready" and inventory.updater ~= "read_only") then
    return t("components.unavailable")
  end
  local modules = type(inventory.modules) == "table" and inventory.modules or {}
  if #modules == 0 then return t("components.unknown") end
  local count = 0
  for _, module in ipairs(modules) do
    if type(module) ~= "table" or type(module.installed_code) ~= "number" or module.installed_code < 0 then
      return t("components.unknown")
    end
    if module.installed_code > 0 then count = count + 1 end
  end
  return t("components.installed", {count=count})
end

local function sectionName(d, id)
  for _, section in ipairs(d.sections or {}) do if section.id == id then return section.label end end
end

render = function()
  local node = current()
  local d = tos.settings.get(node.scope)
  if d and d.sections then sections = d.sections end
  if not d or (node.section and not sectionName(d, node.section)) then
    local system = node.scope == "system" and d or nil
    lastSection = nil
    path = { { scope = "system" } }
    normalizePath()
    node, d = current(), system or tos.settings.get("system")
    sections = d.sections or {}
    tos.back.intercept(false)
  end
  local fields, title = {}, d.name
  local update = node.scope == "system" and node.section == "system" and tos.update.status() or nil
  if type(update) ~= "table" then update = nil end
  if node.scope == "system" and not node.section then
    title = t("title")
    local icons = #(d.sections or {}) > 0
    for _, section in ipairs(d.sections or {}) do
      if type(section.icon) ~= "string" or section.icon == "" then icons = false end
    end
    for _, section in ipairs(d.sections or {}) do link(fields, "section:" .. section.id, section.label, icons and section.icon or nil) end
  elseif node.scope == "system" and node.section == "plugins" then
    title = sectionName(d, node.section)
    local advanced=false
    for _, field in ipairs(d.fields or {}) do
      if field.section == "plugins" then
        local level=field.level or "normal"
        if level=="advanced" then advanced=true end
        if level==(node.advanced and "advanced" or "normal") then fields[#fields+1]=field end
      end
    end
    if node.advanced then title=title.."  "..t("advanced")
    else
      if advanced then link(fields,"level:advanced",t("advanced")) end
      for _, other in ipairs(tos.settings.list()) do
        if other.scope ~= "system" then link(fields, "scope:" .. other.scope, other.name) end
      end
    end
  else
    title = node.section and sectionName(d, node.section) or d.name
    local advanced = false
    for _, field in ipairs(d.fields) do
      if node.scope ~= "system" or field.section == node.section then
        local level = field.level or "normal"
        if level == "advanced" then advanced = true end
        if level == (node.advanced and "advanced" or "normal") then
          if failure and field.key == failure.key then field.error = failure.text end
          if field.key == "update.auto_install" and update and update.silent_install == false then field.help = t("update.confirmation") end
          fields[#fields + 1] = field
        end
      end
    end
    if node.advanced then
      title = title .. "  " .. t("advanced")
    elseif advanced then
      link(fields, "level:advanced", t("advanced"))
    end
    if node.scope == "system" and node.section == "system" and not node.advanced then
      link(fields, "action:about", t("about"))
      local result = update and type(update.result) == "table" and update.result or nil
      link(fields, "action:updates", result and type(result.message) == "string" and result.message or t("updates"))
      if result then fields[#fields].display = excerpt(result.reason or result.notes) end
      link(fields, "action:components", t("components"))
      fields[#fields].display = componentSummary(update and update.inventory)
      link(fields, "action:hub", t("hub"))
      link(fields, "action:android", t("android"))
      link(fields, "action:onboarding", t("onboarding.restart"))
    elseif node.scope ~= "system" then
      link(fields, "scope:system", t("system"))
    end
  end
  if node.scope == "system" then
    local setup=tos.data.get("onboarding")
    if setup and setup.pending == true then
      table.insert(fields, 1, {key="action:completeSetup",type="link",label=t("onboarding.complete"),display=""})
    end
  end
  shown = {}
  local overridden = false
  for _, field in ipairs(fields) do
    if field.type ~= "link" then
      shown[#shown + 1] = field
      if field.overridden then overridden = true end
    end
  end
  if overridden then
    fields[#fields + 1] = { key = "reset:page", type = "link", label = t("reset"),
                            display = "", confirm = t("reset.confirm") }
  end
  local selected = node.scope ~= "system" and "plugins" or node.section or ""
  tos.state.set { title = title, done = t("done"), fields = fields,
                  nav = { items = sections, current = selected } }
end

local function resetField(field)
  local scope = fieldScope(field.key)
  if field.source == "place" then
    local prefix = field.key:gsub("name$", "")
    for _, part in ipairs(PLACE_PARTS) do tos.settings.reset(scope, prefix .. part) end
  else
    tos.settings.reset(scope, field.key)
  end
end

tos.on("page.enter", function(page, variant, params)
  if params and params.scope then path = { { scope = params.scope } }; lastSection = nil end
  normalizePath()
  tos.back.intercept(#path > 1)
  clearFailure()
  render()
end)

tos.on("form.layout", function(value)
  if type(value) ~= "boolean" or wide == value then return end
  wide = value
  normalizePath()
  clearFailure()
  tos.back.intercept(#path > 1)
  render()
end)

tos.on("form.navigate", function(id)
  if not wide then return end
  if not sectionName({ sections = sections }, id) then return end
  -- The sidebar replaces the base selection. Advanced and plugin pages still use go/back.
  path = { { scope = "system", section = id, returnToRoot = true } }
  lastSection = id
  clearFailure()
  tos.back.intercept(false)
  render()
end)

tos.on("locale", function()
  clearFailure()
  render()
end)
tos.on("update", function() if current().scope == "system" and current().section == "system" then render() end end)
tos.on("data", function(alias) if alias == "onboarding" then render() end end)

tos.on("form.change", function(key, value)
  if key == "action:onboarding" then tos.onboarding.open("restart"); return end
  if key == "action:completeSetup" then tos.onboarding.open("resume"); return end
  if key == "action:about" then tos.settings.about(); return end
  if key == "action:updates" or key == "action:components" then tos.update.open(); return end
  if key == "action:hub" then tos.apps.open("hub.main"); return end
  if key == "action:android" then tos.device.openAndroid("settings"); return end
  if key == "reset:page" then
    -- A category or advanced page resets only its own rows. Related system values use their
    -- real scope; the place editor also clears its compound values through the public API.
    for _, field in ipairs(shown) do resetField(field) end
    clearFailure()
    render()
    return
  end
  local section = key:match("^section:(.+)$")
  if section then go { scope = "system", section = section }; return end
  local target = key:match("^scope:(.+)$")
  if target then go { scope = target }; return end
  if key == "level:advanced" then
    local node = current()
    go { scope = node.scope, section = node.section, advanced = true }
    return
  end
  local scope = fieldScope(key)
  local stored, err
  if type(value) == "table" and key:match("name$") then
    local prefix, patch = key:gsub("name$", ""), {}
    for _, part in ipairs(PLACE_PARTS) do if value[part] ~= nil then patch[prefix .. part] = value[part] end end
    stored, err = tos.settings.setMany(scope, patch)
  else
    stored, err = tos.settings.set(scope, key, value)
  end
  if stored == nil and err then
    clearFailure()
    failure = { key = key, text = explain(err) }
    failureTimer = tos.timer.after(3, function()
      failureTimer, failure = nil, nil
      render()
    end)
  else
    clearFailure()
  end
  render()
end)

tos.on("back", function()
  if #path > 1 then table.remove(path) end
  clearFailure()
  tos.back.intercept(#path > 1)
  render()
end)

tos.on("form.reset", function(key)
  for _, field in ipairs(shown) do if field.key == key then resetField(field); break end end
  clearFailure()
  render()
end)

tos.on("page.leave", clearFailure)
tos.on("stop", clearFailure)
