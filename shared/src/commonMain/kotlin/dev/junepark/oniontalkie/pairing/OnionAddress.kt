package dev.junepark.oniontalkie.pairing

/**
 * v3 onion address helpers (Tor rend-spec-v3 section 6):
 * `base32(PUBKEY | CHECKSUM | VERSION)` with `CHECKSUM = SHA3-256(".onion checksum" | PUBKEY | VERSION)[:2]`.
 */
internal object OnionAddress {
    /** Length of the host label (without `.onion`). */
    const val HOST_LENGTH = 56
    private const val VERSION: Byte = 3
    private const val PUBKEY_SIZE = 32
    private val CHECKSUM_PREFIX = ".onion checksum".encodeToByteArray()

    /** Builds the host label for an ed25519 [publicKey] (32 bytes). Mainly useful for tests. */
    fun fromPublicKey(publicKey: ByteArray): String {
        require(publicKey.size == PUBKEY_SIZE) { "ed25519 public key must be 32 bytes" }
        return Base32.encode(publicKey + checksum(publicKey) + VERSION).lowercase()
    }

    /** Result of [check]. */
    enum class Check { VALID, MALFORMED, BAD_CHECKSUM }

    /** Validates a host label (no `.onion` suffix): length, alphabet, version byte and checksum. */
    fun check(host: String): Check {
        if (host.length != HOST_LENGTH) return Check.MALFORMED
        val raw = Base32.decode(host) ?: return Check.MALFORMED
        if (raw[PUBKEY_SIZE + 2] != VERSION) return Check.MALFORMED
        val key = raw.copyOfRange(0, PUBKEY_SIZE)
        val expected = checksum(key)
        return if (raw[PUBKEY_SIZE] == expected[0] && raw[PUBKEY_SIZE + 1] == expected[1]) {
            Check.VALID
        } else {
            Check.BAD_CHECKSUM
        }
    }

    private fun checksum(publicKey: ByteArray): ByteArray =
        Sha3.sha3256(CHECKSUM_PREFIX + publicKey + VERSION).copyOfRange(0, 2)
}
