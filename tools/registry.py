#!/usr/bin/env python3
"""MIT. Local registry validation/generation and owner-host signatures; no remote write actions."""
import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time

import package_plugins as packages
import registry_sources as sources
from release_tools import (ROOT, digest, distribution_base, exclusive_write, json_bytes, no_symlink_path,
                           outside_repository, private_file, read_password, regular, require_owner_host,
                           secret_environment, validate_keyring, PRIVATE_PASSWORD_ENV)
from release import sign as owner_sign, verify as verify_signature


def document(path,limit=1024*1024): return sources.read_json(regular(path,limit).read_bytes(),limit)


def validate_catalog(value):
    spec=importlib.util.spec_from_file_location('registry_catalog_validator',ROOT/'shared/gen/schema_tools.py')
    validator=importlib.util.module_from_spec(spec);spec.loader.exec_module(validator)
    validator.SCHEMAS={path.name:json.loads(path.read_text()) for path in (ROOT/'shared/schema/registry').glob('*.json')}
    validator.validate(value,document='catalog.json')
    if value['expires']<=value['generated'] or len({item['id'] for item in value['plugins']})!=len(value['plugins']):
        raise ValueError('Invalid registry dates/duplicate IDs')
    for base in value['blob_bases']: distribution_base(base)
    for plugin in value['plugins']:
        if plugin['license'] not in sources.SPDX_REDISTRIBUTABLE: raise ValueError('Unsupported redistribution license')
        if len({item['version'] for item in plugin['releases']})!=len(plugin['releases']): raise ValueError('Duplicate release versions')
        for release in plugin['releases']:
            if set(release['why'])!=set(release['permissions']): raise ValueError('Permission explanation mismatch')
    return value


def semver(value):
    match=re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([A-Za-z0-9.-]+))?',value)
    if not match: raise ValueError('Version is not semantic')
    parts=tuple(int(match[i]) for i in (1,2,3));pre=match[4]
    suffix=tuple((0,int(item)) if item.isdigit() else (1,item) for item in pre.split('.')) if pre else ()
    return (*parts,1 if pre is None else 0,suffix)


def write(path,raw):
    path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists():
        if regular(path).read_bytes()!=raw: raise ValueError('Content-addressed output collision')
    else: exclusive_write(path,raw)


def blob_path(root,sha):
    if not re.fullmatch('[a-f0-9]{64}',sha): raise ValueError('Invalid blob hash')
    return no_symlink_path(root/'blobs'/sha[:2]/sha)


def check_index(value):
    packages.validate(value,document='index.json')
    files=value['files']
    if files!=sorted(files,key=lambda item:item['path'].encode('utf-8')) or len({item['path'] for item in files})!=len(files):
        raise ValueError('Unsorted/duplicate plugin file paths')
    total=0
    for item in files:
        packages.safe_relative(item['path'])
        if not packages.allowed_file(item['path']): raise ValueError('Unsupported distributed plugin file')
        total+=item['size']
    canonical=b''.join((item['path']+'\0'+item['sha256']+'\n').encode() for item in files)
    if hashlib.sha256(canonical).hexdigest()!=value['digest'] or total>packages.MAX_PACKAGE_BYTES:
        raise ValueError('Plugin file digest/size mismatch')
    return value


def put_candidate(output,candidate):
    index=candidate['index']
    for sha,raw in candidate['blobs'].items():
        if hashlib.sha256(raw).hexdigest()!=sha: raise ValueError('Blob hash mismatch')
        write(blob_path(output,sha),raw)
    write(output/'registry/files'/(index['digest']+'.json'),packages.index_bytes(index))


def retention(output,previous,catalog):
    """Copy only the live three-version file manifests and referenced immutable blobs."""
    live=set();manifests=set()
    for plugin in catalog['plugins']:
        live.update(plugin.get('screenshots',[]))
        if plugin.get('icon'): live.add(plugin['icon'])
        for release in plugin['releases']:
            manifest=output/'registry/files'/(release['digest']+'.json')
            if not manifest.exists():
                if previous is None: raise ValueError('Previous release manifest is unavailable')
                write(manifest,regular(previous/'registry/files'/manifest.name,65536).read_bytes())
            index=check_index(document(manifest,65536))
            if index['id']!=plugin['id'] or index['version']!=release['version'] or index['digest']!=release['digest'] or sum(item['size'] for item in index['files'])!=release['size']:
                raise ValueError('Catalog release differs from its immutable file manifest')
            manifests.add(manifest.name);live.update(item['sha256'] for item in index['files'])
    for sha in live:
        destination=blob_path(output,sha)
        if not destination.exists():
            if previous is None: raise ValueError('Referenced source blob is unavailable')
            write(destination,regular(blob_path(previous,sha),packages.MAX_FILE_BYTES).read_bytes())
        if digest(destination)!=sha: raise ValueError('Retained source blob hash mismatch')
    for path in (output/'blobs').glob('*/*'):
        if path.name not in live: path.unlink()
    for path in (output/'registry/files').glob('*.json'):
        if path.name not in manifests: path.unlink()


