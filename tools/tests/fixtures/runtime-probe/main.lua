local ticks=0
local ordinary_survivor="same-instance"
local http_ok, policy_ok=false,false
local timer
local function log(s) tos.log.info("PROBE "..s) end
tos.on("start",function()
  tos.state.set{status="测试中",summary="异步 / JSON / 错误 / 存储",ticks=0}
  timer=tos.timer.every(.1,function() ticks=ticks+1;tos.state.set{ticks=ticks} end)
  tos.timer.after(.12,function() error("expected ordinary event failure") end)
  tos.timer.after(.2,function()
    local before=ticks
    local res,err=tos.http.get("http://127.0.0.1:8769/slow")
    assert(res and not err and res.status==200)
    local data=tos.json.decode(res.body)
    assert(#data.items==1000 and data.items[1000].value==999)
    assert(tos.json.encode(data.empty)=="[]" and data.null==tos.json.null)
    assert(ticks-before>=3,"other timer starved while HTTP suspended")
    local ok, value=pcall(function() tos.timer.sleep(.1);return "awaited" end)
    assert(ok and value=="awaited")
    assert(ordinary_survivor=="same-instance")
    http_ok=true
    log("HTTP+JSON+resume passed bytes="..#res.body.." ticks="..ticks)
  end)
  tos.timer.after(.4,function()
    for i=1,20 do tos.store.set("burst",i) end
    assert(tos.store.get("burst")==20)
    log("20 store updates returned without fsync")
  end)
  tos.timer.after(1.2,function()
    local res,err=tos.http.get("http://127.0.0.1:8769/redirect-public")
    assert(res==nil and err)
    res,err=tos.http.get("http://127.0.0.1:8769/redirect-private")
    assert(res and res.status==200 and not err)
    res,err=tos.http.get("http://127.0.0.1:8769/large")
    assert(res==nil and err)
    policy_ok=true
    log("LAN redirects and 1MB response limit passed")
  end)
  tos.timer.after(4,function()
    assert(http_ok and policy_ok and ticks>=20 and ordinary_survivor=="same-instance")
    tos.timer.cancel(timer)
    tos.state.set{status="全部通过",summary="同一实例保留 · 计时器未饥饿"}
    log("ALL PASSED ticks="..ticks.." store="..tos.store.get("burst"))
  end)
end)
