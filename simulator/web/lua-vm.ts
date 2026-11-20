/** MIT. Worker-only adapter to the unmodified production Lua/JNI implementation. */
export interface Step {
  status: number;
  taskId: number;
  message: string | null;
  cpuMicros: null;
  wallMicros: number;
  hostCpuMicros: null;
  hostCalls: number;
  slowestHost: string | null;
  slowestHostMicros: null;
  compileMicros: null;
  cpuAvailable: false;
}
type Host = (method: string, args: unknown[], taskId: number) => unknown;
type Module = {
  HEAPU16: Uint16Array;
  tosHost?: (method: string, args: number, taskId: number) => number;
  [name: string]: any;
};
let modulePromise: Promise<Module> | undefined;
let activeHost: Host | undefined;
let busy = false;

async function loadModule(): Promise<Module> {
  if (typeof document !== "undefined") throw new Error("Lua must run in a dedicated worker");
  modulePromise ??= (async () => {
    const url = "/runtime/tos.js";
    const imported = await import(/* @vite-ignore */ url);
    const module: Module = await imported.default({ locateFile: (name: string) => `/runtime/${name}` });
    module.tosHost = (method, args, taskId) => {
      if (!activeHost) throw new Error("No active Lua host");
      const value = activeHost(method, decode(module, args) as unknown[], taskId);
      if (value && typeof (value as { then?: unknown }).then === "function") {
        throw new Error("Lua host must return synchronously; resume asynchronous tasks separately");
      }
      return encode(module, value);
    };
    return module;
  })();
  return modulePromise;
}

function stringValue(module: Module, value: string): number {
  if (value.length > 1_048_576) throw new Error("Host string exceeds 1 MiB UTF-16 units");
  const buffer = module._malloc(Math.max(2, value.length * 2));
  if (!buffer) throw new Error("Host string allocation failed");
  try {
    for (let index = 0; index < value.length; index++) module.HEAPU16[(buffer >>> 1) + index] = value.charCodeAt(index);
    return module._tos_value_string(buffer, value.length);
  } finally { module._free(buffer); }
}

function encode(module: Module, value: unknown, depth = 0, seen = new Set<object>()): number {
  if (depth > 24) throw new Error("Host value nesting exceeds 24");
  if (value === null || value === undefined) return 0;
  if (typeof value === "boolean") return module._tos_value_bool(value ? 1 : 0);
  if (typeof value === "bigint") {
    if (value < -9223372036854775808n || value > 9223372036854775807n) throw new Error("Host integer exceeds int64");
    return module._tos_value_int(value);
  }
  // Integral Lua floats must remain Double when state/store return them. A
  // boxed Number survives structuredClone without changing its numeric value.
  if (value instanceof Number) return module._tos_value_double(value.valueOf());
  if (typeof value === "number") {
    // Java host integer values normally arrive as Long; safe integral numbers
    // retain that convention. BigInt is mandatory outside the safe range.
    if (Number.isSafeInteger(value) && !Object.is(value, -0)) return module._tos_value_int(BigInt(value));
    return module._tos_value_double(value);
  }
  if (typeof value === "string") return stringValue(module, value);
  if (typeof value !== "object") throw new Error("Unsupported host value");
  if (seen.has(value)) throw new Error("Cyclic host value");
  seen.add(value);
  try {
    if (Array.isArray(value)) {
      if (value.length > 1_048_576) throw new Error("Host array too large");
      const result = module._tos_value_array(value.length);
      value.forEach((item, index) => module._tos_array_set(result, index, encode(module, item, depth + 1, seen)));
      return result;
    }
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) throw new Error("Host object must be a plain record");
    const entries = Object.entries(value);
    if (entries.length > 4096) throw new Error("Host object has too many keys");
    const result = module._tos_value_map();
    for (const [key, item] of entries) module._tos_map_set(result, stringValue(module, key), encode(module, item, depth + 1, seen));
    return result;
  } finally { seen.delete(value); }
}

