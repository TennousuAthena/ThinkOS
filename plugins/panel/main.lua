-- The control sheet that slides down from the top edge (ADR-035). The OS owns the gesture, the sheet and the controls; this
-- plugin decides what the sheet says and what its buttons do. It never changes a level itself: the sliders and the media
-- controls act on the device directly, and everything here goes through tos.device.
local open = false
local clock = nil
local modeError = false

local function t(key, params)
  return tos.i18n.t(key, params)
end

local function headline()
  local now = tos.time.now()
  if not now then return "" end
  return tos.time.format(now, "@time") .. "   " .. tos.time.format(now, "@dateLong")
end

local function render()
  local d = tos.device.get()
  local mode = tos.device.mode()
  local wifi = d.wifi or {}
  local label = wifi.connected and (wifi.ssid or t("wifi.label")) or t("wifi.label")
  local icon = wifi.enabled and "wifi" or "wifi-off"
  local update = tos.update.status().result
  local notice = ""
  if update then
    local detail = update.reason or update.notes or ""
    detail = detail:gsub("%s+", " ")
    notice = update.message .. (detail ~= "" and "\n" .. detail or "")
  end
  if modeError then notice = t("mode.failed") end
  tos.state.set({
    header = headline(),
    wifi = { on = wifi.enabled == true, icon = icon, label = label },
    mute = { on = d.muted == true, icon = d.muted and "volume-off" or "volume" },
    redshift = { on = mode == "redshift" },
    sleep = { on = mode == "sleep" },
    footer = t("network.footer"),
    update = notice,
  })
end

tos.on("page.enter", function()
  open = true
  render()
  -- The time in the header moves on while the sheet is up.
  clock = tos.timer.every(60, function() if open then render() end end, { align = true })
end)

tos.on("page.leave", function()
  open = false
  if clock then tos.timer.cancel(clock); clock = nil end
end)

-- Wi-Fi, the volume keys or a settings page changed something while the sheet is showing.
tos.on("device", function()
  if open then render() end
end)
tos.on("update", function() if open then render() end end)
tos.on("locale", function() if open then render() end end)
tos.on("mode", function() modeError = false; if open then render() end end)

tos.on("tap", function(name)
  if name == "wifi" then
    local wifi = tos.device.get().wifi or {}
    tos.device.wifi(not wifi.enabled)
  elseif name == "mute" then
    tos.device.mute(not tos.device.get().muted)
  elseif name == "redshift" or name == "sleep" then
    local target = tos.device.mode() == name and "day" or name
    local ok = tos.device.setMode(target)
    modeError = not ok
    if open then render() end
  elseif name == "settings" then
    tos.settings.open("system")
  elseif name == "network" then
    tos.device.openAndroid("wifi")
  elseif name == "apps" then
    tos.apps.open("launcher")
  elseif name == "updates" then
    tos.update.open()
  end
end)
