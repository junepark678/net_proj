package dev.junepark.oniontalkie.tor

/** A single control-port connection. TorControl owns and closes the supplied transport. */
interface ControlTransport {
    /** Reads a line without CRLF, or null at EOF. One reader and one writer may run concurrently. */
    suspend fun readLine(): String?

    /** Writes one line with CRLF and flushes it. [line] must not contain CR or LF. */
    suspend fun writeLine(line: String)

    /** Idempotently closes the connection, unblocking pending reads/writes. Must not suspend. */
    fun close()
}

/** The peer closed the connection, or the client was closed/cancelled. */
class TorConnectionClosedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An incomplete or malformed control-protocol message. */
class TorProtocolException(message: String) : Exception(message)

/** A complete negative command reply. The connection can still be used if Tor leaves it open. */
class TorCommandException(val reply: TorReply) : Exception(
    "Tor command failed (${reply.code}): ${reply.lines.joinToString("; ") { it.text }}",
) {
    /** Tor codes 514 (authentication required) and 515 (bad authentication). */
    val isAuthenticationFailure: Boolean get() = reply.code == 514 || reply.code == 515
}
