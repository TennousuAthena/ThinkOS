import test from 'node:test';
import assert from 'node:assert/strict';
import { NativeNav } from '../reference/generated/thinkos-reference.mjs';
import { applyNavigationChange, homePage, openOnboarding, openPage, requestPageFocus, type NavigationContext, type NavigationPage } from '../web/navigation.ts';

const page = (key: string, changes: Partial<NavigationPage> = {}): NavigationPage => ({ key, owner: key.split('.')[0], roles: [], app: false, live: false, priority: 0, variant: 'main', ...changes });
const pages = [page('home.main', { roles: ['home'], variant: 'wide' }), page('clock.main'), page('settings.main', { roles: ['settings'] }), page('welcome.hello', { roles: ['onboarding'] }), page('welcome.next', { roles: ['onboarding'] }), page('other.setup', { roles: ['onboarding'] }), page('player.main', { app: true }), page('alarm.low', { live: true, priority: 1 }), page('alarm.high', { live: true, priority: 2 }), page('video.main', { live: true, priority: 2 })];
function fixture() {
  const nav = new NativeNav(), context: NavigationContext = { pages, current: 'home.main', variant: 'wide', attention: 'AWAKE', now: 100000, lastLocal: -Infinity };
  const focus = (request: Parameters<typeof requestPageFocus>[2]) => { const selected = requestPageFocus(nav, context, request); if (selected) { context.current = selected.page; context.variant = selected.variant; } return selected; };
  const open = (key: string) => { const selected = openPage(nav, context, key); if (selected) { context.current = selected.page; context.variant = selected.variant; } return selected; };
  return { nav, context, focus, open };
}

test('focus protects settings and onboarding even from live requests or answersTap', () => {
  const { nav, context, focus } = fixture(); nav.push('home.main', 'wide', 'SETTINGS', 'settings.main', 'main'); context.current = 'settings.main';
  assert.equal(focus({ page: 'alarm.high', live: true, answersTap: true }), null);
  assert.equal(focus({ page: 'settings.main', release: true }), null); assert.equal(nav.depth, 1);
  nav.clear(); const selected = openOnboarding(nav, context, 'welcome.hello')!; context.current = selected.page;
  assert.equal(focus({ page: 'other.setup', answersTap: true }), null); assert.equal(focus({ page: 'player.main', answersTap: true }), null);
  assert.equal(focus({ page: 'welcome.next' })?.page, 'welcome.next'); assert.equal(nav.depth, 1); assert.equal(nav.topKind, 'ONBOARDING');
  assert.equal(focus({ page: 'welcome.next', release: true })?.page, 'home.main'); assert.equal(nav.depth, 0);
  assert.equal(focus({ page: 'welcome.hello', answersTap: true }), null);
});

test('focus checks asleep, 30-second local activity window, and declared live capability', () => {
  const { context, focus } = fixture(); context.attention = 'ASLEEP'; assert.equal(focus({ page: 'player.main', answersTap: true }), null);
  context.attention = 'AWAKE'; context.lastLocal = context.now - 29999; assert.equal(focus({ page: 'player.main' }), null);
  assert.equal(focus({ page: 'player.main', answersTap: true })?.page, 'player.main');
  context.lastLocal = context.now - 30000; assert.equal(focus({ page: 'clock.main' })?.page, 'clock.main');
  assert.equal(focus({ page: 'clock.main', live: true, answersTap: true }), null);
});

test('takeovers preserve original base, enforce priority and replace the top layer', () => {
  const { nav, focus, context } = fixture(); assert.equal(focus({ page: 'alarm.high', live: true })?.page, 'alarm.high'); assert.equal(nav.depth, 1);
  assert.equal(focus({ page: 'alarm.low', live: true, answersTap: true }), null); assert.equal(nav.topPage, 'alarm.high');
  assert.equal(focus({ page: 'video.main', live: true })?.page, 'video.main'); assert.equal(nav.depth, 1);
  const shown = applyNavigationChange(context, nav.pop()); assert.deepEqual(shown, { page: 'home.main', variant: 'wide' });
});

test('targeted release removes only its own layer and never blindly pops the current page', () => {
  const { nav, focus, open, context } = fixture(); open('player.main'); focus({ page: 'alarm.high', live: true });
  assert.equal(focus({ page: 'clock.main', release: true }), null); assert.equal(nav.depth, 2); assert.equal(context.current, 'alarm.high');
  assert.equal(focus({ page: 'player.main', release: true }), null); assert.equal(nav.depth, 1); assert.equal(context.current, 'alarm.high');
  assert.deepEqual(focus({ page: 'alarm.high', release: true }), { page: 'home.main', variant: 'wide' });
});

test('ordinary focus unwinds an existing app and clears layers when changing the base', () => {
  const { nav, focus, open, context } = fixture(); open('player.main'); focus({ page: 'alarm.low', live: true });
  // Existing native priority also gates a request to unwind to a lower-priority page.
  assert.equal(focus({ page: 'player.main', answersTap: true }), null);
  focus({ page: 'alarm.low', release: true }); open('clock.main'); assert.equal(nav.depth, 2);
  assert.equal(focus({ page: 'player.main' })?.page, 'player.main'); assert.equal(nav.depth, 1);
  assert.equal(focus({ page: 'clock.main' })?.page, 'clock.main'); assert.equal(nav.depth, 0);
  assert.equal(context.current, 'clock.main');
});

test('app entry guards, one native stack, catalog pruning and custom-plugin home fallback', () => {
  const { nav, context, open } = fixture(); assert.equal(open('player.main')?.page, 'player.main');
  nav.variantChanged('album'); nav.push('player.main', 'album', 'SETTINGS', 'settings.main', 'main'); context.current = 'settings.main';
  assert.equal(open('clock.main'), null); assert.equal(nav.has('SETTINGS'), true);
  assert.deepEqual(applyNavigationChange(context, nav.removeKind('SETTINGS')), { page: 'player.main', variant: 'album' });
  assert.deepEqual(applyNavigationChange(context, nav.prune('home.main')), { page: 'home.main', variant: 'wide' });
  assert.equal(homePage([page('solo.main')])?.key, 'solo.main'); assert.equal(homePage([]), undefined);
});

test('full native stack denies new focus without changing current selection', () => {
  const { nav, context, focus } = fixture(); for (let i = 0; i < 8; i++) assert.equal(nav.push(context.current, 'main', 'APP', 'layer' + i, 'main'), true);
  assert.equal(focus({ page: 'player.main' }), null); assert.equal(context.current, 'home.main'); assert.equal(nav.depth, 8);
});
