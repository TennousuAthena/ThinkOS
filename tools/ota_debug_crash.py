#!/usr/bin/env python3
"""MIT. Offline owner-debug v2 experiment producer; not normal release material.

No device, network, build, key generation, installation or runtime fault. Plan
reads only public inputs. Execute additionally requires the reviewed fingerprint
and an existing encrypted owner channel key. Actual commit/crash stays unverified.
"""
import argparse
import base64
import copy
import hashlib
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import zipfile
import ota_lan
import ota_negative
import release
from release_tools import (ROOT, PRIVATE_PASSWORD_ENV, create_directory, digest, exclusive_write,
                           json_bytes, outside_repository, private_directory, private_file,
                           read_password, require_owner_host, secret_environment)

APP='moe.kiwi.thinkos'
PHASE='debug_commit_fault_capability_v2'
KIND='ota5-owner-debug-crash-v2'
PUBLIC=ota_negative.PUBLIC
HEX=re.compile(r'[a-f0-9]{64}')
POSITIVE={'manual_commit_admitted','marker_roundtrip_equal','second_commit_rejected','automatic_commit_rejected',
          'wrong_session_rejected','expired_commit_rejected','trial_1_eligible','trial_2_eligible',
          'trial_3_eligible','legacy_marker_rejected'}
NEGATIVE={'no_pending_eligible','unsubmitted_eligible','installing_eligible','trial_4_eligible',
          'wrong_installed_hash_eligible','wrong_installed_signer_eligible','changed_catalog_eligible','changed_notes_eligible'}
require=ota_negative.require
snapshot=ota_negative._snapshot_file


def identity(actual):
    return {key:actual[key] for key in ('version','code','sha256','size','signer')}


def vector_marker(actual,nonce):
    return {'schema':2,'nonce':nonce,'pending':{'schema':1,'transaction_id':nonce,'phase':'INSTALLING',
        'channel':'beta','catalog_digest':'a'*64,'seq':1,
        'from':{'version':'0.0.1','code':1,'sha256':'4'*64,'size':1,'signer':actual['signer']},
        'to':identity(actual),'mode':'manual','attempts':0,'max_attempts':3,'health_ms':600000,
        'notice_ms':86400000,'failure_limit':3,'created':{'elapsed':100,'boot':1,'wall':1000},
        'notes':{'en':'Readonly v2 policy compatibility'},'session_id':23}}


