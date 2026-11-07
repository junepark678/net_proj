package dev.junepark.oniontalkie.shared.audio

/** Shared audio format and Opus settings used by every Onion Talkie call. */
object AudioConstants {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val FRAME_DURATION_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_DURATION_MS / 1_000
    const val BITRATE = 24_000
    const val ANDROID_OPUS_COMPLEXITY = 5
    const val DESKTOP_OPUS_COMPLEXITY = 10
    const val EXPECTED_PACKET_LOSS_PERCENT = 10
    const val OPUS_MAX_PACKET_BYTES = 1_275
}
