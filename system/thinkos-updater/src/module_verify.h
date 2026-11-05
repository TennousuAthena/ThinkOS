/* SPDX-License-Identifier: MIT */
#ifndef THINKOS_UPDATER_MODULE_VERIFY_H
#define THINKOS_UPDATER_MODULE_VERIFY_H

#include <stddef.h>
#include <stdint.h>

#define UP_MODULE_MANIFEST_MAX (64u * 1024u)
#define UP_MODULE_SIGNATURE_MAX 1024u
#define UP_MODULE_PACKAGE_MAX (16u * 1024u * 1024u)
#define UP_SYSTEM_KEY_MAX 2u

enum up_verify_result {
    UP_VERIFY_OK = 0,
    UP_VERIFY_BAD_SIGNATURE = -1,
    UP_VERIFY_FORMAT = -2,
    UP_VERIFY_UNKNOWN_ID = -3,
    UP_VERIFY_TOO_BIG = -4,
    UP_VERIFY_IO = -5
};

typedef struct up_module_manifest {
    char id[32];
    char kind[16];
    uint32_t code;
    char version[49];
    unsigned char sha256[32];
    uint64_t size;
} up_module_manifest;

/* public_xy contains only the active/backup system roots compiled into the
 * module: P-256 X||Y, big endian, without the SEC1 04 prefix. Never accept keys
 * from a request or a mutable downloaded file. Zero roots fails closed.
 * Signatures are canonical ASN.1 DER ECDSA over SHA-256 of the exact bytes.
 * No manifest text is parsed until a signature verifies. out is zero on error.
 * This validates identity/format only; the daemon owns durable code floors. */
int up_verify_manifest(const unsigned char *bytes, size_t length,
                       const unsigned char *signature, size_t signature_length,
                       const unsigned char (*public_xy)[64], size_t key_count,
                       up_module_manifest *out);

/* Hash an already copied, private regular file using bounded pread from zero;
 * caller's file offset is unchanged. Its size must equal the signed size and
 * be in 1..16MiB. Does not interpret archives or take paths from the manifest. */
int up_sha256_fd(int fd, uint64_t expected_size, unsigned char out[32]);

#endif
