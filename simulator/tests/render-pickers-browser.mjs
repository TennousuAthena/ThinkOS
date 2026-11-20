/* MIT. Real CanvasKit hit testing and DOM picker interactions in headless Chromium. */
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';

const simulator = resolve(dirname(fileURLToPath(import.meta.url)), '..'), directory = resolve(simulator, '.local/render-pickers');
await mkdir(directory, { recursive: true });
const systemStrings = Object.fromEntries(await Promise.all(['en', 'ar', 'zh-Hans'].map(async locale => [locale, JSON.parse(await readFile(resolve(simulator, '../shared/i18n/system', locale + '.json'), 'utf8'))])));
const harness = `import { Renderer } from '/renderer.ts'; import '/styles.css';
const fields=[{key:'region',label:'Region',source:'region',type:'choice',value:'GB',allowEmpty:true},{key:'timezone',label:'Time zone',source:'timezone',type:'choice',value:'Europe/London',allowEmpty:true},{key:'place',label:'Place',source:'place',type:'choice',value:'',place:{name:'',latitude:'',longitude:'',elevation:'',timezone:'',source:'timezone'}}];
window.events=[]; window.calls=[];window.searchMode='valid';window.pendingResolve=null;
const searchPlaces=async(query,locale)=>{window.calls.push({query,locale});if(window.searchMode==='error')throw Error('fixture network failure');if(window.searchMode==='empty')return [];if(window.searchMode==='pending')return new Promise(resolve=>window.pendingResolve=resolve);return [{location:{name:'London result',latitude:51.5,longitude:-0.1,elevation:12,timezone:'Europe/London',source:'manual'},detail:'England, United Kingdom'}];};
window.scene={plugins:{fixture:{manifest:{id:'fixture',pages:[{id:'form',view:'form.json'},{id:'other',view:'form.json'}]},views:{'form.json':{layout:'form',slots:{body:{type:'form',path:'fields',title:'Picker fixture'}}}},state:{fields},config:{},i18n:{},assets:{}}},page:'fixture.form',theme:'DARK',orientation:'landscape',fontScale:1,now:Date.UTC(2026,6,1),locale:'en',timezone:'Asia/Hong_Kong'};
window.renderer=await Renderer.create(document.querySelector('canvas'),event=>window.events.push(event),{systemStrings:${JSON.stringify(systemStrings)},searchPlaces});renderer.setScene(scene);window.ready=true;`;
const vite = await createServer({ configFile: false, root: resolve(simulator, 'web'), publicDir: resolve(simulator, 'public'), server: { host: '127.0.0.1', port: 0, fs: { allow: [simulator] } }, plugins: [{ name: 'picker-test', resolveId(id) { if (id === '/picker-test.js') return '\0picker-test'; }, load(id) { if (id === '\0picker-test') return harness; }, configureServer(server) { server.middlewares.use(async (request, response, next) => { if (request.url !== '/picker-test.html') return next(); response.setHeader('content-type', 'text/html'); response.end(await server.transformIndexHtml('/picker-test.html', '<!doctype html><title>ThinkOS picker conformance</title><canvas style="width:960px;height:600px"></canvas><script type="module" src="/picker-test.js"></script>')); }); } }] });
await vite.listen(); let browser; const passed = [], errors = [];
try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.THINKOS_TEST_CHROME || '/Applications/Chromium.app/Contents/MacOS/Chromium' });
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } }); page.on('pageerror', error => errors.push(String(error)));
  await page.goto(`http://127.0.0.1:${vite.httpServer.address().port}/picker-test.html`);
  await page.waitForFunction(() => window.ready && document.querySelector('canvas').dataset.renderedPage);
  const dialog = page.locator('dialog.sim-field-picker');
  const screenshot = name => page.screenshot({ path: resolve(directory, name + '.png') });
  async function open(label) {
    const box = await page.evaluate(label => window.renderer.getHitRegions().find(hit => hit.label === label)?.box, label); assert.ok(box, `Missing canvas hit ${label}`);
    const bounds = await page.locator('canvas').boundingBox(); await page.mouse.click(bounds.x + (box.x + box.w / 2) * bounds.width / 1280, bounds.y + (box.y + box.h / 2) * bounds.height / 800);
    await dialog.waitFor({ state: 'visible' });
  }
  const lastChange = () => page.evaluate(() => window.events.filter(event => event.event === 'form.change').at(-1));
  const changeCount = () => page.evaluate(() => window.events.filter(event => event.event === 'form.change').length);
  await open('Region'); await screenshot('region'); await dialog.locator('input').fill('China'); await dialog.locator('button[data-value="CN"]').click();
  assert.deepEqual(await lastChange(), { pluginId: 'fixture', event: 'form.change', args: ['region', 'CN'] });
  await open('Region'); await dialog.locator('button[data-value=""]').click(); assert.equal((await lastChange()).args[1], ''); passed.push('region localized inventory/search and allow-empty -> form.change');

  await open('Time zone'); await screenshot('timezone'); await dialog.locator('button[data-area="Asia"]').click();
  assert.ok(await dialog.locator('button[data-value="Asia/Hong_Kong"]').count());
  await dialog.locator('input').fill('New York'); await dialog.locator('button[data-value="America/New_York"]').click(); assert.equal((await lastChange()).args[1], 'America/New_York');
  await open('Time zone'); await dialog.locator('button[data-value=""]').click(); assert.equal((await lastChange()).args[1], ''); passed.push('timezone area/global search and allow-empty -> canonical ID');

  await open('Place'); await dialog.locator('button[data-value="@auto"]').click(); assert.deepEqual((await lastChange()).args[1], { mode: 'auto' });
  await open('Place'); await dialog.locator('[data-action="manual"]').click(); const before = await changeCount();
  await dialog.locator('button[type="submit"]').click(); assert.equal(await changeCount(), before); assert.equal(await dialog.locator('[role="alert"]').textContent(), 'Invalid location');
  for (const [name, value] of Object.entries({ name: 'Custom', latitude: '91', longitude: '0', elevation: '0', timezone: 'Bad/Zone' })) await dialog.locator(`[name="${name}"]`).fill(value);
  await dialog.locator('button[type="submit"]').click(); assert.equal(await changeCount(), before);
  await dialog.locator('[name="latitude"]').fill('0'); await dialog.locator('[name="timezone"]').fill(''); await screenshot('manual');
  await dialog.locator('button[type="submit"]').click(); assert.deepEqual((await lastChange()).args[1], { mode: 'manual', name: 'Custom', latitude: 0, longitude: 0, elevation: 0, timezone: '' });
  passed.push('follow-device has no invented coordinates; manual empty/bounds/timezone validation and six-field commit');

  await open('Place'); await dialog.locator('input').fill('London'); await dialog.locator('.sim-picker-search button').click(); await dialog.locator('button[data-value="Europe/London"]').waitFor(); await screenshot('place-search');
  await dialog.locator('button[data-value="Europe/London"]').click(); assert.equal((await lastChange()).args[1].name, 'London result'); assert.deepEqual(await page.evaluate(() => window.calls.at(-1)), { query: 'London', locale: 'en' });
  await page.evaluate(() => window.searchMode = 'empty'); await open('Place'); await dialog.locator('input').fill('London'); await dialog.locator('.sim-picker-search button').click();
  await page.waitForFunction(() => document.querySelector('[role="status"]')?.textContent === 'No places found'); assert.equal(await dialog.locator('button[data-value="Europe/London"]').count(), 0); await page.keyboard.press('Escape');
  await page.evaluate(() => window.searchMode = 'error'); await open('Place'); await dialog.locator('input').fill('London'); await dialog.locator('.sim-picker-search button').click();
  await dialog.locator('button[data-value="Europe/London"]').waitFor(); assert.equal(await dialog.locator('[role="status"]').textContent(), 'Online search unavailable'); await dialog.locator('button[data-value="Europe/London"]').click(); assert.equal((await lastChange()).args[1].name, 'London');
  passed.push('OS callback receives query/locale; online empty stays empty; failure uses bounded offline zone-city results');

  await page.evaluate(() => window.searchMode = 'pending'); await open('Place'); await dialog.locator('input').fill('London'); await dialog.locator('.sim-picker-search button').click();
  await page.waitForFunction(() => typeof window.pendingResolve === 'function'); const initialCount = await changeCount();
  await page.evaluate(() => { window.scene = { ...window.scene, page: 'fixture.other' }; window.renderer.setScene(window.scene); }); await dialog.waitFor({ state: 'detached' });
  await page.evaluate(() => { window.pendingResolve([{ location: { name: 'Late', latitude: 1, longitude: 2, elevation: 0, timezone: 'Europe/London' }, detail: '' }]); });
  assert.equal(await changeCount(), initialCount); assert.equal(await page.locator('dialog').count(), 0); passed.push('navigation closes picker and late OS search response cannot commit or reopen it');

  await page.evaluate(() => { window.scene = { ...window.scene, locale: 'ar', theme: 'LIGHT' }; window.renderer.setScene(window.scene); }); await open('Place'); assert.equal(await dialog.getAttribute('dir'), 'rtl'); await screenshot('place-arabic-light'); await page.keyboard.press('Escape');
  assert.deepEqual(errors, []); assert.equal(await page.locator('canvas').getAttribute('data-render-error'), null); await page.evaluate(() => window.renderer.destroy());
  await writeFile(resolve(directory, 'report.json'), JSON.stringify({ ok: true, passed }, null, 2)); console.log(JSON.stringify({ ok: true, passed, screenshots: directory }, null, 2));
} catch (error) { await writeFile(resolve(directory, 'report.json'), JSON.stringify({ ok: false, passed, errors, error: String(error) }, null, 2)); throw error; }
finally { await browser?.close(); await vite.close(); }
