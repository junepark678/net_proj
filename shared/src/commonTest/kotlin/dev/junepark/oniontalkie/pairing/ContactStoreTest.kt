package dev.junepark.oniontalkie.pairing

import dev.junepark.oniontalkie.storage.InMemoryStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactStoreTest {
    private val key = Base32.encode(ByteArray(32) { it.toByte() })
    private fun contact(seed: Int, name: String = "peer$seed") =
        Contact(OnionAddress.fromPublicKey(ByteArray(32) { seed.toByte() }), key, name)

    @Test
    fun addRemoveAndList() {
        val store = ContactStore(InMemoryStorage())
        assertTrue(store.list().isEmpty())
        assertTrue(store.add(contact(1)))
        assertTrue(store.add(contact(2)))
        assertEquals(listOf(contact(1), contact(2)), store.contacts.value)
        assertTrue(store.remove(contact(1).onion))
        assertFalse(store.remove(contact(1).onion))
        assertEquals(listOf(contact(2)), store.list())
    }

    @Test
    fun duplicatesAreMergedByOnion() {
        val store = ContactStore(InMemoryStorage())
        store.add(contact(1, "old"))
        store.add(contact(2))
        assertFalse(store.add(contact(1, "new")))
        assertEquals(listOf(contact(1, "new"), contact(2)), store.list())
    }

    @Test
    fun persistsAcrossInstances() {
        val storage = InMemoryStorage()
        ContactStore(storage).apply { add(contact(1)); add(contact(2)); remove(contact(1).onion) }
        assertEquals(listOf(contact(2)), ContactStore(storage).list())
    }

    @Test
    fun corruptAndDuplicatedStorageIsTolerated() {
        assertTrue(ContactStore(InMemoryStorage("not json")).list().isEmpty())
        val one = """{"onion":"a","clientAuthPub":"k","displayName":"n"}"""
        assertEquals(1, ContactStore(InMemoryStorage("[$one,$one]")).list().size)
    }

    @Test
    fun contactFromPayloadKeepsKeyAsBase32() {
        val payload = PairingPayload(contact(3).onion, ByteArray(32) { it.toByte() }, "X")
        assertEquals(Contact(contact(3).onion, key, "X"), Contact.from(payload))
    }

    @Test
    fun identityProducesPayload() {
        val identity = Identity(contact(4).onion, ByteArray(32), "me")
        assertEquals(identity.toPairingPayload(), (PairingPayload.parse(identity.toPairingPayload().encode()) as PairingParseResult.Success).payload)
    }
}
