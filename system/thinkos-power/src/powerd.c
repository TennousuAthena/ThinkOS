/*
 * powerd —— ThinkOS 的断电保护守护进程，随 Magisk 模块 thinkos-power 安装，以 root 运行。
 *
 * 为什么需要它：这台设备没有电池，也没有电源键，关机的办法是拔电源线。拔线时 eMMC 可能正在写，小概率损坏分区
 * （见 docs/40-power-loss.md）。板子上没有专门的“断电”信号：PMIC 的 DC 输入没接圆口电源线（dcin 只有约 0.15 V），
 * 也没有断电中断。可读的是系统主供电轨 VPH_PWR（qpnp-vadc 的 vph_pwr，单位 µV）：通电时稳定在约 3.97–4.05 V，
 * 满载也不下陷。电源线一拔，轨上的电容放电，它会往下掉。本进程盯着它，掉到阈值以下就在剩下的时间里把文件系统
 * 收拾到一致的状态：sync，再用 sysrq 的 u（紧急只读重挂载）把所有读写分区转成只读，让 eMMC 在断电时是闲的。
 *
 * 状态：
 *   监控中    每 interval 毫秒读一次 VPH_PWR；连续 debounce 次低于 trip，或者有一次低于 hard，就“触发”
 *   已触发    做完上面的收尾以后继续看：
 *               回到 recover 以上并保持 recover_ms   → 虚惊（电压只是闪了一下），干净重启，让分区回到可写
 *               掉到 off 以下，或读不到              → 用 sysrq 的 o 立刻关机（由 PMIC 有序断电，不等它自己跌落）
 *               hold_ms 以后还在中间                  → 供电不健康，关机
 *   不上膛    开始的几个样本不在预期范围（< trip + margin）：不是我们认识的板子或状态，不动作，每分钟重测
 *
 * 为什么不在应用里做：要写 /proc/sysrq-trigger，要 root；而且应用崩了、卡了，保护也得在。
 * 为什么不用 BCL（内核自带的低电压监视）：它的低阈值最高 3.2 V，离 4.0 V 太远，到那时留给收尾的时间所剩无几。
 *
 * 手动关机：应用在设置里有一个“关机”（要确认）。它连抽象 socket @thinkos-power 发一行 POWEROFF，本进程先做触发时的
 *   那套收尾（灭背光、sync、转只读），再关机，之后断电是干净的。只认 root 和 ThinkOS 应用的 uid（读 packages.list），
 *   每行最多 64 字节，20 毫秒内读完，不然丢掉；采样循环不被它拖慢。命令：HELLO、STATUS、POWEROFF 与三条背光租约命令。
 *
 * 其它：
 *   --probe       只记录电压，每行 “毫秒 µV 读取耗时µs”，不做任何动作。拔电实验用它量衰减曲线与撑多久。
 *   --replay F    不碰设备，从文件 F 按行读样本（µV 整数，x 表示读失败），虚拟时钟每行走一个 interval。主机测试用。
 *   --dry-run     照常判断并写日志，但不写 sysrq、不灭背光、不重启不关机（sync 照做，因为无害，并能量出耗时）。
 *
 * 进程自己的准备：mlockall（触发时不因缺页去读 eMMC），oom_score_adj=-1000，提前打开所有要写的文件，
 * 持有一把唤醒锁（保证系统不进入挂起而停止采样）。
 *
 * 编译：system/build-power.sh（Android armeabi-v7a）或 system/build-power.sh --host（测试用）。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifdef __linux__
#include <sched.h>
#endif
#ifdef __ANDROID__
#include <android/log.h>
#endif

#define VERSION "3"
#define APP_PACKAGE "moe.kiwi.thinkos"
#define MAX_LEDS 4
#define ARM_SAMPLES 10

static struct {
    const char *vph;             /* NULL = 自动找 */
    const char *sysrq;
    const char *mounts;
    const char *watch;           /* 触发后等它变成只读的挂载点 */
    const char *wakelock;
    const char *reboot_cmd;      /* NULL = /system/bin/reboot */
    const char *poweroff_cmd;    /* NULL = sysrq o */
    const char *leds[MAX_LEDS];
    int led_count;
    int interval_ms;
    int trip_uv, hard_uv, recover_uv, off_uv, margin_uv;
    int debounce;
    int recover_ms, hold_ms;
    int duration_s;
    int dry, probe, no_sync, trace;
    const char *replay;
    const char *socket_name;     /* 以 / 开头是文件 socket（主机测试），否则抽象 socket；NULL = 不开 */
    const char *packages;        /* 读 ThinkOS 应用 uid 的地方 */
    int allow_uid;               /* >= 0 时代替读 packages.list（主机测试） */
} cfg = {
    NULL, "/proc/sysrq-trigger", "/proc/mounts", "/data", "/sys/power/wake_lock", NULL, NULL,
    {NULL}, 0,
    20,
    3800000, 3500000, 3900000, 3400000, 100000,
    3,
    1000, 30000,
    0,
    0, 0, 0, 0,
    NULL,
    "thinkos-power", "/data/system/packages.list", -1,
};

