package dev.junepark.oniontalkie.tor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Authentication methods and cookie location advertised by `PROTOCOLINFO`. */
data class ProtocolInfo(val methods: Set<String>, val cookieFile: String?)

/** Signals accepted by [TorControl.signal]. */
enum class TorSignal { NEWNYM, SHUTDOWN }

/**
 * Coroutine client for the Tor control protocol over a [ControlTransport].
 *
 * A reader coroutine launched in [scope] reads every reply: 6xx replies become [events] (and
 * update [bootstrap]); all others complete the oldest outstanding command, so commands may be
 * issued concurrently and are answered in order. When the connection ends, outstanding and future
 * commands fail with [TorConnectionClosedException].
 *
 * Typical use: [protocolInfo] -> [authenticate] with the cookie bytes read from
 * [ProtocolInfo.cookieFile] -> [setEvents] -> [refreshBootstrap] -> collect [bootstrap].
 *
 * @param random source of the SAFECOOKIE client nonce; the default is fine for the handshake
 * (the nonce only needs to be unpredictable to a peer that can't read the cookie file).
 */
class TorControl(
    private val transport: ControlTransport,
    scope: CoroutineScope,
    private val random: Random = Random.Default,
) {
    private val _bootstrap = MutableStateFlow(0)

    /** Latest bootstrap percentage (0..100), from `STATUS_CLIENT BOOTSTRAP` events and [refreshBootstrap]. */
    val bootstrap: StateFlow<Int> = _bootstrap.asStateFlow()

    private val _events = MutableSharedFlow<TorEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Typed asynchronous events (bootstrap, [TorEvent.Circuit], [TorEvent.HsDesc], ...) for the
     * event types enabled with [setEvents]. Hot: collectors only see events emitted after they subscribe.
     */
    val events: SharedFlow<TorEvent> = _events.asSharedFlow()

    private val writeLock = Mutex()
    private val pending = Channel<CompletableDeferred<TorReply>>(Channel.UNLIMITED)
    private var closedCause: TorConnectionClosedException? = null // guarded by writeLock
    private val readerJob: Job = scope.launch { readLoop() }

    /** Asks Tor which authentication methods it accepts and where its cookie file is. */
    suspend fun protocolInfo(): ProtocolInfo {
        val reply = command("PROTOCOLINFO 1")
        val auth = reply.lines.firstOrNull { it.startsWith("AUTH ") }
            ?: throw TorProtocolException("PROTOCOLINFO reply without AUTH line")
        val args = keywordArgs(splitArgs(auth))
        return ProtocolInfo(
            methods = args["METHODS"]?.split(',')?.toSet().orEmpty(),
            cookieFile = args["COOKIEFILE"],
        )
    }

    /**
     * Authenticates with the 32-byte control [cookie]. Uses SAFECOOKIE (which also proves to us that
     * Tor knows the cookie) when [methods] allows it, otherwise plain COOKIE. Pass
     * [ProtocolInfo.methods] from [protocolInfo]; the default tries SAFECOOKIE only.
     *
     * @throws TorCommandException if Tor rejects the credentials (e.g. 515).
     * @throws TorControlException if the server's SAFECOOKIE proof does not verify.
     */
    suspend fun authenticate(cookie: ByteArray, methods: Set<String> = setOf("SAFECOOKIE")) {
        if ("SAFECOOKIE" in methods) {
            val clientNonce = random.nextBytes(32)
            val reply = command("AUTHCHALLENGE SAFECOOKIE ${clientNonce.toHex()}")
            val args = keywordArgs(splitArgs(reply.lines.firstOrNull().orEmpty()))
            val serverHash = args["SERVERHASH"]
            val serverNonce = args["SERVERNONCE"]
            if (serverHash == null || serverNonce == null) throw TorProtocolException("malformed AUTHCHALLENGE reply")
            val message = cookie + clientNonce + serverNonce.hexToBytes()
            val expected = hmacSha256(SERVER_KEY, message).toHex()
            if (!constantTimeEquals(expected, serverHash.lowercase())) {
                throw TorControlException("Tor's SAFECOOKIE server hash did not verify")
            }
            command("AUTHENTICATE ${hmacSha256(CLIENT_KEY, message).toHex()}")
        } else if ("COOKIE" in methods) {
            command("AUTHENTICATE ${cookie.toHex()}")
        } else {
            throw TorControlException("no supported authentication method in $methods")
        }
    }

    /** `GETINFO key`: returns the value (for `250+` data replies, the data lines joined with `'\n'`). */
    suspend fun getInfo(key: String): String {
        val reply = command("GETINFO $key")
        val prefix = "$key="
        val line = reply.lines.firstOrNull { it.startsWith(prefix) }
            ?: throw TorProtocolException("GETINFO reply lacks $key")
        return line.removePrefix(prefix).removePrefix("\n")
    }

    /** `SIGNAL`: [TorSignal.NEWNYM] for fresh circuits, [TorSignal.SHUTDOWN] to stop Tor gracefully. */
    suspend fun signal(signal: TorSignal) {
        command("SIGNAL ${signal.name}")
    }

    /**
     * `SETEVENTS`: replaces the set of subscribed event types, e.g. [DEFAULT_EVENTS]. An empty
     * collection unsubscribes from everything.
     */
    suspend fun setEvents(events: Collection<String>) {
        command(("SETEVENTS " + events.joinToString(" ")).trimEnd())
    }

    /** Reads `status/bootstrap-phase` into [bootstrap]; needed when Tor had already bootstrapped before we subscribed. */
    suspend fun refreshBootstrap() {
        parseBootstrap(splitArgs(getInfo("status/bootstrap-phase")))?.let { _bootstrap.value = it.progress }
    }

    /**
     * Sends one raw command and returns its reply.
     *
     * @throws TorCommandException on 4xx/5xx replies.
     * @throws TorConnectionClosedException if the connection is gone.
     * @throws IllegalArgumentException if [line] contains CR or LF (command injection guard).
     */
    suspend fun command(line: String): TorReply {
        require('\r' !in line && '\n' !in line) { "control command must be a single line" }
        val answer = CompletableDeferred<TorReply>()
        writeLock.withLock {
            closedCause?.let { throw it }
            pending.trySend(answer)
            try {
                transport.writeLine(line)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cause = TorConnectionClosedException("write failed: ${e.message}", e)
                shutdown(cause)
                throw cause
            }
        }
        val reply = answer.await()
        if (!reply.isSuccess) throw TorCommandException(reply.status, reply.lines.joinToString("; "))
        return reply
    }

    /** Closes the transport and stops the reader; later commands fail with [TorConnectionClosedException]. */
    suspend fun close() {
        transport.close()
        readerJob.cancel()
        withContext(NonCancellable) { writeLock.withLock { shutdown(TorConnectionClosedException()) } }
    }

    private suspend fun readLoop() {
        val parser = ReplyParser()
        var cause = TorConnectionClosedException("control connection closed by Tor")
        try {
            while (true) {
                val line = transport.readLine() ?: break
                val reply = parser.feed(line) ?: continue
                if (reply.isEvent) dispatchEvent(reply) else pending.tryReceive().getOrNull()?.complete(reply)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cause = TorConnectionClosedException("control connection failed: ${e.message}", e)
        } finally {
            withContext(NonCancellable) { writeLock.withLock { shutdown(cause) } }
        }
    }

    private fun dispatchEvent(reply: TorReply) {
        val event = parseEvent(reply)
        if (event is TorEvent.Bootstrap) _bootstrap.value = event.progress
        _events.tryEmit(event)
    }

    /** Must hold [writeLock]. Fails every outstanding command and rejects new ones. */
    private fun shutdown(cause: TorConnectionClosedException) {
        if (closedCause == null) closedCause = cause
        val failure = closedCause!!
        while (true) {
            val waiting = pending.tryReceive().getOrNull() ?: break
            waiting.completeExceptionally(failure)
        }
        transport.close()
    }

    companion object {
        /** Event types used by Onion Talkie: bootstrap progress, circuits and hidden-service descriptors. */
        val DEFAULT_EVENTS: List<String> = listOf("STATUS_CLIENT", "CIRC", "HS_DESC")

        private val SERVER_KEY = "Tor safe cookie authentication server-to-controller hash".encodeToByteArray()
        private val CLIENT_KEY = "Tor safe cookie authentication controller-to-server hash".encodeToByteArray()

        private fun constantTimeEquals(a: String, b: String): Boolean {
            var diff = a.length xor b.length
            for (i in 0 until minOf(a.length, b.length)) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
