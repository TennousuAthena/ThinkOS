/* SPDX-License-Identifier: MIT */
#include "guard_policy.h"
#include <stddef.h>
#include <string.h>

uint64_t up_guard_checksum(const struct up_guard_record *record) {
    const unsigned char *bytes = (const unsigned char *)record;
    uint64_t hash = UINT64_C(1469598103934665603);
    for (size_t i = 0; i < offsetof(struct up_guard_record, checksum); ++i) {
        hash ^= bytes[i]; hash *= UINT64_C(1099511628211);
    }
    return hash;
}
static void initialize(struct up_guard_record *record, const struct up_guard_observation *o) {
    memset(record, 0, sizeof(*record)); memcpy(record->magic, "TOSGRD1", 8); record->version = 1;
    if (o->active) memcpy(record->transaction, o->transaction, 33);
    memcpy(record->boot, o->boot, strlen(o->boot) + 1);
    record->boot_completed = o->boot_completed != 0;
}
int up_guard_due(const struct up_guard_record *record, const char *transaction,
    const char *boot, uint64_t now, int boot_completed) {
    if (!boot_completed || !record->boot_completed || !record->armed || record->automatic_started ||
        record->reboot_attempted || strcmp(record->transaction, transaction) || strcmp(record->boot, boot) ||
        (record->early_started && !record->early_done) || record->health_ms < 60000 || record->health_ms > 86400000 ||
        now < record->completed_mono) return 0;
    return record->early_done || now - record->completed_mono >= record->health_ms;
}
enum up_guard_action up_guard_step(struct up_guard_record *record,
    const struct up_guard_observation *o, uint64_t health_ms) {
    if (!record || !o || !o->transaction || !o->boot || !o->boot[0] || strlen(o->boot) > 39 ||
        (o->active && strlen(o->transaction) != 32) || health_ms < 60000 || health_ms > 86400000) return UP_GUARD_STOP;
    int same_transaction = o->active && !strcmp(record->transaction, o->transaction);
    int changed_boot = record->boot[0] && strcmp(record->boot, o->boot);
    int failed_boot = same_transaction && changed_boot && !record->boot_completed;
    if (!o->active && !record->transaction[0] && !changed_boot &&
        record->boot[0] && record->boot_completed == (o->boot_completed != 0)) return UP_GUARD_WAIT;
    if (!o->active || !same_transaction) {
        initialize(record, o);
        record->health_ms = health_ms;
        record->armed = o->active && o->trial;
        if (record->armed && o->boot_completed) record->completed_mono = o->now;
        return UP_GUARD_SAVE;
    }
    if (o->rollback_attempted || record->automatic_started || record->reboot_attempted ||
        (record->early_started && !record->early_done)) return UP_GUARD_STOP;
    if (changed_boot) {
        memcpy(record->boot, o->boot, strlen(o->boot) + 1);
        record->boot_completed = 0; record->completed_mono = 0;
        record->armed = record->armed || o->trial;
        if (failed_boot && record->armed && !record->early_started) {
            record->early_started = 1;
            return UP_GUARD_EARLY;
        }
    }
    int newly_armed = !record->armed && o->trial;
    if (newly_armed) { record->armed = 1; record->health_ms = health_ms; record->completed_mono = 0; }
    if (o->boot_completed && (!record->boot_completed || newly_armed)) {
        record->boot_completed = 1;
        if (record->armed) record->completed_mono = o->now;
        return record->early_done ? UP_GUARD_ROLLBACK : UP_GUARD_SAVE;
    }
    if (up_guard_due(record, o->transaction, o->boot, o->now, o->boot_completed)) return UP_GUARD_ROLLBACK;
    return changed_boot || newly_armed ? UP_GUARD_SAVE : UP_GUARD_WAIT;
}
