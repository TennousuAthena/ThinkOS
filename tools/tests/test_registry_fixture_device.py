"""Host boundaries only: no real ADB, device, timer, network or private restore."""
import contextlib
import io
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parent))
import registry_fixture_device as target


class Transport:
    def __init__(self, stdout=b'', code=0):
        self.stdout,self.code,self.calls = stdout,code,[]
    def __call__(self,args,**kwargs):
        self.calls.append(args)
        return subprocess.CompletedProcess(args,self.code,self.stdout,b'')


class QuietWatch:
    def __init__(self,*args): self.closed=False
    def check(self): pass
    def close(self): self.closed=True


class RegistryFixtureDeviceBoundaryTest(unittest.TestCase):
    def test_initial_write_uses_fully_qualified_fixture_receiver(self):
        transport=Transport()
        device=target.Device('HUA0BWUT',run=transport)
        device.debug('setting',scope='system',key='cast.miracast',value='false')
        words=shlex.split(transport.calls[0][-1])
        self.assertIn(target.FIXTURE+'/moe.kiwi.thinkos.DebugReceiver',words)
        self.assertNotIn(target.ORIGINAL+'/moe.kiwi.thinkos.DebugReceiver',words)
        self.assertNotIn(target.FIXTURE+'/.DebugReceiver',words)
        self.assertEqual(transport.calls[0][:3],['adb','-s','HUA0BWUT'])

    def test_original_setting_trial_override_and_unknown_command_are_rejected_before_io(self):
        transport=Transport(); device=target.Device('HUA0BWUT',run=transport)
        for kwargs in ({'package':target.ORIGINAL,'scope':'system','key':'cast.miracast','value':'false'},
                       {'scope':'system','key':'plugins.trial_s','value':'60'},
                       {'scope':'system','key':'cast.miracast','value':'true'}):
            with self.assertRaises(target.Stopped): device.debug('setting',**kwargs)
        with self.assertRaises(target.Stopped): device.debug('clearSafeMode')
        self.assertEqual(transport.calls,[])

    def test_private_files_are_read_only_and_shell_token_is_quoted(self):
        transport=Transport(b'PRESENT\n{"installed":[]}')
        device=target.Device('HUA0BWUT',run=transport)
        self.assertEqual(device.json(target.STATE),{'installed':[]})
        words=shlex.split(transport.calls[0][-1])
        self.assertEqual(words[:4],['run-as',target.FIXTURE,'sh','-c'])
        self.assertIn("printf 'PRESENT\\n'",words[4])
        self.assertNotIn(' > ',words[4])
        with self.assertRaises(target.Stopped): device.read(target.FIXTURE,'../settings.xml')
        self.assertEqual(len(transport.calls),1)

    def test_adb_failure_is_not_replayed(self):
        transport=Transport(code=1); device=target.Device('HUA0BWUT',run=transport)
        with self.assertRaises(target.Stopped): device.shell('am','force-stop',target.FIXTURE)
        self.assertEqual(len(transport.calls),1)

    def test_log_evidence_requires_exact_current_fixture_pid(self):
        raw='1762300000.001 1122 21 I ThinkOS: [plugin:x] REGISTRY_STARTED 1.0.0\n1762300000.002 3344 22 I ThinkOS: [plugin:x] REGISTRY_STARTED 1.0.2\nI/ThinkOS(3344): wrong-format\n'
        self.assertEqual(target.pid_logs(raw,3344),[raw.splitlines()[1]])
        self.assertEqual(target.pid_logs(raw,9999),[])

    def test_transition_is_observation_only_and_never_allows_touch_on_wrong_app(self):
        device=object(); watch=QuietWatch()
        guard=target.Guard(device,watch,'home',[] ,())
        guard.transition_until=10**20
        with patch.object(target,'quiet',return_value=True),patch.object(target,'focused_component',return_value=target.ORIGINAL+'/moe.kiwi.thinkos.MainActivity'):
            self.assertIsNotNone(guard.observe_transition())
            with self.assertRaises(target.Stopped): guard.check(expected=target.FIXTURE)
        with patch.object(target,'quiet',return_value=True),patch.object(target,'focused_component',return_value='untrusted.other/.Main'):
            with self.assertRaisesRegex(target.Stopped,'untrusted.other'): guard.observe_transition()
        with patch.object(target,'quiet',return_value=True),patch.object(target,'focused_component',return_value=None):
            self.assertIsNone(guard.observe_transition())
            with self.assertRaises(target.Stopped): guard.check(expected=target.ORIGINAL)
            guard.transition_until=0
            with self.assertRaises(target.Stopped): guard.observe_transition()

    def test_owner_identity_ignores_unrelated_dynamic_policy_counters(self):
        raw='Device Owner:\n admin=ComponentInfo{moe.kiwi.thinkos/.ThinkAdminReceiver}\n name=ThinkOS\n uptime=1\n'
        self.assertEqual(target.owner_identity(raw),target.owner_identity(raw.replace('uptime=1','uptime=2')))
        self.assertNotEqual(target.owner_identity(raw),target.owner_identity(raw.replace('moe.kiwi.thinkos/.ThinkAdminReceiver','other/.Admin')))

    def test_hierarchy_guard_target_is_narrow_and_restored_on_failure(self):
        class FakeGuard:
            target=target.FIXTURE
            def check(self,expected=None): self.seen=expected or self.target
        guard=FakeGuard()
        class FailingHierarchy:
            def tree(self):
                self.observed=guard.target
                raise RuntimeError('owned hierarchy failed')
        hierarchy=FailingHierarchy()
        driver=object.__new__(target.Driver); driver.guard=guard; driver.h=hierarchy
        with self.assertRaises(RuntimeError): driver.nodes(expected=target.ORIGINAL)
        self.assertEqual(hierarchy.observed,target.ORIGINAL)
        self.assertEqual(guard.target,target.FIXTURE)

    def test_cli_rejects_apk_roots_before_any_device_or_origin_io(self):
        with tempfile.TemporaryDirectory(prefix='thinkos-regfixture-cli-',dir='/private/tmp') as temporary:
            root=Path(temporary); public=root/'public'; public.mkdir()
            (public/'keys.json').write_bytes(b'{"schema":1}')
            apk=root/'fixture.apk'
            with zipfile.ZipFile(apk,'w') as archive: archive.writestr('assets/update/keys.json',b'{"different":true}')
            jar=root/'hierarchy.jar'; jar.write_bytes(b'prebuilt')
            material=types.SimpleNamespace(directory=root,meta={'public_keys_sha256':target.sha((public/'keys.json').read_bytes())})
            calls=[]
            def forbidden(*args): calls.append(args); raise AssertionError('Unexpected IO')
            with self.assertRaises(target.Stopped):
                target.main(['--serial','HUA0BWUT','--material',str(root),'--apk',str(apk),'--hierarchy-jar',str(jar),'--output',str(root/'result')],
                            material_open=lambda _:material,device_factory=forbidden,origin_factory=forbidden)
            self.assertEqual(calls,[])
            self.assertIn('APK fixed roots differ',(root/'result/failure.txt').read_text())
            self.assertFalse((root/'result/cleanup-failure.txt').exists())

    def _exercise_cli(self, cleanup_error=None, main_error=None):
        with tempfile.TemporaryDirectory(prefix='thinkos-regfixture-cli-',dir='/private/tmp') as temporary:
            root=Path(temporary); (root/'public').mkdir(); public=b'{"schema":1}'
            (root/'public/keys.json').write_bytes(public)
            apk=root/'fixture.apk'
            with zipfile.ZipFile(apk,'w') as archive:
                archive.writestr('assets/update/keys.json',public)
                archive.writestr('assets/settings/system.json',(target.ROOT/'shared/schema/settings/system.json').read_bytes())
            jar=root/'hierarchy.jar'; jar.write_bytes(b'prebuilt')
            meta={'run_id':'a'*32,'id':'reg-probe-'+('a'*12),'registry_url':'http://192.168.1.2:8877/reg2b/'+('a'*32)+'/registry/index.json',
                  'base_url':'http://192.168.1.2:8877/reg2b/'+('a'*32)+'/', 'public_keys_sha256':target.sha(public)}
            material=types.SimpleNamespace(directory=root,meta=meta)
            calls=[]
            class FakeDevice:
                def __init__(self,*_): self.settings={}
                def original_hashes(self): calls.append('original-read'); return ['unchanged']
                def pid(self): return 4321
                def debug(self,command,**values):
                    calls.append(('debug',command,values)); self.settings[values['key']]=values['value']
                def state(self,*_,**__): return {'locale':'zh-CN','onboardingOpen':False}
                def shell(self,*values,**kwargs): calls.append(('shell',values)); return ''
            class FakeOrigin:
                server=None
                def __init__(self,*_): pass
                def start(self): calls.append('origin-start'); self.server=object(); return self
                def close(self): calls.append('origin-close'); self.server=None
            class FakeHierarchy:
                remote='/data/local/tmp/owned'; jar=remote+'/hierarchy.jar'
                def __init__(self,*_): calls.append('hierarchy')
            def setting(device,key,default,package=target.FIXTURE):
                value=device.settings[key]
                return {'true':True,'false':False,'1':1}.get(value,value)
            def run(driver):
                calls.append('real-driver-boundary')
                if main_error is not None:
                    raise main_error
                driver.check('fake lifecycle only',True)
            with patch.object(target,'preflight',return_value=('home',[],())),patch.object(target.Guard,'check'),patch.object(target.Guard,'policy'),patch.object(target,'effective',side_effect=setting),patch.object(target.Driver,'run',run),patch.object(target.Driver,'return_original'),patch.object(target,'cleanup_hierarchy',side_effect=cleanup_error),contextlib.redirect_stdout(io.StringIO()):
                def invoke():
                    return target.main(['--serial','HUA0BWUT','--material',str(root),'--apk',str(apk),'--hierarchy-jar',str(jar),'--output',str(root/'result')],
                                       device_factory=FakeDevice,material_open=lambda _:material,origin_factory=FakeOrigin,watch_factory=QuietWatch,hierarchy_factory=FakeHierarchy)
                if main_error is not None:
                    with self.assertRaises(type(main_error)) as raised:
                        invoke()
                    self.assertIs(raised.exception,main_error)
                elif cleanup_error is not None:
                    with self.assertRaisesRegex(target.Stopped,'Owned cleanup failed'):
                        invoke()
                else:
                    self.assertEqual(invoke(),0)
            self.assertLess(calls.index('original-read'),calls.index('origin-start'))
            self.assertLess(calls.index('origin-start'),calls.index('real-driver-boundary'))
            self.assertEqual(sum(item=='original-read' for item in calls),1 if main_error is not None else 2)
            commands=[item[1] for item in calls if isinstance(item,tuple) and item[0]=='shell']
            self.assertFalse(any(values[:1] in [('install',),('uninstall',)] or 'force-stop' in values for values in commands))
            result_doc=json.loads((root/'result/result.json').read_text())
            self.assertEqual(result_doc['complete'],cleanup_error is None and main_error is None)
            self.assertFalse(result_doc['original_private_data_written'])
            if cleanup_error is not None:
                self.assertIn('retained',result_doc['temporary_cleanup'])
                self.assertTrue((root/'result/cleanup-failure.txt').is_file())
            else:
                self.assertEqual(result_doc['temporary_cleanup'],'owned helper/reverse removed')
            if main_error is not None:
                self.assertEqual(result_doc['error'],type(main_error).__name__)
                self.assertIn('original main failure',(root/'result/failure.txt').read_text())

    def test_cli_runs_boundary_in_order_with_fake_device_and_never_installs_apk(self):
        self._exercise_cli()

    def test_cleanup_failure_cannot_keep_complete_or_return_success(self):
        self._exercise_cli(cleanup_error=target.Stopped('Unknown owned helper layout retained'))

    def test_cleanup_failure_does_not_mask_original_main_error(self):
        self._exercise_cli(cleanup_error=target.Stopped('Unknown owned helper layout retained'),
                           main_error=ValueError('original main failure'))

    def test_unknown_helper_layout_is_retained_without_delete(self):
        remote='/data/local/tmp/thinkos-registry-'+('a'*32)
        hierarchy=types.SimpleNamespace(remote=remote,jar=remote+'/hierarchy.jar')
        calls=[]
        class FakeDevice:
            def shell(self,*args):
                calls.append(args)
                return '\n'.join([remote,hierarchy.jar,remote+'/unknown.owner'])
        with self.assertRaisesRegex(target.Stopped,'Unknown owned helper layout'):
            target.cleanup_hierarchy(FakeDevice(),QuietWatch(),hierarchy)
        self.assertEqual(len(calls),1)
        self.assertEqual(calls[0][0],'find')

    def test_known_oat_cleanup_is_exact_and_never_recursive(self):
        remote='/data/local/tmp/thinkos-registry-'+('a'*32)
        hierarchy=types.SimpleNamespace(remote=remote,jar=remote+'/hierarchy.jar')
        entries=[remote,hierarchy.jar,remote+'/oat',remote+'/oat/arm',remote+'/oat/arm/hierarchy.odex',remote+'/oat/arm/hierarchy.vdex']
        calls=[]
        class FakeDevice:
            def shell(self,*args):
                calls.append(args)
                return '\n'.join(entries) if args==('find',remote,'-print') else ''
        target.cleanup_hierarchy(FakeDevice(),QuietWatch(),hierarchy)
        self.assertIn(('rm','-f',hierarchy.jar,entries[-2],entries[-1]),calls)
        self.assertEqual([c for c in calls if c[0]=='rmdir'],[('rmdir',remote+'/oat/arm'),('rmdir',remote+'/oat'),('rmdir',remote)])
        self.assertFalse(any('-rf' in c for c in calls))

    def test_existing_original_pending_stops_preflight_without_fixture_mutation(self):
        calls=[]
        class FakeDevice:
            def shell(self,*args):
                calls.append(args)
                if args[:1]==('getprop',): return {'ro.serialno':'HUA0BWUT','ro.build.version.sdk':'27','ro.product.cpu.abilist':'armeabi-v7a,armeabi'}[args[1]]
                if args[:2]==('pm','path'): return 'package:/data/app/fixture/base.apk'
                if args[:1]==('sha256sum',): return 'a'*64+'  /data/app/fixture/base.apk'
                if args==('dumpsys','package',target.FIXTURE): return 'userId=10100 DEBUGGABLE'
                if args==('dumpsys','package',target.ORIGINAL): return 'userId=10074'
                if args==('dumpsys','device_policy'): return 'Device Owner:\n admin=ComponentInfo{'+target.ORIGINAL+'/.admin.ThinkOSAdminReceiver}\n'
                raise AssertionError('Unexpected shell: '+str(args))
            def read(self,package,path):
                calls.append(('read',package,path)); return b'{}'
        with patch.object(target,'focused_component',return_value=target.FIXTURE+'/moe.kiwi.thinkos.MainActivity'),patch.object(target,'home_component',return_value=target.ORIGINAL+'/.MainActivity'),patch.object(target,'home_candidates',return_value=[target.ORIGINAL+'/.MainActivity']):
            with self.assertRaisesRegex(target.Stopped,'transaction exists'):
                target.preflight(FakeDevice(),'a'*64,object())
        self.assertIn(('read',target.ORIGINAL,target.PENDING_PATHS[0]),calls)
        self.assertFalse(any(c[0] in {'am','input'} for c in calls))

    def test_apk_defaults_come_from_packaged_schema_and_required_types_are_checked(self):
        schema=json.loads((target.ROOT/'shared/schema/settings/system.json').read_bytes())
        with tempfile.TemporaryDirectory() as temporary:
            apk=Path(temporary)/'fixture.apk'
            with zipfile.ZipFile(apk,'w') as archive:
                archive.writestr('assets/settings/system.json',json.dumps(schema))
            defaults=target.apk_settings_defaults(apk,'http://127.0.0.1:8877/registry/index.json')
            self.assertEqual(defaults['plugins.trial_s'],schema['fields']['plugins.trial_s']['default'])
            self.assertEqual(defaults['plugins.auto_update'],schema['fields']['plugins.auto_update']['default'])
            self.assertIs(defaults['cast.miracast'],False)
            schema['fields']['cast.miracast']['default']='false'
            with zipfile.ZipFile(apk,'w') as archive:
                archive.writestr('assets/settings/system.json',json.dumps(schema))
            with self.assertRaisesRegex(target.Stopped,'type/default/value incompatible'):
                target.apk_settings_defaults(apk,'http://127.0.0.1:8877/registry/index.json')

    def test_omitted_default_is_effective_without_requiring_override_serialization(self):
        calls=[]
        class FakeDevice:
            schema_defaults={'cast.miracast':False}
            def debug(self,*args,**values): calls.append((args,values))
            def json(self,*args): return {}  # Accepted false equals lower/default; no override is written.
        device=FakeDevice()
        self.assertIs(target.effective(device,'cast.miracast',None),False)
        target.set_initial_setting(device,QuietWatch(),'cast.miracast',False)
        self.assertEqual(len(calls),1)
        self.assertEqual(calls[0][1]['value'],'false')

    def test_delayed_settings_file_is_polled_without_resending_write(self):
        calls=[]; local_reads=[]; ticks=[0.0]
        class FakeDevice:
            schema_defaults={'onboarding.done':0}
            def debug(self,*args,**values): calls.append((args,values))
            def json(self,path,package):
                if path.endswith('/server.json'): return {}
                local_reads.append(path)
                # Model SettingsIO's delayed file snapshot, not immediate echo.
                return {'system':{'onboarding.done':1}} if ticks[0] >= .5 else {}
        def advance(value): ticks[0]+=value
        with patch.object(target.time,'monotonic',side_effect=lambda:ticks[0]),patch.object(target.time,'sleep',side_effect=advance):
            target.set_initial_setting(FakeDevice(),QuietWatch(),'onboarding.done',1)
        self.assertGreaterEqual(ticks[0],.5)
        self.assertGreater(len(local_reads),1)
        self.assertEqual(len(calls),1)

    def test_unflushed_setting_times_out_after_one_write(self):
        calls=[]; ticks=[0.0]
        class FakeDevice:
            schema_defaults={'onboarding.done':0}
            def debug(self,*args,**values): calls.append((args,values))
            def json(self,*args): return {}
        def advance(value): ticks[0]+=value
        with patch.object(target.time,'monotonic',side_effect=lambda:ticks[0]),patch.object(target.time,'sleep',side_effect=advance):
            with self.assertRaisesRegex(target.Stopped,'readback timed out'):
                target.set_initial_setting(FakeDevice(),QuietWatch(),'onboarding.done',1)
        self.assertEqual(len(calls),1)
        self.assertGreaterEqual(ticks[0],5)
        self.assertLess(ticks[0],5.2)

    def test_preflight_does_not_infer_current_api_or_abi_from_serial(self):
        for sdk,abi,reason in [('28','armeabi-v7a','API'),('27','arm64-v8a','ABI')]:
            calls=[]
            class FakeDevice:
                def shell(self,*args):
                    calls.append(args)
                    return {'ro.serialno':'HUA0BWUT','ro.build.version.sdk':sdk,'ro.product.cpu.abilist':abi}[args[1]]
            with self.subTest(sdk=sdk,abi=abi),self.assertRaisesRegex(target.Stopped,reason):
                target.preflight(FakeDevice(),'a'*64,object())
            self.assertTrue(all(c[0]=='getprop' for c in calls))

    def _early_cli_failure(self, stage, fail_diagnostic_write=False):
        with tempfile.TemporaryDirectory(prefix='thinkos-regfixture-early-',dir='/private/tmp') as temporary:
            root=Path(temporary); (root/'public').mkdir(); public=b'{"schema":1}'
            (root/'public/keys.json').write_bytes(public)
            apk=root/'fixture.apk'
            with zipfile.ZipFile(apk,'w') as archive:
                archive.writestr('assets/update/keys.json',public)
                archive.writestr('assets/settings/system.json',(target.ROOT/'shared/schema/settings/system.json').read_bytes())
            jar=root/'hierarchy.jar'; jar.write_bytes(b'prebuilt')
            material=types.SimpleNamespace(directory=root,meta={'public_keys_sha256':target.sha(public),'registry_url':'http://127.0.0.1:8877/registry/index.json'})
            failure=target.Stopped('Unexpected platform permission foreground') if stage=='preflight' else ValueError('early '+stage+' failure')
            calls=[]
            def load(*_):
                calls.append('material')
                if stage=='material': raise failure
                return material
            def device(*_): calls.append('device'); return types.SimpleNamespace()
            def check(*_):
                calls.append('preflight')
                if stage=='preflight': raise failure
                return ('original-home',[],())
            def watch(*_): calls.append('watch'); raise failure
            def forbidden(*_): raise AssertionError('No early-failure device cleanup or origin start')
            original_write=Path.write_text
            def write(path,*args,**kwargs):
                if fail_diagnostic_write and path.name=='failure.txt': raise OSError('diagnostic disk unavailable')
                return original_write(path,*args,**kwargs)
            with patch.object(target,'preflight',side_effect=check),patch.object(target,'cleanup_hierarchy',side_effect=forbidden),patch.object(Path,'write_text',autospec=True,side_effect=write):
                with self.assertRaises(type(failure)) as raised:
                    target.main(['--serial','HUA0BWUT','--material',str(root),'--apk',str(apk),'--hierarchy-jar',str(jar),'--output',str(root/'result')],
                                device_factory=device,material_open=load,origin_factory=forbidden,watch_factory=watch,hierarchy_factory=forbidden)
            self.assertIs(raised.exception,failure)
            self.assertTrue((root/'result').is_dir())
            if not fail_diagnostic_write:
                self.assertIn(str(failure),(root/'result/failure.txt').read_text())
            self.assertFalse((root/'result/cleanup-failure.txt').exists())
            self.assertFalse((root/'result/result.json').exists())
            self.assertEqual(calls,{'material':['material'],'preflight':['material','device','preflight'],
                                    'watch':['material','device','preflight','watch']}[stage])

    def test_early_material_preflight_and_watch_failures_keep_details(self):
        for stage in ('material','preflight','watch'):
            with self.subTest(stage=stage): self._early_cli_failure(stage)

    def test_early_diagnostic_write_failure_does_not_mask_original_exception(self):
        self._early_cli_failure('material',fail_diagnostic_write=True)

    def test_preexisting_output_is_rejected_without_writing_failure_file(self):
        with tempfile.TemporaryDirectory(prefix='thinkos-regfixture-existing-',dir='/private/tmp') as temporary:
            root=Path(temporary); output=root/'existing'; output.mkdir()
            sentinel=output/'owner.txt'; sentinel.write_text('keep')
            jar=root/'hierarchy.jar'; jar.write_bytes(b'prebuilt')
            with self.assertRaisesRegex(target.Stopped,'New private host TMP output required'):
                target.main(['--serial','HUA0BWUT','--material',str(root),'--apk',str(root/'unused.apk'),'--hierarchy-jar',str(jar),'--output',str(output)],
                            material_open=lambda *_:self.fail('No input opened before output validation'))
            self.assertEqual(sentinel.read_text(),'keep')
            self.assertEqual(set(p.name for p in output.iterdir()),{'owner.txt'})

    def _witness_driver(self, lines=None, record_digest=None):
        driver=object.__new__(target.Driver)
        driver.pid=1234; driver.ident='reg-probe-fixture'
        driver.meta={'digests':{'1.0.0':'a'*64,'1.0.2':'b'*64}}
        driver.start_witnesses={}; driver.result={'start_witnesses':[]}; driver.guard=QuietWatch()
        driver.save=lambda:None
        record={'id':driver.ident,'version':'1.0.0','digest':record_digest or 'a'*64,'enabled':True,'trial':True}
        driver.record=lambda:dict(record)
        driver.active=lambda version:version=='1.0.0'
        class FakeDevice:
            pid_value=1234
            def __init__(self): self.lines=list(lines or []); self.log_reads=0
            def pid(self): return self.pid_value
            def logs(self,pid): self.log_reads+=1; return list(self.lines)
        driver.d=FakeDevice()
        return driver,record

    def test_start_witness_survives_ring_rollover_without_recreating_a_marker(self):
        line='1793862700.123 1234 1240 I ThinkOS : [plugin:reg-probe-fixture] REGISTRY_STARTED 1.0.0'
        driver,record=self._witness_driver([line])
        self.assertTrue(driver.capture_started('1.0.0'))
        witness=driver.result['start_witnesses'][0]
        self.assertEqual((witness['pid'],witness['version'],witness['digest']),(1234,'1.0.0','a'*64))
        self.assertEqual(witness['marker'],line)
        reads=driver.d.log_reads
        driver.d.lines=[]; record['trial']=False
        self.assertTrue(driver.started('1.0.0'))
        self.assertEqual(driver.d.log_reads,reads)
        self.assertEqual(len(driver.start_witnesses),1)

    def test_start_witness_rejects_wrong_pid_version_digest_or_inactive_vm(self):
        good='1793862700.123 1234 1240 I ThinkOS : [plugin:reg-probe-fixture] REGISTRY_STARTED 1.0.0'
        for line,digest,active in [(good.replace(' 1234 ',' 9999 '),'a'*64,True),
                                  (good+'-other','a'*64,True),(good,'c'*64,True),(good,'a'*64,False)]:
            driver,_=self._witness_driver([line],record_digest=digest)
            driver.active=lambda version:active
            self.assertFalse(driver.capture_started('1.0.0'))
            self.assertEqual(driver.result['start_witnesses'],[])
        driver,_=self._witness_driver([good]); self.assertTrue(driver.capture_started('1.0.0'))
        driver.pid=5678; driver.d.pid_value=5678
        self.assertFalse(driver.started('1.0.0'))
        self.assertFalse(driver.capture_started('1.0.0'))  # Old PID line cannot certify new cold PID.
        driver.d.lines=[good.replace(' 1234 ',' 5678 ')]
        self.assertTrue(driver.capture_started('1.0.0'))
        self.assertEqual(len(driver.result['start_witnesses']),2)

    def test_single_owned_paused_media_uses_actual_one_based_snapshot(self):
        driver=object.__new__(target.Driver); driver.ident='reg-probe-fixture'
        base={'owner':'reg-probe-fixture:7','kind':'video','status':'paused','count':1,'index':1}
        for overrides,expected in [({},True),({'index':0},False),({'owner':'foreign:7'},False),
                                   ({'owner':'reg-probe-fixture:0'},False),({'count':2},False)]:
            state={**base,**overrides}
            driver.d=types.SimpleNamespace(state=lambda _:dict(state))
            self.assertEqual(driver.paused_media() is not None,expected)

    def _hub_action_nodes(self, include_body=True, top=500):
        def node(text,bounds,description=''):
            return {'text':text,'content-desc':description,'bounds':bounds}
        rows=[node('发现',f'[10,{top}][160,{top+48}]'),
              node('已安装',f'[170,{top}][320,{top+48}]'),
              node('更新',f'[330,{top}][480,{top+48}]'),
              node('',f'[335,{top+2}][475,{top+46}]','更新')]
        if include_body: rows.append(node('更新',f'[30,{top+400}][700,{top+448}]'))
        return rows

    def test_body_action_excludes_duplicate_tabs_without_changing_normal_tab_selection(self):
        for top in (50,500):
            rows=self._hub_action_nodes(top=top); calls=[]
            driver=object.__new__(target.Driver)
            def nodes(expected): calls.append(expected); return list(rows)
            driver.nodes=nodes
            self.assertIs(driver.node(label='更新'),rows[2])
            calls.clear()
            self.assertIs(driver.node(label='更新',body=True),rows[-1])
            self.assertEqual(calls,[target.FIXTURE])  # Header and action share one snapshot.

    def test_body_action_absent_or_unanchored_returns_none_instead_of_a_tab(self):
        driver=object.__new__(target.Driver)
        rows=self._hub_action_nodes(include_body=False)
        driver.nodes=lambda expected:list(rows)
        self.assertIsNone(driver.node(label='更新',body=True))
        rows=self._hub_action_nodes(); rows=[n for n in rows if n.get('text')!='发现']
        self.assertIsNone(driver.node(label='更新',body=True))
        rows=self._hub_action_nodes(); rows[1]['bounds']='[170,100][320,148]'
        self.assertIsNone(driver.node(label='更新',body=True))  # Different rows are not header anchors.

    def test_body_action_tap_scrolls_until_real_detail_action_appears(self):
        driver=object.__new__(target.Driver); states=[False]; scrolls=[]; taps=[]
        driver.nodes=lambda expected:self._hub_action_nodes(include_body=states[0])
        driver.wait=lambda predicate,**kwargs:predicate()
        def scroll(**kwargs): scrolls.append(kwargs); states[0]=True
        driver.scroll=scroll
        driver.tap_node=lambda node,expected:taps.append((node,expected))
        driver.tap('更新',scroll=True,body=True)
        self.assertEqual(len(scrolls),1)
        self.assertEqual(len(taps),1)
        self.assertEqual(target.node_bounds(taps[0][0]),(30,900,700,948))
        self.assertEqual(taps[0][1],target.FIXTURE)

    def _cold_checkpoint_driver(self, output, candidate='1.0.3'):
        driver=object.__new__(target.Driver);driver.pid=1234;driver.ident='reg-probe-fixture'
        driver.meta={'digests':{'1.0.2':'a'*64,'1.0.3':'b'*64,'1.0.5':'c'*64}}
        driver.guard=QuietWatch();driver.output=output;driver.result={}
        driver.record=lambda:{'id':driver.ident,'version':candidate,'digest':driver.meta['digests'][candidate],'enabled':True,'trial':True}
        driver.active=lambda version:version==candidate
        driver.d=types.SimpleNamespace(pid=lambda:1234,read=lambda package,path:b'cache-original' if path.endswith('cache.json') else b'ledger-original')
        driver.phase=lambda *_:None
        return driver

    def test_cold_checkpoint_persists_only_hashes_and_exact_candidate_before_external_actions(self):
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary);driver=self._cold_checkpoint_driver(output,'1.0.5')
            before=driver.checkpoint_cold('1.0.5')
            saved=json.loads((output/'cold-before.json').read_text())
            self.assertEqual(saved,before)
            self.assertEqual(saved['old_pid'],1234)
            self.assertEqual(saved['candidate_version'],'1.0.5')
            self.assertEqual(saved['candidate_digest'],'c'*64)
            self.assertEqual(saved['cache_sha256'],target.sha(b'cache-original'))
            self.assertEqual(saved['ledger_sha256'],target.sha(b'ledger-original'))
            self.assertNotIn('cache-original',(output/'cold-before.json').read_text())
            self.assertNotIn('ledger-original',(output/'cold-before.json').read_text())
            self.assertEqual(json.loads((output/'result.json').read_text())['cold_before'],saved)
            self.assertEqual((output/'cold-before.json').stat().st_mode & 0o777,0o600)

    def test_cold_checkpoint_failure_prevents_origin_stop_and_preserves_existing_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary);driver=self._cold_checkpoint_driver(output)
            existing=output/'cold-before.json';existing.write_text('owner evidence')
            calls=[];driver.origin=types.SimpleNamespace(close=lambda:calls.append('closed'))
            with self.assertRaises(FileExistsError):driver.cold()
            self.assertEqual(calls,[])
            self.assertEqual(existing.read_text(),'owner evidence')
