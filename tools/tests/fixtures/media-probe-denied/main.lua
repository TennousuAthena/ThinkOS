-- No media, library or network permission: every media door must stay shut, and say why.
local function log(s) tos.log.info("MEDIA-PROBE-DENIED " .. s) end
local failed = 0
local function check(name, ok, detail)
  if ok then log("PASS " .. name) else failed = failed + 1; log("FAIL " .. name .. " " .. tostring(detail)) end
end
tos.on("start", function()
  tos.timer.after(1, function()
    local state, err = tos.media.load({{url = "http://127.0.0.1:8099/files/audio-mp3-cbr192.mp3", kind = "audio"}}, {})
    check("load without the media permission is refused", state == nil and tostring(err):find("media permission", 1, true), err)
    state, err = tos.media.pause()
    check("pause without the media permission is refused", state == nil and err ~= nil, err)
    local ok = pcall(tos.state.get, "media.status")
    check("the media state cannot be read without the permission", not ok)
    local listing
    listing, err = tos.library.list({kind = "audio"})
    check("the library cannot be listed without the library permission", listing == nil and err ~= nil, err)
    log("DONE failed=" .. failed)
  end)
end)
