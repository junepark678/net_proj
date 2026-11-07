package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.protocol.Frame
import dev.junepark.oniontalkie.protocol.FrameChannel
import dev.junepark.oniontalkie.protocol.FrameReader
import dev.junepark.oniontalkie.protocol.FrameWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime

/** Valid-looking v3 onion names: lexicographic order SMALL < MID < BIG. */
val SMALL = "b".repeat(56) + ".onion"
val MID = "m".repeat(56) + ".onion"
val BIG = "y".repeat(56) + ".onion"

class FakeIoException(message: String) : Exception(message)

/** One end of an in-memory duplex stream; counts how often [close] reaches the "transport". */
class FakeChannel(val label: String) : FrameChannel {
    lateinit var peer: FakeChannel
        private set
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var current: ByteArray? = null
    private var position = 0
    var closeCount = 0
        private set
    val closed get() = closeCount > 0

    /** When set, the next [write] suspends forever (a stuck transport). */
    var stallWrites = false

    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (closed) return -1
        var buffer = current
        if (buffer == null || position >= buffer.size) {
            buffer = inbox.receiveCatching().getOrNull() ?: return -1
            current = buffer
            position = 0
        }
        // Hand out at most 3 bytes at a time to exercise partial reads.
        val count = minOf(length, buffer.size - position, 3)
        buffer.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun write(bytes: ByteArray) {
        if (stallWrites) awaitCancellation()
        if (closed || peer.closed) throw FakeIoException("$label write on closed stream")
        peer.inbox.send(bytes.copyOf())
    }

    override suspend fun flush() = Unit

    override suspend fun close() {
        closeCount++
        inbox.cancel()
        peer.inbox.close()
    }

    companion object {
        /** Returns (engine side, far side). */
        fun pair(name: String): Pair<FakeChannel, FakeChannel> {
            val a = FakeChannel("$name/engine")
            val b = FakeChannel("$name/far")
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}

/** The far end of a [FakeChannel], decoding everything the engine sends into [received]. */
class Remote(val channel: FakeChannel, scope: CoroutineScope, val engineSide: FakeChannel = channel.peer) {
    val received = mutableListOf<Frame>()
    var endOfStream = false
        private set
    private val reader = FrameReader(channel)
    private val writer = FrameWriter(channel)

    init {
        scope.launch {
            try {
                while (true) received += reader.read() ?: break
            } catch (_: Exception) {
            }
            endOfStream = true
        }
    }

    suspend fun send(frame: Frame) = writer.write(frame)

    /** Closes the far end the way a peer dropping the connection would. */
    suspend fun drop() = channel.close()

    val pings get() = received.filterIsInstance<Frame.Ping>()
    val byes get() = received.filterIsInstance<Frame.Bye>()
}

class FakeListening(override val onion: String) : ListeningService {
    val incoming = Channel<IncomingConnection>(Channel.UNLIMITED)
    var closeCount = 0
        private set
    var acceptFailure: Exception? = null

    override suspend fun accept(): IncomingConnection? {
        acceptFailure?.let { throw it }
        val result = incoming.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        return result.getOrNull()
    }

    override suspend fun close() {
        closeCount++
        incoming.close()
    }

    /** Simulates the listener dying with [error]. */
    fun fail(error: Exception) {
        acceptFailure = error
        incoming.close(error)
    }
}

class FakePresence(private val listening: FakeListening) : PresenceService {
    /** When set, [goOnline] waits for it; completes with the result. */
    var gate: CompletableDeferred<Unit>? = null
    var failure: Exception? = null
    var swallowCancellation = false
    var calls = 0
        private set

    override suspend fun goOnline(): ListeningService {
        calls++
        try {
            gate?.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!swallowCancellation) throw e
            gate?.let { withContext(NonCancellable) { it.await() } }
        }
        failure?.let { throw it }
        return listening
    }
}

class FakeDialer : CallDialer {
    val dialed = mutableListOf<String>()
    var script: suspend (String) -> DialOutcome = { DialOutcome.PeerOffline }

