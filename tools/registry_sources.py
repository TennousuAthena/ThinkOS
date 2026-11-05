"""MIT. Read author Git objects as bounded data; never check out or run author code."""
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import selectors
import subprocess
import tempfile
import time

import package_plugins as packages
from release_tools import ROOT, json_bytes, no_symlink_path, regular

SPDX_REDISTRIBUTABLE = {
    'MIT', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC', '0BSD', 'MPL-2.0', 'CC0-1.0', 'Unlicense',
    'GPL-3.0-only', 'GPL-3.0-or-later', 'LGPL-2.1-only', 'LGPL-2.1-or-later',
    'LGPL-3.0-only', 'LGPL-3.0-or-later', 'AGPL-3.0-only', 'AGPL-3.0-or-later',
}
LOCALES={'zh-Hans','zh-Hant','en','es','fr','pt','ru','ar','hi','bn','ur'}
RESERVED = {path.name for path in (ROOT/'plugins').iterdir() if (path/'manifest.json').is_file()} | {'system','welcome','hub'}
ID = re.compile(r'[a-z][a-z0-9-]{0,63}')
REPO = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}')


def read_json(raw, limit=65536):
    if len(raw)>limit:
        raise ValueError('Registry metadata exceeds its size limit')
    def pairs(items):
        result={}
        for key,value in items:
            if key in result: raise ValueError('Duplicate metadata member')
            result[key]=value
        return result
    def invalid_float(_): raise ValueError('Registry metadata numbers must use integer tokens')
    return json.loads(raw.decode('utf-8'),object_pairs_hook=pairs,parse_float=invalid_float,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Invalid JSON number')))


def local(value,maximum=256):
    if not isinstance(value,dict) or not value or set(value)-LOCALES:
        raise ValueError('Localized metadata must use the supported locale keys')
    if any(not isinstance(text,str) or not text.strip() or len(text.encode('utf-16-le'))//2>maximum or '\x00' in text for text in value.values()):
        raise ValueError('Localized metadata is empty/too large')
    return value


def registration(path,official_allowlist=None):
    path=regular(path,65536)
    identifier=path.stem
    if not ID.fullmatch(identifier): raise ValueError('Registration filename is not a plugin ID')
    value=read_json(path.read_bytes())
    if not isinstance(value,dict) or set(value)!={'repo','path','owners'} or not isinstance(value['repo'],str) or not REPO.fullmatch(value['repo']):
        raise ValueError('Registrations contain only repo/path/owners; authors cannot assign a tier')
    if not isinstance(value['path'],str): raise ValueError('Registration path must be text')
    if value['path']: packages.safe_relative(value['path'])
    owners=value['owners']
    if not isinstance(owners,list) or not 1<=len(owners)<=8 or any(not isinstance(owner,str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,38}',owner) for owner in owners) or len(set(owners))!=len(owners):
        raise ValueError('Invalid registration owners')
    official=False;prefix=''
    if official_allowlist:
        trusted=read_json(regular(official_allowlist,65536).read_bytes())
        if set(trusted)!={'schema','plugins'} or trusted['schema']!=1: raise ValueError('Invalid official allowlist')
        for item in trusted['plugins']:
            if item.get('id')==identifier and {key:item.get(key) for key in ('repo','path','owners')}==value:
                official=True;prefix=item.get('tag_prefix','');break
    if identifier in RESERVED and not official: raise ValueError('Factory plugin IDs are reserved')
    if prefix and not re.fullmatch(r'[a-z][a-z0-9-]{0,63}/',prefix): raise ValueError('Invalid official tag prefix')
    return {'id':identifier,**value,'tier':'official' if official else 'reviewed','tag_prefix':prefix}


def git_environment():
    environment={key:value for key,value in os.environ.items() if not key.startswith('GIT_')}
    environment.update(GIT_CONFIG_NOSYSTEM='1',GIT_CONFIG_SYSTEM='/dev/null',GIT_CONFIG_GLOBAL='/dev/null',
                       GIT_ALLOW_PROTOCOL='https',GIT_TERMINAL_PROMPT='0',GIT_NO_LAZY_FETCH='1')
    return environment


def child_limits():
    resource.setrlimit(resource.RLIMIT_FSIZE,(128*1024*1024,128*1024*1024))
    resource.setrlimit(resource.RLIMIT_CPU,(30,30))


def command(arguments,limit=1024*1024,timeout=60,cwd=None):
    with tempfile.TemporaryFile() as errors:
        process=subprocess.Popen([str(item) for item in arguments],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,
                                 stderr=errors,env=git_environment(),cwd=cwd,preexec_fn=child_limits)
        result=bytearray();deadline=time.monotonic()+timeout
        selector=selectors.DefaultSelector();selector.register(process.stdout,selectors.EVENT_READ)
        try:
            while True:
                remaining=deadline-time.monotonic()
                if remaining<=0 or not selector.select(remaining): raise ValueError('Author data operation exceeded its deadline')
                block=os.read(process.stdout.fileno(),min(65536,limit+1-len(result)))
                if not block: break
                result.extend(block)
                if len(result)>limit: raise ValueError('Author data operation exceeded its output bound')
            try:
                failed=process.wait(timeout=max(.1,deadline-time.monotonic()))
            except subprocess.TimeoutExpired as error:
                raise ValueError('Author data operation exceeded its deadline') from error
            if failed: raise ValueError('Author data operation failed')
            return bytes(result)
        finally:
            selector.close();process.stdout.close()
            if process.poll() is None: process.kill();process.wait()


class BareReader:
    def __init__(self,git_dir,hooks):
        self.directory=no_symlink_path(Path(git_dir))
        self.hooks=Path(hooks)
        if not self.hooks.is_dir() or any(self.hooks.iterdir()): raise ValueError('Git hook directory must be empty')
        self.base=['git','--no-pager','-c','core.hooksPath='+str(self.hooks),'-c','core.fsmonitor=false',
                   '-c','gc.auto=0','-c','maintenance.auto=false','-c','protocol.allow=never',
                   '-c','protocol.https.allow=always','--git-dir='+str(self.directory)]
        if self.git('rev-parse','--is-bare-repository').strip()!=b'true': raise ValueError('Author repository must be bare')

    def git(self,*arguments,limit=1024*1024): return command(self.base+list(arguments),limit=limit)

    def commit(self,tag,prefix=''):
        if not re.fullmatch(re.escape(prefix)+r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?',tag):
            raise ValueError('Tag must identify the exact plugin semantic version')
        value=self.git('rev-parse','--verify','refs/tags/'+tag+'^{commit}').decode('ascii').strip()
        if not re.fullmatch('[a-f0-9]{40}',value): raise ValueError('Expected a full Git SHA-1 commit')
        return value

    def blob(self,oid,limit):
        if not re.fullmatch('[a-f0-9]{40}',oid): raise ValueError('Invalid Git object ID')
        size=int(self.git('cat-file','-s',oid,limit=128).strip())
        if size>limit: raise ValueError('Author blob exceeds its file limit')
        raw=self.git('cat-file','blob',oid,limit=limit)
        if len(raw)!=size: raise ValueError('Git blob size changed')
        return raw

    def licenses(self,commit):
        result={}
        for entry in self.git('ls-tree','-z',commit,'--',*sorted(packages.LEGAL_FILES),limit=8192).split(b'\x00'):
            if not entry: continue
            header,path=entry.split(b'\t',1);mode,kind,oid=header.decode('ascii').split();name=path.decode('utf-8')
            if name not in packages.LEGAL_FILES or mode not in ('100644','100755') or kind!='blob':
                raise ValueError('Root license/notice must be a regular file')
            result[name]=oid
        return result

    def tree(self,commit,prefix,max_entries=512):
        if not re.fullmatch('[a-f0-9]{40}',commit): raise ValueError('Invalid commit')
        arguments=['ls-tree','-r','-z',commit]
        if prefix: arguments+=['--',prefix+'/']
        entries=self.git(*arguments,limit=min(32*1024*1024,max_entries*2048)).split(b'\x00')
        if len(entries)>max_entries+1: raise ValueError('Git subtree has too many entries')
        result={};folded={}
        for entry in entries:
            if not entry: continue
            header,name=entry.split(b'\t',1);mode,kind,oid=header.decode('ascii').split()
            full=name.decode('utf-8')
            if prefix and not full.startswith(prefix+'/'): raise ValueError('Git returned a path outside the plugin subtree')
            relative=full[len(prefix)+1:] if prefix else full
            packages.safe_relative(relative)
            if mode not in ('100644','100755') or kind!='blob': raise ValueError('Symlinks, submodules and non-file objects are forbidden')
            parts=relative.split('/')
            for end in range(1,len(parts)+1):
                path='/'.join(parts[:end]);key=path.casefold()
                if key in folded and folded[key]!=path: raise ValueError('Case-colliding author paths')
                folded[key]=path
            result[relative]=oid
        return result


def fetch(registration_value,cache):
    """Explicit host fetch only. No checkout, author URLs, credentials, hooks or protocol fallback."""
    cache=no_symlink_path(Path(cache))
    cache.mkdir(mode=0o700)
    with tempfile.TemporaryDirectory(prefix='thinkos-registry-hooks-') as name:
        hooks=Path(name)
        command(['git','-c','init.templateDir=','init','--bare','--template='+str(hooks),str(cache)])
        reader=BareReader(cache,hooks)
        url='https://github.com/'+registration_value['repo']+'.git'
        reader.git('fetch','--no-recurse-submodules','--no-tags',url,'+refs/tags/*:refs/tags/*',limit=65536)
    return cache


def trusted_luac(directory,supplied=None):
    if supplied:
        compiler=Path(supplied).resolve()
    else:
        cpp=ROOT/'android/app/src/main/cpp'
        compiler=Path(directory)/'luac54'
        sources=[path for path in sorted((cpp/'lua').glob('*.c')) if path.name not in ('lua.c','luac.c')]
        command(['clang','-O2','-I'+str(cpp/'lua'),cpp/'lua/luac.c',ROOT/'android/app/src/testNative/feedback_cli.c',
                 *sources,'-lm','-o',compiler],limit=65536,timeout=60)
    # Lua 5.5 syntax must not accidentally be accepted for the device's 5.4 runtime.
    output=command([compiler,'-v'],limit=4096)
    if b'Lua 5.4' not in output: raise ValueError('Use the trusted Lua 5.4 syntax compiler')
    return compiler


def validate_source(record,reader,tag,compiler,work):
    commit=reader.commit(tag,record['tag_prefix'])
    entries=reader.tree(commit,record['path'])
    if 'manifest.json' not in entries or 'store.json' not in entries: raise ValueError('Plugin manifest and store.json are required')
    store=read_json(reader.blob(entries['store.json'],65536))
    allowed={'name','summary','author','license','tags','why','icon','screenshots'}
    if not isinstance(store,dict) or set(store)-allowed or not {'name','summary','author','license','why'}<=set(store):
        raise ValueError('Invalid store metadata')
    local(store['name'],80);local(store['summary'])
    if not isinstance(store['author'],str) or not 1<=len(store['author'])<=80 or not isinstance(store['license'],str) or store['license'] not in SPDX_REDISTRIBUTABLE:
        raise ValueError('A redistributable SPDX license and author are required')
    tags=store.get('tags',[])
    if not isinstance(tags,list) or len(tags)>16 or any(not isinstance(item,str) or not re.fullmatch('[a-z][a-z0-9-]{0,39}',item) for item in tags) or len(set(tags))!=len(tags):
        raise ValueError('Invalid store tags')
    source=Path(work)/record['id'];source.mkdir()
    blobs={}
    for path,oid in entries.items():
        if not packages.allowed_file(path): continue
        limit=packages.MAX_JSON_SCRIPT_BYTES if path.endswith(('.json','.lua')) else packages.MAX_FILE_BYTES
        raw=reader.blob(oid,limit)
        if path.endswith('.lua'):
            raw.decode('utf-8')
            if b'\x00' in raw or raw.startswith(b'\x1b'): raise ValueError('Lua must be text, never bytecode')
        destination=source/path;destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(raw)
    license_files={'LICENSE','LICENSE.txt','LICENSE.md','COPYING'}
    if not any((source/name).is_file() for name in license_files):
        # Monorepo plugins inherit the original repository license, byte-for-byte.
        root=reader.licenses(commit)
        found=next((name for name in sorted(license_files) if name in root),None)
        if not found: raise ValueError('Original redistribution license/notice text is required')
        (source/found).write_bytes(reader.blob(root[found],packages.MAX_FILE_BYTES))
    for path in license_files:
        file=source/path
        if file.is_file() and not file.read_bytes().strip(): raise ValueError('License text cannot be empty')
    manifest,files=packages.validate_plugin(source)
    if manifest['id']!=record['id'] or tag!=record['tag_prefix']+'v'+manifest['version']:
        raise ValueError('ID or tag/version does not match its registration')
    permissions=manifest.get('permissions',[])
    if any(permission.startswith('system.') for permission in permissions) or any(page.get('roles') for page in manifest.get('pages',[])):
        raise ValueError('Registry plugins cannot declare system permissions or any roles')
    if not isinstance(store['why'],dict) or set(store['why'])!=set(permissions): raise ValueError('Every declared permission needs an explanation')
    for why in store['why'].values(): local(why)
    for path,file in files:
        if path.endswith('.lua'): command([compiler,'-p',file],limit=4096,timeout=10)
    index=packages.file_index(manifest,files)
    for item in index['files']: blobs[item['sha256']]=(source/item['path']).read_bytes()
    metadata={key:store[key] for key in ('name','summary','author','license')}
    metadata.update(id=record['id'],tier=record['tier'],repo=record['repo'],tags=tags)
    for field in ('icon','screenshots'):
        paths=([store[field]] if field=='icon' else store[field]) if field in store else []
        if not isinstance(paths,list) or len(paths)>(1 if field=='icon' else 8): raise ValueError('Too many store images')
        hashes=[]
        for path in paths:
            if not isinstance(path,str): raise ValueError('Store image paths must be text')
            packages.safe_relative(path)
            if not path.startswith('store/') or not path.lower().endswith(('.png','.jpg','.jpeg','.webp')) or path not in entries:
                raise ValueError('Store images must be regular image files inside store/')
            raw=reader.blob(entries[path],packages.MAX_FILE_BYTES)
            if not (raw.startswith(b'\x89PNG\r\n\x1a\n') or raw.startswith(b'\xff\xd8\xff') or raw.startswith(b'RIFF') and raw[8:12]==b'WEBP'):
                raise ValueError('Unsupported store image data')
            sha=hashlib.sha256(raw).hexdigest();blobs[sha]=raw;hashes.append(sha)
        if hashes: metadata[field]=hashes[0] if field=='icon' else hashes
    release={'version':manifest['version'],'commit':commit,'digest':index['digest'],'api':manifest['api'],
             'size':sum(item['size'] for item in index['files']),'permissions':permissions,'why':store['why'],
             'apps':[item['id'] for item in manifest.get('apps',[])]}
    return {'metadata':metadata,'release':release,'index':index,'blobs':blobs,'tag':tag}
