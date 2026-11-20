import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { nativeSlot, nativeTiles, nativeVertical, nativePanel, nativeTileColors, NativeNav } from '../reference/generated/thinkos-reference.mjs';
import { slotBox, stackBox, tileBoxes, verticalHeights, tileColors } from '../web/render/geometry.ts';
import { array, bound, imagePrincipal, lookup, text } from '../web/render/binding.ts';
import { RENDER_VOCABULARY } from '../web/render/vocabulary.ts';

const vectors = JSON.parse(readFileSync(new URL('../reference/vectors.json', import.meta.url), 'utf8'));
const functions: Record<string, (...args: any[]) => string> = { slot: nativeSlot, tiles: nativeTiles, vertical: nativeVertical, panel: nativePanel, colors: nativeTileColors };
test('renderer vocabulary covers the current shared layout, template and component contracts', () => {
  const layouts = JSON.parse(readFileSync(new URL('../../shared/schema/plugin/layouts.json', import.meta.url), 'utf8'));
  const view = JSON.parse(readFileSync(new URL('../../shared/schema/plugin/view.json', import.meta.url), 'utf8'));
  const resolve = (ref: { $ref: string }) => layouts.$defs[ref.$ref.split('/').at(-1)!];
  const declarations = view.oneOf.map(resolve);
  assert.deepEqual([...RENDER_VOCABULARY.layouts].sort(), declarations.map((d: any) => d.properties?.layout?.const).filter(Boolean).sort());
  assert.deepEqual([...RENDER_VOCABULARY.templates].sort(), declarations.map((d: any) => d.properties?.template?.const).filter(Boolean).sort());
  const components = [...layouts.$defs.component.oneOf, layouts.$defs.formView.properties.slots.properties.body];
  assert.deepEqual([...RENDER_VOCABULARY.components].sort(), components.map(resolve).map((d: any) => d.properties.type.const).sort());
});
test('reference source fingerprints still match the production files', () => {
  for (const [path, expected] of Object.entries(vectors.sha256)) {
    const bytes = readFileSync(new URL(`../../android/app/src/main/java/moe/kiwi/thinkos/${path}`, import.meta.url));
    assert.equal(createHash('sha256').update(bytes).digest('hex'), expected, `${path}: rebuild and review parity vectors after changing production source`);
  }
});
for (const [index, vector] of vectors.cases.entries()) test(`JVM/JS ${vector.command} reference ${index + 1}`, () => {
  assert.deepEqual(JSON.parse(functions[vector.command](...vector.args)), vector.expected);
});
test('pixel adapters keep fractional device density and exact edges', () => {
  assert.deepEqual(stackBox(0, 1280, 800), { x: 32, y: 32, w: 595, h: 736 });
  assert.deepEqual(stackBox(1, 1280, 800), { x: 653, y: 32, w: 595, h: 736 });
  assert.deepEqual(slotBox('focus', 'main', 1280, 800, true, true), { x: 32, y: 123, w: 1216, h: 554 });
  const boxes = tileBoxes(['large', 'large', 'large'], 1280, 800);
  assert.equal(boxes[2], null);
  assert.deepEqual(verticalHeights(100, [10, 99, 99], [0, 1, 2], 5), { heights: [10, 27, 53], gap: 5, offset: 0 });
  assert.equal(tileColors('#ffffff', false).foreground, '#000000');
});
test('original navigation stack preserves variants and enforces bounded depth', () => {
  const nav = new NativeNav();
  assert.equal(nav.push('home.main', 'one', 'APP', 'player.library', 'tracks'), true);
  assert.equal(nav.push('player.library', 'tracks', 'SETTINGS', 'settings.form', ''), true);
  assert.equal(nav.pop(), 'player.library\ntracks');
  assert.equal(nav.pop(), 'home.main\none');
  assert.equal(nav.pop(), null);
  for (let i = 0; i < 8; i++) assert.equal(nav.push('home.main', '', 'APP', `test.page${i}`, ''), true);
  assert.equal(nav.push('test.page7', '', 'APP', 'test.overflow', ''), false);
  assert.equal(nav.depth, 8);
  assert.equal(nav.clear(), 'home.main\n');
  assert.equal(nav.depth, 0);
});
const context = { state: { title: '天气', missing: null, rows: [{ title: '阵雨' }], uses: { daily: { title: '山' } } }, config: { seconds: false }, i18n: { 'zh-Hans': { name: '时钟' } }, locale: 'zh-Hans', variant: 'analog' };
test('binding preserves typed exact values and localization rules', () => {
  assert.equal(text('@name', context), '时钟');
  assert.equal(text('@@name', context), '@name');
  assert.equal(text('{{@name}} {{title}}', context), '时钟 天气');
  assert.equal(bound('{{config.seconds}}', context), false);
  assert.equal(bound('{{variant}}', context), 'analog');
  assert.equal(text('{{uses.daily.title}}', context), '山');
  assert.equal(text('{{uses.unavailable.title | default "—"}}', context), '—');
  assert.equal(text('{{missing | fixed 1}}', context), '');
  assert.equal(text('{{.title}}', context, { title: '行内容' }), '行内容');
  assert.deepEqual(array('{{rows}}', context), [{ title: '阵雨' }]);
});
test('bindings never follow inherited JavaScript properties or evaluate expressions', () => {
  assert.equal(lookup({}, 'constructor.prototype'), undefined);
  assert.equal(lookup(Object.create({ secret: 'hidden' }), 'secret'), undefined);
  assert.equal(text('{{globalThis.document.cookie}}', context), '');
  assert.equal(text('{{title + 1}}', context), '');
  assert.equal(lookup({ constructor: 'ordinary JSON key' }, 'constructor'), 'ordinary JSON key');
});
test('whole public image bindings require a valid permitted export before delegating', () => {
  const consumer = { manifest: { permissions: ['location'], uses: { pictures: { export: 'gallery.daily', shape: 'photos/1' } } }, state: { uses: { pictures: { image: 'https://example.invalid/image.jpg' } } } };
  const provider = { manifest: { exports: { daily: { path: 'public.daily', shape: 'photos/1', requires: ['location'] } } }, state: {} };
  const plugins = { consumer, gallery: provider };
  assert.equal(imagePrincipal('{{uses.pictures.image}}', 'consumer', plugins), 'gallery');
  for (const input of ['prefix{{uses.pictures.image}}', '{{uses.pictures.image | default ""}}', '{{uses.missing.image}}', 'https://example.invalid/image.jpg']) assert.equal(imagePrincipal(input, 'consumer', plugins), 'consumer');
  provider.manifest.exports.daily.shape = 'photos/2';
  assert.equal(imagePrincipal('{{uses.pictures.image}}', 'consumer', plugins), 'consumer');
  provider.manifest.exports.daily.shape = 'photos/1'; consumer.manifest.permissions = [];
  assert.equal(imagePrincipal('{{uses.pictures.image}}', 'consumer', plugins), 'consumer');
  consumer.manifest.permissions = ['location']; provider.manifest.exports.daily.path = 'config.secret';
  assert.equal(imagePrincipal('{{uses.pictures.image}}', 'consumer', plugins), 'consumer');
});
