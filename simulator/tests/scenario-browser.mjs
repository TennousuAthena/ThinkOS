/** MIT. Scenario import replaces optional fixtures atomically and fills schema defaults. */
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,mkdtemp,readFile,writeFile} from 'node:fs/promises';
import {createInterface} from 'node:readline';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {chromium} from 'playwright-core';

const simulator=fileURLToPath(new URL('..',import.meta.url));
await mkdir(path.join(simulator,'.local'),{recursive:true});
const directory=await mkdtemp(path.join(simulator,'.local/scenario-browser-'));
const server=spawn('python3',[path.join(simulator,'tests/workbench_fixture_server.py'),directory],{stdio:['ignore','pipe','pipe']});
let stderr='',browser,page,stage='startup';server.stderr.on('data',data=>stderr+=data);
const results=[],errors=[];
try{
 const ready=await Promise.race([
  new Promise((resolve,reject)=>{createInterface({input:server.stdout}).once('line',line=>{try{resolve(JSON.parse(line));}catch(error){reject(error);}});server.once('exit',code=>reject(Error('fixture exited '+code+': '+stderr)));}),
  new Promise((_,reject)=>setTimeout(()=>reject(Error('fixture startup timed out')),30000).unref())]);
 browser=await chromium.launch({headless:true,executablePath:process.env.THINKOS_TEST_CHROME||'/Applications/Chromium.app/Contents/MacOS/Chromium',args:['--mute-audio']});
 page=await browser.newPage({viewport:{width:1600,height:1100},acceptDownloads:true});page.on('pageerror',error=>errors.push(String(error)));
 await page.goto(ready.url);await page.waitForFunction(()=>document.querySelector('#connection')?.textContent==='运行中');
 const exportScenario=async()=>{const pending=page.waitForEvent('download');await page.click('#export');return JSON.parse(await readFile(await (await pending).path(),'utf8'));};
 const upload=async value=>page.locator('#scenario').setInputFiles({name:'scenario.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(value))});
 const original=await exportScenario();
 const patch={version:1,environment:{locale:'fr',theme:'LIGHT',now:1704067200000,networkMode:'fixture'},page:'clock.face'};
 await upload(patch);await page.waitForFunction(()=>document.querySelector('#locale').value==='fr'&&document.querySelector('#theme').value==='LIGHT');
 await page.waitForFunction(()=>document.querySelector('#screen').dataset.renderedPage==='clock.face');
 const imported=await exportScenario();assert.deepEqual(imported.environment,{...original.environment,...patch.environment});assert.equal(imported.page,patch.page);
 results.push({name:'Partial import preserves defaults, updates controls and navigates',ok:true});
 const profile=JSON.parse(await readFile(path.join(directory,'profiles/workbench.json'),'utf8'));assert.deepEqual(profile.environment,imported.environment);
 results.push({name:'Imported state matches atomic persisted profile',ok:true});
 await page.selectOption('#theme','DARK');await upload(patch);await page.waitForFunction(()=>document.querySelector('#theme').value==='LIGHT');
 assert.deepEqual((await exportScenario()).environment,imported.environment);
 results.push({name:'Selecting the same file again reimports it',ok:true});
 stage='invalid page';await upload({version:1,environment:{locale:'en'},page:'missing.main'});await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('场景页面不可用'));
 assert.deepEqual((await exportScenario()).environment,imported.environment);
 stage='invalid environment';const denied=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/profile'&&response.status()===400);await upload({version:1,environment:{locale:'en',unexpected:true}});await denied;
 await page.waitForFunction(()=>document.querySelector('#scenario').value==='');
 assert.deepEqual((await exportScenario()).environment,imported.environment);
 results.push({name:'Bad page or invalid environment cannot partly mutate state',ok:true});
 stage='reload';await page.reload();await page.waitForFunction(()=>document.querySelector('#connection')?.textContent==='运行中');
 assert.deepEqual((await exportScenario()).environment,imported.environment);assert.deepEqual(errors,[]);
 results.push({name:'Reload restores imported environment',ok:true});
 stage='restore earlier scenario';await upload(original);await page.waitForFunction(()=>document.querySelector('#locale').value==='zh-Hans'&&document.querySelector('#scenario').value==='');
 const restored=await exportScenario();assert.deepEqual(restored.environment,original.environment);assert.equal(Object.hasOwn(restored.environment,'now'),false);
 const restoredProfile=JSON.parse(await readFile(path.join(directory,'profiles/workbench.json'),'utf8'));assert.deepEqual(restoredProfile.environment,original.environment);
 await page.reload();await page.waitForFunction(()=>document.querySelector('#connection')?.textContent==='运行中');
 assert.deepEqual((await exportScenario()).environment,original.environment);
 results.push({name:'Importing an earlier scenario removes the virtual clock from runtime and persistence',ok:true});
}catch(error){results.push({name:'scenario regression',stage,ok:false,error:String(error),notice:await page?.locator('#notice').textContent().catch(()=>null)});process.exitCode=1;}
finally{await browser?.close();server.kill('SIGTERM');await new Promise(resolve=>{if(server.exitCode!==null||server.signalCode!==null)resolve();else server.once('exit',resolve);});await writeFile(path.join(directory,'report.json'),JSON.stringify({results,errors,stderr},null,2)+'\n');console.log(JSON.stringify({results,errors,report:path.join(directory,'report.json')}));}
