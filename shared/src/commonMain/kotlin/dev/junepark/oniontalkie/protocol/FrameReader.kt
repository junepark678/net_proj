package dev.junepark.oniontalkie.protocol

/**
 * Reassembles [Frame]s from a [ByteSource] regardless of how the bytes are chunked (one byte at
 * a time, several frames in one read, ...). Not thread-safe: use from a single coroutine.
 *
 * After any [ProtocolException] the reader is permanently failed and rethrows it; the caller
 * must then close the underlying channel.
 */
class FrameReader(private val source: ByteSource) {
    private val buf = ByteArray(BUFFER_SIZE)
    private var pos = 0
    private var limit = 0
    private var failure: ProtocolException? = null

    /**
     * Reads the next frame, or returns null if the stream ended cleanly between frames.
     *
     * @throws ProtocolException for malformed input or a stream that ends mid-frame.
     */
    suspend fun readFrame(): Frame? {
        failure?.let { throw it }
        try {
            if (pos == limit && !fill()) return null
            val type = buf[pos].toInt() and 0xFF
            val prefix = Frame.prefixLength(type)
            val frame = ByteArray(prefix)
            readInto(frame, 0, prefix)
            val trailer = Frame.trailerLength(type, frame)
            if (trailer == 0) return Frame.decode(frame)
            val whole = frame.copyOf(prefix + trailer)
            readInto(whole, prefix, trailer)
            return Frame.decode(whole)
        } catch (e: ProtocolException) {
            failure = e
            throw e
        }
    }

    /** Refills the buffer; returns false at end of stream. */
    private suspend fun fill(): Boolean {
        val n = source.read(buf, 0, buf.size)
        if (n < 0) return false
        pos = 0
        limit = n
        return true
    }

    private suspend fun readInto(dest: ByteArray, offset: Int, count: Int) {
        var done = 0
        while (done < count) {
            if (pos == limit && !fill()) throw ProtocolException(ProtocolError.TRUNCATED, "stream ended mid-frame")
            val n = minOf(count - done, limit - pos)
            buf.copyInto(dest, offset + done, pos, pos + n)
            pos += n
            done += n
        }
    }

    private companion object {
        const val BUFFER_SIZE = 4096
    }
}
