/* ThinkOS root updater. Fixed commands, kernel peer credentials and descriptor-only input.
 * MIT. No shell interpreter or caller-controlled executable/path is accepted. */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif
#include "module_verify.h"
#include "guard_policy.h"
#ifdef UP_SYSTEM_KEYS_HEADER
#include UP_SYSTEM_KEYS_HEADER
#else
#define UP_SYSTEM_KEY_COUNT 0
static const unsigned char up_system_keys[1][64] = {{0}};
#endif

#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif
#ifndef O_NOFOLLOW
#error "O_NOFOLLOW is required"
#endif
#define APP "moe.kiwi.thinkos"
#define IMMERSIVE "moe.kiwi.thinkos.immersive"
#define MODULES 7
#define LINE_MAX_BYTES 256
#define FILES_MAX 12000
#define COPY_MAX (128ULL * 1024 * 1024)
#define APK_MAX (64ULL * 1024 * 1024)
#define PACKAGE_MAX (16ULL * 1024 * 1024)
#define META_MAX (64ULL * 1024)
#define SIG_MAX 1024
#define IO_TIMEOUT 2000
#define EXEC_TIMEOUT 180000
#define PM_PROCESS "/system/bin/app_process"
#define PM_MAIN "com.android.commands.pm.Pm"
#define PM_CLASS_PATH "CLASSPATH=/system/framework/pm.jar"
/* Leave response/transport slack inside the client's complete 2-second exchange. */
#define PROBE_TIMEOUT (IO_TIMEOUT * 9 / 10)
#define RESULT_NONE 0
#define RESULT_HEALTHY 1
#define RESULT_ROLLED_BACK 2
#define RESULT_FAILED 3
enum phase { PH_IDLE, PH_BEGIN, PH_STAGED, PH_INSTALLING, PH_REBOOT, PH_ROLLING_BACK, PH_FAILED };
static const char *ids[MODULES] = {"thinkos-updater", "thinkos-cast", "thinkos-immersive", "thinkos-shell", "thinkos-certs", "thinkos-power", "timesync"};
/* This private record is versioned and checksummed, and never read from the application. */
struct __attribute__((packed)) durable {
    char magic[8]; uint32_t version, active, phase, result;
    uint32_t changed, originals, apk_changed, vector_present, rollback_attempted;
    uint32_t floor[MODULES], target[MODULES];
    uint64_t reboot_wall, reboot_mono;
    char reboot_boot[40], transaction[33];
    uint8_t reserved[7];
    uint64_t checksum;
};
static struct durable journal;
static const char *root_path = "/data/adb/thinkos";
static const char *packages_path = "/data/system/packages.list";
static const char *modules_path = "/data/adb/modules";
static const char *updates_path = "/data/adb/modules_update";
static const char *vector_path = "/data/adb/lspd/config/modules_config.db";
static const char *listen_name = "@thinkos-updater";
#ifdef UPDATER_HOST_TEST
static const char *test_exec;
static int test_peer = -1;
static const char *test_sdk = "27";
static const char *test_tzdata;
static const char *test_guard_mode;
static const char *test_boot;
static uint64_t test_now = UINT64_MAX;
#endif
static int root_fd = -1;
static int state_broken;
static volatile sig_atomic_t stopping;
static unsigned copied_files;
static uint64_t copied_bytes;

static uint64_t real_mono_ms(void) {
    struct timespec t; if (clock_gettime(CLOCK_MONOTONIC, &t)) return 0; return (uint64_t)t.tv_sec * 1000 + (unsigned)t.tv_nsec / 1000000;
}
static uint64_t mono_ms(void) {
#ifdef UPDATER_HOST_TEST
    if (test_now != UINT64_MAX) return test_now;
#endif
    return real_mono_ms();
}
static uint64_t probe_deadline(void) { uint64_t now = real_mono_ms(); return now ? now + PROBE_TIMEOUT : 0; }
static void terminate(int n) { (void)n; stopping = 1; }
static int regular(int fd, uint64_t limit, struct stat *out) {
    struct stat s; int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || (flags & O_ACCMODE) != O_RDONLY || fstat(fd, &s) || !S_ISREG(s.st_mode) || s.st_size < 0 || (uint64_t)s.st_size > limit) return -1;
    if (out) *out = s;
    return 0;
}
static int write_all(int fd, const void *data, size_t count) {
    const unsigned char *p = data;
    while (count) { ssize_t n = write(fd, p, count); if (n < 0 && errno == EINTR) continue; if (n <= 0) return -1; p += n; count -= (size_t)n; }
    return 0;
}
static int open_dir_at(int parent, const char *name) {
    int fd = openat(parent, name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    struct stat s;
    if (fd >= 0 && (fstat(fd, &s) || s.st_uid != getuid() || (s.st_mode & 0022))) { close(fd); errno = EPERM; return -1; }
    return fd;
}
static int open_owned_dir(const char *path) {
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW); struct stat s;
    if (fd >= 0 && (fstat(fd, &s) || s.st_uid != getuid() || (s.st_mode & 0022))) { close(fd); errno = EPERM; return -1; }
    return fd;
}
static int make_dir_at(int parent, const char *name) {
    if (mkdirat(parent, name, 0700) && errno != EEXIST) return -1;
    return open_dir_at(parent, name);
}
static int copy_fd(int source, int parent, const char *name, uint64_t max) {
    struct stat before, after; unsigned char buffer[32768]; off_t offset = 0;
    if (regular(source, max, &before)) return -1;
    int out = openat(parent, name, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (out < 0) return -1;
    int rc = -1;
    while (offset < before.st_size) {
        size_t chunk = (uint64_t)(before.st_size - offset) > sizeof(buffer) ? sizeof(buffer) : (size_t)(before.st_size - offset);
        ssize_t n = pread(source, buffer, chunk, offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0 || write_all(out, buffer, (size_t)n)) goto done;
        offset += n;
    }
    if (fstat(source, &after) || before.st_dev != after.st_dev || before.st_ino != after.st_ino || before.st_size != after.st_size || fsync(out)) goto done;
    rc = 0;
done:
    close(out); if (rc) unlinkat(parent, name, 0); return rc;
}
static int copy_file_at(int from, const char *name, int to, const char *dest, uint64_t max) {
    int fd = openat(from, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW); if (fd < 0) return -1;
    int rc = copy_fd(fd, to, dest, max); close(fd); return rc;
}
/* Recursion never follows symlinks. Link contents are copied as links, not read as files. */
static int copy_tree(int from, int to, unsigned depth) {
    if (depth > 24) return -1;
    int duplicate = dup(from); if (duplicate < 0) return -1;
    DIR *dir = fdopendir(duplicate); if (!dir) { close(duplicate); return -1; }
    int rc = 0; struct dirent *e;
    while ((e = readdir(dir)) != NULL) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        struct stat s;
        if (++copied_files > FILES_MAX || fstatat(from, e->d_name, &s, AT_SYMLINK_NOFOLLOW)) { rc = -1; break; }
        if (S_ISDIR(s.st_mode)) {
            int a = open_dir_at(from, e->d_name), b = make_dir_at(to, e->d_name);
            if (a < 0 || b < 0 || copy_tree(a, b, depth + 1) || fchmod(b, s.st_mode & 0777) || fsync(b)) rc = -1;
            if (a >= 0) close(a);
            if (b >= 0) close(b);
            if (rc) break;
        } else if (S_ISREG(s.st_mode)) {
            if (s.st_size < 0 || (uint64_t)s.st_size > COPY_MAX - copied_bytes) { rc = -1; break; }
            copied_bytes += (uint64_t)s.st_size;
            if (copy_file_at(from, e->d_name, to, e->d_name, COPY_MAX)) { rc = -1; break; }
            if (fchmodat(to, e->d_name, s.st_mode & 0777, 0)) { rc = -1; break; }
        } else if (S_ISLNK(s.st_mode)) {
            char link[PATH_MAX]; ssize_t n = readlinkat(from, e->d_name, link, sizeof(link) - 1);
            if (n < 0 || (size_t)n >= sizeof(link) - 1) { rc = -1; break; }
            link[n] = 0; if (symlinkat(link, to, e->d_name)) { rc = -1; break; }
        } else { rc = -1; break; }
    }
    closedir(dir); if (!rc && fsync(to)) rc = -1; return rc;
}
static int remove_tree(int parent, const char *name, unsigned depth) {
    if (depth > 24) return -1;
    struct stat s;
    if (fstatat(parent, name, &s, AT_SYMLINK_NOFOLLOW)) return errno == ENOENT ? 0 : -1;
    if (!S_ISDIR(s.st_mode)) return unlinkat(parent, name, 0);
    int fd = open_dir_at(parent, name); if (fd < 0) return -1;
    int duplicate = dup(fd); if (duplicate < 0) { close(fd); return -1; }
    DIR *dir = fdopendir(duplicate); if (!dir) { close(duplicate); close(fd); return -1; }
    int rc = 0; struct dirent *e;
    while ((e = readdir(dir)) != NULL) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        if (remove_tree(fd, e->d_name, depth + 1)) { rc = -1; break; }
    }
    closedir(dir); close(fd);
    return rc ? rc : unlinkat(parent, name, AT_REMOVEDIR);
}
static uint64_t checksum(const struct durable *s) {
    const unsigned char *p = (const unsigned char *)s; uint64_t h = UINT64_C(1469598103934665603);
    for (size_t i = 0; i < offsetof(struct durable, checksum); i++) { h ^= p[i]; h *= UINT64_C(1099511628211); }
    return h;
}
static int save_state(void) {
    struct durable next = journal; next.checksum = checksum(&next);
    unlinkat(root_fd, "state.new", 0);
    int fd = openat(root_fd, "state.new", O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) { state_broken = 1; return -1; }
    int rc = write_all(fd, &next, sizeof(next)) || fsync(fd); close(fd);
    if (rc || renameat(root_fd, "state.new", root_fd, "state.bin") || fsync(root_fd)) { state_broken = 1; return -1; }
    journal = next; return 0;
}
static int load_state(void) {
    memset(&journal, 0, sizeof(journal)); memcpy(journal.magic, "TOSUPD1", 8); journal.version = 1;
    int fd = openat(root_fd, "state.bin", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    struct stat s; struct durable next;
    int rc = fstat(fd, &s) || !S_ISREG(s.st_mode) || s.st_uid != getuid() || (s.st_mode & 0077) || s.st_size != (off_t)sizeof(next) || pread(fd, &next, sizeof(next), 0) != (ssize_t)sizeof(next);
    close(fd);
    if (rc || memcmp(next.magic, "TOSUPD1", 8) || next.version != 1 || next.checksum != checksum(&next) || next.active > 1 || next.phase > PH_FAILED || next.result > RESULT_FAILED || next.changed >= (1u << MODULES) || next.originals >= (1u << MODULES) || next.apk_changed > 1 || next.vector_present > 1 || next.rollback_attempted > 1 || next.transaction[32] || next.reboot_boot[39]) return -1;
    for (int i = 0; i < MODULES; i++) if (next.floor[i] > INT_MAX || next.target[i] > INT_MAX) return -1;
    journal = next; return 0;
}
static int allowed_peer(int fd) {
    uid_t uid;
#if defined(__linux__)
    struct ucred c; socklen_t n = sizeof(c);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &c, &n) || n != sizeof(c)) return 0;
    uid = c.uid;
