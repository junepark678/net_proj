package dev.junepark.oniontalkie.audio

/** Fixed audio parameters shared by capture, codec, transport and playback. */
object AudioConstants {
    /** Sample rate in Hz. Opus fullband; capture and playback are resampled to this if needed. */
    const val SAMPLE_RATE: Int = 48_000

    /** Mono only; stereo is out of scope. */
    const val CHANNELS: Int = 1

    /** Frame duration in milliseconds. */
    const val FRAME_MS: Int = 20

    /** PCM samples per frame per channel (960 at 48 kHz / 20 ms). */
    const val FRAME_SAMPLES: Int = SAMPLE_RATE * FRAME_MS / 1000

    /** Opus target bitrate in bits per second. */
    const val OPUS_BITRATE_BPS: Int = 24_000

    /** Encoder complexity on Android (low, to spare battery and CPU). */
    const val OPUS_COMPLEXITY_ANDROID: Int = 5

    /** Encoder complexity on desktop. */
    const val OPUS_COMPLEXITY_DESKTOP: Int = 10

    /** Expected packet loss in percent that the encoder tunes its inband FEC for. */
    const val OPUS_EXPECTED_LOSS_PERCENT: Int = 10

    /** Upper bound on one encoded Opus packet in bytes (RFC 6716 limit). */
    const val OPUS_MAX_PACKET_BYTES: Int = 1275
}
