package dev.junepark.oniontalkie.tor

import java.net.InetSocketAddress
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** Plain TCP transport shared by Android and desktop. Connect only to a trusted local Tor.
 * Direct SocketChannel I/O keeps full-duplex operations independent and interruptible. */
class JvmControlTransport private constructor(private val channel: SocketChannel) : ControlTransport {
    // Android's Socket input and output adapters take the same blockingLock(). Keep both
    // directions on SocketChannel so a pending control reply can never block a command write.
    private val readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE).apply { limit(0) }

    override suspend fun readLine(): String? = io {
        val line = ByteArrayOutputStream()
        while (true) {
            if (!readBuffer.hasRemaining()) {
                readBuffer.clear()
                val count = channel.read(readBuffer)
                readBuffer.flip()
                if (count < 0) return@io line.toTorControlLineOrNull()
                if (count == 0) continue
            }

            while (readBuffer.hasRemaining()) {
                val byte = readBuffer.get()
                if (byte == LF) return@io line.toTorControlLine()
                line.write(byte.toInt())
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    override suspend fun writeLine(line: String) {
        require('\r' !in line && '\n' !in line) { "A control command must be a single line" }
        io {
            val bytes = ByteBuffer.wrap((line + "\r\n").toByteArray(StandardCharsets.UTF_8))
            while (bytes.hasRemaining()) {
                val count = channel.write(bytes)
                if (count < 0) throw IOException("Tor control socket closed while writing")
                if (count == 0) Thread.yield()
            }
        }
    }

    override fun close() { channel.close() }

    private suspend fun <T> io(block: () -> T): T = try {
        runInterruptible(Dispatchers.IO, block)
    } catch (failure: Throwable) {
        // SocketChannel reports interruption as ClosedByInterruptException rather than
        // InterruptedException. Restore coroutine cancellation semantics in that case.
        runCatching { close() }
        currentCoroutineContext().ensureActive()
        throw failure
    }

    companion object {
        /** Opens TCP with a bounded connect timeout. Command timeouts belong to the caller.
         * DNS resolution, if a hostname is supplied, uses the platform resolver. */
        suspend fun connect(host: String = "127.0.0.1", port: Int, connectTimeoutMillis: Int = 5_000): JvmControlTransport {
            require(port in 1..65535) { "Invalid control port" }
            require(connectTimeoutMillis > 0) { "Connect timeout must be positive" }
            val channel = SocketChannel.open()
            try {
                return runInterruptible(Dispatchers.IO) {
                    val socket = channel.socket()
                    socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
                    socket.tcpNoDelay = true
                    JvmControlTransport(channel)
                }
            } catch (failure: Throwable) {
                runCatching { channel.close() }
                currentCoroutineContext().ensureActive()
                throw failure
            }
        }

        private const val READ_BUFFER_SIZE = 8 * 1024
        private const val LF = 0x0A.toByte()
    }
}

private fun ByteArrayOutputStream.toTorControlLineOrNull(): String? =
    if (size() == 0) null else toTorControlLine()

private fun ByteArrayOutputStream.toTorControlLine(): String {
    val bytes = toByteArray()
    val length = if (bytes.lastOrNull() == 0x0D.toByte()) bytes.size - 1 else bytes.size
    return String(bytes, 0, length, StandardCharsets.UTF_8)
}