def previous_state(directory,public_keys):
    if directory is None: return {'plugins':[],'revoked':[],'seq':0},{'schema':1,'tags':{}}
    directory=no_symlink_path(directory)
    if public_keys is None: raise ValueError('A previous published directory requires trusted public keys')
    keys=validate_keyring(document(public_keys,65536))
    for name in ('index.json','tag-ledger.json'):
        path=directory/'registry'/name
        verify_signature(path,regular(Path(str(path)+'.sig'),1024).read_bytes(),list(keys['registry'].values()))
    catalog=validate_catalog(document(directory/'registry/index.json'))
    ledger=validate_ledger(document(directory/'registry/tag-ledger.json'))
    return catalog,ledger


def validate_ledger(value):
    if not isinstance(value,dict) or set(value)!={'schema','tags'} or value['schema']!=1 or not isinstance(value['tags'],dict):
        raise ValueError('Invalid authenticated tag ledger')
    count=0
    for identifier,tags in value['tags'].items():
        if not sources.ID.fullmatch(identifier) or not isinstance(tags,dict): raise ValueError('Invalid ledger plugin identity')
        for tag,commit in tags.items():
            if not isinstance(tag,str) or not re.fullmatch(r'(?:[a-z][a-z0-9-]{0,63}/)?v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?',tag) or not isinstance(commit,str) or not re.fullmatch('[a-f0-9]{40}',commit):
                raise ValueError('Invalid ledger tag/commit')
            count+=1
    if count>4096: raise ValueError('Tag ledger exceeds its bound')
    return value


def revalidate_distribution(directory,catalog,allowlist=None,luac=None):
    """Owner signing never relies on a CI success flag or an untrusted artifact name."""
    trusted=document(allowlist or ROOT/'registry/main/official-allowlist.json',65536)
    official={item['id']:item for item in trusted['plugins']}
    with tempfile.TemporaryDirectory(prefix='thinkos-owner-registry-validation-') as name:
        work=Path(name);compiler=sources.trusted_luac(work,luac)
        for plugin in catalog['plugins']:
            if plugin['id'] in sources.RESERVED:
                approved=official.get(plugin['id'])
                if plugin['tier']!='official' or not approved or approved['repo']!=plugin['repo']:
                    raise ValueError('Owner catalog uses an unapproved factory identity')
            for release in plugin['releases']:
                with tempfile.TemporaryDirectory(dir=work,prefix='bundle-') as bundle:
                    source=Path(bundle)
                    index=check_index(document(directory/'registry/files'/(release['digest']+'.json'),65536))
                    for item in index['files']:
                        destination=source/item['path'];destination.parent.mkdir(parents=True,exist_ok=True)
                        destination.write_bytes(regular(blob_path(directory,item['sha256']),packages.MAX_FILE_BYTES).read_bytes())
                    manifest,files=packages.validate_plugin(source)
                    if manifest['id']!=plugin['id'] or manifest['version']!=release['version'] or manifest['api']!=release['api']:
                        raise ValueError('Signed metadata differs from the actual plugin manifest')
                    if set(manifest.get('permissions',[]))!=set(release['permissions']) or any(page.get('roles') for page in manifest.get('pages',[])):
                        raise ValueError('Signed plugin permissions/roles are inconsistent')
                    if any(permission.startswith('system.') for permission in manifest.get('permissions',[])):
                        raise ValueError('Registry cannot grant system capabilities')
                    if packages.file_index(manifest,files)!=index:
                        raise ValueError('Distributed bundle differs from its checked file manifest')
                    if [item['id'] for item in manifest.get('apps',[])]!=release['apps']:
                        raise ValueError('Signed app entries differ from the actual manifest')
                    for path,file in files:
                        if path.endswith('.lua'): sources.command([compiler,'-p',file],limit=4096,timeout=10)


