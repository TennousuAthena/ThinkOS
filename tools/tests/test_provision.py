import contextlib
import base64
import io
import json
from pathlib import Path
import subprocess
import struct
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import provision


class DeviceVerificationTest(unittest.TestCase):
    def properties(self):
        return {'ro.lineage.version': provision.EXPECTED_LINEAGE,
                'ro.build.version.sdk': '27', 'ro.product.cpu.abilist': 'armeabi-v7a',
                'ro.build.fingerprint': 'Lenovo/CD-18781Y/CD-18781Y:8.1.0/OPM1:user/release-keys'}

    def test_the_stock_fingerprint_does_not_override_the_real_lineage_property(self):
        self.assertTrue(all(provision.checks(self.properties()).values()))
        bad = self.properties()
        bad['ro.lineage.version'] = ''
        bad['ro.build.fingerprint'] = 'lineage_starfire-userdebug 8.1.0'
        self.assertFalse(provision.checks(bad)['lineage_version'])

    def test_wrong_system_version_or_absent_properties_are_rejected(self):
        for key, value in (('ro.lineage.version', '15.1-different-starfire'), ('ro.build.version.sdk', '28')):
            properties = self.properties(); properties[key] = value
            self.assertFalse(all(provision.checks(properties).values()))
        self.assertFalse(any(provision.checks({}).values()))

    def test_only_armv7_and_its_optional_armeabi_alias_are_accepted(self):
        for value in ('armeabi-v7a', 'armeabi-v7a,armeabi'):
            properties = self.properties(); properties['ro.product.cpu.abilist'] = value
            self.assertTrue(provision.checks(properties)['armv7_only'])
        for value in ('', 'armeabi', 'x86', 'arm64-v8a,armeabi-v7a', 'armeabi-v7a,armeabi-v7a', 'armeabi-v7a,'):
            properties = self.properties(); properties['ro.product.cpu.abilist'] = value
            self.assertFalse(provision.checks(properties)['armv7_only'])

    def test_dry_run_batches_fixed_read_only_commands_without_adb_or_file_writes(self):
        with tempfile.TemporaryDirectory() as directory, patch('provision.subprocess.run') as run:
            output = Path(directory) / 'must-not-exist.json'
            stream = io.StringIO()
            with contextlib.redirect_stdout(stream):
                self.assertEqual(0, provision.main(['verify', '--serial', 'FIRST', '--serial', 'SECOND', '--serial', 'FIRST',
                                                   '--dry-run', '--output', str(output)]))
            run.assert_not_called(); self.assertFalse(output.exists())
            commands = json.loads(stream.getvalue())['commands']
            self.assertEqual(2 * len(provision.PROPERTIES), len(commands))
            self.assertTrue(all(command[3:5] == ['shell', 'getprop'] for command in commands))

    def test_device_read_failure_is_a_failure_even_after_some_good_properties(self):
        with patch('provision.subprocess.run', side_effect=subprocess.TimeoutExpired('adb', 15)):
            report = provision.verify('FIRST')
        self.assertFalse(report['ok']); self.assertIn('error', report)

    def test_subprocesses_receive_argument_lists_with_no_shell(self):
        properties = self.properties()
        def response(command, **kwargs):
            self.assertIsInstance(command, list)
            self.assertNotIn('shell', kwargs)
            self.assertEqual('0', kwargs['env']['ADB_MDNS'])
            return subprocess.CompletedProcess(command, 0, properties.get(command[-1], '') + '\n', '')
        with patch('provision.subprocess.run', side_effect=response):
            self.assertTrue(provision.verify('FIRST')['ok'])


