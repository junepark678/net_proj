package dev.junepark.oniontalkie.protocol

/**
 * Minimal duplex transport contract for the future Tor channel adapter.
 * [read] suspends until it returns 1..[length] bytes or -1 for EOF; zero is invalid.
 * [write] writes the entire array or throws. [close] must be idempotent.
 * Transport implementations disable TCP Nagle themselves.
 */
interface FrameChannel {
    suspend fun read(destination: ByteArray, offset: Int, length: Int): Int
    suspend fun write(bytes: ByteArray)
    suspend fun flush()
    suspend fun close()
}

/** Writes one contiguous buffer and flushes per frame. Calls must be serialized by the caller. */
class FrameWriter(private val channel: FrameChannel) {
    suspend fun write(frame: Frame) {
        channel.write(FrameCodec.encode(frame))
        channel.flush()
    }
}

/**
 * Reads one frame without consuming the next. Calls must be serialized by the caller.
 * Clean EOF between frames returns null. Malformed/truncated input closes the channel and throws
 * [ProtocolException]. An unsupported HELLO sends and flushes BYE(VERSION) before closing.
 * Any read failure is latched and rethrown on subsequent reads, including transport failures.
 */
class FrameReader(private val channel: FrameChannel) {
    private var ended = false
    private var failure: Exception? = null

    suspend fun read(): Frame? {
        failure?.let { throw it }
        if (ended) return null
        try {
            val type = ByteArray(1)
            if (!readFully(type, 0, 1, allowEof = true)) {
                ended = true
                return null
            }
            val header = ByteArray(FrameCodec.headerSize(type[0]))
            header[0] = type[0]
            val headerOffset = if (type[0].toInt() == 1 || type[0].toInt() == 2) {
                readFully(header, 1, 1)
                try {
                    FrameCodec.checkVersion(header[1].toUByte())
                } catch (error: ProtocolException) {
                    if (type[0].toInt() == 1) {
                        try {
                            FrameWriter(channel).write(Frame.Bye(ByeReason.VERSION))
                        } catch (failure: Exception) {
                            error.addSuppressed(failure)
                        }
                    }
                    throw error
                }
                2
            } else {
                1
            }
            readFully(header, headerOffset, header.size - headerOffset)
            // Validate declared length before allocating or reading the payload.
            val bytes = header.copyOf(FrameCodec.frameSize(header))
            readFully(bytes, header.size, bytes.size - header.size)
            return FrameCodec.decode(bytes)
        } catch (error: Exception) {
            failure = error
            try {
                channel.close()
            } catch (failure: Exception) {
                error.addSuppressed(failure)
            }
            throw error
        }
    }

    private suspend fun readFully(bytes: ByteArray, offset: Int, length: Int, allowEof: Boolean = false): Boolean {
        var received = 0
        while (received < length) {
            val count = channel.read(bytes, offset + received, length - received)
            if (count == -1 && received == 0 && allowEof) return false
            if (count == -1) FrameCodec.truncated()
            if (count <= 0 || count > length - received) {
                throw ProtocolException(ProtocolException.Error.INVALID_LENGTH, "Invalid channel read count: $count")
            }
            received += count
        }
        return true
    }
}
