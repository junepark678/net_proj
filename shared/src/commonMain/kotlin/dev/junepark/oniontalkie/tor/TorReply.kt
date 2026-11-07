package dev.junepark.oniontalkie.tor

/** One status line and its optional, dot-unescaped data block. */
data class TorReplyLine(val text: String, val data: List<String>? = null)

/** A complete reply, including continuation lines and the final status line. */
data class TorReply(val code: Int, val lines: List<TorReplyLine>)

// https://spec.torproject.org/control-spec/message-format.html
internal class ReplyReader(private val transport: ControlTransport) {
    suspend fun read(onEvent: (TorReply) -> Unit): TorReply =
        message(transport.readLine() ?: throw TorConnectionClosedException("Tor control connection reached EOF"), onEvent)

    private suspend fun requiredLine(): String = transport.readLine()
        ?: throw TorProtocolException("Unexpected EOF inside a Tor reply")

    private suspend fun message(first: String, onEvent: (TorReply) -> Unit): TorReply {
        var line = first
        val code = header(first).first
        val lines = mutableListOf<TorReplyLine>()
        while (true) {
            val (lineCode, separator) = header(line)
            // Tor normally sends events between replies. Tolerate them between continuation
            // lines too, but never interpret data-block contents as status lines or events.
            if (lineCode == 650 && code != 650) {
                onEvent(message(line, onEvent))
                line = requiredLine()
                continue
            }
            if (lineCode != code) throw TorProtocolException("Reply status changed from $code to $lineCode")
            val data = if (separator == '+') {
                buildList<String> {
                    while (true) {
                        val dataLine = requiredLine()
                        if (dataLine == ".") break
                        add(if (dataLine.startsWith("..")) dataLine.drop(1) else dataLine)
                    }
                }
            } else null
            lines += TorReplyLine(line.substring(4), data)
            if (separator == ' ') return TorReply(code, lines)
            line = requiredLine()
        }
    }

    private fun header(line: String): Pair<Int, Char> {
        if (line.length < 4 || line.take(3).any { it !in '0'..'9' } || line[3] !in " -+") {
            throw TorProtocolException("Invalid Tor reply header")
        }
        val code = line.take(3).toInt()
        if (code !in 100..699) throw TorProtocolException("Invalid Tor reply status $code")
        return code to line[3]
    }
}
