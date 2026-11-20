/* MIT. Built workbench -> CanvasKit -> Lua settings -> authenticated OS gateway. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { chromium } from 'playwright-core';

const simulator = fileURLToPath(new URL('..', import.meta.url)); await mkdir(path.join(simulator, '.local'), { recursive: true });
const directory = await mkdtemp(path.join(simulator, '.local/render-pickers-workbench-'));
const server = spawn('python3', [path.join(simulator, 'tests/workbench_fixture_server.py'), directory], { stdio: ['ignore', 'pipe', 'pipe'] });
let stderr = '', browser, page, stage = 'startup'; server.stderr.on('data', data => stderr += data); const passed = [], errors = [];
try {
  const ready = await Promise.race([new Promise((accept, reject) => { createInterface({ input: server.stdout }).once('line', line => { try { accept(JSON.parse(line)); } catch (error) { reject(error); } }); server.once('exit', code => reject(Error('fixture exited ' + code + ': ' + stderr))); }), new Promise((_, reject) => setTimeout(() => reject(Error('fixture startup timed out')), 30000).unref())]);
  const session = await (await fetch(ready.url + '/api/session')).json();
  const profileUpdate = await fetch(ready.url + '/api/profile', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-ThinkOS-Session': session.token }, body: JSON.stringify({ environment: { locale: 'en', timezone: 'Asia/Hong_Kong', now: Date.UTC(2026, 6, 1, 12), networkMode: 'fixture' } }) }); assert.equal(profileUpdate.status, 200);
  browser = await chromium.launch({ headless: true, executablePath: process.env.THINKOS_TEST_CHROME || '/Applications/Chromium.app/Contents/MacOS/Chromium', args: ['--mute-audio'] });
  page = await browser.newPage({ viewport: { width: 1600, height: 1100 }, acceptDownloads: true }); page.on('pageerror', error => errors.push(String(error)));
  await page.goto(ready.url); await page.waitForFunction(() => document.querySelector('#connection')?.textContent === '运行中');
  async function open(name) { await page.click('#home'); await page.selectOption('#page', name); await page.waitForFunction(name => document.getElementById('screen').dataset.renderedPage === name, name); }
  async function tap(x, y) { await page.evaluate(() => new Promise(accept => requestAnimationFrame(() => requestAnimationFrame(accept)))); const box = await page.locator('#screen').boundingBox(); await page.mouse.click(box.x + x * box.width / 1280, box.y + y * box.height / 800); }
  async function locationPage() {
    await open('settings.form'); await page.waitForFunction(() => JSON.parse(document.getElementById('inspect').textContent).state?.nav?.items?.length > 0);
    const index = await page.locator('#inspect').evaluate(element => JSON.parse(element.textContent).state.nav.items.findIndex(item => item.id === 'location')); assert.ok(index >= 0);
    await tap(150, 32.5 + index * 104 + 45);
    await page.waitForFunction(() => JSON.parse(document.getElementById('inspect').textContent).state?.fields?.some(field => field.source === 'place'));
  }
  async function openPlace() {
    const index = await page.locator('#inspect').evaluate(element => JSON.parse(element.textContent).state.fields.findIndex(field => field.source === 'place')); assert.ok(index >= 0);
    await tap(900, 32.5 + 104 + index * 123.5 + 45); await page.locator('dialog[data-source="place"]').waitFor({ state: 'visible' });
  }
  const capture = async name => { const pending = page.waitForEvent('download'); await page.click('#capture'); const data = await readFile(await (await pending).path()); await writeFile(path.join(directory, name + '.png'), data); return data; };
  const persisted = async () => JSON.parse(await readFile(path.join(directory, 'profiles/workbench.json'), 'utf8'));

  stage = 'fixture gateway search'; await locationPage(); await openPlace();
  const dialog = page.locator('dialog.sim-field-picker'); await dialog.locator('input').fill('London');
  const responsePending = page.waitForResponse(response => new URL(response.url()).pathname === '/api/place-search'); await dialog.locator('.sim-picker-search button').click(); const response = await responsePending;
  assert.equal(response.status(), 200); assert.deepEqual(response.request().postDataJSON(), { query: 'London', locale: 'en' }); assert.ok(response.request().headers()['x-thinkos-session']); assert.equal((await response.json()).offline, true);
  await dialog.locator('button[data-value="Europe/London"]').waitFor(); assert.equal(await dialog.locator('[role="status"]').textContent(), 'Online search unavailable');
  await dialog.locator('button[data-value="Europe/London"]').click();
  await page.waitForFunction(() => JSON.parse(document.getElementById('inspect').textContent).state?.fields?.some(field => field.source === 'place' && field.place?.timezone === 'Europe/London' && field.place?.source === 'manual'));
  passed.push('Actual settings plugin opens place picker; authenticated OS endpoint fixture response selects offline London through Lua setMany');
  stage = 'London clock'; await open('clock.face'); await page.waitForFunction(() => JSON.parse(document.getElementById('inspect').textContent).state?.sun?.timezone === 'Europe/London'); const londonClock = await capture('clock-london');

  stage = 'manual New York'; await locationPage(); await openPlace(); await dialog.locator('[data-action="manual"]').click();
  for (const [key, value] of Object.entries({ name: 'NY test', latitude: '40.7', longitude: '-74', elevation: '12', timezone: 'America/New_York' })) await dialog.locator(`[name="${key}"]`).fill(value);
  // Native grants fresh onboarding interaction credit when the editor commits, even after a slow draft.
  await page.waitForTimeout(5500);
  await dialog.locator('button[type="submit"]').click();
  await page.waitForFunction(() => JSON.parse(document.getElementById('inspect').textContent).state?.fields?.some(field => field.source === 'place' && field.place?.name === 'NY test' && field.place?.timezone === 'America/New_York'));
  await open('clock.face'); await page.waitForFunction(() => JSON.parse(document.getElementById('inspect').textContent).state?.sun?.timezone === 'America/New_York'); const newYorkClock = await capture('clock-new-york'); assert.notDeepEqual(newYorkClock, londonClock);
  const profile = await persisted(); assert.equal(profile.settings.system['location.mode'], 'manual'); assert.equal(profile.settings.system['location.name'], 'NY test'); assert.equal(profile.settings.system['location.latitude'], 40.7); assert.equal(profile.settings.system['location.longitude'], -74); assert.equal(profile.settings.system['location.timezone'], 'America/New_York');
  passed.push('Manual six-field location survives Lua validation/persistence and refreshes clock sun zone plus CanvasKit output');

  stage = 'follow device'; await locationPage(); await openPlace(); await dialog.locator('button[data-value="@auto"]').click();
  await page.waitForFunction(() => JSON.parse(document.getElementById('inspect').textContent).state?.fields?.some(field => field.source === 'place' && field.place?.source === 'timezone'));
  const deviceZone = (await persisted()).environment.timezone;
  await open('clock.face'); await page.waitForFunction(zone => JSON.parse(document.getElementById('inspect').textContent).state?.sun?.timezone === zone, deviceZone);
  assert.equal((await persisted()).settings.system['location.mode'], 'auto'); assert.deepEqual(errors, []); passed.push('Follow-device changes only mode and uses the current device timezone through the full application');
} catch (error) {
  await page?.screenshot({ path: path.join(directory, 'failure.png') }).catch(() => {}); await writeFile(path.join(directory, 'failure-state.json'), await page?.locator('#inspect').textContent().catch(() => '') || '');
  for (const tab of ['logs', 'trace']) { try { await page.locator(`[data-tab="${tab}"]`).click(); await writeFile(path.join(directory, 'failure-' + tab + '.json'), await page.locator('#inspect').textContent()); } catch {} }
  passed.push({ stage, error: String(error) }); process.exitCode = 1;
} finally {
  await browser?.close(); server.kill('SIGTERM'); await new Promise(accept => { if (server.exitCode !== null || server.signalCode !== null) accept(); else server.once('exit', accept); });
  const report = { ok: !process.exitCode, passed, errors, stderr }; await writeFile(path.join(directory, 'report.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify({ ...report, report: path.join(directory, 'report.json') }, null, 2));
}
