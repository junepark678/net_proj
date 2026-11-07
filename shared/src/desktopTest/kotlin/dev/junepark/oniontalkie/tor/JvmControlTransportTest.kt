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
