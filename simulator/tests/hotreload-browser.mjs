/** Real main + Worker + WASM + Python watcher, isolated from the normal dev server. */
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,mkdtemp,readFile,rename,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {createInterface} from 'node:readline';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {build} from 'vite';
import {chromium} from 'playwright-core';

const simulator=path.resolve(fileURLToPath(new URL('..',import.meta.url)));
await mkdir(path.join(simulator,'.local'),{recursive:true});
const directory=await mkdtemp(path.join(simulator,'.local/hotreload-browser-'));
const plugin=path.join(directory,'plugin');await mkdir(path.join(plugin,'views'),{recursive:true});
const manifest={id:'hotreload',name:'Hot reload',version:'1.0.0',api:10,main:'main.lua',permissions:['network'],pages:[{id:'main',title:'Hot reload',view:'views/main.json'}]};
const lua=version=>`tos.on("start",function()
 local retained=tos.store.get("retained")
 if retained==nil then retained="persisted-token";tos.store.set("retained",retained) end
 local starts=(tos.store.get("starts") or 0)+1
 tos.store.set("starts",starts)
 tos.state.set({version="${version}",retained=retained,starts=starts})
end)
`;
const view=label=>JSON.stringify({layout:'focus',slots:{over:{type:'text',value:label,style:'title'},hero:{type:'text',value:'{{version}}',style:'title'},under:{type:'text',value:'{{retained}}',style:'body'}}});
await writeFile(path.join(plugin,'manifest.json'),JSON.stringify(manifest));
await writeFile(path.join(plugin,'main.lua'),lua('initial'));
await writeFile(path.join(plugin,'views/main.json'),view('INITIAL VIEW'));
await build({configFile:false,root:path.join(simulator,'web'),publicDir:path.join(simulator,'public'),logLevel:'warn',build:{outDir:path.join(directory,'dist'),target:'es2022',emptyOutDir:true},worker:{format:'es'}});
const server=spawn('python3',[path.join(simulator,'tests/hotreload_fixture_server.py'),directory],{cwd:path.dirname(simulator),stdio:['ignore','pipe','pipe']});
let serverErrors='',setupTimeout,browser,session,ready;server.stderr.on('data',chunk=>serverErrors+=chunk);
const pageErrors=[],consoleErrors=[],requests=[],snapshots=[],results=[],writes=[];
const report=path.join(directory,'report.json');
async function atomic(relative,value){const file=path.join(plugin,relative),temporary=path.join(path.dirname(file),'.'+path.basename(file)+'.pending');await writeFile(temporary,value);await rename(temporary,file);writes.push(relative);}
async function api(name,body){const response=await fetch(ready.url+'/api/'+name,{method:body?'POST':'GET',headers:{'X-ThinkOS-Session':session.token,...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});return {status:response.status,value:await response.json()};}
try{
 ready=await Promise.race([new Promise((resolve,reject)=>{createInterface({input:server.stdout}).once('line',line=>{try{resolve(JSON.parse(line));}catch(error){reject(error);}});server.once('exit',code=>reject(Error('fixture server exited '+code+': '+serverErrors)));}),new Promise((_,reject)=>{setupTimeout=setTimeout(()=>reject(Error('fixture startup timed out')),30000);})]);clearTimeout(setupTimeout);
 // Read our gateway credentials outside CDP: the font-bearing response exceeds
 // Chromium's inspector body cache, while the application still fetches it normally.
 session=await (await fetch(ready.url+'/api/session')).json();
 browser=await chromium.launch({headless:true,executablePath:process.env.THINKOS_TEST_CHROME||'/Applications/Chromium.app/Contents/MacOS/Chromium'});
 const page=await browser.newPage({viewport:{width:1600,height:1100}});
 page.on('pageerror',error=>pageErrors.push(String(error)));page.on('console',message=>{if(message.type()==='error')consoleErrors.push(message.text());});
 const pendingResponses=[];
 page.on('request',request=>{const pathname=new URL(request.url()).pathname;if(pathname.startsWith('/api/'))requests.push({method:request.method(),path:pathname});});
 page.on('response',response=>{const pathname=new URL(response.url()).pathname;if(pathname==='/api/snapshot')pendingResponses.push(response.json().then(value=>snapshots.push(value)).catch(error=>pageErrors.push(String(error))));});
 const state=()=>page.locator('#inspect').evaluate(element=>JSON.parse(element.textContent));
 const waitState=(version,starts)=>page.waitForFunction(({version,starts})=>{try{const value=JSON.parse(document.querySelector('#inspect').textContent);return value.state?.version===version&&value.state?.starts===starts&&/^[a-f0-9]{64}$/.test(value.digest);}catch{return false;}},{version,starts},{timeout:30000});
 const image=async name=>{await page.waitForFunction(()=>document.querySelector('#screen').dataset.renderedPage==='hotreload.main');const data=await page.locator('#screen').screenshot({path:path.join(directory,name+'.png')});return createHash('sha256').update(data).digest('hex');};
 await page.goto(ready.url);await page.waitForFunction(()=>document.querySelector('#connection')?.textContent==='运行中',null,{timeout:30000});
 await page.selectOption('#page','hotreload.main');await waitState('initial',1);await Promise.all(pendingResponses);
 assert.equal(session.plugins.length,1);assert.equal(session.plugins[0].id,'hotreload');assert.equal(session.plugins[0].factory,false);
 const initial=await state(),initialImage=await image('initial');assert.equal(initial.state.retained,'persisted-token');assert.equal(initial.digest,session.plugins[0].digest);
 results.push({name:'initial Lua state and view render',ok:true,digest:initial.digest,image:initialImage});

 await atomic('main.lua','this is invalid Lua syntax !!!');
 await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('未替换'),null,{timeout:15000});
 await Promise.all(pendingResponses);assert.ok(snapshots.some(value=>value.plugins[0].digest!==initial.digest));
 const badState=await state();assert.equal(badState.digest,initial.digest);assert.equal(badState.generation,initial.generation);assert.deepEqual(badState.state,initial.state);assert.equal(await image('bad-lua-retains-old'),initialImage);
 const oldAuthority=await api('http',{pluginId:'hotreload',digest:initial.digest,method:'GET',url:ready.origin+'/permission'});assert.equal(oldAuthority.status,200);assert.equal(oldAuthority.value.body,'authorized');
 results.push({name:'syntax failure preserves state, pixels, digest and old HTTP permission',ok:true});

 await atomic('views/main.json',view('UPDATED VIEW'));await atomic('main.lua',lua('updated'));
 await waitState('updated',2);const updated=await state();assert.notEqual(updated.digest,initial.digest);assert.ok(updated.generation>initial.generation);assert.equal(updated.state.retained,'persisted-token');
 const updatedImage=await image('updated');assert.notEqual(updatedImage,initialImage);
 const newAuthority=await api('http',{pluginId:'hotreload',digest:updated.digest,method:'GET',url:ready.origin+'/permission'});assert.equal(newAuthority.status,200);
 assert.equal((await api('http',{pluginId:'hotreload',digest:initial.digest,method:'GET',url:ready.origin+'/permission'})).status,400);
 await page.waitForResponse(response=>new URL(response.url()).pathname==='/api/storage'&&response.status()===200,{timeout:1000}).catch(()=>{});
 const profile=JSON.parse(await readFile(path.join(directory,'profiles/hotreload.json'),'utf8'));assert.deepEqual(profile.storage.hotreload,{retained:'persisted-token',starts:2});
 results.push({name:'valid candidate commits new UI and preserves persistent store',ok:true,digest:updated.digest,image:updatedImage});

 const priorSnapshots=snapshots.length;await atomic('views/main.json','{"layout":');
 await page.waitForFunction(()=>document.querySelector('#connection').textContent==='运行中');
 for(let n=0;n<60&&snapshots.length===priorSnapshots;n++)await page.waitForTimeout(100);
 await Promise.all(pendingResponses);assert.ok(snapshots.length>priorSnapshots);const invalid=snapshots.at(-1);assert.equal(invalid.plugins[0].digest,updated.digest);assert.ok(invalid.diagnostics.length);
 const final=await state();assert.equal(final.digest,updated.digest);assert.equal(final.generation,updated.generation);assert.deepEqual(final.state,updated.state);assert.equal(await image('bad-view-retains-updated'),updatedImage);
 results.push({name:'invalid view preserves snapshot, VM and rendered pixels',ok:true,diagnostics:invalid.diagnostics,notice:await page.locator('#notice').textContent()});
 assert.ok(requests.some(request=>request.path==='/api/events'));assert.ok(requests.filter(request=>request.path==='/api/snapshot').length>=3);assert.equal(requests.filter(request=>request.path==='/api/session').length,1);
 assert.equal(await page.locator('#screen').getAttribute('data-render-error'),null);assert.deepEqual(pageErrors,[]);assert.deepEqual(consoleErrors,[]);
 results.push({name:'actual SSE reloads use authenticated snapshots without refetching session',ok:true});
 await writeFile(report,JSON.stringify({browser:browser.version(),results,requests,snapshots:snapshots.map(({revision,plugins,diagnostics})=>({revision,digests:plugins.map(plugin=>plugin.digest),diagnostics})),pageErrors,consoleErrors,serverErrors,writes},null,2));
}catch(error){await writeFile(report,JSON.stringify({results,requests,pageErrors,consoleErrors,serverErrors,writes,error:String(error)},null,2));throw error;}
finally{clearTimeout(setupTimeout);if(browser)await browser.close();server.kill('SIGTERM');}
console.log('Hot reload integration passed. Report: '+report);
