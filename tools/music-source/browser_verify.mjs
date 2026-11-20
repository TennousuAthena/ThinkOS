/** Default online catalogue, search form, Lua worker, loopback source, redirect and silent browser decode. */
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir, mkdtemp, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {createInterface} from 'node:readline';
import {fileURLToPath} from 'node:url';
import {build} from '../../simulator/node_modules/vite/dist/node/index.js';
import {chromium} from '../../simulator/node_modules/playwright-core/index.mjs';

const root = fileURLToPath(new URL('../..', import.meta.url));
const simulator = path.join(root, 'simulator');
const out = process.argv[2] ? path.resolve(process.argv[2]) : await mkdtemp(path.join(tmpdir(), 'thinkos-music-'));
await mkdir(out, {recursive: true});
await build({configFile: false, root: path.join(simulator, 'web'), publicDir: path.join(simulator, 'public'), logLevel: 'warn',
  build: {outDir: path.join(out, 'dist'), target: 'es2022', emptyOutDir: true}, worker: {format: 'es'},
  plugins: [{name: 'test-inspection', transform(source, id) {
    if (id.endsWith('/web/main.ts')) return source + '\nObject.assign(window, { musicTest: { hits: () => renderer.getHitRegions(), state: () => media.snapshot(), video: () => media.video } });';
  }}]});
