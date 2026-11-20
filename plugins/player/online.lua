-- The source returns catalogue metadata first, then short-lived playback addresses.
local protocol = require("online_contract")
local M = {queryLength = protocol.query_length}

local function bounded(value, minimum, maximum)
  return type(value) == "string" and #value >= minimum and #value <= maximum and
    not value:find("[%z\1-\31\127]") and utf8.len(value) ~= nil
end

local function id(value)
  return bounded(value, 1, protocol.id_length) and value:match("^[A-Za-z0-9_-]+$") ~= nil
end

local function address(value)
  if not bounded(value, 1, protocol.url_length) or value:find("[%s#\\]") then return false end
  local authority = value:match("^https?://([^/?#]+)")
  return authority and not authority:find("@", 1, true)
end

function M.source(value)
  if address(value) and not value:find("?", 1, true) then return (value:gsub("/+$", "")) end
end

function M.query(value)
  if type(value) ~= "string" then return end
  value = value:match("^%s*(.-)%s*$")
  if bounded(value, 1, protocol.query_length) then return value end
end

local function encode(value)
  return (value:gsub("([^A-Za-z0-9_.~-])", function(char) return string.format("%%%02X", string.byte(char)) end))
end

local function request(base, path, field, tag)
  local response = tos.http.get(base .. path, {headers = {Accept = "application/json"},
    timeout_ms = protocol.timeout_ms, max_bytes = protocol.max_body, same_origin = true, tag = tag})
  if not response or response.status ~= 200 or type(response.body) ~= "string" or #response.body > protocol.max_body then return end
  local ok, data = pcall(tos.json.decode, response.body)
  if not ok or type(data) ~= "table" then return end
  local values = data[field]
  if type(values) ~= "table" or #values > protocol.max_tracks then return end
  -- An object with string keys must not be accepted as an empty array.
  for key in pairs(values) do
    if type(key) ~= "number" or key < 1 or key > #values or key % 1 ~= 0 then return end
  end
  return values
end

local function catalogue(base, path, tag)
  local values = request(base, path, "tracks", tag)
  if not values then return end
  local seen, tracks = {}, {}
  for _, track in ipairs(values) do
    if type(track) ~= "table" or not id(track.id) or seen[track.id] or
      not bounded(track.title, 1, protocol.title_length) or not bounded(track.artist, 0, protocol.artist_length) then return end
    if track.album ~= nil and not bounded(track.album, 0, protocol.album_length) then return end
    if track.art ~= nil and (not address(track.art) or #track.art > protocol.art_length) then return end
    local duration = track.durationMs
    if duration ~= nil and (math.type(duration) ~= "integer" or duration < 1 or duration > protocol.max_duration_ms) then return end
    seen[track.id] = true
    tracks[#tracks + 1] = {id = track.id, title = track.title, artist = track.artist, album = track.album,
      art = track.art, duration = duration}
  end
  return tracks
end

function M.browse(base, tag)
  return catalogue(base, protocol.browse_path, tag)
end

function M.search(base, query, tag)
  return catalogue(base, protocol.search_path .. "?q=" .. encode(query), tag)
end

function M.resolve(base, tracks, selected, tag)
  local ids, wanted = {}, {}
  for index, track in ipairs(tracks) do ids[index], wanted[track.id] = track.id, true end
  local values = request(base, protocol.resolve_path .. "?ids=" .. table.concat(ids, ","), "links", tag)
  if not values then return end
  local urls = {}
  for _, link in ipairs(values) do
    if type(link) ~= "table" or not id(link.id) or not wanted[link.id] or urls[link.id] or not address(link.url) then return end
    urls[link.id] = link.url
  end
  local entries, index = {}, nil
  for _, track in ipairs(tracks) do
    if urls[track.id] then
      entries[#entries + 1] = {url = urls[track.id], title = track.title, artist = track.artist, album = track.album,
        art = track.art, kind = "audio", onlineId = track.id}
      if track.id == selected then index = #entries end
    end
  end
  if index then return entries, index end
end

return M
