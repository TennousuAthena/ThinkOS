import CanvasKitInit from 'canvaskit-wasm';
import wasmUrl from 'canvaskit-wasm/bin/canvaskit.wasm?url';
import { array, bound, imagePrincipal, lookup, text } from './render/binding.ts';
import type { BindingContext } from './render/binding.ts';
import { contains, DENSITY, dp, inset, intersect, panelBoxes, slotBox, stackBox, tileBoxes, tileColors, verticalHeights } from './render/geometry.ts';
import type { Box } from './render/geometry.ts';
import { formatTime, homeZone, zoneType } from './locale-time.ts';
import { createFieldPicker } from './render/form-picker.ts';
import { dialogString } from './render/dialog-strings.ts';
import type { PlaceSearchResult } from './render/picker-data.ts';
export type { PlaceSearchResult } from './render/picker-data.ts';

type Json = Record<string, any>;
export interface RendererPlugin { manifest: Json; views: Record<string, Json>; state: Json; config: Json; i18n: Json; assets: Record<string, string> }
export interface Scene {
  plugins: Record<string, RendererPlugin>; page: string; variant?: string;
  theme: 'LIGHT' | 'DARK' | 'NIGHT'; orientation: 'landscape' | 'portrait'; fontScale: number; now: number;
  media?: Json; locale?: string; settings?: Json; timezone?: string; hour24?: boolean; location?: Json;
}
export interface RendererEvent { pluginId: string; event: string; args: unknown[] }
export interface RendererOptions {
  fonts?: Array<{ family: string; data: Uint8Array }>;
  glyphs?: Record<string, string>;
  loadImage?: (pluginId: string, source: string) => Promise<Uint8Array | null>;
  video?: HTMLVideoElement;
  systemStrings?: Record<string, Record<string, string>>;
  searchPlaces?: (query: string, locale: string) => Promise<PlaceSearchResult[]>;
}
export { RENDER_VOCABULARY } from './render/vocabulary.ts';

interface Palette { bg: string; surface: string; primary: string; secondary: string; accent: string; divider: string; warn: string }
const DARK: Palette = { bg: '#0e0f12', surface: '#1b1d22', primary: '#f2f3f5', secondary: '#8d929b', accent: '#7c95ff', divider: '#2a2d33', warn: '#ff7a6b' };
const LIGHT: Palette = { bg: '#f7f8fa', surface: '#ffffff', primary: '#1b1d21', secondary: '#6b7280', accent: '#5b7cfa', divider: '#e8eaef', warn: '#f26b5e' };
const NIGHT: Palette = { bg: '#080000', surface: '#140202', primary: '#ed3934', secondary: '#8c2826', accent: '#ff4942', divider: '#38100e', warn: '#ff655a' };
interface Context extends BindingContext { id: string; plugin: RendererPlugin }
interface Hit { box: Box; pluginId: string; tap?: () => void; hold?: () => void; drag?: (x: number, y: number, final: boolean) => void; scroll?: (delta: number) => void; label?: string }
interface TextOptions { size?: number; color?: string; align?: 'left' | 'center' | 'right'; weight?: number; lines?: number; fit?: boolean }

/** CanvasKit owns the device viewport. This renderer is explicitly not visually calibrated yet. */
export class Renderer {
  readonly calibration = { visual: 'uncalibrated', text: 'browser-fallback' };
  private ck: any;
  private surface: any;
  private canvas: HTMLCanvasElement;
  private emit: (event: RendererEvent) => void;
  private options: RendererOptions;
  private scene?: Scene;
  private palette: Palette = DARK;
  private fontProvider: any;
  private fontFamilies: string[] = [];
  private hits: Hit[] = [];
  private scrolls = new Map<string, number>();
  private stackIndices = new Map<string, number>();
  private imageCache = new Map<string, any>();
  private imagePending = new Set<string>();
  private textCache = new Map<string, any>();
  private glyphPaths = new Map<string, any>();
  private frame = 0;
  private destroyed = false;
  private scheduled = false;
  private lastTick = 0;
  private down?: { x: number; y: number; at: number; hit?: Hit; moved: boolean; held: boolean; lastY: number };
  private holdTimer?: ReturnType<typeof setTimeout>;
  private inputDialog?: HTMLDialogElement;
  private pointerHandlers: Array<[string, EventListener]> = [];
  private drawing: any;
  private clip?: Box;
  private formLayouts = new Map<string, boolean>();
  private diagnostics = new Set<string>();
  private tileAnimations = new Map<string, { name: string; started: number }>();
  private tileFaceOffsets = new Map<string, number>();
  private visibleTileOwners = new Set<string>();
  private assetGenerations = new WeakMap<object, number>();
  private nextAssetGeneration = 0;
  private videoControlsUntil = 0;
  private clockBase = 0;
  private clockAnchor = 0;

  private constructor(canvas: HTMLCanvasElement, emit: (event: RendererEvent) => void, options: RendererOptions, ck: any) {
    this.canvas = canvas; this.emit = emit; this.options = options; this.ck = ck;
    canvas.width = 1280; canvas.height = 800;
    canvas.style.touchAction = 'none'; canvas.tabIndex = 0;
    canvas.setAttribute('aria-label', 'ThinkOS 插件预览（视觉未校准）');
    this.surface = ck.MakeCanvasSurface(canvas);
    if (!this.surface) throw new Error('CanvasKit could not create a drawing surface');
    if (options.fonts?.length) {
      this.fontProvider = ck.TypefaceFontProvider.Make();
      for (const font of options.fonts) { this.fontProvider.registerFont(font.data, font.family); if (!this.fontFamilies.includes(font.family)) this.fontFamilies.push(font.family); }
      this.calibration.text = 'supplied-fonts-unverified';
    }
    this.installInput();
    this.frame = requestAnimationFrame(this.tick);
  }

  static async create(canvas: HTMLCanvasElement, emit: (event: RendererEvent) => void, options: RendererOptions = {}): Promise<Renderer> {
    const ck = await CanvasKitInit({ locateFile: () => wasmUrl });
    return new Renderer(canvas, emit, options, ck);
  }

  setScene(scene: Scene): void {
    const old = this.scene;
    this.clockBase = scene.now; this.clockAnchor = performance.now();
    this.scene = scene; this.palette = scene.theme === 'LIGHT' ? LIGHT : scene.theme === 'NIGHT' ? NIGHT : DARK;
    if (old?.page !== scene.page || old?.orientation !== scene.orientation) { this.cancelPointer(); this.closeEditor(); this.tileAnimations.clear(); }
    if (scene.settings?.['tiles.live'] === false || scene.settings?.['motion.reduced'] === true) this.tileAnimations.clear();
    const width = scene.orientation === 'landscape' ? 1280 : 800, height = scene.orientation === 'landscape' ? 800 : 1280;
    if (this.canvas.width !== width || this.canvas.height !== height) {
      this.surface.delete(); this.canvas.width = width; this.canvas.height = height;
      this.surface = this.ck.MakeCanvasSurface(this.canvas);
      if (!this.surface) throw new Error('CanvasKit surface resize failed');
    }
    this.scheduled = true;
  }

  getDiagnostics(): string[] { return [...this.diagnostics]; }
  playTileAnimation(pluginId: string, name: string): boolean {
    if (!this.visibleTileOwners.has(pluginId) || !['pulse', 'nudge', 'flip'].includes(name) || this.scene?.settings?.['tiles.live'] === false || this.scene?.settings?.['motion.reduced'] === true || this.destroyed) return false;
    this.tileAnimations.set(pluginId, { name, started: performance.now() }); this.scheduled = true; return true;
  }
  getHitRegions(): Array<{ box: Box; pluginId: string; label?: string }> { return this.hits.map(({ box, pluginId, label }) => ({ box: { ...box }, pluginId, label })); }
  capturePng(): Uint8Array {
    if (this.destroyed || !this.scene) throw new Error('Renderer has no active scene');
    this.draw();
    const snapshot = this.surface.makeImageSnapshot();
    try { const bytes = snapshot.encodeToBytes(this.ck.ImageFormat.PNG); if (!bytes) throw new Error('PNG encoding failed'); return bytes.slice(); }
    finally { snapshot.delete(); }
  }

  destroy(): void {
    this.destroyed = true; cancelAnimationFrame(this.frame); this.cancelPointer(); this.closeEditor();
    for (const [name, listener] of this.pointerHandlers) this.canvas.removeEventListener(name, listener);
    for (const entry of this.imageCache.values()) entry?.delete?.();
    for (const entry of this.textCache.values()) entry?.delete?.();
    for (const entry of this.glyphPaths.values()) entry?.delete?.();
    this.fontProvider?.delete(); this.surface?.delete();
  }

  private tick = (now: number) => {
    if (this.destroyed) return;
    // Rendering cadence is a preview choice; it is not a ThinkSmart performance measurement.
    const videoActive = this.options.video && !this.options.video.paused && this.options.video.readyState >= 2;
    if (this.scene && (this.scheduled || now - this.lastTick > (videoActive || this.tileAnimations.size ? 16 : 250))) {
      this.lastTick = now; this.scheduled = false;
      try { this.draw(); delete this.canvas.dataset.renderError; this.canvas.dataset.renderedAt = String(now); this.canvas.dataset.renderedPage = this.scene.page; this.canvas.dataset.renderedLocale = this.scene.locale ?? ''; this.canvas.dataset.renderWarnings = [...this.diagnostics].join('\n'); }
      catch (error) { const message = error instanceof Error ? error.message : String(error); this.diagnostics.add(`render: ${message}`); this.canvas.dataset.renderError = message; }
    }
    this.frame = requestAnimationFrame(this.tick);
  };

  private context(id: string): Context | undefined {
    const plugin = this.scene?.plugins[id]; if (!plugin) return undefined;
    // Runtime supplies the permission-filtered public view; rendering never reads another plugin's private state.
    return { id, plugin, state: { ...plugin.state, config: plugin.config, ...(plugin.manifest.permissions?.includes('media') ? { media: this.scene?.media ?? {} } : {}) }, config: plugin.config, i18n: plugin.i18n, variant: this.scene?.variant ?? plugin.state.variant, locale: this.scene?.locale, now: this.previewTime() };
  }
  private previewTime() { return this.clockBase + performance.now() - this.clockAnchor; }

