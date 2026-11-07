package dev.junepark.oniontalkie.metrics

import kotlin.math.abs
import kotlin.math.round

/** Two fractional digits, independent of locale and without scientific notation. */
internal fun formatFixed(value: Double): String? {
    if (!value.isFinite()) return null
    val magnitude = abs(value)
    // At this magnitude Double has no fractional precision. Expand its exponent
    // directly instead of overflowing the scaled Long used for smaller values.
    if (magnitude >= 1e16) {
        val parts = magnitude.toString().split('E', 'e')
        val digits = parts[0].replace(".", "")
        val fractionalDigits = parts[0].substringAfter('.', "").length
        val exponent = parts.getOrNull(1)?.toInt() ?: 0
        val integer = if (exponent >= fractionalDigits) {
            digits + "0".repeat(exponent - fractionalDigits)
        } else {
            digits.dropLast(fractionalDigits - exponent)
        }
        return "${if (value < 0) "-" else ""}$integer.00"
    }
    val scaled = round(magnitude * 100).toLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return "$sign${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
}
