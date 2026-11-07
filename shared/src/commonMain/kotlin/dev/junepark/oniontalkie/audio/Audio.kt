package dev.junepark.oniontalkie.audio

/** The call format: signed 16-bit, 48 kHz mono PCM in 20 ms frames. */
object AudioFormat {
    const val SAMPLE_RATE_HZ = 48_000
    const val FRAME_DURATION_MS = 20L
    const val SAMPLES_PER_FRAME = 960
}

/**
 * One PCM frame. [seq] wraps at 65536; [tsMs] is call-relative media time in
 * milliseconds, before wire-format truncation. [pcm] has exactly 960 samples.
 * The caller transfers ownership of the array and must not subsequently mutate it.
 */
data class AudioFrame(val seq: Int, val tsMs: Long, val pcm: ShortArray) {
    init {
        require(seq in 0..0xffff) { "Sequence must fit an unsigned 16-bit integer" }
        require(tsMs >= 0) { "Timestamp must be nonnegative" }
        require(pcm.size == AudioFormat.SAMPLES_PER_FRAME) { "Expected 960 PCM samples" }
    }
}

/**
 * Blocking capture of the call format. Call [start] before [read], then [stop]
 * in a finally block. Instances are confined to one audio worker; errors propagate
 * to the caller. Implementations must assemble partial device reads into frames.
 */
interface AudioInput {
    /** Acquires the capture device; starting an already started input is an error. */
    fun start()

    /** Returns a fresh, caller-owned 960-sample frame, or null at end of input. */
    fun read(): ShortArray?

    /** Releases capture resources. Safe to repeat, including after a failed start. */
    fun stop()
}

/** Blocking playback of the call format, with the same lifecycle as [AudioInput]. */
interface AudioOutput {
    /** Acquires the playback device; starting an already started output is an error. */
    fun start()

    /**
     * Accepts exactly 960 samples, handling partial device writes internally.
     * Must consume or copy [pcm] before returning; the caller retains ownership.
     * Return means accepted by the device, not necessarily audibly played.
     */
    fun write(pcm: ShortArray)

    /** Releases playback resources. Safe to repeat, including after a failed start. */
    fun stop()
}

/** Creates platform capture. Currently unsupported until device implementation #11/#12. */
expect fun createAudioInput(): AudioInput

/** Creates platform playback. Currently unsupported until device implementation #11/#12. */
expect fun createAudioOutput(): AudioOutput
