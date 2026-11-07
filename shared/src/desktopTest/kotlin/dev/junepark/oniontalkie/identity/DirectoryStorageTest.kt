package dev.junepark.oniontalkie.identity

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DirectoryStorageTest {
    @Test
    fun realDirectoryStoragePersistsIdentityAndContactsAndReplacesFiles() = runTest {
        val directory = Files.createTempDirectory("onion-talkie-storage-test")
        try {
            val storage = directoryStorage(directory.toString())
            val identity = IdentityStore(storage).loadOrCreate { Identity(ONION, KEY, OTHER_KEY, "June") }
            val store = ContactStore.open(storage)
            store.add(identity.pairingPayload())
            store.add(payload("Renamed"))
            val reopened = directoryStorage(directory.toString())
            assertEquals("Renamed", ContactStore.open(reopened).list().single().displayName)
            assertEquals(OTHER_KEY, IdentityStore(reopened).loadOrCreate { error("Already saved") }.clientAuthPrivate)
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(directory.resolve("identity.json")),
                )
            }
            assertFailsWith<IllegalArgumentException> { storage.read("../identity.json") }
            assertFailsWith<IllegalArgumentException> { storage.write("/outside.json", "bad") }
            Files.list(directory).use { paths -> assertEquals(2, paths.count().toInt()) }
            store.remove(ONION)
            assertTrue(ContactStore.open(reopened).list().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
