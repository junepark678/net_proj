package dev.junepark.oniontalkie.identity

/** Private app storage. A failed write must leave the previous file intact. */
interface Storage {
    suspend fun read(name: String): String?
    suspend fun write(name: String, contents: String)
}

/**
 * Creates storage beneath an app-owned data directory. Android callers supply `Context.filesDir.path`;
 * desktop callers supply their chosen application data directory. Own one store instance per file.
 */
expect fun directoryStorage(dataDirectory: String): Storage
