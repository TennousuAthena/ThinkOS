# ThinkOS browser Lua runtime

The WebAssembly translation unit includes the production `tos_bridge.c` directly.
Lua 5.4.9, its pattern-budget patch, and yyjson 0.12.0 are compiled from their
existing Android source paths. No Android source, ABI, build configuration, or
bootstrap is modified. The simulator's limited JNI adapter implements only the
operations used by that source; it is not a JVM.

Run `python3 simulator/scripts/build_wasm.py --check` to verify the source lock
and bootstrap declaration. Run without `--check` to build
`simulator/public/runtime/{tos.js,tos.wasm,bootstrap.lua,sources.json,LICENSES.txt}` using the
reviewed Emscripten 6.0.13 installation under `simulator/.toolchains/emsdk` (or
an explicit `--emcc`). The build never installs or downloads a toolchain.
`toolchain.lock.json` records the inspected upstream installer and release.

Source drift fails the build. `--update-lock` is an explicit review action, not
part of ordinary builds; rerun conformance before accepting changed sources.
Bootstrap extraction recognizes only the current single raw Kotlin string and
`trimIndent()` expression, rejects interpolation/other expressions, and checks
the entire source's hash. Outputs stay outside Android.

Lua integers remain signed 64-bit and floats remain double on wasm32. The JS
host receives `bigint` outside the safe integer range and boxed `Number` for
integral Lua floats; preserve these through state/store and convert only at
presentation boundaries. Lua state, coroutine, JSON, sandbox and instruction/
memory accounting run in the production C implementation. All VMs in a worker
share the production 24 MiB aggregate cap.

Browsers do not provide thread CPU time. The clock adapter explicitly disables
that measurement and the TypeScript result reports CPU fields as unavailable.
The production 200 ms monotonic wall guard, instruction quota, pattern hook and
per-plugin allocation cap remain active. Real device CPU/performance validation
is separate. The JavaScript host must return synchronously; asynchronous APIs
yield and resume through the existing task ID protocol.

Validation:

```sh
python3 -I -m unittest discover -s simulator/tests -p 'test_native*.py' -v
node simulator/native/wasm-conformance.mjs
```

The first command compiles and runs the unmodified production bridge through
the JNI adapter on the host. The second uses an installed Chromium in headless
mode and runs the actual TypeScript adapter plus WASM in a DedicatedWorker;
set `THINKOS_TEST_CHROME` if needed. It starts only a temporary loopback server
and never downloads a browser. Tests cover Unicode, int64, float subtype and
negative zero, JSON null/empty containers, strict parsing, sandboxing, event
dispatch, successive async resumes, cancellation, host exceptions, uncatchable
instruction/pattern limits, memory limits and repeated execution. The native
test also asserts all local/global references are released.

As validated on 2026-11-07: all five Python/native tests and browser WASM
conformance pass. A first browser run exposed a missing exported memory view;
the build now exports `HEAPU16` explicitly and the rerun passes. Source hashes
and an empty Android diff confirm the read-only boundary.

Licenses: simulator adapter and existing ThinkOS bridge MIT; Lua and yyjson
MIT; Emscripten MIT/NCSA. Original vendor notices remain in their source files,
generated JavaScript retains the Emscripten runtime notice, and `LICENSES.txt`
is emitted alongside the binary so the Vite distribution carries full notices.

The companion astronomy implementation ports the repository's MIT Kotlin
algorithms and coefficient tables. All 101 season years, all solar/moon vectors,
DST/polar/civil-day cases, and source-identity checks pass. `locale-time.ts` uses
the original Units and LocaleChain behavior, named time skeletons and raw
SimpleDateFormat fields. `build_assets.py` exports the exact bundled 2026e TZif
transitions, local types, aliases and POSIX footers; 28 dates from 1900 through
2099 for every bundled zone match Python's independent ZoneInfo reader, in
addition to the original Kotlin timezone vectors. Astronomy and time formatting
share those rules; neither uses browser timezone rules for bundled zones.

```sh
node --experimental-strip-types --test simulator/tests/astronomy.test.ts simulator/tests/locale-time.test.ts
python3 simulator/scripts/build_assets.py --check
```

The remaining locale boundary is explicit: browser CLDR supplies localized date,
month, country and period text, so wording/punctuation can differ from Android
8.1 ICU. Browsers have no exemplar-city API; the device's documented zone-id
fallback is used. Raw week patterns use Intl's first-day data and Unicode CLDR's
minimal-week-day metadata when newer Intl omits that field; the Unicode V3
notice is preserved under `assets/licenses` and in the distributed notices.
Only bundled aliases and fixed `Etc/GMT` IDs are supported for zone selection;
other unknown IDs follow the configured home zone, without inventing an offset.
These text/platform differences are not advertised as calibrated device parity.
