-- Bing metadata only. Image bytes, cache and decoding belong to the OS image capability.
local M={}
local function trim(value) return value:match("^%s*(.-)%s*$") end
local function text(value,limit)
  if type(value)~="string" then return "" end
  local stop=utf8.offset(value,limit+1)
  return trim(stop and value:sub(1,stop-1) or value)
end
function M.market(locale,choice)
  if choice and choice~="auto" then return choice end
  local lang,region,script=locale.language,locale.region,locale.script
  if lang=="zh" and (script=="Hans" or region=="CN" or region=="SG") then return "zh-CN" end
  local markets={es="es-ES",fr="fr-FR",pt="pt-BR",ja="ja-JP",de="de-DE"}
  return markets[lang] or (lang=="en" and region=="GB" and "en-GB" or "en-US")
end
function M.endpoint(endpoint)
  assert(type(endpoint)=="string" and #endpoint<=512 and not endpoint:find("[%s%z@?#]"),"Invalid Bing endpoint")
  local origin=endpoint:match("^(https?://[^/]+)")
  assert(origin and endpoint:sub(#origin+1):sub(1,1)=="/","Invalid Bing endpoint")
  return origin
end
function M.url(endpoint,market,index)
  M.endpoint(endpoint)
  assert(type(market)=="string" and market:match("^[a-z][a-z]%-[A-Z][A-Z]$"),"Invalid Bing market")
  assert(index==0 or index==7,"Invalid Bing archive index")
  return endpoint.."?format=js&idx="..index.."&n=8&mkt="..market
end
function M.parse(body,origin,wallpaperOnly)
  assert(type(body)=="table" and type(body.images)=="table","Invalid Bing response")
  local items={}
  for n,item in ipairs(body.images) do
    if n>8 then break end
    if type(item)=="table" and type(item.urlbase)=="string" and #item.urlbase<=512 and
      item.urlbase:match("^/th%?id=OHR%.[%w_.-]+$") and (not wallpaperOnly or item.wp~=false) then
      local stamp=type(item.fullstartdate)=="string" and item.fullstartdate or ""
      if not stamp:match("^%d%d%d%d%d%d%d%d%d%d%d%d$") then
        stamp=type(item.startdate)=="string" and item.startdate:match("^%d%d%d%d%d%d%d%d%d%d%d%d$") or
          (type(item.startdate)=="string" and item.startdate:match("^%d%d%d%d%d%d%d%d$") and item.startdate.."0000") or ""
      end
      local credit,title=text(item.copyright,512),text(item.title,512)
      if title=="" or title=="Info" then
        local at=credit:find("(©",1,true) or credit:find("（©",1,true)
        title=trim(at and credit:sub(1,at-1) or credit)
      end
      if stamp~="" and credit~="" then
        local raw=item.urlbase:sub(8)
        local id=raw:gsub("_[%a%-]+%d+$","")
        items[#items+1]={id=id,src=origin..item.urlbase.."_1920x1080.jpg",title=title,credit=credit,stamp=stamp,wallpaper=item.wp~=false}
      end
    end
  end
  return items
end
function M.restore(items,origin,limit,wallpaperOnly)
  local result={}
  if type(items)~="table" then return result end
  for n,item in ipairs(items) do
    if n>90 then break end
    if type(item)=="table" and type(item.src)=="string" and item.src:sub(1,#origin)==origin then
      local base=item.src:sub(#origin+1):match("^(/th%?id=OHR%.[%w_.-]+)_1920x1080%.jpg$")
      if base then
        local parsed=M.parse({images={{urlbase=base,title=item.title,copyright=item.credit,fullstartdate=item.stamp,wp=item.wallpaper}}},origin,wallpaperOnly)
        if parsed[1] then result[#result+1]=parsed[1] end
      end
    end
  end
  return M.merge({},result,limit)
end
function M.merge(old,fresh,limit)
  local byId={}
  for _,item in ipairs(old or {}) do byId[item.id]=item end
  for _,item in ipairs(fresh or {}) do byId[item.id]=item end
  local result={}; for _,item in pairs(byId) do result[#result+1]=item end
  table.sort(result,function(a,b) if a.stamp==b.stamp then return a.id<b.id end; return a.stamp>b.stamp end)
  local bounded,bytes={},0
  for _,item in ipairs(result) do
    -- Account for worst-case JSON escaping, including persistent metadata fields.
    local cost=(#item.id+#item.src+#item.title+#item.credit+#item.stamp)*6+160
    if #bounded>=limit or bytes+cost>180*1024 then break end
    bytes=bytes+cost; bounded[#bounded+1]=item
  end
  return bounded
end
return M
