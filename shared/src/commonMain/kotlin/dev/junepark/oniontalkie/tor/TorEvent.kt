package dev.junepark.oniontalkie.tor

/** A typed asynchronous notification from Tor (a 650 reply). */
sealed interface TorEvent {
    /** `STATUS_CLIENT ... BOOTSTRAP` progress report. [progress] is 0..100. */
    data class Bootstrap(val severity: String, val progress: Int, val tag: String?, val summary: String?) : TorEvent

    /**
     * `CIRC` circuit status change. [path] holds the relay specs (`$FINGERPRINT~nickname`) and is
     * empty while the circuit is only `LAUNCHED`. [args] has every `KEY=value` field
     * (`BUILD_FLAGS`, `TIME_CREATED`, ...).
     */
    data class Circuit(
        val id: String,
        val status: String,
        val path: List<String>,
        val purpose: String?,
        val reason: String?,
        val args: Map<String, String>,
    ) : TorEvent

    /** `HS_DESC` hidden-service descriptor lookup/upload progress (action is REQUESTED, FAILED, ...). */
    data class HsDesc(
        val action: String,
        val address: String,
        val authType: String?,
        val hsDir: String?,
        val descriptorId: String?,
        val reason: String?,
        val args: Map<String, String>,
    ) : TorEvent

    /** Any event this client does not model; [raw] is the first reply line. */
    data class Other(val keyword: String, val raw: String) : TorEvent
}

/** Parses a 650 [reply] into a [TorEvent]; malformed known events fall back to [TorEvent.Other]. */
internal fun parseEvent(reply: TorReply): TorEvent {
    val raw = reply.lines.firstOrNull().orEmpty()
    val tokens = splitArgs(raw)
    val keyword = tokens.firstOrNull().orEmpty()
    val parsed = when (keyword) {
        "STATUS_CLIENT" -> parseBootstrap(tokens.drop(1))
        "CIRC" -> parseCircuit(tokens)
        "HS_DESC" -> parseHsDesc(tokens)
        else -> null
    }
    return parsed ?: TorEvent.Other(keyword, raw)
}

/** Parses `SEVERITY BOOTSTRAP PROGRESS=n TAG=t SUMMARY=s ...`, also the value of `GETINFO status/bootstrap-phase`. */
internal fun parseBootstrap(tokens: List<String>): TorEvent.Bootstrap? {
    if (tokens.size < 2 || tokens[1] != "BOOTSTRAP") return null
    val args = keywordArgs(tokens)
    val progress = args["PROGRESS"]?.toIntOrNull() ?: return null
    return TorEvent.Bootstrap(tokens[0], progress, args["TAG"], args["SUMMARY"])
}

private fun parseCircuit(tokens: List<String>): TorEvent.Circuit? {
    if (tokens.size < 3) return null
    // The optional path is the first token that is not KEY=value (relay specs start with '$').
    val third = tokens.getOrNull(3)
    val hasPath = third != null && (third.startsWith("$") || '=' !in third)
    val args = keywordArgs(tokens.drop(if (hasPath) 4 else 3))
    val path = if (hasPath) third!!.split(',') else emptyList()
    return TorEvent.Circuit(tokens[1], tokens[2], path, args["PURPOSE"], args["REASON"], args)
}

private fun parseHsDesc(tokens: List<String>): TorEvent.HsDesc? {
    if (tokens.size < 3) return null
    val args = keywordArgs(tokens.drop(5))
    val descriptorId = tokens.getOrNull(5)?.takeIf { '=' !in it }
    return TorEvent.HsDesc(
        action = tokens[1],
        address = tokens[2],
        authType = tokens.getOrNull(3),
        hsDir = tokens.getOrNull(4),
        descriptorId = descriptorId,
        reason = args["REASON"],
        args = args,
    )
}
