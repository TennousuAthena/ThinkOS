tos.on("start",function()
  local photos=tos.json.decode("[]")
  for _,id in ipairs({"A","B","C"}) do photos[#photos+1]={id=id,src=tos.config.base_url.."/"..id..".png",title=id} end
  tos.state.set{photos=photos}
end)
