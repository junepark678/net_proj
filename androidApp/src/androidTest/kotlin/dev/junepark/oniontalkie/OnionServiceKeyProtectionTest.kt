package dev.junepark.oniontalkie

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.junepark.oniontalkie.identity.Storage
import dev.junepark.oniontalkie.onion.OnionKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnionServiceKeyProtectionTest {
    private class MemoryStorage : Storage {
        val files = mutableMapOf<String, String>()
        override suspend fun read(name: String): String? = files[name]
        override suspend fun write(name: String, contents: String) { files[name] = contents }
    }

    @Test fun serviceKeyIsEncryptedAtRestAndReloadsFromAndroidKeystore() {
        val storage = MemoryStorage()
        val serviceKey = "ED25519-V3:" + "A".repeat(86) + "=="

        kotlinx.coroutines.runBlocking { OnionKeyStore(storage).save(serviceKey) }

        val serialized = storage.files.getValue("onion-service-key.json")
        assertFalse("Android Keystore ciphertext must not contain the service key", serialized.contains(serviceKey))
        assertEquals(
            serviceKey,
            kotlinx.coroutines.runBlocking { OnionKeyStore(storage).load() },
        )
    }
}
