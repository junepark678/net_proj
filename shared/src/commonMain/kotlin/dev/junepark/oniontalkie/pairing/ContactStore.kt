package dev.junepark.oniontalkie.pairing

import dev.junepark.oniontalkie.storage.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persisted list of paired [Contact]s, unique by onion address.
 *
 * [contacts] is the change feed: the onion-service publisher (#7) collects it and re-publishes with the
 * current set of client-auth keys whenever it emits while the device is online.
 *
 * Not thread-safe; call the mutators from one thread (e.g. the UI/main dispatcher).
 */
class ContactStore(private val storage: Storage) {
    private val _contacts = MutableStateFlow(load())

    /** Current contacts in insertion order. */
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    /**
     * Adds [contact]. A contact with the same onion is merged by replacing its key and name in place.
     * @return `true` if the contact is new, `false` if an existing one was updated.
     */
    fun add(contact: Contact): Boolean {
        val current = _contacts.value
        val index = current.indexOfFirst { it.onion == contact.onion }
        val isNew = index < 0
        commit(if (isNew) current + contact else current.toMutableList().also { it[index] = contact })
        return isNew
    }

    /** Removes the contact with [onion]. @return `true` if one was removed. */
    fun remove(onion: String): Boolean {
        val current = _contacts.value
        val updated = current.filterNot { it.onion == onion }
        if (updated.size == current.size) return false
        commit(updated)
        return true
    }

    /** Returns a snapshot of all contacts. */
    fun list(): List<Contact> = _contacts.value

    private fun commit(updated: List<Contact>) {
        storage.write(json.encodeToString(LIST_SERIALIZER, updated))
        _contacts.value = updated
    }

    // Unreadable data is treated as an empty list so a corrupt file cannot make the app unlaunchable.
    private fun load(): List<Contact> {
        val text = storage.read() ?: return emptyList()
        return try {
            json.decodeFromString(LIST_SERIALIZER, text).distinctBy { it.onion }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val LIST_SERIALIZER = ListSerializer(Contact.serializer())
    }
}
