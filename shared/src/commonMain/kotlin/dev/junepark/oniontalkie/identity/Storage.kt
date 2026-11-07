package dev.junepark.oniontalkie.identity

/** Private app storage. A failed write must leave the previous file intact. */
interface Storage {
    suspend fun read(name: String): String?
    suspend fun write(name: String, contents: String)
}

/**
 * Creates storage beneath an app-owned data directory, defaulting to [appDataDir].
 * Own one store instance per file.
 */
expect fun directoryStorage(dataDirectory: String = appDataDir()): Storage

/** Android's initialized private files directory, or the desktop XDG data directory/onion-talkie. */
expect fun appDataDir(): String
