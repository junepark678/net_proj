package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.shared.audio.AudioConstants

actual fun createOpusCodec(): OpusCodec = ConcentusOpusCodec(complexity = AudioConstants.ANDROID_OPUS_COMPLEXITY)
