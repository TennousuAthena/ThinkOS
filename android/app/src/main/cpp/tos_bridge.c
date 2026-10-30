#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"
#include "json/yyjson.h"
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define PLUGIN_LIMIT (4u * 1024u * 1024u)
#define TOTAL_LIMIT (24u * 1024u * 1024u)
#define MAX_INSTRUCTIONS 5000000u
#define MAX_TASKS 64
#define CPU_NS 50000000ull
#define WALL_NS 200000000ull
/* CPU spent inside trusted host calls is credited back to the script, up to this much per slice.
   The 200ms wall budget is not extended, so a script cannot loop on host calls to starve others. */
#define HOST_CREDIT_NS 100000000ull
#define JSON_LIMIT (1024u * 1024u)

enum { DONE, WAITING, SCRIPT_ERROR, CPU_ERROR, MEMORY_ERROR, WALL_ERROR, YIELD_ERROR };
typedef struct {
    lua_State *co;
    int ref, id, waiting;
} Task;
typedef struct {
    lua_State *L;
    size_t used;
    uint64_t deadline, wall_deadline, host_credit;
    unsigned instructions, clock_ticks, pattern_steps;
    int exhausted, memory_limit, next_task, dispatch_ref, timer_ref;
    Task tasks[MAX_TASKS], *running;
    JNIEnv *env;
    jobject host;
    jmethodID call;
} Runtime;
static size_t total_used;
static char json_null_key, json_array_key;
static uint64_t nanos(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec;
}
static uint64_t cpu_nanos(void) {
    struct timespec t;
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t);
    return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec;
}
static void *limited_alloc(void *ud, void *p, size_t old, size_t next) {
    Runtime *r = ud;
    if (!p)
        old = 0;
    if (!next) {
        free(p);
        r->used -= old;
        total_used -= old;
        return NULL;
    }
    if (next > old && (next - old > PLUGIN_LIMIT - r->used || next - old > TOTAL_LIMIT - total_used)) {
        r->memory_limit = 1;
        return NULL;
    }
    void *result = realloc(p, next);
    if (result) {
        r->used = r->used - old + next;
        total_used = total_used - old + next;
    }
    return result;
}
static Runtime *owner(lua_State *L) { return *(Runtime **)lua_getextraspace(L); }
static int check_budget(lua_State *L, int read_clock) {
    Runtime *r = owner(L);
    if (!r->exhausted) {
        if (r->memory_limit)
            r->exhausted = MEMORY_ERROR;
        else if (r->instructions >= MAX_INSTRUCTIONS)
            r->exhausted = CPU_ERROR;
        else if (read_clock && cpu_nanos() >= r->deadline)
            r->exhausted = CPU_ERROR;
        else if (read_clock && nanos() >= r->wall_deadline)
            r->exhausted = WALL_ERROR;
    }
    if (r->exhausted)
        return luaL_error(L, r->exhausted == WALL_ERROR     ? "plugin wall budget exceeded"
                             : r->exhausted == MEMORY_ERROR ? "plugin memory budget exceeded"
                                                            : "plugin CPU budget exceeded");
    return 0;
}
static void budget(lua_State *L, lua_Debug *ar) {
    Runtime *r = owner(L);
    r->instructions += 1000;
    // Instruction limit is checked each hook; syscall clocks only every 10k instructions.
    check_budget(L, ++r->clock_ticks % 10 == 0);
}
void tos_pattern_step(lua_State *L) {
    Runtime *r = owner(L);
    if (++r->pattern_steps % 256 == 0) {
        r->instructions += 256;
        check_budget(L, 1);
    }
}
static void begin_slice(Runtime *r) {
    r->instructions = r->clock_ticks = r->pattern_steps = 0;
    r->exhausted = r->memory_limit = 0;
    r->deadline = cpu_nanos() + CPU_NS;
    r->wall_deadline = nanos() + WALL_NS;
    r->host_credit = 0;
}
static int finish_safe_call(lua_State *L, int status, lua_KContext extra) {
    check_budget(L, 1);
    if (status != LUA_OK && status != LUA_YIELD) {
        lua_pushboolean(L, 0);
        lua_pushvalue(L, -2);
        return 2;
    }
    return lua_gettop(L) - (int)extra;
}
static int safe_pcall(lua_State *L) {
    check_budget(L, 0);
    luaL_checkany(L, 1);
    lua_pushboolean(L, 1);
    lua_insert(L, 1);
    int status = lua_pcallk(L, lua_gettop(L) - 2, LUA_MULTRET, 0, 0, finish_safe_call);
    return finish_safe_call(L, status, 0);
}
static int safe_xpcall(lua_State *L) {
    check_budget(L, 0);
    int n = lua_gettop(L);
    luaL_checktype(L, 2, LUA_TFUNCTION);
    lua_pushboolean(L, 1);
    lua_pushvalue(L, 1);
    lua_rotate(L, 3, 2);
    int status = lua_pcallk(L, n - 2, LUA_MULTRET, 2, 2, finish_safe_call);
    return finish_safe_call(L, status, 2);
}
/* JNI's NewStringUTF is modified UTF-8. Lua and plugin files use real UTF-8. */
static char *java_utf8_n(JNIEnv *env, jstring value, size_t *bytes) {
    jsize len = (*env)->GetStringLength(env, value);
    const jchar *u = (*env)->GetStringChars(env, value, NULL);
    char *out = malloc((size_t)len * 3 + 1);
    size_t n = 0;
    if (!out) {
        (*env)->ReleaseStringChars(env, value, u);
        return NULL;
    }
    for (jsize i = 0; i < len; i++) {
        uint32_t c = u[i];
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < len && u[i + 1] >= 0xdc00 && u[i + 1] <= 0xdfff)
            c = 0x10000 + ((c - 0xd800) << 10) + (u[++i] - 0xdc00);
        else if (c >= 0xd800 && c <= 0xdfff)
            c = 0xfffd;
        if (c < 0x80)
            out[n++] = (char)c;
        else if (c < 0x800) {
            out[n++] = (char)(0xc0 | (c >> 6));
            out[n++] = (char)(0x80 | (c & 63));
        } else if (c < 0x10000) {
            out[n++] = (char)(0xe0 | (c >> 12));
            out[n++] = (char)(0x80 | ((c >> 6) & 63));
            out[n++] = (char)(0x80 | (c & 63));
        } else {
            out[n++] = (char)(0xf0 | (c >> 18));
            out[n++] = (char)(0x80 | ((c >> 12) & 63));
            out[n++] = (char)(0x80 | ((c >> 6) & 63));
            out[n++] = (char)(0x80 | (c & 63));
        }
    }
    if (bytes)
        *bytes = n;
    out[n] = 0;
    (*env)->ReleaseStringChars(env, value, u);
    return out;
}
static jstring utf8_java_n(JNIEnv *env, const char *s, size_t len) {
    size_t n = 0;
    jchar *out = malloc((len + 1) * sizeof(jchar));
    if (!out)
        return (*env)->NewStringUTF(env, "native allocation failed");
    for (size_t i = 0; i < len;) {
        uint32_t c = (unsigned char)s[i++];
        int extra = 0;
        uint32_t minimum = 0;
        if (c >= 0xf0 && c <= 0xf4) {
            c &= 7;
            extra = 3;
            minimum = 0x10000;
        } else if (c >= 0xe0 && c <= 0xef) {
            c &= 15;
            extra = 2;
            minimum = 0x800;
        } else if (c >= 0xc2 && c <= 0xdf) {
            c &= 31;
            extra = 1;
            minimum = 0x80;
        } else if (c >= 0x80)
            c = 0xfffd;
        int valid = 1;
        for (int j = 0; j < extra; j++) {
            if (i >= len || ((unsigned char)s[i] & 0xc0) != 0x80) {
                valid = 0;
                break;
            }
            c = (c << 6) | ((unsigned char)s[i++] & 63);
        }
        if (!valid || c < minimum || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff))
            c = 0xfffd;
        if (c >= 0x10000) {
            c -= 0x10000;
            out[n++] = (jchar)(0xd800 + (c >> 10));
            out[n++] = (jchar)(0xdc00 + (c & 1023));
        } else
            out[n++] = (jchar)c;
    }
    jstring result = (*env)->NewString(env, out, (jsize)n);
    free(out);
    return result;
}

