package moe.kiwi.thinkos.cast.airplay

/**
 * FairPlay for AirPlay screen mirroring, the part that needs Apple's reverse-engineered key routine (`libthinkos_playfair.so`, GPLv3,
 * see `cpp/playfair/ORIGIN.md`). Every call returns null for a request that is not what FairPlay sends. Stateless: the caller keeps
 * the 164-byte key message of its connection.
 */
internal object PlayFair {
    private val available: Boolean = try { System.loadLibrary("thinkos_playfair"); true } catch (error: UnsatisfiedLinkError) { false }

    val isAvailable: Boolean get() = available

    @JvmStatic private external fun setup(request: ByteArray): ByteArray?
    @JvmStatic private external fun handshake(request: ByteArray): ByteArray?
    @JvmStatic private external fun decrypt(keyMessage: ByteArray, wrapped: ByteArray): ByteArray?

    /** `POST /fp-setup`, phase 1: 16 bytes in, 142 out. */
    fun phase1(request: ByteArray): ByteArray? = if (available) setup(request) else null
    /** `POST /fp-setup`, phase 2: 164 bytes in, 32 out. */
    fun phase2(request: ByteArray): ByteArray? = if (available) handshake(request) else null
    /** The wrapped AES key of a SETUP (72 bytes) and the key message from phase 2 give the 16-byte key. */
    fun unwrap(keyMessage: ByteArray, wrapped: ByteArray): ByteArray? = if (available) decrypt(keyMessage, wrapped) else null
}
