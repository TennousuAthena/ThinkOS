-- Open-Meteo WMO groups. Unknown future codes remain explicit, never a false clear sky.
local groups = {
  [0] = { "clear-day", "clear", "#e9b35a" },
  [1] = { "partly-day", "mainly_clear", "#e9b35a" },
  [2] = { "partly-day", "partly_cloudy", "#94a7cb" },
  [3] = { "cloud", "overcast", "#94a7cb" },
}
local function add(values, icon, key)
  for _, value in ipairs(values) do groups[value] = { icon, key, "#6e9fd6" } end
end
add({45,48}, "fog", "fog")
add({51,53,55,56,57}, "rain", "drizzle")
add({61,63,65,66,67}, "rain", "rain")
add({71,73,75,77}, "snow", "snow")
add({80,81,82}, "shower", "showers")
add({85,86}, "snow", "snow_showers")
add({95,96,97,99}, "thunder", "thunder")
return function(code, day)
  local group = groups[code]
  if not group then return "unknown", "condition.unknown", "#94a7cb", false end
  local icon, mood = group[1], group[3]
  if not day then
    if icon == "clear-day" then icon = "clear-night" end
    if icon == "partly-day" then icon = "partly-night" end
    mood = "#6678bb"
  end
  return icon, "condition." .. group[2], mood, code >= 51
end
