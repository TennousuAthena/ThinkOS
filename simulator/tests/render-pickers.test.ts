import test from 'node:test';
import assert from 'node:assert/strict';
import { manualPlace, offlinePlaces, regionEntries, timezoneEntries, validatedPlaces } from '../web/render/picker-data.ts';
import { dialogString } from '../web/render/dialog-strings.ts';

test('region inventory, suggestions and localized/English/code search follow native contract', () => {
  const entries = regionEntries('zh-Hans', 'US');
  assert.ok(entries.length > 200); assert.equal(new Set(entries.map(entry => entry.code)).size, entries.length);
  assert.deepEqual(entries.slice(0, 4).map(entry => entry.code), ['US', 'CN', 'HK', 'TW']);
  assert.equal(regionEntries('fr', '', 'United States')[0].code, 'US');
  assert.equal(regionEntries('zh-Hans', '', '中国')[0].code, 'CN');
  assert.ok(regionEntries('en-GB')[0].code === 'GB');
});

test('timezone list switches areas, searches globally and uses bundled DST offsets', () => {
  const now = Date.UTC(2026, 6, 1), entries = timezoneEntries('en', now, 'Asia');
  assert.ok(entries.length > 50); assert.ok(entries.every(entry => entry.area === 'Asia'));
  const london = timezoneEntries('en', now, 'Asia', 'London').find(entry => entry.id === 'Europe/London');
  assert.ok(london); assert.equal(london.dst, true); assert.match(london.offset, /1/);
  assert.equal(timezoneEntries('en', Date.UTC(2026, 0, 1), 'Europe', 'London')[0].dst, false);
});

const draft = { name: '  Home ', latitude: '0', longitude: '-180', elevation: '-500', timezone: '' };
test('manual place commits explicit bounded coordinates and canonical or empty timezone', () => {
  assert.deepEqual(manualPlace(draft), { mode: 'manual', name: 'Home', latitude: 0, longitude: -180, elevation: -500, timezone: '' });
  assert.equal(manualPlace({ ...draft, timezone: 'US/Eastern' }).timezone, 'America/New_York');
  assert.equal(manualPlace({ ...draft, latitude: new Number(19) }).latitude, 19);
  for (const key of ['latitude', 'longitude', 'elevation']) for (const value of ['', '  ', null, undefined, [], {}, NaN, Infinity, '-Infinity']) assert.throws(() => manualPlace({ ...draft, [key]: value }));
  for (const changes of [{ latitude: 90.1 }, { latitude: -90.1 }, { longitude: 180.1 }, { elevation: 10001 }, { elevation: -501 }, { timezone: 'Bad/Zone' }, { name: 'a\u0000b' }]) assert.throws(() => manualPlace({ ...draft, ...changes }));
});

test('offline search is bounded and uses zone.tab coordinates, not empty defaults', () => {
  assert.deepEqual(offlinePlaces('L', 'en'), []);
  const london = offlinePlaces('London', 'en')[0]; assert.equal(london.location.timezone, 'Europe/London');
  assert.ok(london.location.latitude > 50 && london.location.latitude < 52);
  assert.equal(london.location.elevation, 0); assert.equal(london.location.source, 'manual');
  assert.ok(offlinePlaces('America', 'en').length <= 20);
});

test('external search rejects malformed and incomplete rows, clips count and cleans strings', () => {
  assert.throws(() => validatedPlaces({ items: [] }));
  const row = { location: { name: ' Lon\u0000don ', latitude: new Number(51), longitude: 0, elevation: 0, timezone: 'Europe/London' }, detail: ' a\nb ' };
  const rows = validatedPlaces([null, {}, { location: {} }, { location: { ...row.location, latitude: '' } }, { location: { ...row.location, timezone: '' } }, row]);
  assert.equal(rows.length, 1); assert.equal(rows[0].location.name, 'London'); assert.equal(rows[0].detail, 'ab');
  assert.equal(validatedPlaces(Array.from({ length: 40 }, () => row)).length, 20);
});

test('dialog labels use the active locale and system override across shipped locales', () => {
  for (const locale of ['en', 'zh-Hans', 'zh-Hant', 'hi', 'es', 'fr', 'ar', 'bn', 'pt', 'ru', 'ur']) {
    assert.ok(dialogString(locale, undefined, 'confirm')); assert.ok(dialogString(locale, undefined, 'cancel'));
  }
  assert.equal(dialogString('zh-HK', undefined, 'confirm'), '確定');
  assert.equal(dialogString('fr', { fr: { plugins_cancel: 'Annulation', form_confirm: 'Valider' } }, 'confirm'), 'Valider');
  assert.equal(dialogString('fr', { fr: { plugins_cancel: 'Annulation' } }, 'cancel'), 'Annulation');
});
