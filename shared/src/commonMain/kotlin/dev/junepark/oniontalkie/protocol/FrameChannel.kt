package dev.junepark.oniontalkie.protocol

/** Minimal readable half of a byte stream; adapt the Tor channel types to this. */
interface ByteSource {
    /**
     * Reads between 1 and [length] bytes into [buffer] starting at [offset], suspending until at
     * least one byte is available. Returns the number of bytes read, or -1 at end of stream.
     */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** Minimal writable half of a byte stream; adapt the Tor channel types to this. */
interface ByteSink {
    /** Writes all of [bytes]. May buffer; call [flush] to push them out. */
    suspend fun write(bytes: ByteArray)

    /** Pushes buffered bytes to the peer. */
    suspend fun flush()
}
