"""Malformed local-image inputs must fail before allocating unbounded buffers."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('simulator_fonts', Path(__file__).resolve().parents[1] / 'scripts/extract_fonts.py')
fonts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fonts)


class FontInputTest(unittest.TestCase):
    def test_bad_magic_short_data_and_zero_geometry(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'image'
            for data in [b'', bytes(2048)]:
                path.write_bytes(data)
                with self.assertRaises(ValueError):
                    fonts.Ext4(path)
            data = bytearray(2048)
            struct.pack_into('<H', data, 1024+56, 0xef53)
            struct.pack_into('<H', data, 1024+88, 256)
            path.write_bytes(data)
            with self.assertRaisesRegex(ValueError, 'geometry'):
                fonts.Ext4(path)

    def test_reads_are_length_and_total_bounded(self):
        with tempfile.TemporaryFile() as stream:
            image = fonts.Ext4.__new__(fonts.Ext4)
            image.file, image.read_bytes = stream, 0
            for offset, size in [(-1, 1), (0, 64*1024*1024+1), (0, 1)]:
                with self.assertRaises(ValueError):
                    image.read(offset, size)
            image.read_bytes = 512*1024*1024
            with self.assertRaisesRegex(ValueError, 'budget'):
                image.read(0, 1)
