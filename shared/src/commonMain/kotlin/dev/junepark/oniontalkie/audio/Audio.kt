package dev.junepark.oniontalkie.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

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

    override fun equals(other: Any?): Boolean =
        this === other || (other is AudioFrame &&
            seq == other.seq && tsMs == other.tsMs && pcm.contentEquals(other.pcm))

    override fun hashCode(): Int = 31 * (31 * seq + tsMs.hashCode()) + pcm.contentHashCode()
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

/**
 * Starts this blocking input and emits exact PCM frames on an IO dispatcher until EOF or
 * cancellation, then always stops the device. Collection owns the input lifecycle; do not call
 * [start] or [stop] separately while collecting this flow.
 */
fun AudioInput.frames(): Flow<ShortArray> = flow {
    start()
    try {
        while (true) emit(read() ?: break)
    } finally {
        stop()
    }
}.flowOn(Dispatchers.IO)

/** Blocking playback of the call format, with the same lifecycle as [AudioInput]. */
interface AudioOutput {
    /** Acquires the playback device; starting an already started output is an error. */
    fun start()

    /**
     * Accepts exactly 960 samples, handling partial device writes internally.
     * Must consume or copy [pcm] before returning; the caller retains ownership.
     * Return means accepted for playback, not necessarily audibly played.
     */
    fun write(pcm: ShortArray)

    /** Hardware underruns observed by the playback device, when the platform exposes them. */
    val underrunCount: Long get() = 0L

    /** Releases playback resources. Safe to repeat, including after a failed start. */
    fun stop()
}

/** Audio routing scope owned by a call session; desktop implementations may leave it as no-op. */
interface AudioRouteController {
    /** Whether this platform exposes a user-selectable speaker route. */
    val supportsSpeakerphone: Boolean get() = false

    fun beginCommunication()
    fun endCommunication()
    fun setSpeakerphone(enabled: Boolean): Boolean
    fun isSpeakerphoneSelected(): Boolean
}

/** Default route implementation for platforms that have no voice-call audio mode. */
object NoOpAudioRouteController : AudioRouteController {
    override fun beginCommunication() = Unit
    override fun endCommunication() = Unit
    override fun setSpeakerphone(enabled: Boolean) = false
    override fun isSpeakerphoneSelected() = false
}

/** Creates platform microphone capture. */
expect fun createAudioInput(): AudioInput

/** Creates platform speaker/headphone playback. */
expect fun createAudioOutput(): AudioOutput
