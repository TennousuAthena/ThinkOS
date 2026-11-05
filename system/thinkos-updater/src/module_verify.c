/* SPDX-License-Identifier: MIT */
#define _POSIX_C_SOURCE 200809L
#include "module_verify.h"
#include "crypto_config.h"
#include "../vendor/micro-ecc/uECC.h"
#include "../vendor/sha-2/sha-256.h"

#include <errno.h>
#include <limits.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

typedef struct { const unsigned char *bytes; size_t length; } up_slice;

/* P-256 INTEGERs use short-form DER lengths. Reject BER, redundant zeroes,
 * negative values, truncation, trailing bytes and over-wide scalars. */
static int der_integer(const unsigned char **position, const unsigned char *end,
                       unsigned char out[32]) {
    const unsigned char *p = *position;
    if ((size_t)(end - p) < 2 || *p++ != 0x02) return 0;
    size_t length = *p++;
    if (length == 0 || length > 33 || (size_t)(end - p) < length) return 0;
    if (p[0] & 0x80) return 0;
    if (length > 1 && p[0] == 0 && !(p[1] & 0x80)) return 0;
    const unsigned char *next = p + length;
    if (length == 33) { if (*p++ != 0) return 0; --length; }
    unsigned char nonzero = 0;
    for (size_t i = 0; i < length; ++i) nonzero |= p[i];
    if (!nonzero) return 0;
    memset(out, 0, 32);
    memcpy(out + 32 - length, p, length);
    *position = next;
    return 1;
}

static int signature_raw(const unsigned char *signature, size_t length,
                         unsigned char out[64]) {
    if (!signature || length < 8 || length > 72 || signature[0] != 0x30 ||
            signature[1] != length - 2) return 0;
    const unsigned char *position = signature + 2;
    const unsigned char *end = signature + length;
    return der_integer(&position, end, out) &&
           der_integer(&position, end, out + 32) && position == end;
}

