"""Pure host module boundaries; no SDK, key generation, Bluetooth, adb, or device."""
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SYSTEM = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("thinkos_btsink_build", SYSTEM / "btsink_build.py")
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class BluetoothSinkModuleTest(unittest.TestCase):
    def fake_apk(self, extra=None):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"host fixture only")
            archive.writestr("resources.arsc", b"no device acceptance claim")
            for name, value in (extra or {}).items():
                archive.writestr(name, value)
        return data.getvalue()

    def resource_dump(self):
        return "\n".join("    resource 0x7f01000%d bool/%s\n      () false" % (index, name)
                         for index, name in enumerate(sorted(BUILD.RESOURCE_NAMES))) + "\n"

    def test_declaration_is_only_the_two_reviewed_bools_without_code_or_permissions(self):
        BUILD.verify_declaration()
        value = BUILD.baseline()
        self.assertEqual({name: False for name in BUILD.RESOURCE_NAMES}, value["observed_default_bools"])
        self.assertEqual("0", value["bluetooth_on"])
        self.assertFalse(value["device_overlay_verified"])

    def test_source_apk_hash_and_regular_file_are_required_before_any_sdk_command(self):
        with tempfile.TemporaryDirectory() as directory:
            original = Path(directory) / "Bluetooth.apk"
            original.write_bytes(b"a different ROM")
            with self.assertRaisesRegex(ValueError, "receipt"):
                BUILD.verify_source_apk(original)
            expected = dict(BUILD.baseline(), target_sha256=hashlib.sha256(original.read_bytes()).hexdigest())
            with patch.object(BUILD, "baseline", return_value=expected):
                self.assertEqual(original, BUILD.verify_source_apk(original))
                link = Path(directory) / "link.apk"
                link.symlink_to(original)
                with self.assertRaisesRegex(ValueError, "regular"):
                    BUILD.verify_source_apk(link)

    def test_dump_requires_both_default_false_values_and_rejects_variants_and_duplicates(self):
        good = self.resource_dump()
        BUILD.verify_resource_dump(good)
        for bad in (good.replace("false", "true", 1), good + good,
                    good.replace("() false", "(land) false", 1), "",
                    good.replace("() false", "() false\n      (land) true", 1)):
            with self.assertRaises(ValueError):
                BUILD.verify_resource_dump(bad)

    def test_apk_excludes_dex_native_code_zip_slips_assets_and_symlinks(self):
        BUILD.verify_overlay_apk(self.fake_apk())
        for name in ("classes.dex", "lib/armeabi-v7a/extra.so", "lib/arm64-v8a/extra.so",
                     "../AndroidManifest.xml", "assets/boot.sh", "service.sh"):
            with self.assertRaises(ValueError):
                BUILD.verify_overlay_apk(self.fake_apk({name: b"not allowed"}))
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            info = zipfile.ZipInfo("AndroidManifest.xml")
            info.create_system = 3
            info.external_attr = 0o120777 << 16
            archive.writestr(info, b"/somewhere")
            archive.writestr("resources.arsc", b"resource")
        with self.assertRaises(ValueError):
            BUILD.verify_overlay_apk(data.getvalue())

    def test_zip_has_only_fixed_magisk_overlay_paths_and_no_boot_or_replacement_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "module.zip"
            apk = self.fake_apk()
            BUILD.package_module(apk, output)
            first = output.read_bytes()
            BUILD.package_module(apk, output)
            self.assertEqual(first, output.read_bytes())
            with zipfile.ZipFile(output) as archive:
                self.assertEqual({"module.prop", "customize.sh", BUILD.OVERLAY_PATH}, set(archive.namelist()))
                self.assertEqual(0o644, (archive.getinfo(BUILD.OVERLAY_PATH).external_attr >> 16) & 0o777)
                self.assertEqual(0o755, (archive.getinfo("customize.sh").external_attr >> 16) & 0o777)
                self.assertEqual(apk, archive.read(BUILD.OVERLAY_PATH))

    def customize(self, arch="arm", api="27", bootmode="true", digest=None, overlay=True, symlink=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "Bluetooth.apk"
            target.write_bytes(b"read-only fixture; no real ROM")
            apk = root / BUILD.OVERLAY_PATH
            apk.parent.mkdir(parents=True)
            if overlay:
                if symlink:
                    apk.symlink_to(target)
                else:
                    apk.write_bytes(self.fake_apk())
            source = (BUILD.SOURCE / "customize.sh").read_text().replace(
                "TARGET=/system/app/Bluetooth/Bluetooth.apk", "TARGET='" + str(target) + "'")
            actual = digest if digest is not None else BUILD.baseline()["target_sha256"]
            stubs = "abort() { echo \"$1\" >&2; exit 77; }\nui_print() { echo \"$1\"; }\nset_perm_recursive() { echo PERMISSION_ONLY; }\n"
            stubs += "sha256sum() { printf '%s  %s\\n' '" + actual + "' \"$1\"; }\n"
            return subprocess.run(["sh", "-c", stubs + source], text=True, capture_output=True,
                                  env=dict(os.environ, ARCH=arch, API=api, BOOTMODE=bootmode, MODPATH=str(root)))

    def test_installer_refuses_other_arch_api_recovery_and_unknown_rom(self):
        for kwargs in ({"arch": "arm64"}, {"arch": "x86"}, {"api": "26"}, {"api": "28"},
                       {"bootmode": "false"}, {"digest": "0" * 64}, {"overlay": False}, {"symlink": True}):
            result = self.customize(**kwargs)
            self.assertEqual(77, result.returncode, (kwargs, result.stderr))
            self.assertNotIn("PERMISSION_ONLY", result.stdout)

    def test_valid_installer_only_sets_permissions_and_requests_a_human_reboot(self):
        result = self.customize()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, result.stdout.count("PERMISSION_ONLY"))
        self.assertIn("Bluetooth is not enabled", result.stdout)
        self.assertIn("Human reboot required", result.stdout)


if __name__ == "__main__":
    unittest.main()
