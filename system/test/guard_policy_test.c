/* SPDX-License-Identifier: MIT. Pure policy tests, no device/files/executables. */
#include "../thinkos-updater/src/guard_policy.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    struct up_guard_record record = {0};
    const char *token = "0123456789abcdef0123456789abcdef";
    struct up_guard_observation o = {token, "old-boot", 1000, 1, 1, 1, 0};
    assert(up_guard_step(&record, &o, 900000) == UP_GUARD_SAVE);
    assert(!up_guard_due(&record, token, "old-boot", 900999, 1));
    assert(up_guard_due(&record, token, "old-boot", 901000, 1));
    assert(!up_guard_due(&record, "1123456789abcdef0123456789abcdef", "old-boot", 901000, 1));
    assert(!up_guard_due(&record, token, "different-boot", 901000, 1));
    assert(!up_guard_due(&record, token, "old-boot", 999, 1));
    o.boot = "trial-a"; o.boot_completed = 0; o.now = 10;
    assert(up_guard_step(&record, &o, 900000) == UP_GUARD_SAVE);
    o.boot = "trial-b";
    assert(up_guard_step(&record, &o, 900000) == UP_GUARD_EARLY);
    assert(record.early_started && !record.early_done);
    assert(up_guard_step(&record, &o, 900000) == UP_GUARD_STOP);
    record.early_done = 1; o.boot_completed = 1; o.now = 200;
    assert(up_guard_step(&record, &o, 900000) == UP_GUARD_ROLLBACK);
    assert(up_guard_due(&record, token, o.boot, o.now, 1));
    record.automatic_started = 1;
    assert(up_guard_step(&record, &o, 900000) == UP_GUARD_STOP);
    o.active = 0;
    assert(up_guard_step(&record, &o, 900000) == UP_GUARD_SAVE);
    assert(up_guard_step(&record, &o, 900000) == UP_GUARD_WAIT);
    assert(up_guard_step(&record, &o, 1) == UP_GUARD_STOP);
    assert(up_guard_step(NULL, &o, 900000) == UP_GUARD_STOP);
    puts("guard policy passed");
    return 0;
}
