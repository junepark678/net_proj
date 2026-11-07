package dev.junepark.oniontalkie.tor

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
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
 * [socksHost] must be an IPv4 or IPv6 literal (for example `127.0.0.1` or `::1`); hostnames
 * are rejected at construction so local DNS cannot escape the dial deadline.
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
    private val proxyAddress = InetSocketAddress(numericProxyAddress(socksHost), socksPort)

    override suspend fun connect(onion: String, port: Int): DialResult {
        val request = try {
            Socks5Wire.connectRequest(onion, port)
        } catch (error: IllegalArgumentException) {
            return DialResult.Error(TorDialer.ERROR_INVALID_ARGUMENT, error.message ?: "invalid argument")
        }
        // Only channel-backed sockets honor thread interruption, so always use SocketChannel.
        var channel: SocketChannel? = null
        try {
            val result = withTimeout(timeoutMillis) {
                runInterruptible(Dispatchers.IO) {
                    try {
                        val opened = SocketChannel.open()
                        channel = opened
                        handshake(opened, request)
                    } catch (interrupted: ClosedByInterruptException) {
                        // Channel I/O reports interruption this way; let runInterruptible map it to cancellation.
                        throw InterruptedException().apply { initCause(interrupted) }
                    }
                }
            }
            if (result is DialResult.Connected) return result
            channel?.close()
            return result
        } catch (failure: TimeoutCancellationException) {
            runCatching { channel?.close() }
            // A timeout raised by the caller's own scope is cancellation, not a dial timeout.
            currentCoroutineContext().ensureActive()
            return DialResult.Timeout
        } catch (failure: Throwable) {
            runCatching { channel?.close() }
            if (failure is CancellationException) throw failure
            currentCoroutineContext().ensureActive()
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

    private fun handshake(channel: SocketChannel, request: ByteArray): DialResult {
        val socket = channel.socket()
        socket.connect(proxyAddress, CONNECT_TIMEOUT_MILLIS)
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
        return DialResult.Connected(SocketFrameChannel(channel))
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

/** Parse bytes ourselves: even malformed literals must never reach a name resolver. */
private fun numericProxyAddress(host: String): InetAddress {
    fun invalid(): Nothing = throw IllegalArgumentException("SOCKS host must be a numeric IPv4 or IPv6 address")
    fun ipv4(text: String): List<Int> {
        val parts = text.split('.')
        if (parts.size != 4) invalid()
        return parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                (part.length > 1 && part[0] == '0')) invalid()
            part.toInt().also { if (it > 255) invalid() }
        }
    }

    val literal = if (host.startsWith('[') && host.endsWith(']')) host.substring(1, host.length - 1) else host
    if (':' !in literal) {
        if (literal != host) invalid()
        return InetAddress.getByAddress(ipv4(literal).map { it.toByte() }.toByteArray())
    }
    // An IPv4 suffix occupies the last two IPv6 groups. Zone/interface names are not accepted.
    val expanded = if ('.' in literal) {
        val suffixStart = literal.lastIndexOf(':') + 1
        val octets = ipv4(literal.substring(suffixStart))
        literal.substring(0, suffixStart) +
            ((octets[0] shl 8) or octets[1]).toString(16) + ":" +
            ((octets[2] shl 8) or octets[3]).toString(16)
    } else literal
    val halves = expanded.split("::")
    if (halves.size > 2) invalid()
    fun groups(text: String): List<Int> = if (text.isEmpty()) emptyList() else text.split(':').map { group ->
        if (group.length !in 1..4 || group.any { it !in "0123456789abcdefABCDEF" }) invalid()
        group.toInt(16)
    }
    val left = groups(halves[0])
    val right = if (halves.size == 2) groups(halves[1]) else emptyList()
    val count = left.size + right.size
    if (if (halves.size == 2) count >= 8 else count != 8) invalid()
    val words = left + List(8 - count) { 0 } + right
    return InetAddress.getByAddress(ByteArray(16) { index ->
        (words[index / 2] shr (if (index % 2 == 0) 8 else 0)).toByte()
    })
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
