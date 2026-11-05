#!/usr/bin/env python3
"""Fixed regFixture public-Hub rejection cases, then an intentionally durable revocation.

No APK installation, instrumentation, settings mutation, process stop, private
write/restore, health shortcut or floor/history clearing. Raw evidence stays TMP.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import time
import traceback
from urllib.parse import urlsplit
import uuid

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'),str(ROOT/'tools/tests')]
import registry_fixture_device as base
from registry_fixture_origin import Material, Origin, decoded, write_new
from release_tools import no_symlink_path

CASES = ('home','settings','system','tampered-blob')
VERSION = '1.0.2'
MAX_SECONDS = 540
NINE = ('files/settings/local.json','files/settings/local.json.bak','files/settings/server.json',
        'files/settings/server.json.bak','files/store/welcome.json','files/store/welcome.json.bak',
        'files/cast/identity-seed','files/cast/airplay-identity','files/management-exit')
HISTORY = ('files/native-failures.json','files/native-failures.json.bak',
           'shared_prefs/recovery.xml','shared_prefs/recovery.xml.bak',
           'shared_prefs/deck.xml','shared_prefs/deck.xml.bak')
MAX_DURABLE_FILE = 2*1024*1024
PENDING = ['Independent UID only; formal UID/DO/global gestures are not qualified.',
           'No 600-second trial, cold/unclean exit, autonomous recovery, factory reset, limits or performance is run.',
           'Signed revocation is intentionally last and remains in fixture state/cache/floor. No restoration is attempted.']


def healthy_state(state,meta):
    records = state.get('installed',[])
    return (state.get('disabled') is False and len(records)==1 and
            records[0].get('id')==meta['id'] and records[0].get('version')==VERSION and
            records[0].get('digest')==meta['digests'][VERSION] and
            records[0].get('enabled') is True and records[0].get('trial') is False and
            set(records[0].get('confirmed',[]))==set(meta['versions'][VERSION]['permissions']))


def revocation_state(before,after,ident):
    expected = copy.deepcopy(before)
    records = expected.get('installed',[])
    if len(records)!=1 or records[0].get('id')!=ident:
        return False
    records[0].update(enabled=False,trial=False,error='revoked')
    return expected==after


class BudgetGuard(base.Guard):
    def __init__(self,*args,deadline):
        super().__init__(*args)
        self.deadline=deadline

    def budget(self):
        base.require(time.monotonic()<self.deadline,'Absolute 540-second actor budget expired; scene retained')

    def check(self,*args,**kwargs):
        self.budget()
        value=super().check(*args,**kwargs)
        self.budget()
        return value

    def policy(self):
        self.budget(); super().policy(); self.budget()


def durable_hashes(device,guard):
    result={}
    for package in (base.ORIGINAL,base.FIXTURE):
        guard.check()
        uid=device.shell('run-as',package,'id','-u').strip()
        base.require(re.fullmatch(r'[0-9]{1,8}',uid),'Unknown durable file owner')
        files={}
        paths=NINE+HISTORY+((base.STATE,) if package==base.ORIGINAL else ())
        for path in paths:
            guard.check()
            q=shlex.quote(path)
            parents=Path(path).parents
            reject='; '.join('[ ! -L '+shlex.quote(str(parent))+' ] || exit 2' for parent in parents if str(parent)!='.')
            script=reject+'; if [ -L '+q+' ]; then exit 2; elif [ -f '+q+ \
                ' ]; then size=$(stat -c %s '+q+') || exit 4; '+ \
                'case "$size" in \'\'|*[!0-9]*) exit 4;; esac; '+ \
                '[ "$size" -le '+str(MAX_DURABLE_FILE)+" ] || exit 4; printf 'PRESENT\\n'; "+ \
                "stat -c '%f|%u|%h|%s|%i|%Y' "+q+'; sha256sum '+q+ \
                "; stat -c '%f|%u|%h|%s|%i|%Y' "+q+'; elif [ ! -e '+q+ \
                " ]; then printf 'ABSENT\\n'; else exit 3; fi"
            raw=device.shell('run-as',package,'sh','-c',script)
            guard.check()
            base.require(len(raw.encode())<=1024,'Durable hash observation exceeds bound')
            if raw=='ABSENT\n':
                files[path]=None; continue
            rows=raw.splitlines()
            base.require(len(rows)==4 and rows[0]=='PRESENT' and rows[1]==rows[3],'Durable file changed during observation')
            base.require(re.fullmatch(r'[a-fA-F0-9]+(?:\|[0-9]+){5}',rows[1]),'Unknown durable stat shape')
            mode,owner,links,size,_,_=rows[1].split('|')
            base.require(int(mode,16)&0o170000==0o100000 and owner==uid and links=='1' and
                         0<=int(size)<=MAX_DURABLE_FILE,'Unsafe/overlimit durable file retained')
            words=rows[2].split()
            base.require(len(words)==2 and words[1]==path and re.fullmatch(r'[a-f0-9]{64}',words[0]),'Unknown durable SHA observation')
            files[path]={'size':int(size),'sha256':words[0]}
        result[package]=files
    return result


def continuation_preflight(device,apk_sha,material):
    home,homes,owner = base.preflight(device,apk_sha,material,require_fresh=False,expected_return_after_s=900)
    package = device.shell('dumpsys','package',base.FIXTURE)
    base.require(re.search(r'\bversionName=0\.1\.30(?:\s|$)',package) and
                 re.search(r'\bversionCode=1030(?:\s|$)',package),'This actor targets the already installed fixed F030 APK')
    state = device.json(base.STATE) or {}
    base.require(healthy_state(state,material.meta),'Exact existing healthy v3 required; never create/clear its state')
    base.require(all(device.read(base.FIXTURE,path) is None for path in base.PENDING_PATHS),'Fixture APK/module transaction exists; preserve it')
    for key,wanted in {**base.INITIAL,'plugins.mirror':material.meta['registry_url']}.items():
        base.require(base.effective(device,key,None)==wanted,'Fixture preparation differs: '+key)
    media = device.state('media_state')
    base.require(not media.get('owner') and media.get('count')==0 and media.get('status')=='stopped','Stop fixture media through its public page first')
    ui = device.state()
    base.require(ui.get('locale','').startswith('zh') and not ui.get('onboardingOpen'),'Existing Chinese locale/closed guide required')
    ledger = device.json('files/registry/ledger.json') or {}
    raw = material.response(material.prefix+'registry/index.json')
    signature = material.response(material.prefix+'registry/index.json.sig')
    document = decoded(raw)
    base.require(material.verify(raw,signature,'registry'),'Current host publication must authenticate with these APK roots')
    base.require(type(ledger.get('seq')) is int and document['seq']>=ledger['seq'] and
                 (document['seq']>ledger['seq'] or base.sha(raw)==ledger.get('digest')),
                 'Host publication is behind/forks the actual fixture floor; do not rewind either')
    return home,homes,owner


class RejectionDriver(base.Driver):
    def __init__(self,*args):
        super().__init__(*args)
        self.result.update(scope='Fixed same-source independent-UID public Hub rejection/revocation only',
                           pending=PENDING,full_REG2b_complete=False,publications=[],cases=[],
                           fixture_revoked_at_end=False,settings_written=False,processes_stopped=False)

    def audit_durable(self,label):
        now=durable_hashes(self.d,self.guard)
        write_new(self.output/('durable-'+label+'.json'),json.dumps(now,sort_keys=True).encode())
        self.check('O nine/native/recovery/deck and F durable records unchanged '+label,now==self.protected_before)

    def publish(self,**arguments):
        self.guard.check(); self.guard.policy()
        before = self.d.json('files/registry/ledger.json') or {}
        base.require(self.d.pid()==self.pid,'Fixture PID changed before publication')
        pointer = self.origin.publish(**arguments)
        base.require(pointer['seq']>before.get('seq',-1),'New publication must exceed the actual fixture floor')
        self.result['publications'].append({'arguments':arguments,'seq':pointer['seq'],
            'catalog_sha256':pointer['catalog_sha256'],'signature_sha256':pointer['signature_sha256']})
        self.save()  # Preserve the allocated sequence even if UI/transport fails next.
        self.refresh(pointer['seq'])
        return pointer

    def preserve(self,before):
        self.guard.check(); self.guard.policy()
        return (self.d.pid()==self.pid and self.d.read(base.FIXTURE,base.STATE)==before and
                self.stable(VERSION,False) and self.active(VERSION))

    def evidence(self,name):
        self.guard.check()
        write_new(self.output/(name+'.png'),self.d.adb('exec-out','screencap','-p'))

    def hits(self,path):
        server = self.origin.server
        base.require(server is not None,'Owned fixture origin is not running')
        with server.hit_lock:
            return server.hits.get(path,0)

    def confirm_sample(self,value):
        base.require(self.wait(lambda:self.node(resource='android:id/button1')),'Native sample permission dialog missing')
        commit = base.sha((value['id']+'@'+value['version']).encode())[:40]
        wanted = {value['name'],'thinkos-tests/registry-probe@'+commit,'已审核'}
        wanted.update(self.meta['permission_reasons'][p] for p in value['permissions'])
        seen = set()
        for _ in range(5):
            text = '\n'.join(n.get('text','')+'\n'+n.get('content-desc','') for n in self.nodes())
            seen.update(item for item in wanted if item in text)
            if seen==wanted:
                break
            self.scroll()
        self.check('Exact signed source/name/tier/requested reasons before native touch '+value['id'],seen==wanted)
        self.tap_node(base.require(self.node(resource='android:id/button1'),'Native touchscreen Install missing'))
        base.require(self.wait(lambda:self.node(resource='android:id/button1') is None),'Native sample permission dialog did not close')

    def reject(self,sample,before):
        self.phase('reject-'+sample)
        value = self.meta['samples'][sample]
        pointer = self.publish(sample=sample)
        self.check('Publishing '+sample+' alone leaves existing healthy v3 intact',self.preserve(before))
        self.tap('发现'); self.tap(value['name'],scroll=True)
        commit = base.sha((value['id']+'@'+value['version']).encode())[:40]
        base.require(self.wait(lambda:self.node(label='thinkos-tests/registry-probe@'+commit)),'Exact sample detail source missing')
        path = self.material.prefix+'registry/files/'+value['digest']+'.json'
        requests = self.hits(path)
        bad_path = self.material.prefix+'blobs/'+self.meta['tampered_blob_sha256'][:2]+'/'+self.meta['tampered_blob_sha256']
        bad_requests = self.hits(bad_path)
        self.tap('安装',scroll=True,body=True)
        self.confirm_sample(value)
        self.check('Public operation reports failure '+sample,self.wait(lambda:self.node(label='操作失败'),seconds=60) is not None)
        self.check('Authenticated exact file index was requested '+sample,self.hits(path)>requests)
        if sample=='tampered-blob':
            self.check('Actual corrupt blob was requested',self.hits(bad_path)>bad_requests)
        self.check('Rejected '+sample+' leaves complete registry journal and healthy v3 VM unchanged',self.preserve(before))
        self.check('Rejected '+sample+' never activates a VM',value['id'] not in self.diagnostic())
        self.audit_durable('rejected-'+sample)
        self.evidence('rejected-'+sample)
        self.result['cases'].append({'case':sample,'seq':pointer['seq'],'id':value['id'],
            'digest':value['digest'],'native_touch_confirmed':True,'public_failure':True,
            'registry_state_byte_equal':True,'healthy_version':VERSION,'sample_VM_absent':True})
        self.save()
        # Ordinary Back leaves the selected detail; tapping a tab alone also
        # works, but this keeps the next refresh footer on the actual list.
        self.input('keyevent','KEYCODE_BACK')

    def revoke(self,before):
        self.phase('revoke-healthy-v3-last')
        base.require(self.preserve(before),'v3 changed before the intentionally final revocation')
        original = json.loads(before)
        pointer = self.publish(level=3,revoked=True)
        self.check('Signed revocation disables exact v3 and preserves all other journal/history fields',
            self.wait(lambda:revocation_state(original,self.d.json(base.STATE) or {},self.ident),seconds=60) is not None)
        self.check('Revoked v3 VM is removed',self.wait(lambda:self.ident not in self.diagnostic(),seconds=25) is not None)
        self.tap('已安装'); self.tap('仓库测试',scroll=True)
        text = set()
        for _ in range(8):
            text.update(n.get('text','') for n in self.nodes())
            text.update(n.get('content-desc','') for n in self.nodes())
            if '测试台撤销此版本' in text and '已停用' in text:
                break
            self.scroll()
        self.check('Public detail displays stopped status and authenticated revocation reason',
                   {'测试台撤销此版本','已停用'}<=text)
        self.evidence('revoked-v3')
        self.audit_durable('revoked-v3')
        self.result.update(fixture_revoked_at_end=True,revoked_version=VERSION,
            revoked_digest=self.meta['digests'][VERSION],revocation_seq=pointer['seq'],
            fixture_registry_state_sha256_after=base.sha(self.d.read(base.FIXTURE,base.STATE) or b''))
        self.save()

    def run(self):
        self.phase('existing-healthy-v3-baseline')
        self.protected_before=durable_hashes(self.d,self.guard)
        write_new(self.output/'durable-before.json',json.dumps(self.protected_before,sort_keys=True).encode())
        before = base.require(self.d.read(base.FIXTURE,base.STATE),'Existing fixture registry state missing')
        self.check('Initial v3 is exact healthy record and real active VM',healthy_state(json.loads(before),self.meta) and self.active(VERSION))
        write_new(self.output/'fixture-state-before.json',before)
        self.result['fixture_registry_state_sha256_before']=base.sha(before)
        for sample in CASES:
            self.reject(sample,before)
        self.revoke(before)


def main(argv=None,*,device_factory=base.Device,material_open=Material.open,origin_factory=Origin,
         watch_factory=base.TouchWatch,hierarchy_factory=base.Hierarchy):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('serial','material','apk','hierarchy-jar','output'):
        parser.add_argument('--'+name,required=True,type=str if name=='serial' else Path)
    args = parser.parse_args(argv)
    output = no_symlink_path(args.output)
    base.require(str(output).startswith('/private/tmp/') and not output.exists() and output.parent.is_dir(),'New private host TMP output required')
    no_symlink_path(args.hierarchy_jar)
    base.require(args.hierarchy_jar.is_file(),'Existing prebuilt hierarchy jar required')
    output.mkdir(mode=0o700)
    driver=origin=watch=guard=hierarchy=device=None
    reverse=False; main_succeeded=False
    started=time.monotonic()
    deadline=started+MAX_SECONDS
    old_handler=None; alarm_owned=False
    def expired(signum,frame):
        raise base.Stopped('Absolute 540-second actor budget expired; scene retained')
    try:
        old_handler=signal.getsignal(signal.SIGALRM)
        base.require(signal.getitimer(signal.ITIMER_REAL)==(0.0,0.0),'An existing host alarm is active; never replace it')
        signal.signal(signal.SIGALRM,expired)
        alarm_owned=True
        signal.setitimer(signal.ITIMER_REAL,MAX_SECONDS)
        material=material_open(args.material)
        apk_sha=base.validate_apk(args.apk,material)
        device=device_factory(args.serial)
        device.schema_defaults=base.apk_settings_defaults(args.apk,material.meta['registry_url'])
        home,homes,owner=continuation_preflight(device,apk_sha,material)
        watch=watch_factory(device); guard=BudgetGuard(device,watch,home,homes,owner,deadline=deadline)
        guard.check(); guard.policy()
        before=device.original_hashes()
        write_new(output/'original-hashes-before.json',json.dumps(before).encode())
        port=urlsplit(material.meta['base_url']).port
        if urlsplit(material.meta['base_url']).hostname=='127.0.0.1':
            base.require(not any('tcp:'+str(port) in row.split() for row in device.adb('reverse','--list').decode().splitlines()),'Reverse port already owned')
            guard.check(); device.adb('reverse','tcp:'+str(port),'tcp:'+str(port)); reverse=True
        origin=origin_factory(material).start()
        hierarchy=hierarchy_factory(device,guard,uuid.uuid4().hex,args.hierarchy_jar)
        driver=RejectionDriver(device,guard,hierarchy,material,origin,output)
        driver.result['apk_sha256']=apk_sha
        driver.run(); driver.return_original()
        driver.audit_durable('returned-original')
        guard.check(); guard.policy()
        after=device.original_hashes()
        write_new(output/'original-hashes-after.json',json.dumps(after).encode())
        driver.check('Original registry/update full trees unchanged; no restoration attempted',before==after)
        main_succeeded=True
    except BaseException as error:
        try: write_new(output/'failure.txt',traceback.format_exc().encode())
        except BaseException: pass
        if driver:
            try:
                driver.result.update(complete=False,error=str(error) if isinstance(error,base.Stopped) else type(error).__name__)
                driver.save()
            except BaseException: pass
        raise
    finally:
        # The alarm bounds the active actor, including blocking ADB/flock. Once
        # expired, budget guards refuse any further device touch/cleanup.
        if alarm_owned:
            signal.setitimer(signal.ITIMER_REAL,0)
            signal.signal(signal.SIGALRM,old_handler)
        cleanup_error=None
        try:
            if origin: origin.close()
            if guard:
                guard.check()
                if reverse: device.adb('reverse','--remove','tcp:'+str(port))
                if hierarchy: base.cleanup_hierarchy(device,guard,hierarchy)
        except BaseException as error:
            cleanup_error=error
            try: write_new(output/'cleanup-failure.txt',traceback.format_exc().encode())
            except BaseException: pass
        try:
            if watch: watch.close()
        except BaseException as error:
            cleanup_error=cleanup_error or error
        if driver:
            try:
                driver.result.update(complete=main_succeeded and cleanup_error is None,
                    active_elapsed_s=round(time.monotonic()-started,3),maximum_active_seconds=MAX_SECONDS,
                    original_return_after_s_preserved=900,
                    temporary_cleanup='owned helper/reverse removed' if cleanup_error is None else 'retained; exact cleanup failed')
                driver.save()
            except BaseException:
                if main_succeeded: raise
        if main_succeeded and cleanup_error is not None:
            raise base.Stopped('Owned cleanup failed; scene retained') from cleanup_error
    return 0


if __name__=='__main__':
    try: sys.exit(main())
    except (base.Stopped,OSError,ValueError,subprocess.SubprocessError):
        print('REG fixed rejection/revocation actor stopped; private TMP report retains details',file=sys.stderr)
        sys.exit(1)
