"""Real disposable signed input verification + fake UI/root transport boundaries.

These host tests never contact ADB/origin or run any APK. Virtual time tests the
actor's wait bookkeeping only; it does not qualify production 600-second health.
"""
import base64
import contextlib
import copy
import io
import json
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch
import zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0,str(Path(__file__).resolve().parent))
import ota5_device as driver
import ota5_ui as target
import test_ota5_device as real_inputs


class Ota5UiBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):real_inputs.Ota5PreflightTest.setUpClass()
    @classmethod
    def tearDownClass(cls):real_inputs.Ota5PreflightTest.tearDownClass()

    def setUp(self):
        self.inputs=real_inputs.Ota5PreflightTest('runTest');self.inputs.setUp()
        self.addCleanup(self.inputs.doCleanups)
        m=self.inputs.material;work=m.work
        unsigned=work/'baseline-schema-unsigned.apk'
        with zipfile.ZipFile(self.inputs.baseline) as source,zipfile.ZipFile(unsigned,'w') as output:
            for item in source.infolist():
                if not item.filename.startswith('META-INF/'):output.writestr(item,source.read(item))
            output.writestr('assets/settings/system.json',(driver.ROOT/'shared/schema/settings/system.json').read_bytes())
        aligned=work/'baseline-schema-aligned.apk';signed=work/'baseline-schema-signed.apk'
        m.run_fixture([m.zipalign,'-f','4',unsigned,aligned])
        m.run_fixture([m.tools[1],'sign','--ks',m.keystore,'--ks-key-alias','thinkos',
            '--ks-pass','env:'+driver.ota_lan.PRIVATE_PASSWORD_ENV,'--key-pass','env:'+driver.ota_lan.PRIVATE_PASSWORD_ENV,
            '--v4-signing-enabled','false','--out',signed,aligned])
        self.inputs.args.baseline_apk=signed
        actual=driver.release.inspect_apk(signed,m.signer,'0.1.0',tools=m.tools)
        self.inputs.observation['production_roots']['apk'].update(actual)
        self.inputs.save_observation()
        self.jar=work/'fixed-hierarchy.jar';self.jar.write_bytes(b'owner-reviewed-local-helper-fixture')

    def prepared(self):return self.inputs.prepare()
    def args(self,*extra):return driver.parser().parse_args(self.inputs.argv()+['--hierarchy-jar',str(self.jar)]+list(extra))

    def test_real_signed_default_UI_and_install_plans_never_reach_adb_origin_or_private_signer(self):
        values=[]
        for extra in ([],['--plan-install']):
            capture=io.StringIO()
            with patch.object(target,'execute',side_effect=AssertionError('Default plan executed UI')), \
                 patch.object(driver.Ota5Origin,'start',side_effect=AssertionError('Default plan started source')), \
                 patch.object(driver.release,'sign',side_effect=AssertionError('Default plan signed')),contextlib.redirect_stdout(capture):
                self.assertEqual(driver.main(self.inputs.argv()+['--hierarchy-jar',str(self.jar)]+extra,
                    prepare_scenario=self.inputs.prepare,device_factory=lambda _:self.fail('Default plan used ADB')),0)
            values.append(json.loads(capture.getvalue()))
        self.assertFalse(values[0]['ui_plan']['install']);self.assertTrue(values[1]['ui_plan']['install'])
        self.assertNotEqual(values[0]['ui_plan']['ui_input_sha256'],values[1]['ui_plan']['ui_input_sha256'])
        self.assertTrue(values[0]['ui_plan']['baseline_settings_schema_present'])

    def test_ui_fingerprint_binds_raw_signed_inputs_actual_APK_and_helper_before_any_device_action(self):
        first=self.prepared();args=self.args('--execute-ui','--authorize-spare','SPARE123',
            '--output',str(self.inputs.material.work/'ui-report'))
        args.reviewed_sha256=target.ui_plan(first,args)['ui_input_sha256']
        self.jar.write_bytes(b'changed reviewed helper')
        with self.assertRaisesRegex(RuntimeError,'reviewed UI input SHA'):
            target.execute(first,args,device_factory=lambda _:self.fail('Changed helper reached ADB'))
        self.assertFalse(args.output.exists())
        self.jar.write_bytes(b'owner-reviewed-local-helper-fixture')
        self.inputs.result_path.write_bytes(b'\n'+self.inputs.result_path.read_bytes())
        changed=self.prepared()
        with self.assertRaisesRegex(RuntimeError,'reviewed UI input SHA'):
            target.execute(changed,args,device_factory=lambda _:self.fail('Changed input reached ADB'))

    def test_hua_alias_authorization_and_phase_confusion_are_refused_before_output_or_adb(self):
        prepared=self.prepared();args=self.args('--execute-ui','--output',str(self.inputs.material.work/'ui-report'))
        args.reviewed_sha256=target.ui_plan(prepared,args)['ui_input_sha256']
        with self.assertRaisesRegex(RuntimeError,'authorized spare'):
            target.execute(prepared,args,device_factory=lambda _:self.fail('Missing authorization reached ADB'))
        prepared.physical='HUA0BWUT'
        with self.assertRaises(ValueError):target.execute(prepared,args,device_factory=lambda _:self.fail('HUA reached ADB'))
        self.assertFalse(args.output.exists())
        with self.assertRaisesRegex(RuntimeError,'READY-only'):
            target.ui_plan(self.prepared(),self.args('--execute-ui','--plan-install'))

    def test_production_READY_proof_binds_raw_cache_signature_floor_and_actual_download_then_rechecks_stability(self):
        prepared=self.prepared();channel=prepared.target['channel'];state='files/update/state/state-'+channel+'.json'
        metadata='files/update/metadata/catalog-'+channel+'.json';ledger='files/update/ledger/sequences.json'
        signed=prepared.origin._files[channel+'.json.sig']
        values={state:driver.release.json_bytes({'phase':'READY','seq':prepared.target['seq'],'catalog_digest':prepared.target['catalog_sha256'],
                    'size':prepared.target['size'],'downloaded':prepared.target['size']}),
            metadata:driver.release.json_bytes({'body':base64.b64encode(prepared.catalog_bytes).decode(),
                    'signature':base64.b64encode(signed).decode(),'source':prepared.mirror}),
            ledger:driver.release.json_bytes({channel:{'seq':prepared.target['seq'],'digest':prepared.target['catalog_sha256']}})}
        class Device:
            def private(self,path,*args):return prepared.target['sha256'] if args else values[path]
            def document(self,path):return driver.release.parse_document(values[path])
        device=Device();device.channel=channel
        output=self.inputs.material.work/'proof';output.mkdir()
        actor=target.UI(device,types.SimpleNamespace(check=lambda:None),None,prepared,output)
        actor.ready_proof()
        bad=json.loads(values[metadata]);bad['signature']=base64.b64encode(b'wrong-role-signature').decode()
        values[metadata]=driver.release.json_bytes(bad)
        with self.assertRaisesRegex(RuntimeError,'signature/body'):actor.ready_proof()
        self.assertFalse(actor.result['installed'])

    def test_controlled_install_window_never_permits_an_unknown_foreground_or_hardware_input(self):
        calls=[]
        device=types.SimpleNamespace(pid=lambda **kw:None,property=lambda name:'SPARE123')
        guard=object.__new__(target.Guard);guard.device=device;guard.watch=types.SimpleNamespace(check=lambda:calls.append('watch'))
        guard.prepared=types.SimpleNamespace(serial='spare-endpoint',physical='SPARE123')
        guard.pid=10;guard.deadline=200;guard.install_until=150
        with patch.object(target.time,'monotonic',return_value=100),patch.object(target,'quiet',return_value=True), \
             patch.object(target,'focused_component',return_value=None):guard.check()
        with patch.object(target.time,'monotonic',return_value=100),patch.object(target,'quiet',return_value=True), \
             patch.object(target,'focused_component',return_value='com.android.permissioncontroller/.Grant'):
            with self.assertRaisesRegex(RuntimeError,'Foreign/empty'):guard.check()
        with patch.object(target.time,'monotonic',return_value=160),patch.object(target,'focused_component',return_value=None):
            with self.assertRaisesRegex(RuntimeError,'Foreign/empty'):guard.check()

    def test_public_form_duplicate_label_selects_its_clickable_row_and_emits_only_real_touchscreen_input(self):
        prepared=self.prepared();calls=[]
        d=types.SimpleNamespace(shell=lambda *args:calls.append(args))
        g=types.SimpleNamespace(touch=lambda:calls.append('fresh-owned-touch'),check=lambda:None)
        actor=target.UI(d,g,None,prepared,self.inputs.material.work)
        child={'text':'更新镜像','clickable':'false','enabled':'true','bounds':'[20,20][100,50]'}
        row={'content-desc':'更新镜像','clickable':'true','enabled':'true','bounds':'[10,10][300,70]'}
        actor.nodes=lambda:[child,row]
        self.assertIs(actor.node(label='更新镜像'),row)
        actor.tap_node(actor.node(label='更新镜像'))
        self.assertEqual(calls,['fresh-owned-touch',('input','touchscreen','tap',155,40)])

    def test_safe_wake_uses_all_windows_one_blank_touch_then_fresh_page_and_never_repeats_critical_actions(self):
        prepared=self.prepared();calls=[]
        root=ET.fromstring('<hierarchy><node package="moe.kiwi.thinkos" bounds="[0,0][300,200]" clickable="false">'
            '<node package="moe.kiwi.thinkos" text="时钟" bounds="[50,50][250,150]" clickable="false"/>'
            '</node><node package="com.android.systemui" bounds="[0,0][20,200]" clickable="false"/></hierarchy>')
        class Hierarchy:
            def tree(self,*,all_windows=False):calls.append(('hierarchy',all_windows));return root
        g=types.SimpleNamespace(check=lambda:None,touch=lambda:calls.append('owned-touch'))
        actor=target.UI(types.SimpleNamespace(shell=lambda *args:calls.append(args)),g,Hierarchy(),prepared,self.inputs.material.work)
        actor.nodes=lambda:(calls.append('fresh-after-wake') or [{'text':'太阳高度'}])
        with patch.object(target,'focused_component',return_value=target.APP+'/.MainActivity'):
            actor.safe_wake('after-health')
        self.assertEqual(calls,[('hierarchy',True),'owned-touch',('input','touchscreen','tap',298,2),'fresh-after-wake'])
        self.assertFalse(actor.result['safe_wake'][0]['actual_role_or_page_assumed'])
        root.find('node').append(ET.fromstring('<node package="moe.kiwi.thinkos" resource-id="android:id/button1" text="安装" bounds="[30,30][80,70]" clickable="true"/>'))
        before=len(calls)
        with patch.object(target,'focused_component',return_value=target.APP+'/.MainActivity'),self.assertRaisesRegex(RuntimeError,'refuses an existing'):
            actor.safe_wake('not-a-normal-Main')
        self.assertEqual(calls[before:],[('hierarchy',True)])

    def test_source_presence_preserves_default_server_inheritance_and_existing_nonredundant_local_but_refuses_legacy(self):
        defaults={'update.mirror':'','update.channel':'stable'}
        server={'update.mirror':'https://mirror.example','update.channel':'beta'}
        inherited=target.source_presence({},server,defaults)
        self.assertEqual(inherited,{'update.mirror':{'present':False},'update.channel':{'present':False}})
        owned={'update.mirror':'https://my.example','update.channel':'stable'}
        self.assertEqual(target.source_presence(owned,server,defaults)['update.channel'],{'present':True,'value':'stable'})
        for local in ({'update.mirror':'https://mirror.example'},{'update.channel':'bad'},{'update.mirror':42}):
            with self.assertRaises(RuntimeError):target.source_presence(local,server,defaults)

    def _health_actor(self,early=False,wrong=False):
        prepared=self.prepared();ticks=[0.0];tx='a'*32
        pending={'phase':'TRIAL','attempts':1,'transaction_id':tx,'trial_id':'b'*32,'trial_started':{'elapsed':1000},
            'from':target.UI.identity(prepared.baseline),'to':target.UI.identity(prepared.target),
            'catalog_digest':prepared.target['catalog_sha256'],'seq':prepared.target['seq'],'channel':prepared.target['channel'],
            'mode':'manual','health_ms':600000,'session_id':42}
        if wrong:pending['to']=dict(pending['to'],sha256='f'*64)
        class Device:
            channel=prepared.target['channel']
            def pid(self,**kwargs):return 22
            def installed_sha(self):return prepared.target['sha256']
            def owner_and_home(self):pass
            def policy_snapshot(self):return {}
            def document(self,path):
                if path=='files/update/pending.json':return pending if ticks[0]<(20 if early else 600) else None
                if path=='files/update/install-history.json':return {'failures':[],'result':{'transaction_id':tx,'kind':'updated',
                    'version':prepared.target['version'],'code':prepared.target['code'],'needs_recovery_clear':False,
                    'recorded':{'elapsed':1100 if early else 601001}}}
                raise AssertionError(path)
            def private(self,path,*args):
                if path=='files/update/previous.apk':return prepared.baseline['sha256'] if ticks[0]<600 else None
                if path in target.PENDING:return None
                if path=='shared_prefs/recovery.xml':return ('<map><boolean name="safe_mode" value="false"/><string name="healthy_update">'+tx+'</string></map>').encode()
                raise AssertionError(path)
            def shell(self,*args):return 'actual-session-dump'
            def adb(self,*args):return b'1793860000.001 22 22 I ThinkOS: Update trial ready; health_ms=600000 elapsed=1000\n'
        guard=types.SimpleNamespace(pid=11,install_until=0,check=lambda:None)
        output=self.inputs.material.work/('health-early' if early else 'health-wrong' if wrong else 'health-ok');output.mkdir()
        actor=target.UI(Device(),guard,None,prepared,output);actor.ready_proof=lambda:None
        actor.install=True
        actor.effective=lambda key:False;actor.tap=lambda label:actor.result.update(actual_touch_label=label)
        actor.wait=lambda predicate,seconds=30:predicate();actor.history_before_doc={'failures':[]};actor.recovery_before={};actor.original_uid=10001
        actor.original_policy={}
        def advance(seconds):ticks[0]+=seconds
        return actor,ticks,advance

    def test_install_actor_uses_native_Install_and_waits_full_600_virtual_host_seconds_without_shortening_production_policy(self):
        import self_update_device
        actor,ticks,advance=self._health_actor()
        receipt={'session_id':42,'destroyed':True,'legacy_status':1,'permissions_manually_accepted':False,'installer_uid':10001}
        with patch.object(target.time,'monotonic',side_effect=lambda:ticks[0]),patch.object(target.time,'sleep',side_effect=advance), \
             patch.object(self_update_device,'install_session',return_value=receipt):actor.install_healthy()
        self.assertEqual(actor.result['actual_touch_label'],'立即安装')
        self.assertGreaterEqual(ticks[0],600);self.assertTrue(actor.result['real_600s_health'])
        self.assertEqual(actor.result['new_pid'],22)

    def test_changed_install_tuple_or_premature_native_health_fails_and_never_changes_or_clears_history(self):
        for early,wrong,reason in ((False,True,'exact manual transaction'),(True,False,'before its real 600-second gate')):
            actor,ticks,advance=self._health_actor(early,wrong)
            with patch.object(target.time,'monotonic',side_effect=lambda:ticks[0]),patch.object(target.time,'sleep',side_effect=advance):
                with self.assertRaisesRegex(RuntimeError,reason):actor.install_healthy()
            self.assertFalse(actor.result['installed'])


