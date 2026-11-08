package dev.junepark.oniontalkie.tor

import dev.junepark.oniontalkie.protocol.Frame
import dev.junepark.oniontalkie.protocol.FrameReader
import dev.junepark.oniontalkie.protocol.FrameWriter
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class Socks5TorDialerTest {
    private val onion = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion"
    private val success = intArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(20_000) { block() } }

    @Test fun successfulHandshakeSendsDomainNameRequestAndCarriesFrames() = run {
        var request = ByteArray(0)
        val server = FakeSocks5Server { s ->
            request = s.handshakeUntilRequest()
            s.send(*success)
            // Echo one HELLO frame back.
            val hello = s.readExactly(1 + 1 + 2 + 3 + 4)
            s.output.write(hello)
            s.output.flush()
        }
        server.use {
            val result = Socks5TorDialer(socksPort = server.port).connect(onion, 7000)
            val channel = assertIs<DialResult.Connected>(result).channel
            assertTrue((channel as SocketFrameChannel).noDelay)
            val hello = Frame.Hello(1u, "Eve", 48000u)
            FrameWriter(channel).write(hello)
            assertEquals(hello, FrameReader(channel).read())
            channel.close()
            channel.close()
            server.awaitScript()
        }
        val name = onion.encodeToByteArray()
        assertContentEquals(
            byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((7000 shr 8).toByte(), (7000 and 0xFF).toByte()),
            request,
        )
    }

    @Test fun requestUsesDomainAddressTypeEvenForPortAbove32767() {
        val request = Socks5Wire.connectRequest("a.onion", 65535)
        assertContentEquals(byteArrayOf(5, 1, 0, 3, 7) + "a.onion".encodeToByteArray() + byteArrayOf(-1, -1), request)
    }

    @Test fun boundAddressOfEveryTypeIsConsumedWithoutLosingTunnelBytes() = run {
        val replies = listOf(
            intArrayOf(5, 0, 0, 1, 1, 2, 3, 4, 0, 80),
            intArrayOf(5, 0, 0, 4) + IntArray(16) { it } + intArrayOf(0, 80),
            intArrayOf(5, 0, 0, 3, 3, 'a'.code, 'b'.code, 'c'.code, 0, 80),
        )
        for (reply in replies) {
            val server = FakeSocks5Server { s ->
                s.handshakeUntilRequest()
                s.send(*reply, 'X'.code)
                s.readExactly(1)
            }
            server.use {
                val channel = assertIs<DialResult.Connected>(Socks5TorDialer(socksPort = server.port).connect(onion, 1)).channel
                val buffer = ByteArray(1)
                assertEquals(1, channel.read(buffer, 0, 1))
                assertEquals('X'.code.toByte(), buffer[0])
                channel.write(byteArrayOf(1))
                channel.flush()
                channel.close()
                server.awaitScript()
            }
        }
    }

    private fun replyFor(code: Int): DialResult = run {
        val server = FakeSocks5Server { s ->
            s.handshakeUntilRequest()
            s.send(5, code, 0, 1, 0, 0, 0, 0, 0, 0)
        }
        server.use { Socks5TorDialer(socksPort = server.port).connect(onion, 1234).also { server.awaitScript() } }
    }

    @Test fun peerOfflineReplies() {
        for (code in listOf(0xF0, 0xF2, 0xF3, 0xF7)) assertEquals(DialResult.PeerOffline, replyFor(code), "0x${code.toString(16)}")
    }

    @Test fun authRequiredReplies() {
        for (code in listOf(0xF4, 0xF5)) assertEquals(DialResult.AuthRequired, replyFor(code), "0x${code.toString(16)}")
    }

    @Test fun ttlExpiredIsTimeout() { assertEquals(DialResult.Timeout, replyFor(0x06)) }

    @Test fun otherRepliesAreErrorsCarryingTheReplyByte() {
        for (code in listOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x07, 0x08, 0xF1, 0xF6, 0x42)) {
            val result = assertIs<DialResult.Error>(replyFor(code), "0x${code.toString(16)}")
            assertEquals(code, result.code)
            assertTrue(result.message.isNotBlank())
        }
    }

    @Test fun silentProxyTimesOutAfterTheConfiguredDeadline() = run {
        val server = FakeSocks5Server { s -> s.readExactly(3); Thread.sleep(1_500) }
        server.use {
            val started = System.nanoTime()
            val result = Socks5TorDialer(socksPort = server.port, timeoutMillis = 300).connect(onion, 1)
            assertEquals(DialResult.Timeout, result)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_400, "must not wait for the proxy")
        }
    }

    @Test fun stallAfterTheGreetingTimesOut() = run {
        val server = FakeSocks5Server { s -> s.handshakeUntilRequest(); Thread.sleep(1_500) }
        server.use {
            assertEquals(DialResult.Timeout, Socks5TorDialer(socksPort = server.port, timeoutMillis = 300).connect(onion, 1))
        }
    }

    @Test fun defaultsMatchTheSpecification() {
        assertEquals(9050, Socks5TorDialer.DEFAULT_SOCKS_PORT)
        assertEquals(30_000L, TorDialer.DEFAULT_TIMEOUT_MILLIS)
    }

    @Test fun proxyHostnamesAndMalformedLiteralsAreRejectedAtConstruction() {
        for (host in listOf(
            "localhost", "proxy.invalid", "127.0.0.1.invalid", "", "127.1", "256.0.0.1",
            "127.00.0.1", "[127.0.0.1]", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9",
            "1::2::3", ":::1", "::gggg", "::ffff:256.0.0.1", "fe80::1%lo", "[::1",
        )) {
            assertFailsWith<IllegalArgumentException>(host) { Socks5TorDialer(socksHost = host) }
        }
    }

    @Test fun numericProxyAddressesAreAccepted() {
        for (host in listOf("127.0.0.1", "192.168.1.2", "::1", "[::1]", "::", "2001:db8::abcd",
            "1:2:3:4:5:6:7:8", "::ffff:127.0.0.1", "0:0:0:0:0:ffff:127.0.0.1")) {
            Socks5TorDialer(socksHost = host)
        }
    }

    @Test fun callerTimeoutRemainsCancellation() = run {
        val server = FakeSocks5Server { s ->
            s.readExactly(3)
            // Cancellation closes the channel rather than waiting for a handshake reply.
            assertEquals(-1, s.input.read())
        }
        server.use {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(500) {
                    Socks5TorDialer(socksPort = server.port, timeoutMillis = 10_000).connect(onion, 1)
                }
            }
            server.awaitScript()
        }
    }

    @Test fun invalidArgumentsFailWithoutConnecting() = run {
        val dialer = Socks5TorDialer(socksPort = ServerSocket(0).use { it.localPort })
        for ((name, port) in listOf("example.com" to 1, ".onion" to 1, "$onion\r" to 1, "é.onion" to 1, onion to 0, onion to 65536)) {
            val result = assertIs<DialResult.Error>(dialer.connect(name, port), name)
            assertEquals(TorDialer.ERROR_INVALID_ARGUMENT, result.code)
        }
    }

    @Test fun refusedSocksPortIsAnIoError() = run {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(TorDialer.ERROR_IO, assertIs<DialResult.Error>(Socks5TorDialer(socksPort = port).connect(onion, 1)).code)
    }

    @Test fun proxyHangUpDuringHandshakeIsAnIoError() = run {
        val server = FakeSocks5Server { s -> s.readExactly(3) }
        server.use {
            assertEquals(TorDialer.ERROR_IO, assertIs<DialResult.Error>(Socks5TorDialer(socksPort = server.port).connect(onion, 1)).code)
        }
    }

    @Test fun nonSocks5OrUnacceptableAuthIsAProtocolError() = run {
        for (greetingReply in listOf(intArrayOf(4, 0), intArrayOf(5, 0xFF), intArrayOf(5, 2))) {
            val server = FakeSocks5Server { s -> s.readExactly(3); s.send(*greetingReply) }
            server.use {
                val result = assertIs<DialResult.Error>(Socks5TorDialer(socksPort = server.port).connect(onion, 1))
                assertEquals(TorDialer.ERROR_PROTOCOL, result.code)
            }
        }
        val server = FakeSocks5Server { s -> s.handshakeUntilRequest(); s.send(5, 0, 0, 9, 0, 0) }
        server.use {
            assertEquals(TorDialer.ERROR_PROTOCOL, assertIs<DialResult.Error>(Socks5TorDialer(socksPort = server.port).connect(onion, 1)).code)
        }
    }
}
