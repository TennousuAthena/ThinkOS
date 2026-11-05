"""Focused actor boundaries only. No ADB, server, keys, sleep or APK execution."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parent))
import registry_fixture_rejections as target


def fixture():
    meta={'id':'reg-probe-'+('a'*12),'digests':{'1.0.2':'b'*64},
          'versions':{'1.0.2':{'permissions':['network','media','location']}},
          'registry_url':'http://192.168.1.2:8877/reg2b/'+('a'*32)+'/registry/index.json'}
    record={'id':meta['id'],'version':'1.0.2','digest':'b'*64,'enabled':True,'trial':False,
            'confirmed':['network','media','location'],'auto':True,'commit':'c'*40}
    state={'schema':1,'disabled':False,'installed':[record],
           'blocked':[{'id':meta['id'],'digest':'d'*64,'reason':'trial_interrupted'}]}
    return meta,state


class RejectionBoundaryTest(unittest.TestCase):
    def test_healthy_gate_requires_exact_existing_v3_and_permissions_without_erasing_history(self):
        meta,state=fixture();self.assertTrue(target.healthy_state(state,meta))
        for key,value in [('trial',True),('enabled',False),('version','1.0.3'),('digest','e'*64),('confirmed',['network'])]:
            changed=copy.deepcopy(state);changed['installed'][0][key]=value
            with self.subTest(key=key):self.assertFalse(target.healthy_state(changed,meta))
        extra=copy.deepcopy(state);extra['installed'].append(dict(extra['installed'][0],id='unexpected'))
        self.assertFalse(target.healthy_state(extra,meta))
        self.assertEqual(state['blocked'][0]['digest'],'d'*64)

    def test_revocation_is_the_only_allowed_last_state_change_and_cannot_clear_blocked(self):
        meta,before=fixture();after=copy.deepcopy(before)
        after['installed'][0].update(enabled=False,trial=False,error='revoked')
        self.assertTrue(target.revocation_state(before,after,meta['id']))
        for mutate in (lambda v:v.update(blocked=[]),lambda v:v['installed'][0].update(confirmed=[]),
                       lambda v:v['installed'][0].update(error='startup-failed'),lambda v:v['installed'][0].update(enabled=True)):
            changed=copy.deepcopy(after);mutate(changed)
            self.assertFalse(target.revocation_state(before,changed,meta['id']))
        self.assertTrue(before['installed'][0]['enabled'])

    def test_continuation_preflight_preserves_default_fresh_gate_and_refuses_floor_rewind_or_wrong_APK_version(self):
        meta,state=fixture();raw=b'{"seq":9}'
        calls=[]
        class Device:
            package='versionName=0.1.30\nversionCode=1030 minSdk=27'
            floor={'seq':9,'digest':target.base.sha(raw)}
            def shell(self,*args):calls.append(args);return self.package
            def json(self,path):return state if path==target.base.STATE else self.floor
            def read(self,*args):return None
            def state(self,command='ui_state'):
                return {'owner':'','count':0,'status':'stopped'} if command=='media_state' else {'locale':'zh-CN','onboardingOpen':False}
        material=types.SimpleNamespace(meta=meta,prefix='/reg2b/'+('a'*32)+'/',
            response=lambda path:b'signature' if path.endswith('.sig') else raw,
            verify=lambda *args:True)
        values={**target.base.INITIAL,'plugins.mirror':meta['registry_url']}
        device=Device()
        with patch.object(target.base,'preflight',return_value=('home',[],('owner',))) as inherited, \
             patch.object(target.base,'effective',side_effect=lambda d,k,default:values[k]):
            self.assertEqual(target.continuation_preflight(device,'fixed-sha',material),('home',[],('owner',)))
            inherited.assert_called_with(device,'fixed-sha',material,require_fresh=False,expected_return_after_s=900)
            device.floor={'seq':10,'digest':'e'*64}
            with self.assertRaisesRegex(target.base.Stopped,'behind/forks'):target.continuation_preflight(device,'fixed-sha',material)
            device.floor={'seq':9,'digest':'e'*64}
            with self.assertRaisesRegex(target.base.Stopped,'behind/forks'):target.continuation_preflight(device,'fixed-sha',material)
            device.package='versionName=0.1.26\nversionCode=1026 minSdk=27'
            with self.assertRaisesRegex(target.base.Stopped,'fixed F030'):target.continuation_preflight(device,'fixed-sha',material)
        self.assertTrue(all(args[:2]==('dumpsys','package') for args in calls))

    def test_early_failure_is_reported_before_any_device_or_origin_action(self):
        with tempfile.TemporaryDirectory(prefix='thinkos-reg-rejections-',dir='/private/tmp') as temporary:
            root=Path(temporary);jar=root/'hierarchy.jar';jar.write_bytes(b'prebuilt')
            def forbidden(*args):raise AssertionError('Unexpected device/origin action')
            failure=ValueError('existing material changed')
            with self.assertRaises(ValueError) as raised:
                target.main(['--serial','HUA0BWUT','--material',str(root),'--apk',str(root/'fixed.apk'),
                    '--hierarchy-jar',str(jar),'--output',str(root/'report')],
                    material_open=lambda p:(_ for _ in ()).throw(failure),device_factory=forbidden,origin_factory=forbidden)
            self.assertIs(raised.exception,failure)
            self.assertIn('existing material changed',(root/'report/failure.txt').read_text())

    def test_CLI_cleanup_failure_after_success_cannot_return_zero_or_complete(self):
        calls=[]
        class Guard:
            def __init__(self,*args,**kwargs):pass
            def check(self):pass
            def policy(self):pass
        class Device:
            def __init__(self,*args):pass
            def original_hashes(self):calls.append('original-read');return ['unchanged']
        class Origin:
            def __init__(self,*args):pass
            def start(self):calls.append('origin-start');return self
            def close(self):calls.append('origin-close')
        class Watch:
            def __init__(self,*args):pass
            def close(self):calls.append('watch-close')
        class Driver:
            def __init__(self,d,g,h,m,o,output):self.output=output;self.result={'complete':False}
            def run(self):calls.append('real-actor-boundary')
            def return_original(self):calls.append('public-home-boundary')
            def audit_durable(self,label):calls.append('durable-audit')
            def check(self,name,value):self.assertion=value
            def save(self):(self.output/'result.json').write_text(json.dumps(self.result))
        with tempfile.TemporaryDirectory(prefix='thinkos-reg-rejections-',dir='/private/tmp') as temporary:
            root=Path(temporary);jar=root/'hierarchy.jar';jar.write_bytes(b'prebuilt')
            material=types.SimpleNamespace(meta={'registry_url':'http://192.168.1.2:8877/x','base_url':'http://192.168.1.2:8877/'})
            with patch.object(target.base,'validate_apk',return_value='fixed-sha'), \
                 patch.object(target.base,'apk_settings_defaults',return_value={}), \
                 patch.object(target,'continuation_preflight',return_value=('home',[],('owner',))), \
                 patch.object(target,'BudgetGuard',Guard),patch.object(target,'RejectionDriver',Driver), \
                 patch.object(target.base,'cleanup_hierarchy',side_effect=target.base.Stopped('Unknown owned layout')):
                with self.assertRaisesRegex(target.base.Stopped,'Owned cleanup failed'):
                    target.main(['--serial','HUA0BWUT','--material',str(root),'--apk',str(root/'fixed.apk'),
                        '--hierarchy-jar',str(jar),'--output',str(root/'report')],
                        material_open=lambda p:material,device_factory=Device,origin_factory=Origin,
                        watch_factory=Watch,hierarchy_factory=lambda *args:object())
            self.assertFalse(json.loads((root/'report/result.json').read_text())['complete'])
            self.assertTrue((root/'report/cleanup-failure.txt').is_file())
            self.assertEqual(calls,['original-read','origin-start','real-actor-boundary','public-home-boundary','durable-audit',
                                    'original-read','origin-close','watch-close'])

    def test_real_preflight_default_still_requires_zero_but_short_actor_explicitly_accepts_existing_900(self):
        class Device:
            value=900
            def shell(self,*args):
                if args[0]=='getprop':return {'ro.serialno':'HUA0BWUT','ro.build.version.sdk':'27','ro.product.cpu.abilist':'armeabi-v7a'}[args[1]]
                if args[:2]==('pm','path'):return 'package:/data/app/fixed/base.apk'
                if args[0]=='sha256sum':return 'a'*64+'  /data/app/fixed/base.apk'
                if args[:2]==('dumpsys','package'):return 'userId='+('10234 DEBUGGABLE' if args[2]==target.base.FIXTURE else '10001')
                if args==('dumpsys','device_policy'):return 'Device Owner:\n admin=ComponentInfo{'+target.base.ORIGINAL+'/.admin.ThinkOSAdminReceiver}\n'
                raise AssertionError('Unexpected command '+str(args))
            def read(self,*args):return None
            def json(self,*args):return {'installed':[]}
        device=Device()
        setting=lambda d,key,default,*package:device.value if key=='apps.return_after_s' else 600
        with patch.object(target.base,'effective',side_effect=setting), \
             patch.object(target.base,'focused_component',return_value=target.base.FIXTURE+'/moe.kiwi.thinkos.MainActivity'), \
             patch.object(target.base,'home_component',return_value=target.base.ORIGINAL+'/.MainActivity'), \
             patch.object(target.base,'home_candidates',return_value=[target.base.ORIGINAL+'/.MainActivity']):
            with self.assertRaisesRegex(target.base.Stopped,'return-after differs'):target.base.preflight(device,'a'*64,object())
            target.base.preflight(device,'a'*64,object(),expected_return_after_s=900)
            device.value=0
            target.base.preflight(device,'a'*64,object())
            with self.assertRaisesRegex(target.base.Stopped,'return-after differs'):target.base.preflight(device,'a'*64,object(),expected_return_after_s=900)

    def test_fixed_hash_boundary_covers_O_nine_and_both_histories_but_never_freezes_F_registry_or_floor(self):
        calls=[]
        class Guard:
            def check(self):pass
        class Device:
            def shell(self,*args):
                calls.append(args)
                if args[-2:]==('id','-u'):return '10234\n'
                paths=target.NINE+target.HISTORY+(target.base.STATE,)
                path=next(p for p in paths if 'sha256sum '+p+';' in args[-1])
                stamp='81a4|10234|1|8|55|100'
                return 'PRESENT\n'+stamp+'\n'+'a'*64+'  '+path+'\n'+stamp+'\n'
        snapshot=target.durable_hashes(Device(),Guard())
        for package in (target.base.ORIGINAL,target.base.FIXTURE):
            self.assertTrue(set(target.NINE+target.HISTORY)<=set(snapshot[package]))
            self.assertNotIn('files/registry/ledger.json',snapshot[package])
            self.assertNotIn('files/registry/cache.json',snapshot[package])
        self.assertIn(target.base.STATE,snapshot[target.base.ORIGINAL])
        self.assertNotIn(target.base.STATE,snapshot[target.base.FIXTURE])
        self.assertTrue(all(args[:1]==('run-as',) for args in calls))
        self.assertTrue(all('restore' not in args and 'input' not in args for args in calls))

    def test_hash_boundary_refuses_changed_metadata_or_oversize_and_absolute_budget_refuses_further_UI(self):
        class Guard:
            def check(self):pass
        class Device:
            def __init__(self,size,changed):self.size,self.changed=size,changed
            def shell(self,*args):
                if args[-2:]==('id','-u'):return '10234\n'
                path=target.NINE[0];stamp='81a4|10234|1|'+str(self.size)+'|55|100'
                return 'PRESENT\n'+stamp+'\n'+'a'*64+'  '+path+'\n'+(stamp+'0' if self.changed else stamp)+'\n'
        for device,reason in ((Device(8,True),'changed during'),(Device(target.MAX_DURABLE_FILE+1,False),'overlimit')):
            with self.assertRaisesRegex(target.base.Stopped,reason):target.durable_hashes(device,Guard())
        guard=object.__new__(target.BudgetGuard);guard.deadline=10
        with patch.object(target.time,'monotonic',return_value=10),patch.object(target.base.Guard,'check') as inherited:
            with self.assertRaisesRegex(target.base.Stopped,'budget expired'):guard.check()
            inherited.assert_not_called()
