/** Pixel adapters around unchanged production Kotlin compiled by reference/build.py. */
import { nativeSlot, nativeTiles, nativeVertical, nativePanel, nativeTileColors } from '../../reference/generated/thinkos-reference.mjs';
export interface Box { x: number; y: number; w: number; h: number }
export const DENSITY = 1.625;
export const dp = (n: number) => Math.trunc(n * DENSITY);
export const inset = (b: Box, n: number): Box => ({ x: b.x + n, y: b.y + n, w: Math.max(0, b.w - n * 2), h: Math.max(0, b.h - n * 2) });
export const contains = (b: Box, x: number, y: number) => x >= b.x && y >= b.y && x < b.x + b.w && y < b.y + b.h;
export const intersect = (a: Box, b: Box): Box => ({ x: Math.max(a.x, b.x), y: Math.max(a.y, b.y), w: Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)), h: Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y)) });

export function stackBox(index: number, width: number, height: number): Box {
  const edge = dp(20), gap = dp(16);
  if (width > height) { const cell = Math.trunc((width - edge * 2 - gap) / 2); return { x: edge + index * (cell + gap), y: edge, w: cell, h: height - edge * 2 }; }
  const cell = Math.trunc((height - edge * 2 - gap) / 2); return { x: edge, y: edge + index * (cell + gap), w: width - edge * 2, h: cell };
}

function fromNative(value: number[] | null): Box | null {
  return value ? { x: value[0], y: value[1], w: value[2] - value[0], h: value[3] - value[1] } : null;
}
export function slotBox(layout: string, role: string, width: number, height: number, header = false, footer = false): Box {
  return fromNative(JSON.parse(nativeSlot(layout, role, width, height, DENSITY, header, footer)))!;
}
export function tileBoxes(sizes: string[], width: number, height: number, rtl = false): (Box | null)[] {
  return JSON.parse(nativeTiles(sizes.join(','), width, height, DENSITY, rtl)).map(fromNative);
}
export function verticalHeights(available: number, measured: number[], weights: number[], gap: number): { heights: number[]; gap: number; offset: number } {
  return JSON.parse(nativeVertical(Math.trunc(available), measured.map(Math.trunc).join(','), weights.join(','), Math.trunc(gap)));
}
export function panelBoxes(width: number, height: number, header: boolean, quick: boolean, levels: number, media: boolean, footer: boolean, update: boolean, quickCount: number): { width: number; height: number; slots: Record<string, Box | null> } {
  const result = JSON.parse(nativePanel(width, height, DENSITY, header, quick, levels, media, footer, update, quickCount));
  const slots: Record<string, Box | null> = {};
  for (const role of ['header', 'quick', 'levels', 'media', 'footer', 'update']) slots[role] = fromNative(result[role]);
  return { width: result.width, height: result.height, slots };
}
export function tileColors(value: string, night: boolean): { background: string; foreground: string } {
  return JSON.parse(nativeTileColors(value, night ? 1 : 0));
}