static const char *const default_leds[] = {
    "/sys/class/leds/lcd-backlight/brightness", "/sys/class/leds/wled/brightness",
};

static int64_t start_us;
static int use_virtual;
static int64_t virtual_us;
static volatile sig_atomic_t stop_flag;
static int sysrq_fd = -1, vph_fd = -1, wake_fd = -1;
static int led_fd[MAX_LEDS];
static FILE *replay_fp;
static int socket_fd = -1;
static int manual_off_started;
static int emergency_started;

#include "backlight_lease.h"
static struct dark_lease sleep_dark = { "/sys/class/leds/lcd-backlight/brightness", 0, 0 };


static int64_t now_us(void)
{
    if (use_virtual) return virtual_us;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

static void say(const char *fmt, ...)
{
    char text[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(text, sizeof text, fmt, ap);
    va_end(ap);
    int64_t t = now_us() - start_us;
    fprintf(stderr, "[%4lld.%03lld] %s\n", (long long)(t / 1000000), (long long)(t % 1000000 / 1000), text);
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "ThinkOS-powerd", "%s", text);
#endif
}

/* 事件也写到标准输出：测试和拔电实验从那里读 */
static void event(const char *fmt, ...)
{
    char text[200];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(text, sizeof text, fmt, ap);
    va_end(ap);
    printf("EVENT %s at_ms=%lld\n", text, (long long)((now_us() - start_us) / 1000));
    fflush(stdout);
}

static void on_signal(int sig)
{
    (void)sig;
    stop_flag = 1;
}

static void write_small(const char *path, const char *text)
{
    int fd = open(path, O_WRONLY);
    if (fd < 0) return;
    ssize_t r = write(fd, text, strlen(text));
    (void)r;
    close(fd);
}

static void sleep_us(int64_t us)
{
    if (us <= 0) return;
    if (use_virtual) {
        virtual_us += us;
        return;
    }
    struct timespec ts = {(time_t)(us / 1000000), (long)(us % 1000000) * 1000};
    nanosleep(&ts, NULL);
}

/* ---- 读电压 ---- */

/* 内容像 “Result:3987864 Raw:95a8”（µV）。主机测试里也接受一个纯整数。 */
static int parse_uv(const char *text, int *uv)
{
    const char *num = strstr(text, "Result:");
    num = num ? num + 7 : text;
    char *end;
    long v = strtol(num, &end, 10);
    if (end == num || v <= 0 || v > 20000000) return 0;
    *uv = (int)v;
    return 1;
}

static int read_vph(int *uv)
{
    char buf[64];
    ssize_t n = pread(vph_fd, buf, sizeof buf - 1, 0);
    if (n <= 0) return 0;
    buf[n] = 0;
    return parse_uv(buf, uv);
}

/* 1 = 读到，0 = 读失败，-1 = 回放文件读完了。--trace 时每个样本都打一行 “T 毫秒 µV 耗时µs”（拔电实验用） */
static int next_sample(int *uv)
{
    if (!replay_fp) {
        int64_t t = now_us();
        int ok = read_vph(uv);
        if (cfg.trace) {
            if (ok) printf("T %.3f %d %lld\n", (t - start_us) / 1000.0, *uv, (long long)(now_us() - t));
            else printf("T %.3f x %lld\n", (t - start_us) / 1000.0, (long long)(now_us() - t));
            if (fflush(stdout) == EOF) stop_flag = 1;   /* 接收端没了：该退出了（--trace 只在拔电实验里用） */
        }
        return ok;
    }
    char line[64];
    if (!fgets(line, sizeof line, replay_fp)) return -1;
    if (line[0] == 'x') return 0;
    return parse_uv(line, uv);
}

/* 在 dir 的子目录里找 [sub/]vph_pwr；prefix 是子目录名要有的开头。API 27 的 bionic 没有 glob()，所以自己扫。 */
static int open_vph_under(const char *dir, const char *prefix, const char *sub)
{
    DIR *d = opendir(dir);
    if (!d) return -1;
    struct dirent *e;
    int found = -1;
    while (found < 0 && (e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, prefix, strlen(prefix)) != 0) continue;
        char path[300];
        snprintf(path, sizeof path, "%s/%s/%svph_pwr", dir, e->d_name, sub);
        int fd = open(path, O_RDONLY);
        if (fd >= 0) {
            say("reading %s", path);
            found = fd;
        }
    }
    closedir(d);
    return found;
}

