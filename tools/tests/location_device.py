#!/usr/bin/env python3
"""Verify location/astronomy public APIs and geocoding TLS on the armv7 debug device.

    python3 -I tools/tests/location_device.py --output /tmp/thinkos-location-device

Uses a uniquely named directory plugin copied from runtime-probe. It never changes
user settings, HOME, radios or system partitions. Its own override is removed in
finally. Outgoing geocoding queries use the same tos.http API available to plugins.
"""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from urllib.parse import urlencode

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="HUA0BWUT")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/location/device-runtime")
    parser.add_argument("--endpoint", default="https://geocoding-api.open-meteo.com/v1/search")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    plugin_id = "location-probe-" + uuid.uuid4().hex[:8]
    prefix = plugin_id.upper() + " "
    base = ["adb", "-s", args.serial]
    findings = []
    evidence = {"plugin": plugin_id, "passed": findings, "complete": False}

    def adb(*values, check=True):
        result = subprocess.run(base + list(values), capture_output=True, timeout=30)
        if check and result.returncode:
            raise RuntimeError(result.stderr.decode() + result.stdout.decode())
        return result.stdout

    def logs():
        return adb("logcat", "-d", "-s", "ThinkOS:V", "AndroidRuntime:E").decode()

    def setting_bytes(name):
        # Keep only an equality result in the report; settings can contain secrets.
        return adb("exec-out", "run-as", "moe.kiwi.thinkos", "cat", "files/settings/" + name, check=False)

    def wait_for(marker, seconds=30):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            current = logs()
            if prefix + marker in current:
                return current
            time.sleep(0.25)
        raise AssertionError("Missing device result: " + prefix + marker)

    def passed(message):
        findings.append(message)
        print("PASS " + message, flush=True)

    before_settings = {name: setting_bytes(name) for name in ("local.json", "server.json")}
    initial_pid = adb("shell", "pidof", "moe.kiwi.thinkos").strip()
    assert initial_pid, "start the newly installed debug APK before running this probe"
    evidence["initialPid"] = initial_pid.decode()
    try:
        with tempfile.TemporaryDirectory(prefix="thinkos-location-device-") as temporary:
            source = Path(temporary) / plugin_id
            shutil.copytree(ROOT / "tools/tests/fixtures/runtime-probe", source)
            manifest = json.loads((source / "manifest.json").read_text())
            manifest.update(id=plugin_id, name="Location API validation")

            def push(version, code, permissions):
                manifest.update(version=version, permissions=permissions)
                (source / "manifest.json").write_text(json.dumps(manifest))
                (source / "main.lua").write_text(
                    "local prefix=" + json.dumps(prefix) + "\n" + code, encoding="utf-8")
                directory, _ = package_plugin(source, Path(temporary) / "out")
                publish_directory(directory, args.serial)

            push("1.0.0", r'''
tos.on("start", function()
  local now = 1793318400000 -- 2026-10-30 00:00 UTC, independent of the device RTC
  local denied = {
    function() return tos.location.get() end,
    function() return tos.astro.sun(now) end,
    function() return tos.astro.moon(now) end,
    function() return tos.settings.setMany("system", { ["location.latitude"] = 77 }) end,
    function() return tos.http.get("https://geocoding-api.open-meteo.com/v1/search?name=Shanghai") end,
  }
  for index, fn in ipairs(denied) do
    local ok = pcall(fn)
    assert(not ok, "missing permission unexpectedly accepted: " .. index)
  end
  -- Seasonal instants reveal no location and intentionally require no permission.
  local season = tos.astro.seasons(2026)
  assert(type(season) == "table" and season.year == 2026)
  assert(season.marchEquinox < season.juneSolstice and season.juneSolstice < season.septemberEquinox)
  assert(season.septemberEquinox < season.decemberSolstice)
  tos.log.info(prefix .. "DENIED PASSED")
end)
''', [])
            wait_for("DENIED PASSED")
            passed("location, solar, lunar, settings and network calls refuse missing permissions; seasons remain public")

            queries = ["上海", "朝阳区"]
            urls = [args.endpoint + ("&" if "?" in args.endpoint else "?") + urlencode({
                "name": query, "count": 10, "language": "zh", "format": "json"}) for query in queries]
            push("1.0.1", r'''
local now = 1793318400000
local place = {latitude=39.9042,longitude=116.4074,elevation=0,timezone="Asia/Shanghai"}
local function log(message) tos.log.info(prefix .. message) end
local function timed(label, fn)
  local before=assert(tos.time.now(), "device time must be synchronised for elapsed-time measurements")
  local result=fn()
  log(label .. " ms=" .. (tos.time.now()-before))
  return result
end
tos.on("start", function()
  local location=timed("LOCATION", tos.location.get)
  assert(type(location.name)=="string" and type(location.latitude)=="number")
  assert(type(location.longitude)=="number" and type(location.elevation)=="number")
  assert(type(location.timezone)=="string" and type(location.source)=="string")
  assert(location.latitude>=-90 and location.latitude<=90 and location.longitude>=-180 and location.longitude<=180)
  assert(location.source=="timezone" or location.source=="manual" or location.source=="manual-fallback")
  assert(not pcall(function() tos.settings.setMany("system", { ["location.latitude"]=77 }) end),
    "a development plugin must not gain settings admin even if it requests system.settings")
  assert(tos.astro.sun(0)==nil and tos.astro.moon(0)==nil, "untrusted time must not invent events")
  tos.timer.after(.1, function()
    local sun=timed("SUN COLD", function() return tos.astro.sun(now,place) end)
    assert(type(sun.start)=="number" and sun.start<=now and now<sun["end"])
    assert(type(sun.sunrise)=="number" and type(sun.sunset)=="number" and sun.sunrise<sun.noon and sun.noon<sun.sunset)
    assert(type(sun.dayLength)=="number" and sun.dayLength>0 and sun.dayLength<86400000)
    assert(type(sun.dayLengthChange)=="number" and type(sun.maximumAltitude)=="number")
    assert(type(sun.civil)=="table" and type(sun.golden)=="table" and type(sun.blue)=="table")
    assert(sun.polar=="normal" and sun.timezone=="Asia/Shanghai")
    local cached=timed("SUN CACHED", function() return tos.astro.sun(now+1000,place) end)
    assert(cached.sunrise==sun.sunrise)
    tos.timer.after(.1, function()
      local moon=timed("MOON", function() return tos.astro.moon(now,place) end)
      assert(type(moon.start)=="number" and moon.start<=now and now<moon["end"])
      assert(type(moon.rises)=="table" and type(moon.sets)=="table" and type(moon.transits)=="table")
      assert(type(moon.alwaysUp)=="boolean" and type(moon.alwaysDown)=="boolean")
      assert(type(moon.phase)=="table" and type(moon.phase.name)=="string")
      assert(moon.phase.illumination>=0 and moon.phase.illumination<=1)
      assert(moon.phase.ageDays>=0 and moon.phase.ageDays<31)
      assert(moon.phase.previousNewMoon<=now and now<moon.phase.nextNewMoon)
      local season=timed("SEASONS", function() return tos.astro.seasons(2026) end)
      assert(type(season.marchEquinox)=="number" and season.year==2026)
      log("ASTRO PASSED")
      tos.timer.after(.1, function()
        local urls=URLS
        for index,url in ipairs(urls) do
          local response,err=tos.http.get(url)
          assert(response and not err and response.status==200, "geocoding TLS/HTTP failed: " .. tostring(err))
          local body=tos.json.decode(response.body)
          assert(not body.error)
          local count=body.results and #body.results or 0
          if index==1 then assert(count>0, "Shanghai search returned no places") end
          if count>0 then
            local first=body.results[1]
            assert(type(first.latitude)=="number" and type(first.longitude)=="number" and type(first.timezone)=="string")
          end
          log("GEOCODING query=" .. index .. " status=200 results=" .. count)
        end
        log("ALL PASSED")
      end)
    end)
  end)
end)
'''.replace("URLS", "{" + ",".join(json.dumps(url) for url in urls) + "}"), ["location", "network", "system.settings"])
            wait_for("ASTRO PASSED")
            passed("location data and Sun/Moon/Seasons types, explicit place/time zone, cache and untrusted-time guard on armv7")
            current = wait_for("ALL PASSED", seconds=45)
            lines = [line.split(prefix, 1)[1] for line in current.splitlines() if prefix in line]
            evidence["deviceResults"] = lines
            evidence["geocodingQueries"] = queries
            passed("Android API-27 TLS connects directly to geocoding; Chinese city and district queries return valid responses")
            assert initial_pid == adb("shell", "pidof", "moe.kiwi.thinkos").strip(), "app restarted during the probe"
            passed("directory replacement preserves the application PID")
            evidence["complete"] = True
    finally:
        remove_plugin(plugin_id, args.serial)
        final_settings = {name: setting_bytes(name) for name in before_settings}
        evidence["settingsUnchanged"] = before_settings == final_settings
        if not evidence["settingsUnchanged"]:
            evidence["complete"] = False
        (args.output / "logcat.txt").write_text(logs(), encoding="utf-8")
        (args.output / "results.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        assert evidence["settingsUnchanged"], "settings bytes changed during the read-only probe"


if __name__ == "__main__":
    main()
