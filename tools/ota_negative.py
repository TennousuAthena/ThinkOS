#!/usr/bin/env python3
"""MIT. Owner-only fixed signed OTA-5 semantic negatives from verified APK-only LAN material.

Default is a read-only plan. No network/device/build/key generation/publication.
This experiment kind is never a normal release or an owner-debug crash proof.
"""
import argparse
import copy
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import time
import zipfile

import ota_lan
import release
from release_tools import (ROOT, PRIVATE_PASSWORD_ENV, create_directory, digest, exclusive_write,
                           json_bytes, no_symlink_path, outside_repository, private_file,
                           read_password, require_owner_host, secret_environment)

sys.path.insert(0,str(ROOT/'tools/tests'))
from ota5_origin import _materials

PUBLIC = ('keys.json','system-keys.json','updater_keys.h','key-inventory.json','apk-certificate.der')
CASES = ('wrong-channel','apk-digest-mismatch')
KIND = 'ota5-signed-semantic-negative'


def require(value,message):
    if not value:
        raise ValueError(message)
    return value


def _snapshot_file(source,target,limit):
    source = no_symlink_path(Path(source))
    require(source.suffix.lower() not in {'.pem','.key','.p12','.jks','.keystore'} and source.name!='keystore.properties',
            'Private-material filenames are refused as public inputs')
    descriptor = os.open(source.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try: ota_lan._copy_open(descriptor,source.name,target,limit)
    finally: os.close(descriptor)
    return target


def _capture(args,source_root,tools):
    # SDK identity, signatures and copies all see immutable snapshot bytes.
    with tempfile.TemporaryDirectory(prefix='thinkos-owner-negative-inputs-') as name:
        work = Path(name).resolve()
        result_file = _snapshot_file(args.lan_result,work/'lan-result.json',65536)
        manifest = release.parse_document(result_file.read_bytes())
        ota_lan._snapshot_flat(no_symlink_path(args.public_dir),work/'public',PUBLIC)
        trust,signer,public_hashes = ota_lan._trusted_public(work/'public',source_root)
        files,hashes,_,catalog_name,apk_name = _materials(args.lan_dir,manifest)
        raw = files[catalog_name]
        catalog_file = work/'catalog.json';exclusive_write(catalog_file,raw)
        release.verify(catalog_file,files[catalog_name+'.sig'],list(trust['keys']['channel'].values()))
        original = release.validate_catalog(release.parse_document(raw))
        require(original['expires']>int(time.time()*1000),'Original authenticated LAN catalog is expired')
        package = original['release']
        require(package['modules']==[],'This fixed negative producer accepts APK-only source material')
        require(original['channel']+'.json'==catalog_name and package['signer']==signer,'Owner/channel identity differs')
        release.distribution_base(package['source'])
        apk = work/'target.apk';exclusive_write(apk,files[apk_name])
        ota_lan._archive(apk,arm_libraries=True)
        actual = release.inspect_apk(apk,signer,package['version'],debuggable=False,tools=tools)
        require(all(actual[key]==package[key] for key in actual),'Actual signed release APK differs from its authenticated tuple')
        release.packaged_public_trust(apk,trust)
        with zipfile.ZipFile(apk) as archive:
            require('assets/update/debug-keys.json' not in archive.namelist(),'Extra debug roots are refused')
        minimum,previous_hashes = original['seq'],None
        if args.initial:
            require(args.previous_catalog is None,'Initial negative cannot specify a predecessor')
        else:
            require(args.previous_catalog is not None,'Authenticate a predecessor or select explicit initial use')
            previous_file = _snapshot_file(args.previous_catalog,work/'previous.json',1024*1024)
            signature = _snapshot_file(Path(str(args.previous_catalog)+'.sig'),work/'previous.sig',1024).read_bytes()
            release.verify(previous_file,signature,list(trust['keys']['channel'].values()))
            previous = release.validate_catalog(release.parse_document(previous_file.read_bytes()))
            # Only reserve its authenticated sequence; a semantic negative must
            # never masquerade as a valid previous release/APK identity.
            require(previous['release']['code']<=package['code'],'Do not rehearse an older target version')
            minimum = max(minimum,previous['seq'])
            previous_hashes = {'document':digest(previous_file),'signature':hashlib.sha256(signature).hexdigest()}
        return {'files':dict(files),'hashes':dict(hashes),'trust':trust,'public_hashes':public_hashes,
                'signer':signer,'original':original,'catalog_name':catalog_name,'apk_name':apk_name,
                'minimum_seq':minimum,'previous_hashes':previous_hashes,'lan_result_sha256':digest(result_file)}


def _fingerprint(args,value,base,output):
    return hashlib.sha256(json_bytes({'case':args.case,'source_files':value['hashes'],
        'lan_result_sha256':value['lan_result_sha256'],'public_files':value['public_hashes'],
        'previous':value['previous_hashes'],'initial':args.initial,'seq':args.seq,'slot':args.slot,
        'lan_root':base,'output':str(output),'artifact_kind':KIND})).hexdigest()


def prepare(args,*,source_root=ROOT,tools=None):
    require_owner_host()
    require(args.case in CASES,'Unknown fixed semantic negative')
    source_root = Path(source_root)
    source = no_symlink_path(args.lan_dir);public = no_symlink_path(args.public_dir)
    result_path = no_symlink_path(args.lan_result)
    output = outside_repository(args.output)
    require(not output.exists() and output.parent.is_dir(),'Output must be new with an existing parent')
    for parent in (source,public):
        require(output!=parent and output not in parent.parents and parent not in output.parents,'Output must not overlap authenticated inputs')
    require(output!=result_path and output not in result_path.parents,'Output must not contain the input result')
    base = ota_lan.lan_root(args.lan_root)
    tools = release.sdk_tools() if tools is None else tools
    value = _capture(args,source_root,tools)
    require(type(args.seq) is int and value['minimum_seq']<args.seq<=9223372036854775807,
            'New sequence must exceed authenticated source and predecessor sequences')
    old_base = value['original']['release']['urls'][0].rsplit('/',1)[0]
    require(base!=old_base,'Use a separate explicit experimental LAN root')
    document = copy.deepcopy(value['original'])
    document['seq'] = args.seq
    document['release']['urls'] = [base+'/'+value['apk_name']]
    if args.case=='wrong-channel':
        document['channel'] = 'stable' if document['channel']=='beta' else 'beta'
    else:
        original_hash = document['release']['sha256']
        document['release']['sha256'] = ('1' if original_hash[0]=='0' else '0')+original_hash[1:]
    release.validate_catalog(document)
    raw = json_bytes(document)
    reviewed = _fingerprint(args,value,base,output)
    plan = {'schema':1,'stage':'planned','artifact_kind':KIND,'case':args.case,'executed':False,
            'published':False,'device_changed':False,'ota5_device_acceptance_complete':False,'input_sha256':reviewed,
            'source_catalog_sha256':value['hashes'][value['catalog_name']],
            'catalog_sha256':hashlib.sha256(raw).hexdigest(),'channel':value['original']['channel'],
            'document_channel':document['channel'],'previous_seq':value['minimum_seq'],'seq':args.seq,
            'mirror':base+'/'+value['catalog_name'],'files':[value['apk_name'],value['catalog_name'],value['catalog_name']+'.sig'],
            'key_id':value['trust']['keys']['channel'][args.slot]['id'],
            'expected_rejection':'catalog channel mismatch' if args.case=='wrong-channel' else 'downloaded APK SHA-256 mismatch',
            'catalog_may_advance_device_floor':args.case=='apk-digest-mismatch','next_normal_seq_must_exceed':args.seq,
            'owner_debug_crash_material':False,'commit_arm_v2_verified':False,
            'scope':'Fixed signed semantic refusal material only; normal release/origin and complete device runner remain unchanged'}
    if args.reviewed_sha256 is not None:
        require(args.reviewed_sha256==reviewed,'Inputs or fixed negative plan changed after review')
    if not args.execute:
        return plan
    require(args.reviewed_sha256 is not None,'Execute requires the reviewed input fingerprint')
    # Re-read public inputs before creating output, still before private access.
    require(_fingerprint(args,_capture(args,source_root,tools),base,output)==reviewed,'Public source changed before signing')
    create_directory(output)
    exclusive_write(output/value['apk_name'],value['files'][value['apk_name']])
    key = private_file(outside_repository(args.private_key))
    password = read_password(args.password_fd)
    environment = secret_environment(password);password=None
    try:
        with tempfile.TemporaryDirectory(prefix='thinkos-owner-negative-sign-') as name:
            temporary = Path(name).resolve()/value['catalog_name'];exclusive_write(temporary,raw)
            signature = release.sign(temporary,key,value['trust']['keys']['channel'][args.slot],environment)
            release.verify(temporary,signature,list(value['trust']['keys']['channel'].values()))
        require(_fingerprint(args,_capture(args,source_root,tools),base,output)==reviewed,'Public source changed while signing')
        require({p.name for p in output.iterdir()}=={value['apk_name']},'Unexpected experimental output content appeared')
        with tempfile.TemporaryDirectory(prefix='thinkos-negative-output-check-') as name:
            copied = _snapshot_file(output/value['apk_name'],Path(name).resolve()/'copy.apk',ota_lan.MAX_APK)
            require(digest(copied)==value['hashes'][value['apk_name']],
                    'Copied experimental APK changed before catalog commit')
        exclusive_write(output/(value['catalog_name']+'.sig'),signature)
        exclusive_write(output/value['catalog_name'],raw)  # Catalog is committed last.
    finally:
        environment.pop(PRIVATE_PASSWORD_ENV,None)
    plan.update(stage='prepared',executed=True,output_sha256={p.name:digest(p) for p in output.iterdir()})
    return plan


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('--lan-dir',type=Path,required=True)
    result.add_argument('--lan-result',type=Path,required=True)
    result.add_argument('--public-dir',type=Path,required=True)
    result.add_argument('--lan-root',required=True)
    result.add_argument('--output',type=Path,required=True)
    result.add_argument('--case',choices=CASES,required=True)
    result.add_argument('--seq',type=int,required=True)
    previous = result.add_mutually_exclusive_group(required=True)
    previous.add_argument('--initial',action='store_true')
    previous.add_argument('--previous-catalog',type=Path)
    result.add_argument('--private-key',type=Path,required=True)
    result.add_argument('--slot',choices=('active','backup'),default='active')
    result.add_argument('--password-fd',type=int)
    result.add_argument('--reviewed-sha256')
    result.add_argument('--execute',action='store_true')
    return result


def main(argv=None,*,source_root=ROOT,tools=None):
    print(json_bytes(prepare(parser().parse_args(argv),source_root=source_root,tools=tools)).decode(),end='')
    return 0


if __name__=='__main__':
    try:
        sys.exit(main())
    except (ValueError,OSError,RuntimeError,KeyError,TypeError,zipfile.BadZipFile,UnicodeError) as error:
        print('Semantic negative preparation stopped: '+type(error).__name__,file=sys.stderr)
        sys.exit(1)
