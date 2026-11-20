/** MIT. Browser controls for the native form picker contract; no plugin-private network path. */
import { canonicalZone, cityName, localeCandidates, localeInfo } from '../locale-time.ts';
import { TIMEZONE_AREAS, manualPlace, offlinePlaces, regionEntries, timezoneEntries, validatedPlaces } from './picker-data.ts';
import type { PlaceSearchResult } from './picker-data.ts';
import { dialogString } from './dialog-strings.ts';

interface PickerOptions {
  field: Record<string, any>; title: string; locale: string; now: number; deviceTimezone: string; theme: string;
  systemStrings?: Record<string, Record<string, string>>;
  searchPlaces?: (query: string, locale: string) => Promise<PlaceSearchResult[]>;
  pick: (value: unknown) => void; close: () => void;
}

/** The owning Renderer appends/shows this dialog and closes it on navigation or destruction. */
export function createFieldPicker(options: PickerOptions): HTMLDialogElement {
  const { field, locale } = options;
  const dialog = document.createElement('dialog'); dialog.className = 'sim-field-editor sim-field-picker';
  dialog.dataset.source = field.source; dialog.dataset.theme = options.theme; dialog.dir = localeInfo(locale, true).rtl ? 'rtl' : 'ltr';
  const translate = (key: string, fallback: string) => {
    for (const candidate of localeCandidates(locale)) { const value = options.systemStrings?.[candidate]?.[key]; if (typeof value === 'string') return value; }
    return fallback;
  };
  const node = <K extends keyof HTMLElementTagNameMap>(tag: K, text = '', className = '') => {
    const element = document.createElement(tag); element.textContent = text; element.className = className; return element;
  };
  const button = (label: string, click: () => void, className = '') => { const element = node('button', label, className); element.type = 'button'; element.onclick = click; return element; };
  let generation = 0, manual = false;
  const alive = (id: number) => generation === id && dialog.isConnected && dialog.open;
  const back = () => { if (manual) { manual = false; showPlace(); } else options.close(); };
  dialog.addEventListener('close', () => { generation++; });
  dialog.addEventListener('cancel', event => { event.preventDefault(); back(); });
  const header = (manualAction?: () => void) => {
    const element = node('header', '', 'sim-picker-header');
    const previous = button('‹', back); previous.dataset.action = 'back'; previous.setAttribute('aria-label', translate('plugins_cancel', 'Cancel'));
    const title = node('h2', options.title); title.id = 'sim-picker-heading'; dialog.setAttribute('aria-labelledby', title.id);
    element.append(previous, title);
    if (manualAction) { const action = button(translate('setting_location_mode_manual', 'Manual'), manualAction); action.dataset.action = 'manual'; element.append(action); }
    return element;
  };
  const searchInput = (placeholder: string) => { const input = node('input'); input.type = 'search'; input.maxLength = 160; input.placeholder = placeholder; input.setAttribute('aria-label', placeholder); input.autocomplete = 'off'; return input; };
  const row = (title: string, detail: string, value: string, selected: boolean, click: () => void) => {
    const element = button('', click, 'sim-picker-row'); element.dataset.value = value; element.setAttribute('aria-pressed', String(selected));
    element.append(node('span', (selected ? '✓  ' : '') + title)); if (detail) element.append(node('small', detail)); return element;
  };
  const list = () => node('div', '', 'sim-picker-list');
  const pick = (value: unknown) => { generation++; options.pick(value); };

  function showRegion() {
    const search = searchInput(translate('form_region_search', 'Search country or region')), results = list();
    dialog.replaceChildren(header(), search, results);
    const render = () => {
      results.replaceChildren(); if (field.allowEmpty && !search.value.trim()) results.append(row(translate('form_region_all', 'All regions'), '', '', !field.value, () => pick('')));
      for (const entry of regionEntries(locale, String(field.value ?? ''), search.value)) results.append(row(entry.name, entry.code, entry.code, field.value === entry.code, () => pick(entry.code)));
    };
    search.oninput = render; render();
  }

  function showTimezone() {
    const selected = canonicalZone(String(field.value ?? '')), zone = selected ?? canonicalZone(options.deviceTimezone) ?? 'Etc/UTC';
    let area = zone.split('/')[0]; const search = searchInput(translate('form_tz_search', 'Search city or country')), areas = node('div', '', 'sim-picker-areas'), results = list();
    dialog.replaceChildren(header(), search, areas, results);
    const render = () => {
      areas.replaceChildren(); for (const name of TIMEZONE_AREAS) { const tab = button(translate(`tz_area_${name.toLowerCase()}`, name), () => { area = name; search.value = ''; render(); }); tab.dataset.area = name; tab.setAttribute('aria-pressed', String(area === name)); areas.append(tab); }
      results.replaceChildren(); if (field.allowEmpty && !search.value.trim()) results.append(row(translate('form_tz_none', 'None'), '', '', !field.value, () => pick('')));
      for (const entry of timezoneEntries(locale, options.now, area, search.value)) results.append(row(entry.city, [entry.country, entry.id, entry.offset, entry.dst ? translate('form_tz_dst', 'DST') : ''].filter(Boolean).join(' · '), entry.id, selected === entry.id, () => pick(entry.id)));
    };
    search.oninput = render; render();
  }

  let placeQuery = '', placeResults: PlaceSearchResult[] = [], placeMessage = '';
  function showPlace() {
    const viewGeneration = ++generation, search = searchInput(translate('form_place_search', 'Search city, district or county')); search.value = placeQuery;
    const searchForm = node('form', '', 'sim-picker-search'), submit = node('button', '⌕'); submit.type = 'submit'; submit.setAttribute('aria-label', search.placeholder);
    searchForm.append(search, submit); const status = node('p', placeMessage, 'sim-picker-status'); status.setAttribute('role', 'status');
    const results = list(), attribution = node('small', translate('form_place_attribution', 'Open-Meteo  GeoNames  CC BY 4.0'), 'sim-picker-attribution');
    dialog.replaceChildren(header(showManual), searchForm, status, results, attribution);
    const render = () => {
      results.replaceChildren(row(translate('setting_location_mode_auto', 'By time zone'), cityName(options.deviceTimezone), '@auto', field.place?.source === 'timezone', () => pick({ mode: 'auto' })));
      for (const entry of placeResults) {
        const current = field.place, location = entry.location, selected = current?.source !== 'timezone' && current?.name === location.name && Math.abs(Number(current?.latitude) - location.latitude) < 1e-6 && Math.abs(Number(current?.longitude) - location.longitude) < 1e-6;
        results.append(row(location.name, entry.detail, location.timezone, selected, () => pick({ ...location, mode: 'manual' })));
      }
      status.textContent = placeMessage; status.hidden = !placeMessage;
    };
    let busy = false;
    searchForm.onsubmit = async event => {
      event.preventDefault(); placeQuery = search.value.trim().slice(0, 160); if (busy || placeQuery.length < 2) return;
      busy = true; submit.disabled = true; placeMessage = translate('form_place_loading', 'Searching…'); render();
      try {
        if (!options.searchPlaces) throw Error('OS place search unavailable');
        const rows = await options.searchPlaces(placeQuery, locale); if (!alive(viewGeneration)) return;
        placeResults = validatedPlaces(rows); placeMessage = placeResults.length ? '' : translate('form_place_empty', 'No places found');
      } catch {
        if (!alive(viewGeneration)) return;
        placeResults = offlinePlaces(placeQuery, locale); placeMessage = translate('form_place_error', 'Online search unavailable');
      } finally {
        if (alive(viewGeneration)) { busy = false; submit.disabled = false; render(); }
      }
    };
    render();
  }

  function showManual() {
    generation++; manual = true;
    const form = node('form', '', 'sim-picker-manual'), inputs = new Map<string, HTMLInputElement>(); form.noValidate = true;
    const fields = node('section', '', 'sim-picker-manual-fields');
    for (const [key, fallback, min, max] of [['name', 'City, district or county'], ['latitude', 'Latitude', -90, 90], ['longitude', 'Longitude', -180, 180], ['elevation', 'Elevation', -500, 10000], ['timezone', 'Location time zone']] as const) {
      const label = node('label', translate(`setting_location_${key}`, fallback)), input = node('input'); input.name = key; input.maxLength = 160; input.value = String(field.place?.[key] ?? '');
      if (min !== undefined && max !== undefined) { input.type = 'number'; input.step = 'any'; input.min = String(min); input.max = String(max); input.required = true; }
      if (key === 'timezone') input.placeholder = translate('form_place_timezone_empty', 'Device time zone');
      label.append(input); fields.append(label); inputs.set(key, input);
    }
    const error = node('p', '', 'sim-picker-status sim-error'); error.setAttribute('role', 'alert'); error.hidden = true;
    const actions = node('footer', '', 'sim-picker-actions'), cancel = button(dialogString(locale, options.systemStrings, 'cancel'), back), save = node('button', dialogString(locale, options.systemStrings, 'confirm')); save.type = 'submit';
    actions.append(cancel, save); form.append(fields, error, actions); dialog.replaceChildren(header(), form);
    form.onsubmit = event => {
      event.preventDefault();
      try { pick(manualPlace(Object.fromEntries([...inputs].map(([name, input]) => [name, input.value])))); }
      catch { error.textContent = translate('form_place_invalid_values', 'Invalid location'); error.hidden = false; }
    };
    inputs.get('name')?.focus();
  }

  if (field.source === 'region') showRegion(); else if (field.source === 'timezone') showTimezone(); else showPlace();
  return dialog;
}
