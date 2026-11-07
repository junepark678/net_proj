package dev.junepark.oniontalkie.audio

/** Fixed audio format of the whole app: 48 kHz mono 16-bit PCM in 20 ms frames. */
object AudioFormat {
    const val SAMPLE_RATE_HZ: Int = 48_000
    const val FRAME_MS: Int = 20
    const val SAMPLES_PER_FRAME: Int = SAMPLE_RATE_HZ * FRAME_MS / 1000 // 960
}

/**
 * One 20 ms block of audio as carried by the AUDIO message.
 *
 * [seq] counts frames from 0 and [tsMs] is the capture time in milliseconds
 * (`seq * 20` on the sender's [FrameClock]). Both grow monotonically here; the
 * wire format truncates them to u16 / u32.
 */
class AudioFrame(val seq: Int, val tsMs: Long, val pcm: ShortArray) {
    override fun equals(other: Any?): Boolean =
        other is AudioFrame && seq == other.seq && tsMs == other.tsMs && pcm.contentEquals(other.pcm)

    override fun hashCode(): Int = (seq * 31 + tsMs.hashCode()) * 31 + pcm.contentHashCode()

    override fun toString(): String = "AudioFrame(seq=$seq, tsMs=$tsMs, samples=${pcm.size})"
}

/**
 * The 20 ms sender clock: stamps consecutive PCM blocks with `seq` and `tsMs`
 * and tells a real-time sender how long to wait for the next one.
 *
 * It holds no time source of its own; callers pass `now` explicitly, which keeps
 * it deterministic in tests.
 *
 * @param startMs capture time of frame 0, in the caller's millisecond clock.
 */
class FrameClock(val startMs: Long = 0L, val frameMs: Int = AudioFormat.FRAME_MS) {
    private var nextSeq = 0

    /** Stamps [pcm] as the next frame. */
    fun nextFrame(pcm: ShortArray): AudioFrame {
        val seq = nextSeq++
        return AudioFrame(seq, tsMsOf(seq), pcm)
    }

    /** Capture time of frame [seq]. */
    fun tsMsOf(seq: Int): Long = startMs + seq.toLong() * frameMs

    /** Milliseconds from [nowMs] until the next frame is due (0 if already late). */
    fun msUntilNext(nowMs: Long): Long = maxOf(0L, tsMsOf(nextSeq) - nowMs)
}