#else
    gid_t gid; if (getpeereid(fd, &uid, &gid)) return 0;
#endif
#ifdef UPDATER_HOST_TEST
    if (test_peer >= 0) uid = (uid_t)test_peer;
#endif
    if (uid == 0) return 1;
    int list = open(packages_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (list < 0) return 0;
    FILE *f = fdopen(list, "r"); if (!f) { close(list); return 0; }
    char line[2048], package[256]; unsigned long number; int ok = 0;
    while (fgets(line, sizeof(line), f)) {
        if (!strchr(line, '\n')) { ok = 0; break; }
        if (sscanf(line, "%255s %lu", package, &number) == 2 && !strcmp(package, APP)) { ok = number == (unsigned long)uid; break; }
    }
    fclose(f); return ok;
}
/* Recovery control is root-only even though normal requests also admit ThinkOS.
 * This deliberately does not reuse the application's allowed_peer result. */
static int root_peer(int fd) {
    uid_t uid;
#if defined(__linux__)
    struct ucred credential; socklen_t length = sizeof(credential);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &credential, &length) || length != sizeof(credential)) return 0;
    uid = credential.uid;
#else
    gid_t gid; if (getpeereid(fd, &uid, &gid)) return 0;
#endif
#ifdef UPDATER_HOST_TEST
    if (test_peer >= 0) uid = (uid_t)test_peer;
#endif
    return uid == 0;
}
static int operation_lock(void) {
    int fd = openat(root_fd, "updater.lock", O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
    struct stat s;
    if (fd < 0) return -1;
    if (fstat(fd, &s) || !S_ISREG(s.st_mode) || s.st_uid != getuid() || (s.st_mode & 0077) || flock(fd, LOCK_EX)) {
        close(fd); return -1;
    }
    return fd;
}
static void answer(int fd, const char *value) { (void)write_all(fd, value, strlen(value)); (void)write_all(fd, "\n", 1); }
struct request { char line[LINE_MAX_BYTES + 1]; int fds[8]; unsigned count; };
static void close_request(struct request *r) { for (unsigned i = 0; i < r->count; i++) close(r->fds[i]); r->count = 0; }
static int receive_request(int fd, struct request *r) {
    memset(r, 0, sizeof(*r)); size_t used = 0; uint64_t started = real_mono_ms(), deadline = started + IO_TIMEOUT;
    if (!started) return -1;
    while (used < LINE_MAX_BYTES) {
        uint64_t now = real_mono_ms(); if (!now || now >= deadline) return -1;
        struct pollfd p = {fd, POLLIN, 0}; if (poll(&p, 1, (int)(deadline - now)) <= 0) return -1;
        char bytes[LINE_MAX_BYTES + 1]; union { struct cmsghdr alignment; unsigned char bytes[CMSG_SPACE(8 * sizeof(int))]; } control;
        struct iovec io = {bytes, sizeof(bytes)}; struct msghdr msg;
        memset(&msg, 0, sizeof(msg)); memset(&control, 0, sizeof(control)); msg.msg_iov = &io; msg.msg_iovlen = 1; msg.msg_control = control.bytes; msg.msg_controllen = sizeof(control.bytes);
        ssize_t n = recvmsg(fd, &msg, 0);
        if (n <= 0) return -1;
        int bad = msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC);
        for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
            if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS || c->cmsg_len < CMSG_LEN(0)) { bad = 1; continue; }
            size_t length = c->cmsg_len - CMSG_LEN(0); if (length % sizeof(int)) bad = 1;
            for (size_t i = 0; i < length / sizeof(int); i++) {
                int value; memcpy(&value, (unsigned char *)CMSG_DATA(c) + i * sizeof(int), sizeof(value));
                if (r->count < 8) { r->fds[r->count++] = value; if (fcntl(value, F_SETFD, FD_CLOEXEC)) bad = 1; }
                else { close(value); bad = 1; }
            }
        }
        if (bad) return -1;
        for (ssize_t i = 0; i < n; i++) {
            unsigned char ch = (unsigned char)bytes[i];
            if (ch == '\n') {
                if (i + 1 != n || used == 0) return -1;
                r->line[used] = 0;
                for (unsigned j = 0; j < r->count; j++) if (regular(r->fds[j], APK_MAX, NULL)) return -1;
                return 0;
            }
            if (used >= LINE_MAX_BYTES || ch < 32 || ch > 126) return -1;
            r->line[used++] = (char)ch;
        }
    }
    return -1;
}
/* Only the host-test binary can replace these fixed operations. Production ignores argv/env. */
static int execute_until(const char *action, const char *program, char *const args[], char *output, size_t capacity, uint64_t deadline) {
    uint64_t now = real_mono_ms();
    if (output && capacity) output[0] = 0;
    if (!now || !deadline || now >= deadline || (output && !capacity)) return -1;
    int pipes[2]; if (pipe(pipes)) return -1;
    pid_t child = fork(); if (child < 0) { close(pipes[0]); close(pipes[1]); return -1; }
    if (!child) {
        if (setpgid(0, 0)) _exit(126);
        close(pipes[0]); if (dup2(pipes[1], STDOUT_FILENO) < 0) _exit(126); close(pipes[1]);
        int null = open("/dev/null", O_RDWR); if (null >= 0) { (void)dup2(null, STDIN_FILENO); (void)dup2(null, STDERR_FILENO); if (null > 2) close(null); }
        /* API27's pm script has no shebang: execve cannot run it. Only the fixed
         * PM app_process entry gets the ROM's pm.jar; no caller classpath or
         * loader/shell environment is inherited. */
        char *const environment[] = {"PATH=/system/bin:/system/xbin:/data/adb/magisk", "LANG=C",
                                    "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
                                    !strcmp(program, PM_PROCESS) ? PM_CLASS_PATH : NULL, NULL};
#ifdef UPDATER_HOST_TEST
        if (test_exec) {
            /* Preserve the actual executable and argv[0] for the fake to verify
             * before it removes the fixed Java launcher prefix. */
            char *fake[24]; int index = 0; fake[index++] = (char *)test_exec; fake[index++] = (char *)action;
            fake[index++] = (char *)program;
            for (int i = 0; args[i]; i++) { if (index >= 23) _exit(126); fake[index++] = args[i]; }
            fake[index] = NULL;
            execve(test_exec, fake, environment); _exit(127);
        }
#else
        (void)action;
#endif
        execve(program, args, environment); _exit(127);
    }
    (void)setpgid(child, child);
    close(pipes[1]); (void)fcntl(pipes[0], F_SETFL, O_NONBLOCK);
    size_t used = 0; int status = 0, finished = 0, overflow = 0, expired = 0;
    while ((now = real_mono_ms()) && now < deadline) {
        char buffer[1024]; ssize_t n;
        while ((n = read(pipes[0], buffer, sizeof(buffer))) > 0) {
            if (output && capacity > used + (size_t)n && !memchr(buffer, 0, (size_t)n)) { memcpy(output + used, buffer, (size_t)n); used += (size_t)n; }
            else if (output) overflow = 1;
            now = real_mono_ms();
            if (!now || now >= deadline) { expired = 1; break; }
            if (overflow) break;
        }
        if (expired || overflow) break;
        pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child) { finished = 1; break; }
        if (result < 0 && errno != EINTR) break;
        now = real_mono_ms(); if (!now || now >= deadline) { expired = 1; break; }
        int remaining = deadline - now < 20 ? (int)(deadline - now) : 20;
        struct pollfd p = {pipes[0], POLLIN, 0}; (void)poll(&p, 1, remaining);
    }
    if (!finished) { (void)kill(-child, SIGKILL); (void)kill(child, SIGKILL); while (waitpid(child, &status, 0) < 0 && errno == EINTR) {} }
    if (finished && output && capacity) {
        char buffer[1024]; ssize_t n;
        while ((n = read(pipes[0], buffer, sizeof(buffer))) > 0) {
            if (capacity > used + (size_t)n && !memchr(buffer, 0, (size_t)n)) { memcpy(output + used, buffer, (size_t)n); used += (size_t)n; }
            else overflow = 1;
            now = real_mono_ms(); if (!now || now >= deadline) { expired = 1; break; }
            if (overflow) break;
        }
    }
    /* A child that exits with descendants holding stdout must not leave them running. */
    (void)kill(-child, SIGKILL);
    if (output && capacity) output[used] = 0;
    close(pipes[0]);
    return finished && !overflow && !expired && WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : -1;
}
static int execute(const char *action, const char *program, char *const args[], char *output, size_t capacity) {
    uint64_t now = real_mono_ms();
    return execute_until(action, program, args, output, capacity, now ? now + EXEC_TIMEOUT : 0);
}
static int pm_until(const char *action, char *const words[], char *output, size_t capacity, uint64_t deadline) {
    char *args[20] = {PM_PROCESS, "/system/bin", PM_MAIN}; size_t used = 3;
    for (size_t i = 0; words[i]; ++i) { if (used >= 19) return -1; args[used++] = words[i]; }
    args[used] = NULL;
    return execute_until(action, PM_PROCESS, args, output, capacity, deadline);
}
static int pm_execute(const char *action, char *const words[]) {
    uint64_t now = real_mono_ms();
    return pm_until(action, words, NULL, 0, now ? now + EXEC_TIMEOUT : 0);
}
/* Setup operations are independent of distribution keys, but remain fixed,
 * authenticated API27 commands. The host fixtures are not production options. */
