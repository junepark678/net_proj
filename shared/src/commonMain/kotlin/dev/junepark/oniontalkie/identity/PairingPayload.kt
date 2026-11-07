package dev.junepark.oniontalkie.identity

import kotlinx.serialization.Serializable

/** Public QR contents. Keys use unpadded base32; onion addresses use lowercase with `.onion`. */
@Serializable
data class PairingPayload(
    val onion: String,
    val clientAuthPub: String,
    val displayName: String,
    val version: Int = 1,
) {
    init {
        require(validationError() == null) { "Invalid pairing payload: ${validationError()}" }
    }

    /** Encodes at most 200 characters (and 200 UTF-8 bytes) without exposing any private key. */
    fun encode(): String = wireText()

    private fun wireText() = "onion-talkie:v$version;$onion;$clientAuthPub;$displayName"

    private fun validationError(): PairingError? = validate(version, onion, clientAuthPub, displayName)

    companion object {
        /** Parses untrusted text. Invalid input is returned as a typed failure, never thrown. */
        fun parse(text: String): PairingResult {
            if (text.length > 200 || text.encodeToByteArray().size > 200) {
                return PairingResult.Invalid(PairingError.TooLong)
            }
            val fields = text.split(';')
            if (fields.size != 4) return PairingResult.Invalid(PairingError.Format)
            if (fields[0] != "onion-talkie:v1") {
                return PairingResult.Invalid(
                    if (fields[0].startsWith("onion-talkie:v")) PairingError.Version else PairingError.Format,
                )
            }
            val error = validate(1, fields[1], fields[2], fields[3])
            return if (error != null) PairingResult.Invalid(error)
            else PairingResult.Valid(PairingPayload(fields[1], fields[2], fields[3]))
        }

        private fun validate(version: Int, onion: String, key: String, name: String): PairingError? {
            if (version != 1) return PairingError.Version
            if (!validOnion(onion)) return PairingError.Onion
            if (!validAuthKey(key)) return PairingError.ClientAuthKey
            // The delimiter, controls, and malformed UTF-16 are forbidden; ordinary Unicode is preserved.
            if (name.isBlank() || name.any { it == ';' || it.isISOControl() } ||
                name.encodeToByteArray().decodeToString() != name
            ) return PairingError.DisplayName
            val text = "onion-talkie:v1;$onion;$key;$name"
            if (text.length > 200 || text.encodeToByteArray().size > 200) return PairingError.TooLong
            return null
        }
    }
}

/** Reasons why a pairing payload cannot be accepted. */
enum class PairingError { Format, Version, TooLong, Onion, ClientAuthKey, DisplayName }

/** Exhaustive result for untrusted pairing input. */
sealed interface PairingResult {
    data class Valid(val payload: PairingPayload) : PairingResult
    data class Invalid(val reason: PairingError) : PairingResult
}

internal fun validAuthKey(key: String): Boolean = decodeBase32(key)?.size == 32

private fun validOnion(onion: String): Boolean {
    if (onion.length != 62 || !onion.endsWith(".onion") || onion != onion.lowercase()) return false
    val decoded = decodeBase32(onion.removeSuffix(".onion")) ?: return false
    if (decoded.size != 35 || decoded[34] != 3.toByte()) return false
    // Tor rend-spec-v3 §6: SHA3-256(".onion checksum" | PUBKEY | VERSION)[:2].
    val checksum = sha3_256(".onion checksum".encodeToByteArray() + decoded.copyOfRange(0, 32) + byteArrayOf(3))
    return decoded[32] == checksum[0] && decoded[33] == checksum[1]
}

/** Accepts RFC 4648 base32, either case, with no padding or nonzero trailing pad bits. */
internal fun decodeBase32(text: String): ByteArray? {
    if (text.length != 52 && text.length != 56) return null
    val bytes = ByteArray(text.length * 5 / 8)
    var buffer = 0
    var bits = 0
    var index = 0
    for (char in text) {
        if (char !in 'a'..'z' && char !in 'A'..'Z' && char !in '2'..'7') return null
        val value = "abcdefghijklmnopqrstuvwxyz234567".indexOf(char.lowercaseChar())
        if (value < 0) return null
        buffer = (buffer shl 5) or value
        bits += 5
        if (bits >= 8) {
            bits -= 8
            bytes[index++] = (buffer ushr bits).toByte()
            buffer = buffer and ((1 shl bits) - 1)
        }
    }
    return if (buffer == 0) bytes else null
}
