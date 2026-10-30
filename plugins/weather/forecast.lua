-- Validate once, then retain only bounded raw SI data. No host IO or translated state here.
local M = {}
local function number(value, low, high)
  assert(type(value) == "number" and value == value and value >= low and value <= high, "invalid forecast number")
  return value
end
local function stamp(value) return number(value, 1577836800, 4102444800) end
local function code(value)
  number(value, 0, 999)
  assert(value % 1 == 0, "non-integer weather code")
  return value
end
local function object(value)
  assert(type(value) == "table", "missing forecast object")
  return value
end
local function text(value)
  assert(type(value) == "string" and #value > 0 and #value <= 160, "invalid forecast text")
  return value
end
function M.place(value)
  object(value)
  return { latitude = number(value.latitude, -90, 90), longitude = number(value.longitude, -180, 180),
    name = text(value.name), timezone = text(value.timezone) }
end
function M.same(a, b)
  return a and b and a.latitude == b.latitude and a.longitude == b.longitude and a.timezone == b.timezone
end
local fields = {
  hourly = { "temperature_2m", "precipitation_probability", "weather_code" },
  daily = { "temperature_2m_max", "temperature_2m_min", "precipitation_probability_max", "weather_code" },
}
function M.validate(body)
  object(body)
  assert(not body.error, "forecast API error")
  local current = object(body.current)
  local result = { timezone = text(body.timezone), utc_offset_seconds = number(body.utc_offset_seconds, -50400, 50400),
    current = { time = stamp(current.time), temperature_2m = number(current.temperature_2m, -100, 70),
      apparent_temperature = number(current.apparent_temperature, -150, 100),
      relative_humidity_2m = number(current.relative_humidity_2m, 0, 100),
      weather_code = code(current.weather_code), is_day = number(current.is_day, 0, 1),
      wind_speed_10m = number(current.wind_speed_10m, 0, 500) } }
  assert(current.is_day == 0 or current.is_day == 1, "invalid day flag")
  for _, kind in ipairs({ "hourly", "daily" }) do
    local source = object(body[kind])
    local times = object(source.time)
    local count = #times
    assert(count >= (kind == "hourly" and 24 or 5) and count <= (kind == "hourly" and 384 or 16), "invalid forecast length")
    local target = { time = {} }
    for _, key in ipairs(fields[kind]) do
      assert(#object(source[key]) == count, "forecast array lengths disagree")
      target[key] = {}
    end
    for i = 1, count do
      target.time[i] = stamp(times[i])
      if i > 1 then
        assert(times[i] - times[i-1] == (kind == "hourly" and 3600 or 86400), "invalid forecast time step")
      end
      for _, key in ipairs(fields[kind]) do
        local value = source[key][i]
        target[key][i] = key == "weather_code" and code(value)
          or number(value, key:find("temperature", 1, true) and -100 or 0,
            key:find("temperature", 1, true) and 70 or 100)
      end
      if kind == "daily" then assert(target.temperature_2m_min[i] <= target.temperature_2m_max[i], "reversed daily range") end
    end
    result[kind] = target
  end
  return result
end
function M.cache(value)
  object(value)
  assert(value.version == 1, "unsupported weather cache")
  assert(value.fetchedAt == nil or value.fetchedAt == tos.json.null or type(value.fetchedAt) == "number", "invalid cache time")
  return { version = 1, place = M.place(value.place), weather = M.validate(value.weather),
    fetchedAt = type(value.fetchedAt) == "number" and stamp(value.fetchedAt / 1000) * 1000 or nil }
end
function M.url(base, place)
  assert(type(base) == "string" and base:match("^https?://") and not base:find("[?#]"), "invalid forecast endpoint")
  return base .. "?latitude=" .. string.format("%.6f", place.latitude) .. "&longitude=" .. string.format("%.6f", place.longitude)
    .. "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m"
    .. "&hourly=temperature_2m,precipitation_probability,weather_code"
    .. "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
    .. "&temperature_unit=celsius&wind_speed_unit=kmh&forecast_days=5&timezone=auto&timeformat=unixtime"
end
function M.temperature(value, fahrenheit) return fahrenheit and value * 1.8 + 32 or value end
return M