static int setup_property(const char *name, char *out, size_t capacity) {
    if (!capacity) return -1;
    out[0] = 0;
#ifdef UPDATER_HOST_TEST
    if (!strcmp(name, "ro.build.version.sdk")) {
        if (strlen(test_sdk) >= capacity) return -1;
        strcpy(out, test_sdk); return 0;
    }
    if (!strcmp(name, "persist.sys.timezone") || !strcmp(name, "sys.boot_completed")) {
        int input = openat(root_fd, !strcmp(name, "persist.sys.timezone") ? "host-timezone" : "host-boot-completed", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (input < 0) return -1;
        struct stat s; int rc = fstat(input, &s) || !S_ISREG(s.st_mode) || s.st_size < 1 || (uint64_t)s.st_size >= capacity;
        ssize_t length = rc ? -1 : pread(input, out, (size_t)s.st_size, 0);
        close(input);
        if (rc || length != s.st_size) return -1;
        out[length] = 0; return 0;
    }
    return -1;
#elif defined(__ANDROID__)
    char value[PROP_VALUE_MAX]; int length = __system_property_get(name, value);
    if (length <= 0 || (size_t)length >= capacity) return -1;
    memcpy(out, value, (size_t)length + 1); return 0;
#else
    (void)name; return -1;
#endif
}
static int setup_api27(void) {
    char sdk[16]; return !setup_property("ro.build.version.sdk", sdk, sizeof(sdk)) && !strcmp(sdk, "27");
}
static int zone_text(const char *id) {
    size_t length = strlen(id);
    if (!length || length > 64) return 0;
    for (size_t i = 0; i < length; ++i) {
        unsigned char c = (unsigned char)id[i];
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
              c == '_' || c == '+' || c == '-' || c == '/')) return 0;
    }
    return 1;
}
static int pread_exact(int fd, void *data, size_t length, off_t offset) {
    unsigned char *p = data;
    while (length) {
        ssize_t count = pread(fd, p, length, offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        p += count; offset += count; length -= (size_t)count;
    }
    return 0;
}
static uint32_t network_u32(const unsigned char *bytes) {
    return ((uint32_t)bytes[0] << 24) | ((uint32_t)bytes[1] << 16) | ((uint32_t)bytes[2] << 8) | bytes[3];
}
/* API27 tzdata container index: 24-byte header, sorted 52-byte records with
 * 40-byte NUL-terminated IDs. Only fixed, protected OS paths are consulted.
 * AOSP format reference (no implementation copied): ZoneInfoDB.java,
 * https://android.googlesource.com/platform/libcore/+/oreo-mr1-release/luni/src/main/java/libcore/util/ZoneInfoDB.java */
static int zone_in_database(const char *path, const char *id) {
    int input = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (input < 0) return -1;
    struct stat s; unsigned char header[24]; int found = 0, rc = -1;
    if (fstat(input, &s) || !S_ISREG(s.st_mode) || s.st_size < 24 || s.st_size > 4 * 1024 * 1024 || (s.st_mode & 0022)) goto done;
#ifdef UPDATER_HOST_TEST
    if (s.st_uid != getuid()) goto done;
#else
    if (s.st_uid != 0 && s.st_uid != 1000) goto done;
#endif
    if (pread_exact(input, header, sizeof(header), 0) || memcmp(header, "tzdata", 6) || header[11]) goto done;
    for (int i = 6; i < 10; ++i) if (header[i] < '0' || header[i] > '9') goto done;
    if (header[10] < 'a' || header[10] > 'z') goto done;
    uint32_t index = network_u32(header + 12), data = network_u32(header + 16), tab = network_u32(header + 20);
    if (index < 24 || data <= index || tab < data || (uint64_t)tab > (uint64_t)s.st_size ||
            (data - index) % 52 || (data - index) / 52 > 4096) goto done;
    char previous[40] = {0};
    for (uint32_t at = index; at < data; at += 52) {
        unsigned char entry[52], magic[4];
        if (pread_exact(input, entry, sizeof(entry), at)) goto done;
        unsigned char *end = memchr(entry, 0, 40);
        if (!end || end == entry || !zone_text((const char *)entry) ||
                (previous[0] && strcmp(previous, (const char *)entry) >= 0)) goto done;
        for (unsigned char *p = end; p < entry + 40; ++p) if (*p) goto done;
        uint32_t offset = network_u32(entry + 40), length = network_u32(entry + 44);
        if (length < 44 || offset > tab - data || length > tab - data - offset ||
                pread_exact(input, magic, sizeof(magic), (off_t)data + offset) || memcmp(magic, "TZif", 4)) goto done;
        strcpy(previous, (const char *)entry);
        if (!strcmp((const char *)entry, id)) found = 1;
    }
    rc = found;
done:
    close(input); return rc;
}
static int known_zone(const char *id) {
#ifdef UPDATER_HOST_TEST
    return test_tzdata && zone_in_database(test_tzdata, id) == 1;
#else
    int overlay = zone_in_database("/data/misc/zoneinfo/current/tzdata", id);
    if (overlay >= 0) return overlay == 1;
    return zone_in_database("/system/usr/share/zoneinfo/tzdata", id) == 1;
#endif
}
static int set_zone(const char *id) {
    char *const args[] = {"/system/bin/service", "call", "alarm", "3", "s16", (char *)id, NULL};
    return execute("set-timezone", args[0], args, NULL, 0);
}
static const char *set_timezone_request(const char *id) {
    if (!setup_api27()) return "ERR unsupported";
    if (!zone_text(id) || !known_zone(id)) return "ERR zone-unknown";
    char previous[65], current[65];
    if (setup_property("persist.sys.timezone", previous, sizeof(previous)) || !zone_text(previous) || !known_zone(previous)) return "ERR zone-unknown";
    if (!strcmp(previous, id)) return "OK";
    if (!set_zone(id) && !setup_property("persist.sys.timezone", current, sizeof(current)) && !strcmp(current, id)) return "OK";
    /* Binder may fail after mutating the property. Always restore and verify. */
    if (set_zone(previous) || setup_property("persist.sys.timezone", current, sizeof(current)) || strcmp(current, previous)) return "ERR zone-restore-failed";
    return "ERR zone-unknown";
}
static const char *grant_language(void) {
    if (!setup_api27()) return "ERR unsupported";
    char *const words[] = {"grant", APP, "android.permission.CHANGE_CONFIGURATION", NULL};
    return pm_execute("grant-language", words) ? "ERR grant-failed" : "OK";
}
static int connection_alive(int fd) {
    unsigned char byte; ssize_t n = recv(fd, &byte, 1, MSG_PEEK | MSG_DONTWAIT);
    return n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK);
}
static int transaction_path(char *out, size_t size, const char *leaf) { return snprintf(out, size, "%s/transaction/%s", root_path, leaf) < (int)size ? 0 : -1; }
static int positive_code(const char *bytes, size_t length, uint32_t *code) {
    if (!length || length > 10 || bytes[0] < '1' || bytes[0] > '9') return -1;
    uint64_t value = 0;
    for (size_t i = 0; i < length; ++i) {
        if (bytes[i] < '0' || bytes[i] > '9') return -1;
        value = value * 10 + (unsigned)(bytes[i] - '0'); if (value > INT_MAX) return -1;
    }
    *code = (uint32_t)value; return 0;
}
/* A PM-owned APK path is data for a fixed SQL statement, never SQL syntax. */
static int apk_path_text(const char *path, size_t capacity) {
    size_t length = strlen(path);
    if (length < 20 || length >= capacity || strncmp(path, "/data/app/", 10) ||
            strcmp(path + length - 9, "/base.apk") || strstr(path, "//") ||
            strstr(path, "/./") || strstr(path, "/../")) return -1;
    for (size_t i = 0; i < length; ++i)
        if (!strchr("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_./=+-", path[i])) return -1;
    return 0;
}
/* API27 primary interface: fixed pm list arguments, full package-name match and
 * canonical positive versionCode. The substring filter is not an identity check. */