const server = spawn('python3', [path.join(root, 'tools/music-source/browser_fixture.py'), out], {cwd: root, stdio: ['ignore', 'pipe', 'pipe']});
const results = [], errors = [];
let browser, page, stage = 'setup', stderr = '';
server.stderr.on('data', chunk => stderr += chunk);
try {
  const ready = await Promise.race([
    new Promise((resolve, reject) => {
      createInterface({input: server.stdout}).once('line', line => {try {resolve(JSON.parse(line));} catch (error) {reject(error);}});
      server.once('exit', code => reject(Error('fixture exited '+code+': '+stderr)));
    }),
    new Promise((_, reject) => setTimeout(() => reject(Error('fixture startup timed out')), 20000).unref()),
  ]);
  browser = await chromium.launch({headless: true, executablePath: process.env.THINKOS_TEST_CHROME || '/Applications/Chromium.app/Contents/MacOS/Chromium', args: ['--mute-audio']});
  page = await browser.newPage({viewport: {width: 1600, height: 1000}});
  page.setDefaultTimeout(13000);
  page.on('pageerror', error => errors.push(String(error)));
  await page.goto(ready.workbench);
  await page.waitForFunction(() => document.querySelector('#connection')?.textContent === '运行中');
  const waitPage = name => page.waitForFunction(name => document.querySelector('#screen')?.dataset.renderedPage === name, name);
  async function tap(label) {
    await page.waitForFunction(label => window.musicTest.hits().some(hit => hit.label === label), label);
    const target = await page.evaluate(label => window.musicTest.hits().find(hit => hit.label === label).box, label);
    const canvas = page.locator('#screen');
    const size = await canvas.evaluate(element => ({width: element.width, height: element.height}));
    const box = await canvas.boundingBox();
    await page.mouse.click(box.x + (target.x + target.w / 2) * box.width / size.width, box.y + (target.y + target.h / 2) * box.height / size.height);
  }
  stage = 'default online catalogue';
  await page.selectOption('#page', 'player.library');
  await waitPage('player.library');
  await page.waitForFunction(() => ['feature', 'Demo Two'].every(title => window.musicTest.hits().some(hit => hit.label === title)));
  const beforeSearch = await (await fetch(ready.source + '/observations')).json();
  assert.ok(beforeSearch.includes('/v1/browse'));
  assert.equal(beforeSearch.filter(route => route === '/v1/search').length, 0);
  results.push({name: 'the online catalogue appears without a search', ok: true});
  stage = 'playback from catalogue';
  await tap('feature');
  await waitPage('player.library');
  await page.waitForFunction(() => {
    const s = window.musicTest.state();
    return window.musicTest.video().muted && s.title === 'Demo One' && s.status === 'playing' && s.position >= 250;
  });
  results.push({name: 'resolved URL and redirect reach decoder', ok: true, milliseconds: await page.evaluate(() => window.musicTest.state().position)});
  await tap('expand');
  await waitPage('player.audio');
  await tap('toggle');
  await page.waitForFunction(() => window.musicTest.state().status === 'paused');
  await tap('next');
  await page.waitForFunction(() => {
    const s = window.musicTest.state();
    return s.title === 'Demo Two' && s.status === 'playing' && s.position >= 200;
  });
  results.push({name: 'native-style transport controls pause and advance the browser queue', ok: true});
  await tap('upnext');
  await waitPage('player.upnext');
  await tap('Demo One');
  await page.waitForFunction(() => window.musicTest.state().title === 'Demo One');
  await tap('cover');
  await waitPage('player.audio');
  results.push({name:'explicit queue and selection return to the player', ok:true});
  stage = 'sleep gate';
  const environment = async mode => {
    await page.locator('#scenario').setInputFiles({name: 'mode.json', mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify({version: 1, environment: {mode, locale: 'en'}, page: 'player.audio'}))});
    await page.waitForFunction(() => document.querySelector('#scenario').value === '');
  };
  await environment('sleep');
  await page.waitForFunction(() => window.musicTest.state().status === 'paused');
  await tap('toggle');
  await environment('day');
  assert.equal(await page.evaluate(() => window.musicTest.state().status), 'paused');
  results.push({name: 'sleep pauses media, rejects resume and does not autoplay on exit', ok: true});
  stage = 'search';
  await page.selectOption('#page', 'player.library');
  await waitPage('player.library');
  await tap('Search tracks');
  await waitPage('player.search');
  await tap('Track or artist');
  await page.locator('dialog.sim-field-editor input').fill('Demo');
  await page.locator('dialog.sim-field-editor button[type=submit]').click();
  await waitPage('player.library');
  await page.waitForFunction(() => window.musicTest.hits().some(hit => hit.label === 'Demo Two'));
  results.push({name: 'search form to two results', ok: true});
  await tap('Discover');
  await page.waitForFunction(() => window.musicTest.hits().some(hit => hit.label === 'feature'));
  stage = 'empty search layout';
  await tap('Search tracks');
  await waitPage('player.search');
  await tap('Track or artist');
  await page.locator('dialog.sim-field-editor input').fill('Unmatched');
  await page.locator('dialog.sim-field-editor button[type=submit]').click();
  await waitPage('player.library');
  await page.waitForFunction(() => window.musicTest.hits().some(hit => hit.label === 'retry'));
  const emptyHits = await page.evaluate(() => window.musicTest.hits());
  const navigation = emptyHits.find(hit => hit.label === 'Search tracks').box;
  const retry = emptyHits.find(hit => hit.label === 'retry').box;
  assert.ok(retry.w >= navigation.w * 2 && retry.y - navigation.y - navigation.h < 125);
  assert.ok(!emptyHits.some(hit => hit.label === 'Demo Two' || hit.label === 'feature'));
  results.push({name:'empty result stays below search navigation without the discovery hero', ok:true});
  const requests = await (await fetch(ready.source + '/observations')).json();
  assert.equal(requests.filter(route => route === '/v1/search').length, 2);
  assert.ok(['/sample.png','/v1/search','/v1/resolve','/redirect.wav','/one.wav','/two.wav'].every(route => requests.includes(route)), JSON.stringify(requests));
  assert.deepEqual(errors, []);
} catch (error) {
  results.push({name: stage, ok: false, error: String(error), media: await page?.evaluate(() => window.musicTest?.state()).catch(() => null),
    labels: await page?.evaluate(() => window.musicTest?.hits().map(hit => hit.label).filter(Boolean)).catch(() => null)});
  process.exitCode = 1;
} finally {
  await browser?.close();
  server.kill('SIGTERM');
  const report = {results, errors, stderr};
  await writeFile(path.join(out, 'report.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report));
}
