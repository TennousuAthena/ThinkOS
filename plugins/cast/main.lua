-- Casting (ADR-031): the OS receives the cast and plays it; this plugin only owns the pages that show it.
-- A film nobody is looking at keeps no decoder busy: leaving the page pauses a cast, and the sender sees it paused.
-- A shared screen (Miracast) cannot be paused: it is live. Leaving its page ends it, and the sender sees the connection close.
tos.on("page.leave", function()
  if tos.state.get("media.owner") ~= "cast" then return end
  if tos.state.get("media.mirror") then
    tos.media.stop()
    return
  end
  local status = tos.state.get("media.status")
  if status == "playing" or status == "buffering" then tos.media.pause() end
end)
