package dev.junepark.oniontalkie.tor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Signals exposed by the control client. NEWNYM remains subject to Tor's rate limiting. */
enum class TorSignal { NEWNYM, SHUTDOWN }

/** Cookie-authenticated Tor control-port client. Construct with a fresh transport and an owned
 * coroutine scope. Cancelling that scope or calling [close] releases the transport. Commands
 * are serialized; cancelling an in-flight command closes the connection to preserve framing.
 * Callers can apply withTimeout to commands. No password or unauthenticated login is supported.
 */
class TorControl internal constructor(
    private val transport: ControlTransport,
    scope: CoroutineScope,
    private val clientNonce: () -> ByteArray,
) {
    constructor(transport: ControlTransport, scope: CoroutineScope) : this(transport, scope, ::secureControlNonce)

    private val lifetime = SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job])
    private val replies = Channel<TorReply>(1)
    private val commandMutex = Mutex()
    private val mutableBootstrap = MutableStateFlow(0)
    private val mutableEvents = MutableSharedFlow<TorEvent>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Latest known percentage, initially zero. StateFlow may conflate fast updates. */
    val bootstrap: StateFlow<Int> = mutableBootstrap.asStateFlow()

    /** Live events without replay. Slow consumers can lose old events after 64 queued events;
     * event collectors never block command replies or bootstrap updates. */
    val events: SharedFlow<TorEvent> = mutableEvents.asSharedFlow()

    init {
        val readerJob = CoroutineScope(scope.coroutineContext + lifetime).launch {
            try {
                val reader = ReplyReader(transport)
                while (true) {
                    val reply = reader.read(::onEvent)
                    if (reply.code == 650) {
                        onEvent(reply)
                    } else {
                        // Apply snapshots on the reader, in wire order. A command coroutine
                        // may resume after newer events have already arrived.
                        if (reply.code in 200..299) {
                            reply.lines.firstOrNull { it.text.startsWith("status/bootstrap-phase=") }?.let { line ->
                                val value = line.data?.joinToString("\n") ?: line.text.substringAfter('=')
                                parseBootstrapPhase(value)?.let { mutableBootstrap.value = it.progress }
                            }
                        }
                        replies.send(reply)
                    }
                }
            } catch (failure: Throwable) {
                terminate(if (failure is CancellationException) {
                    TorConnectionClosedException("Tor control scope was cancelled", failure)
                } else failure)
            }
        }
        readerJob.invokeOnCompletion { cause ->
            terminate(TorConnectionClosedException("Tor control reader stopped", cause))
        }
    }

    /** Negotiates SAFECOOKIE (preferred) or COOKIE using the 32 cookie bytes, not a pathname.
     * Subscribes to STATUS_CLIENT, CIRC and HS_DESC, then reads the current bootstrap phase
     * so connecting to an already bootstrapped Tor also reports 100. Call once on a fresh
     * connection before issuing other commands. COOKIE requires a trusted local endpoint.
     * A failed or malformed SAFECOOKIE challenge closes the connection without downgrading.
     */
    suspend fun authenticate(cookie: ByteArray) {
        require(cookie.size == 32) { "Tor authentication cookies must contain exactly 32 bytes" }
        commandMutex.withLock {
            try {
                val auth = authFields(exchange("PROTOCOLINFO 1"), "AUTH")
                val methods = auth["METHODS"]?.split(',').orEmpty()
                when {
                    "SAFECOOKIE" in methods -> {
                        val nonce = clientNonce()
                        check(nonce.size == 32) { "SAFECOOKIE client nonce must contain exactly 32 bytes" }
                        val challenge = authFields(exchange("AUTHCHALLENGE SAFECOOKIE ${nonce.toHex()}"), "AUTHCHALLENGE")
                        val serverHash = challenge.hexField("SERVERHASH")
                        val serverNonce = challenge.hexField("SERVERNONCE")
                        val message = cookie + nonce + serverNonce
                        val expected = hmacSha256(SERVER_KEY, message)
                        var difference = 0
                        for (i in expected.indices) {
                            difference = difference or (expected[i].toInt() xor serverHash[i].toInt())
                        }
                        if (difference != 0) throw TorProtocolException("SAFECOOKIE server proof did not verify")
                        exchange("AUTHENTICATE ${hmacSha256(CLIENT_KEY, message).toHex()}")
                    }
                    "COOKIE" in methods -> exchange("AUTHENTICATE ${cookie.toHex()}")
                    else -> throw TorProtocolException("Tor did not advertise a supported cookie authentication method")
                }
            } catch (failure: Throwable) {
                terminate(failure)
                throw failure
            }
        }
        setEvents(TorEventType.STATUS_CLIENT, TorEventType.CIRC, TorEventType.HS_DESC)
        getInfo("status/bootstrap-phase")
    }

    /** Returns one GETINFO value. Data-block line endings are normalized to LF, without the
     * protocol's terminating CRLF. Unknown keys raise [TorCommandException] (normally 552). */
    suspend fun getInfo(key: String): String {
        require(key.isNotEmpty() && key.all { it in '!'..'~' && it != '"' && it != '\\' }) {
            "GETINFO needs one nonempty key without whitespace, quotes or escapes"
        }
        val reply = command("GETINFO $key")
        val entry = reply.lines.firstOrNull { it.text.startsWith("$key=") }
            ?: throw TorProtocolException("GETINFO reply omitted the requested key")
        val value = if (entry.data != null) entry.data.joinToString("\n") else entry.text.substringAfter('=')
        return value
    }

    /** Replaces subscriptions. An empty argument list disables all events, including bootstrap. */
    suspend fun setEvents(vararg events: TorEventType) {
        command("SETEVENTS" + events.distinct().joinToString("") { " ${it.name}" })
    }

    /** Sends a signal and waits for its acknowledgement; does not wait for Tor's process exit. */
    suspend fun signal(signal: TorSignal) { command("SIGNAL ${signal.name}") }

    /** Closes this client and fails pending commands. Does not send SHUTDOWN to Tor. */
    fun close() { terminate(TorConnectionClosedException("Tor control client was closed")) }

    /** Sends one nonblank command line without CR/LF; the transport appends CRLF.
     * Returns the complete 2xx [TorReply], including its status and all continuation/data lines.
     * Negative replies throw [TorCommandException] with the complete reply and leave the
     * connection usable if Tor keeps it open. No command text or reply is logged.
     * Callers own command-specific formatting, including quoting and argument validation.
     */
    suspend fun command(line: String): TorReply {
        require(line.isNotBlank() && '\r' !in line && '\n' !in line) {
            "A control command must be a nonblank single line without CR or LF"
        }
        return commandMutex.withLock { exchange(line) }
    }

    // Caller holds commandMutex, including across the entire authentication handshake.
    private suspend fun exchange(line: String): TorReply {
        val reply = try {
            // Check terminal state before sending another command after EOF or cancellation.
            replies.tryReceive().let { result ->
                if (result.isClosed) throw result.exceptionOrNull()
                    ?: TorConnectionClosedException("Tor control connection was closed")
                if (result.isSuccess) throw TorProtocolException("Unexpected unsolicited Tor reply")
            }
            transport.writeLine(line)
            replies.receive()
        } catch (failure: Throwable) {
            terminate(failure)
            throw failure
        }
        if (reply.code !in 200..299) throw TorCommandException(reply)
        return reply
    }

    private fun onEvent(reply: TorReply) {
        val event = parseEvent(reply)
        if (event is TorEvent.Bootstrap) mutableBootstrap.value = event.progress
        mutableEvents.tryEmit(event)
    }

    private fun terminate(cause: Throwable) {
        replies.close(if (cause is CancellationException) {
            TorConnectionClosedException("Tor control command was cancelled", cause)
        } else cause)
        runCatching { transport.close() }
        lifetime.cancel()
    }

    private companion object {
        val SERVER_KEY = "Tor safe cookie authentication server-to-controller hash".encodeToByteArray()
        val CLIENT_KEY = "Tor safe cookie authentication controller-to-server hash".encodeToByteArray()
    }
}

private fun authFields(reply: TorReply, prefix: String): Map<String, String> {
    val line = reply.lines.singleOrNull { it.text.startsWith("$prefix ") && it.data == null }
        ?: throw TorProtocolException("Authentication reply omitted a unique $prefix line")
    val tokens = controlTokens(line.text)?.drop(1)
        ?: throw TorProtocolException("Malformed $prefix authentication reply")
    val fields = mutableMapOf<String, String>()
    for (token in tokens) {
        val key = token.substringBefore('=')
        if ('=' !in token || fields.put(key, token.substringAfter('=')) != null) {
            throw TorProtocolException("Malformed or duplicate $prefix authentication field")
        }
    }
    return fields
}

private fun Map<String, String>.hexField(name: String): ByteArray {
    val value = get(name)
    if (value == null || value.length != 64 || value.any { it.digitToIntOrNull(16) == null }) {
        throw TorProtocolException("SAFECOOKIE $name must contain exactly 32 hexadecimal bytes")
    }
    return value.hexToBytes()
}