  private send(id: string, event: string, ...args: unknown[]) { this.emit({ pluginId: id, event, args }); }
  private warn(message: string) { if (this.diagnostics.size < 128) this.diagnostics.add(message); }
  private trimImages(cache: Map<string, any>, entries: number, pixels: number) {
    let used = [...cache.values()].reduce((sum, image) => sum + (image ? image.width() * image.height() : 0), 0);
    while (cache.size > entries || used > pixels) { const key = cache.keys().next().value!; const image = cache.get(key); if (image) { used -= image.width() * image.height(); image.delete(); } cache.delete(key); }
  }
  private addHit(hit: Hit) { const box = this.clip ? intersect(this.clip, hit.box) : hit.box; if (box.w > 0 && box.h > 0) this.hits.push({ ...hit, box }); }
  private paint(color: string, stroke?: number) {
    const p = new this.ck.Paint(); p.setAntiAlias(true); p.setColor(this.ck.parseColorString(color));
    if (stroke) { p.setStyle(this.ck.PaintStyle.Stroke); p.setStrokeWidth(stroke); p.setStrokeCap(this.ck.StrokeCap.Round); p.setStrokeJoin(this.ck.StrokeJoin.Round); }
    return p;
  }
  private rect(b: Box, color: string, radius = 0, stroke?: number) {
    if (b.w <= 0 || b.h <= 0) return;
    const p = this.paint(color, stroke), r = this.ck.XYWHRect(b.x, b.y, b.w, b.h);
    if (radius) this.drawing.drawRRect(this.ck.RRectXY(r, radius, radius), p); else this.drawing.drawRect(r, p);
    p.delete();
  }
  private circle(x: number, y: number, radius: number, color: string, stroke?: number) { if (radius <= 0) return; const p = this.paint(color, stroke); this.drawing.drawCircle(x, y, radius, p); p.delete(); }
  private line(x1: number, y1: number, x2: number, y2: number, color: string, width = dp(2)) { const p = this.paint(color, width); this.drawing.drawLine(x1, y1, x2, y2, p); p.delete(); }
  private drawPath(pathOrBuilder: any, paint: any) {
    const path = typeof pathOrBuilder.snapshot === 'function' ? pathOrBuilder.snapshot() : pathOrBuilder;
    this.drawing.drawPath(path, paint);
    if (path !== pathOrBuilder) path.delete();
  }
  private clipped(b: Box, draw: () => void, radius = 0) {
    this.drawing.save(); const r = this.ck.XYWHRect(b.x, b.y, b.w, b.h), old = this.clip;
    if (radius) this.drawing.clipRRect(this.ck.RRectXY(r, radius, radius), this.ck.ClipOp.Intersect, true); else this.drawing.clipRect(r, this.ck.ClipOp.Intersect, false);
    this.clip = old ? intersect(old, b) : b;
    try { draw(); } finally { this.clip = old; this.drawing.restore(); }
  }

  private label(value: string, b: Box, options: TextOptions = {}) {
    if (!value || b.w < 1 || b.h < 1) return;
    const size = (options.size ?? 18) * DENSITY * (this.scene?.fontScale ?? 1), color = options.color ?? this.palette.primary;
    const lines = options.lines ?? 2, align = options.align ?? 'center';
    const key = JSON.stringify([value, Math.floor(b.w), Math.floor(b.h), size, color, lines, align, options.weight, options.fit, this.scene?.locale]);
    let image = this.textCache.get(key);
    if (!image) {
      // Fallback is explicitly reported by calibration.text. It keeps diagnostics readable before ROM fonts are supplied.
      const raster = document.createElement('canvas'); raster.width = Math.min(2048, Math.max(1, Math.ceil(b.w))); raster.height = Math.min(2048, Math.max(1, Math.ceil(b.h)));
      const canvas = raster.getContext('2d')!;
      if (this.fontProvider) {
        const traditional = /^zh-(?:Hant|TW|HK|MO)(?:-|$)/i.test(this.scene?.locale ?? '');
        const cjk = traditional ? 'Noto Sans CJK TC' : 'Noto Sans CJK SC';
        const fontFamilies = this.fontFamilies.toSorted((a, b) => (a === 'Roboto' ? 0 : a === cjk ? 1 : 2) - (b === 'Roboto' ? 0 : b === cjk ? 1 : 2));
        const temporary = this.ck.MakeSurface(raster.width, raster.height);
        if (temporary) {
          const makeParagraph = (fontSize: number) => {
            const builder = this.ck.ParagraphBuilder.MakeFromFontProvider(new this.ck.ParagraphStyle({ textStyle: { color: this.ck.parseColorString(color), fontFamilies, fontSize, fontStyle: { weight: options.weight ?? 400 }, fontFeatures: [{ name: 'tnum', value: 1 }] }, textAlign: align === 'left' ? this.ck.TextAlign.Left : align === 'right' ? this.ck.TextAlign.Right : this.ck.TextAlign.Center, maxLines: lines, ellipsis: '…', textDirection: /^(ar|ur|he)/.test(this.scene?.locale ?? '') ? this.ck.TextDirection.RTL : this.ck.TextDirection.LTR }), this.fontProvider);
            try { builder.addText(value.slice(0, 8192)); return builder.build(); } finally { builder.delete(); }
          };
          let p = makeParagraph(size); p.layout(raster.width);
          if (options.fit) {
            // Tile values and clocks fit a single line in Android. Measure intrinsic width
            // before the ellipsis and include the font ascent/descent in the height limit.
            const scale = Math.min(1, raster.width / Math.max(1, p.getMaxIntrinsicWidth()), raster.height / Math.max(1, p.getHeight()));
            if (scale < 1) { p.delete(); p = makeParagraph(Math.max(1, size * scale * .99)); p.layout(raster.width); }
          }
          temporary.getCanvas().clear(this.ck.TRANSPARENT); temporary.getCanvas().drawParagraph(p, 0, Math.max(0, (raster.height - p.getHeight()) / 2));
          temporary.flush(); image = temporary.makeImageSnapshot(); p.delete(); temporary.delete();
        }
      }
      if (!image) {
        let fontSize = size; canvas.font = `${options.weight ?? 400} ${fontSize}px system-ui, sans-serif`;
        if (options.fit) { fontSize *= Math.min(1, b.w / Math.max(1, canvas.measureText(value).width), b.h / (fontSize * 1.2)); canvas.font = `${options.weight ?? 400} ${fontSize}px system-ui, sans-serif`; }
        const rows: string[] = []; let row = '';
        for (const character of [...value].slice(0, 8192)) {
          if (character === '\n' || (row && canvas.measureText(row + character).width > b.w)) { rows.push(row); row = character === '\n' ? '' : character; }
          else row += character;
        }
        if (row) rows.push(row);
        const allowed = Math.max(1, Math.min(lines, Math.floor(b.h / (fontSize * 1.2))));
        if (rows.length > allowed) { rows.length = allowed; let last = rows[allowed - 1]; while (last && canvas.measureText(last + '…').width > b.w) last = last.slice(0, -1); rows[allowed - 1] = last + '…'; }
        canvas.fillStyle = color; canvas.textAlign = align; canvas.textBaseline = 'middle';
        const x = align === 'left' ? 0 : align === 'right' ? raster.width : raster.width / 2, lineHeight = fontSize * 1.2;
        rows.forEach((line, i) => canvas.fillText(line, x, raster.height / 2 + (i - (rows.length - 1) / 2) * lineHeight));
        image = this.ck.MakeImageFromCanvasImageSource(raster);
      }
      this.textCache.set(key, image);
      this.trimImages(this.textCache, 256, 16 * 1024 * 1024);
    }
    this.drawing.drawImage(image, b.x, b.y);
  }

  private draw() {
    const scene = this.scene!; this.drawing = this.surface.getCanvas(); this.drawing.clear(this.ck.parseColorString(this.palette.bg)); this.hits = []; this.clip = undefined; this.visibleTileOwners.clear();
    const [id, pageId] = scene.page.split('.'), context = this.context(id), viewport = { x: 0, y: 0, w: this.canvas.width, h: this.canvas.height };
    if (!context) { this.label('ThinkOS', viewport, { size: 32 }); this.surface.flush(); return; }
    const page = (context.plugin.manifest.pages ?? []).find((p: Json) => p.id === pageId), view = context.plugin.views[page?.view ?? pageId];
    if (!view) { this.label(`页面不可用 · ${scene.page}`, viewport, { size: 18, color: this.palette.warn }); this.warn(`Missing page: ${scene.page}`); }
    else {
      const variants = view.variants ?? [];
      if (variants.length && !variants.some((v:Json) => v.id === context.variant)) context.variant = variants[0].id;
      this.layout(view, context, viewport, page);
    }
    this.surface.flush();
  }

