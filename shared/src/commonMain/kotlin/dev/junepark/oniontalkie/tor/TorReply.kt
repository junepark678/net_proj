package dev.junepark.oniontalkie.tor

/**
 * One complete control-port reply.
 *
 * @property status the three-digit status code (250 OK, 251 unnecessary, 4xx/5xx errors, 650 async event).
 * @property lines the text of each reply line without the status code and separator. A `250+` data
 * line is stored as its header, a `'\n'`, then the data lines joined with `'\n'` (dot-unstuffed).
 */
data class TorReply(val status: Int, val lines: List<String>) {
    /** `true` for 2xx replies (including 251, "operation was unnecessary"). */
    val isSuccess: Boolean get() = status in 200..299

    /** `true` for 6xx asynchronous event notifications. */
    val isEvent: Boolean get() = status in 600..699
}

/** Base class of all failures raised by [TorControl]. */
open class TorControlException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Tor answered a command with a 4xx or 5xx [status]. */
class TorCommandException(val status: Int, message: String) :
    TorControlException("Tor replied $status: $message")

/** The control connection is closed (or closing) and cannot carry further commands. */
class TorConnectionClosedException(message: String = "control connection closed", cause: Throwable? = null) :
    TorControlException(message, cause)

/** Tor sent something that is not valid control-protocol syntax. */
class TorProtocolException(message: String) : TorControlException(message)

/**
 * Incremental reply assembler: feed it one line at a time and it returns a [TorReply] when the
 * final line (`NNN text`) of a reply arrives. Handles `NNN-` mid lines and `NNN+` data blocks.
 */
internal class ReplyParser {
    private val lines = mutableListOf<String>()
    private var dataHeader: String? = null
    private val data = mutableListOf<String>()

    fun feed(line: String): TorReply? {
        val header = dataHeader
        if (header != null) {
            if (line == ".") {
                lines += header + "\n" + data.joinToString("\n")
                dataHeader = null
                data.clear()
            } else {
                data += if (line.startsWith("..")) line.substring(1) else line
            }
            return null
        }
        if (line.length < 4 || !line.substring(0, 3).all { it in '0'..'9' }) {
            throw TorProtocolException("malformed reply line: $line")
        }
        val status = line.substring(0, 3).toInt()
        val text = line.substring(4)
        when (line[3]) {
            '-' -> lines += text
            '+' -> dataHeader = text
            ' ' -> {
                lines += text
                return TorReply(status, lines.toList()).also { lines.clear() }
            }
            else -> throw TorProtocolException("malformed reply line: $line")
        }
        return null
    }
}
