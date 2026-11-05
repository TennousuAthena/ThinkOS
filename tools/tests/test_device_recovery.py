"""Recovery must never apply another device's bytes or discard an unverified journal."""
import json
from pathlib import Path
import shutil
import stat
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import device_recovery as recovery


class FakeDevice:
    serial = 'test-transport'
    physical = recovery.PHYSICAL
    def __init__(self): self.mutations = []; self.page = ''; self.page_attempts = 0; self.delayed_pages = 0
    def shell(self, *args):
        if args == ('getprop', 'ro.serialno'): return self.physical
        self.mutations.append(args)
        return ''
    def debug(self, *args, **kwargs):
        self.mutations.append((args, kwargs))
        if args == ('page',):
            self.page_attempts += 1
            if self.page_attempts > self.delayed_pages: self.page = kwargs['value']
        if args == ('ui_state',): return 'data="'+json.dumps({'page': self.page})+'"'
        return ''


class DeviceRecoveryTest(unittest.TestCase):
    def test_safe_mode_preferences_are_an_explicit_restorable_scope(self):
        self.files.update(dict(zip(recovery.RECOVERY_PATHS, [b'<map><boolean name="safe_mode" value="false"/></map>', None])))
        original = dict(self.files)
        journal = recovery.capture(self.device, self.ui, recovery.PATHS + recovery.RECOVERY_PATHS)
        for path in recovery.RECOVERY_PATHS: self.files[path] = b'temporary safe-mode fixture'
        result = recovery.restore(journal, self.device)
        self.assertTrue(all(result['restored_files'].values()))
        self.assertEqual(original, self.files)

    def setUp(self):
        self.device = FakeDevice()
        self.files = dict(zip(recovery.PATHS, [b'\x00private bytes\xff', None, b'<deck/>', b'']))
        self.original = dict(self.files)
        self.ui = dict(locale='en-US', theme=None, attention=None, lux=None, orientation=10, page='clock.face')
        self.read = patch.object(recovery, 'read_app_file', side_effect=lambda serial, path: self.files[path]).start()
        self.write = patch.object(recovery, 'restore_app_file', side_effect=lambda serial, path, raw: self.files.__setitem__(path, raw)).start()
        self.addCleanup(patch.stopall)

    def journal(self):
        directory = recovery.capture(self.device, self.ui)
        self.addCleanup(lambda: shutil.rmtree(directory, ignore_errors=True))
        return directory

    def test_exact_binary_empty_and_missing_files_round_trip_privately(self):
        directory = self.journal()
        self.assertEqual(0o700, stat.S_IMODE(directory.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE((directory/'state.json').stat().st_mode))
        self.files.update({p: b'changed' for p in recovery.PATHS})
        result = recovery.restore(directory, self.device)
        self.assertEqual(self.original, self.files)
        self.assertTrue(all(result['restored_files'].values()))
        self.assertFalse(directory.exists())

    def test_foreign_device_is_rejected_before_any_mutation(self):
        directory = self.journal()
        self.device.physical = 'OTHER'; self.device.mutations.clear()
        with self.assertRaises(ValueError): recovery.restore(directory, self.device)
        self.assertEqual([], self.device.mutations)
        self.write.assert_not_called()
        self.assertTrue(directory.exists())

    def test_tampered_bytes_and_paths_are_rejected_before_device_access(self):
        directory = self.journal(); path = directory/'state.json'
        original = json.loads(path.read_text())
        for change in ('checksum', 'path'):
            data = json.loads(json.dumps(original))
            if change == 'checksum': data['files'][recovery.PATHS[0]]['sha256'] = '0'*64
            else: data['files']['files/unrelated'] = data['files'].pop(recovery.PATHS[0])
            path.write_text(json.dumps(data)); self.device.mutations.clear()
            with self.assertRaises(ValueError): recovery.restore(directory, self.device)
            self.assertEqual([], self.device.mutations)
            self.write.assert_not_called()
            self.assertTrue(directory.exists())

    def test_failed_write_retains_the_only_exact_snapshot(self):
        directory = self.journal()
        self.files.update({p: b'changed' for p in recovery.PATHS})
        self.write.side_effect = lambda *args: None
        with self.assertRaises(RuntimeError): recovery.restore(directory, self.device)
        self.assertTrue(directory.exists())
        self.assertEqual(self.original[recovery.PATHS[0]], __import__('base64').b64decode(
            json.loads((directory/'state.json').read_text())['files'][recovery.PATHS[0]]['base64']))

    def test_an_install_baseline_can_also_restore_all_media_stores(self):
        for path in recovery.OPTIONAL_PATHS: self.files[path] = b'private media snapshot'
        original = dict(self.files)
        directory = recovery.capture(self.device, self.ui, recovery.PATHS + recovery.OPTIONAL_PATHS)
        self.addCleanup(lambda: shutil.rmtree(directory, ignore_errors=True))
        self.files.update({p: b'changed' for p in self.files})
        result = recovery.restore(directory, self.device)
        self.assertEqual(original, self.files)
        self.assertEqual(len(recovery.PATHS + recovery.OPTIONAL_PATHS), len(result['restored_files']))

    def test_debug_presence_hold_restores_a_boolean_without_turning_it_into_text(self):
        self.ui['presence'] = False
        directory = self.journal()
        recovery.restore(directory, self.device)
        self.assertIn(('am', 'broadcast', '-n', 'moe.kiwi.thinkos/.DebugReceiver', '--es', 'command',
                       'hold_presence', '--ez', 'value', 'false'), self.device.mutations)

    def test_recovery_waits_for_the_catalog_before_discarding_the_snapshot(self):
        self.device.delayed_pages = 2
        directory = self.journal()
        with patch.object(recovery.time, 'sleep'):
            recovery.restore(directory, self.device)
        self.assertEqual('clock.face', self.device.page)
        self.assertGreaterEqual(self.device.page_attempts, 3)
        self.assertFalse(directory.exists())
