/* MIT. Real canvas hit testing -> workbench -> Lua -> persisted settings. */
import assert from 'node:assert/strict';
import { chromium } from 'playwright-core';
import { readFile } from 'node:fs/promises';
const browser = await chromium.launch({ headless: true, executablePath: process.env.THINKOS_TEST_CHROME || '/Applications/Chromium.app/Contents/MacOS/Chromium' });
const page = await browser.newPage({ viewport: { width: 1600, height: 1100 }, acceptDownloads: true });
const errors = []; page.on('pageerror', error => errors.push(String(error)));
try {
  await page.goto(process.env.THINKOS_SIM_URL || 'http://127.0.0.1:8766');
  await page.waitForFunction(() => document.querySelectorAll('#page option').length === 24);
  await page.selectOption('#orientation', 'landscape'); await page.selectOption('#locale', 'zh-Hans');
  await page.waitForFunction(() => document.getElementById('screen')?.width === 1280);
  async function open(name) { await page.click('#home'); await page.selectOption('#page', name); await page.waitForFunction(name => document.getElementById('screen')?.dataset.renderedPage === name, name); }
  async function tap(x, y) { const b = await page.locator('#screen').boundingBox(); await page.mouse.click(b.x + x * b.width / 1280, b.y + y * b.height / 800); }
  await open('panel.main');
  await page.waitForFunction(() => JSON.parse(document.getElementById('inspect').textContent).state?.wifi?.on !== undefined);
  const wifi = await page.locator('#inspect').evaluate(element => JSON.parse(element.textContent).state.wifi.on);
  await tap(275, 184);
  await page.waitForFunction(wifi => JSON.parse(document.getElementById('inspect').textContent).state?.wifi?.on === !wifi, wifi);
  await tap(275, 184);
  await page.waitForFunction(wifi => JSON.parse(document.getElementById('inspect').textContent).state?.wifi?.on === wifi, wifi);
  console.log('PASS canvas toggle -> Lua');

  await open('settings.form');
  await page.waitForFunction(() => JSON.parse(document.getElementById('inspect').textContent).state?.fields?.some(field => field.key.endsWith('display.brightness_max')));
  const { index, original } = await page.locator('#inspect').evaluate(element => { const fields = JSON.parse(element.textContent).state.fields, index = fields.findIndex(field => field.key.endsWith('display.brightness_max')); return { index, original: fields[index].value }; });
  const target = Number(original) === 80 ? 85 : 80;
  for (const value of [target, original]) {
    await tap(960, 32 + 104 + index * 123 + 55);
    await page.locator('dialog.sim-field-editor input[type=number]').fill(String(value));
    await page.locator('dialog.sim-field-editor button[type=submit]').click();
    await page.waitForFunction(value => JSON.parse(document.getElementById('inspect').textContent).state?.fields?.some(field => field.key.endsWith('display.brightness_max') && Number(field.value) === Number(value)), value);
  }
  const download = page.waitForEvent('download'); await page.click('#capture');
  const file = await (await download).path(), bytes = await readFile(file);
  assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  assert.equal(bytes.readUInt32BE(16), 1280); assert.equal(bytes.readUInt32BE(20), 800);
  assert.deepEqual(errors, []);
  console.log('PASS canvas toggle -> Lua, form editor -> settings -> restored value, CanvasKit PNG export');
} catch (error) {
  await page.screenshot({ path: new URL('../.local/render-browser/interaction-failure.png', import.meta.url).pathname });
  console.log(await page.locator('#inspect').evaluate(element => { const value = JSON.parse(element.textContent); return { fields: value.state?.fields?.filter(field => field.key.endsWith('display.brightness_max')), page: document.getElementById('screen').dataset.renderedPage, notice: document.getElementById('notice').textContent }; })); throw error;
} finally { await browser.close(); }
