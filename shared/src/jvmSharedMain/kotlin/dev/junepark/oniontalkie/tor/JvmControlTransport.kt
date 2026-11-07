package dev.junepark.oniontalkie.tor

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SocketChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Plain TCP transport shared by Android and desktop. Connect only to a trusted local Tor.
 * SocketChannel-backed streams make blocking I/O interruptible on coroutine cancellation. */
class JvmControlTransport private constructor(private val socket: Socket) : ControlTransport {
    private val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
    private val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

    override suspend fun readLine(): String? = runInterruptible(Dispatchers.IO) { reader.readLine() }

    override suspend fun writeLine(line: String) {
        require('\r' !in line && '\n' !in line) { "A control command must be a single line" }
        runInterruptible(Dispatchers.IO) {
            writer.write(line)
            writer.write("\r\n")
            writer.flush()
        }
    }

    override fun close() { socket.close() }

    companion object {
        /** Opens TCP with a bounded connect timeout. Command timeouts belong to the caller.
         * DNS resolution, if a hostname is supplied, uses the platform resolver. */
        suspend fun connect(host: String = "127.0.0.1", port: Int, connectTimeoutMillis: Int = 5_000): JvmControlTransport {
            require(port in 1..65535) { "Invalid control port" }
            require(connectTimeoutMillis > 0) { "Connect timeout must be positive" }
            val socket = SocketChannel.open().socket()
            try {
                return runInterruptible(Dispatchers.IO) {
                    socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
                    socket.tcpNoDelay = true
                    JvmControlTransport(socket)
                }
            } catch (failure: Throwable) {
                runCatching { socket.close() }
                throw failure
            }
        }
    }
}
