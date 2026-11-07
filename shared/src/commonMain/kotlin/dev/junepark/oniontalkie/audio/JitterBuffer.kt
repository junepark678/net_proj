package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.protocol.Frame
import dev.junepark.oniontalkie.protocol.FrameCodec
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.TimeSource

/** A snapshot suitable for forwarding to the per-call metrics logger. */
data class JitterBufferMetrics(
    /** Encoded audio currently queued, in milliseconds. */
    val bufferMs: Int,
    /** Current playout target, bounded by the configured minimum and maximum. */
    val targetDelayMs: Int,
    val late: Long,
    val plc: Long,
    val drops: Long,
)

/**
 * Sequence-aware encoded-audio queue for one call. Call [put] for each received AUDIO frame and
 * [pull] on the 20 ms playout clock; a null result requests one Opus PLC frame. The caller owns
 * serialization between receive and playout workers. `silenceLike` should be true only when the
 * surrounding decoded audio is quiet enough that skipping one encoded frame is unobtrusive.
 *
 * The initial output waits until the target span is buffered. Thereafter, missing sequence numbers
 * produce PLC, late frames are discarded, and queue growth is bounded by [maxMs]. The buffer uses
 * unsigned 16-bit wraparound arithmetic and keeps only 5 seconds of inter-arrival deviation
 * samples. [metricsListener] is invoked synchronously after each accepted/discarded frame or pull.
 *
 * A non-quiet stream may skip at most one queued frame every two seconds while over target. A
 * quiet stream may skip one queued frame per playout tick until it returns to target. This is the
 * bounded frame-granular catch-up path; no time-stretch DSP is applied.
 */
