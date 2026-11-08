package dev.junepark.oniontalkie.identity

private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

/** RFC 4648 base32, uppercase, without padding: the format Tor uses for x25519 client-auth keys. */
internal fun encodeBase32(bytes: ByteArray): String = buildString {
    var buffer = 0
    var bits = 0
    for (byte in bytes) {
        buffer = (buffer shl 8) or (byte.toInt() and 0xff)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            append(ALPHABET[(buffer ushr bits) and 31])
        }
        buffer = buffer and ((1 shl bits) - 1)
    }
    if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
}