def capability(raw,actual,trust,public_hashes):
    result=release.parse_document(raw)
    require(isinstance(result,dict) and result.get('phase')==PHASE and result.get('complete') is True and
            type(result.get('sdk')) is int and result['sdk']==27 and type(result.get('uid')) is int and result['uid']>=10000,
            'A complete installed-candidate spare capability phase is required; arm/roots/class-name claims do not qualify')
    checks=result.get('checks')
    require(isinstance(checks,list) and len(checks)==1 and isinstance(checks[0],dict) and checks[0].get('name')==
            'installed owner-debug APK executes isolated v2 compatibility vectors' and checks[0].get('passed') is True,
            'Actual compatibility phase checks did not pass')
    observed=result.get(PHASE)
    require(isinstance(observed,dict) and type(observed.get('schema')) is int and observed['schema']==1 and observed.get('complete') is True and
            observed.get('scope')=='policy_roundtrip_only','Compatibility scope differs')
    for key in ('runtime_crash_verified','commit_hook_executed','probe_starts_main','probe_starts_runtime',
                'probe_calls_installer','probe_writes_files','global_arm_changed'):
        require(observed.get(key) is False,'Capability receipt exceeds readonly policy scope')
    require(observed.get('apk_stable_during_probe') is True and observed.get('ordinary_application_bound_before_phase') is True and
            observed.get('executing_apk_identity_verified') is True and observed.get('helper_target_classloader_equal') is True,
            'Actual installed identity was not stable during the phase')
    spare=observed.get('spare_identity_sha256');nonce=observed.get('run_id')
    require(isinstance(spare,str) and HEX.fullmatch(spare) and spare!=hashlib.sha256(b'HUA0BWUT').hexdigest() and
            isinstance(nonce,str) and re.fullmatch('[a-f0-9]{32}',nonce),'Bound known spare/run identity required')
    expected={**actual,'package':APP,'debuggable':True,'target_sdk':27}
    require(json_bytes(observed.get('apk'))==json_bytes(expected),'Capability receipt refers to different APK bytes/signer/version')
    roots=observed.get('roots')
    require(isinstance(roots,dict) and type(roots.get('schema')) is int and roots['schema']==1 and roots.get('complete') is True and
            roots.get('scope')=='installed_apk_assets_and_production_key_loaders_only' and
            roots.get('update_keys_load_ok') is True and roots.get('system_keys_load_ok') is True and
            roots.get('six_roles_independent') is True and roots.get('reg_fixture_namespace_present') is False and
            roots.get('apk_stable_during_probe') is True and roots.get('failures')==[] and
            roots.get('declared_role_counts')=={'channel':2,'registry':2,'system':2} and
            json_bytes(roots.get('apk'))==json_bytes(expected),'Actual installed production-root loaders did not qualify')
    for key in ('probe_writes_files','probe_changes_settings','probe_starts_runtime','probe_calls_installer'):
        require(roots.get(key) is False,'Root observation must remain read-only')
    assets=roots.get('assets',{});require(isinstance(assets,dict),'Malformed asset observations')
    for name in ('keys.json','system-keys.json'):
        entry=assets.get(name,{})
        require(isinstance(entry,dict),'Malformed asset observation')
        require(entry.get('present') is True and entry.get('sha256')==public_hashes[name] and
                entry.get('bytes')==len(trust['files'][name]),'Observed APK roots differ from owner exports')
    require(isinstance(assets.get('debug-keys.json'),dict) and assets['debug-keys.json'].get('present') is False,'Extra debug roots cannot qualify')
    vectors=observed.get('vectors',{})
    require(isinstance(vectors,dict) and type(vectors.get('schema')) is int and vectors['schema']==1 and vectors.get('complete') is True and vectors.get('scope')=='policy_roundtrip_only',
            'Actual v2 codec/admission vectors missing')
    require(vectors.get('executing_apk_identity_verified') is True and vectors.get('helper_target_classloader_equal') is True,
            'Installed PM identity alone cannot qualify code executing from another APK')
    outcomes=vectors.get('observed',{})
    require(isinstance(outcomes,dict) and set(outcomes)==POSITIVE|NEGATIVE and all(type(value) is bool and value==(name in POSITIVE)
            for name,value in outcomes.items()),'V2 admission/TRIAL outcome matrix differs')
    encoded=vectors.get('marker_base64')
    require(isinstance(encoded,str) and len(encoded)<=16384,'Bounded actual v2 marker bytes required')
    marker=base64.b64decode(encoded,validate=True)
    require(1<=len(marker)<=8192 and hashlib.sha256(marker).hexdigest()==vectors.get('marker_sha256') and
            json_bytes(release.parse_document(marker))==json_bytes(vector_marker(actual,nonce)),
            'Actual v2 roundtrip marker does not bind the exact installed APK tuple')
    return {'spare_identity_sha256':spare,'run_id':nonce,'scope':'policy_roundtrip_only'}


