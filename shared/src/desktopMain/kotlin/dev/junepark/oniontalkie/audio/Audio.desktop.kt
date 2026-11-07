package dev.junepark.oniontalkie.audio

actual fun createAudioInput(): AudioInput =
    throw UnsupportedOperationException("Desktop audio capture is pending issue #12")

actual fun createAudioOutput(): AudioOutput =
    throw UnsupportedOperationException("Desktop audio playback is pending issue #12")
