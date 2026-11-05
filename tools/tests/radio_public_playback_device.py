#!/usr/bin/env python3
"""Silent public MP3/AAC/HLS playback through real radio-directory row taps.

No build, install, media_load, probe, click report, fake store or TLS override.
Each candidate is only a hint until matched to this run's fresh DE100 cache.
Raw cache, URLs, media dumps and logcat stay in the parent's private journal.
"""
import argparse
import json
import math
import os
from pathlib import Path
import re
import sys
import time
from urllib.parse import urlsplit
import uuid

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools/tests")]
import radio_public_metadata_device as metadata
from device_app_files import read_app_file
from device_hierarchy import node_bounds, visible_nodes
from media_device import APP, Device
from registry_device import (ExternalInput, Guard, PHYSICAL, ProbeError, TouchWatch,
                             cached_hierarchy, file_digest, quiet, require)
from ui_walkthrough import broadcast_json

local = metadata.local
GROUPS = ("mp3", "aac", "hls")
MAX_PLAY_SECONDS = 20
RADIO_OWNER = re.compile(r"radio:[0-9]{1,10}")
SAFE_MEDIA = ("status", "owner", "kind", "index", "count", "position", "source",
              "audioCodec", "audioDecoder", "sampleRate", "channels", "retry")


def health_hash(url):
    a, b = 2166136261, 5381
    for byte in url.encode("utf-8"):
        a = ((a ^ byte) * 16777619) & 0xffffffff
        b = ((b * 33) ^ byte) & 0xffffffff
    return f"{a:08x}{b:08x}"


def candidates_file(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 65536,
            "Use a bounded ordinary candidate JSON file")
    groups = json.loads(path.read_bytes())
    require(isinstance(groups, dict) and set(groups) == set(GROUPS),
            "Candidate keys must be mp3, aac and hls")
    for group in GROUPS:
        require(isinstance(groups[group], list) and 1 <= len(groups[group]) <= 3,
                "Each group needs one to three candidates")
        seen = set()
        for row in groups[group]:
            require(isinstance(row, dict) and set(row) == {"stationuuid", "url", "codec", "hls"} and
                    isinstance(row["stationuuid"], str) and metadata.UUID.fullmatch(row["stationuuid"]) and
                    row["stationuuid"] not in seen and metadata.public_address(row["url"]) and
                    isinstance(row["codec"], str) and re.fullmatch(r"[A-Za-z0-9.+_-]{1,32}", row["codec"]) and
                    type(row["hls"]) is bool and row["hls"] == (group == "hls"),
                    "Invalid or duplicate candidate; HTTPS URL, codec hint and strict HLS flag required")
            seen.add(row["stationuuid"])
    return groups


def requested_groups(value):
    groups = GROUPS if value is None else tuple(value.split(","))
    require(bool(groups) and len(groups) == len(set(groups)) and set(groups) <= set(GROUPS),
            "--groups must be hls or a comma-separated subset of mp3,aac,hls")
    return groups


def station_host(row):
    return (urlsplit(row["url"]).hostname or "").lower().rstrip(".")


