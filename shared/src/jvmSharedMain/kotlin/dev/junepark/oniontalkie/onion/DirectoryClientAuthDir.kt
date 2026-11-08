package dev.junepark.oniontalkie.onion

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A [ClientAuthDir] backed by the directory passed to Tor as `ClientOnionAuthDir`. */
fun directoryClientAuthDir(path: String): ClientAuthDir {
    require(path.isNotBlank()) { "A ClientOnionAuthDir path is required" }
    return DirectoryClientAuthDir(Paths.get(path))
}

private class DirectoryClientAuthDir(private val directory: Path) : ClientAuthDir {
    private fun file(name: String): Path {
        require(name.matches(Regex("[a-z2-7]{56}\\.auth_private"))) { "Invalid client-auth filename" }
        return directory.resolve(name)
    }

    override suspend fun write(fileName: String, contents: String): Unit = withContext(Dispatchers.IO) {
        val destination = file(fileName)
        ensureDirectory()
        // Tor ignores files that do not end in .auth_private, so the temporary name is never
        // picked up half-written. Set the exact mode instead of inheriting the process umask.
        val temporary = try {
            Files.createTempFile(
                directory,
                ".onion-talkie-",
                ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        } catch (_: UnsupportedOperationException) {
            Files.createTempFile(directory, ".onion-talkie-", ".tmp")
        }
        try {
            Files.write(temporary, contents.toByteArray(Charsets.UTF_8))
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                throw IOException("Filesystem for $directory does not support atomic replacement", e)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override suspend fun delete(fileName: String): Unit = withContext(Dispatchers.IO) {
        Files.deleteIfExists(file(fileName))
    }

    private fun ensureDirectory() {
        // Tor refuses a ClientOnionAuthDir that other users can read, so create it 0700.
        try {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        } catch (_: UnsupportedOperationException) {
            Files.createDirectories(directory)
            return
        }
    }
}