  private layout(view: Json, context: Context, b: Box, page?: Json) {
    if (view.template) { this.widgetBody(view, context, b, 'large'); return; }
    const slots = view.slots ?? {}, name = view.layout;
    if (name === 'stacks') {
      ['left', 'right'].forEach((side, index) => {
        const refs = Array.isArray(slots[side]) ? slots[side] : [], key = `${this.scene!.page}:${side}`, position = (this.stackIndices.get(key) ?? 0) % Math.max(1, refs.length);
        const box = stackBox(index, b.w, b.h); this.rect(box, this.palette.surface, dp(32));
        if (refs[position]) this.clipped(box, () => this.widget(refs[position], context, inset(box, dp(24)), 'large'), dp(32));
        this.addHit({ box, pluginId: context.id, scroll: delta => { if (Math.abs(delta) < 20 || !refs.length) return; this.stackIndices.set(key, (position + (delta > 0 ? 1 : refs.length - 1)) % refs.length); this.scheduled = true; } });
      }); return;
    }
    if (name === 'tiles') {
      const refs = (slots.tiles ?? []).slice(0, 16), boxes = tileBoxes(refs.map((r: Json) => r.size), b.w, b.h, this.rtl());
      const title = text(page?.title ?? context.plugin.manifest.name, context), first = boxes.find(Boolean);
      if (first) this.label(title, b.w > b.h ? { x: dp(20), y: dp(20), w: Math.max(0, first.x - dp(40)), h: dp(72) } : { x: dp(20), y: dp(20), w: b.w - dp(40), h: Math.min(dp(72), first.y - dp(20)) }, { size: 32, align: this.rtl() ? 'right' : 'left', weight: 200 });
      refs.forEach((ref: Json, i: number) => { if (boxes[i]) this.widget(ref, context, boxes[i]!, ref.size); else this.warn(`Tile does not fit: ${ref.widget}`); }); return;
    }
    if (name === 'aero') {
      const refs = (slots.windows ?? []).slice(0, 3), horizontal = b.w > b.h, taskbar = dp(42), usable = inset({ ...b, h: b.h - taskbar }, dp(20));
      refs.forEach((ref: Json, i: number) => {
        const box = horizontal ? { x: usable.x + i * usable.w * .29, y: usable.y + (i % 2) * dp(25), w: usable.w * .42, h: usable.h - dp(25) } : { x: usable.x + i * dp(18), y: usable.y + i * usable.h * .25, w: usable.w - i * dp(24), h: usable.h * .50 };
        this.rect(box, this.palette.surface, dp(10)); this.rect(box, this.palette.divider, dp(10), 1);
        const owner = this.context(String(ref.widget).split('.')[0]); this.label(owner ? text(owner.plugin.manifest.name, owner) : ref.widget, { ...box, h: dp(38) }, { size: 14 });
        this.widget(ref, context, inset({ ...box, y: box.y + dp(38), h: box.h - dp(38) }, dp(12)), 'large');
      });
      this.rect({ x: 0, y: b.h - taskbar, w: b.w, h: taskbar }, this.palette.surface);
      this.icon('apps', { x: dp(16), y: b.h - taskbar + dp(8), w: dp(26), h: dp(26) }, this.palette.primary);
      this.addHit({ box: { x: 0, y: b.h - taskbar, w: dp(70), h: taskbar }, pluginId: context.id, tap: () => this.send(context.id, '@navigate', 'tiles.apps') }); return;
    }
    if (name === 'panel') { this.panel(slots, context, b); return; }
    if (name === 'form') {
      const edge = dp(20), hero = slots.hero ? dp(112) : 0;
      if (slots.hero) this.group(slots.hero, context, { x: edge, y: edge, w: b.w - edge * 2, h: hero });
      this.group(slots.body, context, { x: edge, y: edge + hero, w: b.w - edge * 2, h: b.h - edge * 2 - hero }); return;
    }
    if (!['focus', 'split', 'stage'].includes(name)) { this.warn(`Unsupported layout ${name}`); return; }
    const header = this.visibleGroup(slots.over ?? slots.header, context).length > 0;
    const footer = this.visibleGroup(slots.under ?? slots.footer, context).length > 0;
    for (const [role, content] of Object.entries(slots)) {
      let box = slotBox(name, role, b.w, b.h, header, footer);
      if (name === 'split' && ['lead','main'].includes(role)) {
        const visible = this.visibleGroup(slots.lead, context);
        if (visible.every((d:Json) => d.type === 'tabs')) {
          const body = slotBox('focus','content',b.w,b.h,header,footer), h = visible.length ? Math.min(dp(48),body.h) : 0;
          box = role === 'lead' ? {...body,h} : {...body,y:body.y+h,h:body.h-h};
        }
      }
      if (name === 'stage' && role === 'over' && (content as Json)?.type === 'clock' && (content as Json).style === 'float') {
        const edge = dp(24), width = Math.min(dp(220), b.w - edge * 2); this.group(content, context, { x: b.w - edge - width, y: edge, w: width, h: dp(56) });
      } else this.group(content, context, box);
    }
  }

  private widget(ref: Json, parent: Context, b: Box, size: string) {
    const [id, widgetId] = String(ref.widget ?? '').split('.'), context = this.context(id);
    const definition = (context?.plugin.manifest.widgets ?? []).find((w: Json) => w.id === widgetId);
    const filename = definition?.alternates?.tile && size !== 'glance' && this.scene?.page.startsWith('tiles.') ? definition.alternates.tile : definition?.view;
    const view = context?.plugin.views[filename];
    if (!context || !view) { this.warn(`Unavailable widget ${ref.widget}`); return; }
    if (definition.opens) this.addHit({ box: b, pluginId: id, tap: () => this.send(id, '@navigate', `${id}.${definition.opens}`), hold: () => this.send(id, '@settings', id), label: String(ref.widget) });
    this.widgetBody(view, context, b, size, ref.color ? text(ref.color, parent) : undefined);
  }

  private widgetBody(view: Json, context: Context, b: Box, size: string, overrideColor?: string) {
    if (view.template !== 'tile') { this.group({ children: view.children ?? [] }, context, b); return; }
    this.visibleTileOwners.add(context.id);
    const animation = this.tileAnimations.get(context.id);
    this.drawing.save();
    if (animation) {
      const elapsed = performance.now() - animation.started, duration = animation.name === 'nudge' ? 360 : animation.name === 'pulse' ? 450 : 520, progress = Math.min(1, elapsed / duration);
      if (animation.name === 'pulse') { const scale = 1 + .08 * Math.sin(progress * Math.PI); this.drawing.translate(b.x + b.w / 2, b.y + b.h / 2); this.drawing.scale(scale, scale); this.drawing.translate(-b.x - b.w / 2, -b.y - b.h / 2); }
      else if (animation.name === 'nudge') this.drawing.translate(Math.sin(progress * Math.PI * 6) * dp(10) * (1 - progress), 0);
      else { this.drawing.translate(0, b.y + b.h / 2); this.drawing.scale(1, Math.max(.02, Math.abs(Math.cos(progress * Math.PI)))); this.drawing.translate(0, -b.y - b.h / 2); }
      if (progress === 1) { this.tileAnimations.delete(context.id); if (animation.name === 'flip') this.tileFaceOffsets.set(context.id, (this.tileFaceOffsets.get(context.id) ?? 0) + 1); }
    }
    const faces = (view.faces ?? []).filter((f: Json) => text(f.value, context) || text(f.detail, context) || f.content), chosen = (faces.length ? faces : view.faces ?? [{}]);
    const animatingFace = animation?.name === 'flip' && performance.now() - animation.started >= 260 && this.tileAnimations.has(context.id) ? 1 : 0;
    const faceIndex = this.scene?.settings?.['tiles.live'] === false ? 0 : (Math.floor((this.scene?.now ?? 0) / (Number(this.scene?.settings?.['tiles.interval'] ?? 12) * 1000)) + (this.tileFaceOffsets.get(context.id) ?? 0) + animatingFace) % Math.max(1, chosen.length);
    const face = chosen[faceIndex] ?? {}, raw = overrideColor ?? text(view.color, context), fill = /^#[0-9a-f]{6}$/i.test(raw) ? raw : this.palette.accent;
    const nativeColors = tileColors(fill, this.scene?.theme === 'NIGHT');
    this.rect(b, nativeColors.background);
    const foreground = nativeColors.foreground, bright = foreground === '#000000', box = inset(b, dp(size === 'small' ? 8 : 14));
    const picture = view.image && text(view.image.src, context); if (picture) this.image(view.image, context, b);
    const value = text(face.value, context), detail = text(face.detail, context), label = text(face.label, context), icon = text(face.icon, context), content = face.content ?? view.content;
    this.clipped(b, () => {
      if (size === 'small') { if (icon) this.icon(icon, inset(box, Math.max(0, (Math.min(box.w, box.h) - dp(34)) / 2)), foreground); else this.label(value || label, box, { size: 32, color: foreground, fit: true, lines: 1 }); }
      else {
        const caption = { x: box.x, y: box.y + box.h - dp(25), w: box.w, h: dp(25) };
        this.label(label, caption, { size: 14, align: this.rtl() ? 'right' : 'left', color: foreground, lines: 1 });
        const main = { ...box, h: box.h - dp(30) }, withContent = !!content && (size === 'wide' || size === 'large');
        const contentBox = size === 'wide' ? { x: main.x + main.w * .5, y: main.y, w: main.w * .5, h: main.h } : { ...main, h: main.h * .6 };
        if (withContent) { const old = this.palette; this.palette = { ...old, primary: foreground, secondary: foreground, accent: foreground, divider: bright ? '#333333' : '#ffffff44' }; this.component(content, context, contentBox, `tile:${context.id}:${label}`); this.palette = old; }
        const textBox = withContent ? size === 'wide' ? { ...main, w: main.w * .5 - dp(8) } : { ...main, y: main.y + main.h * .64, h: main.h * .36 } : main;
        if (value) this.label(value, { ...textBox, h: detail ? textBox.h * .7 : textBox.h }, { size: picture ? 32 : 72, color: foreground, fit: true, lines: picture ? 2 : 1, align: 'left', weight: 300 });
        else if (icon && !withContent) this.icon(icon, { x: textBox.x, y: textBox.y + Math.max(0, (textBox.h - dp(52)) / 2), w: Math.min(dp(52), textBox.w), h: Math.min(dp(52), textBox.h) }, foreground);
        if (detail && !withContent) this.label(detail, { ...textBox, y: value ? textBox.y + textBox.h * .7 : textBox.y, h: value ? textBox.h * .3 : textBox.h }, { size: 18, color: foreground, align: 'left' });
      }
      const badge = text(view.badge, context); if (badge) this.label(badge, { x: b.x + b.w - dp(44), y: b.y + dp(6), w: dp(36), h: dp(24) }, { size: 14, color: foreground, lines: 1 });
    });
    this.drawing.restore();
  }

  private visibleComponent(d:Json, c:Context) {
    if (d.type === 'nowplaying' && d.style === 'bar') {
      const media = this.media(c);
      return (Number(media.count ?? 0) > 0 && media.status !== 'stopped') || !!text(d.idle, c).trim();
    }
    return !(d.type === 'text' && !text(d.value,c) || d.type === 'image' && !text(d.src,c) || d.type === 'tabs' && !array(d.items,c).length);
  }
  private visibleGroup(definition: Json | undefined, context: Context): Json[] {
    return definition ? (definition.children ?? [definition]).slice(0, 12).filter((child: Json) => this.visibleComponent(child, context)) : [];
  }
  private group(definition: any, context: Context, b: Box, key = '') {
    if (!definition || b.w <= 0 || b.h <= 0) return;
    if (definition.widget) { this.widget(definition, context, b, 'large'); return; }
    if (!definition.children) { this.component(definition, context, b, key); return; }
    const children = this.visibleGroup(definition, context), dense = b.h / DENSITY < 340 && children.length >= 4;
    const fixed: Record<string, number> = { text: 48, icon: 52, progress: 24, tabs: 48, slider: 52, seekbar: 52, controls: 64, toggle: 88 };
    const measured = children.map((c: Json) => dp(c.type === 'text' ? ({ display: dense ? 40 : 88, title: dense ? 28 : 44, caption: 36 }[c.style as string] ?? 48) : c.type === 'progress' && c.style === 'ring' ? 112 : fixed[c.type] ?? 120));
    const weights = children.map((c: Json) => Number.isFinite(c.weight) ? c.weight : Object.hasOwn(fixed, c.type) ? 0 : 1);
    const allocation = verticalHeights(b.h, measured, weights, dp(dense ? 6 : 12)); let y = b.y + allocation.offset;
    children.forEach((c: Json, i: number) => { const copy = dense && c.type === 'text' ? { ...c, style: c.style === 'display' ? 'title' : c.style === 'title' ? 'body' : c.style } : c; this.component(copy, context, { x: b.x, y, w: b.w, h: allocation.heights[i] }, `${key}:${i}`); y += allocation.heights[i] + allocation.gap; });
  }