static int immersive_package(uint32_t *code, char *path, size_t capacity, uint64_t deadline) {
    char output[2 * PATH_MAX + 256];
    char *words[] = {"list", "packages", "-f", "--show-versioncode", "--user", "0", IMMERSIVE, NULL};
    *code = 0; if (capacity) path[0] = 0;
    if (!capacity || pm_until("package-info", words, output, sizeof(output), deadline)) return -1;
    size_t length = strlen(output); if (!length) return 0;
    if (output[length - 1] != '\n') return -1;
    int matched = 0; char *line = output;
    while (*line) {
        char *end = strchr(line, '\n'); if (!end) return -1;
        *end = 0; size_t count = strlen(line); if (count && line[count - 1] == '\r') line[count - 1] = 0;
        if (strncmp(line, "package:", 8)) return -1;
        char *version = strstr(line + 8, " versionCode:"); if (!version) return -1;
        *version = 0; version += 13;
        uint32_t found; if (positive_code(version, strlen(version), &found)) return -1;
        char *separator = strrchr(line + 8, '='); if (!separator || !separator[1]) return -1;
        *separator = 0;
        for (const char *p = separator + 1; *p; ++p)
            if (!strchr("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.", *p)) return -1;
        if (!strcmp(separator + 1, IMMERSIVE)) {
            if (matched || apk_path_text(line + 8, capacity)) return -1;
            strcpy(path, line + 8); *code = found; matched = 1;
        }
        line = end + 1;
    }
    return 0;
}
static int package_path(const char *package, char *path, size_t capacity) {
    if (!strcmp(package, IMMERSIVE)) {
        uint32_t code = 0;
        return immersive_package(&code, path, capacity, probe_deadline()) || !code ? -1 : 0;
    }
    char output[PATH_MAX + 32]; char *words[] = {"path", (char *)package, NULL};
    if (pm_until("package-path", words, output, sizeof(output), probe_deadline())) return -1;
    size_t n = strlen(output); if (n && output[n - 1] == '\n') output[--n] = 0;
    if (n && output[n - 1] == '\r') output[--n] = 0;
    if (strncmp(output, "package:/data/app/", 18) || n < 20 || n >= capacity + 8) return -1;
    for (size_t i = 8; i < n; i++) if (!strchr("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_./=-", output[i])) return -1;
    if (strstr(output, "/../") || strcmp(output + n - 9, "/base.apk")) return -1;
    strcpy(path, output + 8); return 0;
}
static int save_package(int directory, const char *package, const char *name) {
    char path[PATH_MAX];
#ifdef UPDATER_HOST_TEST
    if (test_exec) {
        const char *fixture = !strcmp(package, APP) ? "host-app/base.apk" : !strcmp(package, IMMERSIVE) ? "host-immersive/base.apk" : NULL;
        if (!fixture) return -1;
        if (!strcmp(package, IMMERSIVE)) {
            uint32_t code = 0; char installed[PATH_MAX];
            if (immersive_package(&code, installed, sizeof(installed), probe_deadline()) || !code) return -1;
        }
        if (snprintf(path, sizeof(path), "%s/%s", root_path, fixture) >= (int)sizeof(path)) return -1;
    } else
#endif
    if (package_path(package, path, sizeof(path))) return -1;
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW); if (fd < 0) return -1;
    int rc = copy_fd(fd, directory, name, APK_MAX); close(fd); return rc;
}
static int vector_db_metadata(const struct stat *s, uid_t trusted_uid, gid_t trusted_gid) {
    /* Vector's normal DB is 0660 root:root. Root-group writes are trusted;
     * world writes or a different owner/group never are. */
    return S_ISREG(s->st_mode) && s->st_uid == trusted_uid && s->st_gid == trusted_gid && !(s->st_mode & 0002);
}
static int open_vector_db(void) {
    int fd = open(vector_path, O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW); struct stat s;
#ifdef UPDATER_HOST_TEST
    const uid_t trusted_uid = getuid(); const gid_t trusted_gid = getgid();
#else
    const uid_t trusted_uid = 0; const gid_t trusted_gid = 0;
#endif
    if (fd < 0) return -1;
    if (fstat(fd, &s) || !vector_db_metadata(&s, trusted_uid, trusted_gid)) { close(fd); return -1; }
    return fd;
}
static int sqlite(const char *action, const char *sql, char *output, size_t capacity) {
    int fd = open_vector_db(); if (fd < 0) return -1;
    close(fd);
    char *args[] = {"/system/bin/sqlite3", (char *)vector_path, (char *)sql, NULL};
    return execute(action, args[0], args, output, capacity);
}
static int vector_enabled_at(const char *apk, uint64_t deadline) {
    if (apk_path_text(apk, PATH_MAX)) return 0;
    int fd = open_vector_db(); if (fd < 0) return 0;
    close(fd);
    char output[PATH_MAX + 32], expected[PATH_MAX + 8];
    /* No APK path is interpolated into read SQL. Exactly one enabled row and its
     * byte-identical PM path must be returned; duplicate rows also fail closed. */
    char *args[] = {"/system/bin/sqlite3", "-batch", "-noheader", "-separator", "|", "-init", "/dev/null",
                   (char *)vector_path, "SELECT enabled,apk_path FROM modules WHERE module_pkg_name COLLATE BINARY='moe.kiwi.thinkos.immersive';", NULL};
    if (execute_until("vector-state", args[0], args, output, sizeof(output), deadline)) return 0;
    if (snprintf(expected, sizeof(expected), "1|%s\n", apk) >= (int)sizeof(expected)) return 0;
    return !strcmp(output, expected);
}
static int backup_vector(void) {
    char path[PATH_MAX], sql[PATH_MAX + 30]; if (transaction_path(path, sizeof(path), "vector.db")) return -1;
    if (strchr(path, '\'')) return -1;
    snprintf(sql, sizeof(sql), ".backup '%s'", path);
    if (sqlite("vector-backup", sql, NULL, 0)) return -1;
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    struct stat s; int rc = fstat(fd, &s) || !S_ISREG(s.st_mode) || fsync(fd); close(fd);
    return rc;
}
static int transaction_id(char out[33]) {
    unsigned char bytes[16]; int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC); if (fd < 0) return -1;
    ssize_t n = read(fd, bytes, sizeof(bytes)); close(fd); if (n != (ssize_t)sizeof(bytes)) return -1;
    for (unsigned i = 0; i < sizeof(bytes); i++) snprintf(out + i * 2, 3, "%02x", bytes[i]);
    return 0;
}
static const char *begin_transaction(void) {
    if (journal.active) return "ERR busy";
    if (remove_tree(root_fd, "transaction", 0)) return "ERR io";
    int dir = make_dir_at(root_fd, "transaction"); if (dir < 0) return "ERR io";
    struct durable old = journal; copied_bytes = 0; copied_files = 0;
    journal.originals = 0; journal.changed = 0; journal.apk_changed = 0; journal.vector_present = 0; journal.rollback_attempted = 0;
    memset(journal.target, 0, sizeof(journal.target));
    int good = save_package(dir, APP, "previous.apk") == 0;
    int modules = open_owned_dir(modules_path);
    if (modules < 0) good = 0;
    int updates = open_owned_dir(updates_path);
    if (updates < 0 && errno != ENOENT) good = 0;
    for (int i = 0; good && i < MODULES; i++) {
        struct stat s;
        if (updates >= 0 && !fstatat(updates, ids[i], &s, AT_SYMLINK_NOFOLLOW)) { good = 0; break; }
        if (modules < 0) continue;
        if (fstatat(modules, ids[i], &s, AT_SYMLINK_NOFOLLOW)) { if (errno != ENOENT) good = 0; continue; }
        int source = open_dir_at(modules, ids[i]), target = make_dir_at(dir, ids[i]);
        if (source < 0 || target < 0 || copy_tree(source, target, 0)) good = 0;
        if (source >= 0) close(source);
        if (target >= 0) close(target);
        if (good) journal.originals |= 1u << i;
    }
    if (modules >= 0) close(modules);
    if (updates >= 0) close(updates);
    /* Vector backup is deferred until its first stage, before any change to that APK. */
    if (good && (!transaction_id(journal.transaction)) && !fsync(dir)) {
        journal.active = 1; journal.phase = PH_BEGIN; journal.result = RESULT_NONE;
        if (!save_state()) { close(dir); return "OK"; }
        close(dir); return "ERR io"; /* An uncertain durable BEGIN must retain its backups. */
    }
    journal = old; close(dir); (void)remove_tree(root_fd, "transaction", 0); return "ERR backup-failed";
}
static int module_code(int parent, const char *id, uint32_t *code) {
    *code = 0; int dir = open_dir_at(parent, id); if (dir < 0) return errno == ENOENT ? 0 : -1;
    int fd = openat(dir, "module.prop", O_RDONLY | O_CLOEXEC | O_NOFOLLOW); close(dir); if (fd < 0) return -1;
    char bytes[4097]; struct stat s; int result = -1;
    if (!fstat(fd, &s) && S_ISREG(s.st_mode) && s.st_uid == getuid() && !(s.st_mode & 0022) &&
            s.st_size > 0 && s.st_size < (off_t)sizeof(bytes) &&
            pread(fd, bytes, (size_t)s.st_size, 0) == s.st_size && !memchr(bytes, 0, (size_t)s.st_size)) {
        bytes[s.st_size] = 0; char *save, *line = strtok_r(bytes, "\n", &save); int seen = 0;
        while (line) {
            if (!strncmp(line, "versionCode=", 12)) {
                size_t length = strlen(line + 12); if (length && line[12 + length - 1] == '\r') --length;
                if (seen++ || positive_code(line + 12, length, code)) { *code = 0; result = -1; break; }
                result = 0;
            }
            line = strtok_r(NULL, "\n", &save);
        }
    }
    close(fd); return result;
}
static int installed_code(int index, uint32_t *code, char *apk, size_t capacity, uint64_t deadline) {
    *code = 0; if (capacity) apk[0] = 0;
    if (index == 2) return immersive_package(code, apk, capacity, deadline);
    int modules = open_owned_dir(modules_path); if (modules < 0) return -1;
    int rc = module_code(modules, ids[index], code); close(modules); return rc;
}
static int magisk_enabled(int index) {
    int modules = open_owned_dir(modules_path); if (modules < 0) return 0;
    int directory = open_dir_at(modules, ids[index]); close(modules); if (directory < 0) return 0;
    const char *markers[] = {"disable", "remove", "update"}; struct stat st;
    for (size_t i = 0; i < sizeof(markers) / sizeof(markers[0]); ++i) {
        int rc = fstatat(directory, markers[i], &st, AT_SYMLINK_NOFOLLOW);
        if (!rc || errno != ENOENT) { close(directory); return 0; }
    }
    close(directory);
    int updates = open_owned_dir(updates_path);
    if (updates < 0) return errno == ENOENT;
    int rc = fstatat(updates, ids[index], &st, AT_SYMLINK_NOFOLLOW), missing = rc && errno == ENOENT;
    close(updates); return missing;
}
struct component { uint32_t code; int known, active; };
static struct component observe_component(int index, uint64_t deadline) {
    struct component value = {0, 0, 0}; char apk[PATH_MAX];
    value.known = installed_code(index, &value.code, apk, sizeof(apk), deadline) == 0;
    if (value.known && value.code)
        value.active = index == 2 ? vector_enabled_at(apk, deadline) : magisk_enabled(index);
    return value;
}
static int read_small(int dir, const char *name, unsigned char *bytes, size_t max, size_t *length) {
    int fd = openat(dir, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW); struct stat s;
    if (fd < 0 || regular(fd, max, &s) || s.st_size < 1) { if (fd >= 0) close(fd); return -1; }
    ssize_t n = pread(fd, bytes, (size_t)s.st_size, 0); close(fd); if (n != s.st_size) return -1;
    *length = (size_t)n; return 0;
}
static int install_apk(const char *path, const char *package, int downgrade) {
    char *words[] = {"install", "-r", "-p", (char *)package, (char *)path, NULL};
    char *back[] = {"install", "-r", "-d", "-p", (char *)package, (char *)path, NULL};
    return pm_execute(downgrade ? "rollback-apk" : "install-apk", downgrade ? back : words);
}
static const char *stage_module(struct request *r) {
    if (!journal.active || (journal.phase != PH_BEGIN && journal.phase != PH_STAGED)) return "ERR no-transaction";
    int dir = open_dir_at(root_fd, "transaction"); if (dir < 0) return "ERR io";
    const char *names[] = {"manifest.new", "signature.new", "package.new"}; uint64_t limits[] = {META_MAX, SIG_MAX, PACKAGE_MAX};
    for (int i = 0; i < 3; i++) { unlinkat(dir, names[i], 0); if (copy_fd(r->fds[i], dir, names[i], limits[i])) { close(dir); return "ERR too-big"; } }
    unsigned char manifest[META_MAX], sig[SIG_MAX]; size_t mlen, slen; up_module_manifest value;
    if (read_small(dir, names[0], manifest, sizeof(manifest), &mlen) || read_small(dir, names[1], sig, sizeof(sig), &slen)) { close(dir); return "ERR io"; }
    int verified = up_verify_manifest(manifest, mlen, sig, slen, up_system_keys, UP_SYSTEM_KEY_COUNT, &value);
    if (verified) { close(dir); return verified == -3 ? "ERR unknown-id" : verified == -4 ? "ERR too-big" : verified == -1 ? "ERR bad-signature" : "ERR bad-manifest"; }
    int index; for (index = 0; index < MODULES; index++) if (!strcmp(value.id, ids[index])) break;
    if (index == MODULES) { close(dir); return "ERR unknown-id"; }
    uint32_t installed = 0; char installed_apk[PATH_MAX];
    if (installed_code(index, &installed, installed_apk, sizeof(installed_apk), probe_deadline())) { close(dir); return "ERR installed-state"; }
    if (value.code <= journal.floor[index] || value.code <= installed || (journal.changed & (1u << index))) { close(dir); return "ERR old"; }
    int package = openat(dir, names[2], O_RDONLY | O_CLOEXEC | O_NOFOLLOW); unsigned char hash[32];
    if (package < 0 || up_sha256_fd(package, value.size, hash) || memcmp(hash, value.sha256, 32)) { if (package >= 0) close(package); close(dir); return "ERR bad-hash"; }
    close(package);
    const int vector = !strcmp(value.kind, "vector-apk");
    if (vector && strcmp(value.id, "thinkos-immersive")) { close(dir); return "ERR unknown-id"; }
    if (vector && !journal.vector_present) {
        unlinkat(dir, "immersive.apk", 0); unlinkat(dir, "vector.db", 0);
        if (save_package(dir, IMMERSIVE, "immersive.apk") || backup_vector()) { close(dir); return "ERR vector-unavailable"; }
        journal.vector_present = 1;
    }
    char leaf[64], path[PATH_MAX]; snprintf(leaf, sizeof(leaf), "%s.payload", value.id);
    if (renameat(dir, names[2], dir, leaf) || fsync(dir) || transaction_path(path, sizeof(path), leaf)) { close(dir); return "ERR io"; }
    journal.changed |= 1u << index; journal.target[index] = value.code; journal.phase = PH_INSTALLING;
    if (save_state()) { close(dir); return "ERR io"; }
    int rc;
    if (vector) {
        rc = install_apk(path, IMMERSIVE, 0);
        if (!rc) {
            char apk[PATH_MAX], sql[PATH_MAX + 200], output[128];
            rc = package_path(IMMERSIVE, apk, sizeof(apk));
            if (!rc) {
                snprintf(sql, sizeof(sql), "UPDATE modules SET apk_path='%s', enabled=1 WHERE module_pkg_name='%s'; SELECT changes();", apk, IMMERSIVE);
                rc = sqlite("vector-sync", sql, output, sizeof(output));
                if (!rc && strcmp(output, "1\n") && strcmp(output, "1\r\n")) rc = -1;
            }
        }
    } else {
        /* This API27 ROM exposes the verified root-owned /sbin/magisk link to
         * magisk32. Keep the executable absolute; never search PATH or a shell. */
        char *args[] = {"/sbin/magisk", "--install-module", path, NULL};
        rc = execute("install-module", args[0], args, NULL, 0);
    }
    journal.phase = rc ? PH_FAILED : PH_STAGED; if (rc) journal.result = RESULT_FAILED;
    if (save_state()) rc = -1;
    close(dir); return rc ? "ERR install-failed" : "OK";
}
static const char *install_application(struct request *r) {
    if (!journal.active || (journal.phase != PH_BEGIN && journal.phase != PH_STAGED)) return "ERR no-transaction";
    if (journal.apk_changed) return "ERR busy";
    int dir = open_dir_at(root_fd, "transaction"); if (dir < 0) return "ERR io";
    if (copy_fd(r->fds[0], dir, "application.apk", APK_MAX) || fsync(dir)) { close(dir); return "ERR too-big"; }
    char path[PATH_MAX]; if (transaction_path(path, sizeof(path), "application.apk")) { close(dir); return "ERR io"; }
    journal.apk_changed = 1; journal.phase = PH_INSTALLING;
    if (save_state()) { close(dir); return "ERR io"; }
    int rc = install_apk(path, APP, 0);
    journal.phase = rc ? PH_FAILED : PH_STAGED; if (rc) journal.result = RESULT_FAILED;
    if (save_state()) rc = -1;
    close(dir); return rc ? "ERR install-failed" : "OK";
}
static int restore_modules(int dir) {
    int modules = open_owned_dir(modules_path);
    int updates = open_owned_dir(updates_path);
    if (updates < 0 && errno != ENOENT) { if (modules >= 0) close(modules); return -1; }
    if (modules < 0) { if (updates >= 0) close(updates); return -1; }
    int rc = 0; copied_bytes = 0; copied_files = 0;
    for (int i = 0; i < MODULES; i++) if (journal.changed & (1u << i)) {
        if (i == 2) continue; /* Vector is restored separately. */
        if (updates >= 0 && remove_tree(updates, ids[i], 0)) { rc = -1; break; }
        char temporary[80], previous[80];
        snprintf(temporary, sizeof(temporary), ".thinkos-restore-%s", ids[i]);
        snprintf(previous, sizeof(previous), ".thinkos-old-%s", ids[i]);
        struct stat old;
        if (!fstatat(modules, previous, &old, AT_SYMLINK_NOFOLLOW) || errno != ENOENT || remove_tree(modules, temporary, 0)) { rc = -1; break; }
        if (journal.originals & (1u << i)) {
            int source = open_dir_at(dir, ids[i]), target = make_dir_at(modules, temporary);
            if (source < 0 || target < 0 || copy_tree(source, target, 0)) rc = -1;
            if (source >= 0) close(source);
            if (target >= 0) close(target);
            if (rc) break;
        }
        int moved = !renameat(modules, ids[i], modules, previous);
        if (!moved && errno != ENOENT) { rc = -1; break; }
        if ((journal.originals & (1u << i)) && renameat(modules, temporary, modules, ids[i])) {
            if (moved) (void)renameat(modules, previous, modules, ids[i]);
            rc = -1; break;
        }
        if (fsync(modules) || (moved && remove_tree(modules, previous, 0))) { rc = -1; break; }
    }
    if (!rc && fsync(modules)) rc = -1;
    if (updates >= 0) { if (!rc && fsync(updates)) rc = -1; close(updates); }
    close(modules); return rc;
}
static const char *rollback_internal(int modules_already_restored) {
    if (!journal.active) return "ERR no-transaction";
    if (journal.rollback_attempted) return "ERR already-rolled-back";
    journal.rollback_attempted = 1; journal.phase = PH_ROLLING_BACK;
    if (save_state()) return "ERR io";
    int dir = open_dir_at(root_fd, "transaction"); if (dir < 0) return "ERR backup-missing";
    int rc = modules_already_restored ? 0 : restore_modules(dir); char path[PATH_MAX];
    if (!rc && journal.vector_present) {
        if (transaction_path(path, sizeof(path), "immersive.apk") || install_apk(path, IMMERSIVE, 1)) rc = -1;
        char sql[PATH_MAX + 30];
        if (!rc && !transaction_path(path, sizeof(path), "vector.db")) {
            snprintf(sql, sizeof(sql), ".restore '%s'", path); rc = sqlite("vector-restore", sql, NULL, 0);
            /* pm replacement changes /data/app paths even when restoring an old APK. */
            char apk[PATH_MAX], statement[PATH_MAX + 200], output[128];
            if (!rc) rc = package_path(IMMERSIVE, apk, sizeof(apk));
            if (!rc) {
                snprintf(statement, sizeof(statement), "UPDATE modules SET apk_path='%s' WHERE module_pkg_name='%s'; SELECT changes();", apk, IMMERSIVE);
                rc = sqlite("vector-sync", statement, output, sizeof(output));
                if (!rc && strcmp(output, "1\n") && strcmp(output, "1\r\n")) rc = -1;
            }
        }
        else rc = -1;
    }
    if (!rc && journal.apk_changed) {
        if (transaction_path(path, sizeof(path), "previous.apk") || install_apk(path, APP, 1)) rc = -1;
    }
    close(dir);
    journal.phase = rc ? PH_FAILED : PH_IDLE; journal.active = rc ? 1 : 0; journal.result = rc ? RESULT_FAILED : RESULT_ROLLED_BACK;
    if (save_state()) return "ERR io";
    return rc ? "ERR rollback-failed" : "OK";
}
static const char *rollback(void) { return rollback_internal(0); }
static int boot_id(char out[40]) {
#ifdef UPDATER_HOST_TEST
    if (test_boot) {
        if (!test_boot[0] || strlen(test_boot) > 39) return -1;
        strcpy(out, test_boot); return 0;
    }
#endif
    int fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
#ifdef UPDATER_HOST_TEST
        if (test_exec) { snprintf(out, 40, "host-test-boot"); return 0; }
#endif
        return -1;
    }
    ssize_t n = read(fd, out, 39); close(fd); if (n < 1) return -1;
    if (out[n - 1] == '\n') n--;
    out[n] = 0; return 0;
}
static const char *reboot_request(void) {
    if (!journal.active || journal.phase != PH_STAGED || !journal.changed) return "ERR no-transaction";
    char boot[40] = {0}; if (boot_id(boot)) return "ERR clock";
    uint64_t now = mono_ms(), wall = (uint64_t)time(NULL);
    if (journal.reboot_wall) {
        if (!strcmp(boot, journal.reboot_boot)) { if (now < journal.reboot_mono || now - journal.reboot_mono < 3600000) return "ERR rate-limited"; }
        else if (now < 3600000 || wall < journal.reboot_wall || wall - journal.reboot_wall < 3600) return "ERR rate-limited";
    }
    journal.reboot_wall = wall; journal.reboot_mono = now; memcpy(journal.reboot_boot, boot, sizeof(boot)); journal.phase = PH_REBOOT;
    if (save_state()) return "ERR io";
    char *args[] = {"/system/bin/reboot", NULL};
    if (execute("reboot", args[0], args, NULL, 0)) return "ERR reboot-failed";
    return "OK";
}
static int valid_transaction_id(const char *value) {
    if (strlen(value) != 32) return 0;
    for (size_t i = 0; i < 32; ++i) if (!((value[i] >= '0' && value[i] <= '9') || (value[i] >= 'a' && value[i] <= 'f'))) return 0;
    return 1;
}
static int guard_number(const char *value, uint64_t minimum, uint64_t maximum, uint64_t *out) {
    size_t n = strlen(value); if (!n || n > 10 || (n > 1 && value[0] == '0')) return -1;
    uint64_t number = 0;
    for (size_t i = 0; i < n; ++i) { if (value[i] < '0' || value[i] > '9') return -1; number = number * 10 + (unsigned)(value[i] - '0'); }
    if (number < minimum || number > maximum) return -1;
    *out = number; return 0;
}
/* No policy fallback: defaults are rendered from settings schema by
 * system/gen_guard_policy.py and provisioned with the external guard. */
