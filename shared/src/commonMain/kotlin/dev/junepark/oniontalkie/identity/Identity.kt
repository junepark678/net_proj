package dev.junepark.oniontalkie.identity

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable local identity supplied by the Tor key owner (#7); this module never generates keys.
 * Both client-auth keys are unpadded base32 encodings of 32 opaque bytes. The private key is secret.
 * Keypair correspondence remains the responsibility of the supplier.
 */
@Serializable
class Identity(
    val onion: String,
    val clientAuthPub: String,
    val clientAuthPrivate: String,
    val displayName: String,
) {
    init {
        PairingPayload(onion, clientAuthPub, displayName)
        require(validAuthKey(clientAuthPrivate)) { "Invalid client-auth private key" }
    }

    /** Only public material is exported to a peer. */
    fun pairingPayload(): PairingPayload = PairingPayload(onion, clientAuthPub, displayName)
}

/** Own one instance per app data directory. Invalid persisted data is reported rather than replaced. */
class IdentityStore(private val storage: Storage) {
    private val mutex = Mutex()

    /** Calls [create] only on first launch, then persists before returning the identity. */
    suspend fun loadOrCreate(create: suspend () -> Identity): Identity = mutex.withLock {
        val existing = storage.read("identity.json")
        if (existing != null) Json.decodeFromString<Identity>(existing)
        else create().also { storage.write("identity.json", Json.encodeToString(it)) }
    }
}
