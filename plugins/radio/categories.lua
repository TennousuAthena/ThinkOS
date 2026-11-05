-- Client-owned categories. Upstream tags are data, never UI labels or code.
local M = {}
M.items = {
  {id="news", tags={"news", "noticias", "actualités"}, aliases={"talk", "news talk", "notícias", "新闻", "新闻谈话"}},
  {id="pop", tags={"pop", "pop music", "música pop"}, aliases={"musique pop", "流行"}},
  {id="rock", tags={"rock", "classic rock", "alternative rock"}, aliases={"rock alternativo", "摇滚"}},
  {id="classical", tags={"classical", "classique", "clásica"}, aliases={"classical music", "música clásica", "古典"}},
  {id="jazz", tags={"jazz", "blues", "smooth jazz"}, aliases={"jazz blues", "爵士", "蓝调"}},
  {id="dance", tags={"electronic", "dance", "electrónica"}, aliases={"electronica", "électronique", "edm", "电子", "舞曲"}},
  {id="folk", tags={"country", "folk", "folklore"}, aliases={"folk music", "música folk", "民谣", "乡村"}},
  {id="hiphop", tags={"hip hop", "hip-hop", "rap"}, aliases={"hiphop", "嘻哈", "说唱"}},
  {id="oldies", tags={"oldies", "oldies music", "nostalgia"}, aliases={"nostalgie", "怀旧", "老歌"}},
  {id="religion", tags={"christian", "religious", "gospel"}, aliases={"religion", "religiosa", "宗教"}},
  {id="sports", tags={"sports", "sport", "deportes"}, aliases={"esportes", "体育"}},
  {id="community", tags={"community", "local", "community radio"}, aliases={"communautaire", "comunitaria", "社区", "本地"}}
}
local byId, tagOwner = {}, {}
for _, item in ipairs(M.items) do
  byId[item.id] = item
  for _, tags in ipairs({item.tags, item.aliases}) do
    for _, tag in ipairs(tags) do
      assert(not tagOwner[tag], "a tag must have one category")
      tagOwner[tag] = item.id
    end
  end
end
function M.get(id) return byId[id] end
function M.match(tags)
  local found = {}
  if type(tags) ~= "string" or #tags > 2048 then return found end
  for tag in tags:lower():gmatch("[^,]+") do
    local id = tagOwner[tag:match("^%s*(.-)%s*$")]
    if id then found[id] = true end
  end
  return found
end
return M
