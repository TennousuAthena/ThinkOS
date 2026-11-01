/* Deterministic production player tests using the real sandbox allocator, hooks and JSON codec. */
#include <assert.h>
#include <stdio.h>
#define TOS_NATIVE_TEST 1
#include "../main/cpp/tos_bridge.c"

static uint64_t started;
static size_t peak;
static void *tracking_alloc(void *ud, void *p, size_t old, size_t next) {
    void *result = limited_alloc(ud, p, old, next);
    Runtime *r = ud;
    if (r->used > peak) peak = r->used;
    return result;
}
static double worst;
static int test_begin(lua_State *L) {
    lua_gc(L, LUA_GCCOLLECT);
    begin_slice(owner(L));
    peak = owner(L)->used;
    worst = 0;
    started = cpu_nanos();
    return 0;
}
/* The plugin gave the CPU back (tos.timer.sleep): the runtime starts a new 50 ms slice from here, so check the one that ended. */
static int test_slice(lua_State *L) {
    double cpu = (cpu_nanos() - started) / 1e6;
    if (cpu > worst) worst = cpu;
    assert(!owner(L)->exhausted && cpu < 50);
    begin_slice(owner(L));
    started = cpu_nanos();
    return 0;
}
static int test_end(lua_State *L) {
    Runtime *r = owner(L);
    double cpu = (cpu_nanos() - started) / 1e6;
    if (cpu > worst) worst = cpu;
    assert(!r->exhausted && cpu < 50 && peak < 4 * 1024 * 1024);
    printf("PASS player %s (%.3f ms longest slice, %zu bytes retained, %zu bytes peak)\n", luaL_checkstring(L, 1), worst, r->used, peak);
    return 0;
}
static void source(lua_State *L, const char *global, const char *path) {
    FILE *file = fopen(path, "rb"); assert(file);
    fseek(file, 0, SEEK_END); long size = ftell(file); rewind(file);
    char *data = malloc(size + 1); assert(data && fread(data, 1, size, file) == (size_t)size);
    fclose(file); lua_pushlstring(L, data, size); lua_setglobal(L, global); free(data);
}
int main(int argc, char **argv) {
    assert(argc == 7);
    Runtime *r = calloc(1, sizeof(*r));
    r->L = lua_newstate(tracking_alloc, r); assert(r->L);
    *(Runtime **)lua_getextraspace(r->L) = r;
    lua_pushcfunction(r->L, initialize); assert(lua_pcall(r->L, 0, 0, 0) == LUA_OK);
    const char *names[] = {"main_source", "m3u_source", "library_source", "manifest_json", "strings_json"};
    for (int i = 0; i < 5; i++) source(r->L, names[i], argv[i + 2]);
    lua_pushcfunction(r->L, test_begin); lua_setglobal(r->L, "test_begin");
    lua_pushcfunction(r->L, test_end); lua_setglobal(r->L, "test_end");
    lua_pushcfunction(r->L, test_slice); lua_setglobal(r->L, "test_slice");
    lua_sethook(r->L, budget, LUA_MASKCOUNT, 1000); begin_slice(r);
    source(r->L, "test_source", argv[1]); lua_getglobal(r->L, "test_source");
    size_t length; const char *code = lua_tolstring(r->L, -1, &length);
    assert(luaL_loadbufferx(r->L, code, length, "player_test", "t") == LUA_OK);
    if (lua_pcall(r->L, 0, 0, 0) != LUA_OK) { fprintf(stderr, "%s\n", lua_tostring(r->L, -1)); return 1; }
    lua_close(r->L); free(r); assert(total_used == 0);
    return 0;
}
