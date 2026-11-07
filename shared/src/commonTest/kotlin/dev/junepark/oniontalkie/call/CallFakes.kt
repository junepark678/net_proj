package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.identity.ONION
import dev.junepark.oniontalkie.identity.OTHER_ONION
import dev.junepark.oniontalkie.protocol.Frame
import dev.junepark.oniontalkie.protocol.FrameChannel
import dev.junepark.oniontalkie.protocol.FrameCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Byte-level, partial-read fake: tests exercise the merged reader and writer, not a frame mock. */
internal class FakeChannel : FrameChannel {
    private val input = Channel<ByteArray>(Channel.UNLIMITED)
    private var chunk = byteArrayOf()
    private var position = 0
    val sent = mutableListOf<Frame>()
    var closes = 0
    var flushes = 0
    var peer: FakeChannel? = null
    var writeGate: CompletableDeferred<Unit>? = null
    var closeGate: CompletableDeferred<Unit>? = null
    var failClose = false
    var failWrite = false
    var concurrentWrites = 0
    private var writing = false

    fun feed(frame: Frame) = feedBytes(FrameCodec.encode(frame))
    fun feedBytes(bytes: ByteArray) { input.trySend(bytes).getOrThrow() }
    fun eof() { input.close() }

    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
        while (position == chunk.size) {
            chunk = input.receiveCatching().getOrNull() ?: return -1
            position = 0
        }
        val count = minOf(length, chunk.size - position, 2)
        chunk.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun write(bytes: ByteArray) {
        if (writing) {
            concurrentWrites++
            error("Concurrent transport writes")
        }
        writing = true
        try {
            writeGate?.await()
            check(closes == 0 && !failWrite) { "Transport write failed" }
            sent += FrameCodec.decode(bytes)
            peer?.feedBytes(bytes.copyOf())
        } finally { writing = false }
    }

    override suspend fun flush() { flushes++ }

    override suspend fun close() {
        abandon()
        closeGate?.await()
        check(!failClose) { "Transport close failed" }
    }

    fun abandon() {
        closes++
        input.close()
        peer?.input?.close()
    }

    companion object {
        fun pair(): Pair<FakeChannel, FakeChannel> {
            val a = FakeChannel()
            val b = FakeChannel()
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}

internal class FakeListener : CallListener {
    private val arrivals = Channel<IncomingCall>(Channel.UNLIMITED, onUndeliveredElement = {
        (it.channel as FakeChannel).abandon()
    })
    var closes = 0
    fun arrive(peer: String, channel: FakeChannel) {
        arrivals.trySend(IncomingCall(peer, channel)).getOrThrow()
    }
    override suspend fun accept(): IncomingCall = arrivals.receive()
    override suspend fun close() {
        closes++
        arrivals.close()
        while (true) (arrivals.tryReceive().getOrNull() ?: break).channel.close()
    }
}

internal class FakePresence : CallPresence {
    val listeners = mutableListOf<FakeListener>()
    val listener get() = listeners.last()
    var failure: Exception? = null
    override suspend fun open(): CallListener {
        failure?.let { throw it }
        return FakeListener().also { listeners += it }
    }
}

internal class FakeDialer : CallDialer {
    val requests = mutableListOf<String>()
    var outgoing = FakeChannel()
    var connect: suspend (String) -> FrameChannel = { outgoing }
    override suspend fun dial(peerOnion: String): FrameChannel {
        requests += peerOnion
        return connect(peerOnion)
    }
}

internal class FakeSession : CallSession {
    val captured = Channel<Frame.Audio>(Channel.UNLIMITED)
    val received = mutableListOf<Frame.Audio>()
    var running = false
    var closes = 0
    var failClose = false
    override suspend fun run(sendAudio: suspend (Frame.Audio) -> Unit) {
        running = true
        try { for (frame in captured) sendAudio(frame) } finally { running = false }
    }
    override suspend fun receive(frame: Frame.Audio) { received += frame }
    override suspend fun close() {
        check(!running) { "Media still running during close" }
        closes++
        check(!failClose) { "Media close failed" }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class Fixture(
    scope: CoroutineScope,
    scheduler: TestCoroutineScheduler,
    local: String = ONION,
    val remote: String = OTHER_ONION,
) {
    val presence = FakePresence()
    val dialer = FakeDialer()
    val media = mutableListOf<FakeSession>()
    val engine = CallEngine(local, "June", presence, dialer, {
        FakeSession().also { media += it }
    }, scope) { scheduler.currentTime }

    fun incoming(peer: String = remote): FakeChannel = FakeChannel().also {
        it.feed(Frame.Hello(1u, "Peer", 24_000u))
        presence.listener.arrive(peer, it)
    }
}
