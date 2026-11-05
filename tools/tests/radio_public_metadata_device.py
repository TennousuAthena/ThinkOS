#!/usr/bin/env python3
"""One fixed official Radio Browser DE metadata request through production Lua.

Never taps a station, loads media, probes streams, reports clicks, starts a host
origin, adds a reverse, installs anything or changes TLS/timeouts. The local
directory driver supplies the existing input guard, snapshot and restoration.
"""
import argparse
import base64
import ipaddress
import json
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
import radio_directory_device as local
from device_app_files import read_app_file
from media_device import APP, Device
from registry_device import (ExternalInput, Guard, Hierarchy, PHYSICAL, TouchWatch, cached_hierarchy,
                             file_digest, private_json, quiet, require)
from ui_walkthrough import broadcast_json

ENDPOINT = "https://all.api.radio-browser.info"
COUNTRY = "DE"
INPUTS = {"test": False, "click": False, "country_auto": False, "country": COUNTRY,
          "directory_url": ENDPOINT, "directory": True}
UUID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")


def public_address(value):
    if not isinstance(value, str) or len(value.encode()) > 2048:
        return False
    try:
        parsed = urlsplit(value)
        host = (parsed.hostname or "").lower().rstrip(".")
        if parsed.scheme != "https" or parsed.username is not None or parsed.password is not None or parsed.fragment or not host:
            return False
        if parsed.port is not None and not 1 <= parsed.port <= 65535:
            return False
        try:
            return ipaddress.ip_address(host).is_global
        except ValueError:
            return "." in host and not any(host == suffix or host.endswith("." + suffix) for suffix in
                    ("localhost", "local", "localdomain", "internal", "home", "lan", "invalid", "test", "onion"))
    except ValueError:
        return False


