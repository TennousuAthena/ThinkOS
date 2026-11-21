"""Verify the boot time module, its reproducible packaging, and (with fake commands) that it never gives up."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[1]
VERSION=re.search(r'^version=(.+)$',(ROOT/'timesync/module.prop').read_text(),re.M)[1]


class TimeSyncPackageTest(unittest.TestCase):
    def test_pinned_source_and_shell_syntax(self):
        origin=json.loads((ROOT/'timesync/ORIGIN.json').read_text())
        self.assertEqual('d9dc4e6fa17371aeffdc5acf3a55ccce19b0d835fc784880687b16912a05d28c',origin['archive_sha256'])
        for name,digest in origin['pinned_files'].items():
            self.assertEqual(digest,hashlib.sha256((ROOT/'timesync'/name).read_bytes()).hexdigest())
        subprocess.run(['sh','-n',str(ROOT/'timesync/service.sh')],check=True)

    def test_packages_repeat_identically_with_original_service_and_permissions(self):
        with tempfile.TemporaryDirectory(prefix='thinkos-timesync-') as directory:
            outputs=[]
            for name in ('first','second'):
                output=Path(directory)/name
                subprocess.run(['sh',str(ROOT/'build-timesync.sh'),'--output',str(output)],capture_output=True,check=True)
                archive=output/('timesync-'+VERSION+'.zip');outputs.append(archive.read_bytes())
                with zipfile.ZipFile(archive) as z:
                    self.assertEqual(['LICENSE','ORIGIN.json','module.prop','service.sh'],z.namelist())
                    self.assertEqual((ROOT/'timesync/service.sh').read_bytes(),z.read('service.sh'))
                    self.assertEqual(0o100755,z.getinfo('service.sh').external_attr>>16)
                    for entry in z.infolist():self.assertEqual((1980,1,1,0,0,0),entry.date_time)
            self.assertEqual(outputs[0],outputs[1])


class TimeSyncBehaviourTest(unittest.TestCase):
    """The network only answers after far more rounds than v1.0 (about 30) ever tried."""
    OFFLINE_ROUNDS=150

    def fake(self, directory, name, body):
        path=directory/name
        path.write_text('#!/bin/sh\n'+body)
        path.chmod(path.stat().st_mode|stat.S_IXUSR)

    def test_keeps_trying_until_the_network_answers_then_sets_the_clock_once(self):
        with tempfile.TemporaryDirectory(prefix='thinkos-timesync-run-') as name:
            work=Path(name); bin_dir=work/'bin'; bin_dir.mkdir()
            state=work/'state'; state.mkdir()
            # curl: offline for the first OFFLINE_ROUNDS rounds (four URLs each), then a real Date header.
            self.fake(bin_dir,'curl',f'''n=$(cat "{state}/curl" 2>/dev/null || echo 0); n=$((n+1)); echo $n > "{state}/curl"
[ $n -le {self.OFFLINE_ROUNDS*4} ] && exit 6
printf 'HTTP/1.1 200 OK\\r\\nDate: Sat, 21 Nov 2026 10:00:00 GMT\\r\\n\\r\\n'
''')
            self.fake(bin_dir,'sleep','n=$(cat "%s/sleep" 2>/dev/null || echo 0); echo $((n+1)) > "%s/sleep"\n' % (state,state))
            # date: 2009 until set with -u; records what was set.
            self.fake(bin_dir,'date',f'''if [ "$1" = -u ]; then echo "$2" >> "{state}/set"; exit 0; fi
if [ "$1" = +%Y ]; then [ -f "{state}/set" ] && echo 2026 || echo 2009; exit 0; fi
echo "fake-date"
''')
            env={'PATH':str(bin_dir)+os.pathsep+os.environ['PATH'],'TIMESYNC_LOG':str(work/'log')}
            subprocess.run(['sh',str(ROOT/'timesync/service.sh')],env=env,check=True,timeout=120)
            self.assertEqual(['112110002026.00'],(state/'set').read_text().split())
            self.assertEqual(self.OFFLINE_ROUNDS,int((state/'sleep').read_text()))
            log=(work/'log').read_text()
            self.assertIn('OK after set',log)
            self.assertNotIn('giving up',log)
            self.assertLess(len(log.splitlines()),100,'repeated failures must not grow the log without bound')


if __name__=='__main__':unittest.main()
