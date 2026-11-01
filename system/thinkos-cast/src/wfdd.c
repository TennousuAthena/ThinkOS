/*
 * wfdd —— ThinkOS 的 Wi-Fi Display（Miracast）Sink 守护进程，随 Magisk 模块 thinkos-cast 安装，以 root 运行。
 *
 * 为什么需要它：Windows、手机靠 Wi-Fi Direct 的 probe response 里的 WFD 信息元素发现“无线显示器”。这个元素只能由
 * wpa_supplicant 带出去，而应用层的 WifiP2pManager.setWFDInfo() 需要 signature 级的 CONFIGURE_WIFI_DISPLAY，
 * ThinkOS 的 APK 没有平台签名，拿不到。所以由本进程代为向 wpa_supplicant 的控制 socket 发文本命令。
 *
 * 本进程只做三件事（一个很窄的、写死的命令集，不是通用的 wpa 代理）：
 *   SINK_ON <控制端口> <设备名>   以主 Sink 的身份出现：WFD 元素、P2P 设备名、定期监听
 *   SINK_OFF                      撤销上面的一切
 *   STATUS / HELLO                查询
 *
 * 谁可以说话：只接受 ThinkOS 应用（按 /data/system/packages.list 里的包名查 uid）与 root 的连接，用 SO_PEERCRED
 * 判定，不信任连接里自称的任何东西。请求一行一条、最长 256 字节、读超时 2 秒、一次只服务一个连接；设备名只收
 * 可见字符，不会拼出多余的 wpa 命令。
 *
 * 监听：Linux 抽象命名空间 socket（@thinkos-wfd），Mac 上的测试用文件路径。
 * 自愈：wpa_supplicant 重启（开关 Wi-Fi）会丢掉 WFD 元素，Sink 打开期间每隔几秒检查一次，掉了就重新设置。
 *
 * 编译：system/build.sh（Android armv7）或 system/build.sh --host（测试用）。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#define VERSION "1"
#define MAX_LINE 256
#define MAX_NAME 32            /* P2P 设备名最长 32 字节 */
#define MAX_UIDS 4
#define CLIENT_TIMEOUT_MS 2000
#define WPA_TIMEOUT_MS 2000
#define WPA_SHUTDOWN_TIMEOUT_MS 300   /* 退出时不为一个不应答的 wpa_supplicant 久等 */
#define DEFAULT_WPA_SOCKET "/data/misc/wifi/sockets/p2p0"
#define DEFAULT_PACKAGES "/data/system/packages.list"

static struct {
    const char *listen_name;     /* "@name" = 抽象 socket，否则文件路径 */
    const char *wpa_socket;
    int wpa_uid;                 /* 客户端 socket 要 chown 给谁（wpa_supplicant 以 wifi 运行）；-1 不改 */
    const char *packages_list;
    const char *package;
    unsigned allow_uid[MAX_UIDS];
    int allow_count;
    int listen_period_ms;
    int listen_interval_ms;
    int check_seconds;
} cfg = {
    "@thinkos-wfd", DEFAULT_WPA_SOCKET, 1010, DEFAULT_PACKAGES, "moe.kiwi.thinkos", {0}, 0, 500, 2500, 5,
};

static struct {
    int on;
    int port;
    char name[MAX_NAME + 1];
} sink;

static volatile sig_atomic_t stop_requested;
static int wpa_timeout_ms = WPA_TIMEOUT_MS;

static void say(int level, const char *fmt, ...)
{
    char text[300];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(text, sizeof(text), fmt, ap);
    va_end(ap);
#ifdef __ANDROID__
    __android_log_print(level, "ThinkOS-wfdd", "%s", text);
#else
    (void) level;
    fprintf(stderr, "wfdd: %s\n", text);
#endif
}
#ifdef __ANDROID__
#define LOG_I ANDROID_LOG_INFO
#define LOG_W ANDROID_LOG_WARN
#else
#define LOG_I 0
#define LOG_W 1
#endif

/* ---- wpa_supplicant 控制接口（UNIX 数据报） -------------------------------------------------------------------- */