function decode(module: Module, value: number, depth = 0): unknown {
  if (depth > 24) throw new Error("Host value nesting exceeds 24");
  switch (module._tos_value_kind(value)) {
    case 0: return null;
    case 1: return module._tos_value_i64(value) !== 0n;
    case 2: {
      const integer: bigint = module._tos_value_i64(value);
      return integer >= BigInt(Number.MIN_SAFE_INTEGER) && integer <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(integer) : integer;
    }
    case 3: {
      const real: number = module._tos_value_f64(value);
      return Number.isInteger(real) ? new Number(real) : real;
    }
    case 4: {
      const start = module._tos_value_chars(value) >>> 1;
      const length = module._tos_value_length(value);
      let text = "";
      for (let index = 0; index < length; index += 8192) text += String.fromCharCode(...module.HEAPU16.subarray(start + index, start + Math.min(length, index + 8192)));
      return text;
    }
    case 5: return Array.from({ length: module._tos_value_length(value) }, (_, index) => decode(module, module._tos_value_get(value, index), depth + 1));
    case 6: {
      const result: Record<string, unknown> = Object.create(null);
      for (let index = 0; index < module._tos_value_length(value); index++) {
        result[decode(module, module._tos_value_key(value, index), depth + 1) as string] = decode(module, module._tos_value_get(value, index), depth + 1);
      }
      return result;
    }
    default: throw new Error("Unsupported JNI adapter value");
  }
}

export class LuaVm {
  private handle = 0;
  private module: Module;
  private host: Host;
  private constructor(module: Module, host: Host) { this.module = module; this.host = host; }
  static async create(host: Host): Promise<LuaVm> {
    const vm = new LuaVm(await loadModule(), host);
    vm.handle = vm.call(() => vm.module._tos_vm_create());
    if (!vm.handle) throw new Error("Lua allocation failed");
    return vm;
  }
  private call<T>(operation: () => T): T {
    if (busy) throw new Error("Reentrant Lua execution is forbidden");
    busy = true; activeHost = this.host;
    if (this.module._tos_frame_push() !== 0) { busy = false; activeHost = undefined; throw new Error("JNI frame allocation failed"); }
    try { return operation(); }
    finally { this.module._tos_frame_pop(); activeHost = undefined; busy = false; }
  }
  private live(): number { if (!this.handle) throw new Error("Lua VM is closed"); return this.handle; }
  private step(pointer: number): Step {
    if (!pointer) throw new Error("Native step allocation failed");
    return {
      status: this.module._tos_step_status(pointer), taskId: this.module._tos_step_task(pointer),
      message: decode(this.module, this.module._tos_step_message(pointer)) as string | null,
      cpuMicros: null, wallMicros: Number(this.module._tos_step_metric(pointer, 1)), hostCpuMicros: null,
      hostCalls: Number(this.module._tos_step_metric(pointer, 3)),
      slowestHost: decode(this.module, this.module._tos_step_method(pointer)) as string | null,
      slowestHostMicros: null, compileMicros: null, cpuAvailable: false,
    };
  }
  start(code: string | null, name = "plugin", entry?: "dispatch" | "timer", args: unknown[] = []): Step {
    if (entry === undefined && code === null) throw new Error("Lua source or runtime entry is required");
    return this.call(() => this.step(this.module._tos_vm_start(this.live(), encode(this.module, entry ? null : code), encode(this.module, name), encode(this.module, entry), encode(this.module, args))));
  }
  resume(taskId: number, args: unknown[]): Step {
    return this.call(() => this.step(this.module._tos_vm_resume(this.live(), taskId, encode(this.module, args))));
  }
  bind(): void { this.call(() => this.module._tos_vm_bind(this.live())); }
  cancel(taskId: number): void { this.call(() => this.module._tos_vm_cancel(this.live(), taskId)); }
  memory(): number { return this.call(() => Number(this.module._tos_vm_memory(this.live()))); }
  destroy(): void {
    if (!this.handle) return;
    this.call(() => this.module._tos_vm_destroy(this.handle)); this.handle = 0;
  }
}

export async function readBootstrap(url = "/runtime/bootstrap.lua"): Promise<string> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Cannot load Lua bootstrap (${response.status})`);
  return response.text();
}
