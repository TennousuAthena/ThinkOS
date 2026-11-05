"""Fixed actor policy/observation regressions; no ADB, server or APK execution."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parent))
import registry_fixture_runtime_fault as target


def fixture():
    ident='reg-probe-'+('a'*12)
    meta={'id':ident,'digests':{'1.0.2':'b'*64,'1.0.3':'c'*64,'1.0.4':'d'*64,'1.0.5':'e'*64},
          'versions':{'1.0.2':{'permissions':['network','media','location']},'1.0.4':{'permissions':['network','media','location']}}}
    old={'id':ident,'version':'1.0.2','digest':'b'*64,'commit':'f'*40,'repo':'thinkos-tests/registry-probe',
         'tier':'reviewed','name':'仓库测试','confirmed':['network','media','location'],'auto':True,'enabled':True,'trial':False}
    state={'schema':1,'disabled':False,'installed':[old],
           'blocked':[{'id':ident,'digest':meta['digests'][v],'reason':'trial_interrupted'} for v in ('1.0.3','1.0.5')]}
    return meta,state


def admitted(meta,before):
    old=before['installed'][0]
    value={'id':meta['id'],'version':'1.0.4','digest':meta['digests']['1.0.4'],
           'commit':target.base.sha((meta['id']+'@1.0.4').encode())[:40],
           'repo':'thinkos-tests/registry-probe','tier':'reviewed','name':'仓库测试',
           'confirmed':['network','media','location'],'auto':True,'enabled':True,'trial':True,
           'previous':{k:copy.deepcopy(old[k]) for k in ('version','digest','commit','repo','tier','name','confirmed')}}
    result=copy.deepcopy(before);result['installed']=[value];return result


class RuntimeFaultBoundaryTest(unittest.TestCase):
    def test_initial_gate_requires_both_prior_blocked_and_never_rearms_the_fault_digest(self):
        meta,before=fixture();self.assertTrue(target.initial_state(before,meta))
        for mutate in (lambda s:s.update(blocked=s['blocked'][:1]),
                       lambda s:s['installed'][0].update(trial=True),
                       lambda s:s['installed'][0].update(enabled=False),
                       lambda s:s['blocked'].append({'id':meta['id'],'digest':'d'*64,'reason':'trial-failed'})):
            value=copy.deepcopy(before);mutate(value);self.assertFalse(target.initial_state(value,meta))
        self.assertEqual(len(before['blocked']),2)

    def test_candidate_requires_the_complete_durable_previous_and_exact_existing_permission_set(self):
        meta,before=fixture();value=admitted(meta,before)
        self.assertTrue(target.candidate_state(before,value,meta))
        for mutate in (lambda s:s['installed'][0]['previous'].update(digest='0'*64),
                       lambda s:s['installed'][0].update(confirmed=['network']),
                       lambda s:s['installed'][0].update(auto=False),lambda s:s.update(blocked=[]),
                       lambda s:s['installed'][0].update(trial=False)):
            changed=copy.deepcopy(value);mutate(changed);self.assertFalse(target.candidate_state(before,changed,meta))

    def test_rollback_adds_only_one_trial_failure_and_cannot_erase_old_blocked_or_permissions(self):
        meta,before=fixture();after=copy.deepcopy(before)
        after['blocked'].append({'id':meta['id'],'digest':'d'*64,'reason':'trial-failed'})
        self.assertTrue(target.rollback_state(before,after,meta))
        for mutate in (lambda s:s['blocked'].pop(0),lambda s:s['blocked'][-1].update(reason='trial_interrupted'),
                       lambda s:s['installed'][0].update(confirmed=[]),lambda s:s['installed'][0].update(auto=False),
                       lambda s:s.update(disabled=True)):
            changed=copy.deepcopy(after);mutate(changed);self.assertFalse(target.rollback_state(before,changed,meta))
        self.assertEqual(len(before['blocked']),2)

    def test_error_witnesses_exclude_old_other_plugin_and_startup_failures_and_keep_each_actual_line(self):
        meta,_=fixture();ident=meta['id']
        rows=['100.001 10 11 E ThinkOS: [plugin:'+ident+'] main.lua:31: Deliberate registry runtime failure',
              '101.001 10 11 E ThinkOS: [plugin:other] main.lua:31: Deliberate registry runtime failure',
              '101.002 10 11 E ThinkOS: [plugin:'+ident+'] Deliberate registry startup failure']
        for n in range(1,6):rows.append(str(102+n)+'.001 10 11 E ThinkOS: [plugin:'+ident+'] main.lua:31: Deliberate registry runtime failure')
        observed=target.fault_lines(rows,ident,101.0)
        self.assertEqual(len(observed),5);self.assertEqual(observed,rows[-5:])

    def test_restart_witness_changes_are_not_confused_with_a_continuous_nonzero_VM(self):
        meta,_=fixture();ident=meta['id']
        lines=['100.0 10 11 I ThinkOS: [plugin:'+ident+'] REGISTRY_STARTED 1.0.4',
               '100.1 10 11 I ThinkOS: [plugin:'+ident+'] loaded 1.0.4; Lua 9234 bytes; start cpu=1',
               '101.0 10 11 I ThinkOS: [plugin:'+ident+'] loaded 1.0.3; Lua 9234 bytes']
        first=target.startup_lines(lines,ident,'1.0.4',99)
        self.assertEqual(len(first),2)
        lines.extend(['103.0 10 11 I ThinkOS: [plugin:'+ident+'] REGISTRY_STARTED 1.0.4',
                      '103.1 10 11 I ThinkOS: [plugin:'+ident+'] loaded 1.0.4; Lua 9234 bytes'])
        self.assertNotEqual(target.startup_lines(lines,ident,'1.0.4',99),first)

    def test_unrecognized_fault_material_is_rejected_before_device_or_origin(self):
        calls=[]
        with tempfile.TemporaryDirectory(prefix='thinkos-reg-runtime-',dir='/private/tmp') as name:
            path=Path(name);jar=path/'fixed.jar';jar.write_bytes(b'reviewed shell helper')
            def forbidden(*args):calls.append(args);raise AssertionError('Device/origin reached')
            material=types.SimpleNamespace(meta={'id':'test','digests':{}})
            with self.assertRaisesRegex(target.base.Stopped,'material is incomplete'):
                target.main(['--serial','HUA0BWUT','--material',str(path),'--apk',str(path/'fixed.apk'),
                    '--hierarchy-jar',str(jar),'--output',str(path/'report')],material_open=lambda _:material,
                    device_factory=forbidden,origin_factory=forbidden)
            self.assertEqual(calls,[]);self.assertTrue((path/'report/failure.txt').is_file())