static int open_vph(void)
{
    if (cfg.replay) return 0;
    if (cfg.vph) {
        vph_fd = open(cfg.vph, O_RDONLY);
        return vph_fd >= 0 ? 0 : -1;
    }
    vph_fd = open_vph_under("/sys/devices/soc", "qpnp-vadc-", "");
    if (vph_fd < 0) vph_fd = open_vph_under("/sys/class/hwmon", "hwmon", "device/");
    return vph_fd >= 0 ? 0 : -1;
}

/* ---- 判断：一个纯状态机，时间和样本都从参数进来 ---- */

enum { G_NORMAL, G_TRIPPED, G_DONE };
enum action { A_NONE, A_TRIP, A_REBOOT, A_POWEROFF };

struct guard {
    int state;
    int below;                   /* 连续低于 trip 的样本数 */
    int bad_reads;               /* 触发后连续读失败的次数 */
    int64_t tripped_us;
    int64_t recovered_us;        /* 开始连续高于 recover 的时刻，0 = 没有 */
};

static enum action guard_step(struct guard *g, int64_t now, int ok, int uv)
{
    if (g->state == G_DONE) return A_NONE;
    if (!ok) {
        if (g->state == G_TRIPPED && ++g->bad_reads * cfg.interval_ms >= 500) {
            g->state = G_DONE;
            return A_POWEROFF;
        }
        return A_NONE;
    }
    g->bad_reads = 0;
    if (g->state == G_NORMAL) {
        if (uv <= cfg.hard_uv || (uv < cfg.trip_uv && ++g->below >= cfg.debounce)) {
            g->state = G_TRIPPED;
            g->tripped_us = now;
            g->recovered_us = 0;
            return A_TRIP;
        }
        if (uv >= cfg.trip_uv) g->below = 0;
        return A_NONE;
    }
    /* 已触发 */
    if (uv <= cfg.off_uv) {
        g->state = G_DONE;
        return A_POWEROFF;
    }
    if (uv >= cfg.recover_uv) {
        if (!g->recovered_us) g->recovered_us = now;
        if (now - g->recovered_us >= (int64_t)cfg.recover_ms * 1000) {
            g->state = G_DONE;
            return A_REBOOT;
        }
    } else {
        g->recovered_us = 0;
    }
    if (now - g->tripped_us >= (int64_t)cfg.hold_ms * 1000) {
        g->state = G_DONE;
        return A_POWEROFF;
    }
    return A_NONE;
}

