package dev.junepark.oniontalkie.tor

/**
 * Splits a control-protocol line into space-separated tokens. Double-quoted sections may contain
 * spaces and backslash escapes; the quotes and escape characters are removed from the result, so
 * `SUMMARY="Done \"ok\""` becomes the single token `SUMMARY=Done "ok"`.
 */
internal fun splitArgs(text: String): List<String> {
    val tokens = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuote = false
    var started = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            inQuote && c == '\\' && i + 1 < text.length -> {
                i++
                cur.append(
                    when (val e = text[i]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> e
                    },
                )
            }
            c == '"' -> { inQuote = !inQuote; started = true }
            c == ' ' && !inQuote -> {
                if (started) tokens += cur.toString()
                cur.clear()
                started = false
            }
            else -> { cur.append(c); started = true }
        }
        i++
    }
    if (started) tokens += cur.toString()
    return tokens
}

/** `KEY=value` tokens of [tokens] as a map (keys are not required to be unique; last wins). */
internal fun keywordArgs(tokens: List<String>): Map<String, String> =
    tokens.mapNotNull { t -> t.indexOf('=').takeIf { it > 0 }?.let { t.substring(0, it) to t.substring(it + 1) } }.toMap()
