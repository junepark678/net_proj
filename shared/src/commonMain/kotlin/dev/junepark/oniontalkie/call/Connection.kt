package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.protocol.ByeReason
import dev.junepark.oniontalkie.protocol.Frame
import dev.junepark.oniontalkie.protocol.FrameChannel
import dev.junepark.oniontalkie.protocol.FrameReader
import dev.junepark.oniontalkie.protocol.FrameWriter
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Thrown by [Connection.send] after the stream was closed or a previous write failed. */
internal class ConnectionClosedException : Exception("Connection is closed")

/**
 * One transport stream owned by the engine. Wraps the raw [FrameChannel] to give the engine three
 * guarantees regardless of who closes first (engine, [FrameReader] on a malformed frame, timeout,
 * cancellation):
 *
 * - the raw `close()` runs **at most once** and is always attempted by [shutdown];
 * - frame writes are serialized, so PING/PONG/AUDIO/BYE never interleave on the wire;
 * - all coroutines working on the stream live in [scope], cancelled as one on teardown.
 *
 * It is itself a [FrameChannel] so that [FrameReader]'s own close-on-error goes through the same guard.
 */
internal class Connection(
    val id: Long,
    private val raw: FrameChannel,
    engineContext: CoroutineContext,
    val outbound: Boolean,
    val peerOnion: String?,
    private val byeTimeoutMs: Long,
    private val closeTimeoutMs: Long,
) : FrameChannel {
    // Worker coroutines (keepalive, media, timers, writes of HELLO/ACCEPT/PONG): stopped the moment the
    // engine decides to end the stream, and with the engine's own scope.
    private val job = SupervisorJob(engineContext[Job])
    val scope = CoroutineScope(engineContext.minusKey(Job) + job)

    // The reader is cancelled only by [shutdown], after the BYE: FrameReader closes the channel when its
    // read is cancelled, which would otherwise swallow the BYE. It is detached from the engine scope so
    // that cancelling that scope still lets the teardown write its BYE first.
    private val readerJob = Job()
    val readerScope = CoroutineScope(engineContext.minusKey(Job) + readerJob)

    private val reader = FrameReader(this)
    private val writer = FrameWriter(this)
    private val sendLock = Mutex()
    private val closeLock = Mutex()
    private val shutdownLatch = Mutex()

    @Volatile private var closed = false
    // A cancelled or failed write may have left half a frame on the wire; never append a BYE to it.
    @Volatile private var broken = false

    private var timer: Job? = null
    private var timerSeq = 0L

    /** Next frame, or null at a clean end of stream. Callers must not read concurrently. */
    suspend fun receive(): Frame? = reader.read()

    suspend fun send(frame: Frame) {
        sendLock.withLock { sendLocked(frame) }
    }

    private suspend fun sendLocked(frame: Frame) {
        if (closed || broken) throw ConnectionClosedException()
        try {
            writer.write(frame)
        } catch (t: Throwable) {
            broken = true
            throw t
        }
    }

    /** Arms the single per-connection timer, replacing any earlier one. [onFire] gets the timer's sequence number. */
    fun startTimer(delayMs: Long, onFire: (seq: Long) -> Unit): Long {
        timer?.cancel()
        val seq = ++timerSeq
        timer = scope.launch {
            delay(delayMs)
            onFire(seq)
        }
        return seq
    }

    fun timerIsCurrent(seq: Long): Boolean = seq == timerSeq

    /** Synchronously stops every coroutine working for this stream. Idempotent. */
    fun cancelWork() {
        job.cancel()
    }

    /**
     * Ends the stream exactly once: cancels its coroutines, makes a bounded best-effort attempt to
     * write [bye], then closes the transport. Later calls return immediately.
     */
    suspend fun shutdown(bye: ByeReason?) {
        if (!shutdownLatch.tryLock()) return // never unlocked: the first caller wins
        withContext(NonCancellable) {
            job.cancel()
            if (bye != null) {
                withTimeoutOrNull(byeTimeoutMs) {
                    try {
                        sendLock.withLock { sendLocked(Frame.Bye(bye)) }
                    } catch (_: Exception) {
                        // Peer already gone; closing is all that is left.
                    }
                }
            }
            readerJob.cancel()
            withTimeoutOrNull(closeTimeoutMs) { close() }
        }
    }

    // FrameChannel: reached by FrameReader/FrameWriter only.
    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int =
        raw.read(destination, offset, length)

    override suspend fun write(bytes: ByteArray) = raw.write(bytes)

    override suspend fun flush() = raw.flush()

    override suspend fun close() {
        withContext(NonCancellable) {
            closeLock.withLock {
                if (closed) return@withLock
                closed = true
                try {
                    raw.close()
                } catch (_: Exception) {
                    // Nothing more can be done for a transport that fails to close.
                }
            }
        }
    }
}
