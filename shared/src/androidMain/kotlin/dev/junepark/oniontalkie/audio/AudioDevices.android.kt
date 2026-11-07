package dev.junepark.oniontalkie.audio

actual fun createAudioInput(): AudioInput =
    throw UnsupportedOperationException("android microphone input is not implemented yet (#11)")

actual fun createAudioOutput(): AudioOutput =
    throw UnsupportedOperationException("android speaker output is not implemented yet (#11)")
