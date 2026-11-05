/* SPDX-License-Identifier: MIT */
#ifndef THINKOS_UPDATER_CRYPTO_CONFIG_H
#define THINKOS_UPDATER_CRYPTO_CONFIG_H

/* Identical portable 32-bit arithmetic in the daemon and host verifier.
 * No assembly, extra curves, compressed points or native-endian key formats. */
#define uECC_PLATFORM 0
#define uECC_WORD_SIZE 4
#define uECC_OPTIMIZATION_LEVEL 2
#define uECC_SUPPORTS_secp160r1 0
#define uECC_SUPPORTS_secp192r1 0
#define uECC_SUPPORTS_secp224r1 0
#define uECC_SUPPORTS_secp256r1 1
#define uECC_SUPPORTS_secp256k1 0
#define uECC_SUPPORT_COMPRESSED_POINT 0
#define uECC_VLI_NATIVE_LITTLE_ENDIAN 0
#define uECC_ENABLE_VLI_API 0
#endif
