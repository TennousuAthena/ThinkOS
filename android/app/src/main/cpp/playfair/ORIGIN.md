# playfair — origin and licence

The files in this directory (`hand_garble.c`, `modified_md5.c`, `omg_hax.c`, `omg_hax.h`, `playfair.c`, `playfair.h`, `sap_hash.c`) are
the FairPlay key-decryption routine ("playfair") from **RPiPlay**, https://github.com/FD-/RPiPlay, directory `lib/playfair/`,
copied unchanged from commit `64d0341ed3bef098c940c9ed0675948870a271f9`. The licence they were published under is in `LICENSE.md`
in this directory: **GNU General Public License, version 3**.

It is what lets the device decrypt the AES key an iPhone, iPad or Mac wraps with FairPlay for AirPlay screen mirroring
(`docs/28-casting.md`, ADR-031). It is reverse-engineered from Apple's protocol. ThinkOS's own code is MIT (root `LICENSE`), which does not
cover this directory; the complete APK contains it and is distributed as a whole under GPLv3 (ADR-043), which is what makes including it possible.

`../fairplay_jni.c` is ThinkOS's own glue; the reply tables in it are from RPiPlay's `lib/fairplay_playfair.c` (same commit, same licence).
