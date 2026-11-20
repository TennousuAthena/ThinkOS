/* MIT. Runs the shipped TypeScript adapter + WASM in a real headless browser worker. */
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { stripTypeScriptTypes } from 'node:module';
import { chromium } from 'playwright-core';

const simulator = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const executablePath = process.env.THINKOS_TEST_CHROME || ['/Applications/Chromium.app/Contents/MacOS/Chromium', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'].find(existsSync) || '';
if (!existsSync(executablePath)) throw new Error('Set THINKOS_TEST_CHROME to an installed Chromium binary; this test never downloads a browser');
const moduleText = stripTypeScriptTypes(await readFile(resolve(simulator, 'web/lua-vm.ts'), 'utf8'), { mode: 'strip' });
const workerText = String.raw`
import { LuaVm, readBootstrap } from '/lua-vm.js';
let saved = null, waiting = 0, rejectTime = false;
const host = (method,args,taskId) => {
  if(method==='config') return {city:'香港😀'};
  if(method==='state.set') {saved=structuredClone(args[0]);return null;}
  if(method==='store.get')return saved;
  if(method==='time.now'){if(rejectTime)throw Error('fixture rejection');return 1704067200000;}
  if(method==='module')return 'return {answer=42}';
  if(method==='sleep'||method==='http.get'){waiting=taskId;return null;}
  if(method==='timer.add'||method==='log')return null;
  throw Error('unexpected host call '+method);
};
function check(condition,message){if(!condition)throw Error(message);}
const labels=[];
let vm;
try {
  vm=await LuaVm.create(host);
  const run=(source,status=0)=>{const step=vm.start(source,'conformance');check(step.status===status,JSON.stringify(step));return step;};
  run(await readBootstrap());vm.bind();
  run("assert(tos.config.city=='香港😀');assert(require('sample').answer==42)");
  labels.push('unicode and modules');
  run("local s='[null,{},[],9007199254740993,9223372036854775807,-9223372036854775808]';assert(tos.json.encode(tos.json.decode(s))==s)");
  run("tos.state.set{integer=9007199254740993,i=1,f=1.0,z=-0.0,object={},array=tos.json.decode('[]'),nothing=tos.json.null};local s=tos.store.get('x');assert(math.type(s.i)=='integer' and math.type(s.f)=='float' and math.type(s.z)=='float' and 1/s.z==-math.huge);assert(s.integer==9007199254740993 and math.type(s.integer)=='integer');assert(tos.json.encode(s.array)=='[]' and tos.json.encode(s.object)=='{}' and s.nothing==tos.json.null)");
  check(typeof saved.integer==='bigint' && saved.f instanceof Number,'host must retain bigint and integral float');
  labels.push('int64, float subtype, negative zero, null and empty containers');
  run("assert(io==nil and os==nil and debug==nil and package==nil and _host==nil);assert(not pcall(function()tos.config.city='x'end))");
  run("return tos.json.decode('[1,]')",2);
  run("return tos.json.decode('9223372036854775808')",2);
  labels.push('sandbox and strict JSON');
  run("tos.on('wait',function()local r,e=tos.http.get('https://example.test');assert(r.body=='ok' and e==nil);tos.timer.sleep(.001);tos.state.set{done=true}end)");
  let step=vm.start('','@wait','dispatch',['wait']);
  check(step.status===1&&step.taskId===waiting,'dispatch must suspend');
  const task=step.taskId;
  step=vm.resume(task,[{body:'ok'},null]);check(step.status===1&&step.taskId===task,'second suspension');
  check(vm.resume(task,[]).status===0 && saved.done===true,'async completion');
  step=vm.start('','@wait','dispatch',['wait']);vm.cancel(step.taskId);check(vm.resume(step.taskId,[]).status===2,'cancelled task');
  labels.push('event dispatch, async resumes and cancellation');
  rejectTime=true;run('return tos.time.now()',2);rejectTime=false;run('assert(tos.time.now()==1704067200000)');
  run('while true do pcall(function()while true do end end)end',3);
  run("return string.rep('a',200):match('^a*a*a*a*a*b')",3);
  run("local x=string.rep('x',8*1024*1024)",4);
  labels.push('host exception recovery, uncatchable instruction/pattern and memory limits');
  for(let n=0;n<500;n++)run('assert(tos.time.now()==1704067200000)');
  check(vm.memory()>0&&vm.memory()<4*1024*1024,'Lua memory bounds');
  check(run('return 1').cpuAvailable===false,'CPU must be reported unavailable');
  vm.destroy();vm.destroy();
  postMessage({ok:true,labels});
}catch(error){try{vm?.destroy();}catch{}postMessage({ok:false,error:String(error),stack:error?.stack});}
`;
const runtimeFiles = new Set(['tos.js', 'tos.wasm', 'bootstrap.lua']);
const server = createServer(async (request, response) => {
  try {
    const path = new URL(request.url, 'http://127.0.0.1').pathname;
    if (path === '/') { response.setHeader('Content-Type','text/html'); response.end('<!doctype html><title>ThinkOS VM conformance</title>'); }
    else if (path === '/lua-vm.js' || path === '/run.js') { response.setHeader('Content-Type','text/javascript'); response.end(path === '/run.js' ? workerText : moduleText); }
    else if (path.startsWith('/runtime/') && runtimeFiles.has(path.slice(9))) {
      response.setHeader('Content-Type',path.endsWith('.wasm')?'application/wasm':path.endsWith('.js')?'text/javascript':'text/plain');
      response.end(await readFile(resolve(simulator,'public',path.slice(1))));
    } else { response.statusCode=404;response.end(); }
  } catch(error) { response.statusCode=500;response.end(String(error)); }
});
await new Promise((accept,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',accept);});
let browser;
try {
  browser=await chromium.launch({executablePath,headless:true});
  const page=await browser.newPage();
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  const result=await page.evaluate(()=>new Promise((accept,reject)=>{
    const worker=new Worker('/run.js',{type:'module'});
    const timeout=setTimeout(()=>{worker.terminate();reject(Error('WASM conformance timed out'));},30000);
    worker.onmessage=event=>{clearTimeout(timeout);worker.terminate();accept(event.data);};
    worker.onerror=event=>{clearTimeout(timeout);worker.terminate();reject(Error(event.message));};
  }));
  if(!result.ok)throw Error(JSON.stringify(result));
  console.log(JSON.stringify({result:'PASS',runtime:'Chromium DedicatedWorker + production Lua/WASM',...result}));
} finally { await browser?.close();await new Promise(resolve=>server.close(resolve)); }
