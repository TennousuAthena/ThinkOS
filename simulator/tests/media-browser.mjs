/** Local-only integration: actual browser codecs, gateway, virtual library and Shaka. */
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir, mkdtemp, writeFile} from 'node:fs/promises';
import {createInterface} from 'node:readline';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {build} from 'vite';
import {chromium} from 'playwright-core';

const simulator=path.resolve(fileURLToPath(new URL('..',import.meta.url)));
await mkdir(path.join(simulator,'.local'),{recursive:true});
const directory=await mkdtemp(path.join(simulator,'.local/media-browser-'));
const harness=path.join(directory,'harness');await mkdir(harness);
await writeFile(path.join(harness,'index.html'),'<html><head><link rel="icon" href="data:,"></head><body><script type="module" src="/entry.ts"></script></body></html>');
await writeFile(path.join(harness,'entry.ts'),`
import {Media} from ${JSON.stringify(path.join(simulator,'web/media.ts'))};
const session=await (await fetch('/api/session')).json();
const plugin=session.plugins.find(p=>p.id==='media-test');
const identity={pluginId:plugin.id,digest:plugin.digest};
const requests=[];const events=[];
async function api(name,args,binary=false,signal){
 const record={name,args,aborted:false};requests.push(record);
 try{const response=await fetch('/api/'+name,{method:'POST',headers:{'Content-Type':'application/json','X-ThinkOS-Session':session.token},body:JSON.stringify(args),signal});
 if(!response.ok)throw Error(JSON.stringify(await response.json()));return binary?response:response.json();}
 catch(error){record.aborted=signal?.aborted??false;throw error;}
}
const media=new Media(api,state=>{events.push(state);if(events.length>1000)events.shift();});
document.body.append(media.video);
window.mediaTest={media,events,requests,api,identity,call:(method,...args)=>media.call({...identity,generation:1,permissions:plugin.manifest.permissions,method:'media.'+method,args}),library:options=>api('library',{...identity,options})};
`);
await build({configFile:false,root:harness,publicDir:false,logLevel:'warn',build:{outDir:path.join(directory,'dist'),target:'es2022',emptyOutDir:true}});
const server=spawn('python3',[path.join(simulator,'tests/media_fixture_server.py'),directory],{cwd:path.dirname(simulator),stdio:['ignore','pipe','pipe']});
let serverErrors='';server.stderr.on('data',data=>serverErrors+=data);
let browser,setupTimeout;const results=[],pageErrors=[],consoleErrors=[];
const report=path.join(directory,'report.json');
try{
 const ready=await Promise.race([new Promise((resolve,reject)=>{createInterface({input:server.stdout}).once('line',line=>{try{resolve(JSON.parse(line));}catch(error){reject(error);}});server.once('exit',code=>reject(Error('fixture server exited '+code+': '+serverErrors)));}),new Promise((_,reject)=>{setupTimeout=setTimeout(()=>reject(Error('fixture setup timed out')),30000);})]);
 clearTimeout(setupTimeout);
 browser=await chromium.launch({headless:true,executablePath:process.env.THINKOS_TEST_CHROME||'/Applications/Chromium.app/Contents/MacOS/Chromium',args:['--mute-audio']});
 const page=await browser.newPage();page.on('pageerror',error=>pageErrors.push(String(error)));page.on('console',message=>{if(message.type()==='error')consoleErrors.push(message.text());});
 await page.goto(ready.url);await page.waitForFunction(()=>window.mediaTest);
 const call=(method,...args)=>page.evaluate(([method,args])=>window.mediaTest.call(method,...args),[method,args]);
 const snapshot=()=>page.evaluate(()=>window.mediaTest.media.snapshot());
 const loaded=()=>page.waitForFunction(()=>{const m=window.mediaTest.media,s=m.snapshot();return s.status==='playing'&&m.video.currentTime>.1;},null,{timeout:15000});
 async function test(name,run){try{await run();results.push({name,ok:true});console.log('PASS '+name);}catch(error){results.push({name,ok:false,error:String(error),state:await snapshot()});console.log('FAIL '+name+': '+error);}finally{await call('stop');}}
 const listing=await page.evaluate(()=>window.mediaTest.library({kind:'audio',dir:'/sdcard/Music'}));
 await test('library/WAV playback, duration, pause, seek and next',async()=>{
  assert.equal(listing.items.length,2);assert.equal((await call('load',listing.items))[1],undefined);await loaded();
  let state=await snapshot();assert.ok(Math.abs(state.duration-8000)<50);assert.equal(state.title,'01-tone');
  await call('pause');const before=(await snapshot()).position;await page.waitForTimeout(300);assert.ok(Math.abs((await snapshot()).position-before)<100);
  await call('seek',4000);await page.waitForFunction(()=>window.mediaTest.media.video.currentTime>3.9);await call('play');await loaded();
  await call('next');await page.waitForFunction(()=>window.mediaTest.media.snapshot().index===2&&window.mediaTest.media.video.currentTime>.1);state=await snapshot();assert.equal(state.title,'02-tone');
 });
 await test('local MP4 browser metadata and playback',async()=>{
  const video=await page.evaluate(()=>window.mediaTest.library({kind:'video',dir:'/sdcard/Movies'}));assert.equal(video.items.length,1);
  assert.equal((await call('load',video.items))[1],undefined);await loaded();const state=await snapshot();assert.equal(state.videoWidth,160);assert.equal(state.videoHeight,90);assert.ok(Math.abs(state.duration-8000)<100);
 });
 for(const [type,url] of [['progressive','/clip.mp4'],['hls','/hls/playlist.m3u8'],['dash','/dash/manifest.mpd']])await test('HTTP '+type+' proxy playback',async()=>{
  assert.equal((await call('load',[{url:ready.origin+url,kind:'video',type,title:type}]))[1],undefined);await loaded();const state=await snapshot();assert.equal(state.videoWidth,160);assert.equal(state.videoHeight,90);assert.ok(state.duration>7900);
  if(type!=='progressive'){assert.ok(state.audioTracks.length>=1);assert.equal((await call('audioTrack',1))[1],undefined);}
 });
 await test('probe identifies HLS',async()=>{const result=await call('probe',ready.origin+'/hls/playlist.m3u8');assert.equal(result[0].ok,true);assert.equal(result[0].kind,'hls');});
 await test('cancel pending adaptive manifest',async()=>{
  await page.evaluate(url=>{window.pending=window.mediaTest.call('load',[{url,type:'hls',kind:'video'}]);},ready.origin+'/slow.m3u8');
  await page.waitForFunction(()=>window.mediaTest.requests.some(r=>r.name==='resource'&&r.args.url.endsWith('/slow.m3u8')));
  await call('stop');await page.evaluate(()=>window.pending);await page.waitForTimeout(100);const state=await snapshot();assert.equal(state.status,'stopped');assert.equal(state.count,0);
  assert.ok(await page.evaluate(()=>window.mediaTest.requests.some(r=>r.args.url?.endsWith('/slow.m3u8')&&r.aborted)));
 });
 await test('release only stops matching owner generation',async()=>{
  await call('load',listing.items);await loaded();await page.evaluate(()=>window.mediaTest.media.release('media-test',0));assert.equal((await snapshot()).status,'playing');
  await page.evaluate(()=>window.mediaTest.media.release('media-test',1));await page.waitForFunction(()=>window.mediaTest.media.snapshot().status==='stopped');
 });
 const observations=await (await fetch(ready.origin+'/stats')).json();
 await writeFile(report,JSON.stringify({browser:browser.version(),results,pageErrors,consoleErrors,observations,serverErrors},null,2));
 assert.deepEqual(pageErrors,[]);assert.deepEqual(consoleErrors,[]);assert.ok(results.every(result=>result.ok),'Media integration failures; see '+report);
}catch(error){await writeFile(report,JSON.stringify({results,pageErrors,consoleErrors,serverErrors,error:String(error)},null,2));throw error;}
finally{clearTimeout(setupTimeout);if(browser)await browser.close();server.kill('SIGTERM');}
console.log('Report: '+report);
