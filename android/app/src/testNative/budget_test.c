/* Run with tools/test-lua-native.sh. Exercises actual Lua and production allocator/hook. */
#include <assert.h>
#include <stdio.h>
#define TOS_NATIVE_TEST 1
#include "../main/cpp/tos_bridge.c"

static void test(const char *label, const char *code, int expect_ok, int expect_budget) {
    Runtime *r = calloc(1, sizeof(*r));
    r->L = lua_newstate(limited_alloc, r);
    assert(r->L);
    *(Runtime **)lua_getextraspace(r->L) = r;
    lua_pushcfunction(r->L, initialize);
    assert(lua_pcall(r->L, 0, 0, 0) == LUA_OK);
    lua_sethook(r->L, budget, LUA_MASKCOUNT, 1000);
    begin_slice(r);
    uint64_t begin = nanos();
    lua_State *co = lua_newthread(r->L);
    assert(luaL_loadbufferx(co, code, strlen(code), label, "t") == LUA_OK);
    int results;
    int status = lua_resume(co, NULL, 0, &results);
    double elapsed = (nanos()-begin)/1e6;
    assert((status == LUA_OK) == expect_ok);
    assert((r->exhausted != 0) == expect_budget);
    assert(elapsed < 500);
    assert(r->used <= PLUGIN_LIMIT);
    printf("PASS %s (%.2f ms, %zu bytes)\n",label,elapsed,r->used);
    lua_close(r->L); free(r);
    assert(total_used == 0);
}
int main(void) {
    test("basic Lua + forbidden libraries", "assert(_VERSION=='Lua 5.4'); assert(io==nil and os==nil and package==nil and debug==nil and dofile==nil and loadfile==nil); local f=assert(load('return 42')); assert(f()==42)",1,0);
    test("infinite loop", "while true do end",0,1);
    test("pcall cannot swallow budget", "while true do pcall(function() while true do end end) end",0,1);
    test("xpcall cannot swallow budget", "while true do xpcall(function() while true do end end,function() return '' end) end",0,1);
    test("nested coroutine budget", "local co=coroutine.create(function() while true do end end); while true do coroutine.resume(co) end",0,1);
    test("wrapped coroutine budget", "while true do pcall(coroutine.wrap(function() while true do end end)) end",0,1);
    test("non-yieldable comparator pcall budget", "table.sort({2,1},function() while true do pcall(function() while true do end end) end end)",0,1);
    test("nested non-yieldable xpcall budget", "while true do pcall(function() table.sort({2,1},function() while true do xpcall(function() while true do end end,function() return true end) end end) end) end",0,1);
    test("allocator rejects large string", "local s=string.rep('x',8*1024*1024)",0,0);
    test("caught allocator errors still bounded", "while true do pcall(function() return string.rep('x',8*1024*1024) end) end",0,1);
    test("binary load refused", "local f,e=load(string.dump(function() return 1 end)); assert(f==nil and e~=nil)",1,0);
    test("bounded pattern backtracking", "return string.match(string.rep('a',200),'^a*a*a*a*a*b')",0,1);
    test("pattern pcall cannot swallow budget", "while true do pcall(function() return string.rep('a',200):gsub('^a*a*a*a*a*b','x') end) end",0,1);
    test("pattern normal behavior", "assert(('abc123'):match('(%d+)')=='123'); assert(('a1b2'):gsub('%d','x')=='axbx')",1,0);
    return 0;
}
