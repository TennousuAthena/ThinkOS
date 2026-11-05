-- Weather owns data only. All drawing, animation, input and settings belong to the OS.
local forecast, condition = require("forecast"), require("codes")
local raw, refreshTimer, minuteTimer, previousConfig
local attention, inflight, dirty, stopped, failed = "AWAKE", false, false, false, false
local generation, failures, relevance = 0, 0, nil
local shownTemperature
local due, awaitingClock = true, false
local refresh
local textCache, degreeCache = {}, {}
local function t(key, args)
  if args then return tos.i18n.t(key, args) end
  if not textCache[key] then textCache[key] = tos.i18n.t(key) end
  return textCache[key]
end
local function emptyArray() return tos.json.decode("[]") end
-- Tile colours by condition: every one keeps white text at 4.55:1 or better and stays off the Start backdrops.
local tileColors = {
  ["clear-day"] = "#C2410C", ["partly-day"] = "#B45309", ["clear-night"] = "#6D28D9", ["partly-night"] = "#5B21B6",
  cloud = "#475569", fog = "#0E7490", rain = "#1D4ED8", shower = "#1D4ED8", snow = "#0369A1", thunder = "#86198F",
}
local function round(value) return value < 0 and math.ceil(value - .5) or math.floor(value + .5) end
local function degrees(value)
  local rounded = round(tos.units.temperature(value))
  local key = tostring(rounded) .. tos.units.get().temperature
  if not degreeCache[key] then degreeCache[key] = t("temperature", { value = rounded }) end
  return degreeCache[key]
end
local function expired(now)
  return raw and now and raw.fetchedAt and now - raw.fetchedAt > tos.config.stale_after * 60000
end
local function interval() return tonumber(tos.config.interval) * 60 end
local function old(now)
  return not raw or not forecast.same(raw.place, tos.location.get()) or failed
    or (now and raw.fetchedAt and (now < raw.fetchedAt or now - raw.fetchedAt >= interval() * 1000))
