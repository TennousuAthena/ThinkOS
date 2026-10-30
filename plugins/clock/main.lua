-- Data layer of the clock. The OS draws the faces and ticks the time itself; this script only
-- prepares the text around them (date line, calendar card) and tells the face how to format time.
-- Every string comes from the system locale or this plugin's i18n tables, never from literals.

-- Home cards must be ready before the first ephemeris finishes on the A53. Coalesce refreshes
-- while a calculation is awaiting its result, and discard results for a superseded location.
local sunRunning, sunDirty = false, false
local function refreshSun()
  sunDirty = true
  if sunRunning then return end
  sunRunning = true
  tos.timer.after(.05, function()
    repeat
      sunDirty = false
      local now = tos.time.now()
      local ok, value = pcall(function() return now and tos.astro.sun(now) end)
      if not sunDirty then
        tos.state.set { sun = ok and value or tos.json.null }
        if not ok then tos.log.warn(value) end
      end
    until not sunDirty
    sunRunning = false
  end)
end

local function refresh()
  tos.state.set { timeFormat = tos.config.seconds and "@timeSeconds" or "@time" }
  local now = tos.time.now()
  if not now then
    local waiting = tos.i18n.t("waiting")
    tos.state.set { tileTime = "—", date = waiting, weekday = waiting, month = tos.i18n.t("calendar"), day = "—", sun = tos.json.null }
    return
  end
  tos.state.set {
    tileTime = tos.time.format(now, "@time"),
    date    = tos.time.format(now, "@dateFull"),
    weekday = tos.time.format(now, "@weekday"),
    month   = tos.time.format(now, "@monthYear"),
    day     = tos.time.format(now, "@day"),
  }
  refreshSun()
end

tos.on("start", function()
  refresh()
  tos.timer.every(60, refresh, { align = true })
end)
tos.on("config", refresh)          -- the user changed a setting
tos.on("locale", refresh)          -- the system language, 12/24-hour switch or time zone changed
tos.on("location", refresh)
-- A wake or a newly entered page also refreshes immediately after time sync.
tos.on("page.enter", refresh)
tos.on("attention", refresh)