/* ---- 动作 ---- */

static void sysrq(char key)
{
    if (cfg.dry) {
        say("dry-run: sysrq %c", key);
        return;
    }
    if (sysrq_fd < 0) {
        say("sysrq %c: no trigger file", key);
        return;
    }
    ssize_t r = write(sysrq_fd, &key, 1);
    if (r != 1) say("sysrq %c failed: %s", key, strerror(errno));
}

/* 挂载点 cfg.watch 是不是只读。看不到挂载表（主机测试）就当作是。 */
static int watched_is_readonly(void)
{
    FILE *f = fopen(cfg.mounts, "r");
    if (!f) return 1;
    char line[512];
    int result = 0;
    while (fgets(line, sizeof line, f)) {
        char dev[128], dir[128], type[32], opts[160];
        if (sscanf(line, "%127s %127s %31s %159s", dev, dir, type, opts) == 4 && strcmp(dir, cfg.watch) == 0)
            result = strncmp(opts, "ro", 2) == 0 && (opts[2] == 0 || opts[2] == ',');
    }
    fclose(f);
    return result;
}

/* 触发后的收尾。目标是让 eMMC 在断电的那一刻是闲的：先把脏页刷下去，再强制全部转只读（之后没有任何写）。 */
static void trip_sequence(void)
{
    int64_t t0 = now_us();
    // A low-rail event takes over the backlight; a lease must never restore it.
    emergency_started = 1;
    sleep_dark.deadline = 0;
#ifdef __linux__
    if (!cfg.replay) {
        struct sched_param sp = {50};
        sched_setscheduler(0, SCHED_FIFO, &sp);
    }
#endif
    say("power failing: starting the emergency flush");
    /* The backlight is one of the biggest loads and turning it off is a single write: do it first, so the rail holds longer for the
     * flush below, and the person sees the device has noticed. */
    for (int i = 0; i < cfg.led_count; i++) {
        if (cfg.dry) continue;
        if (led_fd[i] >= 0) {
            ssize_t r = write(led_fd[i], "0", 1);
            (void)r;
        }
    }
    say("backlight off +%lld us", (long long)(now_us() - t0));
    if (!cfg.no_sync) {
        sync();
        say("sync done +%lld us", (long long)(now_us() - t0));
    }
    sysrq('u');
    if (!cfg.dry) {
        int64_t deadline = now_us() + 500000;
        while (!watched_is_readonly() && now_us() < deadline) usleep(2000);
        say("%s %s +%lld us", cfg.watch, watched_is_readonly() ? "is read-only" : "is STILL read-write",
             (long long)(now_us() - t0));
    }
    say("flush finished +%lld us", (long long)(now_us() - t0));
}

#ifdef __ANDROID__
#define SHELL "/system/bin/sh"
#else
#define SHELL "/bin/sh"
#endif

static void run_command(const char *command, const char *fallback_path, const char *fallback_arg)
{
    if (cfg.dry) {
        say("dry-run: %s", command ? command : fallback_path);
        return;
    }
    pid_t pid = fork();
    if (pid == 0) {
        if (command)
            execl(SHELL, "sh", "-c", command, (char *)NULL);
        else
            execl(fallback_path, fallback_path, fallback_arg, (char *)NULL);
        _exit(127);
    }
    if (pid > 0) {
        int status;
        waitpid(pid, &status, 0);
    }
}

static void do_reboot(void)
{
    say("voltage is back: rebooting to get the partitions writable again");
    if (!cfg.no_sync) sync();
    run_command(cfg.reboot_cmd, "/system/bin/reboot", NULL);
}

static void do_poweroff(void)
{
    say("voltage is gone: powering off");
    if (cfg.poweroff_cmd) {
        run_command(cfg.poweroff_cmd, NULL, NULL);
    } else if (sysrq_fd >= 0 || cfg.dry) {
        sysrq('o');
    } else {
        run_command(NULL, "/system/bin/reboot", "-p");
    }
}

/* ---- 手动关机：命令 socket ---- */

