/* SPDX-License-Identifier: MIT
 * Host-only driver. File paths and test public roots belong to this executable,
 * never to updaterd's production socket or root-key configuration. */
#define _POSIX_C_SOURCE 200809L
#include "../src/module_verify.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int load(const char *path, size_t limit, unsigned char **bytes, size_t *length) {
    *bytes = NULL; *length = 0;
    int fd = open(path, O_RDONLY);
    if (fd < 0) return UP_VERIFY_IO;
    struct stat info;
    if (fstat(fd, &info) || !S_ISREG(info.st_mode) || info.st_size < 0) { close(fd); return UP_VERIFY_IO; }
    if ((uint64_t)info.st_size > limit) { close(fd); return UP_VERIFY_TOO_BIG; }
    size_t size = (size_t)info.st_size;
    unsigned char *result = malloc(size ? size : 1);
    if (!result) { close(fd); return UP_VERIFY_IO; }
    size_t have = 0;
    while (have < size) {
        ssize_t amount = read(fd, result + have, size - have);
        if (amount < 0 && errno == EINTR) continue;
        if (amount <= 0) { free(result); close(fd); return UP_VERIFY_IO; }
        have += (size_t)amount;
    }
    close(fd); *bytes = result; *length = size;
    return UP_VERIFY_OK;
}

static void hex(const unsigned char *bytes, size_t size) {
    for (size_t i = 0; i < size; ++i) printf("%02x", bytes[i]);
}

int main(int argc, char **argv) {
    if (argc == 4 && !strcmp(argv[1], "hash")) {
        char *end = NULL; errno = 0;
        unsigned long long expected = strtoull(argv[3], &end, 10);
        if (errno || !end || *end) return 2;
        int fd = open(argv[2], O_RDONLY); unsigned char digest[32];
        if (fd < 0) return 2;
        off_t original = lseek(fd, 2, SEEK_SET);
        int result = up_sha256_fd(fd, (uint64_t)expected, digest);
        if (original >= 0 && lseek(fd, 0, SEEK_CUR) != original) result = UP_VERIFY_IO;
        close(fd); printf("result=%d\n", result);
        if (result == UP_VERIFY_OK) { printf("sha256="); hex(digest, sizeof(digest)); putchar('\n'); }
        return result == UP_VERIFY_OK ? 0 : 1;
    }
    if ((argc != 5 && argc != 6) || strcmp(argv[1], "verify")) {
        fprintf(stderr, "usage: verify_driver verify MANIFEST SIGNATURE RAW_PUBLIC_XY_KEYS [PACKAGE]\n"
                        "       verify_driver hash FILE EXPECTED_SIZE\n");
        return 2;
    }
    unsigned char *manifest = NULL, *signature = NULL, *keys = NULL;
    size_t length = 0, signature_length = 0, keys_length = 0;
    int result = load(argv[2], UP_MODULE_MANIFEST_MAX, &manifest, &length);
    if (!result) result = load(argv[3], UP_MODULE_SIGNATURE_MAX, &signature, &signature_length);
    if (!result) result = load(argv[4], 64 * UP_SYSTEM_KEY_MAX, &keys, &keys_length);
    up_module_manifest parsed; memset(&parsed, 0, sizeof(parsed));
    if (!result && keys_length % 64) result = UP_VERIFY_BAD_SIGNATURE;
    if (!result) result = up_verify_manifest(manifest, length, signature, signature_length,
        (const unsigned char (*)[64])keys, keys_length / 64, &parsed);
    printf("result=%d\n", result);
    if (!result) {
        printf("id=%s\nkind=%s\ncode=%" PRIu32 "\nversion=%s\nsize=%" PRIu64 "\nsha256=",
            parsed.id, parsed.kind, parsed.code, parsed.version, parsed.size);
        hex(parsed.sha256, sizeof(parsed.sha256)); putchar('\n');
        if (argc == 6) {
            int fd = open(argv[5], O_RDONLY); unsigned char digest[32];
            int package_result = up_sha256_fd(fd, parsed.size, digest);
            if (fd >= 0) close(fd);
            int matches = package_result == UP_VERIFY_OK && !memcmp(digest, parsed.sha256, sizeof(digest));
            printf("package_result=%d\npackage_match=%d\n", package_result, matches);
            if (!matches) result = package_result ? package_result : UP_VERIFY_FORMAT;
        }
    } else {
        const unsigned char *bytes = (const unsigned char *)&parsed;
        unsigned char any = 0;
        for (size_t i = 0; i < sizeof(parsed); ++i) any |= bytes[i];
        printf("output_zero=%d\n", !any);
    }
    free(manifest); free(signature); free(keys);
    return result == UP_VERIFY_OK ? 0 : 1;
}
