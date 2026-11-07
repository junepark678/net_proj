package dev.junepark.oniontalkie.tor

import dev.junepark.oniontalkie.identity.IdentityStore
import dev.junepark.oniontalkie.identity.OTHER_ONION
import dev.junepark.oniontalkie.identity.directoryStorage
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OnionKeyStorageTest {
    @Test fun allSecretsSurviveReopeningInOwnerOnlyFiles() = runTest {
        val directory = Files.createTempDirectory("onion-talkie-keys-")
        try {
            val storage = directoryStorage(directory.toString())
            IdentityStore(storage).loadOrCreate { serviceIdentity() }
            val saved = IdentityStore(directoryStorage(directory.toString())).loadOrCreate { error("Already exists") }
            assertEquals(SERVICE_KEY, saved.onionServicePrivateKey)
            assertEquals(ALICE_PUBLIC, saved.clientAuthPub)
            assertEquals(ALICE_PRIVATE, saved.clientAuthPrivate)
            val authDirectory = directory.resolve("client-auth")
            val authStorage = directoryStorage(authDirectory.toString())
            val auth = ClientOnionAuth(authStorage)
            auth.writeForPeer(OTHER_ONION, saved)
            auth.writeForPeer(OTHER_ONION, saved) // Atomic replacement is also supported.
            val stem = OTHER_ONION.removeSuffix(".onion")
            val filename = "$stem.auth_private"
            assertEquals("$stem:descriptor:x25519:$ALICE_PRIVATE\n", Files.readString(authDirectory.resolve(filename)))
            assertEquals(Files.readString(authDirectory.resolve(filename)),
                directoryStorage(authDirectory.toString()).read(filename))
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                for (path in listOf(directory.resolve("identity.json"), authDirectory.resolve(filename))) {
                    assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(path))
                }
            }
            for (bad in listOf("../$filename", "${stem.uppercase()}.auth_private", "x.auth_private", "$stem.auth_private.tmp")) {
                assertFailsWith<IllegalArgumentException> { authStorage.write(bad, "secret") }
            }
            Files.list(authDirectory).use { assertEquals(1, it.count()) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
