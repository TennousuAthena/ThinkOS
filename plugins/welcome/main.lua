-- The factory setup sequence. Navigation, controls and device capabilities belong to the OS.
local function t(key) return tos.i18n.t(key) end
local steps = { "language", "region", "timezone", "wifi", "privacy", "updates", "location", "appearance", "gestures", "welcome" }
local known = { hello=true, done=true }
for _, step in ipairs(steps) do known[step] = true end
local languages = {
  {"zh-CN", "简体中文", "你好"}, {"zh-TW", "繁體中文", "你好"}, {"en", "English", "Hello"},
  {"es", "Español", "Hola"}, {"fr", "Français", "Bonjour"}, {"pt", "Português", "Olá"},
  {"ru", "Русский", "Привет"}, {"ar", "العربية", "مرحبًا"}, {"hi", "हिन्दी", "नमस्ते"},
  {"bn", "বাংলা", "হ্যালো"}, {"ur", "اردو", "السلام علیکم"},
}
local function sameLanguage(tag, current)
  if tag == current.tag or tag == current.language then return true end
  -- The public locale API resolves Chinese script even when the system tag
  -- includes a region or explicit script (for example zh-Hans-CN/zh-Hant-HK).
  -- A different Chinese script still needs the normal system-language change.
  return current.language == "zh" and
    (tag == "zh-CN" and current.script == "Hans" or tag == "zh-TW" and current.script == "Hant")
end
local saved = tos.store.get("progress")
local progress = type(saved) == "table" and saved or {}
progress.schema = 1
progress.step = known[progress.step] and progress.step or "hello"
progress.skipped = type(progress.skipped) == "table" and progress.skipped or {}
progress.gesture = math.max(1, math.min(7, tonumber(progress.gesture) or 1))
local currentPage, visible, greeting, timer = nil, false, 1, nil
local resumeOnly = progress.resumeOnly == true
local shownFields, failure = {}, nil
local continuation = 0
local render, route, advance
local practiceNames={"swipe", "variant", "panel", "back", "home", "long-press"}
local practicing=false

