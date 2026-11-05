-- Pure reachability ledger. Failure is a temporary observation of this device.
local M={LIMIT=900}
function M.hash(url)
  -- Two independent 32-bit accumulators keep collisions rare within the bounded ledger.
  local a,b=2166136261,5381
  for i=1,#url do local c=url:byte(i);a=((a~c)*16777619)&0xffffffff;b=((b*33)~c)&0xffffffff end
  return string.format("%08x%08x",a,b)
end
function M.pause(fails,minutes)
  local ms=minutes*60000
  for _=2,math.min(fails,20) do ms=math.min(ms*3,86400000) end
  return math.min(ms,86400000)
end
function M.usable(record,now) return not record or (record.u or 0)<=now end
function M.due(record,now,recheck)
  if not record or not record.t then return 2 end
  -- Unknown observations do not erase a genuine failure or shorten a live
  -- cooldown. Once it expires, x/r must govern instead of repeatedly treating
  -- the retained old u as a new overdue failure on every background tick.
  if (record.u or 0)>now then return nil end
  if record.x then return now-record.t>=(record.r or 604800000) and 3 or nil end
  if (record.u or 0)>0 then return 1 end
  return now-record.t>=recheck and 3 or nil
end
function M.record(record,result,now,minutes)
  record=record or {}
  if not result or result.error=="policy" or result.error=="unsupported" then
    record.t,record.x,record.r=now,true,result and result.error=="policy" and 3600000 or 604800000
    return record,"unknown"
  end
  if result.ok then return {t=now,o=now,k=result.kind=="hls" and "hls" or nil},"ok" end
  record.f=math.min((record.f or 0)+1,20);record.u=now+M.pause(record.f,minutes)
  record.t,record.x,record.r=now,nil,nil
  return record,"dead"
end
function M.hostFailure(result)
  return result and not result.ok and ({dns=true,tls=true,network=true,timeout=true})[result.error] or false
end
function M.trim(records)
  local keys={}
  for key in pairs(records) do keys[#keys+1]=key end
  table.sort(keys,function(a,b) return (records[a].t or 0)<(records[b].t or 0) end)
  for i=1,math.max(0,#keys-M.LIMIT) do records[keys[i]]=nil end
end
return M
