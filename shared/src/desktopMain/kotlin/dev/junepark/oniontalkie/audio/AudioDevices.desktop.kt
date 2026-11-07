package dev.junepark.oniontalkie.audio

actual fun createAudioInput(): AudioInput =
    throw UnsupportedOperationException("desktop microphone input is not implemented yet (#12)")

actual fun createAudioOutput(): AudioOutput =
    throw UnsupportedOperationException("desktop speaker output is not implemented yet (#12)")
