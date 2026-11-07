package dev.junepark.oniontalkie.simulator

import dev.junepark.oniontalkie.protocol.FrameChannel
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlin.math.ceil

/** One-way network conditions. Delay is one-way; jitter is sampled uniformly around it. */
data class ImpairmentConfig(
    val delayMs: Long = 2,
    val jitterMs: Long = 1,
    val lossPct: Double = 0.0,
    val burstMs: Long = 0,
    /** Zero means unlimited. Otherwise each direction is shaped independently. */
    val kbps: Double = 0.0,
    val seed: Long = 1,
) {
    init {
        require(delayMs in 0..60_000) { "delayMs must be between 0 and 60000" }
        require(jitterMs in 0..60_000) { "jitterMs must be between 0 and 60000" }
        require(lossPct.isFinite() && lossPct in 0.0..100.0) { "lossPct must be between 0 and 100" }
        require(burstMs in 0..60_000) { "burstMs must be between 0 and 60000" }
        require(kbps.isFinite() && kbps >= 0.0) { "kbps must be finite and nonnegative" }
    }
}

/**
 * A duplex in-memory [FrameChannel] pair with per-direction latency, jitter, loss and bandwidth.
 * Only complete AUDIO frames are eligible for loss; control frames remain reliable, matching
 * TCP's byte reliability while allowing the media/jitter-buffer path to experience network loss.
 * A write is queued immediately. Delivery is delayed and rate-shaped asynchronously, preserving
 * stream order even when successive jitter samples differ.
 */
class ImpairedTransport private constructor(
    private val inbound: Direction,
    private val outbound: Direction,
    val impairment: ImpairmentConfig,
) : FrameChannel {
    private val closeMutex = Mutex()
    private var closed = false
    private var pendingPacket: ByteArray? = null
    private var pendingOffset = 0

    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
        require(length > 0) { "Read length must be positive" }
        while (pendingPacket == null || pendingOffset == pendingPacket!!.size) {
            val next = inbound.reads.receiveCatching().getOrNull() ?: return -1
            pendingPacket = next
            pendingOffset = 0
        }
        val packet = checkNotNull(pendingPacket)
        val count = minOf(length, packet.size - pendingOffset)
        packet.copyInto(destination, offset, pendingOffset, pendingOffset + count)
        pendingOffset += count
        return count
    }

    override suspend fun write(bytes: ByteArray) {
        check(!closed) { "Transport is closed" }
        outbound.write(bytes)
    }

    override suspend fun flush() {
        check(!closed) { "Transport is closed" }
    }

    override suspend fun close() = withContext(NonCancellable) {
        closeMutex.withLock {
            if (closed) return@withLock
            closed = true
            coroutineScope {
                val sent = async { outbound.finish() }
                val received = async { inbound.finish() }
                sent.await()
                received.await()
            }
        }
    }

    companion object {
        /** Creates two connected endpoints; the second direction gets a distinct repeatable seed. */
        fun pair(scope: CoroutineScope, impairment: ImpairmentConfig): Pair<ImpairedTransport, ImpairedTransport> {
            val leftToRight = Direction(scope, impairment, impairment.seed)
            val rightToLeft = Direction(scope, impairment, impairment.seed xor 0x5deece66dL)
            return ImpairedTransport(rightToLeft, leftToRight, impairment) to
                ImpairedTransport(leftToRight, rightToLeft, impairment)
        }

        fun pair(
            scope: CoroutineScope,
            delayMs: Long,
            jitterMs: Long,
            lossPct: Double,
            burstMs: Long,
            kbps: Double,
            seed: Long = 1,
        ): Pair<ImpairedTransport, ImpairedTransport> = pair(
            scope,
            ImpairmentConfig(delayMs, jitterMs, lossPct, burstMs, kbps, seed),
        )
    }

    private class Direction(
        scope: CoroutineScope,
        private val config: ImpairmentConfig,
        seed: Long,
    ) {
        val writes = Channel<ByteArray>(Channel.UNLIMITED)
        val reads = Channel<ByteArray>(Channel.UNLIMITED)
        private val finishStarted = AtomicBoolean(false)
        private val random = Random(seed)
        private var burstFramesRemaining = 0
        private val burstFrames = ((config.burstMs + FRAME_DURATION_MS - 1) / FRAME_DURATION_MS)
            .toInt().coerceAtLeast(1)
        private val burstStartProbability = run {
            val p = config.lossPct / 100.0
            if (p == 0.0) 0.0 else if (p == 1.0) 1.0
            else (p / ((1.0 - p) * burstFrames)).coerceAtMost(1.0)
        }
        private val finishMutex = Mutex()
        private val worker = scope.launch(Dispatchers.Default) {
            try {
                coroutineScope {
                    val deliveries = mutableListOf<kotlinx.coroutines.Job>()
                    var nextTransmitNanos = System.nanoTime()
                    var lastDeliveryNanos = nextTransmitNanos
                    for (packet in writes) {
                        deliveries.removeAll { it.isCompleted }
                        val now = System.nanoTime()
                        val transmitStart = maxOf(now, nextTransmitNanos)
                        val transmitNanos = if (config.kbps == 0.0) 0L else
                            ceil(packet.size * 8.0 / config.kbps * NANOS_PER_MILLISECOND).toLong()
                        val transmitEnd = transmitStart + transmitNanos
                        nextTransmitNanos = transmitEnd

                        val isAudio = packet.firstOrNull()?.toInt() == AUDIO_FRAME_TYPE
                        if (isAudio && shouldDropAudio()) continue

                        val jitter = if (config.jitterMs == 0L) 0L else
                            random.nextLong(-config.jitterMs, config.jitterMs + 1)
                        val oneWayDelayNanos = (config.delayMs + jitter).coerceAtLeast(0) * NANOS_PER_MILLISECOND
                        val deliverAt = maxOf(transmitEnd + oneWayDelayNanos, lastDeliveryNanos)
                        lastDeliveryNanos = deliverAt
                        deliveries += launch {
                            val waitNanos = (deliverAt - System.nanoTime()).coerceAtLeast(0)
                            delay((waitNanos / NANOS_PER_MILLISECOND).coerceAtLeast(0))
                            reads.send(packet)
                        }
                    }
                    deliveries.joinAll()
                }
            } finally {
                reads.close()
            }
        }

        suspend fun write(bytes: ByteArray) {
            check(!finishStarted.get()) { "Transport direction is closed" }
            writes.send(bytes.copyOf())
        }

        suspend fun finish() = withContext(NonCancellable) {
            finishMutex.withLock {
                if (finishStarted.compareAndSet(false, true)) writes.close()
                worker.join()
            }
        }

        private fun shouldDropAudio(): Boolean {
            if (burstFramesRemaining > 0) {
                burstFramesRemaining -= 1
                return true
            }
            if (random.nextDouble() < burstStartProbability) {
                burstFramesRemaining = burstFrames - 1
                return true
            }
            return false
        }
    }
}

private const val AUDIO_FRAME_TYPE = 3
private const val FRAME_DURATION_MS = 20L
private const val NANOS_PER_MILLISECOND = 1_000_000L
