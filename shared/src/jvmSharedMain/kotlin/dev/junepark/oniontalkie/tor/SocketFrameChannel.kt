package dev.junepark.oniontalkie.tor

import dev.junepark.oniontalkie.protocol.FrameChannel
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/**
 * [FrameChannel] over a connected TCP socket. Nagle is disabled here. Reads and writes may run
 * concurrently; each direction must be serialized by the caller. Direct channel I/O avoids the
 * shared blocking lock in Android's socket stream adapters. Cancelling an
 * in-flight read or write closes the connection, as the JDK closes an interrupted channel.
 */
internal class SocketFrameChannel(private val channel: SocketChannel) : FrameChannel {
    private val socket get() = channel.socket()

    init { socket.tcpNoDelay = true }

    internal val noDelay: Boolean get() = socket.tcpNoDelay

    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
        require(length > 0) { "Read length must be positive" }
        return io { channel.read(ByteBuffer.wrap(destination, offset, length)) }
    }

    override suspend fun write(bytes: ByteArray) = io {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    // Channel writes are unbuffered.
    override suspend fun flush() { currentCoroutineContext().ensureActive() }

    /** Idempotent; closing an already closed socket is a no-op. */
    override suspend fun close() { channel.close() }

    private suspend fun <T> io(block: () -> T): T = try {
        runInterruptible(Dispatchers.IO, block)
    } catch (failure: Throwable) {
        // Interrupting a SocketChannel reports ClosedByInterruptException; restore cancellation.
        currentCoroutineContext().ensureActive()
        throw failure
    }
}
