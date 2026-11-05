"""Verify the imported boot module and reproducible packaging without executing it."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[1]


class TimeSyncPackageTest(unittest.TestCase):
    def test_pinned_source_and_shell_syntax(self):
        origin=json.loads((ROOT/'timesync/ORIGIN.json').read_text())
        self.assertEqual('d9dc4e6fa17371aeffdc5acf3a55ccce19b0d835fc784880687b16912a05d28c',origin['archive_sha256'])
        for name,digest in origin['unmodified_files'].items():
            self.assertEqual(digest,hashlib.sha256((ROOT/'timesync'/name).read_bytes()).hexdigest())
        subprocess.run(['sh','-n',str(ROOT/'timesync/service.sh')],check=True)

    def test_packages_repeat_identically_with_original_service_and_permissions(self):
        with tempfile.TemporaryDirectory(prefix='thinkos-timesync-') as directory:
            outputs=[]
            for name in ('first','second'):
                output=Path(directory)/name
                subprocess.run(['sh',str(ROOT/'build-timesync.sh'),'--output',str(output)],capture_output=True,check=True)
                archive=output/'timesync-v1.0.zip';outputs.append(archive.read_bytes())
                with zipfile.ZipFile(archive) as z:
                    self.assertEqual(['LICENSE','ORIGIN.json','module.prop','service.sh'],z.namelist())
                    self.assertEqual((ROOT/'timesync/service.sh').read_bytes(),z.read('service.sh'))
                    self.assertEqual(0o100755,z.getinfo('service.sh').external_attr>>16)
                    for entry in z.infolist():self.assertEqual((1980,1,1,0,0,0),entry.date_time)
            self.assertEqual(outputs[0],outputs[1])


if __name__=='__main__':unittest.main()
