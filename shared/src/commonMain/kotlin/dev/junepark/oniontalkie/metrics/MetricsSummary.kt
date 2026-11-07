package dev.junepark.oniontalkie.metrics

import kotlin.math.ceil

/**
 * Nearest-rank percentile of [values] for integer [p] in 1..100, or `null` when [values] is empty.
 * (rank = ceil(p/100 * n); the result is always one of the input values.)
 */
internal fun percentile(values: List<Double>, p: Int): Double? {
    require(p in 1..100) { "p must be in 1..100" }
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val rank = ceil(p * sorted.size / 100.0).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

/** Renders the `summary.json` written on hangup: sample count, duration and p50/p95 per metric. */
internal fun summaryJson(rows: List<CallStats>): String {
    fun metric(name: String, values: List<Double>): String {
        fun num(v: Double?) = v?.let { formatFixed(it) } ?: "null"
        return "    \"$name\": {\"p50\": ${num(percentile(values, 50))}, \"p95\": ${num(percentile(values, 95))}}"
    }
    val metrics = listOf(
        metric("rtt_ms", rows.mapNotNull { it.rttMs }),
        metric("jitter_ms", rows.map { it.jitterMs }),
        metric("loss_pct", rows.map { it.lossPct }),
        metric("buffer_ms", rows.map { it.bufferMs }),
        metric("tx_kbps", rows.map { it.txKbps }),
        metric("rx_kbps", rows.map { it.rxKbps }),
    )
    return buildString {
        append("{\n")
        append("  \"samples\": ${rows.size},\n")
        append("  \"duration_s\": ${rows.lastOrNull()?.tSeconds ?: 0},\n")
        append("  \"late_total\": ${rows.sumOf { it.late }},\n")
        append("  \"plc_total\": ${rows.sumOf { it.plc }},\n")
        append("  \"metrics\": {\n")
        append(metrics.joinToString(",\n"))
        append("\n  }\n}\n")
    }
}