/* Linux and Android in production; the host build (macOS) only needs the same behaviour for the tests. */
static int make_socket(void)
{
#ifdef __linux__
    return socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
#else
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd >= 0) { fcntl(fd, F_SETFD, FD_CLOEXEC); fcntl(fd, F_SETFL, O_NONBLOCK); }
    return fd;
#endif
}

static int accept_client(int fd)
{
#ifdef __linux__
    return accept4(fd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
#else
    int client = accept(fd, NULL, NULL);
    if (client >= 0) { fcntl(client, F_SETFD, FD_CLOEXEC); fcntl(client, F_SETFL, O_NONBLOCK); }
    return client;
#endif
}

static int peer_uid(int fd, unsigned *uid)
{
#ifdef __linux__
    struct ucred cred;
    socklen_t size = sizeof cred;
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &size)) return -1;
    *uid = cred.uid;
    return 0;
#else
    uid_t u;
    gid_t g;
    if (getpeereid(fd, &u, &g)) return -1;
    *uid = u;
    return 0;
#endif
}

static int open_command_socket(void)
{
    if (!cfg.socket_name || !cfg.socket_name[0] || cfg.replay || cfg.probe) return 0;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    socklen_t length;
    if (cfg.socket_name[0] == '/') {
        if (strlen(cfg.socket_name) >= sizeof addr.sun_path) return -1;
        strcpy(addr.sun_path, cfg.socket_name);
        unlink(cfg.socket_name);
        length = sizeof addr;
    } else {
        size_t n = strlen(cfg.socket_name);
        if (n + 1 > sizeof addr.sun_path) return -1;
        memcpy(addr.sun_path + 1, cfg.socket_name, n);
        length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
    }
    int fd = make_socket();
    if (fd < 0) return -1;
    if (bind(fd, (struct sockaddr *)&addr, length) || listen(fd, 4)) {
        close(fd);
        return -1;
    }
    if (cfg.socket_name[0] == '/') chmod(cfg.socket_name, 0600);
    socket_fd = fd;
    return 0;
}

/* ThinkOS 应用的 uid：packages.list 的行是 "包名 uid debuggable dataDir seinfo gids"。 */
static int app_uid(void)
{
    if (cfg.allow_uid >= 0) return cfg.allow_uid;
    FILE *f = fopen(cfg.packages, "r");
    if (!f) return -1;
    char line[512];
    int uid = -1;
    size_t n = strlen(APP_PACKAGE);
    while (fgets(line, sizeof line, f)) {
        if (!strncmp(line, APP_PACKAGE " ", n + 1)) {
            long v = strtol(line + n + 1, NULL, 10);
            if (v > 0 && v < 1000000) uid = (int)v;
            break;
        }
    }
    fclose(f);
    return uid;
}

static int peer_allowed(int fd)
{
    unsigned uid;
    if (peer_uid(fd, &uid)) return 0;
    if (uid == 0) return 1;
    int app = app_uid();
    return app > 0 && (int)uid == app;
}

static void reply(int fd, const char *text)
{
    ssize_t r = write(fd, text, strlen(text));
    (void)r;
}

/* 先做触发时那套收尾（灭背光、sync、只读），再关机。之后拔线时 eMMC 是闲的。 */
static void manual_poweroff(void)
{
    event("manual-poweroff");
    say("power off requested by the app");
    trip_sequence();
    do_poweroff();
}

