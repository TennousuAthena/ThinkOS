#!/usr/bin/env python3
"""One fixed F030 signed 1.0.4 Lua event-failure/rollback exercise.

No APK install, instrumentation, process kill, native/Java crash, settings write,
private write/restore, permission change or history/floor clearing. Reuses the
same-source independent fixture and public Hub/touch path. Import is inert.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import re
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
import registry_fixture_rejections as fixed
from registry_fixture_origin import Material, Origin, package, write_new
from release_tools import no_symlink_path

VERSION = '1.0.2'
CANDIDATE = '1.0.4'
MAX_SECONDS = 540
BudgetGuard = fixed.BudgetGuard
continuation_preflight = fixed.continuation_preflight
durable_hashes = fixed.durable_hashes
PENDING = ['Independent fixture UID only; no formal UID/DO/global-gesture qualification.',
           'No true native unclean exit, Java-crash safe-mode entry, startup-fault, autonomous restart, revocation, 16-instance or performance test.',
           'Runtime-failed 1.0.4 is intentionally added to blocked; no history/floor restoration is permitted.',
           'No internal VM pointer is exposed; VM continuity is evidenced by same PID, live diagnostic and unchanged single startup/load witnesses.']


def initial_state(state, meta):
    if not fixed.healthy_state(state, meta):
        return False
    blocked = state.get('blocked', [])
    if not isinstance(blocked, list) or len(blocked) >= 256:
        return False
    required = {(meta['id'], meta['digests'][v], 'trial_interrupted') for v in ('1.0.3', '1.0.5')}
    actual = {(row.get('id'), row.get('digest'), row.get('reason')) for row in blocked if isinstance(row, dict)}
    return required <= actual and not any(row.get('id') == meta['id'] and row.get('digest') == meta['digests'][CANDIDATE] for row in blocked)


def candidate_state(before, after, meta):
    """Exact durable admission, including the real healthy predecessor/grants."""
    old = before['installed'][0]
    previous = {key:copy.deepcopy(old[key]) for key in ('version','digest','commit','repo','tier','name','confirmed')}
    value = {'id':meta['id'], 'version':CANDIDATE, 'digest':meta['digests'][CANDIDATE],
             'commit':base.sha((meta['id']+'@'+CANDIDATE).encode())[:40],
             'repo':'thinkos-tests/registry-probe', 'tier':'reviewed', 'name':'仓库测试',
             'confirmed':list(meta['versions'][CANDIDATE]['permissions']),
             'auto':old['auto'], 'enabled':True, 'trial':True, 'previous':previous}
    expected = copy.deepcopy(before); expected['installed'] = [value]
    return after == expected


def rollback_state(before, after, meta):
    expected = copy.deepcopy(before)
    expected['blocked'].append({'id':meta['id'],'digest':meta['digests'][CANDIDATE],'reason':'trial-failed'})
    return after == expected


def fault_lines(lines, ident, after_epoch):
    pattern = re.compile(r'ThinkOS\s*:\s*\[plugin:'+re.escape(ident)+r'\]\s+.*Deliberate registry runtime failure\s*$')
    return [line for line in lines if len(line.encode()) <= 2048 and
            float(line.split()[0]) > after_epoch and pattern.search(line)]


def startup_lines(lines, ident, version, after_epoch):
    prefix = r'ThinkOS\s*:\s*\[plugin:'+re.escape(ident)+r'\]\s+'
    started = re.compile(prefix+r'REGISTRY_STARTED\s+'+re.escape(version)+r'\s*$')
    loaded = re.compile(prefix+r'loaded\s+'+re.escape(version)+r';\s+Lua\s+[1-9][0-9]*\s+bytes(?:;.*)?\s*$')
    return [line for line in lines if len(line.encode()) <= 2048 and
            float(line.split()[0]) > after_epoch and (started.search(line) or loaded.search(line))]


def verify_runtime_material(material):
    """Rebuild only the existing deterministic fixed sample; never sign here."""
    meta = material.meta
    for version in (VERSION, CANDIDATE, '1.0.3', '1.0.5'):
        base.require(version in meta['digests'], 'Existing same-root lifecycle material is incomplete')
    base.require(meta['versions'][CANDIDATE]['permissions'] == meta['versions'][VERSION]['permissions'] ==
                 ['network','media','location'], 'Runtime sample must not add permissions')
    index = meta['versions'][CANDIDATE]['index']
    license_rows = [row for row in index['files'] if row['path'] == 'LICENSE']
    base.require(len(license_rows) == 1, 'One existing fixed sample license required')
    row = license_rows[0]
    license_bytes = material.bodies[material.prefix+'blobs/'+row['sha256'][:2]+'/'+row['sha256']]
    expected = package(meta['id'],CANDIDATE,4,meta['media_url'],license_bytes)
    base.require(expected['digest'] == meta['digests'][CANDIDATE] and json.loads(expected['index']) == index,
                 'Only the exact existing signed public-tap runtime-fault sample is accepted')


class RuntimeFaultDriver(fixed.RejectionDriver):
    def __init__(self,*args):
        super().__init__(*args)
        self.result.update(scope='Fixed F030 public-tap Lua runtime failures 1-4 keep live VM; fifth rolls back',
            pending=PENDING,full_REG2b_complete=False,fixture_revoked_at_end=False,
            settings_written=False,processes_stopped=False,errors=[],startup_continuity=[])

    def same_pid(self):
        base.require(self.d.pid() == self.pid, 'Fixture PID changed; no restart or re-arming is allowed')

    def floor(self, pointer):
        self.same_pid()
        value = self.d.json('files/registry/ledger.json') or {}
        base.require(value == {'schema':1,'seq':pointer['seq'],'digest':pointer['catalog_sha256']},
                     'Actual registry floor differs from the exact new signed publication')

    def run(self):
        self.phase('existing-healthy-v3-before-runtime-fault')
        verify_runtime_material(self.material)
        self.protected_before = durable_hashes(self.d,self.guard)
        write_new(self.output/'durable-before.json',json.dumps(self.protected_before,sort_keys=True).encode())
        raw_before = base.require(self.d.read(base.FIXTURE,base.STATE),'Existing healthy fixture state required')
        before = json.loads(raw_before)
        self.check('Exact healthy v3 and both prior blocked digests are retained before new fault',
                   initial_state(before,self.meta) and self.active(VERSION))
        write_new(self.output/'fixture-state-before.json',raw_before)
        self.result['fixture_registry_state_sha256_before'] = base.sha(raw_before)
        logs = self.d.logs(self.pid)
        epoch = float(logs[-1].split()[0]) if logs else 0
        pointer = self.publish(level=5)
        self.floor(pointer)
        self.detail()
        self.tap('更新',scroll=True,body=True)
        self.check('Existing exact permissions need no new consent and actual v5 trial runs',
            self.wait(lambda:candidate_state(before,self.d.json(base.STATE) or {},self.meta) and self.active(CANDIDATE),seconds=60) is not None and
            self.node(resource='android:id/button1') is None)
        self.check('Real candidate startup is captured immediately, before any failure tap',
                   self.wait(lambda:self.capture_started(CANDIDATE),seconds=15) is not None)
        self.wait(lambda:len(startup_lines(self.d.logs(self.pid),self.ident,CANDIDATE,epoch))==2,seconds=15)
        starts = startup_lines(self.d.logs(self.pid),self.ident,CANDIDATE,epoch)
        self.check('One real candidate start and load witness exist',len(starts)==2 and
                   sum('REGISTRY_STARTED' in line for line in starts)==1 and sum('loaded '+CANDIDATE in line for line in starts)==1)
        self.result['startup_continuity'] = starts
        candidate_raw = base.require(self.d.read(base.FIXTURE,base.STATE),'Actual trial journal missing')
        self.open_hub(); self.tap('已安装'); self.tap('仓库测试',scroll=True); self.tap('打开',scroll=True)
        base.require(self.wait(lambda:self.node(label='仓库测试 '+CANDIDATE)), 'Actual runtime-fault app page is absent')
        error_epoch = max(float(line.split()[0]) for line in starts)
        self.check('No pre-existing candidate error is counted',not fault_lines(self.d.logs(self.pid),self.ident,error_epoch))
        for number in range(1,6):
            self.phase('real-Lua-event-error-'+str(number))
            self.same_pid(); self.floor(pointer)
            base.require(self.d.read(base.FIXTURE,base.STATE)==candidate_raw and self.active(CANDIDATE),
                         'Candidate changed before its next single fault tap')
            base.require(startup_lines(self.d.logs(self.pid),self.ident,CANDIDATE,epoch)==starts,
                         'Candidate restarted; preserve evidence without another fault tap')
            self.tap(self.meta['fault_label'])  # One actual public app tap, never callback/debug injection.
            rows = self.wait(lambda:(v if len(v:=fault_lines(self.d.logs(self.pid),self.ident,error_epoch))>=number else None),seconds=12)
            self.check('Exactly '+str(number)+' real Lua event failures were observed',rows is not None and len(rows)==number)
            self.result['errors'] = rows; self.save()
            self.same_pid(); self.floor(pointer)
            if number<5:
                self.check('Errors 1-'+str(number)+' keep the existing trial journal and live VM without restart',
                    self.d.read(base.FIXTURE,base.STATE)==candidate_raw and self.active(CANDIDATE) and
                    startup_lines(self.d.logs(self.pid),self.ident,CANDIDATE,epoch)==starts and
                    self.node(label='仓库测试 '+CANDIDATE) is not None)
                self.audit_durable('error-'+str(number))
            else:
                self.check('Fifth real event failure rolls back only candidate and adds exact trial-failed blocked entry',
                    self.wait(lambda:rollback_state(before,self.d.json(base.STATE) or {},self.meta) and self.active(VERSION),seconds=60) is not None)
                self.check('Restored healthy v3 has an actual new start after the fifth failure',
                    self.wait(lambda:self.capture_started(VERSION),seconds=15) is not None and
                    self.start_witnesses[(self.pid,VERSION,self.meta['digests'][VERSION])]['marker_epoch'] >= float(rows[-1].split()[0]))
        media = base.require(self.wait(self.paused_media,seconds=25),'Rollback v3 exact own paused video is missing')
        self.media_owner = media['owner']; self.stop_media()
        self.check('Only exact rollback-owned paused queue was cleared through the public app',
                   not self.d.state('media_state').get('owner') and self.d.state('media_state').get('count')==0)
        self.same_pid(); self.floor(pointer)
        self.check('Final healthy v3 record/permissions and every prior blocked item are exact',
                   rollback_state(before,self.d.json(base.STATE) or {},self.meta) and self.active(VERSION))
        self.audit_durable('runtime-rollback')
        self.evidence('runtime-rollback-v3')
        after = base.require(self.d.read(base.FIXTURE,base.STATE),'Final actual state missing')
        write_new(self.output/'fixture-state-after.json',after)
        self.result.update(real_event_errors=5,first_four_live_without_restart=True,fifth_rolled_back=True,
            fixture_registry_state_sha256_after=base.sha(after),real_600s_health=False,native_unclean_verified=False,
            safe_mode_verified=False,runtime_failed_digest=self.meta['digests'][CANDIDATE])
        self.save()

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
        verify_runtime_material(material)
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
        driver=RuntimeFaultDriver(device,guard,hierarchy,material,origin,output)
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
        print('REG fixed runtime-fault actor stopped; private TMP report retains details',file=sys.stderr)
        sys.exit(1)