class Driver(local.Driver):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.result.update({"scope": "Fixed official HTTPS/DE metadata through real Lua and native directory UI; no playback or stream probe",
                            "endpoint": ENDPOINT, "country": COUNTRY, "full_APP9_complete": False,
                            "http_evidence": {"native_http_status_measured": False, "tls_root_measured": False,
                                              "tls_version_measured": False, "http_status_inference": None},
                            "pending": ["MP3/AAC/HLS playback", "stream-probe/health matrix", "direct TLS chain/status telemetry"]})

    @classmethod
    def capture(cls, device, guard, output, jar, apk_sha, test_sha):
        # The inherited preflight has a disposable-port absence check. Select an
        # unused journal-only number; this subclass never creates any reverse.
        used = {row[0] for row in local.reverses(device)}
        unused = next((port for port in range(60000, 61000) if "tcp:" + str(port) not in used), None)
        require(unused is not None, "No unused bookkeeping port is available; no reverse will be changed")
        driver = super().capture(device, guard, output, jar, apk_sha, test_sha, unused)
        driver.data["public_metadata"] = 1
        driver.data["endpoint"] = ENDPOINT
        driver.save()
        return driver

    @classmethod
    def load(cls, *args, **kwargs):
        driver = super().load(*args, **kwargs)
        require(driver.data.get("public_metadata") == 1 and driver.data.get("endpoint") == ENDPOINT,
                "Recovery must use this fixed official-metadata journal")
        require(len(driver.data.get("actions", [])) <= 16 and all(action.get("kind") == "setting" and
                action.get("key") in INPUTS and action.get("value") == INPUTS[action["key"]]
                for action in driver.data.get("actions", [])), "Unknown official-metadata settings intent")
        return driver

    def setting(self, key, value):
        require(key in INPUTS and value == INPUTS[key], "Only the six fixed official-metadata inputs may change")
        return super().setting(key, value)

    def check(self, name, value=True):
        if name == "only the owned reverse is removed and five audio streams remain zero":
            name = "reverse inventory stays unchanged and five audio streams remain zero"
        return super().check(name, value)

    def cache(self):
        raw = read_app_file(self.device.serial, local.MUTABLE[2])
        store = local.document(raw)
        cached = json.loads(store.get("directory_cache", "{}"))
        key = "rb\n" + ENDPOINT + "\n" + COUNTRY + "\n"
        return next((entry for entry in cached.get("entries", []) if entry.get("key") == key), None)

    def verify_owned(self):
        # Endpoint is deliberately unset during the first inherited capture.
        # Do not weaken that baseline byte comparison into an endpoint wildcard.
        if self.data.get("public_metadata") != 1:
            return super().verify_owned()
        for path in local.IMMUTABLE:
            require(read_app_file(self.device.serial, path) == self.original(path), "An unrelated immutable preference changed")
        original = local.document(self.original(local.MUTABLE[0]))
        current = local.document(read_app_file(self.device.serial, local.MUTABLE[0]))
        allowed = [self.data["settings"], self.data.get("pending_settings")]
        if self.data.get("stage") in ("restoring", "restored"):
            allowed.append(original)
        require(current in allowed, "Unknown settings edits appeared during official metadata qualification")
        require(all(original.get(scope) == current.get(scope) for scope in (set(original) | set(current)) - {"radio"}),
                "A non-radio settings scope changed")
        before_radio, after_radio = original.get("radio", {}), current.get("radio", {})
        require(all(before_radio.get(key) == after_radio.get(key) for key in (set(before_radio) | set(after_radio)) - set(INPUTS)),
                "An unrelated radio setting changed")
        before = local.document(self.original(local.MUTABLE[2]))
        after = local.document(read_app_file(self.device.serial, local.MUTABLE[2]))
        require(set(after) <= set(before) | {"directory_cache"} and all(key in after and after[key] == value
                for key, value in before.items() if key != "directory_cache"),
                "Favorites, health or an unknown radio key changed; do not overwrite newer data")
        if after.get("directory_cache") != before.get("directory_cache"):
            raw = after.get("directory_cache")
            require(isinstance(raw, str) and len(raw.encode()) <= 65536, "A bounded committed directory cache is required")
            cached = json.loads(raw)
            require(set(cached) == {"v", "entries"} and cached["v"] == 1 and isinstance(cached["entries"], list) and len(cached["entries"]) <= 4,
                    "Unknown official directory cache shape")
            old = json.loads(before.get("directory_cache", '{"entries":[]}')).get("entries", [])
            for entry in cached["entries"]:
                require(isinstance(entry, dict) and set(entry) <= {"key", "items", "more", "offsets"}, "Unknown cache-entry fields appeared")
                if entry in old:
                    continue
                if entry.get("key") == "list\n\n\n":
                    require(not entry.get("items"), "Only the originally empty manual source may be refreshed")
                    continue
                require(entry.get("key") == "rb\n" + ENDPOINT + "\n" + COUNTRY + "\n", "A different host, country or category cache appeared")
                rows = entry.get("items")
                require(rows == {} or isinstance(rows, list) and len(rows) <= 100, "Official metadata is bounded to one 100-row page")
                seen = set()
                for row in rows:
                    require(isinstance(row, dict) and set(row) <= {"uuid", "url", "title", "tags", "country", "clicks", "hls", "directory", "public_only", "h"},
                            "Unknown station fields appeared")
                    require(isinstance(row.get("uuid"), str) and UUID.fullmatch(row["uuid"]) and row["uuid"] not in seen and
                            row.get("country") == COUNTRY and row.get("directory") is True and row.get("public_only") is True and
                            isinstance(row.get("title"), str) and len(row["title"].encode()) <= 120 and
                            isinstance(row.get("tags"), str) and len(row["tags"].encode()) <= 2048 and public_address(row.get("url")),
                            "Invalid or out-of-scope official station metadata")
                    seen.add(row["uuid"])
        require(all(read_app_file(self.device.serial, path) is None for path in (local.MUTABLE[1], local.MUTABLE[3])),
                "An unexpected AtomicFile backup remains")
        self.audit_preferences()

    def qualify(self):
        self.data["remote_created"] = True
        self.data["jar_sha256"] = file_digest(self.jar)
        self.save()
        self.hierarchy = Hierarchy(self.device, self.guard, self.data["token"], self.jar)
        self.overrides(dict(self.data["expected_ui"], attention="ENGAGED"))
        started = time.monotonic()
        for key, value in INPUTS.items():
            self.setting(key, value)
        self.open_radio()

        def completed():
            cached = self.cache()
            rows = cached.get("items") if cached else None
            rows = rows if isinstance(rows, list) else []
            texts = {node.get("text") for node in self.nodes()}
            observed = {"has_cache": cached is not None, "row_count": len(rows),
                        "has_refresh": "刷新" in texts, "has_list_failed": "列表加载失败" in texts,
                        "has_loading": "正在加载" in texts,
                        "first5matched": any(isinstance(row, dict) and row.get("title") in texts for row in rows[:5])}
            # Observe the footer even with no cache. Only bounded booleans/counts
            # are public; a later restoration must not erase this last diagnosis.
            self.result["last_metadata_observation"] = observed
            self.data["last_metadata_observation"] = dict(observed)
            self.save()
            self.report()
            # A cached fallback cannot qualify: production keeps list_failed
            # after any failed fetch. No tab action here clears that notice.
            return observed["has_cache"] and observed["row_count"] > 0 and observed["has_refresh"] and not observed["has_list_failed"] and not observed["has_loading"] and observed["first5matched"]

        self.wait(completed, seconds=45)
        self.wait_stable()
        entry = self.cache()
        require(entry and 0 < len(entry["items"]) <= 100, "No bounded nonempty committed official page was observed")
        self.verify_owned()
        self.check("production Lua committed a nonempty official HTTPS DE directory and displayed real station rows")
        self.check("only the fixed official endpoint, DE and no category were used", entry["key"] == "rb\n" + ENDPOINT + "\nDE\n")
        self.check("no stream probing, media session or click reporting was enabled", INPUTS["test"] is False and INPUTS["click"] is False)
        self.check("HOME, Device Owner, own admin and LockTask stay unchanged", self.policy() == self.data["policy"])
        self.result["count"] = len(entry["items"])
        self.result["uuids"] = [row["uuid"] for row in entry["items"]]
        self.result["public_station_names"] = [row["title"][:60] for row in entry["items"]
                if not any(marker in row["title"] for marker in ("://", "?", "&", "="))][:3]
        self.result["elapsed_to_committed_ui_s"] = round(time.monotonic() - started, 3)
        self.result["http_evidence"].update({"http_status_inference": 200,
                "basis": "Production M.fetch accepts only HTTP 200; a new successful render and committed official DE cache were observed",
                "timeouts_overridden": False, "tls_configuration_overridden": False,
                "elapsed_is_http_latency": False})
        self.data["stage"] = "qualified"
        self.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=PHYSICAL)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--test-apk-sha256", required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--hierarchy-jar", type=Path)
    parser.add_argument("--restore", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(local.SHA.fullmatch(args.apk_sha256) and local.SHA.fullmatch(args.test_apk_sha256) and args.serial == PHYSICAL,
            "The fixed device and two explicit lowercase APK hashes are required")
    for path, expected in ((args.apk, args.apk_sha256), (args.test_apk, args.test_apk_sha256)):
        if path is not None:
            require(file_digest(path) == expected, "A local APK differs from the explicit candidate; nothing is installed")
    jar = args.hierarchy_jar or cached_hierarchy()
    if args.restore is None:
        file_digest(jar)
    else:
        require(args.output.resolve() != args.restore.resolve() and args.restore.resolve() not in args.output.resolve().parents,
                "Public output must be outside the private journal")
    require(not args.output.is_symlink() and (not args.output.exists() or args.output.is_dir() and not any(args.output.iterdir())),
            "Use a fresh ordinary output directory")
    args.output.mkdir(parents=True, mode=0o700, exist_ok=True)
    device = Device(args.serial)
    require(device.shell("getprop", "ro.serialno").strip() == PHYSICAL and quiet(device), "Begin on the fixed device with all five streams zero")
    watch = TouchWatch(device)
    guard = Guard(device, watch)
    driver, failed = None, False
    try:
        driver = Driver.load(device, guard, args.restore, args.output, args.apk_sha256, args.test_apk_sha256) if args.restore else Driver.capture(
            device, guard, args.output, jar, args.apk_sha256, args.test_apk_sha256)
        device.start_log(clear=False)
        if args.restore is None:
            driver.qualify()
        elif driver.data.get("external_input"):
            # Only this explicit recovery invocation, after the owner has
            # returned to APP and the private-data scope still verifies, may
            # begin a new controlled stop/start window after an interruption.
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
            # Preserve a real service failure as a failure, even if restoration succeeds.
            driver.result["failure"] = str(error)
            try:
                driver.restore()
            except Exception as recovery:
                driver.result.update(recovery_required=True, recovery_error=str(recovery))
        raise
    finally:
        try:
            if driver:
                with os.fdopen(os.open(driver.directory / ("logcat-" + uuid.uuid4().hex + ".txt"), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
                    stream.write("\n".join(device.log_since(0)) + "\n")
                driver.result["complete"] = driver.result["complete"] and not failed and driver.result.get("restored") is True
                driver.report()
        finally:
            device.stop_log()
            watch.close()


if __name__ == "__main__":
    main()
