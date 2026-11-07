package dev.junepark.oniontalkie.audio

import kotlin.math.log10

/**
 * Signal-to-noise ratio in dB of [decoded] against [reference]:
 * `10·log10(Σref² / Σ(ref − decoded)²)`.
 *
 * @param delaySamples codec delay: `decoded[i + delaySamples]` is compared with
 * `reference[i]`. Only the overlapping part is compared.
 * @return [Double.POSITIVE_INFINITY] for an exact match.
 */
fun snrDb(reference: ShortArray, decoded: ShortArray, delaySamples: Int = 0): Double {
    require(delaySamples >= 0) { "delaySamples must be >= 0" }
    val n = minOf(reference.size, decoded.size - delaySamples)
    require(n > 0) { "no overlapping samples to compare" }
    var signal = 0.0
    var noise = 0.0
    for (i in 0 until n) {
        val r = reference[i].toDouble()
        val e = r - decoded[i + delaySamples]
        signal += r * r
        noise += e * e
    }
    if (noise == 0.0) return Double.POSITIVE_INFINITY
    return 10.0 * log10(signal / noise)
}
