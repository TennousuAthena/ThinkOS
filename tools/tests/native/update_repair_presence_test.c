#define _GNU_SOURCE
#include <assert.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/types.h>
#include <unistd.h>

/* Linux host-only. Never binds the production updater name. */
int up_repair_presence_test(const char *name, int timeout_ms, uid_t expected_uid);
int main(void) {
    char name[96];
    snprintf(name, sizeof(name), "thinkos-repair-host-test-%ld", (long)getpid());
    assert(up_repair_presence_test(name, 20, getuid()) == 1);
    assert(up_repair_presence_test(name, 0, getuid()) == 0);
    assert(up_repair_presence_test(name, 2001, getuid()) == 0);
    int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    assert(listener >= 0);
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address)); address.sun_family = AF_UNIX;
    memcpy(address.sun_path + 1, name, strlen(name));
    assert(bind(listener, (struct sockaddr *)&address,
        (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name))) == 0);
    assert(listen(listener, 4) == 0);
    assert(up_repair_presence_test(name, 20, getuid()) == 2);
    // A server with the wrong peer identity must never be classified as absent/usable.
    assert(up_repair_presence_test(name, 20, getuid() == 0 ? 1 : 0) == 0);
    close(listener);
    assert(up_repair_presence_test(name, 20, getuid()) == 1);
    puts("fixed endpoint presence errno/peer boundaries passed");
    return 0;
}
