/* MIT. Host-only fixed executor for test_updater.py; never packaged for Android.
 * Derive the isolated fixture from argv[0], not from the whitelisted child env.
 * yyjson is the repository's existing MIT implementation, used only for fixture
 * JSON/logs here. No command, shell, Android tool or interpreter is launched. */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <limits.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "yyjson.h"

extern char **environ;
static char home[PATH_MAX], root[PATH_MAX];
static const char *action, *program;
static int original_count;
static char **original;

static void path(char *out, const char *base, const char *name) {
    if (snprintf(out, PATH_MAX, "%s/%s", base, name) >= PATH_MAX) exit(94);
}
static void crash(const char *why) {
    int error = errno;
    char file[PATH_MAX]; path(file, root, "fake-crash.log");
    FILE *out = fopen(file, "a");
    if (out) {
        fprintf(out, "action=%s error=%d fixture=%s\n", action ? action : "startup", error, why);
        fclose(out);
    }
    exit(94);
}
typedef struct { char *data; size_t size; } bytes;
static bytes read_at(const char *base, const char *name, int optional) {
    char file[PATH_MAX]; path(file, base, name);
    FILE *in = fopen(file, "rb");
    if (!in) { if (optional && errno == ENOENT) return (bytes){NULL, 0}; crash(name); }
    char *data = malloc(65537);
    if (!data) crash("allocation");
    size_t size = fread(data, 1, 65537, in);
    if (ferror(in) || size > 65536) crash(name);
    if (fclose(in)) crash(name);
    data[size] = 0;
    return (bytes){data, size};
}
static char *text(const char *name, int optional) {
    bytes value = read_at(root, name, optional);
    if (value.data && memchr(value.data, 0, value.size)) crash("NUL in fixture text");
    return value.data;
}
static void write_at(const char *base, const char *name, const void *data, size_t size) {
    char file[PATH_MAX]; path(file, base, name);
    FILE *out = fopen(file, "wb");
    if (!out || fwrite(data, 1, size, out) != size || fclose(out)) crash(name);
}
static void write_text(const char *name, const char *value) { write_at(root, name, value, strlen(value)); }
static void copy(const char *from, const char *to) {
    bytes value = read_at(root, from, 0);
    write_at(root, to, value.data, value.size); free(value.data);
}
static void emit(bytes value) {
    if (fwrite(value.data, 1, value.size, stdout) != value.size) crash("stdout");
    free(value.data);
}
static yyjson_mut_doc *document(void) {
    yyjson_mut_doc *doc = yyjson_mut_doc_new(NULL);
    if (!doc) crash("JSON allocation");
    return doc;
}
static yyjson_mut_val *arguments(yyjson_mut_doc *doc, int count, char **values) {
    yyjson_mut_val *array = yyjson_mut_arr(doc);
    for (int i = 0; i < count; ++i) yyjson_mut_arr_add_strcpy(doc, array, values[i]);
    return array;
}
static void log_json(const char *name, yyjson_mut_doc *doc, yyjson_mut_val *value) {
    yyjson_mut_doc_set_root(doc, value);
    size_t length; char *json = yyjson_mut_write(doc, 0, &length);
    if (!json) crash("JSON encode");
    char file[PATH_MAX]; path(file, root, name);
    FILE *out = fopen(file, "a");
    if (!out || fwrite(json, 1, length, out) != length || fputc('\n', out) == EOF || fclose(out)) crash(name);
    free(json); yyjson_mut_doc_free(doc);
}
static yyjson_mut_val *invocation(yyjson_mut_doc *doc) {
    yyjson_mut_val *value = yyjson_mut_obj(doc);
    yyjson_mut_obj_add_strcpy(doc, value, "action", action);
    yyjson_mut_obj_add_strcpy(doc, value, "program", program);
    yyjson_mut_obj_add_val(doc, value, "argv", arguments(doc, original_count, original));
    return value;
}
static void refuse(int code, const char *why) {
    yyjson_mut_doc *doc = document(); yyjson_mut_val *value = invocation(doc);
    yyjson_mut_obj_add_int(doc, value, "code", code);
    yyjson_mut_obj_add_strcpy(doc, value, "why", why);
    log_json("fake-refusals.log", doc, value); exit(code);
}
static int pm_action(void) {
    return !strcmp(action, "package-info") || !strcmp(action, "package-path") ||
           !strcmp(action, "install-apk") || !strcmp(action, "rollback-apk") || !strcmp(action, "grant-language");
}
static void check_environment(int pm) {
    const char *names[] = {"PATH", "LANG", "ANDROID_ROOT", "ANDROID_DATA", "CLASSPATH"};
    const char *values[] = {"/system/bin:/system/xbin:/data/adb/magisk", "C", "/system", "/data", "/system/framework/pm.jar"};
    int count = pm ? 5 : 4, bad = 0;
    yyjson_mut_doc *doc = document(); yyjson_mut_val *value = yyjson_mut_obj(doc);
    yyjson_mut_val *fixed = yyjson_mut_obj(doc), *unexpected = yyjson_mut_arr(doc);
    for (int i = 0; i < count; ++i) {
        const char *actual = getenv(names[i]);
        if (actual) yyjson_mut_obj_add_strcpy(doc, fixed, names[i], actual);
        else yyjson_mut_obj_add_null(doc, fixed, names[i]);
        if (!actual || strcmp(actual, values[i])) bad = 1;
    }
    for (char **entry = environ; *entry; ++entry) {
        size_t size = strcspn(*entry, "="); int known = 0;
        for (int i = 0; i < count; ++i) if (strlen(names[i]) == size && !strncmp(*entry, names[i], size)) known = 1;
        if (!known) { yyjson_mut_arr_add_val(unexpected, yyjson_mut_strncpy(doc, *entry, size)); bad = 1; }
    }
    yyjson_mut_obj_add_strcpy(doc, value, "action", action);
    yyjson_mut_obj_add_str(doc, value, "runtime", "native");
    yyjson_mut_obj_add_val(doc, value, "fixed", fixed);
    yyjson_mut_obj_add_val(doc, value, "unexpected", unexpected);
    log_json("child-environments.jsonl", doc, value);
    if (bad) refuse(97, "fixed child environment");
}
static void delay(double seconds) {
    if (!isfinite(seconds) || seconds < 0 || seconds > 10) crash("fixture delay outside 0..10 seconds");
    struct timespec remaining = {(time_t)seconds, (long)((seconds - (time_t)seconds) * 1000000000)};
    while (nanosleep(&remaining, &remaining)) if (errno != EINTR) crash("fixture nanosleep");
}
static char *field(const char *name) {
    char *manifest = text("transaction/manifest.new", 0), *line = manifest;
    size_t length = strlen(name);
    while (*line) {
        if (!strncmp(line, name, length) && line[length] == '=') {
            char *value = strdup(line + length + 1);
            if (!value) crash("manifest field allocation");
            char *end = strchr(value, '\n');
            if (end) *end = 0;
            free(manifest); return value;
        }
        line = strchr(line, '\n'); if (!line) break; ++line;
    }
    free(manifest); crash("missing manifest fixture field"); return NULL;
}
static void staged_module(void) {
    char *id = field("id"), *code = field("code"), directory[PATH_MAX], modules[PATH_MAX];
    path(modules, home, "modules_update"); path(directory, modules, id);
    if (mkdir(directory, 0777)) crash("staged module directory");
    char prop[512]; int size = snprintf(prop, sizeof(prop), "id=%s\nversion=v1.1\nversionCode=%s\n", id, code);
    if (size < 0 || size >= (int)sizeof(prop)) crash("module fixture size");
    write_at(directory, "module.prop", prop, (size_t)size);
    write_at(directory, "service.sh", "new service\n", 12);
    free(id); free(code);
}
int main(int argc, char **argv) {
    if (argc < 3 || strlen(argv[0]) >= sizeof(home)) return 95;
    strcpy(home, argv[0]); char *slash = strrchr(home, '/');
    if (!slash) return 95;
    *slash = 0; path(root, home, "private");
    action = argv[1]; program = argv[2]; original_count = argc - 3; original = argv + 3;
    yyjson_mut_doc *doc = document(); log_json("program-invocations.jsonl", doc, invocation(doc));
    int pm = pm_action(), semantic_count = original_count; char **semantic = original;
    if (pm) {
        if (strcmp(program, "/system/bin/app_process") || original_count < 3 ||
            strcmp(original[0], "/system/bin/app_process") || strcmp(original[1], "/system/bin") ||
            strcmp(original[2], "com.android.commands.pm.Pm")) refuse(96, "pm program/argv");
        semantic += 3; semantic_count -= 3;
    } else {
        const char *known = NULL;
        if (!strcmp(action, "set-timezone")) known = "/system/bin/service";
        if (!strcmp(action, "install-module")) known = "/sbin/magisk";
        if (!strcmp(action, "reboot") || !strcmp(action, "guard-reboot")) known = "/system/bin/reboot";
        if (!strcmp(action, "vector-state") || !strcmp(action, "vector-backup") || !strcmp(action, "vector-sync") ||
            !strcmp(action, "vector-restore") || !strcmp(action, "guard-vector-disable")) known = "/system/bin/sqlite3";
        if (!known || strcmp(program, known) || !original_count || strcmp(original[0], program)) refuse(96, "program/argv0");
        ++semantic; --semantic_count;
    }
    check_environment(pm);
    doc = document(); yyjson_mut_val *calls = yyjson_mut_arr(doc);
    yyjson_mut_arr_add_strcpy(doc, calls, action);
    for (int i = 0; i < semantic_count; ++i) yyjson_mut_arr_add_strcpy(doc, calls, semantic[i]);
    int read_only = !strcmp(action, "package-info") || !strcmp(action, "package-path") || !strcmp(action, "vector-state");
    log_json(read_only ? "read-executions.jsonl" : "executions.jsonl", doc, calls);
    char *control = text("slow-action", 1);
    if (control && !strcmp(control, action)) delay(10);
    free(control);
    bytes delays = read_at(root, "probe-delays.json", 1);
    if (delays.data) {
        yyjson_doc *input = yyjson_read(delays.data, delays.size, 0);
        if (!input || !yyjson_is_obj(yyjson_doc_get_root(input))) crash("malformed probe-delays fixture");
        yyjson_val *wait = yyjson_obj_get(yyjson_doc_get_root(input), action);
        if (wait && !yyjson_is_num(wait)) crash("non-numeric probe-delay fixture");
        if (wait) delay(yyjson_get_num(wait));
        yyjson_doc_free(input); free(delays.data);
    }
    control = text("fail-action", 1);
    if (control && !strcmp(control, action)) { free(control); return 9; }
    free(control);
    if (!strcmp(action, "package-info")) {
        bytes output = read_at(root, "host-pm-output", 1);
        if (output.data) emit(output);
        else {
            char *code = text("host-pm-code", 0), *apk = text("host-pm-path", 0);
            if (strcmp(code, "absent")) printf("package:%s=moe.kiwi.thinkos.immersive versionCode:%s\n", apk, code);
            free(code); free(apk);
        }
    }
    if (!strcmp(action, "package-path")) {
        if (semantic_count != 2 || strcmp(semantic[0], "path") || strcmp(semantic[1], "moe.kiwi.thinkos")) return 95;
        puts("package:/data/app/thinkos-app-old/base.apk");
    }
    if (!strcmp(action, "vector-state")) {
        bytes value = read_at(root, "host-vector-output", 1);
        emit(value.data ? value : read_at(root, "host-vector-state", 0));
    }
    if (!strcmp(action, "install-module")) staged_module();
    if (!strcmp(action, "install-apk") || !strcmp(action, "rollback-apk")) {
        int immersive = 0;
        for (int i = 0; i < semantic_count; ++i) if (!strcmp(semantic[i], "moe.kiwi.thinkos.immersive")) immersive = 1;
        if (immersive) {
            if (!strcmp(action, "install-apk")) {
                char *code = field("code"), apk[256];
                write_text("host-pm-code", code);
                if (snprintf(apk, sizeof(apk), "/data/app/thinkos-immersive-new-%s/base.apk", code) >= (int)sizeof(apk)) crash("APK fixture path");
                write_text("host-pm-path", apk); free(code);
            } else {
                write_text("host-pm-code", "1"); write_text("host-pm-path", "/data/app/thinkos-immersive-restored/base.apk");
            }
        }
    }
    if (!strcmp(action, "vector-backup")) {
        write_text("transaction/vector.db", "consistent-backup"); copy("host-vector-state", "transaction/vector-state.before");
    }
    if (!strcmp(action, "vector-restore")) copy("transaction/vector-state.before", "host-vector-state");
    if (!strcmp(action, "vector-sync")) {
        if (!semantic_count) return 8;
        const char *sql = semantic[semantic_count - 1], *apk = strstr(sql, "apk_path='");
        if (!apk) return 8;
        apk += strlen("apk_path='"); const char *end = strchr(apk, '\'');
        if (!end) return 8;
        char *previous = text("host-vector-state", 0), *pipe = strchr(previous, '|');
        if (pipe) *pipe = 0;
        char value[PATH_MAX];
        if (snprintf(value, sizeof(value), "%s|%.*s\n", strstr(sql, "enabled=1") ? "1" : previous, (int)(end - apk), apk) >= (int)sizeof(value)) crash("vector fixture size");
        write_text("host-vector-state", value); free(previous); puts("1");
    }
    if (!strcmp(action, "guard-vector-disable")) {
        char *previous = text("host-vector-state", 0), *pipe = strchr(previous, '|'), value[PATH_MAX];
        if (!pipe) crash("vector fixture separator");
        if (snprintf(value, sizeof(value), "0|%s", pipe + 1) >= (int)sizeof(value)) crash("vector fixture size");
        write_text("host-vector-state", value); free(previous); puts("1");
    }
    if (!strcmp(action, "set-timezone")) {
        if (!semantic_count) return 95;
        const char *value = semantic[semantic_count - 1]; control = text("timezone-mismatch", 1);
        if (control && (strcmp(value, "Asia/Hong_Kong") || !strcmp(control, "always"))) value = "Etc/GMT";
        write_text("host-timezone", value); free(control);
    }
    return 0;
}