/* 发一条命令，读一条回复。返回回复长度；-1 = 连不上（wpa_supplicant 不在），-2 = 没有回复。 */
static int wpa_cmd(const char *cmd, char *reply, size_t size)
{
    struct sockaddr_un local, dest;
    char dir[sizeof(local.sun_path)];
    char *slash;
    int fd, n;
    struct pollfd p;

    if (strlen(cfg.wpa_socket) >= sizeof(dest.sun_path))
        return -1;
    snprintf(dir, sizeof(dir), "%s", cfg.wpa_socket);
    slash = strrchr(dir, '/');
    if (slash)
        *slash = '\0';

    fd = socket(AF_UNIX, SOCK_DGRAM, 0);
    if (fd < 0)
        return -1;
    memset(&local, 0, sizeof(local));
    local.sun_family = AF_UNIX;
    if ((size_t) snprintf(local.sun_path, sizeof(local.sun_path), "%s/thinkos_wfdd", dir) >= sizeof(local.sun_path)) {
        close(fd);
        return -1;
    }
    unlink(local.sun_path);
    if (bind(fd, (struct sockaddr *) &local, sizeof(local)) < 0) {
        close(fd);
        return -1;
    }
    /* wpa_supplicant 以 wifi 用户运行，回复要写进这个 socket */
    if (cfg.wpa_uid >= 0 && chown(local.sun_path, (uid_t) cfg.wpa_uid, (gid_t) cfg.wpa_uid) < 0)
        say(LOG_W, "chown(%s) failed: %s", local.sun_path, strerror(errno));
    chmod(local.sun_path, 0770);

    memset(&dest, 0, sizeof(dest));
    dest.sun_family = AF_UNIX;
    snprintf(dest.sun_path, sizeof(dest.sun_path), "%s", cfg.wpa_socket);
    if (connect(fd, (struct sockaddr *) &dest, sizeof(dest)) < 0 || send(fd, cmd, strlen(cmd), 0) < 0) {
        unlink(local.sun_path);
        close(fd);
        return -1;
    }
    p.fd = fd;
    p.events = POLLIN;
    n = -2;
    if (poll(&p, 1, wpa_timeout_ms) > 0) {
        ssize_t got = recv(fd, reply, size - 1, 0);
        if (got >= 0) {
            while (got > 0 && (reply[got - 1] == '\n' || reply[got - 1] == '\r'))
                got--;
            reply[got] = '\0';
            n = (int) got;
        }
    }
    unlink(local.sun_path);
    close(fd);
    return n;
}

static int wpa_ok(const char *cmd)
{
    char reply[128];
    int n = wpa_cmd(cmd, reply, sizeof(reply));
    if (n < 0)
        return n;
    return strncmp(reply, "OK", 2) == 0 ? 0 : -3;
}

/* WFD 设备信息子元素 0：长度 0006 | 0011 = 主 Sink、会话可用 | 控制端口 | 最大吞吐 50 Mbps */
static void wfd_hex(char *out, size_t size)
{
    snprintf(out, size, "00060011%04x0032", sink.port & 0xffff);
}

/* 返回 0 或负数（-1 wpa 不在，-2 没回复，-3 命令被拒绝） */
static int apply_sink(void)
{
    char cmd[160], hex[24];
    int rc;

    wfd_hex(hex, sizeof(hex));
    if ((rc = wpa_ok("SET wifi_display 1")) < 0)
        return rc;
    snprintf(cmd, sizeof(cmd), "WFD_SUBELEM_SET 0 %s", hex);
    if ((rc = wpa_ok(cmd)) < 0)
        return rc;
    snprintf(cmd, sizeof(cmd), "SET device_name %s", sink.name);
    if ((rc = wpa_ok(cmd)) < 0)
        return rc;
    /* 空闲时周期性地听一会儿，让发送端的探测能被应答；对已连接的普通 Wi-Fi 影响很小 */
    snprintf(cmd, sizeof(cmd), "P2P_EXT_LISTEN %d %d", cfg.listen_period_ms, cfg.listen_interval_ms);
    return wpa_ok(cmd);
}

static void revoke_sink(void)
{
    wpa_ok("P2P_EXT_LISTEN");
    wpa_ok("SET wifi_display 0");
    wpa_ok("P2P_GROUP_REMOVE *");
}

/* 元素还在吗？不在（wpa_supplicant 重启过）就重新设置。 */
static void keep_sink(void)
{
    char reply[128], hex[24];
    int n;

    if (!sink.on)
        return;
    wfd_hex(hex, sizeof(hex));
    n = wpa_cmd("WFD_SUBELEM_GET 0", reply, sizeof(reply));
    if (n >= 0 && strcasecmp(reply, hex) == 0 && wpa_cmd("GET wifi_display", reply, sizeof(reply)) >= 0 && strcmp(reply, "1") == 0)
        return;
    if (n == -1)
        return;                 /* wpa_supplicant 不在，等它回来 */
    say(LOG_I, "WFD element lost (%d), setting it again", n);
    if (apply_sink() < 0)
        say(LOG_W, "could not set the WFD element again");
}

/* ---- 谁可以说话 -------------------------------------------------------------------------------------------------- */

static int peer_uid(int fd, unsigned *uid)
{
#if defined(__linux__)
    struct ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) < 0)
        return -1;
    *uid = cred.uid;
    return 0;
#else
    uid_t u;
    gid_t g;
    if (getpeereid(fd, &u, &g) < 0)
        return -1;
    *uid = u;
    return 0;
#endif
}

