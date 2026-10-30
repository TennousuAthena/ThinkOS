-- The settings page is generated from declarations. This plugin only moves values between the
-- OS settings store (tos.settings) and the native form; it never needs to know what a setting means.
local scope = "system"   -- whose settings are on screen: "system" or a plugin id
local failure = nil      -- { key = ..., text = ... } for the last refused edit
local failureTimer = nil

-- Choosing a place writes these settings together, so resetting the place clears all of them.
local PLACE_PARTS = { "mode", "name", "latitude", "longitude", "elevation", "timezone" }

local function clearFailure()
  if failureTimer then tos.timer.cancel(failureTimer) end
  failureTimer, failure = nil, nil
end

local function t(key, params)
  return tos.i18n.t(key, params)
end

-- Refusals arrive as codes so they can be said in the user's language.
local function explain(err)
  local code = err.code
  if code == "order" then
    code = err.args.below and "order_below" or "order_above"
  end
  local text = t("error." .. code, err.args)
  if text == "error." .. code then return err.message end
  return text
end

local function render()
  local d = tos.settings.get(scope)
  if not d then
    scope = "system"
    d = tos.settings.get(scope)
  end
  local fields = d.fields
  if failure then
    for _, f in ipairs(fields) do
      if f.key == failure.key then f.error = failure.text end
    end
  end
  if scope == "system" then
    local others = tos.settings.list()
    for _, s in ipairs(others) do
      if s.scope ~= "system" then
        fields[#fields + 1] = { key = "scope:" .. s.scope, type = "link", label = s.name,
                                display = "", group = t("plugins") }
      end
    end
  else
    fields[#fields + 1] = { key = "scope:system", type = "link", label = t("system"), display = "" }
  end
  tos.state.set { title = d.name, done = t("done"), fields = fields }
end

tos.on("page.enter", function(page, variant, params)
  scope = (params and params.scope) or "system"
  clearFailure()
  render()
end)

tos.on("locale", function()
  -- Descriptors include translated labels, zone names and the automatic location's city.
  clearFailure()
  render()
end)

tos.on("form.change", function(key, value)
  local target = key:match("^scope:(.+)$")
  if target then
    clearFailure()
    scope = target
    render()
    return
  end
  local stored, err
  if type(value) == "table" and key:match("name$") then
    -- A place picker submits its related settings as one compound edit. The OS validates all
    -- values before changing any of them, so coordinates and their time zone stay consistent.
    local prefix = key:gsub("name$", "")
    local patch = {}
    for _, part in ipairs(PLACE_PARTS) do
      if value[part] ~= nil then patch[prefix .. part] = value[part] end
    end
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

tos.on("form.reset", function(key)
  local source
  for _, f in ipairs((tos.settings.get(scope) or { fields = {} }).fields) do
    if f.key == key then source = f.source end
  end
  if source == "place" then
    local prefix = key:gsub("name$", "")
    for _, part in ipairs(PLACE_PARTS) do tos.settings.reset(scope, prefix .. part) end
  else
    tos.settings.reset(scope, key)
  end
  clearFailure()
  render()
end)

tos.on("page.leave", clearFailure)
tos.on("stop", clearFailure)
