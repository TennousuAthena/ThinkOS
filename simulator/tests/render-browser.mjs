import assert from 'node:assert/strict';
import { chromium } from 'playwright-core';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const directory = path.resolve(fileURLToPath(new URL('../.local/render-browser/', import.meta.url)));
await mkdir(directory, { recursive: true });
const browser = await chromium.launch({ headless: true, executablePath: process.env.THINKOS_TEST_CHROME || '/Applications/Chromium.app/Contents/MacOS/Chromium' });
const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });
const errors = [], results = [];
page.on('pageerror', error => errors.push(String(error)));
try {
  await page.goto(process.env.THINKOS_SIM_URL || 'http://127.0.0.1:8766');
  await page.waitForFunction(() => document.getElementById('connection')?.textContent === '运行中', { timeout: 30000 });
  await page.waitForFunction(() => document.querySelectorAll('#page option').length === 24, { timeout: 30000 });
  await page.selectOption('#orientation', 'landscape');
  await page.waitForFunction(() => document.getElementById('screen')?.width === 1280);
  const names = await page.locator('#page option').evaluateAll(options => options.map(option => option.value));
  assert.equal(names.length, 24, 'built-in page inventory must be complete before visual checks');
  for (const name of names) {
    // The real onboarding plugin redirects setup to hello until its greeting step is done.
    const expectedPage = name.startsWith('welcome.') ? 'welcome.hello' : name;
    await page.click('#home');
    await page.selectOption('#page', name);
    await page.waitForFunction(name => document.getElementById('screen')?.dataset.renderedPage === name, expectedPage);
    await page.waitForTimeout(180);
    const state = await page.locator('#screen').evaluate(canvas => ({ ...canvas.dataset }));
    results.push({ page: name, ...state });
    await page.locator('#screen').screenshot({ path: path.join(directory, `${name}.png`) });
    assert.equal(state.renderError, undefined, `${name}: ${state.renderError}`);
    assert.ok(!state.renderWarnings?.includes('Clock format:'), `${name}: ${state.renderWarnings}`);
    assert.ok(state.renderedAt, `${name} has not produced a frame`);
    assert.equal(state.renderedPage, expectedPage, 'navigation must actually display the requested page or its declared redirect');
  }
  await page.selectOption('#orientation', 'portrait');
  await page.click('#home');
  await page.selectOption('#page', 'tiles.main');
  await page.waitForTimeout(300);
  await page.locator('#screen').screenshot({ path: path.join(directory, 'tiles.portrait.png') });
  assert.equal((await page.locator('#screen').evaluate(canvas => canvas.dataset.renderError)), undefined);
  await page.selectOption('#orientation', 'landscape'); await page.click('#home');
  for (const locale of ['zh-Hans', 'zh-Hant', 'en', 'es', 'fr', 'pt', 'ru', 'ar', 'hi', 'bn', 'ur']) {
    await page.selectOption('#locale', locale);
    await page.waitForFunction(locale => document.getElementById('screen')?.dataset.renderedLocale === locale, locale);
    await page.waitForTimeout(180);
    const state = await page.locator('#screen').evaluate(canvas => ({ ...canvas.dataset }));
    assert.equal(state.renderError, undefined, `${locale}: ${state.renderError}`);
    assert.ok(!state.renderWarnings?.includes('Clock format:'), `${locale}: ${state.renderWarnings}`);
    await page.locator('#screen').screenshot({ path: path.join(directory, `home.${locale}.png`) });
  }
  await page.selectOption('#locale', 'zh-Hans');
  assert.deepEqual(errors, []);
} finally {
  await writeFile(path.join(directory, 'report.json'), JSON.stringify({ results, errors }, null, 2));
  await browser.close();
}
console.log(`Checked ${results.length} native plugin page routes, onboarding redirect, both orientations and 11 locales; screenshots require visual review.`);
