package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.protocol.ByeReason
import dev.junepark.oniontalkie.protocol.Frame
import dev.junepark.oniontalkie.protocol.FrameChannel
import dev.junepark.oniontalkie.protocol.FrameCodec
import dev.junepark.oniontalkie.protocol.ProtocolException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The call state machine: presence, dialing, one inbound session at a time, glare resolution,
 * keepalives and teardown. See [CallState] for the states and `docs/ARCHITECTURE.md` for the wire
 * protocol.
 *
 * ## Usage
 * Create one engine per app run with a long-lived [scope] (for example the service scope), collect
 * [state], and call the non-suspending commands from the UI. Commands only enqueue a request, so they
 * are safe from any thread (including a Compose callback); a command that is not valid in the current
 * state (for example [accept] while not ringing) is ignored.
 *
 * ## Concurrency
 * All state lives in one loop coroutine that handles one event at a time: commands, accepted
 * connections, dial results, received frames and timers all arrive as events tagged with the id of
 * the connection they belong to. An event for a connection that is no longer current (a late frame
 * after teardown, a stale timer) is therefore simply ignored, which is what makes simultaneous
 * hang-up/timeout/BYE/cancellation races safe. Teardown never blocks the loop: the state changes
 * first, and the transport's BYE and `close()` run on a detached closer scope. Each transport is
 * wrapped by [Connection], which guarantees its `close()` runs exactly once.
 *
 * ## Rules implemented here
 * - One session at a time. Any further inbound connection receives BYE(BUSY) and is closed.
 * - Dial: connect, write HELLO, then wait [CallEngineConfig.handshakeTimeoutMs] for ACCEPT.
 * - Glare: an inbound HELLO while dialing is the peer dialing us. The side with the lexicographically
 *   smaller normalized onion keeps its outbound stream and answers the inbound one with BYE(GLARE);
 *   the larger side drops its outbound stream (BYE(GLARE)) and accepts the inbound one without ringing.
 *   Because HELLO carries no onion, an inbound connection whose [IncomingConnection.peerOnion] is null
 *   is assumed to be the dialed peer (we only ever dial one peer at a time).
 * - Ringing: an inbound HELLO while idle rings for [CallEngineConfig.ringTimeoutMs] until [accept] or [decline].
 * - Keepalive: PING immediately and then every [CallEngineConfig.pingIntervalMs]; peer PINGs are answered
 *   with PONG. Only PONG and AUDIO count as liveness; [CallEngineConfig.inactivityTimeoutMs] without
 *   either ends the call with [EndReason.TIMEOUT].
 */
