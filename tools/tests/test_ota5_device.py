"""Host preflight: real signed input fixtures and SDK checks, fake read-only ADB.

The saved roots observations here are synthetic parser inputs, not a claim that
Android loaders/device OTA acceptance ran. No sockets, devices or package code
are executed. Disposable crypto/APK construction reuses test_ota_lan fixtures.
"""
import argparse
import contextlib
import copy
import io
import json
from pathlib import Path
import shlex
import subprocess
import unittest
from unittest.mock import patch

import ota5_device as driver
import test_ota_lan as signed_fixture


class ReadOnlyAdb:
    def __init__(self, apk_sha, physical='SPARE123'):
        self.apk_sha, self.physical, self.calls = apk_sha, physical, []
        self.owner = '  Device Owner: \n    admin=ComponentInfo{moe.kiwi.thinkos/moe.kiwi.thinkos.admin.ThinkOSAdminReceiver}\n'
        self.volume = '0'

    def __call__(self, argv, *, capture_output, timeout, env):
        self.calls.append(list(argv))
        assert argv[:3] == ['adb', '-s', 'spare-endpoint'] and argv[3:5] == ['shell', '-T']
        assert env['ADB_MDNS'] == '0' and capture_output and timeout == 30
        command = shlex.split(argv[5])
        if command == ['getprop', 'ro.serialno']: output = self.physical + '\n'
        elif command == ['getprop', 'ro.build.version.sdk']: output = '27\n'
        elif command == ['getprop', 'ro.product.cpu.abi']: output = 'armeabi-v7a\n'
        elif command == ['pm', 'path', driver.APP]: output = 'package:/data/app/moe.kiwi.thinkos-fixture/base.apk\n'
        elif command == ['sha256sum', '/data/app/moe.kiwi.thinkos-fixture/base.apk']: output = self.apk_sha + '  /data/app/moe.kiwi.thinkos-fixture/base.apk\n'
        elif command == ['dumpsys', 'device_policy']: output = self.owner
        elif command == ['cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME']:
            output = driver.APP + '/.MainActivity\n'
        elif len(command) == 5 and command[:3] == ['media', 'volume', '--stream'] and command[3] in '12345' and command[4] == '--get':
            output = '[v] volume is ' + self.volume + '\n'
        else:
            raise AssertionError('Unexpected or mutating ADB command: ' + repr(command))
        return subprocess.CompletedProcess(argv, 0, output.encode(), b'')