class LocalProvisioningTest(unittest.TestCase):
    """Only temporary public-format fixtures; no real key generation or devices."""
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='thinkos local provision ')
        self.work = Path(self.temporary.name).resolve()

    def tearDown(self):
        self.temporary.cleanup()

    def put(self, name, raw):
        path = self.work / name
        path.write_bytes(raw)
        return path

    def json(self, name, value):
        return self.put(name, json.dumps(value, sort_keys=True).encode())

    def release_args(self, operation='batch'):
        apk = self.work / 'owner release.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('lib/armeabi-v7a/libthinkos.so', b'fixture')
        elf = bytearray(20); elf[:6] = b'\x7fELF\x01\x01'; elf[18:20] = b'\x28\x00'
        updater = self.work / 'updater.zip'
        with zipfile.ZipFile(updater, 'w') as archive:
            archive.writestr('module.prop', 'id=thinkos-updater\nversion=v4.2\nversionCode=42\n')
            archive.writestr('bin/updaterd', elf)
        manifest = self.put('updater.manifest', provision.release_tool.module_manifest(
            'thinkos-updater', 'magisk', 42, 'v4.2', provision.digest(updater), updater.stat().st_size))
        signature = self.put('updater.sig', b'\x30fixture-DER-not-a-real-signature')
        prefix = bytes.fromhex('3059301306072a8648ce3d020106082a8648ce3d03010703420004')
        def key(number):
            return {'id': 'fixture-' + str(number), 'algorithm': 'SHA256withECDSA',
                    'spki': base64.b64encode(prefix + bytes([number]) * 64).decode()}
        keyring = self.json('public.json', {'schema': 1, 'active': key(1), 'backup': key(2)})
        return [operation, '--serial', 'FIRST', '--serial', 'SECOND', '--serial', 'FIRST',
                '--apk', str(apk), '--apk-sha256', provision.digest(apk), '--signer-sha256', 'a' * 64,
                '--updater', str(updater), '--updater-sha256', provision.digest(updater),
                '--updater-manifest', str(manifest), '--updater-signature', str(signature),
                '--system-keyring', str(keyring), '--language-permission', 'grant', '--device-owner', 'skip']

    def invoke(self, argv):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = provision.main(argv)
        return code, json.loads(output.getvalue())

    def reject(self, argv):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            provision.main(argv)
        self.assertEqual(2, error.exception.code)

    def golden_args(self):
        partitions = [{'name': name, 'physical_partition': 0, 'start_sector': 34 + i, 'sectors': 1}
                      for i, name in enumerate(sorted(provision.REQUIRED_IMAGES | {'persist'}))]
        record = {'schema': 1, 'sector_size': 512, 'disk_sectors': 200, 'partitions': partitions}
        reference = self.json('reference-gpt.json', record)
        target = self.json('target-gpt.json', record)
        entries = {}
        for name in provision.REQUIRED_IMAGES:
            image = self.put(name + '.img', bytes([len(name)]) * 512)
            entries[name] = {'file': image.name, 'sha256': provision.digest(image), 'size': 512, 'format': 'raw'}
        manifest = self.json('golden.json', {'schema': 1, 'lineage': provision.EXPECTED_LINEAGE,
            'reference_gpt_sha256': provision.digest(reference), 'device_owner': 'none',
            'scrubbed': {key: True for key in provision.SCRUB_CHECKS}, 'images': entries})
        loader = self.put('loader.mbn', b'local reviewed loader')
        return ['golden-plan', '--images', str(self.work), '--manifest', str(manifest),
                '--reference-gpt', str(reference), '--target-gpt', str(target),
                '--output-xml', str(self.work / 'review.xml'), '--device-owner', 'none',
                '--loader', str(loader), '--loader-sha256', provision.digest(loader)]

    def test_batch_defaults_to_dry_run_even_without_the_flag(self):
        args = self.release_args()
        output = self.work / 'not-written.json'
        with patch('provision.subprocess.run') as run, patch('provision.release_tool.run') as host:
            code, report = self.invoke(args + ['--output', str(output)])
        run.assert_not_called(); host.assert_not_called()
        self.assertEqual(0, code); self.assertFalse(output.exists())
        self.assertEqual(['FIRST', 'SECOND'], report['serials'])
        self.assertTrue(report['dry_run']); self.assertFalse(report['complete'])
        self.assertIn('pending', report['inputs']['apk']['metadata'])
        self.assertEqual('v4.2', report['inputs']['updater']['version'])
        writes = [item for item in report['steps'] if 'owner' in item['step'] or 'reboot' in item['step']]
        self.assertTrue(all(item['manual'] for item in writes))

    def test_full_install_includes_every_manual_stage_and_verifies_fixed_sources(self):
        args = self.release_args('install') + ['--resources', str(self.work), '--dry-run']
        source = self.put('source.zip', b'pinned resource')
        with patch.dict(provision.RESOURCES, {source.name: provision.digest(source)}, clear=True), patch('provision.subprocess.run') as run:
            _, report = self.invoke(args)
        run.assert_not_called()
        names = {item['step'] for item in report['steps']}
        self.assertTrue({'verify-external-resources', 'manual-firmware-baseline', 'manual-recovery-lineage-magisk',
                         'manual-framework-and-system-modules', 'updater-owner-install',
                         'offline-onboarding-and-system-acceptance', 'gpl-installation-information'} <= names)

    def test_bad_hash_and_symlink_resource_fail_without_subprocesses(self):
        resource = self.put('resource', b'good')
        with patch.dict(provision.RESOURCES, {'resource': '0' * 64}, clear=True), patch('provision.subprocess.run') as run:
            self.reject(['resources', '--directory', str(self.work), '--dry-run'])
        run.assert_not_called()
        link = self.work / 'link'; link.symlink_to(resource)
        with self.assertRaises(ValueError):
            provision.checked_file(link, provision.digest(resource))

    def test_language_and_management_decisions_are_required_and_never_implied(self):
        args = self.release_args()
        position = args.index('--device-owner')
        self.reject(args[:position] + args[position + 2:])
        position = args.index('--language-permission')
        self.reject(args[:position] + args[position + 2:])
        self.reject(args + ['--dry-run', '--apply-app'])

    def test_formal_system_keyring_is_flat_and_rejects_legacy_or_ambiguous_shapes(self):
        for mode in ('nested', 'boolean', 'extra', 'duplicate-key'):
            args = self.release_args()
            path = Path(args[args.index('--system-keyring') + 1])
            value = json.loads(path.read_text())
            if mode == 'nested': value = {'schema': 1, 'system': {slot: value[slot] for slot in ('active', 'backup')}}
            elif mode == 'boolean': value['schema'] = True
            elif mode == 'extra': value['registry'] = {}
            else: value['backup'] = value['active']
            path.write_text(json.dumps(value))
            with patch('provision.subprocess.run') as run:
                self.reject(args + ['--dry-run'])
            run.assert_not_called()

    def test_wrong_raw_module_manifest_and_arm64_are_rejected(self):
        args = self.release_args()
        manifest = Path(args[args.index('--updater-manifest') + 1]); manifest.write_bytes(manifest.read_bytes().replace(b'code=42', b'code=43'))
        with patch('provision.subprocess.run') as run:
            self.reject(args)
        run.assert_not_called()
        args = self.release_args()
        apk = Path(args[args.index('--apk') + 1])
        with zipfile.ZipFile(apk, 'a') as archive:
            archive.writestr('lib/arm64-v8a/other.so', b'bad')
        args[args.index('--apk-sha256') + 1] = provision.digest(apk)
        self.reject(args)

    def test_signature_and_apk_preflight_fail_before_any_adb(self):
        args = self.release_args() + ['--apply-app']
        with patch('provision.release_tool.verify', side_effect=ValueError('bad signature')), patch('provision.subprocess.run') as run:
            self.reject(args)
        run.assert_not_called()
        with patch('provision.release_tool.verify'), patch('provision.release_tool.inspect_apk', side_effect=ValueError('debuggable APK')), patch('provision.subprocess.run') as run:
            self.reject(args)
        run.assert_not_called()

    def test_apply_only_executes_fixed_app_commands_after_host_checks(self):
        args = self.release_args() + ['--apply-app']
        commands = []
        def run(command, **kwargs):
            self.assertIsInstance(command, list); self.assertNotIn('shell', kwargs)
            commands.append(command)
            return subprocess.CompletedProcess(command, 0, 'Success\n', '')
        good = lambda serial: {'serial': serial, 'ok': True, 'checks': {}, 'properties': {}}
        with patch('provision.release_tool.verify'), patch('provision.release_tool.inspect_apk', return_value={'version': '4.2.0', 'code': 4002000}), patch('provision.verify', side_effect=good), patch('provision.subprocess.run', side_effect=run):
            code, report = self.invoke(args)
        self.assertEqual(0, code); self.assertTrue(report['app_phase_only']); self.assertFalse(report['complete'])
        self.assertEqual(8, len(commands))
        self.assertTrue(all(command[0] == 'adb' and not any(word in command for word in ('su', 'edl', 'reboot', 'clear', 'set-device-owner', 'uninstall')) for command in commands))
        self.assertEqual('4.2.0', report['inputs']['apk']['version'])

    def test_existing_report_is_refused_before_device_writes(self):
        args = self.release_args() + ['--apply-app', '--output', str(self.put('existing.json', b'preserve'))]
        with patch('provision.subprocess.run') as run:
            self.reject(args)
        run.assert_not_called(); self.assertEqual(b'preserve', (self.work / 'existing.json').read_bytes())

    def test_host_preflight_reads_actual_apk_metadata_without_any_device_phase(self):
        args = self.release_args() + ['--check-host']
        with patch('provision.release_tool.verify') as signature, patch('provision.release_tool.inspect_apk', return_value={'version': '4.2.0', 'code': 4002000}), patch('provision.verify') as device, patch('provision.apply_apps') as apply:
            _, report = self.invoke(args)
        signature.assert_called_once(); device.assert_not_called(); apply.assert_not_called()
        self.assertTrue(report['host_only']); self.assertEqual('4.2.0', report['inputs']['apk']['version'])
        self.assertFalse(report['complete'])

    def test_golden_default_dry_run_checks_all_images_but_writes_nothing(self):
        with patch('provision.subprocess.run') as run:
            _, report = self.invoke(self.golden_args() + ['--dry-run'])
        run.assert_not_called(); self.assertFalse((self.work / 'review.xml').exists())
        self.assertTrue(report['gpt_match']); self.assertFalse(report['xml_written'])
        self.assertNotIn('label="persist"', report['rawprogram'])
        self.assertNotIn('patch0.xml', report['steps'][1]['command'])

    def test_golden_can_only_create_new_external_review_xml_never_flash(self):
        args = self.golden_args() + ['--write-plan']
        with patch('provision.subprocess.run') as run:
            _, report = self.invoke(args)
            self.reject(args)
        run.assert_not_called(); self.assertTrue(report['xml_written'])
        self.assertEqual(report['rawprogram'], (self.work / 'review.xml').read_text())
        self.reject(self.golden_args() + ['--execute-flash'])

    def test_gpt_mismatch_overlapping_and_forbidden_partitions_fail_closed(self):
        args = self.golden_args()
        target = self.work / 'target-gpt.json'; value = json.loads(target.read_text()); value['disk_sectors'] += 1
        target.write_text(json.dumps(value)); self.reject(args)
        args = self.golden_args()
        value = json.loads(target.read_text()); value['partitions'][1]['start_sector'] = value['partitions'][0]['start_sector']
        target.write_text(json.dumps(value)); self.reject(args)
        args = self.golden_args()
        manifest = self.work / 'golden.json'; value = json.loads(manifest.read_text()); value['images']['persist'] = value['images']['boot']
        manifest.write_text(json.dumps(value)); self.reject(args)

    def test_image_hash_size_privacy_and_path_traversal_are_not_ignored(self):
        for field, value in [('sha256', '0' * 64), ('size', 513), ('file', '../boot.img')]:
            args = self.golden_args(); path = self.work / 'golden.json'; document = json.loads(path.read_text())
            document['images']['boot'][field] = value; path.write_text(json.dumps(document)); self.reject(args)
        args = self.golden_args(); path = self.work / 'golden.json'; document = json.loads(path.read_text())
        document['scrubbed']['wifi'] = False; path.write_text(json.dumps(document)); self.reject(args)
        args = self.golden_args(); args[args.index('--device-owner') + 1] = 'preserved'; self.reject(args)

    def test_sparse_bounds_are_verified_without_expanding_or_running_tools(self):
        header = struct.pack('<I4H4I', 0xed26ff3a, 1, 0, 28, 12, 512, 1, 1, 0)
        image = self.put('sparse.img', header + struct.pack('<2H2I', 0xcac3, 0, 1, 12))
        self.assertEqual(512, provision.expanded_size(image, 'android-sparse'))
        with self.assertRaises(ValueError):
            provision.expanded_size(image, 'raw')
        image.write_bytes(header + struct.pack('<2H2I', 0xcac1, 0, 1, 524))
        with self.assertRaises(ValueError):
            provision.expanded_size(image, 'android-sparse')

    def test_capture_plan_does_not_reset_or_read_a_device(self):
        loader = self.put('loader.mbn', b'local reviewed loader')
        with patch('provision.subprocess.run') as run:
            _, report = self.invoke(['capture-plan', '--images', str(self.work / 'future-image'),
                '--loader', str(loader), '--loader-sha256', provision.digest(loader), '--serial', 'FIRST',
                '--device-owner', 'none', '--dry-run'])
        run.assert_not_called(); self.assertFalse((self.work / 'future-image').exists())
        self.assertTrue(all(item['manual'] for item in report['steps']))
        self.assertFalse(report['complete'])


if __name__ == '__main__':
    unittest.main()
