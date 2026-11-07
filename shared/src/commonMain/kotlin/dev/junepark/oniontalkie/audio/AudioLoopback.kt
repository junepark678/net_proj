package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.codec.OpusCodec
import kotlin.time.TimeSource

/** Outcome of an [AudioLoopback.run]. Latencies are wall-clock ms from capture-read to playback-write. */
class LoopbackResult(
    val framesProcessed: Int,
    val meanLatencyMs: Double,
    val maxLatencyMs: Double,
    val encodedBytes: Long,
)

/**
 * Test pipeline: input → [FrameClock] → encode → decode → output, one frame at
 * a time with no network and no jitter buffer. It lets device implementations
 * and the call engine be exercised without hardware or Tor.
 *
 * @param decoder separate decoder instance; codecs are stateful, so a real codec
 * must not share one object for both directions.
 */
class AudioLoopback(
    private val input: AudioInput,
    private val output: AudioOutput,
    private val encoder: OpusCodec,
    private val decoder: OpusCodec,
    private val clock: FrameClock = FrameClock(),
) {
    /**
     * Pumps frames until [input] is exhausted or [maxFrames] have been processed,
     * then returns timing statistics. A short final read is zero-padded to a full frame.
     */
    fun run(maxFrames: Int = Int.MAX_VALUE): LoopbackResult {
        val timer = TimeSource.Monotonic
        val buffer = ShortArray(AudioFormat.SAMPLES_PER_FRAME)
        val decoded = ShortArray(AudioFormat.SAMPLES_PER_FRAME)
        var frames = 0
        var sumMs = 0.0
        var maxMs = 0.0
        var bytes = 0L

        input.start()
        output.start()
        try {
            while (frames < maxFrames) {
                buffer.fill(0)
                val mark = timer.markNow()
                val n = input.read(buffer)
                if (n <= 0) break
                val frame = clock.nextFrame(buffer.copyOf())
                val packet = encoder.encode(frame.pcm)
                bytes += packet.size
                val samples = decoder.decode(packet, decoded)
                output.write(decoded.copyOf(samples))
                val ms = mark.elapsedNow().inWholeMicroseconds / 1000.0
                sumMs += ms
                if (ms > maxMs) maxMs = ms
                frames++
            }
        } finally {
            input.close()
            output.close()
        }
        return LoopbackResult(frames, if (frames == 0) 0.0 else sumMs / frames, maxMs, bytes)
    }
}
