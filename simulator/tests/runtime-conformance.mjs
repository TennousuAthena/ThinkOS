/* MIT. Production Runtime + shipped Lua/WASM in an isolated real browser worker. */
import { createServer } from 'node:http';
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, relative } from 'node:path';
import { transformWithOxc } from 'vite';
import { chromium } from 'playwright-core';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..'), simulator = resolve(root, 'simulator');
const executablePath = process.env.THINKOS_TEST_CHROME || ['/Applications/Chromium.app/Contents/MacOS/Chromium', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'].find(existsSync) || '';
if (!existsSync(executablePath)) throw Error('Set THINKOS_TEST_CHROME to a locally installed Chromium; this test never downloads one');
async function collect(directory, base = directory) {
  const files = {};
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const target = resolve(directory, entry.name); if (entry.isSymbolicLink()) throw Error('Fixture contains a symlink');
    if (entry.isDirectory()) Object.assign(files, await collect(target, base));
    else if (entry.isFile()) files[relative(base, target)] = (await readFile(target)).toString('base64');
  } return files;
}
const plugins = [];
for (const id of (await readdir(resolve(root, 'plugins'))).sort()) {
  const directory = resolve(root, 'plugins', id); if (!existsSync(resolve(directory, 'manifest.json'))) continue;
  plugins.push({ id, digest: 'test-' + id, factory: true, manifest: JSON.parse(await readFile(resolve(directory, 'manifest.json'), 'utf8')), files: await collect(directory) });
}
const corpus = JSON.stringify({ token: 'test', revision: 'test', plugins, factoryPlugins: plugins.map(plugin => plugin.id), bootstrap: await readFile(resolve(simulator, 'public/runtime/bootstrap.lua'), 'utf8'), settings: JSON.parse(await readFile(resolve(root, 'shared/schema/settings/system.json'), 'utf8')), environment: { locale: 'zh-Hans', timezone: 'Asia/Hong_Kong', now: 1793980800000, networkMode: 'fixture', library: { items: [], directory: '/fixture', scanned: 0, truncated: false } } });
const worker = await readFile(resolve(simulator, 'tests/runtime-conformance-worker.js'));
const modules = new Map();
for (const directory of ['web', 'generated']) for (const name of await readdir(resolve(simulator, directory))) {
  if (!name.endsWith('.ts')) continue;
  modules.set('/' + directory + '/' + name, (await transformWithOxc(await readFile(resolve(simulator, directory, name), 'utf8'), name)).code);
}
const runtimeFiles = new Set(['tos.js', 'tos.wasm', 'bootstrap.lua']);
const server = createServer(async (request, response) => {
  try {
    const path = new URL(request.url, 'http://127.0.0.1').pathname;
    if (path === '/') { response.setHeader('Content-Type', 'text/html'); response.end('<!doctype html><title>Runtime conformance</title>'); }
    else if (path === '/test-data.json') { response.setHeader('Content-Type', 'application/json'); response.end(corpus); }
    else if (path === '/run.js' || modules.has(path)) { response.setHeader('Content-Type', 'text/javascript'); response.end(path === '/run.js' ? worker : modules.get(path)); }
    else if (path.startsWith('/runtime/') && runtimeFiles.has(path.slice(9))) { response.setHeader('Content-Type', path.endsWith('.wasm') ? 'application/wasm' : 'text/javascript'); response.end(await readFile(resolve(simulator, 'public', path.slice(1)))); }
    else { response.statusCode = 404; response.end(); }
  } catch (error) { response.statusCode = 500; response.end(String(error)); }
});
await new Promise((accept, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', accept); });
let browser;
try {
  browser = await chromium.launch({ executablePath, headless: true }); const page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  const result = await page.evaluate(() => new Promise((accept, reject) => {
    const worker = new Worker('/run.js', { type: 'module' }), timeout = setTimeout(() => { worker.terminate(); reject(Error('Runtime conformance timed out')); }, 30000);
    worker.onmessage = event => { clearTimeout(timeout); worker.terminate(); accept(event.data); };
    worker.onerror = event => { clearTimeout(timeout); worker.terminate(); reject(Error(event.message)); };
  }));
  const directory = resolve(simulator, '.local/runtime-conformance'); await mkdir(directory, { recursive: true }); await writeFile(resolve(directory, 'report.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2)); if (!result.ok) process.exitCode = 1;
} finally { await browser?.close(); await new Promise(accept => server.close(accept)); }