class Ota5PreflightTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        signed_fixture.OtaLanTest.setUpClass()

    @classmethod
    def tearDownClass(cls):
        signed_fixture.OtaLanTest.tearDownClass()

    def setUp(self):
        self.material = signed_fixture.OtaLanTest('test_plan_uses_actual_signed_apk_without_opening_key_or_creating_output')
        self.material.setUp()
        self.addCleanup(self.material.doCleanups)
        self.addCleanup(self.material.tearDown)
        m = self.material
        m.catalog['release']['modules'] = []
        m.seal()
        self.prepared_result = m.execute()
        self.result_path = m.work / 'executed-lan-result.json'
        self.result_path.write_text(json.dumps(self.prepared_result))
        self.baseline = m.make_apk(m.work / 'baseline', version='0.1.0', code=1000)
        actual = driver.release.inspect_apk(self.baseline, m.signer, '0.1.0', tools=m.tools)
        apk = {**actual, 'package': driver.APP, 'debuggable': False, 'target_sdk': 27}
        assets = {name: {'present': True, 'sha256': driver.ota_lan.digest(m.public / name), 'bytes': (m.public / name).stat().st_size}
                  for name in ('keys.json', 'system-keys.json')}
        assets['debug-keys.json'] = {'present': False}
        self.observation = {'phase': 'production_roots', 'complete': True, 'sdk': 27,
            'checks': [{'name': 'installed target APK loads its own independent deployment roots', 'passed': True}],
            'production_roots': {'schema': 1, 'complete': True, 'scope': 'installed_apk_assets_and_production_key_loaders_only',
                'count_basis': 'validated_APK_asset_declarations_after_actual_loaders',
                'declared_role_counts': {'channel': 2, 'registry': 2, 'system': 2}, 'update_keys_load_ok': True,
                'system_keys_load_ok': True, 'six_roles_independent': True, 'reg_fixture_namespace_present': False,
                'apk_stable_during_probe': True, 'failures': [], 'probe_writes_files': False, 'probe_changes_settings': False,
                'probe_starts_runtime': False, 'probe_calls_installer': False, 'apk': apk, 'assets': assets}}
        self.observed_path = m.work / 'saved-roots.json'
        self.save_observation()
        self.args = argparse.Namespace(serial='spare-endpoint', physical_serial='SPARE123', lan_dir=m.work / 'lan',
            lan_result=self.result_path, public_dir=m.public, baseline_apk=self.baseline, baseline_version='0.1.0',
            baseline_kind='release', roots_observation=self.observed_path, execute_preflight=False)

    def save_observation(self):
        self.observed_path.write_text(json.dumps(self.observation))

    def prepare(self, args=None):
        return driver.prepare(args or self.args, source_root=self.material.fixture_source, tools=self.material.tools)

    def argv(self):
        result = []
        for name in ('serial', 'physical_serial', 'lan_dir', 'lan_result', 'public_dir', 'baseline_apk', 'baseline_version', 'roots_observation'):
            result.extend(('--' + name.replace('_', '-'), str(getattr(self.args, name))))
        return result

    def test_real_argv_plan_validates_actual_signatures_and_apks_without_any_adb_or_origin_start(self):
        output = io.StringIO()
        with patch.object(driver.subprocess, 'run', wraps=driver.subprocess.run) as calls, \
                patch.object(driver.release, 'sign', side_effect=AssertionError('Preflight tried to sign')), \
                patch.object(driver.ota_lan, 'read_password', side_effect=AssertionError('Preflight requested a private password')), \
                contextlib.redirect_stdout(output):
            driver.main(self.argv(), prepare_scenario=self.prepare, device_factory=lambda _: self.fail('Default plan used ADB'))
        value = json.loads(output.getvalue())
        self.assertEqual(('planned', False, False), (value['stage'], value['device_observed'], value['origin_started']))
        self.assertEqual((1000, 2000), (value['baseline']['code'], value['target']['code']))
        self.assertTrue(value['owner_public_hashes_compared'])
        self.assertFalse(value['ota5_device_acceptance_complete'])
        self.assertFalse(any(str(call.args[0][0]) == 'adb' for call in calls.call_args_list))

    def test_execute_preflight_only_uses_fixed_reads_and_matches_installed_observed_baseline(self):
        prepared = self.prepare()
        adb = ReadOnlyAdb(prepared.baseline['sha256'])
        with contextlib.redirect_stdout(io.StringIO()) as output:
            driver.main(self.argv() + ['--execute-preflight'], prepare_scenario=self.prepare,
                        device_factory=lambda serial: driver.ReadOnlyDevice(serial, run=adb))
        value = json.loads(output.getvalue())
        self.assertEqual('preflight_complete', value['stage'])
        self.assertTrue(value['device_observed'])
        self.assertFalse(value['device_changed'] or value['origin_started'] or value['installed'])
        self.assertEqual(15, len(adb.calls))

    def test_protected_physical_alias_stops_before_any_other_device_read(self):
        prepared = self.prepare()
        adb = ReadOnlyAdb(prepared.baseline['sha256'], physical='HUA0BWUT')
        with self.assertRaises(ValueError): prepared.preflight(driver.ReadOnlyDevice('spare-endpoint', run=adb))
        self.assertEqual(1, len(adb.calls))
        for name, value in (('serial', 'HUA0BWUT'), ('physical_serial', 'HUA0BWUT'), ('physical_serial', 'unknown')):
            args = copy.copy(self.args); setattr(args, name, value)
            with self.assertRaises(ValueError): self.prepare(args)

    def test_actual_installed_hash_owner_and_audio_unknowns_fail_without_mutation_or_retry(self):
        prepared = self.prepare()
        for bad in ('hash', 'owner', 'volume'):
            adb = ReadOnlyAdb(prepared.baseline['sha256'])
            if bad == 'hash': adb.apk_sha = '0' * 64
            elif bad == 'owner': adb.owner = '  Active admin: ComponentInfo{moe.kiwi.thinkos/moe.kiwi.thinkos.admin.ThinkOSAdminReceiver}\n'
            else: adb.volume = '1'
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                prepared.preflight(driver.ReadOnlyDevice('spare-endpoint', run=adb))
            self.assertLess(len(adb.calls), 15)

    def test_saved_loader_hash_and_scope_failures_never_reach_device_factory(self):
        for bad in ('load', 'asset', 'apk', 'debug', 'scope', 'count', 'complete'):
            original = copy.deepcopy(self.observation)
            value = self.observation['production_roots']
            if bad == 'load': value['system_keys_load_ok'] = False
            elif bad == 'asset': value['assets']['keys.json']['sha256'] = '0' * 64
            elif bad == 'apk': value['apk']['sha256'] = '0' * 64
            elif bad == 'debug': value['assets']['debug-keys.json']['present'] = True
            elif bad == 'scope': value['probe_calls_installer'] = True
            elif bad == 'count': value['declared_role_counts']['system'] = 3
            else: self.observation['complete'] = False
            self.save_observation()
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                driver.main(self.argv() + ['--execute-preflight'], prepare_scenario=self.prepare,
                            device_factory=lambda _: self.fail('Invalid proof reached device'))
            self.observation = original

    def test_saved_instrumentation_stdout_is_parsed_once_and_never_executed(self):
        self.observed_path.write_text('INSTRUMENTATION_STATUS: ignored=fixture\nINSTRUMENTATION_RESULT: result=' + json.dumps(self.observation) + '\nINSTRUMENTATION_CODE: 0\n')
        prepared = self.prepare()
        self.assertEqual(1000, prepared.baseline['code'])
        self.observed_path.write_text(self.observed_path.read_text() + 'INSTRUMENTATION_RESULT: result={}\n')
        with self.assertRaises(ValueError): self.prepare()

    def test_caller_updated_hash_table_cannot_replace_channel_signature(self):
        catalog_path = self.args.lan_dir / 'beta.json'
        document = json.loads(catalog_path.read_bytes()); document['seq'] += 1
        catalog_path.write_bytes(driver.release.json_bytes(document))
        changed = copy.deepcopy(self.prepared_result)
        changed.update(seq=document['seq'], catalog_sha256=driver.ota_lan.digest(catalog_path))
        changed['output_sha256']['beta.json'] = changed['catalog_sha256']
        self.result_path.write_text(json.dumps(changed))
        with self.assertRaises(ValueError): self.prepare()

    def test_real_system_signature_is_rejected_even_with_approved_result_hashes(self):
        m = self.material
        catalog = self.args.lan_dir / 'beta.json'
        signature = driver.release.sign(catalog, m.private / 'system-active.key.pem', m.system['active'], m.environment)
        (self.args.lan_dir / 'beta.json.sig').write_bytes(signature)
        changed = copy.deepcopy(self.prepared_result)
        changed['output_sha256']['beta.json.sig'] = driver.hashlib.sha256(signature).hexdigest()
        self.result_path.write_text(json.dumps(changed))
        with self.assertRaises(ValueError): self.prepare()

    def test_actual_owner_signed_wrong_package_is_rejected_after_valid_channel_authentication(self):
        m = self.material
        wrong = m.make_apk(m.work / 'wrong-package', package='moe.kiwi.thinkos.wrongfixture')
        target = self.args.lan_dir / 'thinkos-0.2.0.apk'
        target.write_bytes(wrong.read_bytes())
        path = self.args.lan_dir / 'beta.json'
        catalog = json.loads(path.read_bytes())
        catalog['release'].update(sha256=driver.ota_lan.digest(target), size=target.stat().st_size)
        path.write_bytes(driver.release.json_bytes(catalog))
        signature = driver.release.sign(path, m.private / 'channel-active.key.pem', m.keys['channel']['active'], m.environment)
        (self.args.lan_dir / 'beta.json.sig').write_bytes(signature)
        changed = copy.deepcopy(self.prepared_result)
        for name in ('beta.json', 'beta.json.sig', 'thinkos-0.2.0.apk'):
            changed['output_sha256'][name] = driver.ota_lan.digest(self.args.lan_dir / name)
        changed['catalog_sha256'] = changed['output_sha256']['beta.json']
        self.result_path.write_text(json.dumps(changed))
        with self.assertRaises(ValueError): self.prepare()


if __name__ == '__main__':
    unittest.main()
