package dev.junepark.oniontalkie.audio

actual fun createOpusCodec(): OpusCodec = ConcentusOpusCodec(AudioConstants.OPUS_COMPLEXITY_ANDROID)