static char *java_utf8(JNIEnv *env, jstring s) { return java_utf8_n(env, s, NULL); }
static jstring utf8_java(JNIEnv *env, const char *s) { return utf8_java_n(env, s, strlen(s)); }
/* yyjson allocations participate in the same per-plugin/global limits as Lua. */
typedef union {
    size_t size;
    long double alignment;
    void *pointer;
} JsonHeader;
static void *json_malloc(void *ctx, size_t size) {
    if (size > SIZE_MAX - sizeof(JsonHeader))
        return NULL;
    JsonHeader *h = limited_alloc(ctx, NULL, 0, size + sizeof(*h));
    if (!h)
        return NULL;
    h->size = size + sizeof(*h);
    return h + 1;
}
static void json_free(void *ctx, void *p) {
    if (p) {
        JsonHeader *h = (JsonHeader *)p - 1;
        limited_alloc(ctx, h, h->size, 0);
    }
}
static void *json_realloc(void *ctx, void *p, size_t old_size, size_t size) {
    if (!p)
        return json_malloc(ctx, size);
    if (size > SIZE_MAX - sizeof(JsonHeader))
        return NULL;
    JsonHeader *h = (JsonHeader *)p - 1;
    h = limited_alloc(ctx, h, h->size, size + sizeof(*h));
    if (!h)
        return NULL;
    h->size = size + sizeof(*h);
    return h + 1;
}
static yyjson_alc json_allocator(Runtime *r) {
    yyjson_alc a = {json_malloc, json_realloc, json_free, r};
    return a;
}
static void mark_array(lua_State *L) {
    lua_rawgetp(L, LUA_REGISTRYINDEX, &json_array_key);
    lua_setmetatable(L, -2);
}
static int is_array(lua_State *L, int index) {
    int equal = 0;
    if (lua_getmetatable(L, index)) {
        lua_rawgetp(L, LUA_REGISTRYINDEX, &json_array_key);
        equal = lua_rawequal(L, -1, -2);
        lua_pop(L, 2);
    }
    return equal;
}
static void push_json(lua_State *L, yyjson_val *v, int depth) {
    tos_pattern_step(L);
    if (depth > 24)
        luaL_error(L, "JSON nesting exceeds 24");
    if (yyjson_is_null(v))
        lua_pushlightuserdata(L, &json_null_key);
    else if (yyjson_is_bool(v))
        lua_pushboolean(L, yyjson_get_bool(v));
    else if (yyjson_is_sint(v))
        lua_pushinteger(L, yyjson_get_sint(v));
    else if (yyjson_is_uint(v)) {
        uint64_t n = yyjson_get_uint(v);
        if (n > INT64_MAX)
            luaL_error(L, "JSON integer exceeds signed 64-bit range");
        lua_pushinteger(L, (lua_Integer)n);
    } else if (yyjson_is_real(v))
        lua_pushnumber(L, yyjson_get_real(v));
    else if (yyjson_is_str(v))
        lua_pushlstring(L, yyjson_get_str(v), yyjson_get_len(v));
    else if (yyjson_is_arr(v)) {
        lua_createtable(L, (int)yyjson_get_len(v), 0);
        mark_array(L);
        size_t i, n;
        yyjson_val *item;
        yyjson_arr_foreach(v, i, n, item) {
            push_json(L, item, depth + 1);
            lua_rawseti(L, -2, (lua_Integer)i + 1);
        }
    } else {
        lua_createtable(L, 0, (int)yyjson_get_len(v));
        size_t i, n;
        yyjson_val *key, *value;
        yyjson_obj_foreach(v, i, n, key, value) {
            lua_pushlstring(L, yyjson_get_str(key), yyjson_get_len(key));
            push_json(L, value, depth + 1);
            lua_rawset(L, -3);
        }
    }
}
typedef struct {
    yyjson_doc *read;
    yyjson_mut_doc *write;
    char *output;
} JsonWork;
static int decode_inner(lua_State *L) {
    JsonWork *w = lua_touserdata(L, 1);
    push_json(L, yyjson_doc_get_root(w->read), 0);
    return 1;
}
static int json_decode(lua_State *L) {
    size_t len;
    const char *s = luaL_checklstring(L, 1, &len);
    luaL_argcheck(L, len <= JSON_LIMIT, 1, "JSON input exceeds 1MB");
    yyjson_alc a = json_allocator(owner(L));
    yyjson_read_err err;
    JsonWork w = {0};
    w.read = yyjson_read_opts((char *)s, len, 0, &a, &err);
    if (!w.read)
        return luaL_error(L, "invalid JSON or memory limit: %s", err.msg ? err.msg : "allocation");
    lua_pushcfunction(L, decode_inner);
    lua_pushlightuserdata(L, &w);
    int status = lua_pcall(L, 1, 1, 0);
    yyjson_doc_free(w.read);
    if (status != LUA_OK)
        return lua_error(L);
    check_budget(L, 1);
    return 1;
}
static yyjson_mut_val *make_json(lua_State *L, yyjson_mut_doc *doc, int index, int depth, const void **seen) {
    tos_pattern_step(L);
    if (depth > 24)
        luaL_error(L, "JSON nesting exceeds 24");
    index = lua_absindex(L, index);
    yyjson_mut_val *result = NULL;
    switch (lua_type(L, index)) {
    case LUA_TNIL:
        result = yyjson_mut_null(doc);
        break;
    case LUA_TLIGHTUSERDATA:
        if (lua_touserdata(L, index) != &json_null_key)
            luaL_error(L, "invalid JSON value");
        result = yyjson_mut_null(doc);
        break;
    case LUA_TBOOLEAN:
        result = yyjson_mut_bool(doc, lua_toboolean(L, index));
        break;
    case LUA_TNUMBER:
        if (lua_isinteger(L, index))
            result = yyjson_mut_sint(doc, lua_tointeger(L, index));
        else {
            double d = lua_tonumber(L, index);
            if (!isfinite(d))
                luaL_error(L, "non-finite JSON number");
            result = yyjson_mut_real(doc, d);
        }
        break;
    case LUA_TSTRING: {
        size_t len;
        const char *s = lua_tolstring(L, index, &len);
        if (len > JSON_LIMIT)
            luaL_error(L, "JSON string exceeds 1MB");
        result = yyjson_mut_strncpy(doc, s, len);
        break;
    }
    case LUA_TTABLE: {
        const void *identity = lua_topointer(L, index);
        for (int i = 0; i < depth; i++)
            if (seen[i] == identity)
                luaL_error(L, "cyclic JSON table");
        seen[depth] = identity;
        size_t count = 0, length = lua_rawlen(L, index);
        int array = is_array(L, index) || length > 0;
        lua_pushnil(L);
        while (lua_next(L, index)) {
            count++;
            if (!lua_isinteger(L, -2) || lua_tointeger(L, -2) < 1 || (uint64_t)lua_tointeger(L, -2) > length)
                array = 0;
            lua_pop(L, 1);
        }
        array = array && (count == length || count == 0);
        result = array ? yyjson_mut_arr(doc) : yyjson_mut_obj(doc);
        if (!result)
            luaL_error(L, "JSON memory limit");
        if (array) {
            for (size_t i = 1; i <= length; i++) {
                lua_rawgeti(L, index, (lua_Integer)i);
                yyjson_mut_val *value = make_json(L, doc, -1, depth + 1, seen);
                lua_pop(L, 1);
                if (!yyjson_mut_arr_append(result, value))
                    luaL_error(L, "JSON memory limit");
            }
        } else {
            lua_pushnil(L);
            while (lua_next(L, index)) {
                if (lua_type(L, -2) != LUA_TSTRING)
                    luaL_error(L, "JSON object keys must be strings");
                size_t n;
                const char *s = lua_tolstring(L, -2, &n);
                yyjson_mut_val *key = yyjson_mut_strncpy(doc, s, n);
                yyjson_mut_val *value = make_json(L, doc, -1, depth + 1, seen);
                if (!key || !yyjson_mut_obj_add(result, key, value))
                    luaL_error(L, "JSON memory limit");
                lua_pop(L, 1);
            }
        }
        break;
    }
    default:
        luaL_error(L, "not a JSON value");
    }
    if (!result)
        luaL_error(L, "JSON memory limit");
    return result;
}
static int encode_inner(lua_State *L) {
    JsonWork *w = lua_touserdata(L, 1);
    const void *seen[25] = {0};
    yyjson_mut_doc_set_root(w->write, make_json(L, w->write, 2, 0, seen));
    yyjson_alc a = json_allocator(owner(L));
    size_t len;
    yyjson_write_err err;
    w->output = yyjson_mut_write_opts(w->write, 0, &a, &len, &err);
    if (!w->output)
        return luaL_error(L, "JSON encoding failed: %s", err.msg ? err.msg : "memory limit");
    if (len > JSON_LIMIT)
        return luaL_error(L, "JSON output exceeds 1MB");
    lua_pushlstring(L, w->output, len);
    return 1;
}
static int json_encode(lua_State *L) {
    yyjson_alc a = json_allocator(owner(L));
    JsonWork w = {0};
    w.write = yyjson_mut_doc_new(&a);
    if (!w.write)
        return luaL_error(L, "JSON memory limit");
    lua_pushcfunction(L, encode_inner);
    lua_pushlightuserdata(L, &w);
    lua_pushvalue(L, 1);
    int status = lua_pcall(L, 2, 1, 0);
    if (w.output)
        json_free(owner(L), w.output);
    yyjson_mut_doc_free(w.write);
    if (status != LUA_OK)
        return lua_error(L);
    check_budget(L, 1);
    return 1;
}