end
local function render()
  local now, place = tos.time.now(), tos.location.get()
  local units = tos.units.get()
  local state = { temperature = "—", summary = t("waiting"), metrics = "", location = place.name,
    status = t("waiting"), stale = true, hourly = emptyArray(), days = emptyArray(), highlight = 0,
    icon = "unknown", mood = "#94a7cb", tileColor = "#475569", tileHumidity = "—", tileWind = "—", tileRain = "—", feelsValue = "—",
    tileWindLabel = t("tile.wind", { unit = t("unit." .. units.wind) }),
    hourlyTitle = t("hourly"), source = t("source"), licence = t("licence") }
  local nextRelevance = .3
  if raw then
    local weather, current = raw.weather, raw.weather.current
    local icon, key, mood, precipitation = condition(current.weather_code, current.is_day == 1)
    local same = forecast.same(raw.place, place)
    local offset = weather.utc_offset_seconds
    local reference = now and now / 1000 or current.time
    local hour = math.floor((reference + offset) / 3600) * 3600 - offset
    local day = math.floor((reference + offset) / 86400)
    state.temperature, state.icon, state.mood = degrees(current.temperature_2m), icon, mood
    state.summary = t(key)
    state.tileColor = tileColors[icon] or "#475569"
    state.tileHumidity = round(current.relative_humidity_2m) .. "%"
    state.tileWind = tostring(round(tos.units.wind(current.wind_speed_10m)))
    state.feelsValue = degrees(current.apparent_temperature)
    state.location = same and place.name or raw.place.name
    state.metrics = t("feels", { value = degrees(current.apparent_temperature) }) .. "  "
      .. t("humidity", { value = round(current.relative_humidity_2m) }) .. "  "
      .. t("wind", { value = round(tos.units.wind(current.wind_speed_10m)),
        unit = t("unit." .. units.wind) })
    for i, timestamp in ipairs(weather.daily.time) do
      if math.floor((timestamp + offset) / 86400) >= day and #state.days < 5 then
        local dailyIcon, dailyKey = condition(weather.daily.weather_code[i], true)
        local range = t("range", { high = degrees(weather.daily.temperature_2m_max[i]), low = degrees(weather.daily.temperature_2m_min[i]) })
        -- Daily boundaries use the API's fixed response offset even across DST. Format
        -- that nominal local date in UTC, rather than shifting it again through IANA.
        state.days[#state.days+1] = { title = tos.time.format((timestamp + offset) * 1000, "@weekday", "UTC"),
          subtitle = t(dailyKey) .. (weather.daily.precipitation_probability_max[i] >= 20 and
            ("  " .. t("rain_chance", { value = round(weather.daily.precipitation_probability_max[i]) })) or ""),
          trailing = range, icon = dailyIcon }
        if math.floor((timestamp + offset) / 86400) == day then
          state.summary = state.summary .. "  " .. range
          state.tileRain = round(weather.daily.precipitation_probability_max[i]) .. "%"
        end
      end
    end
    for i, timestamp in ipairs(weather.hourly.time) do
      if timestamp >= hour and #state.hourly < 24 then
        state.hourly[#state.hourly+1] = tos.units.temperature(weather.hourly.temperature_2m[i])
        if timestamp == hour then state.highlight = #state.hourly end
      end
    end
    state.stale = failed or not same or expired(now) == true or not now or not raw.fetchedAt
      or (now ~= nil and raw.fetchedAt ~= nil and now < raw.fetchedAt)
    state.status = ""
    if state.stale then
      state.status = raw.fetchedAt and t("updated", { time = tos.time.format(raw.fetchedAt, "@time", weather.timezone) }) or t("time_unknown")
      if not now then state.status = t("time_unknown")
      elseif not same then state.status = t("changing_location") .. "  " .. state.status
      elseif expired(now) then state.status = t("expired") .. "  " .. state.status
      elseif failed then state.status = t("offline") .. "  " .. state.status end
    end
    if precipitation or current.wind_speed_10m >= tos.config.high_wind
      or current.temperature_2m >= tos.config.heat or current.temperature_2m <= tos.config.cold then nextRelevance = .85
    elseif now and (reference + offset) % 86400 >= 21600 and (reference + offset) % 86400 < 32400 then nextRelevance = .5 end
  elseif failed then state.summary, state.status = t("unavailable"), ""
  elseif not now then state.status = t("time_unknown") end
  state.cardInfo = state.location .. (state.status ~= "" and ("  " .. state.status) or "")
  -- Public contract weather-now/1 (ADR-027): conditions only, never the place.
  state.public = { now = { temperature = state.temperature, icon = state.icon, condition = state.summary, stale = state.stale } }
  -- Avoid writing relevance on the per-minute timestamp updates.
  if relevance ~= nextRelevance then state.relevance = nextRelevance; relevance = nextRelevance end
  tos.state.set(state)
end
local function cancelRefresh()
  if refreshTimer then tos.timer.cancel(refreshTimer); refreshTimer = nil end
end
local function schedule(seconds)
  cancelRefresh()
  if stopped then return end
  if attention == "ASLEEP" then due = due or seconds <= .05; return end
  refreshTimer = tos.timer.after(math.max(.05, seconds), function() refreshTimer = nil; refresh() end)
end
refresh = function()
  if stopped then return end
  cancelRefresh()
  if attention == "ASLEEP" then due = true; return end
  if inflight then dirty = true; return end
  inflight, due, dirty = true, false, false
  local version, place = generation, forecast.place(tos.location.get())
  local ok, result = pcall(function()
    local response, err = tos.http.get(forecast.url(tos.config.endpoint, place))
    assert(response, err or "network error")
    assert(response.status == 200, "forecast HTTP " .. tostring(response.status))
    return forecast.validate(tos.json.decode(response.body))
  end)
  inflight = false
  if stopped then return end
  if version ~= generation then schedule(.05); return end
  if ok then
    raw = { version = 1, weather = result, place = place, fetchedAt = tos.time.now() }
    -- Keep the raw snapshot as native JSON text. Passing hundreds of scalar values through
    -- typed JNI on armv7 wastes the script's CPU budget on both write and cold-start read.
    tos.store.set("forecast", tos.json.encode(raw))
    failures, failed, awaitingClock = 0, false, not raw.fetchedAt
    tos.log.info("weather refresh ok", place.latitude, place.longitude)
    -- New numbers on the Start screen: ask the OS to pulse this plugin's tiles. Never on the first load.
    local shown = degrees(result.current.temperature_2m)
    if shownTemperature and shown ~= shownTemperature then tos.tile.animate("pulse") end
    shownTemperature = shown
  else
    failures, failed = failures + 1, true
    awaitingClock = not tos.time.now()
    tos.log.warn("weather refresh failed", result)
  end
  render()
  if dirty then schedule(.05)
  elseif awaitingClock then schedule(tos.config.clock_retry)
  elseif ok then schedule(interval())
  else schedule(math.min(tos.config.retry_initial * ({1,2,5,15})[math.min(failures, 4)], tos.config.retry_max)) end
end
tos.on("start", function()
  previousConfig = tos.config
  local cached = tos.store.get("forecast")
  if cached then
    local ok, value = pcall(function() return forecast.cache(type(cached) == "string" and tos.json.decode(cached) or cached) end)
    if ok then raw = value else tos.log.warn("discarded invalid weather cache") end
  end
  render()
  -- Never suspend start on network: hot replacement must finish before the first request.
  schedule(1)
  minuteTimer = tos.timer.every(60, function()
    render()
    if awaitingClock and tos.time.now() and not inflight then awaitingClock = false; schedule(.05) end
  end, { align = true })
end)
tos.on("config", function(cfg)
  local previous = previousConfig
  previousConfig = cfg
  render()
  if previous and cfg.endpoint ~= previous.endpoint then generation = generation + 1; failures = 0; schedule(.05)
  elseif previous and cfg.interval ~= previous.interval then
    local now = tos.time.now()
    schedule(raw and raw.fetchedAt and now and math.max(.05, interval() - (now - raw.fetchedAt) / 1000) or interval())
  end
end)
tos.on("locale", function()
  textCache, degreeCache = {}, {}
  local place = tos.location.get()
  if raw and not forecast.same(raw.place, place) then generation = generation + 1; schedule(.05) end
  render()
end)
tos.on("units", function() degreeCache = {}; render() end)
tos.on("location", function() generation = generation + 1; failures = 0; render(); schedule(.05) end)
tos.on("attention", function(level)
  local sleeping = attention == "ASLEEP"
  attention = level
  if level == "ASLEEP" then cancelRefresh()
  elseif sleeping then
    render()
    local now = tos.time.now()
    if due or awaitingClock or old(now) then schedule(.05)
    else schedule(raw and raw.fetchedAt and now and interval() - (now - raw.fetchedAt) / 1000 or interval()) end
  end
end)
tos.on("page.enter", function() render() end)
tos.on("tap", function(name) if name == "refresh" then refresh() end end)
tos.on("command", function(name) if name == "refresh" then refresh() end end)
tos.on("stop", function()
  stopped = true; cancelRefresh()
  if minuteTimer then tos.timer.cancel(minuteTimer) end
end)
