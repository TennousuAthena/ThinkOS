type Json = Record<string, any>;
export interface BindingContext { state: Json; config: Json; i18n: Json; variant?: string; locale?: string; now?: number }
/** The whole-value public image delegation rule from docs/06 §6.8. */
export function imagePrincipal(expression: unknown, consumerId: string, plugins: Record<string, { manifest: Json; state: Json }>): string {
  const consumer = plugins[consumerId];
  const match = typeof expression === 'string' ? /^\{\{\s*uses\.([a-z][a-z0-9-]*)(?:\.[A-Za-z0-9_.]+)?\s*\}\}$/.exec(expression) : null;
  if (!consumer || !match || lookup(consumer.state, `uses.${match[1]}`) == null) return consumerId;
  const use = lookup(consumer.manifest, `uses.${match[1]}`);
  if (typeof use?.export !== 'string') return consumerId;
  const parts = use.export.split('.'); if (parts.length !== 2) return consumerId;
  const [providerId, name] = parts, provider = plugins[providerId], exported = provider && lookup(provider.manifest, `exports.${name}`);
  if (!exported || exported.shape !== use.shape || typeof exported.path !== 'string' || !exported.path || ['config', 'uses', 'variant'].includes(exported.path.split('.')[0])) return consumerId;
  const permissions = consumer.manifest.permissions ?? [];
  if (!Array.isArray(exported.requires ?? []) || (exported.requires ?? []).some((permission: string) => permission === 'system.settings' || !permissions.includes(permission))) return consumerId;
  return providerId;
}
export function lookup(root: unknown, path: string): any {
  if (!path) return undefined;
  return path.split('.').reduce<any>((value, key) => value !== null && typeof value === 'object' && Object.hasOwn(value, key) ? value[key] : undefined, root);
}
export function translated(key: string, context: BindingContext): string {
  const table = context.i18n ?? {}, locale = context.locale ?? 'zh-Hans';
  const entries = table[locale] ?? table[locale === 'zh-CN' ? 'zh-Hans' : locale.split('-')[0]] ?? table['zh-Hans'] ?? table.en ?? table;
  return typeof entries[key] === 'string' ? entries[key] : key;
}
function source(path: string, context: BindingContext, item?: any): any {
  if (path === 'variant') return context.variant ?? '';
  if (path.startsWith('@')) return translated(path.slice(1), context);
  if (path.startsWith('.')) return lookup(item, path.slice(1));
  if (path === 'config') return context.config;
  if (path.startsWith('config.')) return lookup(context.config, path.slice(7));
  return lookup(item, path) ?? lookup(context.state, path);
}
export function bound(raw: any, context: BindingContext, item?: any): any {
  if (typeof raw !== 'string') return raw;
  const exact = /^\{\{\s*([A-Za-z@.][A-Za-z0-9_.@-]*)\s*\}\}$/.exec(raw);
  if (exact) return source(exact[1], context, item);
  return text(raw, context, item);
}
export function array(raw: any, context: BindingContext): any[] {
  const value = Array.isArray(raw) ? raw : typeof raw === 'string' && !raw.includes('{{') ? source(raw, context) : bound(raw, context);
  return Array.isArray(value) ? value.slice(0, 1000) : [];
}
export function text(raw: any, context: BindingContext, item?: any): string {
  if (raw == null) return '';
  if (typeof raw !== 'string') return String(raw);
  if (raw.startsWith('@') && !raw.startsWith('@@') && !raw.includes('{{')) return translated(raw.slice(1), context);
  const result = raw.replace(/\{\{([^{}]+)\}\}/g, (_, expression: string) => {
    const [path, ...formats] = expression.split('|').map(x => x.trim());
    let value = source(path, context, item);
    for (const format of formats) {
      const op = format.split(' ')[0], arg = format.slice(op.length).trim().replace(/^['"]|['"]$/g, '');
      switch (op) {
        case 'default': if (value == null || value === '') value = arg; break;
        case 'upper': value = value == null ? '' : String(value).toLocaleUpperCase(context.locale); break;
        case 'lower': value = value == null ? '' : String(value).toLocaleLowerCase(context.locale); break;
        case 'round': value = value != null && String(value).trim() !== '' && Number.isFinite(Number(value)) ? Math.round(Number(value)) : ''; break;
        case 'fixed': value = value != null && String(value).trim() !== '' && Number.isFinite(Number(value)) ? Number(value).toFixed(Math.min(6, Math.max(0, Number(arg) || 0))) : ''; break;
        case 'time': case 'date': {
          const n = Number(value); if (value == null || String(value).trim() === '' || !Number.isFinite(n)) { value = ''; break; }
          value = new Intl.DateTimeFormat(context.locale ?? 'zh-Hans', op === 'time' ? { hour: '2-digit', minute: '2-digit' } : { year: 'numeric', month: 'numeric', day: 'numeric' }).format(n); break;
        }
      }
    }
    return value == null ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value);
  });
  return result.startsWith('@@') ? result.slice(1) : result;
}
