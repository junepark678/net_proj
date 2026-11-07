package dev.junepark.oniontalkie.pairing

/** RFC 4648 base32 without padding, as used by Tor for onion addresses and client-auth keys. */
internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Encodes [bytes] as upper-case base32 without padding. */
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    /**
     * Decodes unpadded base32 (either case). Returns `null` for characters outside the
     * alphabet or non-zero trailing bits, so every byte string has exactly one encoding.
     */
    fun decode(text: String): ByteArray? {
        val out = ByteArray(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c.uppercaseChar())
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out[index++] = (buffer shr (bits - 8)).toByte()
                bits -= 8
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return if (buffer == 0) out else null
    }
}
