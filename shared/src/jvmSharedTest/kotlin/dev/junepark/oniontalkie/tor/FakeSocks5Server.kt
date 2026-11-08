package dev.junepark.oniontalkie.tor

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** In-process SOCKS5 proxy on loopback that serves one connection with a test-provided script. */
internal class FakeSocks5Server(private val script: (Session) -> Unit) : Closeable {
    class Session(val socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        fun readExactly(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var received = 0
            while (received < count) {
                val read = input.read(bytes, received, count - received)
                check(read >= 0) { "client closed after $received of $count bytes" }
                received += read
            }
            return bytes
        }

        fun send(vararg bytes: Int) {
            output.write(ByteArray(bytes.size) { bytes[it].toByte() })
            output.flush()
        }

        /** Reads the client greeting and request, answering the greeting with "no authentication". */
        fun handshakeUntilRequest(): ByteArray {
            val greeting = readExactly(3)
            check(greeting.contentEquals(byteArrayOf(5, 1, 0))) { "unexpected greeting ${greeting.toList()}" }
            send(5, 0)
            val head = readExactly(5)
            check(head[3].toInt() == 3) { "request must use domain-name address type" }
            return head + readExactly((head[4].toInt() and 0xFF) + 2)
        }
    }

    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val done = CompletableFuture<Unit>()
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "fake-socks5") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    script(Session(socket))
                }
                done.complete(Unit)
            } catch (failure: Throwable) {
                done.completeExceptionally(failure)
            }
        }
    }

    /** Rethrows any failure of the script (assertion failures included). */
    fun awaitScript() { done.get(10, TimeUnit.SECONDS) }

    override fun close() { server.close() }
}
