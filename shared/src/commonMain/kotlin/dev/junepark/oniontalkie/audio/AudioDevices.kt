package dev.junepark.oniontalkie.audio

/**
 * Microphone side of the audio contract. All methods block; callers run them on
 * a dedicated audio thread. Implementations: Android `AudioRecord` (#11),
 * desktop `javax.sound.sampled` (#12), and test fakes.
 */
interface AudioInput {
    /** Opens the device and starts capturing. */
    fun start()

    /**
     * Blocks until [buffer] is filled with captured 48 kHz mono PCM (normally one
     * [AudioFormat.SAMPLES_PER_FRAME] frame). Returns the number of samples
     * written, which may be less than `buffer.size` only at the end of the
     * stream, or -1 when no more audio will arrive (stopped or source exhausted).
     */
    fun read(buffer: ShortArray): Int

    /** Stops capturing and releases the device. Safe to call more than once. */
    fun close()
}

/** Speaker side of the audio contract; same threading rules as [AudioInput]. */
interface AudioOutput {
    /** Opens the device and starts playback. */
    fun start()

    /** Plays [pcm] (48 kHz mono), blocking while the device buffer is full. */
    fun write(pcm: ShortArray)

    /** Stops playback and releases the device. Safe to call more than once. */
    fun close()
}

/** Creates the platform microphone input. Implemented by #11 (Android) and #12 (desktop). */
expect fun createAudioInput(): AudioInput

/** Creates the platform speaker output. Implemented by #11 (Android) and #12 (desktop). */
expect fun createAudioOutput(): AudioOutput
