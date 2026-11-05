"""Real disposable APK/P256 crypto; saved capability JSON here is synthetic.

These parser fixtures never assert an actual device/policy/crash ran. The tiny
hasCode=false signed APK is data only, never loaded/installed. Real spare phase
receipts must come from the new installed-target readonly capability phase.
"""
import argparse
import base64
import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import time
import unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
import ota_debug_crash as crash
import release
import release_tools as common
import test_ota_lan as fixtures
from ota5_origin import _materials


class OwnerDebugCrashTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):fixtures.OtaLanTest.setUpClass()
    @classmethod
    def tearDownClass(cls):fixtures.OtaLanTest.tearDownClass()
    def setUp(self):
        self.m=fixtures.OtaLanTest('runTest');self.m.setUp()
        self.m.catalog['release']['modules']=[];self.m.seal()
        self.apk=self.m.make_apk(self.m.work/'debug',debuggable=True,version='0.3.0',code=3000)
        actual=release.inspect_apk(self.apk,self.m.signer,'0.3.0',debuggable=True,tools=self.m.tools)
        apk={**actual,'package':crash.APP,'debuggable':True,'target_sdk':27}
        assets={name:{'present':True,'sha256':common.digest(self.m.public/name),'bytes':(self.m.public/name).stat().st_size}
                for name in ('keys.json','system-keys.json')};assets['debug-keys.json']={'present':False}
        roots={'schema':1,'complete':True,'scope':'installed_apk_assets_and_production_key_loaders_only',
            'update_keys_load_ok':True,'system_keys_load_ok':True,'six_roles_independent':True,
            'reg_fixture_namespace_present':False,'apk_stable_during_probe':True,'failures':[],
            'declared_role_counts':{'channel':2,'registry':2,'system':2},'apk':apk,'assets':assets,
            'probe_writes_files':False,'probe_changes_settings':False,'probe_starts_runtime':False,'probe_calls_installer':False}
        self.nonce='7'*32
        marker=common.json_bytes(crash.vector_marker(actual,self.nonce))
        vectors={'schema':1,'complete':True,'scope':'policy_roundtrip_only','executing_apk_identity_verified':True,
            'helper_target_classloader_equal':True,'marker_base64':base64.b64encode(marker).decode(),
            'marker_sha256':hashlib.sha256(marker).hexdigest(),
            'observed':{name:name in crash.POSITIVE for name in crash.POSITIVE|crash.NEGATIVE}}
        body={'schema':1,'complete':True,'scope':'policy_roundtrip_only','run_id':self.nonce,
            'spare_identity_sha256':hashlib.sha256(b'SPARE123').hexdigest(),'apk':apk,'roots':roots,'vectors':vectors,
            'apk_stable_during_probe':True,'ordinary_application_bound_before_phase':True,
            'executing_apk_identity_verified':True,'helper_target_classloader_equal':True}
        for key in ('runtime_crash_verified','commit_hook_executed','probe_starts_main','probe_starts_runtime',
                    'probe_calls_installer','probe_writes_files','global_arm_changed'):body[key]=False
        self.receipt={'sdk':27,'uid':10074,'pid':123,'phase':crash.PHASE,'complete':True,
            'checks':[{'name':'installed owner-debug APK executes isolated v2 compatibility vectors','passed':True}],crash.PHASE:body}
        self.receipt_file=self.m.work/'capability.json';self.save()
        now=int(time.time()*1000)
        self.args=argparse.Namespace(apk=self.apk,version='0.3.0',public_dir=self.m.public,capability_receipt=self.receipt_file,
            previous_catalog=self.m.source/'beta.json',lan_root='http://192.168.1.20:8765/crash-v2',
            source_url='https://example.invalid/ThinkOS/tree/v0.3.0',seq=2,published_ms=now,expires_ms=now+86400000,
            output=self.m.work/'debug-experiment',slot='active',private_key=None,password_fd=None,reviewed_sha256=None,execute=False)
    def tearDown(self):self.m.tearDown();self.m.doCleanups()
    def save(self):self.receipt_file.write_bytes(common.json_bytes(self.receipt))
    def prepare(self,args=None):return crash.prepare(args or self.args,source_root=self.m.fixture_source,tools=self.m.tools)
    def argv(self):
        result=[]
        for key in ('apk','version','public_dir','capability_receipt','previous_catalog','lan_root','source_url','seq','published_ms','expires_ms','output','slot'):
            result+=['--'+key.replace('_','-'),str(getattr(self.args,key))]
        return result
    def test_plan_real_apk_and_receipt_binding_without_key_password_sign_or_output(self):
        with patch.object(crash,'private_file',side_effect=AssertionError('private access')), \
             patch.object(crash,'read_password',side_effect=AssertionError('password access')), \
             patch.object(release,'sign',side_effect=AssertionError('signing')):
            value=self.prepare()
        self.assertEqual((crash.KIND,'planned',False),(value['artifact_kind'],value['stage'],value['executed']))
        self.assertTrue(value['policy_compatibility_verified'])
        self.assertFalse(value['runtime_crash_verified'] or value['actual_commit_verified'] or value['normal_release_eligible'])
        self.assertFalse(self.args.output.exists())
    def test_real_cli_executes_channel_signature_and_only_three_experimental_files(self):
        plan=self.prepare();read_fd,write_fd=os.pipe()
        try:
            os.write(write_fd,(self.m.password+'\n').encode());os.close(write_fd);write_fd=None
            argv=self.argv()+['--execute','--reviewed-sha256',plan['input_sha256'],'--private-key',
                str(self.m.private/'channel-active.key.pem'),'--password-fd',str(read_fd)]
            with contextlib.redirect_stdout(io.StringIO()) as captured:
                self.assertEqual(0,crash.main(argv,source_root=self.m.fixture_source,tools=self.m.tools))
            result=json.loads(captured.getvalue())
        finally:
            os.close(read_fd)
            if write_fd is not None:os.close(write_fd)
        self.assertNotIn(self.m.password,captured.getvalue())
        self.assertEqual(set(plan['files']),{p.name for p in self.args.output.iterdir()})
        self.assertEqual(0o700,self.args.output.stat().st_mode&0o777)
        release.verify(self.args.output/'beta.json',(self.args.output/'beta.json.sig').read_bytes(),list(self.m.keys['channel'].values()))
        self.assertEqual(common.digest(self.apk),common.digest(self.args.output/plan['files'][0]))
        self.assertFalse(result['runtime_crash_verified'] or result['ota5_device_acceptance_complete'])
        with self.assertRaises(ValueError):_materials(self.args.output,result) # Normal Origin stays release-only.
        with self.assertRaises(ValueError):release.inspect_apk(self.apk,self.m.signer,'0.3.0',debuggable=False,tools=self.m.tools)
    def test_old_arm_roots_and_class_name_claims_do_not_qualify_candidate(self):
        good=copy.deepcopy(self.receipt)
        for phase in ('debug_commit_fault_arm','production_roots','class_present'):
            self.receipt=copy.deepcopy(good);self.receipt['phase']=phase;self.save()
            with self.subTest(phase=phase),self.assertRaises(ValueError):self.prepare()
        self.assertFalse(self.args.output.exists())
    def test_wrong_installed_tuple_scope_and_matrix_fail_even_with_updated_receipt_hash(self):
        good=copy.deepcopy(self.receipt)
        for case in ('hash','signer','size','runtime','matrix','marker','executing','loader'):
            self.receipt=copy.deepcopy(good);body=self.receipt[crash.PHASE]
            if case=='hash':body['apk']['sha256']='0'*64
            elif case=='signer':body['apk']['signer']='0'*64
            elif case=='size':body['apk']['size']+=1
            elif case=='runtime':body['probe_starts_runtime']=True
            elif case=='matrix':body['vectors']['observed']['trial_4_eligible']=True
            elif case=='executing':body.pop('executing_apk_identity_verified')
            elif case=='loader':body['vectors']['helper_target_classloader_equal']=False
            else:
                marker=json.loads(base64.b64decode(body['vectors']['marker_base64']));marker['pending']['to']['sha256']='0'*64
                raw=common.json_bytes(marker);body['vectors']['marker_base64']=base64.b64encode(raw).decode();body['vectors']['marker_sha256']=hashlib.sha256(raw).hexdigest()
            self.save()
            with self.subTest(case=case),self.assertRaises(ValueError):self.prepare()
    def test_actual_signed_wrong_owner_release_api_abi_or_package_apks_are_refused(self):
        for index,change in enumerate(({'keystore':self.m.other_keystore},{'debuggable':False},{'sdk':28},
                                       {'abi':'arm64-v8a'},{'package':'other.app'},{'changed_roots':True})):
            options={'debuggable':True,'version':'0.3.0','code':3000,**change}
            bad=self.m.make_apk(self.m.work/('bad-'+str(index)),**options)
            args=copy.copy(self.args);args.apk=bad
            with self.subTest(change=change),self.assertRaises(ValueError):self.prepare(args)
    def test_wrong_previous_role_modules_and_stale_sequence_are_refused(self):
        self.m.seal(role='system')
        with self.assertRaises(ValueError):self.prepare()
        self.m.catalog['release']['modules']=[self.m.module];self.m.seal()
        with self.assertRaises(ValueError):self.prepare()
        self.m.catalog['release']['modules']=[];self.m.seal();self.args.seq=1
        with self.assertRaises(ValueError):self.prepare()
    def test_execute_requires_review_and_wrong_channel_key_never_creates_output(self):
        self.args.execute=True;self.args.private_key=self.m.private/'channel-active.key.pem'
        with self.assertRaises(ValueError):self.prepare()
        self.args.execute=False;self.args.reviewed_sha256=self.prepare()['input_sha256'];self.args.execute=True
        self.args.private_key=self.m.private/'system-active.key.pem'
        with patch.object(crash,'read_password',return_value=self.m.password),self.assertRaises(ValueError):self.prepare()
        self.assertFalse(self.args.output.exists())
    def test_reviewed_receipt_change_during_real_sign_is_refused_before_output_commit(self):
        self.args.reviewed_sha256=self.prepare()['input_sha256'];self.args.execute=True
        self.args.private_key=self.m.private/'channel-active.key.pem';sign=release.sign
        def changed(*args,**kwargs):
            signature=sign(*args,**kwargs);self.receipt[crash.PHASE]['spare_identity_sha256']='1'*64;self.save();return signature
        with patch.object(crash,'read_password',return_value=self.m.password),patch.object(release,'sign',side_effect=changed),self.assertRaises(ValueError):self.prepare()
        self.assertFalse(self.args.output.exists())
    def test_ci_and_public_or_credentialled_lan_origins_fail_closed(self):
        with patch.dict(os.environ,{'CI':'true'}),self.assertRaises(ValueError):self.prepare()
        for value in ('http://8.8.8.8/a','http://localhost/a','http://u:p@192.168.1.20/a','http://192.168.1.20/a/../b'):
            args=copy.copy(self.args);args.lan_root=value
            with self.subTest(root=value),self.assertRaises(ValueError):self.prepare(args)
