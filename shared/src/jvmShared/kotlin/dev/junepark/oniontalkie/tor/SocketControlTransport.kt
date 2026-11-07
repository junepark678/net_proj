package dev.junepark.oniontalkie.tor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket

/** [ControlTransport] over a plain `java.net.Socket` (shared by the Android and desktop targets). */
class SocketControlTransport private constructor(private val socket: Socket) : ControlTransport {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer: Writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

    override suspend fun readLine(): String? = withContext(Dispatchers.IO) { reader.readLine() }

    override suspend fun writeLine(line: String) = withContext(Dispatchers.IO) {
        writer.write(line)
        writer.write("\r\n")
        writer.flush()
    }

    override fun close() = socket.close()

    companion object {
        /** Connects to a control port, typically `127.0.0.1:9051`. */
        suspend fun connect(host: String, port: Int, timeoutMs: Int = 10_000): SocketControlTransport =
            withContext(Dispatchers.IO) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                } catch (e: Exception) {
                    socket.close()
                    throw e
                }
                SocketControlTransport(socket)
            }
    }
}
