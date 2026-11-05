"""CALL-0 input/packaging boundaries. Tiny ELF-shaped data is never executed."""
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('thinkos_call_probe_aar', ROOT / 'tools/call_probe_aar.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class CallProbeAarTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='thinkos-call0-input-test-')
        self.work = Path(self.temporary.name).resolve()
        self.repo = self.work / 'source'; (self.repo / 'tools').mkdir(parents=True)
        (self.repo / 'tools/call-probe-NOTICE.txt').write_bytes(b'local test notice\n')
        self.root_patch = patch.object(probe, 'ROOT', self.repo); self.root_patch.start()
        self.aar = self.work / 'input.aar'
        self.output = self.repo / probe.OUTPUT
        self.native = bytearray(52)
        self.native[:7] = b'\x7fELF\x01\x01\x01'
        struct.pack_into('<H', self.native, 18, 40)
        self.native = bytes(self.native)

    def tearDown(self):
        self.root_patch.stop(); self.temporary.cleanup()

    def pack(self, variant='android', entries=None, native=None):
        native = self.native if native is None else native
        so = 'libjingle_peerconnection_so.so' if variant == 'android' else 'liblkjingle_peerconnection_so.so'
        members = [('AndroidManifest.xml', b'metadata-only-fixture'), ('classes.jar', b'not executed'),
            ('jni/armeabi-v7a/' + so, native), ('jni/arm64-v8a/' + so, b'not ARM32'),
            ('jni/x86/' + so, b'not ARM32'), ('libs/shadow-all.jar', b'non-native bytes')]
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in (members if entries is None else entries): archive.writestr(name, data)
        data = raw.getvalue(); self.aar.write_bytes(data)
        pins = {variant: (len(data), probe.digest(data), len(native), probe.digest(native))}
        return data, patch.object(probe, 'PINS', pins)

    def test_filtered_aar_preserves_every_non_jni_byte_and_only_armv7_native(self):
        for variant in ('android', 'android-prefixed', 'android-prefixed-stripped'):
            original, pins = self.pack(variant)
            with self.subTest(variant=variant), pins:
                filtered, receipt = probe.inspect(self.aar, variant)
            with zipfile.ZipFile(io.BytesIO(original)) as before, zipfile.ZipFile(io.BytesIO(filtered)) as after:
                self.assertEqual({e for e in before.namelist() if not e.startswith('jni/') or e.startswith('jni/armeabi-v7a/')}, set(after.namelist()))
                for name in after.namelist(): self.assertEqual(before.read(name), after.read(name))
                self.assertEqual(['jni/armeabi-v7a/' + receipt['so_filename']], [e for e in after.namelist() if e.startswith('jni/')])
            self.assertEqual(probe.digest(filtered), receipt['filtered_aar_sha256'])
            self.assertEqual('org.webrtc' if variant == 'android' else 'livekit.org.webrtc', receipt['namespace'])

    def test_whole_input_pin_is_checked_before_zip_parsing(self):
        _, pins = self.pack()
        self.aar.write_bytes(b'not a zip')
        with pins, patch.object(probe.zipfile, 'ZipFile', side_effect=AssertionError('must not parse')), self.assertRaisesRegex(ValueError, 'fixed statically'):
            probe.inspect(self.aar, 'android')

    def test_valid_pin_does_not_allow_wrong_elf_class_endianness_or_machine(self):
        for index, value in ((4, 2), (5, 2), (18, 183)):
            native = bytearray(self.native); native[index] = value
            _, pins = self.pack(native=bytes(native))
            with self.subTest(index=index), pins, self.assertRaisesRegex(ValueError, 'ELF32'):
                probe.inspect(self.aar, 'android')

    def test_unsafe_duplicate_and_unexpected_armv7_members_are_refused(self):
        base = [('classes.jar', b'java'), ('jni/armeabi-v7a/libjingle_peerconnection_so.so', self.native)]
        link = zipfile.ZipInfo('link'); link.create_system = 3; link.external_attr = (stat.S_IFLNK | 0o777) << 16
        cases = [base + [('classes.jar', b'duplicate')], base + [('../escape', b'no')],
            base + [('/absolute', b'no')], base + [('bad\\path', b'no')], base + [(link, b'classes.jar')],
            base + [('jni/armeabi-v7a/extra.so', b'not allowed')]]
        for entries in cases:
            _, pins = self.pack(entries=entries)
            with self.subTest(entries=str(entries[-1][0])), pins, self.assertRaises(ValueError): probe.inspect(self.aar, 'android')

    def test_input_leaf_link_fifo_size_and_repository_sources_are_refused(self):
        _, pins = self.pack()
        link = self.work / 'link.aar'; link.symlink_to(self.aar)
        fifo = self.work / 'input.fifo'; os.mkfifo(fifo)
        large = self.work / 'large'; large.write_bytes(b'x' * 128)
        inside = self.repo / 'inside.aar'; inside.write_bytes(self.aar.read_bytes())
        with pins:
            for path in (link, fifo, Path('relative.aar'), inside):
                with self.subTest(path=path), self.assertRaises((ValueError, OSError)): probe.inspect(path, 'android')
        with self.assertRaises(ValueError): probe.regular(large, 127)
        self.assertFalse(self.output.exists())

    def test_generate_writes_only_exact_dedicated_outputs_and_receipt(self):
        _, pins = self.pack()
        with pins: receipt = probe.generate(self.aar, 'android', self.output)
        self.assertEqual(receipt, json.loads((self.output / 'assets/call-probe/webrtc.json').read_bytes()))
        self.assertEqual(b'local test notice\n', (self.output / 'assets/call-probe/NOTICE.txt').read_bytes())
        files = [path for path in self.output.rglob('*') if path.is_file()]
        self.assertEqual(3, len(files))
        for path in files: self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
        self.assertFalse((self.repo / 'android/app/src/main/assets/call-probe').exists())

    def test_unknown_output_content_symlinks_and_other_destinations_fail_closed(self):
        _, pins = self.pack()
        with pins:
            for output in (self.work / 'other', self.repo / 'android/app/src/main/assets'):
                with self.assertRaises(ValueError): probe.generate(self.aar, 'android', output)
            self.output.mkdir(parents=True); extra = self.output / 'unknown'; extra.write_bytes(b'keep')
            with self.assertRaises(ValueError): probe.generate(self.aar, 'android', self.output)
            self.assertEqual(b'keep', extra.read_bytes()); extra.unlink(); self.output.rmdir()
            self.output.symlink_to(self.work, target_is_directory=True)
            with self.assertRaises(ValueError): probe.generate(self.aar, 'android', self.output)

    def test_unknown_variant_and_missing_classes_fail_without_output(self):
        with self.assertRaises(ValueError): probe.inspect(self.aar, 'not-a-variant')
        _, pins = self.pack(entries=[('jni/armeabi-v7a/libjingle_peerconnection_so.so', self.native)])
        with pins, self.assertRaisesRegex(ValueError, 'classes.jar'): probe.generate(self.aar, 'android', self.output)
        self.assertFalse(self.output.exists())


if __name__ == '__main__': unittest.main()
