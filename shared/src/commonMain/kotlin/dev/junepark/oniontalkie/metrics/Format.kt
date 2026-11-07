package dev.junepark.oniontalkie.metrics

import kotlin.math.abs
import kotlin.math.round

/**
 * Formats [value] with exactly [decimals] fractional digits (half away from zero), without
 * locale or exponent surprises. Returns `null` for NaN/infinity.
 */
internal fun formatFixed(value: Double, decimals: Int = 2): String? {
    if (!value.isFinite()) return null
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = round(abs(value) * scale).toLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    val fraction = (scaled % scale).toString().padStart(decimals, '0')
    return if (decimals == 0) "$sign${scaled / scale}" else "$sign${scaled / scale}.$fraction"
}
