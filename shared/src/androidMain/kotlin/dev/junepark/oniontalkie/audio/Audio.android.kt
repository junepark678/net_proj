package dev.junepark.oniontalkie.audio

actual fun createAudioInput(): AudioInput =
    throw UnsupportedOperationException("Android audio capture is pending issue #11")

actual fun createAudioOutput(): AudioOutput =
    throw UnsupportedOperationException("Android audio playback is pending issue #11")