def capture(args,source_root,tools):
    with tempfile.TemporaryDirectory(prefix='thinkos-owner-debug-public-snapshot-') as name:
        work=Path(name).resolve()
        apk=snapshot(args.apk,work/'candidate.apk',ota_lan.MAX_APK)
        evidence=snapshot(args.capability_receipt,work/'capability.json',65536)
        previous=snapshot(args.previous_catalog,work/'previous.json',1024*1024)
        prior_sig=snapshot(Path(str(args.previous_catalog)+'.sig'),work/'previous.sig',1024).read_bytes()
        ota_lan._snapshot_flat(args.public_dir,work/'public',PUBLIC)
        trust,signer,public_hashes=ota_lan._trusted_public(work/'public',source_root)
        ota_lan._archive(apk,arm_libraries=True)
        actual=release.inspect_apk(apk,signer,args.version,debuggable=True,tools=tools)
        release.packaged_public_trust(apk,trust)
        with zipfile.ZipFile(apk) as archive:
            require('assets/update/debug-keys.json' not in archive.namelist(),'Extra debug signing roots are refused')
        proof=capability(evidence.read_bytes(),actual,trust,public_hashes)
        release.verify(previous,prior_sig,list(trust['keys']['channel'].values()))
        prior=release.validate_catalog(release.parse_document(previous.read_bytes()))
        require(prior['release']['modules']==[],'Only an APK-only predecessor is accepted; modules may not be silently removed')
        require(prior['release']['signer']==signer and actual['code']>prior['release']['code'],
                'Owner-debug candidate must be a forward same-owner update')
        require(prior['expires']>int(time.time()*1000),'Authenticated predecessor expired')
        return {'actual':actual,'trust':trust,'public_hashes':public_hashes,'proof':proof,
                'receipt_sha256':digest(evidence),'previous_sha256':digest(previous),
                'previous_sig_sha256':hashlib.sha256(prior_sig).hexdigest(),'previous':prior,'apk_bytes':apk.read_bytes()}


def fingerprint(args,value,document,base,output):
    return hashlib.sha256(json_bytes({'artifact_kind':KIND,'apk':value['actual'],'public':value['public_hashes'],
        'capability_receipt_sha256':value['receipt_sha256'],'previous_catalog_sha256':value['previous_sha256'],
        'previous_signature_sha256':value['previous_sig_sha256'],'catalog':document,'lan_root':base,
        'output':str(output),'slot':args.slot})).hexdigest()