def approval_matches(approvals,identifier,release):
    return any(item=={'id':identifier,'version':release['version'],'commit':release['commit'],'digest':release['digest'],
                     'permissions':sorted(release['permissions'])} for item in approvals)


def build(args):
    output=no_symlink_path(args.output);output.mkdir()
    previous,ledger=previous_state(args.previous,args.keys)
    if not previous['seq']<args.seq<=9007199254740991: raise ValueError('Registry sequence must increase')
    bases=[distribution_base(value)+'/' for value in args.blob_base]
    if len(set(bases))!=len(bases) or not 1<=len(bases)<=8: raise ValueError('Invalid blob bases')
    plan=document(args.sources,65536)
    if not isinstance(plan,list) or len(plan)>256: raise ValueError('Invalid source plan')
    approvals=document(args.approvals,65536) if args.approvals else []
    if not isinstance(approvals,list): raise ValueError('Approvals must be a reviewable list')
    plugins={item['id']:copy.deepcopy(item) for item in previous['plugins']};requests=[];seen=set()
    with tempfile.TemporaryDirectory(prefix='thinkos-registry-data-') as name:
        work=Path(name);hooks=work/'empty-hooks';hooks.mkdir()
        compiler=sources.trusted_luac(work,args.luac)
        for number,item in enumerate(plan):
            if not isinstance(item,dict) or set(item) not in ({'registration','git_dir','tag'}, {'registration','git_dir','tag','kind'}):
                raise ValueError('Source plans contain registration/git_dir/tag and optional topic-community kind only')
            community=item.get('kind')=='topic-community'
            if 'kind' in item and not community: raise ValueError('Unsupported source kind')
            record=sources.registration(Path(item['registration']),None if community else args.official_allowlist)
            if community:
                record['tier']='community'
                prior=plugins.get(record['id'])
                if prior and (prior['tier']!='community' or prior['repo'].casefold()!=record['repo'].casefold()):
                    raise ValueError('Community source cannot replace an occupied registry identity')
            reader=sources.BareReader(Path(item['git_dir']),hooks)
            observed_commit=reader.commit(item['tag'],record['tag_prefix'])
            tags=ledger['tags'].setdefault(record['id'],{})
            expected_commit=tags.get(item['tag'])
            if expected_commit and expected_commit!=observed_commit:
                requests.append({'kind':'tag_moved','requested_action':'issue','id':record['id'],'tag':item['tag'],
                                 'expected_commit':expected_commit,'observed_commit':observed_commit,'remote_action_performed':False})
                continue
            staging=work/str(number);staging.mkdir()
            candidate=sources.validate_source(record,reader,item['tag'],compiler,staging)
            release=candidate['release'];identifier=record['id'];tag=candidate['tag']
            subject=(identifier,tag)
            if subject in seen: raise ValueError('Duplicate source/tag in this build')
            seen.add(subject)
            old=plugins.get(identifier);tags=ledger['tags'].setdefault(identifier,{})
            before=tags.get(tag)
            if before and before!=release['commit']:
                requests.append({'kind':'tag_moved','requested_action':'issue','id':identifier,'tag':tag,
                                 'expected_commit':before,'observed_commit':release['commit'],'remote_action_performed':False})
                continue
            tags[tag]=release['commit']
            matching=next((entry for entry in old['releases'] if entry['version']==release['version']),None) if old else None
            if matching:
                if matching['commit']!=release['commit'] or matching['digest']!=release['digest']:
                    requests.append({'kind':'published_content_changed','requested_action':'issue','id':identifier,'tag':tag,'remote_action_performed':False})
                continue
            old_permissions=set(old['releases'][0]['permissions']) if old else set()
            if old and set(release['permissions'])-old_permissions and not approval_matches(approvals,identifier,release):
                requests.append({'kind':'permissions_increased','requested_action':'pull_request','id':identifier,'tag':tag,
                                 'commit':release['commit'],'digest':release['digest'],'permissions':sorted(release['permissions']),
                                 'added':sorted(set(release['permissions'])-old_permissions),'remote_action_performed':False})
                continue
            releases=([*old['releases'],release] if old else [release])
            releases.sort(key=lambda value:semver(value['version']),reverse=True)
            metadata=candidate['metadata'] if not old or semver(release['version'])>=semver(old['releases'][0]['version']) else {key:value for key,value in old.items() if key!='releases'}
            plugins[identifier]={**metadata,'releases':releases[:3]}
            put_candidate(output,candidate)
    if sum(len(tags) for tags in ledger['tags'].values())>4096: raise ValueError('Tag ledger exceeds its review bound')
    now=int(time.time()*1000)
    revoked=document(args.revocations,131072) if args.revocations else previous['revoked']
    catalog=validate_catalog({'schema':1,'seq':args.seq,'generated':now,'expires':now+args.expires_days*86400000,
                              'blob_bases':bases,'revoked':revoked,'plugins':sorted(plugins.values(),key=lambda item:item['id'])})
    retention(output,args.previous,catalog)
    write(output/'registry/index.json',json_bytes(catalog))
    write(output/'registry/tag-ledger.json',json_bytes(ledger))
    write(output/'review-requests.json',json_bytes({'schema':1,'requests':requests,'remote_actions_performed':False}))
    write(output/'build-report.json',json_bytes({'schema':1,'stage':'unsigned','seq':args.seq,'plugins':len(catalog['plugins']),
                                              'review_requests':len(requests),'published':False,'signed':False}))
    return catalog