/* ThinkOS 的 uid 随安装而定，每次连接都重新查，应用被卸载重装也不用重启本进程 */
static int app_uid(unsigned *uid)
{
    FILE *f = fopen(cfg.packages_list, "r");
    char line[512], pkg[128];
    unsigned u;
    int found = 0;

    if (!f)
        return -1;
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "%127s %u", pkg, &u) == 2 && strcmp(pkg, cfg.package) == 0) {
            *uid = u;
            found = 1;
            break;
        }
    }
    fclose(f);
    return found ? 0 : -1;
}

static int allowed(unsigned uid)
{
    unsigned app;
    int i;

    if (uid == 0)
        return 1;
    for (i = 0; i < cfg.allow_count; i++)
        if (cfg.allow_uid[i] == uid)
            return 1;
    return app_uid(&app) == 0 && app == uid;
}

/* ---- 请求 -------------------------------------------------------------------------------------------------------- */

/* 设备名：1..32 字节，只收可见 ASCII 与 UTF-8 的高位字节；不收引号、反斜杠、反引号、美元符 */
static int valid_name(const char *name)
{
    size_t i, len = strlen(name);
    if (len < 1 || len > MAX_NAME)
        return 0;
    for (i = 0; i < len; i++) {
        unsigned char c = (unsigned char) name[i];
        if (c < 0x20 || c == 0x7f || c == '"' || c == '\\' || c == '`' || c == '$')
            return 0;
    }
    return name[0] != ' ' && name[len - 1] != ' ';
}

static void answer(int fd, const char *fmt, ...)
{
    char text[MAX_LINE + 64];
    va_list ap;
    int n;
    va_start(ap, fmt);
    n = vsnprintf(text, sizeof(text) - 1, fmt, ap);
    va_end(ap);
    if (n < 0)
        return;
    if ((size_t) n > sizeof(text) - 2)
        n = (int) sizeof(text) - 2;
    text[n++] = '\n';
    (void) !write(fd, text, (size_t) n);
}

static const char *wpa_error(int rc)
{
    return rc == -1 ? "wpa-down" : rc == -2 ? "wpa-silent" : "wpa-refused";
}

static void handle(int fd, char *line)
{
    char *arg;

    arg = strchr(line, ' ');
    if (arg)
        *arg++ = '\0';

    if (strcmp(line, "HELLO") == 0) {
        answer(fd, "OK thinkos-wfdd " VERSION);
    } else if (strcmp(line, "SINK_ON") == 0) {
        char *end, *name;
        long port;
        int rc;
        if (!arg) { answer(fd, "ERR bad-request"); return; }
        port = strtol(arg, &end, 10);
        if (end == arg || *end != ' ' || port < 1024 || port > 65535) { answer(fd, "ERR bad-port"); return; }
        name = end + 1;
        if (!valid_name(name)) { answer(fd, "ERR bad-name"); return; }
        sink.port = (int) port;
        snprintf(sink.name, sizeof(sink.name), "%s", name);
        sink.on = 1;
        rc = apply_sink();
        if (rc < 0) {
            /* 没成功也记着“应当开着”，wpa_supplicant 回来后由自愈补上 */
            say(LOG_W, "SINK_ON failed: %s", wpa_error(rc));
            answer(fd, "ERR %s", wpa_error(rc));
            return;
        }
        say(LOG_I, "sink on: control port %d, name \"%s\"", sink.port, sink.name);
        answer(fd, "OK");
    } else if (strcmp(line, "SINK_OFF") == 0) {
        if (sink.on)
            say(LOG_I, "sink off");
        sink.on = 0;
        revoke_sink();
        answer(fd, "OK");
    } else if (strcmp(line, "STATUS") == 0) {
        char reply[128];
        int n = wpa_cmd("WFD_SUBELEM_GET 0", reply, sizeof(reply));
        answer(fd, "OK sink=%s port=%d wpa=%s wfd=%s name=%s", sink.on ? "on" : "off", sink.port,
               n >= 0 ? "up" : "down", n >= 0 && reply[0] ? reply : "-", sink.name);
    } else {
        answer(fd, "ERR unknown");
    }
}

static long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/* 读一行；超时（整行共用一个期限，一个字节一个字节地滴也拖不久）、过长、对端先关闭都算失败 */
static int read_line(int fd, char *line, size_t size)
{
    size_t used = 0;
    long deadline = now_ms() + CLIENT_TIMEOUT_MS;
    while (used < size - 1) {
        struct pollfd p = { fd, POLLIN, 0 };
        long left = deadline - now_ms();
        char c;
        ssize_t n;
        if (left <= 0 || poll(&p, 1, (int) left) <= 0)
            return -1;
        n = read(fd, &c, 1);
        if (n <= 0)
            return -1;
        if (c == '\n') {
            if (used && line[used - 1] == '\r')
                used--;
            line[used] = '\0';
            return 0;
        }
        line[used++] = c;
    }
    return -1;
}

