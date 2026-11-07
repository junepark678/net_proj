package dev.junepark.oniontalkie.tor

import dev.junepark.oniontalkie.protocol.FrameChannel
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/**
 * [FrameChannel] over a connected TCP socket. Nagle is disabled here. Reads and writes may run
 * concurrently; each direction must be serialized by the caller. The socket must be channel-backed
 * (`SocketChannel.open().socket()`) so cancellation interrupts blocking I/O. Cancelling an
 * in-flight read or write closes the connection, as the JDK closes an interrupted channel.
 */
internal class SocketFrameChannel(private val socket: Socket) : FrameChannel {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    init { socket.tcpNoDelay = true }

    internal val noDelay: Boolean get() = socket.tcpNoDelay

    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
        require(length > 0) { "Read length must be positive" }
        return io { input.read(destination, offset, length) }
    }

    override suspend fun write(bytes: ByteArray) = io { output.write(bytes) }

    override suspend fun flush() = io { output.flush() }

    /** Idempotent; closing an already closed socket is a no-op. */
    override suspend fun close() { socket.close() }

    private suspend fun <T> io(block: () -> T): T = try {
        runInterruptible(Dispatchers.IO, block)
    } catch (failure: Throwable) {
        // Interrupting a SocketChannel reports ClosedByInterruptException; restore cancellation.
        currentCoroutineContext().ensureActive()
        throw failure
    }
}
