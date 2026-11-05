"""Fixed negative preparation with real disposable APK/P256 signatures; no device/network/library execution."""
import argparse
import contextlib
import copy
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'tools/tests')]
import ota_negative as negative
import release
import release_tools as common
import test_ota_lan as real_fixtures
from ota5_origin import _materials


class SemanticNegativeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Reuse only the real disposable builder, not its test class inheritance.
        real_fixtures.OtaLanTest.setUpClass()

    @classmethod
    def tearDownClass(cls):
        real_fixtures.OtaLanTest.tearDownClass()

    def setUp(self):
        self.fixture=real_fixtures.OtaLanTest('runTest');self.fixture.setUp()
        self.fixture.catalog['release']['modules']=[];self.fixture.seal()
        self.normal_args=self.fixture.args(output=self.fixture.work/'normal-lan')
        self.normal=self.fixture.execute(self.normal_args)
        self.result_file=self.fixture.work/'normal-result.json'
        self.result_file.write_bytes(common.json_bytes(self.normal))
        self.source=json.loads((self.normal_args.output/'beta.json').read_bytes())

    def tearDown(self):
        self.fixture.tearDown();self.fixture.doCleanups()

    def args(self,case='wrong-channel',**changes):
        value=argparse.Namespace(lan_dir=self.normal_args.output,lan_result=self.result_file,
            public_dir=self.fixture.public,lan_root='http://192.168.1.20:8765/ota/negative',
            output=self.fixture.work/'negative',case=case,seq=self.normal['seq']+1,
            initial=True,previous_catalog=None,private_key=self.fixture.private/'channel-active.key.pem',
            slot='active',password_fd=None,reviewed_sha256=None,execute=False)
        for key,item in changes.items():setattr(value,key,item)
        return value

    def prepare(self,args):
        return negative.prepare(args,source_root=self.fixture.fixture_source,tools=self.fixture.tools)

    def execute_cli(self,args):
        plan=self.prepare(args)
        argv=['--lan-dir',str(args.lan_dir),'--lan-result',str(args.lan_result),'--public-dir',str(args.public_dir),
              '--lan-root',args.lan_root,'--output',str(args.output),'--case',args.case,'--seq',str(args.seq),
              '--private-key',str(args.private_key),'--slot',args.slot,'--reviewed-sha256',plan['input_sha256'],'--execute']
        argv.extend(['--initial'] if args.initial else ['--previous-catalog',str(args.previous_catalog)])
        captured=io.StringIO()
        with patch.object(negative,'read_password',return_value=self.fixture.password),contextlib.redirect_stdout(captured):
            self.assertEqual(negative.main(argv,source_root=self.fixture.fixture_source,tools=self.fixture.tools),0)
        return json.loads(captured.getvalue())

    def test_default_plan_has_no_private_access_signature_output_or_socket(self):
        args=self.args(private_key=self.fixture.work/'nonexistent-encrypted.key.pem')
        with patch.object(negative,'private_file',side_effect=AssertionError('Private access during plan')), \
             patch.object(negative,'read_password',side_effect=AssertionError('Password during plan')), \
             patch.object(release,'sign',side_effect=AssertionError('Signing during plan')), \
             patch('socket.socket',side_effect=AssertionError('Network during plan')):
            plan=self.prepare(args)
        self.assertFalse(args.output.exists());self.assertFalse(plan['executed'])
        self.assertEqual(plan['artifact_kind'],negative.KIND)
        self.assertFalse(plan['owner_debug_crash_material']);self.assertFalse(plan['commit_arm_v2_verified'])

    def test_actual_cli_signatures_bind_exact_two_fixed_mutations_and_normal_origin_refuses_kind(self):
        for case in negative.CASES:
            with self.subTest(case=case):
                args=self.args(case,output=self.fixture.work/case,lan_root='http://192.168.1.20:8765/ota/'+case)
                result=self.execute_cli(args)
                raw=(args.output/'beta.json').read_bytes();doc=release.parse_document(raw)
                release.verify(args.output/'beta.json',(args.output/'beta.json.sig').read_bytes(),list(self.fixture.keys['channel'].values()))
                release.validate_catalog(doc)
                wanted=copy.deepcopy(self.source);wanted['seq']=args.seq
                name='thinkos-'+wanted['release']['version']+'.apk'
                wanted['release']['urls']=[args.lan_root+'/'+name]
                if case=='wrong-channel':wanted['channel']='stable'
                else:
                    sha=wanted['release']['sha256'];wanted['release']['sha256']=('1' if sha[0]=='0' else '0')+sha[1:]
                self.assertEqual(doc,wanted)
                self.assertEqual((args.output/name).read_bytes(),(args.lan_dir/name).read_bytes())
                self.assertEqual(result['catalog_may_advance_device_floor'],case=='apk-digest-mismatch')
                self.assertEqual(result['next_normal_seq_must_exceed'],args.seq)
                self.assertEqual(set(p.name for p in args.output.iterdir()),set(result['files']))
                with self.assertRaisesRegex(ValueError,'executed ota_lan release'):_materials(args.output,result)

    def test_sequence_must_exceed_signed_negative_predecessor_without_resetting_any_input(self):
        first=self.args('apk-digest-mismatch');self.execute_cli(first)
        previous=first.output/'beta.json'
        args=self.args(seq=first.seq,initial=False,previous_catalog=previous,output=self.fixture.work/'next')
        with self.assertRaisesRegex(ValueError,'sequence'):self.prepare(args)
        args.seq+=1
        plan=self.prepare(args)
        self.assertEqual(plan['previous_seq'],first.seq)
        self.assertEqual(plan['seq'],first.seq+1)
        self.assertFalse(args.output.exists())
        self.assertEqual(json.loads((self.normal_args.output/'beta.json').read_bytes()),self.source)

    def test_raw_review_input_change_is_refused_before_private_access_or_output(self):
        args=self.args();plan=self.prepare(args)
        self.result_file.write_bytes(b'\n'+self.result_file.read_bytes())
        args.execute=True;args.reviewed_sha256=plan['input_sha256']
        with patch.object(negative,'private_file',side_effect=AssertionError('Unreviewed private access')):
            with self.assertRaisesRegex(ValueError,'changed after review'):self.prepare(args)
        self.assertFalse(args.output.exists())

    def test_wrong_channel_role_key_cannot_commit_an_experimental_catalog(self):
        args=self.args(private_key=self.fixture.private/'system-active.key.pem')
        plan=self.prepare(args);args.execute=True;args.reviewed_sha256=plan['input_sha256']
        with patch.object(negative,'read_password',return_value=self.fixture.password):
            with self.assertRaisesRegex(ValueError,'selected public role'):self.prepare(args)
        self.assertFalse((args.output/'beta.json').exists());self.assertFalse((args.output/'beta.json.sig').exists())

    def test_a_real_owner_signed_debug_apk_cannot_masquerade_as_the_release_source(self):
        debug=self.fixture.make_apk(self.fixture.work/'debug-signed',debuggable=True)
        name='thinkos-0.2.0.apk';target=self.normal_args.output/name;target.write_bytes(debug.read_bytes())
        doc=copy.deepcopy(self.source);doc['release'].update(sha256=common.digest(target),size=target.stat().st_size)
        catalog=self.normal_args.output/'beta.json';catalog.write_bytes(common.json_bytes(doc))
        signature=release.sign(catalog,self.fixture.private/'channel-active.key.pem',self.fixture.keys['channel']['active'],self.fixture.environment)
        (self.normal_args.output/'beta.json.sig').write_bytes(signature)
        changed=copy.deepcopy(self.normal)
        changed['catalog_sha256']=common.digest(catalog)
        changed['output_sha256']={p.name:common.digest(p) for p in self.normal_args.output.iterdir()}
        self.result_file.write_bytes(common.json_bytes(changed))
        args=self.args()
        with patch.object(negative,'private_file',side_effect=AssertionError('Invalid-source private access')):
            with self.assertRaisesRegex(ValueError,'debuggability'):self.prepare(args)
        self.assertFalse(args.output.exists())

    def test_unknown_file_added_while_real_signing_prevents_catalog_commit(self):
        args=self.args();plan=self.prepare(args);args.execute=True;args.reviewed_sha256=plan['input_sha256']
        actual_sign=release.sign
        def sign(*values,**options):
            signature=actual_sign(*values,**options)
            (args.output/'owner-note.txt').write_text('keep owner addition')
            return signature
        with patch.object(negative,'read_password',return_value=self.fixture.password),patch.object(release,'sign',side_effect=sign):
            with self.assertRaisesRegex(ValueError,'Unexpected experimental output'):self.prepare(args)
        self.assertFalse((args.output/'beta.json').exists());self.assertFalse((args.output/'beta.json.sig').exists())
        self.assertEqual((args.output/'owner-note.txt').read_text(),'keep owner addition')

    def test_extra_debug_root_in_a_real_signed_nondebug_apk_is_refused(self):
        import zipfile
        unsigned=self.fixture.work/'extra-root-unsigned.apk'
        with zipfile.ZipFile(self.fixture.base_apk) as source,zipfile.ZipFile(unsigned,'w') as target:
            for info in source.infolist():
                if not info.filename.startswith('META-INF/'):target.writestr(info,source.read(info))
            target.writestr('assets/update/debug-keys.json',common.json_bytes(self.fixture.keys))
        aligned=self.fixture.work/'extra-root-aligned.apk';signed=self.fixture.work/'extra-root-signed.apk'
        self.fixture.run_fixture([self.fixture.zipalign,'-f','4',unsigned,aligned])
        self.fixture.run_fixture([self.fixture.tools[1],'sign','--ks',self.fixture.keystore,'--ks-key-alias','thinkos',
            '--ks-pass','env:'+common.PRIVATE_PASSWORD_ENV,'--key-pass','env:'+common.PRIVATE_PASSWORD_ENV,
            '--v4-signing-enabled','false','--out',signed,aligned])
        name='thinkos-0.2.0.apk';target=self.normal_args.output/name;target.write_bytes(signed.read_bytes())
        doc=copy.deepcopy(self.source);doc['release'].update(sha256=common.digest(target),size=target.stat().st_size)
        catalog=self.normal_args.output/'beta.json';catalog.write_bytes(common.json_bytes(doc))
        signature=release.sign(catalog,self.fixture.private/'channel-active.key.pem',self.fixture.keys['channel']['active'],self.fixture.environment)
        (self.normal_args.output/'beta.json.sig').write_bytes(signature)
        changed=copy.deepcopy(self.normal);changed['catalog_sha256']=common.digest(catalog)
        changed['output_sha256']={p.name:common.digest(p) for p in self.normal_args.output.iterdir()}
        self.result_file.write_bytes(common.json_bytes(changed))
        with self.assertRaisesRegex(ValueError,'Debug-only signing roots|debug trust|debug roots'):self.prepare(self.args())
