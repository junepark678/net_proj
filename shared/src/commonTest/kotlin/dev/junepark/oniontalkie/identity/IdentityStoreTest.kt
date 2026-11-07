package dev.junepark.oniontalkie.identity

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class IdentityStoreTest {
    @Test
    fun identitySurvivesReopeningWithoutRegeneratingKeys() = runTest {
        val storage = MemoryStorage()
        val first = IdentityStore(storage).loadOrCreate { Identity(ONION, KEY, OTHER_KEY, "June") }
        val restored = IdentityStore(storage).loadOrCreate { error("Must not create another identity") }
        assertEquals(first.onion, restored.onion)
        assertEquals(first.clientAuthPub, restored.clientAuthPub)
        assertEquals(first.clientAuthPrivate, restored.clientAuthPrivate)
        assertEquals(first.displayName, restored.displayName)
        assertFalse(restored.pairingPayload().encode().contains(OTHER_KEY))
        assertFalse(restored.toString().contains(OTHER_KEY))
    }

    @Test
    fun concurrentFirstLaunchOnlyCreatesOneIdentity() = runTest {
        val store = IdentityStore(MemoryStorage())
        var created = 0
        val values = List(5) { async { store.loadOrCreate { created++; Identity(ONION, KEY, OTHER_KEY, "June") } } }.awaitAll()
        assertEquals(1, created)
        assertEquals(1, values.map { it.onion }.toSet().size)
    }

    @Test
    fun unknownSavedIdentityFieldsAreIgnored() = runTest {
        val storage = MemoryStorage()
        storage.files["identity.json"] =
            """{"onion":"$ONION","clientAuthPub":"$KEY","clientAuthPrivate":"$OTHER_KEY","displayName":"June","future":true}"""
        val restored = IdentityStore(storage).loadOrCreate { error("Identity already exists") }
        assertEquals(ONION, restored.onion)
        assertEquals(OTHER_KEY, restored.clientAuthPrivate)
        assertEquals("June", restored.displayName)
    }

    @Test
    fun corruptIdentityIsNotOverwritten() = runTest {
        val storage = MemoryStorage()
        storage.files["identity.json"] = "broken"
        assertFailsWith<IllegalArgumentException> {
            IdentityStore(storage).loadOrCreate { error("Must preserve damaged identity") }
        }
        assertEquals("broken", storage.files["identity.json"])
        assertFailsWith<IllegalArgumentException> { Identity(ONION, KEY, "short", "June") }
    }
}
