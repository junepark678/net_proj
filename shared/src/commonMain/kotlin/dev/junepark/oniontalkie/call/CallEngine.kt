package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.protocol.ByeReason
import dev.junepark.oniontalkie.protocol.Frame
import dev.junepark.oniontalkie.protocol.FrameChannel
import dev.junepark.oniontalkie.protocol.FrameCodec
import dev.junepark.oniontalkie.protocol.FrameReader
import dev.junepark.oniontalkie.protocol.FrameWriter
import dev.junepark.oniontalkie.protocol.ProtocolException
import dev.junepark.oniontalkie.shared.audio.AudioConstants
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Suspendable, single-call controller for Compose/view-model callers. Collect state, goOnline,
 * call, accept/decline, hangUp, and goOffline. Commands return after admission; call negotiation
 * runs in the supplied scope. Illegal/obsolete call commands return false without side effects.
 * goOnline waits for publication and throws on failure; goOffline/close await resource release.
 * Cancelling the owner scope also releases presence, the call and media. close is terminal.
 * clockMillis must be monotonic; inject the test scheduler clock for deterministic deadlines.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class CallEngine(
    localOnion: String,
    displayName: String,
    private val presence: CallPresence,
    private val dialer: CallDialer,
    private val sessions: CallSessionFactory,
    scope: CoroutineScope,
    private val clockMillis: () -> Long = monotonicClock(),
) {
    private val localOnion = normalizeOnion(localOnion)
    private val hello = Frame.Hello(FrameCodec.VERSION, displayName, AudioConstants.BITRATE.toUInt())
        .also(FrameCodec::encode)
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val engineScope = CoroutineScope(scope.coroutineContext + lifetime)
    private val lifecycle = Mutex()
    private val guard = Mutex()
    private val mutableState = MutableStateFlow<CallState>(CallState.Offline)
    val state: StateFlow<CallState> = mutableState.asStateFlow()
    private var onlineJob: Job? = null // lifecycle lock
    private var onlineScope: CoroutineScope? = null // guard lock
    private var active: Session? = null // guard lock

    suspend fun goOnline() = lifecycle.withLock {
        check(lifetime.isActive) { "CallEngine is closed or its owner was cancelled" }
        if (onlineJob?.isActive == true) return@withLock
        val ready = CompletableDeferred<Unit>()
        // ATOMIC ensures finally executes even when the owner is cancelled before dispatch.
        val job = engineScope.launch(start = CoroutineStart.ATOMIC) { runOnline(ready) }
        onlineJob = job
        try {
            ready.await()
        } catch (failure: Exception) {
            withContext(NonCancellable) { job.cancelAndJoin() }
            throw failure
        }
    }

    /** Reusable shutdown, including from a cancelled UI coroutine. */
    suspend fun goOffline() = withContext(NonCancellable) {
        lifecycle.withLock {
            onlineJob?.cancelAndJoin()
            onlineJob = null
        }
    }

    /** Terminal, idempotent shutdown. */
    suspend fun close() = withContext(NonCancellable) {
        lifecycle.withLock {
            lifetime.cancel()
            onlineJob?.join()
            onlineJob = null
            lifetime.join()
        }
    }

    suspend fun call(peerOnion: String): Boolean {
        val peer = normalizeOnion(peerOnion)
        require(peer != localOnion) { "Cannot call this device" }
        return guard.withLock {
            val owner = onlineScope?.takeIf { it.isActive } ?: return@withLock false
            if (active != null) return@withLock false
            startSession(owner, Session(peer, outbound = true))
            true
        }
    }

    suspend fun accept(): Boolean = decide(true)
    suspend fun decline(): Boolean = decide(false)

    private suspend fun decide(accept: Boolean): Boolean = guard.withLock {
        val session = active ?: return@withLock false
        if (mutableState.value !is CallState.Ringing || session.end.isCompleted) return@withLock false
        val chosen = session.decision.complete(accept)
        if (chosen && !accept) session.end.complete(End(CallEndReason.DECLINED, ByeReason.NORMAL))
        chosen
    }

    /** Ends a pending or established call; waits until its transport and media have closed. */
    suspend fun hangUp(): Boolean = withContext(NonCancellable) {
        val session = guard.withLock {
            active?.also { it.end.complete(End(CallEndReason.NORMAL, ByeReason.NORMAL)) }
        } ?: return@withContext false
        session.job.join()
        true
    }

    private suspend fun runOnline(ready: CompletableDeferred<Unit>) {
        var listener: CallListener? = null
        try {
            listener = presence.open()
            coroutineScope {
                currentCoroutineContext().ensureActive()
                guard.withLock {
                    onlineScope = this
                    mutableState.value = CallState.Online()
                }
                ready.complete(Unit)
                while (true) admit(listener.accept(), this)
            }
        } catch (failure: Exception) {
            ready.completeExceptionally(failure)
        } finally {
            withContext(NonCancellable) {
                try { listener?.close() } catch (_: Exception) { /* Still reset UI on close failure. */ }
                guard.withLock {
                    onlineScope = null
                    active = null
                    mutableState.value = CallState.Offline
                }
            }
        }
    }

    private suspend fun admit(incoming: IncomingCall, owner: CoroutineScope) {
        val channel = OwnedChannel(incoming.channel)
        var transferred = false
        try {
            val peer = normalizeOnion(incoming.peerOnion)
            require(peer != localOnion) { "Incoming peer cannot be this device" }
            val rejection = guard.withLock {
                val previous = active
                when {
                    !owner.isActive -> ByeReason.NORMAL
                    previous == null -> {
                        transferred = true
                        startSession(owner, Session(peer, false, channel))
                        null
                    }
                    previous.outbound && mutableState.value is CallState.Dialing &&
                        previous.peer == peer && !previous.end.isCompleted -> {
                        if (localOnion < peer) ByeReason.GLARE else {
                            // Reserve the inbound slot before waiting for the outbound teardown.
                            previous.end.complete(End(CallEndReason.GLARE, ByeReason.GLARE))
                            transferred = true
                            startSession(owner, Session(peer, false, channel), previous.job)
                            null
                        }
                    }
                    else -> ByeReason.BUSY
                }
            }
            if (rejection != null) {
                withTimeoutOrNull(WRITE_TIMEOUT_MS) { FrameWriter(channel).write(Frame.Bye(rejection)) }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // An invalid identity or failed rejection must not take down the listener.
        } finally {
            if (!transferred) channel.closeSafely()
        }
    }

    // guard is held; ATOMIC guarantees each admitted session reaches its cleanup.
    private fun startSession(owner: CoroutineScope, session: Session, previous: Job? = null) {
        active = session
        mutableState.value = if (session.outbound) CallState.Dialing(session.peer)
            else CallState.Ringing(session.peer)
        session.job = owner.launch(start = CoroutineStart.ATOMIC) { runSession(session, previous) }
    }

    private suspend fun runSession(session: Session, previous: Job?) = coroutineScope {
        val worker = launch {
            try {
                previous?.join()
                protocol(session)
            } catch (_: TimeoutCancellationException) {
                session.end.complete(End(CallEndReason.TIMEOUT, ByeReason.NORMAL))
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: PeerOfflineException) {
                session.end.complete(End(CallEndReason.CONNECTION_LOST, peerOffline = true))
            } catch (_: ProtocolException) {
                session.end.complete(End(CallEndReason.PROTOCOL_ERROR))
            } catch (_: Exception) {
                session.end.complete(End(CallEndReason.FAILURE))
            }
        }
        try {
            session.end.await()
        } finally {
            withContext(NonCancellable) {
                session.end.complete(End(CallEndReason.CANCELLED))
                val end = session.end.await()
                // The reader is still live for a graceful BYE. Bound a blocked writer, then
                // close before joining so transport implementations can unblock pending I/O.
                try {
                    end.bye?.let { reason ->
                        withTimeoutOrNull(WRITE_TIMEOUT_MS) { session.write(Frame.Bye(reason), final = true) }
                    }
                } catch (_: Exception) { /* Best-effort BYE; transport release is mandatory. */ }
                worker.cancel()
                session.closeTransport()
                worker.join()
                try { session.media?.close() } catch (_: Exception) { /* Finish all other cleanup. */ }
                guard.withLock {
                    if (active === session) {
                        active = null
                        mutableState.value = when {
                            onlineScope?.isActive != true -> CallState.Offline
                            end.peerOffline -> CallState.PeerOffline(session.peer)
                            else -> CallState.Online(end.reason)
                        }
                    }
                }
            }
        }
    }

    private suspend fun protocol(session: Session) = coroutineScope {
        if (session.outbound) session.installTransport(OwnedChannel(dialer.dial(session.peer)))
        currentCoroutineContext().ensureActive()
        val reader = FrameReader(session.transport())
        val frames = Channel<Frame>(Channel.RENDEZVOUS)
        // The pump survives handshake/ringing deadline cancellation so cleanup can send BYE
        // before cancelling FrameReader (whose failure path also closes the channel).
        launch {
            try {
                while (true) frames.send(reader.read() ?: break)
                frames.close()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                frames.close(failure)
            }
        }
        val responseTimeoutMs = if (session.outbound) OUTBOUND_ACCEPT_TIMEOUT_MS else HANDSHAKE_TIMEOUT_MS
        val response = withTimeoutOrNull(responseTimeoutMs) {
            if (session.outbound) session.write(hello)
            frames.receiveCatching()
        }
        if (response == null) {
            session.end.complete(End(CallEndReason.TIMEOUT, ByeReason.NORMAL))
            return@coroutineScope
        }
        val first = response.getOrThrowOrNull()
        if (session.outbound) {
            if (first !is Frame.Accept) {
                session.finishUnexpected(first)
                return@coroutineScope
            }
        } else {
            if (first !is Frame.Hello) {
                session.finishUnexpected(first)
                return@coroutineScope
            }
            guard.withLock {
                if (active === session && !session.end.isCompleted) {
                    mutableState.value = CallState.Ringing(session.peer, first.displayName)
                }
            }
            val accepted = withTimeoutOrNull(RING_TIMEOUT_MS) {
                select {
                    session.decision.onAwait { it }
                    frames.onReceiveCatching {
                        session.finishUnexpected(it.getOrThrowOrNull())
                        false
                    }
                }
            }
            if (accepted != true) {
                session.end.complete(End(
                    if (accepted == null) CallEndReason.TIMEOUT else CallEndReason.DECLINED,
                    ByeReason.NORMAL,
                ))
                return@coroutineScope
            }
        }
        if (session.end.isCompleted) return@coroutineScope
        session.media = sessions.create(session.peer)
        if (!session.outbound) session.write(Frame.Accept(FrameCodec.VERSION))
        var lastActivity = clockMillis()
        val started = guard.withLock {
            if (active !== session || session.end.isCompleted) false else {
                mutableState.value = CallState.InCall(session.peer)
                true
            }
        }
        if (!started) return@coroutineScope
        // A common mutex protects timestamps on multi-threaded dispatchers too.
        val activity = Mutex()
        // A stalled output must not stop receiving PONG/AUDIO. Keep only the latest
        // pending echo instead of launching an unbounded coroutine per incoming PING.
        val pongRequests = Channel<UInt>(Channel.CONFLATED)
        launch {
            for (token in pongRequests) session.write(Frame.Pong(token, clockMillis().toUInt()))
        }
        launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                session.write(Frame.Ping(clockMillis().toUInt()))
            }
        }
        launch {
            while (true) {
                val remaining = activity.withLock { INACTIVITY_TIMEOUT_MS - (clockMillis() - lastActivity) }
                if (remaining <= 0) {
                    session.end.complete(End(CallEndReason.TIMEOUT, ByeReason.NORMAL))
                    break
                }
                delay(remaining)
            }
        }
        launch {
            try {
                checkNotNull(session.media).run { session.write(it) }
                session.end.complete(End(CallEndReason.NORMAL, ByeReason.NORMAL))
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                session.end.complete(End(CallEndReason.FAILURE, ByeReason.NORMAL))
            }
        }
        while (!session.end.isCompleted) {
            val frame = frames.receiveCatching().getOrThrowOrNull()
            if (frame != null) checkNotNull(session.media).onFrameReceived(frame)
            when (frame) {
                is Frame.Audio -> {
                    activity.withLock { lastActivity = clockMillis() }
                    checkNotNull(session.media).receive(frame)
                }
                is Frame.Pong -> activity.withLock { lastActivity = clockMillis() }
                is Frame.Ping -> pongRequests.trySend(frame.tsMs)
                else -> {
                    session.finishUnexpected(frame)
                    return@coroutineScope
                }
            }
        }
    }

    private data class End(val reason: CallEndReason, val bye: ByeReason? = null, val peerOffline: Boolean = false)

    private class Session(val peer: String, val outbound: Boolean, private var channel: OwnedChannel? = null) {
        lateinit var job: Job
        var media: CallSession? = null
        val end = CompletableDeferred<End>()
        val decision = CompletableDeferred<Boolean>()
        private val writes = Mutex()
        private val resources = Mutex()
        private var transportClosed = false

        // A successful dial can resume concurrently with teardown. Transfer ownership even
        // in a cancelled worker, and immediately release a result arriving after teardown.
        suspend fun installTransport(value: OwnedChannel) = withContext(NonCancellable) {
            resources.withLock {
                if (transportClosed) value.closeSafely() else channel = value
            }
        }

        suspend fun transport(): OwnedChannel = resources.withLock { checkNotNull(channel) }

        suspend fun closeTransport() = resources.withLock {
            transportClosed = true
            channel?.closeSafely()
        }

        suspend fun write(frame: Frame, final: Boolean = false) = writes.withLock {
            if (final || !end.isCompleted) {
                val transport = resources.withLock { channel.takeUnless { transportClosed } }
                transport?.let {
                    FrameWriter(it).write(frame)
                    media?.onFrameSent(frame)
                }
            }
        }

        fun finishUnexpected(frame: Frame?) {
            end.complete(when (frame) {
                null -> End(CallEndReason.CONNECTION_LOST)
                is Frame.Bye -> End(when (frame.reason) {
                    ByeReason.NORMAL -> CallEndReason.NORMAL
                    ByeReason.BUSY -> CallEndReason.BUSY
                    ByeReason.GLARE -> CallEndReason.GLARE
                    ByeReason.VERSION, ByeReason.PROTOCOL_ERROR -> CallEndReason.PROTOCOL_ERROR
                })
                else -> End(CallEndReason.PROTOCOL_ERROR, ByeReason.PROTOCOL_ERROR)
            })
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val RING_TIMEOUT_MS = 30_000L
        // Allow the full incoming ringing window plus one additional Tor transport round trip.
        const val OUTBOUND_ACCEPT_TIMEOUT_MS = RING_TIMEOUT_MS + ACCEPT_TRANSPORT_ALLOWANCE_MS
        private const val ACCEPT_TRANSPORT_ALLOWANCE_MS = 30_000L
        const val PING_INTERVAL_MS = 2_000L
        const val INACTIVITY_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_MS = 1_000L
    }
}

/** The protocol reader closes on failure; every other teardown path shares this once gate. */
private class OwnedChannel(private val delegate: FrameChannel) : FrameChannel {
    private val closing = Mutex()
    // FrameReader may write BYE(VERSION) itself while a UI decline is being sent.
    private val writing = Mutex()
    private var closed = false
    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int =
        delegate.read(destination, offset, length)
    override suspend fun write(bytes: ByteArray) = writing.withLock { delegate.write(bytes) }
    override suspend fun flush() = writing.withLock { delegate.flush() }
    override suspend fun close() = withContext(NonCancellable) {
        closing.withLock {
            if (!closed) {
                closed = true
                delegate.close()
            }
        }
    }

    suspend fun closeSafely() {
        try { close() } catch (_: Exception) { /* Never retry a failed close. */ }
    }
}

private fun <T> kotlinx.coroutines.channels.ChannelResult<T>.getOrThrowOrNull(): T? {
    exceptionOrNull()?.let { throw it }
    return getOrNull()
}

private fun monotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