  private component(d: Json, c: Context, b: Box, key: string) {
    if (!d?.type || b.w < 1 || b.h < 1) return;
    const ident = `${this.scene!.page}:${key}:${d.type}:${d.items ?? d.path ?? ''}`;
    this.clipped(b, () => {
      switch (d.type) {
        case 'text': this.label(text(d.value, c), b, { size: ({ display: 72, title: 32, body: 18, caption: 14 } as Json)[d.style] ?? 18, weight: d.style === 'display' ? 300 : d.style === 'title' ? 500 : 400 }); break;
        case 'icon': { const size = Math.min(b.w, b.h, dp(80)); this.icon(text(d.name, c), { x: b.x + (b.w - size) / 2, y: b.y + (b.h - size) / 2, w: size, h: size }, this.palette.primary); break; }
        case 'clock': this.clock(d, c, b); break;
        case 'progress': this.progress(d, c, b); break;
        case 'chart': this.chart(d, c, b); break;
        case 'list': this.list(d, c, b, ident + (d.scrollKey ? ':'+text(d.scrollKey,c) : '')); break;
        case 'grid': this.grid(d, c, b, ident); break;
        case 'tabs': this.tabs(d, c, b); break;
        case 'form': this.form(d, c, b, ident); break;
        case 'image': this.image(d, c, b); break;
        case 'carousel': this.carousel(d, c, b, ident); break;
        case 'daylight': this.daylight(d, c, b); break;
        case 'moon': this.moon(d, c, b); break;
        case 'toggle': this.toggle(d, c, b); break;
        case 'slider': this.slider(d, c, b); break;
        case 'controls': this.controls(d, c, b); break;
        case 'seekbar': this.seekbar(c, b); break;
        case 'nowplaying': this.nowPlaying(d, c, b); break;
        case 'video': case 'tv': this.video(d, c, b); break;
        default: this.warn(`Unsupported component ${d.type}`);
      }
      if (d.tap && !['list', 'grid', 'tabs', 'form', 'toggle', 'tv', 'controls', 'seekbar', 'slider', 'nowplaying'].includes(d.type)) this.addHit({ box: b, pluginId: c.id, tap: () => this.send(c.id, 'tap', d.tap), hold: d.hold ? () => this.send(c.id, 'tap', d.hold) : undefined, label: d.tap });
    });
  }

