package dev.junepark.oniontalkie.tor

/** Subscriptions supported by this client. SETEVENTS replaces the previous subscription. */
enum class TorEventType { STATUS_CLIENT, CIRC, HS_DESC, STREAM }

/** Parsed event fields plus the complete reply for extensions and metrics. Statuses remain
 * strings so future Tor versions can add values without breaking the event reader. */
sealed interface TorEvent {
    val raw: TorReply

    data class Bootstrap(
        val progress: Int,
        val tag: String?,
        val summary: String?,
        val fields: Map<String, String>,
        override val raw: TorReply,
    ) : TorEvent

    data class Circuit(
        val id: String,
        val status: String,
        val path: List<String>,
        val fields: Map<String, String>,
        override val raw: TorReply,
    ) : TorEvent

    data class HiddenServiceDescriptor(
        val action: String,
        val address: String,
        val authType: String,
        val directory: String,
        val descriptorId: String?,
        val fields: Map<String, String>,
        override val raw: TorReply,
    ) : TorEvent

    /** Unknown events and events missing required fields are preserved rather than fatal. */
    data class Unknown(override val raw: TorReply) : TorEvent
}

internal fun parseEvent(reply: TorReply): TorEvent {
    val tokens = controlTokens(reply.lines.firstOrNull()?.text.orEmpty()) ?: return TorEvent.Unknown(reply)
    when (tokens.firstOrNull()) {
        "STATUS_CLIENT" -> return parseBootstrap(tokens.drop(1), reply) ?: TorEvent.Unknown(reply)
        "CIRC" -> if (tokens.size >= 3) {
            // LongName uses either $fingerprint~nickname or $fingerprint=nickname.
            val path = tokens.getOrNull(3)?.takeIf { it.startsWith('$') || '=' !in it }
            return TorEvent.Circuit(
                tokens[1], tokens[2], path?.split(',').orEmpty(),
                fields(tokens.drop(if (path == null) 3 else 4)), reply,
            )
        }
        "HS_DESC" -> if (tokens.size >= 5) {
            return TorEvent.HiddenServiceDescriptor(
                tokens[1], tokens[2], tokens[3], tokens[4],
                tokens.getOrNull(5)?.takeUnless { '=' in it }, fields(tokens.drop(5)), reply,
            )
        }
    }
    return TorEvent.Unknown(reply)
}

internal fun parseBootstrapPhase(value: String): TorEvent.Bootstrap? =
    controlTokens(value)?.let { parseBootstrap(it, TorReply(250, listOf(TorReplyLine(value)))) }

private fun parseBootstrap(tokens: List<String>, raw: TorReply): TorEvent.Bootstrap? {
    if (tokens.size < 2 || tokens[1] != "BOOTSTRAP") return null
    val fields = fields(tokens.drop(2))
    val progress = fields["PROGRESS"]?.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
    return TorEvent.Bootstrap(progress, fields["TAG"], fields["SUMMARY"], fields, raw)
}

private fun fields(tokens: List<String>): Map<String, String> = tokens.filter { '=' in it }
    .associate { it.substringBefore('=') to it.substringAfter('=') }

// Tor emits both QuotedString and CString: tolerate C escapes per the protocol's guidance.
internal fun controlTokens(value: String): List<String>? {
    val result = mutableListOf<String>()
    val token = StringBuilder()
    var quoted = false
    var present = false
    var i = 0
    while (i < value.length) {
        val c = value[i++]
        when {
            c == '"' -> { quoted = !quoted; present = true }
            c == '\\' && quoted -> {
                if (i == value.length) return null
                val escaped = value[i++]
                token.append(when (escaped) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    in '0'..'7' -> {
                        var octal = escaped - '0'
                        var digits = 1
                        while (digits < 3 && i < value.length && value[i] in '0'..'7') {
                            octal = octal * 8 + (value[i++] - '0')
                            digits++
                        }
                        octal.toChar()
                    }
                    else -> escaped
                })
                present = true
            }
            c == ' ' && !quoted -> {
                if (present) { result += token.toString(); token.clear(); present = false }
            }
            else -> { token.append(c); present = true }
        }
    }
    if (quoted) return null
    if (present) result += token.toString()
    return result
}