def prepare(args,*,source_root=ROOT,tools=None):
    require_owner_host()
    output=outside_repository(args.output)
    require(not output.exists() and output.parent.is_dir(),'Use a new output directory with an existing parent')
    for path in (args.apk,args.capability_receipt,args.previous_catalog,args.public_dir):
        path=release.no_symlink_path(Path(path))
        require(output!=path and output not in path.parents and path not in output.parents,'Output overlaps public input')
    base=ota_lan.lan_root(args.lan_root)
    release.distribution_base(args.source_url) # The normal source HTTPS policy is unchanged.
    tools=release.sdk_tools() if tools is None else tools
    value=capture(args,source_root,tools);actual=value['actual'];prior=value['previous']
    require(type(args.seq) is int and prior['seq']<args.seq<=9223372036854775807,'New seq must exceed authenticated predecessor')
    now=int(time.time()*1000)
    require(type(args.published_ms) is int and type(args.expires_ms) is int and 0<=args.published_ms<=now<args.expires_ms,
            'Use explicit current catalog publication/expiry times')
    apk_name='owner-debug-crash-v2-'+actual['version']+'.apk';catalog_name=prior['channel']+'.json'
    document={'schema':1,'channel':prior['channel'],'seq':args.seq,'published':args.published_ms,'expires':args.expires_ms,
        'release':{**identity(actual),'api':prior['release']['api'],'device':copy.deepcopy(prior['release']['device']),
            'sdk':27,'urls':[base+'/'+apk_name],'modules':[],
            'notes':{'en':'Owner-debug commit-bound crash v2 experiment; not a normal release.'},'source':args.source_url}}
    release.validate_catalog(document);raw=json_bytes(document)
    reviewed=fingerprint(args,value,document,base,output)
    require(args.reviewed_sha256 is None or args.reviewed_sha256==reviewed,'Inputs/plan changed after owner review')
    plan={'schema':1,'stage':'planned','artifact_kind':KIND,'executed':False,'published':False,'device_changed':False,
        'normal_release_eligible':False,'policy_compatibility_verified':True,'proof_scope':'policy_roundtrip_only',
        'owner_public_hashes_compared':True,'receipt_provenance':'owner_saved_local_observation_not_hardware_attestation',
        'runtime_crash_verified':False,'actual_commit_verified':False,'ota5_device_acceptance_complete':False,
        'baseline_arm_capability_verified':False,'requires_separate_baseline_arm_and_real_trial':True,
        'input_sha256':reviewed,'capability_receipt_sha256':value['receipt_sha256'],'apk':actual,
        'previous_seq':prior['seq'],'seq':args.seq,'channel':prior['channel'],'mirror':base+'/'+catalog_name,
        'catalog_sha256':hashlib.sha256(raw).hexdigest(),'key_id':value['trust']['keys']['channel'][args.slot]['id'],
        'files':[apk_name,catalog_name,catalog_name+'.sig'],
        'scope':'Installed-owner-debug v2 policy compatibility only; normal LAN origin refuses this experimental kind'}
    if not args.execute:return plan
    require(args.reviewed_sha256 is not None and args.private_key is not None,'Execute requires reviewed SHA and existing owner channel key')
    require(fingerprint(args,capture(args,source_root,tools),document,base,output)==reviewed,'Public inputs changed before private-key access')
    key=private_file(outside_repository(args.private_key));private_directory(key.parent)
    password=read_password(args.password_fd);environment=secret_environment(password);password=None
    try:
        with tempfile.TemporaryDirectory(prefix='thinkos-owner-debug-sign-') as name:
            signed=Path(name).resolve()/catalog_name;exclusive_write(signed,raw)
            signature=release.sign(signed,key,value['trust']['keys']['channel'][args.slot],environment)
            release.verify(signed,signature,list(value['trust']['keys']['channel'].values()))
        require(fingerprint(args,capture(args,source_root,tools),document,base,output)==reviewed,'Public inputs changed while signing')
        create_directory(output,private=True)
        exclusive_write(output/apk_name,value['apk_bytes'])
        require(digest(output/apk_name)==actual['sha256'],'Copied APK bytes changed')
        exclusive_write(output/(catalog_name+'.sig'),signature)
        require({p.name for p in output.iterdir()}=={apk_name,catalog_name+'.sig'},'Unexpected experimental output content')
        exclusive_write(output/catalog_name,raw) # Catalog committed last; no source/report/key/receipt copied.
        require({p.name for p in output.iterdir()}==set(plan['files']),'Unexpected final experimental output')
    finally:environment.pop(PRIVATE_PASSWORD_ENV,None)
    plan.update(stage='prepared',executed=True,output_sha256={p.name:digest(p) for p in output.iterdir()})
    return plan


def parser():
    result=argparse.ArgumentParser(description=__doc__)
    result.add_argument('--apk',type=Path,required=True);result.add_argument('--version',required=True)
    result.add_argument('--public-dir',type=Path,required=True);result.add_argument('--capability-receipt',type=Path,required=True)
    result.add_argument('--previous-catalog',type=Path,required=True)
    result.add_argument('--lan-root',required=True);result.add_argument('--source-url',required=True)
    result.add_argument('--seq',type=int,required=True);result.add_argument('--published-ms',type=int,required=True)
    result.add_argument('--expires-ms',type=int,required=True);result.add_argument('--output',type=Path,required=True)
    result.add_argument('--slot',choices=('active','backup'),default='active');result.add_argument('--private-key',type=Path)
    result.add_argument('--password-fd',type=int);result.add_argument('--reviewed-sha256');result.add_argument('--execute',action='store_true')
    return result


def main(argv=None,*,source_root=ROOT,tools=None):
    print(json_bytes(prepare(parser().parse_args(argv),source_root=source_root,tools=tools)).decode(),end='');return 0


if __name__=='__main__':
    try:sys.exit(main())
    except (ValueError,OSError,RuntimeError,KeyError,TypeError,AttributeError,zipfile.BadZipFile,UnicodeError):
        print('Owner-debug experimental preparation stopped',file=sys.stderr);sys.exit(1)
