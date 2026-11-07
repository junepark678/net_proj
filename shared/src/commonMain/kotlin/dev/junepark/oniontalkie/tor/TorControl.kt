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
class TorControl(private val transport: ControlTransport, scope: CoroutineScope) {
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
                    if (reply.code == 650) onEvent(reply) else replies.send(reply)
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

    /** Authenticates with the 32 bytes read from Tor's cookie file (not its pathname).
     * Subscribes to STATUS_CLIENT, CIRC and HS_DESC, then reads the current bootstrap phase
     * so connecting to an already bootstrapped Tor also reports 100. Use only a trusted local
     * control endpoint: COOKIE authentication sends the cookie without server verification.
     */
    suspend fun authenticate(cookie: ByteArray) {
        require(cookie.size == 32) { "Tor authentication cookies must contain exactly 32 bytes" }
        val hex = "0123456789abcdef"
        val encoded = buildString(64) {
            for (byte in cookie) {
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4]); append(hex[value and 15])
            }
        }
        command("AUTHENTICATE $encoded")
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
        if (key == "status/bootstrap-phase") parseBootstrapPhase(value)?.let { mutableBootstrap.value = it.progress }
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

    private suspend fun command(line: String): TorReply = commandMutex.withLock {
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
        reply
    }

    private fun onEvent(reply: TorReply) {
        val event = parseEvent(reply)
        if (event is TorEvent.Bootstrap) mutableBootstrap.value = event.progress
        mutableEvents.tryEmit(event)
    }

    private fun terminate(cause: Throwable) {
        replies.close(cause)
        runCatching { transport.close() }
        lifetime.cancel()
    }
}