/* Typed JNI values. No source compilation or JSON serialization at the boundary. */
static struct {
    jclass object, map, list, map_type, list_type, string, number, boolean, long_cls, double_cls, step;
    jmethodID map_new, map_put, map_entries, iterator, has_next, next, entry_key, entry_value;
    jmethodID list_new, list_add, list_get, list_size, long_new, double_new, bool_new, number_long,
        number_double, bool_value, step_new;
} j;
static jclass global_class(JNIEnv *e, const char *name) {
    jclass local = (*e)->FindClass(e, name);
    jclass global = (*e)->NewGlobalRef(e, local);
    (*e)->DeleteLocalRef(e, local);
    return global;
}
static void init_java(JNIEnv *e) {
    if (j.object)
        return;
    j.object = global_class(e, "java/lang/Object");
    j.map = global_class(e, "java/util/Map");
    j.list = global_class(e, "java/util/List");
    j.string = global_class(e, "java/lang/String");
    j.number = global_class(e, "java/lang/Number");
    j.boolean = global_class(e, "java/lang/Boolean");
    j.long_cls = global_class(e, "java/lang/Long");
    j.double_cls = global_class(e, "java/lang/Double");
    j.step = global_class(e, "moe/kiwi/thinkos/plugin/NativeLua$Step");
    jclass map_impl = global_class(e, "java/util/LinkedHashMap"),
           list_impl = global_class(e, "java/util/ArrayList");
    j.map_new = (*e)->GetMethodID(e, map_impl, "<init>", "()V");
    j.list_new = (*e)->GetMethodID(e, list_impl, "<init>", "()V");
    // Retain concrete classes for construction; interface methods are valid on each instance.
    jclass map_interface = j.map, list_interface = j.list;
    j.map_type = j.map;
    j.list_type = j.list;
    j.map = map_impl;
    j.list = list_impl;
    j.map_put = (*e)->GetMethodID(e, map_interface, "put",
                                  "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    j.map_entries = (*e)->GetMethodID(e, map_interface, "entrySet", "()Ljava/util/Set;");
    jclass iterable = (*e)->FindClass(e, "java/lang/Iterable"),
           iter = (*e)->FindClass(e, "java/util/Iterator"), entry = (*e)->FindClass(e, "java/util/Map$Entry");
    j.iterator = (*e)->GetMethodID(e, iterable, "iterator", "()Ljava/util/Iterator;");
    j.has_next = (*e)->GetMethodID(e, iter, "hasNext", "()Z");
    j.next = (*e)->GetMethodID(e, iter, "next", "()Ljava/lang/Object;");
    j.entry_key = (*e)->GetMethodID(e, entry, "getKey", "()Ljava/lang/Object;");
    j.entry_value = (*e)->GetMethodID(e, entry, "getValue", "()Ljava/lang/Object;");
    j.list_add = (*e)->GetMethodID(e, list_interface, "add", "(Ljava/lang/Object;)Z");
    j.list_get = (*e)->GetMethodID(e, list_interface, "get", "(I)Ljava/lang/Object;");
    j.list_size = (*e)->GetMethodID(e, list_interface, "size", "()I");
    j.long_new = (*e)->GetMethodID(e, j.long_cls, "<init>", "(J)V");
    j.double_new = (*e)->GetMethodID(e, j.double_cls, "<init>", "(D)V");
    j.bool_new = (*e)->GetMethodID(e, j.boolean, "<init>", "(Z)V");
    j.number_long = (*e)->GetMethodID(e, j.number, "longValue", "()J");
    j.number_double = (*e)->GetMethodID(e, j.number, "doubleValue", "()D");
    j.bool_value = (*e)->GetMethodID(e, j.boolean, "booleanValue", "()Z");
    j.step_new = (*e)->GetMethodID(e, j.step, "<init>", "(IILjava/lang/String;JJ)V");
    (*e)->DeleteLocalRef(e, iterable);
    (*e)->DeleteLocalRef(e, iter);
    (*e)->DeleteLocalRef(e, entry);
    // Interface globals deliberately retained for instanceof checks.
}
static jobject lua_java(lua_State *L, int index, int depth, const void **seen) {
    tos_pattern_step(L);
    if (depth > 24)
        luaL_error(L, "host value nesting exceeds 24");
    Runtime *r = owner(L);
    JNIEnv *e = r->env;
    index = lua_absindex(L, index);
    switch (lua_type(L, index)) {
    case LUA_TNIL:
        return NULL;
    case LUA_TLIGHTUSERDATA:
        if (lua_touserdata(L, index) == &json_null_key)
            return NULL;
        break;
    case LUA_TBOOLEAN:
        return (*e)->NewObject(e, j.boolean, j.bool_new, (jboolean)lua_toboolean(L, index));
    case LUA_TNUMBER:
        return lua_isinteger(L, index)
                   ? (*e)->NewObject(e, j.long_cls, j.long_new, (jlong)lua_tointeger(L, index))
                   : (*e)->NewObject(e, j.double_cls, j.double_new, (jdouble)lua_tonumber(L, index));
    case LUA_TSTRING: {
        size_t len;
        const char *s = lua_tolstring(L, index, &len);
        return utf8_java_n(e, s, len);
    }
    case LUA_TTABLE: {
        const void *identity = lua_topointer(L, index);
        for (int i = 0; i < depth; i++)
            if (seen[i] == identity)
                luaL_error(L, "cyclic host value");
        seen[depth] = identity;
        size_t count = 0, length = lua_rawlen(L, index);
        int array = is_array(L, index) || length > 0;
        lua_pushnil(L);
        while (lua_next(L, index)) {
            count++;
            if (!lua_isinteger(L, -2) || lua_tointeger(L, -2) < 1 || (uint64_t)lua_tointeger(L, -2) > length)
                array = 0;
            lua_pop(L, 1);
        }
        array = array && (count == length || count == 0);
        jobject out = (*e)->NewObject(e, array ? j.list : j.map, array ? j.list_new : j.map_new);
        if (array) {
            for (size_t i = 1; i <= length; i++) {
                lua_rawgeti(L, index, i);
                jobject value = lua_java(L, -1, depth + 1, seen);
                lua_pop(L, 1);
                (*e)->CallBooleanMethod(e, out, j.list_add, value);
                if (value)
                    (*e)->DeleteLocalRef(e, value);
            }
        } else {
            lua_pushnil(L);
            while (lua_next(L, index)) {
                if (lua_type(L, -2) != LUA_TSTRING)
                    luaL_error(L, "host object keys must be strings");
                jobject key = lua_java(L, -2, depth + 1, seen), value = lua_java(L, -1, depth + 1, seen);
                jobject old = (*e)->CallObjectMethod(e, out, j.map_put, key, value);
                (*e)->DeleteLocalRef(e, key);
                if (value)
                    (*e)->DeleteLocalRef(e, value);
                if (old)
                    (*e)->DeleteLocalRef(e, old);
                lua_pop(L, 1);
            }
        }
        return out;
    }
    }
    luaL_error(L, "unsupported host value");
    return NULL;
}
static void push_java_string(lua_State *L, jstring text) {
    JNIEnv *e = owner(L)->env;
    jsize length = (*e)->GetStringLength(e, text), pos = 0;
    luaL_Buffer buffer;
    luaL_buffinit(L, &buffer);
    while (pos < length) {
        jchar chars[512];
        jsize n = length - pos;
        if (n > 512)
            n = 512;
        (*e)->GetStringRegion(e, text, pos, n, chars);
        if (n == 512 && chars[n - 1] >= 0xd800 && chars[n - 1] <= 0xdbff)
            n--;
        for (jsize i = 0; i < n; i++) {
            uint32_t c = chars[i];
            char out[4];
            size_t count;
            if (c >= 0xd800 && c <= 0xdbff && i + 1 < n && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff)
                c = 0x10000 + ((c - 0xd800) << 10) + (chars[++i] - 0xdc00);
            else if (c >= 0xd800 && c <= 0xdfff)
                c = 0xfffd;
            if (c < 0x80) {
                out[0] = c;
                count = 1;
            } else if (c < 0x800) {
                out[0] = 0xc0 | (c >> 6);
                out[1] = 0x80 | (c & 63);
                count = 2;
            } else if (c < 0x10000) {
                out[0] = 0xe0 | (c >> 12);
                out[1] = 0x80 | ((c >> 6) & 63);
                out[2] = 0x80 | (c & 63);
                count = 3;
            } else {
                out[0] = 0xf0 | (c >> 18);
                out[1] = 0x80 | ((c >> 12) & 63);
                out[2] = 0x80 | ((c >> 6) & 63);
                out[3] = 0x80 | (c & 63);
                count = 4;
            }
            luaL_addlstring(&buffer, out, count);
        }
        pos += n;
    }
    luaL_pushresult(&buffer);
}
static void java_lua(lua_State *L, jobject value, int depth) {
    tos_pattern_step(L);
    Runtime *r = owner(L);
    JNIEnv *e = r->env;
    if (depth > 24)
        luaL_error(L, "host value nesting exceeds 24");
    if (!value) {
        if (depth)
            lua_pushlightuserdata(L, &json_null_key);
        else
            lua_pushnil(L);
        return;
    }
    if ((*e)->IsInstanceOf(e, value, j.string))
        push_java_string(L, value);
    else if ((*e)->IsInstanceOf(e, value, j.boolean))
        lua_pushboolean(L, (*e)->CallBooleanMethod(e, value, j.bool_value));
    else if ((*e)->IsInstanceOf(e, value, j.number)) {
        if ((*e)->IsInstanceOf(e, value, j.double_cls))
            lua_pushnumber(L, (*e)->CallDoubleMethod(e, value, j.number_double));
        else
            lua_pushinteger(L, (*e)->CallLongMethod(e, value, j.number_long));
    } else if ((*e)->IsInstanceOf(e, value, j.list_type)) {
        int n = (*e)->CallIntMethod(e, value, j.list_size);
        lua_createtable(L, n, 0);
        mark_array(L);
        for (int i = 0; i < n; i++) {
            jobject item = (*e)->CallObjectMethod(e, value, j.list_get, i);
            java_lua(L, item, depth + 1);
            if (item)
                (*e)->DeleteLocalRef(e, item);
            lua_rawseti(L, -2, i + 1);
        }
    } else if ((*e)->IsInstanceOf(e, value, j.map_type)) {
        lua_newtable(L);
        jobject entries = (*e)->CallObjectMethod(e, value, j.map_entries),
                iterator = (*e)->CallObjectMethod(e, entries, j.iterator);
        while ((*e)->CallBooleanMethod(e, iterator, j.has_next)) {
            jobject entry = (*e)->CallObjectMethod(e, iterator, j.next),
                    key = (*e)->CallObjectMethod(e, entry, j.entry_key),
                    item = (*e)->CallObjectMethod(e, entry, j.entry_value);
            java_lua(L, key, depth + 1);
            java_lua(L, item, depth + 1);
            lua_rawset(L, -3);
            if (item)
                (*e)->DeleteLocalRef(e, item);
            (*e)->DeleteLocalRef(e, key);
            (*e)->DeleteLocalRef(e, entry);
        }
        (*e)->DeleteLocalRef(e, iterator);
        (*e)->DeleteLocalRef(e, entries);
    } else
        luaL_error(L, "unsupported Java value");
}
static int invoke_host_inner(lua_State *L) {
    Runtime *r = owner(L);
    JNIEnv *e = r->env;
    check_budget(L, 1);
    const char *method = luaL_checkstring(L, 1);
    int count = lua_gettop(L) - 1;
    jobjectArray args = (*e)->NewObjectArray(e, count, j.object, NULL);
    const void *seen[25] = {0};
    for (int i = 0; i < count; i++) {
        jobject v = lua_java(L, i + 2, 0, seen);
        (*e)->SetObjectArrayElement(e, args, i, v);
        if (v)
            (*e)->DeleteLocalRef(e, v);
    }
    jstring name = (*e)->NewStringUTF(e, method);
    uint64_t before = cpu_nanos();
    jobject result = (*e)->CallObjectMethod(e, r->host, r->call, name, args, r->running ? r->running->id : 0);
    {
        uint64_t spent = cpu_nanos() - before;
        uint64_t room = HOST_CREDIT_NS - r->host_credit;
        uint64_t credit = spent < room ? spent : room;
        r->host_credit += credit;
        r->deadline += credit;
    }
    if ((*e)->ExceptionCheck(e)) {
        (*e)->ExceptionClear(e);
        return luaL_error(L, "host call rejected: %s", method);
    }
    java_lua(L, result, 0);
    check_budget(L, 1);
    return 1;
}
static int invoke_host(lua_State *L) {
    JNIEnv *e = owner(L)->env;
    int n = lua_gettop(L);
    if ((*e)->PushLocalFrame(e, 64) < 0)
        return luaL_error(L, "JNI allocation failed");
    lua_pushcfunction(L, invoke_host_inner);
    lua_insert(L, 1);
    int status = lua_pcall(L, n, 1, 0);
    (*e)->PopLocalFrame(e, NULL);
    if (status != LUA_OK)
        return lua_error(L);
    return 1;
}
static int await_cont(lua_State *L, int status, lua_KContext ctx) { return lua_gettop(L); }
static int await_host(lua_State *L) {
    Runtime *r = owner(L);
    if (!r->running || r->running->co != L || !lua_isyieldable(L))
        return luaL_error(L, "async API requires a yieldable event");
    invoke_host(L);
    lua_settop(L, 0);
    r->running->waiting = 1;
    return lua_yieldk(L, 0, 0, await_cont);
}
static int text_load(lua_State *L) {
    size_t len;
    const char *s = luaL_checklstring(L, 1, &len);
    const char *name = luaL_optstring(L, 2, "plugin chunk");
    int status = luaL_loadbufferx(L, s, len, name, "t");
    if (status != LUA_OK) {
        lua_pushnil(L);
        lua_insert(L, -2);
        return 2;
    }
    if (!lua_isnone(L, 4)) {
        lua_pushvalue(L, 4);
        lua_setupvalue(L, -2, 1);
    }
    return 1;
}
static int initialize(lua_State *L) {
    luaL_requiref(L, "_G", luaopen_base, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_STRLIBNAME, luaopen_string, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_TABLIBNAME, luaopen_table, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_MATHLIBNAME, luaopen_math, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_UTF8LIBNAME, luaopen_utf8, 1);
    lua_pop(L, 1);
    luaL_requiref(L, LUA_COLIBNAME, luaopen_coroutine, 1);
    lua_pop(L, 1);
    const char *removed[] = {"dofile", "loadfile", "collectgarbage", "print", NULL};
    for (int i = 0; removed[i]; i++) {
        lua_pushnil(L);
        lua_setglobal(L, removed[i]);
    }
    lua_pushcfunction(L, text_load);
    lua_setglobal(L, "load");
    lua_pushcfunction(L, safe_pcall);
    lua_setglobal(L, "pcall");
    lua_pushcfunction(L, safe_xpcall);
    lua_setglobal(L, "xpcall");
    lua_pushcfunction(L, invoke_host);
    lua_setglobal(L, "_host");
    lua_pushcfunction(L, await_host);
    lua_setglobal(L, "_await");
    lua_newtable(L);
    lua_pushliteral(L, "sealed JSON array");
    lua_setfield(L, -2, "__metatable");
    lua_rawsetp(L, LUA_REGISTRYINDEX, &json_array_key);
    lua_newtable(L);
    lua_pushcfunction(L, json_decode);
    lua_setfield(L, -2, "decode");
    lua_pushcfunction(L, json_encode);
    lua_setfield(L, -2, "encode");
    lua_pushlightuserdata(L, &json_null_key);
    lua_setfield(L, -2, "null");
    lua_setglobal(L, "_json");
    return 0;
}
JNIEXPORT jlong JNICALL Java_moe_kiwi_thinkos_plugin_NativeLua_create(JNIEnv *e, jobject self) {
    init_java(e);
    Runtime *r = calloc(1, sizeof(*r));
    if (!r)
        return 0;
    r->env = e;
    r->host = (*e)->NewGlobalRef(e, self);
    jclass cls = (*e)->GetObjectClass(e, self);
    r->call = (*e)->GetMethodID(e, cls, "call", "(Ljava/lang/String;[Ljava/lang/Object;I)Ljava/lang/Object;");
    (*e)->DeleteLocalRef(e, cls);
    r->L = lua_newstate(limited_alloc, r);
    if (!r->L) {
        (*e)->DeleteGlobalRef(e, r->host);
        free(r);
        return 0;
    }
    *(Runtime **)lua_getextraspace(r->L) = r;
    begin_slice(r);
    lua_pushcfunction(r->L, initialize);
    if (lua_pcall(r->L, 0, 0, 0) != LUA_OK) {
        lua_close(r->L);
        (*e)->DeleteGlobalRef(e, r->host);
        free(r);
        return 0;
    }
    // Automatic generational collection; never force a full collection per event.
    lua_gc(r->L, LUA_GCGEN, 20, 100);
    lua_sethook(r->L, budget, LUA_MASKCOUNT, 1000);
    return (jlong)(intptr_t)r;
}
typedef struct {
    const char *code, *name;
    size_t code_size;
    int ref;
    jobjectArray args;
    const char *entry;
    Task *task;
} Prepare;
static int prepare(lua_State *L) {
    Prepare *p = lua_touserdata(L, 1);
    lua_State *co = lua_newthread(L);
    p->task->co = co;
    p->task->ref = luaL_ref(L, LUA_REGISTRYINDEX);
    if (p->code) {
        if (luaL_loadbufferx(co, p->code, p->code_size, p->name, "t") != LUA_OK) {
            lua_xmove(co, L, 1);
            return lua_error(L);
        }
    } else {
        int ref = strcmp(p->entry, "dispatch") == 0 ? owner(L)->dispatch_ref : owner(L)->timer_ref;
        lua_rawgeti(co, LUA_REGISTRYINDEX, ref);
        if (!lua_isfunction(co, -1))
            return luaL_error(L, "unbound runtime entry");
    }
    return 0;
}
static int push_args(lua_State *L) {
    Prepare *p = lua_touserdata(L, 1);
    Runtime *r = owner(L);
    JNIEnv *e = r->env;
    int n = p->args ? (*e)->GetArrayLength(e, p->args) : 0;
    lua_settop(L, 0);
    for (int i = 0; i < n; i++) {
        jobject arg = (*e)->GetObjectArrayElement(e, p->args, i);
        java_lua(L, arg, 0);
        if (arg)
            (*e)->DeleteLocalRef(e, arg);
    }
    return n;
}
static const char *error_text(lua_State *L) {
    const char *s = lua_tostring(L, -1);
    return s ? s : "Lua error";
}
static jobject step_result(Runtime *r, Task *t, int status, const char *message, uint64_t cpu,
                           uint64_t wall) {
    JNIEnv *e = r->env;
    jstring msg = message ? utf8_java(e, message) : NULL;
    jobject result =
        (*e)->NewObject(e, j.step, j.step_new, t ? t->id : 0, status, msg, (jlong)cpu, (jlong)wall);
    if (msg)
        (*e)->DeleteLocalRef(e, msg);
    return result;
}
static void release_task(Runtime *r, Task *t) {
    lua_settop(t->co, 0);
    luaL_unref(r->L, LUA_REGISTRYINDEX, t->ref);
    memset(t, 0, sizeof(*t));
}
static jobject resume_task(Runtime *r, Task *t, Prepare *p, int fresh) {
    uint64_t cpu = cpu_nanos(), wall = nanos();
    begin_slice(r);
    r->running = t;
    t->waiting = 0;
    lua_State *co = t->co;
    int argc = 0, status = LUA_OK, results = 0;
    if (p->args) {
        // Argument conversion is protected on the root state, then moved to coroutine.
        lua_settop(r->L, 0);
        lua_pushcfunction(r->L, push_args);
        lua_pushlightuserdata(r->L, p);
        status = lua_pcall(r->L, 1, LUA_MULTRET, 0);
        if (status == LUA_OK) {
            argc = lua_gettop(r->L);
            lua_xmove(r->L, co, argc);
        }
    }
    if (status == LUA_OK)
        status = lua_resume(co, NULL, argc, &results);
    if (r->memory_limit)
        r->exhausted = MEMORY_ERROR;
    if (!r->exhausted && cpu_nanos() >= r->deadline)
        r->exhausted = CPU_ERROR;
    if (!r->exhausted && nanos() >= r->wall_deadline)
        r->exhausted = WALL_ERROR;
    int kind = status == LUA_OK       ? DONE
               : status == LUA_YIELD  ? (t->waiting ? WAITING : YIELD_ERROR)
               : status == LUA_ERRMEM ? MEMORY_ERROR
                                      : SCRIPT_ERROR;
    if (r->exhausted)
        kind = r->exhausted;
    const char *message = NULL;
    if (kind == CPU_ERROR)
        message = "plugin CPU budget exceeded";
    else if (kind == WALL_ERROR)
        message = "plugin wall budget exceeded (200ms)";
    else if (kind == MEMORY_ERROR)
        message = "plugin memory budget exceeded";
    else if (kind == YIELD_ERROR)
        message = "unexpected coroutine yield";
    else if (kind == SCRIPT_ERROR)
        message = error_text(status == LUA_OK ? co : (lua_gettop(r->L) ? r->L : co));
    jobject result = step_result(r, t, kind, message, (cpu_nanos() - cpu) / 1000, (nanos() - wall) / 1000);
    r->running = NULL;
    if (kind != WAITING)
        release_task(r, t);
    lua_settop(r->L, 0);
    return result;
}
JNIEXPORT jobject JNICALL Java_moe_kiwi_thinkos_plugin_NativeLua_start(JNIEnv *e, jobject self, jlong handle,
                                                                       jstring entry, jobjectArray args,
                                                                       jstring source, jstring label) {
    Runtime *r = (Runtime *)(intptr_t)handle;
    r->env = e;
    Task *t = NULL;
    for (int i = 0; i < MAX_TASKS; i++)
        if (!r->tasks[i].id) {
            t = &r->tasks[i];
            break;
        }
    if (!t)
        return step_result(r, NULL, MEMORY_ERROR, "maximum 64 suspended events", 0, 0);
    t->id = ++r->next_task;
    size_t code_size = 0;
    char *name = label ? java_utf8(e, label) : NULL,
         *code = source ? java_utf8_n(e, source, &code_size) : NULL,
         *method = entry ? java_utf8(e, entry) : NULL;
    if ((source && !code) || (label && !name) || (entry && !method)) {
        free(code);
        free(name);
        free(method);
        memset(t, 0, sizeof(*t));
        return step_result(r, NULL, MEMORY_ERROR, "native string allocation failed", 0, 0);
    }
    Prepare p = {.code = code,
                 .code_size = code_size,
                 .name = name ? name : "plugin",
                 .args = args,
                 .entry = method,
                 .task = t};
    begin_slice(r);
    lua_settop(r->L, 0);
    lua_pushcfunction(r->L, prepare);
    lua_pushlightuserdata(r->L, &p);
    int status = lua_pcall(r->L, 1, 0, 0);
    free(name);
    free(code);
    free(method);
    if (status != LUA_OK) {
        jobject out =
            step_result(r, t, status == LUA_ERRMEM ? MEMORY_ERROR : SCRIPT_ERROR, error_text(r->L), 0, 0);
        if (t->co)
            release_task(r, t);
        else
            memset(t, 0, sizeof(*t));
        lua_settop(r->L, 0);
        return out;
    }
    return resume_task(r, t, &p, 1);
}
JNIEXPORT jobject JNICALL Java_moe_kiwi_thinkos_plugin_NativeLua_resume(JNIEnv *e, jobject self, jlong handle,
                                                                        jint id, jobjectArray args) {
    Runtime *r = (Runtime *)(intptr_t)handle;
    r->env = e;
    for (int i = 0; i < MAX_TASKS; i++)
        if (r->tasks[i].id == id) {
            Prepare p = {.args = args};
            return resume_task(r, &r->tasks[i], &p, 0);
        }
    return step_result(r, NULL, SCRIPT_ERROR, "cancelled or unknown event", 0, 0);
}
JNIEXPORT void JNICALL Java_moe_kiwi_thinkos_plugin_NativeLua_bind(JNIEnv *e, jobject self, jlong handle) {
    Runtime *r = (Runtime *)(intptr_t)handle;
    lua_getglobal(r->L, "_dispatch");
    r->dispatch_ref = luaL_ref(r->L, LUA_REGISTRYINDEX);
    lua_getglobal(r->L, "_timer");
    r->timer_ref = luaL_ref(r->L, LUA_REGISTRYINDEX);
    lua_pushnil(r->L);
    lua_setglobal(r->L, "_dispatch");
    lua_pushnil(r->L);
    lua_setglobal(r->L, "_timer");
}
JNIEXPORT void JNICALL Java_moe_kiwi_thinkos_plugin_NativeLua_cancel(JNIEnv *e, jobject self, jlong handle,
                                                                     jint id) {
    Runtime *r = (Runtime *)(intptr_t)handle;
    for (int i = 0; i < MAX_TASKS; i++)
        if (r->tasks[i].id == id) {
            release_task(r, &r->tasks[i]);
            break;
        }
}
JNIEXPORT jlong JNICALL Java_moe_kiwi_thinkos_plugin_NativeLua_memory(JNIEnv *e, jobject self, jlong handle) {
    return (jlong)((Runtime *)(intptr_t)handle)->used;
}
JNIEXPORT void JNICALL Java_moe_kiwi_thinkos_plugin_NativeLua_destroy(JNIEnv *e, jobject self, jlong handle) {
    Runtime *r = (Runtime *)(intptr_t)handle;
    lua_sethook(r->L, NULL, 0, 0);
    lua_close(r->L);
    (*e)->DeleteGlobalRef(e, r->host);
    free(r);
}
