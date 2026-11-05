"""MIT. Explicit spare-only public settings/native CHECK/DOWNLOAD to real READY.

Never installs, instruments, grants, reboots, clears or restores private data.
Release private observations use host root, not an application su dependency.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import traceback
import uuid
import xml.etree.ElementTree as ET

import ota5_device as plan
from device_hierarchy import node_bounds, visible_nodes
from registry_device import TouchWatch, Hierarchy, focused_component, quiet
from registry_fixture_device import cleanup_hierarchy
from release_tools import json_bytes, no_symlink_path

APP=plan.APP
PENDING=('files/update/pending.json','files/update/pending.json.bak',
         'files/update/module-pending.json','files/update/module-pending.json.bak')
HISTORY=('files/update/install-history.json','files/update/install-history.json.bak',
         'files/update/install-failure.json','files/update/install-failure.json.bak',
         'files/native-failures.json','files/native-failures.json.bak',
         'shared_prefs/recovery.xml','shared_prefs/recovery.xml.bak',
         'shared_prefs/deck.xml','shared_prefs/deck.xml.bak','files/plugins/registry-state.json',
         'files/store/welcome.json','files/store/welcome.json.bak','files/cast/identity-seed',
         'files/cast/airplay-identity','files/management-exit')
SETTINGS=('files/settings/local.json','files/settings/server.json')
REMAINING=['Native INSTALL/PackageInstaller and post-install real 600-second health are not executed.',
           'Automatic scheduling, all refusal samples and owner-debug commit/crash/rollback remain pending.',
           'This is one APK-only manual source path, not full OTA-5 acceptance or a system reboot qualification.']


def source_presence(local,server,defaults):
    """Public set removes values equal to the validated lower layer (Store:210).
    Legacy redundant local values cannot be reconstructed by that API, so stop
    before the first edit rather than silently changing inheritance semantics.
    """
    result={}
    for key in ('update.mirror','update.channel'):
        for layer in (local,server,defaults):
            if key not in layer:continue
            value=layer[key]
            valid=isinstance(value,str) and (value in ('stable','beta') if key=='update.channel' else
                len(value)<=1024 and '%s' not in value and all(32<=ord(c)<127 for c in value))
            require(valid,'Existing source layer cannot be safely restored through the public editor: '+key)
        below=server.get(key,defaults[key])
        present=key in local
        require(not present or local[key]!=below,'Legacy redundant local source cannot preserve its presence through public set: '+key)
        result[key]={'present':present,**({'value':local[key]} if present else {})}
    return result


def blank_corner(root):
    """Select from complete native windows; do not confuse attention-role changes
    with owner input. An approval/editor/update window never receives this tap.
    """
    windows=[dict(n.attrib) for n in root.findall('node')]
    app=[n for n in windows if n.get('package')==APP and len(node_bounds(n))==4 and
         node_bounds(n)[2]>node_bounds(n)[0] and node_bounds(n)[3]>node_bounds(n)[1]]
    require(len(app)==1,'Safe wake requires exactly one ordinary Main application window')
    viewport=node_bounds(app[0]);a,b,c,d=viewport
    require(c-a>=160 and d-b>=160,'Owned Main viewport is too small for a proven blank wake point')
    nodes=[dict(n.attrib) for n in root.iter('node')]
    forbidden={'立即安装','安装并重启','准备安装','检查更新','下载更新','安装插件','允许','拒绝','确定','取消'}
    for node in nodes:
        require(node.get('resource-id') not in ('android:id/button1','android:id/button2','android:id/button3') and
                node.get('class') not in ('android.widget.EditText','android.app.Dialog','android.app.AlertDialog') and
                node.get('text') not in forbidden and node.get('content-desc') not in forbidden,
                'Safe wake refuses an existing confirmation/editor/update window')
    def inside(point,node):
        bounds=node_bounds(node)
        return len(bounds)==4 and bounds[0]<=point[0]<bounds[2] and bounds[1]<=point[1]<bounds[3]
    blockers=[n for n in nodes if n.get('enabled','true')=='true' and
              any(n.get(key)=='true' for key in ('clickable','long-clickable','checkable','password'))]
    foreign=[n for n in windows if n.get('package')!=APP]
    for point in ((c-2,b+2),(a+2,b+2),(c-2,d-2),(a+2,d-2)):
        if not any(inside(point,n) for n in blockers+foreign):return point,viewport
    raise RuntimeError('No noninteractive Main corner is proven; do not send a wake touch')


def require(value,message):
    if not value: raise RuntimeError(message)
    return value


def ui_plan(prepared,args):
    jar=no_symlink_path(args.hierarchy_jar)
    with __import__('tempfile').TemporaryDirectory(prefix='thinkos-ota5-ui-review-') as temporary:
        copy=plan._snapshot(jar,Path(temporary)/'hierarchy.jar',16*1024*1024)
        jar_sha=hashlib.sha256(copy.read_bytes()).hexdigest()
    install=args.execute_install or args.plan_install
    require(not(args.execute_ui and install),'READY-only execution cannot consume an install plan')
    value={'schema':1,'scope':'authorized_spare_public_UI_normal_install_and_health' if install else 'authorized_spare_public_UI_to_READY_only',
           'input_sha256':prepared.report()['input_sha256'],'serial':prepared.serial,
           'physical_serial':prepared.physical,'hierarchy_sha256':jar_sha,'install':install}
    value['baseline_settings_schema_present']=prepared.setting_defaults is not None
    value['target_settings_schema_present']=prepared.target_setting_defaults is not None
    value['ui_input_sha256']=hashlib.sha256(json_bytes(value)).hexdigest()
    return value


class Device(plan.ReadOnlyDevice):
    def __init__(self,serial,**kwargs):
        super().__init__(serial,**kwargs);self.base=['adb','-s',serial]
        self.directory=None

    def adb(self,*arguments,timeout=35,check=True):
        require(arguments and arguments[0] in ('shell','push','exec-out','logcat'),'ADB operation outside public-UI driver')
        result=self.run(self.base+list(map(str,arguments)),capture_output=True,timeout=timeout,
                        env={**os.environ,'ADB_MDNS':'0'})
        require(not check or result.returncode==0,'ADB operation failed; no automatic replay')
        require(len(result.stdout)<=3*1024*1024 and len(result.stderr)<=65536,'ADB observation exceeds bound')
        return result.stdout

    def shell(self,*arguments,check=True):
        require(arguments and arguments[0] in ('input','su','getevent','dumpsys','wm','pidof','cmd','getprop',
                'sh','mkdir','cat','rm','find','rmdir'),'Shell operation outside public-UI driver')
        return self.adb('shell','-T',shlex.join(list(map(str,arguments))),check=check).decode('utf-8')

    def pid(self,optional=False):
        value=self.shell('pidof',APP,check=False).strip()
        if optional and not value:return None
        require(re.fullmatch('[1-9][0-9]*',value),'Exactly one live production process required')
        return int(value)

    def private(self,path,limit=2*1024*1024,hash_only=False):
        channel=getattr(self,'channel',None)
        names=set(PENDING+HISTORY+SETTINGS+('files/update/ledger/sequences.json',))
        if channel in ('stable','beta'):
            names|={'files/update/state/state-'+channel+'.json','files/update/metadata/catalog-'+channel+'.json'}
        code=getattr(self,'target_code',None)
        if type(code) is int:
            names|={'files/update/failure-'+str(code)+'.json','files/update/failure-'+str(code)+'.json.bak'}
        names|={'files/update/previous.apk','files/store/welcome.json','files/store/welcome.json.bak',
                'files/cast/identity-seed','files/cast/airplay-identity','files/management-exit'}
        if hash_only and path=='files/update/downloads/'+getattr(self,'target_sha','')+'.apk':
            names.add(path)
        require(path in names and self.directory in ('/data/user/0/'+APP,'/data/data/'+APP),'Private read outside fixed target paths')
        full=self.directory+'/'+path;q=shlex.quote(full)
        parents=[str(parent) for parent in Path(full).parents if str(parent).startswith(self.directory)]
        prefix='; '.join('[ ! -L '+shlex.quote(parent)+' ] || exit 2' for parent in parents)
        command=prefix+'; if [ -L '+q+' ]; then exit 2; elif [ ! -e '+q+" ]; then printf 'ABSENT\\n'; "+ \
            'elif [ -f '+q+' ]; then size=$(stat -c %s '+q+') || exit 2; '+ \
            'case "$size" in \'\'|*[!0-9]*) exit 2;; esac; [ "$size" -le '+str(limit)+ \
            " ] || exit 2; printf 'PRESENT\\n'; before=$(stat -c '%i|%s|%Y' "+q+'); '+ \
            ('sha256sum '+q if hash_only else 'base64 '+q)+ \
            '; after=$(stat -c \'%i|%s|%Y\' '+q+'); [ "$before" = "$after" ] || exit 2; else exit 2; fi'
        raw=self.shell('su','-c',command)
        if raw=='ABSENT\n':return None
        require(raw.startswith('PRESENT\n'),'Private observation failed')
        if hash_only:
            words=raw[8:].split()
            require(len(words)==2 and words[1]==full and re.fullmatch('[a-f0-9]{64}',words[0]),'Unknown file digest observation')
            return words[0]
        encoded=''.join(raw[8:].splitlines());value=base64.b64decode(encoded,validate=True)
        require(len(value)<=limit,'Private observation exceeds bound')
        return value

    def document(self,path):
        raw=self.private(path)
        if raw is None:return None
        return plan.settings_schema(raw, limit=2*1024*1024) if path in SETTINGS else plan.release.parse_document(raw)

    def policy_snapshot(self):
        self.owner_and_home()
        policy=self._read('dumpsys','device_policy')
        admins=sorted(set(re.findall(r'admin=ComponentInfo\{('+re.escape(APP)+r'/[^}]+)\}',policy)))
        require(1<=len(admins)<=4,'Known bounded production admin inventory required')
        activities=self._read('dumpsys','activity','activities')
        lock=re.findall(r'\bmLockTaskModeState=([A-Z]+)',activities)
        require(lock==['LOCKED'],'Existing locked kiosk is required; never change it here')
        package=self._read('dumpsys','package',APP)
        permissions={}
        for name in ('CAMERA','RECORD_AUDIO','READ_EXTERNAL_STORAGE','WRITE_EXTERNAL_STORAGE'):
            rows=re.findall(r'^\s*android\.permission\.'+name+r': granted=(true|false)',package,re.M)
            require(len(rows)<=1,'Ambiguous permission observation')
            permissions[name]=rows==['true']
        return {'admins':admins,'lock_task':lock[0],'runtime_permission_grants':permissions}


class Guard:
    def __init__(self,device,watch,prepared,deadline):
        self.device,self.watch,self.prepared,self.deadline=device,watch,prepared,deadline
        self.pid=device.pid()
        self.install_until=0

    def check(self):
        require(time.monotonic()<self.deadline,'Public-UI observation budget expired')
        self.watch.check()
        physical=self.device.property('ro.serialno')
        plan._spare(self.prepared.serial,physical)
        require(physical==self.prepared.physical,'Physical spare changed; stop without input')
        component=focused_component(self.device)
        known=component is not None and component.split('/',1)[0]==APP
        require(known or component is None and time.monotonic()<self.install_until,'Foreign/empty foreground: '+str(component))
        pid=self.device.pid(optional=time.monotonic()<self.install_until)
        require((pid==self.pid or time.monotonic()<self.install_until) and quiet(self.device),'Production process changed or audio is not zero')
        self.watch.check()

    def touch(self):
        self.check()
        require(focused_component(self.device) is not None and self.device.pid()==self.pid,'Actual owned old/new foreground required for touch')


class UI:
    def __init__(self,device,guard,hierarchy,prepared,output):
        self.d,self.guard,self.h,self.p,self.output=device,guard,hierarchy,prepared,output
        self.result={'schema':1,'complete':False,'scope':'actual_public_settings_native_check_download_to_READY_only',
                     'installed':False,'device_changed':False,'checks':[],'pending':REMAINING,
                     'raw_restore':False,'private_files_written':False,'native_ready_verified':False,
                     'source_restore_completed':False,'baseline':dict(prepared.baseline),'target':dict(prepared.target),
                     'roots_observation_sha256':prepared.observation_sha256}

    def save(self):
        (self.output/'result.json').write_bytes(json_bytes(self.result))

    def check(self,name,value):
        self.result['checks'].append({'name':name,'passed':bool(value)});self.save();require(value,name)

    def nodes(self):
        self.guard.check()
        return [dict(n.attrib) for n in visible_nodes(self.h.tree()) if n.get('package') in (APP,'android')]

    def node(self,label=None,klass=None):
        values=[n for n in self.nodes() if n.get('enabled','true')=='true' and
                (label is not None and label in (n.get('text'),n.get('content-desc')) or klass is not None and n.get('class')==klass)]
        actionable=[n for n in values if n.get('clickable')=='true']
        if actionable:values=actionable
        boxes={tuple(node_bounds(n)):n for n in values}
        return next(iter(boxes.values())) if len(boxes)==1 else None

    def wait(self,predicate,seconds=30):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            self.guard.check();value=predicate()
            if value:return value
            time.sleep(.3)
        raise RuntimeError('Bounded public UI/state observation timed out')

    def input(self,*args):
        self.guard.touch();self.d.shell('input',*args);self.guard.check()

    def safe_wake(self,phase):
        self.guard.check()
        component=focused_component(self.d)
        require(component in (APP+'/.MainActivity',APP+'/'+APP+'.MainActivity'),
                'Wake preparation requires the existing normal Main; no takeover')
        root=self.h.tree(all_windows=True)
        point,viewport=blank_corner(root)
        self.input('touchscreen','tap',*point)  # Exactly once, never a repeated critical action.
        after=self.nodes()  # Read the actual post-wake role/page before navigation.
        self.result.setdefault('safe_wake',[]).append({'phase':phase,'point':point,'viewport':viewport,
            'after_nodes':len(after),'actual_role_or_page_assumed':False})
        self.save()

    def tap_node(self,node):
        bounds=node_bounds(require(node,'Unique visible public control required'))
        require(len(bounds)==4 and 0<=bounds[0]<bounds[2] and 0<=bounds[1]<bounds[3],'Invalid visible UI bounds')
        self.input('touchscreen','tap',(bounds[0]+bounds[2])//2,(bounds[1]+bounds[3])//2)

    def scroll(self,up=True):
        boxes=[node_bounds(n) for n in self.nodes() if n.get('scrollable')=='true']
        boxes=sorted(set(tuple(b) for b in boxes),key=lambda b:(b[2]-b[0])*(b[3]-b[1]),reverse=True)
        require(boxes and (len(boxes)==1 or (boxes[0][2]-boxes[0][0])*(boxes[0][3]-boxes[0][1])>
                (boxes[1][2]-boxes[1][0])*(boxes[1][3]-boxes[1][1])),'Unique main settings scroller required')
        a,b,c,d=boxes[0];x=(a+c)//2
        self.input('touchscreen','swipe',x,b+int((d-b)*(.8 if up else .2)),x,b+int((d-b)*(.2 if up else .8)),350)

    def tap(self,label,scroll=False):
        node=self.node(label=label)
        for _ in range(8 if scroll else 0):
            if node:break
            self.scroll();node=self.node(label=label)
        self.tap_node(node)

    def effective(self,key):
        for path in SETTINGS:
            layer=self.d.document(path) or {}
            if key in layer.get('system',{}):return layer['system'][key]
        return self.p.setting_defaults[key]

    def public_advanced(self):
        # A caller enters the existing public system settings page. This method
        # navigates only labels emitted by its real form, never a debug page.
        if self.node(label='更新镜像'):return
        if any(n.get('scrollable')=='true' for n in self.nodes()):
            for _ in range(4):self.scroll(up=False)
        if self.node(label='更新镜像'):return
        if self.node(label='系统'):self.tap('系统')
        self.tap('高级',scroll=True)
        self.wait(lambda:self.node(label='更新镜像'))

    def open_settings(self):
        if self.node(label='更新镜像') or self.node(label='系统') or self.node(label='高级'):return
        direct=self.node(label='系统设置')
        if direct:self.tap_node(direct)
        else:
            bounds=[node_bounds(n) for n in self.nodes() if n.get('package')==APP]
            require(bounds,'Owned application viewport absent')
            a,b,c,d=max(bounds,key=lambda v:(v[2]-v[0])*(v[3]-v[1]))
            self.input('touchscreen','swipe',(a+c)//2,b+1,(a+c)//2,b+int((d-b)*.6),450)
            self.tap('设置')
        self.wait(lambda:self.node(label='系统') or self.node(label='高级'))

    def update_window(self):
        # The factory settings plugin may replace this row's title with the
        # production notice. Match only the exact notice derived from its real
        # install-history result, never a private callback or invented form key.
        result=(self.d.document('files/update/install-history.json') or {}).get('result',{})
        label={'updated':'已更新到 '+str(result.get('version','')),'rolled_back':'更新已回退',
               'rollback_failed':'回退失败','install_failed':'安装失败'}.get(result.get('kind'))
        for index in range(9):
            value=self.node(label='软件更新') or (self.node(label=label) if label else None)
            if value:self.tap_node(value);return
            if index<8:self.scroll()
        raise RuntimeError('Public software-update row is not uniquely identifiable')

    @staticmethod
    def preferences(raw):
        if raw is None:return {}
        require(b'<!DOCTYPE' not in raw and b'<!ENTITY' not in raw,'Untrusted preference XML declaration')
        root=ET.fromstring(raw);require(root.tag=='map','Unexpected preference XML')
        result={}
        for node in root:
            key=node.get('name');require(key is not None and key not in result,'Unknown/duplicate preference key')
            if node.tag=='string':value=node.text or ''
            elif node.tag=='boolean':require(node.get('value') in ('true','false'),'Unknown preference boolean');value=node.get('value')=='true'
            elif node.tag in ('int','long','float'):value=node.get('value')
            else:raise RuntimeError('Unknown preference value type')
            result[key]=value
        return result

    def set_mirror(self,value):
        require(isinstance(value,str) and len(value)<=1024 and '%s' not in value and all(32<=ord(c)<127 for c in value),'Bounded native-input-safe ASCII mirror required')
        self.tap('更新镜像',scroll=True)
        editor=self.wait(lambda:self.node(klass='android.widget.EditText'))
        old=editor.get('text','');require(len(old)<=1024,'Existing editor content exceeds schema bound')
        self.tap_node(editor)
        if old:self.input('keyevent','KEYCODE_MOVE_END',*(['KEYCODE_DEL']*len(old)))
        self.check('Real native editor was cleared',self.wait(lambda:self.node(klass='android.widget.EditText')).get('text','')=='')
        if value:self.input('text',value)
        self.check('Real native editor contains exact reviewed mirror',self.wait(lambda:self.node(klass='android.widget.EditText')).get('text','')==value)
        self.tap_node(self.wait(lambda:next((n for n in self.nodes() if n.get('resource-id')=='android:id/button1'),None)))
        self.wait(lambda:self.effective('update.mirror')==value,seconds=7)
        self.result['device_changed']=True;self.save()

    def set_channel(self,value):
        require(value in ('stable','beta'),'Unknown catalog channel')
        if self.effective('update.channel')==value:return
        self.tap('更新通道',scroll=True);self.tap('稳定版' if value=='stable' else '测试版')
        self.wait(lambda:self.effective('update.channel')==value,seconds=7)
        self.result['device_changed']=True;self.save()

    def history(self):
        paths=HISTORY+PENDING+('files/update/failure-'+str(self.p.target['code'])+'.json',
                            'files/update/failure-'+str(self.p.target['code'])+'.json.bak')
        return {path:None if (raw:=self.d.private(path)) is None else hashlib.sha256(raw).hexdigest() for path in paths}

    def ready(self):
        value=self.d.document('files/update/state/state-'+self.d.channel+'.json') or {}
        target=self.p.target
        return (value.get('phase')=='READY' and value.get('seq')==target['seq'] and
                value.get('catalog_digest')==target['catalog_sha256'] and
                value.get('size')==target['size'] and value.get('downloaded')==target['size'])

    def ready_proof(self):
        state='files/update/state/state-'+self.d.channel+'.json'
        metadata='files/update/metadata/catalog-'+self.d.channel+'.json'
        first={path:self.d.private(path) for path in (state,metadata,'files/update/ledger/sequences.json')}
        require(self.ready(),'Exact production READY is required')
        cached=plan.release.parse_document(require(first[metadata],'Authenticated metadata absent'))
        floor=plan.release.parse_document(require(first['files/update/ledger/sequences.json'],'Actual floor absent')).get(self.d.channel,{})
        signature=self.p.origin._files[self.d.channel+'.json.sig']
        self.check('Raw native cache signature/body and source equal authenticated reviewed input',
            base64.b64decode(cached.get('body',''),validate=True)==self.p.catalog_bytes and
            base64.b64decode(cached.get('signature',''),validate=True)==signature and cached.get('source')==self.p.mirror)
        self.check('Actual floor is exact authenticated seq and digest',floor=={'seq':self.p.target['seq'],'digest':self.p.target['catalog_sha256']})
        self.check('Actual downloaded APK bytes match the signed target',self.d.private('files/update/downloads/'+self.p.target['sha256']+'.apk',64*1024*1024,True)==self.p.target['sha256'])
        self.check('Native READY/cache/floor remain stable through complete verification',first=={path:self.d.private(path) for path in first})

    @staticmethod
    def identity(value):
        return {key:value[key] for key in ('version','code','sha256','size','signer')}

    def install_healthy(self):
        require(self.install,'Only explicitly reviewed install mode may reach native INSTALL')
        self.ready_proof()
        require(self.effective('update.auto_install') is False,'Auto-install changed before the fresh manual Install')
        old_pid=self.guard.pid
        self.guard.install_until=time.monotonic()+180
        self.result['install_touch_attempted']=True;self.save()
        self.tap('立即安装')  # Only this explicit mode ever submits this fresh touchscreen release.
        pending=None
        until=time.monotonic()+180
        while time.monotonic()<until:
            self.guard.check()
            value=self.d.document('files/update/pending.json')
            if value:
                require(value.get('from')==self.identity(self.p.baseline) and value.get('to')==self.identity(self.p.target) and
                        value.get('catalog_digest')==self.p.target['catalog_sha256'] and value.get('seq')==self.p.target['seq'] and
                        value.get('channel')==self.d.channel and value.get('mode')=='manual' and value.get('health_ms')==600000,
                        'Actual install journal differs from the exact manual transaction')
                self.result.setdefault('pending_observations',[])
                if not self.result['pending_observations'] or value!=self.result['pending_observations'][-1]:
                    require(len(self.result['pending_observations'])<12,'Too many changing install observations')
                    self.result['pending_observations'].append(value);self.save()
                if value.get('phase')=='TRIAL' and value.get('attempts')==1 and self.d.pid(optional=True) not in (None,old_pid):
                    pending=value;break
            time.sleep(.5)
        require(pending is not None,'Ordinary replacement did not start exact first TRIAL; no launch/retry is attempted')
        self.guard.pid=self.d.pid();self.guard.install_until=0;self.guard.check()
        self.check('Actual new MAIN PID runs the exact owner-signed target APK',self.d.installed_sha()==self.p.target['sha256'])
        self.check('Actual predecessor backup remains exact during trial',self.d.private('files/update/previous.apk',64*1024*1024,True)==self.p.baseline['sha256'])
        def gate_witness():
            raw=self.d.adb('logcat','-d','-v','epoch','--pid='+str(self.guard.pid),'-s','ThinkOS:V').decode('utf-8',errors='replace')
            pattern=r'^\s*\d+\.\d+\s+'+str(self.guard.pid)+r'\s+\d+\s+I\s+ThinkOS\s*:\s*Update trial ready; health_ms=([0-9]+) elapsed=([0-9]+)\s*$'
            rows=[]
            for line in raw.splitlines():
                found=re.fullmatch(pattern,line)
                if found and 0<int(found[1])<=600000 and len(line)<=2048:
                    rows.append({'line':line,'remaining_ms':int(found[1]),'elapsed_ms':int(found[2])})
            return rows[-1] if rows else None
        witness=self.wait(gate_witness,seconds=20)
        ready_elapsed=witness['elapsed_ms']-(600000-witness['remaining_ms'])
        require(ready_elapsed>=(pending.get('trial_started') or {}).get('elapsed',-1),'Actual first-frame/catalog gate precedes trial')
        self.result['first_frame_catalog_witness']=witness
        started=time.monotonic();self.result['health_samples']=[]
        self.result.update(old_pid=old_pid,new_pid=self.guard.pid,transaction_id=pending['transaction_id'],real_health_observation_started=started);self.save()
        # Never restart/instrument/reopen the process during this actual health
        # window. Read-only polling neither shortens nor resets its 600 seconds.
        while time.monotonic()-started<600:
            self.guard.check()
            current=self.d.document('files/update/pending.json')
            require(current is None or current.get('transaction_id')==pending['transaction_id'] and current.get('phase')=='TRIAL' and
                    current.get('trial_id')==pending.get('trial_id') and current.get('attempts')==1,'Trial changed/restarted/rolled back; preserve it')
            if current is None:
                early=(self.d.document('files/update/install-history.json') or {}).get('result',{})
                require(early.get('transaction_id')==pending['transaction_id'] and
                        (early.get('recorded') or {}).get('elapsed',-1)>=ready_elapsed+600000,
                        'Native health/pending cleanup occurred before its real 600-second gate')
            self.result['health_samples'].append({'elapsed_s':round(time.monotonic()-started,3),'pending_trial':current is not None,'pid':self.guard.pid});self.save()
            time.sleep(min(10,max(0,600-(time.monotonic()-started))))
        self.wait(lambda:self.d.private('files/update/pending.json') is None,seconds=120)
        history=self.d.document('files/update/install-history.json') or {};result=history.get('result',{})
        self.check('Real complete 600-second observation ends with exact healthy transaction and target',
            time.monotonic()-started>=600 and result.get('transaction_id')==pending['transaction_id'] and
            result.get('kind')=='updated' and result.get('code')==self.p.target['code'] and result.get('version')==self.p.target['version'] and
            (result.get('recorded') or {}).get('elapsed',-1)>=ready_elapsed+600000 and
            result.get('needs_recovery_clear') is False and self.d.installed_sha()==self.p.target['sha256'])
        self.check('Health naturally clears pending and exact predecessor backup',all(self.d.private(path) is None for path in PENDING) and self.d.private('files/update/previous.apk',64*1024*1024,True) is None)
        prior=self.d.document('files/update/install-history.json') or {}
        self.check('Normal update preserves all existing failure/blacklist entries',prior.get('failures',[])==self.history_before_doc.get('failures',[]))
        session=pending.get('session_id')
        from self_update_device import install_session
        receipt=install_session(self.d.shell('dumpsys','package'),session) if type(session) is int else None
        self.check('Real platform session succeeds without manual system permission acceptance',receipt is not None and receipt['destroyed'] and
            receipt['legacy_status']==1 and receipt['permissions_manually_accepted'] is False and receipt['installer_uid']==self.original_uid)
        self.d.owner_and_home()
        self.check('Existing Device Owner/admin/LockTask/runtime grants remain unchanged',self.d.policy_snapshot()==self.original_policy)
        expected=dict(self.recovery_before);expected.pop('crashes',None)
        expected.update(safe_mode=False,healthy_update=pending['transaction_id'])
        self.check('Production healthy-update recovery acknowledgement is the only recovery semantic change',
                   self.preferences(self.d.private('shared_prefs/recovery.xml'))==expected)
        self.result.update(installed=True,actual_new_process_observed=True,real_600s_health=True,health_elapsed_s=round(time.monotonic()-started,3),platform_session=receipt)
        self.save()

    def run(self):
        require(self.p.setting_defaults is not None,'Pinned baseline APK settings schema is required for effective-value observations')
        before=self.history();require(all(before[p] is None for p in PENDING),'A real APK/module transaction exists; preserve it')
        self.history_before_doc=self.d.document('files/update/install-history.json') or {}
        self.recovery_before=self.preferences(self.d.private('shared_prefs/recovery.xml'))
        require(self.recovery_before.get('safe_mode',False) is False and not self.recovery_before.get('crashes',''),
                'Preserve an existing safe-mode/crash incident; normal rehearsal cannot clear it')
        settings_before={path:self.d.document(path) or {} for path in SETTINGS}
        server_before=self.d.private(SETTINGS[1])
        source_before=source_presence(settings_before[SETTINGS[0]].get('system',{}),
            settings_before[SETTINGS[1]].get('system',{}),self.p.setting_defaults)
        require(self.effective('update.auto_install') is False,'Automatic installation must already be off; never toggle it here')
        require(self.effective('update.enabled') is True,'Updates must already be enabled')
        # The shipped default (the domestic release source) has no catalog during a rehearsal, so it only answers 404;
        # any other configured source could serve a competing catalog and make this source exercise ambiguous.
        require(self.effective('update.sources') in ('',self.p.setting_defaults['update.sources']),
                'Other configured sources would make this source exercise ambiguous')
        original={key:self.effective(key) for key in ('update.mirror','update.channel')}
        require(isinstance(original['update.mirror'],str) and len(original['update.mirror'])<=1024 and
                '%s' not in original['update.mirror'] and all(32<=ord(c)<127 for c in original['update.mirror']),'Original mirror cannot be safely restored through native ASCII editor')
        require(len(self.p.mirror)<=1024 and '%s' not in self.p.mirror,'Prepared mirror exceeds native-input/schema constraints')
        ledger=self.d.document('files/update/ledger/sequences.json') or {'schema':1}
        floor=ledger.get(self.d.channel)
        require(floor is None or type(floor.get('seq')) is int and floor['seq']<self.p.target['seq'],'Prepared catalog must be strictly newer than actual device floor; never reset it')
        current=self.d.document('files/update/state/state-'+self.d.channel+'.json') or {}
        require(current.get('phase') not in ('INSTALLING','DOWNLOADING'),'Existing active update must remain untouched')
        self.result.update(original_source=original,initial_history=before,initial_floor=ledger,
            source_presence_before=source_before,server_before_present=server_before is not None,
            server_before_sha256=None if server_before is None else hashlib.sha256(server_before).hexdigest())
        self.save()  # Write the private receipt before the first safe-wake/source touch.
        self.safe_wake('before-source-setup')
        self.open_settings();self.public_advanced();self.set_channel(self.d.channel);self.set_mirror(self.p.mirror)
        self.input('keyevent','KEYCODE_BACK');self.update_window()
        self.check('Actual native update page shows the exact installed baseline',self.node(label='当前版本 '+self.p.baseline['version']) is not None)
        self.tap('检查更新')
        self.wait(lambda:(self.d.document('files/update/state/state-'+self.d.channel+'.json') or {}).get('catalog_digest')==self.p.target['catalog_sha256'],seconds=90)
        cache=self.d.document('files/update/metadata/catalog-'+self.d.channel+'.json') or {}
        self.check('Production authenticated cache contains exact reviewed catalog and source',
                   base64.b64decode(cache.get('body',''),validate=True)==self.p.catalog_bytes and cache.get('source')==self.p.mirror)
        self.check('Native update page offers the exact target version',self.node(label='新版本 '+self.p.target['version']) is not None)
        require(self.effective('update.auto_install') is False,'Auto-install changed before download')
        self.tap('下载更新');self.wait(self.ready,seconds=600)
        self.check('Native page reports downloaded and exposes native Install',self.node(label='更新已下载') is not None and self.node(label='立即安装') is not None)
        self.ready_proof()
        self.check('Production READY has exact digest/seq/bytes and automatic install remains off',self.ready() and self.effective('update.auto_install') is False)
        self.result['native_ready_verified']=True;self.save()
        (self.output/'ready.png').write_bytes(self.d.adb('exec-out','screencap','-p'))
        if self.install:
            self.install_healthy()
        else:self.input('keyevent','KEYCODE_BACK')
        self.safe_wake('before-source-restore')
        self.open_settings()
        self.public_advanced()
        require(self.effective('update.mirror')==self.p.mirror and self.effective('update.channel')==self.d.channel,'Source changed externally; do not overwrite it')
        require(self.d.private(SETTINGS[1])==server_before,'Server content changed during rehearsal; do not overwrite newer management values')
        self.set_mirror(original['update.mirror']);self.set_channel(original['update.channel'])
        self.check('Original effective source values restored by the same public native editors',all(self.effective(k)==v for k,v in original.items()))
        self.check('Original source local presence/value and Server bytes restored without raw writes',
            source_presence((self.d.document(SETTINGS[0]) or {}).get('system',{}),
                (self.d.document(SETTINGS[1]) or {}).get('system',{}),self.p.setting_defaults)==source_before and
            self.d.private(SETTINGS[1])==server_before)
        self.result['source_restore_completed']=True;self.save()
        after=self.history()
        allowed=('files/update/install-history.json','files/update/install-history.json.bak','shared_prefs/recovery.xml','shared_prefs/recovery.xml.bak') if self.install else ()
        self.check('Unrelated durable files unchanged; production update/recovery acknowledgements checked separately',all(after[p]==before[p] for p in before if p not in allowed))
        def without_source(layer):
            value=json.loads(json.dumps(layer));scope=value.get('system',{})
            scope.pop('update.mirror',None);scope.pop('update.channel',None)
            if not scope:value.pop('system',None)
            return value
        self.check('No unrelated local/server preference changed',all(without_source(self.d.document(path) or {})==without_source(settings_before[path]) for path in SETTINGS))
        self.check('Exact expected APK and policy retained',self.d.installed_sha()==(self.p.target if self.install else self.p.baseline)['sha256'])
        self.d.owner_and_home()
        self.check('Original admin/LockTask/runtime permission grants preserved at final UI',self.d.policy_snapshot()==self.original_policy)
        if self.node(label='完成'):self.tap('完成')
        if self.install:self.result['pending']=REMAINING[1:]
        self.result.update(stage='normal_install_health_complete' if self.install else 'native_READY_complete',complete=True,installed=self.install,full_OTA5_complete=False)
        self.save()


def execute(prepared,args,*,device_factory=Device,watch_factory=TouchWatch,hierarchy_factory=Hierarchy,ui_factory=UI):
    plan._spare(prepared.serial,prepared.physical)
    reviewed=ui_plan(prepared,args)
    require(args.authorize_spare==prepared.physical and args.reviewed_sha256==reviewed['ui_input_sha256'],'Explicit authorized spare and reviewed UI input SHA are required before ADB')
    require(prepared.setting_defaults is not None,'Actual baseline APK settings schema is required before ADB')
    defaults=prepared.setting_defaults
    require(all(key in defaults for key in ('update.auto_install','update.enabled','update.sources','update.mirror','update.channel')) and
            type(defaults['update.auto_install']) is bool and type(defaults['update.enabled']) is bool and
            all(isinstance(defaults[key],str) for key in ('update.sources','update.mirror','update.channel')),
            'Actual APK update setting defaults are missing or malformed')
    require(prepared.target_setting_defaults is not None and all(prepared.target_setting_defaults.get(key)==defaults[key]
        for key in ('update.auto_install','update.enabled','update.sources','update.mirror','update.channel')),
        'Normal UI rehearsal requires matching actual baseline/target update defaults; changed-default migration is a separate case')
    output=no_symlink_path(args.output)
    require(str(output).startswith('/private/tmp/') and not output.exists() and output.parent.is_dir(),'New private TMP report directory required')
    output.mkdir(mode=0o700)
    device=watch=guard=hierarchy=ui=None;started=False;success=False
    try:
        os.environ['ADB_MDNS']='0'
        device=device_factory(prepared.serial);prepared.preflight(device)
        component=focused_component(device)
        require(component and component.split('/',1)[0]==APP,'Open the authorized spare app through its ordinary HOME/public launcher first')
        require(device._read('getprop','persist.sys.locale').strip().startswith('zh'),'Actual spare must already use Chinese; do not change its locale here')
        package=device._read('dumpsys','package',APP)
        found=re.findall(r'^\s*dataDir=(/data/(?:user/0|data)/'+re.escape(APP)+r')\s*$',package,re.M)
        require(len(set(found))==1,'One actual production private directory is required')
        device.directory=found[0];device.channel=prepared.target['channel'];device.target_sha=prepared.target['sha256'];device.target_code=prepared.target['code']
        require(device.shell('su','-c','id -u').strip()=='0','Bounded release observation requires already available host read-only root')
        watch=watch_factory(device);guard=Guard(device,watch,prepared,time.monotonic()+1800);guard.check()
        require(not prepared.origin._address.is_loopback,'UI rehearsal requires the prepared reachable RFC1918 LAN address, no reverse mutation')
        prepared.origin.start();started=True
        jar=plan._snapshot(args.hierarchy_jar,output/'hierarchy.jar',16*1024*1024)
        require(hashlib.sha256(jar.read_bytes()).hexdigest()==reviewed['hierarchy_sha256'],'Reviewed helper changed before immutable snapshot')
        hierarchy=hierarchy_factory(device,guard,uuid.uuid4().hex,jar)
        ui=ui_factory(device,guard,hierarchy,prepared,output)
        ui.install=args.execute_install
        if ui.install:ui.result['scope']='actual_public_normal_source_install_new_MAIN_and_real_600s_health_only'
        uid=re.search(r'\buserId=([0-9]+)',package)
        require(uid is not None,'Actual production UID is unavailable')
        ui.original_uid=int(uid[1]);ui.original_policy=device.policy_snapshot()
        ui.result.update(reviewed=reviewed,original_policy=ui.original_policy);ui.save();ui.run()
        events=prepared.origin.events()
        require(events['total']>0 and events['dropped']==0 and not any(e.get(k) for e in events['events'] for k in
            ('serial_found','android_id_found','mac_found','sensitive_header_found')),'Source request audit is incomplete or contains sensitive headers')
        (output/'origin-events.json').write_bytes(json_bytes(events))
        success=True
    except BaseException as error:
        try:(output/'failure.txt').write_text(traceback.format_exc())
        except BaseException:pass
        if ui:
            try:ui.result.update(complete=False,error=type(error).__name__);ui.save()
            except BaseException:pass
        raise
    finally:
        cleanup_error=None
        try:
            if started:prepared.origin.close()
            if guard:
                guard.check()
                if hierarchy:cleanup_hierarchy(device,guard,hierarchy)
        except BaseException as error:
            cleanup_error=error
            try:(output/'cleanup-failure.txt').write_text(traceback.format_exc())
            except BaseException:pass
        try:
            if watch:watch.close()
        except BaseException as error:cleanup_error=cleanup_error or error
        if ui:
            try:
                ui.result.update(complete=success and cleanup_error is None,temporary_cleanup=cleanup_error is None)
                ui.save()
            except BaseException:
                if success:raise
        if success and cleanup_error:raise RuntimeError('Owned cleanup failed; scene retained') from cleanup_error
    return ui.result