  private icon(name: string, b: Box, color: string) {
    if (!name) return;
    const source = this.options.glyphs?.[name];
    if (source) {
      let path = this.glyphPaths.get(name);
      if (!path) { const match = /<path[^>]*\sd=["']([^"']+)/.exec(source); if (match) { path = this.ck.Path.MakeFromSVGString(match[1]); if (path) this.glyphPaths.set(name, path); } }
      if (path) { const match = /viewBox=["']([\d.\s-]+)/.exec(source), coords = match?.[1].trim().split(/\s+/).map(Number) ?? [0, 0, 1024, 1024]; this.drawing.save(); this.drawing.translate(b.x, b.y); this.drawing.scale(b.w / coords[2], b.h / coords[3]); const p = this.paint(color); this.drawPath(path, p); p.delete(); this.drawing.restore(); return; }
    }
    const x = b.x + b.w / 2, y = b.y + b.h / 2, r = Math.min(b.w, b.h) * .36, stroke = Math.max(1, Math.min(b.w, b.h) / 20);
    if (name === 'clock') { this.circle(x, y, r, color, stroke); this.line(x, y, x, y - r * .6, color, stroke); this.line(x, y, x + r * .45, y + r * .2, color, stroke); }
    else if (name === 'play') { const p = new this.ck.PathBuilder(); p.moveTo(x - r * .5, y - r); p.lineTo(x + r, y); p.lineTo(x - r * .5, y + r); p.close(); const paint = this.paint(color); this.drawPath(p, paint); paint.delete(); p.delete(); }
    else if (name === 'photo') { this.rect({ x: x - r, y: y - r * .8, w: r * 2, h: r * 1.6 }, color, 0, stroke); this.circle(x + r * .4, y - r * .3, r * .16, color); this.line(x - r * .8, y + r * .5, x - r * .1, y - r * .1, color, stroke); this.line(x - r * .1, y - r * .1, x + r * .8, y + r * .5, color, stroke); }
    else if (name === 'music') { this.line(x + r * .3, y - r, x + r * .3, y + r * .6, color, stroke); this.line(x + r * .3, y - r, x + r, y - r * .6, color, stroke * 2); this.circle(x - r * .1, y + r * .6, r * .35, color); }
    else if (name === 'calendar') { this.rect({ x: x - r, y: y - r * .8, w: r * 2, h: r * 1.8 }, color, stroke, stroke); this.line(x - r, y - r * .2, x + r, y - r * .2, color, stroke); }
    else if (name === 'list') { for (let i = -1; i <= 1; i++) this.line(x - r, y + i * r * .6, x + r, y + i * r * .6, color, stroke); }
    else if (name === 'star') { const path = new this.ck.PathBuilder(); for (let i = 0; i < 10; i++) { const a = i * Math.PI / 5 - Math.PI / 2, radius = r * (i % 2 ? 19 / 43 : 1); if (i) path.lineTo(x + Math.cos(a) * radius, y + Math.sin(a) * radius); else path.moveTo(x + Math.cos(a) * radius, y + Math.sin(a) * radius); } path.close(); const p = this.paint(color, stroke); this.drawPath(path, p); p.delete(); path.delete(); }
    else if (name.startsWith('moon-phase-')) {
      const parts = name.split('-'), fraction = Math.max(0, Math.min(100, Number(parts[3]) || 0)) / 100, side = parts[2] === 'n' ? -1 : 1;
      const dim = this.paint(color); dim.setAlphaf(70 / 255); this.drawing.drawCircle(x, y, r, dim); dim.delete();
      if (fraction >= .98) this.circle(x, y, r, color);
      else if (fraction > .02) {
        const path = new this.ck.PathBuilder();
        for (let i = 0; i <= 96; i++) { const yy = -1 + i / 48, xx = Math.sqrt(Math.max(0, 1 - yy * yy)) * side; if (i) path.lineTo(x + xx * r, y + yy * r); else path.moveTo(x + xx * r, y + yy * r); }
        for (let i = 96; i >= 0; i--) { const yy = -1 + i / 48, xx = (1 - 2 * fraction) * Math.sqrt(Math.max(0, 1 - yy * yy)) * side; path.lineTo(x + xx * r, y + yy * r); }
        path.close(); const p = this.paint(color); this.drawPath(path, p); p.delete(); path.delete();
      }
    }
    else { this.circle(x, y, r, color, stroke); this.label('?', b, { size: Math.min(32, b.h / DENSITY * .6), color, lines: 1 }); this.warn(`Icon has no supplied vector: ${name}`); }
  }

  private rtl() { return /^(ar|ur|he)/.test(this.scene?.locale ?? ''); }

  private clock(d: Json, c: Context, b: Box) {
    const style = text(d.style ?? 'digital', c), now = this.previewTime(), zone = homeZone(this.scene?.timezone ?? 'Etc/UTC', this.scene?.location?.timezone), pattern = (typeof d.format === 'string' && d.format.startsWith('@') ? d.format : text(d.format, c)) || (c.config.seconds ? '@timeSeconds' : '@time');
    const formatted = (timeZone?: string) => { try { return formatTime(now, pattern, timeZone, c.locale ?? 'zh-Hans', this.scene?.hour24 !== false, zone); } catch (error) { this.warn(`Clock format: ${String(error)}`); return '—'; } };
    if (style === 'analog') { this.analog(b, new Date(now + zoneType(zone, now).offset * 1000), pattern.includes('s') || pattern === '@timeSeconds'); return; }
    if (style === 'solar') { this.daylight({ path: d.sun ?? 'sun' }, c, { ...b, h: b.h * .76 }); this.label(formatted(zone), { x: b.x + b.w * .2, y: b.y + b.h * .24, w: b.w * .6, h: b.h * .3 }, { size: 72, fit: true, lines: 1 }); return; }
    if (style === 'world') {
      const zones = (d.zones ?? []).map((z: Json) => ({ zone: text(z.timezone, c), label: text(z.label, c) })).filter((z: Json) => z.zone).slice(0, 4); if (!zones.length) zones.push({ zone, label: '' }); const count = zones.length;
      zones.forEach((z: Json, i: number) => { const box = { x: b.x + (b.w > b.h ? i * b.w / count : 0), y: b.y + (b.w > b.h ? 0 : i * b.h / count), w: b.w > b.h ? b.w / count : b.w, h: b.w > b.h ? b.h : b.h / count }; this.label(z.label || z.zone.split('/').pop().replaceAll('_', ' '), { ...box, h: box.h * .25 }, { size: 18 }); this.label(formatted(z.zone), { ...box, y: box.y + box.h * .25, h: box.h * .75 }, { size: 72, lines: 1, fit: true }); }); return;
    }
    const size = style === 'float' ? 32 : Math.min(180, b.w / DENSITY * .23, b.h / DENSITY * .65);
    this.label(now < 1577836800000 ? '--:--' : formatted(zone), b, { size, fit: true, lines: 1, weight: 300, color: style === 'float' ? '#ffffff' : this.palette.primary, align: style === 'float' ? 'right' : 'center' });
  }
  private analog(b: Box, date: Date, seconds = true) {
    const x = b.x + b.w / 2, y = b.y + b.h / 2, r = Math.min(b.w, b.h) * .44;
    this.circle(x, y, r, this.palette.divider, 2);
    for (let i = 0; i < 60; i++) { const a = i * Math.PI / 30 - Math.PI / 2, length = r * (i % 5 === 0 ? .11 : .04); this.line(x + Math.cos(a) * (r - length), y + Math.sin(a) * (r - length), x + Math.cos(a) * r * .96, y + Math.sin(a) * r * .96, i % 5 ? this.palette.secondary : this.palette.primary, 2); }
    const hand = (a: number, length: number, width: number, color: string) => this.line(x, y, x + Math.cos(a - Math.PI / 2) * length, y + Math.sin(a - Math.PI / 2) * length, color, width);
    hand(((date.getUTCHours() % 12) + date.getUTCMinutes() / 60) * Math.PI / 6, r * .5, r * .04, this.palette.primary);
    hand((date.getUTCMinutes() + date.getUTCSeconds() / 60) * Math.PI / 30, r * .76, r * .027, this.palette.primary);
    if (seconds) hand(date.getUTCSeconds() * Math.PI / 30, r * .8, Math.max(1, r * .009), this.palette.accent); this.circle(x, y, r * .04, this.palette.accent);
  }

  private progress(d: Json, c: Context, b: Box) {
    const value = Number(bound(d.value, c)), maximum = Number(bound(d.max ?? 1, c)), valid = Number.isFinite(value) && Number.isFinite(maximum) && maximum > 0;
    const fraction = valid ? Math.max(0, Math.min(1, value / maximum)) : 0;
    if (d.style === 'ring') { const r = Math.max(0, Math.min(b.w, b.h) / 2 - dp(6)), x = b.x + b.w / 2, y = b.y + b.h / 2; this.circle(x, y, r, this.palette.divider, dp(4)); if (valid) { const path = new this.ck.PathBuilder(); path.addArc(this.ck.XYWHRect(x - r, y - r, r * 2, r * 2), -90, fraction * 360); const p = this.paint(this.palette.accent, dp(4)); this.drawPath(path, p); p.delete(); path.delete(); } this.label(valid ? `${Math.round(fraction * 100)}%` : '—', b, { size: 18 }); }
    else { this.line(b.x + dp(2), b.y + b.h / 2, b.x + b.w - dp(2), b.y + b.h / 2, this.palette.divider, dp(4)); if (fraction > 0) this.line(b.x + dp(2), b.y + b.h / 2, b.x + dp(2) + (b.w - dp(4)) * fraction, b.y + b.h / 2, this.palette.accent, dp(4)); }
  }

  private chart(d: Json, c: Context, b: Box) {
    const values = array(d.values, c).slice(0, 168).map(v => typeof v === 'number' && Number.isFinite(v) ? v : null), valid = values.filter((v): v is number => v !== null); if (!valid.length) return;
    const lowValue = Number(bound(d.min, c)), highValue = Number(bound(d.max, c)), low = Number.isFinite(lowValue) ? lowValue : Math.min(...valid), high = Number.isFinite(highValue) ? highValue : Math.max(...valid);
    const box = inset(b, dp(12)), range = high - low || 1, baseY = box.y + box.h - (Math.max(low, Math.min(high, 0)) - low) / range * box.h, points = values.map((v, i) => v === null ? null : { x: box.x + (values.length === 1 ? box.w / 2 : i * box.w / (values.length - 1)), y: box.y + box.h - (v - low) / range * box.h });
    if (d.style === 'bar') { const slot = box.w / values.length; points.forEach((p, i) => { if (p) this.rect({ x: box.x + i * slot + slot * .15, y: Math.min(p.y, baseY), w: slot * .7, h: Math.abs(p.y - baseY) || 1 }, this.palette.accent); }); }
    else { let segment: Array<{ x: number; y: number }> = []; const flush = () => { if (!segment.length) return; const path = new this.ck.PathBuilder(); path.moveTo(segment[0].x, segment[0].y); segment.slice(1).forEach(p => path.lineTo(p.x, p.y)); if (d.style === 'area') { path.lineTo(segment[segment.length - 1].x, baseY); path.lineTo(segment[0].x, baseY); path.close(); const fill = this.paint(`${this.palette.accent}33`); this.drawPath(path, fill); fill.delete(); } for (let i = 1; i < segment.length; i++) this.line(segment[i - 1].x, segment[i - 1].y, segment[i].x, segment[i].y, this.palette.accent); if (segment.length === 1) this.circle(segment[0].x, segment[0].y, dp(3), this.palette.accent); path.delete(); segment = []; }; for (const p of points) { if (p) segment.push(p); else flush(); } flush(); }
    const highlight = Number(bound(d.highlight, c)) - 1, p = points[highlight]; if (p) this.circle(p.x, p.y, dp(6), this.palette.accent);
  }

  private scrollHit(b: Box, c: Context, key: string, extent: number) { this.addHit({ box: b, pluginId: c.id, scroll: delta => { this.scrolls.set(key, Math.max(0, Math.min(Math.max(0, extent - b.h), (this.scrolls.get(key) ?? 0) + delta))); this.scheduled = true; } }); }
  private list(d: Json, c: Context, b: Box, key: string) {
    const rows = array(d.items, c).slice(0, 1000), rowH = dp(80), offset = Math.min(this.scrolls.get(key) ?? 0, Math.max(0, rows.length * rowH - b.h)); this.scrollHit(b, c, key, rows.length * rowH);
    const current = Number(bound(d.current, c));
    rows.forEach((item, index) => { const box = { x: b.x, y: b.y + index * rowH - offset, w: b.w, h: rowH }; if (box.y + box.h < b.y || box.y > b.y + b.h) return;
      const marked = current === index + 1, row = d.item ?? {}, icon = text(row.icon, c, item), trailing = text(row.trailing, c, item), title = text(row.title, c, item), subtitle = text(row.subtitle, c, item);
      if (marked) this.rect(inset(box, dp(2)), this.palette.surface, dp(14));
      let x = box.x + dp(8); const art = text(row.image,c,item);
      if (art) { this.image({src:art,fit:'contain'},c,{x,y:box.y+dp(16),w:dp(48),h:dp(48)}); x+=dp(60); }
      else if (icon) { this.icon(icon, { x, y: box.y + dp(22), w: dp(36), h: dp(36) }, marked ? this.palette.accent : this.palette.primary); x += dp(48); }
      const trailW = trailing ? Math.min(dp(140), box.w * .3) : 0, width = Math.max(0, box.x + box.w - dp(8) - trailW - x);
      this.label(title, { x, y: box.y + (subtitle ? dp(12) : dp(20)), w: width, h: dp(34) }, { size: 18, align: this.rtl() ? 'right' : 'left', lines: 1, color: marked ? this.palette.accent : this.palette.primary });
      if (subtitle) this.label(subtitle, { x, y: box.y + dp(43), w: width, h: dp(25) }, { size: 14, align: this.rtl() ? 'right' : 'left', lines: 1, color: this.palette.secondary });
      if (trailing) this.label(trailing, { x: box.x + box.w - trailW, y: box.y, w: trailW - dp(8), h: box.h }, { size: 14, color: this.palette.secondary, align: 'right', lines: 1 });
      if (row.progress != null) this.progress({ value: bound(row.progress, c, item), max: bound(row.max ?? 1, c, item) }, c, { x, y: box.y + box.h - dp(8), w: width, h: dp(4) });
      if (d.tap) this.addHit({ box, pluginId: c.id, tap: () => this.send(c.id, 'tap', d.tap, index + 1), label: title });
    });
  }

  private grid(d: Json, c: Context, b: Box, key: string) {
    const rows = array(d.items, c), columns = Math.max(3, Math.floor(b.w / DENSITY / 96)), cellW = b.w / columns, cellH = dp(106), offset = this.scrolls.get(key) ?? 0; this.scrollHit(b, c, key, Math.ceil(rows.length / columns) * cellH);
    rows.forEach((row, index) => { let column = index % columns; if (this.rtl()) column = columns - 1 - column; const box = { x: b.x + column * cellW, y: b.y + Math.floor(index / columns) * cellH - offset, w: cellW, h: cellH }; if (box.y + box.h < b.y || box.y > b.y + b.h) return;
      const iconBox = { x: box.x + (box.w - dp(56)) / 2, y: box.y + dp(8), w: dp(56), h: dp(56) }, image = text(d.item?.image, c, row), icon = text(d.item?.icon ?? 'apps', c, row);
      if (image && !image.startsWith('app:')) this.image({ src: image, fit: 'contain' }, c, iconBox); else this.icon(icon || 'apps', iconBox, this.palette.primary);
      const title = text(d.item?.title, c, row); this.label(title, { x: box.x + dp(4), y: box.y + dp(68), w: box.w - dp(8), h: dp(28) }, { size: 14, lines: 1 });
      if (d.tap) this.addHit({ box, pluginId: c.id, tap: () => this.send(c.id, 'tap', d.tap, index + 1), label: title });
    });
  }

  private tabs(d: Json, c: Context, b: Box) {
    const items = array(d.items, c), current = bound(d.current, c), width = b.w / Math.max(1, items.length);
    items.slice(0, 32).forEach((item, i) => { const label = typeof item === 'string' ? item : item.title ?? item.label ?? item.id ?? '', selected = current === i + 1 || current === item.id, box = { x: b.x + width * i, y: b.y, w: width, h: b.h }; this.label(String(label), inset(box, dp(4)), { size: 18, color: selected ? this.palette.accent : this.palette.secondary, lines: 1 }); if (selected) this.rect({ x: box.x + dp(12), y: box.y + box.h - dp(3), w: Math.max(0, box.w - dp(24)), h: dp(3) }, this.palette.accent); if (d.tap) this.addHit({ box, pluginId: c.id, tap: () => this.send(c.id, 'tap', d.tap, i + 1), label: String(label) }); });
  }

  private form(d: Json, c: Context, b: Box, key: string) {
    const fields = array(d.path, c), title = text(d.title, c), subtitle = text(d.subtitle, c), nav = lookup(c.state, d.nav ?? '') as Json | undefined;
    const wide = b.w / DENSITY >= 600 && Array.isArray(nav?.items) && nav.items.length > 0;
    if (this.formLayouts.get(key) !== wide) { this.formLayouts.set(key, wide); queueMicrotask(() => { if (!this.destroyed) this.send(c.id, 'form.layout', wide); }); }
    let content = { ...b };
    if (wide) { const width = Math.min(dp(220), b.w * .32); nav!.items.forEach((item: Json, i: number) => { const box = { x: b.x, y: b.y + i * dp(64), w: width - dp(12), h: dp(58) }; if (item.id === nav!.current) this.rect(box, this.palette.surface, dp(8)); this.label(String(item.label ?? item.id), inset(box, dp(10)), { size: 18, align: 'left', lines: 1 }); this.addHit({ box, pluginId: c.id, tap: () => this.send(c.id, 'form.navigate', item.id) }); }); content = { x: b.x + width, y: b.y, w: b.w - width, h: b.h }; }
    const heading = title ? dp(subtitle ? 90 : 64) : 0, footer = d.primary || d.secondary || d.done || d.back ? dp(64) : 0;
    if (title) this.label(title, { ...content, h: dp(58) }, { size: 32, align: 'left', lines: 1 });
    if (subtitle) this.label(subtitle, { ...content, y: content.y + dp(56), h: dp(30) }, { size: 14, color: this.palette.secondary, align: 'left' });
    const body = { ...content, y: content.y + heading, h: Math.max(0, content.h - heading - footer) }, rowH = dp(76), offset = this.scrolls.get(key) ?? 0;
    this.clipped(body, () => { this.scrollHit(body, c, key, fields.length * rowH); fields.forEach((field, index) => {
      const box = { x: body.x, y: body.y + index * rowH - offset, w: body.w, h: rowH }; if (box.y + box.h < body.y || box.y > body.y + body.h) return;
      const label = text(field.label ?? field.key, c), display = field.display ?? (field.type === 'bool' ? '' : field.value ?? ''), enabled = field.enabled !== false && field.readonly !== true;
      this.label(label, { x: box.x + dp(4), y: box.y, w: box.w * .57, h: dp(44) }, { size: 18, align: 'left', color: enabled ? this.palette.primary : this.palette.secondary, lines: 1 });
      if (field.help || field.error) this.label(text(field.error ?? field.help, c), { x: box.x + dp(4), y: box.y + dp(40), w: box.w * .8, h: dp(28) }, { size: 14, align: 'left', lines: 1, color: field.error ? this.palette.warn : this.palette.secondary });
      if (field.type === 'bool') { const on = Boolean(field.value), switchBox = { x: box.x + box.w - dp(60), y: box.y + dp(17), w: dp(46), h: dp(26) }; this.rect(switchBox, on ? this.palette.accent : this.palette.divider, dp(13)); this.circle(switchBox.x + (on ? switchBox.w - dp(13) : dp(13)), switchBox.y + dp(13), dp(10), '#ffffff'); }
      else this.label(String(display), { x: box.x + box.w * .57, y: box.y, w: box.w * .38, h: dp(58) }, { size: 14, color: this.palette.secondary, align: 'right', lines: 1 });
      this.line(box.x, box.y + box.h - 1, box.x + box.w, box.y + box.h - 1, this.palette.divider, 1);
      if (enabled) this.addHit({ box, pluginId: c.id, label, tap: () => field.type === 'bool' ? this.send(c.id, 'form.change', field.key, !field.value) : field.type === 'link' ? this.confirmField(c, field) : this.editField(c, field) });
    }); });
    if (footer) {
      const actions = [['back', d.back], ['secondary', d.secondary], ['primary', d.primary ?? d.done]].filter(([, value]) => text(value, c));
      actions.forEach(([action, label], i) => { const box = { x: content.x + i * content.w / actions.length, y: content.y + content.h - footer, w: content.w / actions.length, h: footer }; this.label(text(label, c), inset(box, dp(8)), { size: 18, color: action === 'primary' ? this.palette.accent : this.palette.primary }); this.addHit({ box, pluginId: c.id, tap: () => d.tap ? this.send(c.id, 'tap', d.tap, action) : this.send(c.id, '@back') }); });
    }
  }

  private confirmField(c: Context, field: Json) {
    if (!field.confirm) { this.send(c.id, 'form.change', field.key, true); return; }
    this.closeEditor(); const dialog = document.createElement('dialog'); dialog.className = 'sim-field-editor'; this.inputDialog = dialog;
    const heading = document.createElement('h2'); heading.textContent = text(field.confirm, c);
    const actions = document.createElement('div'), cancel = document.createElement('button'), confirm = document.createElement('button');
    cancel.textContent = dialogString(this.scene?.locale ?? 'en', this.options.systemStrings, 'cancel'); cancel.onclick = () => this.closeEditor(); confirm.textContent = dialogString(this.scene?.locale ?? 'en', this.options.systemStrings, 'confirm'); confirm.onclick = () => { this.send(c.id, 'form.change', field.key, true); this.closeEditor(); };
    actions.append(cancel, confirm); dialog.append(heading, actions); dialog.addEventListener('cancel', () => this.closeEditor()); document.body.append(dialog); dialog.showModal(); cancel.focus();
  }
  private editField(c: Context, field: Json) {
    if (['region', 'timezone', 'place'].includes(field.source)) {
      this.closeEditor();
      const dialog = createFieldPicker({ field, title: text(field.label ?? field.key, c), locale: this.scene?.locale ?? 'en', now: this.scene?.now ?? Date.now(), deviceTimezone: this.scene?.timezone ?? 'Etc/UTC', theme: this.scene?.theme ?? 'DARK', systemStrings: this.options.systemStrings, searchPlaces: this.options.searchPlaces, close: () => this.closeEditor(), pick: value => { this.closeEditor(); this.send(c.id, 'form.change', field.key, value); } });
      this.inputDialog = dialog; document.body.append(dialog); dialog.showModal(); dialog.querySelector('input')?.focus(); return;
    }
    this.closeEditor(); const dialog = document.createElement('dialog'); dialog.className = 'sim-field-editor'; this.inputDialog = dialog;
    const form = document.createElement('form'); form.method = 'dialog'; const heading = document.createElement('h2'); heading.textContent = text(field.label ?? field.key, c); form.append(heading);
    const choices = field.options ?? field.choices;
    let input: HTMLInputElement | HTMLSelectElement;
    if (Array.isArray(choices)) { const select = document.createElement('select'); choices.slice(0, 1000).forEach((choice: any, i: number) => { const option = document.createElement('option'); option.value = String(i); option.textContent = typeof choice === 'object' ? String(choice.label ?? choice.value) : String(choice); option.selected = (typeof choice === 'object' ? choice.value : choice) === field.value; select.append(option); }); input = select; }
    else { const element = document.createElement('input'); element.type = field.secret || field.type === 'password' ? 'password' : field.type === 'number' ? 'number' : field.type === 'time' ? 'time' : 'text'; element.value = String(field.value ?? ''); if (Number.isFinite(field.min)) element.min = String(field.min); if (Number.isFinite(field.max)) element.max = String(field.max); if (Number.isFinite(field.step)) element.step = String(field.step); element.maxLength = Math.min(4096, field.maxLength ?? 2048); input = element; }
    input.setAttribute('aria-label', heading.textContent ?? '设置值'); form.append(input);
    const actions = document.createElement('div'), cancel = document.createElement('button'), save = document.createElement('button'); cancel.type = 'button'; cancel.textContent = dialogString(this.scene?.locale ?? 'en', this.options.systemStrings, 'cancel'); cancel.onclick = () => this.closeEditor(); save.type = 'submit'; save.textContent = dialogString(this.scene?.locale ?? 'en', this.options.systemStrings, 'confirm'); actions.append(cancel, save); form.append(actions);
    form.onsubmit = event => { event.preventDefault(); let value: any = input.value; if (Array.isArray(choices)) { const choice = choices[Number(value)]; value = typeof choice === 'object' ? choice.value : choice; } else if (field.type === 'number') { value = Number(value); if (!Number.isFinite(value)) return; } this.send(c.id, 'form.change', field.key, value); this.closeEditor(); };
    dialog.addEventListener('cancel', () => this.closeEditor()); dialog.append(form); document.body.append(dialog); dialog.showModal(); input.focus();
  }
  private closeEditor() { this.inputDialog?.close(); this.inputDialog?.remove(); this.inputDialog = undefined; }

  private imageOwner(expression: unknown, context: Context): Context {
    return this.context(imagePrincipal(expression, context.id, this.scene?.plugins ?? {})) ?? context;
  }
  private image(d: Json, c: Context, b: Box, delegated?: Context) {
    const source = text(d.src, c); if (!source) return;
    const owner = delegated ?? this.imageOwner(d.src, c);
    // Each verified package has its own immutable asset map. A hot reload must not
    // reuse decoded images or delayed results from an older package generation.
    let generation = this.assetGenerations.get(owner.plugin.assets);
    if (generation === undefined) { generation = ++this.nextAssetGeneration; this.assetGenerations.set(owner.plugin.assets, generation); }
    const key = `${owner.id}@${generation}:${source}`, image = this.imageCache.get(key);
    if (!image) { this.requestImage(key, source, owner); return; }
    const iw = image.width(), ih = image.height(), fit = text(d.fit ?? 'contain', c), scale = fit === 'contain' ? Math.min(b.w / iw, b.h / ih) : Math.max(b.w / iw, b.h / ih), width = iw * scale, height = ih * scale;
    let x = b.x + (b.w - width) / 2, y = b.y + (b.h - height) / 2;
    if (fit === 'pan' && bound(d.pan ?? true, c) !== false && this.scene?.settings?.['motion.reduced'] !== true) { const seconds = Math.max(1, Number(bound(d.pan_seconds ?? 12, c))), phase = (1 - Math.cos((this.scene!.now / (seconds * 1000)) * Math.PI)) / 2; x = b.x + (b.w - width) * phase; y = b.y + (b.h - height) * phase; }
    this.clipped(b, () => this.drawing.drawImageRect(image, this.ck.XYWHRect(0, 0, iw, ih), this.ck.XYWHRect(x, y, width, height), null));
  }
  private requestImage(key: string, source: string, c: Context) {
    if (this.imagePending.has(key) || this.imageCache.has(key)) return; this.imagePending.add(key);
    const asset = c.plugin.assets[source.replace(/^asset:/, '')] ?? c.plugin.assets[source];
    const task = asset && /^(blob:|data:)/.test(asset) ? fetch(asset).then(r => r.arrayBuffer()).then(b => new Uint8Array(b)) : this.options.loadImage ? this.options.loadImage(c.id, source) : Promise.resolve(null);
    task.then(bytes => { if (this.destroyed) return; if (!bytes || bytes.length > 16 * 1024 * 1024) { this.warn(`Image unavailable: ${source.slice(0, 120)}`); this.imageCache.set(key, null); }
      else { const image = this.ck.MakeImageFromEncoded(bytes); if (image && image.width() * image.height() <= 16 * 1024 * 1024) this.imageCache.set(key, image); else { image?.delete(); this.imageCache.set(key, null); this.warn(`Image decode failed: ${source.slice(0, 120)}`); } }
      this.trimImages(this.imageCache, 32, 16 * 1024 * 1024); this.scheduled = true;
    }).catch(error => { if (!this.destroyed) { this.imageCache.set(key, null); this.trimImages(this.imageCache, 32, 16 * 1024 * 1024); this.warn(`Image: ${String(error)}`); } }).finally(() => this.imagePending.delete(key));
  }
  private carousel(d: Json, c: Context, b: Box, key: string) {
    const items = array(d.items, c); if (!items.length) return;
    const interval = Math.max(.1, Number(bound(d.interval, c)) || 20), manual = this.stackIndices.get(key), index = manual ?? Math.floor(this.scene!.now / (interval * 1000)) % items.length, item = items[index % items.length], source = typeof item === 'string' ? item : item.src ?? item.url ?? item.path;
    this.image({ ...d, src: source }, c, b, this.imageOwner(d.items, c));
    if (bound(d.caption ?? 'auto', c) !== 'off' && typeof item === 'object') { const label = item.title ?? item.caption, credit = item.credit ?? item.attribution; if (label || credit) { this.rect({ x: b.x, y: b.y + b.h - dp(80), w: b.w, h: dp(80) }, '#00000099'); this.label([label, credit].filter(Boolean).join('\n'), { x: b.x + dp(24), y: b.y + b.h - dp(74), w: b.w - dp(48), h: dp(68) }, { size: 14, color: '#ffffff', align: 'left' }); } }
    this.addHit({ box: b, pluginId: c.id, scroll: delta => { if (Math.abs(delta) > 20) { this.stackIndices.set(key, Math.max(0, Math.min(items.length - 1, index + (delta > 0 ? 1 : -1)))); this.scheduled = true; } } });
  }

  private daylight(d: Json, c: Context, b: Box) {
    const data = lookup(c.state, d.path ?? 'sun'), x = b.x + b.w / 2, y = b.y + b.h / 2, r = Math.max(0, Math.min(b.w, b.h) / 2 - dp(12)); this.circle(x, y, r, this.palette.divider, dp(4));
    if (!data) return;
    const sunrise = Number(data.sunrise), sunset = Number(data.sunset), noon = Number(data.noon ?? data.solar_noon), start = Number.isFinite(noon) ? noon - 43200000 : sunrise - 21600000;
    if (!Number.isFinite(sunrise) || !Number.isFinite(sunset) || sunset <= sunrise) return;
    const angle = (time: number) => ((time - start) / 86400000 * 360) + 90;
    const path = new this.ck.PathBuilder(); path.addArc(this.ck.XYWHRect(x - r, y - r, r * 2, r * 2), angle(sunrise), (sunset - sunrise) / 86400000 * 360); const p = this.paint(this.palette.accent, dp(8)); this.drawPath(path, p); p.delete(); path.delete();
    const a = angle(this.scene!.now) * Math.PI / 180; this.circle(x + Math.cos(a) * r, y + Math.sin(a) * r, dp(5), this.palette.primary);
  }
  private moon(d: Json, c: Context, b: Box) {
    const value = lookup(c.state, d.path), data = value?.phase ?? value, fraction = Number(data?.illumination), x = b.x + b.w / 2, y = b.y + b.h * .41, r = Math.min(b.w * .45, b.h * .36); this.circle(x, y, r, this.palette.divider);
    if (Number.isFinite(fraction) && fraction >= 0 && fraction <= 1) { const right = (data.waxing !== false) !== (text(d.hemisphere ?? 'north', c) === 'south'), path = new this.ck.PathBuilder(); for (let step = 0; step <= 96; step++) { const yy = -1 + step / 48, extent = Math.sqrt(Math.max(0, 1 - yy * yy)), xx = extent * (right ? 1 : -1); if (!step) path.moveTo(x + xx * r, y + yy * r); else path.lineTo(x + xx * r, y + yy * r); } for (let step = 96; step >= 0; step--) { const yy = -1 + step / 48, extent = Math.sqrt(Math.max(0, 1 - yy * yy)); path.lineTo(x + (1 - 2 * fraction) * extent * (right ? 1 : -1) * r, y + yy * r); } path.close(); const p = this.paint(this.palette.primary); this.drawPath(path, p); p.delete(); path.delete(); }
    this.label(Number.isFinite(fraction) ? `${Math.round(fraction * 100)}%` : '—', { x: b.x, y: b.y + b.h * .83, w: b.w, h: b.h * .17 }, { size: 14, color: this.palette.secondary });
  }

  private toggle(d: Json, c: Context, b: Box) { const on = bound(d.on, c) === true || bound(d.on, c) === 'true'; this.rect(inset(b, dp(4)), on ? this.palette.accent : this.palette.surface, dp(12)); const color = on ? '#ffffff' : this.palette.primary; this.icon(text(d.icon, c), { x: b.x + (b.w - dp(28)) / 2, y: b.y + dp(12), w: dp(28), h: dp(28) }, color); this.label(text(d.label, c), { x: b.x + dp(4), y: b.y + b.h - dp(36), w: b.w - dp(8), h: dp(30) }, { size: 14, color, lines: 1 }); this.addHit({ box: b, pluginId: c.id, tap: () => this.send(c.id, 'tap', d.tap), hold: d.hold ? () => this.send(c.id, 'tap', d.hold) : undefined }); }
  private slider(d: Json, c: Context, b: Box) { const level = d.level === 'volume' ? 'volume' : 'brightness', value = Number(this.scene?.settings?.[level] ?? this.scene?.media?.[level] ?? .5), x = b.x + dp(40), width = b.w - dp(56), y = b.y + b.h / 2; this.icon(level, { x: b.x + dp(4), y: y - dp(12), w: dp(24), h: dp(24) }, this.palette.secondary); this.line(x, y, x + width, y, this.palette.divider, dp(4)); this.line(x, y, x + width * value, y, this.palette.accent, dp(4)); this.circle(x + width * value, y, dp(9), this.palette.primary); this.addHit({ box: b, pluginId: c.id, drag: (px, _py, final) => { if (final) this.send(c.id, '@level', level, Math.max(0, Math.min(1, (px - x) / width))); }, tap: () => {} }); }
  private media(c: Context): Json { return this.scene?.media ?? c.state.media ?? {}; }
  private controls(d: Json, c: Context, b: Box) { const media = this.media(c), active = ['playing', 'buffering', 'reconnecting'].includes(media.status), actions = d.mode === 'play' ? ['toggle'] : d.mode === 'seek' ? ['rewind', 'toggle', 'forward'] : ['prev', 'toggle', 'next'], size = Math.min(dp(64), b.h - dp(8), b.w / (actions.length + 1)), gap = size * .35, all = size * actions.length + gap * (actions.length - 1); actions.forEach((action, i) => { const box = { x: b.x + (b.w - all) / 2 + i * (size + gap), y: b.y + (b.h - size) / 2, w: size, h: size }; if (action === 'toggle') this.circle(box.x + size / 2, box.y + size / 2, size / 2, this.palette.accent); const color = action === 'toggle' ? this.palette.bg : this.palette.primary; if (action === 'toggle' && !active) this.icon('play', inset(box, size * .24), color); else if (action === 'toggle') { this.rect({ x: box.x + size * .34, y: box.y + size * .28, w: size * .10, h: size * .44 }, color); this.rect({ x: box.x + size * .56, y: box.y + size * .28, w: size * .10, h: size * .44 }, color); } else this.label(action === 'prev' ? '‹' : action === 'next' ? '›' : action === 'rewind' ? '−10' : '+10', box, { size: 32, color, fit: true, lines: 1 }); this.addHit({ box, pluginId: c.id, tap: () => { this.videoControlsUntil = performance.now() + 4000; if (action === 'rewind' || action === 'forward') this.send(c.id, '@media', 'seek', Math.max(0, Number(media.position ?? 0) + (action === 'rewind' ? -10000 : 10000))); else this.send(c.id, '@media', action); }, label: action }); }); }
  private seekbar(c: Context, b: Box) { const media = this.media(c), position = Number(media.position ?? 0), duration = Number(media.duration ?? 0), value = duration > 0 ? Math.max(0, Math.min(1, position / duration)) : 0, x = b.x + dp(8), width = b.w - dp(16), y = b.y + b.h * .35; this.line(x, y, x + width, y, this.palette.divider, dp(4)); if (value) this.line(x, y, x + width * value, y, this.palette.accent, dp(4)); const stamp = (seconds: number) => `${Math.floor(Math.max(0, seconds) / 60)}:${String(Math.floor(Math.max(0, seconds) % 60)).padStart(2, '0')}`; this.label(media.live ? 'LIVE' : stamp(position / 1000), { x, y: y + dp(7), w: width / 2, h: dp(24) }, { size: 14, align: 'left', color: this.palette.secondary }); this.label(duration ? stamp((duration - position) / 1000) : '', { x: x + width / 2, y: y + dp(7), w: width / 2, h: dp(24) }, { size: 14, align: 'right', color: this.palette.secondary }); if (duration > 0) this.addHit({ box: b, pluginId: c.id, drag: (px, _py, final) => { if (final) this.send(c.id, '@media', 'seek', Math.round(Math.max(0, Math.min(1, (px - x) / width)) * duration)); }, tap: () => {} }); }
  private nowPlaying(d: Json, c: Context, b: Box) { const media = this.media(c);
    if (d.style === 'bar') {
      if (!media.count || media.status === 'stopped') { this.label(text(d.idle,c), b, {size:14}); return; }
      const transport = Math.min(b.w * .45, dp(172)), title = {...b, w:b.w-transport};
      this.label(String(media.title), {...title,h:b.h*.57}, {size:18,align:'left',lines:1});
      this.label(String(media.artist), {...title,y:b.y+b.h*.57,h:b.h*.43}, {size:14,align:'left',lines:1});
      if (d.tap) this.addHit({box:title,pluginId:c.id,label:d.tap,tap:()=>this.send(c.id,'tap',d.tap)});
      this.controls({mode:'queue'}, c, {...b,x:b.x+title.w,w:transport}); return;
    }
    if (c.variant === 'queue' && Array.isArray(media.queue)) {
      const rowHeight=dp(60), key=c.id+':upnext', offset=this.scrolls.get(key)??0;
      this.scrollHit(b,c,key,rowHeight*media.queue.length);
      media.queue.forEach((entry:Json,i:number)=>{ const box={...b,y:b.y+i*rowHeight-offset,h:rowHeight};
        if(!intersect(box,b)) return;
        this.label(String(entry.title),box,{size:18,color:media.index===i+1?this.palette.accent:this.palette.primary});
        this.addHit({box,pluginId:c.id,label:entry.title,tap:()=>this.send(c.id,'@media','jump',i+1)});
      }); return;
    }
    const full = d.style !== 'bar' && d.style !== 'card', horizontal = full && b.w > b.h * 1.2; const coverSize = full ? Math.min(horizontal ? b.w * .42 : b.w * .55, b.h * (horizontal ? .85 : .42)) : Math.min(b.h, dp(84)); const cover = { x: b.x + (horizontal ? 0 : (b.w - coverSize) / 2), y: b.y, w: coverSize, h: coverSize }; this.rect(cover, this.palette.surface, dp(12)); if (media.art) this.image({ src: media.art, fit: 'contain' }, c, cover, this.context(String(media.owner ?? '').split(':')[0]) ?? c); else this.icon('music', inset(cover, coverSize * .25), this.palette.accent); const content = horizontal ? { x: b.x + coverSize + dp(24), y: b.y, w: b.w - coverSize - dp(24), h: b.h } : { x: b.x, y: b.y + coverSize + dp(12), w: b.w, h: b.h - coverSize - dp(12) }; this.label(String(media.title || text(d.idle, c)), { ...content, h: content.h * .25 }, { size: full ? 32 : 18, lines: 2 }); this.label(String(media.artist ?? ''), { ...content, y: content.y + content.h * .25, h: content.h * .12 }, { size: 18, color: this.palette.secondary, lines: 1 }); this.seekbar(c, { ...content, y: content.y + content.h * .42, h: content.h * .2 }); this.controls({ mode: 'queue' }, c, { ...content, y: content.y + content.h * .64, h: content.h * .3 }); const notice = text(d.notice, c); if (notice) this.label(notice, { x: b.x, y: b.y + b.h - dp(26), w: b.w, h: dp(26) }, { size: 14, color: this.palette.warn, lines: 1 }); }
  private video(d: Json, c: Context, b: Box) {
    const media = this.media(c), element = this.options.video, television = d.type === 'tv';
    this.rect(b, '#000000');
    this.addHit({ box: b, pluginId: c.id, tap: () => { this.videoControlsUntil = this.videoControlsUntil > performance.now() ? 0 : performance.now() + 4000; this.scheduled = true; } });
    if (element && element.readyState >= 2 && element.videoWidth > 0) {
      try {
        const frame = this.ck.MakeImageFromCanvasImageSource(element);
        try {
          const scale = d.fit === 'cover' ? Math.max(b.w / element.videoWidth, b.h / element.videoHeight) : Math.min(b.w / element.videoWidth, b.h / element.videoHeight), w = element.videoWidth * scale, h = element.videoHeight * scale;
          this.clipped(b, () => this.drawing.drawImageRect(frame, this.ck.XYWHRect(0, 0, element.videoWidth, element.videoHeight), this.ck.XYWHRect(b.x + (b.w - w) / 2, b.y + (b.h - h) / 2, w, h), null));
        } finally { frame.delete(); }
      } catch { this.warn('Video frame is unavailable to CanvasKit'); }
    } else if (media.status === 'error' || media.status === 'unavailable') this.label(String(media.error?.message ?? media.error ?? '无法播放'), b, { size: 18, color: '#ffffff' });
    const show = media.status !== 'playing' || this.videoControlsUntil > performance.now();
    if (!show) return;
    const channel = television ? bound(d.channel, c) ?? {} : {}, title = channel.title ?? channel.name ?? media.title;
    this.rect({ x: b.x, y: b.y + b.h - dp(136), w: b.w, h: dp(136) }, '#000000aa');
    if (title) this.label(String(title), { x: b.x + dp(20), y: b.y + dp(20), w: b.w - dp(40), h: dp(48) }, { size: television ? 32 : 18, color: '#ffffff', align: 'left' });
    if (!television) {
      this.seekbar(c, { x: b.x + dp(24), y: b.y + b.h - dp(124), w: b.w - dp(48), h: dp(50) });
      this.controls({ mode: 'seek' }, c, { x: b.x, y: b.y + b.h - dp(76), w: b.w, h: dp(70) });
    } else {
      const actions = ['prev', 'next', 'list', 'line', 'favorite'], labels = ['‹', '›', 'list', '≋', 'star'];
      actions.forEach((action, i) => {
        const box = { x: b.x + i * b.w / 5, y: b.y + b.h - dp(76), w: b.w / 5, h: dp(70) };
        if (i === 2 || i === 4) this.icon(labels[i], { x: box.x + (box.w - dp(28)) / 2, y: box.y + (box.h - dp(28)) / 2, w: dp(28), h: dp(28) }, '#ffffff'); else this.label(labels[i], box, { size: 32, color: '#ffffff' });
        this.addHit({ box, pluginId: c.id, tap: () => { this.videoControlsUntil = performance.now() + 4000; this.send(c.id, 'tap', d.tap, action); }, label: action });
      });
    }
  }

  private panel(slots: Json, c: Context, b: Box) {
    const toggles = slots.quick?.children ?? [], levels = slots.levels?.children ?? [];
    const plan = panelBoxes(b.w, b.h, !!slots.header, !!slots.quick, levels.length, !!slots.media && Number(this.media(c).count ?? 0) > 0, !!slots.footer, !!slots.update && !!text(slots.update.value, c), toggles.length);
    const left = b.x + (b.w - plan.width) / 2;
    for (const [role, rectangle] of Object.entries(plan.slots)) {
      if (!rectangle || !slots[role]) continue;
      const box = { ...rectangle, x: rectangle.x + left, y: rectangle.y + b.y };
      if (role === 'quick') {
        const columns = toggles.length > 4 ? 3 : Math.max(1, toggles.length), rows = Math.ceil(toggles.length / columns);
        toggles.forEach((toggle: Json, index: number) => this.component(toggle, c, { x: box.x + (index % columns) * box.w / columns, y: box.y + Math.floor(index / columns) * box.h / rows, w: box.w / columns, h: box.h / rows }, `quick:${index}`));
      } else if (role === 'levels') levels.forEach((level: Json, index: number) => this.component(level, c, { ...box, y: box.y + index * box.h / levels.length, h: box.h / levels.length }, `level:${index}`));
      else this.group(slots[role], c, box);
    }
  }

  private installInput() {
    const point = (event: PointerEvent | WheelEvent) => { const r = this.canvas.getBoundingClientRect(); return { x: (event.clientX - r.left) * this.canvas.width / r.width, y: (event.clientY - r.top) * this.canvas.height / r.height }; };
    const on = (name: string, listener: EventListener) => { this.canvas.addEventListener(name, listener, { passive: false }); this.pointerHandlers.push([name, listener]); };
    on('pointerdown', ((event: PointerEvent) => { if (!event.isPrimary || event.button !== 0) return; event.preventDefault(); this.canvas.focus(); const p = point(event), hit = [...this.hits].reverse().find(h => (h.tap || h.drag || h.hold) && contains(h.box, p.x, p.y)); this.down = { ...p, at: performance.now(), hit, moved: false, held: false, lastY: p.y }; this.canvas.setPointerCapture(event.pointerId); this.holdTimer = setTimeout(() => { if (this.down && !this.down.moved) { this.down.held = true; if (hit?.hold) hit.hold(); else this.send(hit?.pluginId ?? this.scene!.page.split('.')[0], '@settings', hit?.pluginId ?? this.scene!.page.split('.')[0]); } }, 500); }) as EventListener);
    on('pointermove', ((event: PointerEvent) => { if (!this.down) return; const p = point(event), down = this.down, dx = p.x - down.x, dy = p.y - down.y; if (Math.hypot(dx, dy) > dp(8)) { down.moved = true; clearTimeout(this.holdTimer); } if (down.hit?.drag && (Math.abs(dx) > Math.abs(dy) || down.moved)) down.hit.drag(p.x, p.y, false); else if (down.moved && Math.abs(dy) > Math.abs(dx)) { const scroll = [...this.hits].reverse().find(h => h.scroll && contains(h.box, down.x, down.y)); if (scroll?.scroll) scroll.scroll(down.lastY - p.y); } down.lastY = p.y; }) as EventListener);
    on('pointerup', ((event: PointerEvent) => { if (!this.down) return; const p = point(event), down = this.down, dx = p.x - down.x, dy = p.y - down.y, owner = this.scene?.page.split('.')[0] ?? ''; clearTimeout(this.holdTimer); this.down = undefined;
      if (down.held) return;
      if (down.hit?.drag && Math.abs(dx) >= Math.abs(dy)) { down.hit.drag(p.x, p.y, true); return; }
      if (down.moved) {
        if (down.x < dp(24) && dx > dp(48) && Math.abs(dx) > Math.abs(dy)) this.send(owner, '@back');
        else if (down.y > this.canvas.height - dp(24) && dy < -dp(48)) this.send(owner, '@home');
        else if (down.y < dp(24) && dy > dp(48)) this.send(owner, '@control');
        else if (Math.abs(dx) > dp(48) && Math.abs(dx) > Math.abs(dy)) this.send(owner, '@deck', dx < 0 ? 1 : -1);
        else if (Math.abs(dy) > dp(48) && !this.hits.some(h => h.scroll && contains(h.box, down.x, down.y))) this.send(owner, '@variant', dy < 0 ? 1 : -1);
      } else if (down.hit?.tap && contains(down.hit.box, p.x, p.y)) down.hit.tap();
    }) as EventListener);
    on('pointercancel', (() => this.cancelPointer()) as EventListener);
    on('lostpointercapture', (() => this.cancelPointer()) as EventListener);
    on('wheel', ((event: WheelEvent) => { event.preventDefault(); const p = point(event), hit = [...this.hits].reverse().find(h => h.scroll && contains(h.box, p.x, p.y)); hit?.scroll?.(event.deltaY * DENSITY); }) as EventListener);
    on('keydown', ((event: KeyboardEvent) => { const owner = this.scene?.page.split('.')[0] ?? ''; if (event.key === 'Escape' || event.key === 'Backspace') { event.preventDefault(); this.send(owner, '@back'); } else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); this.send(owner, '@deck', event.key === 'ArrowRight' ? 1 : -1); } else if (event.key === 'ArrowUp' || event.key === 'ArrowDown') { event.preventDefault(); this.send(owner, '@variant', event.key === 'ArrowUp' ? 1 : -1); } else if (event.key === 'Home') { event.preventDefault(); this.send(owner, '@home'); } }) as EventListener);
  }
  private cancelPointer() { clearTimeout(this.holdTimer); this.down = undefined; }
}
