"""Fail-closed checks for the intentional device self-update observer."""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).parent))
from self_update_device import install_session, retained_file, adopted_source_version
from downgrade_device import shell_downgrade_sessions


class InstallObserverTest(unittest.TestCase):
    def test_source_adoption_matches_the_whole_version_property(self):
        self.assertEqual(adopted_source_version('other=ok\nthinkos.version=0.1.1\n','0.1.1','0.1.2'),'other=ok\nthinkos.version=0.1.2\n')
        self.assertEqual(adopted_source_version('thinkos.version=0.1.2\n','0.1.1','0.1.2'),'thinkos.version=0.1.2\n')
        for value in ('thinkos.version=0.1.10\n','thinkos.version=0.1.1\nthinkos.version=0.1.2\n','other=ok\n'):
            with self.assertRaises(ValueError):adopted_source_version(value,'0.1.1','0.1.2')

    def test_shell_identity_is_not_its_declared_name_or_estimated_size(self):
        dump='''Historical install sessions:
  Session 18:
    mInstallerUid=2000 installFlags=0xf2 sizeBytes=11430805 appPackageName=null appIcon=false
    mDestroyed=true mFinalStatus=1
  Session 19:
    mInstallerUid=10074 installFlags=0x92 sizeBytes=9534301 appPackageName=moe.kiwi.thinkos appIcon=false
    mDestroyed=true mFinalStatus=1
  Session 20:
    mInstallerUid=2000 installFlags=0xf2 sizeBytes=9534301 appPackageName=other.app appIcon=false
    mDestroyed=true mFinalStatus=1
'''
        self.assertEqual([s['session_id'] for s,_ in shell_downgrade_sessions(dump,set())],[18])
        self.assertEqual(shell_downgrade_sessions(dump,{18}),[])

    def test_only_the_requested_terminal_session_counts(self):
        dump='''Active install sessions:
  Session 18:
    mInstallerUid=10074 mDestroyed=false mFinalStatus=0
Historical install sessions:
  Session 19:
    mInstallerUid=10074 mPermissionsManuallyAccepted=false mDestr
  oyed=true mFinalStatus=1 mFinalMessage=null
  Session 20:
    mInstallerUid=2000 mDestroyed=true mFinalStatus=-25
'''
        self.assertIsNone(install_session(dump,17))
        self.assertFalse(install_session(dump,18)['destroyed'])
        self.assertIsNone(install_session(dump,18)['permissions_manually_accepted'])
        self.assertEqual(install_session(dump,19),{'session_id':19,'installer_uid':10074,
            'destroyed':True,'legacy_status':1,'permissions_manually_accepted':False})
        self.assertEqual(install_session(dump,20)['legacy_status'],-25)

    def test_cache_refresh_cannot_hide_lost_preferences_or_channels(self):
        original={'prefs':json.dumps({'favorites':['cctv1'],'recent':'cctv1'}),
            'list':json.dumps({'items':[{'k':'cctv1'}],'at':100,'sig':'source'}),
            'health':json.dumps({'channel':{'failures':1}})}
        def raw(value):return json.dumps(value).encode()
        refreshed=dict(original,prefs=json.dumps({'recent':'cctv1','favorites':['cctv1']}),
            list=json.dumps({'items':[{'k':'cctv1'}],'at':200,'sig':'source'}),health='{}')
        self.assertTrue(retained_file('files/store/iptv.json',raw(original),raw(refreshed)))
        for change in ({'prefs':'{}'}, {'list':'{"items":[],"at":200,"sig":"source"}'},
                       {'list':'{"items":[{"k":"cctv1"}],"at":true,"sig":"source"}'},
                       {'health':'[]'}):
            self.assertFalse(retained_file('files/store/iptv.json',raw(original),raw(dict(refreshed,**change))))
        self.assertFalse(retained_file('files/settings/local.json',b'{}',b'{ }'))
        self.assertFalse(retained_file('files/store/iptv.json',raw(original),None))


if __name__=='__main__':unittest.main()
