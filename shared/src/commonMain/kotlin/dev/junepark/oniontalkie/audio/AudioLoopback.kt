package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.codec.OpusCodec
import kotlin.math.log10
import kotlin.time.Duration
import kotlin.time.TimeSource

/** A decoded frame and capture-through-playback-acceptance time (excludes device queues). */
data class LoopbackFrameResult(val frame: AudioFrame, val latency: Duration)

/** Metrics for one bounded run. SNR is NaN for no frames and infinite for exact PCM. */
data class LoopbackResult(
    val framesProcessed: Int,
    val framesDropped: Int,
    val snrDb: Double,
    val maxLatency: Duration,
)

/**
 * Hardware-independent mic → encoder → decoder → speaker harness, one frame at
 * a time with no queue. The caller starts/stops the I/O and owns codec resources.
 * Calls are blocking and confined to one worker; I/O and codec errors propagate.
 * This harness does not schedule frames; a sender may use [FrameClock] for pacing.
 */
class AudioLoopback(
    private val input: AudioInput,
    private val output: AudioOutput,
    private val codec: OpusCodec,
    private val clock: FrameClock = FrameClock(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** Runs one complete frame, or returns null on EOF without encoding or playback. */
    fun processNextFrame(): LoopbackFrameResult? = processFrame()

    /**
     * Processes up to [maxFrames], stopping at EOF. I/O lifecycle stays with the caller.
     * SNR compares aligned PCM (no codec-delay compensation). This queue-free pipeline
     * never drops frames: failures propagate instead of being counted as drops.
     */
    fun run(maxFrames: Int): LoopbackResult {
        require(maxFrames >= 0) { "Frame limit must be nonnegative" }
        var framesProcessed = 0
        var maxLatency = Duration.ZERO
        var signal = 0.0
        var noise = 0.0
        while (framesProcessed < maxFrames) {
            val result = processFrame { reference, decoded ->
                reference.indices.forEach { i ->
                    val sample = reference[i].toDouble()
                    val error = sample - decoded[i]
                    signal += sample * sample
                    noise += error * error
                }
            } ?: break
            framesProcessed++
            maxLatency = maxOf(maxLatency, result.latency)
        }
        val snrDb = when {
            framesProcessed == 0 -> Double.NaN
            noise == 0.0 -> Double.POSITIVE_INFINITY
            else -> 10 * log10(signal / noise)
        }
        return LoopbackResult(framesProcessed, 0, snrDb, maxLatency)
    }

    private fun processFrame(onDecoded: ((ShortArray, ShortArray) -> Unit)? = null): LoopbackFrameResult? {
        val started = timeSource.markNow()
        val pcm = input.read() ?: return null
        val captured = clock.nextFrame(pcm)
        val packet = codec.encode(captured.pcm)
        val decoded = ShortArray(AudioFormat.SAMPLES_PER_FRAME)
        check(codec.decode(packet, decoded) == decoded.size) { "Codec must decode 960 samples" }
        output.write(decoded)
        val latency = started.elapsedNow()
        onDecoded?.invoke(captured.pcm, decoded)
        return LoopbackFrameResult(captured.copy(pcm = decoded), latency)
    }
}
