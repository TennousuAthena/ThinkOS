"""Registry author-data security tests. Bare fixture objects are never checked out."""
import argparse
import base64
import copy
import importlib.util
import json
import os
import shlex
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
import registry as registry_tool
import registry_sources as source_tool
import release_tools


class RegistrySourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='thinkos registry syntax fixture ')
        cls.compiler=source_tool.trusted_luac(Path(cls.temp.name).resolve())

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='thinkos bare registry fixture ')
        self.work=Path(self.temp.name).resolve();self.hooks=self.work/'empty-hooks';self.hooks.mkdir()
        self.repo=self.work/'source.git'
        self.git('init','--bare','--template='+str(self.hooks),str(self.repo),outside=True)
        self.registration=self.work/'fixture.json'
        self.registration.write_text(json.dumps({'repo':'alice/fixture','path':'','owners':['alice']}))
        self.files={
            'manifest.json':json.dumps({'id':'fixture','name':'Fixture','version':'1.0.0','api':10,'main':'main.lua','permissions':['network'],
                'pages':[{'id':'main','view':'views/main.json','title':'Fixture'}],
                'apps':[{'id':'fixture','page':'main','title':'Fixture'}]}).encode(),
            'main.lua':b'tos.on("page.enter", function() tos.state.set{value="fixture"} end)\n',
            'views/main.json':json.dumps({'layout':'focus','slots':{'hero':{'type':'text','value':'{{value}}','style':'body'}}}).encode(),
            'store.json':json.dumps({'name':{'en':'Fixture'},'summary':{'en':'Data fixture'},'author':'alice','license':'MIT',
                                     'why':{'network':{'en':'Read public data'}},'tags':['utility']}).encode(),
            'LICENSE':(ROOT/'LICENSE').read_bytes(),
        }

    def tearDown(self): self.temp.cleanup()

    def git(self,*args,raw=None,outside=False):
        command=['git','-c','core.hooksPath='+str(self.hooks),'-c','commit.gpgsign=false']
        if not outside: command+=['--git-dir='+str(self.repo)]
        environment=source_tool.git_environment()
        environment.update(GIT_AUTHOR_NAME='Isolated fixture',GIT_AUTHOR_EMAIL='fixture@example.invalid',
                           GIT_COMMITTER_NAME='Isolated fixture',GIT_COMMITTER_EMAIL='fixture@example.invalid')
        return subprocess.run(command+list(args),input=raw,capture_output=True,check=True,env=environment,cwd=self.work).stdout

    def commit(self,files=None,tag='v1.0.0'):
        tree={}
        for path,value in (files or self.files).items():
            parts=path.split('/');folder=tree
            for part in parts[:-1]: folder=folder.setdefault(part,{})
            if isinstance(value,tuple):
                mode,data=value
                if mode=='160000': folder[parts[-1]]=(mode,'commit',data)
                else: folder[parts[-1]]=(mode,'blob',self.git('hash-object','-w','--stdin',raw=data).strip().decode())
            else: folder[parts[-1]]=('100644','blob',self.git('hash-object','-w','--stdin',raw=value).strip().decode())
        def emit(nodes):
            entries=[]
            for name,node in nodes.items():
                mode,kind,oid=('040000','tree',emit(node)) if isinstance(node,dict) else node
                entries.append((mode+' '+kind+' '+oid+'\t'+name+'\0').encode())
            return self.git('mktree','-z','--missing',raw=b''.join(entries)).strip().decode()
        commit=self.git('commit-tree',emit(tree),raw=b'isolated author fixture\n').strip().decode()
        self.git('update-ref','refs/tags/'+tag,commit)
        return commit

    def validate(self,tag='v1.0.0',record=None):
        staging=Path(tempfile.mkdtemp(dir=self.work,prefix='data-'))
        return source_tool.validate_source(record or source_tool.registration(self.registration),
            source_tool.BareReader(self.repo,self.hooks),tag,self.compiler,staging)

    def metadata(self,**patch):
        value=json.loads(self.files['store.json']);value.update(patch);self.files['store.json']=json.dumps(value).encode()

    def manifest(self,**patch):
        value=json.loads(self.files['manifest.json']);value.update(patch);self.files['manifest.json']=json.dumps(value).encode()

    def test_valid_bare_plugin_includes_original_license_but_not_store_data(self):
        commit=self.commit();result=self.validate()
        self.assertEqual(commit,result['release']['commit'])
        paths={item['path'] for item in result['index']['files']}
        self.assertIn('LICENSE',paths);self.assertNotIn('store.json',paths)
        self.assertEqual(['network'],result['release']['permissions'])
        for sha,raw in result['blobs'].items(): self.assertEqual(sha,__import__('hashlib').sha256(raw).hexdigest())

    def test_executable_author_scripts_and_git_hooks_have_no_side_effect(self):
        marker=self.work/'author-executed'
        self.files['.hooks/run.sh']=('100755',('touch '+str(marker)+'\n').encode())
        self.files['main.lua']=('100755',('os.execute('+json.dumps('touch '+shlex.quote(str(marker)))+')\n').encode())
        self.commit();result=self.validate()
        self.assertFalse(marker.exists())
        self.assertNotIn('.hooks/run.sh',{item['path'] for item in result['index']['files']})

    def test_symlink_and_submodule_are_rejected_before_materialization(self):
        old=self.commit()
        for value in (('120000',b'/outside/secret'),('160000',old)):
            files=copy.deepcopy(self.files);files['unsafe']=value;self.commit(files)
            with self.subTest(value=value[0]),self.assertRaises(ValueError): self.validate()

    def test_case_collisions_and_oversized_blobs_are_rejected(self):
        for mutation in ({'extra/A.txt':b'a','extra/a.txt':b'b'}, {'assets/oversize.png':b'x'*(1024*1024+1)},
                         {'main.lua':b'x'*(256*1024+1)}):
            files=copy.deepcopy(self.files);files.update(mutation);self.commit(files)
            with self.subTest(paths=list(mutation)),self.assertRaises(ValueError): self.validate()

    def test_system_permissions_roles_and_missing_reasons_are_rejected(self):
        original=copy.deepcopy(self.files)
        for permission in ('system.settings','system.plugins'):
            self.files=copy.deepcopy(original);self.manifest(permissions=[permission]);self.metadata(why={permission:{'en':'forbidden'}})
            self.commit()
            with self.assertRaises(ValueError): self.validate()
        self.files=copy.deepcopy(original)
        manifest=json.loads(self.files['manifest.json']);manifest.pop('apps');manifest['pages'][0]['roles']=['home']
        self.files['manifest.json']=json.dumps(manifest).encode();self.commit()
        with self.assertRaises(ValueError): self.validate()
        self.files=copy.deepcopy(original);self.metadata(why={});self.commit()
        with self.assertRaises(ValueError): self.validate()

    def test_license_lua_syntax_bytecode_and_version_mismatch_are_rejected(self):
        original=copy.deepcopy(self.files)
        for mutation in ('missing-license','bad-spdx','syntax','bytecode','version'):
            self.files=copy.deepcopy(original)
            if mutation=='missing-license': self.files.pop('LICENSE')
            elif mutation=='bad-spdx': self.metadata(license='LicenseRef-NotRedistributable')
            elif mutation=='syntax': self.files['main.lua']=b'function (\n'
            elif mutation=='bytecode': self.files['main.lua']=b'\x1bLua fixture'
            else: self.manifest(version='2.0.0')
            self.commit()
            with self.subTest(mutation=mutation),self.assertRaises(ValueError): self.validate()

    def test_author_cannot_claim_official_or_a_factory_identity(self):
        record={'repo':'alice/fixture','path':'../escape','owners':['alice']}
        self.registration.write_text(json.dumps(record))
        with self.assertRaises(ValueError): source_tool.registration(self.registration)
        record.update(path='',tier='official');self.registration.write_text(json.dumps(record))
        with self.assertRaises(ValueError): source_tool.registration(self.registration)
        record.pop('tier');reserved=self.work/'hub.json';reserved.write_text(json.dumps(record))
        with self.assertRaises(ValueError): source_tool.registration(reserved)

    def test_explicit_maintainer_official_allowlist_keeps_original_id(self):
        self.manifest(id='player');self.commit()
        path=self.work/'player.json';registration={'repo':'alice/fixture','path':'','owners':['alice']};path.write_text(json.dumps(registration))
        allowlist=self.work/'official.json';allowlist.write_text(json.dumps({'schema':1,'plugins':[{'id':'player',**registration}]}))
        record=source_tool.registration(path,allowlist)
        result=self.validate(record=record)
        self.assertEqual('player',result['metadata']['id']);self.assertEqual('official',result['metadata']['tier'])


