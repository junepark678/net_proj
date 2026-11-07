package dev.junepark.oniontalkie.tor

import dev.junepark.oniontalkie.identity.validAuthKey
import kotlin.io.encoding.Base64

/** One peer's raw 32-byte x25519 public key, in unpadded RFC 4648 base32. */
class PeerAuthKey(publicKey: String) {
    val publicKey: String

    init {
        require(validAuthKey(publicKey)) { "Invalid x25519 public key" }
        this.publicKey = publicKey.uppercase()
    }
}

internal class ClientAuthKeyPair(val publicKey: String, val privateKey: String)
internal expect fun generateClientAuthKeyPair(): ClientAuthKeyPair
internal expect fun clientAuthPublicKey(privateKey: String): String

internal fun encodeBase32(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    return buildString {
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 255)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append(alphabet[(buffer ushr bits) and 31])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) append(alphabet[(buffer shl (5 - bits)) and 31])
    }
}

/** Validate Tor's expanded 64-byte ed25519 secret, never an ed25519 seed or a PEM key. */
internal fun validateServiceKey(key: String) {
    require(key.startsWith("ED25519-V3:")) { "Expected an ED25519-V3 service key" }
    val blob = key.substringAfter(':')
    require(blob.matches(Regex("[A-Za-z0-9+/]{86}(==)?"))) { "Invalid service key encoding" }
    val bytes = Base64.Default.decode(blob.padEnd(88, '='))
    require(bytes.size == 64 && Base64.Default.encode(bytes).trimEnd('=') == blob.trimEnd('=')) {
        "Invalid expanded service key"
    }
}

internal fun addOnionCommand(key: String, localPort: Int, peers: List<PeerAuthKey>): String {
    require(localPort in 1..65535) { "Invalid local listener port" }
    if (key != "NEW:ED25519-V3") validateServiceKey(key)
    // Tor requires at least one authorized client for V3Auth. The owner supplies its own key
    // when there are no paired peers, never falling back to a publicly accessible service.
    require(peers.isNotEmpty()) { "V3 authorization requires a client key" }
    return buildString {
        append("ADD_ONION $key Flags=V3Auth Port=9999,127.0.0.1:$localPort")
        for (peer in peers) append(" ClientAuthV3=${peer.publicKey}")
    }
}
