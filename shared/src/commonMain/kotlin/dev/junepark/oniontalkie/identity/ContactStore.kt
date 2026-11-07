package dev.junepark.oniontalkie.identity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A paired peer, identified uniquely by its validated lowercase onion address. */
@Serializable
data class Contact(val onion: String, val clientAuthPub: String, val displayName: String) {
    init {
        PairingPayload(onion, clientAuthPub, displayName)
    }
}

/**
 * Persisted, observable peers. Own one instance per app data directory.
 * [republish] is the #7 integration seam: when [isOnline] is true it receives the complete current
 * authorization list after each key-set change, including removals. Offline publication must use
 * [list] when going online. Callbacks must not re-enter this store.
 * Publication-hook failures are logged without failing the saved operation; retry with [list] when
 * going online. Coroutine cancellation still propagates after the durable state is committed.
 */
class ContactStore private constructor(
    private val storage: Storage,
    initial: List<Contact>,
    private val isOnline: () -> Boolean,
    private val republish: suspend (clientAuthPubs: List<String>) -> Unit,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)
    /** Current read-only snapshots, emitted only after successful persistence. */
    val contacts: StateFlow<List<Contact>> = state.asStateFlow()

    /** Returns the current read-only contact snapshot. */
    fun list(): List<Contact> = state.value.toList()

    /** Adds a validated payload, replacing the existing peer with the same onion address. */
    suspend fun add(payload: PairingPayload): Unit = mutex.withLock {
        val contact = Contact(payload.onion, payload.clientAuthPub.uppercase(), payload.displayName)
        val old = state.value
        val index = old.indexOfFirst { it.onion == contact.onion }
        val next = old.toMutableList()
        if (index < 0) next.add(contact) else next[index] = contact
        save(next)
    }

    /** Removes a peer, persisting before emitting; returns false for an unknown address. */
    suspend fun remove(onion: String): Boolean = mutex.withLock {
        val next = state.value.filterNot { it.onion == onion }
        if (next.size == state.value.size) return@withLock false
        save(next)
        true
    }

    private suspend fun save(next: List<Contact>) {
        val old = state.value
        if (old == next) return
        // Storage can suspend even after replacing the file. Finish the commit before cancellation
        // can release the mutex, so disk and the observable snapshot always agree.
        withContext(NonCancellable) {
            storage.write("contacts.json", json.encodeToString(next))
            state.value = next.toList()
        }
        try {
            if (keys(old) != keys(next) && isOnline()) republish(keys(next).sorted())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not log payloads or hook exception messages, which may contain identity material.
            println("Contact change saved; authorization publication failed. Retry when going online.")
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Loads validated JSON, merging duplicates by onion with the last entry winning. */
        suspend fun open(
            storage: Storage,
            isOnline: () -> Boolean = { false },
            republish: suspend (clientAuthPubs: List<String>) -> Unit = {},
        ): ContactStore {
            val text = storage.read("contacts.json")
            val loaded = if (text == null) emptyList() else json.decodeFromString<List<Contact>>(text)
            val merged = loaded.map { it.copy(clientAuthPub = it.clientAuthPub.uppercase()) }
                .associateBy { it.onion }.values.toList()
            return ContactStore(storage, merged, isOnline, republish)
        }

        private fun keys(contacts: List<Contact>): Set<String> = contacts.map { it.clientAuthPub }.toSet()
    }
}
