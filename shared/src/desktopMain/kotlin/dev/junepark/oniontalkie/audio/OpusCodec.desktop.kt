package dev.junepark.oniontalkie.audio

actual fun createOpusCodec(): OpusCodec = ConcentusOpusCodec(complexity = 10)
