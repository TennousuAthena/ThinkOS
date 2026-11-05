#!/usr/bin/env python3
"""Build the fixed API27 resource overlay. Never installs or contacts a device."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "system/thinkos-btsink"
ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "moe.kiwi.thinkos.btsink.overlay"
RESOURCE_NAMES = {"profile_supported_a2dp_sink", "profile_supported_avrcp_controller"}
OVERLAY_PATH = "system/vendor/overlay/ThinkOSBluetoothSink.apk"
APK_LIMIT = 2 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def baseline():
    value = json.loads((SOURCE / "baseline.json").read_text())
    require(value["sdk"] == 27 and value["arch"] == "arm" and
            value["target_package"] == "com.android.bluetooth", "Unexpected target baseline")
    return value


def verify_source_apk(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), "Use a regular reviewed Bluetooth APK")
    require(not path.resolve().is_relative_to(ROOT), "Keep the extracted vendor APK outside the repository")
    require(0 < path.stat().st_size <= 32 * 1024 * 1024, "Bluetooth APK exceeds the build input bound")
    require(hashlib.sha256(path.read_bytes()).hexdigest() == baseline()["target_sha256"],
            "Bluetooth APK does not match the read-only device receipt")
    return path


def verify_resource_dump(text):
    for name in RESOURCE_NAMES:
        blocks = re.findall(r"^\s+resource 0x[0-9a-fA-F]+ bool/" + re.escape(name) +
                            r"\s*\n(.*?)(?=^\s+resource |\Z)", text, re.M | re.S)
        require(len(blocks) == 1, "Missing or duplicated Bluetooth boolean")
        values = re.findall(r"^\s+\(([^)]*)\)\s+(true|false)\s*$", blocks[0], re.M)
        require(values == [("", "false")], "Bluetooth boolean differs from the reviewed default")


def verify_declaration():
    manifest = ET.parse(SOURCE / "AndroidManifest.xml").getroot()
    require(manifest.attrib.get("package") == PACKAGE, "Unexpected overlay package")
    require({node.tag for node in manifest} == {"uses-sdk", "overlay", "application"} and len(manifest) == 3,
            "The resource overlay cannot declare permissions or components")
    sdk, overlay, app = manifest.find("uses-sdk"), manifest.find("overlay"), manifest.find("application")
    require(sdk.attrib == {ANDROID + "minSdkVersion": "27", ANDROID + "targetSdkVersion": "27"}, "API27 only")
    require(overlay.attrib == {ANDROID + "targetPackage": "com.android.bluetooth", ANDROID + "isStatic": "true",
                               ANDROID + "priority": "999"}, "Only the reviewed static target is permitted")
    require(app.attrib.get(ANDROID + "hasCode") == "false" and not len(app), "Overlay must contain no executable component")
    resources = ET.parse(SOURCE / "res/values/config.xml").getroot()
    require(len(resources) == 2 and {node.attrib.get("name") for node in resources} == RESOURCE_NAMES and
            all(node.tag == "bool" and (node.text or "").strip() == "true" for node in resources),
            "Only the two reviewed sink booleans may be overlaid")
    require(baseline()["target_sha256"] in (SOURCE / "customize.sh").read_text(), "Installer pin is stale")


def verify_overlay_apk(data):
    require(0 < len(data) <= APK_LIMIT, "Overlay APK exceeds its fixed bound")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and len(names) <= 32, "Duplicated or excessive APK entries")
        require({"AndroidManifest.xml", "resources.arsc"} <= set(names), "Overlay APK lacks resources")
        require(all(name in {"AndroidManifest.xml", "resources.arsc"} or
                    re.fullmatch(r"META-INF/[A-Za-z0-9_.-]+", name) for name in names),
                "Resource overlay cannot include dex, native libraries, assets, or arbitrary files")
        require(all(info.file_size <= APK_LIMIT and ((info.external_attr >> 16) & 0o170000) in (0, 0o100000)
                    for info in archive.infolist()), "Non-regular or oversized APK entry")


def package_module(apk, output):
    verify_declaration()
    verify_overlay_apk(apk)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".btsink-", suffix=".zip", delete=False) as temporary:
        pending = Path(temporary.name)
    try:
        with zipfile.ZipFile(pending, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in (("module.prop", (SOURCE / "module.prop").read_bytes()),
                               ("customize.sh", (SOURCE / "customize.sh").read_bytes()), (OVERLAY_PATH, apk)):
                entry = zipfile.ZipInfo(name, (2026, 11, 5, 0, 0, 0))
                entry.create_system = 3
                entry.external_attr = (0o100755 if name.endswith(".sh") else 0o100644) << 16
                archive.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED)
        os.replace(pending, output)
    finally:
        pending.unlink(missing_ok=True)


def sdk_tools(sdk):
    sdk = Path(sdk)
    versions = [folder for folder in (sdk / "build-tools").iterdir() if folder.is_dir() and
                re.fullmatch(r"\d+(?:\.\d+){1,2}", folder.name)]
    require(bool(versions), "Android SDK build tools are missing")
    tools = max(versions, key=lambda folder: tuple(map(int, folder.name.split("."))))
    # compileSdk's public manifest attributes; min/target remain exactly 27.
    gradle = (ROOT / "android/app/build.gradle.kts").read_text()
    compile_sdk = re.search(r"compileSdk\s*=\s*(\d+)", gradle)
    require(compile_sdk is not None, "Cannot read compileSdk")
    android_jar = sdk / "platforms" / ("android-" + compile_sdk[1]) / "android.jar"
    for path in (android_jar, tools / "aapt2", tools / "zipalign", tools / "apksigner"):
        require(path.is_file(), "Missing Android SDK build input")
    return tools, android_jar


def command(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bluetooth-apk", type=Path, required=True, help="Reviewed original APK, outside the repository")
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--debug-keystore", type=Path, default=Path.home() / ".android/debug.keystore")
    parser.add_argument("--output", type=Path, default=ROOT / "system/build/thinkos-btsink-v1.0.zip")
    args = parser.parse_args()
    original = verify_source_apk(args.bluetooth_apk)
    verify_declaration()
    sdk = args.sdk
    if sdk is None:
        properties = (ROOT / "android/local.properties").read_text()
        match = re.search(r"^sdk.dir=(.+)$", properties, re.M)
        require(match is not None, "Pass --sdk or configure android/local.properties")
        sdk = Path(match[1].strip())
    tools, android_jar = sdk_tools(sdk)
    require(args.debug_keystore.is_file() and not args.debug_keystore.is_symlink(),
            "An existing local debug keystore is required; no key is generated")
    badging = command([tools / "aapt2", "dump", "badging", original], capture_output=True, text=True).stdout
    require(re.search(r"^package: name='com\.android\.bluetooth'", badging, re.M), "Unexpected source APK package")
    dump = command([tools / "aapt2", "dump", "resources", original], capture_output=True, text=True).stdout
    verify_resource_dump(dump)
    with tempfile.TemporaryDirectory(prefix="thinkos-btsink-") as directory:
        work = Path(directory)
        compiled, unsigned, aligned, signed = [work / name for name in ("res.zip", "unsigned.apk", "aligned.apk", "overlay.apk")]
        command([tools / "aapt2", "compile", "--dir", SOURCE / "res", "-o", compiled])
        command([tools / "aapt2", "link", "-o", unsigned, "-I", android_jar, "--manifest", SOURCE / "AndroidManifest.xml",
                 "--min-sdk-version", "27", "--target-sdk-version", "27", "--no-resource-deduping", "--no-resource-removal", compiled])
        command([tools / "zipalign", "-f", "4", unsigned, aligned])
        # A prototype resource APK, never a new official key or production signing migration.
        command([tools / "apksigner", "sign", "--ks", args.debug_keystore, "--ks-key-alias", "androiddebugkey",
                 "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", signed, aligned])
        command([tools / "apksigner", "verify", signed])
        package_module(signed.read_bytes(), args.output)
    print(args.output)


if __name__ == "__main__":
    main()