static void serve(int fd)
{
    unsigned uid = 0;
    char line[MAX_LINE];

    if (peer_uid(fd, &uid) < 0 || !allowed(uid)) {
        say(LOG_W, "refused a connection from uid %u", uid);
        answer(fd, "ERR denied");
        return;
    }
    if (read_line(fd, line, sizeof(line)) < 0) {
        answer(fd, "ERR bad-request");
        return;
    }
    handle(fd, line);
}

/* ---- 进程 -------------------------------------------------------------------------------------------------------- */

static int listen_socket(void)
{
    struct sockaddr_un addr;
    socklen_t len;
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0)
        return -1;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (cfg.listen_name[0] == '@') {
#if defined(__linux__)
        size_t n = strlen(cfg.listen_name) - 1;
        if (n >= sizeof(addr.sun_path) - 1) { close(fd); return -1; }
        memcpy(addr.sun_path + 1, cfg.listen_name + 1, n);
        len = (socklen_t) (offsetof(struct sockaddr_un, sun_path) + 1 + n);
#else
        close(fd);
        errno = ENOTSUP;
        return -1;
#endif
    } else {
        if (strlen(cfg.listen_name) >= sizeof(addr.sun_path)) { close(fd); return -1; }
        strcpy(addr.sun_path, cfg.listen_name);
        unlink(cfg.listen_name);
        len = sizeof(addr);
    }
    /* 抽象名字任何应用都能抢先绑定：绑不上就让位给 service.sh 的重试，不假装成功 */
    if (bind(fd, (struct sockaddr *) &addr, len) < 0 || listen(fd, 4) < 0) {
        close(fd);
        return -1;
    }
    if (cfg.listen_name[0] != '@')
        chmod(cfg.listen_name, 0600);
    return fd;
}

static void on_signal(int sig)
{
    (void) sig;
    stop_requested = 1;
}

static int usage(const char *me)
{
    fprintf(stderr,
            "usage: %s [--listen @name|path] [--wpa-socket path] [--wpa-uid n|-1] [--packages-list file] [--package name]\n"
            "          [--allow-uid n] [--listen-period ms] [--listen-interval ms] [--check seconds]\n", me);
    return 2;
}

int main(int argc, char **argv)
{
    int i, fd;
    time_t last_check = 0;
    struct sigaction sa;

    for (i = 1; i < argc; i += 2) {
        const char *a = argv[i];
        const char *v = i + 1 < argc ? argv[i + 1] : NULL;
        if (!v)
            return usage(argv[0]);
        if (!strcmp(a, "--listen")) cfg.listen_name = v;
        else if (!strcmp(a, "--wpa-socket")) cfg.wpa_socket = v;
        else if (!strcmp(a, "--wpa-uid")) cfg.wpa_uid = atoi(v);
        else if (!strcmp(a, "--packages-list")) cfg.packages_list = v;
        else if (!strcmp(a, "--package")) cfg.package = v;
        else if (!strcmp(a, "--allow-uid") && cfg.allow_count < MAX_UIDS) cfg.allow_uid[cfg.allow_count++] = (unsigned) strtoul(v, NULL, 10);
        else if (!strcmp(a, "--listen-period")) cfg.listen_period_ms = atoi(v);
        else if (!strcmp(a, "--listen-interval")) cfg.listen_interval_ms = atoi(v);
        else if (!strcmp(a, "--check")) cfg.check_seconds = atoi(v);
        else return usage(argv[0]);
    }
    if (cfg.listen_period_ms < 100 || cfg.listen_interval_ms < cfg.listen_period_ms || cfg.check_seconds < 1)
        return usage(argv[0]);

    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    fd = listen_socket();
    if (fd < 0) {
        say(LOG_W, "cannot listen on %s: %s", cfg.listen_name, strerror(errno));
        return 1;
    }
    say(LOG_I, "wfdd " VERSION " listening on %s, wpa %s", cfg.listen_name, cfg.wpa_socket);

    while (!stop_requested) {
        struct pollfd p = { fd, POLLIN, 0 };
        time_t now;
        int ready = poll(&p, 1, 1000);
        if (ready > 0 && (p.revents & POLLIN)) {
            int client = accept(fd, NULL, NULL);
            if (client >= 0) {
                serve(client);
                close(client);
            }
        }
        now = time(NULL);
        if (sink.on && now - last_check >= cfg.check_seconds) {
            last_check = now;
            keep_sink();
        }
    }
    if (sink.on) {
        wpa_timeout_ms = WPA_SHUTDOWN_TIMEOUT_MS;
        revoke_sink();
        say(LOG_I, "stopped: sink revoked");
    }
    close(fd);
    if (cfg.listen_name[0] != '@')
        unlink(cfg.listen_name);
    return 0;
}
