package dev.junepark.oniontalkie.tor

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.junepark.oniontalkie.protocol.Frame
import dev.junepark.oniontalkie.protocol.FrameCodec
import dev.junepark.oniontalkie.protocol.FrameReader
import dev.junepark.oniontalkie.protocol.FrameWriter
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on ART: desktop socket adapters do not reproduce Android's shared blockingLock. */
@RunWith(AndroidJUnit4::class)
class TorDialerDuplexTest {
    @Test(timeout = 20_000)
    fun blockedReadDoesNotPreventSendingAFrame() = runBlocking {
        val ping = Frame.Ping(42u)
        val pong = Frame.Pong(42u, 43u)
        val receivedPing = CompletableDeferred<ByteArray>()
        val releaseRead = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val server = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        try {
            val received = executor.submit<ByteArray> {
                server.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val input = DataInputStream(peer.getInputStream())
                    val output = peer.getOutputStream()
                    fun readExactly(count: Int) = ByteArray(count).also { input.readFully(it) }
                    assertArrayEquals(byteArrayOf(5, 1, 0), readExactly(3))
                    output.write(byteArrayOf(5, 0))
                    val request = readExactly(5)
                    assertArrayEquals(byteArrayOf(5, 1, 0, 3), request.copyOf(4))
                    readExactly((request[4].toInt() and 0xff) + 2)
                    output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    // Send no tunnel bytes until the test has checked that PING arrived.
                    val bytes = readExactly(FrameCodec.encode(ping).size)
                    receivedPing.complete(bytes)
                    check(releaseRead.await(10, TimeUnit.SECONDS))
                    output.write(FrameCodec.encode(pong))
                    bytes
                }
            }
            val dialer: TorDialer = Socks5TorDialer(socksPort = server.localPort, timeoutMillis = 5_000)
            val result = dialer.connect("peer.onion", 7000)
            assertTrue("Expected a connected tunnel, got $result", result is DialResult.Connected)
            val channel = (result as DialResult.Connected).channel
            val readThread = CompletableDeferred<Thread>()
            val reader = async(Dispatchers.IO) {
                readThread.complete(Thread.currentThread())
                FrameReader(channel).read()
            }
            try {
                val thread = readThread.await()
                // Confirm entry into the actual blocking socket read before attempting the write.
                // This also matches the SocketChannelImpl.read beneath the old stream adapter.
                withTimeout(5_000) {
                    while (thread.stackTrace.none { it.className.contains("SocketChannel") && it.methodName == "read" }) {
                        assertFalse("Read must remain pending", reader.isCompleted)
                        delay(10)
                    }
                }
                withTimeout(5_000) { FrameWriter(channel).write(ping) }
                assertArrayEquals(FrameCodec.encode(ping), withTimeout(5_000) { receivedPing.await() })
                assertFalse("Peer has not released the read yet", reader.isCompleted)
                releaseRead.countDown()
                assertEquals(pong, withTimeout(5_000) { reader.await() })
                assertArrayEquals(FrameCodec.encode(ping), received.get(5, TimeUnit.SECONDS))
            } finally {
                releaseRead.countDown()
                channel.close()
                reader.cancelAndJoin()
            }
        } finally {
            releaseRead.countDown()
            server.close()
            executor.shutdownNow()
            assertTrue("Fake SOCKS peer must stop", executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
