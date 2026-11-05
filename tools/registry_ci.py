#!/usr/bin/env python3
"""MIT. Keyless registry CI: author objects remain data; outputs unsigned materials/review requests."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import tempfile

import registry as catalog
import registry_sources as sources
import registry_discovery as discovery
import os
from release_tools import ROOT, exclusive_write, json_bytes, no_symlink_path


def fetch_ref(repository,cache,ref,hooks):
    if not sources.REPO.fullmatch(repository): raise ValueError('Invalid registry repository')
    sources.command(['git','-c','init.templateDir=','init','--bare','--template='+str(hooks),cache])
    reader=sources.BareReader(cache,hooks)
    url='https://github.com/'+repository+'.git'
    reader.git('fetch','--no-recurse-submodules','--no-tags',url,ref,limit=65536)
    commit=reader.git('rev-parse','--verify','FETCH_HEAD^{commit}').decode('ascii').strip()
    if not re.fullmatch('[a-f0-9]{40}',commit): raise ValueError('Expected a SHA-1 registry commit')
    return reader,commit


def previous_distribution(reader,work,keys):
    # A missing or inaccessible dist branch is not silently treated as first use.
    url='https://github.com/'+work['repository']+'.git'
    reader.git('fetch','--no-recurse-submodules','--no-tags',url,'refs/heads/dist',limit=65536)
    commit=reader.git('rev-parse','FETCH_HEAD^{commit}').decode('ascii').strip()
    previous=work['root']/'previous';previous.mkdir()
    entries=reader.tree(commit,'',max_entries=100000)
    def extract(path,limit):
        if path not in entries: raise ValueError('Authenticated distribution references missing content')
        destination=previous/path;destination.parent.mkdir(parents=True,exist_ok=True)
        exclusive_write(destination,reader.blob(entries[path],limit))
    for path in ('registry/index.json','registry/tag-ledger.json','registry/index.json.sig','registry/tag-ledger.json.sig'):
        extract(path,1024 if path.endswith('.sig') else 1024*1024)
    authenticated,_=catalog.previous_state(previous,keys)
    live=set()
    for plugin in authenticated['plugins']:
        live.update(plugin.get('screenshots',[]))
        if plugin.get('icon'): live.add(plugin['icon'])
        for release in plugin['releases']:
            path='registry/files/'+release['digest']+'.json'
            if not (previous/path).exists(): extract(path,65536)
            index=catalog.check_index(catalog.document(previous/path,65536))
            if index['id']!=plugin['id'] or index['digest']!=release['digest']: raise ValueError('Authenticated file manifest identity mismatch')
            live.update(item['sha256'] for item in index['files'])
    for sha in live:
        path='blobs/'+sha[:2]+'/'+sha
        extract(path,1024*1024)
        if catalog.digest(previous/path)!=sha: raise ValueError('Published blob hash mismatch')
    return previous


def prepare(args):
    if not re.fullmatch('[a-f0-9]{40}',args.commit): raise ValueError('CI input must pin a full registry commit')
    root=no_symlink_path(args.workspace);root.mkdir()
    hooks=root/'empty-hooks';hooks.mkdir()
    reader,commit=fetch_ref(args.repository,root/'registry.git',args.commit,hooks)
    if commit!=args.commit: raise ValueError('Registry commit changed while fetching')
    registrations=root/'registrations';registrations.mkdir()
    entries=reader.tree(commit,'plugins');records=[]
    official=ROOT/'registry/main/official-allowlist.json'
    for path,oid in entries.items():
        if not re.fullmatch(r'[a-z][a-z0-9-]{0,63}\.json',path): raise ValueError('Registry plugins directory contains an unsupported entry')
        destination=registrations/path;exclusive_write(destination,reader.blob(oid,65536))
        records.append((destination,sources.registration(destination,official)))
    previous=None
    if not args.initial:
        previous=previous_distribution(reader,{'repository':args.repository,'root':root},args.keys)
    before,ledger=catalog.previous_state(previous,args.keys)
    caches={};plan=[];unavailable=[]
    for path,record in records:
        repository=record['repo']
        if repository not in caches:
            directory=root/('author-'+hashlib.sha256(repository.encode()).hexdigest())
            sources.fetch(record,directory);caches[repository]=directory
        author=sources.BareReader(caches[repository],hooks)
        tags=author.git('for-each-ref','--format=%(refname:strip=2)','refs/tags',limit=512*1024).decode('utf-8').splitlines()
        prefix=record['tag_prefix']
        names=[tag for tag in tags if re.fullmatch(re.escape(prefix)+r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?',tag)]
        names.sort(key=lambda tag:catalog.semver(tag[len(prefix)+1:]),reverse=True)
        selected=set(names[:3])
        for tag,expected in ledger['tags'].get(record['id'],{}).items():
            if tag in names and author.commit(tag,prefix)!=expected: selected.add(tag)
        if not selected: unavailable.append({'id':record['id'],'reason':'no-matching-version-tags','remote_action_performed':False})
        for tag in sorted(selected): plan.append({'registration':str(path),'git_dir':str(caches[repository]),'tag':tag})
    community_report=None
    compiler=None
    if args.mode=='community':
        reports=root/'reports';reports.mkdir()
        cache=getattr(args,'discovery_cache',None) or root/'topic-cache'
        token_env=getattr(args,'github_token_env',None)
        if token_env and not re.fullmatch(r'[A-Z][A-Z0-9_]{0,63}',token_env): raise ValueError('Invalid authentication environment name')
        found=discovery.discover(cache,getattr(args,'community_max_repos',32),os.environ.get(token_env) if token_env else None)
        exclusive_write(reports/'community-discovery.json',json_bytes(found))
        if not found['complete']: raise ValueError('Public community discovery incomplete; signed history retained')
        compiler=sources.trusted_luac(root)
        try:
            added,community_report=discovery.community_plan(found,root/'community',before,ledger,records,caches,compiler,fetch=sources.fetch)
        except discovery.DiscoveryError as failure:
            exclusive_write(reports/'community-validation.json',json_bytes({'schema':1,'complete':False,'error':failure.code,
                'signed':False,'published':False,'remote_actions_performed':False}))
            raise ValueError('Community validation incomplete; signed history retained') from None
        exclusive_write(reports/'community-validation.json',json_bytes(community_report))
        plan.extend(added)
    if len(plan)>256 or len(json_bytes(plan))>65536: raise ValueError('Combined source plan exceeds its existing build bound')
    source_plan=root/'sources.json';exclusive_write(source_plan,json_bytes(plan))
    reviewed={}
    for filename,limit in (('approvals.json',65536),('revocations.json',131072)):
        raw=reader.git('ls-tree','-z',commit,'--',filename,limit=4096)
        if not raw: continue
        row=raw.removesuffix(b'\x00');header,path=row.split(b'\t',1);mode,kind,oid=header.decode('ascii').split()
        if path.decode('utf-8')!=filename or mode not in ('100644','100755') or kind!='blob':
            raise ValueError('Reviewed policy files must be ordinary JSON data')
        destination=root/filename;exclusive_write(destination,reader.blob(oid,limit))
        reviewed[filename]=destination
    output=root/'output'
    build_args=argparse.Namespace(output=output,previous=previous,keys=args.keys,seq=before['seq']+1,
                                 blob_base=args.blob_base,sources=source_plan,approvals=reviewed.get('approvals.json'),revocations=reviewed.get('revocations.json'),
                                 official_allowlist=official,luac=compiler,expires_days=30)
    catalog.build(build_args)
    exclusive_write(output/'ci-context.json',json_bytes({'schema':1,'mode':args.mode,'registry_commit':commit,
        'historical_checks_performed':not args.initial,'unavailable_sources':unavailable,
        'signed':False,'published':False,'remote_actions_performed':False,
        'community_discovery':community_report,
        'owner_required':'Review materials and requests; sign and publish only on the owner host'}))


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository',required=True);parser.add_argument('--commit',required=True)
    parser.add_argument('--workspace',type=Path,required=True);parser.add_argument('--mode',choices=('validate','scan','unsigned','community'),required=True)
    parser.add_argument('--keys',type=Path,default=ROOT/'android/app/src/main/assets/update/keys.json')
    parser.add_argument('--blob-base',action='append',required=True)
    parser.add_argument('--initial',action='store_true',help='Explicit first-use material generation only; historical checks are not claimed')
    parser.add_argument('--discovery-cache',type=Path,help='Optional persistent sanitized ETag cache, no credentials')
    parser.add_argument('--community-max-repos',type=int,choices=range(1,discovery.REPO_MAX+1),default=32)
    parser.add_argument('--github-token-env',help='Optional bearer-token environment variable NAME only')
    args=parser.parse_args(argv);prepare(args)


if __name__=='__main__':
    try: main()
    except (ValueError,OSError,KeyError,TypeError) as error:
        print('Keyless registry CI stopped: '+type(error).__name__,file=sys.stderr);sys.exit(1)
