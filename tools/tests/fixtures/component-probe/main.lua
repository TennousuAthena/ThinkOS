local progress=25
tos.on("start",function()
  local rows={}
  for i=1,100 do rows[i]={title="Row "..i,subtitle="Scroll and tap",icon="sun",trailing=tostring(i),value=i} end
  local noon=1793332800000
  tos.state.set{
    progress=progress, lastTap="Tap a row or the ring", rows=rows,
    values={4,8,tos.json.null,6,12,10,16}, highlight=5,
    moon={phase={illumination=.25,waxing=true}}, hemisphere="south",
    sun={noon=noon,sunrise=noon-21600000,sunset=noon+21600000,altitude=25,
      maximumAltitude=60,polar="normal",civil={dawn=noon-24000000,dusk=noon+24000000},
      nautical={dawn=noon-26400000,dusk=noon+26400000},astronomical={dawn=noon-28800000,dusk=noon+28800000}}
  }
  tos.log.info("COMPONENT PROBE LOADED without location permission")
  tos.timer.after(.3,function() tos.page.focus("split") end)
  tos.timer.every(2,function()
    progress=(progress+5)%101
    tos.state.set{progress=progress}
  end)
end)
tos.on("tap",function(name,index)
  tos.state.set{lastTap=name.." "..tostring(index or "")}
  tos.log.info("COMPONENT PROBE TAP "..name.." "..tostring(index or ""))
end)