local function persist() tos.store.set("progress", progress) end
local function skippedSteps()
  local list = {}
  for _, step in ipairs(steps) do if step ~= "welcome" and progress.skipped[step] then list[#list+1] = step end end
  return list
end
local function unfinished()
  -- A stored step alone may belong to an old migrated installation or demo.
  -- Only a session that actually entered onboarding creates a continuation.
  return progress.started==true and progress.step~="done"
end
local function publicState()
  local setup={pending=unfinished() or #skippedSteps()>0,skipped=skippedSteps()}
  -- Only an explicit saved choice is public; locale fallback is not a choice.
  if type(progress.region)=="string" and progress.region:match("^[A-Z][A-Z]$") then setup.region=progress.region end
  return {setup=setup}
end
local function cancelTimer() if timer then tos.timer.cancel(timer); timer=nil end end
local function stopPractice()
  if practicing then tos.onboarding.stop(); practicing=false end
end
local function startPractice()
  if progress.step == "gestures" and progress.gesture <= #practiceNames then
    practicing=true
    tos.onboarding.practice(practiceNames[progress.gesture], t("gesture."..progress.gesture))
  else stopPractice() end
end
local function link(key, label, display)
  return {key=key, type="link", label=label, display=display or ""}
end
local function online()
  local device = tos.device.get()
  return device.online == true or (device.wifi or {}).connected == true
end
local function region()
  local value = progress.region or tos.locale().region or ""
  return value:match("^[A-Z][A-Z]$") and value or "CN"
end
local function zones() return tos.tz.zones(region()) or {} end
local function applicable(step)
  if step == "wifi" then return not online() end
  if step == "timezone" then return tos.device.canSetTimeZone() and #zones() > 1 end
  return true
end
local function eligible(step)
  return applicable(step) and (not resumeOnly or step == "welcome" or progress.skipped[step] == true)
end
local function indexOf(step)
  for i, value in ipairs(steps) do if value == step then return i end end
  return 0
end
local function write(key, value)
  local result, err = tos.settings.set("system", key, value)
  if err then failure=t("error"); render(); return false end
  return result ~= nil
end
local function finish()
  if not write("onboarding.done", tos.onboarding.status().version) then return end
  for _, step in ipairs(steps) do if not applicable(step) then progress.skipped[step]=nil end end
  progress.resumeOnly=false
  progress.started=false
  progress.step="done"; persist(); render()
  tos.back.intercept(false)
  tos.page.release(currentPage or "hello")
end
local function jump(step)
  failure=nil; progress.step=step; persist(); route()
end
advance = function(skip)
  local step = progress.step
  if skip and step ~= "hello" and step ~= "welcome" and applicable(step) then progress.skipped[step]=true
  elseif step ~= "hello" then progress.skipped[step]=nil end
  local start = indexOf(step)
  for _, value in ipairs(steps) do if not applicable(value) then progress.skipped[value]=nil end end
  for i=start+1,#steps do if eligible(steps[i]) then jump(steps[i]); return end end
  finish()
end
local function previous()
  failure=nil
  if progress.step == "hello" then
    persist(); tos.back.intercept(false); tos.page.release(currentPage or "hello"); return
  end
  if progress.step == "gestures" and progress.gesture > 1 then
    progress.gesture=progress.gesture-1; persist(); route(); return
  end
  for i=indexOf(progress.step)-1,1,-1 do if eligible(steps[i]) then jump(steps[i]); return end end
  if resumeOnly then persist(); tos.back.intercept(false); tos.page.release(currentPage or "setup")
  else jump("hello") end
end
local function skipAll()
  for _, step in ipairs(steps) do if step ~= "welcome" and applicable(step) then progress.skipped[step]=true end end
  finish()
end
local function languageTag(language, country)
  local script = language.script
  return language.language .. (script and script ~= "" and "-" .. script or "") .. "-" .. country
end
local function setTimeZoneOrOpenDate(id, step, generation)
  -- A cached capability does not guarantee the ROM accepts this ID or that
  -- the updater still answers. Android owns the fallback and the actual zone.
  local function current()
    return visible and currentPage == "setup" and progress.step == step and continuation == generation
  end
  if not current() then return false end
  local applied = tos.device.setTimeZone(id)
  -- The native await can return after Home, Back or a new page.enter. Never
  -- reopen Android or advance a different visit from an old RPC continuation.
  if not current() then return false end
  if not applied then tos.device.openAndroid("date") end
  return true
end
local function changeRegion(value)
  if type(value) ~= "string" or not value:match("^[A-Z][A-Z]$") then return end
  local generation = continuation
  progress.region=value; persist()
  -- Region defaults must not replace an existing preference elsewhere (§4.6).
  if (value == "US" or value == "LR" or value == "MM") and not write("units.system", "imperial") then return end
  local locale = tos.locale()
  if locale.region ~= value and tos.device.canSetLocale() then tos.device.setLocale(languageTag(locale, value)) end
  local entries = zones()
  if #entries == 1 and tos.device.canSetTimeZone() then
    if not setTimeZoneOrOpenDate(entries[1].id, "region", generation) then return end
  end
  if not visible or currentPage ~= "setup" or progress.step ~= "region" or continuation ~= generation then return end
  advance(false)
end

render = function()
  -- A system description includes every translated field. Read it only once
  -- for this publication; the next render gets fresh values and locale labels.
  local fieldsByKey
  local function descriptor(key)
    if not fieldsByKey then
      fieldsByKey={}
      for _, field in ipairs(tos.settings.get("system").fields or {}) do fieldsByKey[field.key]=field end
    end
    return fieldsByKey[key]
  end
  local step = progress.step
  local state = { step=step, title=t(step), subtitle=failure or "", primary=t("continue"), secondary="", back=t("back"),
                  fields={}, hero="", greeting=languages[greeting][3], public=publicState() }
  shownFields = {}
  if step == "language" then
    local current = tos.locale()
    local function add(item) state.fields[#state.fields+1]=link("language:"..item[1], item[2]) end
    for _, item in ipairs(languages) do if sameLanguage(item[1], current) then add(item) end end
    for _, item in ipairs(languages) do if not sameLanguage(item[1], current) and item[1]:match("^[a-z]+") == current.language then add(item) end end
    for _, item in ipairs(languages) do if item[1]:match("^[a-z]+") ~= current.language then add(item) end end
  elseif step == "region" then
    state.fields[1]={key="region", type="choice", source="region", label=t("region"), value=region(), display=region()}
  elseif step == "timezone" then
    for _, entry in ipairs(zones()) do state.fields[#state.fields+1]=link("timezone:"..entry.id, entry.city, entry.time) end
  elseif step == "wifi" then
    state.subtitle=failure or t(online() and "wifi.connected" or "wifi.disconnected")
    state.primary=t(online() and "continue" or "connect")
    state.secondary=t("skip")
  elseif step == "privacy" then
    state.subtitle=failure or t("privacy.body"); state.secondary=t("licenses")
  elseif step == "updates" then
    state.subtitle=failure or t("updates.body"); state.secondary=t("downloadOnly")
  elseif step == "location" then
    local field = descriptor("location.name")
    if field then state.fields[#state.fields+1]=field end
    state.fields[#state.fields+1]=link("action:timezone", t("changeTimeZone"), tos.tz.home())
    state.hero=tos.time.format(tos.time.now(), "@time"); state.secondary=t("skip")
  elseif step == "appearance" then
    for _, key in ipairs {"theme.mode", "redshift.trigger", "display.orientation"} do
      local field=descriptor(key); if field then state.fields[#state.fields+1]=field end
    end
  elseif step == "gestures" then
    state.instruction=progress.gesture > #practiceNames and t("gesture."..progress.gesture) or ""
    state.gestureNext=progress.gesture > #practiceNames and t("continue") or ""
    state.gestureSkip=progress.gesture <= #practiceNames and t("skip") or ""
  elseif step == "welcome" then
    state.primary=t("start"); state.back=t("back")
  end
  for _, field in ipairs(state.fields) do shownFields[field.key]=field end
  tos.state.set(state)
end

route = function()
  continuation=continuation+1
  cancelTimer()
  stopPractice()
  local target = progress.step == "hello" and "hello" or progress.step == "gestures" and "gestures" or "setup"
  render()
  if not visible then return end
  if currentPage ~= target then tos.page.focus(target); return end
  tos.back.intercept(true)
  startPractice()
  if progress.step == "hello" then
    timer=tos.timer.every(tos.onboarding.status().greeting_seconds, function()
      if visible and progress.step == "hello" then greeting=greeting % #languages+1; render() end
    end)
  elseif progress.step == "location" then
    timer=tos.timer.every(60, function() if visible and progress.step == "location" then render() end end, {align=true})
  end
end

tos.on("page.enter", function(page, variant, params)
  currentPage=page; visible=true
  if params and params.mode == "restart" then
    -- Re-running on an already configured device starts an unfinished session,
    -- so Home and a later process restart can resume it instead of skipping it.
    local _, err = tos.settings.setMany("system", {["onboarding.done"]=0, ["onboarding.attempts"]=0})
    if err then failure=t("error"); render(); return end
    resumeOnly=false; progress={schema=1,step="hello",skipped={},gesture=1,resumeOnly=false,started=true}; persist()
  elseif params and params.mode == "resume" then
    if unfinished() then
      -- Home/back is a pause, including the required Home lesson. Keep the
      -- exact screen and lesson; a finished session still resumes only skips.
      resumeOnly=progress.resumeOnly==true
    else
      for _, step in ipairs(steps) do if not applicable(step) then progress.skipped[step]=nil end end
      resumeOnly=#skippedSteps() > 0
      progress.resumeOnly=resumeOnly
      if not resumeOnly then finish(); return end
      for _, step in ipairs(steps) do if eligible(step) then progress.step=step; break end end
    end
    progress.started=true;persist()
  elseif progress.step == "done" then
    progress.step="hello"; progress.started=true; persist()
  else
    -- Also activates legacy progress only when the OS really enters it.
    progress.started=true;persist()
  end
  route()
end)
tos.on("page.leave", function() continuation=continuation+1; visible=false; cancelTimer(); stopPractice(); persist() end)
tos.on("stop", function() continuation=continuation+1; visible=false; cancelTimer(); stopPractice(); persist() end)
tos.on("back", function() if visible then previous() end end)
tos.on("locale", function() if visible then render() end end)
tos.on("device", function() if visible and progress.step == "wifi" then render() end end)
tos.on("location", function() if visible and progress.step == "location" then render() end end)

local function primary()
  if progress.step == "welcome" then finish()
  elseif progress.step == "wifi" and not online() then tos.device.openAndroid("wifi")
  elseif progress.step == "updates" then if write("update.auto_install", true) then advance(false) end
  elseif progress.step == "region" then changeRegion(region())
  elseif progress.step == "gestures" then
    if progress.gesture > #practiceNames then advance(false) end
  else advance(false) end
end
local function secondary()
  if progress.step == "privacy" then tos.settings.about()
  elseif progress.step == "updates" then if write("update.auto_install", false) then advance(false) end
  else advance(true) end
end
tos.on("tap", function(name, action)
  if not visible then return end
  if name == "hello.next" or name == "hello.skip" or name == "hello.accessibility" then
    if currentPage~="hello" or progress.step~="hello" then return end
    if name=="hello.next" then advance(false)
    elseif name=="hello.skip" then skipAll()
    else tos.device.openAndroid("accessibility") end
  elseif name == "gesture.next" or name == "gesture.skip" or name == "gesture.back" then
    if currentPage~="gestures" or progress.step~="gestures" then return end
    if name=="gesture.next" then primary() elseif name=="gesture.skip" then secondary() else previous() end
  elseif name == "setup" then
    if currentPage~="setup" or progress.step=="hello" or progress.step=="gestures" or progress.step=="done" then return end
    if action == "primary" then primary() elseif action == "secondary" then secondary() elseif action == "back" then previous() end
  end
end)
tos.on("practice", function(name)
  if not visible or progress.step ~= "gestures" or name ~= practiceNames[progress.gesture] then return end
  progress.gesture=progress.gesture+1; persist()
  if name == "home" then
    -- Home is the global exit. Persist the next lesson without taking the screen back.
    return
  end
  render(); startPractice()
end)
tos.on("form.change", function(key, value)
  if not visible or currentPage~="setup" or progress.step=="hello" or progress.step=="gestures" or progress.step=="done" then return end
  if progress.step == "language" then
    local tag=key:match("^language:(.+)$")
    if not tag then return end
    local current=tos.locale()
    if sameLanguage(tag, current) then advance(false)
    elseif tos.device.setLocale(tag) then advance(false)
    else tos.device.openAndroid("locale") end
    return
  end
  if progress.step == "region" and key == "region" then changeRegion(value); return end
  if progress.step == "timezone" then
    local id=key:match("^timezone:(.+)$")
    if id and setTimeZoneOrOpenDate(id, "timezone", continuation) then advance(false) end
    return
  end
  if progress.step == "location" and key == "action:timezone" then tos.device.openAndroid("date"); return end
  if not shownFields[key] then return end
  if progress.step == "location" and key == "location.name" and type(value) == "table" then
    local patch={}
    for _, part in ipairs {"mode", "name", "latitude", "longitude", "elevation", "timezone"} do
      if value[part] ~= nil then patch["location."..part]=value[part] end
    end
    local _, err=tos.settings.setMany("system", patch)
    failure=err and t("error") or nil
  elseif progress.step == "appearance" then write(key, value) end
  render()
end)

-- Publish the continuation row without taking over the screen at plugin startup.
render()

