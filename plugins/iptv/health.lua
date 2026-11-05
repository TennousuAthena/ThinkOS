-- Which lines work. A line that fails is paused for a while and tried again afterwards; the pause grows with every failure in a
-- row, so a line that is gone for good costs one test a day. A record is {f = failures in a row, u = paused until (ms),
-- o = last time it worked, t = last time it was tested, x = cannot be tested here (r = ms until it is worth another look, a week
-- when absent), k = "hls" when it is a playlist}; a line with
-- no record has never been tested and counts as usable. Pure functions, no tos.* calls.
local M = {}

M.MAX_PAUSE = 24 * 3600 * 1000
local MINUTE = 60 * 1000

-- Milliseconds of pause after `fails` failures in a row, when the first one pauses `baseMinutes`: x1, x3, x9 ... up to a day.
function M.pause(fails, baseMinutes)
  local ms = baseMinutes * MINUTE
  for _ = 2, fails do
    ms = ms * 3
    if ms >= M.MAX_PAUSE then return M.MAX_PAUSE end
  end
  return math.min(ms, M.MAX_PAUSE)
end

function M.usable(record, now) return record == nil or (record.u or 0) <= now end

function M.ok(record, now)
  record = record or {}
  record.f, record.u, record.x, record.r = nil, nil, nil, nil
  record.o, record.t = now, now
  return record
end

function M.fail(record, now, baseMinutes)
  record = record or {}
  record.f = (record.f or 0) + 1
  record.u = now + M.pause(record.f, baseMinutes)
  record.t, record.x, record.r = now, nil, nil
  return record
end

-- A test that cannot tell (the line needs a protocol the test does not speak, or the network refuses the test): not a failure.
-- `retryMs` is when it is worth trying again: soon for a refusal that the user may lift (plain http from the internet is switched
-- on in the system settings), a week otherwise.
function M.unknown(record, now, retryMs)
  record = record or {}
  record.t, record.x, record.r = now, true, retryMs
  return record
end

-- Why a line should be tested now, as a rank (lower first), or nil when it need not be:
--   1 it was paused and the pause is over, 2 it never was tested, 3 the last test is older than recheckMs.
-- A line the test cannot judge is looked at again when its `r` says, after a week if it says nothing.
function M.due(record, now, recheckMs)
  if record == nil or record.t == nil then return 2 end
  if (record.u or 0) > 0 then return record.u <= now and 1 or nil end
  local age = now - record.t
  if record.x then return age >= (record.r or 7 * 24 * 3600 * 1000) and 3 or nil end
  return age >= recheckMs and 3 or nil
end

return M
