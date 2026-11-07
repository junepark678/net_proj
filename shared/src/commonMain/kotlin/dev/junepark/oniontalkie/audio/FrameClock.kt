package dev.junepark.oniontalkie.audio

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A single-sender 20 ms media clock, starting at sequence/timestamp zero.
 * Create it when capture starts. [nextFrame] advances media time by one frame;
 * [delayUntilNextFrame] lets the sender pace synthetic sources using its scheduler.
 * Late senders get zero delay: this clock neither sleeps nor drops frames.
 */
class FrameClock(timeSource: TimeSource = TimeSource.Monotonic) {
    private val origin = timeSource.markNow()
    private var frameCount = 0L

    /** Stamps [pcm] and takes ownership of its array. Invalid PCM does not advance time. */
    fun nextFrame(pcm: ShortArray): AudioFrame {
        val frame = AudioFrame(
            seq = (frameCount % 65536).toInt(),
            tsMs = frameCount * AudioFormat.FRAME_DURATION_MS,
            pcm = pcm,
        )
        frameCount++
        return frame
    }

    /** Time left before the next media frame is due, measured monotonically. */
    fun delayUntilNextFrame(): Duration =
        (frameCount * AudioFormat.FRAME_DURATION_MS).milliseconds
            .minus(origin.elapsedNow()).coerceAtLeast(Duration.ZERO)
}