    override suspend fun connect(onion: String): DialOutcome {
        dialed += onion
        return script(onion)
    }
}

/** Records what the engine hands the media and lets tests send audio and fail it. */
class RecordingMedia : CallMedia {
    var runs = 0
        private set
    var running = 0
        private set
    var maxConcurrent = 0
        private set
    var cleanups = 0
        private set
    val audio = mutableListOf<Frame.Audio>()
    var link: MediaLink? = null
        private set
    var failure: Exception? = null

    override suspend fun run(link: MediaLink) {
        runs++
        running++
        maxConcurrent = maxOf(maxConcurrent, running)
        this.link = link
        try {
            failure?.let { throw it }
            link.incomingAudio.collect { audio += it }
        } finally {
            running--
            cleanups++
        }
    }
}

/** Everything one engine under test needs. [engineScope] defaults to the test's background scope. */
class Rig(
    val test: TestScope,
    val own: String = MID,
    val name: String = "Me",
    val media: CallMedia = CallMedia.Silent,
    val config: CallEngineConfig = CallEngineConfig(),
    val engineScope: CoroutineScope = test.backgroundScope,
) {
    val listening = FakeListening(own)
    val presence = FakePresence(listening)
    val dialer = FakeDialer()
    val engine = CallEngine(engineScope, presence, dialer, name, media, config) { test.currentTime }
    val channels = mutableListOf<FakeChannel>()
    val state get() = engine.state.value

    private fun newPair(name: String): Pair<FakeChannel, Remote> {
        val (engineSide, far) = FakeChannel.pair(name)
        channels += engineSide
        channels += far
        return engineSide to Remote(far, test.backgroundScope)
    }

    /** Makes the next `dial` connect to a fresh far end, which is returned. */
    fun answerDialsWith(name: String = "out"): Remote {
        val (engineSide, remote) = newPair(name)
        dialer.script = { DialOutcome.Connected(engineSide) }
        return remote
    }

    /** Delivers a new inbound connection to the listener and returns the caller's end. */
    fun inbound(name: String = "in", peerOnion: String? = null): Remote {
        val (engineSide, remote) = newPair(name)
        listening.incoming.trySend(IncomingConnection(engineSide, peerOnion))
        return remote
    }

    fun goOnline() {
        engine.goOnline()
        test.testScheduler.runCurrent()
    }

    fun settle() = test.testScheduler.runCurrent()

    /** Every transport this rig created was closed exactly once. */
    fun assertAllClosedOnce() {
        channels.filter { it.label.endsWith("/engine") }.forEach {
            kotlin.test.assertEquals(1, it.closeCount, "${it.label} close count")
        }
    }
}

/** Several engines on one in-memory network: dialing an online node's onion hands it an inbound stream. */
class FakeNetwork(private val test: TestScope) {
    private val nodes = mutableMapOf<String, Rig>()
    private val streams = mutableListOf<FakeChannel>()

    fun node(onion: String): Rig {
        val rig = Rig(test, own = onion, name = "n-${onion.take(3)}")
        rig.dialer.script = { target ->
            val callee = nodes[target]
            if (callee == null || callee.engine.state.value !is CallState.Online && callee.engine.state.value !is CallState.Dialing &&
                callee.engine.state.value !is CallState.PeerOffline
            ) {
                DialOutcome.PeerOffline
            } else {
                val (near, far) = FakeChannel.pair("$onion->$target")
                streams += near
                streams += far
                callee.listening.incoming.send(IncomingConnection(far))
                DialOutcome.Connected(near)
            }
        }
        nodes[onion] = rig
        return rig
    }

    fun assertEveryStreamClosedOnce() {
        test.testScheduler.runCurrent()
        streams.forEach { kotlin.test.assertEquals(1, it.closeCount, "${it.label} close count") }
    }
}