/* 每个采样周期调用一次：接受连接，20 毫秒内读完一行，答复。不阻塞采样。 */
static void service_socket(void)
{
    if (manual_off_started) return;
    dark_expire(&sleep_dark, now_us());
    if (socket_fd < 0) return;
    for (int served = 0; served < 4; served++) {
        int fd = accept_client(socket_fd);
        if (fd < 0) return;
        char line[64];
        size_t used = 0;
        struct timespec t0;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        while (used < sizeof line - 1) {
            struct timespec t1;
            clock_gettime(CLOCK_MONOTONIC, &t1);
            long waited = (t1.tv_sec - t0.tv_sec) * 1000 + (t1.tv_nsec - t0.tv_nsec) / 1000000;
            if (waited >= 20) break;
            struct pollfd p = {fd, POLLIN, 0};
            if (poll(&p, 1, (int)(20 - waited)) <= 0) break;
            ssize_t r = read(fd, line + used, sizeof line - 1 - used);
            if (r <= 0) break;
            used += (size_t)r;
            if (memchr(line, '\n', used)) break;
        }
        line[used] = 0;
        char *end = memchr(line, '\n', used);
        if (!end) { close(fd); continue; }
        *end = 0;
        if (!peer_allowed(fd)) reply(fd, "ERR denied\n");
        else if (!strcmp(line, "HELLO")) reply(fd, "OK thinkos-power " VERSION "\n");
        else if (!strcmp(line, "STATUS")) reply(fd, "OK {\"protocol\":1,\"poweroff\":true}\n");
        else if (!strcmp(line, "DARK_BEGIN") || !strcmp(line, "DARK_KEEP") || !strcmp(line, "DARK_END")) {
            int ok = 0;
            if (!cfg.dry && !cfg.replay && !emergency_started) {
                if (!strcmp(line, "DARK_BEGIN")) ok = dark_begin(&sleep_dark, now_us());
                else if (!strcmp(line, "DARK_KEEP")) ok = dark_keep(&sleep_dark, now_us());
                else ok = dark_end(&sleep_dark);
            }
            reply(fd, ok ? "OK\n" : "ERR backlight unavailable\n");
        }
        else if (!strcmp(line, "POWEROFF")) {
            manual_off_started = 1;
            reply(fd, "OK\n");
            close(fd);
            manual_poweroff();
            return;
        } else reply(fd, "ERR unknown\n");
        close(fd);
    }
}

/* ---- 两种主循环 ---- */

static int cmp_int(const void *a, const void *b)
{
    int x = *(const int *)a, y = *(const int *)b;
    return (x > y) - (x < y);
}

static int run_probe(void)
{
    int64_t end = cfg.duration_s ? now_us() + (int64_t)cfg.duration_s * 1000000 : 0;
    while (!stop_flag && (!end || now_us() < end)) {
        int64_t t = now_us();
        int uv = 0;
        int ok = read_vph(&uv);
        int64_t took = now_us() - t;
        if (ok) {
            printf("%.3f %d %lld\n", (t - start_us) / 1000.0, uv, (long long)took);
        } else {
            printf("%.3f x %lld\n", (t - start_us) / 1000.0, (long long)took);
        }
        /* 接收端没了（管道断了）就退出，不要空转着读电压 */
        if (fflush(stdout) == EOF) return 1;
        sleep_us((int64_t)cfg.interval_ms * 1000 - took);
    }
    return 0;
}

/* 返回 0 = 正常结束（只有回放会）。上膛失败时每分钟重测。 */
static int run_guard(void)
{
    struct guard g = {G_NORMAL, 0, 0, 0, 0};
    for (;;) {
        int samples[ARM_SAMPLES], n = 0, ended = 0;
        while (n < ARM_SAMPLES && !stop_flag) {
            int uv, r = next_sample(&uv);
            if (r < 0) {
                ended = 1;
                break;
            }
            if (r > 0) samples[n++] = uv;
            service_socket();
            sleep_us((int64_t)cfg.interval_ms * 1000);
        }
        if (stop_flag || ended) return 0;
        qsort(samples, n, sizeof samples[0], cmp_int);
        int median = samples[n / 2];
        if (median >= cfg.trip_uv + cfg.margin_uv) {
            say("armed: rail reads %d uV, trips below %d uV", median, cfg.trip_uv);
            event("armed uv=%d", median);
            break;
        }
        say("not armed: rail reads %d uV, expected at least %d uV", median, cfg.trip_uv + cfg.margin_uv);
        event("refused uv=%d", median);
        if (replay_fp) return 0;
        for (int i = 0; i < 60 && !stop_flag; i++) { service_socket(); sleep(1); }
        if (stop_flag) return 0;
    }

    int64_t end = cfg.duration_s ? now_us() + (int64_t)cfg.duration_s * 1000000 : 0;
    while (!stop_flag && (!end || now_us() < end)) {
        int64_t t = now_us();
        int uv = 0;
        int r = next_sample(&uv);
        if (r < 0) return 0;
        enum action a = guard_step(&g, t, r > 0, uv);
        if (a == A_TRIP) {
            event("tripped uv=%d", uv);
            say("tripped: rail reads %d uV", uv);
            trip_sequence();
        } else if (a == A_REBOOT) {
            event("reboot uv=%d", uv);
            do_reboot();
        } else if (a == A_POWEROFF) {
            event("poweroff uv=%d", r > 0 ? uv : 0);
            do_poweroff();
        }
        service_socket();
        sleep_us((int64_t)cfg.interval_ms * 1000 - (now_us() - t));
    }
    return 0;
}

