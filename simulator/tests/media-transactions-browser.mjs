/** MIT. Real Shaka/video with the gateway fixture; inject only ticket delay/failure. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { build } from 'vite';
import { chromium } from 'playwright-core';

const simulator = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
await mkdir(path.join(simulator, '.local'), { recursive: true });
const directory = await mkdtemp(path.join(simulator, '.local/media-transactions-')), harness = path.join(directory, 'harness');
await mkdir(harness);
await writeFile(path.join(harness, 'index.html'), '<!doctype html><script type="module" src="/entry.ts"></script>');
await writeFile(path.join(harness, 'entry.ts'), `
import {Media} from ${JSON.stringify(path.join(simulator, 'web/media.ts'))};
const session=await (await fetch('/api/session')).json(),plugin=session.plugins.find(p=>p.id==='media-test');
const identity={pluginId:plugin.id,digest:plugin.digest},control={fail:false,delay:false,release:null},events=[],ownerEvents=[];
async function api(name,args,binary=false,signal){
 if(name==='resource-ticket'&&control.fail){control.fail=false;throw Error('fixture ticket rejected');}
 if(name==='resource-ticket'&&control.delay){control.delay=false;await new Promise(resolve=>control.release=resolve);control.release=null;}
 const response=await fetch('/api/'+name,{method:'POST',headers:{'Content-Type':'application/json','X-ThinkOS-Session':session.token},body:JSON.stringify(args),signal});
 if(!response.ok)throw Error(JSON.stringify(await response.json()));return binary?response:response.json();
}
const media=new Media(api,state=>events.push(state),(owner,name,payload)=>ownerEvents.push({pluginId:owner.pluginId,generation:owner.generation,name,payload}));
window.mediaTest={media,control,events,ownerEvents,identity,permissions:plugin.manifest.permissions,call:(method,...args)=>media.call({...identity,generation:1,permissions:plugin.manifest.permissions,method:'media.'+method,args}),library:()=>api('library',{...identity,options:{kind:'audio',dir:'/sdcard/Music'}})};
`);
await build({ configFile: false, root: harness, publicDir: false, logLevel: 'silent', build: { outDir: path.join(directory, 'dist'), target: 'es2022', emptyOutDir: true } });
const server = spawn('python3', [path.join(simulator, 'tests/media_fixture_server.py'), directory], { cwd: path.dirname(simulator), stdio: ['ignore', 'pipe', 'pipe'] });
let serverErrors = ''; server.stderr.on('data', data => serverErrors += data);
const results = [], pageErrors = []; let browser;
try {
  const ready = await Promise.race([new Promise((accept, reject) => { createInterface({ input: server.stdout }).once('line', line => { try { accept(JSON.parse(line)); } catch (error) { reject(error); } }); server.once('exit', code => reject(Error('fixture exited ' + code + serverErrors))); }), new Promise((_, reject) => { setTimeout(() => reject(Error('fixture startup timeout')), 30000).unref(); })]);
  browser = await chromium.launch({ headless: true, executablePath: process.env.THINKOS_TEST_CHROME || '/Applications/Chromium.app/Contents/MacOS/Chromium', args: ['--mute-audio'] });
  const page = await browser.newPage(); page.on('pageerror', error => pageErrors.push(String(error))); await page.goto(ready.url); await page.waitForFunction(() => window.mediaTest);
  const call = (method, ...args) => page.evaluate(([method, args]) => window.mediaTest.call(method, ...args), [method, args]);
  const snapshot = () => page.evaluate(() => window.mediaTest.media.snapshot());
  const listing = await page.evaluate(() => window.mediaTest.library());
  const old = { ...listing.items[0], title: 'retained' }, next = { ...listing.items[1], title: 'winner' };
  const loaded = () => page.waitForFunction(() => window.mediaTest.media.video.readyState >= 2, null, { timeout: 10000 });
  async function test(name, body) {
    try { await body(); results.push({ name, ok: true }); }
    catch (error) { results.push({ name, ok: false, error: String(error), state: await snapshot() }); }
    finally { await page.evaluate(() => { window.mediaTest.control.release?.(); window.mediaTest.media.setMode('day'); }); await call('stop'); }
  }
  await test('Rejected queue metadata keeps the prior playable session', async () => {
    assert.equal((await call('load', [old], { autoplay: false }))[1], undefined); await loaded();
    assert.equal((await call('load', [{ url: 'rtsp://example.invalid/test', title: 'bad' }]))[0], null);
    const state = await snapshot(); assert.equal(state.title, 'retained'); assert.equal(state.count, 1); assert.notEqual(state.status, 'error');
  });
  await test('Rejected resource ticket keeps prior queue and playback position', async () => {
    await call('load', [old], { autoplay: false }); await loaded(); await call('seek', 2000);
    await page.evaluate(() => { window.mediaTest.control.fail = true; });
    assert.equal((await call('load', [next], { autoplay: false }))[0], null);
    const state = await snapshot(); assert.equal(state.title, 'retained'); assert.equal(state.count, 1); assert.notEqual(state.status, 'error'); assert.ok(Math.abs(state.position - 2000) < 200);
  });
  await test('Late ticket cannot seek or autoplay a newer successful load', async () => {
    await page.evaluate(item => { window.mediaTest.control.delay = true; window.pendingLoad = window.mediaTest.call('load', [item], { autoplay: false, start: 3500 }); }, old);
    await page.waitForFunction(() => window.mediaTest.control.release !== null);
    assert.equal((await call('load', [next], { autoplay: false }))[1], undefined); await loaded();
    await page.evaluate(() => window.mediaTest.control.release()); await page.evaluate(() => window.pendingLoad); await page.waitForTimeout(100);
    const state = await snapshot(); assert.equal(state.title, 'winner'); assert.ok(state.position < 200, 'old load applied its start position to new session');
  });
  await test('Stop while a resource ticket is pending leaves playback stopped', async () => {
    await page.evaluate(item => { window.mediaTest.control.delay = true; window.pendingLoad = window.mediaTest.call('load', [item], { autoplay: false }); }, old);
    await page.waitForFunction(() => window.mediaTest.control.release !== null); await call('stop');
    await page.evaluate(() => window.mediaTest.control.release()); await page.evaluate(() => window.pendingLoad); await page.waitForTimeout(50);
    const state = await snapshot(); assert.equal(state.status, 'stopped'); assert.equal(state.count, 0);
  });
  await test('Releasing a pending new generation cannot replace the active old generation', async () => {
    await call('load', [old], { autoplay: false }); await loaded();
    await page.evaluate(item => { const t = window.mediaTest; t.control.delay = true; window.pendingLoad = t.media.call({ ...t.identity, generation: 2, permissions: t.permissions, method: 'media.load', args: [[item], { autoplay: false }] }); }, next);
    await page.waitForFunction(() => window.mediaTest.control.release !== null);
    await page.evaluate(() => { window.mediaTest.media.release('media-test', 2); window.mediaTest.control.release(); });
    await page.evaluate(() => window.pendingLoad); await page.waitForTimeout(50);
    const state = await snapshot(); assert.equal(state.title, 'retained'); assert.equal(state.count, 1); assert.notEqual(state.status, 'error'); assert.notEqual(state.status, 'stopped');
  });
  await test('Track, replacement and stop events target the correct owning generation', async () => {
    await page.evaluate(() => { window.mediaTest.ownerEvents.length = 0; });
    await call('load', [old, next], { autoplay: false }); await loaded(); await call('next'); await loaded();
    await page.evaluate(item => { const t = window.mediaTest; return t.media.call({ ...t.identity, generation: 2, permissions: t.permissions, method: 'media.load', args: [[item], { autoplay: false }] }); }, next);
    await loaded(); await call('stop');
    const events = await page.evaluate(() => window.mediaTest.ownerEvents);
    assert.ok(events.some(event => event.name === 'media.track' && event.generation === 1 && event.payload.index === 2));
    assert.ok(events.some(event => event.name === 'media.track' && event.generation === 2 && event.payload.index === 1));
    assert.ok(events.some(event => event.name === 'media.session' && event.generation === 1 && event.payload.reason === 'replaced'));
    assert.ok(events.some(event => event.name === 'media.session' && event.generation === 2 && event.payload.reason === 'stopped'));
    assert.ok(events.every(event => event.pluginId === 'media-test'));
  });
  await test('Actual end of the final media item notifies its owner once', async () => {
    await call('load', [old], { autoplay: false }); await loaded();
    await page.evaluate(() => { window.mediaTest.ownerEvents.length = 0; });
    await call('seek', 7900); await call('play');
    await page.waitForFunction(() => window.mediaTest.ownerEvents.some(event => event.name === 'queue.end'), null, { timeout: 5000 });
    const events = await page.evaluate(() => window.mediaTest.ownerEvents.filter(event => event.name === 'queue.end'));
    assert.equal(events.length, 1); assert.equal(events[0].generation, 1); assert.deepEqual(events[0].payload, { index: 1, count: 1 });
  });
  await test('Video error events carry structured details to the owner', async () => {
    await call('load', [old], { autoplay: false }); await loaded();
    await page.evaluate(() => { window.mediaTest.ownerEvents.length = 0; window.mediaTest.media.video.dispatchEvent(new Event('error')); });
    const events = await page.evaluate(() => window.mediaTest.ownerEvents.filter(event => event.name === 'media.error'));
    assert.equal(events.length, 1); assert.equal(events[0].generation, 1); assert.equal(typeof events[0].payload.message, 'string'); assert.equal(events[0].payload.index, 1);
  });
  await test('Sleep pauses playback, denies new playback and wake does not resume it', async () => {
    await call('load', [old]); await page.waitForFunction(() => window.mediaTest.media.video.currentTime > .1);
    await page.evaluate(() => window.mediaTest.media.setMode('sleep'));
    const position = (await snapshot()).position; await page.waitForTimeout(150);
    assert.equal((await snapshot()).status, 'paused'); assert.ok(Math.abs((await snapshot()).position - position) < 100);
    assert.deepEqual(await call('play'), [null, 'asleep']); assert.deepEqual(await call('load', [next]), [null, 'asleep']);
    assert.equal((await snapshot()).title, 'retained');
    await page.evaluate(() => window.mediaTest.media.setMode('day')); await page.waitForTimeout(150);
    assert.equal((await snapshot()).status, 'paused'); assert.ok(Math.abs((await snapshot()).position - position) < 100);
  });
  await test('Sleep invalidates a delayed load before it can replace the active queue', async () => {
    await call('load', [old], { autoplay: false }); await loaded();
    await page.evaluate(item => { window.mediaTest.control.delay = true; window.pendingLoad = window.mediaTest.call('load', [item]); }, next);
    await page.waitForFunction(() => window.mediaTest.control.release !== null);
    await page.evaluate(() => { window.mediaTest.media.setMode('sleep'); window.mediaTest.control.release(); });
    await page.evaluate(() => window.pendingLoad); await page.waitForTimeout(50);
    const state = await snapshot(); assert.equal(state.title, 'retained'); assert.equal(state.status, 'paused');
  });
  await writeFile(path.join(directory, 'report.json'), JSON.stringify({ results, pageErrors, serverErrors }, null, 2));
  console.log(JSON.stringify({ results, report: path.join(directory, 'report.json') }, null, 2));
  assert.deepEqual(pageErrors, []); assert.ok(results.every(result => result.ok), 'Media transaction regressions');
} finally { await browser?.close(); server.kill('SIGTERM'); }
