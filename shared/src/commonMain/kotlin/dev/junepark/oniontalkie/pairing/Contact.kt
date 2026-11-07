package dev.junepark.oniontalkie.pairing

import kotlinx.serialization.Serializable

/**
 * A paired peer.
 *
 * @property onion the peer's v3 onion host label (no `.onion`), the unique key of a contact.
 * @property clientAuthPub the peer's x25519 client-auth public key, upper-case base32 as Tor writes it.
 * @property displayName name chosen by the peer when pairing.
 */
@Serializable
data class Contact(
    val onion: String,
    val clientAuthPub: String,
    val displayName: String,
) {
    companion object {
        /** Builds a contact from a validated [payload]. */
        fun from(payload: PairingPayload): Contact =
            Contact(payload.onion, Base32.encode(payload.clientAuthPub), payload.displayName)
    }
}

/**
 * This device's own public identity, shown to peers as a pairing payload.
 * The private halves of the keys belong to #7 and are not handled here.
 */
data class Identity(
    val onion: String,
    val clientAuthPub: ByteArray,
    val displayName: String,
) {
    /** The payload to render as a QR code. */
    fun toPairingPayload(): PairingPayload = PairingPayload(onion, clientAuthPub, displayName)

    override fun equals(other: Any?): Boolean =
        other is Identity && onion == other.onion &&
            clientAuthPub.contentEquals(other.clientAuthPub) && displayName == other.displayName

    override fun hashCode(): Int =
        (onion.hashCode() * 31 + clientAuthPub.contentHashCode()) * 31 + displayName.hashCode()
}
