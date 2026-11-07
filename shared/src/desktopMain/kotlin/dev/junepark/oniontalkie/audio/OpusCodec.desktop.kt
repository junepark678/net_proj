package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.shared.audio.AudioConstants

actual fun createOpusCodec(): OpusCodec = ConcentusOpusCodec(complexity = AudioConstants.DESKTOP_OPUS_COMPLEXITY)
