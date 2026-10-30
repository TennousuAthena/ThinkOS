# Native source policy

Lua 5.4.9 retains its upstream MIT license and source. The only modified Lua source is `lua/lstrlib.c`:

- calls `tos_pattern_step` on pattern recursion, character checks, balanced scans and literal-search retries;
- propagates the currently executing plugin's CPU/instruction/wall limit through the native matcher;
- does not expose debug/package/io/os or change pattern syntax.

The host callback lives in `tos_bridge.c`; its exhaustion latch also propagates through protected calls. Normal API suspension is `lua_yieldk`, while resource exhaustion is a classified error, so budget failures cannot be mistaken for pending IO.

`json/` vendors yyjson 0.12.0 (MIT, source URLs and SHA-256 in SOURCE.json), compiled from C for the same armeabi-v7a ABI. Its allocator charges both JSON arenas and Lua allocations against 4MiB/plugin and 24MiB aggregate. Codec conversion is protected, depth <=24, body <=1MiB, and native arenas are freed even on Lua OOM. No external binary/AAR is used. Debug and release native code use -O2 for representative A53 performance.

When upgrading Lua, reapply the small lstrlib budget calls and run src/testNative/run.sh, including pathological match/gsub, pcall, literal scan, asynchronous resume/cancellation and allocator tests. Do not replace the source without this regression suite.