class RegistryGenerationTest(unittest.TestCase):
    setUp=RegistrySourceTest.setUp
    tearDown=RegistrySourceTest.tearDown
    git=RegistrySourceTest.git
    commit=RegistrySourceTest.commit
    manifest=RegistrySourceTest.manifest
    metadata=RegistrySourceTest.metadata

    @classmethod
    def setUpClass(cls):
        cls.material=tempfile.TemporaryDirectory(prefix='thinkos disposable registry signatures ')
        cls.keydir=Path(cls.material.name).resolve();cls.compiler=source_tool.trusted_luac(cls.keydir)
        cls.password='disposable registry fixture passphrase'
        cls.environment=release_tools.secret_environment(cls.password)
        cls.keyring={'schema':1}
        for role in ('channel','registry'):
            cls.keyring[role]={}
            for slot in ('active','backup'):
                path=cls.keydir/(role+'-'+slot+'.key.pem')
                subprocess.run(['openssl','genpkey','-algorithm','EC','-pkeyopt','ec_paramgen_curve:P-256','-aes-256-cbc',
                                '-pass','env:'+release_tools.PRIVATE_PASSWORD_ENV,'-out',str(path)],
                               env=cls.environment,check=True,capture_output=True,stdin=subprocess.DEVNULL)
                os.chmod(path,0o600)
                raw=subprocess.run(['openssl','pkey','-in',str(path),'-passin','env:'+release_tools.PRIVATE_PASSWORD_ENV,
                                    '-pubout','-outform','DER'],env=cls.environment,check=True,capture_output=True,stdin=subprocess.DEVNULL).stdout
                cls.keyring[role][slot]=release_tools.public_key(role+'-'+slot,raw)
        cls.public=cls.keydir/'public.json';cls.public.write_bytes(release_tools.json_bytes(cls.keyring))

    @classmethod
    def tearDownClass(cls): cls.material.cleanup()

    def build(self,tag='v1.0.0',previous=None,approvals=None):
        number=len(list(self.work.glob('distribution-*')))+1
        plan=self.work/('plan-'+str(number)+'.json')
        plan.write_text(json.dumps([{'registration':str(self.registration),'git_dir':str(self.repo),'tag':tag}]))
        output=self.work/('distribution-'+str(number))
        old_seq=registry_tool.document(previous/'registry/index.json')['seq'] if previous else 0
        args=argparse.Namespace(output=output,previous=previous,keys=self.public,seq=old_seq+1,
             blob_base=['https://example.invalid/registry/immutable'],sources=plan,approvals=approvals,
             revocations=None,official_allowlist=None,luac=self.compiler,expires_days=30)
        result=registry_tool.build(args)
        return output,result

    def sign(self,output,previous=None):
        args=argparse.Namespace(directory=output,keys=self.public,private_key=self.keydir/'registry-active.key.pem',
                                slot='active',previous=previous,password_fd=None,luac=self.compiler)
        with patch.object(registry_tool,'read_password',return_value=self.password),patch.dict(os.environ,{
            'CI':'','GITHUB_ACTIONS':'','GITLAB_CI':'','BUILD_BUILDID':''}):
            registry_tool.sign(args)

    def test_actual_openssl_signatures_content_addressing_and_three_version_retention(self):
        previous=None;oldest=None
        for patch_number in range(4):
            version='1.0.'+str(patch_number);tag='v'+version
            self.manifest(version=version);self.commit(tag=tag)
            output,result=self.build(tag,previous);self.sign(output,previous)
            authenticated,_=registry_tool.previous_state(output,self.public)
            self.assertEqual(result,authenticated)
            if oldest is None: oldest=result['plugins'][0]['releases'][0]['digest']
            previous=output
        self.assertEqual(['1.0.3','1.0.2','1.0.1'],[value['version'] for value in result['plugins'][0]['releases']])
        self.assertFalse((output/'registry/files'/(oldest+'.json')).exists())
        for release in result['plugins'][0]['releases']:
            index=registry_tool.document(output/'registry/files'/(release['digest']+'.json'),65536)
            registry_tool.check_index(index)
            license_entry=next(item for item in index['files'] if item['path']=='LICENSE')
            self.assertEqual((ROOT/'LICENSE').read_bytes(),registry_tool.blob_path(output,license_entry['sha256']).read_bytes())

    def test_moved_tag_keeps_published_version_even_if_new_target_has_invalid_lua(self):
        expected=self.commit();first,_=self.build();self.sign(first)
        self.files['main.lua']=b'function ( invalid';observed=self.commit()
        second,result=self.build(previous=first)
        self.assertNotEqual(expected,observed)
        self.assertEqual(expected,result['plugins'][0]['releases'][0]['commit'])
        request=registry_tool.document(second/'review-requests.json')['requests'][0]
        self.assertEqual('tag_moved',request['kind']);self.assertEqual('issue',request['requested_action'])
        self.assertFalse(request['remote_action_performed'])

    def test_permission_increase_waits_for_exact_reviewed_digest_then_can_be_admitted(self):
        self.commit();first,_=self.build();self.sign(first)
        self.manifest(version='1.1.0',permissions=['network','alert'])
        self.metadata(why={'network':{'en':'Read public data'},'alert':{'en':'Show a reminder'}})
        self.commit(tag='v1.1.0')
        pending,result=self.build('v1.1.0',first);self.sign(pending,first)
        self.assertEqual('1.0.0',result['plugins'][0]['releases'][0]['version'])
        request=registry_tool.document(pending/'review-requests.json')['requests'][0]
        self.assertEqual('permissions_increased',request['kind']);self.assertEqual('pull_request',request['requested_action'])
        self.assertFalse(request['remote_action_performed'])
        approval={key:request[key] for key in ('id','commit','digest','permissions')};approval['version']='1.1.0'
        approvals=self.work/'approvals.json';approvals.write_text(json.dumps([approval]))
        accepted,result=self.build('v1.1.0',pending,approvals)
        self.assertEqual('1.1.0',result['plugins'][0]['releases'][0]['version'])
        self.assertEqual(['network','alert'],result['plugins'][0]['releases'][0]['permissions'])

    def test_authenticated_previous_bytes_cannot_be_tampered_or_replaced_with_first_use(self):
        self.commit();first,_=self.build();self.sign(first)
        path=first/'registry/index.json';path.write_bytes(path.read_bytes()+b'\n')
        with self.assertRaises(ValueError): registry_tool.previous_state(first,self.public)
