package dev.junepark.oniontalkie.storage

import dev.junepark.oniontalkie.pairing.Contact
import dev.junepark.oniontalkie.pairing.ContactStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileStorageTest {
    @Test
    fun readWriteAndContactStoreOnDisk() {
        val dir = Files.createTempDirectory("onion-talkie-test").toFile()
        try {
            val storage = FileStorage(dir.resolve("nested").path, "contacts.json")
            assertNull(storage.read())
            val contact = Contact("o", "K", "n")
            ContactStore(storage).add(contact)
            assertEquals(listOf(contact), ContactStore(FileStorage(dir.resolve("nested").path, "contacts.json")).list())
            storage.write("x")
            assertEquals("x", storage.read())
        } finally {
            dir.deleteRecursively()
        }
    }
}
