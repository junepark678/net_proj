package dev.junepark.oniontalkie.onion

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class DirectoryClientAuthDirTest {
    private val name = "a".repeat(56) + ".auth_private"

    @Test fun writesPrivateFilesAtomicallyAndDeletes(): Unit = runBlocking {
        val root = Files.createTempDirectory("onion-talkie-auth-").resolve("auth")
        val dir = directoryClientAuthDir(root.toString())
        dir.write(name, "first\n")
        dir.write(name, "second\n")
        val file = root.resolve(name)
        assertEquals("second\n", Files.readString(file))
        assertEquals(listOf(name), Files.list(root).use { s -> s.map { it.fileName.toString() }.toList() })
        if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root)))
        }
        dir.delete(name)
        dir.delete(name)
        assertFalse(Files.exists(file))
    }

    @Test fun rejectsNamesThatCouldEscapeTheDirectory(): Unit = runBlocking {
        val dir = directoryClientAuthDir(Files.createTempDirectory("onion-talkie-auth-").toString())
        assertFailsWith<IllegalArgumentException> { dir.write("../x.auth_private", "x") }
        assertFailsWith<IllegalArgumentException> { dir.delete("passwd") }
    }

    @Test fun onionKeyFileIsOwnerOnlyUnderDirectoryStorage(): Unit = runBlocking {
        val root = Files.createTempDirectory("onion-talkie-key-")
        OnionKeyStore(dev.junepark.oniontalkie.identity.directoryStorage(root.toString())).save(SERVICE_KEY)
        val file = root.resolve("onion-service-key.json")
        assertEquals(SERVICE_KEY, OnionKeyStore(dev.junepark.oniontalkie.identity.directoryStorage(root.toString())).load())
        if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        }
    }
}
