package dev.junepark.oniontalkie.audio

/** Fixed audio format used by every Onion Talkie call. */
object AudioConstants {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val FRAME_DURATION_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_DURATION_MS / 1_000
    const val BITRATE = 24_000
}