class Driver(metadata.Driver):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.candidates = None
        self.groups = tuple(self.data.get("requested_groups", GROUPS))
        self.result.update(scope="Real native radio row selection and silent OS decoding/progress for public MP3/AAC/HLS",
                           playback={}, screenshot_count=0,
                           requested_groups=list(self.groups), completion_scope="Only requested_groups in this run; no prior report is merged or changed",
                           identity_basis="Fresh cache UUID+URL, same native list row title+source hostname, native radio.now station+actual container, and OS single audio queue; ICY/metadata title is not identity",
                           pending=["stream probe/health matrix"],
                           not_tested=["background playback", "atomic owner-scoped stop", "OS queue URL telemetry"],
                           playback_poll_budget_s=MAX_PLAY_SECONDS,
                           timing="20s state-poll budget; inherited single ADB/Hierarchy timeouts and guarded cleanup are separately bounded",
                           full_APP9_complete=False)

    @classmethod
    def capture(cls, *args, **kwargs):
        driver = super().capture(*args, **kwargs)
        driver.data.update(public_playback=1, controlled_rows=[], health_allowed=[], current_allowed=[], playback_intent=None, media_owner=None)
        driver.save()
        return driver

    @classmethod
    def load(cls, *args, **kwargs):
        driver = super().load(*args, **kwargs)
        rows = driver.data.get("controlled_rows")
        allowed = driver.data.get("health_allowed")
        require(driver.data.get("public_playback") == 1 and isinstance(rows, list) and len(rows) <= 100 and
                isinstance(allowed, list) and len(allowed) <= 45 and len(allowed) == len(set(allowed)),
                "Recovery requires this playback journal and bounded owned hashes")
        require(all(isinstance(row, dict) and metadata.UUID.fullmatch(row.get("uuid", "")) and
                    metadata.public_address(row.get("url")) and isinstance(row.get("title"), str) and
                    row.get("h") == health_hash(row["url"]) for row in rows),
                "Unknown controlled station identity")
        require(set(allowed) <= {row["h"] for row in rows}, "A health hash is outside this run's real directory")
        current = driver.data.get("current_allowed")
        require(isinstance(current, list) and len(current) <= 5 and set(current) <= set(allowed),
                "Unknown current attempt set")
        intent = driver.data.get("playback_intent")
        require(intent is None or isinstance(intent, dict) and set(intent) == {"uuid", "url", "title", "h"} and
                any(all(intent[key] == row.get(key) for key in intent) for row in rows),
                "Unknown playback intent")
        owner = driver.data.get("media_owner")
        require(owner is None or isinstance(owner, str) and RADIO_OWNER.fullmatch(owner), "Unknown radio instance token")
        require(tuple(driver.groups) == requested_groups(",".join(driver.groups)), "Unknown journal group selection")
        return driver

    def effective(self):
        definitions = json.loads((ROOT / "plugins/radio/manifest.json").read_text())["settings"]
        server = local.document(self.original("files/settings/server.json")).get("radio", {})
        saved = local.document(read_app_file(self.device.serial, local.MUTABLE[0])).get("radio", {})
        return {key: saved.get(key, server.get(key, field["default"])) for key, field in definitions.items()}

    def guarded(self):
        self.guard.check()
        ui = broadcast_json(self.device, "ui_state")
        require(not ui.get("safeMode") and not ui.get("onboardingOpen"), "Preserve a recovery/welcome session")
        require(all(ui.get(key) == value for key, value in self.data["expected_ui"].items()),
                "An unrelated UI hold or direction changed")
        media = broadcast_json(self.device, "media_state")
        if media.get("status") != "stopped" or any(media.get(key) for key in ("owner", "kind", "count")):
            owner = media.get("owner")
            if not (self.data.get("playback_intent") and isinstance(owner, str) and RADIO_OWNER.fullmatch(owner) and
                    media.get("kind") == "audio" and media.get("count") == 1 and media.get("index") == 1 and
                    ui.get("page") in ("radio.stations", "radio.now")):
                raise ExternalInput("Media is outside this run's single radio intent; leave it untouched")
            if self.data.get("media_owner") is None:
                self.data["media_owner"] = owner
                self.save()
            elif owner != self.data["media_owner"]:
                raise ExternalInput("The radio instance token changed during the selected session; leave it untouched")
        return ui

    def verify_owned(self):
        if self.data.get("public_playback") != 1 or not self.data.get("controlled_rows"):
            return super().verify_owned()
        # Same settings/cache/immutable checks as the metadata parent, with the
        # explicit stream-error ledger delta below. No parent/global monkeypatch.
        for path in local.IMMUTABLE:
            require(read_app_file(self.device.serial, path) == self.original(path), "An immutable private file changed")
        original = local.document(self.original(local.MUTABLE[0]))
        current = local.document(read_app_file(self.device.serial, local.MUTABLE[0]))
        allowed = [self.data["settings"], self.data.get("pending_settings")]
        restoring = self.data.get("stage") in ("restoring", "restored")
        if restoring:
            allowed.append(original)
        require(current in allowed, "Unknown settings edits appeared")
        require(all(original.get(scope) == current.get(scope) for scope in (set(original) | set(current)) - {"radio"}),
                "A non-radio setting changed")
        before_radio, after_radio = original.get("radio", {}), current.get("radio", {})
        require(all(before_radio.get(key) == after_radio.get(key) for key in
                    (set(before_radio) | set(after_radio)) - set(metadata.INPUTS)), "An unrelated radio setting changed")
        before = local.document(self.original(local.MUTABLE[2]))
        after = local.document(read_app_file(self.device.serial, local.MUTABLE[2]))
        require(set(after) <= set(before) | {"directory_cache", "health"} and all(key in after and after[key] == value
                for key, value in before.items() if key not in ("directory_cache", "health")),
                "Favorites or an unknown radio key changed; preserve the newer data")
        require(after.get("directory_cache") == self.data["committed_cache"] or
                restoring and after.get("directory_cache") == before.get("directory_cache"),
                "The committed controlled directory changed")
        raw = after.get("health", "{}")
        require(isinstance(raw, str) and len(raw.encode()) <= 81920, "Unbounded health ledger")
        old = json.loads(before.get("health", "{}"))
        now = json.loads(raw)
        require(isinstance(old, dict) and isinstance(now, dict) and len(now) <= 900 and set(old) <= set(now),
                "A health record was removed or the ledger is unknown; do not overwrite it")
        own = set(self.data["health_allowed"])
        require(set(now) <= set(old) | own and all(now[key] == value for key, value in old.items() if key not in own),
                "An unrelated health record changed")
        for key in own & set(now):
            record = now[key]
            require(isinstance(record, dict) and set(record) <= {"t", "o", "u", "f", "x", "r", "k"} and
                    all(type(record[field]) in (int, float) and math.isfinite(record[field]) and
                        0 <= record[field] <= 2**53 and int(record[field]) == record[field]
                        for field in ("t", "o", "u", "f", "r") if field in record) and
                    ("f" not in record or record["f"] <= 20) and ("x" not in record or type(record["x"]) is bool) and
                    ("k" not in record or record["k"] == "hls"),
                    "Unknown owned health record shape")
        require(all(read_app_file(self.device.serial, path) is None for path in (local.MUTABLE[1], local.MUTABLE[3])),
                "An unexpected AtomicFile backup remains")
        self.audit_preferences()

    def station(self, required=True):
        require(broadcast_json(self.device, "ui_state").get("page") == "radio.now", "A real now-playing page is required")
        _, height = self.geometry()
        names = {row["title"] for row in self.data["controlled_rows"]}
        matches = [node.get("text") for node in self.nodes() if node.get("text") in names and
                   node_bounds(node)[1] < height * .3]
        unique = len(matches) == 1
        if required:
            require(unique, "The native current station is missing or ambiguous")
        return matches[0] if unique else None

    def current_row(self, name, state):
        require(state.get("source") in ("progressive", "hls"), "The actual media container is not attributable")
        matches = [row for row in self.data["controlled_rows"] if row["title"] == name and
                   bool(row.get("hls")) == (state["source"] == "hls")]
        require(len(matches) == 1 and all(sum(row[field] == matches[0][field] for row in self.data["controlled_rows"]) == 1
                for field in ("url", "uuid", "h")), "Native station plus actual container is ambiguous")
        return matches[0]

    def stop_owned(self):
        self.guarded()
        state = broadcast_json(self.device, "media_state")
        if state.get("status") == "stopped" and not any(state.get(key) for key in ("owner", "kind", "count")):
            self.data["playback_intent"] = None
            self.data["current_allowed"] = []
            self.data["media_owner"] = None
            self.save()
            return
        require(self.data.get("playback_intent") is not None, "No owned playback intent; leave media untouched")
        if not (self.data.get("media_owner") and state.get("owner") == self.data["media_owner"] and
                state.get("kind") == "audio" and state.get("count") == state.get("index") == 1):
            raise ExternalInput("Media ownership changed; preserve the session without stopping it")
        name = self.station()
        row = self.current_row(name, state)
        require(row["h"] in self.data["current_allowed"], "Automatic playback escaped the current registered attempt set")
        self.guarded()
        state = broadcast_json(self.device, "media_state")
        require(state.get("owner") == self.data["media_owner"] and state.get("kind") == "audio" and
                state.get("count") == state.get("index") == 1 and self.station() == name and
                self.current_row(name, state)["h"] == row["h"],
                "Media ownership changed before stop; leave it untouched")
        # This debug receiver is not atomic/owner-scoped. The guard and fresh
        # current-station recheck are evidence, not a claim to remove that race.
        self.guard.check()
        self.device.debug("media_stop")
        self.wait(lambda: (value := broadcast_json(self.device, "media_state")).get("status") == "stopped" and
                  not any(value.get(key) for key in ("owner", "kind", "count")), seconds=5)
        self.data["playback_intent"] = None
        self.data["current_allowed"] = []
        self.data["media_owner"] = None
        self.save()

    def list_snapshot(self):
        require(self.guarded().get("page") == "radio.stations", "The actual station list is required")
        require(self.hierarchy is not None, "The existing native hierarchy helper is required")
        tree = self.hierarchy.tree()
        nodes = list(visible_nodes(tree))
        lists = [node for node in nodes if node.get("package") == APP and node.get("class") == "android.widget.ListView"]
        require(len(lists) == 1, "A unique native station ListView is required")
        bounds = node_bounds(lists[0])
        visible = {id(node) for node in nodes}
        known = {(row["title"], station_host(row)) for row in self.data["controlled_rows"]}
        rows = []
        # The uncompressed dump preserves each ListView's direct native Row.
        # Never join a title in one row to a hostname from a different row.
        for native_row in lists[0].findall("node"):
            labels = [node.get("text") for node in native_row.iter("node") if id(node) in visible and node.get("package") == APP and node.get("text")]
            matches = [(title, host) for title, host in known if labels.count(title) == 1 and labels.count(host) == 1]
            if len(matches) != 1:
                continue
            left, top, right, bottom = node_bounds(native_row)
            if not (bounds[0] <= left < right <= bounds[2] and bounds[1] <= top < bottom <= bounds[3]):
                continue
            title, host = matches[0]
            rows.append({"text": title, "subtitle": host, "bounds": native_row.get("bounds")})
        return bounds, rows

    def list_scroll(self, bounds, downward):
        left, top, right, bottom = bounds
        width, height = self.geometry()
        require(0 <= left < right <= width and 24 <= top < bottom <= height - 24 and
                32 <= (left + right) // 2 <= width - 32 and bottom - top >= 100,
                "Only an inset real list region may scroll")
        first, last = (.78, .25) if downward else (.25, .78)
        self.input("touchscreen", "swipe", (left + right) // 2, int(top + (bottom - top) * first),
                   (left + right) // 2, int(top + (bottom - top) * last), 280)

    def tap_station(self, item):
        # Return to the actual top without touching tabs, footer or screen edges.
        previous = None
        for _ in range(40):
            bounds, rows = self.list_snapshot()
            signature = tuple((node["text"], node["subtitle"]) for node in rows)
            if signature == previous:
                break
            previous = signature
            self.list_scroll(bounds, False)
        else:
            raise ProbeError("The bounded real list did not reach its top")
        seen, previous = set(), None
        for _ in range(100):
            bounds, rows = self.list_snapshot()
            seen.update((node["text"], node["subtitle"]) for node in rows)
            require(len(seen) <= 100, "Do not traverse beyond the fresh DE100 page")
            hits = [node for node in rows if node["text"] == item["title"] and node["subtitle"] == station_host(item)]
            if hits:
                require(len(hits) == 1, "Ambiguous native station row")
                left, top, right, bottom = node_bounds(hits[0])
                require(bounds[1] + 6 < (top + bottom) // 2 < bounds[3] - 6, "Station row center is clipped; do not tap")
                self.register(item)
                self.input("touchscreen", "tap", (left + right) // 2, (top + bottom) // 2)
                return
            signature = tuple((node["text"], node["subtitle"]) for node in rows)
            require(signature != previous, "Selected station is absent from the bounded real list")
            previous = signature
            self.list_scroll(bounds, True)
        raise ProbeError("A candidate was not found within the real DE100 list")

    def register(self, item):
        config = self.effective()
        require(config.get("test") is False and config.get("click") is False and config.get("host_cooldown") is False,
                "No probe, click or host-wide health mutation may be enabled")
        limit = config.get("failover_limit")
        require(type(limit) is int and 1 <= limit <= 5, "Unknown failover limit")
        records = json.loads(local.document(read_app_file(self.device.serial, local.MUTABLE[2])).get("health", "{}"))
        now = int(self.device.shell("date", "+%s").strip()) * 1000
        require(all(not (now - 2000 <= value.get("u", 0) <= now + 25000) for value in records.values()),
                "A cooldown boundary makes this attempt's automatic candidates uncertain")
        rows = self.data["controlled_rows"]
        index = next(index for index, row in enumerate(rows) if row["uuid"] == item["uuid"])
        ordered = rows[index:] + rows[:index]
        possible = [row for row in ordered if records.get(row["h"], {}).get("u", 0) <= now][:limit]
        require(possible and possible[0]["uuid"] == item["uuid"] and len({row["url"] for row in possible}) == len(possible),
                "Selected station is cooling down or automatic identities are ambiguous")
        self.data["health_allowed"] = sorted(set(self.data["health_allowed"]) | {row["h"] for row in possible})
        self.data["current_allowed"] = [row["h"] for row in possible]
        self.data["media_owner"] = None
        self.data["playback_intent"] = {key: item[key] for key in ("uuid", "url", "title", "h")}
        self.save()

    def play_candidate(self, group, candidate):
        self.guarded()
        self.verify_owned()
        entry = self.cache()
        matches = [row for row in entry["items"] if row["uuid"] == candidate["stationuuid"] and row["url"] == candidate["url"]]
        report = {"stationuuid": candidate["stationuuid"], "url_sha256": local.digest(candidate["url"].encode()),
                  "hint_codec": candidate["codec"], "passed": False, "observations": []}
        self.result["playback"].setdefault(group, []).append(report)
        if len(matches) != 1 or bool(matches[0].get("hls")) != candidate["hls"]:
            report["reason"] = "candidate UUID/URL/HLS flag did not match the fresh production cache"
            self.report()
            return False
        item = matches[0]
        if sum(row["title"] == item["title"] and bool(row.get("hls")) == bool(item.get("hls")) for row in entry["items"]) != 1 or \
                any(sum(row[field] == item[field] for row in entry["items"]) != 1 for field in ("url", "uuid", "h")) or \
                sum(row["title"] == item["title"] and station_host(row) == station_host(item) for row in entry["items"]) != 1:
            report["reason"] = "ambiguous directory identity"
            self.report()
            return False
        try:
            self.tap_station(item)
            started, first = time.monotonic(), None
            while time.monotonic() - started < MAX_PLAY_SECONDS:
                ui = self.guarded()
                if ui.get("page") != "radio.now":
                    time.sleep(.15)
                    continue
                current = self.station(required=False)
                if current is None:
                    time.sleep(.15)
                    continue
                state = broadcast_json(self.device, "media_state")
                facts = {key: state.get(key) for key in SAFE_MEDIA}
                error = state.get("error")
                if isinstance(error, dict):
                    facts["error_category"] = error.get("category") if error.get("category") in ("http", "network", "codec", "format", "policy") else "other"
                    facts["error_code"] = error.get("code") if type(error.get("code")) is int else None
                    facts["http_status"] = error.get("status") if type(error.get("status")) is int else None
                report["observations"].append(facts)
                require(len(report["observations"]) <= 100, "Observation count exceeds the bounded playback window")
                self.data["last_playback"] = report
                self.save()
                self.report()
                if current != item["title"] or state.get("source") != ("hls" if candidate["hls"] else "progressive"):
                    report["reason"] = "automatic failover changed the native current station or actual media container"
                    break
                ready = self.data.get("media_owner") is not None and state.get("owner") == self.data["media_owner"] and state.get("kind") == "audio" and state.get("count") == state.get("index") == 1 and state.get("status") == "playing" and bool(state.get("audioDecoder")) and state.get("sampleRate", 0) > 0
                expected = state.get("audioCodec") == "mpeg" if group == "mp3" else state.get("audioCodec") in ("mp4a-latm", "aac") if group == "aac" else state.get("source") == "hls"
                if ready and expected:
                    if first is None or state["position"] < first:
                        first = state["position"]
                    if state["position"] - first >= 1000 and self.station() == item["title"] and self.current_row(current, state)["uuid"] == item["uuid"]:
                        report.update(passed=True, position_advanced_ms=state["position"] - first,
                                      native_current_station_matched=True, actual_container_matched=True, os_single_queue_matched=True)
                        if not self.result["screenshot_count"]:
                            self.guard.check()
                            self.device.screenshot(self.output / "now-playing.png")
                            self.result["screenshot_count"] = 1
                        return True
                else:
                    first = None
                if state.get("status") in ("error", "ended", "stopped"):
                    report["reason"] = "selected stream reached an error/end/stop before qualification"
                    break
                time.sleep(.2)
            report.setdefault("reason", "20-second state-poll budget expired without required decoding/progress; individual ADB/hierarchy calls retain inherited bounds")
            return False
        except ExternalInput:
            raise
        except ProbeError:
            report.setdefault("reason", "selected candidate did not expose an unambiguous owned native row/session")
            return False
        finally:
            self.report()
            self.stop_owned()
            self.verify_owned()
            if self.guarded().get("page") == "radio.now":
                self.input("keyevent", "4")
                self.wait(lambda: broadcast_json(self.device, "ui_state").get("page") == "radio.stations", seconds=8)

    def qualify(self):
        require(self.candidates is not None, "Candidate hints are required")
        require(self.effective().get("host_cooldown") is False, "Preserve enabled host-wide health settings; do not run")
        super().qualify()
        entry = self.cache()
        rows = entry["items"]
        self.data["controlled_rows"] = rows
        self.data["committed_cache"] = local.document(read_app_file(self.device.serial, local.MUTABLE[2]))["directory_cache"]
        require(all(row.get("h") == health_hash(row["url"]) for row in rows), "Real cache health hashes do not match the production algorithm")
        self.data["stage"] = "playback"
        self.save()
        passed = {}
        for group in self.groups:
            passed[group] = False
            for candidate in self.candidates[group]:
                if self.play_candidate(group, candidate):
                    passed[group] = True
                    break
            self.result["groups"] = dict(passed)
            self.report()
        self.verify_owned()
        require(self.policy() == self.data["policy"], "Device Owner, admin, HOME or LockTask changed")
        self.check("requested groups " + ",".join(self.groups) + " actually decode and advance silently on their selected station", all(passed.values()))
        self.data["stage"] = "qualified"
        self.save()

    def restore(self):
        if self.data.get("controlled_foreground"):
            local.controlled_check(self.device, self.guard, self)
            require(self.data.get("playback_intent") is None, "An incomplete playback cannot use a stopped-process recovery window")
            if self.device.shell("pidof", APP, check=False).strip():
                media = broadcast_json(self.device, "media_state")
                require(media.get("status") == "stopped" and not any(media.get(key) for key in ("owner", "kind", "count")),
                        "Preserve media that appeared in the controlled process window")
        else:
            self.stop_owned()
        return super().restore()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=PHYSICAL)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--test-apk-sha256", required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--candidates", type=Path)
    parser.add_argument("--groups", help="comma-separated mp3,aac,hls subset; default all groups")
    parser.add_argument("--hierarchy-jar", type=Path)
    parser.add_argument("--restore", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    groups = requested_groups(args.groups)
    require(local.SHA.fullmatch(args.apk_sha256) and local.SHA.fullmatch(args.test_apk_sha256) and args.serial == PHYSICAL,
            "The fixed device and explicit installed main/test APK hashes are required")
    candidates = None if args.restore else candidates_file(args.candidates) if args.candidates else None
    require(args.restore is not None or candidates is not None, "Provide --candidates for a new run")
    for path, expected in ((args.apk, args.apk_sha256), (args.test_apk, args.test_apk_sha256)):
        if path is not None:
            require(file_digest(path) == expected, "Local APK differs from its explicit candidate hash; never install")
    jar = args.hierarchy_jar or cached_hierarchy()
    if args.restore is None:
        file_digest(jar)
    else:
        require(args.output.resolve() != args.restore.resolve() and args.restore.resolve() not in args.output.resolve().parents,
                "Output must be outside the private recovery journal")
    require(not args.output.is_symlink() and (not args.output.exists() or args.output.is_dir() and not any(args.output.iterdir())),
            "Use a fresh ordinary output directory")
    args.output.mkdir(parents=True, mode=0o700, exist_ok=True)
    device = Device(args.serial)
    require(device.shell("getprop", "ro.serialno").strip() == PHYSICAL and quiet(device), "Begin with five zero audio streams on the fixed device")
    watch, driver, failed = TouchWatch(device), None, False
    guard = Guard(device, watch)
    try:
        driver = Driver.load(device, guard, args.restore, args.output, args.apk_sha256, args.test_apk_sha256) if args.restore else Driver.capture(
            device, guard, args.output, jar, args.apk_sha256, args.test_apk_sha256)
        device.start_log(clear=False)
        if args.restore is None:
            driver.candidates = candidates
            driver.groups = groups
            driver.data["requested_groups"] = list(groups)
            driver.result["requested_groups"] = list(groups)
            driver.save()
            driver.qualify()
        elif driver.data.get("external_input"):
            guard.check()
            driver.verify_owned()
            driver.data["external_input"] = False
            driver.save()
        driver.restore()
        driver.result["complete"] = args.restore is None
    except ExternalInput as error:
        failed = True
        if driver:
            driver.data["external_input"] = True
            driver.save()
            driver.result.update(failure=str(error), recovery_required=True)
        raise
    except Exception as error:
        failed = True
        if driver:
            driver.result["failure"] = str(error)
            try:
                driver.restore()
            except Exception as recovery:
                driver.result.update(recovery_required=True, recovery_error=str(recovery))
        raise
    finally:
        try:
            if driver:
                with os.fdopen(os.open(driver.directory / ("logcat-" + uuid.uuid4().hex + ".txt"),
                                       os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
                    stream.write("\n".join(device.log_since(0)) + "\n")
                driver.result["complete"] = driver.result["complete"] and not failed and driver.result.get("restored") is True
                driver.report()
        finally:
            device.stop_log()
            watch.close()


if __name__ == "__main__":
    main()