class JitterBuffer(
    private val minMs: Int = DEFAULT_MIN_MS,
    private val maxMs: Int = DEFAULT_MAX_MS,
    private val clockMs: () -> Long = monotonicClock(),
    private val metricsListener: ((JitterBufferMetrics) -> Unit)? = null,
) {
    init {
        require(minMs >= FRAME_DURATION_MS) { "minMs must be at least one audio frame" }
        require(maxMs >= minMs) { "maxMs must be greater than or equal to minMs" }
    }

    private val maxFrames = ceil(maxMs.toDouble() / FRAME_DURATION_MS).toInt()
    private val packets = mutableMapOf<Long, ByteArray>()
    private val seen = mutableSetOf<Long>()
    private val jitterSamples = mutableListOf<Deviation>()

    private var anchorSequence: Int? = null
    private var firstBufferedSequence: Long? = null
    private var nextSequence: Long? = null
    private var previousArrivalMs: Long? = null
    private var lastObservedMs = clockMs()
    private var lastLateMs: Long? = null
    private var lastDecayMs = 0L
    private var lastNonQuietDropMs: Long? = null

    private var lateCount = 0L
    private var plcCount = 0L
    private var dropCount = 0L
    private var lateFloorMs = minMs
    private var currentTargetMs = minMs

    /** Current metrics; reading this also expires old jitter samples and applies stable decay. */
    val metrics: JitterBufferMetrics
        get() {
            refresh(clockMs())
            return snapshot()
        }

    /** Puts an encoded AUDIO frame. The payload is copied so ownership remains unambiguous. */
    fun put(frame: Frame.Audio) {
        require(frame.payload.size <= FrameCodec.MAX_AUDIO_BYTES) { "AUDIO payload exceeds the wire limit" }
        val now = clockMs()
        refresh(now)

        val sequence = extend(frame.seq.toInt())
        if (sequence in seen) return // A duplicate must not inflate jitter or late counts.

        val expected = nextSequence
        if (expected != null && sequence < expected) {
            recordArrival(now)
            seen += sequence
            noteLate(now)
            pruneSeen()
            publishMetrics()
            return
        }

        val proposedFirst = expected ?: minOf(firstBufferedSequence ?: sequence, sequence)
        val proposedLast = maxOf(packets.keys.maxOrNull() ?: sequence, sequence)
        if (proposedLast - proposedFirst + 1 > maxFrames) {
            // Do not let a delayed sender or malicious sequence jump allocate an unbounded queue.
            dropCount++
            publishMetrics()
            return
        }

        recordArrival(now)
        seen += sequence
        packets[sequence] = frame.payload.copyOf()
        if (nextSequence == null) {
            firstBufferedSequence = minOf(firstBufferedSequence ?: sequence, sequence)
        }
        publishMetrics()
    }

    /**
     * Returns the next encoded packet, or null when the playout tick needs Opus PLC. If [silenceLike]
     * is true, at most one buffered packet is skipped on this tick when the queue is above target.
     */
    fun pull(silenceLike: Boolean = false): ByteArray? {
        val now = clockMs()
        refresh(now)

        if (nextSequence == null) {
            val first = firstBufferedSequence
            val last = packets.keys.maxOrNull()
            val required = framesFor(currentTargetMs)
            if (first == null || last == null || last - first + 1 < required) {
                plcCount++
                publishMetrics()
                return null
            }
            nextSequence = first
        }

        val sequence = nextSequence!!
        val packet = packets.remove(sequence)
        if (packet == null) plcCount++
        nextSequence = sequence + 1
        maybeDropForCatchUp(now, silenceLike)
        pruneSeen()
        publishMetrics()
        return packet
    }

    private fun extend(sequence: Int): Long {
        val reference = nextSequence ?: firstBufferedSequence ?: anchorSequence?.toLong()
        if (reference == null) {
            anchorSequence = sequence
            return sequence.toLong()
        }
        val referenceRaw = (reference and SEQUENCE_MASK.toLong()).toInt()
        val delta = signedSequenceDelta(sequence, referenceRaw)
        return reference + delta
    }

    private fun recordArrival(now: Long) {
        previousArrivalMs?.let { previous ->
            val deviation = abs((now - previous) - FRAME_DURATION_MS).toInt()
            jitterSamples += Deviation(now, deviation)
        }
        previousArrivalMs = now
        pruneSamples(now)
        recomputeTarget(now)
    }

    private fun noteLate(now: Long) {
        lateCount++
        lateFloorMs = (maxOf(lateFloorMs, currentTargetMs) + FRAME_DURATION_MS.toInt()).coerceAtMost(maxMs)
        lastLateMs = now
        lastDecayMs = now
        recomputeTarget(now)
    }

    private fun refresh(now: Long) {
        require(now >= lastObservedMs) { "clockMs must be monotonic" }
        lastObservedMs = now
        pruneSamples(now)
        lastLateMs?.let { lastLate ->
            if (now - lastLate >= DECAY_INTERVAL_MS) {
                val elapsedSinceDecay = now - maxOf(lastLate, lastDecayMs)
                val steps = elapsedSinceDecay / DECAY_INTERVAL_MS
                if (steps > 0) {
                    lateFloorMs = maxOf(minMs, lateFloorMs - (steps * FRAME_DURATION_MS).toInt())
                    lastDecayMs = maxOf(lastLate, lastDecayMs) + steps * DECAY_INTERVAL_MS
                }
            }
        }
        recomputeTarget(now)
    }

    private fun recomputeTarget(now: Long) {
        pruneSamples(now)
        val p95 = if (jitterSamples.isEmpty()) minMs else {
            val ordered = jitterSamples.map { it.deviationMs }.sorted()
            val index = ceil(ordered.size * PERCENTILE).toInt().coerceAtLeast(1) - 1
            ordered[index]
        }
        currentTargetMs = maxOf(minMs, maxOf(lateFloorMs, p95)).coerceAtMost(maxMs)
    }

    private fun pruneSamples(now: Long) {
        val cutoff = now - JITTER_WINDOW_MS
        jitterSamples.removeAll { it.atMs < cutoff }
    }

    private fun maybeDropForCatchUp(now: Long, silenceLike: Boolean) {
        val cursor = nextSequence ?: return
        val targetFrames = framesFor(currentTargetMs)
        val highestQueued = packets.keys.maxOrNull() ?: return
        if (highestQueued - cursor + 1 <= targetFrames) return

        val mayDrop = if (silenceLike) {
            true
        } else {
            val lastDrop = lastNonQuietDropMs
            lastDrop == null || now - lastDrop >= NON_QUIET_DROP_INTERVAL_MS
        }
        if (!mayDrop || packets.remove(cursor) == null) return

        dropCount++
        nextSequence = cursor + 1
        if (!silenceLike) lastNonQuietDropMs = now
    }

    private fun pruneSeen() {
        val cursor = nextSequence ?: return
        val cutoff = cursor - SEEN_HISTORY_FRAMES
        seen.removeAll { it < cutoff }
    }

    private fun framesFor(delayMs: Int): Long =
        ceil(delayMs.toDouble() / FRAME_DURATION_MS).toLong()

    private fun snapshot() = JitterBufferMetrics(
        bufferMs = (packets.size * FRAME_DURATION_MS).toInt(),
        targetDelayMs = currentTargetMs,
        late = lateCount,
        plc = plcCount,
        drops = dropCount,
    )

    private fun publishMetrics() {
        metricsListener?.invoke(snapshot())
    }

    private data class Deviation(val atMs: Long, val deviationMs: Int)

    companion object {
        const val DEFAULT_MIN_MS = 60
        const val DEFAULT_MAX_MS = 1_500
        private const val FRAME_DURATION_MS = AudioFormat.FRAME_DURATION_MS
        private const val JITTER_WINDOW_MS = 5_000L
        private const val DECAY_INTERVAL_MS = 1_000L
        private const val NON_QUIET_DROP_INTERVAL_MS = 2_000L
        private const val SEEN_HISTORY_FRAMES = 32_768L
        private const val SEQUENCE_MASK = 0xffff
        private const val PERCENTILE = 0.95

        private fun signedSequenceDelta(sequence: Int, reference: Int): Int =
            ((sequence - reference + 0x8000) and SEQUENCE_MASK) - 0x8000
    }
}

private fun monotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
