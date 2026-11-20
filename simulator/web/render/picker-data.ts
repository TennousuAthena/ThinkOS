/** Form picker data and value rules ported from RegionPicker, TimezonePicker and PlaceSearch. */
import { TZ_DATA } from '../../generated/tz-data.ts';
import { canonicalZone, cityName, countryName, localeInfo, offsetLabel, zoneType } from '../locale-time.ts';

export const TIMEZONE_AREAS = ['Asia', 'Europe', 'America', 'Africa', 'Australia', 'Pacific', 'Atlantic', 'Indian', 'Antarctica', 'Arctic', 'Etc'];
export interface RegionEntry { code: string; name: string; search: string }
export interface TimezoneEntry { id: string; city: string; country: string; area: string; offset: string; dst: boolean; search: string }
export interface PlaceValue { mode: 'manual'; name: string; latitude: number; longitude: number; elevation: number; timezone: string; source?: 'manual' }
export interface PlaceSearchResult { location: Omit<PlaceValue, 'mode'>; detail: string }

const suggestions: Record<string, string[]> = { zh: ['CN', 'HK', 'TW', 'MO', 'SG'], en: ['US', 'GB', 'AU', 'CA', 'NZ', 'IN'], es: ['ES', 'MX', 'AR', 'CO'], fr: ['FR', 'CA', 'BE', 'CH'], pt: ['BR', 'PT'], ar: ['EG', 'SA', 'AE'], ru: ['RU'], hi: ['IN'], bn: ['BD', 'IN'], ur: ['PK', 'IN'] };
const queryText = (query: string, locale: string) => query.trim().slice(0, 160).toLocaleLowerCase(locale);
export function regionEntries(locale: string, selected = '', query = ''): RegionEntry[] {
  const collator = new Intl.Collator(locale), info = localeInfo(locale, true), queryValue = queryText(query, locale);
  const entries = [...new Set(Object.values(TZ_DATA.zones).map(zone => zone[0]).filter(code => /^[A-Z]{2}$/.test(code)))].map(code => {
    const name = countryName(code, locale); return { code, name, search: `${code} ${name} ${countryName(code, 'en')}`.toLocaleLowerCase(locale) };
  }).sort((a, b) => collator.compare(a.name, b.name));
  if (queryValue) return entries.filter(entry => entry.search.includes(queryValue));
  const codes = [...new Set([selected.toUpperCase(), info.region, ...(suggestions[info.language] ?? [])].filter(Boolean))];
  return [...codes.map(code => entries.find(entry => entry.code === code)).filter((entry): entry is RegionEntry => !!entry), ...entries.filter(entry => !codes.includes(entry.code))];
}

export function timezoneEntries(locale: string, now: number, area: string, query = ''): TimezoneEntry[] {
  const search = queryText(query, locale), collator = new Intl.Collator(locale);
  return Object.entries(TZ_DATA.zones).map(([id, record]) => {
    const city = cityName(id), country = countryName(record[0], locale), type = zoneType(id, now);
    return { id, city, country, area: id.split('/')[0], offset: offsetLabel(type.offset), dst: type.dst, search: `${city} ${country} ${id.replaceAll('_', ' ')}`.toLocaleLowerCase(locale) };
  }).filter(entry => search ? entry.search.includes(search) : entry.area === area).sort((a, b) => collator.compare(a.city, b.city));
}

/** No fallback to 0: missing coordinates must be entered explicitly before a manual commit. */
export function manualPlace(input: Record<string, unknown>): PlaceValue {
  const number = (key: string, low: number, high: number) => {
    const value = input[key];
    if (!(typeof value === 'number' || typeof value === 'string' || value instanceof Number) || String(value).trim() === '') throw Error('invalid location');
    const parsed = Number(value); if (!Number.isFinite(parsed) || parsed < low || parsed > high) throw Error('invalid location'); return parsed;
  };
  const rawZone = typeof input.timezone === 'string' ? input.timezone.trim() : '';
  const timezone = rawZone ? canonicalZone(rawZone) : '';
  if (timezone === null || typeof input.name !== 'string' || input.name.length > 160 || /[\x00-\x1f\x7f]/.test(input.name)) throw Error('invalid location');
  return { mode: 'manual', name: input.name.trim(), latitude: number('latitude', -90, 90), longitude: number('longitude', -180, 180), elevation: number('elevation', -500, 10000), timezone };
}

/** The same bounded offline fallback as PlaceSearch.offline, using zone.tab coordinates. */
export function offlinePlaces(query: string, locale: string): PlaceSearchResult[] {
  const search = queryText(query, locale); if (search.length < 2) return [];
  return Object.entries(TZ_DATA.zones).filter(([id]) => !id.startsWith('Etc/')).map(([id, zone]) => {
    const name = cityName(id), detail = countryName(zone[0], locale);
    return { location: { name, latitude: zone[1], longitude: zone[2], elevation: 0, timezone: id, source: 'manual' as const }, detail };
  }).filter(entry => `${entry.location.name} ${entry.detail} ${entry.location.timezone.replaceAll('_', ' ')}`.toLocaleLowerCase(locale).includes(search))
    .sort((a, b) => Number(!a.location.name.toLocaleLowerCase(locale).startsWith(search)) - Number(!b.location.name.toLocaleLowerCase(locale).startsWith(search)) || a.location.name.localeCompare(b.location.name, locale)).slice(0, 20);
}

/** UI boundary: ignore malformed gateway rows and never place untrusted markup into the DOM. */
export function validatedPlaces(raw: unknown): PlaceSearchResult[] {
  if (!Array.isArray(raw)) throw Error('Invalid place search response');
  const clean = (value: unknown) => typeof value === 'string' ? value.replace(/[\x00-\x1f\x7f]/g, '').trim().slice(0, 160) : '';
  return raw.slice(0, 20).flatMap(entry => {
    if (!entry || typeof entry !== 'object' || !entry.location || typeof entry.location !== 'object') return [];
    try {
      const location = manualPlace({ ...entry.location, name: clean(entry.location.name), timezone: clean(entry.location.timezone) });
      if (!location.name || !location.timezone) return [];
      return [{ location: { name: location.name, latitude: location.latitude, longitude: location.longitude, elevation: location.elevation, timezone: location.timezone, source: 'manual' as const }, detail: clean(entry.detail) }];
    } catch { return []; }
  });
}