static int is_text(unsigned char c) { return c >= 0x20 && c <= 0x7e; }
static int is_alpha_num(unsigned char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
}
static int literal(up_slice value, const char *expected) {
    size_t length = strlen(expected);
    return value.length == length && memcmp(value.bytes, expected, length) == 0;
}
static int field(up_slice line, const char *prefix, up_slice *value) {
    size_t length = strlen(prefix);
    if (line.length <= length || memcmp(line.bytes, prefix, length)) return 0;
    value->bytes = line.bytes + length; value->length = line.length - length;
    return 1;
}
static int decimal(up_slice value, uint64_t maximum, uint64_t *result) {
    if (!value.length || (value.length > 1 && value.bytes[0] == '0')) return 0;
    uint64_t number = 0;
    for (size_t i = 0; i < value.length; ++i) {
        if (value.bytes[i] < '0' || value.bytes[i] > '9') return 0;
        unsigned int digit = value.bytes[i] - '0';
        if (number > (maximum - digit) / 10) return 0;
        number = number * 10 + digit;
    }
    if (!number) return 0;
    *result = number;
    return 1;
}
static int hex_digit(unsigned char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static int parse_verified(const unsigned char *bytes, size_t length, up_module_manifest *out) {
    up_slice lines[7]; size_t start = 0, count = 0;
    for (size_t i = 0; i < length; ++i) {
        if (bytes[i] == '\n') {
            if (count == 7 || i == start) return UP_VERIFY_FORMAT;
            lines[count].bytes = bytes + start; lines[count].length = i - start;
            ++count; start = i + 1;
        } else if (!is_text(bytes[i])) return UP_VERIFY_FORMAT;
    }
    /* Exactly seven ordered LF-terminated lines; no trailing blank or comment. */
    if (count != 7 || start != length || !literal(lines[0], "thinkos-module 1")) return UP_VERIFY_FORMAT;
    up_slice id, kind, code, version, digest, size;
    if (!field(lines[1], "id=", &id) || !field(lines[2], "kind=", &kind) ||
            !field(lines[3], "code=", &code) || !field(lines[4], "version=", &version) ||
            !field(lines[5], "sha256=", &digest) || !field(lines[6], "size=", &size)) return UP_VERIFY_FORMAT;
    static const char *const ids[] = { "thinkos-updater", "thinkos-cast", "thinkos-immersive",
        "thinkos-shell", "thinkos-certs", "thinkos-power", "timesync" };
    int known = 0;
    for (size_t i = 0; i < sizeof(ids) / sizeof(ids[0]); ++i) if (literal(id, ids[i])) known = 1;
    if (!known) return UP_VERIFY_UNKNOWN_ID;
    if (!literal(kind, literal(id, "thinkos-immersive") ? "vector-apk" : "magisk")) return UP_VERIFY_FORMAT;
    uint64_t parsed_code, parsed_size;
    if (!decimal(code, INT32_MAX, &parsed_code)) return UP_VERIFY_FORMAT;
    if (!decimal(size, UINT64_MAX, &parsed_size)) return UP_VERIFY_FORMAT;
    if (parsed_size > UP_MODULE_PACKAGE_MAX) return UP_VERIFY_TOO_BIG;
    if (!version.length || version.length >= sizeof(out->version) || !is_alpha_num(version.bytes[0])) return UP_VERIFY_FORMAT;
    for (size_t i = 0; i < version.length; ++i) if (!is_alpha_num(version.bytes[i]) &&
            version.bytes[i] != '.' && version.bytes[i] != '_' && version.bytes[i] != '-' && version.bytes[i] != '+') return UP_VERIFY_FORMAT;
    if (digest.length != 64) return UP_VERIFY_FORMAT;
    for (size_t i = 0; i < 32; ++i) {
        int high = hex_digit(digest.bytes[i * 2]), low = hex_digit(digest.bytes[i * 2 + 1]);
        if (high < 0 || low < 0) return UP_VERIFY_FORMAT;
        out->sha256[i] = (unsigned char)((high << 4) | low);
    }
    memcpy(out->id, id.bytes, id.length); out->id[id.length] = 0;
    memcpy(out->kind, kind.bytes, kind.length); out->kind[kind.length] = 0;
    memcpy(out->version, version.bytes, version.length); out->version[version.length] = 0;
    out->code = (uint32_t)parsed_code; out->size = parsed_size;
    return UP_VERIFY_OK;
}

int up_verify_manifest(const unsigned char *bytes, size_t length,
                       const unsigned char *signature, size_t signature_length,
                       const unsigned char (*public_xy)[64], size_t key_count,
                       up_module_manifest *out) {
    if (!out) return UP_VERIFY_FORMAT;
    memset(out, 0, sizeof(*out));
    if (length > UP_MODULE_MANIFEST_MAX || signature_length > UP_MODULE_SIGNATURE_MAX) return UP_VERIFY_TOO_BIG;
    if (!bytes || !length || !public_xy || !key_count || key_count > UP_SYSTEM_KEY_MAX) return UP_VERIFY_BAD_SIGNATURE;
    unsigned char raw[64], hash[32];
    if (!signature_raw(signature, signature_length, raw)) return UP_VERIFY_BAD_SIGNATURE;
    uECC_Curve curve = uECC_secp256r1();
    for (size_t i = 0; i < key_count; ++i) if (!uECC_valid_public_key(public_xy[i], curve)) return UP_VERIFY_BAD_SIGNATURE;
    if (key_count == 2 && memcmp(public_xy[0], public_xy[1], 64) == 0) return UP_VERIFY_BAD_SIGNATURE;
    calc_sha_256(hash, bytes, length);
    int verified = 0;
    for (size_t i = 0; i < key_count; ++i) if (uECC_verify(public_xy[i], hash, sizeof(hash), raw, curve)) verified = 1;
    if (!verified) return UP_VERIFY_BAD_SIGNATURE;
    up_module_manifest parsed; memset(&parsed, 0, sizeof(parsed));
    int result = parse_verified(bytes, length, &parsed);
    if (result == UP_VERIFY_OK) memcpy(out, &parsed, sizeof(parsed));
    return result;
}

int up_sha256_fd(int fd, uint64_t expected_size, unsigned char out[32]) {
    if (!out) return UP_VERIFY_IO;
    memset(out, 0, 32);
    if (!expected_size || expected_size > UP_MODULE_PACKAGE_MAX) return UP_VERIFY_TOO_BIG;
    struct stat before, after;
    if (fstat(fd, &before) < 0 || !S_ISREG(before.st_mode) || before.st_size < 0 ||
            (uint64_t)before.st_size != expected_size) return UP_VERIFY_IO;
    unsigned char hash[32], buffer[16 * 1024]; struct Sha_256 context;
    sha_256_init(&context, hash);
    uint64_t offset = 0;
    while (offset < expected_size) {
        size_t wanted = expected_size - offset < sizeof(buffer) ? (size_t)(expected_size - offset) : sizeof(buffer);
        ssize_t amount = pread(fd, buffer, wanted, (off_t)offset);
        if (amount < 0 && errno == EINTR) continue;
        if (amount <= 0 || (size_t)amount > wanted) return UP_VERIFY_IO;
        sha_256_write(&context, buffer, (size_t)amount); offset += (size_t)amount;
    }
    if (fstat(fd, &after) < 0 || before.st_dev != after.st_dev || before.st_ino != after.st_ino ||
            after.st_size != before.st_size) return UP_VERIFY_IO;
    sha_256_close(&context); memcpy(out, hash, 32);
    return UP_VERIFY_OK;
}