class Ota5SettingsSchemaParserTest(unittest.TestCase):
    def test_actual_settings_schema_accepts_its_finite_decimal_defaults_and_steps(self):
        raw = (driver.ROOT / 'shared/schema/settings/system.json').read_bytes()
        document = driver.settings_schema(raw)
        self.assertEqual(document['scope'], 'system')
        self.assertEqual(document['fields']['onboarding.greeting_s']['default'], 2.4)
        self.assertEqual(document['fields']['location.latitude']['step'], 0.001)
        with self.assertRaises(ValueError):
            driver.release.parse_document(raw)  # Distribution parser remains deliberately integer-only.

    def test_settings_schema_rejects_duplicate_nonfinite_overflow_and_oversized_input(self):
        for raw in (b'{"fields":{"n":{"default":1,"default":2}}}',
                    b'{"n":NaN}', b'{"n":Infinity}', b'{"n":-Infinity}', b'{"n":1e9999}',
                    b' ' * (1024 * 1024 + 1)):
            with self.subTest(kind=raw[:40]), self.assertRaises(ValueError):
                driver.settings_schema(raw)


    def test_read_only_document_allows_finite_settings_layers_but_never_float_ledgers(self):
        device = object.__new__(target.Device)
        values = {'files/settings/local.json': b'{"system":{"location.latitude":22.25}}',
                  'files/settings/server.json': b'{"system":{"onboarding.greeting_s":2.4}}',
                  'files/update/ledger/sequences.json': b'{"schema":1,"beta":{"seq":1.5}}'}
        device.private = lambda path: values[path]
        self.assertEqual(device.document('files/settings/local.json')['system']['location.latitude'], 22.25)
        self.assertEqual(device.document('files/settings/server.json')['system']['onboarding.greeting_s'], 2.4)
        with self.assertRaises(ValueError):
            device.document('files/update/ledger/sequences.json')
        for raw in (b'{"system":{"location.latitude":NaN}}', b'{"system":{"x":1,"x":2}}'):
            values['files/settings/local.json'] = raw
            with self.assertRaises(ValueError):
                device.document('files/settings/local.json')