/* ---- 启动 ---- */

static int int_arg(const char *name, const char *text, int minimum, int maximum)
{
    char *end;
    long v = strtol(text, &end, 10);
    if (*text == 0 || *end || v < minimum || v > maximum) {
        fprintf(stderr, "powerd: %s needs a number from %d to %d\n", name, minimum, maximum);
        exit(2);
    }
    return (int)v;
}

static void usage(void)
{
    fprintf(stderr,
            "powerd " VERSION " - power-loss guard\n"
            "  powerd [--dry-run] [--interval-ms N] [--trip-uv N] [--hard-uv N] [--recover-uv N] [--off-uv N]\n"
            "         [--debounce N] [--recover-ms N] [--hold-ms N] [--duration S] [--no-sync] [--trace]\n"
            "         [--vph FILE] [--sysrq FILE] [--mounts FILE] [--watch DIR] [--led FILE]...\n"
            "         [--reboot-cmd CMD] [--poweroff-cmd CMD] [--wakelock FILE]\n"
            "         [--socket NAME|/PATH] [--packages FILE] [--allow-uid N] [--no-socket]\n"
            "  powerd --probe [--interval-ms N] [--duration S] [--vph FILE]\n"
            "  powerd --replay FILE ...\n");
}

int main(int argc, char **argv)
{
    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        const char *v = i + 1 < argc ? argv[i + 1] : NULL;
        if (!strcmp(a, "--probe")) cfg.probe = 1;
        else if (!strcmp(a, "--dry-run")) cfg.dry = 1;
        else if (!strcmp(a, "--no-sync")) cfg.no_sync = 1;
        else if (!strcmp(a, "--trace")) cfg.trace = 1;
        else if (!strcmp(a, "--no-socket")) cfg.socket_name = NULL;
        else if (!strcmp(a, "--help") || !strcmp(a, "-h")) { usage(); return 0; }
        else if (!v) { usage(); return 2; }
        else if (!strcmp(a, "--interval-ms")) cfg.interval_ms = int_arg(a, argv[++i], 1, 1000);
        else if (!strcmp(a, "--trip-uv")) cfg.trip_uv = int_arg(a, argv[++i], 1000000, 6000000);
        else if (!strcmp(a, "--hard-uv")) cfg.hard_uv = int_arg(a, argv[++i], 1000000, 6000000);
        else if (!strcmp(a, "--recover-uv")) cfg.recover_uv = int_arg(a, argv[++i], 1000000, 6000000);
        else if (!strcmp(a, "--off-uv")) cfg.off_uv = int_arg(a, argv[++i], 1000000, 6000000);
        else if (!strcmp(a, "--margin-uv")) cfg.margin_uv = int_arg(a, argv[++i], 0, 2000000);
        else if (!strcmp(a, "--debounce")) cfg.debounce = int_arg(a, argv[++i], 1, 100);
        else if (!strcmp(a, "--recover-ms")) cfg.recover_ms = int_arg(a, argv[++i], 0, 600000);
        else if (!strcmp(a, "--hold-ms")) cfg.hold_ms = int_arg(a, argv[++i], 1, 3600000);
        else if (!strcmp(a, "--duration")) cfg.duration_s = int_arg(a, argv[++i], 0, 86400);
        else if (!strcmp(a, "--vph")) cfg.vph = argv[++i];
        else if (!strcmp(a, "--sysrq")) cfg.sysrq = argv[++i];
        else if (!strcmp(a, "--mounts")) cfg.mounts = argv[++i];
        else if (!strcmp(a, "--watch")) cfg.watch = argv[++i];
        else if (!strcmp(a, "--wakelock")) cfg.wakelock = argv[++i];
        else if (!strcmp(a, "--reboot-cmd")) cfg.reboot_cmd = argv[++i];
        else if (!strcmp(a, "--poweroff-cmd")) cfg.poweroff_cmd = argv[++i];
        else if (!strcmp(a, "--replay")) cfg.replay = argv[++i];
        else if (!strcmp(a, "--socket")) cfg.socket_name = argv[++i];
        else if (!strcmp(a, "--packages")) cfg.packages = argv[++i];
        else if (!strcmp(a, "--allow-uid")) cfg.allow_uid = int_arg(a, argv[++i], 1, 1000000);
        else if (!strcmp(a, "--led")) {
            if (cfg.led_count >= MAX_LEDS) { usage(); return 2; }
            cfg.leds[cfg.led_count++] = argv[++i];
        } else { usage(); return 2; }
    }
    if (cfg.hard_uv >= cfg.trip_uv || cfg.off_uv >= cfg.hard_uv || cfg.recover_uv <= cfg.trip_uv) {
        fprintf(stderr, "powerd: need off < hard < trip < recover\n");
        return 2;
    }

    if (cfg.replay) {
        use_virtual = 1;
        cfg.dry = 1;
        cfg.no_sync = 1;
        replay_fp = fopen(cfg.replay, "r");
        if (!replay_fp) {
            fprintf(stderr, "powerd: cannot read %s\n", cfg.replay);
            return 2;
        }
    }
    start_us = now_us();
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGPIPE, SIG_IGN);

    if (open_vph() < 0) {
        say("no VPH_PWR to read (%s)", cfg.vph ? cfg.vph : "searched /sys/devices/soc/qpnp-vadc-*");
        return 3;
    }
    if (cfg.probe) return run_probe();

    if (!cfg.led_count)
        for (size_t i = 0; i < sizeof default_leds / sizeof default_leds[0]; i++) cfg.leds[cfg.led_count++] = default_leds[i];
    for (int i = 0; i < MAX_LEDS; i++) led_fd[i] = -1;
    if (!cfg.replay) {
        sysrq_fd = open(cfg.sysrq, O_WRONLY | O_APPEND);
        for (int i = 0; i < cfg.led_count; i++) led_fd[i] = open(cfg.leds[i], O_WRONLY);
        if (sysrq_fd < 0) say("no %s: the emergency read-only step is unavailable", cfg.sysrq);
        wake_fd = open(cfg.wakelock, O_WRONLY | O_APPEND);
        if (wake_fd >= 0) {
            ssize_t r = write(wake_fd, "thinkos-power\n", 14);
            (void)r;
        }
        write_small("/proc/self/oom_score_adj", "-1000");
#ifdef __linux__
        mlockall(MCL_CURRENT | MCL_FUTURE);
#endif
    }
    if (open_command_socket()) say("cannot open the command socket %s: no manual power off (%s)", cfg.socket_name, strerror(errno));
    say("powerd " VERSION "%s", cfg.dry ? " (dry run)" : "");
    int rc = run_guard();
    if (!manual_off_started) dark_end(&sleep_dark);
    if (socket_fd >= 0 && cfg.socket_name[0] == '/') unlink(cfg.socket_name);
    if (wake_fd >= 0) {
        write_small("/sys/power/wake_unlock", "thinkos-power\n");
    }
    return rc;
}