def sign(args):
    require_owner_host()
    args.directory=no_symlink_path(args.directory)
    keys=validate_keyring(document(args.keys,65536));key=private_file(outside_repository(args.private_key))
    catalog_path=args.directory/'registry/index.json';catalog=validate_catalog(document(catalog_path))
    # Recheck content addressing immediately before owner signing, even for CI-produced inputs.
    retention(args.directory,None,catalog)
    revalidate_distribution(args.directory,catalog,getattr(args,'official_allowlist',None),getattr(args,'luac',None))
    previous,_=previous_state(args.previous,args.keys)
    if catalog['seq']<=previous['seq']: raise ValueError('Cannot sign a non-increasing registry sequence')
    ledger=args.directory/'registry/tag-ledger.json'
    validate_ledger(document(ledger))
    password=read_password(args.password_fd);environment=secret_environment(password);password=None
    try:
        for path in (catalog_path,ledger):
            signature=owner_sign(path,key,keys['registry'][args.slot],environment)
            exclusive_write(Path(str(path)+'.sig'),signature)
        write(args.directory/'owner-signature-report.json',json_bytes({'schema':1,'signed':True,'published':False,
              'key_id':keys['registry'][args.slot]['id'],'catalog_sha256':digest(catalog_path),'seq':catalog['seq']}))
    finally: environment.pop(PRIVATE_PASSWORD_ENV,None)


def parser():
    result=argparse.ArgumentParser(description=__doc__);sub=result.add_subparsers(dest='action',required=True)
    fetch=sub.add_parser('fetch');fetch.add_argument('--registration',type=Path,required=True);fetch.add_argument('--cache',type=Path,required=True)
    fetch.add_argument('--official-allowlist',type=Path)
    generate=sub.add_parser('build')
    generate.add_argument('--sources',type=Path,required=True);generate.add_argument('--output',type=Path,required=True)
    generate.add_argument('--seq',type=int,required=True);generate.add_argument('--blob-base',action='append',required=True)
    generate.add_argument('--previous',type=Path);generate.add_argument('--keys',type=Path)
    generate.add_argument('--official-allowlist',type=Path);generate.add_argument('--approvals',type=Path);generate.add_argument('--revocations',type=Path)
    generate.add_argument('--luac',type=Path);generate.add_argument('--expires-days',type=int,choices=range(1,366),default=30,metavar='1..365')
    signing=sub.add_parser('sign');signing.add_argument('--directory',type=Path,required=True);signing.add_argument('--keys',type=Path,required=True)
    signing.add_argument('--private-key',type=Path,required=True);signing.add_argument('--slot',choices=('active','backup'),default='active')
    signing.add_argument('--previous',type=Path);signing.add_argument('--password-fd',type=int)
    signing.add_argument('--official-allowlist',type=Path);signing.add_argument('--luac',type=Path)
    return result


def main(argv=None):
    args=parser().parse_args(argv)
    if args.action=='fetch': sources.fetch(sources.registration(args.registration,args.official_allowlist),args.cache)
    elif args.action=='build': build(args)
    else: sign(args)
    print(json.dumps({'action':args.action,'published':False,'remote_actions_performed':False}))


if __name__=='__main__':
    try: main()
    except (ValueError,OSError,KeyError,TypeError) as error:
        print('Registry operation stopped: '+type(error).__name__,file=sys.stderr);sys.exit(1)
