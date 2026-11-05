#!/usr/bin/env python3
"""Run the production fixed shell transaction against fake Android commands."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
SCRIPT=ROOT/'thinkos-shell/shell.sh'
FAKE='''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
p=Path(os.environ['THINKOS_SHELL_FAKE']);state=json.loads(p.read_text());command=Path(sys.argv[0]).name;args=sys.argv[1:]
event=command+':'+args[0] if args else command
if state.get('fail')==event:
    state.pop('fail');p.write_text(json.dumps(state));sys.exit(1)
if command=='settings':
    assert args[1:3]==['global','policy_control']
    if args[0]=='get':print(state['policy'] if state['policy'] is not None else 'null')
    elif args[0]=='put':state['policy']=args[3]
    elif args[0]=='delete':state['policy']=None
    else:raise AssertionError(args)
elif command=='appops':
    assert args[1:3]==['moe.kiwi.thinkos','SYSTEM_ALERT_WINDOW']
    if args[0]=='get':print('unexpected mode' if state.get('bad_mode') else 'No operations.' if state['mode']=='default' else 'SYSTEM_ALERT_WINDOW: '+state['mode']+'; time=+5s')
    elif args[0]=='set':state['mode']=args[3]
    else:raise AssertionError(args)
elif command=='getprop':
    assert args==['sys.thinkos.shell'];print(state['property'])
elif command=='setprop':
    assert args[0]=='sys.thinkos.shell';state['property']=args[1]
elif command=='am':
    assert args==['broadcast','--receiver-foreground','-a','moe.kiwi.thinkos.SHELL_CHANGED','-n','moe.kiwi.thinkos/.shell.ShellChangedReceiver']
    with open(os.environ['THINKOS_SHELL_NOTIFICATIONS'],'a') as output:output.write(json.dumps(state)+'\\n')
else:raise AssertionError(command)
p.write_text(json.dumps(state))
'''


class ShellTransactionTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='thinkos shell host ')
        self.work=Path(self.temp.name);self.state=self.work/'android.json';self.journal=self.work/'private state'
        self.notifications=self.work/'notifications.jsonl'
        self.env=dict(os.environ,THINKOS_SHELL_FAKE=str(self.state),THINKOS_SHELL_NOTIFICATIONS=str(self.notifications),PATH=str(self.work)+os.pathsep+os.environ['PATH'])
        for name in ('settings','appops','getprop','setprop','am'):
            path=self.work/name;path.write_text(FAKE);path.chmod(0o755)
        self.write(policy=None,mode='default',property='')
    def tearDown(self):self.temp.cleanup()
    def write(self,**values):self.state.write_text(json.dumps(values))
    def read(self):return json.loads(self.state.read_text())
    def run_script(self,action,success=True):
        result=subprocess.run(['sh',str(SCRIPT),action,str(self.journal)],env=self.env,capture_output=True,text=True,timeout=20)
        self.assertEqual(result.returncode==0,success,result.stdout+result.stderr)
        return result
    def test_absent_policy_and_default_permission_restore(self):
        before=self.read();self.run_script('apply')
        self.assertEqual(self.read(),dict(policy='immersive.full=*',mode='allow',property='2'))
        self.assertEqual(self.journal.stat().st_mode&0o777,0o700)
        self.run_script('restore');self.assertEqual(self.read(),before);self.assertFalse(self.journal.exists())
    def test_hides_both_bars_and_preserves_other_policy_with_literal_wildcard(self):
        original='immersive.navigation=one.app:immersive.status=old.app:immersive.preconfirms=*'
        self.write(policy=original,mode='ignore',property='0');before=self.read();self.run_script('apply')
        self.assertEqual(self.read()['policy'],'immersive.preconfirms=*:immersive.full=*')
        self.run_script('restore');self.assertEqual(self.read(),before)
    def test_repeated_apply_and_reboot_keep_the_original_profile(self):
        before=self.read();self.run_script('apply');self.run_script('apply')
        value=self.read();value['property']='';self.write(**value);self.run_script('apply')
        self.run_script('restore');self.assertEqual(self.read(),before)
    def test_uninstall_preserves_later_manual_policy_and_permission(self):
        self.run_script('apply');value=self.read();value.update(policy='immersive.status=manual.app',mode='ignore');self.write(**value)
        self.run_script('restore');self.assertEqual(self.read(),dict(policy='immersive.status=manual.app',mode='ignore',property=''))
    def test_reapply_preserves_changes_made_between_boots(self):
        self.run_script('apply');value=self.read();value.update(policy='immersive.navigation=manual.app',mode='deny',property='');self.write(**value)
        self.run_script('apply');self.run_script('restore')
        self.assertEqual(self.read(),dict(policy='immersive.navigation=manual.app',mode='deny',property=''))
    def test_each_failed_mutation_rolls_back(self):
        for event in ('settings:put','appops:set','setprop:sys.thinkos.shell'):
            with self.subTest(event=event):
                self.write(policy='immersive.navigation=manual.app',mode='ignore',property='',fail=event)
                self.run_script('apply',False)
                self.assertEqual(self.read(),dict(policy='immersive.navigation=manual.app',mode='ignore',property=''))
                self.assertFalse(self.journal.exists())
    def test_unknown_appop_and_oversized_policy_do_not_mutate(self):
        for value in (dict(policy=None,mode='default',property='',bad_mode=True),dict(policy='x'*4097,mode='default',property='')):
            with self.subTest(value=list(value)):
                self.write(**value);self.run_script('apply',False);self.assertEqual(self.read(),value)
    def test_restore_read_failures_retain_the_journal_for_a_verified_retry(self):
        for event in ('settings:get','appops:get','getprop:sys.thinkos.shell'):
            with self.subTest(event=event):
                self.write(policy=None,mode='default',property='');before=self.read();self.run_script('apply')
                value=self.read();value['fail']=event;self.write(**value)
                self.run_script('restore',False);self.assertTrue((self.journal/'complete').exists())
                self.run_script('restore');self.assertEqual(self.read(),before);self.assertFalse(self.journal.exists())
    def test_bad_action_and_relative_journal_are_rejected(self):
        self.run_script('anything',False)
        result=subprocess.run(['sh',str(SCRIPT),'apply','relative'],env=self.env,capture_output=True,timeout=10)
        self.assertNotEqual(result.returncode,0)
    def test_fixed_notification_follows_the_complete_apply_and_restore(self):
        before=self.read();self.run_script('apply');self.run_script('restore')
        self.assertEqual([json.loads(line) for line in self.notifications.read_text().splitlines()],
                         [dict(policy='immersive.full=*',mode='allow',property='2'),before])
    def test_an_absent_or_old_receiver_does_not_roll_back_ready_policy(self):
        self.write(policy=None,mode='default',property='',fail='am:broadcast')
        self.run_script('apply');self.assertEqual(self.read(),dict(policy='immersive.full=*',mode='allow',property='2'))
        self.run_script('restore');self.assertEqual(self.read(),dict(policy=None,mode='default',property=''))


if __name__=='__main__':unittest.main()
