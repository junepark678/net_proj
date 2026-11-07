package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.codec.OpusCodec
import kotlin.time.Duration
import kotlin.time.TimeSource

/** A decoded frame and capture-through-playback-acceptance time (excludes device queues). */
data class LoopbackResult(val frame: AudioFrame, val latency: Duration)

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
    fun processNextFrame(): LoopbackResult? {
        val started = timeSource.markNow()
        val pcm = input.read() ?: return null
        val captured = clock.nextFrame(pcm)
        val packet = codec.encode(captured.pcm)
        val decoded = ShortArray(AudioFormat.SAMPLES_PER_FRAME)
        check(codec.decode(packet, decoded) == decoded.size) { "Codec must decode 960 samples" }
        output.write(decoded)
        return LoopbackResult(captured.copy(pcm = decoded), started.elapsedNow())
    }
}
