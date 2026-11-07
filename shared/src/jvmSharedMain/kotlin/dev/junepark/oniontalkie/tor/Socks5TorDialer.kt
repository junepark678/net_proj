package dev.junepark.oniontalkie.tor

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.SocketChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

/**
 * [TorDialer] that speaks SOCKS5 (RFC 1928) to Tor's local SocksPort, shared by Android and desktop.
 *
 * The onion name is sent as address type 0x03 (domain name), so it is resolved only by Tor and never
 * by the local resolver. Authentication method 0x00 (none) is the only one offered. Tor reports onion
 * service failures with the 0xF0..0xF7 reply codes only when its SocksPort has the `ExtendedErrors`
 * flag; without it those failures arrive as generic codes and map to [DialResult.Error].
 *
 * Connect plus handshake must finish within [timeoutMillis] (30 s default), otherwise the result is
 * [DialResult.Timeout]. The deadline does not apply to the established connection.
 */
class Socks5TorDialer(
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = DEFAULT_SOCKS_PORT,
    private val timeoutMillis: Long = TorDialer.DEFAULT_TIMEOUT_MILLIS,
) : TorDialer {
    init {
        require(socksPort in 1..65535) { "Invalid SOCKS port" }
        require(timeoutMillis > 0) { "Timeout must be positive" }
    }

    override suspend fun connect(onion: String, port: Int): DialResult {
        val request = try {
            Socks5Wire.connectRequest(onion, port)
        } catch (error: IllegalArgumentException) {
            return DialResult.Error(TorDialer.ERROR_INVALID_ARGUMENT, error.message ?: "invalid argument")
        }
        // Only channel-backed sockets honor thread interruption, so always use SocketChannel.
        val socket = SocketChannel.open().socket()
        try {
            val result = withTimeout(timeoutMillis) {
                runInterruptible(Dispatchers.IO) {
                    try {
                        handshake(socket, request)
                    } catch (interrupted: ClosedByInterruptException) {
                        // Channel I/O reports interruption this way; let runInterruptible map it to cancellation.
                        throw InterruptedException().apply { initCause(interrupted) }
                    }
                }
            }
            if (result is DialResult.Connected) return result
            socket.close()
            return result
        } catch (failure: TimeoutCancellationException) {
            runCatching { socket.close() }
            // A timeout raised by the caller's own scope is cancellation, not a dial timeout.
            currentCoroutineContext().ensureActive()
            return DialResult.Timeout
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            if (failure is CancellationException) throw failure
            // ClosedByInterruptException from our own cancellation is surfaced above; anything
            // left here is a local I/O or protocol failure.
            return when (failure) {
                is SocketTimeoutException -> DialResult.Timeout
                is Socks5ProtocolException -> DialResult.Error(TorDialer.ERROR_PROTOCOL, failure.message ?: "SOCKS5 protocol error")
                is IOException -> DialResult.Error(TorDialer.ERROR_IO, failure.message ?: failure::class.simpleName ?: "I/O error")
                else -> throw failure
            }
        }
    }

    private fun handshake(socket: Socket, request: ByteArray): DialResult {
        socket.connect(InetSocketAddress(socksHost, socksPort), CONNECT_TIMEOUT_MILLIS)
        socket.tcpNoDelay = true
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        output.write(Socks5Wire.GREETING)
        output.flush()
        val method = input.readExactly(2)
        if (method[0].toInt() != 5) throw Socks5ProtocolException("Not a SOCKS5 proxy (version ${method[0].toInt() and 0xFF})")
        if (method[1].toInt() != 0) {
            val chosen = method[1].toInt() and 0xFF
            return DialResult.Error(
                TorDialer.ERROR_PROTOCOL,
                if (chosen == 0xFF) "SOCKS5 proxy accepts no offered authentication method" else "SOCKS5 proxy selected unsupported method 0x${chosen.toString(16)}",
            )
        }

        output.write(request)
        output.flush()
        val head = input.readExactly(4)
        if (head[0].toInt() != 5) throw Socks5ProtocolException("Malformed SOCKS5 reply version ${head[0].toInt() and 0xFF}")
        val reply = head[1].toInt() and 0xFF
        if (reply != 0) return Socks5Replies.failure(reply)

        // Consume BND.ADDR and BND.PORT exactly so no tunnel bytes are lost.
        val addressLength = when (val type = head[3].toInt() and 0xFF) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> input.readExactly(1)[0].toInt() and 0xFF
            else -> throw Socks5ProtocolException("Unknown SOCKS5 bound address type $type")
        }
        input.readExactly(addressLength + 2)
        return DialResult.Connected(SocketFrameChannel(socket))
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var received = 0
        while (received < count) {
            val read = read(bytes, received, count - received)
            if (read < 0) throw EOFException("SOCKS5 proxy closed the connection during the handshake")
            received += read
        }
        return bytes
    }

    private class Socks5ProtocolException(message: String) : IOException(message)

    companion object {
        const val DEFAULT_SOCKS_PORT: Int = 9050
        private const val CONNECT_TIMEOUT_MILLIS: Int = 5_000
    }
}

/** SOCKS5 request encoding. */
internal object Socks5Wire {
    /** VER=5, NMETHODS=1, METHODS={0x00 no authentication}. */
    val GREETING: ByteArray = byteArrayOf(0x05, 0x01, 0x00)

    /** Builds `VER CMD RSV ATYP=0x03 LEN NAME PORT` for CONNECT; throws [IllegalArgumentException] on bad input. */
    fun connectRequest(onion: String, port: Int): ByteArray {
        require(port in 1..65535) { "Invalid port $port" }
        require(onion.length in ".onion".length + 1..255 && onion.endsWith(".onion")) { "Not a .onion host name" }
        require(onion.all { it.code in 0x21..0x7E }) { "Onion host name must be printable ASCII" }
        val name = onion.encodeToByteArray()
        return byteArrayOf(0x05, 0x01, 0x00, 0x03, name.size.toByte()) + name +
            byteArrayOf((port ushr 8).toByte(), port.toByte())
    }
}
