package dev.junepark.oniontalkie.onion

import dev.junepark.oniontalkie.identity.Storage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private class StoredServiceKey(val privateKey: String)

/**
 * Persists the ED25519-V3 onion-service secret as `onion-service-key.json` in app storage.
 * On desktop and Android this is the app data directory; `directoryStorage` creates the file
 * with owner-only (0600) permissions and replaces it atomically. Android additionally wraps the
 * blob using a non-exportable Android Keystore key. Corrupt content is reported, never replaced, since the key
 * is the service's identity.
 */
class OnionKeyStore(
    private val storage: Storage,
    private val keyProtector: OnionServiceKeyProtector = createOnionServiceKeyProtector(),
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** The saved `ED25519-V3:<base64>` blob, or null on first run. */
    suspend fun load(): String? = mutex.withLock {
        val text = storage.read(FILE) ?: return@withLock null
        val stored = json.decodeFromString<StoredServiceKey>(text).privateKey
        val key = if (validServiceKeyBlob(stored)) {
            // Migrate keys written by earlier versions. The replacement is protected before
            // this method returns and uses Storage's atomic owner-only write semantics.
            keyProtector.protect(stored).also { storage.write(FILE, json.encodeToString(StoredServiceKey(it))) }
            stored
        } else {
            keyProtector.unprotect(stored)
        }
        require(validServiceKeyBlob(key)) { "Stored onion service key is invalid" }
        key
    }

    suspend fun save(blob: String): Unit = mutex.withLock {
        require(validServiceKeyBlob(blob)) { "Invalid onion service key" }
        storage.write(FILE, json.encodeToString(StoredServiceKey(keyProtector.protect(blob))))
    }

    private companion object {
        const val FILE = "onion-service-key.json"
    }
}

/** Where Tor's `ClientOnionAuthDir` lives; implemented per platform in `jvmSharedMain`. */
interface ClientAuthDir {
    /** Atomically writes a private (owner-only) `<id>.auth_private` file. */
    suspend fun write(fileName: String, contents: String)

    /** Removes the file if present. */
    suspend fun delete(fileName: String)
}
