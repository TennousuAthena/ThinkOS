#include "lua.h"
#include "lauxlib.h"

/* Link the vendored pattern hook for the standalone, trusted UI fixture CLI.
 * Sandbox budget enforcement is exercised separately by budget_test.c and JNI tests. */
void tos_pattern_step(lua_State *L) {
    static unsigned long steps;
    if (++steps > 5000000) luaL_error(L, "feedback fixture pattern budget exceeded");
}
