-- Casting (ADR-031): the OS receives the cast and plays it; this plugin only owns the pages that show it.
-- A film nobody is looking at keeps no decoder busy: leaving the page pauses a cast, and the sender sees it paused.
-- A shared screen (Miracast) cannot be paused: it is live. Leaving its page ends it, and the sender sees the connection close.
tos.on("page.leave", function(page)
  if page == "home" then return end
  if tos.state.get("media.owner") ~= "cast" then return end
  if tos.state.get("media.mirror") then
    tos.media.stop()
    return
  end
  local status = tos.state.get("media.status")
  if status == "playing" or status == "buffering" then tos.media.pause() end
end)

-- The waiting page: which receivers are on, and what to do on the sender. The switches are system settings, which a plugin
-- may open but not change: a tap on any row goes there. Coming back from the settings is a new page.enter, so it shows the result.
local function tr(key, params) return tos.i18n.t(key, params) end

local function render()
  local status = tos.cast.status()
  local function row(icon, title, hint, on, missing)
    local state = not on and "state.off" or missing and "state.module" or "state.on"
    return {icon = icon, title = title, subtitle = hint, trailing = tr(state)}
  end
  local footer = not status.ip and (status.dlna or status.airplay) and tr("footer.offline") or ""
  tos.state.set({
    headline = tr("home.headline", {name = status.name}),
    rows = {
      row("cast", "DLNA", tr("hint.dlna"), status.dlna),
      row("tv", "AirPlay", tr("hint.airplay"), status.airplay),
      row("radio", "Miracast", tr("hint.miracast"), status.miracast, status.miracastModule == false),
    },
    footer = footer,
  })
end

tos.on("page.enter", function(page) if page == "home" then render() end end)
tos.on("locale", render)
tos.on("cast", render)
tos.on("tap", function(name)
  if name == "settings" then tos.settings.open("cast") end
end)
tos.on("start", render)
