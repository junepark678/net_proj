package dev.junepark.oniontalkie.storage

/** A single named blob of text persisted by the platform; tests substitute an in-memory fake. */
interface Storage {
    /** Returns the stored text, or `null` if nothing has been written yet. */
    fun read(): String?

    /** Replaces the stored text. Implementations should make the replacement atomic. */
    fun write(text: String)
}

/** Directory where the app keeps its private data (contacts, identity). Platform-specific. */
expect fun appDataDir(): String
