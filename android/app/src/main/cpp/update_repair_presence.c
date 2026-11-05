#define _GNU_SOURCE
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <poll.h>
#include <time.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <jni.h>
#endif

enum { UP_REPAIR_UNKNOWN = 0, UP_REPAIR_ABSENT = 1, UP_REPAIR_PRESENT = 2 };

static int64_t elapsed_ms(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return -1;
    return (int64_t)value.tv_sec * 1000 + value.tv_nsec / 1000000;
}

/* No write/send/read/accept/SCM_RIGHTS, no subprocess, no configurable endpoint. */
static int presence(const char *name, int timeout_ms, uid_t expected_uid) {
    if (!name || !*name || strlen(name) > 100 || timeout_ms < 1 || timeout_ms > 2000) return UP_REPAIR_UNKNOWN;
    int64_t now = elapsed_ms();
    if (now < 0) return UP_REPAIR_UNKNOWN;
    const int64_t deadline = now + timeout_ms;
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return UP_REPAIR_UNKNOWN;
    int answer = UP_REPAIR_UNKNOWN;
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    const size_t length = strlen(name);
    memcpy(address.sun_path + 1, name, length);
    int result = connect(fd, (struct sockaddr *)&address,
                         (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + length));
    int error = result == 0 ? 0 : errno;
    while (error == EINPROGRESS || error == EALREADY || error == EAGAIN || error == EINTR) {
        now = elapsed_ms();
        if (now < 0 || now >= deadline) goto done;
        struct pollfd wait = { .fd = fd, .events = POLLOUT };
        result = poll(&wait, 1, (int)(deadline - now));
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0) goto done;
        socklen_t size = sizeof(error);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) != 0 || size != sizeof(error)) goto done;
    }
    if (error == ECONNREFUSED || error == ENOENT) answer = UP_REPAIR_ABSENT;
    else if (error == 0) {
        struct ucred peer;
        socklen_t size = sizeof(peer);
        memset(&peer, 0, sizeof(peer));
        if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &size) == 0 && size == sizeof(peer) && peer.uid == expected_uid)
            answer = UP_REPAIR_PRESENT;
    }
done:
    close(fd);
    return answer;
}

#ifdef __ANDROID__
JNIEXPORT jint JNICALL Java_moe_kiwi_thinkos_update_UpdateRepairPresence_nativeProbe(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return presence("thinkos-updater", 2000, 0);
}
#endif

#ifdef THINKOS_REPAIR_PRESENCE_TEST
/* Only the separate host test translation unit can call this. No production JNI args. */
int up_repair_presence_test(const char *name, int timeout_ms, uid_t expected_uid) {
    return presence(name, timeout_ms, expected_uid);
}
#endif
