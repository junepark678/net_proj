package dev.junepark.oniontalkie.tor

import dev.junepark.oniontalkie.identity.Identity
import dev.junepark.oniontalkie.identity.PairingPayload
import dev.junepark.oniontalkie.identity.Storage

/**
 * Writes this device's private credential for a peer we will dial, not the peer's public key.
 * Supply directoryStorage for the directory configured as Tor's ClientOnionAuthDir. Prepare
 * files before starting Tor, or reload Tor's configuration after adding a file while running.
 */
class ClientOnionAuth(private val storage: Storage) {
    suspend fun writeForPeer(peerOnion: String, identity: Identity) {
        // Reuse checksum and base32 validation; also excludes path and command injection.
        PairingPayload(peerOnion, identity.clientAuthPub, "Peer")
        val address = peerOnion.removeSuffix(".onion")
        storage.write(
            "$address.auth_private",
            "$address:descriptor:x25519:${identity.clientAuthPrivate.uppercase()}\n",
        )
    }
}
