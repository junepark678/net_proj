package dev.junepark.oniontalkie.identity

import java.io.IOException
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun directoryStorage(dataDirectory: String): Storage {
    require(dataDirectory.isNotBlank()) { "An app data directory is required" }
    return DirectoryStorage(Paths.get(dataDirectory))
}

private class DirectoryStorage(private val directory: Path) : Storage {
    private fun file(name: String): Path {
        require(name.matches(Regex("[a-z-]+\\.json|[a-z2-7]{56}\\.auth_private"))) { "Invalid storage filename" }
        return directory.resolve(name)
    }

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        val path = file(name)
        try {
            String(Files.readAllBytes(path), Charsets.UTF_8)
        } catch (_: java.nio.file.NoSuchFileException) {
            null
        }
    }

    override suspend fun write(name: String, contents: String): Unit = withContext(Dispatchers.IO) {
        val destination = file(name)
        Files.createDirectories(directory)
        // createTempFile uses owner-only permissions on POSIX systems, including for identity secrets.
        val temporary = Files.createTempFile(directory, ".onion-talkie-", ".tmp")
        try {
            Files.write(temporary, contents.toByteArray(Charsets.UTF_8))
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                // A non-atomic replacing move could leave a torn destination on failure,
                // which would break the Storage contract. Fail without touching the file.
                throw IOException("Filesystem for $directory does not support atomic replacement", e)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
