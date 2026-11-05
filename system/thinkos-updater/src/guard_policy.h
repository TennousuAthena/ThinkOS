/* SPDX-License-Identifier: MIT */
#ifndef THINKOS_GUARD_POLICY_H
#define THINKOS_GUARD_POLICY_H
#include <stdint.h>

enum up_guard_action { UP_GUARD_WAIT, UP_GUARD_SAVE, UP_GUARD_EARLY, UP_GUARD_ROLLBACK, UP_GUARD_STOP };
/* Separate private state: never supplied by the application/socket caller. */
struct __attribute__((packed)) up_guard_record {
    char magic[8]; uint32_t version;
    char transaction[33], boot[40];
    uint8_t boot_completed, armed, early_started, early_done, automatic_started, reboot_attempted;
    uint8_t reserved[7];
    uint64_t completed_mono, health_ms;
    uint64_t checksum;
};
struct up_guard_observation {
    const char *transaction, *boot;
    uint64_t now;
    int active, trial, boot_completed, rollback_attempted;
};
/* Updates a caller-owned record; caller must sync it BEFORE UP_GUARD_EARLY.
 * UP_GUARD_ROLLBACK only requests a transaction-bound daemon check; it does
 * not authorize a side effect by itself. */
enum up_guard_action up_guard_step(struct up_guard_record *record,
    const struct up_guard_observation *observation, uint64_t health_ms);
int up_guard_due(const struct up_guard_record *record, const char *transaction,
    const char *boot, uint64_t now, int boot_completed);
uint64_t up_guard_checksum(const struct up_guard_record *record);
#endif