class CallEngine(
    private val scope: CoroutineScope,
    private val presence: PresenceService,
    private val dialer: CallDialer,
    private val displayName: String,
    private val media: CallMedia = CallMedia.Silent,
    private val config: CallEngineConfig = CallEngineConfig(),
    /** Monotonic milliseconds; injectable so tests can drive virtual time. */
    private val clockMs: () -> Long = monotonicClockMs(),
) {
    init {
        require(displayName.isNotBlank() && displayName.encodeToByteArray().size <= FrameCodec.MAX_NAME_BYTES) {
            "Display name must be 1..${FrameCodec.MAX_NAME_BYTES} UTF-8 bytes"
        }
    }

    private val mutableState = MutableStateFlow<CallState>(CallState.Offline())

    /** The current state; conflated, so collectors always see the latest. */
    val state: StateFlow<CallState> = mutableState.asStateFlow()

    // ---- Commands (thread-safe, non-suspending) ------------------------------------------------

    /** Starts presence (Offline → GoingOnline → Online). */
    fun goOnline() = post(Event.GoOnline)

    /** Ends any call, stops listening, and returns to Offline. */
    fun goOffline() = post(Event.GoOffline)

    /** Calls [onion] (`xxxx.onion` or the bare 56-character name). Valid while Online or PeerOffline. */
    fun dial(onion: String) = post(Event.Dial(onion))

    /** Answers the ringing call. */
    fun accept() = post(Event.Accept)

    /** Rejects the ringing call with BYE(NORMAL). */
    fun decline() = post(Event.Decline)

    /** Cancels a dial, declines a ring, or ends the call, whichever is current. */
    fun hangUp() = post(Event.HangUp)

    /** Clears [CallState.PeerOffline] and [CallState.Online.lastCall] back to a plain idle Online. */
    fun dismiss() = post(Event.Dismiss)

    /**
     * Stops the engine for good: ends everything like [goOffline], then waits until every transport
     * and the listener have been closed. Safe to call repeatedly or after the scope was cancelled.
     */
    suspend fun close() {
        loop.cancelAndJoin()
        closerJob.children.toList().joinAll()
    }

    // ---- Internals -----------------------------------------------------------------------------

    private sealed interface Event {
        data object GoOnline : Event
        data object GoOffline : Event
        class Dial(val onion: String) : Event
        data object Accept : Event
        data object Decline : Event
        data object HangUp : Event
        data object Dismiss : Event
        class Started(val gen: Long, val service: ListeningService) : Event
        class StartFailed(val gen: Long, val cause: Throwable) : Event
        class Incoming(val gen: Long, val connection: IncomingConnection) : Event
        class ListenerEnded(val gen: Long, val cause: Throwable?) : Event
        class DialDone(val attempt: Long, val outcome: DialOutcome) : Event
        class HelloSent(val connId: Long) : Event
        class Received(val connId: Long, val frame: Frame) : Event
        class Closed(val connId: Long, val cause: Throwable?) : Event
        class MediaFailed(val connId: Long, val cause: Throwable) : Event
        class Timer(val ownerId: Long, val kind: TimerKind, val seq: Long) : Event
    }

    private enum class TimerKind { HELLO_WAIT, HANDSHAKE, GLARE_WAIT, RING, INACTIVITY }

    private sealed interface Phase {
        data object Offline : Phase
        class Starting(val gen: Long, val job: Job) : Phase

        /** Online, not in a session. [peerOffline] selects the PeerOffline view of the same phase. */
        class Idle(val peerOffline: String? = null) : Phase

        /**
         * [conn] is the outbound stream once connected (null while the dialer works or after BYE(GLARE)
         * told us to wait for the peer's own call, [awaitingInbound]).
         */
        class Dialing(
            val attempt: Long,
            val peer: String,
            val job: Job,
            var conn: Connection? = null,
            var awaitingInbound: Boolean = false,
            var glareTimer: Job? = null,
        ) : Phase

        class Ringing(val conn: Connection, val peer: PeerInfo) : Phase

        class InCall(
            val conn: Connection,
            val peer: PeerInfo,
            val direction: CallDirection,
            val audio: Channel<Frame.Audio>,
            var lastActivityMs: Long,
        ) : Phase
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val mediaGate = Mutex()
    private val cleanupLatch = Mutex()

    // Closers outlive the engine's own scope so that cancelling it still closes every transport.
    private val closerJob = SupervisorJob()
    private val closerScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + closerJob)

    // Loop-confined state: touched only by handle() and, after the loop has ended, by finish().
    private var phase: Phase = Phase.Offline
    private var offlineError: String? = null
    private var lastCall: CallEnd? = null
    private var service: ListeningService? = null
    private var ownOnion: String = ""
    private var acceptJob: Job? = null
    private var pending: Connection? = null
    private var nextId = 0L
    private var generation = 0L

    // Must stay the last declaration: the loop may start running before the constructor returns.
    private val loop: Job = scope.launch {
        try {
            for (event in events) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failAll(e)
                }
            }
        } finally {
            finish()
        }
    }

    init {
        loop.invokeOnCompletion { finish() }
    }

    private fun post(event: Event) {
        if (events.trySend(event).isFailure) discard(event)
    }

    /** Releases the resource a never-handled event carries. */
    private fun discard(event: Event) {
        when (event) {
            is Event.Started -> closeService(event.service)
            is Event.Incoming -> closeRaw(event.connection.channel)
            is Event.DialDone -> (event.outcome as? DialOutcome.Connected)?.let { closeRaw(it.channel) }
            else -> Unit
        }
    }

    private fun handle(event: Event) {
        when (event) {
            Event.GoOnline -> if (phase is Phase.Offline) startOnline()
            Event.GoOffline -> if (phase !is Phase.Offline) {
                teardownAll()
                goOffline(null)
            }
            is Event.Dial -> if (phase is Phase.Idle) startDial(event.onion)
            Event.Accept -> (phase as? Phase.Ringing)?.let { acceptRinging(it) }
            Event.Decline -> (phase as? Phase.Ringing)?.let { endRinging(it, EndReason.LOCAL_HANGUP) }
            Event.HangUp -> when (val p = phase) {
                is Phase.Dialing -> finishDial(p, EndReason.LOCAL_HANGUP, ByeReason.NORMAL)
                is Phase.Ringing -> endRinging(p, EndReason.LOCAL_HANGUP)
                is Phase.InCall -> endCall(p, EndReason.LOCAL_HANGUP, ByeReason.NORMAL)
                else -> Unit
            }
            Event.Dismiss -> if (phase is Phase.Idle) {
                lastCall = null
                phase = Phase.Idle()
                publish()
            }
            is Event.Started -> onStarted(event)
            is Event.StartFailed -> {
                val p = phase
                if (p is Phase.Starting && p.gen == event.gen) goOffline(describe(event.cause))
            }
            is Event.Incoming -> onIncoming(event)
            is Event.ListenerEnded -> if (event.gen == generation && service != null) {
                teardownAll()
                goOffline(event.cause?.let(::describe) ?: "Listener closed")
            }
            is Event.DialDone -> onDialDone(event)
            is Event.HelloSent -> {
                val p = phase
                if (p is Phase.Dialing && p.conn?.id == event.connId) armHandshake(p.conn!!)
            }
            is Event.Received -> onReceived(event.connId, event.frame)
            is Event.Closed -> onClosed(event.connId, event.cause)
            is Event.MediaFailed -> {
                val p = phase
                if (p is Phase.InCall && p.conn.id == event.connId) {
                    endCall(p, EndReason.MEDIA_ERROR, ByeReason.NORMAL, describe(event.cause))
                }
            }
            is Event.Timer -> onTimer(event)
        }
    }

    // ---- Presence ------------------------------------------------------------------------------

    private fun startOnline() {
        val gen = ++generation
        val job = scope.launch {
            try {
                post(Event.Started(gen, presence.goOnline()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(Event.StartFailed(gen, e))
            }
        }
        phase = Phase.Starting(gen, job)
        offlineError = null
        publish()
    }

    private fun onStarted(event: Event.Started) {
        val p = phase
        if (p !is Phase.Starting || p.gen != event.gen) {
            closeService(event.service) // went offline while publishing
            return
        }
        val svc = event.service
        service = svc
        ownOnion = canonicalOnion(svc.onion) ?: svc.onion.trim().lowercase()
        val gen = event.gen
        acceptJob = scope.launch {
            try {
                while (true) {
                    val incoming = svc.accept() ?: break
                    post(Event.Incoming(gen, incoming))
                }
                post(Event.ListenerEnded(gen, null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(Event.ListenerEnded(gen, e))
            }
        }
        phase = Phase.Idle()
        publish()
    }

    // ---- Inbound -------------------------------------------------------------------------------

    private fun onIncoming(event: Event.Incoming) {
        val incoming = event.connection
        val p = phase
        if (event.gen != generation || service == null) {
            closeRaw(incoming.channel)
            return
        }
        val conn = newConnection(incoming.channel, outbound = false, peerOnion = incoming.peerOnion?.let(::canonicalOnion))
        val acceptable = pending == null && when (p) {
            is Phase.Idle -> true
            is Phase.Dialing -> conn.peerOnion == null || conn.peerOnion == p.peer
            else -> false
        }
        if (!acceptable) {
            terminate(conn, ByeReason.BUSY)
            return
        }
        // One slot for streams that have not said HELLO yet.
        pending = conn
        startReader(conn)
        armTimer(conn, TimerKind.HELLO_WAIT, config.helloWaitMs)
    }

    private fun onPendingFrame(conn: Connection, frame: Frame) {
        pending = null
        if (frame !is Frame.Hello) {
            terminate(conn, ByeReason.PROTOCOL_ERROR)
            return
        }
        when (val p = phase) {
            is Phase.Idle -> {
                phase = Phase.Ringing(conn, PeerInfo(conn.peerOnion, frame.displayName))
                armTimer(conn, TimerKind.RING, config.ringTimeoutMs)
                publish()
            }
            is Phase.Dialing ->
                // A caller known to be someone else than the dialed peer is not a glare partner.
                if (conn.peerOnion != null && conn.peerOnion != p.peer) terminate(conn, ByeReason.BUSY)
                else resolveGlare(p, conn, frame)
            else -> terminate(conn, ByeReason.BUSY)
        }
    }

    /** The peer is calling us while we call it. Compare onions: the smaller keeps its outbound stream. */
    private fun resolveGlare(dial: Phase.Dialing, inbound: Connection, hello: Frame.Hello) {
        if (ownOnion < dial.peer) {
            terminate(inbound, ByeReason.GLARE)
            return
        }
        dial.job.cancel()
        dial.glareTimer?.cancel()
        dial.conn?.let { terminate(it, ByeReason.GLARE) }
        startCall(inbound, PeerInfo(dial.peer, hello.displayName), CallDirection.INCOMING, sendAccept = true)
    }

    private fun acceptRinging(ringing: Phase.Ringing) {
        startCall(ringing.conn, ringing.peer, CallDirection.INCOMING, sendAccept = true)
    }

    private fun endRinging(ringing: Phase.Ringing, reason: EndReason, bye: ByeReason? = ByeReason.NORMAL) {
        terminate(ringing.conn, bye)
        lastCall = CallEnd(ringing.peer, reason, CallDirection.INCOMING)
        phase = Phase.Idle()
        publish()
    }

    // ---- Outbound ------------------------------------------------------------------------------

    private fun startDial(rawOnion: String) {
        val onion = canonicalOnion(rawOnion)
        if (onion == null || onion == ownOnion) {
            lastCall = CallEnd(PeerInfo(rawOnion, null), EndReason.INVALID_PEER, CallDirection.OUTGOING)
            phase = Phase.Idle()
            publish()
            return
        }
        val attempt = ++nextId
        val job = scope.launch {
            val outcome = try {
                withTimeoutOrNull(config.connectTimeoutMs) { dialer.connect(onion) } ?: DialOutcome.Timeout
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DialOutcome.Failed(describe(e))
            }
            post(Event.DialDone(attempt, outcome))
        }
        lastCall = null
        phase = Phase.Dialing(attempt, onion, job)
        publish()
    }

    private fun onDialDone(event: Event.DialDone) {
        val p = phase
        val outcome = event.outcome
        if (p !is Phase.Dialing || p.attempt != event.attempt) {
            discard(event) // cancelled or superseded; do not leak the stream
            return
        }
        when (outcome) {
            is DialOutcome.Connected -> {
                val conn = newConnection(outcome.channel, outbound = true, peerOnion = p.peer)
                p.conn = conn
                startReader(conn)
                conn.scope.launch {
                    try {
                        conn.send(Frame.Hello(FrameCodec.VERSION, displayName, config.opusRateHint))
                        post(Event.HelloSent(conn.id))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        post(Event.Closed(conn.id, e))
                    }
                }
            }
            DialOutcome.PeerOffline -> {
                lastCall = null
                phase = Phase.Idle(peerOffline = p.peer)
                publish()
            }
            DialOutcome.AuthRequired -> finishDial(p, EndReason.DIAL_FAILED, null, "Peer requires authorization")
            DialOutcome.Timeout -> finishDial(p, EndReason.DIAL_FAILED, null, "Timed out connecting")
            is DialOutcome.Failed -> finishDial(p, EndReason.DIAL_FAILED, null, outcome.message)
        }
    }

    /** Starts the ACCEPT wait; called once HELLO is on the wire. */
    private fun armHandshake(conn: Connection) = armTimer(conn, TimerKind.HANDSHAKE, config.handshakeTimeoutMs)

    private fun onDialingFrame(p: Phase.Dialing, conn: Connection, frame: Frame) {
        when (frame) {
            is Frame.Accept -> startCall(conn, PeerInfo(p.peer, null), CallDirection.OUTGOING, sendAccept = false)
            is Frame.Bye -> when (frame.reason) {
                ByeReason.GLARE -> {
                    if (ownOnion < p.peer) {
                        // The peer claims to be the smaller side, which contradicts our own comparison.
                        finishDial(p, EndReason.PROTOCOL_ERROR, null, "Unexpected BYE(GLARE)")
                    } else {
                        // We are the larger side: our stream loses; the peer's own call is on its way.
                        terminate(conn, null)
                        p.conn = null
                        p.awaitingInbound = true
                        p.glareTimer = scope.launch {
                            delay(config.handshakeTimeoutMs)
                            post(Event.Timer(p.attempt, TimerKind.GLARE_WAIT, 0))
                        }
                    }
                }
                ByeReason.BUSY -> finishDial(p, EndReason.PEER_BUSY, null)
                ByeReason.NORMAL -> finishDial(p, EndReason.PEER_DECLINED, null)
                ByeReason.VERSION -> finishDial(p, EndReason.VERSION_MISMATCH, null)
                ByeReason.PROTOCOL_ERROR -> finishDial(p, EndReason.PROTOCOL_ERROR, null)
            }
            else -> finishDial(p, EndReason.PROTOCOL_ERROR, ByeReason.PROTOCOL_ERROR, "Unexpected frame before ACCEPT")
        }
    }

    private fun finishDial(p: Phase.Dialing, reason: EndReason, bye: ByeReason?, detail: String? = null) {
        p.job.cancel()
        p.glareTimer?.cancel()
        p.conn?.let { terminate(it, bye) }
        lastCall = CallEnd(PeerInfo(p.peer, null), reason, CallDirection.OUTGOING, detail)
        phase = Phase.Idle()
        publish()
    }

    // ---- In call -------------------------------------------------------------------------------

    private fun startCall(conn: Connection, peer: PeerInfo, direction: CallDirection, sendAccept: Boolean) {
        val audio = Channel<Frame.Audio>(MAX_BUFFERED_AUDIO, BufferOverflow.DROP_OLDEST)
        val call = Phase.InCall(conn, peer, direction, audio, clockMs())
        (phase as? Phase.Dialing)?.let {
            it.job.cancel()
            it.glareTimer?.cancel()
        }
        phase = call
        lastCall = null
        armTimer(conn, TimerKind.INACTIVITY, config.inactivityTimeoutMs)
        val link = Link(conn, peer, audio, ::post)
        conn.scope.launch {
            try {
                // ACCEPT must precede every other frame we send.
                if (sendAccept) conn.send(Frame.Accept(FrameCodec.VERSION))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(Event.Closed(conn.id, e))
                return@launch
            }
            launch { keepAlive(conn) }
            launch {
                try {
                    mediaGate.withLock { media.run(link) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    post(Event.MediaFailed(conn.id, e))
                }
            }
        }
        publish()
    }

    private suspend fun keepAlive(conn: Connection) {
        try {
            while (true) {
                conn.send(Frame.Ping(clockMs().toUInt()))
                delay(config.pingIntervalMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            post(Event.Closed(conn.id, e))
        }
    }

    private fun onCallFrame(p: Phase.InCall, frame: Frame) {
        when (frame) {
            is Frame.Audio -> {
                p.lastActivityMs = clockMs()
                p.audio.trySend(frame)
            }
            is Frame.Pong -> p.lastActivityMs = clockMs()
            is Frame.Ping -> p.conn.scope.launch {
                try {
                    p.conn.send(Frame.Pong(frame.tsMs, clockMs().toUInt()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    post(Event.Closed(p.conn.id, e))
                }
            }
            is Frame.Bye -> endCall(p, byeToReason(frame.reason), null)
            is Frame.Hello, is Frame.Accept ->
                endCall(p, EndReason.PROTOCOL_ERROR, ByeReason.PROTOCOL_ERROR, "Unexpected handshake frame in call")
        }
    }

    private fun byeToReason(reason: ByeReason) = when (reason) {
        ByeReason.NORMAL -> EndReason.REMOTE_HANGUP
        ByeReason.VERSION -> EndReason.VERSION_MISMATCH
        ByeReason.PROTOCOL_ERROR -> EndReason.PROTOCOL_ERROR
        // BUSY/GLARE cannot legitimately end an established call.
        ByeReason.BUSY, ByeReason.GLARE -> EndReason.PROTOCOL_ERROR
    }

    private fun endCall(p: Phase.InCall, reason: EndReason, bye: ByeReason?, detail: String? = null) {
        p.audio.close()
        terminate(p.conn, bye)
        lastCall = CallEnd(p.peer, reason, p.direction, detail)
        phase = Phase.Idle()
        publish()
    }

    // ---- Frames, closes and timers -------------------------------------------------------------

    private fun onReceived(connId: Long, frame: Frame) {
        val p = phase
        when {
            pending?.id == connId -> onPendingFrame(pending!!, frame)
            p is Phase.Dialing && p.conn?.id == connId -> onDialingFrame(p, p.conn!!, frame)
            p is Phase.Ringing && p.conn.id == connId -> {
                if (frame is Frame.Bye) endRinging(p, EndReason.REMOTE_HANGUP, null)
                else endRinging(p, EndReason.PROTOCOL_ERROR, ByeReason.PROTOCOL_ERROR)
            }
            p is Phase.InCall && p.conn.id == connId -> onCallFrame(p, frame)
            // Anything else belongs to a connection that is already gone.
        }
    }

    private fun onClosed(connId: Long, cause: Throwable?) {
        val p = phase
        val reason = closeReason(cause)
        when {
            pending?.id == connId -> {
                terminate(pending!!, null)
                pending = null
            }
            p is Phase.Dialing && p.conn?.id == connId -> finishDial(p, reason, null, cause?.let(::describe))
            p is Phase.Ringing && p.conn.id == connId ->
                endRinging(p, if (cause == null) EndReason.REMOTE_HANGUP else reason, null)
            p is Phase.InCall && p.conn.id == connId -> endCall(p, reason, null, cause?.let(::describe))
        }
    }

    private fun closeReason(cause: Throwable?) = when {
        cause is ProtocolException && cause.error == ProtocolException.Error.VERSION -> EndReason.VERSION_MISMATCH
        cause is ProtocolException -> EndReason.PROTOCOL_ERROR
        else -> EndReason.CONNECTION_LOST
    }

    private fun onTimer(t: Event.Timer) {
        val p = phase
        when (t.kind) {
            TimerKind.GLARE_WAIT ->
                if (p is Phase.Dialing && p.attempt == t.ownerId && p.awaitingInbound) {
                    finishDial(p, EndReason.HANDSHAKE_TIMEOUT, null)
                }
            TimerKind.HELLO_WAIT -> pending?.takeIf { it.id == t.ownerId && it.timerIsCurrent(t.seq) }?.let {
                pending = null
                terminate(it, ByeReason.PROTOCOL_ERROR)
            }
            TimerKind.HANDSHAKE ->
                if (p is Phase.Dialing && p.conn?.id == t.ownerId && p.conn!!.timerIsCurrent(t.seq)) {
                    finishDial(p, EndReason.HANDSHAKE_TIMEOUT, ByeReason.NORMAL)
                }
            TimerKind.RING ->
                if (p is Phase.Ringing && p.conn.id == t.ownerId && p.conn.timerIsCurrent(t.seq)) {
                    endRinging(p, EndReason.RING_TIMEOUT)
                }
            TimerKind.INACTIVITY ->
                if (p is Phase.InCall && p.conn.id == t.ownerId && p.conn.timerIsCurrent(t.seq)) {
                    val idle = clockMs() - p.lastActivityMs
                    if (idle >= config.inactivityTimeoutMs) {
                        endCall(p, EndReason.TIMEOUT, ByeReason.NORMAL)
                    } else {
                        armTimer(p.conn, TimerKind.INACTIVITY, config.inactivityTimeoutMs - idle)
                    }
                }
        }
    }

    private fun armTimer(conn: Connection, kind: TimerKind, delayMs: Long) {
        conn.startTimer(delayMs) { seq -> post(Event.Timer(conn.id, kind, seq)) }
    }

    // ---- Teardown ------------------------------------------------------------------------------

    private fun newConnection(channel: FrameChannel, outbound: Boolean, peerOnion: String?) = Connection(
        id = ++nextId,
        raw = channel,
        engineContext = scope.coroutineContext,
        outbound = outbound,
        peerOnion = peerOnion,
        byeTimeoutMs = config.byeTimeoutMs,
        closeTimeoutMs = config.closeTimeoutMs,
    )

    private fun startReader(conn: Connection) {
        conn.readerScope.launch {
            try {
                while (true) {
                    val frame = conn.receive() ?: break
                    post(Event.Received(conn.id, frame))
                }
                post(Event.Closed(conn.id, null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(Event.Closed(conn.id, e))
            }
        }
    }

    /** Stops [conn]'s coroutines now; writes [bye] (bounded) and closes the transport in the background. */
    private fun terminate(conn: Connection, bye: ByeReason?) {
        conn.cancelWork()
        closerScope.launch { conn.shutdown(bye) }
    }

    private fun closeRaw(channel: FrameChannel) {
        closerScope.launch {
            withContext(NonCancellable) {
                try {
                    channel.close()
                } catch (_: Exception) {
                    // Nothing more can be done.
                }
            }
        }
    }

    private fun closeService(svc: ListeningService) {
        closerScope.launch {
            withContext(NonCancellable) {
                try {
                    svc.close()
                } catch (_: Exception) {
                    // Nothing more can be done.
                }
            }
        }
    }

    /** Ends every session and the listener, leaving [phase] to the caller. */
    private fun teardownAll() {
        pending?.let { terminate(it, ByeReason.NORMAL) }
        pending = null
        when (val p = phase) {
            is Phase.Starting -> p.job.cancel()
            is Phase.Dialing -> {
                p.job.cancel()
                p.glareTimer?.cancel()
                p.conn?.let { terminate(it, ByeReason.NORMAL) }
            }
            is Phase.Ringing -> terminate(p.conn, ByeReason.NORMAL)
            is Phase.InCall -> {
                p.audio.close()
                terminate(p.conn, ByeReason.NORMAL)
            }
            else -> Unit
        }
        acceptJob?.cancel()
        acceptJob = null
        service?.let(::closeService)
        service = null
        generation++ // invalidates anything the old listener or start attempt still posts
    }

    private fun goOffline(error: String?) {
        phase = Phase.Offline
        offlineError = error
        lastCall = null
        publish()
    }

    private fun failAll(cause: Exception) {
        teardownAll()
        goOffline("Internal error: ${describe(cause)}")
    }

    /** Final cleanup when the loop ends for any reason (close, scope cancellation). Runs once. */
    private fun finish() {
        if (!cleanupLatch.tryLock()) return
        events.close()
        while (true) {
            discard(events.tryReceive().getOrNull() ?: break)
        }
        if (phase !is Phase.Offline || service != null || pending != null) {
            teardownAll()
        }
        if (phase !is Phase.Offline) goOffline(null)
    }

    private fun publish() {
        mutableState.value = when (val p = phase) {
            Phase.Offline -> CallState.Offline(offlineError)
            is Phase.Starting -> CallState.GoingOnline
            is Phase.Idle ->
                if (p.peerOffline != null) CallState.PeerOffline(p.peerOffline)
                else CallState.Online(ownOnion, lastCall)
            is Phase.Dialing -> CallState.Dialing(p.peer)
            is Phase.Ringing -> CallState.Ringing(p.peer)
            is Phase.InCall -> CallState.InCall(p.peer, p.direction)
        }
    }

    private fun describe(t: Throwable) = t.message ?: t::class.simpleName ?: "error"

    private class Link(
        private val conn: Connection,
        override val peer: PeerInfo,
        audio: Channel<Frame.Audio>,
        private val post: (Event) -> Unit,
    ) : MediaLink {
        override val incomingAudio: Flow<Frame.Audio> = audio.receiveAsFlow()

        override suspend fun sendAudio(seq: UShort, tsMs: UInt, payload: ByteArray) {
            try {
                conn.send(Frame.Audio(seq, tsMs, payload))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(Event.Closed(conn.id, e)) // a dead stream ends the call as CONNECTION_LOST
                throw e
            }
        }
    }

    private companion object {
        const val MAX_BUFFERED_AUDIO = 64
    }
}

/** `abc….onion` in lowercase for a v3 onion address given with or without the suffix; null if malformed. */
internal fun canonicalOnion(text: String): String? {
    val name = text.trim().lowercase().removeSuffix(".onion")
    if (name.length != 56 || !name.all { it in 'a'..'z' || it in '2'..'7' }) return null
    return "$name.onion"
}

private fun monotonicClockMs(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
