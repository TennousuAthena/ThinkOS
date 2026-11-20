/* MIT. Exercise production main routing with the exact authenticated Runtime navigate payloads. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { chromium } from 'playwright-core';
const simulator = fileURLToPath(new URL('..', import.meta.url)); await mkdir(path.join(simulator, '.local'), { recursive: true });
const directory = await mkdtemp(path.join(simulator, '.local/navigation-browser-'));
const server = spawn('python3', [path.join(simulator, 'tests/workbench_fixture_server.py'), directory], { stdio: ['ignore', 'pipe', 'pipe'] });
let stderr = '', browser, page; server.stderr.on('data', data => stderr += data); const passed = [], errors = [];
try {
  const ready = await Promise.race([new Promise((accept, reject) => { createInterface({ input: server.stdout }).once('line', line => accept(JSON.parse(line))); server.once('exit', code => reject(Error('fixture exited ' + code + stderr))); }), new Promise((_, reject) => setTimeout(() => reject(Error('fixture startup timeout')), 30000).unref())]);
  browser = await chromium.launch({ headless: true, executablePath: process.env.THINKOS_TEST_CHROME || '/Applications/Chromium.app/Contents/MacOS/Chromium', args: ['--mute-audio'] });
  page = await browser.newPage({ viewport: { width: 1600, height: 1100 } }); page.on('pageerror', error => errors.push(String(error)));
  // Test-owned message injection exercises main's real receiver without adding a production debug API.
  await page.addInitScript(() => { const Original = window.Worker; window.Worker = class extends Original { constructor(...args) { super(...args); window.testWorker = this; } }; });
  await page.goto(ready.url); await page.waitForFunction(() => document.querySelector('#connection')?.textContent === '运行中');
  const shown = () => page.locator('#screen').getAttribute('data-rendered-page');
  const waitPage = key => page.waitForFunction(key => document.querySelector('#screen')?.dataset.renderedPage === key, key);
  async function navigate(message) { await page.evaluate(message => window.testWorker.dispatchEvent(new MessageEvent('message', { data: { type: 'navigate', ...message } })), message); await page.evaluate(() => new Promise(accept => requestAnimationFrame(() => requestAnimationFrame(accept)))); }
  async function environment(value) { await page.evaluate(value => new Promise(accept => { const listener = event => { if (event.data.type === 'state' && Object.entries(value).every(([key, expected]) => event.data.environment[key] === expected)) { window.testWorker.removeEventListener('message', listener); accept(); } }; window.testWorker.addEventListener('message', listener); window.testWorker.postMessage({ type: 'environment', value }); }), value); }
  await page.selectOption('#page', 'settings.form'); await waitPage('settings.form');
  await navigate({ action: 'focus', page: 'player.video', live: true, answersTap: true, origin: 'page' }); assert.equal(await shown(), 'settings.form');
  await navigate({ action: 'release', page: 'settings.form', origin: 'page' }); assert.equal(await shown(), 'settings.form'); passed.push('Settings resists both focus and release requests');
  await page.click('#home'); await waitPage('home.main');
  await navigate({ action: 'focus', page: 'player.video', live: true, origin: 'page' }); assert.equal(await shown(), 'home.main');
  await navigate({ action: 'focus', page: 'player.video', live: true, answersTap: true, origin: 'page' }); await waitPage('player.video');
  await navigate({ action: 'release', page: 'clock.face', origin: 'page' }); assert.equal(await shown(), 'player.video');
  await navigate({ action: 'focus', page: 'cast.video', live: true, answersTap: true, origin: 'page' }); await waitPage('cast.video');
  await navigate({ action: 'release', page: 'player.video', origin: 'page' }); assert.equal(await shown(), 'cast.video');
  await navigate({ action: 'release', page: 'cast.video', origin: 'page' }); await waitPage('home.main'); passed.push('Recent local interaction guards background focus; takeover replacement and targeted release preserve base');
  await environment({ attention: 'ASLEEP' }); await navigate({ action: 'focus', page: 'player.video', live: true, answersTap: true, origin: 'page' }); assert.equal(await shown(), 'home.main'); await environment({ attention: 'AWAKE' }); passed.push('Sleep blocks even a tap-answering plugin focus');
  await navigate({ action: 'focus', page: 'welcome.hello', origin: 'onboarding', mode: 'restart' }); await waitPage('welcome.hello');
  await navigate({ action: 'focus', page: 'player.video', live: true, answersTap: true, origin: 'page' }); assert.equal(await shown(), 'welcome.hello');
  await navigate({ action: 'release', page: 'player.video', origin: 'page' }); assert.equal(await shown(), 'welcome.hello');
  await navigate({ action: 'release', page: 'welcome.hello', origin: 'page' }); await waitPage('home.main'); passed.push('System onboarding entry protects its owner and owner release returns home');
  await page.selectOption('#page', 'settings.form'); await waitPage('settings.form'); await page.selectOption('#page', 'player.video'); await waitPage('player.video'); passed.push('Explicit workbench page selection remains available');
  assert.deepEqual(errors, []);
} catch (error) { passed.push({ error: String(error) }); await page?.screenshot({ path: path.join(directory, 'failure.png') }).catch(() => {}); process.exitCode = 1; }
finally { await browser?.close(); server.kill('SIGTERM'); await new Promise(accept => { if (server.exitCode !== null || server.signalCode !== null) accept(); else server.once('exit', accept); }); const result = { ok: !process.exitCode, passed, errors, stderr }; await writeFile(path.join(directory, 'report.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify({ ...result, report: path.join(directory, 'report.json') }, null, 2)); }
