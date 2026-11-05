"""Run the real installer over fake adb/build boundaries; never contacts a device."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class UpdaterSpareTargetTest(unittest.TestCase):
    def run_installer(self, arguments, physical='SPARE123', inherited='HUA0BWUT', adb_exit=0):
        with tempfile.TemporaryDirectory(prefix='thinkos-updater-target-') as directory:
            work=Path(directory)
            (work/'install-updater.sh').write_bytes((ROOT/'system/install-updater.sh').read_bytes())
            (work/'build-updater.sh').write_text('#!/bin/sh\necho build >> "$CALLS"\nexit 42\n')
            (work/'build-updater.sh').chmod(0o700)
            (work/'adb').write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$CALLS"\n'
                                   'case "$*" in *"shell getprop ro.serialno") printf "%s\\n" "$PHYSICAL"; exit "$ADB_EXIT";; *) exit 93;; esac\n')
            (work/'adb').chmod(0o700)
            calls=work/'calls'
            env=dict(os.environ,PATH=str(work)+os.pathsep+os.environ['PATH'],CALLS=str(calls),PHYSICAL=physical,
                     ANDROID_SERIAL=inherited,ADB_EXIT=str(adb_exit))
            result=subprocess.run(['bash',str(work/'install-updater.sh'),*arguments],env=env,capture_output=True,text=True,timeout=5)
            return result, calls.read_text().splitlines() if calls.exists() else []

    def test_implicit_or_protected_targets_are_rejected_before_any_adb_or_build(self):
        for args in [[],['--keys-header','/owner/key.h'],['--serial','HUA0BWUT','--keys-header','/owner/key.h'],
                     ['--serial','bad serial','--keys-header','/owner/key.h']]:
            with self.subTest(args=args):
                result,calls=self.run_installer(args,inherited='SPARE_FROM_ENV')
                self.assertNotEqual(0,result.returncode)
                self.assertEqual([],calls)

    def test_network_alias_of_protected_hardware_is_rejected_before_build_or_push(self):
        result,calls=self.run_installer(['--serial','192.168.1.8:5555','--keys-header','/owner/key.h'],physical='HUA0BWUT')
        self.assertNotEqual(0,result.returncode)
        self.assertEqual(['-s 192.168.1.8:5555 shell getprop ro.serialno'],calls)

    def test_unknown_or_unavailable_identity_is_rejected_before_build(self):
        for physical,status in [('',0),('unknown',0),('null',0),('0',0),('unknown serial',0),('SPARE123',1)]:
            with self.subTest(physical=physical,status=status):
                result,calls=self.run_installer(['--serial','SPARE123','--keys-header','/owner/key.h'],physical=physical,adb_exit=status)
                self.assertNotEqual(0,result.returncode)
                self.assertEqual(['-s SPARE123 shell getprop ro.serialno'],calls)

    def test_explicit_verified_spare_reaches_build_without_any_install_command(self):
        result,calls=self.run_installer(['--serial','SPARE123','--keys-header','/owner/key.h'])
        self.assertEqual(42,result.returncode)
        self.assertEqual(['-s SPARE123 shell getprop ro.serialno','build'],calls)
