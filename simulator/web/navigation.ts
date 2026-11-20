/** MIT. DeckRouter request/open policy around the unchanged Kotlin NavStack. */
import type { NativeNav } from '../reference/generated/thinkos-reference.mjs';
export interface NavigationPage { key: string; owner: string; roles: string[]; app: boolean; live: boolean; priority: number; variant: string }
export interface NavigationContext { pages: NavigationPage[]; current: string; variant: string; attention: string; now: number; lastLocal: number }
export interface NavigationSelection { page: string; variant: string }
export interface FocusRequest { page: string; live?: boolean; release?: boolean; answersTap?: boolean }
const selection = (page: NavigationPage): NavigationSelection => ({ page: page.key, variant: page.variant });
export function homePage(pages: NavigationPage[]): NavigationPage | undefined { return pages.find(page => page.roles.includes('home')) ?? pages[0]; }
export function applyNavigationChange(context: NavigationContext, change: string | null | undefined): NavigationSelection | null {
  if (change == null) return null;
  const [key, variant] = change.split('\n'), page = context.pages.find(page => page.key === key);
  return page ? { page: key, variant } : homePage(context.pages) ? selection(homePage(context.pages)!) : null;
}
function push(nav: NativeNav, context: NavigationContext, kind: string, page: NavigationPage): NavigationSelection | null {
  return nav.push(context.current, context.variant, kind, page.key, page.variant) ? selection(page) : null;
}
export function requestPageFocus(nav: NativeNav, context: NavigationContext, request: FocusRequest): NavigationSelection | null {
  const page = context.pages.find(page => page.key === request.page); if (!page) return null;
  if (nav.has('ONBOARDING')) {
    const current = context.pages.find(page => page.key === context.current);
    if (page.owner !== current?.owner || !page.roles.includes('onboarding')) return null;
    if (request.release) { nav.clear(); return applyNavigationChange(context, ''); }
    nav.replaceTop(page.key, page.variant); return selection(page);
  }
  if (page.roles.includes('onboarding') || nav.has('SETTINGS')) return null;
  if (request.release) return applyNavigationChange(context, nav.remove(page.key));
  if (context.attention === 'ASLEEP' || !request.answersTap && context.now - context.lastLocal < 30000) return null;
  if (request.live && !page.live) return null;
  const active = context.pages.find(page => page.key === context.current);
  if (nav.topKind === 'TAKEOVER' && active && page.priority < active.priority) return null;
  if (page.key === context.current) return selection(page);
  const unwound = nav.unwindTo(page.key); if (unwound != null) return applyNavigationChange(context, unwound);
  if (request.live && nav.topKind === 'TAKEOVER') { nav.replaceTop(page.key, page.variant); return selection(page); }
  if (request.live) return push(nav, context, 'TAKEOVER', page);
  if (page.app) return push(nav, context, 'APP', page);
  nav.clear(); return selection(page);
}
/** User-owned application/tile opening has its own native entry, not a plugin focus bypass. */
export function openPage(nav: NativeNav, context: NavigationContext, key: string): NavigationSelection | null {
  if (nav.has('SETTINGS') || nav.has('ONBOARDING')) return null;
  const page = context.pages.find(page => page.key === key); if (!page || page.roles.includes('onboarding')) return null;
  if (page.key === context.current) return selection(page);
  const unwound = nav.unwindTo(page.key); if (unwound != null) return applyNavigationChange(context, unwound);
  return page.app || page.live || nav.depth > 0 ? push(nav, context, 'APP', page) : selection(page);
}
export function openOnboarding(nav: NativeNav, context: NavigationContext, key: string): NavigationSelection | null {
  const candidates = context.pages.filter(page => page.roles.includes('onboarding'));
  const page = candidates.find(page => page.key === key) ?? candidates.find(page => page.key.endsWith('.hello')) ?? candidates[0]; if (!page) return null;
  if (nav.has('ONBOARDING')) { nav.replaceTop(page.key, page.variant); return selection(page); }
  nav.clear(); const home = homePage(context.pages); if (!home) return null;
  return nav.push(home.key, home.variant, 'ONBOARDING', page.key, page.variant) ? selection(page) : null;
}
