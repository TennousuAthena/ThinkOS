/* MIT. Compile the production VM verbatim; Android files are never generated or edited. */
#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#endif
#include <time.h>
#include <stdint.h>
#include "shim.h"
#ifdef __EMSCRIPTEN__
#include <emscripten.h>
static int simulator_clock_gettime(clockid_t clock, struct timespec *result) {
    /* Browsers cannot measure thread CPU. Zero deliberately disables that one
       limit; the adapter reports CPU unavailable, never as a measured zero. */
    double ms = clock == CLOCK_THREAD_CPUTIME_ID ? 0 : emscripten_get_now();
    result->tv_sec=(time_t)(ms/1000.0);
    result->tv_nsec=(long)((ms-(double)result->tv_sec*1000.0)*1000000.0);
    return 0;
}
#define clock_gettime simulator_clock_gettime
#endif
#include "android/app/src/main/cpp/tos_bridge.c"
_Static_assert(sizeof(lua_Integer)==8, "ThinkOS Lua integers must remain 64-bit");
_Static_assert(sizeof(lua_Number)==8, "ThinkOS Lua numbers must remain double");
#ifdef __EMSCRIPTEN__
#undef clock_gettime
#endif

uintptr_t tos_vm_create(void) {
    return (uintptr_t)Java_moe_kiwi_thinkos_plugin_NativeLua_create(tos_environment(),tos_new_host());
}
jobject tos_vm_start(uintptr_t handle,jobject source,jobject label,jobject entry,jobject args) {
    return Java_moe_kiwi_thinkos_plugin_NativeLua_start(tos_environment(),NULL,(jlong)handle,entry,args,source,label);
}
jobject tos_vm_resume(uintptr_t handle,int task,jobject args) {
    return Java_moe_kiwi_thinkos_plugin_NativeLua_resume(tos_environment(),NULL,(jlong)handle,task,args);
}
void tos_vm_bind(uintptr_t handle){Java_moe_kiwi_thinkos_plugin_NativeLua_bind(tos_environment(),NULL,(jlong)handle);}
void tos_vm_cancel(uintptr_t handle,int task){Java_moe_kiwi_thinkos_plugin_NativeLua_cancel(tos_environment(),NULL,(jlong)handle,task);}
int64_t tos_vm_memory(uintptr_t handle){return Java_moe_kiwi_thinkos_plugin_NativeLua_memory(tos_environment(),NULL,(jlong)handle);}
void tos_vm_destroy(uintptr_t handle){Java_moe_kiwi_thinkos_plugin_NativeLua_destroy(tos_environment(),NULL,(jlong)handle);}
