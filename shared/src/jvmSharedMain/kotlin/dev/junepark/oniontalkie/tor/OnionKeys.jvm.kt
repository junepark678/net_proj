package dev.junepark.oniontalkie.tor

import dev.junepark.oniontalkie.identity.decodeBase32
import java.security.SecureRandom
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

// Use the lightweight API without registering a JCA provider. Android API 26 does not provide
// the desktop JDK's X25519 JCA implementation; both targets use the same raw key format.
internal actual fun generateClientAuthKeyPair(): ClientAuthKeyPair {
    val privateKey = X25519PrivateKeyParameters(SecureRandom())
    return ClientAuthKeyPair(
        encodeBase32(privateKey.generatePublicKey().encoded),
        encodeBase32(privateKey.encoded),
    )
}

internal actual fun clientAuthPublicKey(privateKey: String): String {
    val raw = requireNotNull(decodeBase32(privateKey)?.takeIf { it.size == 32 }) {
        "Invalid x25519 private key"
    }
    return encodeBase32(X25519PrivateKeyParameters(raw, 0).generatePublicKey().encoded)
}
