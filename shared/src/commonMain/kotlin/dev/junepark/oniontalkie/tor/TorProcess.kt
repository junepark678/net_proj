package dev.junepark.oniontalkie.tor

import kotlinx.coroutines.flow.Flow

/** Paths and deadlines for one private Tor client. Pass an explicit executable on Android. */
data class TorConfig(
    val dataDir: String,
    val executablePath: String? = null,
    val startupTimeoutMillis: Long = DEFAULT_STARTUP_TIMEOUT_MILLIS,
    val shutdownTimeoutMillis: Long = DEFAULT_SHUTDOWN_TIMEOUT_MILLIS,
) {
    init {
        require(dataDir.isNotBlank()) { "Tor needs a data directory" }
        require(executablePath == null || executablePath.isNotBlank()) { "Tor executable path cannot be blank" }
        require(startupTimeoutMillis > 0) { "Tor startup timeout must be positive" }
        require(shutdownTimeoutMillis in 1..DEFAULT_SHUTDOWN_TIMEOUT_MILLIS) {
            "Tor shutdown timeout must be between 1 and 5000 ms"
        }
        listOf(dataDir, executablePath.orEmpty()).forEach { value ->
            require('\n' !in value && '\r' !in value && '\u0000' !in value) {
                "Tor paths cannot contain control characters"
            }
        }
    }

    /** Readable alias for callers that prefer the longer name. */
    val dataDirectory: String
        get() = dataDir

    /** Tor reads client authorization keys from a separate private directory. */
    val clientOnionAuthDirectory: String
        get() = "${dataDir.trimEnd('/', '\\')}/client-auth"

    companion object {
        const val DEFAULT_STARTUP_TIMEOUT_MILLIS = 45_000L
        const val DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 5_000L
    }
}

/** One line from Tor's notice-level stdout log. */
data class TorLogLine(val timestampMillis: Long, val text: String)

/** A Tor binary failed to start, exit, or expose its authenticated local listeners. */
class TorProcessException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Owns one Tor daemon. [start] returns only after the cookie-authenticated control port and
 * SOCKS listener have been discovered; its returned flow streams daemon logs. [stop] is
 * idempotent, reaps the child within five seconds, and permits a subsequent [start].
 */
expect class TorProcess(config: TorConfig) {
    /** Available after [start] succeeds; no fixed port is assumed. */
    val controlPort: Int

    /** Available after [start] succeeds; discovered through Tor's control protocol. */
    val socksPort: Int

    /** The private persistent directory used by Tor. */
    val dataDir: String

    /** Starts Tor and returns its bounded log stream once the local listeners are ready. */
    suspend fun start(): Flow<TorLogLine>

    /** Stops and reaps Tor. Safe to call more than once. */
    suspend fun stop()
}

/** Readable alias for callers that prefer the longer name. */
val TorProcess.dataDirectory: String
    get() = dataDir

/** Escapes a filesystem path for a quoted value in torrc. */
internal fun quoteTorrcPath(path: String): String {
    require(path.isNotBlank() && '\n' !in path && '\r' !in path && '\u0000' !in path) {
        "Tor configuration paths must be nonblank single-line values"
    }
    return "\"${path.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

/** Generated for each run so control and SOCKS ports are local-only and OS-assigned. */
internal fun renderTorrc(config: TorConfig, controlPortFile: String): String = buildString {
    appendLine("SocksPort 127.0.0.1:auto")
    appendLine("ControlPort 127.0.0.1:auto")
    appendLine("CookieAuthentication 1")
    appendLine("ControlPortWriteToFile ${quoteTorrcPath(controlPortFile)}")
    appendLine("DataDirectory ${quoteTorrcPath(config.dataDirectory)}")
    appendLine("Log notice stdout")
    append("ClientOnionAuthDir ${quoteTorrcPath(config.clientOnionAuthDirectory)}")
}
