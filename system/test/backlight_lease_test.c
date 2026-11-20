#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "../thinkos-power/src/backlight_lease.h"

int main(void)
{
    char path[] = "/tmp/thinkos-backlight.XXXXXX";
    int fd = mkstemp(path);
    assert(fd >= 0);
    close(fd);
    struct dark_lease lease = {path, 0, 0};
    assert(!dark_begin(&lease, 0));
    assert(dark_write(path, 13));
    assert(dark_begin(&lease, 0) && dark_read(path) == 0);
    assert(!dark_begin(&lease, 1));
    assert(dark_keep(&lease, 5000000));
    dark_expire(&lease, DARK_LEASE_US);
    assert(dark_read(path) == 0);
    dark_expire(&lease, DARK_LEASE_US + 5000000);
    assert(dark_read(path) == 13 && !dark_keep(&lease, DARK_LEASE_US + 5000001));
    assert(dark_begin(&lease, 0) && dark_end(&lease) && dark_read(path) == 13);
    assert(dark_begin(&lease, 0) && dark_write(path, 17));
    assert(!dark_keep(&lease, 1) && dark_end(&lease) && dark_read(path) == 17);
    unlink(path);
    puts("PASS capture/blackout/release/expiry/late renewal/external brightness");
    return 0;
}
