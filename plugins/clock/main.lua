-- Data layer of the clock. The OS draws the faces and ticks the time itself; this script only
-- prepares the text around them (date line, calendar card) and tells the face how to format time.
-- Every string comes from the system locale or this plugin's i18n tables, never from literals.

-- Home cards must be ready before the first ephemeris finishes on the A53. Coalesce refreshes
-- while a calculation is awaiting its result, and discard results for a superseded location.
local sunRunning, sunDirty = false, false
local moonAt = 0
-- The glyph assumes the northern hemisphere: lit side on the right while waxing. Mirror it south of the equator.
local function moonState(now)
  local ok, moon = pcall(tos.astro.moon, now)
  if not ok or type(moon) ~= "table" or not moon.phase then return { moonIcon = "moon", moonName = "—" } end
  local phase = moon.phase
  local ok2, place = pcall(tos.location.get)
  local southern = ok2 and type(place) == "table" and (place.latitude or 0) < 0
  local waxing = phase.waxing
  if southern then waxing = not waxing end
  local tens = math.floor(phase.illumination * 10 + .5) * 10
  return { moonIcon = "moon-phase-" .. (waxing and "w" or "n") .. "-" .. tostring(tens), moonName = tos.i18n.t("moon." .. phase.name) }
end
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
        local function at(ms) return ms and tos.time.format(ms, "@time", value.timezone) or "—" end
        tos.state.set {
          sun = ok and value or tos.json.null,
          sunriseText = ok and at(value.sunrise) or "—",
          sunsetText = ok and at(value.sunset) or "—",
        }
        if not ok then tos.log.warn(value) end
        -- The Moon moves a degree an hour: recompute it every ten minutes, not every tick.
        if now and (now - moonAt >= 600000 or now < moonAt) then moonAt = now; tos.state.set(moonState(now)) end
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
    tos.state.set { tileTime = "—", date = waiting, weekday = waiting, month = tos.i18n.t("calendar"), day = "—", dayNumber = "—",
      sun = tos.json.null, sunriseText = "—", sunsetText = "—", moonIcon = "moon", moonName = waiting }
    return
  end
  tos.state.set {
    tileTime = tos.time.format(now, "@time"),
    date    = tos.time.format(now, "@dateFull"),
    weekday = tos.time.format(now, "@weekday"),
    month   = tos.time.format(now, "@monthYear"),
    day     = tos.time.format(now, "@day"),
    dayNumber = tos.time.format(now, "d"),
  }
  refreshSun()
end

tos.on("start", function()
  refresh()
  tos.timer.every(60, refresh, { align = true })
end)
tos.on("config", refresh)          -- the user changed a setting
-- The system language, 12/24-hour switch or time zone changed: the Moon's name is translated text, so recompute it too.
tos.on("locale", function() moonAt = 0; refresh() end)
tos.on("location", refresh)
-- A wake or a newly entered page also refreshes immediately after time sync.
tos.on("page.enter", refresh)
tos.on("attention", refresh)
