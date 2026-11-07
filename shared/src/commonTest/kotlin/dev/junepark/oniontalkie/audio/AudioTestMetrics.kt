package dev.junepark.oniontalkie.audio

import kotlin.math.log10

/**
 * Signal-to-noise ratio over overlapping samples after skipping [delaySamples]
 * leading decoded samples (codec delay). Exact PCM, including silence, yields +Infinity.
 */
fun snrDb(reference: ShortArray, decoded: ShortArray, delaySamples: Int = 0): Double {
    require(delaySamples >= 0) { "Codec delay must be nonnegative" }
    require(delaySamples < decoded.size) { "Codec delay must leave decoded samples" }
    val samples = minOf(reference.size, decoded.size - delaySamples)
    require(samples > 0) { "SNR requires overlapping samples" }
    var signal = 0.0
    var noise = 0.0
    repeat(samples) { i ->
        val sample = reference[i].toDouble()
        val error = sample - decoded[i + delaySamples]
        signal += sample * sample
        noise += error * error
    }
    return if (noise == 0.0) Double.POSITIVE_INFINITY else 10 * log10(signal / noise)
}
