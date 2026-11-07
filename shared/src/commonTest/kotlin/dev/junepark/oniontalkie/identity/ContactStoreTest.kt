package dev.junepark.oniontalkie.identity

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class MemoryStorage : Storage {
    val files = mutableMapOf<String, String>()
    var failWrites = false
    override suspend fun read(name: String) = files[name]
    override suspend fun write(name: String, contents: String) {
        yield()
        check(!failWrites) { "Disk write failed" }
        files[name] = contents
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContactStoreTest {
    @Test
    fun addRemovePersistAndEmitState() = runTest {
        val storage = MemoryStorage()
        val store = ContactStore.open(storage)
        val states = mutableListOf<List<Contact>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { store.contacts.take(3).toList(states) }
        assertEquals(emptyList(), store.list())
        store.add(payload())
        assertEquals(store.list(), ContactStore.open(storage).list())
        assertFalse(store.remove(OTHER_ONION))
        assertTrue(store.remove(ONION))
        job.join()
        assertEquals(listOf(0, 1, 0), states.map { it.size })
        assertTrue(ContactStore.open(storage).list().isEmpty())
    }

    @Test
    fun duplicatesMergeByOnionAndNormalizeKeyCase() = runTest {
        val storage = MemoryStorage()
        val store = ContactStore.open(storage)
        store.add(payload())
        store.add(PairingPayload(ONION, KEY.lowercase(), "Updated"))
        assertEquals(listOf(Contact(ONION, KEY, "Updated")), store.list())
        assertEquals(store.list(), ContactStore.open(storage).list())
        storage.files["contacts.json"] = Json.encodeToString(
            listOf(Contact(ONION, KEY, "Old"), Contact(ONION, KEY.lowercase(), "Latest")),
        )
        assertEquals(listOf(Contact(ONION, KEY, "Latest")), ContactStore.open(storage).list())
    }

    @Test
    fun onlineKeyChangesRepublishCompleteAuthorizationList() = runTest {
        val updates = mutableListOf<List<String>>()
        val store = ContactStore.open(MemoryStorage(), isOnline = { true }, republish = { updates.add(it) })
        store.add(payload())
        store.add(payload("Renamed"))
        store.add(PairingPayload(OTHER_ONION, OTHER_KEY, "Peer"))
        store.remove(ONION)
        store.remove(OTHER_ONION)
        assertEquals(listOf(listOf(KEY), listOf(KEY, OTHER_KEY).sorted(), listOf(OTHER_KEY), emptyList()), updates)
    }

    @Test
    fun offlineChangesDoNotPublish() = runTest {
        val store = ContactStore.open(MemoryStorage(), republish = { error("Offline publication") })
        store.add(payload())
        store.remove(ONION)
    }

    @Test
    fun writeFailureLeavesStateAndFileIntactAndDoesNotPublish() = runTest {
        val storage = MemoryStorage()
        var calls = 0
        val store = ContactStore.open(storage, { true }, { calls++ })
        store.add(payload())
        val saved = storage.files.toMap()
        storage.failWrites = true
        assertFailsWith<IllegalStateException> { store.remove(ONION) }
        assertEquals(saved, storage.files)
        assertEquals(1, store.contacts.value.size)
        assertEquals(1, calls)
    }

    @Test
    fun publicationFailureKeepsDurableContact() = runTest {
        val storage = MemoryStorage()
        val store = ContactStore.open(storage, { true }, { error("Tor unavailable") })
        assertFailsWith<IllegalStateException> { store.add(payload()) }
        assertEquals(store.list(), ContactStore.open(storage).list())
        assertEquals(1, store.list().size)
    }

    @Test
    fun concurrentAddsDoNotLosePeers() = runTest {
        val store = ContactStore.open(MemoryStorage())
        listOf(
            async { store.add(payload()) },
            async { store.add(PairingPayload(OTHER_ONION, OTHER_KEY, "Peer")) },
        ).awaitAll()
        assertEquals(setOf(ONION, OTHER_ONION), store.list().map { it.onion }.toSet())
    }

    @Test
    fun corruptOrInvalidSavedContactsAreNotSilentlyDiscarded() = runTest {
        val storage = MemoryStorage()
        for (bad in listOf("not json", "[{\"onion\":\"bad\",\"clientAuthPub\":\"$KEY\",\"displayName\":\"Peer\"}]")) {
            storage.files["contacts.json"] = bad
            assertFailsWith<IllegalArgumentException> { ContactStore.open(storage) }
            assertEquals(bad, storage.files["contacts.json"])
        }
    }
}
