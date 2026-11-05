local running, dirty, trigger = false, false, "start"
local function refresh(why)
  trigger, dirty = why, true
  if running then return end
  running = true
  tos.timer.after(.05, function()
    repeat
      dirty = false
      local now = tos.time.now()
      if not now then running = false; tos.timer.after(1, function() refresh("retry") end); return end
      local place = tos.location.get()
      local sun, err = tos.astro.sun(now)
      if not sun then error(err or "Missing solar result") end
      if not dirty then
        tos.log.info("WEL_TIMEZONE " .. tos.json.encode({
          trigger = trigger, epoch = now, zone = place.timezone,
          clock = tos.time.format(now, "HHmmss"), clock_ui = tos.time.format(now, "@time"),
          date = tos.time.format(now, "yyyyMMdd"),
          sun_start = sun.start, sun_end = sun["end"], sun_zone = sun.timezone,
          sunrise = sun.sunrise, sunset = sun.sunset,
          sunrise_text = tos.time.format(sun.sunrise, "HH:mm", sun.timezone),
          sunset_text = tos.time.format(sun.sunset, "HH:mm", sun.timezone),
        }))
      end
    until not dirty
    running = false
  end)
end
tos.on("start", function() refresh("start") end)
tos.on("locale", function() refresh("locale") end)
tos.on("location", function() refresh("location") end)
