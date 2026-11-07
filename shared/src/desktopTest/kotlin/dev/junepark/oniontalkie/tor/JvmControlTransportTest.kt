package dev.junepark.oniontalkie.tor

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

class JvmControlTransportTest {
    @Test fun rawCommandsValidateBeforeWritingAndPreserveCompleteReplies() = runBlocking {
        withTimeout(5_000) {
            ServerSocket(0).use { server ->
                val addOnion = "ADD_ONION NEW:ED25519-V3 Port=9999,127.0.0.1:9999 ClientAuthV3=peer-key"
                val peer = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        socket.soTimeout = 2_000
                        val reader = socket.getInputStream().bufferedReader()
                        val writer = socket.getOutputStream().bufferedWriter()
                        assertEquals(addOnion, reader.readLine())
                        writer.write("250-ServiceID=test-service\r\n250-PrivateKey=ED25519-V3:test-key\r\n250 OK\r\n")
                        writer.flush()
                        assertEquals("DEL_ONION missing", reader.readLine())
                        writer.write("552-Unknown service\r\n552 missing\r\n")
                        writer.flush()
                        assertEquals("GETINFO test/data", reader.readLine())
                        writer.write("251+test/data=\r\nfirst\r\n..dot\r\n.\r\n251 OK\r\n")
                        writer.flush()
                    }
                }
                val control = TorControl(JvmControlTransport.connect(port = server.localPort), this)
                try {
                    for (line in listOf("", " \t ", "GETINFO version\r", "GETINFO version\n", "GETINFO version\r\nSIGNAL SHUTDOWN")) {
                        assertFailsWith<IllegalArgumentException> { control.command(line) }
                    }
                    assertEquals(TorReply(250, listOf(
                        TorReplyLine("ServiceID=test-service"), TorReplyLine("PrivateKey=ED25519-V3:test-key"), TorReplyLine("OK"),
                    )), control.command(addOnion))
                    val error = assertFailsWith<TorCommandException> { control.command("DEL_ONION missing") }
                    assertEquals(TorReply(552, listOf(TorReplyLine("Unknown service"), TorReplyLine("missing"))), error.reply)
                    assertEquals(TorReply(251, listOf(
                        TorReplyLine("test/data=", listOf("first", ".dot")), TorReplyLine("OK"),
                    )), control.command("GETINFO test/data"))
                } finally { control.close() }
                peer.await()
            }
        }
    }

    @Test fun sendsCrLfAndReadsDataRepliesOverTcp() = runBlocking {
        withTimeout(5_000) {
            ServerSocket(0).use { server ->
                val peer = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        assertEquals("GETINFO version\r\n", socket.getInputStream().readNBytes(17).decodeToString())
                        socket.getOutputStream().write("250-version=test-tor\r\n250 OK\r\n".encodeToByteArray())
                        socket.getOutputStream().flush()
                    }
                }
                val control = TorControl(JvmControlTransport.connect(port = server.localPort), this)
                try { assertEquals("test-tor", control.getInfo("version")) } finally { control.close() }
                peer.await()
            }
        }
    }

    @Test fun cancellingReaderInterruptsSocketAndDoesNotLeakAThread() = runBlocking {
        withTimeout(5_000) {
            ServerSocket(0).use { server ->
                val transport = JvmControlTransport.connect(port = server.localPort)
                server.accept().use { peer ->
                    val reading = async(start = CoroutineStart.UNDISPATCHED) { transport.readLine() }
                    reading.cancelAndJoin()
                    // Interrupting SocketChannel I/O closes its underlying connection.
                    peer.soTimeout = 2_000
                    assertEquals(-1, runInterruptible(Dispatchers.IO) { peer.getInputStream().read() })
                    assertTrue(reading.isCancelled)
                }
                transport.close()
            }
        }
    }

    @Test fun scopeCancellationClosesAnIdleClient() = runBlocking {
        withTimeout(5_000) {
            ServerSocket(0).use { server ->
                val transport = JvmControlTransport.connect(port = server.localPort)
                server.accept().use { peer ->
                    val job = kotlinx.coroutines.Job()
                    val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + job)
                    TorControl(transport, scope)
                    job.cancelAndJoin()
                    peer.soTimeout = 2_000
                    assertEquals(-1, runInterruptible(Dispatchers.IO) { peer.getInputStream().read() })
                }
            }
        }
    }

    @Test fun refusesCommandInjectionAtTransportBoundary() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val transport = JvmControlTransport.connect(port = server.localPort)
            try {
                assertFailsWith<IllegalArgumentException> { transport.writeLine("GETINFO version\r\nSIGNAL SHUTDOWN") }
            } finally { transport.close() }
        }
    }
}
