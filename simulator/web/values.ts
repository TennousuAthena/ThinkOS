// MIT — mirrors JsonDocument's atomic recursive merge; values never inherit object prototypes.
export type Obj = Record<string, any>;
export function record(value: unknown): value is Obj {
  return value !== null && typeof value === 'object' && !Array.isArray(value) && !(value instanceof Number);
}
export function clone<T>(value: T): T { return structuredClone(value); }
export function path(value: unknown, key: string): any {
  if (!key) return value;
  let current: any = value;
  for (const segment of key.split('.')) {
    if (!record(current) || !Object.hasOwn(current, segment)) return null;
    current = current[segment];
  }
  return current;
}
export function json(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'bigint') return value.toString();
  if (value instanceof Number) { const n=value.valueOf(); return Object.is(n,-0)?'-0.0':Number.isInteger(n)?json(n)+'.0':json(n); }
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error('non-finite JSON value');
    return String(value);
  }
  if (typeof value === 'string' || typeof value === 'boolean') return JSON.stringify(value);
  if (Array.isArray(value)) return '[' + value.map(json).join(',') + ']';
  if (record(value)) return '{' + Object.entries(value).map(([k,v]) => JSON.stringify(k)+':'+json(v)).join(',') + '}';
  throw new Error('Unsupported JSON type');
}
/** Keep Lua int64 and integral doubles across JSON persistence, including negative zero. */
export function parseJson(source:string,limit=16*1024*1024):any {
  if(new TextEncoder().encode(source).length>limit)throw Error('JSON document exceeds limit');
  let at=0;
  const space=()=>{while(/[\x20\t\r\n]/.test(source[at]??'!'))at++;};
  const string=()=>{const begin=at++;while(at<source.length){const c=source[at++];if(c==='"')return JSON.parse(source.slice(begin,at));if(c==='\\')at++;}throw Error('unterminated JSON string');};
  function value(depth:number):any {
    if(depth>32)throw Error('JSON nesting exceeds limit');space();const c=source[at];
    if(c==='"')return string();
    if(c==='{'||c==='['){const object=c==='{',out:any=object?Object.create(null):[],end=object?'}':']';at++;space();if(source[at]===end){at++;return out;}
      while(true){space();if(object&&source[at]!=='"')throw Error('expected JSON key');const key=object?string():out.length;if(object){space();if(source[at++]!==':')throw Error('expected colon');}out[key]=value(depth+1);space();if(source[at]===end){at++;return out;}if(source[at++]!==',')throw Error('expected comma');}
    }
    for(const [word,result] of [['true',true],['false',false],['null',null]] as const)if(source.startsWith(word,at)){at+=word.length;return result;}
    const token=/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(source.slice(at))?.[0];if(!token)throw Error('invalid JSON value');at+=token.length;
    const n=Number(token);if(!Number.isFinite(n))throw Error('non-finite JSON value');
    if(/[.eE]/.test(token)||token==='-0')return Number.isInteger(n)?new Number(n):n;
    return Number.isSafeInteger(n)?n:BigInt(token);
  }
  const result=value(0);space();if(at!==source.length)throw Error('trailing JSON input');return result;
}
export function bounded(value: unknown, limit = 256 * 1024): void {
  if (new TextEncoder().encode(json(value)).length > limit) throw new Error('JSON document exceeds limit');
}
export function merge(before: Obj, patch: unknown, limit = 256 * 1024): Obj {
  if (!record(patch)) throw new Error('state patch must be an object');
  function visit(a: Obj, b: Obj, depth: number): Obj {
    if (depth > 24) throw new Error('host value nesting exceeds 24');
    const result: Obj = Object.assign(Object.create(null), a);
    for (const [key,value] of Object.entries(b)) {
      result[key] = record(result[key]) && record(value) ? visit(result[key], value, depth+1) : clone(value);
    }
    return result;
  }
  const result = visit(before, patch, 0); bounded(result, limit); return result;
}
export function base64Text(value: string): string {
  return new TextDecoder('utf-8', {fatal: true}).decode(Uint8Array.from(atob(value), c=>c.charCodeAt(0)));
}
export function defaults(fields: Obj): Obj {
  return Object.fromEntries(Object.entries(fields).map(([key,field])=>[key,clone(field.default)]));
}
export function translate(table: Obj, key: string, params: Obj = {}): string {
  let text = table[key] ?? path(table,key) ?? key;
  if (record(text)) text = text.other ?? key;
  return String(text).replace(/\{([A-Za-z0-9_]+)\}/g, (whole,k)=>params[k] === undefined ? whole : String(params[k]));
}
export function eventAllowed(name: string, api: number, grants: Set<string>): boolean {
  const rule: Record<string,[number,string]> = {
    presence:[1,'presence'],gesture:[1,'presence'],location:[1,'location'],device:[1,'system.device'],
    cast:[9,'media'],apps:[9,'system.apps'],update:[10,'system.update'],practice:[10,'system.settings'],plugins:[10,'system.plugins'],
    media:[7,'media'],'media.session':[7,'media'],'media.track':[7,'media'],'media.error':[7,'media'],'media.warning':[7,'media'],'queue.end':[7,'media'],
  };
  return name==='mode'?api>=10:!rule[name] || (api >= rule[name][0] && grants.has(rule[name][1]));
}
export function validateSetting(field: Obj, value: unknown): unknown {
  if(value instanceof Number)value=value.valueOf();
  if (field.type === 'bool' && typeof value !== 'boolean') throw new Error('type');
  if (field.type === 'number') {
    if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error('type');
    if (value < (field.min ?? -Infinity)) throw new Error('min');
    if (value > (field.max ?? Infinity)) throw new Error('max');
  }
  if (field.type === 'text' || field.type === 'choice') {
    if (typeof value !== 'string') throw new Error('type');
    if (value.length > (field.maxLength ?? 1024)) throw new Error('length');
    if(/[\x00-\x08\x0a-\x1f]/.test(value))throw Error('chars');
    if(field.type==='choice'&&value===''&&!field.allowEmpty)throw Error('required');
    if (field.choices && !field.choices.some((c:Obj)=>c.value === value) && !(field.allowEmpty && value === '')) throw new Error('option');
  }
  if(field.type==='time'&&(typeof value!=='string'||!/^([01][0-9]|2[0-3]):[0-5][05]$/.test(value)))throw Error('time');
  return value;
}
export class Health {
  errors: number[] = []; restarts: number[] = [];
  failure(now: number, resource: boolean): number | null {
    this.errors=this.errors.filter(t=>now-t<3600000); this.restarts=this.restarts.filter(t=>now-t<3600000);
    this.errors.push(now); if (!resource && this.errors.length<5) return null;
    this.errors=[]; this.restarts.push(now);
    return this.restarts.length>=5 ? 3600000 : 2**this.restarts.length*1000;
  }
}
