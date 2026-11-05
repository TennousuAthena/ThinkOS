-- Factory hub control flow; all installation/confirmation belongs to the mocked OS API.
assert(_VERSION=="Lua 5.4")
local handlers,state,publications,calls={},nil,0,{}
local inventory,mode={},"ok"
local alpha={id="alpha",name="Alpha",summary="Catalog entry",version="1.1.0",repo="alice/alpha",commit=string.rep("a",40),
  tier="reviewed",license="MIT",size=1024,permissions={"network"},why={network="Read public data"}}
local function remember(name,id) calls[#calls+1]={name,id} end
tos={
  on=function(name,fn)handlers[name]=fn end,
  json={decode=function()return{}end,null={}},i18n={t=function(key)return key end},
  locale=function()return{language="en",region="US",script=""}end,
  state={set=function(value)state=value;publications=publications+1 end},
  back={intercept=function()end},settings={open=function(scope)remember("settings",scope)end},
  update={status=function()return{current_version="0.1.6"}end,open=function()remember("system");return true end},
  plugins={
    installed=function()return inventory end,
    catalog=function(opts)
      assert(opts.limit<=50 and opts.offset>=0)
      if mode=="leave" then handlers["page.leave"]() end
      if mode=="unconfigured" then return{items={},total=0,configured=false,refreshing=false,error="unconfigured"} end
      if mode=="catalog-failure" then return{items={alpha},total=1,configured=true,refreshing=false,error="network"} end
      return{items={alpha},total=1,configured=true,refreshing=false,error=tos.json.null}
    end,
    install=function(id)
      remember("install",id)
      if mode=="cancel" then return false,"cancelled" end
      inventory={{id=id,name="Alpha",version="1.1.0",tier="reviewed",repo="alice/alpha",auto=true,enabled=true}}
      return true
    end,
    update=function(id)remember("update",id);return true end,
    open=function(id)remember("open",id);return mode~="deny",mode=="deny" and "denied" or nil end,
    setAuto=function(id,on)remember("auto",id);inventory[1].auto=on;return true end,
    uninstall=function(id)remember("uninstall",id);inventory={};return true end,
  },
}
assert(arg[1],"pass plugins/hub/main.lua")
assert(loadfile(arg[1]))()
local function emit(name,...)assert(handlers[name],name)(...)end
local function click(title)
  for index,row in ipairs(state.items)do if row.title==title then emit("tap","row",index);return end end
  error("Missing row: "..title)
end
local function has(title)for _,row in ipairs(state.items)do if row.title==title then return true end end;return false end
local function pass(text)print("PASS "..text)end
emit("page.enter");assert(state.tab==1 and has("Alpha") and #calls==0 and state.footer=="refresh")
pass("successful catalog with the native JSON null sentinel does not display a refresh failure")
emit("tap","row",0);emit("tap","row","1");assert(#calls==0)
click("Alpha");assert(has("source")and has("permission.network")and has("install"))
mode="cancel";click("install");assert(state.footer~="action.failed" and #inventory==0 and has("install"))
pass("discover opens provenance/permission details; cancellation is handled by OS without fake consent or installation")
mode="ok";click("install");assert(inventory[1].id=="alpha" and has("open")and not has("install"))
click("auto");assert(inventory[1].auto==false and calls[#calls][1]=="auto")
mode="deny";click("open");assert(state.footer=="action.failed")
mode="ok";click("uninstall");assert(#inventory==0 and has("Alpha"))
pass("mutations use only tos.plugins; installed controls follow actual returned OS state")
inventory={{id="offpage",name="Off-page update",version="1.0.0",available_version="2.0.0",update=true,tier="official",auto=true}}
emit("tap","tab",3);assert(has("systemUpdate")and has("Off-page update"))
click("systemUpdate");assert(calls[#calls][1]=="system")
click("Off-page update");assert(has("update"))
click("update");assert(calls[#calls][1]=="update"and calls[#calls][2]=="offpage")
emit("back");assert(has("Off-page update"))
pass("updates include installed entries outside the discover page; system updates use the native update capability")
emit("tap","tab",1)
mode="unconfigured";emit("tap","footer")
assert(state.footer=="catalog.unconfigured" and has("catalog.settings") and not has("Alpha"))
mode="catalog-failure";emit("tap","footer")
assert(state.footer=="catalog.failed" and has("Alpha"))
pass("missing configuration has its own state; a configured catalog failure remains a failure")
mode="ok";emit("tap","footer");assert(state.footer=="refresh")
local before=publications;mode="leave";emit("tap","footer")
assert(publications==before)
pass("late catalog results cannot repaint a page after it has left")