static int guard_limits(uint64_t *health, uint64_t *poll_ms) {
    int directory = open_dir_at(root_fd, "guard"); if (directory < 0) return -1;
    int fd = openat(directory, "policy", O_RDONLY | O_CLOEXEC | O_NOFOLLOW); close(directory);
    if (fd < 0) return -1;
    struct stat s; char bytes[128];
    int rc = fstat(fd, &s) || !S_ISREG(s.st_mode) || s.st_uid != getuid() || (s.st_mode & 0077) || s.st_size < 1 || s.st_size >= (off_t)sizeof(bytes);
    ssize_t n = rc ? -1 : pread(fd, bytes, (size_t)s.st_size, 0); close(fd);
    if (rc || n != s.st_size) return -1;
    bytes[n] = 0;
    for (ssize_t i = 0; i < n; ++i) if ((bytes[i] < 32 && bytes[i] != '\n') || (unsigned char)bytes[i] > 126) return -1;
    const char prefix[] = "thinkos-guard-policy 1\nhealth_ms=";
    if (strncmp(bytes, prefix, sizeof(prefix) - 1)) return -1;
    char *first = bytes + sizeof(prefix) - 1, *line = strchr(first, '\n');
    if (!line) return -1;
    *line++ = 0;
    if (strncmp(line, "poll_ms=", 8)) return -1;
    char *second = line + 8, *end = strchr(second, '\n');
    if (!end || end[1]) return -1;
    *end = 0;
    if (guard_number(first, 60000, 86400000, health) || guard_number(second, 1000, 60000, poll_ms) ||
        *health % 60000 || *poll_ms % 1000) return -1;
    return 0;
}
static int guard_load(struct up_guard_record *record) {
    memset(record, 0, sizeof(*record));
    int fd = openat(root_fd, "guard-state.bin", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    struct stat s;
    int rc = fstat(fd, &s) || !S_ISREG(s.st_mode) || s.st_uid != getuid() || (s.st_mode & 0077) ||
        s.st_size != (off_t)sizeof(*record) || pread_exact(fd, record, sizeof(*record), 0);
    close(fd);
    if (rc || memcmp(record->magic, "TOSGRD1", 8) || record->version != 1 ||
        record->checksum != up_guard_checksum(record) || record->transaction[32] || record->boot[39] ||
        (record->transaction[0] && !valid_transaction_id(record->transaction)) || !record->boot[0] ||
        record->boot_completed > 1 || record->armed > 1 || record->early_started > 1 || record->early_done > 1 ||
        record->automatic_started > 1 || record->reboot_attempted > 1 || (record->early_done && !record->early_started) ||
        record->health_ms < 60000 || record->health_ms > 86400000) return -1;
    return 0;
}
static int guard_save(struct up_guard_record *record) {
    record->checksum = up_guard_checksum(record);
    unlinkat(root_fd, "guard-state.new", 0);
    int fd = openat(root_fd, "guard-state.new", O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return -1;
    int rc = write_all(fd, record, sizeof(*record)) || fsync(fd); close(fd);
    return rc || renameat(root_fd, "guard-state.new", root_fd, "guard-state.bin") || fsync(root_fd) ? -1 : 0;
}
static int boot_completed(void) {
    char value[16]; return !setup_property("sys.boot_completed", value, sizeof(value)) && !strcmp(value, "1");
}
/* Must hold updater.lock. Rechecks both the root journal and its private guard
 * deadline; a token from a former transaction can never roll back a new BEGIN. */
static const char *guard_rollback(const char *token) {
    if (!valid_transaction_id(token)) return "ERR bad-transaction";
    if (!journal.active || strcmp(journal.transaction, token)) return "ERR no-transaction";
    struct up_guard_record record; uint64_t health, poll_ms; char boot[40] = {0};
    if (guard_limits(&health, &poll_ms) || guard_load(&record) || boot_id(boot)) return "ERR guard-state";
    if (!record.magic[0]) return "ERR guard-state";
    if (journal.rollback_attempted || record.automatic_started || record.reboot_attempted) return "ERR already-rolled-back";
    if (record.health_ms != health || !up_guard_due(&record, token, boot, mono_ms(), boot_completed())) return "ERR not-due";
    record.automatic_started = 1;
    if (guard_save(&record)) return "ERR io";
    const char *result = rollback_internal(record.early_done);
    if (strcmp(result, "OK")) return result;
    record.reboot_attempted = 1;
    if (guard_save(&record)) return "ERR io";
    journal.reboot_wall = (uint64_t)time(NULL); journal.reboot_mono = mono_ms();
    memcpy(journal.reboot_boot, boot, sizeof(boot));
    if (save_state()) return "ERR io";
    char *args[] = {"/system/bin/reboot", NULL};
    if (execute("guard-reboot", args[0], args, NULL, 0)) {
        journal.result = RESULT_FAILED; journal.phase = PH_FAILED; (void)save_state();
        return "ERR reboot-failed";
    }
    return "OK";
}
static int guard_early_restore(struct up_guard_record *record) {
    int directory = open_dir_at(root_fd, "transaction"); if (directory < 0) return -1;
    int rc = restore_modules(directory);
    if (!rc && journal.vector_present) {
        char output[128];
        rc = sqlite("guard-vector-disable", "UPDATE modules SET enabled=0 WHERE module_pkg_name='moe.kiwi.thinkos.immersive'; SELECT changes();", output, sizeof(output));
        if (!rc && strcmp(output, "1\n") && strcmp(output, "1\r\n")) rc = -1;
    }
    close(directory);
    if (rc) { journal.result = RESULT_FAILED; journal.phase = PH_FAILED; (void)save_state(); return -1; }
    record->early_done = 1;
    return guard_save(record);
}
static int guard_tick_locked(int early, char requested[33], uint64_t *poll_ms) {
    requested[0] = 0;
    uint64_t health; struct up_guard_record record; char boot[40] = {0};
    if (guard_limits(&health, poll_ms) || guard_load(&record) || boot_id(boot)) return -1;
    int completed = boot_completed();
    if (early && completed) return -1;
    struct up_guard_observation observation = {journal.transaction, boot, mono_ms(), journal.active,
        journal.phase == PH_REBOOT || journal.apk_changed || (journal.changed && record.boot[0] && strcmp(record.boot, boot)),
        completed, journal.rollback_attempted};
    enum up_guard_action action = up_guard_step(&record, &observation, health);
    if (action == UP_GUARD_STOP) return 0;
    if (action == UP_GUARD_EARLY && !early) {
        /* A late watcher must not pretend the module files were restored before
         * mounts. Preserve the claim and stop for explicit recovery. */
        return guard_save(&record) ? -1 : 0;
    }
    if (action == UP_GUARD_SAVE || action == UP_GUARD_EARLY || action == UP_GUARD_ROLLBACK)
        if (guard_save(&record)) return -1;
    if (action == UP_GUARD_EARLY) return guard_early_restore(&record);
    if (action == UP_GUARD_ROLLBACK && !early) memcpy(requested, journal.transaction, 33);
    return 0;
}
static int guard_request(const char *token) {
    struct sockaddr_un address; memset(&address, 0, sizeof(address)); address.sun_family = AF_UNIX;
    socklen_t length;
    if (listen_name[0] == '@') {
#ifdef __linux__
        size_t n = strlen(listen_name + 1); if (n >= sizeof(address.sun_path) - 1) return -1;
        memcpy(address.sun_path + 1, listen_name + 1, n); length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + n + 1);
#else
        return -1;
#endif
    } else {
#ifdef UPDATER_HOST_TEST
        if (strlen(listen_name) >= sizeof(address.sun_path)) return -1;
        strcpy(address.sun_path, listen_name); length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + strlen(address.sun_path) + 1);
#else
        return -1;
#endif
    }
    int fd = socket(AF_UNIX, SOCK_STREAM, 0); if (fd < 0) return -1;
    struct timeval timeout = {2, 0}; (void)setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    if (connect(fd, (struct sockaddr *)&address, length) || !root_peer(fd)) { close(fd); return -1; }
    char request[64]; int n = snprintf(request, sizeof(request), "GUARD_ROLLBACK %s\n", token);
    if (n < 0 || write_all(fd, request, (size_t)n)) { close(fd); return -1; }
    char reply[128]; size_t used = 0;
    while (used < sizeof(reply) - 1) {
        ssize_t count = read(fd, reply + used, 1);
        if (count != 1) { close(fd); return -1; }
        if (reply[used++] == '\n') { reply[used] = 0; close(fd); return !strcmp(reply, "ERR unknown\n") ? -1 : 0; }
    }
    close(fd); return -1;
}
static int guard_run(int early, int once) {
    do {
        int lock = operation_lock(); if (lock < 0) return 1;
        char requested[33]; uint64_t poll_ms = 0;
        int rc = load_state() || guard_tick_locked(early, requested, &poll_ms);
        close(lock);
        if (rc) return 1;
        if (requested[0] && guard_request(requested)) {
            /* A broken updater module must not disable recovery of itself. The
             * stable external binary takes the same lock and rechecks state. */
            lock = operation_lock(); if (lock < 0) return 1;
            if (load_state()) { close(lock); return 1; }
            const char *result = guard_rollback(requested);
            fprintf(stderr, "ThinkOS guard recovery: %s\n", result); close(lock);
        }
        if (early || once) return 0;
        struct timespec wait = {(time_t)(poll_ms / 1000), (long)(poll_ms % 1000) * 1000000};
        while (nanosleep(&wait, &wait) && errno == EINTR && !stopping) {}
    } while (!stopping);
    return 0;
}
static const char *healthy_request(void) {
    if (!journal.active || (journal.phase != PH_STAGED && journal.phase != PH_REBOOT)) return "ERR no-transaction";
    if (journal.changed) {
        char boot[40] = {0}, complete[16];
        if (journal.phase != PH_REBOOT || !journal.reboot_wall || !journal.reboot_boot[0] ||
                boot_id(boot) || !strcmp(boot, journal.reboot_boot) ||
                setup_property("sys.boot_completed", complete, sizeof(complete)) || strcmp(complete, "1"))
            return "ERR not-active";
        uint64_t deadline = probe_deadline();
        for (int i = 0; i < MODULES; ++i) if (journal.changed & (1u << i)) {
            struct component value = observe_component(i, deadline);
            if (!value.known || value.code != journal.target[i] || !value.active) return "ERR not-active";
        }
    } else if (journal.phase != PH_STAGED || !journal.apk_changed) return "ERR no-transaction";
    /* Only actual post-reboot component evidence may advance anti-rollback floors.
     * APK-only transactions retain their original no-reboot completion path. */
    for (int i = 0; i < MODULES; ++i) if (journal.changed & (1u << i)) journal.floor[i] = journal.target[i];
    journal.active = 0; journal.phase = PH_IDLE; journal.result = RESULT_HEALTHY;
    if (save_state()) return "ERR io";
    return "OK";
}
static void status(int fd) {
    char out[3072]; int used = snprintf(out, sizeof(out), "OK {\"protocol\":1,\"keys\":%u,\"transaction\":%s,\"id\":\"%s\",\"phase\":%u,\"result\":%u,\"rollback_attempted\":%s,\"modules\":[", (unsigned)UP_SYSTEM_KEY_COUNT, journal.active ? "true" : "false", journal.transaction, journal.phase, journal.result, journal.rollback_attempted ? "true" : "false");
    uint64_t deadline = probe_deadline();
    for (int i = 0; i < MODULES; i++) {
        struct component value = observe_component(i, deadline);
        used += snprintf(out + used, sizeof(out) - (size_t)used, "%s{\"id\":\"%s\",\"code\":%ld,\"floor\":%u,\"staged\":%u,\"active\":%s}", i ? "," : "", ids[i], value.known ? (long)value.code : -1L, journal.floor[i], journal.target[i], value.active ? "true" : "false");
    }
    snprintf(out + used, sizeof(out) - (size_t)used, "],\"setup\":{\"timezone\":%s,\"language\":%s,\"device_owner\":false}}", setup_api27() ? "true" : "false", setup_api27() ? "true" : "false"); answer(fd, out);
}
static void serve(int fd) {
    if (!allowed_peer(fd)) { answer(fd, "ERR denied"); return; }
    struct request r;
    if (receive_request(fd, &r)) { close_request(&r); answer(fd, "ERR bad-request"); return; }
    unsigned expected = !strcmp(r.line, "STAGE") ? 3 : !strcmp(r.line, "INSTALL_APK") ? 1 : 0;
    if (r.count != expected) { close_request(&r); answer(fd, "ERR bad-fds"); return; }
    int lock = operation_lock();
    if (lock < 0 || load_state()) { if (lock >= 0) close(lock); close_request(&r); answer(fd, "ERR io"); return; }
    if (!strcmp(r.line, "HELLO")) answer(fd, "OK thinkos-updater 1");
    else if (!strcmp(r.line, "STATUS")) status(fd);
    else if (!strncmp(r.line, "GUARD_ROLLBACK ", 15)) answer(fd, root_peer(fd) ? guard_rollback(r.line + 15) : "ERR denied");
    else if (!strcmp(r.line, "DEVICE_OWNER_ON")) answer(fd, "ERR unavailable");
    else if (!strcmp(r.line, "GRANT_LANGUAGE")) answer(fd, connection_alive(fd) ? grant_language() : "ERR cancelled");
    else if (!strncmp(r.line, "SET_TIMEZONE ", 13)) answer(fd, connection_alive(fd) ? set_timezone_request(r.line + 13) : "ERR cancelled");
    else if (strcmp(r.line, "BEGIN") && strcmp(r.line, "STAGE") && strcmp(r.line, "INSTALL_APK") && strcmp(r.line, "REBOOT") && strcmp(r.line, "HEALTHY") && strcmp(r.line, "ROLLBACK")) answer(fd, "ERR unknown");
    else if (UP_SYSTEM_KEY_COUNT < 1 || UP_SYSTEM_KEY_COUNT > 2) answer(fd, "ERR keys-missing");
    else if (state_broken) answer(fd, "ERR io");
    else if (!strcmp(r.line, "BEGIN")) answer(fd, begin_transaction());
    else if (!strcmp(r.line, "STAGE")) answer(fd, stage_module(&r));
    else if (!strcmp(r.line, "INSTALL_APK")) answer(fd, install_application(&r));
    else if (!strcmp(r.line, "ROLLBACK")) answer(fd, rollback());
    else if (!strcmp(r.line, "REBOOT")) answer(fd, reboot_request());
    else {
        const char *result = healthy_request(); answer(fd, result);
        /* Reply within the shared probe budget; large private backup cleanup is
         * allowed only after the durable success has already reached the client. */
        if (!strcmp(result, "OK")) { (void)remove_tree(root_fd, "transaction", 0); (void)fsync(root_fd); }
    }
    close_request(&r);
    close(lock);
}
static int listener(void) {
    struct sockaddr_un addr; memset(&addr, 0, sizeof(addr)); addr.sun_family = AF_UNIX; socklen_t size;
    if (listen_name[0] == '@') {
#ifdef __linux__
        size_t n = strlen(listen_name + 1); if (n >= sizeof(addr.sun_path) - 1) return -1;
        memcpy(addr.sun_path + 1, listen_name + 1, n); size = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
#else
        return -1;
#endif
    } else {
#ifdef UPDATER_HOST_TEST
        if (strlen(listen_name) >= sizeof(addr.sun_path)) return -1;
        strcpy(addr.sun_path, listen_name); unlink(listen_name); size = sizeof(addr);
#else
        return -1;
#endif
    }
    int fd = socket(AF_UNIX, SOCK_STREAM, 0); if (fd < 0) return -1;
    (void)fcntl(fd, F_SETFD, FD_CLOEXEC);
    if (bind(fd, (struct sockaddr *)&addr, size) || listen(fd, 4)) { close(fd); return -1; }
    if (listen_name[0] != '@') (void)chmod(listen_name, 0600);
    return fd;
}
int main(int argc, char **argv) {
    int guard_mode = 0;
#ifdef UPDATER_HOST_TEST
    for (int i = 1; i < argc; i++) {
        if (i + 1 >= argc) { fprintf(stderr, "Missing host-test argument\n"); return 2; }
        const char *a = argv[i], *v = argv[++i];
        if (!strcmp(a, "--root")) root_path = v;
        else if (!strcmp(a, "--listen")) listen_name = v;
        else if (!strcmp(a, "--packages-list")) packages_path = v;
        else if (!strcmp(a, "--modules")) modules_path = v;
        else if (!strcmp(a, "--modules-update")) updates_path = v;
        else if (!strcmp(a, "--vector-db")) vector_path = v;
        else if (!strcmp(a, "--test-exec")) test_exec = v;
        else if (!strcmp(a, "--test-peer-uid")) { char *end; long n = strtol(v, &end, 10); if (*end || n < 0 || n > INT_MAX) return 2; test_peer = (int)n; }
        else if (!strcmp(a, "--test-sdk")) test_sdk = v;
        else if (!strcmp(a, "--test-tzdata")) test_tzdata = v;
        else if (!strcmp(a, "--test-boot-id")) test_boot = v;
        else if (!strcmp(a, "--test-now-ms")) { if (guard_number(v, 0, UINT32_MAX, &test_now)) return 2; }
        else if (!strcmp(a, "--guard-mode")) test_guard_mode = v;
        else { fprintf(stderr, "Unknown host-test option\n"); return 2; }
    }
    if (test_guard_mode) {
        if (!strcmp(test_guard_mode, "early")) guard_mode = 1;
        else if (!strcmp(test_guard_mode, "watch")) guard_mode = 2;
        else if (!strcmp(test_guard_mode, "tick")) guard_mode = 3;
        else return 2;
    }
#else
    if (getuid() != 0) return 2;
    if (argc == 2 && !strcmp(argv[1], "--guard-early")) guard_mode = 1;
    else if (argc == 2 && !strcmp(argv[1], "--guard-watch")) guard_mode = 2;
    else if (argc != 1) return 2;
#endif
    if (root_path[0] != '/' || strchr(root_path, '\'') || strchr(root_path, '\n')) return 2;
    umask(077);
    if (mkdir(root_path, 0700) && errno != EEXIST) return 1;
    root_fd = open(root_path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW); if (root_fd < 0) return 1;
    struct stat st; if (fstat(root_fd, &st) || st.st_uid != getuid() || (st.st_mode & 0077) || load_state()) { close(root_fd); return 1; }
    signal(SIGPIPE, SIG_IGN); signal(SIGTERM, terminate); signal(SIGINT, terminate);
    if (guard_mode) { int result = guard_run(guard_mode == 1, guard_mode == 3); close(root_fd); return result; }
    int server = listener(); if (server < 0) { close(root_fd); return 1; }
    while (!stopping) {
        struct pollfd p = {server, POLLIN, 0}; int ready = poll(&p, 1, 1000);
        if (ready < 0) { if (errno == EINTR) continue; break; }
        if (!ready) continue;
        int client = accept(server, NULL, NULL); if (client < 0) continue;
        (void)fcntl(client, F_SETFD, FD_CLOEXEC); struct timeval wait = {2, 0};
        (void)setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &wait, sizeof(wait)); serve(client); close(client);
    }
    close(server); close(root_fd);
#ifdef UPDATER_HOST_TEST
    if (listen_name[0] != '@') unlink(listen_name);
#endif
    return 0;
}
