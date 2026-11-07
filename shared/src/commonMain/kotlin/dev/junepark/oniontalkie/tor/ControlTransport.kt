package dev.junepark.oniontalkie.tor

/**
 * Line-oriented byte pipe to a Tor control port. Implementations own the socket;
 * [TorControl] never touches platform APIs. Lines are exchanged without the
 * trailing CRLF.
 */
interface ControlTransport {
    /** Reads the next line (CRLF stripped), or returns `null` once the peer closed the stream. */
    suspend fun readLine(): String?

    /** Writes [line] followed by CRLF and flushes. */
    suspend fun writeLine(line: String)

    /** Closes the connection; a blocked [readLine] must return or throw promptly. */
    fun close()
}
